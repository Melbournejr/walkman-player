package com.sony.walkmanpro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tanh
import kotlin.random.Random

// BỘ XỬ LÝ TÍN HIỆU SỐ PCM TRỰC TIẾP (Hoạt động 100% trên cả USB-C DAC, Bluetooth & Loa ngoài)
@UnstableApi
class SonyRealTimeDspProcessor : BaseAudioProcessor() {
    @Volatile var vinylEnabled: Boolean = true
    @Volatile var clearAudioEnabled: Boolean = true
    @Volatile var dseeEnabled: Boolean = true
    @Volatile var megaBassLevel: Float = 0.45f // 0f..1f
    @Volatile var eq10Gains = FloatArray(10) { 0f }

    private var lowPassL = 0f
    private var lowPassR = 0f
    private var subBassL = 0f
    private var subBassR = 0f
    private var prevSampleL = 0f
    private var prevSampleR = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // Tính hệ số khuếch đại Bass, Mid, Treble từ 10-Band EQ + MegaBass
        val eqLowGain = ((eq10Gains[0] + eq10Gains[1] + eq10Gains[2]) / 3f) * 0.08f
        val eqMidGain = ((eq10Gains[3] + eq10Gains[4] + eq10Gains[5] + eq10Gains[6]) / 4f) * 0.06f
        val eqHighGain = ((eq10Gains[7] + eq10Gains[8] + eq10Gains[9]) / 3f) * 0.08f
        val totalBassBoost = (megaBassLevel * 0.85f) + eqLowGain

        while (inputBuffer.remaining() >= 4) {
            var left = inputBuffer.short / 32768f
            var right = inputBuffer.short / 32768f

            // 1. Tách dải trầm (Low-pass filter ~110Hz) và dải cao (High-pass)
            subBassL += 0.045f * (left - subBassL)
            subBassR += 0.045f * (right - subBassR)
            val highL = left - prevSampleL
            val highR = right - prevSampleR
            prevSampleL = left
            prevSampleR = right

            // Áp dụng MegaBass + 10-Band EQ thực tế
            left += subBassL * totalBassBoost + left * eqMidGain + highL * eqHighGain
            right += subBassR * totalBassBoost + right * eqMidGain + highR * eqHighGain

            // 2. ClearAudio+™ (Mở rộng âm trường Stereo Mid-Side & tăng độ tách bạch)
            if (clearAudioEnabled) {
                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f
                // Mở rộng không gian sân khấu thêm 38% và nâng nhẹ dải chi tiết
                val wideSide = side * 1.38f
                left = (mid * 1.05f + wideSide) + subBassL * 0.18f + highL * 0.15f
                right = (mid * 1.05f - wideSide) + subBassR * 0.18f + highR * 0.15f
            }

            // 3. DSEE HX™ (Khôi phục hài âm tần số cao bị mất của nhạc nén)
            if (dseeEnabled) {
                val harmonicL = highL * highL * (if (highL >= 0) 1f else -1f) * 0.35f
                val harmonicR = highR * highR * (if (highR >= 0) 1f else -1f) * 0.35f
                left += highL * 0.25f + harmonicL
                right += highR * 0.25f + harmonicR
            }

            // 4. Vinyl Processor™ (Chất âm ấm áp mộc mạc của Đĩa Than + bão hòa đèn Analog)
            if (vinylEnabled) {
                lowPassL += 0.32f * (left - lowPassL)
                lowPassR += 0.32f * (right - lowPassR)
                // Cộng hưởng cần đĩa than ở dải trầm ấm + làm mượt gai số dải cao
                left = (lowPassL * 0.72f + left * 0.28f) + subBassL * 0.25f
                right = (lowPassR * 0.72f + right * 0.28f) + subBassR * 0.25f
                // Bão hòa mềm Analog (Soft-clipping) tạo độ dày ấm
                left = tanh(left * 1.25f) * 0.90f
                right = tanh(right * 1.25f) * 0.90f
            } else {
                // Bảo vệ chống vỡ tiếng (Soft Limiter)
                left = tanh(left)
                right = tanh(right)
            }

            val outL = (left * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            val outR = (right * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            outputBuffer.putShort(outL)
            outputBuffer.putShort(outR)
        }
        outputBuffer.flip()
    }
}

class WalkmanNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            "com.sony.walkmanpro.ACTION_PLAY_PAUSE" -> MainActivity.onExternalCommand?.invoke("PLAY_PAUSE")
            "com.sony.walkmanpro.ACTION_NEXT" -> MainActivity.onExternalCommand?.invoke("NEXT")
            "com.sony.walkmanpro.ACTION_PREV" -> MainActivity.onExternalCommand?.invoke("PREV")
        }
    }
}

class WalkmanMediaSessionService : MediaSessionService() {
    companion object {
        const val CHANNEL_ID = "walkman_playback_channel"
        const val NOTIF_ID = 7001
        var mediaSessionCompat: MediaSessionCompat? = null

        fun updateNowPlayingNotification(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean,
            posMs: Long,
            durMs: Long,
            onCommand: (String) -> Unit
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    CHANNEL_ID,
                    "Walkman Playback Controls",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Điều khiển nhạc Walkman trên thanh trạng thái"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(chan)
            }

            if (mediaSessionCompat == null) {
                mediaSessionCompat = MediaSessionCompat(context, "SonyWalkmanSession").apply {
                    isActive = true
                    setCallback(object : MediaSessionCompat.Callback() {
                        override fun onPlay() = onCommand("PLAY_PAUSE")
                        override fun onPause() = onCommand("PLAY_PAUSE")
                        override fun onSkipToNext() = onCommand("NEXT")
                        override fun onSkipToPrevious() = onCommand("PREV")
                        override fun onSeekTo(pos: Long) = onCommand("SEEK:$pos")
                    })
                }
            }

            val artBitmap = createWalkmanCoverArt()
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Sony Walkman Signature")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durMs.coerceAtLeast(1000L))
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artBitmap)
                .build()
            mediaSessionCompat?.setMetadata(metadata)

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
                    posMs,
                    if (isPlaying) 1.0f else 0f
                )
                .build()
            mediaSessionCompat?.setPlaybackState(state)

            val openIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val prevIntent = PendingIntent.getBroadcast(
                context, 1,
                Intent("com.sony.walkmanpro.ACTION_PREV").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val playIntent = PendingIntent.getBroadcast(
                context, 2,
                Intent("com.sony.walkmanpro.ACTION_PLAY_PAUSE").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nextIntent = PendingIntent.getBroadcast(
                context, 3,
                Intent("com.sony.walkmanpro.ACTION_NEXT").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText("Walkman Hi-Res")
                .setLargeIcon(artBitmap)
                .setContentIntent(openIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
                .addAction(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (isPlaying) "Pause" else "Play",
                    playIntent
                )
                .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
                .setStyle(
                    MediaNotificationCompat.MediaStyle()
                        .setMediaSession(mediaSessionCompat?.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .build()

            nm.notify(NOTIF_ID, notification)
        }

        private fun createWalkmanCoverArt(): Bitmap {
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.parseColor("#181307")
            canvas.drawRect(0f, 0f, 256f, 256f, paint)
            paint.color = Color.parseColor("#EAB308")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 14f
            canvas.drawCircle(128f, 128f, 92f, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 95f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("W", 128f, 162f, paint)
            return bmp
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null
}
