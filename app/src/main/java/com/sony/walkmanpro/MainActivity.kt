package com.sony.walkmanpro

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val DapChassisBlack = Color(0xFF07080B)
private val DapPanelDark = Color(0xFF13161E)
private val DapGlassScreen = Color(0xFF030406)
private val PanelSurface = Color(0xFF171A22)
private val ElevatedMetallic = Color(0xFF222733)
private val BezelBorder = Color(0xFF2D3342)
private val ChampagneGold = Color(0xFFC8B588)
private val ChampagneGoldDim = Color(0xFF827350)
private val EchoCrimsonRed = Color(0xFFE11D48)
private val EchoCyanSpec = Color(0xFF38BDF8)
private val ColdSilver = Color(0xFFA6B0BE)
private val TextPure = Color(0xFFF1F5F9)
private val TextMuted = Color(0xFF64748B)
private val LedPlayGreen = Color(0xFF10B981)

data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri,
    val folderInfo: String,
    val sizeBytes: Long = 0L,
    var formatName: String = "AUDIO"
)

enum class PlayMode(val label: String, val badgeCode: String) {
    SEQUENTIAL("Sequential", "NOR"),
    REPEAT_ONE("Repeat One", "RPT"),
    SHUFFLE("Shuffle", "SHF")
}

enum class AppScreen {
    NOW_PLAYING,
    LIBRARY,
    AUDIO_LAB
}

data class AudioOutputState(
    val deviceType: String = "Speaker",
    val isBluetooth: Boolean = false,
    val deviceName: String = "Internal Speaker",
    val codecEstimate: String = "PCM",
    val batteryPercent: Int = 100
)

@UnstableApi
class MainActivity : ComponentActivity() {
    companion object {
        var onExternalCommand: ((String) -> Unit)? = null
        private var sharedExoPlayer: ExoPlayer? = null
        private var sharedDsdEngine: FiioDirectDsdEngine? = null
        private val sharedDspProcessor = SonyRealTimeDspProcessor()
        @Volatile private var isDsdModeActive: Boolean = false
    }

    private var onTrackEndedCallback: (() -> Unit)? = null
    private var masterVolume: Float = 0.75f
    private var sleepFadeMultiplier: Float = 1.0f
    private var crossfadeEnabled: Boolean = true
    private var tapeSpeed: Float = 1.0f
    private var pureDsdEnabled: Boolean = false

    private val backgroundHandler = Handler(Looper.getMainLooper())
    private var currentTrackRef: AudioTrack? = null
    private var currentIndexRef: Int = 0

    private val backgroundKeepAliveRunnable = object : Runnable {
        override fun run() {
            try {
                if (getActiveIsPlaying()) {
                    val pos = getActivePosition()
                    val dur = getActiveDuration().toLong().coerceAtLeast(1000L)
                    currentTrackRef?.let { t ->
                        PlaylistStorage.saveTrackPosition(applicationContext, t.title, pos, currentIndexRef)
                        WalkmanMediaSessionService.updateNowPlayingNotification(
                            context = applicationContext,
                            title = t.title,
                            artist = t.artist,
                            isPlaying = true,
                            posMs = pos.toLong(),
                            durMs = dur
                        ) { cmd ->
                            onExternalCommand?.invoke(cmd)
                        }
                    }
                }
            } catch (_: Throwable) {}
            backgroundHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startPersistentForegroundService()
        requestIgnoreBatteryOptimizationsIfNeeded()
        loadSavedDspSettings()
        initAudioEngines()
        backgroundHandler.post(backgroundKeepAliveRunnable)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = DapChassisBlack,
                    surface = DapPanelDark,
                    primary = ChampagneGold,
                    onPrimary = DapChassisBlack,
                    onBackground = TextPure,
                    onSurface = TextPure
                )
            ) {
                SnowskyEchoDapApp(
                    onAutoScanAll = { scanAllMusicSources(this@MainActivity) },
                    onParseManualUris = { uris -> parseManualFiles(this@MainActivity, uris) },
                    onParseFolderUri = { treeUri -> parseFolderTree(this@MainActivity, treeUri) },
                    onPlayTrack = { track, idx, startMs, onEnd ->
                        currentTrackRef = track
                        currentIndexRef = idx
                        onTrackEndedCallback = onEnd
                        playTrackUnified(track, startMs)
                    },
                    onTogglePlay = { togglePlayUnified() },
                    onStopPlayback = { stopUnified() },
                    onSeekTo = { ms -> seekUnified(ms) },
                    onSetMasterVolume = { vol -> updateMasterVolume(vol) },
                    onSetSleepMultiplier = { mult ->
                        sleepFadeMultiplier = mult.coerceIn(0f, 1f)
                        applyEffectiveVolume()
                    },
                    onSetPureDsdMode = { enabled -> updatePureDsdMode(enabled) },
                    onSetBass = { level -> updateMegaBass(level) },
                    onSetVinyl = { enabled -> updateVinylEffect(enabled) },
                    onSetClearAudio = { enabled -> updateClearAudio(enabled) },
                    onSetDsee = { enabled -> updateDsee(enabled) },
                    onSetChannelBalance = { bal -> updateChannelBalance(bal) },
                    onSetPhaseInvert = { inv -> updatePhaseInvert(inv) },
                    onSetCrossfade = { cf -> updateCrossfade(cf) },
                    onSetTapeSpeed = { spd -> updateTapeSpeed(spd) },
                    onSetKeepScreenOn = { keepOn -> setDeskScreenKeepOn(keepOn) },
                    onToggleOrientation = { toggleScreenOrientation() },
                    onSetEqBand10 = { band, gain -> updateEqBand10(band, gain) },
                    getIsPlaying = { getActiveIsPlaying() },
                    getPosition = { getActivePosition() },
                    getDuration = { getActiveDuration() },
                    getAudioOutputState = { detectAudioOutputState(this@MainActivity) },
                    getTrackSpecs = { track -> inspectTrackSpecsSafe(this@MainActivity, track) }
                )
            }
        }
    }

    private fun toggleScreenOrientation() {
        val currentOrient = resources.configuration.orientation
        requestedOrientation = if (currentOrient == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        backgroundHandler.postDelayed({
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }, 4000L)
    }

    private fun getActiveIsPlaying(): Boolean {
        return if (isDsdModeActive) {
            sharedDsdEngine?.isPlaying == true
        } else {
            sharedExoPlayer?.isPlaying == true
        }
    }

    private fun getActivePosition(): Int {
        return if (isDsdModeActive) {
            (sharedDsdEngine?.currentPositionMs ?: 0).coerceAtLeast(0)
        } else {
            (sharedExoPlayer?.currentPosition ?: 0L).toInt().coerceAtLeast(0)
        }
    }

    private fun getActiveDuration(): Int {
        return if (isDsdModeActive) {
            (sharedDsdEngine?.durationMs ?: 180000).coerceAtLeast(1)
        } else {
            val d = sharedExoPlayer?.duration ?: 1L
            if (d <= 0L || d == C.TIME_UNSET) 1 else d.toInt()
        }
    }

    private fun startPersistentForegroundService() {
        try {
            val serviceIntent = Intent(applicationContext, WalkmanMediaSessionService::class.java)
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        } catch (_: Throwable) {}
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun setDeskScreenKeepOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun initAudioEngines() {
        if (sharedDsdEngine == null) {
            sharedDsdEngine = FiioDirectDsdEngine(applicationContext, sharedDspProcessor) {
                onTrackEndedCallback?.invoke()
            }.apply {
                pureDsdMode = pureDsdEnabled
            }
        }

        if (sharedExoPlayer != null) return

        val renderersFactory = object : DefaultRenderersFactory(applicationContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(sharedDspProcessor))
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        sharedExoPlayer = ExoPlayer.Builder(applicationContext, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
                volume = masterVolume
                playbackParameters = PlaybackParameters(tapeSpeed, tapeSpeed)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (!isDsdModeActive && playbackState == Player.STATE_ENDED) {
                            onTrackEndedCallback?.invoke()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(
                            applicationContext,
                            "Không thể phát file này (${error.errorCodeName})",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            }
    }

    private fun loadSavedDspSettings() {
        val prefs = getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
        masterVolume = prefs.getFloat("dsp_master_vol", 0.75f)
        pureDsdEnabled = prefs.getBoolean("dsp_pure_dsd_mode", false)
        sharedDsdEngine?.pureDsdMode = pureDsdEnabled
        sharedDspProcessor.megaBassLevel = prefs.getFloat("dsp_bass", 0.45f)
        sharedDspProcessor.vinylEnabled = prefs.getBoolean("dsp_vinyl", true)
        sharedDspProcessor.clearAudioEnabled = prefs.getBoolean("dsp_clear_audio", true)
        sharedDspProcessor.dseeEnabled = prefs.getBoolean("dsp_dsee", true)

        val rawSavedBal = prefs.getFloat("dsp_balance", 0f)
        val cleanBal = if (abs(rawSavedBal) < 0.04f) 0f else rawSavedBal.coerceIn(-0.5f, 0.5f)
        sharedDspProcessor.channelBalance = cleanBal
        if (cleanBal == 0f && rawSavedBal != 0f) {
            prefs.edit().putFloat("dsp_balance", 0f).apply()
        }

        sharedDspProcessor.phaseInvert = prefs.getBoolean("dsp_phase_invert", false)
        crossfadeEnabled = prefs.getBoolean("dsp_crossfade", true)
        tapeSpeed = prefs.getFloat("dsp_tape_speed", 1.0f)
        for (i in 0 until 10) {
            sharedDspProcessor.eq10Gains[i] = prefs.getFloat("dsp_eq_$i", 0f)
        }
    }

    private fun applyEffectiveVolume() {
        val eff = (masterVolume * sleepFadeMultiplier).coerceIn(0f, 1f)
        sharedExoPlayer?.volume = eff
        sharedDsdEngine?.masterVolume = eff
    }

    private fun isDsdTrack(track: AudioTrack): Boolean {
        val fmt = track.formatName.lowercase()
        val title = track.title.lowercase()
        val uriStr = track.uri.toString().lowercase()
        if (fmt == "dff" || fmt == "dsf" || title.endsWith(".dff") || title.endsWith(".dsf") ||
            uriStr.endsWith(".dff") || uriStr.endsWith(".dsf")
        ) {
            return true
        }
        return try {
            contentResolver.openInputStream(track.uri)?.use { ins ->
                val b = ByteArray(4)
                if (ins.read(b) == 4) {
                    val m = String(b, 0, 4, Charsets.ISO_8859_1)
                    m == "FRM8" || m == "DSD "
                } else false
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun playTrackUnified(track: AudioTrack, startMs: Long = 0L) {
        try {
            startPersistentForegroundService()
            if (isDsdTrack(track)) {
                isDsdModeActive = true
                sharedExoPlayer?.pause()
                sharedDsdEngine?.pureDsdMode = pureDsdEnabled
                sharedDsdEngine?.masterVolume = (masterVolume * sleepFadeMultiplier).coerceIn(0f, 1f)
                sharedDsdEngine?.startDsdPlayback(track.uri, startMs.toInt())
            } else {
                isDsdModeActive = false
                sharedDsdEngine?.stop()
                val player = sharedExoPlayer ?: return
                player.setMediaItem(MediaItem.fromUri(track.uri))
                player.prepare()
                if (startMs > 0) player.seekTo(startMs)
                player.playbackParameters = PlaybackParameters(tapeSpeed, tapeSpeed)

                if (crossfadeEnabled) {
                    player.volume = 0.05f
                    player.play()
                    val targetVol = (masterVolume * sleepFadeMultiplier).coerceIn(0f, 1f)
                    Thread {
                        for (step in 1..12) {
                            try {
                                Thread.sleep(90)
                                val v = (targetVol * (step / 12f)).coerceIn(0f, 1f)
                                runOnUiThread { sharedExoPlayer?.volume = v }
                            } catch (_: Throwable) {}
                        }
                    }.start()
                } else {
                    applyEffectiveVolume()
                    player.play()
                }
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Không thể phát: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayUnified(): Boolean {
        return if (isDsdModeActive) {
            sharedDsdEngine?.togglePlayPause() ?: false
        } else {
            val player = sharedExoPlayer ?: return false
            if (player.isPlaying) {
                player.pause()
            } else {
                startPersistentForegroundService()
                applyEffectiveVolume()
                player.play()
            }
            player.isPlaying
        }
    }

    private fun stopUnified() {
        if (isDsdModeActive) {
            sharedDsdEngine?.stop()
        } else {
            sharedExoPlayer?.let {
                it.pause()
                it.seekTo(0)
            }
        }
    }

    private fun seekUnified(ms: Int) {
        if (isDsdModeActive) {
            sharedDsdEngine?.seekTo(ms)
        } else {
            sharedExoPlayer?.let { player ->
                val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                player.seekTo(ms.toLong().coerceIn(0L, dur))
            }
        }
    }

    private fun updateMasterVolume(vol: Float) {
        masterVolume = vol.coerceIn(0f, 1f)
        applyEffectiveVolume()
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("dsp_master_vol", masterVolume).apply()
    }

    private fun updatePureDsdMode(enabled: Boolean) {
        pureDsdEnabled = enabled
        sharedDsdEngine?.pureDsdMode = enabled
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dsp_pure_dsd_mode", enabled).apply()
    }

    private fun updateMegaBass(level: Float) {
        sharedDspProcessor.megaBassLevel = level.coerceIn(0f, 1f)
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("dsp_bass", sharedDspProcessor.megaBassLevel).apply()
    }

    private fun updateVinylEffect(enabled: Boolean) {
        sharedDspProcessor.vinylEnabled = enabled
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dsp_vinyl", enabled).apply()
    }

    private fun updateClearAudio(enabled: Boolean) {
        sharedDspProcessor.clearAudioEnabled = enabled
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dsp_clear_audio", enabled).apply()
    }

    private fun updateDsee(enabled: Boolean) {
        sharedDspProcessor.dseeEnabled = enabled
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dsp_dsee", enabled).apply()
    }

    private fun updateChannelBalance(bal: Float) {
        val snappedBal = if (abs(bal) < 0.03f) 0f else bal.coerceIn(-0.5f, 0.5f)
        sharedDspProcessor.channelBalance = snappedBal
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("dsp_balance", snappedBal).apply()
    }

    private fun updatePhaseInvert(inv: Boolean) {
        sharedDspProcessor.phaseInvert = inv
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dsp_phase_invert", inv).apply()
    }

    private fun updateCrossfade(cf: Boolean) {
        crossfadeEnabled = cf
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dsp_crossfade", cf).apply()
    }

    private fun updateTapeSpeed(spd: Float) {
        tapeSpeed = spd.coerceIn(0.75f, 1.30f)
        sharedExoPlayer?.playbackParameters = PlaybackParameters(tapeSpeed, tapeSpeed)
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("dsp_tape_speed", tapeSpeed).apply()
    }

    private fun updateEqBand10(band10Index: Int, gainDb: Float) {
        if (band10Index in 0..9) {
            sharedDspProcessor.eq10Gains[band10Index] = gainDb.coerceIn(-10f, 10f)
            getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
                .edit().putFloat("dsp_eq_$band10Index", sharedDspProcessor.eq10Gains[band10Index]).apply()
        }
    }

    private var bluetoothA2dpProxy: android.bluetooth.BluetoothA2dp? = null

    private fun ensureBluetoothA2dpProxy(context: Context) {
        if (bluetoothA2dpProxy != null) return
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bm?.adapter ?: return
            adapter.getProfileProxy(context.applicationContext, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile?) {
                    if (profile == android.bluetooth.BluetoothProfile.A2DP) {
                        bluetoothA2dpProxy = proxy as? android.bluetooth.BluetoothA2dp
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == android.bluetooth.BluetoothProfile.A2DP) {
                        bluetoothA2dpProxy = null
                    }
                }
            }, android.bluetooth.BluetoothProfile.A2DP)
        } catch (_: Throwable) {}
    }

    private fun getPhoneBatteryPercent(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
        } catch (_: Throwable) {
            100
        }
    }

    private fun queryRealBluetoothCodecAndBattery(deviceNameHint: String): Pair<String?, Int> {
        var realCodecName: String? = null
        var batteryPercent = -1
        try {
            ensureBluetoothA2dpProxy(this)
            val proxy = bluetoothA2dpProxy
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val devices = proxy?.connectedDevices
            val activeDevice = if (!devices.isNullOrEmpty()) {
                devices.firstOrNull { it.name?.equals(deviceNameHint, ignoreCase = true) == true } ?: devices[0]
            } else {
                bm?.adapter?.bondedDevices?.firstOrNull {
                    it.name?.equals(deviceNameHint, ignoreCase = true) == true
                }
            }

            if (activeDevice != null) {
                try {
                    val battMethod = activeDevice.javaClass.getMethod("getBatteryLevel")
                    val level = battMethod.invoke(activeDevice) as? Int ?: -1
                    if (level in 0..100) batteryPercent = level
                } catch (_: Throwable) {}

                if (proxy != null) {
                    try {
                        val getCodecStatusMethod = proxy.javaClass.getMethod("getCodecStatus", android.bluetooth.BluetoothDevice::class.java)
                        val codecStatus = getCodecStatusMethod.invoke(proxy, activeDevice)
                        if (codecStatus != null) {
                            val getCodecConfigMethod = codecStatus.javaClass.getMethod("getCodecConfig")
                            val codecConfig = getCodecConfigMethod.invoke(codecStatus)
                            if (codecConfig != null) {
                                val getCodecTypeMethod = codecConfig.javaClass.getMethod("getCodecType")
                                val codecType = getCodecTypeMethod.invoke(codecConfig) as? Int ?: -1
                                realCodecName = when (codecType) {
                                    0 -> "Bluetooth SBC"
                                    1 -> "Bluetooth AAC"
                                    2 -> "Qualcomm® aptX™"
                                    3 -> "Qualcomm® aptX™ HD"
                                    4 -> "Sony LDAC™"
                                    5, 6, 7, 8, 9 -> "Qualcomm® aptX™ Adaptive"
                                    else -> "Qualcomm® aptX™ Adaptive"
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return Pair(realCodecName, batteryPercent)
    }

    private fun detectAudioOutputState(context: Context): AudioOutputState {
        ensureBluetoothA2dpProxy(context)
        val phoneBatt = getPhoneBatteryPercent(context)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (dev in devices) {
                when (dev.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                        val rawName = dev.productName?.toString() ?: "Bluetooth Audio"
                        val (realCodec, btBattery) = queryRealBluetoothCodecAndBattery(rawName)
                        val effectiveBatt = if (btBattery in 0..100) btBattery else phoneBatt
                        val codecLabel = realCodec ?: when {
                            rawName.contains("LDAC", true) || rawName.contains("1000X", true) -> "Sony LDAC™"
                            rawName.contains("Noble", true) || rawName.contains("Falcon", true) || rawName.contains("aptx", true) -> "Qualcomm® aptX™ Adaptive"
                            else -> "Bluetooth A2DP"
                        }
                        return AudioOutputState("Bluetooth", true, rawName, codecLabel, effectiveBatt)
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                        return AudioOutputState("4.4mm / 3.5mm", false, "Balanced / Headphones", "Direct Output", phoneBatt)
                    }
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> {
                        return AudioOutputState("USB-DAC", false, dev.productName?.toString() ?: "USB HiFi DAC", "Bit-Perfect Output", phoneBatt)
                    }
                }
            }
        }
        return AudioOutputState("Speaker", false, "Internal Stereo Speaker", "PCM Output", phoneBatt)
    }

    private fun inspectTrackSpecsSafe(context: Context, track: AudioTrack): Pair<Int, String> {
        if (isDsdTrack(track)) {
            return parseDsdSpecsDirectly(context, track.uri)
        }

        val lowerTitle = track.title.lowercase()
        val lowerFmt = track.formatName.lowercase()
        var bitrate = 1536
        var sampleRate = "192kHz | 24bit"
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, track.uri)
            val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            if (!brStr.isNullOrBlank()) {
                val bps = brStr.toIntOrNull() ?: 1536000
                bitrate = (bps / 1000).coerceIn(64, 18432)
            }
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            sampleRate = when {
                lowerTitle.contains("384k") || mime.contains("x-flac") -> "384kHz | 32bit"
                lowerTitle.endsWith(".flac") || lowerFmt == "flac" -> "192kHz | 24bit"
                lowerTitle.endsWith(".mqa") || lowerFmt == "mqa" -> "352.8kHz | 24bit"
                lowerTitle.endsWith(".wav") -> "192kHz | 24bit"
                else -> "192kHz | 24bit"
            }
        } catch (_: Throwable) {
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
        return Pair(bitrate, sampleRate)
    }

    private fun parseDsdSpecsDirectly(context: Context, uri: Uri): Pair<Int, String> {
        try {
            val engine = sharedDsdEngine ?: FiioDirectDsdEngine(context, sharedDspProcessor) {}
            val info = engine.parseDsdHeaderAccurate(context, uri, 0L)
            if (info.isValid) {
                val kbps = (info.dsdSampleRate * info.channels) / 1000
                val dsdName = when {
                    info.dsdSampleRate >= 11289600 -> "11.2MHz | 1bit"
                    info.dsdSampleRate >= 5644800 -> "5.6MHz | 1bit"
                    else -> "2.8MHz | 1bit"
                }
                return Pair(kbps, dsdName)
            }
        } catch (_: Throwable) {}
        return Pair(11289, "5.6MHz | 1bit")
    }

    private fun isExcluded(text: String, file: String): Boolean {
        val ex = listOf("record", "call@", "call_", "voice", "ghi am", "ghi_am", "cuoc goi", "ringtone", "notification", "alarm")
        val lower = file.lowercase()
        return ex.any { text.contains(it) } || lower.endsWith(".amr") || lower.endsWith(".3gp")
    }

    private fun scanAllMusicSources(context: Context): Pair<List<AudioTrack>, Int> {
        val valid = mutableListOf<AudioTrack>()
        val seen = mutableSetOf<String>()
        var skipped = 0

        val volumes = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            volumes.addAll(MediaStore.getExternalVolumeNames(context).map { MediaStore.Audio.Media.getContentUri(it) })
        }
        volumes.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        for (volumeUri in volumes.distinct()) {
            try {
                context.contentResolver.query(volumeUri, projection, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                    val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else -1

                    while (c.moveToNext()) {
                        if (idCol < 0) continue
                        val id = c.getLong(idCol)
                        val name = if (nameCol >= 0) c.getString(nameCol) ?: "" else ""
                        val title = if (titleCol >= 0) c.getString(titleCol) ?: name.substringBeforeLast(".") else name.substringBeforeLast(".")
                        val artist = if (artistCol >= 0) c.getString(artistCol) ?: "James" else "James"
                        val dur = if (durCol >= 0) c.getLong(durCol) else 0L
                        val sz = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                        val relPath = if (relCol >= 0) c.getString(relCol) ?: "" else ""
                        val folderName = relPath.trimEnd('/').substringAfterLast('/').ifEmpty { "FIIO" }

                        val key = "${title.trim().lowercase()}_$sz"
                        if (seen.contains(key)) continue

                        if (isExcluded("$title $name $relPath".lowercase(), name) || (dur in 1..14999)) {
                            skipped++
                        } else {
                            seen.add(key)
                            val uri = ContentUris.withAppendedId(volumeUri, id)
                            valid.add(AudioTrack(id, title, artist, dur, uri, folderName, sz, name.substringAfterLast(".", "WAV").uppercase()))
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        return Pair(valid, skipped)
    }

    private fun parseManualFiles(context: Context, uris: List<Uri>): List<AudioTrack> {
        val added = mutableListOf<AudioTrack>()
        for (uri in uris) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            var fn = "Track"
            var sz = 0L
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (ni >= 0) fn = c.getString(ni) ?: fn
                        if (si >= 0) sz = c.getLong(si)
                    }
                }
            } catch (_: Throwable) {}
            added.add(AudioTrack(System.currentTimeMillis() + added.size, fn.substringBeforeLast("."), "James", 0L, uri, "FIIO", sz, fn.substringAfterLast(".", "WAV").uppercase()))
        }
        return added
    }

    private fun parseFolderTree(context: Context, treeUri: Uri): Pair<List<AudioTrack>, Int> {
        val added = mutableListOf<AudioTrack>()
        var skipped = 0
        try { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        val rootDir = DocumentFile.fromTreeUri(context, treeUri) ?: return Pair(emptyList(), 0)
        val exts = listOf(".mp3", ".flac", ".wav", ".m4a", ".dsf", ".dff", ".mqa", ".aac", ".ogg")

        fun scanDir(dir: DocumentFile) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) {
                    scanDir(f)
                } else if (f.isFile) {
                    val n = f.name ?: continue
                    val lower = n.lowercase()
                    if (exts.any { lower.endsWith(it) }) {
                        if (isExcluded(lower, n)) {
                            skipped++
                        } else {
                            added.add(AudioTrack(System.currentTimeMillis() + added.size, n.substringBeforeLast("."), "James", 0L, f.uri, dir.name ?: "FIIO", f.length(), n.substringAfterLast(".", "WAV").uppercase()))
                        }
                    }
                }
            }
        }
        try { scanDir(rootDir) } catch (_: Throwable) {}
        return Pair(added, skipped)
    }
}

object PlaylistStorage {
    private const val PREF_NAME = "walkman_v7_storage"
    private const val KEY_TRACKS = "saved_tracks_json"
    private const val KEY_FOLDERS = "saved_folder_uris"
    private const val KEY_LAST_INDEX = "saved_last_index"
    private const val KEY_LAST_POS = "saved_last_pos_ms"
    private const val KEY_FAVORITES = "saved_favorites_set"
    private const val KEY_PLAY_MODE = "saved_play_mode"

    fun savePlaylist(context: Context, tracks: List<AudioTrack>) {
        try {
            val arr = JSONArray()
            tracks.take(2000).forEach { t ->
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("duration", t.duration)
                    put("uri", t.uri.toString())
                    put("folderInfo", t.folderInfo)
                    put("sizeBytes", t.sizeBytes)
                    put("formatName", t.formatName)
                }
                arr.put(obj)
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_TRACKS, arr.toString()).apply()
        } catch (_: Throwable) {}
    }

    fun loadPlaylist(context: Context): List<AudioTrack> {
        val list = mutableListOf<AudioTrack>()
        try {
            val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_TRACKS, null) ?: return emptyList()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AudioTrack(
                        id = obj.optLong("id", i.toLong()),
                        title = obj.optString("title", "Born For Music"),
                        artist = obj.optString("artist", "James"),
                        duration = obj.optLong("duration", 226000L),
                        uri = Uri.parse(obj.getString("uri")),
                        folderInfo = obj.optString("folderInfo", "FIIO"),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        formatName = obj.optString("formatName", "WAV")
                    )
                )
            }
        } catch (_: Throwable) {}
        return list
    }

    fun saveTrackPosition(context: Context, trackTitle: String, posMs: Int, index: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_LAST_INDEX, index)
            .putInt(KEY_LAST_POS, posMs)
            .putInt("pos_${trackTitle.hashCode()}", posMs)
            .apply()
    }

    fun getSavedTrackPosition(context: Context, trackTitle: String): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt("pos_${trackTitle.hashCode()}", 0)
    }

    fun loadFavorites(context: Context): Set<String> {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
    }

    fun toggleFavorite(context: Context, trackTitle: String): Set<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.contains(trackTitle)) current.remove(trackTitle) else current.add(trackTitle)
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        return current
    }

    fun savePlayMode(context: Context, mode: PlayMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_PLAY_MODE, mode.name).apply()
    }

    fun loadPlayMode(context: Context): PlayMode {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_PLAY_MODE, PlayMode.SEQUENTIAL.name) ?: PlayMode.SEQUENTIAL.name
        return try { PlayMode.valueOf(name) } catch (_: Throwable) { PlayMode.SEQUENTIAL }
    }

    fun loadLastIndex(context: Context): Int = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_LAST_INDEX, 0)
    fun loadLastPos(context: Context): Int = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_LAST_POS, 0)

    fun addSavedFolderUri(context: Context, treeUri: Uri) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FOLDERS, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(treeUri.toString())
        prefs.edit().putStringSet(KEY_FOLDERS, set).apply()
    }

    fun getSavedFolderUris(context: Context): List<Uri> {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getStringSet(KEY_FOLDERS, emptySet())?.map { Uri.parse(it) } ?: emptyList()
    }
}

fun formatTimeMs(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hrs = totalSec / 3600
    val min = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (hrs > 0) {
        "%d:%02d:%02d".format(hrs, min, sec)
    } else {
        "0:%02d:%02d".format(min, sec)
    }
}

@Composable
fun SnowskyEchoDapApp(
    onAutoScanAll: () -> Pair<List<AudioTrack>, Int>,
    onParseManualUris: (List<Uri>) -> List<AudioTrack>,
    onParseFolderUri: (Uri) -> Pair<List<AudioTrack>, Int>,
    onPlayTrack: (AudioTrack, Int, Long, () -> Unit) -> Unit,
    onTogglePlay: () -> Boolean,
    onStopPlayback: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSetMasterVolume: (Float) -> Unit,
    onSetSleepMultiplier: (Float) -> Unit,
    onSetPureDsdMode: (Boolean) -> Unit,
    onSetBass: (Float) -> Unit,
    onSetVinyl: (Boolean) -> Unit,
    onSetClearAudio: (Boolean) -> Unit,
    onSetDsee: (Boolean) -> Unit,
    onSetChannelBalance: (Float) -> Unit,
    onSetPhaseInvert: (Boolean) -> Unit,
    onSetCrossfade: (Boolean) -> Unit,
    onSetTapeSpeed: (Float) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onToggleOrientation: () -> Unit,
    onSetEqBand10: (Int, Float) -> Unit,
    getIsPlaying: () -> Boolean,
    getPosition: () -> Int,
    getDuration: () -> Int,
    getAudioOutputState: () -> AudioOutputState,
    getTrackSpecs: (AudioTrack) -> Pair<Int, String>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(AppScreen.NOW_PLAYING) }
    var playlist by remember { mutableStateOf(PlaylistStorage.loadPlaylist(context)) }
    var favoriteTitles by remember { mutableStateOf(PlaylistStorage.loadFavorites(context)) }
    var playMode by remember { mutableStateOf(PlaylistStorage.loadPlayMode(context)) }

    var isScanning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(PlaylistStorage.loadLastIndex(context)) }
    var isPlaying by remember { mutableStateOf(getIsPlaying()) }
    var posMs by remember { mutableStateOf(PlaylistStorage.loadLastPos(context)) }
    var durMs by remember { mutableStateOf(226000) }

    // Trạng thái OTA Update
    var otaAutoCheck by remember { mutableStateOf(OtaUpdateManager.isAutoCheckEnabled(context)) }
    var otaIsChecking by remember { mutableStateOf(false) }
    var otaAvailableRelease by remember { mutableStateOf<OtaReleaseInfo?>(null) }
    var otaIsDownloading by remember { mutableStateOf(false) }
    var otaDownloadPercent by remember { mutableStateOf(0) }
    var otaDownloadedMb by remember { mutableStateOf(0f) }
    var otaTotalMb by remember { mutableStateOf(0f) }
    var otaDownloadedFile by remember { mutableStateOf<File?>(null) }
    var otaErrorMsg by remember { mutableStateOf<String?>(null) }

    val dspPrefs = context.getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
    var masterVol by remember { mutableStateOf(dspPrefs.getFloat("dsp_master_vol", 0.75f)) }
    var pureDsdMode by remember { mutableStateOf(dspPrefs.getBoolean("dsp_pure_dsd_mode", false)) }
    var megaBass by remember { mutableStateOf(dspPrefs.getFloat("dsp_bass", 0.45f)) }
    var vinylProcessor by remember { mutableStateOf(dspPrefs.getBoolean("dsp_vinyl", true)) }
    var clearAudioPlus by remember { mutableStateOf(dspPrefs.getBoolean("dsp_clear_audio", true)) }
    var dseeUltimate by remember { mutableStateOf(dspPrefs.getBoolean("dsp_dsee", true)) }
    var channelBalance by remember {
        val raw = dspPrefs.getFloat("dsp_balance", 0f)
        mutableStateOf(if (abs(raw) < 0.04f) 0f else raw)
    }
    var phaseInvert by remember { mutableStateOf(dspPrefs.getBoolean("dsp_phase_invert", false)) }
    var crossfadeEnabled by remember { mutableStateOf(dspPrefs.getBoolean("dsp_crossfade", true)) }
    var tapeSpeed by remember { mutableStateOf(dspPrefs.getFloat("dsp_tape_speed", 1.0f)) }
    var keepScreenOnDeskMode by remember { mutableStateOf(false) }
    var sleepTimerRemainingSec by remember { mutableStateOf(0) }

    val eq10Bands = remember {
        mutableStateListOf(
            dspPrefs.getFloat("dsp_eq_0", 2.0f),
            dspPrefs.getFloat("dsp_eq_1", 1.5f),
            dspPrefs.getFloat("dsp_eq_2", 1.0f),
            dspPrefs.getFloat("dsp_eq_3", 0.0f),
            dspPrefs.getFloat("dsp_eq_4", 0.0f),
            dspPrefs.getFloat("dsp_eq_5", 1.0f),
            dspPrefs.getFloat("dsp_eq_6", 2.0f),
            dspPrefs.getFloat("dsp_eq_7", 3.0f),
            dspPrefs.getFloat("dsp_eq_8", 2.5f),
            dspPrefs.getFloat("dsp_eq_9", 3.5f)
        )
    }

    var audioOutput by remember { mutableStateOf(getAudioOutputState()) }
    var currentBitrate by remember { mutableStateOf(1536) }
    var currentSampleRate by remember { mutableStateOf("192kHz | 24bit") }

    val notifPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    BackHandler(enabled = currentScreen != AppScreen.NOW_PLAYING) {
        currentScreen = AppScreen.NOW_PLAYING
    }

    fun triggerOtaCheck(showToastWhenUpToDate: Boolean) {
        if (otaIsChecking) return
        otaIsChecking = true
        otaErrorMsg = null
        scope.launch {
            when (val res = OtaUpdateManager.checkForUpdates(context)) {
                is OtaCheckResult.UpdateAvailable -> {
                    otaIsChecking = false
                    otaDownloadedFile = null
                    otaDownloadPercent = 0
                    otaAvailableRelease = res.release
                }
                is OtaCheckResult.UpToDate -> {
                    otaIsChecking = false
                    if (showToastWhenUpToDate) {
                        Toast.makeText(
                            context,
                            "Bạn đang dùng phiên bản mới nhất (Walkman ${res.currentVersion})",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                is OtaCheckResult.Error -> {
                    otaIsChecking = false
                    if (showToastWhenUpToDate) {
                        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun updateSystemMediaNotification(playing: Boolean) {
        val t = playlist.getOrNull(currentIndex) ?: return
        WalkmanMediaSessionService.updateNowPlayingNotification(
            context = context,
            title = t.title,
            artist = t.artist,
            isPlaying = playing,
            posMs = posMs.toLong(),
            durMs = durMs.toLong().coerceAtLeast(t.duration)
        ) { cmd ->
            MainActivity.onExternalCommand?.invoke(cmd)
        }
    }

    fun playAtIndex(idx: Int, customResumePos: Long? = null) {
        if (playlist.isEmpty()) return
        playlist.getOrNull(currentIndex)?.let { oldTrack ->
            if (posMs > 5000) {
                PlaylistStorage.saveTrackPosition(context, oldTrack.title, posMs, currentIndex)
            }
        }

        val safeIdx = (idx + playlist.size) % playlist.size
        currentIndex = safeIdx
        val track = playlist[safeIdx]

        val savedPosForThisTrack = PlaylistStorage.getSavedTrackPosition(context, track.title)
        val startMs = customResumePos ?: if (track.duration > 600000L && savedPosForThisTrack > 10000) {
            savedPosForThisTrack.toLong()
        } else 0L

        posMs = startMs.toInt()
        durMs = track.duration.toInt().coerceAtLeast(1)

        scope.launch(Dispatchers.IO) {
            val specs = getTrackSpecs(track)
            withContext(Dispatchers.Main) {
                currentBitrate = specs.first
                currentSampleRate = specs.second
            }
        }

        onPlayTrack(track, safeIdx, startMs) {
            val nextIdx = when (playMode) {
                PlayMode.REPEAT_ONE -> currentIndex
                PlayMode.SHUFFLE -> playlist.indices.random()
                PlayMode.SEQUENTIAL -> (currentIndex + 1) % playlist.size
            }
            playAtIndex(nextIdx, 0L)
        }
        isPlaying = true
        updateSystemMediaNotification(true)
    }

    LaunchedEffect(sleepTimerRemainingSec > 0) {
        while (sleepTimerRemainingSec > 0) {
            delay(1000)
            sleepTimerRemainingSec--
            if (sleepTimerRemainingSec in 1..60) {
                onSetSleepMultiplier(sleepTimerRemainingSec / 60f)
            } else if (sleepTimerRemainingSec == 0) {
                onStopPlayback()
                isPlaying = false
                onSetSleepMultiplier(1.0f)
                updateSystemMediaNotification(false)
            }
        }
    }

    DisposableEffect(playlist, currentIndex, playMode, isPlaying) {
        MainActivity.onExternalCommand = { cmd ->
            scope.launch(Dispatchers.Main) {
                when {
                    cmd == "PLAY_PAUSE" -> {
                        if (!isPlaying && !getIsPlaying()) {
                            playAtIndex(currentIndex, posMs.toLong())
                        } else {
                            isPlaying = onTogglePlay()
                            updateSystemMediaNotification(isPlaying)
                        }
                    }
                    cmd == "NEXT" -> {
                        if (playlist.isNotEmpty()) {
                            val next = if (playMode == PlayMode.SHUFFLE) playlist.indices.random() else currentIndex + 1
                            playAtIndex(next, null)
                        }
                    }
                    cmd == "PREV" -> {
                        if (playlist.isNotEmpty()) playAtIndex(currentIndex - 1, null)
                    }
                    cmd.startsWith("SEEK:") -> {
                        val target = cmd.substringAfter("SEEK:").toIntOrNull() ?: 0
                        posMs = target
                        onSeekTo(target)
                        playlist.getOrNull(currentIndex)?.let {
                            PlaylistStorage.saveTrackPosition(context, it.title, posMs, currentIndex)
                        }
                        updateSystemMediaNotification(isPlaying)
                    }
                }
            }
        }
        onDispose {}
    }

    fun runDeduplicatedScan() {
        if (isScanning) return
        isScanning = true
        scope.launch(Dispatchers.IO) {
            val autoRes = onAutoScanAll()
            val savedFolders = PlaylistStorage.getSavedFolderUris(context)
            val folderTracks = mutableListOf<AudioTrack>()
            for (treeUri in savedFolders) {
                val r = onParseFolderUri(treeUri)
                folderTracks.addAll(r.first)
            }

            val mergedList = mutableListOf<AudioTrack>()
            val seenKeys = mutableSetOf<String>()
            for (track in (folderTracks + autoRes.first + playlist)) {
                val key = "${track.title.trim().lowercase()}_${track.sizeBytes}"
                if (!seenKeys.contains(key)) {
                    seenKeys.add(key)
                    mergedList.add(track)
                }
            }

            PlaylistStorage.savePlaylist(context, mergedList)
            withContext(Dispatchers.Main) {
                playlist = mergedList
                isScanning = false
                Toast.makeText(context, "Đã cập nhật ${mergedList.size} bài hát", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runDeduplicatedScan()
    }

    val manualFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            isScanning = true
            scope.launch(Dispatchers.IO) {
                val added = onParseManualUris(uris)
                val merged = (added + playlist).distinctBy { "${it.title.trim().lowercase()}_${it.sizeBytes}" }
                PlaylistStorage.savePlaylist(context, merged)
                withContext(Dispatchers.Main) {
                    playlist = merged
                    isScanning = false
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            PlaylistStorage.addSavedFolderUri(context, treeUri)
            isScanning = true
            scope.launch(Dispatchers.IO) {
                val res = onParseFolderUri(treeUri)
                val merged = (res.first + playlist).distinctBy { "${it.title.trim().lowercase()}_${it.sizeBytes}" }
                PlaylistStorage.savePlaylist(context, merged)
                withContext(Dispatchers.Main) {
                    playlist = merged
                    isScanning = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notifPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val hasPerm = ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        if (hasPerm && playlist.isEmpty()) {
            runDeduplicatedScan()
        }
        playlist.getOrNull(currentIndex)?.let { track ->
            withContext(Dispatchers.IO) {
                val specs = getTrackSpecs(track)
                withContext(Dispatchers.Main) {
                    currentBitrate = specs.first
                    currentSampleRate = specs.second
                }
            }
        }
        if (otaAutoCheck) {
            delay(1500)
            triggerOtaCheck(showToastWhenUpToDate = false)
        }
    }

    LaunchedEffect(isPlaying) {
        var tickCounter = 0
        while (true) {
            isPlaying = getIsPlaying()
            if (isPlaying) {
                posMs = getPosition()
                durMs = getDuration().coerceAtLeast(1)
                tickCounter++
                if (tickCounter % 15 == 0) {
                    playlist.getOrNull(currentIndex)?.let { t ->
                        PlaylistStorage.saveTrackPosition(context, t.title, posMs, currentIndex)
                    }
                    audioOutput = getAudioOutputState()
                    updateSystemMediaNotification(true)
                }
            }
            delay(60)
        }
    }

    val currentTrack = playlist.getOrNull(currentIndex)
    val isCurrentFavorite = currentTrack != null && favoriteTitles.contains(currentTrack.title)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DapChassisBlack)
            .systemBarsPadding()
    ) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                fadeIn(animationSpec = tween(240)) togetherWith fadeOut(animationSpec = tween(180))
            },
            label = "dap_screen_transition"
        ) { screen ->
            when (screen) {
                AppScreen.NOW_PLAYING -> SnowskyEchoNowPlayingScreen(
                    track = currentTrack,
                    trackIndex = currentIndex,
                    totalTracks = playlist.size,
                    formatName = currentTrack?.formatName ?: "WAV",
                    sampleRateText = currentSampleRate,
                    bitrateKbps = currentBitrate,
                    masterVolumePercent = (masterVol * 100).roundToInt(),
                    audioOutput = audioOutput,
                    isFavorite = isCurrentFavorite,
                    onToggleFavorite = {
                        currentTrack?.let {
                            favoriteTitles = PlaylistStorage.toggleFavorite(context, it.title)
                        }
                    },
                    isPlaying = isPlaying,
                    playMode = playMode,
                    posMs = posMs,
                    durMs = durMs,
                    onSeek = { target ->
                        posMs = target
                        onSeekTo(target)
                        currentTrack?.let {
                            PlaylistStorage.saveTrackPosition(context, it.title, posMs, currentIndex)
                        }
                        updateSystemMediaNotification(isPlaying)
                    },
                    onToggleShuffle = {
                        playMode = if (playMode == PlayMode.SHUFFLE) PlayMode.SEQUENTIAL else PlayMode.SHUFFLE
                        PlaylistStorage.savePlayMode(context, playMode)
                    },
                    onToggleRepeat = {
                        playMode = if (playMode == PlayMode.REPEAT_ONE) PlayMode.SEQUENTIAL else PlayMode.REPEAT_ONE
                        PlaylistStorage.savePlayMode(context, playMode)
                    },
                    onPlayPause = {
                        if (playlist.isEmpty()) {
                            currentScreen = AppScreen.LIBRARY
                        } else if (!isPlaying && !getIsPlaying()) {
                            playAtIndex(currentIndex, posMs.toLong())
                        } else {
                            isPlaying = onTogglePlay()
                            updateSystemMediaNotification(isPlaying)
                        }
                    },
                    onPrev = {
                        if (playlist.isNotEmpty()) playAtIndex(currentIndex - 1, null)
                    },
                    onNext = {
                        if (playlist.isNotEmpty()) {
                            val next = if (playMode == PlayMode.SHUFFLE) playlist.indices.random() else currentIndex + 1
                            playAtIndex(next, null)
                        }
                    },
                    onToggleOrientation = onToggleOrientation,
                    onOpenLibrary = { currentScreen = AppScreen.LIBRARY },
                    onOpenAudioLab = {
                        audioOutput = getAudioOutputState()
                        currentScreen = AppScreen.AUDIO_LAB
                    }
                )

                AppScreen.LIBRARY -> SignatureLibraryScreen(
                    tracks = playlist,
                    favoriteTitles = favoriteTitles,
                    currentIndex = currentIndex,
                    onBackToPlayer = { currentScreen = AppScreen.NOW_PLAYING },
                    onAutoScan = {
                        val hasPerm = ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) runDeduplicatedScan() else permLauncher.launch(requiredPermission)
                    },
                    onPickFiles = { manualFilesLauncher.launch(arrayOf("audio/*")) },
                    onPickFolder = { folderPickerLauncher.launch(null) },
                    onToggleFavorite = { title ->
                        favoriteTitles = PlaylistStorage.toggleFavorite(context, title)
                    },
                    onClearSaved = {
                        playlist = emptyList()
                        PlaylistStorage.savePlaylist(context, emptyList())
                        posMs = 0
                    },
                    onSelectTrack = { track ->
                        val realIndex = playlist.indexOfFirst { it.id == track.id && it.title == track.title }
                        if (realIndex >= 0) {
                            playAtIndex(realIndex, null)
                            currentScreen = AppScreen.NOW_PLAYING
                        }
                    }
                )

                AppScreen.AUDIO_LAB -> SignatureAudioLabScreen(
                    pureDsdMode = pureDsdMode,
                    masterVol = masterVol,
                    megaBass = megaBass,
                    clearAudio = clearAudioPlus,
                    dsee = dseeUltimate,
                    vinyl = vinylProcessor,
                    phaseInvert = phaseInvert,
                    channelBalance = channelBalance,
                    crossfade = crossfadeEnabled,
                    tapeSpeed = tapeSpeed,
                    keepScreenOn = keepScreenOnDeskMode,
                    sleepTimerRemainingSec = sleepTimerRemainingSec,
                    eq10Bands = eq10Bands,
                    currentTrack = currentTrack,
                    currentBitrate = currentBitrate,
                    currentSampleRate = currentSampleRate,
                    audioOutput = audioOutput,
                    otaAutoCheck = otaAutoCheck,
                    otaIsChecking = otaIsChecking,
                    onOtaAutoCheckToggle = { enabled ->
                        otaAutoCheck = enabled
                        OtaUpdateManager.setAutoCheckEnabled(context, enabled)
                    },
                    onCheckOtaNow = { triggerOtaCheck(showToastWhenUpToDate = true) },
                    onBack = { currentScreen = AppScreen.NOW_PLAYING },
                    onPureDsdToggle = {
                        pureDsdMode = it
                        onSetPureDsdMode(it)
                    },
                    onMasterVolChange = {
                        masterVol = it
                        onSetMasterVolume(it)
                    },
                    onBassChange = {
                        megaBass = it
                        onSetBass(it)
                    },
                    onClearAudioToggle = {
                        clearAudioPlus = it
                        onSetClearAudio(it)
                    },
                    onDseeToggle = {
                        dseeUltimate = it
                        onSetDsee(it)
                    },
                    onVinylToggle = {
                        vinylProcessor = it
                        onSetVinyl(it)
                    },
                    onPhaseInvertToggle = {
                        phaseInvert = it
                        onSetPhaseInvert(it)
                    },
                    onBalanceChange = { rawBal ->
                        val snapped = if (abs(rawBal) < 0.03f) 0f else rawBal.coerceIn(-0.5f, 0.5f)
                        channelBalance = snapped
                        onSetChannelBalance(snapped)
                    },
                    onBand10Change = { idx, gain ->
                        eq10Bands[idx] = gain
                        onSetEqBand10(idx, gain)
                    },
                    onResetEq = {
                        for (i in 0 until 10) {
                            eq10Bands[i] = 0f
                            onSetEqBand10(i, 0f)
                        }
                    },
                    onCrossfadeToggle = {
                        crossfadeEnabled = it
                        onSetCrossfade(it)
                    },
                    onTapeSpeedChange = {
                        tapeSpeed = it
                        onSetTapeSpeed(it)
                    },
                    onKeepScreenOnToggle = {
                        keepScreenOnDeskMode = it
                        onSetKeepScreenOn(it)
                    },
                    onSetSleepMinutes = { mins ->
                        sleepTimerRemainingSec = mins * 60
                        onSetSleepMultiplier(1.0f)
                    }
                )
            }
        }

        otaAvailableRelease?.let { release ->
            OtaUpdateDialog(
                release = release,
                isDownloading = otaIsDownloading,
                downloadPercent = otaDownloadPercent,
                downloadedMb = otaDownloadedMb,
                totalMb = otaTotalMb,
                downloadedFile = otaDownloadedFile,
                errorMessage = otaErrorMsg,
                onDismiss = {
                    if (!otaIsDownloading) {
                        otaAvailableRelease = null
                        otaErrorMsg = null
                    }
                },
                onStartDownload = {
                    if (!otaIsDownloading) {
                        otaIsDownloading = true
                        otaErrorMsg = null
                        otaDownloadPercent = 0
                        scope.launch {
                            val res = OtaUpdateManager.downloadUpdateApk(context, release) { dl, total, pct ->
                                otaDownloadPercent = pct
                                otaDownloadedMb = dl / (1024f * 1024f)
                                otaTotalMb = total / (1024f * 1024f)
                            }
                            otaIsDownloading = false
                            res.onSuccess { file ->
                                otaDownloadedFile = file
                                val installRes = OtaUpdateManager.triggerApkInstallation(context, file)
                                installRes.onFailure { err ->
                                    otaErrorMsg = err.localizedMessage
                                }
                            }.onFailure { err ->
                                otaErrorMsg = err.localizedMessage ?: "Lỗi tải gói cập nhật APK."
                            }
                        }
                    }
                },
                onInstallDownloaded = { file ->
                    val installRes = OtaUpdateManager.triggerApkInstallation(context, file)
                    installRes.onFailure { err ->
                        otaErrorMsg = err.localizedMessage
                    }
                }
            )
        }
    }
}

@Composable
fun OtaUpdateDialog(
    release: OtaReleaseInfo,
    isDownloading: Boolean,
    downloadPercent: Int,
    downloadedMb: Float,
    totalMb: Float,
    downloadedFile: File?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit,
    onInstallDownloaded: (File) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(PanelSurface)
                .border(1.5.dp, ChampagneGold, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = ChampagneGold, modifier = Modifier.size(22.dp))
                    Text(
                        text = "Bản Cập Nhật Mới",
                        color = TextPure,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ChampagneGold.copy(alpha = 0.18f))
                        .border(1.dp, ChampagneGold, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "v${release.cleanVersion}",
                        color = ChampagneGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Text(
                text = "${release.releaseTitle} (${if (release.publishedAt.isNotBlank()) release.publishedAt else "GitHub Release"})",
                color = ColdSilver,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DapChassisBlack)
                    .border(1.dp, BezelBorder, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = "CHANGELOG:",
                    color = ChampagneGold,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = release.changelog,
                    color = TextPure,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            if (isDownloading) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Đang tải gói APK...",
                            color = ChampagneGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (totalMb > 0f) {
                                "%d%% (%.1f / %.1f MB)".format(downloadPercent, downloadedMb, totalMb)
                            } else {
                                "$downloadPercent%"
                            },
                            color = TextPure,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (downloadPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = ChampagneGold,
                        trackColor = BezelBorder
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    color = EchoCrimsonRed,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isDownloading) {
                    TextButton(onClick = onDismiss) {
                        Text("Để sau", color = TextMuted, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (downloadedFile != null && !isDownloading) {
                    Button(
                        onClick = { onInstallDownloaded(downloadedFile) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LedPlayGreen,
                            contentColor = DapChassisBlack
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cài đặt ngay", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = onStartDownload,
                        enabled = !isDownloading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ChampagneGold,
                            contentColor = DapChassisBlack
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (isDownloading) "Đang tải..." else "Cập nhật ngay",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * =============================================================================================
 * MÀN HÌNH NOW PLAYING VỚI KẾT XUẤT 3D PHOTOREALISTIC CASSETTE & ÁNH SÁNG ĐỊNH HƯỚNG
 * =============================================================================================
 */
@Composable
fun SnowskyEchoNowPlayingScreen(
    track: AudioTrack?,
    trackIndex: Int,
    totalTracks: Int,
    formatName: String,
    sampleRateText: String,
    bitrateKbps: Int,
    masterVolumePercent: Int,
    audioOutput: AudioOutputState,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    isPlaying: Boolean,
    playMode: PlayMode,
    posMs: Int,
    durMs: Int,
    onSeek: (Int) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleOrientation: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenAudioLab: () -> Unit
) {
    val safeMaxDur = durMs.toFloat().coerceAtLeast(1f)
    val safeCurrPos = posMs.toFloat().coerceIn(0f, safeMaxDur)
    val progressFraction = (safeCurrPos / safeMaxDur).coerceIn(0f, 1f)

    // Giữ nguyên 100% logic vật lý chuyển động băng hiện tại
    val targetLinearTapeSpeed = if (isPlaying) 1.0f else 0.0f
    val smoothTapeMotion by animateFloatAsState(
        targetValue = targetLinearTapeSpeed,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "tape_inertia"
    )

    var cumulativeTapeDistance by remember { mutableStateOf(0f) }
    var lastSyncedPosMs by remember { mutableStateOf(posMs) }

    LaunchedEffect(posMs) {
        val diffMs = posMs - lastSyncedPosMs
        if (abs(diffMs) > 600) {
            cumulativeTapeDistance = (cumulativeTapeDistance + diffMs * 0.16f) % 100000f
        }
        lastSyncedPosMs = posMs
    }

    LaunchedEffect(isPlaying, smoothTapeMotion) {
        var lastNanos = withFrameNanos { it }
        while (isPlaying || smoothTapeMotion > 0.01f) {
            val now = withFrameNanos { it }
            val dt = (now - lastNanos) / 1_000_000_000f
            lastNanos = now
            cumulativeTapeDistance = (cumulativeTapeDistance + smoothTapeMotion * dt * 95f) % 100000f
        }
    }

    val trackOrderStr = if (totalTracks > 0) "%03d/%03d".format(trackIndex + 1, totalTracks) else "001/035"
    val specLineStr = "$formatName | $sampleRateText | ${bitrateKbps}kbps"
    val trackTitleStr = track?.title ?: "BORN FOR MUSIC"
    val trackArtistStr = track?.artist?.takeIf { !it.contains("<unknown>", true) } ?: "James"
    val albumNameStr = track?.folderInfo ?: "FIIO"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0E1016), Color(0xFF07080B), Color(0xFF050608))
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Gờ nút bấm vật lý trên đỉnh máy DAP (Có highlight kim loại và bóng đổ nhẹ)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                // Nút nguồn/Play màu đỏ vát 3D
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(8.dp)
                        .shadow(4.dp, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFFFB7185), EchoCrimsonRed, Color(0xFF9F1239)))
                        )
                        .clickable { onPlayPause() }
                )
                Box(
                    modifier = Modifier
                        .width(50.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF475063), Color(0xFF1E222B)))
                        )
                )
                Box(
                    modifier = Modifier
                        .width(34.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF475063), Color(0xFF1E222B)))
                        )
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier
                        .size(34.dp)
                        .shadow(4.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF222734), Color(0xFF13161E))))
                        .border(1.dp, Color(0xFF384052), RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Library", tint = ColdSilver, modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = onToggleOrientation,
                    modifier = Modifier
                        .size(34.dp)
                        .shadow(4.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF222734), Color(0xFF13161E))))
                        .border(1.dp, Color(0xFF384052), RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate", tint = ColdSilver, modifier = Modifier.size(15.dp))
                }
                IconButton(
                    onClick = onOpenAudioLab,
                    modifier = Modifier
                        .size(34.dp)
                        .shadow(4.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF222734), Color(0xFF13161E))))
                        .border(1.dp, ChampagneGoldDim.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Audio Lab", tint = ChampagneGold, modifier = Modifier.size(16.dp))
                }
            }
        }

        // =========================================================================================
        // KHUNG THÂN MÁY DAP TRUNG TÂM VỚI BÓNG ĐỔ SÂU, VÁT CẠNH BEVEL KIM LOẠI & NGUỒN SÁNG TRÊN-TRÁI
        // =========================================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 6.dp)
                .shadow(24.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF2E3444), Color(0xFF1B1F29), Color(0xFF101319), Color(0xFF0A0C10)),
                        start = Offset(0f, 0f),
                        end = Offset(1000f, 1400f)
                    )
                )
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF5A657D), Color(0xFF2E3545), Color(0xFF151821)),
                        start = Offset(0f, 0f),
                        end = Offset(900f, 1200f)
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Hốc màn hình kính đen chìm sâu vào thân máy (Recessed Beveled Glass Display)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .shadow(12.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(DapGlassScreen)
                    .border(
                        width = 2.2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF14171F), Color(0xFF2C3342), Color(0xFF475166)),
                            start = Offset(0f, 0f),
                            end = Offset(800f, 1000f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(10.dp)
            ) {
                // Vỏ băng Cassette 3D với chất liệu nhôm phay xước & viền Bevel phản sáng
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(14.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF384050),
                                    Color(0xFF262B36),
                                    Color(0xFF1A1E26),
                                    Color(0xFF12151B)
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(800f, 800f)
                            )
                        )
                        .border(
                            width = 1.5.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF647087), Color(0xFF363D4D), Color(0xFF191D26)),
                                start = Offset(0f, 0f),
                                end = Offset(700f, 700f)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    // Vân kim loại phay xước ngang (Brushed Metal Micro-Texture) & Bevel sáng cạnh trên外壳
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        // Đường highlight ánh sáng chiếu từ trên xuống mép vỏ cassette
                        drawLine(
                            color = Color.White.copy(alpha = 0.22f),
                            start = Offset(12.dp.toPx(), 1.dp.toPx()),
                            end = Offset(w - 12.dp.toPx(), 1.dp.toPx()),
                            strokeWidth = 1.2.dp.toPx()
                        )
                        // Các đường vân phay xước mảnh
                        var yLine = 8.dp.toPx()
                        while (yLine < h - 8.dp.toPx()) {
                            drawLine(
                                color = Color.White.copy(alpha = 0.018f),
                                start = Offset(0f, yLine),
                                end = Offset(w, yLine),
                                strokeWidth = 1.dp.toPx()
                            )
                            yLine += 4.dp.toPx()
                        }
                    }

                    // 4 ốc vít lục giác chìm 3D ở 4 góc mặt băng
                    CassetteCornerScrew(Modifier.align(Alignment.TopStart).padding(6.dp))
                    CassetteCornerScrew(Modifier.align(Alignment.TopEnd).padding(6.dp))
                    CassetteCornerScrew(Modifier.align(Alignment.BottomStart).padding(6.dp))
                    CassetteCornerScrew(Modifier.align(Alignment.BottomEnd).padding(6.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 1. THANH TRẠNG THÁI HUD TRÊN MÀN HÌNH
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White, modifier = Modifier.size(13.dp))
                                    Text("$masterVolumePercent", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }
                                Icon(
                                    imageVector = if (audioOutput.isBluetooth) Icons.Default.BluetoothAudio else Icons.Default.Headphones,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(trackOrderStr, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            }

                            Text("FAST-PC", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Shuffle, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(12.dp))
                                Box(modifier = Modifier.border(1.dp, Color.White, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 0.5.dp)) {
                                    Text(playMode.badgeCode, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }
                                Icon(Icons.Default.SdStorage, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(12.dp))
                                Box(modifier = Modifier.border(1.dp, Color.White, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 0.5.dp)) {
                                    Text("${audioOutput.batteryPercent}%", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }

                        // 2. DẢI NHÃN BĂNG DẬP NỔI (EMBOSSED METALLIC LABEL BANNER)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .shadow(4.dp, RoundedCornerShape(5.dp))
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF14171F), Color(0xFF232834), Color(0xFF161922))
                                    )
                                )
                                .border(
                                    width = 1.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(Color(0xFF2D3342), Color(0xFF556078))
                                    ),
                                    shape = RoundedCornerShape(5.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = trackTitleStr.uppercase(),
                                color = Color(0xFFF8FAFC),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                letterSpacing = 1.2.sp
                            )
                        }

                        // =========================================================================
                        // 3. CỬA SỔ KÍNH ACRYLIC 3D CHỨA 2 CUỘN BĂNG TỪ PHẢN XẠ DỊ HƯỚNG & ĐẦU TỪ
                        // =========================================================================
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.94f)
                                .height(132.dp)
                                .shadow(8.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF040507), Color(0xFF0C0F15), Color(0xFF06080C))
                                    )
                                )
                                .border(
                                    width = 2.5.dp,
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF1E232E), Color(0xFF4A5368), Color(0xFF64708A), Color(0xFF262B38)),
                                        start = Offset(0f, 0f),
                                        end = Offset(600f, 300f)
                                    ),
                                    shape = RoundedCornerShape(20.dp)
                                )
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val centerY = h * 0.44f
                                val leftX = w * 0.255f
                                val rightX = w * 0.745f

                                // A. Tấm lót chống ma sát màu Graphite bên dưới 2 cuộn băng (Inner Slip-Sheet)
                                drawRoundRect(
                                    brush = Brush.radialGradient(
                                        colors = listOf(Color(0xFF181C26), Color(0xFF0A0C10)),
                                        center = Offset(w * 0.5f, centerY),
                                        radius = w * 0.48f
                                    ),
                                    topLeft = Offset(w * 0.06f, h * 0.08f),
                                    size = Size(w * 0.88f, h * 0.76f),
                                    cornerRadius = CornerRadius(14.dp.toPx())
                                )

                                // B. BÁN KÍNH VẬT LÝ CỦA 2 CUỘN BĂNG TỪ (GIỮ NGUYÊN LOGIC BẢO TOÀN DIỆN TÍCH)
                                val hubOuterR = h * 0.205f
                                val minSpoolR = h * 0.222f
                                val maxSpoolR = h * 0.445f

                                val minArea = minSpoolR * minSpoolR
                                val maxArea = maxSpoolR * maxSpoolR

                                val leftSpoolR = sqrt(maxArea - progressFraction * (maxArea - minArea))
                                val rightSpoolR = sqrt(minArea + progressFraction * (maxArea - minArea))

                                val leftAngle = -((cumulativeTapeDistance * (maxSpoolR / leftSpoolR.coerceAtLeast(1f))) % 360f)
                                val rightAngle = -((cumulativeTapeDistance * (maxSpoolR / rightSpoolR.coerceAtLeast(1f))) % 360f)

                                // C. KẾT XUẤT 3D CUỘN BĂNG TỪ (ANISOTROPIC CONICAL SHEEN + MULTI-LAYER WINDING + 3D SHADOW)
                                listOf(
                                    Triple(leftX, leftSpoolR, leftAngle),
                                    Triple(rightX, rightSpoolR, rightAngle)
                                ).forEach { (cx, spoolR, angle) ->
                                    // 1. Bóng đổ mềm lệch xuống góc dưới-phải (Do nguồn sáng chiếu từ trên-trái)
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                Color.Black.copy(alpha = 0.85f),
                                                Color.Black.copy(alpha = 0.45f),
                                                Color.Transparent
                                            ),
                                            center = Offset(cx + 4.dp.toPx(), centerY + 5.dp.toPx()),
                                            radius = spoolR + 7.dp.toPx()
                                        ),
                                        radius = spoolR + 7.dp.toPx(),
                                        center = Offset(cx + 4.dp.toPx(), centerY + 5.dp.toPx())
                                    )

                                    // 2. Lớp nền từ tính Oxit Sắt / Coban đậm (Base Magnetic Pack)
                                    drawCircle(
                                        color = Color(0xFF19110E),
                                        radius = spoolR,
                                        center = Offset(cx, centerY)
                                    )

                                    // 3. Hiệu ứng phản xạ dị hướng 3D (Anisotropic Butterfly Sheen) quay nhẹ theo cuộn băng
                                    rotate(angle * 0.35f - 30f, pivot = Offset(cx, centerY)) {
                                        drawCircle(
                                            brush = Brush.sweepGradient(
                                                0.00f to Color(0xFF160E0B),
                                                0.12f to Color(0xFF4E352B),
                                                0.20f to Color(0xFF735042), // Cực sáng phản chiếu 1
                                                0.28f to Color(0xFF4E352B),
                                                0.50f to Color(0xFF140C09),
                                                0.62f to Color(0xFF4E352B),
                                                0.70f to Color(0xFF6B493B), // Cực sáng phản chiếu 2
                                                0.78f to Color(0xFF4E352B),
                                                1.00f to Color(0xFF160E0B),
                                                center = Offset(cx, centerY)
                                            ),
                                            radius = spoolR,
                                            center = Offset(cx, centerY)
                                        )
                                    }

                                    // 4. Lớp phủ tạo độ dày vòm 3D ở mép trong và mép ngoài cuộn băng (3D Edge Bevel Shading)
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            0.0f to Color.Black.copy(alpha = 0.7f),
                                            0.45f to Color.Transparent,
                                            0.88f to Color.Transparent,
                                            0.96f to Color.White.copy(alpha = 0.14f), // Gờ sáng mép ngoài cuộn băng
                                            1.0f to Color.Black.copy(alpha = 0.75f),
                                            center = Offset(cx, centerY),
                                            radius = spoolR.coerceAtLeast(1f)
                                        ),
                                        radius = spoolR,
                                        center = Offset(cx, centerY)
                                    )

                                    // 5. Các vòng rãnh lớp băng quấn siêu chi tiết (Fine Winding Layer Grooves)
                                    val tapeThickness = (spoolR - hubOuterR).coerceAtLeast(0f)
                                    val numRings = (tapeThickness / 2.2.dp.toPx()).toInt().coerceIn(2, 22)
                                    for (rIdx in 1..numRings) {
                                        val ratio = rIdx.toFloat() / (numRings + 1)
                                        val ringR = hubOuterR + tapeThickness * ratio
                                        val isHighlightRing = (rIdx % 3 == 0)
                                        drawCircle(
                                            color = if (isHighlightRing) {
                                                Color(0xFF9E7362).copy(alpha = 0.25f)
                                            } else {
                                                Color(0xFF0A0605).copy(alpha = 0.55f)
                                            },
                                            radius = ringR,
                                            center = Offset(cx, centerY),
                                            style = Stroke(width = if (isHighlightRing) 0.7.dp.toPx() else 0.9.dp.toPx())
                                        )
                                    }

                                    // 6. Viền ngoài cùng của cuộn dây từ có độ dày sắc nét
                                    drawCircle(
                                        color = Color(0xFF3E2B23),
                                        radius = spoolR,
                                        center = Offset(cx, centerY),
                                        style = Stroke(width = 1.5.dp.toPx())
                                    )
                                }

                                // D. DÂY BĂNG MYLAR BÁN TRONG SUỐT, CÓ BÓNG ĐỔ & ĐẦU TỪ KIM LOẠI Ở ĐÁY CỬA SỔ
                                val rollerLeftX = w * 0.125f
                                val rollerRightX = w * 0.875f
                                val rollerY = h * 0.85f
                                val rollerR = 6.dp.toPx()

                                val leftTangentX = leftX - leftSpoolR * 0.78f
                                val leftTangentY = centerY + leftSpoolR * 0.62f
                                val rightTangentX = rightX + rightSpoolR * 0.78f
                                val rightTangentY = centerY + rightSpoolR * 0.62f

                                // 1. Bóng đổ của sợi dây băng xuống nền hốc máy
                                drawLine(
                                    color = Color.Black.copy(alpha = 0.70f),
                                    start = Offset(leftTangentX + 2.dp.toPx(), leftTangentY + 2.dp.toPx()),
                                    end = Offset(rollerLeftX - rollerR * 0.5f + 2.dp.toPx(), rollerY + 2.dp.toPx()),
                                    strokeWidth = 3.5.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color.Black.copy(alpha = 0.70f),
                                    start = Offset(rollerLeftX, rollerY + rollerR * 0.55f + 2.dp.toPx()),
                                    end = Offset(rollerRightX, rollerY + rollerR * 0.55f + 2.dp.toPx()),
                                    strokeWidth = 4.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color.Black.copy(alpha = 0.70f),
                                    start = Offset(rollerRightX + rollerR * 0.5f + 2.dp.toPx(), rollerY + 2.dp.toPx()),
                                    end = Offset(rightTangentX + 2.dp.toPx(), rightTangentY + 2.dp.toPx()),
                                    strokeWidth = 3.5.dp.toPx(),
                                    cap = StrokeCap.Round
                                )

                                // 2. Cụm Đầu Từ Đọc Kim Loại (Magnetic Play Head) & Đệm Nỉ Ép Băng (Pressure Pad) ở giữa đáy
                                val headWidth = 28.dp.toPx()
                                val headHeight = 10.dp.toPx()
                                val headTop = rollerY - 2.dp.toPx()
                                drawRoundRect(
                                    brush = Brush.horizontalGradient(
                                        listOf(Color(0xFF475569), Color(0xFFCBD5E1), Color(0xFF64748B))
                                    ),
                                    topLeft = Offset(w * 0.5f - headWidth * 0.5f, headTop),
                                    size = Size(headWidth, headHeight),
                                    cornerRadius = CornerRadius(3.dp.toPx())
                                )
                                // Khe từ đọc (Magnetic Gap) ở chính giữa đầu từ
                                drawLine(
                                    color = Color(0xFF0F172A),
                                    start = Offset(w * 0.5f, headTop),
                                    end = Offset(w * 0.5f, headTop + headHeight),
                                    strokeWidth = 1.2.dp.toPx()
                                )
                                // Đệm ép bằng đồng phía sau sợi băng
                                drawRoundRect(
                                    color = Color(0xFFB45309),
                                    topLeft = Offset(w * 0.5f - 8.dp.toPx(), rollerY - 5.dp.toPx()),
                                    size = Size(16.dp.toPx(), 3.dp.toPx()),
                                    cornerRadius = CornerRadius(1.dp.toPx())
                                )

                                // 3. Sợi dây băng Mylar hơi trong suốt (Semi-translucent Copper-Brown Ribbon)
                                drawLine(
                                    color = Color(0xFF6D483B).copy(alpha = 0.88f),
                                    start = Offset(leftTangentX, leftTangentY),
                                    end = Offset(rollerLeftX - rollerR * 0.55f, rollerY),
                                    strokeWidth = 2.6.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color(0xFF7D5344).copy(alpha = 0.92f),
                                    start = Offset(rollerLeftX, rollerY + rollerR * 0.45f),
                                    end = Offset(rollerRightX, rollerY + rollerR * 0.45f),
                                    strokeWidth = 3.0.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color(0xFF6D483B).copy(alpha = 0.88f),
                                    start = Offset(rollerRightX + rollerR * 0.55f, rollerY),
                                    end = Offset(rightTangentX, rightTangentY),
                                    strokeWidth = 2.6.dp.toPx(),
                                    cap = StrokeCap.Round
                                )

                                // Viền sáng mỏng dọc mép trên của sợi dây băng (Specular Ribbon Edge)
                                drawLine(
                                    color = Color(0xFFFDE68A).copy(alpha = 0.30f),
                                    start = Offset(leftTangentX, leftTangentY - 0.8.dp.toPx()),
                                    end = Offset(rollerLeftX - rollerR * 0.55f, rollerY - 0.8.dp.toPx()),
                                    strokeWidth = 0.8.dp.toPx()
                                )
                                drawLine(
                                    color = Color(0xFFFDE68A).copy(alpha = 0.35f),
                                    start = Offset(rollerLeftX, rollerY + rollerR * 0.45f - 1.dp.toPx()),
                                    end = Offset(rollerRightX, rollerY + rollerR * 0.45f - 1.dp.toPx()),
                                    strokeWidth = 0.9.dp.toPx()
                                )
                                drawLine(
                                    color = Color(0xFFFDE68A).copy(alpha = 0.30f),
                                    start = Offset(rollerRightX + rollerR * 0.55f, rollerY - 0.8.dp.toPx()),
                                    end = Offset(rightTangentX, rightTangentY - 0.8.dp.toPx()),
                                    strokeWidth = 0.8.dp.toPx()
                                )

                                // 4. Hiệu ứng vệt bóng lướt mượt trên sợi dây băng khi chạy (Translucent Moving Shimmer)
                                val shimmerSpacing = 22.dp.toPx()
                                val shimmerLen = 9.dp.toPx()
                                val movingOffset = (cumulativeTapeDistance * 0.95f) % shimmerSpacing

                                var curX = rollerLeftX + movingOffset
                                while (curX < rollerRightX - shimmerLen) {
                                    drawLine(
                                        brush = Brush.horizontalGradient(
                                            listOf(
                                                Color.Transparent,
                                                ChampagneGold.copy(alpha = 0.85f),
                                                Color.White.copy(alpha = 0.70f),
                                                Color.Transparent
                                            ),
                                            startX = curX,
                                            endX = curX + shimmerLen
                                        ),
                                        start = Offset(curX, rollerY + rollerR * 0.45f),
                                        end = Offset(curX + shimmerLen, rollerY + rollerR * 0.45f),
                                        strokeWidth = 2.4.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                    curX += shimmerSpacing
                                }

                                // Vệt sáng chạy trên 2 nhánh dây nghiêng Trái & Phải
                                for (seg in 0..1) {
                                    val p = ((cumulativeTapeDistance * 0.045f + seg * 0.5f) % 1.0f)
                                    val lx = leftTangentX + (rollerLeftX - leftTangentX) * p
                                    val ly = leftTangentY + (rollerY - leftTangentY) * p
                                    drawCircle(
                                        color = ChampagneGold.copy(alpha = 0.75f),
                                        radius = 1.8.dp.toPx(),
                                        center = Offset(lx, ly)
                                    )

                                    val rx = rollerRightX + (rightTangentX - rollerRightX) * p
                                    val ry = rollerY + (rightTangentY - rollerY) * p
                                    drawCircle(
                                        color = ChampagneGold.copy(alpha = 0.75f),
                                        radius = 1.8.dp.toPx(),
                                        center = Offset(rx, ry)
                                    )
                                }

                                // 5. Hai Con Lăn Dẫn Hướng 3D (Machined Brass/Steel Guide Rollers)
                                val rollerAngle = -(cumulativeTapeDistance * 3.2f) % 360f
                                listOf(rollerLeftX, rollerRightX).forEach { rx ->
                                    // Bóng đổ con lăn
                                    drawCircle(
                                        color = Color.Black.copy(alpha = 0.7f),
                                        radius = rollerR + 1.5.dp.toPx(),
                                        center = Offset(rx + 1.dp.toPx(), rollerY + 1.5.dp.toPx())
                                    )
                                    // Vành cao su ép băng
                                    drawCircle(
                                        color = Color(0xFF1E293B),
                                        radius = rollerR,
                                        center = Offset(rx, rollerY)
                                    )
                                    // Lõi kim loại phay sáng
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            listOf(Color(0xFFE2E8F0), Color(0xFF64748B)),
                                            center = Offset(rx - 1.dp.toPx(), rollerY - 1.dp.toPx()),
                                            radius = rollerR * 0.78f
                                        ),
                                        radius = rollerR * 0.75f,
                                        center = Offset(rx, rollerY)
                                    )
                                    rotate(rollerAngle, pivot = Offset(rx, rollerY)) {
                                        drawLine(
                                            color = Color(0xFF0F172A),
                                            start = Offset(rx - rollerR * 0.7f, rollerY),
                                            end = Offset(rx + rollerR * 0.7f, rollerY),
                                            strokeWidth = 1.4.dp.toPx()
                                        )
                                        drawLine(
                                            color = Color(0xFF0F172A),
                                            start = Offset(rx, rollerY - rollerR * 0.7f),
                                            end = Offset(rx, rollerY + rollerR * 0.7f),
                                            strokeWidth = 1.4.dp.toPx()
                                        )
                                    }
                                    drawCircle(
                                        color = ChampagneGold,
                                        radius = rollerR * 0.32f,
                                        center = Offset(rx, rollerY)
                                    )
                                }

                                // E. CỬA SỔ THƯỚC ĐO LƯỢNG BĂNG CHÌM Ở GIỮA (BEVELED SCALE WINDOW)
                                val winL = w * 0.39f
                                val winR = w * 0.61f
                                val winT = centerY - h * 0.16f
                                val winB = centerY + h * 0.16f

                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        listOf(Color(0xFF141820), Color(0xFF222734), Color(0xFF161A23))
                                    ),
                                    topLeft = Offset(winL, winT),
                                    size = Size(winR - winL, winB - winT),
                                    cornerRadius = CornerRadius(5.dp.toPx())
                                )
                                drawRoundRect(
                                    color = Color(0xFF5A657A),
                                    topLeft = Offset(winL, winT),
                                    size = Size(winR - winL, winB - winT),
                                    cornerRadius = CornerRadius(5.dp.toPx()),
                                    style = Stroke(1.2.dp.toPx())
                                )

                                for (t in 0..10) {
                                    val tx = winL + 5.dp.toPx() + (winR - winL - 10.dp.toPx()) * (t.toFloat() / 10f)
                                    val tickH = if (t % 5 == 0) 6.dp.toPx() else 3.2.dp.toPx()
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.75f),
                                        start = Offset(tx, centerY - tickH),
                                        end = Offset(tx, centerY + tickH),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }

                                val gaugeX = winL + 5.dp.toPx() + (winR - winL - 10.dp.toPx()) * progressFraction
                                drawLine(
                                    color = EchoCrimsonRed,
                                    start = Offset(gaugeX, winT + 2.dp.toPx()),
                                    end = Offset(gaugeX, winB - 2.dp.toPx()),
                                    strokeWidth = 2.2.dp.toPx()
                                )

                                // F. VẼ 2 TRỤC BÁNH RĂNG 3D (3D METALLIC & CRIMSON REEL HUBS)
                                val redCoreR = hubOuterR * 0.68f
                                val innerHoleR = hubOuterR * 0.27f

                                listOf(leftX to leftAngle, rightX to rightAngle).forEach { (cx, angle) ->
                                    // Bóng đổ của vành trục lên cuộn băng
                                    drawCircle(
                                        color = Color.Black.copy(alpha = 0.8f),
                                        radius = hubOuterR + 2.dp.toPx(),
                                        center = Offset(cx + 1.5.dp.toPx(), centerY + 2.dp.toPx())
                                    )

                                    // Vành hợp kim nhôm vát sáng 3D (Machined Aluminum Rim)
                                    drawCircle(
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color.White, Color(0xFFCBD5E1), Color(0xFF64748B)),
                                            start = Offset(cx - hubOuterR, centerY - hubOuterR),
                                            end = Offset(cx + hubOuterR, centerY + hubOuterR)
                                        ),
                                        radius = hubOuterR,
                                        center = Offset(cx, centerY)
                                    )

                                    // Rãnh tối giữa vành nhôm và lõi đỏ
                                    drawCircle(
                                        color = Color(0xFF14171F),
                                        radius = hubOuterR * 0.85f,
                                        center = Offset(cx, centerY)
                                    )

                                    // Lõi cánh quạt màu Đỏ Crimson có khối cầu 3D (3D Shaded Crimson Hub)
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(Color(0xFFFB7185), EchoCrimsonRed, Color(0xFF9F1239)),
                                            center = Offset(cx - redCoreR * 0.25f, centerY - redCoreR * 0.25f),
                                            radius = redCoreR * 1.2f
                                        ),
                                        radius = redCoreR,
                                        center = Offset(cx, centerY)
                                    )

                                    // 6 cánh quạt kim loại + 6 vấu kẹp băng (Drive Teeth) quay mượt
                                    rotate(angle, pivot = Offset(cx, centerY)) {
                                        for (spoke in 0 until 6) {
                                            rotate(spoke * 60f, pivot = Offset(cx, centerY)) {
                                                // Bóng dưới cánh quạt
                                                drawLine(
                                                    color = Color.Black.copy(alpha = 0.55f),
                                                    start = Offset(cx + 1.dp.toPx(), centerY - innerHoleR),
                                                    end = Offset(cx + 1.dp.toPx(), centerY - hubOuterR * 0.96f),
                                                    strokeWidth = 2.8.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )
                                                // Thân cánh quạt bạc sáng
                                                drawLine(
                                                    color = Color(0xFFF8FAFC),
                                                    start = Offset(cx, centerY - innerHoleR),
                                                    end = Offset(cx, centerY - hubOuterR * 0.96f),
                                                    strokeWidth = 2.5.dp.toPx(),
                                                    cap = StrokeCap.Round
                                                )
                                                // Lỗ cơ khí nhỏ trên vành
                                                drawCircle(
                                                    color = Color(0xFF0A0C10),
                                                    radius = 1.9.dp.toPx(),
                                                    center = Offset(cx, centerY - hubOuterR * 0.74f)
                                                )
                                            }
                                        }
                                    }

                                    // Trục kim loại trung tâm (Center Spindle)
                                    drawCircle(Color(0xFF07080C), innerHoleR, Offset(cx, centerY))
                                    drawCircle(
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color.White, Color(0xFF64748B)),
                                            start = Offset(cx - innerHoleR, centerY - innerHoleR),
                                            end = Offset(cx + innerHoleR, centerY + innerHoleR)
                                        ),
                                        radius = innerHoleR,
                                        center = Offset(cx, centerY),
                                        style = Stroke(1.4.dp.toPx())
                                    )
                                }

                                // G. HIỆU ỨNG MẶT KÍNH ACRYLIC TRONG SUỐT & BÓNG ĐỔ KHUNG CỬA SỔ (3D ACRYLIC LENS OVERLAY)
                                // 1. Dải phản quang chéo chính trên mặt kính
                                val mainGlare = Path().apply {
                                    moveTo(w * 0.30f, 0f)
                                    lineTo(w * 0.45f, 0f)
                                    lineTo(w * 0.27f, h)
                                    lineTo(w * 0.12f, h)
                                    close()
                                }
                                drawPath(
                                    path = mainGlare,
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.09f),
                                            Color.White.copy(alpha = 0.03f)
                                        )
                                    )
                                )

                                // 2. Dải phản quang phụ mảnh hơn bên cạnh
                                val secondGlare = Path().apply {
                                    moveTo(w * 0.48f, 0f)
                                    lineTo(w * 0.53f, 0f)
                                    lineTo(w * 0.35f, h)
                                    lineTo(w * 0.30f, h)
                                    close()
                                }
                                drawPath(
                                    path = secondGlare,
                                    color = Color.White.copy(alpha = 0.04f)
                                )

                                // 3. Bóng đổ hắt từ mép trên cửa sổ xuống (Top Inner Drop Shadow)
                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        0.0f to Color.Black.copy(alpha = 0.65f),
                                        0.12f to Color.Transparent,
                                        0.88f to Color.Transparent,
                                        1.0f to Color.White.copy(alpha = 0.08f)
                                    ),
                                    topLeft = Offset(0f, 0f),
                                    size = Size(w, h),
                                    cornerRadius = CornerRadius(20.dp.toPx())
                                )

                                // 4. Viền sáng khúc xạ thủy tinh mảnh bên trong mép cửa sổ (Inner Glass Specular Rim)
                                drawRoundRect(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.25f),
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.12f)
                                        ),
                                        start = Offset(0f, 0f),
                                        end = Offset(w, h)
                                    ),
                                    topLeft = Offset(1.5.dp.toPx(), 1.5.dp.toPx()),
                                    size = Size(w - 3.dp.toPx(), h - 3.dp.toPx()),
                                    cornerRadius = CornerRadius(18.dp.toPx()),
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                        }

                        // 4. HÀNG THÔNG TIN NGHỆ SĨ - ALBUM - HIFI & THỜI GIAN + THANH TIẾN TRÌNH ĐỎ NGANG MẶT BĂNG
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PersonOutline, null, tint = Color.White, modifier = Modifier.size(13.dp))
                                    Text(trackArtistStr, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Album, null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(albumNameStr, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(Icons.Default.Equalizer, null, tint = Color.White, modifier = Modifier.size(13.dp))
                                    Text("HiFi", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text((if (isPlaying) "▶ " else "⏸ ") + formatTimeMs(posMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(formatTimeMs(durMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }

                            Slider(
                                value = safeCurrPos,
                                onValueChange = { onSeek(it.toInt()) },
                                valueRange = 0f..safeMaxDur,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(16.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = EchoCrimsonRed,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                                )
                            )
                        }

                        // 5. ĐẾ HÌNH THANG DẬP NỔI 3D PHÍA DƯỚI (RAISED TRAPEZOID HEAD SHIELD)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.90f)
                                .height(32.dp)
                                .shadow(6.dp, RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF323948), Color(0xFF212631), Color(0xFF161A22))
                                    )
                                )
                                .border(
                                    width = 1.2.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(Color(0xFF647087), Color(0xFF2B313E))
                                    ),
                                    shape = RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp, bottomStart = 3.dp, bottomEnd = 3.dp)
                                )
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Lỗ trục Capstan kim loại dập chìm
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF06070A))
                                        .border(1.2.dp, Color(0xFF64748B), CircleShape)
                                )

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(
                                        text = specLineStr,
                                        color = EchoCyanSpec,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                    Box(modifier = Modifier.border(1.dp, Color(0xFFA855F7), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 0.5.dp)) {
                                        Text(
                                            text = if (specLineStr.contains("MHz", true)) "DSD" else "SQ",
                                            color = Color(0xFFD8B4FE),
                                            fontSize = 7.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Favorite",
                                        tint = if (isFavorite) EchoCrimsonRed else Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .size(13.dp)
                                            .clickable { onToggleFavorite() }
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF06070A))
                                        .border(1.2.dp, Color(0xFF64748B), CircleShape)
                                )
                            }
                        }
                    }

                    // Lớp phản quang chéo tổng thể qua mặt kính LCD ngoài cùng của máy DAP
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val screenGlare = Path().apply {
                            moveTo(w * 0.44f, 0f)
                            lineTo(w * 0.62f, 0f)
                            lineTo(w * 0.46f, h)
                            lineTo(w * 0.28f, h)
                            close()
                        }
                        drawPath(
                            path = screenGlare,
                            brush = Brush.linearGradient(
                                colors = listOf(Color.White.copy(alpha = 0.045f), Color.White.copy(alpha = 0.008f))
                            )
                        )
                    }
                }
            }

            // Phần cằm máy vát kim loại 3D khắc chìm SNOWSKY / ECHO
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 10.dp, end = 10.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "❄ SNOWSKY",
                    color = Color(0xFF526077),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "ECHO",
                    color = Color(0xFF526077),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.8.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // BÀN PHÍM CƠ HỌC PIANO-KEY VẬT LÝ CÓ CHIỀU SÂU 3D & BÓNG ĐỔ (PHÍA DƯỚI CÙNG)
        CassetteMechanicalPianoKeyDeck(
            isPlaying = isPlaying,
            playMode = playMode,
            onToggleShuffle = onToggleShuffle,
            onPrev = onPrev,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onToggleRepeat = onToggleRepeat
        )
    }
}

@Composable
fun CassetteCornerScrew(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(9.dp)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFCBD5E1), Color(0xFF475569), Color(0xFF1E293B))
                )
            )
            .border(1.dp, Color(0xFF090B10), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(1.2.dp)
                .background(Color(0xFF090B10))
        )
    }
}

/**
 * CỤM NÚT ẤN CƠ HỌC KIỂU ĐÀI CASSETTE THẬT CÓ BÓNG ĐỔ VÀ CẠNH VÁT 3D
 */
@Composable
fun CassetteMechanicalPianoKeyDeck(
    isPlaying: Boolean,
    playMode: PlayMode,
    onToggleShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeat: () -> Unit
) {
    val isShuffle = playMode == PlayMode.SHUFFLE
    val isRepeatOne = playMode == PlayMode.REPEAT_ONE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .shadow(14.dp, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF040507))
            .border(
                width = 1.5.dp,
                brush = Brush.verticalGradient(listOf(Color(0xFF1C2029), Color(0xFF3E4658))),
                shape = RoundedCornerShape(15.dp)
            )
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CassetteMechanicalKey(
            modifier = Modifier.weight(0.85f),
            label = "SHUF",
            isLatched = isShuffle,
            ledColor = ChampagneGold,
            onClick = onToggleShuffle
        ) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (isShuffle) ChampagneGold else ColdSilver,
                modifier = Modifier.size(18.dp)
            )
        }

        CassetteMechanicalKey(
            modifier = Modifier.weight(1f),
            label = "REW",
            isLatched = false,
            ledColor = null,
            onClick = onPrev
        ) {
            Icon(
                imageVector = Icons.Default.FastRewind,
                contentDescription = "Previous",
                tint = TextPure,
                modifier = Modifier.size(22.dp)
            )
        }

        CassetteMechanicalKey(
            modifier = Modifier.weight(1.45f),
            label = if (isPlaying) "PLAYING" else "PLAY",
            isLatched = isPlaying,
            isPrimaryGoldKey = true,
            ledColor = if (isPlaying) LedPlayGreen else EchoCrimsonRed,
            onClick = onPlayPause
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                tint = if (isPlaying) ChampagneGold else TextPure,
                modifier = Modifier.size(26.dp)
            )
        }

        CassetteMechanicalKey(
            modifier = Modifier.weight(1f),
            label = "FFWD",
            isLatched = false,
            ledColor = null,
            onClick = onNext
        ) {
            Icon(
                imageVector = Icons.Default.FastForward,
                contentDescription = "Next",
                tint = TextPure,
                modifier = Modifier.size(22.dp)
            )
        }

        CassetteMechanicalKey(
            modifier = Modifier.weight(0.85f),
            label = "RPT",
            isLatched = isRepeatOne,
            ledColor = ChampagneGold,
            onClick = onToggleRepeat
        ) {
            Icon(
                imageVector = if (isRepeatOne) Icons.Default.RepeatOne else Icons.Default.Repeat,
                contentDescription = "Repeat",
                tint = if (isRepeatOne) ChampagneGold else ColdSilver,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun CassetteMechanicalKey(
    modifier: Modifier = Modifier,
    label: String,
    isLatched: Boolean,
    isPrimaryGoldKey: Boolean = false,
    ledColor: Color?,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val isDepressed = isPressed || isLatched
    val topInsetDp by animateDpAsState(
        targetValue = if (isDepressed) 4.dp else 0.dp,
        animationSpec = tween(durationMillis = 90),
        label = "key_depress_offset"
    )
    val bottomLipDp by animateDpAsState(
        targetValue = if (isDepressed) 1.dp else 4.dp,
        animationSpec = tween(durationMillis = 90),
        label = "key_bottom_lip"
    )

    val topSurfaceColors = when {
        isDepressed && isPrimaryGoldKey -> listOf(Color(0xFF282318), Color(0xFF16130D))
        isDepressed -> listOf(Color(0xFF151820), Color(0xFF0E1016))
        isPrimaryGoldKey -> listOf(Color(0xFF3D3525), Color(0xFF262117), Color(0xFF17140E))
        else -> listOf(Color(0xFF323948), Color(0xFF1F242E), Color(0xFF13161D))
    }

    val borderStrokeColor = when {
        isPrimaryGoldKey && isLatched -> ChampagneGold
        isPrimaryGoldKey -> ChampagneGoldDim
        isLatched -> ChampagneGold.copy(alpha = 0.75f)
        else -> Color(0xFF434C61)
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(top = topInsetDp)
            .shadow(if (isDepressed) 1.dp else 6.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF090B0E))
    ) {
        // Mặt phím nổi phía trên (ạo cạnh vát dày 3D ở đáy phím)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomLipDp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.verticalGradient(topSurfaceColors))
                .border(1.dp, borderStrokeColor, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(vertical = 4.dp, horizontal = 4.dp)
        ) {
            if (!isDepressed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(0.88f)
                        .height(1.2.dp)
                        .background(Color.White.copy(alpha = 0.22f))
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (ledColor != null) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (isLatched) ledColor else Color(0xFF2A303C))
                        )
                    }
                    Text(
                        text = label,
                        color = if (isLatched) ChampagneGold else TextMuted,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                }

                iconContent()

                Column(verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(1.dp)
                            .background(Color.Black.copy(alpha = 0.65f))
                    )
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.12f))
                    )
                }
            }
        }
    }
}

/**
 * =============================================================================================
 * 2. MÀN HÌNH SETTINGS / AUDIO LAB
 * =============================================================================================
 */
@Composable
fun SignatureAudioLabScreen(
    pureDsdMode: Boolean,
    masterVol: Float,
    megaBass: Float,
    clearAudio: Boolean,
    dsee: Boolean,
    vinyl: Boolean,
    phaseInvert: Boolean,
    channelBalance: Float,
    crossfade: Boolean,
    tapeSpeed: Float,
    keepScreenOn: Boolean,
    sleepTimerRemainingSec: Int,
    eq10Bands: List<Float>,
    currentTrack: AudioTrack?,
    currentBitrate: Int,
    currentSampleRate: String,
    audioOutput: AudioOutputState,
    otaAutoCheck: Boolean,
    otaIsChecking: Boolean,
    onOtaAutoCheckToggle: (Boolean) -> Unit,
    onCheckOtaNow: () -> Unit,
    onBack: () -> Unit,
    onPureDsdToggle: (Boolean) -> Unit,
    onMasterVolChange: (Float) -> Unit,
    onBassChange: (Float) -> Unit,
    onClearAudioToggle: (Boolean) -> Unit,
    onDseeToggle: (Boolean) -> Unit,
    onVinylToggle: (Boolean) -> Unit,
    onPhaseInvertToggle: (Boolean) -> Unit,
    onBalanceChange: (Float) -> Unit,
    onBand10Change: (Int, Float) -> Unit,
    onResetEq: () -> Unit,
    onCrossfadeToggle: (Boolean) -> Unit,
    onTapeSpeedChange: (Float) -> Unit,
    onKeepScreenOnToggle: (Boolean) -> Unit,
    onSetSleepMinutes: (Int) -> Unit
) {
    val freqLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DapChassisBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PanelSurface)
                        .border(1.dp, BezelBorder, RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPure,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = "Audio Lab",
                        color = TextPure,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Sound Settings, DSP & OTA Update",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (pureDsdMode) ChampagneGold.copy(alpha = 0.15f) else PanelSurface)
                    .border(
                        width = 1.dp,
                        color = if (pureDsdMode) ChampagneGold else BezelBorder,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (pureDsdMode) "PURE" else "PROCESSED",
                    color = if (pureDsdMode) ChampagneGold else ColdSilver,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            AudioLabSectionCard(title = "SOUND MODE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Pure DSD Mode",
                            color = TextPure,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Bỏ qua toàn bộ DSP, nghe trung tính",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = pureDsdMode,
                        onCheckedChange = onPureDsdToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DapChassisBlack,
                            checkedTrackColor = ChampagneGold,
                            uncheckedThumbColor = ColdSilver,
                            uncheckedTrackColor = DapPanelDark
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = BezelBorder)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Signal Path Status", color = ColdSilver, fontSize = 12.sp)
                    Text(
                        text = if (pureDsdMode) "Pure Direct (1:1 L/R Matched)" else "DSP Processed (1:1 L/R Matched)",
                        color = if (pureDsdMode) ChampagneGold else TextPure,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            AudioLabSectionCard(title = "PROCESSING") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Master Output Gain", color = TextPure, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("${(masterVol * 100).roundToInt()}%", color = ChampagneGold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = masterVol.coerceIn(0f, 1f),
                        onValueChange = onMasterVolChange,
                        colors = SliderDefaults.colors(
                            thumbColor = ChampagneGold,
                            activeTrackColor = ChampagneGold,
                            inactiveTrackColor = BezelBorder
                        )
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Channel Balance (L - R)", color = TextPure, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val balText = when {
                                channelBalance < -0.03f -> "L +${(-channelBalance * 100).roundToInt()}%"
                                channelBalance > 0.03f -> "R +${(channelBalance * 100).roundToInt()}%"
                                else -> "Center (0.0 dB)"
                            }
                            Text(
                                text = balText,
                                color = if (abs(channelBalance) < 0.03f) LedPlayGreen else ChampagneGold,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (abs(channelBalance) >= 0.03f) {
                                Text(
                                    text = "Reset 0",
                                    color = ChampagneGold,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(DapPanelDark)
                                        .border(1.dp, ChampagneGoldDim, RoundedCornerShape(4.dp))
                                        .clickable { onBalanceChange(0f) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("L", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Slider(
                            value = channelBalance.coerceIn(-0.5f, 0.5f),
                            onValueChange = onBalanceChange,
                            valueRange = -0.5f..0.5f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = ColdSilver,
                                activeTrackColor = ChampagneGold,
                                inactiveTrackColor = BezelBorder
                            )
                        )
                        Text("R", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                Column(
                    modifier = Modifier.alpha(if (pureDsdMode) 0.38f else 1.0f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = BezelBorder)

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Mega Bass", color = TextPure, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("+${(megaBass * 10).roundToInt()} dB", color = ChampagneGold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = megaBass.coerceIn(0f, 1f),
                            onValueChange = { if (!pureDsdMode) onBassChange(it) },
                            enabled = !pureDsdMode,
                            colors = SliderDefaults.colors(
                                thumbColor = ChampagneGold,
                                activeTrackColor = ChampagneGold,
                                inactiveTrackColor = BezelBorder
                            )
                        )
                    }

                    AudioLabSwitchRow(
                        title = "Clear Audio",
                        subtitle = "Tối ưu độ tách bạch và không gian Stereo",
                        checked = clearAudio,
                        enabled = !pureDsdMode,
                        onCheckedChange = onClearAudioToggle
                    )

                    AudioLabSwitchRow(
                        title = "DSEE",
                        subtitle = "Khôi phục dải âm tần số cao",
                        checked = dsee,
                        enabled = !pureDsdMode,
                        onCheckedChange = onDseeToggle
                    )

                    AudioLabSwitchRow(
                        title = "Vinyl Processor",
                        subtitle = "Tái tạo chất âm ấm mộc của đĩa than",
                        checked = vinyl,
                        enabled = !pureDsdMode,
                        onCheckedChange = onVinylToggle
                    )

                    AudioLabSwitchRow(
                        title = "Phase Invert",
                        subtitle = "Đảo cực pha âm thanh 180°",
                        checked = phaseInvert,
                        enabled = !pureDsdMode,
                        onCheckedChange = onPhaseInvertToggle
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (pureDsdMode) 0.38f else 1.0f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(PanelSurface)
                    .border(1.dp, BezelBorder, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EQUALIZER (10-BAND)",
                        color = ChampagneGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp
                    )
                    TextButton(
                        onClick = { if (!pureDsdMode) onResetEq() },
                        enabled = !pureDsdMode,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Reset EQ", color = ColdSilver, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                freqLabels.forEachIndexed { idx, freq ->
                    val gainVal = eq10Bands.getOrElse(idx) { 0f }.coerceIn(-8f, 8f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${freq}Hz",
                            color = ColdSilver,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(46.dp)
                        )
                        Slider(
                            value = gainVal,
                            onValueChange = { if (!pureDsdMode) onBand10Change(idx, it) },
                            valueRange = -8f..8f,
                            enabled = !pureDsdMode,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = ChampagneGold,
                                activeTrackColor = ChampagneGold,
                                inactiveTrackColor = BezelBorder
                            )
                        )
                        Text(
                            text = "%+.1f".format(gainVal),
                            color = if (gainVal != 0f) ChampagneGold else TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(40.dp)
                        )
                    }
                }
            }

            AudioLabSectionCard(title = "OTHER & OTA UPDATE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Phiên bản: Walkman ${BuildConfig.VERSION_NAME}",
                            color = TextPure,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Nguồn OTA: github.com/${BuildConfig.GITHUB_REPO}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = onCheckOtaNow,
                        enabled = !otaIsChecking,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ChampagneGold,
                            contentColor = DapChassisBlack
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        if (otaIsChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = DapChassisBlack
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Đang kiểm tra...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Kiểm tra cập nhật", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                AudioLabSwitchRow(
                    title = "Tự động kiểm tra cập nhật",
                    subtitle = "Kiểm tra bản APK mới trên GitHub Releases khi mở ứng dụng",
                    checked = otaAutoCheck,
                    enabled = true,
                    onCheckedChange = onOtaAutoCheckToggle
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = BezelBorder)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sleep Timer", color = TextPure, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = if (sleepTimerRemainingSec > 0) "${sleepTimerRemainingSec / 60}m ${sleepTimerRemainingSec % 60}s" else "Off",
                        color = if (sleepTimerRemainingSec > 0) ChampagneGold else TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Off", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m").forEach { (mins, label) ->
                        val active = (mins == 0 && sleepTimerRemainingSec == 0) ||
                                (mins > 0 && sleepTimerRemainingSec in ((mins - 14) * 60)..(mins * 60))
                        OutlinedButton(
                            onClick = { onSetSleepMinutes(mins) },
                            modifier = Modifier.weight(1f).height(32.dp),
                            border = BorderStroke(1.dp, if (active) ChampagneGold else BezelBorder),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(label, fontSize = 11.sp, color = if (active) ChampagneGold else ColdSilver)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = BezelBorder)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Playback Speed", color = TextPure, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("${"%.2f".format(tapeSpeed)}x", color = ColdSilver, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.85f, 0.95f, 1.0f, 1.10f, 1.20f).forEach { spd ->
                        val selected = tapeSpeed == spd
                        OutlinedButton(
                            onClick = { onTapeSpeedChange(spd) },
                            modifier = Modifier.weight(1f).height(32.dp),
                            border = BorderStroke(1.dp, if (selected) ChampagneGold else BezelBorder),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("${spd}x", fontSize = 11.sp, color = if (selected) ChampagneGold else ColdSilver)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                AudioLabSwitchRow(
                    title = "Smooth Crossfade",
                    subtitle = "Chuyển bài mượt không ngắt quãng",
                    checked = crossfade,
                    enabled = true,
                    onCheckedChange = onCrossfadeToggle
                )

                Spacer(modifier = Modifier.height(8.dp))

                AudioLabSwitchRow(
                    title = "Keep Screen On",
                    subtitle = "Giữ sáng màn hình khi đang mở ứng dụng",
                    checked = keepScreenOn,
                    enabled = true,
                    onCheckedChange = onKeepScreenOnToggle
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = BezelBorder)
                Spacer(modifier = Modifier.height(12.dp))

                AudioLabInfoRow("Output Device", audioOutput.deviceName)
                AudioLabInfoRow("Audio Codec", audioOutput.codecEstimate)
                AudioLabInfoRow("Playing File", currentTrack?.title ?: "None")
                AudioLabInfoRow("Stream Format", "${currentTrack?.formatName ?: "PCM"} • $currentSampleRate • $currentBitrate kbps")
                AudioLabInfoRow("App Version", "Walkman ${BuildConfig.VERSION_NAME}")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun AudioLabSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PanelSurface)
            .border(1.dp, BezelBorder, RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Text(
            text = title,
            color = ChampagneGold,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp
        )
        Spacer(modifier = Modifier.height(14.dp))
        content()
    }
}

@Composable
fun AudioLabSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = TextPure, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = TextMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DapChassisBlack,
                checkedTrackColor = ChampagneGold,
                uncheckedThumbColor = ColdSilver,
                uncheckedTrackColor = DapPanelDark
            )
        )
    }
}

@Composable
fun AudioLabInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextMuted, fontSize = 12.sp)
        Text(
            text = value,
            color = ColdSilver,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

/**
 * =============================================================================================
 * 3. MÀN HÌNH THƯ VIỆN NHẠC (MUSIC LIBRARY)
 * =============================================================================================
 */
@Composable
fun SignatureLibraryScreen(
    tracks: List<AudioTrack>,
    favoriteTitles: Set<String>,
    currentIndex: Int,
    onBackToPlayer: () -> Unit,
    onAutoScan: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onClearSaved: () -> Unit,
    onSelectTrack: (AudioTrack) -> Unit
) {
    var subTab by remember { mutableStateOf(0) }
    var selectedFolderFilter by remember { mutableStateOf<String?>(null) }

    val foldersMap = remember(tracks) { tracks.groupBy { it.folderInfo } }

    val displayedTracks = remember(tracks, favoriteTitles, subTab, selectedFolderFilter) {
        when (subTab) {
            1 -> tracks.filter { favoriteTitles.contains(it.title) }
            2 -> if (selectedFolderFilter != null) tracks.filter { it.folderInfo == selectedFolderFilter } else emptyList()
            else -> tracks
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DapChassisBlack)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = onBackToPlayer,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PanelSurface)
                        .border(1.dp, BezelBorder, RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPure,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text("Music Library", color = TextPure, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("${tracks.size} tracks", color = TextMuted, fontSize = 11.sp)
                }
            }

            if (tracks.isNotEmpty()) {
                TextButton(onClick = onClearSaved) {
                    Text("Clear", color = TextMuted, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onAutoScan,
                modifier = Modifier.weight(1f).height(36.dp),
                border = BorderStroke(1.dp, ChampagneGoldDim),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Auto Scan", fontSize = 11.sp, color = ChampagneGold)
            }
            OutlinedButton(
                onClick = onPickFolder,
                modifier = Modifier.weight(1f).height(36.dp),
                border = BorderStroke(1.dp, BezelBorder),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Add Folder", fontSize = 11.sp, color = TextPure)
            }
            OutlinedButton(
                onClick = onPickFiles,
                modifier = Modifier.weight(1f).height(36.dp),
                border = BorderStroke(1.dp, BezelBorder),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("+ Files", fontSize = 11.sp, color = TextPure)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tabs = listOf(
                "All (${tracks.size})",
                "Favorites (${tracks.count { favoriteTitles.contains(it.title) }})",
                "Folders (${foldersMap.size})"
            )
            tabs.forEachIndexed { idx, label ->
                val active = subTab == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) ChampagneGold else PanelSurface)
                        .clickable {
                            subTab = idx
                            selectedFolderFilter = null
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (active) DapChassisBlack else ColdSilver
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (subTab == 2 && selectedFolderFilter == null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val folderEntries = foldersMap.entries.toList()
                itemsIndexed(folderEntries) { _, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PanelSurface)
                            .border(1.dp, BezelBorder, RoundedCornerShape(12.dp))
                            .clickable { selectedFolderFilter = entry.key }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = ChampagneGold, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(entry.key, color = TextPure, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("${entry.value.size} tracks", color = TextMuted, fontSize = 11.sp)
                            }
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
                    }
                }
            }
        } else {
            if (subTab == 2 && selectedFolderFilter != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "← Folders / $selectedFolderFilter",
                        color = ChampagneGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { selectedFolderFilter = null }
                    )
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(displayedTracks) { _, track ->
                    val isFav = favoriteTitles.contains(track.title)
                    val isPlayingTrack = tracks.getOrNull(currentIndex)?.id == track.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isPlayingTrack) ElevatedMetallic else PanelSurface)
                            .border(
                                width = 1.dp,
                                color = if (isPlayingTrack) ChampagneGoldDim else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectTrack(track) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = track.title,
                                fontWeight = if (isPlayingTrack) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isPlayingTrack) ChampagneGold else TextPure,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val cleanArtist = track.artist.takeIf { !it.contains("<unknown>", true) } ?: track.folderInfo
                            Text(
                                text = "$cleanArtist • ${track.formatName}",
                                color = TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onToggleFavorite(track.title) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (isFav) ChampagneGold else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
