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
    private var isLsbFirst = false
    private var isDstCompressed = false

    private var channels = 2
    private var dsdSampleRate = 2822400
    private var pcmSampleRate = 88200
    private var decimationBytesPerSample = 4
    private var dsfBlockSizePerChannel = 4096

    private var dataStartOffset = 0L
    private var dataSize = 0L
    private var currentBytesRead = 0L
    private var durationUs = 0L

    private var lp1L = 0f
    private var lp2L = 0f
    private var lp3L = 0f
    private var lp1R = 0f
    private var lp2R = 0f
    private var lp3R = 0f

    companion object {
        // Bảng lọc Sinc 8-bit chuẩn hóa cho MSB-first (.DFF) và LSB-first (.DSF)
        private val MSB_DECODE_TABLE = FloatArray(256)
        private val LSB_DECODE_TABLE = FloatArray(256)

        init {
            val weights = floatArrayOf(0.08f, 0.11f, 0.14f, 0.17f, 0.17f, 0.14f, 0.11f, 0.08f)
            for (b in 0..255) {
                var sumMsb = 0f
                var sumLsb = 0f
                for (bit in 0..7) {
                    val msbBit = if (((b shr (7 - bit)) and 1) == 1) 1f else -1f
                    val lsbBit = if (((b shr bit) and 1) == 1) 1f else -1f
                    sumMsb += msbBit * weights[bit]
                    sumLsb += lsbBit * weights[bit]
                }
                MSB_DECODE_TABLE[b] = sumMsb
                LSB_DECODE_TABLE[b] = sumLsb
            }
        }
    }

    override fun sniff(input: ExtractorInput): Boolean {
        return try {
            val header = ByteArray(16)
            if (!input.peekFully(header, 0, 16, true)) return false
            val magic0 = String(header, 0, 4, Charsets.ISO_8859_1)
            val magic12 = String(header, 12, 4, Charsets.ISO_8859_1)
            if (magic0 == "DSD ") return true
            if (magic0 == "FRM8" && magic12 == "DSD ") return true
            false
        } catch (_: Throwable) {
            false
        }
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        val track = output.track(0, C.TRACK_TYPE_AUDIO)
        trackOutput = track
        // Xuất Format ngay trong init() để đảm bảo MediaCodec/AudioSink không bao giờ bị lỗi thiếu Format
        val initFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(88200)
            .build()
        track.format(initFormat)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        return try {
            if (!isInitialized) {
                isInitialized = true
                if (!parseHeaderSafe(input)) {
                    emitSilenceAndFinish()
                    return Extractor.RESULT_END_OF_INPUT
                }
            }

            if (isDstCompressed) {
                // Nếu file .DFF bị nén DST độc quyền, phát 0.5s im lặng và dừng êm ái thay vì văng app
                emitSilenceAndFinish()
                return Extractor.RESULT_END_OF_INPUT
            }

            if (currentBytesRead >= dataSize) {
                return Extractor.RESULT_END_OF_INPUT
            }

            if (isDsfFormat) {
                decodeDsfChunk(input)
            } else {
                decodeDffChunk(input)
            }
        } catch (_: EOFException) {
            Extractor.RESULT_END_OF_INPUT
        } catch (_: Throwable) {
            Extractor.RESULT_END_OF_INPUT
        }
    }

    private fun emitSilenceAndFinish() {
        try {
            extractorOutput?.seekMap(SeekMap.Unseekable(1_000_000L))
            val silence = ByteArray(4096)
            val parsable = ParsableByteArray(silence)
            trackOutput?.sampleData(parsable, silence.size)
            trackOutput?.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, silence.size, 0, null)
            currentBytesRead = dataSize
        } catch (_: Throwable) {}
    }

    private fun readBytesExact(input: ExtractorInput, target: ByteArray, len: Int): Boolean {
        var offset = 0
        while (offset < len) {
            val r = input.read(target, offset, len - offset)
            if (r == C.RESULT_END_OF_INPUT || r <= 0) return false
            offset += r
        }
        return true
    }

    private fun skipBytesExact(input: ExtractorInput, count: Long): Boolean {
        var rem = count
        val scratch = ByteArray(4096)
        while (rem > 0L) {
            val step = rem.coerceAtMost(4096L).toInt()
            val r = input.read(scratch, 0, step)
            if (r == C.RESULT_END_OF_INPUT || r <= 0) return false
            rem -= r
        }
        return true
    }

    private fun parseHeaderSafe(input: ExtractorInput): Boolean {
        val h16 = ByteArray(16)
        if (!readBytesExact(input, h16, 16)) return false
        val magic = String(h16, 0, 4, Charsets.ISO_8859_1)

        if (magic == "DSD ") {
            // Định dạng Sony .DSF
            isDsfFormat = true
            val rest76 = ByteArray(76)
            if (!readBytesExact(input, rest76, 76)) return false
            val full = h16 + rest76
            val bb = ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN)

            channels = bb.getInt(48).coerceIn(1, 6)
            dsdSampleRate = bb.getInt(52).coerceIn(2822400, 22579200)
            val bitsPerSample = bb.getInt(56)
            isLsbFirst = (bitsPerSample == 1)
            dsfBlockSizePerChannel = bb.getInt(64).coerceIn(64, 65536)

            val dataChunkSz = bb.getLong(84)
            dataStartOffset = 92L
            val totalLen = input.length
            dataSize = when {
                dataChunkSz > 12L -> dataChunkSz - 12L
                totalLen > 92L -> totalLen - 92L
                else -> 100L * 1024L * 1024L
            }
        } else if (magic == "FRM8") {
            // Định dạng Philips DSDIFF (.DFF)
            isDsfFormat = false
            isLsbFirst = false
            channels = 2
            dsdSampleRate = 2822400

            val chunkHdr = ByteArray(12)
            var foundSoundData = false
            var loops = 0

            while (loops < 128 && readBytesExact(input, chunkHdr, 12)) {
                loops++
                val id = String(chunkHdr, 0, 4, Charsets.ISO_8859_1)
                val sz = ByteBuffer.wrap(chunkHdr, 4, 8).order(ByteOrder.BIG_ENDIAN).long
                if (sz < 0L) break

                when (id) {
                    "PROP" -> {
                        if (sz in 4L..131072L) {
                            val propBuf = ByteArray(sz.toInt())
                            if (!readBytesExact(input, propBuf, propBuf.size)) return false
                            parseDffProp(propBuf)
                            if (sz % 2L != 0L) skipBytesExact(input, 1L)
                        } else {
                            val pad = if (sz % 2L != 0L) sz + 1L else sz
                            if (!skipBytesExact(input, pad)) return false
                        }
                    }
                    "DSD " -> {
                        dataStartOffset = input.position
                        val totalLen = input.length
                        dataSize = when {
                            sz > 0L -> sz
                            totalLen > dataStartOffset -> totalLen - dataStartOffset
                            else -> 100L * 1024L * 1024L
                        }
                        foundSoundData = true
                        break
                    }
                    "DST " -> {
                        isDstCompressed = true
                        foundSoundData = true
                        dataStartOffset = input.position
                        dataSize = 0L
                        break
                    }
                    else -> {
                        val pad = if (sz % 2L != 0L) sz + 1L else sz
                        if (!skipBytesExact(input, pad)) return false
                    }
                }
            }
            if (!foundSoundData) return false
        } else {
            return false
        }

        pcmSampleRate = 88200
        decimationBytesPerSample = ((dsdSampleRate / 8) / pcmSampleRate).coerceIn(4, 32)

        val bytesPerSec = (dsdSampleRate / 8).toLong() * channels.toLong()
        durationUs = if (bytesPerSec > 0L && dataSize > 0L) {
            (dataSize * 1_000_000L) / bytesPerSec
        } else {
            180_000_000L
        }

        extractorOutput?.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = !isDstCompressed
            override fun getDurationUs(): Long = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val frac = (timeUs.toDouble() / durationUs.coerceAtLeast(1L).toDouble()).coerceIn(0.0, 1.0)
                val rawOff = (dataSize * frac).toLong()
                val blk = if (isDsfFormat) {
                    (dsfBlockSizePerChannel * channels).toLong()
                } else {
                    (decimationBytesPerSample * channels).toLong()
                }
                val safeBlk = blk.coerceAtLeast(1L)
                val aligned = (rawOff / safeBlk) * safeBlk
                return SeekMap.SeekPoints(SeekPoint(timeUs, dataStartOffset + aligned))
            }
        })

        return true
    }

    private fun parseDffProp(prop: ByteArray) {
        if (prop.size < 16) return
        var p = 4 // Bỏ qua "SND "
        while (p <= prop.size - 12) {
            val subId = String(prop, p, 4, Charsets.ISO_8859_1)
            val subSz = ByteBuffer.wrap(prop, p + 4, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
            p += 12
            if (subSz < 0 || p + subSz > prop.size) break

            when (subId) {
                "FS  " -> {
                    if (subSz >= 4) {
                        val fs = ByteBuffer.wrap(prop, p, 4).order(ByteOrder.BIG_ENDIAN).int
                        if (fs in 2822400..22579200) dsdSampleRate = fs
                    }
                }
                "CHNL" -> {
                    if (subSz >= 2) {
                        val ch = ByteBuffer.wrap(prop, p, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                        if (ch in 1..8) channels = ch
                    }
                }
                "CMPR" -> {
                    if (subSz >= 4) {
                        val cmpr = String(prop, p, 4, Charsets.ISO_8859_1)
                        if (cmpr == "DST ") isDstCompressed = true
                    }
                }
            }
            val pad = if (subSz % 2 != 0) subSz + 1 else subSz
            p += pad
        }
    }

    private fun decodeDffChunk(input: ExtractorInput): Int {
        val frameBytes = (decimationBytesPerSample * channels).coerceAtLeast(1)
        val remBytes = (dataSize - currentBytesRead).coerceAtLeast(0L)
        val wantBytes = ((frameBytes * 1024).toLong().coerceAtMost(remBytes)).toInt()
        val alignedWant = (wantBytes / frameBytes) * frameBytes
        if (alignedWant < frameBytes) return Extractor.RESULT_END_OF_INPUT

        val buf = ByteArray(alignedWant)
        var got = 0
        while (got < alignedWant) {
            val r = input.read(buf, got, alignedWant - got)
            if (r == C.RESULT_END_OF_INPUT || r <= 0) break
            got += r
        }

        val frames = got / frameBytes
        if (frames <= 0) return Extractor.RESULT_END_OF_INPUT

        val outBytes = ByteArray(frames * 4)
        val outBb = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        val scale = 1.0f / decimationBytesPerSample.toFloat()

        var pos = 0
        for (f in 0 until frames) {
            var accL = 0f
            var accR = 0f
            for (s in 0 until decimationBytesPerSample) {
                val bL = buf[pos].toInt() and 0xFF
                val bR = if (channels >= 2) buf[pos + 1].toInt() and 0xFF else bL
                accL += MSB_DECODE_TABLE[bL]
                accR += MSB_DECODE_TABLE[bR]
                pos += channels
            }

            val inL = accL * scale
            val inR = accR * scale

            // Lọc thông thấp 3 bậc (3-pole Butterworth/IIR) cho chất âm DSD mượt, sạch nhiễu tần số cao
            lp1L += 0.30f * (inL - lp1L)
            lp2L += 0.30f * (lp1L - lp2L)
            lp3L += 0.30f * (lp2L - lp3L)

            lp1R += 0.30f * (inR - lp1R)
            lp2R += 0.30f * (lp1R - lp2R)
            lp3R += 0.30f * (lp2R - lp3R)

            val pcmL = (lp3L * 26000f).toInt().coerceIn(-32768, 32767).toShort()
            val pcmR = (lp3R * 26000f).toInt().coerceIn(-32768, 32767).toShort()
            outBb.putShort(pcmL)
            outBb.putShort(pcmR)
        }

        val ptsUs = ((currentBytesRead.toDouble() / dataSize.coerceAtLeast(1L).toDouble()) * durationUs).toLong()
        currentBytesRead += (frames * frameBytes)

        val parsable = ParsableByteArray(outBytes)
        trackOutput?.sampleData(parsable, outBytes.size)
        trackOutput?.sampleMetadata(ptsUs, C.BUFFER_FLAG_KEY_FRAME, outBytes.size, 0, null)
        return Extractor.RESULT_CONTINUE
    }

    private fun decodeDsfChunk(input: ExtractorInput): Int {
        val totalBlk = dsfBlockSizePerChannel * channels
        val raw = ByteArray(totalBlk)
        if (!readBytesExact(input, raw, totalBlk)) return Extractor.RESULT_END_OF_INPUT

        val table = if (isLsbFirst) LSB_DECODE_TABLE else MSB_DECODE_TABLE
        val frames = (dsfBlockSizePerChannel / decimationBytesPerSample).coerceAtLeast(1)
        val outBytes = ByteArray(frames * 4)
        val outBb = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        val scale = 1.0f / decimationBytesPerSample.toFloat()

        for (f in 0 until frames) {
            var accL = 0f
            var accR = 0f
            val base = f * decimationBytesPerSample
            for (s in 0 until decimationBytesPerSample) {
                val iL = (base + s).coerceAtMost(raw.size - 1)
                val iR = if (channels >= 2) (dsfBlockSizePerChannel + base + s).coerceAtMost(raw.size - 1) else iL
                accL += table[raw[iL].toInt() and 0xFF]
                accR += table[raw[iR].toInt() and 0xFF]
            }

            val inL = accL * scale
            val inR = accR * scale

            lp1L += 0.30f * (inL - lp1L)
            lp2L += 0.30f * (lp1L - lp2L)
            lp3L += 0.30f * (lp2L - lp3L)

            lp1R += 0.30f * (inR - lp1R)
            lp2R += 0.30f * (lp1R - lp2R)
            lp3R += 0.30f * (lp2R - lp3R)

            val pcmL = (lp3L * 26000f).toInt().coerceIn(-32768, 32767).toShort()
            val pcmR = (lp3R * 26000f).toInt().coerceIn(-32768, 32767).toShort()
            outBb.putShort(pcmL)
            outBb.putShort(pcmR)
        }

        val ptsUs = ((currentBytesRead.toDouble() / dataSize.coerceAtLeast(1L).toDouble()) * durationUs).toLong()
        currentBytesRead += totalBlk

        val parsable = ParsableByteArray(outBytes)
        trackOutput?.sampleData(parsable, outBytes.size)
        trackOutput?.sampleMetadata(ptsUs, C.BUFFER_FLAG_KEY_FRAME, outBytes.size, 0, null)
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        currentBytesRead = (position - dataStartOffset).coerceAtLeast(0L)
        lp1L = 0f; lp2L = 0f; lp3L = 0f
        lp1R = 0f; lp2R = 0f; lp3R = 0f
    }

    override fun release() {}
}
