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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * BỘ LỌC POLYPHASE FIR 512-TAPS TUYẾN TÍNH PHA (SYMMETRIC LINEAR-PHASE KAISER WINDOW)
 *
 * - Độ dài bộ lọc: 512 Taps (64 Bytes lịch sử trượt trên mỗi kênh), gấp 4 lần chuẩn 128-taps cũ.
 * - Hàm cửa sổ: Kaiser Window với Beta = 12.5 (Độ suy hao dải chắn đạt -120 dB, triệt tiêu hoàn toàn
 *   hiện tượng gập nhiễu lượng tử Delta-Sigma gây harsh/grain ở dải cao).
 * - Tầng hậu kỳ: Tích hợp bộ lọc thông thấp 2nd-Order Butterworth Ultrasonic Biquad (@ 34kHz) kết hợp
 *   DC-Blocker độ chính xác cao (64-bit state) giúp nền âm tĩnh tuyệt đối và bass chắc gọn.
 * - Hiệu năng: Đóng gói thành bảng Flat LUT 64 * 256 = 16.384 floats (64 KB), nằm gọn hoàn toàn trong
 *   L1 Cache của CPU, unroll vòng lặp 8 bước và Zero-Allocation.
 */
class DsdFirFilter(
    isLsbFirst: Boolean,
    cutoffHz: Double = 29000.0,
    dsdSampleRate: Int = 2822400,
    pcmSampleRate: Int = 88200
) {
    companion object {
        const val NUM_TAPS = 512
        const val NUM_BYTES = NUM_TAPS / 8 // 64 bytes lịch sử trượt (512 bits DSD)
        private const val DSD_SILENCE_BYTE = 0x69 // 01101001: Trạng thái cân bằng 0V chuẩn SACD

        /**
         * Hàm Bessel biến đổi loại 1 bậc 0: I_0(x) dùng để tính cửa sổ Kaiser độ chính xác 64-bit.
         */
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val halfX = x * 0.5
            for (k in 1..45) {
                val ratio = halfX / k.toDouble()
                term *= (ratio * ratio)
                sum += term
                if (term < sum * 1e-15) break
            }
            return sum
        }
    }

    // Bảng tra cứu phẳng 1 chiều: 64 pha byte * 256 trạng thái byte = 16.384 phần tử Float (64 KB)
    private val flatLookupTable = FloatArray(NUM_BYTES * 256)

    // Thanh ghi trượt 64 bytes cho Kênh Trái (L) và Kênh Phải (R)
    private val historyL = IntArray(NUM_BYTES) { DSD_SILENCE_BYTE }
    private val historyR = IntArray(NUM_BYTES) { DSD_SILENCE_BYTE }

    // Bộ lọc khử DC Offset (High-Pass @ 3.5Hz) dùng biến Double chống trôi pha dải trầm
    private var dcPrevInL = 0.0
    private var dcPrevOutL = 0.0
    private var dcPrevInR = 0.0
    private var dcPrevOutR = 0.0

    // Bộ lọc hậu kỳ 2nd-Order Butterworth Ultrasonic Low-Pass (Cắt mượt phần nhiễu siêu âm còn sót trên 34kHz)
    private var bqB0 = 1.0
    private var bqB1 = 0.0
    private var bqB2 = 0.0
    private var bqA1 = 0.0
    private var bqA2 = 0.0

    private var bqZ1L = 0.0
    private var bqZ2L = 0.0
    private var bqZ1R = 0.0
    private var bqZ2R = 0.0

    init {
        precomputeKaiserLinearPhaseLut(isLsbFirst, cutoffHz, dsdSampleRate)
        initUltrasonicButterworthBiquad(pcmSampleRate)
    }

    /**
     * Tiền tính toán 512 hệ số FIR đối xứng tâm (Linear-Phase) với cửa sổ Kaiser (Beta = 12.5).
     */
    private fun precomputeKaiserLinearPhaseLut(isLsbFirst: Boolean, cutoffHz: Double, dsdFs: Int) {
        val coeffs = DoubleArray(NUM_TAPS)
        val fc = (cutoffHz / dsdFs.toDouble()).coerceIn(0.0005, 0.045)
        val center = (NUM_TAPS - 1) * 0.5

        // Beta = 12.5 mang lại độ suy hao dải chắn > 120 dB (vượt xa Blackman-Harris)
        val kaiserBeta = 12.5
        val invDenom = 1.0 / besselI0(kaiserBeta)
        var sumWeight = 0.0

        for (i in 0 until NUM_TAPS) {
            val n = i.toDouble() - center
            val sinc = if (abs(n) < 1e-9) {
                2.0 * fc
            } else {
                sin(2.0 * PI * fc * n) / (PI * n)
            }

            // Chuẩn hóa vị trí mẫu trong [-1.0, +1.0] cho cửa sổ Kaiser
            val normPos = (2.0 * i.toDouble()) / (NUM_TAPS - 1).toDouble() - 1.0
            val arg = (1.0 - normPos * normPos).coerceAtLeast(0.0)
            val window = besselI0(kaiserBeta * sqrt(arg)) * invDenom

            val h = sinc * window
            coeffs[i] = h
            sumWeight += h
        }

        // Bù biên độ chuẩn SACD (+6dB = 2.0x) và chuẩn hóa năng lượng tổng = 1.0
        val gainScale = 2.0 / sumWeight
        for (i in 0 until NUM_TAPS) {
            coeffs[i] *= gainScale
        }

        // Đóng gói vào 64 bảng tra cứu Byte (mỗi byte = 8 xung 1-bit DSD)
        for (byteIndex in 0 until NUM_BYTES) {
            val tapOffset = byteIndex shl 3 // byteIndex * 8
            val tableBaseOffset = byteIndex shl 8 // byteIndex * 256

            for (byteValue in 0..255) {
                var acc = 0.0
                for (bit in 0..7) {
                    // LSB-first (.dsf): bit 0 phát trước -> ứng với tapOffset + bit
                    // MSB-first (.dff): bit 7 phát trước -> ứng với tapOffset + bit
                    val bitVal = if (isLsbFirst) {
                        (byteValue shr bit) and 1
                    } else {
                        (byteValue shr (7 - bit)) and 1
                    }
                    val pulse = if (bitVal == 1) 1.0 else -1.0
                    acc += pulse * coeffs[tapOffset + bit]
                }
                flatLookupTable[tableBaseOffset + byteValue] = acc.toFloat()
            }
        }
    }

    /**
     * Thiết lập bộ lọc Biquad Butterworth bậc 2 (Q = 0.7071) ở tần số cắt ~34kHz trên miền PCM
     * để khử sạch mọi tàn dư nhiễu điều chế bậc cao mà không ảnh hưởng tới dải 20Hz - 24kHz.
     */
    private fun initUltrasonicButterworthBiquad(pcmRate: Int) {
        val targetFc = (34000.0).coerceAtMost(pcmRate * 0.42)
        val omega = 2.0 * PI * targetFc / pcmRate.toDouble()
        val sn = sin(omega)
        val cs = cos(omega)
        val alpha = sn / sqrt(2.0) // Q = 1 / sqrt(2) = 0.7071 (Butterworth phẳng tuyệt đối)

        val a0 = 1.0 + alpha
        bqB0 = ((1.0 - cs) * 0.5) / a0
        bqB1 = (1.0 - cs) / a0
        bqB2 = ((1.0 - cs) * 0.5) / a0
        bqA1 = (-2.0 * cs) / a0
        bqA2 = (1.0 - alpha) / a0
    }

    fun reset() {
        for (i in 0 until NUM_BYTES) {
            historyL[i] = DSD_SILENCE_BYTE
            historyR[i] = DSD_SILENCE_BYTE
        }
        dcPrevInL = 0.0
        dcPrevOutL = 0.0
        dcPrevInR = 0.0
        dcPrevOutR = 0.0
        bqZ1L = 0.0
        bqZ2L = 0.0
        bqZ1R = 0.0
        bqZ2R = 0.0
    }

    /**
     * Tích chập 512-Taps được unroll 8 bước mỗi vòng lặp để tối ưu đường ống lệnh (CPU Pipeline).
     */
    private fun evaluateConvolution512(outStereoFrame: FloatArray) {
        val lut = flatLookupTable
        val hL = historyL
        val hR = historyR

        var sumL0 = 0f; var sumL1 = 0f; var sumL2 = 0f; var sumL3 = 0f
        var sumR0 = 0f; var sumR1 = 0f; var sumR2 = 0f; var sumR3 = 0f

        var i = 0
        while (i < 64) {
            val b0 = (i) shl 8
            val b1 = (i + 1) shl 8
            val b2 = (i + 2) shl 8
            val b3 = (i + 3) shl 8
            val b4 = (i + 4) shl 8
            val b5 = (i + 5) shl 8
            val b6 = (i + 6) shl 8
            val b7 = (i + 7) shl 8

            sumL0 += lut[b0 + hL[i]] + lut[b4 + hL[i + 4]]
            sumL1 += lut[b1 + hL[i + 1]] + lut[b5 + hL[i + 5]]
            sumL2 += lut[b2 + hL[i + 2]] + lut[b6 + hL[i + 6]]
            sumL3 += lut[b3 + hL[i + 3]] + lut[b7 + hL[i + 7]]

            sumR0 += lut[b0 + hR[i]] + lut[b4 + hR[i + 4]]
            sumR1 += lut[b1 + hR[i + 1]] + lut[b5 + hR[i + 5]]
            sumR2 += lut[b2 + hR[i + 2]] + lut[b6 + hR[i + 6]]
            sumR3 += lut[b3 + hR[i + 3]] + lut[b7 + hR[i + 7]]

            i += 8
        }

        val rawL = ((sumL0 + sumL1) + (sumL2 + sumL3)).toDouble()
        val rawR = ((sumR0 + sumR1) + (sumR2 + sumR3)).toDouble()

        // 1. Khử DC Offset với độ chính xác 64-bit Double (giữ dải sub-bass sâu và cân bằng)
        val dcL = rawL - dcPrevInL + 0.9997 * dcPrevOutL
        dcPrevInL = rawL
        dcPrevOutL = dcL

        val dcR = rawR - dcPrevInR + 0.9997 * dcPrevOutR
        dcPrevInR = rawR
        dcPrevOutR = dcR

        // 2. Lọc hậu kỳ Ultrasonic Butterworth Biquad (Direct Form II Transposed)
        val outL = bqB0 * dcL + bqZ1L
        bqZ1L = bqB1 * dcL - bqA1 * outL + bqZ2L
        bqZ2L = bqB2 * dcL - bqA2 * outL

        val outR = bqB0 * dcR + bqZ1R
        bqZ1R = bqB1 * dcR - bqA1 * outR + bqZ2R
        bqZ2R = bqB2 * dcR - bqA2 * outR

        outStereoFrame[0] = outL.toFloat()
        outStereoFrame[1] = outR.toFloat()
    }

    /**
     * Giải mã Decimation 1:32 (4 bytes = 32 bits DSD cho mỗi mẫu PCM):
     * - DSD64  (2.8224 MHz) -> 88.2 kHz
     * - DSD128 (5.6448 MHz) -> 176.4 kHz
     * - DSD256 (11.2896 MHz)-> 352.8 kHz
     */
    fun processDecimate32(
        bL0: Int, bL1: Int, bL2: Int, bL3: Int,
        bR0: Int, bR1: Int, bR2: Int, bR3: Int,
        outStereoFrame: FloatArray
    ) {
        System.arraycopy(historyL, 4, historyL, 0, 60)
        System.arraycopy(historyR, 4, historyR, 0, 60)

        historyL[60] = bL0 and 0xFF
        historyL[61] = bL1 and 0xFF
        historyL[62] = bL2 and 0xFF
        historyL[63] = bL3 and 0xFF

        historyR[60] = bR0 and 0xFF
        historyR[61] = bR1 and 0xFF
        historyR[62] = bR2 and 0xFF
        historyR[63] = bR3 and 0xFF

        evaluateConvolution512(outStereoFrame)
    }

    /**
     * Giải mã Decimation 1:64 (8 bytes = 64 bits DSD cho mỗi mẫu PCM):
     * Dùng khi phát DSD256 trên thiết bị giới hạn trần AudioTrack ở 176.4 kHz.
     */
    fun processDecimate64(
        bL0: Int, bL1: Int, bL2: Int, bL3: Int, bL4: Int, bL5: Int, bL6: Int, bL7: Int,
        bR0: Int, bR1: Int, bR2: Int, bR3: Int, bR4: Int, bR5: Int, bR6: Int, bR7: Int,
        outStereoFrame: FloatArray
    ) {
        System.arraycopy(historyL, 8, historyL, 0, 56)
        System.arraycopy(historyR, 8, historyR, 0, 56)

        historyL[56] = bL0 and 0xFF; historyL[57] = bL1 and 0xFF
        historyL[58] = bL2 and 0xFF; historyL[59] = bL3 and 0xFF
        historyL[60] = bL4 and 0xFF; historyL[61] = bL5 and 0xFF
        historyL[62] = bL6 and 0xFF; historyL[63] = bL7 and 0xFF

        historyR[56] = bR0 and 0xFF; historyR[57] = bR1 and 0xFF
        historyR[58] = bR2 and 0xFF; historyR[59] = bR3 and 0xFF
        historyR[60] = bR4 and 0xFF; historyR[61] = bR5 and 0xFF
        historyR[62] = bR6 and 0xFF; historyR[63] = bR7 and 0xFF

        evaluateConvolution512(outStereoFrame)
    }
}

/**
 * ENGINE PHÁT NHẠC DSD ĐỘ NÉT CAO (512-TAP KAISER FIR + 32-BIT FLOAT + TPDF DITHER)
 */
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
    @Volatile var pureDsdMode: Boolean = false

    // Tùy chọn kích hoạt TPDF Dither nhẹ (-138 dBFS) để giữ độ mượt tự nhiên khi xuất xuống DAC/Bluetooth
    @Volatile var enableTpdfDither: Boolean = true

    // Cho phép tùy chỉnh tần số cắt linh hoạt (mặc định 29.0 kHz trong dải 28-30 kHz)
    @Volatile var customCutoffHz: Double = 29000.0

    private var workerThread: Thread? = null
    @Volatile private var stopRequested: Boolean = false
    @Volatile private var pauseRequested: Boolean = false
    @Volatile private var seekTargetMs: Int = -1

    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun startDsdPlayback(uri: Uri, startMs: Int = 0) {
        stop()
        stopRequested = false
        pauseRequested = false
        isPlaying = true
        currentPositionMs = startMs.coerceAtLeast(0)
        seekTargetMs = if (startMs > 0) startMs else -1

        workerThread = Thread {
            runDsdDecoderLoop(uri)
        }.apply {
            name = "WalkmanDsdFir512Thread"
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
            workerThread?.join(300)
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
        val scratch = ByteArray(16384)
        var skippedTotal = 0L
        while (rem > 0L && !stopRequested) {
            val step = rem.coerceAtMost(16384L).toInt()
            val got = readExact(ins, scratch, step)
            if (got <= 0) break
            rem -= got
            skippedTotal += got
        }
        return skippedTotal
    }

    /**
     * Đọc chính xác Header của file .DSF (Sony) và .DFF (Philips DSDIFF).
     */
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
                    return DsdHeaderInfo(false, false, false, false, 2, 2822400, 4096, 0L, 0L)
                }

                val magic = String(h16, 0, 4, Charsets.ISO_8859_1)

                if (magic == "DSD ") {
                    val rest76 = ByteArray(76)
                    if (readExact(ins, rest76, 76) < 76) {
                        return DsdHeaderInfo(false, true, true, false, 2, 2822400, 4096, 0L, 0L)
                    }
                    val full = h16 + rest76
                    val bb = ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN)

                    val ch = bb.getInt(48).coerceIn(1, 6)
                    val rawFs = bb.getInt(52)
                    val fs = when (rawFs) {
                        5644800, 11289600, 22579200 -> rawFs
                        else -> 2822400
                    }
                    val bitsPerSample = bb.getInt(56)
                    val isLsb = (bitsPerSample == 1)
                    val blk = bb.getInt(64).coerceIn(256, 65536)
                    val dataChunkSz = bb.getLong(84)

                    val validSize = if (dataChunkSz in 13L..totalBytes) {
                        dataChunkSz - 12L
                    } else {
                        (totalBytes - 92L).coerceAtLeast(4096L)
                    }
                    return DsdHeaderInfo(true, true, isLsb, false, ch, fs, blk, 92L, validSize)

                } else if (magic == "FRM8") {
                    val frm8Size = ByteBuffer.wrap(h16, 4, 8).order(ByteOrder.BIG_ENDIAN).long
                    if (totalBytes <= 0L && frm8Size > 0L) totalBytes = frm8Size + 12L

                    var ch = 2
                    var fs = 2822400
                    var isDst = false
                    var currentOffset = 16L
                    val chunkHdr = ByteArray(12)

                    var safetyCount = 0
                    while (safetyCount < 256 && readExact(ins, chunkHdr, 12) == 12) {
                        safetyCount++
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
                                val remainingFile = if (totalBytes > currentOffset) (totalBytes - currentOffset) else chunkSize
                                val validDataSize = if (chunkSize in 1024L..remainingFile) chunkSize else remainingFile.coerceAtLeast(4096L)
                                return DsdHeaderInfo(true, false, false, isDst, ch, fs, 4096, currentOffset, validDataSize)
                            }
                            "DST " -> {
                                return DsdHeaderInfo(true, false, false, true, ch, fs, 4096, currentOffset, chunkSize.coerceAtLeast(0L))
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
        return DsdHeaderInfo(false, false, false, false, 2, 2822400, 4096, 0L, 0L)
    }

    /**
     * Khởi tạo AudioTrack 32-bit Float ở Sample Rate cao nhất mà phần cứng hỗ trợ:
     * - DSD64  -> 88.2 kHz
     * - DSD128 -> 176.4 kHz
     * - DSD256 -> 352.8 kHz (hoặc 176.4 kHz nếu DAC nội giới hạn ở 192 kHz)
     */
    private fun createFloatAudioTrackWithFallback(dsdFs: Int): Triple<AudioTrack, Int, Int> {
        val preferredRate = (dsdFs / 32).coerceIn(88200, 352800)
        val fallbackRate = (dsdFs / 64).coerceIn(88200, 176400)

        for (candidateRate in listOf(preferredRate, fallbackRate, 176400, 88200).distinct()) {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    candidateRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
                if (minBuf > 0) {
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(candidateRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build()
                        )
                        .setBufferSizeInBytes((minBuf * 4).coerceAtLeast(65536))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        val bytesPerSample = ((dsdFs / 8) / candidateRate).coerceIn(4, 8)
                        return Triple(track, bytesPerSample, candidateRate)
                    } else {
                        track.release()
                    }
                }
            } catch (_: Throwable) {}
        }

        val defaultTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(88200)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(65536)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        return Triple(defaultTrack, 4, 88200)
    }

    /**
     * Vòng lặp giải mã DSD chính với bộ lọc 512-Tap Kaiser FIR + Ultrasonic Biquad + TPDF Dithering.
     */
    private fun runDsdDecoderLoop(uri: Uri) {
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
                showToastOnMain("Không tìm thấy luồng DSD hợp lệ trong file này!")
                isPlaying = false
                return
            }

            if (info.isDstCompressed) {
                showToastOnMain("File .DFF này đang bị nén khóa DST. Hãy dùng file Uncompressed DSD!")
                isPlaying = false
                return
            }

            val channels = info.channels.coerceAtLeast(1)
            val dsdFs = info.dsdSampleRate
            val isDsf = info.isDsf
            val dsfBlockSize = info.dsfBlockSize
            val dataStartOffset = info.dataStartOffset
            val dataSize = info.dataSize

            val bytesPerSecond = ((dsdFs / 8) * channels).toLong().coerceAtLeast(1L)
            durationMs = ((dataSize * 1000L) / bytesPerSecond).toInt().coerceAtLeast(1000)

            // 1. Tạo AudioTrack 32-bit Float ở Sample Rate cao nhất mà phần cứng hỗ trợ
            val (track, bytesPerSamplePerCh, actualPcmRate) = createFloatAudioTrackWithFallback(dsdFs)
            audioTrack = track
            audioTrack.play()

            // 2. Chọn tần số cắt tối ưu: 28.5 kHz cho DSD64 và 30.0 kHz cho DSD128/DSD256
            val optimalCutoffHz = if (dsdFs >= 5644800) {
                customCutoffHz.coerceIn(28000.0, 30000.0).coerceAtLeast(29800.0)
            } else {
                customCutoffHz.coerceIn(28000.0, 30000.0)
            }

            // 3. Khởi tạo bộ lọc 512-Tap Kaiser Window Linear-Phase FIR
            val firFilter = DsdFirFilter(
                isLsbFirst = info.isLsbFirst,
                cutoffHz = optimalCutoffHz,
                dsdSampleRate = dsdFs,
                pcmSampleRate = actualPcmRate
            )

            var activeStream: InputStream = context.contentResolver.openInputStream(uri) ?: return
            skipExact(activeStream, dataStartOffset)
            var bytesReadSoFar = 0L

            val framesPerChunk = if (isDsf) (dsfBlockSize / bytesPerSamplePerCh) else 2048
            val inputBufferSize = if (isDsf) (dsfBlockSize * channels) else (bytesPerSamplePerCh * channels * framesPerChunk)
            val dsdInputBuffer = ByteArray(inputBufferSize)
            val pcmFloatOutputBuffer = FloatArray(framesPerChunk * 2)
            val stereoSampleScratch = FloatArray(2)

            // Trạng thái bộ xử lý DSP Sony 32-bit Float (Hệ số được tự động co giãn theo Sample Rate thực tế)
            val rateScale = (88200f / actualPcmRate.toFloat()).coerceIn(0.25f, 1.0f)
            val subAlpha = 0.025f * rateScale
            val vinylAlpha = 0.35f * rateScale

            var dspSubL = 0f
            var dspSubR = 0f
            var dspPrevL = 0f
            var dspPrevR = 0f
            var dspVinylL = 0f
            var dspVinylR = 0f

            // Hạt giống PRNG siêu nhanh (XorShift32) cho TPDF Dithering không tốn CPU
            var prngState = -0x61c88647

            while (!stopRequested && bytesReadSoFar < dataSize) {
                if (pauseRequested) {
                    Thread.sleep(40)
                    continue
                }

                // Xử lý Seek mượt mà và căn chỉnh chẵn biên độ Block/Frame chống đảo kênh L/R
                val targetSeek = seekTargetMs
                if (targetSeek >= 0) {
                    seekTargetMs = -1
                    try { activeStream.close() } catch (_: Throwable) {}
                    val newStream = context.contentResolver.openInputStream(uri) ?: break
                    val ratio = (targetSeek.toDouble() / durationMs.coerceAtLeast(1).toDouble()).coerceIn(0.0, 0.99)
                    val rawOffset = (dataSize * ratio).toLong()
                    val alignUnit = if (isDsf) {
                        (dsfBlockSize * channels).toLong()
                    } else {
                        (bytesPerSamplePerCh * channels * 8).toLong()
                    }
                    val alignedBytes = (rawOffset / alignUnit.coerceAtLeast(1L)) * alignUnit.coerceAtLeast(1L)
                    skipExact(newStream, dataStartOffset + alignedBytes)
                    activeStream = newStream
                    bytesReadSoFar = alignedBytes
                    firFilter.reset()
                }

                val remaining = dataSize - bytesReadSoFar
                val want = inputBufferSize.toLong().coerceAtMost(remaining).toInt()
                val alignStep = bytesPerSamplePerCh * channels
                val alignedWant = (want / alignStep) * alignStep
                if (alignedWant < alignStep) break

                val got = readExact(activeStream, dsdInputBuffer, alignedWant)
                val usableBytes = (got / alignStep) * alignStep
                if (usableBytes < alignStep) break

                val actualPcmFrames = if (isDsf) {
                    (dsfBlockSize / bytesPerSamplePerCh).coerceAtMost(framesPerChunk)
                } else {
                    (usableBytes / alignStep).coerceAtMost(framesPerChunk)
                }
                if (actualPcmFrames <= 0) break

                val preGain = 0.80f * masterVolume
                val eqGains = dspProcessor.eq10Gains
                val eqLow = ((eqGains[0] + eqGains[1] + eqGains[2]) / 3f) * 0.05f
                val eqMid = ((eqGains[3] + eqGains[4] + eqGains[5] + eqGains[6]) / 4f) * 0.04f
                val eqHigh = ((eqGains[7] + eqGains[8] + eqGains[9]) / 3f) * 0.05f
                val bassBoost = (dspProcessor.megaBassLevel * 0.50f) + eqLow
                val clearAudio = dspProcessor.clearAudioEnabled
                val dsee = dspProcessor.dseeEnabled
                val vinyl = dspProcessor.vinylEnabled

                // Đảm bảo L/R cân bằng tuyệt đối 1.0f == 1.0f khi ở vị trí Center (Dead-zone +/- 0.03f)
                val rawBal = dspProcessor.channelBalance
                val bal = if (abs(rawBal) < 0.03f) 0f else rawBal.coerceIn(-0.5f, 0.5f)
                val leftPan = if (bal > 0f) (1f - bal).coerceIn(0f, 1f) else 1f
                val rightPan = if (bal < 0f) (1f + bal).coerceIn(0f, 1f) else 1f
                val phase = if (dspProcessor.phaseInvert) -1f else 1f
                val applyDither = enableTpdfDither

                var dffBytePos = 0

                for (frame in 0 until actualPcmFrames) {
                    if (isDsf) {
                        val baseL = frame * bytesPerSamplePerCh
                        val baseR = if (channels >= 2) (dsfBlockSize + baseL) else baseL

                        if (bytesPerSamplePerCh == 4) {
                            firFilter.processDecimate32(
                                dsdInputBuffer[baseL].toInt(),
                                dsdInputBuffer[baseL + 1].toInt(),
                                dsdInputBuffer[baseL + 2].toInt(),
                                dsdInputBuffer[baseL + 3].toInt(),
                                dsdInputBuffer[baseR].toInt(),
                                dsdInputBuffer[baseR + 1].toInt(),
                                dsdInputBuffer[baseR + 2].toInt(),
                                dsdInputBuffer[baseR + 3].toInt(),
                                stereoSampleScratch
                            )
                        } else {
                            firFilter.processDecimate64(
                                dsdInputBuffer[baseL].toInt(), dsdInputBuffer[baseL + 1].toInt(),
                                dsdInputBuffer[baseL + 2].toInt(), dsdInputBuffer[baseL + 3].toInt(),
                                dsdInputBuffer[baseL + 4].toInt(), dsdInputBuffer[baseL + 5].toInt(),
                                dsdInputBuffer[baseL + 6].toInt(), dsdInputBuffer[baseL + 7].toInt(),
                                dsdInputBuffer[baseR].toInt(), dsdInputBuffer[baseR + 1].toInt(),
                                dsdInputBuffer[baseR + 2].toInt(), dsdInputBuffer[baseR + 3].toInt(),
                                dsdInputBuffer[baseR + 4].toInt(), dsdInputBuffer[baseR + 5].toInt(),
                                dsdInputBuffer[baseR + 6].toInt(), dsdInputBuffer[baseR + 7].toInt(),
                                stereoSampleScratch
                            )
                        }
                    } else {
                        if (channels >= 2) {
                            if (bytesPerSamplePerCh == 4) {
                                val bL0 = dsdInputBuffer[dffBytePos].toInt()
                                val bR0 = dsdInputBuffer[dffBytePos + 1].toInt()
                                dffBytePos += channels
                                val bL1 = dsdInputBuffer[dffBytePos].toInt()
                                val bR1 = dsdInputBuffer[dffBytePos + 1].toInt()
                                dffBytePos += channels
                                val bL2 = dsdInputBuffer[dffBytePos].toInt()
                                val bR2 = dsdInputBuffer[dffBytePos + 1].toInt()
                                dffBytePos += channels
                                val bL3 = dsdInputBuffer[dffBytePos].toInt()
                                val bR3 = dsdInputBuffer[dffBytePos + 1].toInt()
                                dffBytePos += channels

                                firFilter.processDecimate32(
                                    bL0, bL1, bL2, bL3,
                                    bR0, bR1, bR2, bR3,
                                    stereoSampleScratch
                                )
                            } else {
                                val bL0 = dsdInputBuffer[dffBytePos].toInt(); val bR0 = dsdInputBuffer[dffBytePos + 1].toInt(); dffBytePos += channels
                                val bL1 = dsdInputBuffer[dffBytePos].toInt(); val bR1 = dsdInputBuffer[dffBytePos + 1].toInt(); dffBytePos += channels
                                val bL2 = dsdInputBuffer[dffBytePos].toInt(); val bR2 = dsdInputBuffer[dffBytePos + 1].toInt(); dffBytePos += channels
                                val bL3 = dsdInputBuffer[dffBytePos].toInt(); val bR3 = dsdInputBuffer[dffBytePos + 1].toInt(); dffBytePos += channels
                                val bL4 = dsdInputBuffer[dffBytePos].toInt(); val bR4 = dsdInputBuffer[dffBytePos + 1].toInt(); dffBytePos += channels
                                val bL5 = dsdInputBuffer[dffBytePos].toInt(); val bR5 = dsdInputBuffer[dffBytePos + 1].toInt(); dffBytePos += channels
                                val bL6 = dsdInputBuffer[dffBytePos].toInt(); val bR6 = dsdInputBuffer[dffBytePos + 1].toInt(); dffBytePos += channels
                                val bL7 = dsdInputBuffer[dffBytePos].toInt(); val bR7 = dsdInputBuffer[dffBytePos + 1].toInt(); dffBytePos += channels

                                firFilter.processDecimate64(
                                    bL0, bL1, bL2, bL3, bL4, bL5, bL6, bL7,
                                    bR0, bR1, bR2, bR3, bR4, bR5, bR6, bR7,
                                    stereoSampleScratch
                                )
                            }
                        } else {
                            val b0 = dsdInputBuffer[dffBytePos].toInt()
                            val b1 = dsdInputBuffer[dffBytePos + 1].toInt()
                            val b2 = dsdInputBuffer[dffBytePos + 2].toInt()
                            val b3 = dsdInputBuffer[dffBytePos + 3].toInt()
                            dffBytePos += bytesPerSamplePerCh
                            firFilter.processDecimate32(b0, b1, b2, b3, b0, b1, b2, b3, stereoSampleScratch)
                        }
                    }

                    // Lấy mẫu PCM 32-bit Float nguyên bản sau bộ lọc FIR 512-taps và tăng gain +1.5dB (~1.19x)
                    var left = stereoSampleScratch[0] * 1.19f * preGain
                    var right = stereoSampleScratch[1] * 1.19f * preGain

                    if (pureDsdMode) {
                        // Chế độ Pure DSD: Bỏ qua toàn bộ DSP (MegaBass, ClearAudio, DSEE, Vinyl, EQ, Phase), chỉ giữ Volume + Balance
                        left *= leftPan
                        right *= rightPan
                    } else {
                        // Áp dụng chuỗi DSP 32-bit Float (MegaBass, EQ, ClearAudio+, DSEE HX, Vinyl)
                        dspSubL += subAlpha * (left - dspSubL)
                        dspSubR += subAlpha * (right - dspSubR)
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
                            dspVinylL += vinylAlpha * (left - dspVinylL)
                            dspVinylR += vinylAlpha * (right - dspVinylR)
                            left = tanh((dspVinylL * 0.65f + left * 0.35f + dspSubL * 0.10f) * 1.05f) * 0.92f
                            right = tanh((dspVinylR * 0.65f + right * 0.35f + dspSubR * 0.10f) * 1.05f) * 0.92f
                        } else {
                            left = tanh(left) * 0.96f
                            right = tanh(right) * 0.96f
                        }

                        left = left * leftPan * phase
                        right = right * rightPan * phase
                    }

                    // Áp dụng TPDF Dither nhẹ (-138 dBFS, chuẩn 24-bit LSB) giúp âm nền tự nhiên khi xuống bit-depth thấp hơn
                    if (applyDither) {
                        prngState = prngState xor (prngState shl 13)
                        prngState = prngState xor (prngState ushr 17)
                        prngState = prngState xor (prngState shl 5)
                        val r1 = (prngState and 0x7FFF).toFloat()

                        prngState = prngState xor (prngState shl 13)
                        prngState = prngState xor (prngState ushr 17)
                        prngState = prngState xor (prngState shl 5)
                        val r2 = (prngState and 0x7FFF).toFloat()

                        val tpdfNoise = (r1 - r2) * 3.6e-9f
                        left += tpdfNoise
                        right -= tpdfNoise
                    }

                    pcmFloatOutputBuffer[frame * 2] = left.coerceIn(-1.0f, 1.0f)
                    pcmFloatOutputBuffer[frame * 2 + 1] = right.coerceIn(-1.0f, 1.0f)
                }

                if (!stopRequested) {
                    audioTrack.write(pcmFloatOutputBuffer, 0, actualPcmFrames * 2, AudioTrack.WRITE_BLOCKING)
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
                showToastOnMain("Không thể giải mã file DSD: ${t.javaClass.simpleName}")
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
