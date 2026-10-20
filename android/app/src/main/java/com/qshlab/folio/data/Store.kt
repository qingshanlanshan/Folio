package com.qshlab.folio.data

import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; explicitNulls = false }

fun now(): Double = System.currentTimeMillis() / 1000.0

class NotFound(what: String) : Exception("Not found: $what")

/** The built-in inbox for recordings not filed in a project yet (same as on the Mac). */
const val UNSORTED = "_unsorted"

/** Bumped whenever stored data changes in the background, so screens re-read it. */
object Changes {
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun bump() { version.value++ }
}

/** Write then rename, so a crash never leaves half a file. */
fun File.writeAtomic(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) { delete(); tmp.renameTo(this) }
}

inline fun <reified T> File.readJson(): T? = if (exists()) runCatching { json.decodeFromString<T>(readText()) }.getOrNull() else null
inline fun <reified T> File.writeJson(value: T) = writeAtomic(json.encodeToString(value))

/**
 * Everything lives in plain folders under filesDir/Folio, laid out like Folio on the Mac:
 *
 *   projects/<p>/project.json, project.md, glossary.tsv, state.md, state-history/, todos.json
 *   projects/<p>/meetings/<id>/meeting.json, audio.m4a, transcript.json, voices.json, live.jsonl,
 *                              corrections.json, summary.json
 *   people/<id>.json, <id>.voices.json
 */
class Store(val home: File) {
    val projects = File(home, "projects")
    val people = File(home, "people")
    val trash = File(home, "trash")

    data class ProjectInfo(val id: String, val meta: ProjectMeta, val meetings: Int, val updated: Long)
    data class MeetingInfo(val id: String, val meta: MeetingMeta)

    private fun slug(name: String): String {
        val s = name.replace(Regex("""[/\\:#?%*|"<>\x00-\x1f]+"""), " ").trim().trim('.')
            .replace(Regex("""\s+"""), "-").take(60)
        return s.ifEmpty { "untitled" }
    }

    private fun unique(parent: File, base: String): File {
        var f = File(parent, base); var n = 2
        while (f.exists()) { f = File(parent, "$base-$n"); n++ }
        return f
    }

    /** Deleting moves things to an in-app trash, so mistakes can be undone from a file manager. */
    fun moveToTrash(f: File) {
        trash.mkdirs()
        f.renameTo(unique(trash, f.name))
    }

    // --- projects

    fun projectDir(pid: String): File {
        val d = File(projects, pid)
        if (pid.contains("/") || pid.contains("..") || !File(d, "project.json").exists()) throw NotFound(pid)
        return d
    }

    fun listProjects(): List<ProjectInfo> {
        projects.mkdirs()
        return projects.listFiles().orEmpty().mapNotNull { d ->
            val meta = File(d, "project.json").readJson<ProjectMeta>() ?: return@mapNotNull null
            val ms = File(d, "meetings").listFiles().orEmpty().filter { File(it, "meeting.json").exists() }
            ProjectInfo(d.name, meta, ms.size, (ms.map { File(it, "meeting.json").lastModified() } + File(d, "project.json").lastModified()).max())
        }.sortedWith(compareBy<ProjectInfo>({ it.id != UNSORTED }, { -it.updated }))
    }

    fun ensureUnsorted() {
        val d = File(projects, UNSORTED)
        if (File(d, "project.json").exists()) return
        File(d, "meetings").mkdirs()
        File(d, "project.json").writeJson(ProjectMeta("Unsorted", now(), builtin = true))
    }

    fun createProject(name: String): String {
        projects.mkdirs()
        val d = unique(projects, slug(name))
        File(d, "meetings").mkdirs()
        File(d, "project.json").writeJson(ProjectMeta(name.trim(), now()))
        File(d, "project.md").writeText("")
        File(d, "glossary.tsv").writeText("# term\talso heard as (comma separated)\tnote\n")
        return d.name
    }

    fun project(pid: String): ProjectMeta = File(projectDir(pid), "project.json").readJson() ?: throw NotFound(pid)
    fun saveProject(pid: String, meta: ProjectMeta) = File(projectDir(pid), "project.json").writeJson(meta)
    fun knowledge(pid: String): String = File(projectDir(pid), "project.md").let { if (it.exists()) it.readText() else "" }
    fun setKnowledge(pid: String, text: String) = File(projectDir(pid), "project.md").writeAtomic(text)
    fun glossary(pid: String): String = File(projectDir(pid), "glossary.tsv").let { if (it.exists()) it.readText() else "" }
    fun setGlossary(pid: String, text: String) = File(projectDir(pid), "glossary.tsv").writeAtomic(text)

    // --- meetings

    fun meetingDir(pid: String, mid: String): File {
        val d = File(File(projectDir(pid), "meetings"), mid)
        if (mid.contains("/") || mid.contains("..") || !File(d, "meeting.json").exists()) throw NotFound(mid)
        return d
    }

    fun listMeetings(pid: String): List<MeetingInfo> =
        File(projectDir(pid), "meetings").listFiles().orEmpty().mapNotNull { d ->
            File(d, "meeting.json").readJson<MeetingMeta>()?.let { MeetingInfo(d.name, it) }
        }.sortedByDescending { it.meta.date }

    fun createMeeting(pid: String, title: String, date: Double, source: String, original: String?): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date((date * 1000).toLong()))
        val d = unique(File(projectDir(pid), "meetings"), "$stamp-${slug(title)}")
        d.mkdirs()
        File(d, "meeting.json").writeJson(MeetingMeta(title, date, source, original, "queued", now()))
        return d.name
    }

    /** Moves a recording (folder and all) to another project. Returns its id there. */
    fun moveMeeting(pid: String, mid: String, to: String): String {
        val src = meetingDir(pid, mid)
        val dest = unique(File(projectDir(to), "meetings"), mid)
        dest.parentFile?.mkdirs()
        if (!src.renameTo(dest)) { src.copyRecursively(dest); src.deleteRecursively() }
        return dest.name
    }

    fun meeting(pid: String, mid: String): MeetingMeta = File(meetingDir(pid, mid), "meeting.json").readJson() ?: throw NotFound(mid)

    @Synchronized
    fun updateMeeting(pid: String, mid: String, change: (MeetingMeta) -> MeetingMeta) {
        val f = File(meetingDir(pid, mid), "meeting.json")
        f.writeJson(change(f.readJson<MeetingMeta>() ?: throw NotFound(mid)))
    }

    fun transcript(pid: String, mid: String): Transcript? = File(meetingDir(pid, mid), "transcript.json").readJson()
    fun saveTranscript(pid: String, mid: String, t: Transcript) = File(meetingDir(pid, mid), "transcript.json").writeJson(t)

    fun audioFile(pid: String, mid: String) = File(meetingDir(pid, mid), "audio.m4a")
}
