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
 * 1. BỘ XỬ LÝ MASTERING DSP 64-BIT DOUBLE PRECISION BIQUAD CASCADE CHO EXOPLAYER (PCM/FLAC/WAV/MP3)
 * - Tách riêng 10 bộ lọc Biquad độc lập cho 10 tần số Equalizer (31Hz, 62Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz).
 * - Tái tạo rõ nét 4 chế độ âm thanh: Mega Bass, Clear Audio+, DSEE Ultimate, Vinyl Processor.
 */
@UnstableApi
class SonyRealTimeDspProcessor : BaseAudioProcessor() {
    companion object {
        private val ISO_FREQS = doubleArrayOf(
            31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
        )
    }

    @Volatile var pureDirectBypass: Boolean = false
    @Volatile var megaBassLevel: Float = 0.25f
    @Volatile var vinylEnabled: Boolean = false
    @Volatile var clearAudioEnabled: Boolean = false
    @Volatile var dseeEnabled: Boolean = false
    @Volatile var channelBalance: Float = 0.0f
    @Volatile var phaseInvert: Boolean = false
    val eq10Gains = FloatArray(10) { 0f }

    private class StereoBiquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0
        var a1 = 0.0; var a2 = 0.0
        var z1L = 0.0; var z2L = 0.0
        var z1R = 0.0; var z2R = 0.0
        var active = false

        fun reset() {
            z1L = 0.0; z2L = 0.0; z1R = 0.0; z2R = 0.0
        }

        fun setBypass() {
            b0 = 1.0; b1 = 0.0; b2 = 0.0
            a1 = 0.0; a2 = 0.0
            active = false
        }

        fun setHighPass(fs: Double, f0: Double, q: Double) {
            val w0 = 2.0 * PI * f0 / fs
            val cosw0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosw0) * 0.5) / a0
            b1 = -(1.0 + cosw0) / a0
            b2 = ((1.0 + cosw0) * 0.5) / a0
            a1 = (-2.0 * cosw0) / a0
            a2 = (1.0 - alpha) / a0
            active = true
        }

        fun setPeaking(fs: Double, f0: Double, q: Double, gainDb: Double) {
            if (abs(gainDb) < 0.03 || f0 >= fs * 0.47) {
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
            active = true
        }

        fun setHighShelf(fs: Double, f0: Double, q: Double, gainDb: Double) {
            if (abs(gainDb) < 0.03 || f0 >= fs * 0.47) {
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
            active = true
        }

        fun processL(x: Double): Double {
            if (!active) return x
            val y = b0 * x + z1L
            z1L = b1 * x - a1 * y + z2L
            z2L = b2 * x - a2 * y
            return y
        }

        fun processR(x: Double): Double {
            if (!active) return x
            val y = b0 * x + z1R
            z1R = b1 * x - a1 * y + z2R
            z2R = b2 * x - a2 * y
            return y
        }
    }

    private val subsonicHp = StereoBiquad()
    private val eqBands = Array(10) { StereoBiquad() }

    private val megaBassPunch = StereoBiquad()
    private val megaBassClean = StereoBiquad()
    private val clearAudioDip = StereoBiquad()
    private val clearAudioVocal = StereoBiquad()
    private val dseePresence = StereoBiquad()
    private val dseeAirShelf = StereoBiquad()
    private val vinylWarmBody = StereoBiquad()
    private val vinylSweetMid = StereoBiquad()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val fs = inputAudioFormat.sampleRate.toDouble().coerceAtLeast(44100.0)
        subsonicHp.setHighPass(fs, 20.0, 0.7071)
        return inputAudioFormat
    }

    override fun onFlush() {
        subsonicHp.reset()
        for (b in eqBands) b.reset()
        megaBassPunch.reset()
        megaBassClean.reset()
        clearAudioDip.reset()
        clearAudioVocal.reset()
        dseePresence.reset()
        dseeAirShelf.reset()
        vinylWarmBody.reset()
        vinylSweetMid.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val fs = inputAudioFormat.sampleRate.toDouble().coerceAtLeast(44100.0)
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)

        val isAllEqFlat = eq10Gains.all { abs(it) < 0.05f }
        val isAllDspOff = pureDirectBypass || (megaBassLevel < 0.02f && !clearAudioEnabled && !dseeEnabled && !vinylEnabled && isAllEqFlat)

        var autoHeadroomAtten = 1.0

        if (!isAllDspOff) {
            // 1. Cấu hình 10 cần Equalizer độc lập (Đáp ứng 1:1 chuẩn xác từng tần số)
            var maxPositiveEq = 0.0
            for (i in 0 until 10) {
                val g = eq10Gains[i].toDouble()
                eqBands[i].setPeaking(fs, ISO_FREQS[i], 1.414, g)
                if (g > maxPositiveEq) maxPositiveEq = g
            }

            // 2. Cấu hình Mega Bass (0..1 -> 0..+6.0dB @ 55Hz kèm khử bùn 215Hz)
            val bassDb = megaBassLevel.toDouble() * 6.0
            if (bassDb > 0.05) {
                megaBassPunch.setPeaking(fs, 55.0, 0.90, bassDb)
                megaBassClean.setPeaking(fs, 215.0, 1.35, -bassDb * 0.22)
            } else {
                megaBassPunch.setBypass()
                megaBassClean.setBypass()
            }

            // 3. Cấu hình Clear Audio+ (Khử âm hộp 280Hz, đẩy vocal 2.4kHz rõ nét)
            if (clearAudioEnabled) {
                clearAudioDip.setPeaking(fs, 280.0, 1.40, -1.8)
                clearAudioVocal.setPeaking(fs, 2400.0, 0.85, 2.2)
            } else {
                clearAudioDip.setBypass()
                clearAudioVocal.setBypass()
            }

            // 4. Cấu hình DSEE Ultimate (Nâng chi tiết 5.5kHz & High-Shelf Air 10.5kHz)
            if (dseeEnabled) {
                dseePresence.setPeaking(fs, 5500.0, 1.00, 1.6)
                dseeAirShelf.setHighShelf(fs, 10500.0, 0.707, 3.2)
            } else {
                dseePresence.setBypass()
                dseeAirShelf.setBypass()
            }

            // 5. Cấu hình Vinyl Processor (Ấm mộc 145Hz & ngọt trung âm 850Hz)
            if (vinylEnabled) {
                vinylWarmBody.setPeaking(fs, 145.0, 0.85, 2.0)
                vinylSweetMid.setPeaking(fs, 850.0, 0.75, 1.4)
            } else {
                vinylWarmBody.setBypass()
                vinylSweetMid.setBypass()
            }

            val totalBoostEst = (maxPositiveEq * 0.65) + (bassDb * 0.55) +
                    (if (clearAudioEnabled) 1.0 else 0.0) +
                    (if (dseeEnabled) 1.2 else 0.0) +
                    (if (vinylEnabled) 1.0 else 0.0)
            if (totalBoostEst > 1.5) {
                autoHeadroomAtten = 10.0.pow(-(totalBoostEst - 1.5) / 28.0)
            }
        }

        val bal = if (abs(channelBalance) < 0.03f) 0f else channelBalance.coerceIn(-0.5f, 0.5f)
        val leftPan = if (bal > 0f) (1.0 - bal).coerceIn(0.0, 1.0) else 1.0
        val rightPan = if (bal < 0f) (1.0 + bal).coerceIn(0.0, 1.0) else 1.0
        val phase = if (phaseInvert) -1.0 else 1.0

        val outBuffer = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= ch * 2) {
                var L = inputBuffer.short.toDouble() / 32768.0
                var R = if (ch >= 2) inputBuffer.short.toDouble() / 32768.0 else L
                for (c in 2 until ch) inputBuffer.short

                L = subsonicHp.processL(L)
                R = subsonicHp.processR(R)

                if (!isAllDspOff) {
                    L *= autoHeadroomAtten
                    R *= autoHeadroomAtten

                    for (i in 0 until 10) {
                        L = eqBands[i].processL(L)
                        R = eqBands[i].processR(R)
                    }

                    L = megaBassClean.processL(L)
                    R = megaBassClean.processR(R)
                    L = megaBassPunch.processL(L)
                    R = megaBassPunch.processR(R)

                    if (clearAudioEnabled) {
                        L = clearAudioDip.processL(L)
                        R = clearAudioDip.processR(R)
                        L = clearAudioVocal.processL(L)
                        R = clearAudioVocal.processR(R)
                        val mid = (L + R) * 0.5
                        val side = (L - R) * 0.5 * 1.24
                        L = mid + side
                        R = mid - side
                    }

                    if (vinylEnabled) {
                        L = vinylWarmBody.processL(L)
                        R = vinylWarmBody.processR(R)
                        L = vinylSweetMid.processL(L)
                        R = vinylSweetMid.processR(R)
                        L += 0.045 * (L * L) * (if (L >= 0.0) 1.0 else -1.0)
                        R += 0.045 * (R * R) * (if (R >= 0.0) 1.0 else -1.0)
                    }

                    if (dseeEnabled) {
                        L = dseePresence.processL(L)
                        R = dseePresence.processR(R)
                        L = dseeAirShelf.processL(L)
                        R = dseeAirShelf.processR(R)
                    }
                }

                L = L * leftPan * phase
                R = R * rightPan * phase

                if (abs(L) > 0.92) L = (if (L > 0) 1.0 else -1.0) * (0.92 + 0.08 * tanh((abs(L) - 0.92) / 0.08))
                if (abs(R) > 0.92) R = (if (R > 0) 1.0 else -1.0) * (0.92 + 0.08 * tanh((abs(R) - 0.92) / 0.08))

                val sL = (L * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                val sR = (R * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                outBuffer.putShort(sL)
                if (ch >= 2) outBuffer.putShort(sR)
                for (c in 2 until ch) outBuffer.putShort(0)
            }
        } else {
            while (inputBuffer.remaining() >= ch * 4) {
                var L = inputBuffer.float.toDouble()
                var R = if (ch >= 2) inputBuffer.float.toDouble() else L
                for (c in 2 until ch) inputBuffer.float

                L = subsonicHp.processL(L)
                R = subsonicHp.processR(R)

                if (!isAllDspOff) {
                    L *= autoHeadroomAtten
                    R *= autoHeadroomAtten

                    for (i in 0 until 10) {
                        L = eqBands[i].processL(L)
                        R = eqBands[i].processR(R)
                    }

                    L = megaBassClean.processL(L)
                    R = megaBassClean.processR(R)
                    L = megaBassPunch.processL(L)
                    R = megaBassPunch.processR(R)

                    if (clearAudioEnabled) {
                        L = clearAudioDip.processL(L)
                        R = clearAudioDip.processR(R)
                        L = clearAudioVocal.processL(L)
                        R = clearAudioVocal.processR(R)
                        val mid = (L + R) * 0.5
                        val side = (L - R) * 0.5 * 1.24
                        L = mid + side
                        R = mid - side
                    }

                    if (vinylEnabled) {
                        L = vinylWarmBody.processL(L)
                        R = vinylWarmBody.processR(R)
                        L = vinylSweetMid.processL(L)
                        R = vinylSweetMid.processR(R)
                        L += 0.045 * (L * L) * (if (L >= 0.0) 1.0 else -1.0)
                        R += 0.045 * (R * R) * (if (R >= 0.0) 1.0 else -1.0)
                    }

                    if (dseeEnabled) {
                        L = dseePresence.processL(L)
                        R = dseePresence.processR(R)
                        L = dseeAirShelf.processL(L)
                        R = dseeAirShelf.processR(R)
                    }
                }

                L = L * leftPan * phase
                R = R * rightPan * phase

                if (abs(L) > 0.92) L = (if (L > 0) 1.0 else -1.0) * (0.92 + 0.08 * tanh((abs(L) - 0.92) / 0.08))
                if (abs(R) > 0.92) R = (if (R > 0) 1.0 else -1.0) * (0.92 + 0.08 * tanh((abs(R) - 0.92) / 0.08))

                outBuffer.putFloat(L.toFloat())
                if (ch >= 2) outBuffer.putFloat(R.toFloat())
                for (c in 2 until ch) outBuffer.putFloat(0f)
            }
        }
        outBuffer.flip()
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
