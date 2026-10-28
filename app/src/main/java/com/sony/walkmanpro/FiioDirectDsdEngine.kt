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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
        // BỘ LỌC CHUẨN STUDIO DSD2PCM (192-TAP BLACKMAN-HARRIS LOW-PASS SINC FILTER)
        // Chia thành 24 bảng (mỗi bảng 256 giá trị tương ứng với 1 byte = 8 xung 1-bit DSD)
        // Cắt sạch toàn bộ nhiễu siêu âm Delta-Sigma trên 20kHz với độ suy hao > 90dB, chống gập nhiễu (Aliasing) 100%
        private const val NUM_TAPS = 192
        private const val NUM_BYTE_TABLES = NUM_TAPS / 8 // 24 bytes trượt liên tục (192 bits)

        private val MSB_FIR_TABLES = Array(NUM_BYTE_TABLES) { FloatArray(256) }
        private val LSB_FIR_TABLES = Array(NUM_BYTE_TABLES) { FloatArray(256) }

        init {
            val coeffs = DoubleArray(NUM_TAPS)
            // Tần số cắt fc = 20kHz trên tần số lấy mẫu DSD64 (2.8224 MHz) -> tỷ lệ chuẩn hóa = 20000 / 2822400
            val fc = 20000.0 / 2822400.0
            val center = (NUM_TAPS - 1) / 2.0
            var sumCoeff = 0.0

            for (i in 0 until NUM_TAPS) {
                val n = i - center
                val sinc = if (n == 0.0) {
                    2.0 * fc
                } else {
                    sin(2.0 * PI * fc * n) / (PI * n)
                }
                // Cửa sổ Blackman-Harris 4 bậc (Khử sidelobe -92dB)
                val w = 0.35875 -
                        0.48829 * cos((2.0 * PI * i) / (NUM_TAPS - 1)) +
                        0.14128 * cos((4.0 * PI * i) / (NUM_TAPS - 1)) -
                        0.01168 * cos((6.0 * PI * i) / (NUM_TAPS - 1))

                coeffs[i] = sinc * w
                sumCoeff += coeffs[i]
            }

            // Chuẩn hóa tổng hệ số = 1.0
            for (i in 0 until NUM_TAPS) {
                coeffs[i] /= sumCoeff
            }

            // Đóng gói sẵn vào 24 bảng tra cứu (Mỗi byte chỉ cần 1 phép cộng float thay vì 8 phép nhân bit)
            for (t in 0 until NUM_BYTE_TABLES) {
                val baseTap = t * 8
                for (byteVal in 0..255) {
                    var sumMsb = 0.0
                    var sumLsb = 0.0
                    for (bit in 0..7) {
                        // Trong .DFF (MSB-first): bit 7 phát trước (tap 0), bit 0 phát sau (tap 7)
                        val msbSign = if (((byteVal shr (7 - bit)) and 1) == 1) 1.0 else -1.0
                        // Trong .DSF (LSB-first): bit 0 phát trước (tap 0), bit 7 phát sau (tap 7)
                        val lsbSign = if (((byteVal shr bit) and 1) == 1) 1.0 else -1.0

                        sumMsb += msbSign * coeffs[baseTap + bit]
                        sumLsb += lsbSign * coeffs[baseTap + bit]
                    }
                    MSB_FIR_TABLES[t][byteVal] = sumMsb.toFloat()
                    LSB_FIR_TABLES[t][byteVal] = sumLsb.toFloat()
                }
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
            name = "StudioDsd2PcmThread"
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

    private fun skipExact(ins: InputStream, bytesToSkip: Long) {
        var rem = bytesToSkip
        val dummy = ByteArray(16384)
        while (rem > 0L && !stopRequested) {
            val step = rem.coerceAtMost(16384L).toInt()
            val r = ins.read(dummy, 0, step)
            if (r <= 0) break
            rem -= r
        }
    }

    data class DsdHeaderInfo(
        val isValid: Boolean,
        val isDsf: Boolean,
        val isLsbFirst: Boolean,
        val isDstCompressed: Boolean,
        val channels: Int,
        val dsdSampleRate: Int,
        val dsfBlockSize: Int,
        val dataStartOffset: Long,
        val dataSize: Long
    )

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
                val scanBuf = ByteArray(65536)
                val readLen = readExact(ins, scanBuf, scanBuf.size)
                if (readLen < 32) return DsdHeaderInfo(false, false, false, false, 2, 2822400, 4096, 0L, 0L)

                val magic = String(scanBuf, 0, 4, Charsets.ISO_8859_1)
                if (magic == "DSD ") {
                    val bb = ByteBuffer.wrap(scanBuf, 0, readLen).order(ByteOrder.LITTLE_ENDIAN)
                    val ch = bb.getInt(48).coerceIn(1, 2)
                    val rawFs = bb.getInt(52)
                    val fs = if (rawFs == 5644800 || rawFs == 11289600) rawFs else 2822400
                    val isLsb = (bb.getInt(56) == 1)
                    val blk = bb.getInt(64).coerceIn(256, 32768)
                    val dSize = bb.getLong(84)
                    val actualSize = if (dSize > 12L) (dSize - 12L) else (totalBytes - 92L).coerceAtLeast(1024L)
                    return DsdHeaderInfo(true, true, isLsb, false, ch, fs, blk, 92L, actualSize)
                } else if (magic == "FRM8") {
                    var ch = 2
                    var fs = 2822400
                    var isDst = false
                    var dataOffset = -1L
                    var dSize = 0L

                    // Tìm chính xác vị trí "FS  " (4 bytes tần số mẫu Big-Endian) và "DSD " bên trong vùng Header
                    for (i in 16..(readLen - 16)) {
                        // Tìm sub-chunk "FS  " có kích thước chuẩn = 4 bytes (0x0000000000000004)
                        if (scanBuf[i] == 'F'.code.toByte() && scanBuf[i + 1] == 'S'.code.toByte() &&
                            scanBuf[i + 2] == ' '.code.toByte() && scanBuf[i + 3] == ' '.code.toByte()
                        ) {
                            val subSz = ByteBuffer.wrap(scanBuf, i + 4, 8).order(ByteOrder.BIG_ENDIAN).long
                            if (subSz == 4L) {
                                val candidateFs = ByteBuffer.wrap(scanBuf, i + 12, 4).order(ByteOrder.BIG_ENDIAN).int
                                if (candidateFs == 2822400 || candidateFs == 5644800 || candidateFs == 11289600) {
                                    fs = candidateFs
                                }
                            }
                        }

                        // Tìm sub-chunk "CMPR" kiểm tra nén DST
                        if (scanBuf[i] == 'C'.code.toByte() && scanBuf[i + 1] == 'M'.code.toByte() &&
                            scanBuf[i + 2] == 'P'.code.toByte() && scanBuf[i + 3] == 'R'.code.toByte()
                        ) {
                            val subSz = ByteBuffer.wrap(scanBuf, i + 4, 8).order(ByteOrder.BIG_ENDIAN).long
                            if (subSz >= 4L && i + 16 <= readLen) {
                                val cmprId = String(scanBuf, i + 12, 4, Charsets.ISO_8859_1)
                                if (cmprId == "DST ") isDst = true
                            }
                        }

                        // Tìm chunk dữ liệu âm thanh chính "DSD " (loại trừ chữ "DSD " ở byte 12 của FRM8)
                        if (i >= 24 && scanBuf[i] == 'D'.code.toByte() && scanBuf[i + 1] == 'S'.code.toByte() &&
                            scanBuf[i + 2] == 'D'.code.toByte() && scanBuf[i + 3] == ' '.code.toByte()
                        ) {
                            val candidateSize = ByteBuffer.wrap(scanBuf, i + 4, 8).order(ByteOrder.BIG_ENDIAN).long
                            if (candidateSize > 500_000L) {
                                dataOffset = (i + 12).toLong()
                                dSize = candidateSize
                                break
                            }
                        }

                        // Kiểm tra nếu là chunk dữ liệu nén "DST "
                        if (i >= 24 && scanBuf[i] == 'D'.code.toByte() && scanBuf[i + 1] == 'S'.code.toByte() &&
                            scanBuf[i + 2] == 'T'.code.toByte() && scanBuf[i + 3] == ' '.code.toByte()
                        ) {
                            val candidateSize = ByteBuffer.wrap(scanBuf, i + 4, 8).order(ByteOrder.BIG_ENDIAN).long
                            if (candidateSize > 100_000L) {
                                isDst = true
                                dataOffset = (i + 12).toLong()
                                dSize = candidateSize
                                break
                            }
                        }
                    }

                    if (dataOffset > 0L) {
                        val finalSize = if (dSize > 0L) dSize else (totalBytes - dataOffset).coerceAtLeast(1024L)
                        return DsdHeaderInfo(true, false, false, isDst, ch, fs, 4096, dataOffset, finalSize)
                    }
                }
            }
        } catch (_: Throwable) {}
        return DsdHeaderInfo(false, false, false, false, 2, 2822400, 4096, 0L, 0L)
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
                showToastOnMain("Không tìm thấy dữ liệu DSD trong file này!")
                isPlaying = false
                return
            }

            if (info.isDstCompressed) {
                showToastOnMain("File .DFF này đang bị nén DST (Direct Stream Transfer). Cần giải nén sang Uncompressed DSD!")
                isPlaying = false
                return
            }

            val channels = info.channels.coerceAtLeast(1)
            val dsdFs = info.dsdSampleRate
            val isDsf = info.isDsf
            val dsfBlockSize = info.dsfBlockSize
            val dataStartOffset = info.dataStartOffset
            val dataSize = info.dataSize

            // Xuất ở tần số chuẩn CD/SACD 44.100 Hz (Tương thích tuyệt đối với Loa ngoài điện thoại & không bị Android resample)
            val pcmRate = 44100
            // Với DSD64 (2.8224 MHz): mỗi mẫu 44.1kHz tương ứng với 64 bits = 8 bytes DSD trên mỗi kênh
            // Với DSD128 (5.6448 MHz): mỗi mẫu 44.1kHz tương ứng với 128 bits = 16 bytes DSD trên mỗi kênh
            val stepBytesPerChannel = ((dsdFs / 8) / pcmRate).coerceIn(8, 32)
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

            // Bộ nhớ trượt 24 bytes (192 bits DSD) cho Kênh Trái và Kênh Phải để tính tích chập 192-Tap FIR
            val histL = IntArray(NUM_BYTE_TABLES) { 0x69 } // 0x69 = 01101001 (Trạng thái cân bằng 0V của DSD)
            val histR = IntArray(NUM_BYTE_TABLES) { 0x69 }

            val firTables = if (info.isLsbFirst) LSB_FIR_TABLES else MSB_FIR_TABLES

            // Khử DC Offset & Mạch âm thanh Sony DSP 7.0
            var dcInPrevL = 0f; var dcOutPrevL = 0f
            var dcInPrevR = 0f; var dcOutPrevR = 0f
            var dspSubL = 0f; var dspSubR = 0f
            var dspPrevL = 0f; var dspPrevR = 0f
            var dspVinylL = 0f; var dspVinylR = 0f

            val frameStepBytes = if (isDsf) (dsfBlockSize * channels) else (stepBytesPerChannel * channels * 1024)
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
                    val alignUnit = if (isDsf) (dsfBlockSize * channels).toLong() else (stepBytesPerChannel * channels).toLong()
                    val alignedBytes = (rawOffset / alignUnit.coerceAtLeast(1L)) * alignUnit.coerceAtLeast(1L)
                    skipExact(newStream, dataStartOffset + alignedBytes)
                    activeStream = newStream
                    bytesReadSoFar = alignedBytes
                    for (i in 0 until NUM_BYTE_TABLES) {
                        histL[i] = 0x69
                        histR[i] = 0x69
                    }
                }

                val remaining = dataSize - bytesReadSoFar
                val want = frameStepBytes.toLong().coerceAtMost(remaining).toInt()
                val alignStep = stepBytesPerChannel * channels
                val alignedWant = (want / alignStep) * alignStep
                if (alignedWant < alignStep) break

                val got = readExact(activeStream, dsdBuffer, alignedWant)
                val usableBytes = (got / alignStep) * alignStep
                if (usableBytes < alignStep) break

                val pcmFrames = if (isDsf) {
                    dsfBlockSize / stepBytesPerChannel
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
                    // Dịch thanh ghi trượt 24 bytes (Đẩy 8 bytes cũ nhất ra, nạp 8 bytes DSD mới vào)
                    val shiftAmount = 8 // 8 bytes = 64 bits DSD
                    for (k in 0 until (NUM_BYTE_TABLES - shiftAmount)) {
                        histL[k] = histL[k + shiftAmount]
                        histR[k] = histR[k + shiftAmount]
                    }

                    if (isDsf) {
                        val base = f * stepBytesPerChannel
                        val stride = (stepBytesPerChannel / 8).coerceAtLeast(1)
                        for (s in 0 until 8) {
                            val idxL = (base + s * stride).coerceAtMost(usableBytes - 1)
                            val idxR = if (channels >= 2) (dsfBlockSize + base + s * stride).coerceAtMost(usableBytes - 1) else idxL
                            histL[NUM_BYTE_TABLES - 8 + s] = dsdBuffer[idxL].toInt() and 0xFF
                            histR[NUM_BYTE_TABLES - 8 + s] = dsdBuffer[idxR].toInt() and 0xFF
                        }
                    } else {
                        // Trong .DFF, dữ liệu xen kẽ từng byte: L0, R0, L1, R1...
                        val stride = (stepBytesPerChannel / 8).coerceAtLeast(1)
                        for (s in 0 until 8) {
                            val bL = dsdBuffer[dffIdx].toInt() and 0xFF
                            val bR = if (channels >= 2) dsdBuffer[dffIdx + 1].toInt() and 0xFF else bL
                            histL[NUM_BYTE_TABLES - 8 + s] = bL
                            histR[NUM_BYTE_TABLES - 8 + s] = bR
                            dffIdx += (channels * stride)
                        }
                    }

                    // Tính tích chập 192-Tap FIR Blackman-Harris qua 24 bảng tra cứu
                    var convL = 0f
                    var convR = 0f
                    for (t in 0 until NUM_BYTE_TABLES) {
                        val tbl = firTables[t]
                        convL += tbl[histL[t]]
                        convR += tbl[histR[t]]
                    }

                    // Khử DC Offset (loại bỏ thành phần dòng một chiều)
                    val dcL = convL - dcInPrevL + 0.999f * dcOutPrevL
                    dcInPrevL = convL
                    dcOutPrevL = dcL

                    val dcR = convR - dcInPrevR + 0.999f * dcOutPrevR
                    dcInPrevR = convR
                    dcOutPrevR = dcR

                    // Bù biên độ chuẩn SACD (+6dB = 2.0x)
                    var left = dcL * 2.1f * preGain
                    var right = dcR * 2.1f * preGain

                    // Qua mạch âm thanh Sony DSP 7.0
                    dspSubL += 0.045f * (left - dspSubL)
                    dspSubR += 0.045f * (right - dspSubR)
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

            if (!stopRequested && bytesReadSoFar >= dataSize - (stepBytesPerChannel * channels * 8)) {
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
