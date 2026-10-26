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

// BỘ XỬ LÝ DSP BẢN 7.0 NGUYÊN BẢN + BỘ CHỐNG RÈ LOA ĐIỆN THOẠI (CLEAN HEADROOM)
@UnstableApi
class SonyRealTimeDspProcessor : BaseAudioProcessor() {
    @Volatile var vinylEnabled: Boolean = true
    @Volatile var clearAudioEnabled: Boolean = true
    @Volatile var dseeEnabled: Boolean = true
    @Volatile var megaBassLevel: Float = 0.45f
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

        // Hạ nhẹ Pre-gain đầu vào (0.78f) để khi cộng dồn Bass + EQ không bao giờ làm rè loa ngoài điện thoại
        val preGain = 0.78f
        val eqLowGain = ((eq10Gains[0] + eq10Gains[1] + eq10Gains[2]) / 3f) * 0.05f
        val eqMidGain = ((eq10Gains[3] + eq10Gains[4] + eq10Gains[5] + eq10Gains[6]) / 4f) * 0.04f
        val eqHighGain = ((eq10Gains[7] + eq10Gains[8] + eq10Gains[9]) / 3f) * 0.05f
        val totalBassBoost = (megaBassLevel * 0.55f) + eqLowGain

        while (inputBuffer.remaining() >= 4) {
            var left = (inputBuffer.short / 32768f) * preGain
            var right = (inputBuffer.short / 32768f) * preGain

            subBassL += 0.045f * (left - subBassL)
            subBassR += 0.045f * (right - subBassR)
            val highL = left - prevSampleL
            val highR = right - prevSampleR
            prevSampleL = left
            prevSampleR = right

            left += subBassL * totalBassBoost + left * eqMidGain + highL * eqHighGain
            right += subBassR * totalBassBoost + right * eqMidGain + highR * eqHighGain

            if (clearAudioEnabled) {
                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f
                val wideSide = side * 1.28f
                left = (mid + wideSide) + subBassL * 0.10f + highL * 0.08f
                right = (mid - wideSide) + subBassR * 0.10f + highR * 0.08f
            }

            if (dseeEnabled) {
                left += highL * 0.15f
                right += highR * 0.15f
            }

            if (vinylEnabled) {
                lowPassL += 0.35f * (left - lowPassL)
                lowPassR += 0.35f * (right - lowPassR)
                left = (lowPassL * 0.65f + left * 0.35f) + subBassL * 0.12f
                right = (lowPassR * 0.65f + right * 0.35f) + subBassR * 0.12f
                left = tanh(left * 1.05f) * 0.92f
                right = tanh(right * 1.05f) * 0.92f
            } else {
                left = tanh(left) * 0.95f
                right = tanh(right) * 0.95f
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
