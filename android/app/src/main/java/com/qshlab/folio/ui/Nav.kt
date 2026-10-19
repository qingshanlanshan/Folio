package com.qshlab.folio.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qshlab.folio.FolioApp
import com.qshlab.folio.MainActivity
import com.qshlab.folio.speech.MODELS
import com.qshlab.folio.update.Updates
import kotlinx.coroutines.flow.MutableStateFlow

/** Something changed on disk that screens should re-read (they also re-read when tasks change). */
val dataVersion = com.qshlab.folio.data.Changes.version
fun changed() = com.qshlab.folio.data.Changes.bump()

fun enc(s: String): String = Uri.encode(s)

@Composable
fun FolioNav(activity: MainActivity) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val err by FolioApp.instance.lastError.collectAsState()
    LaunchedEffect(err) { err?.let { snack.showSnackbar(it); FolioApp.instance.lastError.value = null } }
    LaunchedEffect(Unit) { Updates.check(ctx) }
    val open by activity.openMeeting
    LaunchedEffect(open) {
        open?.let { (p, m) ->
            nav.navigate("meeting/${enc(p)}/${enc(m)}") { launchSingleTop = true }
            activity.openMeeting.value = null
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        NavHost(nav, "home", Modifier.padding(PaddingValues(bottom = pad.calculateBottomPadding()))) {
            composable("home") { Home(nav, activity) }
            composable("project/{pid}") { ProjectScreen(nav, it.arguments!!.getString("pid")!!) }
            composable("meeting/{pid}/{mid}?t={t}", listOf(navArgument("t") { nullable = true; defaultValue = null })) {
                MeetingScreen(nav, it.arguments!!.getString("pid")!!, it.arguments!!.getString("mid")!! + "?t=" + (it.arguments?.getString("t") ?: "-1"))
            }
            composable("people") { PeopleScreen(nav) }
            composable("person/{id}") { PersonScreen(nav, it.arguments!!.getString("id")!!) }
            composable("search?q={q}&person={person}", listOf(navArgument("q") { nullable = true; defaultValue = "" }, navArgument("person") { nullable = true; defaultValue = null })) { SearchScreen(nav, it.arguments?.getString("q").orEmpty(), it.arguments?.getString("person")) }
            composable("settings") { SettingsScreen(nav) }
            composable("activity") { ActivityScreen(nav) }
            composable("speedtest") { Screen(nav, "Speed & battery test") { SpeedTestScreen() } }
        }
    }
}

/** A plain screen with a back arrow and a title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(nav: NavHostController, title: String, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Heading) },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { actions() },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(nav: NavHostController, activity: MainActivity) {
    val app = FolioApp.instance
    val version by dataVersion.collectAsState()
    val tasks by app.engine.tasks.collectAsState()
    val installed by app.models.installed.collectAsState()
    val projects = remember(version, tasks.size) { app.store.listProjects() }
    var adding by remember { mutableStateOf(false) }
    val shared by activity.shared
    val update by Updates.available.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Folio", fontFamily = Heading, fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton({ nav.navigate("search?q=&person=") }) { Icon(Icons.Default.Search, "Search") }
                    IconButton({ nav.navigate("people") }) { Icon(Icons.Default.People, "People") }
                    IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New project") }) },
        bottomBar = { StatusBar(nav, Modifier.navigationBarsPadding().padding(12.dp)) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (MODELS.filter { it.required }.any { it.id !in installed }) item {
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("settings") }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Set up speech recognition", fontWeight = FontWeight.SemiBold)
                        Hint("Folio transcribes on this phone. Download the speech models once (about 780 MB, use Wi-Fi) in Settings.")
                    }
                }
            }
            update?.let { u -> item {
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("settings") }) {
                    Column(Modifier.padding(14.dp)) { Text("Folio ${u.version} is available", fontWeight = FontWeight.SemiBold); Hint("Open Settings to update.") }
                }
            } }
            if (projects.isEmpty()) item {
                Hint("Create a project — a research group, a course, a client — then record or import meetings into it.", Modifier.padding(24.dp))
            }
            items(projects, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("project/${enc(p.id)}") }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.meta.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Hint("${p.meetings} recording${if (p.meetings == 1) "" else "s"}")
                    }
                }
            }
        }
    }
    if (adding) NameDialog("New project", "Project or course name", "", { adding = false }) { name ->
        val id = app.store.createProject(name); changed(); adding = false; nav.navigate("project/${enc(id)}")
    }
    shared?.let { uri ->
        AlertDialog(onDismissRequest = { activity.shared.value = null }, title = { Text("Import into which project?") },
            text = {
                Column { for (p in projects) Text(p.meta.name, Modifier.fillMaxWidth().clickable {
                    importInto(app, p.id, uri); activity.shared.value = null; nav.navigate("project/${enc(p.id)}")
                }.padding(vertical = 12.dp)) }
            },
            confirmButton = { TextButton({ activity.shared.value = null }) { Text("Cancel") } })
    }
}

@Composable
fun NameDialog(title: String, label: String, initial: String, dismiss: () -> Unit, done: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton({ if (text.isNotBlank()) done(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

/** Always-visible status: recording, what's being processed, or idle. Tap for Activity. */
@Composable
fun StatusBar(nav: NavHostController, modifier: Modifier = Modifier) {
    val app = FolioApp.instance
    val rec by app.recorder.state.collectAsState()
    val tasks by app.engine.tasks.collectAsState()
    val loaded by app.engine.loaded.collectAsState()
    Card(modifier.fillMaxWidth().clickable { nav.navigate("activity") }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val task = tasks.firstOrNull { it.running }
            when {
                rec != null -> { Dot(RecRed); Spacer(Modifier.width(8.dp)); Text("Recording ${clock(rec!!.elapsed)}", color = RecRed, fontWeight = FontWeight.SemiBold) }
                task != null -> Text((if (task.stopping) "Stopping…" else (STEP_LABEL[task.step] ?: task.step) +
                    if (task.progress > 0) " ${(task.progress * 100).toInt()}%" else "") + if (tasks.size > 1) "  +${tasks.size - 1} waiting" else "")
                else -> Hint("Idle" + if (loaded.isNotEmpty()) " · speech model in memory" else "")
            }
            Spacer(Modifier.weight(1f))
            Hint("Activity ›")
        }
    }
}
