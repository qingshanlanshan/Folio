package com.qshlab.folio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SR = 16000

/** Decodes any audio/video file Android can read to 16 kHz mono float samples. */
object Decoder {
    fun decode(ctx: Context, uri: Uri, progress: (Float) -> Unit = {}): FloatArray =
        MediaExtractor().run {
            setDataSource(ctx, uri, null)
            try { decode(this, progress) } finally { release() }
        }

    fun decode(file: File, progress: (Float) -> Unit = {}): FloatArray =
        MediaExtractor().run {
            setDataSource(file.absolutePath)
            try { decode(this, progress) } finally { release() }
        }

    private fun decode(ex: MediaExtractor, progress: (Float) -> Unit): FloatArray {
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio in this file.")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val out = FloatBuilder()
        val resampler = Resampler()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                        if (durUs > 0) progress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                        ex.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val mono = toMono(buf, channels, pcmFloat)
                    out.add(resampler.process(mono, rate))
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release()
        return out.toArray()
    }

    private fun toMono(buf: ByteBuffer, channels: Int, isFloat: Boolean): FloatArray {
        val frames: Int
        val res: FloatArray
        if (isFloat) {
            val fb = buf.asFloatBuffer(); frames = fb.remaining() / channels; res = FloatArray(frames)
            for (i in 0 until frames) { var s = 0f; for (c in 0 until channels) s += fb.get(i * channels + c); res[i] = s / channels }
        } else {
            val sb = buf.asShortBuffer(); frames = sb.remaining() / channels; res = FloatArray(frames)
            for (i in 0 until frames) { var s = 0f; for (c in 0 until channels) s += sb.get(i * channels + c); res[i] = s / channels / 32768f }
        }
        return res
    }
}

/** Streaming linear resampler to 16 kHz (good enough for speech recognition). */
class Resampler {
    private var pos = 0.0     // next output position, in input samples relative to this chunk (may be -1..0)
    private var prev = 0f     // last sample of the previous chunk (index -1)
    fun process(x: FloatArray, rate: Int): FloatArray {
        if (rate == SR || x.isEmpty()) return x
        val step = rate.toDouble() / SR
        val out = FloatBuilder((x.size.toLong() * SR / rate + 8).toInt())
        fun at(i: Int) = if (i < 0) prev else x[i]
        while (true) {
            val i = kotlin.math.floor(pos).toInt()
            if (i + 1 >= x.size) break          // needs the next chunk
            val f = (pos - i).toFloat()
            val a = at(i); val b = at(i + 1)
            out.add(a + (b - a) * f)
            pos += step
        }
        pos -= x.size
        prev = x.last()
        return out.toArray()
    }
}

class FloatBuilder(cap: Int = 1 shl 16) {
    private var a = FloatArray(cap); var size = 0; private set
    fun add(x: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
    fun add(x: FloatArray) { while (size + x.size > a.size) a = a.copyOf(a.size * 2); System.arraycopy(x, 0, a, size, x.size); size += x.size }
    fun toArray(): FloatArray = a.copyOf(size)
}

/** 16 kHz mono 16-bit PCM written as it comes in (crash-safe: a raw .pcm file, no header to fix). */
class PcmWriter(val file: File) {
    private val out = FileOutputStream(file, true).buffered(1 shl 16)
    var samples = file.length() / 2; private set
    fun write(x: FloatArray) {
        val bb = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        out.write(bb.array()); samples += x.size
    }
    fun close() = out.close()

    companion object {
        fun read(file: File): FloatArray {
            val n = (file.length() / 2).toInt()
            val res = FloatArray(n)
            DataInputStream(file.inputStream().buffered(1 shl 16)).use { inp ->
                val bytes = ByteArray(n * 2); inp.readFully(bytes)
                val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until n) res[i] = sb.get(i) / 32768f
            }
            return res
        }
    }
}

/** AAC in an .m4a, for playback (and as the stored copy of recordings). */
object AacWriter {
    fun write(pcm: FloatArray, file: File, bitrate: Int = 48_000) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!.order(ByteOrder.LITTLE_ENDIAN)
                    val n = minOf(buf.capacity() / 2, pcm.size - pos, 4096)
                    if (n <= 0) {
                        codec.queueInputBuffer(idx, 0, 0, pos * 1_000_000L / SR, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        for (i in 0 until n) buf.putShort((pcm[pos + i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                        codec.queueInputBuffer(idx, 0, n * 2, pos * 1_000_000L / SR, 0)
                        pos += n
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat); muxer.start()
            } else if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release()
        muxer.stop(); muxer.release()
        tmp.renameTo(file)
    }
}

fun mix(tracks: List<FloatArray>): FloatArray {
    if (tracks.size == 1) return tracks[0]
    val n = tracks.maxOf { it.size }
    val m = FloatArray(n)
    for (t in tracks) for (i in t.indices) m[i] += t[i]
    val peak = m.maxOfOrNull { kotlin.math.abs(it) } ?: 1f
    if (peak > 1f) for (i in m.indices) m[i] /= peak
    return m
}
