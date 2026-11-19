package com.sony.walkmanpro

import com.sony.walkmanpro.audio.decoder.DsdHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DsdHeaderTest {
    @Test
    fun dsfCountsSamplesExcludingBlockPadding() {
        for (rate in listOf(2_822_400, 5_644_800, 11_289_600, 3_072_000)) {
            val bytes = dsf(rate, 2, rate.toLong(), rate / 4 + 8192L)
            assertEquals(DsdHeader(rate, 2, 1000L, false), DsdHeader.read(bytes.inputStream()))
        }
    }

    @Test
    fun rejectsTruncatedAndInconsistentDsfHeaders() {
        assertNull(DsdHeader.read(dsf(2_822_400, 2, 2_822_400, 10).inputStream()))
        assertNull(DsdHeader.read(dsf(44_100, 2, 1, 8).inputStream()))
        assertNull(DsdHeader.read(dsf(2_822_400, 2, 1, 8192).copyOf(85).inputStream()))
    }

    @Test
    fun readsDffPropertiesAndDstDurationFromFrameRate() {
        val freq = chunk("FS  ", ByteBuffer.allocate(4).putInt(2_822_400).array())
        val channels = chunk("CHNL", byteArrayOf(0, 2))
        val props = chunk("PROP", "SND ".toByteArray() + freq + channels)
        val uncompressed = "DSD ".toByteArray() + props + chunk("DSD ", ByteArray(7056))
        val frameTable = chunk("FRTE", ByteBuffer.allocate(6).putInt(150).putShort(75).array())
        val compressed = "DSD ".toByteArray() + props + chunk("DST ", frameTable)
        assertEquals(DsdHeader(2_822_400, 2, 10L, false), DsdHeader.read(chunk("FRM8", uncompressed).inputStream()))
        assertEquals(DsdHeader(2_822_400, 2, 2000L, true), DsdHeader.read(chunk("FRM8", compressed).inputStream()))
    }

    private fun dsf(rate: Int, channels: Int, samples: Long, bytes: Long) = ByteBuffer.allocate(92)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("DSD ".toByteArray()).putLong(28).putLong(92 + bytes).putLong(0)
        .put("fmt ".toByteArray()).putLong(52).putInt(1).putInt(0).putInt(2).putInt(channels)
        .putInt(rate).putInt(1).putLong(samples).putInt(4096).putInt(0)
        .put("data".toByteArray()).putLong(bytes + 12).array()

    private fun chunk(name: String, body: ByteArray) = ByteBuffer.allocate(12 + body.size + (body.size and 1))
        .put(name.toByteArray()).putLong(body.size.toLong()).put(body).array()
}
