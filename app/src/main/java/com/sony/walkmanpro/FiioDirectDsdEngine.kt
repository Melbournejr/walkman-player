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
import kotlin.math.abs

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
        seekTargetMs = -1

        workerThread = Thread {
            runFFmpegDirectDsdPlayback(uri, startMs)
        }.apply {
            name = "WalkmanFFmpegDirectThread"
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
            workerThread?.join(400)
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

    private fun createHighResFloatAudioTrack(dsdFs: Int): Pair<AudioTrack, Int> {
        val preferredRate = if (dsdFs >= 5644800) 176400 else 88200
        for (rate in listOf(preferredRate, 176400, 88200).distinct()) {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    rate,
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
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build()
                        )
                        .setBufferSizeInBytes((minBuf * 4).coerceAtLeast(65536))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        return Pair(track, rate)
                    } else {
                        track.release()
                    }
                }
            } catch (_: Throwable) {}
        }

        val fallback = AudioTrack.Builder()
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
        return Pair(fallback, 88200)
    }

    private fun runFFmpegDirectDsdPlayback(uri: Uri, initialStartMs: Int) {
        var audioTrack: AudioTrack? = null
        try {
            var totalFileBytes = 0L
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0) totalFileBytes = afd.length
                }
            } catch (_: Throwable) {}

            val info = parseDsdHeaderAccurate(context, uri, totalFileBytes)
            val channels = info.channels.coerceAtLeast(1)
            val dsdFs = info.dsdSampleRate.coerceAtLeast(2822400)
            val bytesPerSec = ((dsdFs / 8) * channels).toLong().coerceAtLeast(1L)
            if (info.dataSize > 0L) {
                durationMs = ((info.dataSize * 1000L) / bytesPerSec).toInt().coerceAtLeast(1000)
            }

            if (!FFmpegNativeDsdDecoder.isAvailable()) {
                showToastOnMain("Lỗi: Thư viện C++ FFmpeg chưa được nạp!")
                isPlaying = false
                return
            }

            val (track, targetPcmRate) = createHighResFloatAudioTrack(dsdFs)
            audioTrack = track
            audioTrack.play()

            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val callback = object : NativePcmCallback {
                    override fun onNativePcmChunk(
                        pcmBuffer: FloatArray,
                        floatSampleCount: Int,
                        currentPositionMs: Long
                    ): Int {
                        while (pauseRequested && !stopRequested) {
                            try { Thread.sleep(30) } catch (_: Throwable) {}
                        }
                        if (stopRequested) return -1

                        audioTrack.write(pcmBuffer, 0, floatSampleCount, AudioTrack.WRITE_BLOCKING)
                        this@FiioDirectDsdEngine.currentPositionMs = currentPositionMs.toInt().coerceAtMost(durationMs)
                        return 0
                    }

                    override fun getRealtimeDspParams(outParams: FloatArray): Int {
                        if (stopRequested) return -1
                        val pendingSeek = seekTargetMs
                        if (pendingSeek >= 0) {
                            seekTargetMs = -1
                            return pendingSeek.coerceAtLeast(1)
                        }

                        val rawBal = dspProcessor.channelBalance
                        val bal = if (abs(rawBal) < 0.03f) 0f else rawBal.coerceIn(-0.5f, 0.5f)
                        val leftPan = if (bal > 0f) (1f - bal).coerceIn(0f, 1f) else 1f
                        val rightPan = if (bal < 0f) (1f + bal).coerceIn(0f, 1f) else 1f

                        outParams[0] = masterVolume
                        outParams[1] = if (pureDsdMode) 1.0f else 0.0f
                        outParams[2] = dspProcessor.megaBassLevel
                        outParams[3] = if (dspProcessor.clearAudioEnabled) 1.0f else 0.0f
                        outParams[4] = if (dspProcessor.dseeEnabled) 1.0f else 0.0f
                        outParams[5] = if (dspProcessor.vinylEnabled) 1.0f else 0.0f
                        outParams[6] = leftPan
                        outParams[7] = rightPan
                        outParams[8] = if (dspProcessor.phaseInvert) -1.0f else 1.0f
                        for (i in 0 until 10) {
                            outParams[9 + i] = dspProcessor.eq10Gains[i]
                        }
                        return 0
                    }
                }

                val decodeStatus = FFmpegNativeDsdDecoder.nativeDecodeDsdFile(
                    pfd.fd,
                    initialStartMs,
                    targetPcmRate,
                    callback
                )

                if (decodeStatus == 0 && !stopRequested) {
                    isPlaying = false
                    mainHandler.post { onTrackEnded() }
                }
            }
        } catch (t: Throwable) {
            if (!stopRequested) {
                showToastOnMain("Lỗi giải mã FFmpeg DSD: ${t.localizedMessage}")
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

