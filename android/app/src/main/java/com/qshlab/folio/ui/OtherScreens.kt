package com.qshlab.folio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.qshlab.folio.FolioApp
import com.qshlab.folio.ai.CLAUDE_MODELS
import com.qshlab.folio.ai.Search
import com.qshlab.folio.speech.MODELS
import com.qshlab.folio.sync.Sync
import com.qshlab.folio.update.Updates
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- people

@Composable
fun PeopleScreen(nav: NavHostController) {
    val app = FolioApp.instance
    val version by dataVersion.collectAsState()
    val people = remember(version) { app.engine.people.all() }
    var adding by remember { mutableStateOf(false) }
    TabScreen(nav, "People", actions = { TextButton({ adding = true }) { Text("Add") } }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Hint("Shared across projects. Name a speaker in any recording to add them; their voice is recognised next time.") }
            items(people, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("person/${enc(p.id)}") }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.name); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name + if (p.me) " (me)" else "", fontWeight = FontWeight.SemiBold)
                            Hint("${p.projects.size} project${if (p.projects.size == 1) "" else "s"}" + if (p.notes.isNotEmpty()) " · ${p.notes.take(60)}" else "")
                        }
                        Hint(if (p.voices.isEmpty()) "no voice yet" else "${p.voices.size} voice sample${if (p.voices.size > 1) "s" else ""}")
                    }
                }
            }
        }
    }
    if (adding) NameDialog("Add a person", "Name", "", { adding = false }) { n -> val p = app.engine.people.create(n); adding = false; changed(); nav.navigate("person/${enc(p.id)}") }
}

@Composable
fun PersonScreen(nav: NavHostController, id: String) {
    val app = FolioApp.instance
    val ppl = app.engine.people
    var v by remember { mutableIntStateOf(0) }
    val p = remember(v) { ppl.find(id) }
    if (p == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val projects = remember { app.store.listProjects() }
    val appearances = remember(v) { ppl.appearances(id) }
    var notes by remember(p.notes) { mutableStateOf(p.notes) }
    var aliases by remember(p.aliases) { mutableStateOf(p.aliases.joinToString(", ")) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    fun save(np: com.qshlab.folio.data.Person) { ppl.save(np); v++; changed() }
    Screen(nav, p.name, actions = { TextButton({ renaming = true }) { Text("Rename") } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(if (p.voices.isEmpty()) "No voice sample yet: name them in a recording" else "${p.voices.size} voice sample${if (p.voices.size > 1) "s" else ""}", Modifier.weight(1f))
                Checkbox(p.me, { save(p.copy(me = it)) }); Hint("This is me")
            }
            Text("Projects", fontWeight = FontWeight.SemiBold)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (pr in projects) FilterChip(pr.id in p.projects, { save(p.copy(projects = if (pr.id in p.projects) p.projects - pr.id else p.projects + pr.id)) }, { Text(pr.meta.name) })
            }
            OutlinedTextField(aliases, { aliases = it }, Modifier.fillMaxWidth(), label = { Text("Also known as (comma separated)") })
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Notes — role, what they work on. Claude reads this.") })
            Row {
                Button({ save(p.copy(notes = notes, aliases = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() })) },
                    enabled = notes != p.notes || aliases != p.aliases.joinToString(", ")) { Text("Save") }
                Spacer(Modifier.weight(1f))
                TextButton({ removing = true }) { Text("Remove person", color = MaterialTheme.colorScheme.error) }
            }
            SectionTitle("Spoke in")
            if (appearances.isEmpty()) Hint("Not in any recording yet.")
            for (a in appearances) Card(Modifier.fillMaxWidth().clickable { nav.navigate("meeting/${enc(a.project)}/${enc(a.meeting)}") }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp)) { Text(a.title, fontWeight = FontWeight.SemiBold); Hint("${a.projectName} · ${whenText(a.date)} · ${clock(a.seconds)} spoken") }
            }
            if (appearances.isNotEmpty()) OutlinedButton({ nav.navigate("search?q=&person=${enc(id)}") }) { Text("Everything ${p.name} said →") }
        }
    }
    if (renaming) NameDialog("Rename", "Name", p.name, { renaming = false }) { n -> save(p.copy(name = n)); renaming = false }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("Remove ${p.name}?") },
        text = { Text("Recordings keep their text; the speaker just becomes unnamed.") },
        confirmButton = { TextButton({ ppl.delete(id); removing = false; changed(); nav.popBackStack() }) { Text("Remove") } },
        dismissButton = { TextButton({ removing = false }) { Text("Cancel") } })
}

// ---------- search

@Composable
fun SearchScreen(nav: NavHostController, initial: String, person: String?) {
    val app = FolioApp.instance
    var q by remember { mutableStateOf(initial) }
    var result by remember { mutableStateOf<Search.Result?>(null) }
    val scope = rememberCoroutineScope()
    val who = remember(person) { person?.takeIf { it.isNotEmpty() }?.let { app.engine.people.find(it) } }
    fun run() { scope.launch { result = withContext(Dispatchers.IO) { app.engine.search.search(q, who?.id) } } }
    LaunchedEffect(Unit) { if (who != null || q.isNotEmpty()) run() }
    Screen(nav, who?.let { "Said by ${it.name}" } ?: "Search") {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (who == null) OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Words or names") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { run() }))
            result?.let { r ->
                Hint("${r.total} passage${if (r.total == 1) "" else "s"}", Modifier.padding(vertical = 8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(r.people) { p -> Row(Modifier.clickable { nav.navigate("person/${enc(p.id)}") }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.name); Spacer(Modifier.width(8.dp)); Text(p.name, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(12.dp))
                        Text("What ${p.name} said", Modifier.clickable { nav.navigate("search?q=&person=${enc(p.id)}") }, color = MaterialTheme.colorScheme.primary)
                    } }
                    items(r.hits) { h ->
                        Card(Modifier.fillMaxWidth().clickable { nav.navigate("meeting/${enc(h.project)}/${enc(h.meeting)}?t=${h.start}") }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Row { Text(h.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Hint(clock(h.start)) }
                                Hint("${h.projectName} · ${whenText(h.date)}")
                                Text("${h.speaker}: ${h.snippet}", fontFamily = Heading, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- settings

@Composable
fun SettingsScreen(nav: NavHostController) {
    val app = FolioApp.instance
    val ctx = LocalContext.current
    val claude = app.engine.claude
    val scope = rememberCoroutineScope()
    var v by remember { mutableIntStateOf(0) }
    var key by remember { mutableStateOf("") }
    var test by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val installed by app.models.installed.collectAsState()
    val downloads by app.models.downloads.collectAsState()
    val update by Updates.available.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf<Float?>(null) }
    val prefs = ctx.getSharedPreferences("settings", 0)
    val synced by dataVersion.collectAsState()      // sync may bring the API key from another device
    val month = remember(v, synced) { claude.thisMonth() }

    Screen(nav, "Settings") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Claude", fontFamily = Heading, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Hint("Summaries, transcript fixes and questions are written by Claude. Only text (transcripts and project notes) is sent to Anthropic; audio stays on this phone.")
            Text("Model", fontWeight = FontWeight.SemiBold)
            for (m in CLAUDE_MODELS) Row(Modifier.fillMaxWidth().clickable { claude.modelId = m.id; v++ }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(claude.modelId == m.id, { claude.modelId = m.id; v++ })
                Column { Text(m.name); Hint("$${m.inPrice} / $${m.outPrice} per million tokens") }
            }
            Text("API key", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Hint(claude.keyHint?.let { "Saved ($it)" } ?: "Not set — get one at console.anthropic.com → API keys.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(key, { key = it }, Modifier.weight(1f), placeholder = { Text(if (claude.key != null) "Replace with a new key" else "sk-ant-…") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Spacer(Modifier.width(8.dp))
                Button({ if (key.trim().startsWith("sk-ant-")) { claude.key = key.trim(); key = ""; test = null; v++ } else test = "That doesn't look like an Anthropic API key (they start with sk-ant-)." },
                    enabled = key.isNotBlank()) { Text("Save") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ testing = true; test = null; scope.launch {
                    test = withContext(Dispatchers.IO) { runCatching { "✓ ${claude.model.name} answered: ${claude.test()}" }.getOrElse { it.message } }
                    testing = false; v++
                } }, enabled = claude.key != null && !testing) { Text(if (testing) "Testing…" else "Test connection") }
                if (claude.key != null) TextButton({ claude.key = null; v++ }) { Text("Remove key", color = MaterialTheme.colorScheme.error) }
            }
            test?.let { Hint(it) }
            Hint("This month: ${month.requests} request${if (month.requests == 1) "" else "s"} · about $${"%.2f".format(month.usd)} (estimated; your Anthropic console has the exact bill)")

            SyncSection()

            Text("Speech models", fontFamily = Heading, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Hint("Transcription runs on this phone. Download once, on Wi-Fi.")
            for (m in MODELS) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(m.title, fontWeight = FontWeight.SemiBold); Hint("${m.purpose} · ${m.size / 1_000_000} MB") }
                        val d = downloads[m.id]
                        when {
                            m.id in installed -> TextButton({ app.models.delete(m.id) }) { Text("Delete") }
                            d != null && d.error == null -> TextButton({ app.models.cancel(m.id) }) { Text("Cancel") }
                            else -> Button({ app.models.download(m.id) }) { Text(if (d?.error != null) "Retry" else "Download") }
                        }
                    }
                    downloads[m.id]?.let { d -> if (d.error != null) Hint(d.error, color = MaterialTheme.colorScheme.error) else LinearProgressIndicator({ d.progress }, Modifier.fillMaxWidth().padding(top = 6.dp)) }
                }
            }
            TextButton({ nav.navigate("speedtest") }) { Text("Speed & battery test →") }

            Text("Notifications", fontFamily = Heading, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Hint("While recording or processing, Folio shows an ongoing notification (with Stop). When a transcript finishes and Folio isn't open, it tells you.")

            Text("Updates", fontFamily = Heading, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Hint("Installed: ${Updates.installedVersionName(ctx)}. New versions come from folio.qshlab.com.")
            var auto by remember { mutableStateOf(Updates.autoCheck(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Check automatically", Modifier.weight(1f)); Switch(auto, { auto = it; Updates.setAutoCheck(ctx, it) }) }
            update?.let { u ->
                Text("Folio ${u.version} is available (${"%.1f".format(u.size / 1048576.0)} MB).", fontWeight = FontWeight.SemiBold)
                u.notes?.let { Hint(it) }
                updating?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth()) }
                Button({ updating = 0f; scope.launch {
                    runCatching { Updates.install(ctx, Updates.download(ctx, u) { updating = it }) }.onFailure { app.lastError.value = "Update failed: ${it.message}" }
                    updating = null
                } }, enabled = updating == null) { Text("Download and install") }
            } ?: OutlinedButton({ checking = true; scope.launch { val r = Updates.check(ctx, force = true); checking = false; if (r == null) app.lastError.value = "Folio is up to date." } },
                enabled = !checking) { Text(if (checking) "Checking…" else "Check for updates") }
            Hint("Folio ${Updates.installedVersionName(ctx)} · speech by sherpa-onnx (Apache-2.0) with NVIDIA Parakeet (CC-BY-4.0) · test clip from the AMI corpus (CC BY 4.0).", Modifier.padding(top = 16.dp))
        }
    }
}

// ---------- sync

private fun ago(t: Double): String {
    val s = System.currentTimeMillis() / 1000.0 - t
    return when { s < 60 -> "just now"; s < 3600 -> "${(s / 60).toInt()} min ago"; s < 86400 -> "${(s / 3600).toInt()} h ago"; else -> whenText(t) }
}

/** Sync with the Mac through the user's own Cloudflare R2 bucket. Off by default. */
@Composable
fun SyncSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by Sync.config.collectAsState()
    val st by Sync.status.collectAsState()
    var mode by remember { mutableStateOf<String?>(null) }      // "paste" | "setup"
    var account by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("folio-sync") }
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var showCode by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }

    fun act(block: () -> Unit) {
        busy = true; err = null
        scope.launch {
            err = withContext(Dispatchers.IO) { runCatching { block(); null }.getOrElse { it.message ?: "Something went wrong." } }
            busy = false
            if (err == null) { mode = null; secret = ""; code = "" }
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { v -> act { Sync.join(v) } } }
    fun scan() = runCatching {
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
            .setOrientationLocked(false).setPrompt("Point at the code in Folio on your Mac: Settings → Sync → Code for another device"))
    }.onFailure { err = "Couldn't open the camera. Paste the code instead." }

    Text("Sync", fontFamily = Heading, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
    val c = cfg
    if (c == null) {
        Hint("Keep projects, recordings and people the same on this phone and your Mac, through your own Cloudflare R2 storage (free up to 10 GB). Everything is encrypted on the phone before upload, so Cloudflare can't read it.")
        when (mode) {
            null -> {
                Hint("On your Mac: Folio → Settings → Sync → “Code for another device”, then scan it here.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ scan() }, enabled = !busy) { Text(if (busy) "Joining…" else "Scan code") }
                    OutlinedButton({ mode = "paste"; err = null }) { Text("Paste code") }
                }
                TextButton({ mode = "setup"; err = null }) { Text("No Mac yet? Set up sync on this phone") }
            }
            "paste" -> {
                OutlinedTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), placeholder = { Text("folio1:…") }, minLines = 2,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ act { Sync.join(code) } }, enabled = !busy && code.isNotBlank()) { Text(if (busy) "Joining…" else "Join") }
                    TextButton({ mode = null; err = null }) { Text("Cancel") }
                }
            }
            "setup" -> {
                Hint("1. In the Cloudflare dashboard → R2, create a bucket, for example “folio-sync”.\n2. In R2 → Manage API tokens → Create API token: Object Read & Write, limited to that bucket. Copy the access key ID and secret.\n3. Fill these in. The account ID is on the R2 overview page.")
                OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("Account ID") }, singleLine = true)
                OutlinedTextField(bucket, { bucket = it }, Modifier.fillMaxWidth(), label = { Text("Bucket") }, singleLine = true)
                OutlinedTextField(keyId, { keyId = it }, Modifier.fillMaxWidth(), label = { Text("Access key ID") }, singleLine = true)
                OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text("Secret access key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ act { Sync.connect(account, bucket, keyId, secret) } },
                        enabled = !busy && listOf(account, bucket, keyId, secret).all { it.isNotBlank() }) { Text(if (busy) "Connecting…" else "Connect") }
                    TextButton({ mode = null; err = null }) { Text("Cancel") }
                }
            }
        }
        err?.let { Hint(it, color = MaterialTheme.colorScheme.error) }
        return
    }

    val devices = remember(st) { Sync.devices() }
    Text("On · bucket “${c.bucket}”", fontWeight = FontWeight.SemiBold)
    Hint(when { st.running -> "Syncing…"; st.error != null -> st.error!!; st.last != null -> "Synced ${ago(st.last!!)}"; else -> "Waiting…" },
        color = if (st.error != null && !st.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    for (d in devices) Row(Modifier.fillMaxWidth()) {
        Text((if (d.platform == "android") "📱 " else "💻 ") + d.name + (if (d.me) " (this phone)" else ""), Modifier.weight(1f))
        if (!d.me) Hint("seen ${ago(d.seen)}")
    }
    Hint("Synced: projects, finished recordings (audio, transcript, summary), people and your Claude API key. Deleting on one device moves it to the trash on the others.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ Sync.kick() }, enabled = !st.running) { Text("Sync now") }
        OutlinedButton({ showCode = true }) { Text("Code for another device") }
    }
    TextButton({ confirmOff = true }) { Text("Turn off sync", color = MaterialTheme.colorScheme.error) }

    if (showCode) AlertDialog(onDismissRequest = { showCode = false },
        title = { Text("Code for another device") },
        text = { Text("Anyone with this code can read and change your Folio data. Send it only to yourself, then paste it in Folio on the other device (Settings → Sync).") },
        confirmButton = { TextButton({
            showCode = false
            ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, Sync.code()), "Send the sync code"))
        }) { Text("Share") } },
        dismissButton = { TextButton({
            showCode = false
            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Folio sync code", Sync.code()))
        }) { Text("Copy") } })
    if (confirmOff) AlertDialog(onDismissRequest = { confirmOff = false },
        title = { Text("Turn off sync?") },
        text = { Text("Nothing is deleted, here or in your bucket. You can join again with a code.") },
        confirmButton = { TextButton({ confirmOff = false; Sync.disconnect() }) { Text("Turn off", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmOff = false }) { Text("Cancel") } })
}

// ---------- activity

@Composable
fun ActivityScreen(nav: NavHostController) {
    val app = FolioApp.instance
    val ctx = LocalContext.current
    val rec by app.recorder.state.collectAsState()
    val tasks by app.engine.tasks.collectAsState()
    val loaded by app.engine.loaded.collectAsState()
    var auto by remember { mutableIntStateOf(app.engine.autoUnloadMinutes) }
    var quitting by remember { mutableStateOf<Boolean?>(null) }
    Screen(nav, "Activity") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint("Leaving the app is like closing a window: recording and processing keep going, with a notification. Quit stops Folio.")
            SectionTitle("Recording")
            rec?.let { r -> Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(RecRed); Spacer(Modifier.width(8.dp)); Text(r.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(clock(r.elapsed)); Spacer(Modifier.width(8.dp))
                    OutlinedButton({ app.recorder.stop() }, enabled = !r.stopping) { Text("■ Stop") }
                }
            } } ?: Hint("Not recording.")
            SectionTitle("Tasks")
            if (tasks.isEmpty()) Hint("Nothing running.")
            for (t in tasks) Card(Modifier.fillMaxWidth().clickable { if (t.mid != "_state") nav.navigate("meeting/${enc(t.pid)}/${enc(t.mid)}") }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, fontWeight = FontWeight.SemiBold)
                            Hint(com.qshlab.folio.engine.KIND_LABEL[t.kind] + " · " + (if (t.stopping) "stopping…" else if (t.running) (STEP_LABEL[t.step] ?: t.step) + if (t.progress > 0) " ${(t.progress * 100).toInt()}%" else "" else "waiting"))
                        }
                        OutlinedButton({ app.engine.stop(t.key) }, enabled = !t.stopping) { Text(if (t.running) "Stop" else "Remove") }
                    }
                    if (t.running) LinearProgressIndicator({ t.progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
            if (tasks.size > 1) OutlinedButton({ app.engine.stopAll() }) { Text("Stop all tasks") }
            SectionTitle("Memory")
            if (loaded.isEmpty()) Hint("No speech model loaded. It loads when needed (a few seconds) and uses about 1 GB while loaded.")
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speech model (Parakeet) loaded", Modifier.weight(1f)); OutlinedButton({ app.engine.unload() }, enabled = tasks.none { it.running }) { Text("Unload") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Unload after idle", Modifier.weight(1f))
                for (m in listOf(2, 5, 15)) FilterChip(auto == m, { auto = m; app.engine.autoUnloadMinutes = m }, { Text("$m min") }, Modifier.padding(start = 6.dp))
            }
            SectionTitle("Folio")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ quitting = false }) { Text("Quit Folio") }
                OutlinedButton({ quitting = true }) { Text("Force quit", color = MaterialTheme.colorScheme.error) }
            }
            Hint("Quit stops and saves a recording and stops tasks (unfinished transcriptions resume next time). Force quit ends everything immediately; audio already recorded is recovered next time.")
        }
    }
    quitting?.let { force ->
        AlertDialog(onDismissRequest = { quitting = null }, title = { Text(if (force) "Force quit Folio?" else "Quit Folio?") },
            text = { Text(when {
                force -> "Everything stops immediately. Audio already recorded is recovered next time; tasks need starting again."
                rec != null -> "The recording will be stopped, saved, and transcribed next time you open Folio."
                tasks.isNotEmpty() -> "Transcriptions continue next time; fixes and summaries need starting again."
                else -> "Folio stops until you open it again."
            }) },
            confirmButton = { TextButton({
                if (force) android.os.Process.killProcess(android.os.Process.myPid())
                app.engine.quit(false) { app.recorder.stop() }
                (ctx as? android.app.Activity)?.finishAffinity()
                kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { kotlinx.coroutines.delay(4000); android.os.Process.killProcess(android.os.Process.myPid()) }
            }) { Text(if (force) "Force quit" else "Quit") } },
            dismissButton = { TextButton({ quitting = null }) { Text("Cancel") } })
    }
}
