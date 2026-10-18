package com.qshlab.folio.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qshlab.folio.FolioApp
import com.qshlab.folio.speech.MODELS
import com.qshlab.folio.speech.SpeedTest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun SpeedTestScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val models = FolioApp.instance.models
    val installed by models.installed.collectAsState()
    val downloads by models.downloads.collectAsState()
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    var result by remember { mutableStateOf<SpeedTest.Result?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val running = job?.isActive == true
    val ready = MODELS.filter { it.required }.all { it.id in installed }
    // Keep the screen on during the test (the battery baseline assumes it).
    val activity = LocalActivity.current
    DisposableEffect(running) {
        if (running) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Speed & battery test", style = MaterialTheme.typography.headlineSmall)
        Text("Measures live-transcript delay, battery use and full-transcription speed on this phone, with a 2-minute meeting clip.",
            style = MaterialTheme.typography.bodyMedium)
        for (m in MODELS.filter { it.required }) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(m.title, fontWeight = FontWeight.SemiBold)
                    Text("${m.purpose} · ${m.size / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
                    val d = downloads[m.id]
                    when {
                        m.id in installed -> Text("✓ Downloaded", style = MaterialTheme.typography.bodySmall)
                        d?.error != null -> { Text("Failed: ${d.error}", color = MaterialTheme.colorScheme.error); OutlinedButton({ models.download(m.id) }) { Text("Retry") } }
                        d != null -> { LinearProgressIndicator({ d.progress }, Modifier.fillMaxWidth().padding(top = 8.dp)); Text("${(d.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall) }
                        else -> OutlinedButton({ models.download(m.id) }) { Text("Download") }
                    }
                }
            }
        }
        Row {
            Button(enabled = ready && !running, onClick = {
                log.clear(); result = null
                job = scope.launch {
                    try { result = SpeedTest(ctx, models).run { line -> scope.launch { log += line } } }
                    catch (e: Exception) { log += "Error: ${e.message}" }
                    job = null
                }
            }) { Text(if (running) "Running… (about 5 minutes)" else "Run test") }
            if (running) { Spacer(Modifier.width(8.dp)); OutlinedButton({ job?.cancel() }) { Text("Stop") } }
        }
        result?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Results", fontWeight = FontWeight.SemiBold)
                    fun battery(l: SpeedTest.Live) = l.perHour?.let { "about %.0f%% battery per hour".format(it) }
                        ?: if (r.charging) "battery: unplug the charger and run again" else "battery: not reported by this phone"
                    Text("Live, streaming model: words appear %.1f s behind the speaker (95%%: %.1f s); %s.".format(
                        r.streaming.delayMedian, r.streaming.delayMax, battery(r.streaming)))
                    Text("Live, Parakeet pieces: text appears %.1f s after each pause (worst %.1f s); %s.".format(
                        r.pieces.delayMedian, r.pieces.delayMax, battery(r.pieces)))
                    if (r.idleMa != null) Text("Current: %.0f mA idle, %s streaming, %s Parakeet pieces.".format(r.idleMa,
                        r.streaming.ma?.let { "%.0f mA".format(it) } ?: "?", r.pieces.ma?.let { "%.0f mA".format(it) } ?: "?"),
                        style = MaterialTheme.typography.bodySmall)
                    Text("After recording: %.1f× real time → a 1-hour meeting takes about %.0f min, plus ~%.0f min to find speakers.".format(
                        r.fullSpeed, 60 / r.fullSpeed, (r.speakerSeconds ?: 0.0) / r.clipSeconds * 60))
                    r.tempC?.let { Text("Battery temperature at the end: %.0f °C.".format(it), style = MaterialTheme.typography.bodySmall) }
                    Text("Streaming sample: ${r.streamingSample.take(160)}…", style = MaterialTheme.typography.bodySmall)
                    Text("Parakeet sample: ${r.parakeetSample.take(160)}…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        for (line in log) Text(line, style = MaterialTheme.typography.bodySmall)
    }
}
