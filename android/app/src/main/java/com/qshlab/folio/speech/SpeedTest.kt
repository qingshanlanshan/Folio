package com.qshlab.folio.speech

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.qshlab.folio.audio.Decoder
import com.qshlab.folio.audio.SR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Measures, on this phone, with a bundled 2-minute meeting clip (AMI corpus, CC BY 4.0) played in real
 * time like a microphone:
 *  - live text with the streaming model: how far behind the speaker the words appear, and battery use;
 *  - live text with Parakeet on speech pieces (the more accurate alternative): delay and battery;
 *  - the full after-recording pass: speed, and how long speaker detection takes.
 */
class SpeedTest(private val ctx: Context, private val models: ModelStore) {
    data class Live(val delayMedian: Double, val delayMax: Double, val ma: Double?, val perHour: Double?)
    data class Result(
        val streaming: Live, val pieces: Live, val idleMa: Double?, val charging: Boolean,
        val fullSpeed: Double, val speakerSeconds: Double?, val clipSeconds: Double,
        val tempC: Double?, val streamingSample: String, val parakeetSample: String,
    )

    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private fun currentMa(): Double? {
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (ua == Int.MIN_VALUE || ua == 0) return null
        // Most phones report microamps; a few report milliamps.
        return if (abs(ua) > 20_000) abs(ua) / 1000.0 else abs(ua).toDouble()
    }

    private fun capacityMah(): Double? {
        val uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (uah <= 0 || pct <= 0) return null
        return uah / 1000.0 / (pct / 100.0)
    }

    /** Runs [block] while sampling the battery current once a second; returns the mean (mA). */
    private suspend fun <T> power(block: suspend () -> T): Pair<T, Double?> {
        val samples = mutableListOf<Double>()
        val sampler = CoroutineScope(Dispatchers.Default).launch {
            while (true) { currentMa()?.let { synchronized(samples) { samples += it } }; delay(1000) }
        }
        try {
            val r = block()
            return r to synchronized(samples) { samples.takeIf { it.isNotEmpty() }?.average() }
        } finally { sampler.cancel() }
    }

    /** Feeds [pcm] in 32 ms windows at real-time pace. */
    private suspend fun realtime(pcm: FloatArray, onWindow: (FloatArray, Long) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        var i = 0
        val win = 512
        while (i + win <= pcm.size) {
            coroutineContext.ensureActive()
            onWindow(pcm.copyOfRange(i, i + win), start)
            i += win
            val wait = start + i * 1000L / SR - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
        }
    }

    suspend fun run(log: (String) -> Unit): Result = withContext(Dispatchers.Default) {
        val clip = File(ctx.cacheDir, "test-clip.m4a")
        ctx.assets.open("test-clip.m4a").use { i -> clip.outputStream().use { i.copyTo(it) } }
        val pcm = Decoder.decode(clip)
        val clipSec = pcm.size.toDouble() / SR
        val charging = bm.isCharging
        val cap = capacityMah()

        log("Measuring idle power for 10 s (keep the screen on, don't touch the phone)…")
        val idle = mutableListOf<Double>()
        repeat(20) { currentMa()?.let(idle::add); delay(500) }
        val idleMa = idle.takeIf { it.isNotEmpty() }?.average()
        fun perHour(ma: Double?) = if (idleMa != null && ma != null && cap != null && !charging)
            (ma - idleMa).coerceAtLeast(0.0) / cap * 100 else null

        // --- 1. streaming model
        log("Live run 1/2: streaming model, 2 minutes in real time…")
        val live = LiveStream(models)
        val lags = mutableListOf<Double>()
        val streamText = StringBuilder()
        var uttStart = 0.0            // audio time where the current utterance began
        var fed = 0L
        var seen = 0
        val (_, streamMa) = power {
            realtime(pcm) { w, start ->
                live.feed(w); fed += w.size
                val r = live.partial()
                // New tokens: how far behind the audio they appeared.
                val now = (SystemClock.elapsedRealtime() - start) / 1000.0
                for (k in seen until r.tokens.size) {
                    val spoken = uttStart + r.timestamps.getOrElse(k) { 0f }
                    lags += now - spoken
                }
                seen = r.tokens.size
                if (live.endpoint()) {
                    val text = live.finish()
                    if (text.isNotEmpty() && streamText.length < 400) streamText.append(text.lowercase()).append(". ")
                    uttStart = fed.toDouble() / SR; seen = 0
                }
            }
        }
        live.release()
        val sLags = lags.sorted()
        val streaming = Live(sLags.getOrElse(sLags.size / 2) { 0.0 }, sLags.getOrElse((sLags.size * 0.95).toInt()) { 0.0 }, streamMa, perHour(streamMa))
        log("  words appear %.1f s behind the speaker (median).".format(streaming.delayMedian))

        // --- 2. Parakeet on speech pieces
        log("Loading Parakeet…")
        val asr = Parakeet(models)
        log("Live run 2/2: Parakeet on speech pieces, 2 minutes in real time…")
        val latencies = mutableListOf<Double>()
        val pkText = StringBuilder()
        val asrThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(asrThread)
        val vad = VoiceActivity(models, minSilence = 0.5f, maxSpeech = 10f)
        val (_, piecesMa) = power {
            fun drain(start: Long) {
                while (!vad.vad.empty()) {
                    val seg = vad.vad.front(); vad.vad.pop()
                    val speechEnd = start + (seg.start + seg.samples.size) * 1000L / SR
                    val samples = seg.samples
                    scope.launch {
                        val words = asr.transcribe(samples, seg.start.toDouble() / SR)
                        synchronized(latencies) { latencies += (SystemClock.elapsedRealtime() - speechEnd) / 1000.0 }
                        if (pkText.length < 400) pkText.append(words.joinToString(" ") { it.text }).append(' ')
                    }
                }
            }
            var t0 = 0L
            realtime(pcm) { w, start -> t0 = start; vad.vad.acceptWaveform(w); drain(start) }
            vad.vad.flush(); drain(t0)
            withContext(asrThread) {}           // the queue is FIFO: this waits for it to drain
        }
        vad.release()
        val pl = latencies.sorted()
        val pieces = Live(pl.getOrElse(pl.size / 2) { 0.0 }, pl.lastOrNull() ?: 0.0, piecesMa, perHour(piecesMa))
        log("  text appears %.1f s after each pause (median).".format(pieces.delayMedian))

        // --- 3. full pass
        log("Full pass: transcribing the whole clip as fast as possible…")
        val regions = VoiceActivity(models).let { v -> v.regions(pcm).also { v.release() } }
        val t1 = SystemClock.elapsedRealtime()
        for ((a, b) in regions) asr.transcribe(pcm.copyOfRange((a * SR).toInt(), minOf((b * SR).toInt(), pcm.size)), a)
        val full = (SystemClock.elapsedRealtime() - t1) / 1000.0
        asr.release()
        asrThread.close()

        log("Speakers: telling voices apart…")
        val t2 = SystemClock.elapsedRealtime()
        val emb = Embedder(models)
        val r = Diarizer.run(pcm, regions, emb)
        emb.release()
        val spk = (SystemClock.elapsedRealtime() - t2) / 1000.0
        log("  found ${r.centroids.size} speakers.")

        val temp = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.div(10.0)
        Result(streaming, pieces, idleMa, charging, clipSec / full, spk, clipSec, temp,
            streamText.toString().trim(), pkText.toString().trim())
    }
}
