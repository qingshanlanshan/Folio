package com.qshlab.folio.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.qshlab.folio.audio.SR
import com.qshlab.folio.data.Word
import com.qshlab.folio.data.round3

/** A word with its time in seconds; speaker is filled in later. */
data class W(var text: String, val start: Double, var end: Double, val conf: Double = 1.0, var speaker: Int? = null) {
    fun toWord() = Word(text, round3(start), round3(end), conf, speaker)
}

private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

/** Speech regions (seconds) found by Silero VAD. */
class VoiceActivity(models: ModelStore, minSilence: Float = 0.3f, maxSpeech: Float = 20f) {
    private val config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = models.path("voices", "silero_vad.onnx"), threshold = 0.5f,
            minSilenceDuration = minSilence, minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = maxSpeech),
        sampleRate = SR, numThreads = 1)
    val vad = Vad(null, config)
    val window = 512

    fun regions(pcm: FloatArray, check: () -> Unit = {}): List<Pair<Double, Double>> {
        vad.reset()
        val out = mutableListOf<Pair<Double, Double>>()
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                out += s.start.toDouble() / SR to (s.start + s.samples.size).toDouble() / SR
                vad.pop()
            }
        }
        var i = 0
        while (i + window <= pcm.size) {
            vad.acceptWaveform(pcm.copyOfRange(i, i + window))
            drain()
            i += window
            if (i % (SR * 30) < window) check()
        }
        vad.flush(); drain()
        return out
    }

    fun release() = vad.release()
}

/** Parakeet TDT 0.6B (int8) through sherpa-onnx. Long audio is fed in speech pieces found by the VAD. */
class Parakeet(models: ModelStore) {
    private val rec = OfflineRecognizer(null, OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SR, featureDim = 80, dither = 0f),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = models.path("parakeet", "encoder.int8.onnx"),
                decoder = models.path("parakeet", "decoder.int8.onnx"),
                joiner = models.path("parakeet", "joiner.int8.onnx")),
            tokens = models.path("parakeet", "tokens.txt"),
            numThreads = THREADS, modelType = "nemo_transducer"),
        decodingMethod = "greedy_search"))

    /** Words of one piece (≤ ~30 s), with times offset by [offset] seconds. */
    fun transcribe(pcm: FloatArray, offset: Double = 0.0): List<W> {
        if (pcm.size < SR / 10) return emptyList()
        val s = rec.createStream()
        try {
            s.acceptWaveform(pcm, SR)
            rec.decode(s)
            val r = rec.getResult(s)
            return tokensToWords(r.tokens, r.timestamps, r.durations, offset)
        } finally { s.release() }
    }

    fun release() = rec.release()

    companion object {
        fun tokensToWords(tokens: Array<String>, ts: FloatArray, durs: FloatArray?, offset: Double): List<W> {
            val words = mutableListOf<W>()
            for (k in tokens.indices) {
                val t = tokens[k]
                if (t.isBlank()) continue
                val start = offset + ts.getOrElse(k) { 0f }
                val end = start + (durs?.getOrNull(k)?.takeIf { it > 0f } ?: 0.08f)
                val prev = words.lastOrNull()
                // New word at a space, or after sentence punctuation ("Paul." + "'Cause"), keeping 3.5 whole.
                val newWord = t.startsWith(" ") || prev == null ||
                    (prev.text.last() in ".?!,;:" && !t.first().isDigit())
                if (newWord) words += W(t.trim(), start, end)
                else { prev!!.text += t; prev.end = end }
            }
            return words
        }
    }
}

/**
 * Live text while recording: a small streaming model that shows words as they're spoken (well under a
 * second behind) at low CPU cost. It's only a draft — after recording, Parakeet transcribes everything.
 * One utterance at a time: [feed] audio, read [partial]; when [endpoint] says the speaker paused,
 * [finish] returns the utterance and starts the next.
 */
class LiveStream(models: ModelStore) {
    private val rec = OnlineRecognizer(null, OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SR, featureDim = 80, dither = 0f),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = models.path("live", "encoder.int8.onnx"),
                decoder = models.path("live", "decoder.int8.onnx"),
                joiner = models.path("live", "joiner.int8.onnx")),
            tokens = models.path("live", "tokens.txt"),
            numThreads = 2),
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0f),
            rule2 = EndpointRule(true, 0.8f, 0f),
            rule3 = EndpointRule(false, 0f, 20f)),
        enableEndpoint = true,
        decodingMethod = "greedy_search"))
    private var stream: OnlineStream = rec.createStream()

    fun feed(pcm: FloatArray) {
        stream.acceptWaveform(pcm, SR)
        while (rec.isReady(stream)) rec.decode(stream)
    }

    /** The current utterance so far: text, its tokens and their times (seconds into the utterance). */
    fun partial() = rec.getResult(stream)

    fun endpoint() = rec.isEndpoint(stream)

    fun finish(): String {
        val text = rec.getResult(stream).text.trim()
        rec.reset(stream)
        return text
    }

    fun release() { stream.release(); rec.release() }
}

/** Whisper base.en, for the optional "listen again" pass. */
class WhisperListen(models: ModelStore) {
    private val rec = OfflineRecognizer(null, OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SR, featureDim = 80, dither = 0f),
        modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = models.path("whisper", "base.en-encoder.int8.onnx"),
                decoder = models.path("whisper", "base.en-decoder.int8.onnx"),
                language = "en", task = "transcribe", tailPaddings = 1000, enableTokenTimestamps = true),
            tokens = models.path("whisper", "base.en-tokens.txt"),
            numThreads = THREADS, modelType = "whisper"),
        decodingMethod = "greedy_search"))

    /** Words of one piece; when token times aren't available, spread evenly over the piece. */
    fun transcribe(pcm: FloatArray, offset: Double): List<W> {
        val s = rec.createStream()
        try {
            s.acceptWaveform(pcm, SR)
            rec.decode(s)
            val r = rec.getResult(s)
            if (r.timestamps.size == r.tokens.size && r.tokens.isNotEmpty()) {
                return Parakeet.tokensToWords(r.tokens, r.timestamps, null, offset)
            }
            val ws = r.text.split(Regex("""\s+""")).filter { it.isNotBlank() }
            val dur = pcm.size.toDouble() / SR
            return ws.mapIndexed { i, w -> W(w, offset + dur * i / ws.size, offset + dur * (i + 1) / ws.size) }
        } finally { s.release() }
    }

    fun release() = rec.release()
}

/** TitaNet speaker embeddings (unit length). */
class Embedder(models: ModelStore) {
    private val ex = SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(
        model = models.path("voices", "nemo_en_titanet_small.onnx"), numThreads = THREADS))

    fun embed(pcm: FloatArray): FloatArray {
        val st = ex.createStream()
        try {
            st.acceptWaveform(pcm, SR)
            st.inputFinished()
            val e = ex.compute(st)
            var n = 0f; for (v in e) n += v * v
            n = kotlin.math.sqrt(n) + 1e-9f
            return FloatArray(e.size) { e[it] / n }
        } finally { st.release() }
    }

    fun release() = ex.release()
}
