package com.qshlab.folio.engine

import android.content.Context
import android.content.Intent
import com.qshlab.folio.ai.Claude
import com.qshlab.folio.ai.ClaudeError
import com.qshlab.folio.ai.Fixes
import com.qshlab.folio.ai.Search
import com.qshlab.folio.ai.Summaries
import com.qshlab.folio.audio.AacWriter
import com.qshlab.folio.audio.Decoder
import com.qshlab.folio.audio.PcmWriter
import com.qshlab.folio.audio.SR
import com.qshlab.folio.audio.mix
import com.qshlab.folio.data.MeetingVoices
import com.qshlab.folio.data.NotFound
import com.qshlab.folio.data.People
import com.qshlab.folio.data.Speaker
import com.qshlab.folio.data.Store
import com.qshlab.folio.data.Transcript
import com.qshlab.folio.data.now
import com.qshlab.folio.data.writeJson
import com.qshlab.folio.speech.Diarizer
import com.qshlab.folio.speech.Embedder
import com.qshlab.folio.speech.ModelStore
import com.qshlab.folio.speech.Parakeet
import com.qshlab.folio.speech.VoiceActivity
import com.qshlab.folio.speech.W
import com.qshlab.folio.speech.WhisperListen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** A queued or running piece of work on one meeting ("_state" for a project's state). */
data class Task(
    val key: String, val pid: String, val mid: String, val kind: String, val title: String,
    val step: String = "queued", val progress: Float = 0f, val running: Boolean = false, val stopping: Boolean = false,
    val jid: String = UUID.randomUUID().toString(), val opts: Map<String, String> = emptyMap(),
)

val KIND_LABEL = mapOf("process" to "Transcribing", "speakers" to "Finding speakers", "correct" to "Fixing transcript",
    "summarize" to "Summarizing", "state" to "Updating project state")

/**
 * One worker runs the heavy steps one at a time (they share the CPU, memory and battery). Models load
 * when needed and are dropped after a few idle minutes. While recording, waiting work holds off so the
 * live text stays responsive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Engine(private val ctx: Context, val store: Store, val models: ModelStore) {
    val people = People(store)
    val claude = Claude(ctx, store.home)
    val summaries = Summaries(store, people, claude)
    val search = Search(store, people)
    val fixes = Fixes(ctx, store, people, claude)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val queue = Channel<Task>(Channel.UNLIMITED)
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks
    private var current: Job? = null
    @Volatile var recording: () -> Boolean = { false }
    @Volatile var foreground = false

    // --- models in memory

    private var parakeet: Parakeet? = null
    private var whisper: WhisperListen? = null
    private var lastUse = System.currentTimeMillis()
    private val _loaded = MutableStateFlow<Set<String>>(emptySet())
    val loaded: StateFlow<Set<String>> = _loaded
    var autoUnloadMinutes: Int
        get() = ctx.getSharedPreferences("settings", 0).getInt("autoUnload", 5)
        set(v) = ctx.getSharedPreferences("settings", 0).edit().putInt("autoUnload", v).apply()

    @Synchronized fun asr(): Parakeet = parakeet ?: Parakeet(models).also { parakeet = it; _loaded.update { s -> s + "parakeet" } }
    @Synchronized private fun listener(): WhisperListen = whisper ?: WhisperListen(models).also { whisper = it; _loaded.update { s -> s + "whisper" } }

    @Synchronized fun unload(which: String? = null) {
        if (which == null || which == "parakeet") { parakeet?.release(); parakeet = null; _loaded.update { it - "parakeet" } }
        if (which == null || which == "whisper") { whisper?.release(); whisper = null; _loaded.update { it - "whisper" } }
    }

    fun touch() { lastUse = System.currentTimeMillis() }

    init {
        scope.launch { worker() }
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(30_000)
                val m = autoUnloadMinutes
                if (m > 0 && _tasks.value.isEmpty() && !recording() && System.currentTimeMillis() - lastUse > m * 60_000L) unload()
            }
        }
    }

    // --- queue

    fun submit(pid: String, mid: String, kind: String, opts: Map<String, String> = emptyMap()) {
        val key = "$pid/$mid"
        if (_tasks.value.any { it.key == key }) return
        val title = if (mid == "_state") "Where things stand" else runCatching { store.meeting(pid, mid).title }.getOrDefault(mid)
        val t = Task(key, pid, mid, kind, title, opts = opts)
        _tasks.update { it + t }
        if (kind == "process") runCatching { store.updateMeeting(pid, mid) { it.copy(status = "queued", error = "") } }
        queue.trySend(t)
        FolioService.ensure(ctx)
    }

    /** Removes a waiting task, or stops the running one at its next checkpoint. */
    fun stop(key: String) {
        val t = _tasks.value.firstOrNull { it.key == key } ?: return
        if (!t.running) {
            _tasks.update { l -> l.filter { it.key != key } }
            stopped(t, quitting = false)
        } else {
            _tasks.update { l -> l.map { if (it.key == key) it.copy(stopping = true) else it } }
            current?.cancel()
        }
    }

    fun stopAll() = _tasks.value.forEach { stop(it.key) }

    private fun stopped(t: Task, quitting: Boolean) {
        if (t.kind != "process") return
        runCatching {
            store.updateMeeting(t.pid, t.mid) { if (quitting) it.copy(status = "queued") else it.copy(status = "stopped", error = "Stopped before transcription finished.") }
        }
    }

    private fun step(key: String, step: String, progress: Float) {
        _tasks.update { l -> l.map { if (it.key == key) it.copy(step = step, progress = progress) else it } }
    }

    private suspend fun worker() {
        for (t in queue) {
            if (_tasks.value.none { it.jid == t.jid }) continue            // removed while waiting
            while (recording()) delay(1000)
            _tasks.update { l -> l.map { if (it.jid == t.jid) it.copy(running = true) else it } }
            val main = t.kind == "process" || t.kind == "speakers"
            val t0 = System.currentTimeMillis()
            var ok = false
            val job = scope.launch {
                try {
                    if (main) store.updateMeeting(t.pid, t.mid) { it.copy(status = "processing") }
                    run(t)
                    ok = true
                    if (main) store.updateMeeting(t.pid, t.mid) { it.copy(status = "ready", error = "", processedIn = (System.currentTimeMillis() - t0) / 1000.0) }
                    else if (t.mid != "_state") store.updateMeeting(t.pid, t.mid) { it.copy(jobError = "") }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (t.kind == "speakers") runCatching { store.updateMeeting(t.pid, t.mid) { it.copy(status = "ready") } }
                    stopped(t, quitting = false)
                } catch (e: NotFound) {
                } catch (e: Throwable) {
                    android.util.Log.e("Folio", "task ${t.kind} failed", e)
                    val msg = (if (e is ClaudeError) e.message else e.message ?: e.toString())?.take(400) ?: "failed"
                    runCatching {
                        if (main) store.updateMeeting(t.pid, t.mid) { it.copy(status = "error", error = msg) }
                        else if (t.mid == "_state") store.saveProject(t.pid, store.project(t.pid).copy(stateError = msg))
                        else store.updateMeeting(t.pid, t.mid) { it.copy(jobError = "${KIND_LABEL[t.kind]}: $msg") }
                    }
                }
            }
            current = job
            job.join()
            current = null
            touch()
            _tasks.update { l -> l.filter { it.jid != t.jid } }
            com.qshlab.folio.data.Changes.bump()
            if (ok) {
                if (t.kind == "process" && claude.key != null) submit(t.pid, t.mid, "summarize")
                else Notify.done(ctx, this, t)
            }
            if (_tasks.value.isEmpty()) FolioService.ensure(ctx)
        }
    }

    private suspend fun run(t: Task) {
        when (t.kind) {
            "process" -> process(t)
            "speakers" -> redoSpeakers(t, t.opts["speakers"]?.toIntOrNull() ?: -1)
            "summarize" -> summaries.summarize(t.pid, t.mid, { step(t.key, "summarizing", it) }, checker())
            "correct" -> fixes.run(t.pid, t.mid, t.opts["mode"] ?: "both", { mixed(t.pid, t.mid) },
                if (models.ready("whisper")) { pcm, off -> listen(pcm, off) } else null, summaries,
                { s, p -> step(t.key, s, p) }, checker())
            "state" -> {
                val note = t.opts["note"].orEmpty()
                if (note.isNotBlank() && t.opts["addToKnowledge"] == "1") summaries.noteToKnowledge(t.pid, note)
                step(t.key, "updating state", 0.2f)
                if (t.opts["mode"] == "rebuild") summaries.rebuild(t.pid, note, checker()) else summaries.revise(t.pid, note, checker())
            }
        }
    }

    /** A check usable from blocking code: throws if this task was stopped. */
    private suspend fun checker(): () -> Unit {
        val job = coroutineContext[Job]!!
        return { if (!job.isActive) throw kotlinx.coroutines.CancellationException("stopped") }
    }

    private fun listen(pcm: FloatArray, offset: Double): List<W> {
        touch()
        val vad = VoiceActivity(models)
        val out = mutableListOf<W>()
        try {
            val l = listener()
            for ((a, b) in vad.regions(pcm)) out += l.transcribe(pcm.copyOfRange((a * SR).toInt(), minOf((b * SR).toInt(), pcm.size)), offset + a)
        } finally { vad.release(); unload("whisper") }
        return out
    }

    // --- transcription + speakers

    /** Recorded meetings have mic/system tracks; imports have original.*. Returns name → samples. */
    fun tracks(pid: String, mid: String): Map<String, FloatArray> {
        val d = store.meetingDir(pid, mid)
        val out = linkedMapOf<String, FloatArray>()
        for (n in listOf("mic", "system")) {
            val pcm = File(d, "$n.pcm"); val m4a = File(d, "$n.m4a")
            if (pcm.exists() && pcm.length() > SR) out[n] = PcmWriter.read(pcm)
            else if (m4a.exists()) out[n] = Decoder.decode(m4a)
        }
        if (out.isEmpty()) {
            val orig = d.listFiles { f -> f.name.startsWith("original.") }?.firstOrNull()
                ?: File(d, "audio.m4a").takeIf { it.exists() } ?: throw IllegalStateException("No audio found.")
            out["main"] = Decoder.decode(orig)
        }
        return out
    }

    fun mixed(pid: String, mid: String): FloatArray = mix(tracks(pid, mid).values.toList())

    private suspend fun transcribe(key: String, pcm: FloatArray, part: Int, parts: Int): Pair<List<W>, List<Pair<Double, Double>>> {
        val vad = VoiceActivity(models, minSilence = 0.3f, maxSpeech = 20f)
        val regions = try { vad.regions(pcm) } finally { vad.release() }
        val asr = asr()
        val words = mutableListOf<W>()
        regions.forEachIndexed { i, (a, b) ->
            coroutineContext.ensureActive()
            words += asr.transcribe(pcm.copyOfRange((a * SR).toInt(), minOf((b * SR).toInt(), pcm.size)), a)
            step(key, "transcribing", (part + (i + 1f) / regions.size) / parts)
            touch()
        }
        return words to regions
    }

    private fun norm(w: String) = w.lowercase().replace(Regex("[^a-z0-9']"), "")

    /** Without headphones the mic also hears the phone's speaker; drop mic words the other track has too. */
    private fun dropEcho(mic: List<W>, other: List<W>): List<W> {
        if (other.isEmpty()) return mic
        return mic.filter { w -> other.none { o -> kotlin.math.abs(o.start - w.start) < 1.0 && norm(o.text).isNotEmpty() && norm(o.text) == norm(w.text) } }
    }

    private suspend fun process(t: Task) {
        val d = store.meetingDir(t.pid, t.mid)
        step(t.key, "decoding", 0f)
        val tracks = tracks(t.pid, t.mid)
        val full = mix(tracks.values.toList())
        if (full.size < SR / 2) throw IllegalStateException("No audio found.")
        // Keep compact copies; the raw recording files go once encoded.
        for ((n, pcm) in tracks) if (n != "main" && !File(d, "$n.m4a").exists()) AacWriter.write(pcm, File(d, "$n.m4a"))
        AacWriter.write(full, File(d, "audio.m4a"))
        for (n in listOf("mic", "system")) File(d, "$n.pcm").delete()
        store.updateMeeting(t.pid, t.mid) { it.copy(status = "processing", duration = full.size.toDouble() / SR) }

        val results = linkedMapOf<String, Pair<List<W>, List<Pair<Double, Double>>>>()
        tracks.entries.forEachIndexed { i, (n, pcm) -> results[n] = transcribe(t.key, pcm, i, tracks.size) }
        val voices = HashMap<Int, FloatArray>()
        val names = HashMap<String, String>()
        val words: List<W>
        val emb = Embedder(models)
        try {
            step(t.key, "speakers", 0f)
            if (tracks.keys == setOf("mic", "system")) {
                // The phone's own audio + mic: the mic is you; only the other side needs telling apart.
                val mic = dropEcho(results["mic"]!!.first, results["system"]!!.first).onEach { it.speaker = 0 }
                names["0"] = "Me"
                Diarizer.run(tracks["mic"]!!, results["mic"]!!.second, emb, numSpeakers = 1).centroids[0]?.let { voices[0] = it }
                val sys = results["system"]!!
                val r = Diarizer.run(tracks["system"]!!, sys.second, emb) { step(t.key, "speakers", it) }
                Diarizer.assign(sys.first, r.turns, offset = 1)
                r.centroids.forEach { (k, v) -> voices[k + 1] = v }
                words = (mic + sys.first).sortedBy { it.start }
            } else {
                val (n, pcm) = tracks.entries.first()
                val (ws, regions) = results[n]!!
                val r = Diarizer.run(pcm, regions, emb, numSpeakers = t.opts["speakers"]?.toIntOrNull() ?: -1) { step(t.key, "speakers", it) }
                Diarizer.assign(ws, r.turns)
                voices.putAll(r.centroids)
                words = ws
            }
        } finally { emb.release() }
        File(d, "voices.json").writeJson(MeetingVoices(voices.mapKeys { it.key.toString() }.mapValues { it.value.toList() }))
        val speakers = words.mapNotNull { it.speaker }.distinct().sorted().associate { it.toString() to Speaker(name = names[it.toString()]) }
        store.saveTranscript(t.pid, t.mid, Transcript(asr = "parakeet", words = words.map { it.toWord() }, speakers = speakers))
        people.match(t.pid, t.mid)
    }

    /** Re-detect speakers only (e.g. with a given count), keeping any text edits. */
    private suspend fun redoSpeakers(t: Task, num: Int) {
        val d = store.meetingDir(t.pid, t.mid)
        val tr = store.transcript(t.pid, t.mid) ?: throw NotFound(t.mid)
        val tracks = tracks(t.pid, t.mid)
        val online = tracks.keys == setOf("mic", "system")
        val pcm = if (online) tracks["system"]!! else mix(tracks.values.toList())
        val idx = tr.words.indices.filter { !(online && tr.words[it].speaker == 0) }
        val ws = idx.map { W(tr.words[it].text, tr.words[it].start, tr.words[it].end) }
        val vad = VoiceActivity(models)
        val regions = try { vad.regions(pcm) } finally { vad.release() }
        val emb = Embedder(models)
        val r = try { Diarizer.run(pcm, regions, emb, numSpeakers = num) { step(t.key, "speakers", it) } } finally { emb.release() }
        coroutineContext.ensureActive()
        Diarizer.assign(ws, r.turns, offset = if (online) 1 else 0)
        val words = tr.words.toMutableList()
        idx.forEachIndexed { k, i -> words[i] = words[i].copy(speaker = ws[k].speaker) }
        val used = words.mapNotNull { it.speaker }.distinct().sorted()
        val speakers = used.associate { s -> s.toString() to if (online && s == 0) (tr.speakers["0"] ?: Speaker("Me")) else Speaker() }
        val old = com.qshlab.folio.data.MeetingVoices()
        val voices = HashMap<String, List<Float>>()
        if (online) people.meetingVoices(t.pid, t.mid)["0"]?.let { voices["0"] = it.toList() }
        r.centroids.forEach { (k, v) -> voices[(k + if (online) 1 else 0).toString()] = v.toList() }
        File(d, "voices.json").writeJson(old.copy(voices = voices))
        store.saveTranscript(t.pid, t.mid, tr.copy(words = words, speakers = speakers))
        people.match(t.pid, t.mid)
    }

    /** At start: resume work interrupted by the app being closed. */
    fun resume() {
        for (p in store.listProjects()) for (m in store.listMeetings(p.id)) {
            when (m.meta.status) {
                "queued", "processing" -> if (store.transcript(p.id, m.id) != null) store.updateMeeting(p.id, m.id) { it.copy(status = "ready") } else submit(p.id, m.id, "process")
                "recording" -> {
                    val d = store.meetingDir(p.id, m.id)
                    val has = listOf("mic", "system").any { File(d, "$it.pcm").let { f -> f.exists() && f.length() > SR } }
                    if (has) submit(p.id, m.id, "process") else store.updateMeeting(p.id, m.id) { it.copy(status = "error", error = "The recording was interrupted.") }
                }
            }
        }
    }

    fun quit(force: Boolean, live: () -> Unit) {
        live()
        if (!force) _tasks.value.filter { it.running }.forEach { t -> stopped(t, quitting = true) }
        current?.cancel()
        ctx.stopService(Intent(ctx, FolioService::class.java))
    }

    fun now() = com.qshlab.folio.data.now()
}
