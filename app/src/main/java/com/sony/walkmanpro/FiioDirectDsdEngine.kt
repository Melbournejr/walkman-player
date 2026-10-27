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
        // Bảng lọc giải mã nhanh 1-bit DSD sang biên độ PCM chuẩn cho cả MSB (.DFF) và LSB (.DSF)
        private val MSB_LUT = FloatArray(256)
        private val LSB_LUT = FloatArray(256)

        init {
            val weights = floatArrayOf(0.08f, 0.11f, 0.14f, 0.17f, 0.17f, 0.14f, 0.11f, 0.08f)
            for (b in 0..255) {
                var m = 0f
                var l = 0f
                for (bit in 0..7) {
                    m += (if (((b shr (7 - bit)) and 1) == 1) 1f else -1f) * weights[bit]
                    l += (if (((b shr bit) and 1) == 1) 1f else -1f) * weights[bit]
                }
                MSB_LUT[b] = m
                LSB_LUT[b] = l
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
            workerThread?.interrupt()
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

    private fun runDsdLoop(uri: Uri) {
        var audioTrack: AudioTrack? = null
        try {
            // Lấy tổng dung lượng file thật
            var totalFileBytes = 0L
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0) totalFileBytes = afd.length
                }
            } catch (_: Throwable) {}

            val firstStream = context.contentResolver.openInputStream(uri) ?: run {
                showToastOnMain("Không thể mở file DSD này!")
                isPlaying = false
                return
            }

            // Phân tích Header của .DFF hoặc .DSF
            val h16 = ByteArray(16)
            if (readExact(firstStream, h16, 16) < 16) {
                firstStream.close()
                isPlaying = false
                return
            }

            val magic = String(h16, 0, 4, Charsets.ISO_8859_1)
            var isDsf = false
            var isLsb = false
            var isDstCompressed = false
            var channels = 2
            var dsdFs = 2822400
            var dsfBlockSize = 4096
            var dataStartOffset = 0L
            var dataSize = 0L

            if (magic == "DSD ") {
                isDsf = true
                val rest76 = ByteArray(76)
                readExact(firstStream, rest76, 76)
                val full = h16 + rest76
                val bb = ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN)
                channels = bb.getInt(48).coerceIn(1, 6)
                dsdFs = bb.getInt(52).coerceIn(2822400, 22579200)
                isLsb = (bb.getInt(56) == 1)
                dsfBlockSize = bb.getInt(64).coerceIn(256, 65536)
                val dataChunkSz = bb.getLong(84)
                dataStartOffset = 92L
                dataSize = when {
                    dataChunkSz > 12L -> dataChunkSz - 12L
                    totalFileBytes > 92L -> totalFileBytes - 92L
                    else -> 64L * 1024L * 1024L
                }
            } else if (magic == "FRM8") {
                isDsf = false
                isLsb = false
                var offsetTracker = 16L
                val chunkHdr = ByteArray(12)
                var loops = 0

                while (loops < 128 && readExact(firstStream, chunkHdr, 12) == 12) {
                    loops++
                    offsetTracker += 12L
                    val id = String(chunkHdr, 0, 4, Charsets.ISO_8859_1)
                    val sz = ByteBuffer.wrap(chunkHdr, 4, 8).order(ByteOrder.BIG_ENDIAN).long
                    if (sz < 0L) break

                    when (id) {
                        "PROP" -> {
                            if (sz in 4L..131072L) {
                                val prop = ByteArray(sz.toInt())
                                readExact(firstStream, prop, prop.size)
                                offsetTracker += sz
                                // Quét tìm FS, CHNL, CMPR bên trong PROP
                                var p = 4
                                while (p <= prop.size - 12) {
                                    val subId = String(prop, p, 4, Charsets.ISO_8859_1)
                                    val subSz = ByteBuffer.wrap(prop, p + 4, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
                                    p += 12
                                    if (subSz < 0 || p + subSz > prop.size) break
                                    if (subId == "FS  " && subSz >= 4) {
                                        val fs = ByteBuffer.wrap(prop, p, 4).order(ByteOrder.BIG_ENDIAN).int
                                        if (fs in 2822400..22579200) dsdFs = fs
                                    } else if (subId == "CHNL" && subSz >= 2) {
                                        val ch = ByteBuffer.wrap(prop, p, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                                        if (ch in 1..6) channels = ch
                                    } else if (subId == "CMPR" && subSz >= 4) {
                                        val cmpr = String(prop, p, 4, Charsets.ISO_8859_1)
                                        if (cmpr == "DST ") isDstCompressed = true
                                    }
                                    p += if (subSz % 2 != 0) subSz + 1 else subSz
                                }
                                if (sz % 2L != 0L) {
                                    skipExact(firstStream, 1L)
                                    offsetTracker += 1L
                                }
                            } else {
                                val pad = if (sz % 2L != 0L) sz + 1L else sz
                                skipExact(firstStream, pad)
                                offsetTracker += pad
                            }
                        }
                        "DSD " -> {
                            dataStartOffset = offsetTracker
                            dataSize = when {
                                sz > 0L -> sz
                                totalFileBytes > dataStartOffset -> totalFileBytes - dataStartOffset
                                else -> 64L * 1024L * 1024L
                            }
                            break
                        }
                        "DST " -> {
                            isDstCompressed = true
                            break
                        }
                        else -> {
                            val pad = if (sz % 2L != 0L) sz + 1L else sz
                            skipExact(firstStream, pad)
                            offsetTracker += pad
                        }
                    }
                }
            } else {
                firstStream.close()
                showToastOnMain("Định dạng file DSD không hợp lệ!")
                isPlaying = false
                return
            }

            if (isDstCompressed) {
                firstStream.close()
                showToastOnMain("File .DFF này dùng nén khóa DST (SACD ISO). Hãy dùng file .DFF hoặc .DSF chuẩn Uncompressed DSD!")
                isPlaying = false
                return
            }

            val pcmRate = 88200
            val decimBytes = ((dsdFs / 8) / pcmRate).coerceIn(4, 32)
            val bytesPerSecond = ((dsdFs / 8) * channels).toLong().coerceAtLeast(1L)
            durationMs = ((dataSize * 1000L) / bytesPerSecond).toInt().coerceAtLeast(1000)

            // Khởi tạo phần cứng AudioTrack 88.2kHz Stereo trực tiếp như FiiO Music
            val minBufSize = AudioTrack.getMinBufferSize(
                pcmRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(16384)

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

            var activeStream: InputStream = firstStream
            var bytesReadSoFar = 0L

            // Bộ lọc thông thấp DSD + Bộ lọc DSP Sony (Vinyl, ClearAudio+, 10-Band EQ)
            var lp1L = 0f; var lp2L = 0f; var lp3L = 0f
            var lp1R = 0f; var lp2R = 0f; var lp3R = 0f
            var dspSubL = 0f; var dspSubR = 0f
            var dspPrevL = 0f; var dspPrevR = 0f
            var dspVinylL = 0f; var dspVinylR = 0f

            val lut = if (isLsb) LSB_LUT else MSB_LUT
            val invDecim = 1.0f / decimBytes.toFloat()

            val frameStepBytes = if (isDsf) (dsfBlockSize * channels) else (decimBytes * channels * 1024)
            val dsdBuffer = ByteArray(frameStepBytes)

            while (!stopRequested && bytesReadSoFar < dataSize) {
                if (pauseRequested) {
                    Thread.sleep(60)
                    continue
                }

                // Xử lý khi người dùng kéo thanh tua (Seekbar)
                val targetSeek = seekTargetMs
                if (targetSeek >= 0) {
                    seekTargetMs = -1
                    try { activeStream.close() } catch (_: Throwable) {}
                    val newStream = context.contentResolver.openInputStream(uri) ?: break
                    val ratio = (targetSeek.toDouble() / durationMs.coerceAtLeast(1).toDouble()).coerceIn(0.0, 0.99)
                    val rawOffset = (dataSize * ratio).toLong()
                    val alignUnit = if (isDsf) (dsfBlockSize * channels).toLong() else (decimBytes * channels).toLong()
                    val alignedBytes = (rawOffset / alignUnit.coerceAtLeast(1L)) * alignUnit.coerceAtLeast(1L)
                    skipExact(newStream, dataStartOffset + alignedBytes)
                    activeStream = newStream
                    bytesReadSoFar = alignedBytes
                    lp1L = 0f; lp2L = 0f; lp3L = 0f
                    lp1R = 0f; lp2R = 0f; lp3R = 0f
                }

                val toRead = frameStepBytes.toLong().coerceAtMost(dataSize - bytesReadSoFar).toInt()
                if (toRead < decimBytes * channels) break

                val got = readExact(activeStream, dsdBuffer, toRead)
                if (got < decimBytes * channels) break

                val pcmFrames = if (isDsf) {
                    dsfBlockSize / decimBytes
                } else {
                    got / (decimBytes * channels)
                }
                if (pcmFrames <= 0) break

                val pcmShorts = ShortArray(pcmFrames * 2)

                // Lấy cấu hình DSP Sony trực tiếp
                val preGain = 0.78f * masterVolume
                val eqGains = dspProcessor.eq10Gains
                val eqLow = ((eqGains[0] + eqGains[1] + eqGains[2]) / 3f) * 0.05f
                val eqMid = ((eqGains[3] + eqGains[4] + eqGains[5] + eqGains[6]) / 4f) * 0.04f
                val eqHigh = ((eqGains[7] + eqGains[8] + eqGains[9]) / 3f) * 0.05f
                val bassBoost = (dspProcessor.megaBassLevel * 0.55f) + eqLow
                val clearAudio = dspProcessor.clearAudioEnabled
                val dsee = dspProcessor.dseeEnabled
                val vinyl = dspProcessor.vinylEnabled
                val bal = dspProcessor.channelBalance
                val leftPan = if (bal > 0f) (1f - bal).coerceIn(0f, 1f) else 1f
                val rightPan = if (bal < 0f) (1f + bal).coerceIn(0f, 1f) else 1f
                val phase = if (dspProcessor.phaseInvert) -1f else 1f

                var dffIdx = 0
                for (f in 0 until pcmFrames) {
                    var sumL = 0f
                    var sumR = 0f

                    if (isDsf) {
                        val base = f * decimBytes
                        for (s in 0 until decimBytes) {
                            val iL = (base + s).coerceAtMost(got - 1)
                            val iR = if (channels >= 2) (dsfBlockSize + base + s).coerceAtMost(got - 1) else iL
                            sumL += lut[dsdBuffer[iL].toInt() and 0xFF]
                            sumR += lut[dsdBuffer[iR].toInt() and 0xFF]
                        }
                    } else {
                        for (s in 0 until decimBytes) {
                            val bL = dsdBuffer[dffIdx].toInt() and 0xFF
                            val bR = if (channels >= 2) dsdBuffer[dffIdx + 1].toInt() and 0xFF else bL
                            sumL += lut[bL]
                            sumR += lut[bR]
                            dffIdx += channels
                        }
                    }

                    // Lọc thông thấp 3 tầng giải mã DSD 1-Bit
                    lp1L += 0.32f * (sumL * invDecim - lp1L)
                    lp2L += 0.32f * (lp1L - lp2L)
                    lp3L += 0.32f * (lp2L - lp3L)

                    lp1R += 0.32f * (sumR * invDecim - lp1R)
                    lp2R += 0.32f * (lp1R - lp2R)
                    lp3R += 0.32f * (lp2R - lp3R)

                    var left = lp3L * preGain
                    var right = lp3R * preGain

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
                        val side = (left - right) * 0.5f * 1.28f
                        left = (mid + side) + dspSubL * 0.10f + highL * 0.08f
                        right = (mid - side) + dspSubR * 0.10f + highR * 0.08f
                    }
                    if (dsee) {
                        left += highL * 0.15f
                        right += highR * 0.15f
                    }
                    if (vinyl) {
                        dspVinylL += 0.35f * (left - dspVinylL)
                        dspVinylR += 0.35f * (right - dspVinylR)
                        left = tanh((dspVinylL * 0.65f + left * 0.35f + dspSubL * 0.12f) * 1.05f) * 0.92f
                        right = tanh((dspVinylR * 0.65f + right * 0.35f + dspSubR * 0.12f) * 1.05f) * 0.92f
                    } else {
                        left = tanh(left) * 0.95f
                        right = tanh(right) * 0.95f
                    }

                    left = left * leftPan * phase
                    right = right * rightPan * phase

                    pcmShorts[f * 2] = (left * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    pcmShorts[f * 2 + 1] = (right * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }

                audioTrack.write(pcmShorts, 0, pcmShorts.size)
                bytesReadSoFar += got
                currentPositionMs = ((bytesReadSoFar.toDouble() / dataSize.coerceAtLeast(1L).toDouble()) * durationMs).toInt()
            }

            try { activeStream.close() } catch (_: Throwable) {}

            if (!stopRequested && bytesReadSoFar >= dataSize - (decimBytes * channels * 4)) {
                isPlaying = false
                mainHandler.post { onTrackEnded() }
            }
        } catch (t: Throwable) {
            showToastOnMain("Lỗi đọc luồng DSD: ${t.javaClass.simpleName}")
            isPlaying = false
        } finally {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Throwable) {}
        }
    }
}
