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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qshlab.folio.FolioApp
import com.qshlab.folio.MainActivity
import com.qshlab.folio.data.UNSORTED
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

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val shared by activity.shared
    Scaffold(snackbarHost = { SnackbarHost(snack) },
        bottomBar = { if (route in TABS.map { it.route }) BottomBar(nav, route) }) { pad ->
        NavHost(nav, "record", Modifier.padding(PaddingValues(bottom = pad.calculateBottomPadding()))) {
            composable("record") { RecordScreen(nav) }
            composable("home") { Home(nav) }
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
    // A file shared into Folio from another app: ask which project it goes to.
    shared?.let { uri ->
        val app = FolioApp.instance
        val projects = remember { app.store.listProjects() }
        AlertDialog(onDismissRequest = { activity.shared.value = null }, title = { Text("Import into which project?") },
            text = {
                Column { for (p in projects) Text(p.meta.name, Modifier.fillMaxWidth().clickable {
                    importInto(app, p.id, uri); activity.shared.value = null; nav.navigate("project/${enc(p.id)}")
                }.padding(vertical = 12.dp)) }
            },
            confirmButton = { TextButton({ activity.shared.value = null }) { Text("Cancel") } })
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val TABS = listOf(Tab("record", "Record", Icons.Default.FiberManualRecord), Tab("home", "Projects", Icons.Default.Folder),
    Tab("people", "People", Icons.Default.People))

/** Record · Projects · People, with what Folio is doing just above. */
@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    Column {
        StatusBar(nav, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            for (t in TABS) NavigationBarItem(selected = route == t.route, label = { Text(t.label) },
                icon = { Icon(t.icon, null, tint = if (t.route == "record") RecRed else LocalContentColor.current) },
                onClick = {
                    if (route != t.route) nav.navigate(t.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
        }
    }
}

/** A top-level screen (one of the tabs): title, search and settings, no back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScreen(nav: NavHostController, title: String, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title, fontFamily = Heading, fontWeight = FontWeight.SemiBold) },
            actions = {
                actions()
                IconButton({ nav.navigate("search?q=&person=") }) { Icon(Icons.Default.Search, "Search") }
                IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        content()
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

@Composable
fun Home(nav: NavHostController) {
    val app = FolioApp.instance
    val version by dataVersion.collectAsState()
    val tasks by app.engine.tasks.collectAsState()
    val projects = remember(version, tasks.size) { app.store.listProjects() }
    var adding by remember { mutableStateOf(false) }
    val update by Updates.available.collectAsState()

    TabScreen(nav, "Projects", actions = { TextButton({ adding = true }) { Text("New") } }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SetupCard(nav) }
            update?.let { u -> item {
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("settings") }) {
                    Column(Modifier.padding(14.dp)) { Text("Folio ${u.version} is available", fontWeight = FontWeight.SemiBold); Hint("Open Settings to update.") }
                }
            } }
            items(projects, key = { it.id }) { p ->
                val inbox = p.id == UNSORTED
                MenuCard({ nav.navigate("project/${enc(p.id)}") }, projectActions(nav, p.id, p.meta.name, p.meetings)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.meta.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                            color = if (inbox) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        Hint("${p.meetings} recording${if (p.meetings == 1) "" else "s"}")
                    }
                }
            }
            if (projects.size <= 1) item {
                Hint("Create a project — a research group, a course, a client — and file recordings in it. Recordings you don't file go to Unsorted.", Modifier.padding(24.dp))
            }
        }
    }
    if (adding) NameDialog("New project", "Project or course name", "", { adding = false }) { name ->
        val id = app.store.createProject(name); changed(); adding = false; nav.navigate("project/${enc(id)}")
    }
}

/** Shown until the speech models are downloaded. */
@Composable
fun SetupCard(nav: NavHostController) {
    val installed by FolioApp.instance.models.installed.collectAsState()
    if (MODELS.filter { it.required }.all { it.id in installed }) return
    Card(Modifier.fillMaxWidth().clickable { nav.navigate("settings") }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("Set up speech recognition", fontWeight = FontWeight.SemiBold)
            Hint("Folio transcribes on this phone. Download the speech models once (about 780 MB, use Wi-Fi) in Settings.")
        }
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
