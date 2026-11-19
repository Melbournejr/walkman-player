package com.sony.walkmanpro.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.sony.walkmanpro.audio.dsp.DspSettings
import com.sony.walkmanpro.audio.dsp.PlaybackDsp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

@UnstableApi
internal class DspAudioSink(
    delegate: AudioSink,
    private val settings: () -> DspSettings,
    private val onFormat: (Format?) -> Unit
) : ForwardingAudioSink(delegate) {
    private var dsp: PlaybackDsp? = null
    private var encoding = C.ENCODING_INVALID
    private var processed = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
    private var pending: ByteBuffer? = null
    private var pendingOriginal: ByteBuffer? = null

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        encoding = inputFormat.pcmEncoding
        dsp = PlaybackDsp(inputFormat.sampleRate, inputFormat.channelCount)
        onFormat(inputFormat)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        var output = pending
        if (pendingOriginal != null) {
            check(pendingOriginal === buffer)
        } else {
            val processor = dsp
            val bytes = sampleBytes()
            if (processor != null && bytes > 0 && buffer.hasRemaining() &&
                buffer.remaining() % (bytes * processor.channels) == 0 && processor.prepare(settings())) {
                val length = buffer.remaining()
                val start = buffer.position()
                if (processed.capacity() < length) {
                    processed = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN)
                }
                processed.clear()
                while (buffer.hasRemaining()) {
                    for (i in processor.frame.indices) processor.frame[i] = readSample(buffer)
                    processor.processFrame()
                    for (i in processor.frame.indices) writeSample(processed, processor.frame[i])
                }
                buffer.position(start)
                processed.flip()
                output = processed
            }
            // A partially consumed buffer must be submitted unchanged on subsequent calls.
            pending = output ?: buffer
            pendingOriginal = buffer
        }
        val submitted = output ?: buffer
        val start = submitted.position()
        val consumed = super.handleBuffer(submitted, presentationTimeUs, encodedAccessUnitCount)
        if (consumed) {
            buffer.position(buffer.limit())
            pending = null
            pendingOriginal = null
        } else if (submitted !== buffer) {
            buffer.position(buffer.position() + submitted.position() - start)
        }
        return consumed
    }

    override fun handleDiscontinuity() {
        dsp?.reset()
        super.handleDiscontinuity()
    }

    override fun flush() {
        pending = null
        pendingOriginal = null
        dsp?.reset()
        super.flush()
    }

    override fun reset() {
        flush()
        dsp = null
        onFormat(null)
        super.reset()
    }

    private fun sampleBytes() = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun readSample(buffer: ByteBuffer): Double = when (encoding) {
        C.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128.0
        C.ENCODING_PCM_16BIT -> buffer.short / 32768.0
        C.ENCODING_PCM_16BIT_BIG_ENDIAN -> java.lang.Short.reverseBytes(buffer.short) / 32768.0
        C.ENCODING_PCM_24BIT -> {
            val lo = buffer.get().toInt() and 255
            val mid = buffer.get().toInt() and 255
            val hi = buffer.get().toInt()
            (lo or (mid shl 8) or (hi shl 16)) / 8388608.0
        }
        C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
            val hi = buffer.get().toInt()
            val mid = buffer.get().toInt() and 255
            val lo = buffer.get().toInt() and 255
            (lo or (mid shl 8) or (hi shl 16)) / 8388608.0
        }
        C.ENCODING_PCM_32BIT -> buffer.int / 2147483648.0
        C.ENCODING_PCM_32BIT_BIG_ENDIAN -> Integer.reverseBytes(buffer.int) / 2147483648.0
        else -> buffer.float.toDouble()
    }

    private fun writeSample(buffer: ByteBuffer, sample: Double) {
        when (encoding) {
            C.ENCODING_PCM_8BIT -> buffer.put((scaled(sample, 128) + 128).toByte())
            C.ENCODING_PCM_16BIT -> buffer.putShort(scaled(sample, 32768).toShort())
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> buffer.putShort(java.lang.Short.reverseBytes(scaled(sample, 32768).toShort()))
            C.ENCODING_PCM_24BIT -> {
                val value = scaled(sample, 8388608).toInt()
                buffer.put(value.toByte())
                buffer.put((value shr 8).toByte())
                buffer.put((value shr 16).toByte())
            }
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                val value = scaled(sample, 8388608).toInt()
                buffer.put((value shr 16).toByte())
                buffer.put((value shr 8).toByte())
                buffer.put(value.toByte())
            }
            C.ENCODING_PCM_32BIT -> buffer.putInt(scaled(sample, 2147483648L).toInt())
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> buffer.putInt(Integer.reverseBytes(scaled(sample, 2147483648L).toInt()))
            C.ENCODING_PCM_FLOAT -> buffer.putFloat(sample.toFloat())
        }
    }

    private fun scaled(sample: Double, scale: Long): Long =
        (sample * scale).roundToLong().coerceIn(-scale, scale - 1)
}
