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

/**
 * BỘ LỌC POLYPHASE FIR 128-TAPS CHUYÊN DỤNG CHO DSD -> PCM 32-BIT FLOAT
 *
 * - Sử dụng hàm cửa sổ 4-Term Blackman-Harris (suy hao dải chắn -92dB) kết hợp hàm Sinc lý tưởng.
 * - Tối ưu hóa tốc độ bằng 16 bảng tra cứu tiền tính toán (16 bytes * 8 bits = 128 taps).
 * - Xử lý chính xác thứ tự bit MSB-first (chuẩn Philips .DFF) và LSB-first (chuẩn Sony .DSF).
 * - Hoàn toàn không cấp phát bộ nhớ (Zero-Allocation) trong quá trình lọc thời gian thực.
 */
class DsdFirFilter(isLsbFirst: Boolean, cutoffHz: Double = 28000.0, dsdSampleRate: Int = 2822400) {

    companion object {
        const val NUM_TAPS = 128
        const val NUM_BYTES = NUM_TAPS / 8 // 16 bytes lịch sử trượt (128 bits)
        private const val DSD_SILENCE_BYTE = 0x69 // 01101001: Trạng thái cân bằng 0V của dòng 1-bit DSD
    }

    // Bảng tra cứu 1 chiều phẳng: [16 pha byte * 256 giá trị byte] = 4096 phần tử Float
    // Giúp truy xuất bộ nhớ đệm CPU L1 Cache cực nhanh trong vòng lặp
    private val flatLookupTable = FloatArray(NUM_BYTES * 256)

    // Thanh ghi dịch (Shift Register) lưu 16 bytes DSD gần nhất của Kênh Trái và Kênh Phải
    private val historyL = IntArray(NUM_BYTES) { DSD_SILENCE_BYTE }
    private val historyR = IntArray(NUM_BYTES) { DSD_SILENCE_BYTE }

    // Trạng thái bộ lọc khử dòng một chiều (DC Blocker High-Pass @ ~5Hz)
    private var dcPrevInL = 0f
    private var dcPrevOutL = 0f
    private var dcPrevInR = 0f
    private var dcPrevOutR = 0f

    init {
        precomputeCoefficients(isLsbFirst, cutoffHz, dsdSampleRate)
    }

    /**
     * Tiền tính toán hệ số FIR 128-Taps và đóng gói thành bảng tra cứu theo từng byte.
     */
    private fun precomputeCoefficients(isLsbFirst: Boolean, cutoffHz: Double, dsdFs: Int) {
        val coeffs = DoubleArray(NUM_TAPS)
        // Tần số cắt chuẩn hóa (Normalized Cutoff Frequency)
        val fc = (cutoffHz / dsdFs.toDouble()).coerceIn(0.001, 0.05)
        val center = (NUM_TAPS - 1) / 2.0
        var sumWeight = 0.0

        for (i in 0 until NUM_TAPS) {
            val n = i - center
            // 1. Hàm Sinc lý tưởng: sin(2 * pi * fc * n) / (pi * n)
            val sinc = if (n == 0.0) {
                2.0 * fc
            } else {
                sin(2.0 * PI * fc * n) / (PI * n)
            }

            // 2. Cửa sổ Blackman-Harris 4 bậc giúp triệt tiêu nhiễu lượng tử tần số cao (> 24kHz)
            val phase = (2.0 * PI * i) / (NUM_TAPS - 1)
            val window = 0.35875 -
                    0.48829 * cos(phase) +
                    0.14128 * cos(2.0 * phase) -
                    0.01168 * cos(3.0 * phase)

            coeffs[i] = sinc * window
            sumWeight += coeffs[i]
        }

        // 3. Chuẩn hóa tổng hệ số FIR và bù biên độ chuẩn SACD (+6dB ~ 2.0x vì điều chế DSD giới hạn biên độ ở 50%)
        val sacdGainCompensation = 2.0 / sumWeight
        for (i in 0 until NUM_TAPS) {
            coeffs[i] *= sacdGainCompensation
        }

        // 4. Đóng gói 128 hệ số vào 16 bảng tra cứu Byte (mỗi byte chứa 8 xung 1-bit)
        for (byteIndex in 0 until NUM_BYTES) {
            val tapOffset = byteIndex * 8
            val tableBaseOffset = byteIndex * 256

            for (byteValue in 0..255) {
                var acc = 0.0
                for (bit in 0..7) {
                    // Nếu LSB-first (.DSF): bit 0 được phát ra trước (tương ứng với tap nhỏ hơn)
                    // Nếu MSB-first (.DFF): bit 7 được phát ra trước (tương ứng với tap nhỏ hơn)
                    val bitVal = if (isLsbFirst) {
                        (byteValue shr bit) and 1
                    } else {
                        (byteValue shr (7 - bit)) and 1
                    }
                    // Ánh xạ bit {0, 1} sang xung lưỡng cực {-1.0, +1.0}
                    val pulse = if (bitVal == 1) 1.0 else -1.0
                    acc += pulse * coeffs[tapOffset + bit]
                }
                flatLookupTable[tableBaseOffset + byteValue] = acc.toFloat()
            }
        }
    }

    /**
     * Đặt lại trạng thái bộ lọc khi chuyển bài hoặc khi người dùng Seek.
     */
    fun reset() {
        for (i in 0 until NUM_BYTES) {
            historyL[i] = DSD_SILENCE_BYTE
            historyR[i] = DSD_SILENCE_BYTE
        }
        dcPrevInL = 0f
        dcPrevOutL = 0f
        dcPrevInR = 0f
        dcPrevOutR = 0f
    }

    /**
     * Đẩy 4 bytes DSD mới (32 bits/kênh - Decimation 1:32) vào thanh ghi trượt 16-byte
     * và tính tích chập 128-taps cho cả 2 kênh L/R.
     */
    fun processDecimate32(
        bL0: Int, bL1: Int, bL2: Int, bL3: Int,
        bR0: Int, bR1: Int, bR2: Int, bR3: Int,
        outStereoFrame: FloatArray
    ) {
        // Dịch 12 bytes cũ sang trái (unrolled loop để đạt tốc độ tối đa)
        System.arraycopy(historyL, 4, historyL, 0, 12)
        System.arraycopy(historyR, 4, historyR, 0, 12)

        // Nạp 4 bytes mới nhất vào cuối thanh ghi 16-byte
        historyL[12] = bL0 and 0xFF
        historyL[13] = bL1 and 0xFF
        historyL[14] = bL2 and 0xFF
        historyL[15] = bL3 and 0xFF

        historyR[12] = bR0 and 0xFF
        historyR[13] = bR1 and 0xFF
        historyR[14] = bR2 and 0xFF
        historyR[15] = bR3 and 0xFF

        // Tính tổng tích chập 128-taps thông qua 16 pha byte
        var sumL = 0f
        var sumR = 0f
        for (i in 0 until NUM_BYTES) {
            val baseIdx = i shl 8 // i * 256
            sumL += flatLookupTable[baseIdx + historyL[i]]
            sumR += flatLookupTable[baseIdx + historyR[i]]
        }

        // Khử thành phần DC Offset (High-pass filter nhẹ ở 5Hz) để màng loa không bị lệch tâm
        val cleanL = sumL - dcPrevInL + 0.9995f * dcPrevOutL
        dcPrevInL = sumL
        dcPrevOutL = cleanL

        val cleanR = sumR - dcPrevInR + 0.9995f * dcPrevOutR
        dcPrevInR = sumR
        dcPrevOutR = cleanR

        outStereoFrame[0] = cleanL
        outStereoFrame[1] = cleanR
    }

    /**
     * Đẩy 8 bytes DSD mới (64 bits/kênh - Decimation 1:64 dự phòng khi thiết bị giới hạn sample rate)
     */
    fun processDecimate64(
        bL0: Int, bL1: Int, bL2: Int, bL3: Int, bL4: Int, bL5: Int, bL6: Int, bL7: Int,
        bR0: Int, bR1: Int, bR2: Int, bR3: Int, bR4: Int, bR5: Int, bR6: Int, bR7: Int,
        outStereoFrame: FloatArray
    ) {
        System.arraycopy(historyL, 8, historyL, 0, 8)
        System.arraycopy(historyR, 8, historyR, 0, 8)

        historyL[8] = bL0 and 0xFF; historyL[9] = bL1 and 0xFF
        historyL[10] = bL2 and 0xFF; historyL[11] = bL3 and 0xFF
        historyL[12] = bL4 and 0xFF; historyL[13] = bL5 and 0xFF
        historyL[14] = bL6 and 0xFF; historyL[15] = bL7 and 0xFF

        historyR[8] = bR0 and 0xFF; historyR[9] = bR1 and 0xFF
        historyR[10] = bR2 and 0xFF; historyR[11] = bR3 and 0xFF
        historyR[12] = bR4 and 0xFF; historyR[13] = bR5 and 0xFF
        historyR[14] = bR6 and 0xFF; historyR[15] = bR7 and 0xFF

        var sumL = 0f
        var sumR = 0f
        for (i in 0 until NUM_BYTES) {
            val baseIdx = i shl 8
            sumL += flatLookupTable[baseIdx + historyL[i]]
            sumR += flatLookupTable[baseIdx + historyR[i]]
        }

        val cleanL = sumL - dcPrevInL + 0.9995f * dcPrevOutL
        dcPrevInL = sumL
        dcPrevOutL = cleanL

        val cleanR = sumR - dcPrevInR + 0.9995f * dcPrevOutR
        dcPrevInR = sumR
        dcPrevOutR = cleanR

        outStereoFrame[0] = cleanL
        outStereoFrame[1] = cleanR
    }
}

/**
 * ENGINE PHÁT NHẠC DSD TRỰC TIẾP QUA AUDIOTRACK 32-BIT FLOAT
 *
 * - Hỗ trợ DSD64 (2.8224 MHz -> 88.2 kHz), DSD128 (5.6448 MHz -> 176.4 kHz), DSD256 (11.2896 MHz -> 352.8 kHz).
 * - Đọc chuẩn xác cấu trúc Sony .DSF (Block-interleaved, LSB/MSB) và Philips .DFF (Byte-interleaved, MSB).
 * - Tích hợp chuỗi DSP 32-bit Float (MegaBass, 10-Band EQ, ClearAudio+, DSEE HX, Vinyl Processor, Balance, Phase).
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
            name = "WalkmanDsdFir128Thread"
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
            val r = ins.read(scratch, 0, step)
            if (r <= 0) break
            rem -= r
            skippedTotal += r
        }
        return skippedTotal
    }

    /**
     * Đọc chính xác Header của file .DSF (Sony) và .DFF (Philips DSDIFF) theo đúng cấu trúc Chunk.
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
                    // 1. Định dạng Sony .DSF (Little-Endian)
                    // DSD chunk (28 bytes) + fmt chunk (52 bytes) + data chunk header (12 bytes) = 92 bytes
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
                    // Trong chuẩn .DSF: bitsPerSample == 1 nghĩa là LSB-first, == 8 nghĩa là MSB-first
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
                    // 2. Định dạng Philips DSDIFF .DFF (Big-Endian, luôn luôn là MSB-first)
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

                                    // Duyệt các sub-chunk bên trong PROP (bỏ qua 4 bytes đầu "SND ")
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
                                        // Chuẩn IFF yêu cầu căn chỉnh chẵn byte (Even-byte alignment)
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
     * Khởi tạo AudioTrack 32-bit Float với cơ chế tự động tương thích Sample Rate phần cứng:
     * - DSD64  (2.8224 MHz) -> 88.2 kHz  (Decimation 1:32 = 4 bytes/sample)
     * - DSD128 (5.6448 MHz) -> 176.4 kHz (Decimation 1:32 = 4 bytes/sample)
     * - DSD256 (11.2896 MHz)-> 352.8 kHz (Decimation 1:32) hoặc hạ về 176.4 kHz (Decimation 1:64) nếu Android HAL giới hạn ở 192 kHz.
     */
    private fun createFloatAudioTrackWithFallback(dsdFs: Int): Pair<AudioTrack, Int> {
        // Ưu tiên tỷ lệ Decimation 1:32 (4 bytes DSD = 32 bits -> 1 mẫu PCM Float)
        val preferredRate = (dsdFs / 32).coerceIn(88200, 352800)
        val fallbackRate = (dsdFs / 64).coerceIn(88200, 176400)

        for (candidateRate in listOf(preferredRate, fallbackRate, 88200)) {
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
                        return Pair(track, bytesPerSample)
                    } else {
                        track.release()
                    }
                }
            } catch (_: Throwable) {}
        }

        // Trường hợp thiết bị cực cũ: Khởi tạo mặc định ở 88.2 kHz Float
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
        return Pair(defaultTrack, 4)
    }

    /**
     * Vòng lặp giải mã chính trên luồng ưu tiên cao (Thread.MAX_PRIORITY).
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

            // 1. Tạo AudioTrack 32-bit Float (88.2 kHz / 176.4 kHz / 352.8 kHz)
            val (track, bytesPerSamplePerCh) = createFloatAudioTrackWithFallback(dsdFs)
            audioTrack = track
            audioTrack.play()

            // 2. Khởi tạo bộ lọc FIR 128-Taps với tần số cắt 24kHz chuẩn SACD Book
            val firFilter = DsdFirFilter(
                isLsbFirst = info.isLsbFirst,
                cutoffHz = 28000.0,
                dsdSampleRate = dsdFs
            )

            var activeStream: InputStream = context.contentResolver.openInputStream(uri) ?: return
            skipExact(activeStream, dataStartOffset)
            var bytesReadSoFar = 0L

            // Bộ đệm cấp phát 1 lần duy nhất ngoài vòng lặp (Zero-Allocation trong while loop)
            val framesPerChunk = if (isDsf) (dsfBlockSize / bytesPerSamplePerCh) else 2048
            val inputBufferSize = if (isDsf) (dsfBlockSize * channels) else (bytesPerSamplePerCh * channels * framesPerChunk)
            val dsdInputBuffer = ByteArray(inputBufferSize)
            val pcmFloatOutputBuffer = FloatArray(framesPerChunk * 2)
            val stereoSampleScratch = FloatArray(2)

            // Trạng thái bộ xử lý DSP Sony 32-bit Float
            var dspSubL = 0f
            var dspSubR = 0f
            var dspPrevL = 0f
            var dspPrevR = 0f
            var dspVinylL = 0f
            var dspVinylR = 0f

            while (!stopRequested && bytesReadSoFar < dataSize) {
                if (pauseRequested) {
                    Thread.sleep(40)
                    continue
                }

                // Xử lý Seek mượt mà và căn chỉnh đúng biên độ Block/Frame
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
                        (bytesPerSamplePerCh * channels).toLong()
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

                // Đọc cấu hình DSP hiện tại (1 lần mỗi chunk để tối ưu hiệu năng)
                val preGain = 0.80f * masterVolume
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

                var dffBytePos = 0

                for (frame in 0 until actualPcmFrames) {
                    if (isDsf) {
                        // Giải mã cấu trúc Block-Interleaved của Sony .DSF
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
                        // Giải mã cấu trúc Byte-Interleaved (L0, R0, L1, R1...) của Philips .DFF
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
                            // Trường hợp DSD Mono (1 kênh)
                            val b0 = dsdInputBuffer[dffBytePos].toInt()
                            val b1 = dsdInputBuffer[dffBytePos + 1].toInt()
                            val b2 = dsdInputBuffer[dffBytePos + 2].toInt()
                            val b3 = dsdInputBuffer[dffBytePos + 3].toInt()
                            dffBytePos += bytesPerSamplePerCh
                            firFilter.processDecimate32(b0, b1, b2, b3, b0, b1, b2, b3, stereoSampleScratch)
                        }
                    }

                    // Lấy mẫu PCM 32-bit Float nguyên bản sau bộ lọc FIR 128-taps và tăng gain +1.5dB (~1.19x)
                    var left = stereoSampleScratch[0] * 1.19f * preGain
                    var right = stereoSampleScratch[1] * 1.19f * preGain

                    if (pureDsdMode) {
                        // Chế độ Pure DSD: Bỏ qua toàn bộ DSP (MegaBass, ClearAudio, DSEE, Vinyl, EQ, Phase), chỉ giữ Volume + Balance
                        left = (left * leftPan).coerceIn(-1.0f, 1.0f)
                        right = (right * rightPan).coerceIn(-1.0f, 1.0f)
                    } else {
                        // Áp dụng chuỗi DSP 32-bit Float (MegaBass, EQ, ClearAudio+, DSEE HX, Vinyl)
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
                            left = tanh(left) * 0.96f
                            right = tanh(right) * 0.96f
                        }

                        left = (left * leftPan * phase).coerceIn(-1.0f, 1.0f)
                        right = (right * rightPan * phase).coerceIn(-1.0f, 1.0f)
                    }

                    pcmFloatOutputBuffer[frame * 2] = left
                    pcmFloatOutputBuffer[frame * 2 + 1] = right
                }

                // Xuất trực tiếp mảng PCM 32-bit Float xuống phần cứng AudioTrack
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

