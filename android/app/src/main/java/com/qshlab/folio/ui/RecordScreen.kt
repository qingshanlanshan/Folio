package com.qshlab.folio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.qshlab.folio.FolioApp
import com.qshlab.folio.data.UNSORTED

/** The Record tab: pick where it's saved (or leave it in Unsorted), then record or import. While recording,
 *  the project can still be changed; the recording is filed there when it stops. */
@Composable
fun RecordScreen(nav: NavHostController) {
    val app = FolioApp.instance
    val prefs = LocalContext.current.getSharedPreferences("settings", 0)
    val version by dataVersion.collectAsState()
    val rec by app.recorder.state.collectAsState()
    val projects = remember(version) { app.store.listProjects() }
    var chosen by remember { mutableStateOf(prefs.getString("recordTo", UNSORTED)!!) }
    val pid = chosen.takeIf { id -> projects.any { it.id == id } } ?: UNSORTED
    val live = rec

    TabScreen(nav, "Record") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SetupCard(nav)
            ProjectPicker(projects.map { it.id to it.meta.name }, live?.target ?: pid) { id ->
                if (live != null) app.recorder.saveTo(id)
                else { chosen = id; prefs.edit().putString("recordTo", id).apply() }
            }
            if (live != null) {
                LiveCard(live, full = true)
                if (live.target != live.pid) Hint("It's saved to the project you picked when you stop.")
            } else {
                Capture(pid)
                Hint(if (pid == UNSORTED) "Unsorted keeps recordings until you file them: open the recording, then its menu → Move to project. In a project, summaries use its notes and earlier meetings."
                     else "Folio transcribes it with speakers when you stop, and summarizes it with this project's notes and earlier meetings.")
            }
        }
    }
}

@Composable
private fun ProjectPicker(options: List<Pair<String, String>>, selected: String, pick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint("Save to", Modifier.padding(end = 10.dp))
        Box {
            OutlinedButton({ open = true }) {
                Text((options.firstOrNull { it.first == selected }?.second ?: "Unsorted") + "  ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(open, { open = false }) {
                for ((id, name) in options) DropdownMenuItem({ Text(name) }, { open = false; pick(id) })
            }
        }
    }
}
