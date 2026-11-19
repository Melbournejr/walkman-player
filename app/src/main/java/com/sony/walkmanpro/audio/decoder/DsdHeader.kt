package com.sony.walkmanpro.audio.decoder

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class DsdHeader(
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long?,
    val compressed: Boolean
) {
    companion object {
        private const val MAX_HEADER_BYTES = 1024L * 1024
        private val validRates = setOf(
            2_822_400, 5_644_800, 11_289_600, 22_579_200,
            3_072_000, 6_144_000, 12_288_000, 24_576_000
        )

        fun read(input: InputStream): DsdHeader? = try {
            val prefix = input.readExactly(16)
            when (prefix.id()) {
                "DSD " -> readDsf(prefix, input)
                "FRM8" -> readDff(prefix, input)
                else -> null
            }
        } catch (_: EOFException) {
            null
        }

        private fun readDsf(prefix: ByteArray, input: InputStream): DsdHeader? {
            val bytes = prefix + input.readExactly(76)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (header.getLong(4) != 28L || bytes.id(28) != "fmt " || header.getLong(32) != 52L ||
                header.getInt(40) != 1 || header.getInt(44) != 0 || bytes.id(80) != "data") return null
            val channels = header.getInt(52)
            val rate = header.getInt(56)
            val bits = header.getInt(60)
            val samples = header.getLong(64)
            val blockSize = header.getInt(72)
            val dataSize = header.getLong(84)
            if (channels !in 1..6 || rate !in validRates || bits !in setOf(1, 8) ||
                samples <= 0 || samples > Long.MAX_VALUE / 1000 || blockSize <= 0 || dataSize <= 12 ||
                (samples - 1) / 8 + 1 > (dataSize - 12) / channels) return null
            return DsdHeader(rate, channels, samples * 1000 / rate, compressed = false)
        }

        private fun readDff(prefix: ByteArray, input: InputStream): DsdHeader? {
            if (prefix.id(12) != "DSD ") return null
            val formSize = ByteBuffer.wrap(prefix).getLong(4)
            if (formSize < 4 || formSize > Long.MAX_VALUE - 12) return null
            val end = formSize + 12
            var offset = 16L
            var rate = 0
            var channels = 0
            while (offset <= end - 12 && offset < MAX_HEADER_BYTES) {
                val chunk = input.readExactly(12)
                val size = ByteBuffer.wrap(chunk).getLong(4)
                offset += 12
                if (size < 0 || size > end - offset) return null
                when (chunk.id()) {
                    "PROP" -> {
                        if (size !in 4L..MAX_HEADER_BYTES - offset) return null
                        val properties = input.readExactly(size.toInt())
                        if (properties.id() != "SND ") return null
                        var pos = 4
                        while (pos <= properties.size - 12) {
                            val id = properties.id(pos)
                            val length = ByteBuffer.wrap(properties).getLong(pos + 4)
                            pos += 12
                            if (length < 0 || length > properties.size - pos) return null
                            val data = ByteBuffer.wrap(properties, pos, length.toInt())
                            if (id == "FS  " && length == 4L) rate = data.int
                            if (id == "CHNL" && length >= 2L) channels = data.short.toInt()
                            pos += length.toInt() + (length and 1).toInt()
                            if (pos > properties.size) return null
                        }
                    }
                    "DSD ", "DST " -> {
                        if (rate !in validRates || channels !in 1..32 || size <= 0) return null
                        val compressed = chunk.id() == "DST "
                        val duration = if (compressed) {
                            if (size < 18) return null
                            val frte = input.readExactly(18)
                            val data = ByteBuffer.wrap(frte)
                            val frames = data.getInt(12).toLong() and 0xffffffffL
                            val fps = data.getShort(16).toInt() and 0xffff
                            if (frte.id() == "FRTE" && data.getLong(4) == 6L && fps > 0) frames * 1000 / fps else null
                        } else {
                            val bytesPerSecond = rate.toLong() * channels / 8
                            size / bytesPerSecond * 1000 + size % bytesPerSecond * 1000 / bytesPerSecond
                        }
                        return DsdHeader(rate, channels, duration, compressed)
                    }
                    else -> {
                        if (size > MAX_HEADER_BYTES - offset) return null
                        input.skipExactly(size)
                    }
                }
                if (size and 1L != 0L) input.skipExactly(1)
                offset += size + (size and 1)
            }
            return null
        }

        private fun ByteArray.id(offset: Int = 0) = String(this, offset, 4, Charsets.US_ASCII)

        private fun InputStream.readExactly(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = read(bytes, offset, count - offset)
                if (read <= 0) throw EOFException()
                offset += read
            }
            return bytes
        }

        private fun InputStream.skipExactly(count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = skip(remaining)
                if (skipped > 0) remaining -= skipped
                else if (read() < 0) throw EOFException()
                else remaining--
            }
        }
    }
}
