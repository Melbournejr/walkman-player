package com.sony.walkmanpro

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class WalkmanExtractorsFactory : ExtractorsFactory {
    private val defaultFactory = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> {
        val std = defaultFactory.createExtractors()
        return arrayOf(DsdAudioExtractor()) + std
    }
}

// BỘ TÁCH LUỒNG & GIẢI MÃ TRỰC TIẾP DSD (.DFF / .DSF) SANG PCM CHO EXOPLAYER
@UnstableApi
class DsdAudioExtractor : Extractor {
    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var isInitialized = false
    private var isDsfFormat = false // true = .dsf, false = .dff (FRM8)
    private var isLsbFirst = true   // .dsf thường là LSB first, .dff là MSB first

    private var channels = 2
    private var dsdSampleRate = 2822400 // Mặc định DSD64 (2.8224 MHz)
    private var pcmSampleRate = 88200   // Tần số PCM xuất ra (88.2kHz Hi-Res)
    private var decimationBytesPerSample = 4 // DSD64: 32 bits (4 bytes) mỗi kênh -> 1 mẫu PCM 88.2kHz
    private var dsfBlockSizePerChannel = 4096

    private var dataStartOffset = 0L
    private var dataSize = 0L
    private var currentBytesRead = 0L
    private var durationUs = 0L

    // Bộ lọc thông thấp 2 tầng (2-Stage IIR Low-Pass Filter) khử nhiễu siêu âm tần số cao của DSD Delta-Sigma
    private var filter1L = 0f
    private var filter2L = 0f
    private var filter1R = 0f
    private var filter2R = 0f

    companion object {
        // Bảng tra cứu nhanh tổng năng lượng 1-bit của 1 byte (-8 đến +8) cho cả MSB và LSB
        private val BYTE_WEIGHT_TABLE = FloatArray(256).apply {
            for (b in 0..255) {
                var ones = 0
                for (bit in 0..7) {
                    if (((b shr bit) and 1) == 1) ones++
                }
                // Quy chuẩn 0..8 bit 1 về dải biên độ -1.0f .. +1.0f
                this[b] = (ones - 4) / 4.0f
            }
        }
    }

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(16)
        val peeked = try {
            input.peekFully(header, 0, 16, true)
        } catch (_: Exception) {
            false
        }
        if (!peeked) return false

        val magic4 = String(header, 0, 4, Charsets.US_ASCII)
        // Kiểm tra chữ ký file .DSF ("DSD ")
        if (magic4 == "DSD ") return true
        // Kiểm tra chữ ký file .DFF ("FRM8" + ... + "DSD ")
        if (magic4 == "FRM8" && String(header, 12, 4, Charsets.US_ASCII) == "DSD ") return true

        return false
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!isInitialized) {
            if (!parseDsdHeader(input)) return Extractor.RESULT_END_OF_INPUT
            isInitialized = true
        }

        if (currentBytesRead >= dataSize) {
            return Extractor.RESULT_END_OF_INPUT
        }

        return if (isDsfFormat) {
            readAndDecodeDsfBlock(input)
        } else {
            readAndDecodeDffStream(input)
        }
    }

    private fun parseDsdHeader(input: ExtractorInput): Boolean {
        val head = ByteArray(16)
        input.readFully(head, 0, 16)
        val magic = String(head, 0, 4, Charsets.US_ASCII)

        if (magic == "DSD ") {
            // Cấu trúc file .DSF (Little-Endian)
            isDsfFormat = true
            val restHeader = ByteArray(76) // Tổng header DSF chuẩn là 28 + 52 + 12 = 92 bytes (đã đọc 16)
            input.readFully(restHeader, 0, 76)
            val full = head + restHeader
            val bb = ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN)

            val formatVersion = bb.getInt(36)
            val channelType = bb.getInt(44)
            channels = bb.getInt(48).coerceIn(1, 2)
            dsdSampleRate = bb.getInt(52).coerceAtLeast(2822400)
            val bitsPerSample = bb.getInt(56)
            isLsbFirst = (bitsPerSample == 1)
            val sampleCountPerChannel = bb.getLong(60)
            dsfBlockSizePerChannel = bb.getInt(64).coerceAtLeast(1)

            val dataChunkSize = bb.getLong(84)
            dataStartOffset = 92L
            dataSize = if (dataChunkSize > 12L) (dataChunkSize - 12L) else (input.length - 92L).coerceAtLeast(4096L)
        } else if (magic == "FRM8") {
            // Cấu trúc file .DFF / DSDIFF (Big-Endian)
            isDsfFormat = false
            isLsbFirst = false
            channels = 2
            dsdSampleRate = 2822400

            // Quét tìm các chunk PROP (chứa FS, CHNL) và chunk "DSD " (chứa dữ liệu âm thanh 1-bit)
            val scanBuf = ByteArray(8192)
            val toPeek = (input.length - 16L).coerceIn(64L, 8192L).toInt()
            input.peekFully(scanBuf, 0, toPeek, true)

            var pos = 0
            var foundDsdOffset = -1L
            var foundDsdSize = 0L

            while (pos <= toPeek - 12) {
                val c0 = scanBuf[pos].toInt().toChar()
                val c1 = scanBuf[pos + 1].toInt().toChar()
                val c2 = scanBuf[pos + 2].toInt().toChar()
                val c3 = scanBuf[pos + 3].toInt().toChar()
                val tag = "$c0$c1$c2$c3"

                if (tag == "FS  " && pos + 16 <= toPeek) {
                    val bb = ByteBuffer.wrap(scanBuf, pos + 12, 4).order(ByteOrder.BIG_ENDIAN)
                    val parsedFs = bb.int
                    if (parsedFs >= 2822400) dsdSampleRate = parsedFs
                } else if (tag == "CHNL" && pos + 14 <= toPeek) {
                    val bb = ByteBuffer.wrap(scanBuf, pos + 12, 2).order(ByteOrder.BIG_ENDIAN)
                    val ch = bb.short.toInt()
                    if (ch in 1..6) channels = ch.coerceAtMost(2)
                } else if (tag == "DSD " && pos > 4 && pos + 12 <= toPeek) {
                    val bb = ByteBuffer.wrap(scanBuf, pos + 4, 8).order(ByteOrder.BIG_ENDIAN)
                    val chunkSz = bb.long
                    if (chunkSz > 1024L) {
                        foundDsdOffset = 16L + pos + 12L
                        foundDsdSize = chunkSz
                        break
                    }
                }
                pos++
            }

            if (foundDsdOffset > 16L) {
                val skipBytes = (foundDsdOffset - 16L).toInt()
                input.skipFully(skipBytes)
                dataStartOffset = foundDsdOffset
                dataSize = foundDsdSize
            } else {
                dataStartOffset = 128L
                input.skipFully(112)
                dataSize = (input.length - 128L).coerceAtLeast(65536L)
            }
        } else {
            return false
        }

        // Cấu hình tỷ lệ giải mã Decimation:
        // DSD64 (2.8224 MHz) -> 4 bytes (32 bits) = 1 mẫu PCM 88.2 kHz
        // DSD128 (5.6448 MHz) -> 8 bytes (64 bits) = 1 mẫu PCM 88.2 kHz
        // DSD256 (11.2896 MHz) -> 16 bytes (128 bits) = 1 mẫu PCM 88.2 kHz
        pcmSampleRate = 88200
        decimationBytesPerSample = ((dsdSampleRate / 8) / pcmSampleRate).coerceIn(4, 32)

        val bytesPerSecond = (dsdSampleRate / 8).toLong() * channels.toLong()
        durationUs = if (bytesPerSecond > 0) (dataSize * 1_000_000L) / bytesPerSecond else 180_000_000L

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(pcmSampleRate)
            .setAverageBitrate(dsdSampleRate * channels)
            .build()

        trackOutput?.format(format)

        extractorOutput?.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val ratio = (timeUs.toDouble() / durationUs.coerceAtLeast(1L).toDouble()).coerceIn(0.0, 1.0)
                val rawOffset = (dataSize * ratio).toLong()
                val alignBlock = if (isDsfFormat) (dsfBlockSizePerChannel * channels).toLong() else (decimationBytesPerSample * channels).toLong()
                val alignedOffset = (rawOffset / alignBlock.coerceAtLeast(1L)) * alignBlock.coerceAtLeast(1L)
                return SeekMap.SeekPoints(SeekPoint(timeUs, dataStartOffset + alignedOffset))
            }
        })

        return true
    }

    // Giải mã luồng .DFF (Interleaved Byte: L, R, L, R...)
    private fun readAndDecodeDffStream(input: ExtractorInput): Int {
        val stepBytes = decimationBytesPerSample * channels
        val targetPcmFrames = 1024
        val bytesToRead = (stepBytes * targetPcmFrames).toLong().coerceAtMost(dataSize - currentBytesRead).toInt()
        if (bytesToRead < stepBytes) return Extractor.RESULT_END_OF_INPUT

        val rawDsd = ByteArray(bytesToRead)
        val actualRead = input.read(rawDsd, 0, bytesToRead)
        if (actualRead <= 0) return Extractor.RESULT_END_OF_INPUT

        val framesDecoded = actualRead / stepBytes
        if (framesDecoded <= 0) return Extractor.RESULT_END_OF_INPUT

        val pcmBytes = ByteArray(framesDecoded * 4)
        val pcmBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        var idx = 0
        val invDecim = 1.0f / decimationBytesPerSample.toFloat()

        for (f in 0 until framesDecoded) {
            var sumL = 0f
            var sumR = 0f
            for (b in 0 until decimationBytesPerSample) {
                if (channels == 2) {
                    val byteL = rawDsd[idx].toInt() and 0xFF
                    val byteR = rawDsd[idx + 1].toInt() and 0xFF
                    sumL += BYTE_WEIGHT_TABLE[byteL]
                    sumR += BYTE_WEIGHT_TABLE[byteR]
                    idx += 2
                } else {
                    val byteM = rawDsd[idx].toInt() and 0xFF
                    val w = BYTE_WEIGHT_TABLE[byteM]
                    sumL += w
                    sumR += w
                    idx += 1
                }
            }

            val rawL = sumL * invDecim
            val rawR = sumR * invDecim

            // Lọc thông thấp 2 tầng khử nhiễu lượng tử DSD
            filter1L += 0.35f * (rawL - filter1L)
            filter2L += 0.35f * (filter1L - filter2L)

            filter1R += 0.35f * (rawR - filter1R)
            filter2R += 0.35f * (filter1R - filter2R)

            val outShortL = (filter2L * 28000f).toInt().coerceIn(-32768, 32767).toShort()
            val outShortR = (filter2R * 28000f).toInt().coerceIn(-32768, 32767).toShort()

            pcmBuffer.putShort(outShortL)
            pcmBuffer.putShort(outShortR)
        }

        val timeUs = ((currentBytesRead.toDouble() / dataSize.coerceAtLeast(1L).toDouble()) * durationUs).toLong()
        currentBytesRead += actualRead

        val parsable = ParsableByteArray(pcmBytes)
        trackOutput?.sampleData(parsable, pcmBytes.size)
        trackOutput?.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, pcmBytes.size, 0, null)

        return Extractor.RESULT_CONTINUE
    }

    // Giải mã luồng .DSF (Block Interleaved: 4096 bytes Kênh Trái, tiếp theo 4096 bytes Kênh Phải)
    private fun readAndDecodeDsfBlock(input: ExtractorInput): Int {
        val totalBlockBytes = dsfBlockSizePerChannel * channels
        val rawBlock = ByteArray(totalBlockBytes)
        val hasRead = try {
            input.readFully(rawBlock, 0, totalBlockBytes, true)
        } catch (_: Exception) {
            false
        }
        if (!hasRead) return Extractor.RESULT_END_OF_INPUT

        val framesDecoded = dsfBlockSizePerChannel / decimationBytesPerSample
        val pcmBytes = ByteArray(framesDecoded * 4)
        val pcmBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val invDecim = 1.0f / decimationBytesPerSample.toFloat()

        for (f in 0 until framesDecoded) {
            var sumL = 0f
            var sumR = 0f
            val baseOffset = f * decimationBytesPerSample

            for (b in 0 until decimationBytesPerSample) {
                val bL = rawBlock[baseOffset + b].toInt() and 0xFF
                val bR = if (channels >= 2) rawBlock[dsfBlockSizePerChannel + baseOffset + b].toInt() and 0xFF else bL
                sumL += BYTE_WEIGHT_TABLE[bL]
                sumR += BYTE_WEIGHT_TABLE[bR]
            }

            val rawL = sumL * invDecim
            val rawR = sumR * invDecim

            filter1L += 0.35f * (rawL - filter1L)
            filter2L += 0.35f * (filter1L - filter2L)

            filter1R += 0.35f * (rawR - filter1R)
            filter2R += 0.35f * (filter1R - filter2R)

            val outShortL = (filter2L * 28000f).toInt().coerceIn(-32768, 32767).toShort()
            val outShortR = (filter2R * 28000f).toInt().coerceIn(-32768, 32767).toShort()

            pcmBuffer.putShort(outShortL)
            pcmBuffer.putShort(outShortR)
        }

        val timeUs = ((currentBytesRead.toDouble() / dataSize.coerceAtLeast(1L).toDouble()) * durationUs).toLong()
        currentBytesRead += totalBlockBytes

        val parsable = ParsableByteArray(pcmBytes)
        trackOutput?.sampleData(parsable, pcmBytes.size)
        trackOutput?.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, pcmBytes.size, 0, null)

        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        currentBytesRead = (position - dataStartOffset).coerceAtLeast(0L)
        filter1L = 0f
        filter2L = 0f
        filter1R = 0f
        filter2R = 0f
    }

    override fun release() {}
}
