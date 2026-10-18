package com.qshlab.folio.ai

import com.qshlab.folio.data.ActionItem
import com.qshlab.folio.data.NotFound
import com.qshlab.folio.data.People
import com.qshlab.folio.data.ProgressItem
import com.qshlab.folio.data.StateIndex
import com.qshlab.folio.data.StateUpdated
import com.qshlab.folio.data.StateVersion
import com.qshlab.folio.data.Store
import com.qshlab.folio.data.Summary
import com.qshlab.folio.data.Todo
import com.qshlab.folio.data.TodoClosed
import com.qshlab.folio.data.TodoFrom
import com.qshlab.folio.data.Todos
import com.qshlab.folio.data.Topic
import com.qshlab.folio.data.Transcript
import com.qshlab.folio.data.json
import com.qshlab.folio.data.now
import com.qshlab.folio.data.paragraphs
import com.qshlab.folio.data.readJson
import com.qshlab.folio.data.speakerName
import com.qshlab.folio.data.text
import com.qshlab.folio.data.writeAtomic
import com.qshlab.folio.data.writeJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** glossary.tsv: term <TAB> also heard as (comma separated) <TAB> note. '#' starts a comment. */
data class GlossaryEntry(val term: String, val heard: List<String>, val note: String)

fun parseGlossary(text: String): List<GlossaryEntry> = text.lines().mapNotNull { line ->
    if (line.isBlank() || line.trimStart().startsWith("#")) return@mapNotNull null
    var cols = line.split(Regex("\t|\\|")).map { it.trim() }
    if (cols.size == 1 && !cols[0].contains(",") && cols[0].contains(" - ")) cols = cols[0].split(" - ", limit = 2).map { it.trim() }
    val term = cols[0].takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    GlossaryEntry(term, cols.getOrElse(1) { "" }.split(",").map { it.trim() }.filter { it.isNotEmpty() }, cols.getOrElse(2) { "" })
}

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

/** The first JSON object in a reply (one retry with a nudge is done by the caller). */
fun firstJsonObject(text: String): JsonObject? {
    val start = text.indexOf('{'); val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { lenient.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
}

fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
fun JsonObject.arr(k: String) = (this[k] as? JsonArray).orEmpty()

private fun clock(s: Double) = "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())
private fun day(t: Double, fmt: String = "d MMM yyyy") = SimpleDateFormat(fmt, Locale.US).format(Date((t * 1000).toLong()))

/**
 * Project-aware meeting summaries, the rolling project state (state.md, kept up to date and editable,
 * with history) and to-dos, as on the Mac. Each request reads a fixed-size digest of the past (project
 * notes, state, the last 3 meetings, open to-dos, people present, glossary), never the whole history.
 */
class Summaries(private val store: Store, private val people: People, private val claude: Claude) {

    fun transcriptText(t: Transcript) = t.paragraphs().joinToString("\n") { "[${clock(it.start)}] ${t.speakerName(it.speaker)}: ${t.text(it)}" }

    fun todos(pid: String): List<Todo> = File(store.projectDir(pid), "todos.json").readJson<Todos>()?.items ?: emptyList()
    fun saveTodos(pid: String, items: List<Todo>) = File(store.projectDir(pid), "todos.json").writeJson(Todos(items))

    fun state(pid: String): String = File(store.projectDir(pid), "state.md").let { if (it.exists()) it.readText() else "" }

    fun saveState(pid: String, text: String, reason: String) {
        val d = store.projectDir(pid)
        val old = state(pid)
        val meta = store.project(pid)
        if (old.isNotBlank()) {
            val h = File(d, "state-history").apply { mkdirs() }
            val name = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date()) + ".md"
            File(h, name).writeText(old)
            val idx = File(h, "index.json").readJson<StateIndex>() ?: StateIndex()
            val prev = meta.stateUpdated
            File(h, "index.json").writeJson(idx.copy(versions = idx.versions + StateVersion(name, prev?.at ?: now(), prev?.reason ?: "")))
        }
        File(d, "state.md").writeAtomic(text.trim() + "\n")
        store.saveProject(pid, meta.copy(stateUpdated = StateUpdated(now(), reason), stateError = null))
    }

    data class Version(val file: String, val at: Double, val reason: String, val text: String)

    fun history(pid: String): List<Version> {
        val h = File(store.projectDir(pid), "state-history")
        val idx = (File(h, "index.json").readJson<StateIndex>() ?: StateIndex()).versions.toMutableList()
        val known = idx.map { it.file }.toSet()
        h.listFiles { f -> f.name.endsWith(".md") }.orEmpty().filter { it.name !in known }
            .forEach { idx += StateVersion(it.name, it.lastModified() / 1000.0, "") }
        return idx.sortedByDescending { it.at }.mapNotNull { v -> File(h, v.file).takeIf { it.exists() }?.let { Version(v.file, v.at, v.reason, it.readText()) } }
    }

    fun restore(pid: String, file: String) {
        val v = history(pid).firstOrNull { it.file == file } ?: throw NotFound(file)
        saveState(pid, v.text, "restored the version from ${day(v.at, "d MMM yyyy HH:mm")}")
    }

    /** Only the most recent meeting with a transcript moves the project state forward. */
    fun isLatest(pid: String, mid: String): Boolean {
        val date = store.meeting(pid, mid).date
        return store.listMeetings(pid).none { it.id != mid && it.meta.date > date && store.transcript(pid, it.id) != null }
    }

    private fun todoDate(x: Todo, dates: Map<String, Double>) = dates[x.from?.meeting] ?: x.created

    private fun context(pid: String, mid: String, t: Transcript, latest: Boolean): String {
        val meta = store.project(pid)
        val knowledge = store.knowledge(pid)
        val present = t.speakers.values.mapNotNull { it.person }.mapNotNull(people::find)
        val gl = parseGlossary(store.glossary(pid))
        val date = store.meeting(pid, mid).date
        val meetings = store.listMeetings(pid)
        val dates = meetings.associate { it.id to it.meta.date }
        var open = todos(pid).filter { it.status == "open" }
        if (!latest) open = open.filter { todoDate(it, dates) < date }   // an older meeting can't close later to-dos
        val earlier = meetings.filter { it.meta.date < date && it.meta.status == "ready" }.take(3).reversed().mapNotNull { m ->
            File(store.meetingDir(pid, m.id), "summary.json").readJson<Summary>()?.let { s ->
                "- ${m.meta.title} (${day(m.meta.date)}): ${s.overview}" + if (s.decisions.isNotEmpty()) " Decisions: " + s.decisions.take(5).joinToString("; ") else ""
            }
        }
        val parts = mutableListOf("Project: ${meta.name}")
        if (knowledge.isNotBlank()) parts += "About the project (from the user):\n" + knowledge.take(4000)
        state(pid).takeIf { it.isNotBlank() }?.let {
            val label = if (latest) "Project state before this meeting" else "Current project state (written after LATER meetings: background only, report only what this meeting says)"
            parts += "$label:\n" + it.take(4000)
        }
        if (earlier.isNotEmpty()) parts += "Earlier meetings:\n" + earlier.joinToString("\n")
        if (open.isNotEmpty()) parts += "Open to-dos (id: task — owner):\n" + open.take(40).joinToString("\n") { "${it.id}: ${it.text}" + if (it.owner.isNotEmpty()) " — ${it.owner}" else "" }
        if (present.isNotEmpty()) parts += "People in this meeting:\n" + present.joinToString("\n") { "- ${it.name}" + if (it.notes.isNotEmpty()) ": ${it.notes.take(300)}" else "" }
        if (gl.isNotEmpty()) parts += "Glossary: " + gl.take(60).joinToString("; ") { it.term + if (it.note.isNotEmpty()) " (${it.note})" else "" }
        return parts.joinToString("\n\n")
    }

    private val SYSTEM = """You write meeting minutes for a project's ongoing record. Use the project context to understand
names, terms and what is going on, but report only what was said in THIS meeting's transcript. Never invent
facts, owners or dates. Speaker labels like "Speaker B" are unnamed people; keep them as they are.
Reply with JSON only."""

    private val SCHEMA = """{
  "title": "short title for this meeting (max 8 words)",
  "overview": "3-5 sentences: purpose, what happened, outcome",
  "topics": [{"title": "...", "summary": "1-3 sentences", "start": "m:ss"}],
  "decisions": ["decision made in this meeting"],
  "action_items": [{"task": "...", "owner": "person or empty", "due": "as said, or empty"}],
  "todo_updates": [{"id": "id of an open to-do", "status": "done", "note": "evidence from this meeting"}],
  "progress": [{"area": "...", "update": "what moved forward or got stuck"}],
  "open_questions": ["unresolved question"]
}"""

    fun chatJson(system: String, user: String, effort: String, check: () -> Unit): JsonObject {
        var last = ""
        repeat(2) { attempt ->
            last = claude.chat(system, if (attempt == 0) user else "$user\n\nReply with valid JSON only.", effort = effort, check = check)
            firstJsonObject(last)?.let { return it }
        }
        throw ClaudeError("Claude didn't return a usable answer: ${last.take(120)}")
    }

    fun summarize(pid: String, mid: String, progress: (Float) -> Unit, check: () -> Unit): Summary {
        val t = people.resolve(store.transcript(pid, mid) ?: throw NotFound(mid))
        val latest = isLatest(pid, mid)
        val ctx = context(pid, mid, t, latest)
        progress(0.1f)
        // Claude's context is far larger than any meeting, so one request holds the whole transcript.
        val o = chatJson(SYSTEM, "$ctx\n\nTranscript:\n${transcriptText(t)}\n\nWrite the minutes as JSON in this shape:\n$SCHEMA", "medium", check)
        progress(0.75f)
        val s = Summary(
            title = o.str("title").take(80), overview = o.str("overview"),
            topics = o.arr("topics").mapNotNull { (it as? JsonObject)?.let { x -> Topic(x.str("title"), x.str("summary"), x.str("start")) } }.filter { it.title.isNotBlank() }.take(15),
            decisions = o.arr("decisions").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }.take(20),
            actionItems = o.arr("action_items").mapNotNull { (it as? JsonObject)?.let { x -> ActionItem(x.str("task"), x.str("owner"), x.str("due")) } }.filter { it.task.isNotBlank() }.take(25),
            todoUpdates = o.arr("todo_updates").mapNotNull { (it as? JsonObject)?.let { x -> com.qshlab.folio.data.TodoUpdate(x.str("id"), x.str("status"), x.str("note")) } }.take(25),
            progress = o.arr("progress").mapNotNull { (it as? JsonObject)?.let { x -> ProgressItem(x.str("area"), x.str("update")) } }.filter { it.update.isNotBlank() }.take(15),
            openQuestions = o.arr("open_questions").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }.take(20),
            at = now(), writer = claude.model.name, updatedState = latest,
        )
        File(store.meetingDir(pid, mid), "summary.json").writeJson(s)
        applyTodos(pid, mid, s)
        if (latest) updateState(pid, store.meeting(pid, mid).title, s, check)
        progress(1f)
        return s
    }

    fun summary(pid: String, mid: String): Summary? = File(store.meetingDir(pid, mid), "summary.json").readJson()

    private fun applyTodos(pid: String, mid: String, s: Summary) {
        // Re-summarizing replaces the to-dos this meeting created (unless you already touched them).
        var items = todos(pid).filter { !(it.from?.meeting == mid && !it.touched) }.map {
            if (it.closedBy?.meeting == mid && !it.touched) it.copy(status = "open", closedBy = null) else it
        }
        val byId = items.associateBy { it.id }.toMutableMap()
        for (u in s.todoUpdates) {
            val x = byId[u.id] ?: continue
            if (u.status.equals("done", true) && x.status == "open")
                byId[u.id] = x.copy(status = "done", closedBy = TodoClosed(mid, u.note.take(200)), updated = now())
        }
        items = items.map { byId[it.id] ?: it }
        val names = people.all().associate { it.name.lowercase() to it.id }
        items = items + s.actionItems.map { a ->
            Todo(UUID.randomUUID().toString().take(6), a.task.trim(), a.owner.trim(), names[a.owner.trim().lowercase()], a.due.trim(),
                "open", TodoFrom(mid), created = now())
        }
        saveTodos(pid, items)
    }

    private val STATE_SYSTEM = """You maintain a short living document describing where a project stands. Merge in what the
latest meeting changed; keep what is still true; drop what is resolved or outdated. Markdown, at most 350
words, with these sections: ## Goal, ## Current status, ## Recent decisions, ## Open issues, ## Next steps.
Only state facts found in the inputs."""

    private fun cleanMd(s: String) = s.trim().removePrefix("```markdown").removePrefix("```").removeSuffix("```").trim()

    private fun updateState(pid: String, title: String, s: Summary, check: () -> Unit) {
        val open = todos(pid).filter { it.status == "open" }.take(30)
        val user = "Project: ${store.project(pid).name}\n\nAbout the project:\n${store.knowledge(pid).take(2500).ifEmpty { "(nothing yet)" }}\n\n" +
            "Current state document:\n${state(pid).ifEmpty { "(empty: write the first version)" }}\n\n" +
            "Latest meeting “$title”:\n${json.encodeToString(Summary.serializer(), s).take(6000)}\n\n" +
            "Open to-dos:\n" + open.joinToString("\n") { "- ${it.text}" + if (it.owner.isNotEmpty()) " (${it.owner})" else "" } +
            "\n\nWrite the updated state document."
        cleanMd(claude.chat(STATE_SYSTEM, user, effort = "medium", check = check)).takeIf { it.isNotEmpty() }?.let { saveState(pid, it, "after “$title”") }
    }

    // --- by hand: update with a note, or rebuild from every meeting

    private val REVISE_SYSTEM = STATE_SYSTEM + """
The user has told you something important changed. Their note takes priority over everything else: rewrite
whatever it contradicts, and say what it changes."""

    private val REBUILD_SYSTEM = """You write a short document describing where a project stands now, from scratch, using the
project notes and its meeting record (oldest first). Later meetings override earlier ones; the user's note, if
any, overrides everything. Markdown, at most 350 words, with these sections: ## Goal, ## Current status,
## Recent decisions, ## Open issues, ## Next steps. Only state facts found in the inputs."""

    fun noteToKnowledge(pid: String, note: String) {
        val k = store.knowledge(pid).trimEnd()
        store.setKnowledge(pid, ((if (k.isEmpty()) "" else "$k\n\n") + "## Update ${day(now())}\n${note.trim()}\n"))
    }

    fun revise(pid: String, note: String, check: () -> Unit) {
        val open = todos(pid).filter { it.status == "open" }.take(30)
        val user = "Project: ${store.project(pid).name}\n\nAbout the project:\n${store.knowledge(pid).take(4000).ifEmpty { "(nothing yet)" }}\n\n" +
            "Current state document:\n${state(pid).ifEmpty { "(empty)" }}\n\nOpen to-dos:\n" + open.joinToString("\n") { "- ${it.text}" } +
            "\n\nThe user's note on what changed:\n${note.trim()}\n\nWrite the updated state document."
        cleanMd(claude.chat(REVISE_SYSTEM, user, effort = "medium", check = check)).takeIf { it.isNotEmpty() }?.let { saveState(pid, it, "updated with your note") }
    }

    fun rebuild(pid: String, note: String, check: () -> Unit) {
        val digests = store.listMeetings(pid).sortedBy { it.meta.date }.mapNotNull { m ->
            val s = summary(pid, m.id) ?: return@mapNotNull null
            buildString {
                append("### ${m.meta.title} (${day(m.meta.date)})\n${s.overview}")
                if (s.decisions.isNotEmpty()) append("\nDecisions: " + s.decisions.joinToString("; "))
                if (s.actionItems.isNotEmpty()) append("\nAction items: " + s.actionItems.joinToString("; ") { it.task + if (it.owner.isNotEmpty()) " (${it.owner})" else "" })
                if (s.progress.isNotEmpty()) append("\nProgress: " + s.progress.joinToString("; ") { "${it.area}: ${it.update}" })
                if (s.openQuestions.isNotEmpty()) append("\nOpen questions: " + s.openQuestions.joinToString("; "))
            }
        }
        val user = "Project: ${store.project(pid).name}\n\nAbout the project:\n${store.knowledge(pid).take(4000).ifEmpty { "(nothing yet)" }}\n\n" +
            "Meetings:\n${digests.joinToString("\n\n").ifEmpty { "(no summarized meetings yet)" }}" +
            (if (note.isNotBlank()) "\n\nThe user's note (takes priority):\n${note.trim()}" else "") + "\n\nWrite the state document."
        cleanMd(claude.chat(REBUILD_SYSTEM, user, effort = "medium", check = check)).takeIf { it.isNotEmpty() }?.let {
            saveState(pid, it, "rebuilt from all meetings" + if (note.isNotBlank()) " and your note" else "")
        }
    }

    // --- ask the project

    data class Answer(val text: String, val sources: List<Search.Hit>)

    fun ask(pid: String, question: String, search: Search): Answer {
        val words = Regex("[\\w']+").findAll(question.lowercase()).map { it.value }.filter { it.length > 3 }.toSet()
        val scored = LinkedHashMap<Pair<String, Double>, Pair<Int, Search.Hit>>()
        for (w in words) for (h in search.search(w, limit = 400).hits) {
            if (h.project != pid) continue
            val k = h.meeting to h.start
            scored[k] = ((scored[k]?.first ?: 0) + 1) to h
        }
        val top = scored.values.sortedWith(compareBy({ -it.first }, { -it.second.date })).take(14).map { it.second }
        val sums = store.listMeetings(pid).take(8).mapNotNull { m -> summary(pid, m.id)?.let { "- ${m.meta.title} (${day(m.meta.date, "d MMM")}): ${it.overview}" } }
        val material = "Project state:\n${state(pid).ifEmpty { "(none)" }}\n\nMeeting summaries (newest first):\n${sums.joinToString("\n").ifEmpty { "(none)" }}" +
            "\n\nPassages:\n" + top.joinToString("\n") { "[${it.title}, ${clock(it.start)}] ${it.speaker}: ${it.snippet}" }
        val text = claude.chat("""You answer questions about a project from its meeting record. Use only the provided material.
Cite sources inline like [Meeting title, m:ss]. If the material doesn't answer it, say so briefly.""",
            "Project: ${store.project(pid).name}\n\n$material\n\nQuestion: $question", effort = "low").trim()
        return Answer(text, top)
    }
}

/** Search across every project: people by name, and transcript text (optionally only what a person said). */
class Search(private val store: Store, private val people: People) {
    data class Hit(val project: String, val projectName: String, val meeting: String, val title: String, val date: Double,
                   val start: Double, val speaker: String, val snippet: String)
    data class Result(val people: List<com.qshlab.folio.data.Person>, val hits: List<Hit>, val total: Int)

    private val cache = HashMap<String, Pair<Long, Transcript>>()

    private fun transcript(pid: String, mid: String): Transcript? {
        val f = File(store.meetingDir(pid, mid), "transcript.json")
        if (!f.exists()) return null
        val hit = cache[f.path]
        if (hit != null && hit.first == f.lastModified()) return hit.second
        val t = people.resolve(f.readJson<Transcript>() ?: return null)
        cache[f.path] = f.lastModified() to t
        return t
    }

    fun search(q: String, person: String? = null, limit: Int = 60): Result {
        val ql = q.trim().lowercase()
        val ppl = if (ql.isNotEmpty() && person == null) people.all().filter { p -> (listOf(p.name) + p.aliases).any { ql in it.lowercase() } } else emptyList()
        val hits = mutableListOf<Hit>()
        for (proj in store.listProjects()) for (m in store.listMeetings(proj.id)) {
            val t = transcript(proj.id, m.id) ?: continue
            val ks = person?.let { pp -> t.speakers.filter { it.value.person == pp }.keys }
            for (p in t.paragraphs()) {
                if (ks != null && p.speaker?.toString() !in ks) continue
                val text = t.text(p)
                val i = if (ql.isEmpty()) 0 else text.lowercase().indexOf(ql)
                if (i < 0) continue
                val a = maxOf(0, i - 80); val b = minOf(text.length, i + ql.length + 120)
                hits += Hit(proj.id, proj.meta.name, m.id, m.meta.title, m.meta.date, p.start, t.speakerName(p.speaker),
                    (if (a > 0) "…" else "") + text.substring(a, b) + if (b < text.length) "…" else "")
            }
        }
        hits.sortWith(compareBy({ -it.date }, { it.start }))
        return Result(ppl, hits.take(limit), hits.size)
    }
}
