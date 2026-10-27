package com.sony.walkmanpro

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class WalkmanExtractorsFactory : ExtractorsFactory {
    private val defaultFactory = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> {
        val std = defaultFactory.createExtractors()
        return arrayOf<Extractor>(DsdAudioExtractor()) + std
    }

    override fun createExtractors(
        uri: Uri,
        responseHeaders: MutableMap<String, MutableList<String>>
    ): Array<Extractor> {
        val std = defaultFactory.createExtractors(uri, responseHeaders)
        return arrayOf<Extractor>(DsdAudioExtractor()) + std
    }
}

@UnstableApi
class DsdAudioExtractor : Extractor {
    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var isInitialized = false
    private var isDsfFormat = false
    private var isUnsupportedDst = false

    private var channels = 2
    private var dsdSampleRate = 2822400 // DSD64 mặc định (2.8224 MHz)
    private var pcmSampleRate = 88200   // Xuất ra PCM 88.2 kHz
    private var decimationBytesPerSample = 4
    private var dsfBlockSizePerChannel = 4096

    private var dataStartOffset = 0L
    private var dataSize = 0L
    private var currentBytesRead = 0L
    private var durationUs = 0L

    private var filter1L = 0f
    private var filter2L = 0f
    private var filter1R = 0f
    private var filter2R = 0f

    companion object {
        private val BYTE_WEIGHT_TABLE = FloatArray(256).apply {
            for (b in 0..255) {
                var ones = 0
                for (bit in 0..7) {
                    if (((b shr bit) and 1) == 1) ones++
                }
                this[b] = (ones - 4) / 4.0f
            }
        }
    }

    override fun sniff(input: ExtractorInput): Boolean {
        return try {
            val header = ByteArray(16)
            val ok = input.peekFully(header, 0, 16, true)
            if (!ok) return false
            val magic4 = String(header, 0, 4, Charsets.US_ASCII)
            if (magic4 == "DSD ") return true
            if (magic4 == "FRM8" && String(header, 12, 4, Charsets.US_ASCII) == "DSD ") return true
            false
        } catch (_: Throwable) {
            false
        }
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        return try {
            if (!isInitialized) {
                if (!parseDsdHeaderSafe(input)) return Extractor.RESULT_END_OF_INPUT
                isInitialized = true
            }

            if (isUnsupportedDst || currentBytesRead >= dataSize) {
                return Extractor.RESULT_END_OF_INPUT
            }

            if (isDsfFormat) {
                readAndDecodeDsfBlockSafe(input)
            } else {
                readAndDecodeDffStreamSafe(input)
            }
        } catch (_: EOFException) {
            Extractor.RESULT_END_OF_INPUT
        } catch (e: Throwable) {
            e.printStackTrace()
            Extractor.RESULT_END_OF_INPUT
        }
    }

    private fun readFullySafe(input: ExtractorInput, buf: ByteArray, length: Int): Boolean {
        var total = 0
        while (total < length) {
            val r = input.read(buf, total, length - total)
            if (r == C.RESULT_END_OF_INPUT || r <= 0) return false
            total += r
        }
        return true
    }

    private fun skipBytesSafe(input: ExtractorInput, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        val dummy = ByteArray(4096)
        while (remaining > 0L) {
            val step = remaining.coerceAtMost(4096L).toInt()
            val r = input.read(dummy, 0, step)
            if (r == C.RESULT_END_OF_INPUT || r <= 0) return false
            remaining -= r
        }
        return true
    }

    private fun parseDsdHeaderSafe(input: ExtractorInput): Boolean {
        val head16 = ByteArray(16)
        if (!readFullySafe(input, head16, 16)) return false
        val magic = String(head16, 0, 4, Charsets.US_ASCII)

        if (magic == "DSD ") {
            // 1. Giải mã Header chuẩn .DSF (Little-Endian, tổng 92 bytes)
            isDsfFormat = true
            val rest76 = ByteArray(76)
            if (!readFullySafe(input, rest76, 76)) return false
            val full = head16 + rest76
            val bb = ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN)

            channels = bb.getInt(48).coerceIn(1, 2)
            dsdSampleRate = bb.getInt(52).coerceAtLeast(2822400)
            dsfBlockSizePerChannel = bb.getInt(64).coerceIn(256, 32768)

            val dataChunkSize = bb.getLong(84)
            dataStartOffset = 92L
            val fileLen = input.length
            dataSize = if (dataChunkSize > 12L) {
                dataChunkSize - 12L
            } else if (fileLen > 92L) {
                fileLen - 92L
            } else {
                64L * 1024L * 1024L
            }
        } else if (magic == "FRM8") {
            // 2. Giải mã tuần tự từng Chunk của chuẩn .DFF / DSDIFF (Big-Endian, không dùng peekFully)
            isDsfFormat = false
            channels = 2
            dsdSampleRate = 2822400
            var foundDsdData = false

            val chunkHeader = ByteArray(12)
            var safetyCounter = 0

            while (safetyCounter < 100 && readFullySafe(input, chunkHeader, 12)) {
                safetyCounter++
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 8).order(ByteOrder.BIG_ENDIAN).long

                if (chunkSize < 0L) break

                when (chunkId) {
                    "PROP" -> {
                        // Đọc bên trong PROP chunk (4 bytes "SND " + các sub-chunk FS, CHNL, CMPR...)
                        if (chunkSize in 4L..65536L) {
                            val propBytes = ByteArray(chunkSize.toInt())
                            if (!readFullySafe(input, propBytes, propBytes.size)) return false
                            parseDffPropSubChunks(propBytes)
                        } else {
                            if (!skipBytesSafe(input, chunkSize)) return false
                        }
                    }
                    "DSD " -> {
                        // Đã tới điểm bắt đầu dữ liệu âm thanh 1-bit DSD thật sự
                        dataStartOffset = input.position
                        val fileLen = input.length
                        dataSize = if (chunkSize > 0L) {
                            chunkSize
                        } else if (fileLen > dataStartOffset) {
                            fileLen - dataStartOffset
                        } else {
                            64L * 1024L * 1024L
                        }
                        foundDsdData = true
                        break
                    }
                    "DST " -> {
                        // File .dff bị nén DST (Direct Stream Transfer), đánh dấu kết thúc an toàn để không văng app
                        isUnsupportedDst = true
                        foundDsdData = true
                        dataStartOffset = input.position
                        dataSize = 0L
                        break
                    }
                    else -> {
                        // Bỏ qua các chunk phụ (FVER, COMT, DIIN, MANF...)
                        val paddedSize = if (chunkSize % 2L != 0L) chunkSize + 1L else chunkSize
                        if (!skipBytesSafe(input, paddedSize)) return false
                    }
                }
            }

            if (!foundDsdData) return false
        } else {
            return false
        }

        pcmSampleRate = 88200
        decimationBytesPerSample = ((dsdSampleRate / 8) / pcmSampleRate).coerceIn(4, 32)

        val bytesPerSecond = (dsdSampleRate / 8).toLong() * channels.toLong()
        durationUs = if (bytesPerSecond > 0 && dataSize > 0) {
            (dataSize * 1_000_000L) / bytesPerSecond
        } else {
            180_000_000L
        }

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
                val alignBlock = if (isDsfFormat) {
                    (dsfBlockSizePerChannel * channels).toLong()
                } else {
                    (decimationBytesPerSample * channels).toLong()
                }
                val safeAlign = alignBlock.coerceAtLeast(1L)
                val alignedOffset = (rawOffset / safeAlign) * safeAlign
                return SeekMap.SeekPoints(SeekPoint(timeUs, dataStartOffset + alignedOffset))
            }
        })

        return true
    }

    private fun parseDffPropSubChunks(propBytes: ByteArray) {
        if (propBytes.size < 16) return
        var pos = 4 // Bỏ qua 4 bytes đầu "SND "
        while (pos <= propBytes.size - 12) {
            val subId = String(propBytes, pos, 4, Charsets.US_ASCII)
            val subSize = ByteBuffer.wrap(propBytes, pos + 4, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
            pos += 12
            if (subSize < 0 || pos + subSize > propBytes.size) break

            when (subId) {
                "FS  " -> {
                    if (subSize >= 4) {
                        val fs = ByteBuffer.wrap(propBytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int
                        if (fs >= 2822400) dsdSampleRate = fs
                    }
                }
                "CHNL" -> {
                    if (subSize >= 2) {
                        val ch = ByteBuffer.wrap(propBytes, pos, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                        if (ch in 1..8) channels = ch
                    }
                }
                "CMPR" -> {
                    if (subSize >= 4) {
                        val cmpr = String(propBytes, pos, 4, Charsets.US_ASCII)
                        if (cmpr == "DST ") {
                            isUnsupportedDst = true
                        }
                    }
                }
            }
            val paddedSub = if (subSize % 2 != 0) subSize + 1 else subSize
            pos += paddedSub
        }
    }

    // Giải mã luồng .DFF an toàn tuyệt đối (Interleaved Bytes: Ch0, Ch1, ...)
    private fun readAndDecodeDffStreamSafe(input: ExtractorInput): Int {
        val stepBytes = (decimationBytesPerSample * channels).coerceAtLeast(1)
        val targetPcmFrames = 1024
        val remainingInFile = (dataSize - currentBytesRead).coerceAtLeast(0L)
        val maxBytesToRead = ((stepBytes * targetPcmFrames).toLong().coerceAtMost(remainingInFile)).toInt()
        val alignedBytesToRead = (maxBytesToRead / stepBytes) * stepBytes

        if (alignedBytesToRead < stepBytes) return Extractor.RESULT_END_OF_INPUT

        val rawDsd = ByteArray(alignedBytesToRead)
        var totalRead = 0
        while (totalRead < alignedBytesToRead) {
            val r = input.read(rawDsd, totalRead, alignedBytesToRead - totalRead)
            if (r == C.RESULT_END_OF_INPUT || r <= 0) break
            totalRead += r
        }

        val framesDecoded = totalRead / stepBytes
        if (framesDecoded <= 0) return Extractor.RESULT_END_OF_INPUT

        val pcmBytes = ByteArray(framesDecoded * 4)
        val pcmBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val invDecim = 1.0f / decimationBytesPerSample.toFloat()

        var idx = 0
        for (f in 0 until framesDecoded) {
            var sumL = 0f
            var sumR = 0f
            for (b in 0 until decimationBytesPerSample) {
                val byteL = rawDsd[idx].toInt() and 0xFF
                val byteR = if (channels >= 2) rawDsd[idx + 1].toInt() and 0xFF else byteL
                sumL += BYTE_WEIGHT_TABLE[byteL]
                sumR += BYTE_WEIGHT_TABLE[byteR]
                idx += channels
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
        currentBytesRead += (framesDecoded * stepBytes)

        val parsable = ParsableByteArray(pcmBytes)
        trackOutput?.sampleData(parsable, pcmBytes.size)
        trackOutput?.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, pcmBytes.size, 0, null)

        return Extractor.RESULT_CONTINUE
    }

    // Giải mã luồng .DSF an toàn
    private fun readAndDecodeDsfBlockSafe(input: ExtractorInput): Int {
        val totalBlockBytes = dsfBlockSizePerChannel * channels
        val rawBlock = ByteArray(totalBlockBytes)
        if (!readFullySafe(input, rawBlock, totalBlockBytes)) {
            return Extractor.RESULT_END_OF_INPUT
        }

        val framesDecoded = (dsfBlockSizePerChannel / decimationBytesPerSample).coerceAtLeast(1)
        val pcmBytes = ByteArray(framesDecoded * 4)
        val pcmBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val invDecim = 1.0f / decimationBytesPerSample.toFloat()

        for (f in 0 until framesDecoded) {
            var sumL = 0f
            var sumR = 0f
            val baseOffset = f * decimationBytesPerSample

            for (b in 0 until decimationBytesPerSample) {
                val posL = (baseOffset + b).coerceAtMost(rawBlock.size - 1)
                val posR = if (channels >= 2) (dsfBlockSizePerChannel + baseOffset + b).coerceAtMost(rawBlock.size - 1) else posL
                val bL = rawBlock[posL].toInt() and 0xFF
                val bR = rawBlock[posR].toInt() and 0xFF
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
