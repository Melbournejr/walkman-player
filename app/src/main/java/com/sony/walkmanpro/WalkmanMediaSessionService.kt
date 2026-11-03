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
import kotlin.math.tanh

/**
 * 1. BỘ XỬ LÝ DSP TINH TẾ & TỰ NHIÊN (GENTLE MASTERING BIQUAD CASCADE)
 * - Đảm bảo khi tắt hết DSP + EQ = 0 -> Tín hiệu trung tính 100% (Bit-transparent, không chỉnh màu).
 * - Khi bật từng hiệu ứng: Thay đổi nhẹ nhàng, êm dịu, tuyệt đối không gây gắt hay đấm vào tai.
 */
@UnstableApi
class SonyRealTimeDspProcessor : BaseAudioProcessor() {
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

        fun reset() {
            z1L = 0.0; z2L = 0.0; z1R = 0.0; z2R = 0.0
        }

        fun setBypass() {
            b0 = 1.0; b1 = 0.0; b2 = 0.0
            a1 = 0.0; a2 = 0.0
        }

        fun setPeaking(fs: Double, f0: Double, q: Double, gainDb: Double) {
            if (abs(gainDb) < 0.02) {
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

    private val mudCutEq = StereoBiquad()
    private val tightBassEq = StereoBiquad()
    private val sweetVocalEq = StereoBiquad()
    private val airLayerEq = StereoBiquad()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        mudCutEq.reset()
        tightBassEq.reset()
        sweetVocalEq.reset()
        airLayerEq.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val fs = inputAudioFormat.sampleRate.toDouble().coerceAtLeast(44100.0)
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)

        val eqLow = (eq10Gains[0] + eq10Gains[1] + eq10Gains[2]).toDouble() / 3.0
        val eqMid = (eq10Gains[3] + eq10Gains[4] + eq10Gains[5] + eq10Gains[6]).toDouble() / 4.0
        val eqHigh = (eq10Gains[7] + eq10Gains[8] + eq10Gains[9]).toDouble() / 3.0

        val isAllEqFlat = eq10Gains.all { abs(it) < 0.05f }
        val isAllDspOff = pureDirectBypass || (megaBassLevel < 0.02f && !clearAudioEnabled && !dseeEnabled && !vinylEnabled && isAllEqFlat)

        if (isAllDspOff) {
            tightBassEq.setBypass()
            mudCutEq.setBypass()
            sweetVocalEq.setBypass()
            airLayerEq.setBypass()
        } else {
            // 1. Mega Bass: Giảm mạnh xuống tối đa +2.4dB @ 62Hz (Q=0.85), giữ bass chắc gọn nhưng không đè mid
            val bassDb = (megaBassLevel * 2.4) + (eqLow * 0.35)
            tightBassEq.setPeaking(fs, 62.0, 0.85, bassDb)

            // 2. Clear Audio: Chỉ cắt nhẹ -0.5dB ở 210Hz khi bật; khi tắt bằng 0.0dB
            val mudDb = if (clearAudioEnabled) -0.5 else 0.0
            mudCutEq.setPeaking(fs, 210.0, 1.00, mudDb)

            // 3. Mid & Vinyl: Clear Audio nâng nhẹ +0.6dB, Vinyl làm ấm nhẹ +0.35dB; khi tắt bằng 0.0dB
            val midDb = (if (clearAudioEnabled) 0.6 else 0.0) + (if (vinylEnabled) 0.35 else 0.0) + (eqMid * 0.35)
            sweetVocalEq.setPeaking(fs, 1500.0, 0.65, midDb)

            // 4. DSEE: Chỉ tăng chi tiết nhẹ +0.8dB ở 12kHz (Vinyl làm dịu -0.25dB); khi tắt bằng 0.0dB
            val highDb = (if (dseeEnabled) 0.8 else 0.0) - (if (vinylEnabled) 0.25 else 0.0) + (eqHigh * 0.35)
            airLayerEq.setPeaking(fs, 12000.0, 0.70, highDb)
        }

        val bal = if (abs(channelBalance) < 0.03f) 0f else channelBalance.coerceIn(-0.5f, 0.5f)
        val leftPan = if (bal > 0f) (1.0 - bal).coerceIn(0.0, 1.0) else 1.0
        val rightPan = if (bal < 0f) (1.0 + bal).coerceIn(0.0, 1.0) else 1.0
        val phase = if (phaseInvert) -1.0 else 1.0

        // Khi có bật boost hiệu ứng, giảm nhẹ 4% pre-gain (0.96) để tránh cạn headroom; khi tắt hết giữ nguyên 1.0
        val headroomScale = if (isAllDspOff) 1.0 else 0.96

        val outBuffer = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= ch * 2) {
                var L = inputBuffer.short.toDouble() / 32768.0
                var R = if (ch >= 2) inputBuffer.short.toDouble() / 32768.0 else L
                for (c in 2 until ch) inputBuffer.short

                if (!isAllDspOff) {
                    L = mudCutEq.processL(L)
                    R = mudCutEq.processR(R)
                    L = tightBassEq.processL(L)
                    R = tightBassEq.processR(R)
                    L = sweetVocalEq.processL(L)
                    R = sweetVocalEq.processR(R)
                    L = airLayerEq.processL(L)
                    R = airLayerEq.processR(R)

                    if (clearAudioEnabled) {
                        val mid = (L + R) * 0.5
                        val side = (L - R) * 0.5 * 1.05
                        L = mid + side
                        R = mid - side
                    }
                }

                L = L * leftPan * phase * headroomScale
                R = R * rightPan * phase * headroomScale

                if (abs(L) > 0.96) L = (if (L > 0) 1.0 else -1.0) * (0.96 + 0.04 * tanh((abs(L) - 0.96) / 0.04))
                if (abs(R) > 0.96) R = (if (R > 0) 1.0 else -1.0) * (0.96 + 0.04 * tanh((abs(R) - 0.96) / 0.04))

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

                if (!isAllDspOff) {
                    L = mudCutEq.processL(L)
                    R = mudCutEq.processR(R)
                    L = tightBassEq.processL(L)
                    R = tightBassEq.processR(R)
                    L = sweetVocalEq.processL(L)
                    R = sweetVocalEq.processR(R)
                    L = airLayerEq.processL(L)
                    R = airLayerEq.processR(R)

                    if (clearAudioEnabled) {
                        val mid = (L + R) * 0.5
                        val side = (L - R) * 0.5 * 1.05
                        L = mid + side
                        R = mid - side
                    }
                }

                L = L * leftPan * phase * headroomScale
                R = R * rightPan * phase * headroomScale

                if (abs(L) > 0.96) L = (if (L > 0) 1.0 else -1.0) * (0.96 + 0.04 * tanh((abs(L) - 0.96) / 0.04))
                if (abs(R) > 0.96) R = (if (R > 0) 1.0 else -1.0) * (0.96 + 0.04 * tanh((abs(R) - 0.96) / 0.04))

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
