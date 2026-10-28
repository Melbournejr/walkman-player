package com.sony.walkmanpro

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.roundToInt
import kotlin.math.sin

// Bảng màu Sony Walkman Signature Series (Black + Gold + Brushed Silver)
private val BgDeepBlack = Color(0xFF090A0D)
private val SurfaceDark = Color(0xFF12141A)
private val SurfaceCard = Color(0xFF171A22)
private val BorderSubtle = Color(0xFF262A36)
private val SignatureGold = Color(0xFFD4AF37)
private val SignatureGoldLight = Color(0xFFF3E5AB)
private val BrushedSilver = Color(0xFF94A3B8)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF64748B)

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

enum class PlayMode(val label: String) {
    SEQUENTIAL("Sequential"),
    REPEAT_ONE("Repeat One"),
    SHUFFLE("Shuffle")
}

enum class AppScreen {
    NOW_PLAYING,
    LIBRARY,
    AUDIO_LAB
}

data class AudioOutputState(
    val deviceType: String = "Loa thiết bị",
    val isBluetooth: Boolean = false,
    val deviceName: String = "Built-in Speaker",
    val codecEstimate: String = "Stereo"
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
                    background = BgDeepBlack,
                    surface = SurfaceDark,
                    primary = SignatureGold,
                    onPrimary = Color.Black,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                )
            ) {
                SignatureWalkmanApp(
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
        sharedDspProcessor.channelBalance = prefs.getFloat("dsp_balance", 0f)
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
        sharedDspProcessor.channelBalance = bal.coerceIn(-1f, 1f)
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("dsp_balance", sharedDspProcessor.channelBalance).apply()
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
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
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

    private fun queryRealBluetoothCodecAndBattery(deviceNameHint: String): Pair<String?, Int> {
        var realCodecName: String? = null
        var batteryPercent = -1
        try {
            ensureBluetoothA2dpProxy(this)
            val proxy = bluetoothA2dpProxy
            val devices = proxy?.connectedDevices
            val activeDevice = if (!devices.isNullOrEmpty()) {
                devices.firstOrNull { it.name?.equals(deviceNameHint, ignoreCase = true) == true } ?: devices[0]
            } else {
                android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.firstOrNull {
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
                                    4 -> "Sony LDAC™ Hi-Res"
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
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (dev in devices) {
                when (dev.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                        val rawName = dev.productName?.toString() ?: "Bluetooth Audio"
                        val (realCodec, batteryLevel) = queryRealBluetoothCodecAndBattery(rawName)
                        val displayWithBattery = if (batteryLevel in 0..100) {
                            "$rawName ($batteryLevel%)"
                        } else {
                            rawName
                        }
                        val codecLabel = realCodec ?: when {
                            rawName.contains("LDAC", true) || rawName.contains("1000X", true) -> "Sony LDAC™"
                            rawName.contains("Noble", true) || rawName.contains("Falcon", true) || rawName.contains("aptx", true) -> "Qualcomm® aptX™ Adaptive"
                            else -> "Bluetooth A2DP"
                        }
                        return AudioOutputState("Bluetooth", true, displayWithBattery, codecLabel)
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                        return AudioOutputState("Wired 3.5mm/4.4mm", false, "Balanced / Single-Ended", "Direct PCM/DSD")
                    }
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> {
                        return AudioOutputState("USB-DAC", false, dev.productName?.toString() ?: "USB HiFi DAC", "Bit-Perfect Output")
                    }
                }
            }
        }
        return AudioOutputState("Speaker", false, "Internal Stereo Speaker", "PCM Output")
    }

    private fun inspectTrackSpecsSafe(context: Context, track: AudioTrack): Pair<Int, String> {
        if (isDsdTrack(track)) {
            return parseDsdSpecsDirectly(context, track.uri)
        }

        val lowerTitle = track.title.lowercase()
        val lowerFmt = track.formatName.lowercase()
        var bitrate = 320
        var sampleRate = "44.1 kHz • 16-bit"
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, track.uri)
            val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            if (!brStr.isNullOrBlank()) {
                val bps = brStr.toIntOrNull() ?: 320000
                bitrate = (bps / 1000).coerceIn(64, 18432)
            }
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            sampleRate = when {
                lowerTitle.contains("384k") || mime.contains("x-flac") -> "384.0 kHz • 32-bit"
                lowerTitle.endsWith(".flac") || lowerFmt == "flac" -> "96.0 kHz • 24-bit"
                lowerTitle.endsWith(".mqa") || lowerFmt == "mqa" -> "352.8 kHz • MQA"
                lowerTitle.endsWith(".wav") -> "96.0 kHz • 24-bit"
                else -> "44.1 kHz • 16-bit"
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
                    info.dsdSampleRate >= 11289600 -> "DSD256 • 11.2 MHz"
                    info.dsdSampleRate >= 5644800 -> "DSD128 • 5.6 MHz"
                    else -> "DSD64 • 2.8 MHz"
                }
                return Pair(kbps, dsdName)
            }
        } catch (_: Throwable) {}
        return Pair(5644, "DSD64 • 2.8 MHz")
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
                        val artist = if (artistCol >= 0) c.getString(artistCol) ?: "Unknown Artist" else "Unknown Artist"
                        val dur = if (durCol >= 0) c.getLong(durCol) else 0L
                        val sz = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                        val relPath = if (relCol >= 0) c.getString(relCol) ?: "" else ""
                        val folderName = relPath.trimEnd('/').substringAfterLast('/').ifEmpty { "Music" }

                        val key = "${title.trim().lowercase()}_$sz"
                        if (seen.contains(key)) continue

                        if (isExcluded("$title $name $relPath".lowercase(), name) || (dur in 1..14999)) {
                            skipped++
                        } else {
                            seen.add(key)
                            val uri = ContentUris.withAppendedId(volumeUri, id)
                            valid.add(AudioTrack(id, title, artist, dur, uri, folderName, sz, name.substringAfterLast(".", "AUDIO").uppercase()))
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
            added.add(AudioTrack(System.currentTimeMillis() + added.size, fn.substringBeforeLast("."), "Direct Audio", 0L, uri, "Selected Files", sz, fn.substringAfterLast(".", "DSD/PCM").uppercase()))
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
                            added.add(AudioTrack(System.currentTimeMillis() + added.size, n.substringBeforeLast("."), dir.name ?: "Unknown Artist", 0L, f.uri, dir.name ?: "Folder", f.length(), n.substringAfterLast(".", "PCM").uppercase()))
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
                        title = obj.optString("title", "Track"),
                        artist = obj.optString("artist", "Unknown Artist"),
                        duration = obj.optLong("duration", 0L),
                        uri = Uri.parse(obj.getString("uri")),
                        folderInfo = obj.optString("folderInfo", "Music"),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        formatName = obj.optString("formatName", "AUDIO")
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
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

@Composable
fun SignatureWalkmanApp(
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
    var durMs by remember { mutableStateOf(1) }

    // Trạng thái DSP & Audio Lab
    val dspPrefs = context.getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
    var masterVol by remember { mutableStateOf(dspPrefs.getFloat("dsp_master_vol", 0.75f)) }
    var pureDsdMode by remember { mutableStateOf(dspPrefs.getBoolean("dsp_pure_dsd_mode", false)) }
    var megaBass by remember { mutableStateOf(dspPrefs.getFloat("dsp_bass", 0.45f)) }
    var vinylProcessor by remember { mutableStateOf(dspPrefs.getBoolean("dsp_vinyl", true)) }
    var clearAudioPlus by remember { mutableStateOf(dspPrefs.getBoolean("dsp_clear_audio", true)) }
    var dseeUltimate by remember { mutableStateOf(dspPrefs.getBoolean("dsp_dsee", true)) }
    var channelBalance by remember { mutableStateOf(dspPrefs.getFloat("dsp_balance", 0f)) }
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
    var currentBitrate by remember { mutableStateOf(320) }
    var currentSampleRate by remember { mutableStateOf("44.1 kHz • 16-bit") }

    val notifPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    // Xử lý nút Back vật lý của điện thoại để quay về Now Playing
    BackHandler(enabled = currentScreen != AppScreen.NOW_PLAYING) {
        currentScreen = AppScreen.NOW_PLAYING
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
    }

    LaunchedEffect(isPlaying) {
        var tickCounter = 0
        while (true) {
            isPlaying = getIsPlaying()
            if (isPlaying) {
                posMs = getPosition()
                durMs = getDuration().coerceAtLeast(1)
                tickCounter++
                if (tickCounter % 5 == 0) {
                    playlist.getOrNull(currentIndex)?.let { t ->
                        PlaylistStorage.saveTrackPosition(context, t.title, posMs, currentIndex)
                    }
                    audioOutput = getAudioOutputState()
                    updateSystemMediaNotification(true)
                }
            }
            delay(200)
        }
    }

    val currentTrack = playlist.getOrNull(currentIndex)

    // Chuyển màn hình mượt mà (AnimatedContent)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeepBlack)
            .systemBarsPadding()
    ) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                fadeIn(animationSpec = tween(260)) togetherWith fadeOut(animationSpec = tween(220))
            },
            label = "screen_transition"
        ) { screen ->
            when (screen) {
                AppScreen.NOW_PLAYING -> SignatureNowPlayingScreen(
                    track = currentTrack,
                    formatSummary = if (currentSampleRate.contains("DSD", true)) {
                        currentSampleRate
                    } else {
                        "${currentTrack?.formatName ?: "PCM"} • $currentSampleRate"
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
                    onOpenLibrary = { currentScreen = AppScreen.LIBRARY },
                    onOpenAudioLab = {
                        audioOutput = getAudioOutputState()
                        currentScreen = AppScreen.AUDIO_LAB
                    }
                )

                AppScreen.LIBRARY -> SignatureLibraryScreen(
                    context = context,
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
                    onBalanceChange = {
                        channelBalance = it
                        onSetChannelBalance(it)
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
    }
}

/**
 * 1. MÀN HÌNH NOW PLAYING (TỐI GIẢN, CAO CẤP, TẬP TRUNG VÀO TRẢI NGHIỆM NGHE)
 */
@Composable
fun SignatureNowPlayingScreen(
    track: AudioTrack?,
    formatSummary: String,
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
    onOpenLibrary: () -> Unit,
    onOpenAudioLab: () -> Unit
) {
    var showAnalogVu by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "signature_motion")
    val discAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing)),
        label = "disc_rotation"
    )
    val vuPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "vu_motion"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Thanh điều hướng trên cùng tối giản: Thư viện (Trái) & Audio Lab / Settings (Phải)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenLibrary,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(SurfaceDark)
            ) {
                Icon(Icons.Default.QueueMusic, contentDescription = "Library", tint = BrushedSilver, modifier = Modifier.size(20.dp))
            }

            // Nút mở Audio Lab / Settings
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
                    .clickable { onOpenAudioLab() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Tune, contentDescription = "Audio Lab", tint = SignatureGold, modifier = Modifier.size(16.dp))
                Text(
                    text = "Audio Lab",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                )
            }
        }

        // Trung tâm: Ảnh bìa Album lớn / VU Meter tinh tế (Chạm để chuyển đổi)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF1C1F2B), SurfaceDark, BgDeepBlack)
                    )
                )
                .border(1.dp, SignatureGold.copy(alpha = 0.28f), RoundedCornerShape(28.dp))
                .clickable { showAnalogVu = !showAnalogVu },
            contentAlignment = Alignment.Center
        ) {
            if (showAnalogVu) {
                // Chế độ VU Meter đôi tối giản chuẩn Signature
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MinimalistVuMeter(
                            channelLabel = "L",
                            needleAngle = if (isPlaying) (sin(vuPhase) * 28f) else -34f
                        )
                        MinimalistVuMeter(
                            channelLabel = "R",
                            needleAngle = if (isPlaying) (sin(vuPhase + 0.9f) * 28f) else -34f
                        )
                    }
                }
            } else {
                // Chế độ Artwork Đĩa Vinyl Vàng Kim tinh tế
                Canvas(modifier = Modifier.fillMaxSize(0.76f)) {
                    val radius = size.minDimension / 2f
                    // Vành đĩa ngoài
                    drawCircle(
                        color = Color(0xFF0D0F14),
                        radius = radius
                    )
                    drawCircle(
                        color = SignatureGold.copy(alpha = 0.35f),
                        radius = radius,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    // Các rãnh đĩa đồng tâm mảnh
                    drawCircle(
                        color = Color(0xFF1E2330),
                        radius = radius * 0.82f,
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFF1E2330),
                        radius = radius * 0.64f,
                        style = Stroke(width = 1.dp.toPx())
                    )

                    rotate(if (isPlaying) discAngle else 0f) {
                        // Ánh kim quay nhẹ trên bề mặt đĩa
                        drawLine(
                            color = SignatureGold.copy(alpha = 0.18f),
                            start = Offset(center.x, center.y - radius * 0.88f),
                            end = Offset(center.x, center.y + radius * 0.88f),
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // Nhãn tâm đĩa màu Vàng Kim Signature
                    drawCircle(
                        color = SignatureGold,
                        radius = radius * 0.26f
                    )
                    drawCircle(
                        color = BgDeepBlack,
                        radius = radius * 0.06f
                    )
                }
            }
        }

        // Thông tin bài hát: Tên bài hát, Nghệ sĩ • Album & Dòng Format nhỏ gọn
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = track?.title ?: "Chưa chọn bài hát",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            val artistText = track?.artist?.takeIf { !it.contains("<unknown>", true) } ?: "Unknown Artist"
            val albumText = track?.folderInfo ?: "Album"
            Text(
                text = "$artistText • $albumText",
                color = BrushedSilver,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Dòng thông tin format nhỏ gọn duy nhất (ví dụ: DSD128 • 5.6 MHz)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceDark)
                    .border(1.dp, SignatureGold.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    text = formatSummary,
                    color = SignatureGoldLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp
                )
            }
        }

        // Thanh tiến trình (Seekbar) + Thời gian hiện tại / Tổng
        val safeMaxDur = durMs.toFloat().coerceAtLeast(1f)
        val safeCurrPos = posMs.toFloat().coerceIn(0f, safeMaxDur)

        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = safeCurrPos,
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..safeMaxDur,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = SignatureGold,
                    activeTrackColor = SignatureGold,
                    inactiveTrackColor = BorderSubtle
                )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTimeMs(posMs),
                    color = BrushedSilver,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = formatTimeMs(durMs),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Cụm nút điều khiển chính: Shuffle – Previous – Play/Pause – Next – Repeat
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isShuffleActive = playMode == PlayMode.SHUFFLE
            val isRepeatActive = playMode == PlayMode.REPEAT_ONE

            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (isShuffleActive) SignatureGold else TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(
                onClick = onPrev,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = TextPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Nút Play / Pause trung tâm mạ vàng Signature
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(SignatureGoldLight, SignatureGold, Color(0xFFB38728))
                        )
                    )
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = BgDeepBlack,
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(
                onClick = onNext,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = TextPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }

            IconButton(onClick = onToggleRepeat) {
                Icon(
                    imageVector = if (isRepeatActive) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = "Repeat",
                    tint = if (isRepeatActive) SignatureGold else TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun MinimalistVuMeter(channelLabel: String, needleAngle: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(width = 115.dp, height = 74.dp)) {
            drawArc(
                color = SignatureGold.copy(alpha = 0.28f),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            rotate(needleAngle, pivot = Offset(size.width / 2f, size.height * 0.92f)) {
                drawLine(
                    color = SignatureGold,
                    start = Offset(size.width / 2f, size.height * 0.92f),
                    end = Offset(size.width / 2f, 8.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            drawCircle(
                color = BrushedSilver,
                radius = 3.dp.toPx(),
                center = Offset(size.width / 2f, size.height * 0.92f)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "CH $channelLabel",
            color = TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
    }
}

/**
 * 2. MÀN HÌNH SETTINGS / AUDIO LAB (4 SECTION RÕ RÀNG, KẾT NỐI TRỰC TIẾP BIẾN DSP & PURE DSD MODE)
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
            .background(BgDeepBlack)
    ) {
        // Header cố định
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
                        .clip(CircleShape)
                        .background(SurfaceDark)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text(
                        text = "Audio Lab",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Signature Acoustic Engine",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            // Badge trạng thái Pure / Processed
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (pureDsdMode) SignatureGold.copy(alpha = 0.16f) else SurfaceDark)
                    .border(
                        width = 1.dp,
                        color = if (pureDsdMode) SignatureGold else BorderSubtle,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (pureDsdMode) "PURE DIRECT" else "PROCESSED",
                    color = if (pureDsdMode) SignatureGold else BrushedSilver,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Nội dung cuộn 4 Section
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // =========================================================================
            // SECTION 1: SOUND MODE
            // =========================================================================
            AudioLabSectionCard(title = "SOUND MODE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Pure DSD Mode",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Bỏ qua toàn bộ DSP, nghe trung tính",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = pureDsdMode,
                        onCheckedChange = onPureDsdToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BgDeepBlack,
                            checkedTrackColor = SignatureGold,
                            uncheckedThumbColor = BrushedSilver,
                            uncheckedTrackColor = SurfaceDark
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = BorderSubtle)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Trạng thái đường truyền",
                        color = BrushedSilver,
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (pureDsdMode) "Pure (Bit-Perfect Bypass)" else "Processed (Sony DSP Active)",
                        color = if (pureDsdMode) SignatureGoldLight else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // =========================================================================
            // SECTION 2: PROCESSING
            // =========================================================================
            AudioLabSectionCard(title = "PROCESSING") {
                // Master Pre-Amp & Channel Balance luôn hoạt động kể cả trong Pure DSD Mode
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Master Output Level", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("${(masterVol * 100).roundToInt()}%", color = SignatureGold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = masterVol.coerceIn(0f, 1f),
                        onValueChange = onMasterVolChange,
                        colors = SliderDefaults.colors(
                            thumbColor = SignatureGold,
                            activeTrackColor = SignatureGold,
                            inactiveTrackColor = BorderSubtle
                        )
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Channel Balance (L - R)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Channel Balance", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        val balLabel = when {
                            channelBalance < -0.02f -> "L +${(-channelBalance * 100).roundToInt()}%"
                            channelBalance > 0.02f -> "R +${(channelBalance * 100).roundToInt()}%"
                            else -> "Center"
                        }
                        Text(
                            text = balLabel,
                            color = BrushedSilver,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable { onBalanceChange(0f) }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("L", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Slider(
                            value = channelBalance.coerceIn(-0.5f, 0.5f),
                            onValueChange = onBalanceChange,
                            valueRange = -0.5f..0.5f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = BrushedSilver,
                                activeTrackColor = SignatureGold,
                                inactiveTrackColor = BorderSubtle
                            )
                        )
                        Text("R", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                // Các thành phần DSP bị bỏ qua khi bật Pure DSD Mode
                Column(
                    modifier = Modifier.alpha(if (pureDsdMode) 0.40f else 1.0f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = BorderSubtle)

                    // Mega Bass Slider
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Mega Bass", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("+${(megaBass * 10).roundToInt()} dB", color = SignatureGold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = megaBass.coerceIn(0f, 1f),
                            onValueChange = { if (!pureDsdMode) onBassChange(it) },
                            enabled = !pureDsdMode,
                            colors = SliderDefaults.colors(
                                thumbColor = SignatureGold,
                                activeTrackColor = SignatureGold,
                                inactiveTrackColor = BorderSubtle
                            )
                        )
                    }

                    AudioLabSwitchRow(
                        title = "ClearAudio+",
                        subtitle = "Mở rộng không gian âm trường 3D Stereo",
                        checked = clearAudio,
                        enabled = !pureDsdMode,
                        onCheckedChange = onClearAudioToggle
                    )

                    AudioLabSwitchRow(
                        title = "DSEE HX",
                        subtitle = "Tái tạo hài âm dải tần số cao",
                        checked = dsee,
                        enabled = !pureDsdMode,
                        onCheckedChange = onDseeToggle
                    )

                    AudioLabSwitchRow(
                        title = "Vinyl Processor",
                        subtitle = "Mô phỏng cộng hưởng ấm áp của kim đĩa than",
                        checked = vinyl,
                        enabled = !pureDsdMode,
                        onCheckedChange = onVinylToggle
                    )

                    AudioLabSwitchRow(
                        title = "Phase Invert (180°)",
                        subtitle = "Đảo cực pha tín hiệu đầu ra cho DAC rời",
                        checked = phaseInvert,
                        enabled = !pureDsdMode,
                        onCheckedChange = onPhaseInvertToggle
                    )
                }
            }

            // =========================================================================
            // SECTION 3: EQUALIZER (10-BAND & RESET)
            // =========================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (pureDsdMode) 0.40f else 1.0f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp))
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EQUALIZER (10-BAND)",
                        color = SignatureGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    TextButton(
                        onClick = { if (!pureDsdMode) onResetEq() },
                        enabled = !pureDsdMode,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Reset EQ", color = BrushedSilver, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                            color = BrushedSilver,
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
                                thumbColor = SignatureGold,
                                activeTrackColor = SignatureGold,
                                inactiveTrackColor = BorderSubtle
                            )
                        )
                        Text(
                            text = "%+.1f".format(gainVal),
                            color = if (gainVal != 0f) SignatureGoldLight else TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(40.dp)
                        )
                    }
                }
            }

            // =========================================================================
            // SECTION 4: OTHER (PLAYBACK TOOLS, FILE SPECS & VERSION INFO)
            // =========================================================================
            AudioLabSectionCard(title = "OTHER") {
                // Hẹn giờ ngủ (Sleep Timer)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sleep Timer (Fade-Out)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = if (sleepTimerRemainingSec > 0) "${sleepTimerRemainingSec / 60}m ${sleepTimerRemainingSec % 60}s" else "Off",
                        color = if (sleepTimerRemainingSec > 0) SignatureGold else TextSecondary,
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
                            border = BorderStroke(1.dp, if (active) SignatureGold else BorderSubtle),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(label, fontSize = 11.sp, color = if (active) SignatureGold else BrushedSilver)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                // Tốc độ phát (Playback Speed)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Playback Speed", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("${"%.2f".format(tapeSpeed)}x", color = BrushedSilver, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.85f, 0.95f, 1.0f, 1.10f, 1.20f).forEach { spd ->
                        val selected = tapeSpeed == spd
                        OutlinedButton(
                            onClick = { onTapeSpeedChange(spd) },
                            modifier = Modifier.weight(1f).height(32.dp),
                            border = BorderStroke(1.dp, if (selected) SignatureGold else BorderSubtle),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("${spd}x", fontSize = 11.sp, color = if (selected) SignatureGold else BrushedSilver)
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
                    subtitle = "Giữ sáng màn hình khi đặt trên bàn làm việc",
                    checked = keepScreenOn,
                    enabled = true,
                    onCheckedChange = onKeepScreenOnToggle
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                // Thông tin thiết bị đầu ra & File đang phát
                AudioLabInfoRow("Output Device", audioOutput.deviceName)
                AudioLabInfoRow("Audio Codec", audioOutput.codecEstimate)
                AudioLabInfoRow("Current Track", currentTrack?.title ?: "None")
                AudioLabInfoRow("Stream Specs", "${currentTrack?.formatName ?: "PCM"} • $currentSampleRate • $currentBitrate kbps")
                AudioLabInfoRow("App Version", "Walkman Signature Edition • v10.0 (128-Tap FIR)")
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
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text(
            text = title,
            color = SignatureGold,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
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
            Text(text = title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BgDeepBlack,
                checkedTrackColor = SignatureGold,
                uncheckedThumbColor = BrushedSilver,
                uncheckedTrackColor = SurfaceDark
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
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = BrushedSilver,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

/**
 * 3. MÀN HÌNH THƯ VIỆN NHẠC (ĐỒNG BỘ PHONG CÁCH TỐI GIẢN SIGNATURE)
 */
@Composable
fun SignatureLibraryScreen(
    context: Context,
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
            .background(BgDeepBlack)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header Thư viện
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
                        .clip(CircleShape)
                        .background(SurfaceDark)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text("Music Library", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("${tracks.size} tracks available", color = TextSecondary, fontSize = 11.sp)
                }
            }

            if (tracks.isNotEmpty()) {
                TextButton(onClick = onClearSaved) {
                    Text("Clear", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Các nút nạp nhạc gọn gàng
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onAutoScan,
                modifier = Modifier.weight(1f).height(36.dp),
                border = BorderStroke(1.dp, SignatureGold.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Auto Scan", fontSize = 11.sp, color = SignatureGoldLight)
            }
            OutlinedButton(
                onClick = onPickFolder,
                modifier = Modifier.weight(1f).height(36.dp),
                border = BorderStroke(1.dp, BorderSubtle),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Add Folder", fontSize = 11.sp, color = TextPrimary)
            }
            OutlinedButton(
                onClick = onPickFiles,
                modifier = Modifier.weight(1f).height(36.dp),
                border = BorderStroke(1.dp, BorderSubtle),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("+ Files", fontSize = 11.sp, color = TextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Phân loại: All | Favorites | Folders
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
                        .background(if (active) SignatureGold else SurfaceCard)
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
                        color = if (active) BgDeepBlack else BrushedSilver
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
                            .background(SurfaceCard)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                            .clickable { selectedFolderFilter = entry.key }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = SignatureGold, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(entry.key, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("${entry.value.size} tracks", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
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
                        color = SignatureGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { selectedFolderFilter = null }
                    )
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(displayedTracks) { index, track ->
                    val isFav = favoriteTitles.contains(track.title)
                    val isPlayingTrack = tracks.getOrNull(currentIndex)?.id == track.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isPlayingTrack) Color(0xFF1E1B12) else SurfaceCard)
                            .border(
                                width = 1.dp,
                                color = if (isPlayingTrack) SignatureGold.copy(alpha = 0.6f) else Color.Transparent,
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
                                color = if (isPlayingTrack) SignatureGoldLight else TextPrimary,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val cleanArtist = track.artist.takeIf { !it.contains("<unknown>", true) } ?: track.folderInfo
                            Text(
                                text = "$cleanArtist • ${track.formatName}",
                                color = TextSecondary,
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
                                tint = if (isFav) SignatureGold else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
