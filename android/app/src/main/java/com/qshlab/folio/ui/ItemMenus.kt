package com.qshlab.folio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.qshlab.folio.FolioApp
import com.qshlab.folio.data.Person
import com.qshlab.folio.data.UNSORTED

// ---------- choosing several items: a long-press starts it, then taps pick instead of open

class Selection {
    var on by mutableStateOf(false)
        private set
    val ids = mutableStateListOf<String>()
    fun start(id: String) { ids.clear(); ids += id; on = true }
    fun toggle(id: String) { if (id in ids) ids -= id else ids += id }
    fun all(all: List<String>) { ids.clear(); ids += all }
    fun none() = ids.clear()
    fun clear() { ids.clear(); on = false }
}

@Composable
fun rememberSelection() = remember { Selection() }

/** A card that opens on tap. A long-press starts choosing (when [sel] is given); while choosing, taps pick. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PickCard(id: String, sel: Selection?, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val haptic = LocalHapticFeedback.current
    val choosing = sel?.on == true
    val picked = choosing && id in sel!!.ids
    Card(Modifier.fillMaxWidth().combinedClickable(
        onClick = { if (choosing) sel!!.toggle(id) else onClick() },
        onLongClick = if (sel == null) null else ({
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (sel.on) sel.toggle(id) else sel.start(id)
        })),
        colors = CardDefaults.cardColors(if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        border = if (picked) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null) {
        if (choosing) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(picked, { sel!!.toggle(id) }, Modifier.padding(start = 2.dp))
            Column(Modifier.weight(1f), content = content)
        } else Column(content = content)
    }
}

/** One thing to do with what's chosen. With an icon it sits in the bar; without, in its ⋮ menu. */
data class SelAction(val label: String, val icon: ImageVector? = null, val enabled: Boolean = true, val danger: Boolean = false, val run: () -> Unit)

/** Replaces the top bar while choosing: ✕, how many, select all, then the actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(sel: Selection, all: List<String>, actions: List<SelAction>) {
    BackHandler { sel.clear() }
    var more by remember { mutableStateOf(false) }
    val every = all.isNotEmpty() && sel.ids.size == all.size
    val any = sel.ids.isNotEmpty()
    TopAppBar(title = { Text("${sel.ids.size} selected") },
        navigationIcon = { IconButton({ sel.clear() }) { Icon(Icons.Default.Close, "Done") } },
        actions = {
            IconButton({ if (every) sel.none() else sel.all(all) }) {
                Icon(if (every) Icons.Default.Deselect else Icons.Default.SelectAll, if (every) "Deselect all" else "Select all")
            }
            for (a in actions.filter { it.icon != null }) IconButton(a.run, enabled = any && a.enabled) {
                Icon(a.icon!!, a.label, tint = if (a.danger && any) MaterialTheme.colorScheme.error else LocalContentColor.current)
            }
            val rest = actions.filter { it.icon == null }
            if (rest.isNotEmpty()) Box {
                IconButton({ more = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(more, { more = false }) {
                    for (a in rest) DropdownMenuItem({ Text(a.label) }, { more = false; a.run() }, enabled = any && a.enabled)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer))
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** What can be done with the chosen recordings of project [pid], with their dialogs. */
@Composable
fun recordingSelection(pid: String, sel: Selection): List<SelAction> {
    val app = FolioApp.instance
    val engine = app.engine
    val tasks by engine.tasks.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val ids = sel.ids.toList()
    val busy = { mid: String -> tasks.any { it.key == "$pid/$mid" } || runCatching { app.store.meeting(pid, mid).status == "recording" }.getOrDefault(false) }
    val title = { mid: String -> runCatching { app.store.meeting(pid, mid).title }.getOrDefault(mid) }
    if (renaming && ids.size == 1) NameDialog("Rename", "Title", title(ids[0]), { renaming = false }) { n ->
        app.store.updateMeeting(pid, ids[0]) { it.copy(title = n) }; renaming = false; sel.clear(); changed()
    }
    if (moving) ProjectPickerDialog(if (pid == UNSORTED) "Move to project" else "Move to", pid, { moving = false }) { to ->
        val skipped = ids.filter(busy)
        for (mid in ids - skipped.toSet()) runCatching { engine.move(pid, mid, to) }.onFailure { app.lastError.value = it.message }
        if (skipped.isNotEmpty()) app.lastError.value = "${plural(skipped.size, "recording")} still being processed stayed here."
        sel.clear(); changed()
    }
    if (deleting) ConfirmDialog(if (ids.size == 1) "Delete “${title(ids[0])}”?" else "Delete ${ids.size} recordings?",
        "They're moved to Folio's trash, with their transcripts.", "Delete", { deleting = false }) {
        for (mid in ids) {
            engine.stop("$pid/$mid")
            runCatching { app.store.moveToTrash(app.store.meetingDir(pid, mid)) }
        }
        sel.clear(); changed()
    }
    val canSummarize = engine.claude.key != null && ids.any { !busy(it) && app.store.transcript(pid, it) != null }
    return listOf(
        SelAction("Rename", Icons.Default.Edit, enabled = ids.size == 1) { renaming = true },
        SelAction(if (pid == UNSORTED) "Move to project" else "Move to", Icons.AutoMirrored.Filled.DriveFileMove, enabled = ids.any { !busy(it) }) { moving = true },
        SelAction("Delete", Icons.Default.Delete, danger = true) { deleting = true },
        SelAction("Summarize", enabled = canSummarize) {
            for (mid in ids) if (!busy(mid) && app.store.transcript(pid, mid) != null) engine.submit(pid, mid, "summarize")
            sel.clear()
        },
        SelAction("Stop processing", enabled = ids.any { id -> tasks.any { it.key == "$pid/$id" } }) {
            for (mid in ids) engine.stop("$pid/$mid")
            sel.clear()
        },
    )
}

/** What can be done with the chosen projects (Unsorted is never chosen). */
@Composable
fun projectSelection(sel: Selection): List<SelAction> {
    val app = FolioApp.instance
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val ids = sel.ids.toList()
    val name = { pid: String -> runCatching { app.store.project(pid).name }.getOrDefault(pid) }
    if (renaming && ids.size == 1) NameDialog("Rename project", "Name", name(ids[0]), { renaming = false }) { n ->
        app.store.saveProject(ids[0], app.store.project(ids[0]).copy(name = n)); renaming = false; sel.clear(); changed()
    }
    if (deleting) {
        val n = ids.sumOf { pid -> runCatching { app.store.listMeetings(pid).size }.getOrDefault(0) }
        ConfirmDialog(if (ids.size == 1) "Delete “${name(ids[0])}”?" else "Delete ${ids.size} projects?",
            if (n > 0) "${plural(n, "recording")}, with transcripts and summaries, are moved to Folio's trash." else "They're moved to Folio's trash.",
            "Delete", { deleting = false }) {
            for (pid in ids) runCatching { app.store.moveToTrash(app.store.projectDir(pid)) }
            sel.clear(); changed()
        }
    }
    return listOf(
        SelAction("Rename", Icons.Default.Edit, enabled = ids.size == 1) { renaming = true },
        SelAction("Delete", Icons.Default.Delete, danger = true) { deleting = true },
    )
}

/** What can be done with the chosen people. Several can be marked as one person. */
@Composable
fun personSelection(sel: Selection): List<SelAction> {
    val app = FolioApp.instance
    val ppl = app.engine.people
    var renaming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf<Person?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val ids = sel.ids.toList()
    val chosen = ids.mapNotNull { ppl.find(it) }
    if (renaming && chosen.size == 1) NameDialog("Rename", "Name", chosen[0].name, { renaming = false }) { n ->
        ppl.save(chosen[0].copy(name = n)); renaming = false; sel.clear(); changed()
    }
    if (merging) AlertDialog(onDismissRequest = { merging = false }, title = { Text("Same person. Keep which name?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (p in chosen) Text(p.name, Modifier.fillMaxWidth().clickable { merging = false; keep = p }.padding(vertical = 12.dp))
            }
        },
        confirmButton = { TextButton({ merging = false }) { Text("Cancel") } })
    keep?.let { k ->
        val rest = chosen.filter { it.id != k.id }
        ConfirmDialog("Merge into ${k.name}?", "${rest.joinToString(", ") { it.name }}: recordings, voice samples and notes move to ${k.name}; the other names are kept as other names.",
            "Merge", { keep = null }) {
            for (p in rest) runCatching { ppl.mergePeople(p.id, k.id) }.onFailure { app.lastError.value = it.message }
            sel.clear(); changed()
        }
    }
    if (deleting) ConfirmDialog(if (chosen.size == 1) "Remove ${chosen[0].name}?" else "Remove ${chosen.size} people?",
        "Recordings keep their text; the speakers just become unnamed.", "Remove", { deleting = false }) {
        for (p in chosen) runCatching { ppl.delete(p.id) }
        sel.clear(); changed()
    }
    return listOf(
        SelAction("Rename", Icons.Default.Edit, enabled = ids.size == 1) { renaming = true },
        SelAction("Remove", Icons.Default.Delete, danger = true) { deleting = true },
        SelAction("Same person (merge)", enabled = ids.size > 1) { merging = true },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, action: String, dismiss: () -> Unit, confirm: () -> Unit) =
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton({ confirm(); dismiss() }) { Text(action, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })

/** Pick a project (not [except]); Unsorted is listed first. */
@Composable
fun ProjectPickerDialog(title: String, except: String, dismiss: () -> Unit, pick: (String) -> Unit) {
    val projects = remember { FolioApp.instance.store.listProjects().filter { it.id != except } }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (p in projects) Text(p.meta.name, Modifier.fillMaxWidth().clickable { dismiss(); pick(p.id) }.padding(vertical = 12.dp))
                if (projects.isEmpty()) Hint("Create a project first.")
            }
        },
        confirmButton = { TextButton(dismiss) { Text("Cancel") } })
}

/** Pick a person (not [except]). */
@Composable
fun PersonPickerDialog(title: String, except: String, dismiss: () -> Unit, pick: (Person) -> Unit) {
    val people = remember { FolioApp.instance.engine.people.all().filter { it.id != except } }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (p in people) Text(p.name, Modifier.fillMaxWidth().clickable { dismiss(); pick(p) }.padding(vertical = 12.dp))
                if (people.isEmpty()) Hint("There's no one else yet.")
            }
        },
        confirmButton = { TextButton(dismiss) { Text("Cancel") } })
}

/** "Same person as…" on a person's page: pick another person, confirm, merge into them. [done] gets the person kept. */
@Composable
fun mergePersonAction(id: String, name: String, done: (String) -> Unit = {}): SelAction {
    val app = FolioApp.instance
    var picking by remember { mutableStateOf(false) }
    var into by remember { mutableStateOf<Person?>(null) }
    if (picking) PersonPickerDialog("$name is the same person as", id, { picking = false }) { into = it }
    into?.let { o ->
        ConfirmDialog("Merge $name into ${o.name}?", "Their recordings, voice samples and notes move to ${o.name}, and “$name” is kept as another name.", "Merge", { into = null }) {
            runCatching { app.engine.people.mergePeople(id, o.id) }.onSuccess { changed(); done(o.id) }.onFailure { app.lastError.value = it.message }
        }
    }
    return SelAction("Same person as…") { picking = true }
}
