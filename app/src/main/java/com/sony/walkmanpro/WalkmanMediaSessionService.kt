package com.sony.walkmanpro

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.util.UnstableApi
import com.sony.walkmanpro.playback.PlaybackController

@UnstableApi
class WalkmanMediaSessionService : Service() {
    internal inner class PlaybackBinder : Binder() {
        val playback get() = controller
    }

    private lateinit var controller: PlaybackController
    private lateinit var session: MediaSessionCompat
    private lateinit var dsdWakeLock: PowerManager.WakeLock
    private var started = false
    private var foreground = false
    private val binder = PlaybackBinder()
    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) controller.onBecomingNoisy()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Walkman Playback", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        dsdWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walkman:dsd")
            .apply { setReferenceCounted(false) }
        session = MediaSessionCompat(this, "WalkmanPlayback").apply {
            setSessionActivity(openPlayer())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = this@WalkmanMediaSessionService.controller.play()
                override fun onPause() = this@WalkmanMediaSessionService.controller.pause()
                override fun onSkipToNext() = this@WalkmanMediaSessionService.controller.advance(1)
                override fun onSkipToPrevious() = this@WalkmanMediaSessionService.controller.advance(-1)
                override fun onSeekTo(pos: Long) = this@WalkmanMediaSessionService.controller.seek(pos.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                override fun onStop() = this@WalkmanMediaSessionService.controller.pause()
            })
        }
        controller = PlaybackController(applicationContext, ::publish, ::startPlayback) { message ->
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
        ContextCompat.registerReceiver(this, disconnectReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        when (intent?.action) {
            PLAY -> controller.play()
            PAUSE -> controller.pause()
            NEXT -> controller.advance(1)
            PREVIOUS -> controller.advance(-1)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.persist()
        if (!controller.state.value.wantsPlayback) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        unregisterReceiver(disconnectReceiver)
        if (dsdWakeLock.isHeld) dsdWakeLock.release()
        controller.release()
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        super.onDestroy()
    }

    private fun startPlayback(): Boolean {
        return try {
            if (!started) {
                startService(Intent(this, WalkmanMediaSessionService::class.java))
                started = true
            }
            if (!foreground) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else 0
                ServiceCompat.startForeground(this, NOTIFICATION, notification(controller.state.value), serviceType)
                foreground = true
            }
            true
        } catch (_: IllegalStateException) {
            Toast.makeText(this, "Mở Walkman để tiếp tục phát nhạc.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    @SuppressLint("Wakelock", "WakelockTimeout") // Released on pause, focus loss, engine switch, completion and service destruction.
    private fun publish() {
        if (controller.dsdPlaying && !dsdWakeLock.isHeld) dsdWakeLock.acquire()
        if (!controller.dsdPlaying && dsdWakeLock.isHeld) dsdWakeLock.release()
        val state = controller.state.value
        val track = state.track
        if (track == null) {
            session.isActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
            if (started) {
                started = false
                stopSelf()
            }
            return
        }
        if (!started) return

        session.isActive = true
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.duration.toLong())
            .build())
        val sessionState = when {
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            state.wantsPlayback -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO)
            .setState(sessionState, state.position.toLong(), if (state.isPlaying) 1f else 0f)
            .build())

        if (foreground && !state.wantsPlayback) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
    }

    private fun notification(state: PlaybackController.State): Notification {
        val toggle = if (state.wantsPlayback) PAUSE else PLAY
        val toggleIcon = if (state.wantsPlayback) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.track?.title ?: getString(R.string.app_name))
            .setContentText(state.track?.artist)
            .setContentIntent(openPlayer())
            .setOngoing(state.wantsPlayback)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Prev", transport(PREVIOUS, 1))
            .addAction(toggleIcon, if (state.wantsPlayback) "Pause" else "Play", transport(toggle, 2))
            .addAction(android.R.drawable.ic_media_next, "Next", transport(NEXT, 3))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun openPlayer() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun transport(action: String, id: Int) = PendingIntent.getService(
        this, id, Intent(this, WalkmanMediaSessionService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val CHANNEL = "walkman_signature_playback_channel"
        const val NOTIFICATION = 1001
        const val PLAY = "com.sony.walkmanpro.PLAY"
        const val PAUSE = "com.sony.walkmanpro.PAUSE"
        const val NEXT = "com.sony.walkmanpro.NEXT"
        const val PREVIOUS = "com.sony.walkmanpro.PREVIOUS"
    }
}
