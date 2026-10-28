package com.sony.walkmanpro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tanh

@UnstableApi
class FiioDirectDsdEngine(
    private val context: Context,
    private val dspProcessor: SonyRealTimeDspProcessor,
    private val onTrackEnded: () -> Unit
) {
    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var currentPositionMs: Int = 0
        private set
    @Volatile var durationMs: Int = 180000
        private set
    @Volatile var masterVolume: Float = 0.75f

    private var workerThread: Thread? = null
    @Volatile private var stopRequested: Boolean = false
    @Volatile private var pauseRequested: Boolean = false
    @Volatile private var seekTargetMs: Int = -1

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        // Bảng tra cứu chính xác tổng bit 1 của từng byte (-1.0f .. +1.0f)
        // Phép cộng tổng số bit 1 trong 1 byte là bất biến dù MSB-first (.DFF) hay LSB-first (.DSF)
        private val BYTE_INTEGRATION_LUT = FloatArray(256).apply {
            for (b in 0..255) {
                var ones = 0
                for (bit in 0..7) {
                    if (((b shr bit) and 1) == 1) ones++
                }
                // 0 bit 1 -> -1.0f, 4 bit 1 -> 0.0f (Im lặng chuẩn DSD 0x69/0x55), 8 bit 1 -> +1.0f
                this[b] = (ones - 4) * 0.25f
            }
        }
    }

    fun startDsdPlayback(uri: Uri, startMs: Int = 0) {
        stop()
        stopRequested = false
        pauseRequested = false
        isPlaying = true
        currentPositionMs = startMs.coerceAtLeast(0)
        seekTargetMs = if (startMs > 0) startMs else -1

        workerThread = Thread {
            runDsdLoop(uri)
        }.apply {
            name = "CrystalDsdAudioThread"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun togglePlayPause(): Boolean {
        if (workerThread == null || !workerThread!!.isAlive) return false
        pauseRequested = !pauseRequested
        isPlaying = !pauseRequested
        return isPlaying
    }

    fun seekTo(ms: Int) {
        currentPositionMs = ms.coerceIn(0, durationMs.coerceAtLeast(1))
        seekTargetMs = currentPositionMs
    }

    fun stop() {
        stopRequested = true
        pauseRequested = false
        isPlaying = false
        try {
            workerThread?.join(250)
        } catch (_: Throwable) {}
        workerThread = null
    }

    private fun showToastOnMain(msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

    private fun readExact(ins: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len && !stopRequested) {
            val r = ins.read(buf, total, len - total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    private fun skipExact(ins: InputStream, bytesToSkip: Long): Long {
        var rem = bytesToSkip
        val dummy = ByteArray(16384)
        var skippedTotal = 0L
        while (rem > 0L && !stopRequested) {
            val step = rem.coerceAtMost(16384L).toInt()
            val r = ins.read(dummy, 0, step)
            if (r <= 0) break
            rem -= r
            skippedTotal += r
        }
        return skippedTotal
    }

    data class DsdHeaderInfo(
        val isValid: Boolean,
        val isDsf: Boolean,
        val isDstCompressed: Boolean,
        val channels: Int,
        val dsdSampleRate: Int,
        val dsfBlockSize: Int,
        val dataStartOffset: Long,
        val dataSize: Long
    )

    // Duyệt tuần tự đúng chuẩn cây IFF Chunk của Philips DSDIFF (.DFF) và Sony (.DSF)
    // Tuyệt đối không quét mò từng byte gây ra lỗi thời lượng 2371:24 và lệch kênh!
    fun parseDsdHeaderAccurate(context: Context, uri: Uri, knownFileBytes: Long): DsdHeaderInfo {
        var totalBytes = knownFileBytes
        if (totalBytes <= 0L) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0) totalBytes = afd.length
                }
            } catch (_: Throwable) {}
        }

        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val h16 = ByteArray(16)
                if (readExact(ins, h16, 16) < 16) {
                    return DsdHeaderInfo(false, false, false, 2, 2822400, 4096, 0L, 0L)
                }

                val magic = String(h16, 0, 4, Charsets.ISO_8859_1)
                if (magic == "DSD ") {
                    val rest76 = ByteArray(76)
                    if (readExact(ins, rest76, 76) < 76) {
                        return DsdHeaderInfo(false, true, false, 2, 2822400, 4096, 0L, 0L)
                    }
                    val full = h16 + rest76
                    val bb = ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN)
                    val ch = bb.getInt(48).coerceIn(1, 6)
                    val rawFs = bb.getInt(52)
                    val fs = if (rawFs == 5644800 || rawFs == 11289600 || rawFs == 22579200) rawFs else 2822400
                    val blk = bb.getInt(64).coerceIn(256, 65536)
                    val dataChunkSz = bb.getLong(84)
                    val validSize = if (dataChunkSz in 13L..totalBytes) {
                        dataChunkSz - 12L
                    } else {
                        (totalBytes - 92L).coerceAtLeast(4096L)
                    }
                    return DsdHeaderInfo(true, true, false, ch, fs, blk, 92L, validSize)
                } else if (magic == "FRM8") {
                    val frm8Size = ByteBuffer.wrap(h16, 4, 8).order(ByteOrder.BIG_ENDIAN).long
                    if (totalBytes <= 0L && frm8Size > 0L) totalBytes = frm8Size + 12L

                    var ch = 2
                    var fs = 2822400
                    var isDst = false
                    var currentOffset = 16L
                    val chunkHdr = ByteArray(12)

                    // Duyệt chính xác từng Chunk cấp 1 bên trong FRM8 (FVER, PROP, COMT, DIIN, DSD , DST ...)
                    var safety = 0
                    while (safety < 200 && readExact(ins, chunkHdr, 12) == 12) {
                        safety++
                        val chunkId = String(chunkHdr, 0, 4, Charsets.ISO_8859_1)
                        val chunkSize = ByteBuffer.wrap(chunkHdr, 4, 8).order(ByteOrder.BIG_ENDIAN).long
                        currentOffset += 12L

                        if (chunkSize < 0L) break

                        when (chunkId) {
                            "PROP" -> {
                                if (chunkSize in 4L..262144L) {
                                    val propData = ByteArray(chunkSize.toInt())
                                    val got = readExact(ins, propData, propData.size)
                                    currentOffset += got
                                    // Phân tích các sub-chunk bên trong PROP (sau 4 byte "SND ")
                                    var p = 4
                                    while (p <= got - 12) {
                                        val subId = String(propData, p, 4, Charsets.ISO_8859_1)
                                        val subSz = ByteBuffer.wrap(propData, p + 4, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
                                        p += 12
                                        if (subSz < 0 || p + subSz > got) break
                                        when (subId) {
                                            "FS  " -> {
                                                if (subSz == 4) {
                                                    val parsedFs = ByteBuffer.wrap(propData, p, 4).order(ByteOrder.BIG_ENDIAN).int
                                                    if (parsedFs == 2822400 || parsedFs == 5644800 || parsedFs == 11289600 || parsedFs == 22579200) {
                                                        fs = parsedFs
                                                    }
                                                }
                                            }
                                            "CHNL" -> {
                                                if (subSz >= 2) {
                                                    val parsedCh = ByteBuffer.wrap(propData, p, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                                                    if (parsedCh in 1..6) ch = parsedCh
                                                }
                                            }
                                            "CMPR" -> {
                                                if (subSz >= 4) {
                                                    val cmpr = String(propData, p, 4, Charsets.ISO_8859_1)
                                                    if (cmpr == "DST ") isDst = true
                                                }
                                            }
                                        }
                                        val padSub = if (subSz % 2 != 0) subSz + 1 else subSz
                                        p += padSub
                                    }
                                    if (chunkSize % 2L != 0L) {
                                        skipExact(ins, 1L)
                                        currentOffset += 1L
                                    }
                                } else {
                                    val pad = if (chunkSize % 2L != 0L) chunkSize + 1L else chunkSize
                                    currentOffset += skipExact(ins, pad)
                                }
                            }
                            "DSD " -> {
                                // Đã tìm thấy chính xác Chunk âm thanh DSD thật sự!
                                val remainingFile = if (totalBytes > currentOffset) (totalBytes - currentOffset) else chunkSize
                                val validDataSize = if (chunkSize in 1024L..remainingFile) chunkSize else remainingFile.coerceAtLeast(4096L)
                                return DsdHeaderInfo(true, false, isDst, ch, fs, 4096, currentOffset, validDataSize)
                            }
                            "DST " -> {
                                return DsdHeaderInfo(true, false, true, ch, fs, 4096, currentOffset, chunkSize.coerceAtLeast(0L))
                            }
                            else -> {
                                val pad = if (chunkSize % 2L != 0L) chunkSize + 1L else chunkSize
                                currentOffset += skipExact(ins, pad)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return DsdHeaderInfo(false, false, false, 2, 2822400, 4096, 0L, 0L)
    }

    private fun runDsdLoop(uri: Uri) {
        var audioTrack: AudioTrack? = null
        try {
            var totalFileBytes = 0L
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0) totalFileBytes = afd.length
                }
            } catch (_: Throwable) {}

            val info = parseDsdHeaderAccurate(context, uri, totalFileBytes)
            if (!info.isValid) {
                showToastOnMain("Không tìm thấy dữ liệu DSD hợp lệ!")
                isPlaying = false
                return
            }

            if (info.isDstCompressed) {
                showToastOnMain("File .DFF này dùng nén DST. Hãy dùng file Uncompressed DSD!")
                isPlaying = false
                return
            }

            val channels = info.channels.coerceAtLeast(1)
            val dsdFs = info.dsdSampleRate
            val isDsf = info.isDsf
            val dsfBlockSize = info.dsfBlockSize
            val dataStartOffset = info.dataStartOffset
            val dataSize = info.dataSize

            // Giải mã về PCM 88.200 Hz (Tần số chuẩn Hi-Res của SACD: 2.8224 MHz / 32 = 88.2 kHz)
            // Giữ tốc độ bit-rate cao để lọc nhiễu lượng tử cực kỳ dễ dàng và mượt mà
            val pcmRate = 88200
            // DSD64 (2.8224 MHz): 4 bytes (32 bits) mỗi kênh -> 1 mẫu PCM 88.2 kHz
            // DSD128 (5.6448 MHz): 8 bytes (64 bits) mỗi kênh -> 1 mẫu PCM 88.2 kHz
            // DSD256 (11.2896 MHz): 16 bytes (128 bits) mỗi kênh -> 1 mẫu PCM 88.2 kHz
            val bytesPerSamplePerCh = ((dsdFs / 8) / pcmRate).coerceIn(4, 32)
            val bytesPerSecond = ((dsdFs / 8) * channels).toLong().coerceAtLeast(1L)
            durationMs = ((dataSize * 1000L) / bytesPerSecond).toInt().coerceAtLeast(1000)

            val minBufSize = AudioTrack.getMinBufferSize(
                pcmRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(32768)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(pcmRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack.play()

            var activeStream: InputStream = context.contentResolver.openInputStream(uri) ?: return
            skipExact(activeStream, dataStartOffset)
            var bytesReadSoFar = 0L

            // Bộ tích hợp trượt CIC (Moving Average 8-tap trên luồng 88.2kHz = 256/512 bits DSD liên tục, không bỏ sót 1 bit nào!)
            val cicWindow = 8
            val ringL = FloatArray(cicWindow)
            val ringR = FloatArray(cicWindow)
            var ringPos = 0
            var ringSumL = 0f
            var ringSumR = 0f

            // Mạch lọc thông thấp 4 bậc (4-Stage Cascade Low-Pass cắt sạch mọi tiếng sôi > 18kHz) + Khử DC Offset
            var lp1L = 0f; var lp2L = 0f; var lp3L = 0f; var lp4L = 0f
            var lp1R = 0f; var lp2R = 0f; var lp3R = 0f; var lp4R = 0f
            var dcInPrevL = 0f; var dcOutPrevL = 0f
            var dcInPrevR = 0f; var dcOutPrevR = 0f

            // Mạch âm thanh Sony DSP 7.0
            var dspSubL = 0f; var dspSubR = 0f
            var dspPrevL = 0f; var dspPrevR = 0f
            var dspVinylL = 0f; var dspVinylR = 0f

            val invBytesPerSample = 1.0f / bytesPerSamplePerCh.toFloat()
            val invCicWindow = 1.0f / cicWindow.toFloat()

            val frameStepBytes = if (isDsf) (dsfBlockSize * channels) else (bytesPerSamplePerCh * channels * 1024)
            val dsdBuffer = ByteArray(frameStepBytes)

            while (!stopRequested && bytesReadSoFar < dataSize) {
                if (pauseRequested) {
                    Thread.sleep(50)
                    continue
                }

                val targetSeek = seekTargetMs
                if (targetSeek >= 0) {
                    seekTargetMs = -1
                    try { activeStream.close() } catch (_: Throwable) {}
                    val newStream = context.contentResolver.openInputStream(uri) ?: break
                    val ratio = (targetSeek.toDouble() / durationMs.coerceAtLeast(1).toDouble()).coerceIn(0.0, 0.99)
                    val rawOffset = (dataSize * ratio).toLong()
                    // Căn chỉnh chẵn tuyệt đối theo bội số của (bytesPerSamplePerCh * channels) để không bao giờ đảo kênh L/R
                    val alignUnit = if (isDsf) (dsfBlockSize * channels).toLong() else (bytesPerSamplePerCh * channels).toLong()
                    val alignedBytes = (rawOffset / alignUnit.coerceAtLeast(1L)) * alignUnit.coerceAtLeast(1L)
                    skipExact(newStream, dataStartOffset + alignedBytes)
                    activeStream = newStream
                    bytesReadSoFar = alignedBytes

                    ringL.fill(0f); ringR.fill(0f)
                    ringSumL = 0f; ringSumR = 0f
                    lp1L = 0f; lp2L = 0f; lp3L = 0f; lp4L = 0f
                    lp1R = 0f; lp2R = 0f; lp3R = 0f; lp4R = 0f
                }

                val remaining = dataSize - bytesReadSoFar
                val want = frameStepBytes.toLong().coerceAtMost(remaining).toInt()
                val alignStep = bytesPerSamplePerCh * channels
                val alignedWant = (want / alignStep) * alignStep
                if (alignedWant < alignStep) break

                val got = readExact(activeStream, dsdBuffer, alignedWant)
                val usableBytes = (got / alignStep) * alignStep
                if (usableBytes < alignStep) break

                val pcmFrames = if (isDsf) {
                    dsfBlockSize / bytesPerSamplePerCh
                } else {
                    usableBytes / alignStep
                }
                if (pcmFrames <= 0) break

                val pcmShorts = ShortArray(pcmFrames * 2)

                val preGain = 0.78f * masterVolume
                val eqGains = dspProcessor.eq10Gains
                val eqLow = ((eqGains[0] + eqGains[1] + eqGains[2]) / 3f) * 0.05f
                val eqMid = ((eqGains[3] + eqGains[4] + eqGains[5] + eqGains[6]) / 4f) * 0.04f
                val eqHigh = ((eqGains[7] + eqGains[8] + eqGains[9]) / 3f) * 0.05f
                val bassBoost = (dspProcessor.megaBassLevel * 0.50f) + eqLow
                val clearAudio = dspProcessor.clearAudioEnabled
                val dsee = dspProcessor.dseeEnabled
                val vinyl = dspProcessor.vinylEnabled
                val bal = dspProcessor.channelBalance
                val leftPan = if (bal > 0f) (1f - bal).coerceIn(0f, 1f) else 1f
                val rightPan = if (bal < 0f) (1f + bal).coerceIn(0f, 1f) else 1f
                val phase = if (dspProcessor.phaseInvert) -1f else 1f

                var dffIdx = 0
                for (f in 0 until pcmFrames) {
                    var blockSumL = 0f
                    var blockSumR = 0f

                    if (isDsf) {
                        val base = f * bytesPerSamplePerCh
                        // Tích hợp liên tục 100% số byte trong khung (Không bỏ sót bất kỳ byte nào dù là DSD64 hay DSD128!)
                        for (s in 0 until bytesPerSamplePerCh) {
                            val idxL = (base + s).coerceAtMost(usableBytes - 1)
                            val idxR = if (channels >= 2) (dsfBlockSize + base + s).coerceAtMost(usableBytes - 1) else idxL
                            blockSumL += BYTE_INTEGRATION_LUT[dsdBuffer[idxL].toInt() and 0xFF]
                            blockSumR += BYTE_INTEGRATION_LUT[dsdBuffer[idxR].toInt() and 0xFF]
                        }
                    } else {
                        // Trong .DFF, dữ liệu xen kẽ từng byte: L, R, L, R...
                        // Cộng dồn đủ tất cả bytesPerSamplePerCh (4 bytes với DSD64, 8 bytes với DSD128)
                        for (s in 0 until bytesPerSamplePerCh) {
                            val bL = dsdBuffer[dffIdx].toInt() and 0xFF
                            val bR = if (channels >= 2) dsdBuffer[dffIdx + 1].toInt() and 0xFF else bL
                            blockSumL += BYTE_INTEGRATION_LUT[bL]
                            blockSumR += BYTE_INTEGRATION_LUT[bR]
                            dffIdx += channels
                        }
                    }

                    val sampleAvgL = blockSumL * invBytesPerSample
                    val sampleAvgR = blockSumR * invBytesPerSample

                    // Tầng 1: Bộ lọc CIC trung bình trượt 8 khung liên tiếp (Tương đương tích hợp 256 bits ở DSD64 và 512 bits ở DSD128)
                    ringSumL -= ringL[ringPos]
                    ringSumR -= ringR[ringPos]
                    ringL[ringPos] = sampleAvgL
                    ringR[ringPos] = sampleAvgR
                    ringSumL += sampleAvgL
                    ringSumR += sampleAvgR
                    ringPos = (ringPos + 1) and (cicWindow - 1)

                    val cicOutL = ringSumL * invCicWindow
                    val cicOutR = ringSumR * invCicWindow

                    // Tầng 2: Khử DC Offset (High-Pass ~12Hz)
                    val dcL = cicOutL - dcInPrevL + 0.9990f * dcOutPrevL
                    dcInPrevL = cicOutL
                    dcOutPrevL = dcL

                    val dcR = cicOutR - dcInPrevR + 0.9990f * dcOutPrevR
                    dcInPrevR = cicOutR
                    dcOutPrevR = dcR

                    // Tầng 3: Mạch lọc thông thấp 4 bậc (4-Pole Cascade Butterworth ~18kHz) triệt tiêu hoàn toàn tiếng sôi cao tần
                    lp1L += 0.28f * (dcL - lp1L)
                    lp2L += 0.28f * (lp1L - lp2L)
                    lp3L += 0.28f * (lp2L - lp3L)
                    lp4L += 0.28f * (lp3L - lp4L)

                    lp1R += 0.28f * (dcR - lp1R)
                    lp2R += 0.28f * (lp1R - lp2R)
                    lp3R += 0.28f * (lp2R - lp3R)
                    lp4R += 0.28f * (lp3R - lp4R)

                    // Bù biên độ chuẩn SACD (+6dB = 2.0x)
                    var left = lp4L * 2.15f * preGain
                    var right = lp4R * 2.15f * preGain

                    // Qua mạch âm thanh Sony DSP 7.0
                    dspSubL += 0.025f * (left - dspSubL)
                    dspSubR += 0.025f * (right - dspSubR)
                    val highL = left - dspPrevL
                    val highR = right - dspPrevR
                    dspPrevL = left
                    dspPrevR = right

                    left += dspSubL * bassBoost + left * eqMid + highL * eqHigh
                    right += dspSubR * bassBoost + right * eqMid + highR * eqHigh

                    if (clearAudio) {
                        val mid = (left + right) * 0.5f
                        val side = (left - right) * 0.5f * 1.25f
                        left = (mid + side) + dspSubL * 0.08f + highL * 0.06f
                        right = (mid - side) + dspSubR * 0.08f + highR * 0.06f
                    }
                    if (dsee) {
                        left += highL * 0.12f
                        right += highR * 0.12f
                    }
                    if (vinyl) {
                        dspVinylL += 0.35f * (left - dspVinylL)
                        dspVinylR += 0.35f * (right - dspVinylR)
                        left = tanh((dspVinylL * 0.65f + left * 0.35f + dspSubL * 0.10f) * 1.05f) * 0.92f
                        right = tanh((dspVinylR * 0.65f + right * 0.35f + dspSubR * 0.10f) * 1.05f) * 0.92f
                    } else {
                        left = tanh(left) * 0.95f
                        right = tanh(right) * 0.95f
                    }

                    left = left * leftPan * phase
                    right = right * rightPan * phase

                    pcmShorts[f * 2] = (left * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    pcmShorts[f * 2 + 1] = (right * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }

                if (!stopRequested) {
                    audioTrack.write(pcmShorts, 0, pcmShorts.size)
                    bytesReadSoFar += usableBytes
                    currentPositionMs = ((bytesReadSoFar.toDouble() / dataSize.coerceAtLeast(1L).toDouble()) * durationMs).toInt()
                }
            }

            try { activeStream.close() } catch (_: Throwable) {}

            if (!stopRequested && bytesReadSoFar >= dataSize - (bytesPerSamplePerCh * channels * 8)) {
                isPlaying = false
                mainHandler.post { onTrackEnded() }
            }
        } catch (_: InterruptedException) {
            isPlaying = false
        } catch (t: Throwable) {
            if (!stopRequested) {
                showToastOnMain("Không thể đọc file DSD: ${t.javaClass.simpleName}")
            }
            isPlaying = false
        } finally {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Throwable) {}
        }
    }
}
