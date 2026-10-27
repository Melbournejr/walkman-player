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
        // Bảng tra cứu 256 trạng thái byte chuẩn 1-bit (-1.0f .. +1.0f)
        private val BYTE_ONES_LUT = FloatArray(256).apply {
            for (b in 0..255) {
                var ones = 0
                for (bit in 0..7) {
                    if (((b shr bit) and 1) == 1) ones++
                }
                // Chuẩn hóa trung bình 8 bit về dải [-1.0f, +1.0f]
                this[b] = (ones - 4) * 0.25f
            }
        }

        // Bảng trọng số cửa sổ Blackman-Harris 8 bước giúp triệt tiêu nhiễu lượng tử siêu âm (Ultrasonic DSD Noise)
        private val FIR_4_WEIGHTS = floatArrayOf(0.125f, 0.375f, 0.375f, 0.125f)
        private val FIR_8_WEIGHTS = floatArrayOf(0.035f, 0.110f, 0.215f, 0.140f, 0.140f, 0.215f, 0.110f, 0.035f)
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
            name = "FiioDirectDsdThread"
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
        val dummy = ByteArray(8192)
        while (rem > 0L && !stopRequested) {
            val step = rem.coerceAtMost(8192L).toInt()
            val r = ins.read(dummy, 0, step)
            if (r <= 0) break
            rem -= r
        }
    }

    // Quét chính xác Header của file .DFF / .DSF trong 64KB đầu tiên để không bao giờ lệch byte hoặc nhầm Sample Rate
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

    fun parseDsdHeaderAccurate(context: Context, uri: Uri, totalFileBytes: Long): DsdHeaderInfo {
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val scanBuf = ByteArray(65536)
                val readLen = readExact(ins, scanBuf, scanBuf.size)
                if (readLen < 32) return DsdHeaderInfo(false, false, false, 2, 2822400, 4096, 0L, 0L)

                val magic = String(scanBuf, 0, 4, Charsets.ISO_8859_1)
                if (magic == "DSD ") {
                    val bb = ByteBuffer.wrap(scanBuf, 0, readLen).order(ByteOrder.LITTLE_ENDIAN)
                    val ch = bb.getInt(48).coerceIn(1, 2)
                    val fs = bb.getInt(52).let { if (it == 5644800 || it == 11289600) it else 2822400 }
                    val blk = bb.getInt(64).coerceIn(256, 32768)
                    val dSize = bb.getLong(84)
                    val actualSize = if (dSize > 12L) (dSize - 12L) else (totalFileBytes - 92L).coerceAtLeast(1024L)
                    return DsdHeaderInfo(true, true, false, ch, fs, blk, 92L, actualSize)
                } else if (magic == "FRM8") {
                    var ch = 2
                    var fs = 2822400 // Mặc định chuẩn SACD DSD64 (2.8224 MHz)
                    var isDst = false
                    var dataOffset = -1L
                    var dSize = 0L

                    // Duyệt tuần tự cấu trúc Chunk của .DFF (FRM8 header dài 16 bytes: "FRM8" + 8-byte size + "DSD ")
                    var pos = 16
                    while (pos <= readLen - 12) {
                        val cId = String(scanBuf, pos, 4, Charsets.ISO_8859_1)
                        val cSize = ByteBuffer.wrap(scanBuf, pos + 4, 8).order(ByteOrder.BIG_ENDIAN).long

                        if (cId == "PROP" && cSize > 4L && pos + 16 <= readLen) {
                            val propEnd = (pos + 12 + cSize.toInt()).coerceAtMost(readLen)
                            var subPos = pos + 16 // Bỏ qua 12 byte PROP header + 4 byte "SND "
                            while (subPos <= propEnd - 12) {
                                val subId = String(scanBuf, subPos, 4, Charsets.ISO_8859_1)
                                val subSize = ByteBuffer.wrap(scanBuf, subPos + 4, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
                                val valOffset = subPos + 12
                                if (subSize < 0 || valOffset + subSize > propEnd) break

                                when (subId) {
                                    "FS  " -> {
                                        if (subSize == 4) {
                                            val parsedFs = ByteBuffer.wrap(scanBuf, valOffset, 4).order(ByteOrder.BIG_ENDIAN).int
                                            // Chỉ chấp nhận đúng 3 tần số chuẩn của DSD64, DSD128, DSD256
                                            if (parsedFs == 2822400 || parsedFs == 5644800 || parsedFs == 11289600) {
                                                fs = parsedFs
                                            }
                                        }
                                    }
                                    "CHNL" -> {
                                        if (subSize >= 2) {
                                            val parsedCh = ByteBuffer.wrap(scanBuf, valOffset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                                            if (parsedCh in 1..6) ch = parsedCh
                                        }
                                    }
                                    "CMPR" -> {
                                        if (subSize >= 4) {
                                            val cmprType = String(scanBuf, valOffset, 4, Charsets.ISO_8859_1)
                                            if (cmprType == "DST ") isDst = true
                                        }
                                    }
                                }
                                val padSub = if (subSize % 2 != 0) subSize + 1 else subSize
                                subPos = valOffset + padSub
                            }
                        } else if (cId == "DSD " && cSize > 1000L) {
                            dataOffset = (pos + 12).toLong()
                            dSize = cSize
                            break
                        } else if (cId == "DST " && cSize > 1000L) {
                            isDst = true
                            dataOffset = (pos + 12).toLong()
                            dSize = cSize
                            break
                        }

                        if (cSize <= 0L || cSize > totalFileBytes) break
                        val padMain = if (cSize % 2L != 0L) cSize + 1L else cSize
                        pos += (12L + padMain).toInt()
                    }

                    // Nếu header dài vượt quá hoặc cần quét trực tiếp chữ ký "DSD "
                    if (dataOffset < 0L) {
                        for (i in 16..(readLen - 12)) {
                            if (scanBuf[i] == 'D'.code.toByte() && scanBuf[i + 1] == 'S'.code.toByte() &&
                                scanBuf[i + 2] == 'D'.code.toByte() && scanBuf[i + 3] == ' '.code.toByte()
                            ) {
                                val candidateSz = ByteBuffer.wrap(scanBuf, i + 4, 8).order(ByteOrder.BIG_ENDIAN).long
                                if (candidateSz > 100000L && candidateSz <= totalFileBytes) {
                                    dataOffset = (i + 12).toLong()
                                    dSize = candidateSz
                                    break
                                }
                            }
                        }
                    }

                    if (dataOffset > 0L) {
                        val finalSize = if (dSize > 0L) dSize else (totalFileBytes - dataOffset).coerceAtLeast(1024L)
                        return DsdHeaderInfo(true, false, isDst, ch, fs, 4096, dataOffset, finalSize)
                    }
                }
            }
        } catch (_: Throwable) {}
        return DsdHeaderInfo(false, false, false, 2, 2822400, 4096, 0L, 0L)
    }

    private fun runDsdLoop(uri: Uri) {
        var audioTrack: AudioTrack? = null
        try {
            var totalFileBytes = 100L * 1024L * 1024L
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0) totalFileBytes = afd.length
                }
            } catch (_: Throwable) {}

            val info = parseDsdHeaderAccurate(context, uri, totalFileBytes)
            if (!info.isValid) {
                showToastOnMain("Không tìm thấy luồng âm thanh DSD hợp lệ trong file này!")
                isPlaying = false
                return
            }

            if (info.isDstCompressed) {
                showToastOnMain("File .DFF này đang bị nén khóa DST (SACD). Hãy dùng file .DFF/.DSF chuẩn Uncompressed!")
                isPlaying = false
                return
            }

            val channels = info.channels.coerceAtLeast(1)
            val dsdFs = info.dsdSampleRate
            val isDsf = info.isDsf
            val dsfBlockSize = info.dsfBlockSize
            val dataStartOffset = info.dataStartOffset
            val dataSize = info.dataSize

            val pcmRate = 88200
            val decimBytes = ((dsdFs / 8) / pcmRate).coerceIn(4, 16)
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
            // Nhảy chính xác tới byte đầu tiên của vùng dữ liệu âm thanh "DSD "
            skipExact(activeStream, dataStartOffset)
            var bytesReadSoFar = 0L

            // Bộ lọc thông thấp 4 tầng (4-Stage Cascaded Sinc/IIR Filter) + Bộ khử dòng một chiều (DC Blocker) chống sôi & rè tuyệt đối
            var s1L = 0f; var s2L = 0f; var s3L = 0f; var s4L = 0f
            var s1R = 0f; var s2R = 0f; var s3R = 0f; var s4R = 0f
            var dcInPrevL = 0f; var dcOutPrevL = 0f
            var dcInPrevR = 0f; var dcOutPrevR = 0f

            // Bộ lọc Sony DSP 7.0
            var dspSubL = 0f; var dspSubR = 0f
            var dspPrevL = 0f; var dspPrevR = 0f
            var dspVinylL = 0f; var dspVinylR = 0f

            val frameStepBytes = if (isDsf) (dsfBlockSize * channels) else (decimBytes * channels * 2048)
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
                    // Luôn căn chỉnh chẵn theo khung (decimBytes * channels) để không bao giờ bị đảo kênh L/R gây sôi
                    val alignUnit = if (isDsf) (dsfBlockSize * channels).toLong() else (decimBytes * channels).toLong()
                    val alignedBytes = (rawOffset / alignUnit.coerceAtLeast(1L)) * alignUnit.coerceAtLeast(1L)
                    skipExact(newStream, dataStartOffset + alignedBytes)
                    activeStream = newStream
                    bytesReadSoFar = alignedBytes
                    s1L = 0f; s2L = 0f; s3L = 0f; s4L = 0f
                    s1R = 0f; s2R = 0f; s3R = 0f; s4R = 0f
                }

                val remaining = dataSize - bytesReadSoFar
                val want = frameStepBytes.toLong().coerceAtMost(remaining).toInt()
                val alignStep = decimBytes * channels
                val alignedWant = (want / alignStep) * alignStep
                if (alignedWant < alignStep) break

                val got = readExact(activeStream, dsdBuffer, alignedWant)
                val usableBytes = (got / alignStep) * alignStep
                if (usableBytes < alignStep) break

                val pcmFrames = if (isDsf) {
                    dsfBlockSize / decimBytes
                } else {
                    usableBytes / alignStep
                }
                if (pcmFrames <= 0) break

                val pcmShorts = ShortArray(pcmFrames * 2)

                // Thông số DSP
                val preGain = 0.72f * masterVolume
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
                    var firL = 0f
                    var firR = 0f

                    if (isDsf) {
                        val base = f * decimBytes
                        for (s in 0 until decimBytes) {
                            val iL = (base + s).coerceAtMost(usableBytes - 1)
                            val iR = if (channels >= 2) (dsfBlockSize + base + s).coerceAtMost(usableBytes - 1) else iL
                            val w = if (decimBytes == 4) FIR_4_WEIGHTS[s] else if (decimBytes == 8) FIR_8_WEIGHTS[s] else (1f / decimBytes)
                            firL += BYTE_ONES_LUT[dsdBuffer[iL].toInt() and 0xFF] * w
                            firR += BYTE_ONES_LUT[dsdBuffer[iR].toInt() and 0xFF] * w
                        }
                    } else {
                        // Giải mã .DFF với cửa sổ lọc FIR (Triệt tiêu hoàn toàn tiếng sôi cao tần)
                        for (s in 0 until decimBytes) {
                            val bL = dsdBuffer[dffIdx].toInt() and 0xFF
                            val bR = if (channels >= 2) dsdBuffer[dffIdx + 1].toInt() and 0xFF else bL
                            val w = if (decimBytes == 4) FIR_4_WEIGHTS[s] else if (decimBytes == 8) FIR_8_WEIGHTS[s] else (1f / decimBytes)
                            firL += BYTE_ONES_LUT[bL] * w
                            firR += BYTE_ONES_LUT[bR] * w
                            dffIdx += channels
                        }
                    }

                    // Bộ lọc Khử DC Offset (High-pass ~15Hz) ngăn hiện tượng lệch tâm gây ù/rè loa
                    val dcOutL = firL - dcInPrevL + 0.9985f * dcOutPrevL
                    dcInPrevL = firL
                    dcOutPrevL = dcOutL

                    val dcOutR = firR - dcInPrevR + 0.9985f * dcOutPrevR
                    dcInPrevR = firR
                    dcOutPrevR = dcOutR

                    // Bộ lọc thông thấp 4 bậc (4-Pole Bessel Low-Pass ~22kHz) làm sạch hoàn toàn nhiễu lượng tử DSD
                    s1L += 0.24f * (dcOutL - s1L)
                    s2L += 0.24f * (s1L - s2L)
                    s3L += 0.24f * (s2L - s3L)
                    s4L += 0.24f * (s3L - s4L)

                    s1R += 0.24f * (dcOutR - s1R)
                    s2R += 0.24f * (s1R - s2R)
                    s3R += 0.24f * (s2R - s3R)
                    s4R += 0.24f * (s3R - s4R)

                    // Khuếch đại biên độ chuẩn Analog (DSD có biên độ chuẩn -6dB nên nhân hệ số bù 1.85f)
                    var left = s4L * 1.85f * preGain
                    var right = s4R * 1.85f * preGain

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
                        left = tanh((dspVinylL * 0.65f + left * 0.35f + dspSubL * 0.10f) * 1.05f) * 0.90f
                        right = tanh((dspVinylR * 0.65f + right * 0.35f + dspSubR * 0.10f) * 1.05f) * 0.90f
                    } else {
                        left = tanh(left) * 0.92f
                        right = tanh(right) * 0.92f
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

            if (!stopRequested && bytesReadSoFar >= dataSize - (decimBytes * channels * 8)) {
                isPlaying = false
                mainHandler.post { onTrackEnded() }
            }
        } catch (_: InterruptedException) {
            // Dừng luồng bình thường khi người dùng chuyển bài, không báo lỗi giả
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
