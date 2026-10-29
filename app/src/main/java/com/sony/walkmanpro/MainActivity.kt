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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

// =========================================================================================
// BẢNG MÀU VẬT LIỆU SONY WALKMAN SIGNATURE SERIES (WM1ZM2 CHAMPAGNE GOLD & WM1AM2 COLD SILVER)
// =========================================================================================
private val WalkmanBlack = Color(0xFF08090C)
private val ChassisDark = Color(0xFF101217)
private val PanelSurface = Color(0xFF161920)
private val ElevatedMetallic = Color(0xFF202530)
private val BezelBorder = Color(0xFF2B313E)
private val ChampagneGold = Color(0xFFC8B588)      // Vàng Champagne nhạt chuẩn WM1ZM2
private val ChampagneGoldDim = Color(0xFF827350)   // Vàng đồng trầm cho viền khắc CNC
private val ColdSilver = Color(0xFFA6B0BE)         // Bạc lạnh chuẩn nhôm phay WM1AM2
private val TextPure = Color(0xFFF1F5F9)
private val TextMuted = Color(0xFF64748B)
private val LedPlayGreen = Color(0xFF10B981)       // Đèn LED báo PLAY
private val LedRecAmber = Color(0xFFF59E0B)        // Đèn LED báo HI-RES / STANDBY

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

enum class PlayerViewMode(val title: String) {
    CASSETTE("CASSETTE"),
    COVER_ART("COVER"),
    MINIMAL("MINIMAL")
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
    val codecEstimate: String = "PCM"
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
                    background = WalkmanBlack,
                    surface = ChassisDark,
                    primary = ChampagneGold,
                    onPrimary = WalkmanBlack,
                    onBackground = TextPure,
                    onSurface = TextPure
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

        // Sửa triệt để lỗi lệch kênh Trái/Phải do sai số lưu trữ: nếu gần 0 thì ép cứng về 0.0f
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
        // Tự động hút về đúng 0.0f khi người dùng kéo gần tâm (Dead-zone +/- 0.03f)
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
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (dev in devices) {
                when (dev.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                        val rawName = dev.productName?.toString() ?: "Bluetooth Audio"
                        val (realCodec, batteryLevel) = queryRealBluetoothCodecAndBattery(rawName)
                        val displayWithBattery = if (batteryLevel in 0..100) {
                            "$rawName • $batteryLevel%"
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
                        return AudioOutputState("4.4mm / 3.5mm", false, "Balanced / Headphones", "Direct Output")
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
        var sampleRate = "44.1 kHz / 16-bit"
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
                lowerTitle.contains("384k") || mime.contains("x-flac") -> "384.0 kHz / 32-bit"
                lowerTitle.endsWith(".flac") || lowerFmt == "flac" -> "96.0 kHz / 24-bit"
                lowerTitle.endsWith(".mqa") || lowerFmt == "mqa" -> "352.8 kHz / MQA"
                lowerTitle.endsWith(".wav") -> "96.0 kHz / 24-bit"
                else -> "44.1 kHz / 16-bit"
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
    var currentBitrate by remember { mutableStateOf(320) }
    var currentSampleRate by remember { mutableStateOf("44.1 kHz / 16-bit") }

    val notifPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

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

    // Vòng lặp cập nhật vị trí với tần suất cao (80ms) để cuộn băng và thanh tiến trình đồng bộ chính xác với nhạc
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
            delay(80)
        }
    }

    val currentTrack = playlist.getOrNull(currentIndex)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WalkmanBlack)
            .systemBarsPadding()
    ) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                fadeIn(animationSpec = tween(240)) togetherWith fadeOut(animationSpec = tween(180))
            },
            label = "walkman_screen_transition"
        ) { screen ->
            when (screen) {
                AppScreen.NOW_PLAYING -> WalkmanSignatureNowPlayingScreen(
                    track = currentTrack,
                    formatBadgeText = if (currentSampleRate.contains("DSD", true)) {
                        currentSampleRate
                    } else {
                        "${currentTrack?.formatName ?: "PCM"} • $currentSampleRate"
                    },
                    bitrateKbps = currentBitrate,
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
    }
}

/**
 * =============================================================================================
 * 1. MÀN HÌNH NOW PLAYING (3 CHẾ ĐỘ XEM, QUÁN TÍNH VẬT LÝ BĂNG TỪ & NÚT BẤM ĐÀI CASSETTE THẬT)
 * =============================================================================================
 */
@Composable
fun WalkmanSignatureNowPlayingScreen(
    track: AudioTrack?,
    formatBadgeText: String,
    bitrateKbps: Int,
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
    var viewMode by remember { mutableStateOf(PlayerViewMode.CASSETTE) }

    val safeMaxDur = durMs.toFloat().coerceAtLeast(1f)
    val safeCurrPos = posMs.toFloat().coerceIn(0f, safeMaxDur)
    val progressFraction = (safeCurrPos / safeMaxDur).coerceIn(0f, 1f)

    // Mô phỏng vật lý cuộn băng:
    // 1. Có quán tính (Inertia) khi bắt đầu quay (Play) và khi hãm dừng (Pause).
    // 2. Khi người dùng Seek, góc quay lập tức nhảy tương ứng theo quãng đường băng đã tua.
    // 3. Tốc độ góc của mỗi cuộn tỷ lệ nghịch với bán kính cuộn băng hiện tại (cuộn ít băng quay nhanh hơn).
    val targetAngularVelocity = if (isPlaying) 68f else 0f
    val smoothVelocity by animateFloatAsState(
        targetValue = targetAngularVelocity,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "reel_inertia_velocity"
    )

    var baseSpinAngle by remember { mutableStateOf(0f) }
    var lastSyncedPosMs by remember { mutableStateOf(posMs) }

    // Khi Seek (độ lệch thời gian đột ngột > 800ms), cập nhật góc nhảy ngay lập tức
    LaunchedEffect(posMs) {
        val diffMs = posMs - lastSyncedPosMs
        if (abs(diffMs) > 800) {
            baseSpinAngle = (baseSpinAngle + diffMs * 0.12f) % 36000f
        }
        lastSyncedPosMs = posMs
    }

    // Vòng lặp 60fps tính góc quay mượt với quán tính
    LaunchedEffect(isPlaying, smoothVelocity) {
        var lastNanos = withFrameNanos { it }
        while (isPlaying || smoothVelocity > 0.4f) {
            val now = withFrameNanos { it }
            val dt = (now - lastNanos) / 1_000_000_000f
            lastNanos = now
            baseSpinAngle = (baseSpinAngle + smoothVelocity * dt) % 36000f
        }
    }

    // Tỷ lệ vận tốc góc thực tế: Cuộn Trái (nhỏ dần -> nhanh dần), Cuộn Phải (to dần -> chậm dần)
    val leftSpeedRatio = 1.35f - 0.55f * progressFraction
    val rightSpeedRatio = 0.80f + 0.55f * progressFraction
    val leftReelAngle = (baseSpinAngle * leftSpeedRatio) % 360f
    val rightReelAngle = (baseSpinAngle * rightSpeedRatio) % 360f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Thanh đỉnh máy: Nút Library | Bộ chọn 3 chế độ CASSETTE - COVER - MINIMAL | Nút Audio Lab
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenLibrary,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelSurface)
                    .border(1.dp, BezelBorder, RoundedCornerShape(10.dp))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Library",
                    tint = ColdSilver,
                    modifier = Modifier.size(19.dp)
                )
            }

            // Công tắc chuyển đổi 3 chế độ: CASSETTE | COVER | MINIMAL
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(ChassisDark)
                    .border(1.dp, BezelBorder, RoundedCornerShape(9.dp))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerViewMode.values().forEach { mode ->
                    val active = viewMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) ElevatedMetallic else Color.Transparent)
                            .clickable { viewMode = mode }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = mode.title,
                            color = if (active) ChampagneGold else TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }

            IconButton(
                onClick = onOpenAudioLab,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelSurface)
                    .border(1.dp, BezelBorder, RoundedCornerShape(10.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Audio Lab",
                    tint = ChampagneGold,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        // 2. Khu vực hiển thị chính theo 3 chế độ (Cassette View / Cover Art View / Minimal View)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = viewMode,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) },
                label = "player_mode_content"
            ) { mode ->
                when (mode) {
                    PlayerViewMode.CASSETTE -> SonyWalkmanRealisticCassetteDeck(
                        trackTitle = track?.title ?: "NO TRACK SELECTED",
                        trackArtist = track?.artist?.takeIf { !it.contains("<unknown>", true) } ?: "SONY WALKMAN",
                        formatBadge = formatBadgeText,
                        isPlaying = isPlaying,
                        posMs = posMs,
                        leftReelAngle = leftReelAngle,
                        rightReelAngle = rightReelAngle,
                        capstanPhase = baseSpinAngle,
                        progressFraction = progressFraction
                    )

                    PlayerViewMode.COVER_ART -> SonySignatureCoverArtView(
                        formatBadge = formatBadgeText,
                        isPlaying = isPlaying,
                        reelAngle = leftReelAngle
                    )

                    PlayerViewMode.MINIMAL -> SonySignatureMinimalFocusView(
                        trackTitle = track?.title ?: "No Track Selected",
                        trackArtist = track?.artist?.takeIf { !it.contains("<unknown>", true) } ?: "Unknown Artist",
                        albumName = track?.folderInfo ?: "Album",
                        formatBadge = formatBadgeText,
                        bitrateKbps = bitrateKbps,
                        isPlaying = isPlaying,
                        posMs = posMs,
                        durMs = durMs
                    )
                }
            }
        }

        // 3. Thông tin bài hát & Đèn LED báo trạng thái kiểu Walkman cổ điển (Ẩn bớt tiêu đề lặp khi ở chế độ Minimal)
        if (viewMode != PlayerViewMode.MINIMAL) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Cụm đèn báo LED nhỏ (PLAY / HI-RES Indicator) + Nhãn Format tinh tế
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Đèn LED PLAY
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ChassisDark)
                            .border(0.8.dp, BezelBorder, RoundedCornerShape(4.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isPlaying) LedPlayGreen else Color(0xFF334155))
                        )
                        Text(
                            text = if (isPlaying) "PLAY" else "PAUSE",
                            color = if (isPlaying) LedPlayGreen else TextMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                    }

                    // Nhãn Format (ví dụ: DSD128 • 5.6 MHz)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(PanelSurface)
                            .border(0.8.dp, ChampagneGoldDim.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(ChampagneGold)
                        )
                        Text(
                            text = formatBadgeText,
                            color = ChampagneGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.6.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = track?.title ?: "Chưa chọn bài hát",
                    color = TextPure,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                val cleanArtist = track?.artist?.takeIf { !it.contains("<unknown>", true) } ?: "Unknown Artist"
                val cleanAlbum = track?.folderInfo ?: "Album"
                Text(
                    text = "$cleanArtist — $cleanAlbum",
                    color = ColdSilver,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Thanh tiến trình phong cách Thước Analog (Analog Scale Progress Bar)
        AnalogScaleSeekBar(
            posMs = posMs,
            durMs = durMs,
            progressFraction = progressFraction,
            onSeek = onSeek
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 5. Cụm phím bấm cơ học kiểu đài Cassette thật (Mechanical Piano-Key Transport Deck)
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

/**
 * GIAO DIỆN BĂNG CASSETTE CHÂN THỰC CÓ CHIỀU SÂU, TỐC ĐỘ QUAY VẬT LÝ & CHỈ BÁO TAPE MOVING
 */
@Composable
fun SonyWalkmanRealisticCassetteDeck(
    trackTitle: String,
    trackArtist: String,
    formatBadge: String,
    isPlaying: Boolean,
    posMs: Int,
    leftReelAngle: Float,
    rightReelAngle: Float,
    capstanPhase: Float,
    progressFraction: Float
) {
    val isHiResOrDsd = formatBadge.contains("DSD", true) || formatBadge.contains("96.", true) ||
            formatBadge.contains("192.", true) || formatBadge.contains("384.", true) || formatBadge.contains("FLAC", true)

    val tapeTypeTitle = if (isHiResOrDsd) "METAL MASTER 90" else "BHF 60"
    val biasLabel = if (isHiResOrDsd) "TYPE IV • 70μs EQ" else "TYPE I • 120μs EQ"
    val tapeCounterStr = "%03d".format((posMs / 1000) % 1000)

    // Khung hốc chứa băng của máy Walkman (Recessed Cassette Compartment)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(274.dp)
            .shadow(12.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF252A34), Color(0xFF171A21), Color(0xFF0E1015))
                )
            )
            .border(1.5.dp, Color(0xFF383F4E), RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        // 4 Ốc vít kim loại ở 4 góc vỏ băng
        CassetteCornerScrew(Modifier.align(Alignment.TopStart))
        CassetteCornerScrew(Modifier.align(Alignment.TopEnd))
        CassetteCornerScrew(Modifier.align(Alignment.BottomStart))
        CassetteCornerScrew(Modifier.align(Alignment.BottomEnd))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Nhãn băng kim loại phay xước + Bộ đếm số cơ học (Tape Counter)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF1A1E26), Color(0xFF282E3B), Color(0xFF1A1E26))
                        )
                    )
                    .border(
                        width = 0.9.dp,
                        color = ChampagneGoldDim.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "SONY",
                            color = ChampagneGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.2.sp
                        )
                        Text(
                            text = tapeTypeTitle,
                            color = ColdSilver,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = trackTitle,
                        color = TextPure,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Hộp số cơ học 3 chữ số (Mechanical 3-Digit Counter)
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "COUNTER",
                        color = TextMuted,
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF07080A))
                            .border(1.dp, Color(0xFF333947), RoundedCornerShape(4.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = tapeCounterStr,
                            color = ChampagneGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp
                        )
                    }
                }
            }

            // 2. Cửa sổ nhựa Acrylic trong suốt có chiều sâu (Recessed Acrylic Window)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF060709), Color(0xFF0E1117), Color(0xFF07090C))
                        )
                    )
                    .border(2.dp, Color(0xFF2D3443), RoundedCornerShape(10.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val centerY = h * 0.48f
                    val leftReelX = w * 0.26f
                    val rightReelX = w * 0.74f

                    // Vẽ khung viền chìm tạo chiều sâu 3D bên trong cửa sổ nhựa
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.65f),
                        topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                        size = Size(w - 6.dp.toPx(), h - 6.dp.toPx()),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                        style = Stroke(width = 3.dp.toPx())
                    )

                    // Bán kính 2 cuộn băng từ thay đổi thực tế theo tiến độ bài hát
                    val maxSpoolRadius = h * 0.43f
                    val minSpoolRadius = h * 0.21f
                    val leftSpoolRadius = maxSpoolRadius - (maxSpoolRadius - minSpoolRadius) * progressFraction
                    val rightSpoolRadius = minSpoolRadius + (maxSpoolRadius - minSpoolRadius) * progressFraction

                    // Vẽ cuộn dây băng từ bên Trái (Supply Spool) với hiệu ứng vân đồng tâm
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF281F1C), Color(0xFF16110F), Color(0xFF241C19)),
                            center = Offset(leftReelX, centerY),
                            radius = leftSpoolRadius
                        ),
                        radius = leftSpoolRadius,
                        center = Offset(leftReelX, centerY)
                    )
                    drawCircle(
                        color = Color(0xFF382C28),
                        radius = leftSpoolRadius,
                        center = Offset(leftReelX, centerY),
                        style = Stroke(width = 1.2.dp.toPx())
                    )

                    // Vẽ cuộn dây băng từ bên Phải (Take-up Spool)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF281F1C), Color(0xFF16110F), Color(0xFF241C19)),
                            center = Offset(rightReelX, centerY),
                            radius = rightSpoolRadius
                        ),
                        radius = rightSpoolRadius,
                        center = Offset(rightReelX, centerY)
                    )
                    drawCircle(
                        color = Color(0xFF382C28),
                        radius = rightSpoolRadius,
                        center = Offset(rightReelX, centerY),
                        style = Stroke(width = 1.2.dp.toPx())
                    )

                    // Vẽ dây băng từ căng ngang từ cuộn trái xuống trục đọc từ ở đáy rồi lên cuộn phải
                    val guideLeftX = w * 0.12f
                    val guideRightX = w * 0.88f
                    val guideBottomY = h * 0.88f
                    drawLine(
                        color = Color(0xFF3D302A),
                        start = Offset(leftReelX - leftSpoolRadius * 0.7f, centerY + leftSpoolRadius * 0.6f),
                        end = Offset(guideLeftX, guideBottomY),
                        strokeWidth = 1.6.dp.toPx()
                    )
                    drawLine(
                        color = Color(0xFF3D302A),
                        start = Offset(guideLeftX, guideBottomY),
                        end = Offset(guideRightX, guideBottomY),
                        strokeWidth = 1.8.dp.toPx()
                    )
                    drawLine(
                        color = Color(0xFF3D302A),
                        start = Offset(guideRightX, guideBottomY),
                        end = Offset(rightReelX + rightSpoolRadius * 0.7f, centerY + rightSpoolRadius * 0.6f),
                        strokeWidth = 1.6.dp.toPx()
                    )

                    // Chỉ báo "Tape Moving" (Dải xung chạy dọc dây băng ở đáy khi đang phát)
                    if (isPlaying) {
                        val dashOffset = (capstanPhase * 1.4f) % 24f
                        var dx = guideLeftX + dashOffset
                        while (dx < guideRightX - 10f) {
                            drawLine(
                                color = ChampagneGold.copy(alpha = 0.55f),
                                start = Offset(dx, guideBottomY),
                                end = Offset(dx + 6f, guideBottomY),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            dx += 24f
                        }
                    }

                    // Vạch chia thước đo lượng băng (0 — 50 — 100) ở chính giữa cửa sổ
                    val scaleStartX = w * 0.39f
                    val scaleEndX = w * 0.61f
                    drawLine(
                        color = ColdSilver.copy(alpha = 0.40f),
                        start = Offset(scaleStartX, centerY),
                        end = Offset(scaleEndX, centerY),
                        strokeWidth = 1.dp.toPx()
                    )
                    for (tick in 0..8) {
                        val tx = scaleStartX + (scaleEndX - scaleStartX) * (tick / 8f)
                        val tickH = if (tick % 4 == 0) 6.dp.toPx() else if (tick % 2 == 0) 4.dp.toPx() else 2.2.dp.toPx()
                        drawLine(
                            color = if (tick % 4 == 0) ChampagneGold else ColdSilver.copy(alpha = 0.55f),
                            start = Offset(tx, centerY - tickH),
                            end = Offset(tx, centerY + tickH),
                            strokeWidth = 1.2.dp.toPx()
                        )
                    }

                    // Vẽ 2 trục bánh răng quay độc lập (Left Reel & Right Reel)
                    val hubRadius = h * 0.195f
                    val reelPairs = listOf(leftReelX to leftReelAngle, rightReelX to rightReelAngle)

                    reelPairs.forEach { (cx, angle) ->
                        // Vành kim loại sáng của trục bánh răng
                        drawCircle(
                            color = Color(0xFF141820),
                            radius = hubRadius,
                            center = Offset(cx, centerY)
                        )
                        drawCircle(
                            color = ColdSilver,
                            radius = hubRadius,
                            center = Offset(cx, centerY),
                            style = Stroke(width = 2.4.dp.toPx())
                        )

                        // 6 chấu bánh răng + 3 vấu kẹp băng quay mượt theo góc vật lý
                        rotate(angle, pivot = Offset(cx, centerY)) {
                            for (spoke in 0 until 6) {
                                rotate(spoke * 60f, pivot = Offset(cx, centerY)) {
                                    drawLine(
                                        color = ChampagneGold,
                                        start = Offset(cx, centerY - hubRadius * 0.36f),
                                        end = Offset(cx, centerY - hubRadius * 0.94f),
                                        strokeWidth = 2.8.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                        }

                        // Tâm trục tối
                        drawCircle(
                            color = Color(0xFF060709),
                            radius = hubRadius * 0.34f,
                            center = Offset(cx, centerY)
                        )
                        drawCircle(
                            color = ChampagneGoldDim,
                            radius = hubRadius * 0.34f,
                            center = Offset(cx, centerY),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    // Hiệu ứng lớp kính Acrylic phản quang đa tầng
                    val highlightPath1 = Path().apply {
                        moveTo(w * 0.28f, 0f)
                        lineTo(w * 0.42f, 0f)
                        lineTo(w * 0.24f, h)
                        lineTo(w * 0.10f, h)
                        close()
                    }
                    drawPath(
                        path = highlightPath1,
                        brush = Brush.linearGradient(
                            colors = listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.015f))
                        )
                    )
                }
            }

            // 3. Cụm đầu từ & Con lăn dẫn băng phía dưới (Head Shield & Capstan Rollers)
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .height(30.dp)
                    .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF222733), Color(0xFF151820)))
                    )
                    .border(1.dp, Color(0xFF343B4A), RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Con lăn dẫn băng trái
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF08090C))
                            .border(1.2.dp, ColdSilver.copy(alpha = 0.6f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) ChampagneGold else TextMuted)
                    )
                }

                // Dòng thông số Bias & Chỉ báo TAPE RUNNING
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) LedPlayGreen else LedRecAmber.copy(alpha = 0.5f))
                    )
                    Text(
                        text = if (isPlaying) "TAPE RUNNING • $biasLabel" else "STANDBY • $biasLabel",
                        color = ChampagneGold.copy(alpha = 0.9f),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp
                    )
                }

                // Con lăn dẫn băng phải
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) ChampagneGold else TextMuted)
                    )
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF08090C))
                            .border(1.2.dp, ColdSilver.copy(alpha = 0.6f), CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
fun CassetteCornerScrew(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(listOf(Color(0xFF475063), Color(0xFF222630)))
            )
            .border(0.8.dp, Color(0xFF0D0F14), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(1.dp)
                .background(Color(0xFF111318))
        )
    }
}

/**
 * CHẾ ĐỘ XEM 2: COVER ART / ĐĨA VINYL KIM LOẠI CAO CẤP
 */
@Composable
fun SonySignatureCoverArtView(
    formatBadge: String,
    isPlaying: Boolean,
    reelAngle: Float
) {
    Box(
        modifier = Modifier
            .size(258.dp)
            .shadow(10.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1E232E), ChassisDark, WalkmanBlack)
                )
            )
            .border(1.5.dp, BezelBorder, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize(0.82f)) {
            val r = size.minDimension / 2f
            drawCircle(color = Color(0xFF0C0E13), radius = r)
            drawCircle(color = ChampagneGoldDim.copy(alpha = 0.55f), radius = r, style = Stroke(width = 1.8.dp.toPx()))
            drawCircle(color = Color(0xFF1C202B), radius = r * 0.80f, style = Stroke(width = 1.dp.toPx()))
            drawCircle(color = Color(0xFF1C202B), radius = r * 0.62f, style = Stroke(width = 1.dp.toPx()))
            drawCircle(color = Color(0xFF1C202B), radius = r * 0.44f, style = Stroke(width = 1.dp.toPx()))

            rotate(reelAngle) {
                drawLine(
                    color = ChampagneGold.copy(alpha = 0.25f),
                    start = Offset(center.x, center.y - r * 0.92f),
                    end = Offset(center.x, center.y + r * 0.92f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            drawCircle(color = ChampagneGold, radius = r * 0.25f)
            drawCircle(color = WalkmanBlack, radius = r * 0.06f)
        }
    }
}

/**
 * CHẾ ĐỘ XEM 3: MINIMAL VIEW (CHỈ TẬP TRUNG VÀO TYPOGRAPHY, THÔNG SỐ DIGITAL & CỤM NÚT BẤM)
 */
@Composable
fun SonySignatureMinimalFocusView(
    trackTitle: String,
    trackArtist: String,
    albumName: String,
    formatBadge: String,
    bitrateKbps: Int,
    isPlaying: Boolean,
    posMs: Int,
    durMs: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ChassisDark)
            .border(1.dp, BezelBorder, RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isPlaying) LedPlayGreen else LedRecAmber)
                )
                Text(
                    text = if (isPlaying) "DIRECT STREAMING" else "PAUSED",
                    color = ColdSilver,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp
                )
            }

            Text(
                text = "$bitrateKbps kbps",
                color = ChampagneGold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        HorizontalDivider(color = BezelBorder)

        Text(
            text = trackTitle,
            color = TextPure,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 30.sp
        )

        Text(
            text = "$trackArtist • $albumName",
            color = ColdSilver,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(PanelSurface)
                    .border(1.dp, ChampagneGoldDim.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = formatBadge,
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = "${formatTimeMs(posMs)} / ${formatTimeMs(durMs)}",
                color = TextPure,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * THANH TIẾN TRÌNH PHONG CÁCH THƯỚC TRƯỢT ANALOG (ANALOG RAIL PROGRESS BAR)
 */
@Composable
fun AnalogScaleSeekBar(
    posMs: Int,
    durMs: Int,
    progressFraction: Float,
    onSeek: (Int) -> Unit
) {
    val safeMaxDur = durMs.toFloat().coerceAtLeast(1f)
    val safeCurrPos = posMs.toFloat().coerceIn(0f, safeMaxDur)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ChassisDark)
            .border(1.dp, BezelBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Vạch chia Analog mảnh phía trên rãnh trượt
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        ) {
            val tickCount = 24
            for (i in 0..tickCount) {
                val x = size.width * (i.toFloat() / tickCount)
                val passed = (i.toFloat() / tickCount) <= progressFraction
                val tickH = if (i % 6 == 0) size.height else size.height * 0.55f
                drawLine(
                    color = if (passed) ChampagneGold else BezelBorder,
                    start = Offset(x, size.height - tickH),
                    end = Offset(x, size.height),
                    strokeWidth = if (i % 6 == 0) 1.5.dp.toPx() else 1.dp.toPx()
                )
            }
        }

        Slider(
            value = safeCurrPos,
            onValueChange = { onSeek(it.toInt()) },
            valueRange = 0f..safeMaxDur,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = ChampagneGold,
                activeTrackColor = ChampagneGold,
                inactiveTrackColor = Color(0xFF222631)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTimeMs(posMs),
                color = ChampagneGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "ANALOG TAPE SCALE",
                color = TextMuted,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp
            )
            Text(
                text = formatTimeMs(durMs),
                color = ColdSilver,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * CỤM NÚT ẤN CƠ HỌC KIỂU ĐÀI CASSETTE THẬT (MECHANICAL PIANO-KEY TRANSPORT DECK)
 * - Các phím bấm hình khối chữ nhật vát cạnh kim loại, có rãnh khía chống trượt và độ lún vật lý khi nhấn.
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

    // Khung kim loại bao quanh bàn phím cơ học
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF050608))
            .border(1.5.dp, Color(0xFF2E3442), RoundedCornerShape(14.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Phím 1: SHUFFLE
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

        // Phím 2: REW / PREVIOUS
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

        // Phím 3: PLAY / PAUSE (Phím chính rộng hơn, có chấm tactile vàng & đèn LED xanh lá như đài Cassette thật)
        CassetteMechanicalKey(
            modifier = Modifier.weight(1.45f),
            label = if (isPlaying) "PLAYING" else "PLAY",
            isLatched = isPlaying,
            isPrimaryGoldKey = true,
            ledColor = if (isPlaying) LedPlayGreen else LedRecAmber.copy(alpha = 0.4f),
            onClick = onPlayPause
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                tint = if (isPlaying) ChampagneGold else TextPure,
                modifier = Modifier.size(26.dp)
            )
        }

        // Phím 4: FF / NEXT
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

        // Phím 5: REPEAT
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

    // Hiệu ứng lún cơ học khi nhấn tay hoặc khi phím đang chốt (isLatched)
    val isDepressed = isPressed || isLatched
    val topInsetDp by animateDpAsState(
        targetValue = if (isDepressed) 4.dp else 0.dp,
        animationSpec = tween(durationMillis = 90),
        label = "key_depress_offset"
    )

    val topSurfaceColors = when {
        isDepressed && isPrimaryGoldKey -> listOf(Color(0xFF282318), Color(0xFF191610))
        isDepressed -> listOf(Color(0xFF151820), Color(0xFF101218))
        isPrimaryGoldKey -> listOf(Color(0xFF332D20), Color(0xFF221E15), Color(0xFF17140E))
        else -> listOf(Color(0xFF2A303D), Color(0xFF1C2029), Color(0xFF14171E))
    }

    val borderStrokeColor = when {
        isPrimaryGoldKey && isLatched -> ChampagneGold
        isPrimaryGoldKey -> ChampagneGoldDim
        isLatched -> ChampagneGold.copy(alpha = 0.75f)
        else -> Color(0xFF384052)
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(top = topInsetDp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(topSurfaceColors))
            .border(1.dp, borderStrokeColor, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 5.dp, horizontal = 4.dp)
    ) {
        // Viền sáng kim loại vát trên đỉnh phím khi chưa bị nhấn lún
        if (!isDepressed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.85f)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.16f))
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Đèn LED nhỏ hoặc rãnh khía cơ học trên mặt phím
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
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp
                )
            }

            // Icon chính ở giữa phím
            iconContent()

            // 2 rãnh khía ngang chống trượt ở mép dưới phím Cassette (Tactile Grip Grooves)
            Column(verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(1.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                )
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )
            }
        }
    }
}

/**
 * =============================================================================================
 * 2. MÀN HÌNH SETTINGS / AUDIO LAB (4 SECTION RÕ RÀNG, CÓ NÚT CENTER BALANCE CHỐNG LỆCH KÊNH)
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
            .background(WalkmanBlack)
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
                        text = "Sound Settings & DSP",
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
            // 1. SOUND MODE
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
                            checkedThumbColor = WalkmanBlack,
                            checkedTrackColor = ChampagneGold,
                            uncheckedThumbColor = ColdSilver,
                            uncheckedTrackColor = ChassisDark
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

            // 2. PROCESSING
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

                // Channel Balance (L - R) kèm nút Reset Center 0.0
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
                                        .background(ChassisDark)
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

            // 3. EQUALIZER (10-BAND)
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

            // 4. OTHER
            AudioLabSectionCard(title = "OTHER") {
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
                AudioLabInfoRow("App Version", "Walkman Signature Edition • v10.3")
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
                checkedThumbColor = WalkmanBlack,
                checkedTrackColor = ChampagneGold,
                uncheckedThumbColor = ColdSilver,
                uncheckedTrackColor = ChassisDark
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
            .background(WalkmanBlack)
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
                        color = if (active) WalkmanBlack else ColdSilver
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
