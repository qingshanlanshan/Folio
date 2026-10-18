package com.qshlab.folio.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The on-device models, downloaded once into filesDir/models/<id>/. Speech recognition, voice detection
 * and speaker voices are required; Whisper (for "listen again" fixes) is optional.
 */
data class ModelFile(val name: String, val url: String, val size: Long)
data class SpeechModel(val id: String, val title: String, val purpose: String, val files: List<ModelFile>, val required: Boolean) {
    val size get() = files.sumOf { it.size }
}

private const val HF = "https://huggingface.co"
private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

val MODELS = listOf(
    SpeechModel("parakeet", "Parakeet TDT 0.6B", "Speech to text", listOf(
        ModelFile("encoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/encoder.int8.onnx", 652_184_296),
        ModelFile("decoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/decoder.int8.onnx", 7_257_753),
        ModelFile("joiner.int8.onnx", "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/joiner.int8.onnx", 1_739_080),
        ModelFile("tokens.txt", "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/tokens.txt", 9_384),
    ), required = true),
    SpeechModel("live", "Streaming Zipformer (English)", "Live text while recording", listOf(
        ModelFile("encoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx", 71_100_000),
        ModelFile("decoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/decoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx", 1_300_000),
        ModelFile("joiner.int8.onnx", "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx", 300_000),
        ModelFile("tokens.txt", "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/tokens.txt", 5_000),
    ), required = true),
    SpeechModel("voices", "Silero VAD + TitaNet", "Finding speech and telling voices apart", listOf(
        ModelFile("silero_vad.onnx", "$GH/asr-models/silero_vad.onnx", 643_854),
        ModelFile("nemo_en_titanet_small.onnx", "$GH/speaker-recongition-models/nemo_en_titanet_small.onnx", 40_257_283),
    ), required = true),
    SpeechModel("whisper", "Whisper base.en", "“Listen again” transcript fixes (optional)", listOf(
        ModelFile("base.en-encoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/base.en-encoder.int8.onnx", 29_100_000),
        ModelFile("base.en-decoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/base.en-decoder.int8.onnx", 130_700_000),
        ModelFile("base.en-tokens.txt", "$HF/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/base.en-tokens.txt", 800_000),
    ), required = false),
)

class ModelStore(val root: File) {
    data class Download(val id: String, val progress: Float, val error: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloads: StateFlow<Map<String, Download>> = _downloads
    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<String>> = _installed

    fun dir(id: String) = File(root, id)
    fun path(id: String, file: String) = File(dir(id), file).absolutePath
    fun ready(id: String) = id in _installed.value
    val requiredReady get() = MODELS.filter { it.required }.all { ready(it.id) }

    private fun scan(): Set<String> = MODELS.filter { m -> File(root, "${m.id}/.complete").exists() }.map { it.id }.toSet()

    fun download(id: String) {
        if (jobs[id]?.isActive == true) return
        val model = MODELS.first { it.id == id }
        jobs[id] = scope.launch {
            set(id, Download(id, 0f))
            try {
                val dir = dir(id).apply { mkdirs() }
                var done = 0L
                for (f in model.files) {
                    val target = File(dir, f.name)
                    // Flaky connections: retry a few times; each retry resumes the partial file.
                    var attempt = 0
                    while (true) {
                        try {
                            fetch(f.url, target) { got -> set(id, Download(id, ((done + got).toFloat() / model.size).coerceAtMost(1f))) }
                            break
                        } catch (e: java.io.IOException) {
                            if (++attempt >= 6) throw e
                            kotlinx.coroutines.delay(2000L * attempt)
                        }
                    }
                    done += target.length()
                }
                File(dir, ".complete").writeText("ok")
                _installed.value = scan()
                _downloads.value = _downloads.value - id
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) { _downloads.value = _downloads.value - id; throw e }
                set(id, Download(id, 0f, e.message ?: "Download failed"))
            }
        }
    }

    fun cancel(id: String) { jobs[id]?.cancel(); _downloads.value = _downloads.value - id }

    fun delete(id: String) {
        cancel(id)
        dir(id).deleteRecursively()
        _installed.value = scan()
    }

    fun sizeOnDisk(id: String) = dir(id).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun set(id: String, d: Download) { _downloads.value = _downloads.value + (id to d) }

    /** Downloads with resume: a partial file continues where it stopped. */
    private suspend fun fetch(url: String, out: File, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val part = File(out.parentFile, out.name + ".part")
        if (out.exists()) { onProgress(out.length()); return@withContext }
        var redirects = 0
        var u = URL(url)
        while (true) {
            val have = part.length()
            val conn = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "Folio/0.1 (Android)")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 8) {
                u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            if (code == 416) { conn.disconnect(); break }               // already complete
            if (code !in 200..299) throw IllegalStateException("HTTP $code for ${out.name}")
            val append = code == 206
            conn.inputStream.use { inp ->
                FileOutputStream(part, append).use { o ->
                    val buf = ByteArray(1 shl 16)
                    var got = if (append) have else 0L
                    while (true) {
                        ensureActive()
                        val n = inp.read(buf); if (n < 0) break
                        o.write(buf, 0, n); got += n
                        onProgress(got)
                    }
                }
            }
            break
        }
        part.renameTo(out)
    }
}
