package com.qshlab.folio.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import com.qshlab.folio.FolioApp
import com.qshlab.folio.data.Paragraph
import com.qshlab.folio.data.Transcript
import com.qshlab.folio.data.export
import com.qshlab.folio.data.paragraphs
import com.qshlab.folio.data.replaceSpan
import com.qshlab.folio.data.setSpeaker
import com.qshlab.folio.data.speakerName
import com.qshlab.folio.data.text
import kotlinx.coroutines.delay
import java.io.File

private val SOURCE = mapOf("glossary" to "Glossary", "audio" to "Second listen", "text" to "Claude")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MeetingScreen(nav: NavHostController, pid: String, midAndT: String) {
    val mid = midAndT.substringBefore('?')
    val app = FolioApp.instance
    val ctx = LocalContext.current
    val store = app.store
    val engine = app.engine
    val version by dataVersion.collectAsState()
    val tasks by engine.tasks.collectAsState()
    val rec by app.recorder.state.collectAsState()
    val task = tasks.firstOrNull { it.key == "$pid/$mid" }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(task != null) { while (task != null) { delay(1500); tick++ } }
    var v by remember { mutableIntStateOf(0) }
    val meta = remember(version, tasks, tick, v) { runCatching { store.meeting(pid, mid) }.getOrNull() }
    if (meta == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val t = remember(version, tasks, tick, v) { store.transcript(pid, mid)?.let(engine.people::resolve) }
    val summary = remember(version, tasks, tick, v) { engine.summaries.summary(pid, mid) }
    val fixes = remember(version, tasks, tick, v) { engine.fixes.load(pid, mid).items.filter { it.status == "pending" } }
    val paras = remember(t) { t?.paragraphs() ?: emptyList() }
    var tab by remember(summary != null) { mutableIntStateOf(if (summary != null || task?.kind == "summarize") 0 else 1) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Paragraph?>(null) }
    var who by remember { mutableStateOf<Pair<Int, Paragraph?>?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    // --- player
    val audio = store.audioFile(pid, mid)
    val player = remember(audio.exists()) { if (audio.exists()) ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(audio.toURI().toString())); prepare() } else null }
    DisposableEffect(player) { onDispose { player?.release() } }
    var pos by remember { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(player) { while (player != null) { pos = player.currentPosition / 1000.0; playing = player.isPlaying; delay(150) } }
    fun seek(s: Double) { player?.let { it.seekTo(((s - 0.15).coerceAtLeast(0.0) * 1000).toLong()); it.play() } }
    val startAt = midAndT.substringAfter("?t=", "").toDoubleOrNull()
    LaunchedEffect(t != null, startAt) {
        if (t != null && startAt != null && startAt >= 0) {
            tab = 1
            val pi = paras.indexOfLast { it.start <= startAt + 0.01 }.coerceAtLeast(0)
            delay(200); list.scrollToItem(3 + pi); player?.seekTo((startAt * 1000).toLong())
        }
    }

    fun save(nt: Transcript) { store.saveTranscript(pid, mid, nt); v++ }
    fun share(fmt: String) {
        val tt = t ?: return
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "$mid.$fmt").apply { writeText(tt.export(fmt, meta.title)) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export transcript"))
    }

    Screen(nav, meta.title, actions = {
        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(menu, { menu = false }) {
            val busy = task != null
            if (t != null) {
                DropdownMenuItem({ Text("Fix: check text (Claude)") }, { menu = false; tab = 1; engine.submit(pid, mid, "correct", mapOf("mode" to "text")) }, enabled = !busy && engine.claude.key != null)
                DropdownMenuItem({ Text("Fix: listen again (audio)") }, { menu = false; tab = 1; engine.submit(pid, mid, "correct", mapOf("mode" to "audio")) }, enabled = !busy && app.models.ready("whisper"))
                DropdownMenuItem({ Text("Fix: both") }, { menu = false; tab = 1; engine.submit(pid, mid, "correct", mapOf("mode" to "both")) }, enabled = !busy && engine.claude.key != null)
                HorizontalDivider()
                DropdownMenuItem({ Text("Summarize again") }, { menu = false; tab = 0; engine.submit(pid, mid, "summarize") }, enabled = !busy && engine.claude.key != null)
                DropdownMenuItem({ Text("Find speakers again") }, { menu = false; engine.submit(pid, mid, "speakers") }, enabled = !busy)
                for (n in listOf(2, 3, 4, 5, 6)) DropdownMenuItem({ Text("   …with $n speakers") }, { menu = false; engine.submit(pid, mid, "speakers", mapOf("speakers" to "$n")) }, enabled = !busy)
                HorizontalDivider()
                DropdownMenuItem({ Text("Export Markdown") }, { menu = false; share("md") })
                DropdownMenuItem({ Text("Export text") }, { menu = false; share("txt") })
                DropdownMenuItem({ Text("Export subtitles (SRT)") }, { menu = false; share("srt") })
                HorizontalDivider()
            }
            DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true })
            DropdownMenuItem({ Text("Transcribe again") }, { menu = false; engine.submit(pid, mid, "process") }, enabled = !busy && meta.status != "recording")
            DropdownMenuItem({ Text("Delete…") }, { menu = false; deleting = true })
        }
    }) {
        val live = rec
        if (meta.status == "recording" && live != null && live.mid == mid) {
            Column(Modifier.padding(16.dp)) { LiveCard(live, full = true) }
            return@Screen
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item {
                Hint(whenText(meta.date) + (if (meta.duration > 0) " · ${clock(meta.duration)}" else "") + (t?.let { " · ${it.speakers.size} speaker${if (it.speakers.size == 1) "" else "s"}" } ?: ""))
                if (task != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Badge((if (task.stopping) "Stopping" else STEP_LABEL[task.step] ?: task.step) + if (task.progress > 0) " ${(task.progress * 100).toInt()}%" else "")
                    Spacer(Modifier.width(8.dp)); TextButton({ engine.stop(task.key) }) { Text(if (task.running) "Stop" else "Remove") }
                } else if (meta.status == "error") Hint("Failed: ${meta.error}", color = MaterialTheme.colorScheme.error)
                if (meta.jobError.isNotEmpty() && task == null) Hint(meta.jobError, color = MaterialTheme.colorScheme.error)
            }
            stickyHeader {
                if (player != null) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (player.isPlaying) player.pause() else player.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play") }
                    val dur = (player.duration.takeIf { it > 0 } ?: (meta.duration * 1000).toLong()).toFloat()
                    Slider((pos * 1000).toFloat().coerceIn(0f, dur.coerceAtLeast(1f)), { player.seekTo(it.toLong()) }, Modifier.weight(1f), valueRange = 0f..dur.coerceAtLeast(1f))
                    Hint(" ${clock(pos)}")
                }
            }
            item {
                if (t == null) {
                    Hint(when {
                        meta.status == "stopped" -> "Transcription was stopped."
                        task != null || meta.status != "error" -> "The transcript will appear here when processing finishes."
                        else -> "Processing failed."
                    }, Modifier.padding(vertical = 20.dp))
                    if (meta.status == "stopped" && task == null) Button({ engine.submit(pid, mid, "process") }) { Text("Transcribe") }
                } else TabRow(tab, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Summary") })
                    Tab(tab == 1, { tab = 1 }, text = { Text(if (fixes.isNotEmpty()) "Transcript · ${fixes.size}" else "Transcript") })
                }
            }
            if (t != null && tab == 0) item {
                SummaryView(pid, mid, summary, task, { s -> tab = 1; seek(s) })
            }
            if (t != null && tab == 1) {
                if (fixes.isNotEmpty()) item {
                    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${fixes.size} suggested fix${if (fixes.size == 1) "" else "es"}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                TextButton({ engine.fixes.decide(pid, mid, fixes.map { it.id }.toSet(), false); v++ }) { Text("Dismiss all") }
                                TextButton({ engine.fixes.decide(pid, mid, fixes.map { it.id }.toSet(), true); v++ }) { Text("Accept all") }
                            }
                            for (f in fixes.take(30)) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(buildAnnotatedString {
                                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.error, textDecoration = TextDecoration.LineThrough)) { append(f.old) }
                                        append(" → ")
                                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) { append(f.new) }
                                    }, fontFamily = Heading)
                                    Hint("${SOURCE[f.source]}${if (f.why.isNotEmpty()) " · ${f.why}" else ""}")
                                }
                                TextButton({ engine.fixes.decide(pid, mid, setOf(f.id), false); v++ }) { Text("✕") }
                                TextButton({ engine.fixes.decide(pid, mid, setOf(f.id), true); v++ }) { Text("✓") }
                            }
                        }
                    }
                }
                item { SpeakerBar(t, { who = it to null }) { k, accept ->
                    val s = t.speakers[k.toString()]
                    if (accept) (s?.suggested?.person ?: s?.person)?.let { engine.people.link(pid, mid, k.toString(), it) }
                    else engine.people.unlink(pid, mid, k.toString(), reject = true)
                    v++
                } }
                val fixWords = fixes.flatMap { (it.first..it.last).toList() }.toSet()
                itemsIndexed(paras, key = { _, p -> p.first }) { _, p ->
                    ParagraphRow(t, p, pos, playing, fixWords, onSeek = ::seek, onSpeaker = { who = (p.speaker ?: 0) to p }, onEdit = { editing = p })
                }
            }
        }
    }

    editing?.let { p ->
        var text by remember(p) { mutableStateOf(t!!.text(p)) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit text") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp)) },
            confirmButton = { TextButton({ if (text.isNotBlank()) save(t!!.replaceSpan(p.first, p.last, text)); editing = null }) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
    who?.let { (spk, para) -> SpeakerSheet(pid, mid, t!!, spk, para, { who = null }) { v++ } }
    if (renaming) NameDialog("Rename", "Title", meta.title, { renaming = false }) { n -> store.updateMeeting(pid, mid) { it.copy(title = n) }; renaming = false; v++; changed() }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete “${meta.title}”?") },
        text = { Text("The recording and its transcript are moved to Folio's trash.") },
        confirmButton = { TextButton({ task?.let { engine.stop(it.key) }; store.moveToTrash(store.meetingDir(pid, mid)); deleting = false; changed(); nav.popBackStack() }) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } })
}

@Composable
private fun ParagraphRow(t: Transcript, p: Paragraph, pos: Double, playing: Boolean, fixWords: Set<Int>,
                         onSeek: (Double) -> Unit, onSpeaker: () -> Unit, onEdit: () -> Unit) {
    val words = t.words.subList(p.first, p.last + 1)
    val cur = if (pos > 0 && pos >= p.start - 0.2 && pos <= p.end + 0.3) words.indexOfLast { it.start <= pos } else -1
    val hl = Color(0x66F2C94C); val fixBg = Color(0x47E3A03A)
    val starts = IntArray(words.size)
    val annotated = buildAnnotatedString {
        words.forEachIndexed { i, w ->
            starts[i] = length
            val style = when {
                i == cur -> SpanStyle(background = hl)
                (p.first + i) in fixWords -> SpanStyle(background = fixBg)
                w.edited -> SpanStyle(textDecoration = TextDecoration.Underline)
                else -> null
            }
            if (style != null) withStyle(style) { append(w.text) } else append(w.text)
            append(' ')
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onSpeaker).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(speakerColor(p.speaker)); Spacer(Modifier.width(6.dp))
                Text(t.speakerName(p.speaker), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Hint(clock(p.start), Modifier.clickable { onSeek(p.start) })
        }
        Text(annotated, Modifier.pointerInput(p.first) {
            detectTapGestures(onTap = { off ->
                layout?.let { l -> val o = l.getOffsetForPosition(off); val i = starts.indexOfLast { it <= o }.coerceAtLeast(0); onSeek(words[i].start) }
            }, onLongPress = { onEdit() })
        }, fontFamily = Heading, fontSize = 16.sp, lineHeight = 25.sp, onTextLayout = { layout = it })
    }
}

/** Speakers, longest first, with "is this Sarah?" suggestions to confirm. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerBar(t: Transcript, open: (Int) -> Unit, decide: (Int, Boolean) -> Unit) {
    val secs = HashMap<Int, Double>()
    for (w in t.words) w.speaker?.let { secs[it] = (secs[it] ?: 0.0) + (w.end - w.start) }
    val ids = t.speakers.keys.mapNotNull { it.toIntOrNull() }.filter { (secs[it] ?: 0.0) > 0 }.sortedByDescending { secs[it] }
    FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in ids) {
            val s = t.speakers[k.toString()]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { open(k) }.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(speakerColor(k)); Spacer(Modifier.width(5.dp)); Text(t.speakerName(k), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Hint(" ${clock(secs[k] ?: 0.0)}")
                if (s?.person == null && s?.suggested != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("${s.suggested.name}?", Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x55F2C94C)).padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 13.sp)
                    Text(" ✓", Modifier.clickable { decide(k, true) }.padding(4.dp)); Text("✕", Modifier.clickable { decide(k, false) }.padding(4.dp))
                } else if (s?.person != null && s.auto) {
                    Hint(" · by voice"); Text(" ✓", Modifier.clickable { decide(k, true) }.padding(4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakerSheet(pid: String, mid: String, t: Transcript, spk: Int, para: Paragraph?, close: () -> Unit, changed: () -> Unit) {
    val app = FolioApp.instance
    val people = remember { app.engine.people.all() }
    var q by remember { mutableStateOf("") }
    val info = t.speakers[spk.toString()]
    val ql = q.trim().lowercase()
    val matches = people.filter { ql.isEmpty() || it.name.lowercase().contains(ql) || it.aliases.any { a -> a.lowercase().contains(ql) } }
        .sortedWith(compareBy({ pid !in it.projects }, { it.name.lowercase() })).take(6)
    val exact = people.firstOrNull { it.name.lowercase() == ql }
    fun link(person: String) { app.engine.people.link(pid, mid, spk.toString(), person); changed(); close() }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Who is ${t.speakerName(spk)}?", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Type a name…") }, singleLine = true)
            for (p in matches) Row(Modifier.fillMaxWidth().clickable { link(p.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.name); Spacer(Modifier.width(10.dp)); Text(p.name, Modifier.weight(1f)); if (p.id == info?.person) Text("✓")
            }
            if (ql.isNotEmpty() && exact == null) Text("+ New person “${q.trim()}”", Modifier.fillMaxWidth().clickable {
                link(app.engine.people.create(q.trim(), pid).id)
            }.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary)
            if (info?.person != null) Text("Not ${info.name}", Modifier.fillMaxWidth().clickable { app.engine.people.unlink(pid, mid, spk.toString(), false); changed(); close() }.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (para != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Hint("Only this paragraph was said by")
                val ids = t.speakers.keys.mapNotNull { it.toIntOrNull() }
                for (s in ids.filter { it != para.speaker }) Row(Modifier.fillMaxWidth().clickable {
                    app.store.saveTranscript(pid, mid, app.store.transcript(pid, mid)!!.setSpeaker(para.first, para.last, s)); changed(); close()
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Dot(speakerColor(s)); Spacer(Modifier.width(8.dp)); Text(t.speakerName(s)) }
                val next = (ids.maxOrNull() ?: -1) + 1
                Row(Modifier.fillMaxWidth().clickable {
                    app.store.saveTranscript(pid, mid, app.store.transcript(pid, mid)!!.setSpeaker(para.first, para.last, next)); changed(); close()
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Dot(speakerColor(next)); Spacer(Modifier.width(8.dp)); Text("Another speaker") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryView(pid: String, mid: String, s: com.qshlab.folio.data.Summary?, task: com.qshlab.folio.engine.Task?, jump: (Double) -> Unit) {
    val app = FolioApp.instance
    if (s == null) {
        Column(Modifier.padding(vertical = 16.dp)) {
            when {
                task?.kind == "summarize" -> Hint("Writing the summary… ${(task.progress * 100).toInt()}%")
                app.engine.claude.key == null -> Hint("Summaries are written by Claude. Add your API key in Settings, then summarize.")
                else -> Button({ app.engine.submit(pid, mid, "summarize") }, enabled = task == null) { Text("Summarize") }
            }
        }
        return
    }
    val todos = remember(s.at) { app.engine.summaries.todos(pid).filter { it.closedBy?.meeting == mid } }
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(s.overview, fontFamily = Heading, fontSize = 17.sp, lineHeight = 26.sp)
        @Composable fun head(x: String) = Text(x.uppercase(), Modifier.padding(top = 14.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        @Composable fun bullet(content: @Composable () -> Unit) = Row { Text("•  "); content() }
        if (s.topics.isNotEmpty()) { head("Topics"); for (x in s.topics) bullet {
            Text(buildAnnotatedString {
                if (x.start.isNotEmpty()) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(x.start + "  ") }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(x.title) }; append(" — " + x.summary)
            }, Modifier.clickable { x.start.split(":").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { jump(it[0] * 60.0 + it[1]) } }, fontFamily = Heading)
        } }
        if (s.decisions.isNotEmpty()) { head("Decisions"); for (x in s.decisions) bullet { Text(x, fontFamily = Heading) } }
        if (s.actionItems.isNotEmpty()) { head("Action items"); for (x in s.actionItems) bullet {
            Text(x.task + (if (x.owner.isNotEmpty()) " — ${x.owner}" else "") + (if (x.due.isNotEmpty()) " (due ${x.due})" else ""), fontFamily = Heading)
        } }
        if (todos.isNotEmpty()) { head("Done since earlier meetings"); for (x in todos) bullet { Text(x.text, fontFamily = Heading, textDecoration = TextDecoration.LineThrough) } }
        if (s.progress.isNotEmpty()) { head("Progress"); for (x in s.progress) bullet { Text("${x.area}: ${x.update}", fontFamily = Heading) } }
        if (s.openQuestions.isNotEmpty()) { head("Open questions"); for (x in s.openQuestions) bullet { Text(x, fontFamily = Heading) } }
        if (!s.updatedState) Hint("This is an older meeting, so re-summarizing it didn't change the project state.", Modifier.padding(top = 10.dp))
        Hint("Written by ${s.writer.ifEmpty { "Claude" }} using the project's knowledge, state, to-dos and earlier meetings.", Modifier.padding(top = 10.dp))
    }
}
