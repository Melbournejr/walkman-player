package com.sony.walkmanpro.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.sony.walkmanpro.AudioTrack
import com.sony.walkmanpro.FiioDirectDsdEngine
import com.sony.walkmanpro.PlayMode
import com.sony.walkmanpro.PlaylistStorage
import com.sony.walkmanpro.audio.DspAudioSink
import com.sony.walkmanpro.audio.dsp.DspSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

@UnstableApi
internal class PlaybackController(
    private val context: Context,
    private val onChanged: () -> Unit,
    private val onStartPlayback: () -> Boolean,
    private val onError: (String) -> Unit
) {
    data class State(
        val playlist: List<AudioTrack>,
        val index: Int,
        val mode: PlayMode,
        val wantsPlayback: Boolean = false,
        val isPlaying: Boolean = false,
        val position: Int = 0,
        val duration: Int = 0,
        val sleepRemaining: Int = 0
    ) {
        val track get() = playlist.getOrNull(index)
    }

    private val preferences = context.getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
    private var pendingPreferences = preferences.edit()
    private val handler = Handler(Looper.getMainLooper())
    private val flushPreferences = Runnable { writeSettings() }
    @Volatile private var dsp = DspSettings(
        direct = preferences.getBoolean("dsp_pure_dsd_mode", false),
        bass = preferences.getFloat("dsp_bass", 0.25f).finite(0.25f).coerceIn(0f, 1f),
        vinyl = preferences.getBoolean("dsp_vinyl", true),
        clarity = preferences.getBoolean("dsp_clear_audio", true),
        treble = preferences.getBoolean("dsp_dsee", true),
        balance = preferences.getFloat("dsp_balance", 0f).finite(0f).coerceIn(-0.5f, 0.5f),
        invert = preferences.getBoolean("dsp_phase_invert", false),
        eq = List(10) { preferences.getFloat("dsp_eq_$it", 0f).finite(0f).coerceIn(-10f, 10f) }
    )
    private val initialList = PlaylistStorage.loadPlaylist(context)
    private val initialSelection = PlaylistStorage.restoreSelection(context, initialList)
    private val initialIndex = initialSelection.first
    private val mutableState = MutableStateFlow(State(initialList, initialIndex, PlaylistStorage.loadPlayMode(context),
        position = initialSelection.second,
        duration = initialList.getOrNull(initialIndex)?.duration?.toPlaybackMs() ?: 0))
    val state = mutableState.asStateFlow()
    private var selectedIndex = initialIndex
    private var playlist = initialList
    private var mode = mutableState.value.mode
    private var resumePosition = initialSelection.second
    private var trackLoaded = false
    private var usingDsd = false
    private var dsdWantsPlayback = false
    private var resumeOnFocusGain = false
    private var dsdFocusHeld = false
    private var released = false
    private var lastStoredUri: String? = null
    private var lastStoredPosition = -1
    private var lastStoredIndex = -1
    private var lastStoredAt = 0L
    private var sleepUntil = 0L
    private var fadeFrom = 0L
    private var masterVolume = preferences.getFloat("dsp_master_vol", 0.75f).finite(0.75f).coerceIn(0f, 1f)
    private var fadeEnabled = preferences.getBoolean("dsp_crossfade", true)
    private var tapeSpeed = preferences.getFloat("dsp_tape_speed", 1f).finite(1f).coerceIn(0.75f, 1.30f)

    private val dsdEngine = FiioDirectDsdEngine(context, { dsp }) { position, error ->
        if (!usingDsd || released) return@FiioDirectDsdEngine
        releaseDsdFocus()
        val intended = dsdWantsPlayback
        resumePosition = position
        dsdWantsPlayback = false
        trackLoaded = false
        if (error == null && intended) advance(1, automatic = true)
        else {
            if (error != null) onError(error)
            update(persist = true)
        }
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ focus ->
            if (usingDsd && trackLoaded) {
                when (focus) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (resumeOnFocusGain && dsdWantsPlayback) dsdEngine.setPlaying(true)
                        resumeOnFocusGain = false
                        update()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        resumeOnFocusGain = dsdWantsPlayback
                        dsdEngine.setPlaying(false)
                        update(persist = true)
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                }
            }
        }, handler).build()

    @Volatile private var sinkFormat: Format? = null
    val pcmFormat get() = if (trackLoaded && !usingDsd) sinkFormat else null
    val pcmSource get() = if (trackLoaded && !usingDsd) player.audioFormat else null
    val dsdPlaying get() = usingDsd && isPlaying()
    val dsdOutput get() = if (usingDsd) dsdEngine.output else null
    val dsdRoute get(): AudioDeviceInfo? = if (usingDsd) dsdEngine.routedDevice else null

    private val renderer = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean): AudioSink {
            val sink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .setEnableAudioTrackPlaybackParams(true)
                .build()
            return DspAudioSink(sink, { dsp }) { sinkFormat = it }
        }
    }
    private val player: ExoPlayer = ExoPlayer.Builder(context, renderer)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build().apply {
            setPlaybackSpeed(this)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (released || usingDsd || !trackLoaded) return
                    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                        player.playbackState == Player.STATE_ENDED) {
                        if (player.playWhenReady) advance(1, automatic = true)
                        else update(persist = true)
                    } else if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                        update(persist = !player.playWhenReady)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (released || usingDsd || !trackLoaded) return
                    resumePosition = position()
                    trackLoaded = false
                    this@apply.playWhenReady = false
                    onError("Không thể phát tệp (${error.errorCodeName}).")
                    update(persist = true)
                }
            })
        }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (sleepUntil != 0L && now >= sleepUntil) {
                sleepUntil = 0
                pause()
                applyVolume()
            } else {
                if (fadeFrom > 0 || sleepUntil > 0) applyVolume()
                update(persist = isPlaying() && now - lastStoredAt >= 15_000L, notify = false)
            }
        }
    }

    fun select(index: Int, fromStart: Boolean = false) {
        val track = playlist.getOrNull(index) ?: return
        persist()
        val start = if (!fromStart && track.duration > 600_000L) PlaylistStorage.getSavedTrackPosition(context, track)
            else 0
        start(index, start)
    }

    fun play() {
        if (wantsPlayback()) return
        if (!trackLoaded) {
            start(selectedIndex, resumePosition)
        } else {
            if (!onStartPlayback()) return
            if (usingDsd) {
                if (requestDsdFocus()) {
                    dsdWantsPlayback = true
                    dsdEngine.setPlaying(true)
                }
            } else player.play()
            update()
        }
    }

    fun pause() {
        if (usingDsd) {
            dsdWantsPlayback = false
            dsdEngine.setPlaying(false)
            releaseDsdFocus()
        } else if (trackLoaded) {
            player.pause()
        }
        fadeFrom = 0
        applyVolume()
        update(persist = true)
    }

    fun toggle() {
        if (wantsPlayback()) pause() else play()
    }

    fun seek(positionMs: Int) {
        val target = positionMs.coerceIn(0, duration().takeIf { it > 0 } ?: Int.MAX_VALUE)
        resumePosition = target
        if (trackLoaded) {
            if (usingDsd) dsdEngine.seekTo(target) else player.seekTo(target.toLong())
        }
        update(persist = true)
    }

    fun advance(step: Int, automatic: Boolean = false) {
        if (playlist.isEmpty()) return
        val next = nextTrackIndex(selectedIndex, playlist.size, mode, step, automatic)
        select(next, fromStart = automatic)
    }

    fun changeMode(value: PlayMode) {
        mode = value
        PlaylistStorage.savePlayMode(context, value)
        update()
    }

    fun replacePlaylist(tracks: List<AudioTrack>) {
        val oldTrack = playlist.getOrNull(selectedIndex)
        val newIndex = tracks.indexOfFirst { it.uri == oldTrack?.uri }
        if (newIndex < 0) {
            resetTrack()
            resumePosition = 0
        }
        playlist = tracks
        selectedIndex = newIndex.coerceAtLeast(0)
        update(persist = true)
    }

    fun setSleepMinutes(minutes: Int) {
        sleepUntil = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0
        applyVolume()
        update()
    }

    fun setVolume(value: Float) {
        masterVolume = value.finite(masterVolume).coerceIn(0f, 1f)
        applyVolume()
        save { putFloat("dsp_master_vol", masterVolume) }
    }

    fun setDirect(value: Boolean) {
        dsp = dsp.copy(direct = value)
        if (value) fadeFrom = 0L
        setPlaybackSpeed(player)
        applyVolume()
        save { putBoolean("dsp_pure_dsd_mode", value) }
    }

    fun setBass(value: Float) {
        dsp = dsp.copy(bass = value.finite(0f).coerceIn(0f, 1f))
        save { putFloat("dsp_bass", dsp.bass) }
    }

    fun setVinyl(value: Boolean) {
        dsp = dsp.copy(vinyl = value)
        save { putBoolean("dsp_vinyl", value) }
    }

    fun setClarity(value: Boolean) {
        dsp = dsp.copy(clarity = value)
        save { putBoolean("dsp_clear_audio", value) }
    }

    fun setTreble(value: Boolean) {
        dsp = dsp.copy(treble = value)
        save { putBoolean("dsp_dsee", value) }
    }

    fun setBalance(value: Float) {
        dsp = dsp.copy(balance = value.finite(0f).coerceIn(-0.5f, 0.5f).takeIf { abs(it) >= 0.03f } ?: 0f)
        save { putFloat("dsp_balance", dsp.balance) }
    }

    fun setInvert(value: Boolean) {
        dsp = dsp.copy(invert = value)
        save { putBoolean("dsp_phase_invert", value) }
    }

    fun setEq(index: Int, value: Float) {
        val bands = dsp.eq.toMutableList()
        bands[index] = value.finite(0f).coerceIn(-10f, 10f)
        dsp = dsp.copy(eq = bands.toList())
        save { putFloat("dsp_eq_$index", bands[index]) }
    }

    fun setFade(value: Boolean) {
        fadeEnabled = value
        if (!value) {
            fadeFrom = 0
            applyVolume()
        }
        save { putBoolean("dsp_crossfade", value) }
    }

    fun setSpeed(value: Float) {
        tapeSpeed = value.finite(1f).coerceIn(0.75f, 1.3f)
        setPlaybackSpeed(player)
        save { putFloat("dsp_tape_speed", tapeSpeed) }
    }

    fun persist() {
        val track = playlist.getOrNull(selectedIndex) ?: return
        val position = position()
        val uri = track.uri.toString()
        if (uri != lastStoredUri || position != lastStoredPosition || selectedIndex != lastStoredIndex) {
            PlaylistStorage.saveTrackPosition(context, track, position, selectedIndex)
            lastStoredUri = uri
            lastStoredPosition = position
            lastStoredIndex = selectedIndex
        }
        lastStoredAt = SystemClock.elapsedRealtime()
    }

    fun onBecomingNoisy() {
        if (usingDsd && dsdWantsPlayback) pause()
    }

    fun release() {
        persist()
        released = true
        handler.removeCallbacksAndMessages(null)
        writeSettings()
        releaseDsdFocus()
        dsdEngine.release()
        player.release()
    }

    private fun start(index: Int, position: Int) {
        val track = playlist.getOrNull(index) ?: return
        if (!onStartPlayback()) return
        resetTrack()
        selectedIndex = index
        resumePosition = position.coerceAtLeast(0)
        usingDsd = track.isDsd()
        trackLoaded = true
        applyVolume()
        if (usingDsd) {
            dsdWantsPlayback = requestDsdFocus()
            if (dsdWantsPlayback) dsdEngine.start(track.uri, resumePosition, play = true)
            else trackLoaded = false
        } else {
            fadeFrom = if (fadeEnabled && !dsp.direct) SystemClock.elapsedRealtime() else 0
            applyVolume()
            player.setMediaItem(MediaItem.fromUri(track.uri), resumePosition.toLong())
            player.prepare()
            player.play()
        }
        update(persist = true)
    }

    private fun resetTrack() {
        trackLoaded = false
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        dsdWantsPlayback = false
        dsdEngine.stop()
        releaseDsdFocus()
        fadeFrom = 0
        sinkFormat = null
    }

    private fun applyVolume() {
        val now = SystemClock.elapsedRealtime()
        val sleepGain = if (sleepUntil > 0) ((sleepUntil - now) / 60_000f).coerceIn(0f, 1f) else 1f
        val fade = if (fadeFrom > 0) ((now - fadeFrom) / 480f).coerceIn(0f, 1f) else 1f
        if (fade >= 1f) fadeFrom = 0
        player.volume = masterVolume * sleepGain * fade
        dsdEngine.volume = masterVolume * sleepGain
    }

    private fun setPlaybackSpeed(target: ExoPlayer) {
        val rate = if (dsp.direct) 1f else tapeSpeed
        target.playbackParameters = PlaybackParameters(rate, rate)
    }

    private fun requestDsdFocus(): Boolean {
        if (dsdFocusHeld) return true
        dsdFocusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!dsdFocusHeld) onError("Thiết bị đang dùng âm thanh ở ứng dụng khác.")
        return dsdFocusHeld
    }

    private fun releaseDsdFocus() {
        resumeOnFocusGain = false
        if (dsdFocusHeld) {
            dsdFocusHeld = false
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private fun isPlaying(): Boolean = trackLoaded && if (usingDsd) dsdEngine.isPlaying else player.isPlaying
    private fun wantsPlayback(): Boolean = trackLoaded && if (usingDsd) dsdWantsPlayback else player.playWhenReady
    private fun position(): Int = if (!trackLoaded) resumePosition
        else if (usingDsd) dsdEngine.positionMs else player.currentPosition.toPlaybackMs()
    private fun duration(): Int = (if (!trackLoaded) 0 else if (usingDsd) dsdEngine.durationMs else player.duration.toPlaybackMs())
        .takeIf { it > 0 } ?: playlist.getOrNull(selectedIndex)?.duration?.toPlaybackMs() ?: 0

    private fun update(persist: Boolean = false, notify: Boolean = true) {
        if (released) return
        if (persist) persist()
        mutableState.value = State(playlist, selectedIndex, mode, wantsPlayback(), isPlaying(), position(), duration(),
            ((sleepUntil - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt())
        handler.removeCallbacks(tick)
        if (wantsPlayback() || sleepUntil > 0) handler.postDelayed(tick, if (fadeFrom > 0) 30L else 500L)
        if (notify) onChanged()
    }

    private fun save(change: android.content.SharedPreferences.Editor.() -> Unit) {
        pendingPreferences.change()
        handler.removeCallbacks(flushPreferences)
        handler.postDelayed(flushPreferences, 400)
    }

    private fun writeSettings() {
        pendingPreferences.apply()
        pendingPreferences = preferences.edit()
    }

    private fun Float.finite(fallback: Float) = if (isFinite()) this else fallback
}

internal fun Long.toPlaybackMs(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

internal fun AudioTrack.isDsd() = formatName.equals("DSF", true) || formatName.equals("DFF", true) ||
    title.endsWith(".dsf", true) || title.endsWith(".dff", true) ||
    uri.lastPathSegment.orEmpty().let { it.endsWith(".dsf", true) || it.endsWith(".dff", true) }

internal fun nextTrackIndex(index: Int, size: Int, mode: PlayMode, step: Int, automatic: Boolean): Int = when {
    size < 2 -> 0
    automatic && mode == PlayMode.REPEAT_ONE -> index
    mode == PlayMode.SHUFFLE && step > 0 -> {
        val other = (0 until size - 1).random()
        if (other >= index) other + 1 else other
    }
    else -> Math.floorMod(index + step, size)
}
