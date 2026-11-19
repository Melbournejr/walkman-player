package com.sony.walkmanpro

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.sony.walkmanpro.audio.DspAudioSink
import com.sony.walkmanpro.audio.dsp.DspSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class DspAudioSinkTest {
    @Test
    fun directPassesOriginalBufferForEverySupportedPcmWidth() {
        val encodings = listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        for (rate in listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)) {
            for (encoding in encodings) {
                val original = buffer().put(ByteArray(48) { (17 * it).toByte() }).flip() as ByteBuffer
                val input = getBytes(original)
                val sink = DspAudioSink(delegate { sent ->
                    assertSame(original, sent)
                    assertArrayEquals(input, getBytes(sent))
                    sent.position(sent.limit())
                    true
                }, { DspSettings(direct = true) }, {})
                sink.configure(rawFormat(rate, 6, encoding), 0, null)
                assertTrue(sink.handleBuffer(original, 0L, 1))
            }
        }
    }

    @Test
    fun activeFloatDspKeepsAllChannelsAndUsesTheSameBufferOnPartialWrites() {
        val original = buffer()
        for (i in 1..6) original.putFloat(i / 16f)
        original.flip()
        var pending: ByteBuffer? = null
        var settings = DspSettings(bass = 0f, clarity = false, treble = false, vinyl = false, invert = true)
        val sink = DspAudioSink(delegate { sent ->
            assertNotSame(original, sent)
            if (pending == null) {
                pending = sent
                for (i in 1..3) assertTrue(sent.float == -i / 16f)
                false
            } else {
                assertSame(pending, sent)
                for (i in 4..6) assertTrue(sent.float == -i / 16f)
                true
            }
        }, { settings }, {})
        sink.configure(rawFormat(192_000, 6, C.ENCODING_PCM_FLOAT), 0, null)
        assertFalse(sink.handleBuffer(original, 0L, 1))
        settings = settings.copy(direct = true)
        assertTrue(sink.handleBuffer(original, 0L, 1))
        assertFalse(original.hasRemaining())
    }

    private fun rawFormat(rate: Int, channels: Int, encoding: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(rate).setChannelCount(channels)
        .setPcmEncoding(encoding).build()

    private fun buffer() = ByteBuffer.allocateDirect(48).order(ByteOrder.LITTLE_ENDIAN)

    private fun getBytes(buffer: ByteBuffer): ByteArray =
        ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

    private fun delegate(onBuffer: (ByteBuffer) -> Boolean): AudioSink =
        Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "configure" -> null
                "handleBuffer" -> onBuffer(args[0] as ByteBuffer)
                else -> error("Unexpected call ${method.name}")
            }
        } as AudioSink
}
