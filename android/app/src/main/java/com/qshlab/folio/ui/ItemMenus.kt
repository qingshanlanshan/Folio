package com.qshlab.folio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.qshlab.folio.FolioApp
import com.qshlab.folio.data.Person
import com.qshlab.folio.data.UNSORTED

/** One choice in a long-press menu. */
data class MenuAction(val label: String, val danger: Boolean = false, val run: () -> Unit)

/** A card that opens on tap and shows [actions] on long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MenuCard(onClick: () -> Unit, actions: List<MenuAction>, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = {
            if (actions.isNotEmpty()) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open = true }
        }), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) { Column(content = content) }
        DropdownMenu(open, { open = false }) {
            for (a in actions) DropdownMenuItem({ Text(a.label, color = if (a.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                { open = false; a.run() })
        }
    }
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

/** "Same person as…": pick another person, confirm, merge into them. [done] gets the person kept. */
@Composable
fun mergePersonAction(id: String, name: String, done: (String) -> Unit = {}): MenuAction {
    val app = FolioApp.instance
    var picking by remember { mutableStateOf(false) }
    var into by remember { mutableStateOf<Person?>(null) }
    if (picking) PersonPickerDialog("$name is the same person as", id, { picking = false }) { into = it }
    into?.let { o ->
        ConfirmDialog("Merge $name into ${o.name}?", "Their recordings, voice samples and notes move to ${o.name}, and “$name” is kept as another name.", "Merge", { into = null }) {
            runCatching { app.engine.people.mergePeople(id, o.id) }.onSuccess { changed(); done(o.id) }.onFailure { app.lastError.value = it.message }
        }
    }
    return MenuAction("Same person as…") { picking = true }
}

/** The long-press choices for a recording, with their dialogs. Returns the menu to show. */
@Composable
fun recordingActions(nav: NavHostController, pid: String, mid: String, title: String, busy: Boolean): List<MenuAction> {
    val app = FolioApp.instance
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (renaming) NameDialog("Rename", "Title", title, { renaming = false }) { n ->
        app.store.updateMeeting(pid, mid) { it.copy(title = n, titleSource = "user") }; renaming = false; changed()
    }
    if (moving) ProjectPickerDialog(if (pid == UNSORTED) "Move to project" else "Move to", pid, { moving = false }) { to ->
        runCatching { app.engine.move(pid, mid, to) }.onFailure { app.lastError.value = it.message }
    }
    if (deleting) ConfirmDialog("Delete “$title”?", "The recording and its transcript are moved to Folio's trash.", "Delete", { deleting = false }) {
        app.engine.tasks.value.firstOrNull { it.key == "$pid/$mid" }?.let { app.engine.stop(it.key) }
        app.store.moveToTrash(app.store.meetingDir(pid, mid)); changed()
    }
    return listOfNotNull(
        MenuAction("Open") { nav.navigate("meeting/${enc(pid)}/${enc(mid)}?t=-1") },
        MenuAction("Rename") { renaming = true },
        MenuAction(if (pid == UNSORTED) "Move to project…" else "Move to…") { moving = true }.takeIf { !busy },
        MenuAction("Delete…", danger = true) { deleting = true },
    )
}

/** The long-press choices for a project (Unsorted can't be renamed or deleted). */
@Composable
fun projectActions(nav: NavHostController, pid: String, name: String, recordings: Int): List<MenuAction> {
    val app = FolioApp.instance
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (renaming) NameDialog("Rename project", "Name", name, { renaming = false }) { n ->
        app.store.saveProject(pid, app.store.project(pid).copy(name = n)); renaming = false; changed()
    }
    if (deleting) ConfirmDialog("Delete “$name”?", if (recordings > 0) "Its $recordings recording${if (recordings == 1) "" else "s"}, transcripts and summaries are moved to Folio's trash." else "It's moved to Folio's trash.", "Delete", { deleting = false }) {
        app.store.moveToTrash(app.store.projectDir(pid)); changed()
    }
    return listOf(MenuAction("Open") { nav.navigate("project/${enc(pid)}") }) +
        if (pid == UNSORTED) emptyList() else listOf(MenuAction("Rename") { renaming = true }, MenuAction("Delete…", danger = true) { deleting = true })
}

/** The long-press choices for a person. */
@Composable
fun personActions(nav: NavHostController, id: String, name: String): List<MenuAction> {
    val app = FolioApp.instance
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (renaming) NameDialog("Rename", "Name", name, { renaming = false }) { n ->
        app.engine.people.save(app.engine.people.get(id).copy(name = n)); renaming = false; changed()
    }
    if (deleting) ConfirmDialog("Remove $name?", "Recordings keep their text; the speaker just becomes unnamed.", "Remove", { deleting = false }) {
        app.engine.people.delete(id); changed()
    }
    val merge = mergePersonAction(id, name)
    return listOf(MenuAction("Open") { nav.navigate("person/${enc(id)}") }, MenuAction("Rename") { renaming = true },
        merge, MenuAction("Remove…", danger = true) { deleting = true })
}
