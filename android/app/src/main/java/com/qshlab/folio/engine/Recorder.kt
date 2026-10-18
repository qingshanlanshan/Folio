package com.qshlab.folio.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import com.qshlab.folio.audio.PcmWriter
import com.qshlab.folio.audio.SR
import com.qshlab.folio.data.now
import com.qshlab.folio.speech.LiveStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

@Serializable
data class LiveEvent(val seq: Int, val track: String, val start: Double, val text: String)

data class LiveState(
    val pid: String, val mid: String, val title: String, val tracks: List<String>,
    val elapsed: Double = 0.0, val levels: Map<String, Float> = emptyMap(),
    val events: List<LiveEvent> = emptyList(), val partials: Map<String, String> = emptyMap(),
    val stopping: Boolean = false, val error: String? = null,
)

/**
 * A live recording: each track (mic, and optionally the phone's own audio) is read in 0.1 s blocks,
 * written to <track>.pcm as it arrives (so nothing is lost if the app is killed), and fed to the
 * streaming model for live text. On stop, the meeting goes through the full pipeline.
 */
class Recorder(private val engine: Engine) {
    private val _state = MutableStateFlow<LiveState?>(null)
    val state: StateFlow<LiveState?> = _state
    @Volatile private var running = false
    private val threads = mutableListOf<Thread>()
    private var seq = 0

    val active get() = _state.value?.let { !it.stopping } == true

    @SuppressLint("MissingPermission")   // checked by the UI before starting
    fun start(pid: String, title: String, mic: Boolean, projection: MediaProjection?) {
        check(_state.value == null) { "Already recording." }
        val store = engine.store
        val mid = store.createMeeting(pid, title, now(), "recording", null)
        val tracks = listOfNotNull("mic".takeIf { mic }, "system".takeIf { projection != null })
        store.updateMeeting(pid, mid) { it.copy(status = "recording", tracks = tracks) }
        val d = store.meetingDir(pid, mid)
        _state.value = LiveState(pid, mid, title, tracks)
        running = true
        seq = 0
        val log = File(d, "live.jsonl")
        for (tr in tracks) {
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val buf = maxOf(AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SR / 5 * 2)
            val rec = if (tr == "mic") {
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION).setAudioFormat(fmt).setBufferSizeInBytes(buf * 4).build()
            } else {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN).build()
                AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg).setAudioFormat(fmt).setBufferSizeInBytes(buf * 4).build()
            }
            threads += thread(name = "folio-rec-$tr") { loop(tr, rec, File(d, "$tr.pcm"), log) }
        }
        engine.recording = { running }
    }

    private fun loop(track: String, rec: AudioRecord, file: File, log: File) {
        val writer = PcmWriter(file)
        val live = runCatching { LiveStream(engine.models) }.getOrNull()
        val block = ShortArray(SR / 10)
        var level = 0f
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(block, 0, block.size)
                if (n <= 0) continue
                val x = FloatArray(n) { block[it] / 32768f }
                writer.write(x)
                var e = 0f; for (v in x) e += v * v
                level = 0.7f * level + 0.3f * sqrt(e / n)
                val elapsed = writer.samples.toDouble() / SR
                if (live != null) {
                    live.feed(x)
                    val partial = live.partial().text.trim().lowercase()
                    if (live.endpoint()) {
                        val text = live.finish().lowercase()
                        if (text.isNotEmpty()) {
                            val ev = LiveEvent(synchronized(this) { seq++ }, track, elapsed, text.replaceFirstChar { it.uppercase() })
                            log.appendText(com.qshlab.folio.data.json.encodeToString(ev).replace("\n", "") + "\n")
                            _state.update { s -> s?.copy(events = s.events + ev, partials = s.partials - track) }
                        }
                    } else {
                        _state.update { s -> s?.copy(partials = if (partial.isEmpty()) s.partials - track else s.partials + (track to partial)) }
                    }
                }
                _state.update { s -> s?.copy(elapsed = maxOf(s.elapsed, elapsed), levels = s.levels + (track to level)) }
            }
        } catch (e: Exception) {
            _state.update { s -> s?.copy(error = e.message ?: "Recording failed") }
        } finally {
            runCatching { rec.stop() }; rec.release()
            writer.close()
            live?.release()
        }
    }

    /** Stops and saves; the meeting is then transcribed with speakers. */
    fun stop() {
        val s = _state.value ?: return
        if (s.stopping) return
        _state.update { it?.copy(stopping = true) }
        running = false
        thread(name = "folio-rec-stop") {
            threads.forEach { it.join(5000) }
            threads.clear()
            val dur = s.tracks.maxOfOrNull { File(engine.store.meetingDir(s.pid, s.mid), "$it.pcm").length() / 2.0 / SR } ?: 0.0
            if (dur < 1) engine.store.updateMeeting(s.pid, s.mid) { it.copy(status = "error", error = _state.value?.error ?: "Nothing was recorded.", duration = dur) }
            else {
                engine.store.updateMeeting(s.pid, s.mid) { it.copy(status = "queued", duration = dur) }
                engine.submit(s.pid, s.mid, "process")
            }
            _state.value = null
            com.qshlab.folio.data.Changes.bump()
            FolioService.ensure(engine.store.let { FolioAppRef.ctx })
        }
    }
}

/** The application context, for places that only have the engine. */
object FolioAppRef { lateinit var ctx: android.content.Context }
