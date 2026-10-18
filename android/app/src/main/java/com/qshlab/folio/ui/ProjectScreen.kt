package com.qshlab.folio.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.qshlab.folio.FolioApp
import com.qshlab.folio.ai.ClaudeError
import com.qshlab.folio.ai.Summaries
import com.qshlab.folio.data.NotFound
import com.qshlab.folio.data.now
import com.qshlab.folio.engine.FolioService
import com.qshlab.folio.engine.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Copies a picked or shared file into a new meeting and queues it for transcription. */
fun importInto(app: FolioApp, pid: String, uri: Uri) {
    val cr = app.contentResolver
    var name = "Recording"
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
    val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 } ?: "m4a"
    val mid = app.store.createMeeting(pid, name.substringBeforeLast('.'), now(), "import", name)
    val d = app.store.meetingDir(pid, mid)
    cr.openInputStream(uri)?.use { i -> File(d, "original.$ext").outputStream().use { i.copyTo(it) } }
    app.engine.submit(pid, mid, "process")
    changed()
}

private data class Source(val id: String, val label: String, val sub: String, val mic: Boolean, val phone: Boolean)
private val SOURCES = listOf(
    Source("mic", "Mic", "In-person meeting, lecture or class", true, false),
    Source("both", "Phone + mic", "Audio playing on this phone, plus you on the mic. Calls in Zoom/Teams/Meet usually can't be captured: put them on speaker instead.", true, true),
    Source("phone", "Phone audio", "A webinar or video playing on this phone (apps can block this)", false, true),
)

@Composable
fun ProjectScreen(nav: NavHostController, pid: String) {
    val app = FolioApp.instance
    val version by dataVersion.collectAsState()
    val tasks by app.engine.tasks.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tasks.isNotEmpty()) { while (tasks.isNotEmpty()) { delay(1500); tick++ } }
    val meta = remember(version, tasks, tick) { runCatching { app.store.project(pid) }.getOrNull() }
    if (meta == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    var tab by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Screen(nav, meta.name, actions = {
        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true })
            DropdownMenuItem({ Text("Delete project…") }, { menu = false; deleting = true })
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Capture(pid)
            TabRow(tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("Overview", "Recordings", "Knowledge").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> Overview(nav, pid, version + tick)
                1 -> Recordings(nav, pid, tasks, version + tick)
                else -> Knowledge(pid)
            }
        }
    }
    if (renaming) NameDialog("Rename project", "Name", meta.name, { renaming = false }) { n ->
        app.store.saveProject(pid, app.store.project(pid).copy(name = n)); renaming = false; changed()
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete “${meta.name}”?") },
        text = { Text("Its recordings, transcripts and summaries are moved to Folio's trash.") },
        confirmButton = { TextButton({ app.store.moveToTrash(app.store.projectDir(pid)); deleting = false; changed(); nav.popBackStack() }) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } })
}

@Composable
private fun Capture(pid: String) {
    val app = FolioApp.instance
    val ctx = LocalContext.current
    val rec by app.recorder.state.collectAsState()
    val prefs = ctx.getSharedPreferences("settings", 0)
    var src by remember { mutableStateOf(prefs.getString("source", "mic")!!) }
    var title by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val source = SOURCES.first { it.id == src }

    fun go(code: Int, data: android.content.Intent?) {
        prefs.edit().putString("source", src).apply()
        val t = title.trim().ifEmpty { java.text.SimpleDateFormat("'Recording' d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) }
        FolioService.record(ctx, pid, t, source.mic, code, data)
        title = ""
    }
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) go(r.resultCode, r.data) else app.lastError.value = "Phone audio needs your permission to record."
    }
    fun start() {
        if (!app.models.requiredReady) { app.lastError.value = "Download the speech models in Settings first."; return }
        if (source.phone) projection.launch((ctx.getSystemService(MediaProjectionManager::class.java)).createScreenCaptureIntent())
        else go(0, null)
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) start() else app.lastError.value = "Folio needs the microphone to record."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) { for (u in uris) runCatching { importInto(app, pid, u) }.onFailure { app.lastError.value = it.message } }
    }

    val live = rec
    if (live != null && live.pid == pid) { LiveCard(live); return }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live != null) {
                Row(verticalAlignment = Alignment.CenterVertically) { Dot(RecRed); Spacer(Modifier.width(8.dp)); Text("Recording in another project: ${live.title}") }
            } else {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SOURCES.forEachIndexed { i, s ->
                        SegmentedButton(src == s.id, { src = s.id }, SegmentedButtonDefaults.itemShape(i, SOURCES.size), icon = {},
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primaryContainer, activeContentColor = MaterialTheme.colorScheme.primary)) {
                            Text(s.label, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
                Hint(source.sub)
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), placeholder = { Text("Title (optional)") }, singleLine = true)
                Button({
                    val need = listOfNotNull(Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS.takeIf { android.os.Build.VERSION.SDK_INT >= 33 })
                    if (need.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) start() else perms.launch(need.toTypedArray())
                }, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = RecRed, contentColor = androidx.compose.ui.graphics.Color.White),
                    shape = RoundedCornerShape(12.dp)) {
                    Dot(androidx.compose.ui.graphics.Color.White, 10); Spacer(Modifier.width(10.dp)); Text("Start recording", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            OutlinedButton({ picker.launch(arrayOf("audio/*", "video/*")) }, Modifier.fillMaxWidth()) { Text("Import audio or video files") }
        }
    }
}

/** Live recording on the project page: timer, level, Stop, and the draft text. */
@Composable
fun LiveCard(live: com.qshlab.folio.engine.LiveState, full: Boolean = false) {
    val app = FolioApp.instance
    val list = rememberLazyListState()
    LaunchedEffect(live.events.size, live.partials) { if (live.events.isNotEmpty()) list.animateScrollToItem(maxOf(0, live.events.size)) }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(RecRed); Spacer(Modifier.width(8.dp))
                Text(if (live.stopping) "Saving…" else "Recording", color = RecRed, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp)); Text(clock(live.elapsed), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                OutlinedButton({ app.recorder.stop() }, enabled = !live.stopping) { Text("■ Stop") }
            }
            for (tr in live.tracks) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Hint(if (tr == "mic") "Mic" else "Phone", Modifier.width(44.dp))
                LinearProgressIndicator({ (kotlin.math.sqrt(live.levels[tr] ?: 0f) * 2.6f).coerceIn(0f, 1f) }, Modifier.width(120.dp))
            }
            live.error?.let { Hint(it, color = MaterialTheme.colorScheme.error) }
            val both = live.tracks.size == 2
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (full) 600.dp else 260.dp).padding(top = 8.dp), state = list) {
                if (live.events.isEmpty() && live.partials.isEmpty()) item { Hint("Listening… text appears as people speak.") }
                items(live.events.size) { i ->
                    val e = live.events[i]
                    Text(buildAnnotatedString {
                        if (both) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (e.track == "mic") speakerColor(0) else speakerColor(1))) { append(if (e.track == "mic") "You  " else "Others  ") }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(clock(e.start) + "  ") }
                        append(e.text)
                    }, Modifier.padding(vertical = 3.dp), fontFamily = Serif, fontSize = 15.sp)
                }
                live.partials.forEach { (tr, p) -> item { Text((if (both) (if (tr == "mic") "You  " else "Others  ") else "") + p, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = Serif, fontSize = 15.sp) } }
            }
            Hint("Draft. After you stop, the whole recording is transcribed again with speakers.", Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Recordings(nav: NavHostController, pid: String, tasks: List<Task>, version: Int) {
    val app = FolioApp.instance
    val meetings = remember(version, tasks) { app.store.listMeetings(pid) }
    if (meetings.isEmpty()) { Hint("No recordings yet. Record or import one above.", Modifier.padding(16.dp)); return }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { for (m in meetings) MeetingRow(nav, pid, m, tasks.firstOrNull { it.key == "$pid/${m.id}" }) }
}

@Composable
fun MeetingRow(nav: NavHostController, pid: String, m: com.qshlab.folio.data.Store.MeetingInfo, task: Task?) {
    Card(Modifier.fillMaxWidth().clickable { nav.navigate("meeting/${enc(pid)}/${enc(m.id)}?t=-1") }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.meta.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2)
                val status = task?.let { (STEP_LABEL[it.step] ?: it.step) + if (it.progress > 0) " ${(it.progress * 100).toInt()}%" else "" }
                    ?: when (m.meta.status) { "ready" -> null; "error" -> "Failed"; else -> STEP_LABEL[m.meta.status] ?: m.meta.status }
                if (status != null) Badge(status, m.meta.status == "error") else Hint(clock(m.meta.duration))
            }
            Hint(whenText(m.meta.date) + (m.meta.original?.takeIf { m.meta.source == "import" }?.let { " · $it" } ?: ""))
            if (task != null && task.running) LinearProgressIndicator({ task.progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

@Composable
private fun Knowledge(pid: String) {
    val app = FolioApp.instance
    var k by remember(pid) { mutableStateOf(app.store.knowledge(pid)) }
    var g by remember(pid) { mutableStateOf(app.store.glossary(pid)) }
    var saved by remember { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Project knowledge", fontWeight = FontWeight.SemiBold)
        Hint("Goals, background, who's who, current status — anything the summary should know.")
        OutlinedTextField(k, { k = it; saved = false }, Modifier.fillMaxWidth().heightIn(min = 160.dp))
        Text("Glossary", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Hint("One term per line: term | how it's misheard (comma separated) | note. Used to fix transcripts.")
        OutlinedTextField(g, { g = it; saved = false }, Modifier.fillMaxWidth().heightIn(min = 120.dp))
        Button({ app.store.setKnowledge(pid, k); app.store.setGlossary(pid, g.replace(" | ", "\t")); saved = true; changed() }, enabled = !saved) { Text("Save") }
    }
}

@Composable
private fun Overview(nav: NavHostController, pid: String, version: Int) {
    val app = FolioApp.instance
    val sums = app.engine.summaries
    val scope = rememberCoroutineScope()
    val meetings = remember(version) { app.store.listMeetings(pid) }
    if (meetings.isEmpty()) { Hint("Record or import the first meeting above — the overview, to-dos and project state build up from there.", Modifier.padding(16.dp)); return }
    var q by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<Summaries.Answer?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Ask this project…") }, singleLine = true)
        Spacer(Modifier.width(8.dp))
        Button({
            asking = true; answer = null
            scope.launch {
                try { answer = withContext(Dispatchers.IO) { sums.ask(pid, q.trim(), app.engine.search) } }
                catch (e: Exception) { app.lastError.value = if (e is ClaudeError) e.message else "Couldn't answer: ${e.message}" }
                asking = false
            }
        }, enabled = q.isNotBlank() && !asking) { Text(if (asking) "…" else "Ask") }
    }
    answer?.let { a ->
        Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text(a.text, fontFamily = Serif, fontSize = 15.sp)
                if (a.sources.isNotEmpty()) Hint("Passages:", Modifier.padding(top = 8.dp))
                for (h in a.sources.take(5)) Text("${h.title}, ${clock(h.start)}", Modifier.clickable {
                    nav.navigate("meeting/${enc(h.project)}/${enc(h.meeting)}?t=${h.start}")
                }.padding(vertical = 3.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                TextButton({ answer = null }) { Text("Close") }
            }
        }
    }
    StateSection(pid, version)
    Todos(nav, pid, version)
    SectionTitle("Recent meetings")
    val tasks by app.engine.tasks.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { for (m in meetings.take(5)) MeetingRow(nav, pid, m, tasks.firstOrNull { it.key == "$pid/${m.id}" }) }
}

@Composable
private fun StateSection(pid: String, version: Int) {
    val app = FolioApp.instance
    val sums = app.engine.summaries
    val tasks by app.engine.tasks.collectAsState()
    val job = tasks.firstOrNull { it.key == "$pid/_state" }
    var mode by remember { mutableStateOf<String?>(null) }
    val state = remember(version, job) { sums.state(pid) }
    val meta = remember(version, job) { app.store.project(pid) }
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var toKnowledge by remember { mutableStateOf(true) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Where things stand", Modifier.weight(1f))
        if (mode == null && job == null) {
            TextButton({ text = state; mode = "edit" }) { Text("Edit") }
            TextButton({ mode = "update" }) { Text("Update…") }
            TextButton({ mode = "history" }) { Text("History") }
        }
    }
    if (job != null) Row(verticalAlignment = Alignment.CenterVertically) { Badge(if (job.running) "Updating" else "Waiting"); Spacer(Modifier.width(8.dp)); Hint("Rewriting the project state…") }
    meta.stateError?.takeIf { job == null }?.let { Hint("Last update failed: $it", color = MaterialTheme.colorScheme.error) }
    when (mode) {
        "edit" -> Column {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp))
            Row { TextButton({ sums.saveState(pid, text, "edited by you"); mode = null; changed() }) { Text("Save") }; TextButton({ mode = null }) { Text("Cancel") } }
        }
        "update" -> Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("What changed?", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp), placeholder = { Text("e.g. We dropped the hardware prototype and now focus on the app; the deadline moved to March.") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(toKnowledge, { toKnowledge = it }); Hint("Also add this note to the project knowledge, so future summaries know")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ app.engine.submit(pid, "_state", "state", mapOf("mode" to "note", "note" to note, "addToKnowledge" to if (toKnowledge) "1" else "0")); mode = null; note = "" },
                        enabled = note.isNotBlank()) { Text("Update") }
                    OutlinedButton({ app.engine.submit(pid, "_state", "state", mapOf("mode" to "rebuild", "note" to note, "addToKnowledge" to if (toKnowledge && note.isNotBlank()) "1" else "0")); mode = null; note = "" }) { Text("Rebuild") }
                    TextButton({ mode = null }) { Text("Cancel") }
                }
                Hint("Update revises the state with your note. Rebuild writes it again from every meeting's summary plus the project knowledge and your note — for a big change of aim. The old version stays in History.")
            }
        }
        "history" -> {
            val versions = remember { sums.history(pid) }
            var open by remember { mutableStateOf<String?>(null) }
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Row { Text("Earlier versions", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); TextButton({ mode = null }) { Text("Close") } }
                    if (versions.isEmpty()) Hint("No earlier versions yet.")
                    for (v in versions) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { open = if (open == v.file) null else v.file }) {
                                Text(whenText(v.at), color = MaterialTheme.colorScheme.primary); Hint(v.reason)
                            }
                            TextButton({ sums.restore(pid, v.file); mode = null; changed() }) { Text("Restore") }
                        }
                        if (open == v.file) Markdownish(v.text)
                    }
                }
            }
        }
        else -> if (state.isNotBlank()) {
            Markdownish(state)
            meta.stateUpdated?.let { Hint("Updated ${whenText(it.at)} ${it.reason}", Modifier.padding(top = 6.dp)) }
        } else Hint("Written by Claude after each meeting is summarized, and kept up to date. You can edit it, or use Update… to describe the project yourself.")
    }
}

@Composable
private fun Todos(nav: NavHostController, pid: String, version: Int) {
    val app = FolioApp.instance
    val sums = app.engine.summaries
    var v by remember { mutableIntStateOf(0) }
    val items = remember(version, v) { sums.todos(pid) }
    val titles = remember(version) { app.store.listMeetings(pid).associate { it.id to it.meta.title } }
    var newTodo by remember { mutableStateOf("") }
    var showDone by remember { mutableStateOf(false) }
    fun save(f: (List<com.qshlab.folio.data.Todo>) -> List<com.qshlab.folio.data.Todo>) { sums.saveTodos(pid, f(sums.todos(pid))); v++ }
    val open = items.filter { it.status == "open" }; val done = items.filter { it.status != "open" }
    SectionTitle("To-dos · ${open.size} open")
    for (x in open) Row(verticalAlignment = Alignment.Top) {
        Checkbox(false, { save { l -> l.map { if (it.id == x.id) it.copy(status = "done", touched = true, updated = now()) else it } } })
        Column(Modifier.padding(top = 12.dp)) {
            Text(x.text)
            Hint(listOfNotNull(x.owner.takeIf { it.isNotEmpty() }, x.due.takeIf { it.isNotEmpty() }?.let { "due $it" }, x.from?.meeting?.let { titles[it] }).joinToString(" · "))
        }
    }
    OutlinedTextField(newTodo, { newTodo = it }, Modifier.fillMaxWidth(), placeholder = { Text("Add a to-do") }, singleLine = true,
        trailingIcon = { TextButton({ save { it + com.qshlab.folio.data.Todo(java.util.UUID.randomUUID().toString().take(6), newTodo.trim(), touched = true, created = now()) }; newTodo = "" }, enabled = newTodo.isNotBlank()) { Text("Add") } })
    if (done.isNotEmpty()) Hint("${if (showDone) "▾" else "▸"} ${done.size} done", Modifier.clickable { showDone = !showDone }.padding(vertical = 8.dp))
    if (showDone) for (x in done) Row(verticalAlignment = Alignment.Top) {
        Checkbox(true, { save { l -> l.map { if (it.id == x.id) it.copy(status = "open", touched = true) else it } } })
        Column(Modifier.padding(top = 12.dp)) {
            Text(x.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            x.closedBy?.meeting?.let { Hint("closed in ${titles[it] ?: "a meeting"}") }
        }
    }
}
