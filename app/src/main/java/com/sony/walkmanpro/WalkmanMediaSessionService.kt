package com.sony.walkmanpro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 1. BỘ XỬ LÝ DSP THỜI GIAN THỰC 64-BIT DOUBLE PRECISION CHO EXOPLAYER (PCM 16-BIT / 24-BIT / 32-BIT / FLOAT)
 * - Áp dụng trực tiếp lên luồng PCM trước khi ra AudioTrack:
 *   + Pure DSD Mode (Bypass 100% toàn bộ DSP khi bật)
 *   + 10-Band Graphic Equalizer độc lập (31Hz .. 16kHz)
 *   + Mega Bass (Low-Shelf 55Hz + Punch 90Hz + Mud-Cut 230Hz)
 *   + Clear Audio+ (Stereo Mid/Side 1.38x + Contour Bass/Vocal/Air)
 *   + DSEE (High-Frequency Harmonic Exciter + 8.5kHz Air Shelf)
 *   + Vinyl Processor (Even-Order Tube/Analog Warmth + 165Hz Body + 13kHz Soft Roll-off)
 *   + Channel Balance & Phase Invert 180°
 */
@UnstableApi
class SonyRealTimeDspProcessor : BaseAudioProcessor() {
    @Volatile var pureDirectBypass: Boolean = false
    @Volatile var megaBassLevel: Float = 0.45f
    @Volatile var vinylEnabled: Boolean = true
    @Volatile var clearAudioEnabled: Boolean = true
    @Volatile var dseeEnabled: Boolean = true
    @Volatile var channelBalance: Float = 0.0f
    @Volatile var phaseInvert: Boolean = false
    val eq10Gains = FloatArray(10) { 0f }

    companion object {
        private val EQ10_FREQS = doubleArrayOf(
            31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
        )
    }

    private class StereoBiquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0
        var a1 = 0.0; var a2 = 0.0
        var z1L = 0.0; var z2L = 0.0
        var z1R = 0.0; var z2R = 0.0

        fun reset() {
            z1L = 0.0; z2L = 0.0; z1R = 0.0; z2R = 0.0
        }

        fun setBypass() {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
        }

        fun setHighPass(fs: Double, f0: Double, q: Double) {
            if (f0 <= 0.0 || f0 >= fs * 0.48) { setBypass(); return }
            val w0 = 2.0 * PI * f0 / fs
            val cosw0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosw0) * 0.5) / a0
            b1 = -(1.0 + cosw0) / a0
            b2 = ((1.0 + cosw0) * 0.5) / a0
            a1 = (-2.0 * cosw0) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun setPeaking(fs: Double, f0: Double, q: Double, gainDb: Double) {
            if (abs(gainDb) < 0.03 || f0 <= 0.0 || f0 >= fs * 0.48) {
                setBypass()
                return
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / fs
            val cosw0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha / a
            b0 = (1.0 + alpha * a) / a0
            b1 = (-2.0 * cosw0) / a0
            b2 = (1.0 - alpha * a) / a0
            a1 = (-2.0 * cosw0) / a0
            a2 = (1.0 - alpha / a) / a0
        }

        fun setLowShelf(fs: Double, f0: Double, q: Double, gainDb: Double) {
            if (abs(gainDb) < 0.03 || f0 <= 0.0 || f0 >= fs * 0.48) {
                setBypass()
                return
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / fs
            val cosw0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            val a0 = (a + 1.0) + (a - 1.0) * cosw0 + twoSqrtAAlpha
            b0 = (a * ((a + 1.0) - (a - 1.0) * cosw0 + twoSqrtAAlpha)) / a0
            b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosw0)) / a0
            b2 = (a * ((a + 1.0) - (a - 1.0) * cosw0 - twoSqrtAAlpha)) / a0
            a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosw0)) / a0
            a2 = ((a + 1.0) + (a - 1.0) * cosw0 - twoSqrtAAlpha) / a0
        }

        fun setHighShelf(fs: Double, f0: Double, q: Double, gainDb: Double) {
            if (abs(gainDb) < 0.03 || f0 <= 0.0 || f0 >= fs * 0.48) {
                setBypass()
                return
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / fs
            val cosw0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            val a0 = (a + 1.0) - (a - 1.0) * cosw0 + twoSqrtAAlpha
            b0 = (a * ((a + 1.0) + (a - 1.0) * cosw0 + twoSqrtAAlpha)) / a0
            b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosw0)) / a0
            b2 = (a * ((a + 1.0) + (a - 1.0) * cosw0 - twoSqrtAAlpha)) / a0
            a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosw0)) / a0
            a2 = ((a + 1.0) - (a - 1.0) * cosw0 - twoSqrtAAlpha) / a0
        }

        fun processL(x: Double): Double {
            val y = b0 * x + z1L
            z1L = b1 * x - a1 * y + z2L
            z2L = b2 * x - a2 * y
            return y
        }

        fun processR(x: Double): Double {
            val y = b0 * x + z1R
            z1R = b1 * x - a1 * y + z2R
            z2R = b2 * x - a2 * y
            return y
        }
    }

    private val subsonicHp = StereoBiquad()
    private val eqBands = Array(10) { StereoBiquad() }

    private val megaBassSubShelf = StereoBiquad()
    private val megaBassPunchPeak = StereoBiquad()
    private val megaBassMudCut = StereoBiquad()

    private val clearBassFilter = StereoBiquad()
    private val clearVocalFilter = StereoBiquad()
    private val clearAirFilter = StereoBiquad()

    private val dseeExtractorHp = StereoBiquad()
    private val dseeAirShelf = StereoBiquad()

    private val vinylWarmthPeak = StereoBiquad()
    private val vinylRolloffShelf = StereoBiquad()

    // Chấp nhận mọi định dạng PCM (16-bit, 24-bit, 32-bit, Float) và xuất ra PCM_FLOAT hoặc PCM_16BIT
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        val supported = enc == C.ENCODING_PCM_16BIT ||
                enc == C.ENCODING_PCM_24BIT ||
                enc == C.ENCODING_PCM_24BIT_BIG_ENDIAN ||
                enc == C.ENCODING_PCM_32BIT ||
                enc == C.ENCODING_PCM_FLOAT
        if (!supported) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        val fs = inputAudioFormat.sampleRate.toDouble().coerceAtLeast(44100.0)
        subsonicHp.setHighPass(fs, 20.0, 0.7071)
        dseeExtractorHp.setHighPass(fs, 3600.0, 0.7071)

        // Nếu đầu vào là 24-bit hoặc 32-bit hoặc Float -> xuất thống nhất ra PCM_FLOAT 32-bit để giữ dải động cao nhất
        return if (enc == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                C.ENCODING_PCM_FLOAT
            )
        }
    }

    override fun onFlush() {
        subsonicHp.reset()
        for (b in eqBands) b.reset()
        megaBassSubShelf.reset()
        megaBassPunchPeak.reset()
        megaBassMudCut.reset()
        clearBassFilter.reset()
        clearVocalFilter.reset()
        clearAirFilter.reset()
        dseeExtractorHp.reset()
        dseeAirShelf.reset()
        vinylWarmthPeak.reset()
        vinylRolloffShelf.reset()
    }

    private fun updateFiltersForCurrentBlock(fs: Double): Boolean {
        var hasActiveEq = false
        for (i in 0 until 10) {
            val g = eq10Gains[i].toDouble()
            if (abs(g) > 0.03) hasActiveEq = true
            eqBands[i].setPeaking(fs, EQ10_FREQS[i], 1.414, g)
        }

        val mb = megaBassLevel.toDouble()
        if (mb > 0.01) {
            megaBassSubShelf.setLowShelf(fs, 55.0, 0.75, mb * 9.5)
            megaBassPunchPeak.setPeaking(fs, 90.0, 1.15, mb * 4.5)
            megaBassMudCut.setPeaking(fs, 230.0, 1.30, -mb * 2.2)
        } else {
            megaBassSubShelf.setBypass()
            megaBassPunchPeak.setBypass()
            megaBassMudCut.setBypass()
        }

        if (clearAudioEnabled) {
            clearBassFilter.setPeaking(fs, 65.0, 1.0, 2.6)
            clearVocalFilter.setPeaking(fs, 2600.0, 0.85, 3.2)
            clearAirFilter.setHighShelf(fs, 10500.0, 0.7071, 3.5)
        } else {
            clearBassFilter.setBypass()
            clearVocalFilter.setBypass()
            clearAirFilter.setBypass()
        }

        if (dseeEnabled) {
            dseeAirShelf.setHighShelf(fs, 8500.0, 0.75, 4.5)
        } else {
            dseeAirShelf.setBypass()
        }

        if (vinylEnabled) {
            vinylWarmthPeak.setPeaking(fs, 165.0, 0.85, 3.2)
            vinylRolloffShelf.setHighShelf(fs, 13000.0, 0.7071, -2.2)
        } else {
            vinylWarmthPeak.setBypass()
            vinylRolloffShelf.setBypass()
        }

        return hasActiveEq
    }

    private inline fun processStereoSamplePair(
        inL: Double,
        inR: Double,
        pureBypass: Boolean,
        hasActiveEq: Boolean,
        mbLevel: Float,
        clearAudio: Boolean,
        dsee: Boolean,
        vinyl: Boolean,
        leftPan: Double,
        rightPan: Double,
        phaseSign: Double,
        outPair: DoubleArray
    ) {
        var L = inL
        var R = inR

        if (!pureBypass) {
            L = subsonicHp.processL(L)
            R = subsonicHp.processR(R)

            // 1. 10-Band EQ
            if (hasActiveEq) {
                for (i in 0 until 10) {
                    L = eqBands[i].processL(L)
                    R = eqBands[i].processR(R)
                }
            }

            // 2. Mega Bass
            if (mbLevel > 0.01f) {
                L = megaBassSubShelf.processL(L)
                R = megaBassSubShelf.processR(R)
                L = megaBassPunchPeak.processL(L)
                R = megaBassPunchPeak.processR(R)
                L = megaBassMudCut.processL(L)
                R = megaBassMudCut.processR(R)
            }

            // 3. Vinyl Processor
            if (vinyl) {
                L = vinylWarmthPeak.processL(L)
                R = vinylWarmthPeak.processR(R)
                L = vinylRolloffShelf.processL(L)
                R = vinylRolloffShelf.processR(R)
                val dL = L * 1.15
                val dR = R * 1.15
                L = tanh(dL) + 0.045 * (dL * dL) * (if (L >= 0.0) 1.0 else -1.0)
                R = tanh(dR) + 0.045 * (dR * dR) * (if (R >= 0.0) 1.0 else -1.0)
            }

            // 4. DSEE Harmonic Exciter
            if (dsee) {
                val hfL = dseeExtractorHp.processL(L)
                val hfR = dseeExtractorHp.processR(R)
                L += tanh(hfL * 2.4) * 0.22
                R += tanh(hfR * 2.4) * 0.22
                L = dseeAirShelf.processL(L)
                R = dseeAirShelf.processR(R)
            }

            // 5. Clear Audio+
            if (clearAudio) {
                L = clearBassFilter.processL(L)
                R = clearBassFilter.processR(R)
                L = clearVocalFilter.processL(L)
                R = clearVocalFilter.processR(R)
                L = clearAirFilter.processL(L)
                R = clearAirFilter.processR(R)
                val mid = (L + R) * 0.5
                val side = (L - R) * 0.5 * 1.38
                L = mid * 0.96 + side
                R = mid * 0.96 - side
            }
        }

        L = L * leftPan * phaseSign
        R = R * rightPan * phaseSign

        // Soft-Knee Limiter bảo vệ chống vỡ tiếng (clipping)
        if (abs(L) > 0.92) L = (if (L > 0.0) 1.0 else -1.0) * (0.92 + 0.08 * tanh((abs(L) - 0.92) / 0.08))
        if (abs(R) > 0.92) R = (if (R > 0.0) 1.0 else -1.0) * (0.92 + 0.08 * tanh((abs(R) - 0.92) / 0.08))

        outPair[0] = L
        outPair[1] = R
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val fs = inputAudioFormat.sampleRate.toDouble().coerceAtLeast(44100.0)
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
        val inEnc = inputAudioFormat.encoding

        val pureBypass = pureDirectBypass
        val mbLevel = megaBassLevel
        val clearAudio = clearAudioEnabled
        val dsee = dseeEnabled
        val vinyl = vinylEnabled
        val hasActiveEq = if (!pureBypass) updateFiltersForCurrentBlock(fs) else false

        val bal = if (abs(channelBalance) < 0.03f) 0f else channelBalance.coerceIn(-0.5f, 0.5f)
        val leftPan = if (bal > 0f) (1.0 - bal).coerceIn(0.0, 1.0) else 1.0
        val rightPan = if (bal < 0f) (1.0 + bal).coerceIn(0.0, 1.0) else 1.0
        val phaseSign = if (phaseInvert && !pureBypass) -1.0 else 1.0

        val stereoPair = DoubleArray(2)

        when (inEnc) {
            C.ENCODING_PCM_16BIT -> {
                val outBuffer = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
                inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                while (inputBuffer.remaining() >= ch * 2) {
                    val inL = inputBuffer.short.toDouble() / 32768.0
                    val inR = if (ch >= 2) inputBuffer.short.toDouble() / 32768.0 else inL
                    for (c in 2 until ch) inputBuffer.short

                    processStereoSamplePair(
                        inL, inR, pureBypass, hasActiveEq, mbLevel, clearAudio, dsee, vinyl,
                        leftPan, rightPan, phaseSign, stereoPair
                    )

                    val sL = (stereoPair[0] * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                    val sR = (stereoPair[1] * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                    outBuffer.putShort(sL)
                    if (ch >= 2) outBuffer.putShort(sR)
                    for (c in 2 until ch) outBuffer.putShort(0)
                }
                outBuffer.flip()
            }

            C.ENCODING_PCM_24BIT -> {
                val frames = remaining / (ch * 3)
                val outBuffer = replaceOutputBuffer(frames * ch * 4).order(ByteOrder.LITTLE_ENDIAN)
                while (inputBuffer.remaining() >= ch * 3) {
                    val b0L = inputBuffer.get().toInt() and 0xFF
                    val b1L = inputBuffer.get().toInt() and 0xFF
                    val b2L = inputBuffer.get().toInt()
                    val intL = (b2L shl 16) or (b1L shl 8) or b0L
                    val inL = intL.toDouble() / 8388608.0

                    val inR = if (ch >= 2) {
                        val b0R = inputBuffer.get().toInt() and 0xFF
                        val b1R = inputBuffer.get().toInt() and 0xFF
                        val b2R = inputBuffer.get().toInt()
                        val intR = (b2R shl 16) or (b1R shl 8) or b0R
                        intR.toDouble() / 8388608.0
                    } else inL

                    for (c in 2 until ch) {
                        inputBuffer.get(); inputBuffer.get(); inputBuffer.get()
                    }

                    processStereoSamplePair(
                        inL, inR, pureBypass, hasActiveEq, mbLevel, clearAudio, dsee, vinyl,
                        leftPan, rightPan, phaseSign, stereoPair
                    )

                    outBuffer.putFloat(stereoPair[0].toFloat())
                    if (ch >= 2) outBuffer.putFloat(stereoPair[1].toFloat())
                    for (c in 2 until ch) outBuffer.putFloat(0f)
                }
                outBuffer.flip()
            }

            C.ENCODING_PCM_32BIT -> {
                val outBuffer = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
                inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                while (inputBuffer.remaining() >= ch * 4) {
                    val inL = inputBuffer.int.toDouble() / 2147483648.0
                    val inR = if (ch >= 2) inputBuffer.int.toDouble() / 2147483648.0 else inL
                    for (c in 2 until ch) inputBuffer.int

                    processStereoSamplePair(
                        inL, inR, pureBypass, hasActiveEq, mbLevel, clearAudio, dsee, vinyl,
                        leftPan, rightPan, phaseSign, stereoPair
                    )

                    outBuffer.putFloat(stereoPair[0].toFloat())
                    if (ch >= 2) outBuffer.putFloat(stereoPair[1].toFloat())
                    for (c in 2 until ch) outBuffer.putFloat(0f)
                }
                outBuffer.flip()
            }

            else -> {
                val outBuffer = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
                inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                while (inputBuffer.remaining() >= ch * 4) {
                    val inL = inputBuffer.float.toDouble()
                    val inR = if (ch >= 2) inputBuffer.float.toDouble() else inL
                    for (c in 2 until ch) inputBuffer.float

                    processStereoSamplePair(
                        inL, inR, pureBypass, hasActiveEq, mbLevel, clearAudio, dsee, vinyl,
                        leftPan, rightPan, phaseSign, stereoPair
                    )

                    outBuffer.putFloat(stereoPair[0].toFloat())
                    if (ch >= 2) outBuffer.putFloat(stereoPair[1].toFloat())
                    for (c in 2 until ch) outBuffer.putFloat(0f)
                }
                outBuffer.flip()
            }
        }
    }
}

/**
 * 2. BỘ NHẬN TÍN HIỆU NÚT BẤM TRÊN MÀN HÌNH KHÓA / TAI NGHE BLUETOOTH
 */
class WalkmanNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            WalkmanMediaSessionService.ACTION_PLAY_PAUSE -> {
                WalkmanMediaSessionService.commandCallback?.invoke("PLAY_PAUSE")
            }
            WalkmanMediaSessionService.ACTION_NEXT -> {
                WalkmanMediaSessionService.commandCallback?.invoke("NEXT")
            }
            WalkmanMediaSessionService.ACTION_PREV -> {
                WalkmanMediaSessionService.commandCallback?.invoke("PREV")
            }
        }
    }
}

/**
 * 3. DỊCH VỤ PHÁT NHẠC NỀN & MEDIA SESSION HỆ THỐNG
 */
class WalkmanMediaSessionService : Service() {
    companion object {
        const val CHANNEL_ID = "walkman_signature_playback_channel"
        const val NOTIF_ID = 1001
        const val ACTION_PLAY_PAUSE = "com.sony.walkmanpro.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.sony.walkmanpro.ACTION_NEXT"
        const val ACTION_PREV = "com.sony.walkmanpro.ACTION_PREV"

        var commandCallback: ((String) -> Unit)? = null
        private var mediaSession: MediaSessionCompat? = null

        fun updateNowPlayingNotification(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean,
            posMs: Long,
            durMs: Long,
            onCommand: (String) -> Unit
        ) {
            commandCallback = onCommand
            ensureChannel(context)

            if (mediaSession == null) {
                mediaSession = MediaSessionCompat(context.applicationContext, "SonyWalkmanSession").apply {
                    setCallback(object : MediaSessionCompat.Callback() {
                        override fun onPlay() { commandCallback?.invoke("PLAY_PAUSE") }
                        override fun onPause() { commandCallback?.invoke("PLAY_PAUSE") }
                        override fun onSkipToNext() { commandCallback?.invoke("NEXT") }
                        override fun onSkipToPrevious() { commandCallback?.invoke("PREV") }
                        override fun onSeekTo(pos: Long) { commandCallback?.invoke("SEEK:$pos") }
                    })
                    isActive = true
                }
            }

            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durMs.coerceAtLeast(1000L))
                .build()
            mediaSession?.setMetadata(metadata)

            val state = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    posMs.coerceAtLeast(0L),
                    if (isPlaying) 1.0f else 0.0f
                )
                .build()
            mediaSession?.setPlaybackState(state)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(context, title, artist, isPlaying))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID,
                        "Walkman Signature Playback",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(ch)
                }
            }
        }

        fun buildNotification(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean
        ): Notification {
            ensureChannel(context)

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPending = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val prevPending = PendingIntent.getBroadcast(
                context, 1, Intent(context, WalkmanNotificationReceiver::class.java).apply { action = ACTION_PREV },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val playPausePending = PendingIntent.getBroadcast(
                context, 2, Intent(context, WalkmanNotificationReceiver::class.java).apply { action = ACTION_PLAY_PAUSE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nextPending = PendingIntent.getBroadcast(
                context, 3, Intent(context, WalkmanNotificationReceiver::class.java).apply { action = ACTION_NEXT },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(contentPending)
                .setOngoing(isPlaying)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Prev", prevPending)
                .addAction(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (isPlaying) "Pause" else "Play",
                    playPausePending
                )
                .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
                .setStyle(
                    MediaStyle()
                        .setMediaSession(mediaSession?.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIF_ID, buildNotification(this, "Sony Walkman Pro", "Ready", false))
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
