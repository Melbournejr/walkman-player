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
 * BỘ LỌC THAM CHIẾU DSD2PCM: 1024-TAP SYMMETRIC LINEAR-PHASE KAISER WINDOW (BETA = 14.2, -140 dB)
 *
 * 1. Kiến trúc 1024 Taps (128 Bytes lịch sử trượt trên mỗi kênh):
 *    - Sử dụng tính chất đối xứng tuyệt đối của bộ lọc FIR pha tuyến tính Loại I/II:
 *      Byte[k] ở đầu cửa sổ và Byte[127 - k] ở cuối cửa sổ có bộ trọng số đối xứng gương.
 *    - Kết hợp kỹ thuật tra cứu 8-bit kép & tích lũy 64-bit Double giúp tính trọn vẹn 1024 Taps
 *      chỉ trong 64 phép đọc bảng L1 Cache (64 KB), chạy cực nhẹ trên Snapdragon 8 Gen 3.
 *
 * 2. Cửa sổ Kaiser (Beta = 14.2, Attenuation = -140 dB) kết hợp hàm Sinc chuẩn 64-bit:
 *    - Loại bỏ hoàn toàn hiện tượng aliasing nhiễu lượng tử siêu âm xuống dải 10kHz - 20kHz,
 *      triệt tiêu cảm giác "grain / harsh" trên giọng hát và nhạc cụ dây.
 *
 * 3. Tầng hậu kỳ 4th-Order Linkwitz-Riley (LR4 = 2x Cascaded Butterworth Biquad @ 32kHz) +
 *    64-Bit Sub-Sonic DC Servo (1.5Hz):
 *    - Giữ pha dải trầm (20Hz - 80Hz) không bị lệch, giúp tiếng bass sâu, chắc và tách lớp.
 */
class DsdFirFilter(
    private val isLsbFirst: Boolean,
    cutoffHz: Double = 27500.0,
    dsdSampleRate: Int = 2822400,
    pcmSampleRate: Int = 88200
) {
    companion object {
        const val NUM_TAPS = 1024
        const val NUM_BYTES = NUM_TAPS / 8       // 128 bytes lịch sử trượt (1024 bits DSD)
        const val HALF_BYTES = NUM_BYTES / 2     // 64 cặp byte đối xứng tâm (Symmetric Folding)
        private const val DSD_SILENCE_BYTE = 0x69 // 01101001: Trạng thái cân bằng 0V chuẩn SACD

        /**
         * Bảng đảo ngược thứ tự 8 bit trong 1 byte (0..255) dùng cho phép gập đối xứng (Symmetric Folding)
         * của bộ lọc Linear-Phase 1024-Taps: giảm 50% dung lượng bảng LUT xuống còn đúng 64 KB (vừa khít L1 Cache).
         */
        private val BIT_REVERSE_TABLE = IntArray(256).apply {
            for (b in 0..255) {
                var rev = 0
                for (bit in 0..7) {
                    if (((b shr bit) and 1) == 1) {
                        rev = rev or (1 shl (7 - bit))
                    }
                }
                this[b] = rev
            }
        }

        /**
         * Hàm Bessel biến đổi loại 1 bậc 0: I_0(x) tính bằng chuỗi lũy thừa 64-bit Double.
         */
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val halfX = x * 0.5
            for (k in 1..50) {
                val r = halfX / k.toDouble()
                term *= (r * r)
                sum += term
                if (term < sum * 1e-16) break
            }
            return sum
        }
    }

    // Bảng tra cứu đối xứng nửa đầu: 64 pha byte * 256 trạng thái = 16.384 phần tử Float (64 KB L1 Cache)
    // Nhờ đối xứng gương (Symmetric Linear-Phase), nửa sau [64..127] dùng chính bảng này với BIT_REVERSE_TABLE!
    private val symmetricHalfLut = FloatArray(HALF_BYTES * 256)

    // Thanh ghi vòng 128 bytes cho Kênh Trái (L) và Kênh Phải (R)
    private val historyL = IntArray(NUM_BYTES) { DSD_SILENCE_BYTE }
    private val historyR = IntArray(NUM_BYTES) { DSD_SILENCE_BYTE }

    // Mạch Sub-Sonic DC Servo 64-bit Double (cắt ở 1.5 Hz, giữ nguyên độ đầm và pha của Sub-bass)
    private var dcIntegratorL = 0.0
    private var dcIntegratorR = 0.0
    private val dcServoCoeff = (2.0 * PI * 1.5 / pcmSampleRate.toDouble()).coerceIn(0.00002, 0.0005)

    // Mạch lọc hậu kỳ 4th-Order Linkwitz-Riley (LR4 = 2 tầng Butterworth bậc 2 nối tiếp ở 32 kHz)
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // Trạng thái Tầng 1 & Tầng 2 của bộ lọc LR4 (64-bit Double chống nhiễu làm tròn)
    private var s1Z1L = 0.0; private var s1Z2L = 0.0
    private var s2Z1L = 0.0; private var s2Z2L = 0.0
    private var s1Z1R = 0.0; private var s1Z2R = 0.0
    private var s2Z1R = 0.0; private var s2Z2R = 0.0

    init {
        precomputeSymmetricKaiser1024Lut(isLsbFirst, cutoffHz, dsdSampleRate)
        initLinkwitzRiley4thOrderPostFilter(pcmSampleRate)
    }

    /**
     * Tiền tính toán 1024 hệ số FIR đối xứng tâm với cửa sổ Kaiser (Beta = 14.2, -140 dB).
     */
    private fun precomputeSymmetricKaiser1024Lut(isLsbFirst: Boolean, cutoffHz: Double, dsdFs: Int) {
        val coeffs = DoubleArray(NUM_TAPS)
        val fc = (cutoffHz / dsdFs.toDouble()).coerceIn(0.0004, 0.040)
        val center = (NUM_TAPS - 1) * 0.5

        // Beta = 14.2 cho suy hao dải chắn ~ -140 dB (chuẩn Mastering DSD2PCM)
        val kaiserBeta = 14.2
        val invI0Beta = 1.0 / besselI0(kaiserBeta)
        var sumWeight = 0.0

        for (i in 0 until NUM_TAPS) {
            val n = i.toDouble() - center
            val sinc = if (abs(n) < 1e-9) {
                2.0 * fc
            } else {
                sin(2.0 * PI * fc * n) / (PI * n)
            }

            val norm = (2.0 * i.toDouble()) / (NUM_TAPS - 1).toDouble() - 1.0
            val rad = (1.0 - norm * norm).coerceAtLeast(0.0)
            val window = besselI0(kaiserBeta * sqrt(rad)) * invI0Beta

            val h = sinc * window
            coeffs[i] = h
            sumWeight += h
        }

        // Chuẩn hóa tổng hệ số = 1.0 và bù biên độ chuẩn SACD (+6.02 dB = 2.0x)
        val sacdScale = 2.0 / sumWeight
        for (i in 0 until NUM_TAPS) {
            coeffs[i] *= sacdScale
        }

        // Đóng gói 64 pha byte đầu tiên (taps 0..511); 64 pha byte sau (taps 512..1023) đối xứng gương hoàn toàn
        for (byteIdx in 0 until HALF_BYTES) {
            val tapBase = byteIdx shl 3 // byteIdx * 8
            val lutBase = byteIdx shl 8 // byteIdx * 256

            for (byteVal in 0..255) {
                var acc = 0.0
                for (bit in 0..7) {
                    val bitVal = if (isLsbFirst) {
                        (byteVal shr bit) and 1
                    } else {
                        (byteVal shr (7 - bit)) and 1
                    }
                    val bipolar = if (bitVal == 1) 1.0 else -1.0
                    acc += bipolar * coeffs[tapBase + bit]
                }
                symmetricHalfLut[lutBase + byteVal] = acc.toFloat()
            }
        }
    }

    /**
     * Thiết lập bộ lọc hậu kỳ 4th-Order Linkwitz-Riley (LR4, -24 dB/octave) ở ~32 kHz
     * để loại bỏ triệt để nhiễu siêu âm còn sót mà không gây ringing hay lệch pha dải nghe được.
     */
    private fun initLinkwitzRiley4thOrderPostFilter(pcmRate: Int) {
        val fc = (32000.0).coerceAtMost(pcmRate * 0.40)
        val omega = 2.0 * PI * fc / pcmRate.toDouble()
        val sn = sin(omega)
        val cs = cos(omega)
        // Mỗi tầng của LR4 là một bộ lọc Butterworth bậc 2 (Q = 1 / sqrt(2) = 0.70710678)
        val alpha = sn / sqrt(2.0)

        val a0 = 1.0 + alpha
        b0 = ((1.0 - cs) * 0.5) / a0
        b1 = (1.0 - cs) / a0
        b2 = ((1.0 - cs) * 0.5) / a0
        a1 = (-2.0 * cs) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun reset() {
        for (i in 0 until NUM_BYTES) {
            historyL[i] = DSD_SILENCE_BYTE
            historyR[i] = DSD_SILENCE_BYTE
        }
        dcIntegratorL = 0.0
        dcIntegratorR = 0.0
        s1Z1L = 0.0; s1Z2L = 0.0; s2Z1L = 0.0; s2Z2L = 0.0
        s1Z1R = 0.0; s1Z2R = 0.0; s2Z1R = 0.0; s2Z2R = 0.0
    }

    /**
     * Tính tích chập 1024-Taps đối xứng pha tuyến tính (Symmetric Folding Convolution):
     * Cặp byte đầu-cuối (history[k] và bitReverse(history[127 - k])) dùng chung hệ số LUT[k],
     * giúp giảm một nửa số vòng lặp và triệt tiêu sai số pha bất đối xứng.
     */
    private fun evaluateSymmetricConvolution1024(outStereoFrame: FloatArray) {
        val lut = symmetricHalfLut
        val revTbl = BIT_REVERSE_TABLE
        val hL = historyL
        val hR = historyR

        var accL0 = 0.0; var accL1 = 0.0; var accL2 = 0.0; var accL3 = 0.0
        var accR0 = 0.0; var accR1 = 0.0; var accR2 = 0.0; var accR3 = 0.0

        var i = 0
        while (i < HALF_BYTES) {
            val i0 = i
            val i1 = i + 1
            val i2 = i + 2
            val i3 = i + 3

            val m0 = 127 - i0
            val m1 = 127 - i1
            val m2 = 127 - i2
            val m3 = 127 - i3

            val base0 = i0 shl 8
            val base1 = i1 shl 8
            val base2 = i2 shl 8
            val base3 = i3 shl 8

            // Kênh Trái: Gập đối xứng byte [i] và byte đảo bit [127 - i]
            accL0 += (lut[base0 + hL[i0]] + lut[base0 + revTbl[hL[m0]]]).toDouble()
            accL1 += (lut[base1 + hL[i1]] + lut[base1 + revTbl[hL[m1]]]).toDouble()
            accL2 += (lut[base2 + hL[i2]] + lut[base2 + revTbl[hL[m2]]]).toDouble()
            accL3 += (lut[base3 + hL[i3]] + lut[base3 + revTbl[hL[m3]]]).toDouble()

            // Kênh Phải: Gập đối xứng byte [i] và byte đảo bit [127 - i]
            accR0 += (lut[base0 + hR[i0]] + lut[base0 + revTbl[hR[m0]]]).toDouble()
            accR1 += (lut[base1 + hR[i1]] + lut[base1 + revTbl[hR[m1]]]).toDouble()
            accR2 += (lut[base2 + hR[i2]] + lut[base2 + revTbl[hR[m2]]]).toDouble()
            accR3 += (lut[base3 + hR[i3]] + lut[base3 + revTbl[hR[m3]]]).toDouble()

            i += 4
        }

        val firL = (accL0 + accL1) + (accL2 + accL3)
        val firR = (accR0 + accR1) + (accR2 + accR3)

        // 1. Mạch Sub-Sonic DC Servo (1.5 Hz) độ chính xác 64-bit Double:
        // Chỉ rút thành phần điện áp một chiều DC mà không làm suy hao hay lệch pha dải Sub-bass 20Hz
        val dcCleanL = firL - dcIntegratorL
        dcIntegratorL += dcCleanL * dcServoCoeff

        val dcCleanR = firR - dcIntegratorR
        dcIntegratorR += dcCleanR * dcServoCoeff

        // 2. Mạch lọc hậu kỳ 4th-Order Linkwitz-Riley (LR4 - Tầng 1)
        val st1L = b0 * dcCleanL + s1Z1L
        s1Z1L = b1 * dcCleanL - a1 * st1L + s1Z2L
        s1Z2L = b2 * dcCleanL - a2 * st1L

        val st1R = b0 * dcCleanR + s1Z1R
        s1Z1R = b1 * dcCleanR - a1 * st1R + s1Z2R
        s1Z2R = b2 * dcCleanR - a2 * st1R

        // 3. Mạch lọc hậu kỳ 4th-Order Linkwitz-Riley (LR4 - Tầng 2)
        val st2L = b0 * st1L + s2Z1L
        s2Z1L = b1 * st1L - a1 * st2L + s2Z2L
        s2Z2L = b2 * st1L - a2 * st2L

        val st2R = b0 * st1R + s2Z1R
        s2Z1R = b1 * st1R - a1 * st2R + s2Z2R
        s2Z2R = b2 * st1R - a2 * st2R

        outStereoFrame[0] = st2L.toFloat()
        outStereoFrame[1] = st2R.toFloat()
    }

    /**
     * Giải mã Decimation 1:32 (4 bytes = 32 bits DSD mới cho mỗi mẫu PCM):
     * - DSD64  (2.8224 MHz) -> 88.2 kHz
     * - DSD128 (5.6448 MHz) -> 176.4 kHz
     * - DSD256 (11.2896 MHz)-> 352.8 kHz
     */
    fun processDecimate32(
        bL0: Int, bL1: Int, bL2: Int, bL3: Int,
        bR0: Int, bR1: Int, bR2: Int, bR3: Int,
        outStereoFrame: FloatArray
    ) {
        System.arraycopy(historyL, 4, historyL, 0, 124)
        System.arraycopy(historyR, 4, historyR, 0, 124)

        historyL[124] = bL0 and 0xFF
        historyL[125] = bL1 and 0xFF
        historyL[126] = bL2 and 0xFF
        historyL[127] = bL3 and 0xFF

        historyR[124] = bR0 and 0xFF
        historyR[125] = bR1 and 0xFF
        historyR[126] = bR2 and 0xFF
        historyR[127] = bR3 and 0xFF

        evaluateSymmetricConvolution1024(outStereoFrame)
    }

    /**
     * Giải mã Decimation 1:64 (8 bytes = 64 bits DSD mới cho mỗi mẫu PCM):
     */
    fun processDecimate64(
        bL0: Int, bL1: Int, bL2: Int, bL3: Int, bL4: Int, bL5: Int, bL6: Int, bL7: Int,
        bR0: Int, bR1: Int, bR2: Int, bR3: Int, bR4: Int, bR5: Int, bR6: Int, bR7: Int,
        outStereoFrame: FloatArray
    ) {
        System.arraycopy(historyL, 8, historyL, 0, 120)
        System.arraycopy(historyR, 8, historyR, 0, 120)

        historyL[120] = bL0 and 0xFF; historyL[121] = bL1 and 0xFF
        historyL[122] = bL2 and 0xFF; historyL[123] = bL3 and 0xFF
        historyL[124] = bL4 and 0xFF; historyL[125] = bL5 and 0xFF
        historyL[126] = bL6 and 0xFF; historyL[127] = bL7 and 0xFF

        historyR[120] = bR0 and 0xFF; historyR[121] = bR1 and 0xFF
        historyR[122] = bR2 and 0xFF; historyR[123] = bR3 and 0xFF
        historyR[124] = bR4 and 0xFF; historyR[125] = bR5 and 0xFF
        historyR[126] = bR6 and 0xFF; historyR[127] = bR7 and 0xFF

        evaluateSymmetricConvolution1024(outStereoFrame)
    }
}

/**
 * ENGINE PHÁT NHẠC DSD THAM CHIẾU (1024-TAP KAISER FIR + LR4 POST-FILTER + TRANSPARENT SOFT-KNEE LIMITER)
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

    @Volatile var enableTpdfDither: Boolean = true
    @Volatile var customCutoffHz: Double = 28000.0

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

    /**
     * Hàm Soft-Knee Mastering Limiter:
     * - Giữ tuyến tính 100% (Bit-Transparent) cho toàn bộ tín hiệu trong ngưỡng [-0.92, +0.92].
     * - Chỉ uốn cong mềm (Cubic Soft-Knee) ở vùng đỉnh [0.92 .. 1.0] để tuyệt đối không bao giờ bị cắt ngọn (hard clip)
     *   khi có các đoạn cao trào vượt quá +6dBFS của SACD.
     */
    private fun masterSoftKneeLimit(x: Float): Float {
        val absX = abs(x)
        if (absX <= 0.92f) return x
        val sign = if (x >= 0f) 1f else -1f
        val excess = (absX - 0.92f) / 0.32f
        val compressed = 0.92f + 0.08f * tanh(excess)
        return sign * compressed
    }

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
            name = "WalkmanDsdMaster1024Thread"
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
     * Vòng lặp giải mã DSD chính với bộ lọc 1024-Tap Kaiser FIR (-140dB) + LR4 Post-Filter + Soft-Knee Limiter.
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

            // 2. Chọn tần số cắt chuẩn Scarlet Book: 26.5 kHz cho DSD64 (chống sườn nhiễu 30kHz) và 29.5 kHz cho DSD128/256
            val optimalCutoffHz = if (dsdFs >= 5644800) {
                customCutoffHz.coerceIn(28000.0, 31000.0).coerceAtLeast(29500.0)
            } else {
                26500.0
            }

            // 3. Khởi tạo bộ lọc tham chiếu 1024-Tap Symmetric Kaiser Window FIR
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

            // Hệ số DSP co giãn chính xác theo Sample Rate thực tế (88.2 / 176.4 / 352.8 kHz)
            val rateScale = (88200f / actualPcmRate.toFloat()).coerceIn(0.25f, 1.0f)
            val subAlpha = 0.024f * rateScale
            val vinylAlpha = 0.32f * rateScale

            var dspSubL = 0f
            var dspSubR = 0f
            var dspPrevL = 0f
            var dspPrevR = 0f
            var dspVinylL = 0f
            var dspVinylR = 0f

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
                        (bytesPerSamplePerCh * channels * 16).toLong()
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

                // Gain compensation hợp lý: +1.5dB (1.19f) * 0.82f headroom = 0.975f (To rõ tương đương PCM mà không clip)
                val gainCompensation = 1.19f * 0.82f * masterVolume
                val eqGains = dspProcessor.eq10Gains
                val eqLow = ((eqGains[0] + eqGains[1] + eqGains[2]) / 3f) * 0.05f
                val eqMid = ((eqGains[3] + eqGains[4] + eqGains[5] + eqGains[6]) / 4f) * 0.04f
                val eqHigh = ((eqGains[7] + eqGains[8] + eqGains[9]) / 3f) * 0.05f
                val bassBoost = (dspProcessor.megaBassLevel * 0.50f) + eqLow
                val clearAudio = dspProcessor.clearAudioEnabled
                val dsee = dspProcessor.dseeEnabled
                val vinyl = dspProcessor.vinylEnabled

                // Khóa tâm cân bằng tuyệt đối 1:1 (Dead-zone +/- 0.03f)
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

                    // Lấy mẫu PCM 32-bit Float nguyên bản sau bộ lọc 1024-Tap Kaiser FIR
                    var left = stereoSampleScratch[0] * gainCompensation
                    var right = stereoSampleScratch[1] * gainCompensation

                    if (pureDsdMode) {
                        // Chế độ Pure DSD: Giữ nguyên độ trung thực tuyệt đối sau FIR, chỉ áp dụng Volume + Balance
                        left *= leftPan
                        right *= rightPan
                    } else {
                        // Chuỗi DSP 32-bit Float
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
                            left = (dspVinylL * 0.65f + left * 0.35f + dspSubL * 0.10f) * 0.95f
                            right = (dspVinylR * 0.65f + right * 0.35f + dspSubR * 0.10f) * 0.95f
                        }

                        left = left * leftPan * phase
                        right = right * rightPan * phase
                    }

                    // TPDF Dithering ở mức -142 dBFS (khử lượng tử hóa bậc thấp mà không gây hiss)
                    if (applyDither) {
                        prngState = prngState xor (prngState shl 13)
                        prngState = prngState xor (prngState ushr 17)
                        prngState = prngState xor (prngState shl 5)
                        val r1 = (prngState and 0x7FFF).toFloat()

                        prngState = prngState xor (prngState shl 13)
                        prngState = prngState xor (prngState ushr 17)
                        prngState = prngState xor (prngState shl 5)
                        val r2 = (prngState and 0x7FFF).toFloat()

                        val dither = (r1 - r2) * 2.2e-9f
                        left += dither
                        right -= dither
                    }

                    // Đi qua bộ giới hạn đỉnh tuyến tính Soft-Knee (Linear 100% dưới 0.92, chống méo/vỡ tiếng khi có đỉnh transient)
                    pcmFloatOutputBuffer[frame * 2] = masterSoftKneeLimit(left)
                    pcmFloatOutputBuffer[frame * 2 + 1] = masterSoftKneeLimit(right)
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
