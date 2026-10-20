package com.qshlab.folio.data

import com.qshlab.folio.speech.Diarizer
import com.qshlab.folio.speech.SAME_VOICE
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * People, shared across projects: people/<id>.json and people/<id>.voices.json (one voice print per
 * confirmed meeting). After each transcription, speakers are matched to known voices: ≥ 0.85 links
 * automatically, ≥ 0.7 is suggested. Only confirmed links add voice prints.
 */
@Serializable
data class VoicePrints(val prints: List<List<Float>> = emptyList())

@Serializable
data class MeetingVoices(val voices: Map<String, List<Float>> = emptyMap())

const val AUTO_LINK = 0.85f

class People(private val store: Store) {
    private val dir get() = store.people

    private fun file(id: String): File {
        if (!Regex("[a-z0-9-]+").matches(id)) throw NotFound(id)
        return File(dir, "$id.json").also { if (!it.exists()) throw NotFound(id) }
    }

    fun all(): List<Person> {
        dir.mkdirs()
        return dir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".voices.json") }.orEmpty()
            .mapNotNull { f -> f.readJson<Person>()?.copy(id = f.name.removeSuffix(".json")) }
            .sortedBy { it.name.lowercase() }
    }

    fun get(id: String): Person = file(id).readJson<Person>()?.copy(id = id) ?: throw NotFound(id)
    fun find(id: String?): Person? = id?.let { runCatching { get(it) }.getOrNull() }

    fun create(name: String, project: String? = null): Person {
        dir.mkdirs()
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).ifEmpty { "person" }
        val id = "$base-${UUID.randomUUID().toString().take(4)}"
        val p = Person(id, name.trim(), projects = listOfNotNull(project), created = now())
        File(dir, "$id.json").writeJson(p)
        return p
    }

    fun save(p: Person) {
        if (p.me) all().filter { it.me && it.id != p.id }.forEach { File(dir, "${it.id}.json").writeJson(it.copy(me = false)) }
        file(p.id).writeJson(p)
    }

    fun me(): Person? = all().firstOrNull { it.me }

    private fun printsFile(id: String) = File(dir, "$id.voices.json")
    fun prints(id: String): List<FloatArray> = printsFile(id).readJson<VoicePrints>()?.prints?.map { it.toFloatArray() } ?: emptyList()

    private fun addPrint(id: String, vec: FloatArray, src: VoiceSource) {
        val p = get(id)
        if (p.voices.any { it.project == src.project && it.meeting == src.meeting }) return
        printsFile(id).writeJson(VoicePrints(prints(id).map { it.toList() } + listOf(Diarizer.unit(vec).toList())))
        save(p.copy(voices = p.voices + src.copy(added = now())))
    }

    private fun removePrintsFrom(id: String, project: String, meeting: String) {
        val p = find(id) ?: return
        val keep = p.voices.indices.filter { !(p.voices[it].project == project && p.voices[it].meeting == meeting) }
        if (keep.size == p.voices.size) return
        val arr = prints(id)
        printsFile(id).writeJson(VoicePrints(keep.mapNotNull { arr.getOrNull(it)?.toList() }))
        save(p.copy(voices = keep.map { p.voices[it] }))
    }

    /** After a recording moves: its voice prints point to the new place, and its people join the project. */
    fun retarget(project: String, meeting: String, to: String, newMeeting: String) {
        val linked = store.transcript(to, newMeeting)?.speakers?.values?.mapNotNull { it.person }?.toSet() ?: emptySet()
        for (p in all()) {
            var q = p
            if (p.voices.any { it.project == project && it.meeting == meeting })
                q = q.copy(voices = q.voices.map { if (it.project == project && it.meeting == meeting) it.copy(project = to, meeting = newMeeting) else it })
            if (p.id in linked && to !in p.projects && to != UNSORTED) q = q.copy(projects = q.projects + to)
            if (q != p) file(p.id).writeJson(q)
        }
    }

    fun delete(id: String) {
        for (proj in store.listProjects()) for (m in store.listMeetings(proj.id)) {
            val t = store.transcript(proj.id, m.id) ?: continue
            var changed = false
            val spk = t.speakers.mapValues { (_, s) ->
                var x = s
                if (s.person == id) { x = x.copy(person = null, auto = false); changed = true }
                if (s.suggested?.person == id) { x = x.copy(suggested = null); changed = true }
                x
            }
            if (changed) store.saveTranscript(proj.id, m.id, t.copy(speakers = spk))
        }
        store.moveToTrash(file(id))
        printsFile(id).takeIf { it.exists() }?.let(store::moveToTrash)
    }

    fun meetingVoices(pid: String, mid: String): Map<String, FloatArray> =
        File(store.meetingDir(pid, mid), "voices.json").readJson<MeetingVoices>()?.voices?.mapValues { it.value.toFloatArray() } ?: emptyMap()

    fun link(pid: String, mid: String, speaker: String, person: String, auto: Boolean = false): Transcript {
        val t = store.transcript(pid, mid) ?: throw NotFound(mid)
        // A person is one speaker per meeting. If you already named another speaker as them, the two are
        // the same voice split in two: merge. An automatic match there was probably wrong: it's dropped below.
        if (!auto) t.speakers.entries.firstOrNull { it.key != speaker && it.value.person == person && !it.value.auto }
            ?.let { return mergeSpeakers(pid, mid, speaker, it.key) }
        val prev = t.speakers[speaker]?.person
        if (prev != null && prev != person) removePrintsFrom(prev, pid, mid)
        val spk = t.speakers.mapValues { (k, s) -> if (k != speaker && s.person == person) s.copy(person = null, auto = false) else s }.toMutableMap()
        spk[speaker] = (spk[speaker] ?: Speaker()).copy(person = person, auto = auto, suggested = null)
        val nt = t.copy(speakers = spk)
        store.saveTranscript(pid, mid, nt)
        val p = get(person)
        if (pid !in p.projects) save(p.copy(projects = p.projects + pid))
        if (!auto) meetingVoices(pid, mid)[speaker]?.let { addPrint(person, it, VoiceSource(pid, mid, speaker)) }
        return nt
    }

    /** Two speakers in one recording are the same person: [src]'s words become [dst]'s. */
    fun mergeSpeakers(pid: String, mid: String, src: String, dst: String): Transcript {
        require(src != dst) { "Same speaker" }
        val t = store.transcript(pid, mid) ?: throw NotFound(mid)
        val a = t.speakers[src] ?: throw NotFound(src)
        var b = t.speakers[dst] ?: throw NotFound(dst)
        var secA = 0.0; var secB = 0.0
        val to = dst.toInt()
        val words = t.words.map { w ->
            when (w.speaker?.toString()) {
                src -> { secA += w.end - w.start; w.copy(speaker = to) }
                dst -> { secB += w.end - w.start; w }
                else -> w
            }
        }
        if (b.person == null && a.person != null) b = b.copy(person = a.person, auto = a.auto, suggested = null)
        if (b.name == null && a.name != null) b = b.copy(name = a.name)
        if (b.person == null && b.suggested == null && a.suggested != null) b = b.copy(suggested = a.suggested)
        val nt = t.copy(words = words, speakers = t.speakers - src + (dst to b), edited = true)
        store.saveTranscript(pid, mid, nt)
        // One voice for the merged speaker: the two prints, weighted by how long each spoke.
        val voices = meetingVoices(pid, mid).toMutableMap()
        val va = voices.remove(src); val vb = voices[dst]
        if (va != null && vb != null && va.size == vb.size) {
            val ua = Diarizer.unit(va); val ub = Diarizer.unit(vb); val total = maxOf(secA + secB, 1e-9)
            voices[dst] = FloatArray(ua.size) { ((ua[it] * secA + ub[it] * secB) / total).toFloat() }
        } else if (va != null) voices[dst] = va
        val vf = File(store.meetingDir(pid, mid), "voices.json")
        if (vf.exists()) vf.writeJson(MeetingVoices(voices.mapValues { it.value.toList() }))
        setOfNotNull(a.person, b.person).forEach { removePrintsFrom(it, pid, mid) }
        val bp = b.person
        if (bp != null && !b.auto && find(bp) != null) voices[dst]?.let { addPrint(bp, it, VoiceSource(pid, mid, dst)) }
        return nt
    }

    /** Two people are the same person: [src]'s recordings, voice samples, names and notes move to [dst]. */
    fun mergePeople(src: String, dst: String): Person {
        require(src != dst) { "Same person" }
        val a = get(src); get(dst)
        for (proj in store.listProjects()) for (m in store.listMeetings(proj.id)) {
            val t = store.transcript(proj.id, m.id) ?: continue
            val ka = t.speakers.entries.firstOrNull { it.value.person == src }?.key
            val kb = t.speakers.entries.firstOrNull { it.value.person == dst }?.key
            var changed = false
            val spk = t.speakers.mapValues { (k, s) ->
                var x = s
                if (s.suggested?.person == src) { x = x.copy(suggested = s.suggested.copy(person = dst)); changed = true }
                if (k == ka) { x = x.copy(person = dst); changed = true }
                x
            }
            if (changed) store.saveTranscript(proj.id, m.id, t.copy(speakers = spk))
            if (ka != null && kb != null) mergeSpeakers(proj.id, m.id, ka, kb)
        }
        val b = get(dst)
        val pa = prints(src); val pb = prints(dst)
        val seen = b.voices.map { it.project to it.meeting }.toSet()
        val keep = a.voices.indices.filter { (a.voices[it].project to a.voices[it].meeting) !in seen && it < pa.size }
        var voices = b.voices
        if (keep.isNotEmpty() && (pb.isEmpty() || pb[0].size == pa[keep[0]].size)) {
            printsFile(dst).writeJson(VoicePrints((pb + keep.map { pa[it] }).map { it.toList() }))
            voices = voices + keep.map { a.voices[it] }
        }
        val known = (b.aliases + b.name).map { it.lowercase() }.toSet()
        val aliases = b.aliases + (listOf(a.name) + a.aliases).filter { it.isNotBlank() && it.lowercase() !in known }.distinctBy { it.lowercase() }
        val notes = if (a.notes.isNotBlank() && a.notes.trim() !in b.notes) (b.notes.trimEnd() + "\n\n" + a.notes.trim()).trim() else b.notes
        val nb = b.copy(voices = voices, aliases = aliases, projects = (b.projects + a.projects).distinct(), notes = notes, me = b.me || a.me)
        file(dst).writeJson(nb)
        store.moveToTrash(file(src))
        printsFile(src).takeIf { it.exists() }?.let(store::moveToTrash)
        return nb
    }

    fun unlink(pid: String, mid: String, speaker: String, reject: Boolean): Transcript {
        val t = store.transcript(pid, mid) ?: throw NotFound(mid)
        val s = t.speakers[speaker] ?: Speaker()
        s.person?.let { removePrintsFrom(it, pid, mid) }
        val nt = t.copy(speakers = t.speakers + (speaker to s.copy(person = null, auto = false, suggested = if (reject) null else s.suggested)))
        store.saveTranscript(pid, mid, nt)
        return nt
    }

    /** After transcription: link or suggest known people for each speaker. */
    fun match(pid: String, mid: String) {
        val t = store.transcript(pid, mid) ?: return
        val voices = meetingVoices(pid, mid)
        if (voices.isEmpty()) return
        val known = all().filter { it.voices.isNotEmpty() }
        data class Score(val s: Float, val k: String, val p: String)
        val scores = mutableListOf<Score>()
        for ((k, v) in voices) {
            if (t.speakers[k]?.person != null) continue
            val u = Diarizer.unit(v)
            for (p in known) {
                val arr = prints(p.id).filter { it.size == u.size }
                if (arr.isEmpty()) continue
                val best = arr.maxOf { Diarizer.dot(it, u) } + if (pid in p.projects) 0.02f else 0f
                scores += Score(best, k, p.id)
            }
        }
        val spk = t.speakers.toMutableMap()
        val takenS = mutableSetOf<String>()
        val takenP = spk.values.mapNotNull { it.person }.toMutableSet()
        for (sc in scores.sortedByDescending { it.s }) {
            if (sc.k in takenS || sc.p in takenP || sc.s < SAME_VOICE) continue
            takenS += sc.k; takenP += sc.p
            val cur = spk[sc.k] ?: Speaker()
            spk[sc.k] = if (sc.s >= AUTO_LINK) cur.copy(person = sc.p, auto = true, suggested = null)
            else cur.copy(suggested = Suggestion(sc.p, Math.round(sc.s * 1000) / 1000.0))
        }
        // A meeting recorded with the phone's own audio + mic: speaker 0 ("Me") is you.
        val me = me()
        if (me != null && spk["0"]?.name == "Me" && "0" !in takenS && spk["0"]?.person == null) spk["0"] = spk["0"]!!.copy(person = me.id, auto = true)
        store.saveTranscript(pid, mid, t.copy(speakers = spk))
    }

    data class Appearance(val project: String, val projectName: String, val meeting: String, val title: String, val date: Double, val seconds: Double)

    fun appearances(id: String): List<Appearance> {
        val out = mutableListOf<Appearance>()
        for (proj in store.listProjects()) for (m in store.listMeetings(proj.id)) {
            val t = store.transcript(proj.id, m.id) ?: continue
            val ks = t.speakers.filter { it.value.person == id }.keys
            if (ks.isEmpty()) continue
            val secs = t.words.filter { it.speaker?.toString() in ks }.sumOf { it.end - it.start }
            out += Appearance(proj.id, proj.meta.name, m.id, m.meta.title, m.meta.date, secs)
        }
        return out.sortedByDescending { it.date }
    }

    /** Display names from linked people (and the suggested person's name, for the UI). */
    fun resolve(t: Transcript): Transcript {
        val cache = HashMap<String, Person?>()
        fun person(id: String) = cache.getOrPut(id) { find(id) }
        return t.copy(speakers = t.speakers.mapValues { (_, s) ->
            var x = s
            val p = s.person?.let(::person)
            if (p != null) x = x.copy(name = p.name) else if (s.person != null) x = x.copy(person = null)
            s.suggested?.let { sug -> x = person(sug.person)?.let { x.copy(suggested = sug.copy(name = it.name)) } ?: x.copy(suggested = null) }
            x
        })
    }
}
