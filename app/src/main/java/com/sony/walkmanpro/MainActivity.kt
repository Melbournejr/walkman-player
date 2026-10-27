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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sin

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
    SEQUENTIAL("Tuần tự"),
    REPEAT_ONE("Lặp 1 bài"),
    SHUFFLE("Xáo trộn")
}

data class AudioOutputState(
    val deviceType: String = "Loa thiết bị",
    val isBluetooth: Boolean = false,
    val deviceName: String = "Built-in Speaker",
    val codecEstimate: String = "Stereo"
)

enum class WalkmanSkin(val shortName: String, val fullName: String) {
    WM1ZM2("WM1ZM2 Gold", "SONY SIGNATURE NW-WM1ZM2"),
    TPSL2("TPS-L2", "SONY WALKMAN TPS-L2 (1979)"),
    W995I("W995i", "SONY ERICSSON W995i"),
    W850I("W850i", "SONY ERICSSON W850i"),
    A306("NW-A306", "SONY HI-RES NW-A306")
}

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

    private val backgroundHandler = Handler(Looper.getMainLooper())
    private var currentTrackRef: AudioTrack? = null
    private var currentIndexRef: Int = 0

    private val backgroundKeepAliveRunnable = object : Runnable {
        override fun run() {
            try {
                val playing = getActiveIsPlaying()
                if (playing) {
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
            MaterialTheme(colorScheme = darkColorScheme()) {
                WalkmanMasterRoot(
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
        // Kiểm tra trực tiếp 4 byte đầu tiên của file ("FRM8" hoặc "DSD ")
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
                // Chuyển sang FiioDirectDsdEngine để chạy mượt .DFF / .DSF qua AudioTrack
                isDsdModeActive = true
                sharedExoPlayer?.pause()
                sharedDsdEngine?.masterVolume = (masterVolume * sleepFadeMultiplier).coerceIn(0f, 1f)
                sharedDsdEngine?.startDsdPlayback(track.uri, startMs.toInt())
            } else {
                // Chạy ExoPlayer cho MP3, FLAC, WAV, M4A
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

    private fun detectAudioOutputState(context: Context): AudioOutputState {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (dev in devices) {
                when (dev.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> {
                        val name = dev.productName?.toString() ?: "Bluetooth Audio"
                        val codecName = when {
                            name.contains("LDAC", true) || name.contains("1000X", true) -> "Sony LDAC (990 kbps / 96kHz)"
                            name.contains("aptx", true) -> "Qualcomm aptX Adaptive / HD"
                            else -> "Bluetooth AAC / SBC HD"
                        }
                        return AudioOutputState("Bluetooth Audio", true, name, codecName)
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                        return AudioOutputState("Cổng 3.5mm / 4.4mm Balanced", false, "WM1ZM2 S-Master HX DAC", "Direct DSD / PCM 384kHz")
                    }
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> {
                        return AudioOutputState("USB-DAC", false, dev.productName?.toString() ?: "USB HiFi DAC", "Bit-Perfect PCM/DSD")
                    }
                }
            }
        }
        return AudioOutputState("Loa ngoài thiết bị", false, "Stereo Speaker", "Standard Audio")
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
                lowerTitle.contains("384k") || mime.contains("x-flac") -> "PCM 384.0 kHz • 32-bit"
                lowerTitle.endsWith(".flac") || lowerFmt == "flac" -> "FLAC 192.0 kHz • 24-bit Hi-Res"
                lowerTitle.endsWith(".mqa") || lowerFmt == "mqa" -> "MQA Studio 352.8 kHz"
                lowerTitle.endsWith(".wav") -> "PCM 96.0 kHz • 24-bit"
                else -> "44.1 kHz • 16-bit Stereo"
            }
        } catch (_: Throwable) {
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
        return Pair(bitrate, sampleRate)
    }

    private fun parseDsdSpecsDirectly(context: Context, uri: Uri): Pair<Int, String> {
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val buf = ByteArray(512)
                val read = ins.read(buf)
                if (read >= 64) {
                    val magic = String(buf, 0, 4, Charsets.ISO_8859_1)
                    if (magic == "DSD ") {
                        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                        val ch = bb.getInt(48).coerceIn(1, 6)
                        val fs = bb.getInt(52).coerceAtLeast(2822400)
                        val kbps = (fs * ch) / 1000
                        val dsdName = when {
                            fs >= 11289600 -> "DSD256 11.2 MHz • 1-bit"
                            fs >= 5644800 -> "DSD128 5.6 MHz • 1-bit"
                            else -> "DSD64 2.8 MHz • 1-bit"
                        }
                        return Pair(kbps, dsdName)
                    } else if (magic == "FRM8") {
                        var fs = 2822400
                        for (i in 0..(read - 16)) {
                            if (buf[i] == 'F'.code.toByte() && buf[i + 1] == 'S'.code.toByte() &&
                                buf[i + 2] == ' '.code.toByte() && buf[i + 3] == ' '.code.toByte()
                            ) {
                                val parsed = ByteBuffer.wrap(buf, i + 12, 4).order(ByteOrder.BIG_ENDIAN).int
                                if (parsed >= 2822400) fs = parsed
                                break
                            }
                        }
                        val kbps = (fs * 2) / 1000
                        val dsdName = when {
                            fs >= 11289600 -> "DSDIFF DSD256 11.2 MHz"
                            fs >= 5644800 -> "DSDIFF DSD128 5.6 MHz"
                            else -> "DSDIFF DSD64 2.8 MHz"
                        }
                        return Pair(kbps, dsdName)
                    }
                }
            }
        } catch (_: Throwable) {}
        return Pair(5644, "DSD 2.8 MHz • 1-bit Direct")
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
                        val artist = if (artistCol >= 0) c.getString(artistCol) ?: "Walkman Audio" else "Walkman Audio"
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
            added.add(AudioTrack(System.currentTimeMillis() + added.size, fn.substringBeforeLast("."), "Audiophile Direct", 0L, uri, "File chọn lẻ", sz, fn.substringAfterLast(".", "DSD/PCM").uppercase()))
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
                            added.add(AudioTrack(System.currentTimeMillis() + added.size, n.substringBeforeLast("."), dir.name ?: "Hi-Res Folder", 0L, f.uri, dir.name ?: "Thư mục", f.length(), n.substringAfterLast(".", "PCM").uppercase()))
                        }
                    }
                }
            }
        }
        try { scanDir(rootDir) } catch (_: Throwable) {}
        return Pair(added, skipped)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

object PlaylistStorage {
    private const val PREF_NAME = "walkman_v7_storage"
    private const val KEY_TRACKS = "saved_tracks_json"
    private const val KEY_FOLDERS = "saved_folder_uris"
    private const val KEY_SKIN = "saved_skin_name"
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
                        artist = obj.optString("artist", "Walkman"),
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

    fun saveSkin(context: Context, skin: WalkmanSkin) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_SKIN, skin.name).apply()
    }

    fun loadSkin(context: Context): WalkmanSkin {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_SKIN, WalkmanSkin.WM1ZM2.name) ?: WalkmanSkin.WM1ZM2.name
        return try { WalkmanSkin.valueOf(name) } catch (_: Throwable) { WalkmanSkin.WM1ZM2 }
    }
}

fun formatTimeMs(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

@Composable
fun WalkmanMasterRoot(
    onAutoScanAll: () -> Pair<List<AudioTrack>, Int>,
    onParseManualUris: (List<Uri>) -> List<AudioTrack>,
    onParseFolderUri: (Uri) -> Pair<List<AudioTrack>, Int>,
    onPlayTrack: (AudioTrack, Int, Long, () -> Unit) -> Unit,
    onTogglePlay: () -> Boolean,
    onStopPlayback: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSetMasterVolume: (Float) -> Unit,
    onSetSleepMultiplier: (Float) -> Unit,
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

    var activeTab by remember { mutableStateOf(0) }
    var currentSkin by remember { mutableStateOf(PlaylistStorage.loadSkin(context)) }
    var playlist by remember { mutableStateOf(PlaylistStorage.loadPlaylist(context)) }
    var favoriteTitles by remember { mutableStateOf(PlaylistStorage.loadFavorites(context)) }
    var playMode by remember { mutableStateOf(PlaylistStorage.loadPlayMode(context)) }

    var isScanning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(PlaylistStorage.loadLastIndex(context)) }
    var isPlaying by remember { mutableStateOf(getIsPlaying()) }
    var posMs by remember { mutableStateOf(PlaylistStorage.loadLastPos(context)) }
    var durMs by remember { mutableStateOf(1) }

    var keepScreenOnDeskMode by remember { mutableStateOf(false) }
    var sleepTimerRemainingSec by remember { mutableStateOf(0) }

    val dspPrefs = context.getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
    var masterVol by remember { mutableStateOf(dspPrefs.getFloat("dsp_master_vol", 0.75f)) }
    var megaBass by remember { mutableStateOf(dspPrefs.getFloat("dsp_bass", 0.45f)) }
    var vinylProcessor by remember { mutableStateOf(dspPrefs.getBoolean("dsp_vinyl", true)) }
    var clearAudioPlus by remember { mutableStateOf(dspPrefs.getBoolean("dsp_clear_audio", true)) }
    var dseeUltimate by remember { mutableStateOf(dspPrefs.getBoolean("dsp_dsee", true)) }
    var channelBalance by remember { mutableStateOf(dspPrefs.getFloat("dsp_balance", 0f)) }
    var phaseInvert by remember { mutableStateOf(dspPrefs.getBoolean("dsp_phase_invert", false)) }
    var crossfadeEnabled by remember { mutableStateOf(dspPrefs.getBoolean("dsp_crossfade", true)) }
    var tapeSpeed by remember { mutableStateOf(dspPrefs.getFloat("dsp_tape_speed", 1.0f)) }

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
            Toast.makeText(context, "Khôi phục vị trí đã nghe: ${formatTimeMs(savedPosForThisTrack)}", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "Hẹn giờ ngủ: Đã giảm âm và tắt nhạc!", Toast.LENGTH_LONG).show()
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
                Toast.makeText(context, "Đã cập nhật ${mergedList.size} bài hát!", Toast.LENGTH_SHORT).show()
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
        val hasPerm = ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        if (hasPerm && playlist.isEmpty()) {
            runDeduplicatedScan()
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
    val isCurrentFavorite = currentTrack != null && favoriteTitles.contains(currentTrack.title)

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B0F19))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WalkmanSkin.values().forEach { skin ->
                    val selected = skin == currentSkin
                    val skinColor = when (skin) {
                        WalkmanSkin.WM1ZM2 -> Color(0xFFEAB308)
                        WalkmanSkin.TPSL2 -> Color(0xFF38BDF8)
                        WalkmanSkin.W995I -> Color(0xFFFF3B1D)
                        WalkmanSkin.W850I -> Color(0xFFFF6600)
                        WalkmanSkin.A306 -> Color(0xFFF59E0B)
                    }

                    Button(
                        onClick = {
                            currentSkin = skin
                            PlaylistStorage.saveSkin(context, skin)
                        },
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (selected) skinColor else Color(0xFF334155)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) skinColor else Color(0xFF1E293B),
                            contentColor = if (selected && skin == WalkmanSkin.WM1ZM2) Color.Black else Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = skin.shortName,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0B0F19)) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.Album, null) },
                    label = { Text("Walkman 9.5", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.LibraryMusic, null) },
                    label = { Text("Thư viện (${playlist.size})", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Equalizer, null) },
                    label = { Text("Audio 7.0 & Tiện ích", fontWeight = FontWeight.Bold) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF05070D))
                .padding(padding)
        ) {
            when (activeTab) {
                0 -> FullScreenWalkmanDeckV95(
                    skin = currentSkin,
                    track = currentTrack,
                    audioOutput = audioOutput,
                    bitrate = currentBitrate,
                    sampleRate = currentSampleRate,
                    isPlaying = isPlaying,
                    playMode = playMode,
                    isFavorite = isCurrentFavorite,
                    keepScreenOn = keepScreenOnDeskMode,
                    sleepTimerRemainingSec = sleepTimerRemainingSec,
                    tapeSpeed = tapeSpeed,
                    posMs = posMs,
                    durMs = durMs,
                    masterVol = masterVol,
                    onVolumeChange = {
                        masterVol = it
                        onSetMasterVolume(it)
                    },
                    onSeek = { target ->
                        posMs = target
                        onSeekTo(target)
                        currentTrack?.let {
                            PlaylistStorage.saveTrackPosition(context, it.title, posMs, currentIndex)
                        }
                        updateSystemMediaNotification(isPlaying)
                    },
                    onToggleDeskMode = {
                        keepScreenOnDeskMode = !keepScreenOnDeskMode
                        onSetKeepScreenOn(keepScreenOnDeskMode)
                        Toast.makeText(
                            context,
                            if (keepScreenOnDeskMode) "Đã bật Giữ sáng màn hình (Desk Mode)" else "Đã tắt Giữ sáng màn hình",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onCyclePlayMode = {
                        val modes = PlayMode.values()
                        playMode = modes[(playMode.ordinal + 1) % modes.size]
                        PlaylistStorage.savePlayMode(context, playMode)
                        Toast.makeText(context, "Chế độ: ${playMode.label}", Toast.LENGTH_SHORT).show()
                    },
                    onToggleFavorite = {
                        currentTrack?.let {
                            favoriteTitles = PlaylistStorage.toggleFavorite(context, it.title)
                        }
                    },
                    onPlayPause = {
                        if (playlist.isEmpty()) {
                            activeTab = 1
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
                    }
                )
                1 -> FiioStyleCategorizedLibraryView(
                    context = context,
                    tracks = playlist,
                    favoriteTitles = favoriteTitles,
                    isScanning = isScanning,
                    currentIndex = currentIndex,
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
                            activeTab = 0
                        }
                    }
                )
                2 -> FullSonyDspAndAudiophileToolsView(
                    masterVol = masterVol,
                    megaBass = megaBass,
                    vinylProcessor = vinylProcessor,
                    clearAudio = clearAudioPlus,
                    dseeUltimate = dseeUltimate,
                    channelBalance = channelBalance,
                    phaseInvert = phaseInvert,
                    crossfade = crossfadeEnabled,
                    tapeSpeed = tapeSpeed,
                    sleepTimerRemainingSec = sleepTimerRemainingSec,
                    eq10Bands = eq10Bands,
                    audioOutput = audioOutput,
                    onMasterVolChange = {
                        masterVol = it
                        onSetMasterVolume(it)
                    },
                    onSetSleepMinutes = { mins ->
                        sleepTimerRemainingSec = mins * 60
                        onSetSleepMultiplier(1.0f)
                        if (mins > 0) {
                            Toast.makeText(context, "Hẹn giờ tắt nhạc sau $mins phút (Giảm âm 60s cuối)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Đã tắt Hẹn giờ ngủ", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onTapeSpeedChange = {
                        tapeSpeed = it
                        onSetTapeSpeed(it)
                    },
                    onCrossfadeToggle = {
                        crossfadeEnabled = it
                        onSetCrossfade(it)
                    },
                    onBalanceChange = {
                        channelBalance = it
                        onSetChannelBalance(it)
                    },
                    onPhaseInvertToggle = {
                        phaseInvert = it
                        onSetPhaseInvert(it)
                    },
                    onVinylToggle = {
                        vinylProcessor = it
                        onSetVinyl(it)
                    },
                    onClearAudioToggle = {
                        clearAudioPlus = it
                        onSetClearAudio(it)
                    },
                    onDseeToggle = {
                        dseeUltimate = it
                        onSetDsee(it)
                    },
                    onBassChange = {
                        megaBass = it
                        onSetBass(it)
                    },
                    onBand10Change = { idx, gain ->
                        eq10Bands[idx] = gain
                        onSetEqBand10(idx, gain)
                    },
                    onApplyPreset10 = { presetValues ->
                        presetValues.forEachIndexed { i, v ->
                            eq10Bands[i] = v
                            onSetEqBand10(i, v)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun FullScreenWalkmanDeckV95(
    skin: WalkmanSkin,
    track: AudioTrack?,
    audioOutput: AudioOutputState,
    bitrate: Int,
    sampleRate: String,
    isPlaying: Boolean,
    playMode: PlayMode,
    isFavorite: Boolean,
    keepScreenOn: Boolean,
    sleepTimerRemainingSec: Int,
    tapeSpeed: Float,
    posMs: Int,
    durMs: Int,
    masterVol: Float,
    onVolumeChange: (Float) -> Unit,
    onSeek: (Int) -> Unit,
    onToggleDeskMode: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val trackTitle = track?.title ?: "CHƯA CHỌN BÀI HÁT"
    val trackArtist = track?.artist ?: "Bấm tab 'Thư viện' để chọn nhạc"
    val format = track?.formatName ?: "AUDIO"

    val infiniteTransition = rememberInfiniteTransition(label = "walkman_anim")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "reel_angle"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "beat_pulse"
    )

    val accentColor = when (skin) {
        WalkmanSkin.WM1ZM2 -> Color(0xFFEAB308)
        WalkmanSkin.TPSL2 -> Color(0xFF38BDF8)
        WalkmanSkin.W995I -> Color(0xFFFF3B1D)
        WalkmanSkin.W850I -> Color(0xFFFF6600)
        WalkmanSkin.A306 -> Color(0xFFF59E0B)
    }

    val bodyGradient = when (skin) {
        WalkmanSkin.WM1ZM2 -> Brush.verticalGradient(listOf(Color(0xFF261D07), Color(0xFF17130A), Color(0xFF0B0904)))
        WalkmanSkin.TPSL2 -> Brush.verticalGradient(listOf(Color(0xFF1E3A6D), Color(0xFF122444), Color(0xFF0B162C)))
        WalkmanSkin.W995I -> Brush.verticalGradient(listOf(Color(0xFF1F161B), Color(0xFF12131A), Color(0xFF090A0F)))
        WalkmanSkin.W850I -> Brush.verticalGradient(listOf(Color(0xFF231810), Color(0xFF121318), Color(0xFF08090C)))
        WalkmanSkin.A306 -> Brush.verticalGradient(listOf(Color(0xFF221D15), Color(0xFF14151C), Color(0xFF0A0B0E)))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .background(bodyGradient, RoundedCornerShape(24.dp))
            .border(
                width = 3.dp,
                color = if (skin == WalkmanSkin.WM1ZM2) Color(0xFFEAB308) else accentColor.copy(alpha = 0.85f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (skin == WalkmanSkin.TPSL2) Color(0xFFD0D7E2) else Color(0xFF1E2433), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = skin.fullName,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    color = if (skin == WalkmanSkin.TPSL2) Color(0xFF0F172A) else Color.White
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "📁 ${track?.folderInfo ?: "SIGNATURE SERIES"}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    if (sleepTimerRemainingSec > 0) {
                        Text(
                            text = "🌙 ${sleepTimerRemainingSec / 60}m${sleepTimerRemainingSec % 60}s",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF22C55E),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (tapeSpeed != 1.0f) {
                        Text(
                            text = "⚡ ${"%.2f".format(tapeSpeed)}x",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF38BDF8),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Button(
                onClick = onToggleDeskMode,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (keepScreenOn) Color(0xFF22C55E) else Color(0xFF0F172A),
                    contentColor = if (keepScreenOn) Color.Black else Color.White
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(if (keepScreenOn) "☀ GIỮ SÁNG: BẬT" else "☀ GIỮ SÁNG", fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF090D17), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF26334D), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (audioOutput.isBluetooth) Icons.Default.BluetoothAudio else Icons.Default.Headphones,
                    contentDescription = null,
                    tint = if (audioOutput.isBluetooth) Color(0xFF38BDF8) else accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(audioOutput.deviceName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(audioOutput.codecEstimate, color = Color(0xFF38BDF8), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text("$format • $bitrate kbps", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                Text(sampleRate, color = Color.LightGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF080B12), RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (skin) {
                    WalkmanSkin.WM1ZM2 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1D1709), RoundedCornerShape(12.dp))
                                .border(2.dp, Color(0xFFEAB308), RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("FIIO/SONY DIRECT DSD • S-MASTER HX™ AMP", color = Color(0xFFEAB308), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("CH-L", color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    Canvas(modifier = Modifier.size(width = 125.dp, height = 82.dp)) {
                                        drawArc(color = Color(0xFFEAB308).copy(alpha = 0.35f), startAngle = 190f, sweepAngle = 160f, useCenter = false, style = Stroke(width = 5f))
                                        val swingL = if (isPlaying) (sin(angle * 0.12f) * 36f) else -38f
                                        rotate(swingL, pivot = Offset(size.width / 2, size.height)) {
                                            drawLine(color = Color(0xFFEF4444), start = Offset(size.width / 2, size.height), end = Offset(size.width / 2, 6f), strokeWidth = 5f)
                                        }
                                    }
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("CH-R", color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    Canvas(modifier = Modifier.size(width = 125.dp, height = 82.dp)) {
                                        drawArc(color = Color(0xFFEAB308).copy(alpha = 0.35f), startAngle = 190f, sweepAngle = 160f, useCenter = false, style = Stroke(width = 5f))
                                        val swingR = if (isPlaying) (sin((angle * 0.12f) + 1.2f) * 36f) else -38f
                                        rotate(swingR, pivot = Offset(size.width / 2, size.height)) {
                                            drawLine(color = Color(0xFFEF4444), start = Offset(size.width / 2, size.height), end = Offset(size.width / 2, 6f), strokeWidth = 5f)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    WalkmanSkin.TPSL2 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFDE68A), RoundedCornerShape(8.dp))
                                .border(2.dp, Color(0xFFD97706), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("SONY CHF-60 • NORMAL POSITION", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color(0xFF92400E), fontFamily = FontFamily.Monospace)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF162032), RoundedCornerShape(14.dp))
                                .border(2.dp, Color(0xFF475569), RoundedCornerShape(14.dp))
                                .padding(vertical = 22.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LargeCassetteReel(if (isPlaying) angle else 0f)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("TAPE COUNTER", fontSize = 8.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                                Box(
                                    modifier = Modifier
                                        .background(Color.Black, RoundedCornerShape(4.dp))
                                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("%03d".format((posMs / 1000) % 999), color = Color(0xFFF8FAFC), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                }
                            }
                            LargeCassetteReel(if (isPlaying) angle else 0f)
                        }
                    }

                    else -> {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(145.dp)) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                rotate(if (isPlaying) angle else 0f) {
                                    drawCircle(color = accentColor.copy(alpha = 0.2f), style = Stroke(width = 22f))
                                    drawCircle(color = accentColor, radius = size.minDimension / 2.3f, style = Stroke(width = 6f))
                                    drawLine(color = Color.White, start = Offset(size.width / 2, 10f), end = Offset(size.width / 2, 32f), strokeWidth = 5f)
                                }
                                drawCircle(color = Color.White, radius = 15f)
                            }
                        }

                        Canvas(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                            val barCount = 20
                            val barWidth = size.width / (barCount * 1.4f)
                            for (i in 0 until barCount) {
                                val ratio = if (isPlaying) ((sin((angle * 0.08f) + i) + 1f) / 2f).coerceIn(0.15f, 1f) else 0.12f
                                val barH = size.height * ratio
                                drawRoundRect(
                                    color = accentColor,
                                    topLeft = Offset(i * (size.width / barCount), size.height - barH),
                                    size = Size(barWidth, barH),
                                    cornerRadius = CornerRadius(4f, 4f)
                                )
                            }
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(trackTitle, color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(trackArtist, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }

                // KHÓA AN TOÀN 100% CHO SLIDER SEEKBAR (Tránh lỗi IllegalArgumentException khi mở file DSD có duration = 0)
                val safeMaxDur = durMs.toFloat().coerceAtLeast(1f)
                val safeCurrPos = posMs.toFloat().coerceIn(0f, safeMaxDur)

                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = safeCurrPos,
                        onValueChange = { onSeek(it.toInt()) },
                        valueRange = 0f..safeMaxDur,
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTimeMs(posMs), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                        Text(formatTimeMs(durMs), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111726), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.VolumeDown, null, tint = accentColor, modifier = Modifier.size(18.dp))
            Slider(
                value = masterVol.coerceIn(0f, 1f),
                onValueChange = onVolumeChange,
                modifier = Modifier.weight(1f).height(22.dp),
                colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
            )
            Text("${(masterVol * 100).toInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(38.dp), textAlign = TextAlign.End)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onCyclePlayMode,
                    modifier = Modifier.background(Color(0xFF1E293B), CircleShape)
                ) {
                    val icon = when (playMode) {
                        PlayMode.SEQUENTIAL -> Icons.Default.Repeat
                        PlayMode.REPEAT_ONE -> Icons.Default.RepeatOne
                        PlayMode.SHUFFLE -> Icons.Default.Shuffle
                    }
                    Icon(icon, null, tint = accentColor)
                }
                Text(playMode.label, fontSize = 9.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier
                    .size(134.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF3A4152), Color(0xFF1B202B))))
                    .border(
                        width = 4.dp,
                        color = if (isPlaying && skin == WalkmanSkin.W850I) accentColor.copy(alpha = pulse) else accentColor.copy(alpha = 0.7f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onPrev, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                    Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onNext, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                    Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Button(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    modifier = Modifier.size(58.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = if (skin == WalkmanSkin.WM1ZM2) Color.Black else Color.White, modifier = Modifier.size(30.dp))
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.background(Color(0xFF1E293B), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (isFavorite) Color(0xFFEF4444) else Color.White
                    )
                }
                Text(if (isFavorite) "Đã thích" else "Yêu thích", fontSize = 9.sp, color = if (isFavorite) Color(0xFFEF4444) else Color.LightGray, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun LargeCassetteReel(rotation: Float) {
    Canvas(modifier = Modifier.size(72.dp)) {
        drawCircle(color = Color(0xFF271C19), radius = size.minDimension / 2)
        drawCircle(color = Color(0xFFE2E8F0), radius = size.minDimension / 2.8f, style = Stroke(width = 6f))
        rotate(rotation) {
            for (i in 0 until 6) {
                rotate(i * 60f) {
                    drawLine(color = Color(0xFFE2E8F0), start = Offset(size.width / 2, size.height / 2 - 8f), end = Offset(size.width / 2, 10f), strokeWidth = 5f)
                }
            }
        }
        drawCircle(color = Color(0xFF0F172A), radius = 10f)
    }
}

@Composable
fun FiioStyleCategorizedLibraryView(
    context: Context,
    tracks: List<AudioTrack>,
    favoriteTitles: Set<String>,
    isScanning: Boolean,
    currentIndex: Int,
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

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141C2B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("QUẢN LÝ THƯ VIỆN NHẠC WALKMAN", fontWeight = FontWeight.Black, color = Color(0xFFEAB308), fontSize = 13.sp)
                    if (tracks.isNotEmpty()) {
                        TextButton(onClick = onClearSaved, contentPadding = PaddingValues(0.dp)) {
                            Text("Xóa DS", color = Color(0xFFEF4444), fontSize = 11.sp)
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onAutoScan,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5500), contentColor = Color.White),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Text("QUÉT TỰ ĐỘNG", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = onPickFolder,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7), contentColor = Color.White),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Text("LƯU THƯ MỤC", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = onPickFiles,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Text("+ CHỌN FILE", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tabs = listOf(
                        "Tất cả (${tracks.size})",
                        "♥ Yêu thích (${tracks.count { favoriteTitles.contains(it.title) }})",
                        "📁 Thư mục (${foldersMap.size})"
                    )
                    tabs.forEachIndexed { idx, label ->
                        val active = subTab == idx
                        OutlinedButton(
                            onClick = {
                                subTab = idx
                                selectedFolderFilter = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (active) Color(0xFFEAB308) else Color(0xFF0F172A),
                                contentColor = if (active) Color.Black else Color.White
                            ),
                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp)
                        ) {
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (subTab == 2 && selectedFolderFilter == null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val folderEntries = foldersMap.entries.toList()
                itemsIndexed(folderEntries) { _, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111622), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                            .clickable { selectedFolderFilter = entry.key }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = Color(0xFFEAB308), modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(entry.key, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${entry.value.size} bài hát lưu trong thư mục này", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.LightGray)
                    }
                }
            }
        } else {
            if (subTab == 2 && selectedFolderFilter != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { selectedFolderFilter = null }) {
                        Text("← Quay lại danh sách Thư mục", color = Color(0xFFEAB308), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Text("• [$selectedFolderFilter]", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(displayedTracks) { index, track ->
                    val isFav = favoriteTitles.contains(track.title)
                    val isPlayingTrack = tracks.getOrNull(currentIndex)?.id == track.id
                    val savedResumePos = PlaylistStorage.getSavedTrackPosition(context, track.title)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isPlayingTrack) Color(0xFF2B1B12) else Color(0xFF111622), RoundedCornerShape(10.dp))
                            .border(1.dp, if (isPlayingTrack) Color(0xFFEAB308) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { onSelectTrack(track) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${index + 1}. ${track.title}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val resumeText = if (savedResumePos > 10000) " • ⏱ Đang nghe: ${formatTimeMs(savedResumePos)}" else ""
                            Text("${track.artist} • 📁 ${track.folderInfo} • [${track.formatName}]$resumeText", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (track.duration > 0) {
                                Text(
                                    formatTimeMs(track.duration.toInt()),
                                    color = Color(0xFFEAB308),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            IconButton(onClick = { onToggleFavorite(track.title) }) {
                                Icon(
                                    imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isFav) Color(0xFFEF4444) else Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FullSonyDspAndAudiophileToolsView(
    masterVol: Float,
    megaBass: Float,
    vinylProcessor: Boolean,
    clearAudio: Boolean,
    dseeUltimate: Boolean,
    channelBalance: Float,
    phaseInvert: Boolean,
    crossfade: Boolean,
    tapeSpeed: Float,
    sleepTimerRemainingSec: Int,
    eq10Bands: List<Float>,
    audioOutput: AudioOutputState,
    onMasterVolChange: (Float) -> Unit,
    onSetSleepMinutes: (Int) -> Unit,
    onTapeSpeedChange: (Float) -> Unit,
    onCrossfadeToggle: (Boolean) -> Unit,
    onBalanceChange: (Float) -> Unit,
    onPhaseInvertToggle: (Boolean) -> Unit,
    onVinylToggle: (Boolean) -> Unit,
    onClearAudioToggle: (Boolean) -> Unit,
    onDseeToggle: (Boolean) -> Unit,
    onBassChange: (Float) -> Unit,
    onBand10Change: (Int, Float) -> Unit,
    onApplyPreset10: (List<Float>) -> Unit
) {
    val labels10 = listOf("31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")

    val presets10 = listOf(
        "Heavy" to listOf(6f, 5f, 3f, 1f, 0f, 1f, 2f, 4f, 5f, 6f),
        "Pop" to listOf(2f, 3f, 4f, 3f, 1f, 1f, 2f, 3f, 4f, 4f),
        "Jazz" to listOf(4f, 3f, 2f, 1f, -1f, 0f, 1f, 2f, 3f, 4f),
        "Vinyl Warm" to listOf(4f, 4f, 2f, 1f, 0f, 0f, 1f, 2f, 3f, 3f),
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SONY PURE AUDIO 7.0 + FIIO DSD ENGINE", fontWeight = FontWeight.Black, fontSize = 14.sp, color = Color(0xFFEAB308))
            Text("✓ V9.5 DIRECT DSD", color = Color(0xFF22C55E), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141C2B))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("🌙 HẸN GIỜ NGỦ (GIẢM ÂM TỪ TỪ 60S CUỐI)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                    Text(
                        text = if (sleepTimerRemainingSec > 0) "Còn ${sleepTimerRemainingSec / 60}p ${sleepTimerRemainingSec % 60}s" else "Đang tắt",
                        color = Color(0xFF22C55E),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "TẮT", 15 to "15p", 30 to "30p", 45 to "45p", 60 to "60p").forEach { (mins, label) ->
                        OutlinedButton(
                            onClick = { onSetSleepMinutes(mins) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Text(label, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("⚡ TỐC ĐỘ BĂNG & CAO ĐỘ (TAPE SPEED)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                    Text("${"%.2f".format(tapeSpeed)}x", color = Color(0xFF38BDF8), fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.85f to "0.85x Slow", 0.95f to "0.95x Tape", 1.0f to "1.0x Chuẩn", 1.10f to "1.10x Fast", 1.20f to "1.20x Night").forEach { (spd, title) ->
                        OutlinedButton(
                            onClick = { onTapeSpeedChange(spd) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 1.dp)
                        ) {
                            Text(title, fontSize = 9.sp, color = if (tapeSpeed == spd) Color(0xFFEAB308) else Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF1E293B))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Smooth Crossfade Transition", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Text("Vuốt mượt âm lượng khi chuyển qua bài hát mới", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = crossfade, onCheckedChange = onCrossfadeToggle)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("🎧 CÂN BẰNG KÊNH TRÁI / PHẢI (L/R BALANCE)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                    val balText = when {
                        channelBalance < -0.05f -> "L +${(-channelBalance * 100).roundToInt()}%"
                        channelBalance > 0.05f -> "R +${(channelBalance * 100).roundToInt()}%"
                        else -> "CENTER (0)"
                    }
                    Text(balText, color = Color(0xFFEAB308), fontWeight = FontWeight.Black, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("L", color = Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Slider(
                        value = channelBalance.coerceIn(-0.5f, 0.5f),
                        onValueChange = onBalanceChange,
                        valueRange = -0.5f..0.5f,
                        modifier = Modifier.weight(1f)
                    )
                    Text("R", color = Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    TextButton(onClick = { onBalanceChange(0f) }) {
                        Text("Giữa", color = Color(0xFF38BDF8), fontSize = 11.sp)
                    }
                }
                HorizontalDivider(color = Color(0xFF1E293B))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Phase Inverter (Đảo pha âm thanh 180°)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Text("Đảo cực tín hiệu PCM cho USB-DAC & tai nghe cao cấp", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = phaseInvert, onCheckedChange = onPhaseInvertToggle)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF241C0C)),
            border = BorderStroke(1.dp, Color(0xFFEAB308))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Vinyl Processor™ (7.0 Pure Warmth)", fontWeight = FontWeight.Black, color = Color(0xFFEAB308), fontSize = 14.sp)
                        Text("Chất âm ấm chuẩn 7.0 kèm mạch Clean Headroom chống rè loa ngoài điện thoại", color = Color.LightGray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = vinylProcessor,
                        onCheckedChange = onVinylToggle,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFEAB308), checkedTrackColor = Color(0xFF854D0E))
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ClearAudio+™ (Mid-Side 3D Stereo)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Text("Mở rộng sân khấu âm thanh trong trẻo không méo tiếng", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = clearAudio, onCheckedChange = onClearAudioToggle)
                }
                HorizontalDivider(color = Color(0xFF1E293B))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("DSEE HX™ (Harmonic Exciter)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Text("Tái tạo dải cao mượt mà chuẩn Walkman 7.0", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = dseeUltimate, onCheckedChange = onDseeToggle)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CLEARBASS / SUB-BASS RESONANCE", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text("+${(megaBass * 10).toInt()} dB", color = Color(0xFFFF6600), fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = megaBass.coerceIn(0f, 1f),
                    onValueChange = onBassChange,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFF5500), activeTrackColor = Color(0xFFFF5500))
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SONY 10-BAND GRAPHIC EQUALIZER", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets10.forEach { (name, vals) ->
                        OutlinedButton(
                            onClick = { onApplyPreset10(vals) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 1.dp)
                        ) {
                            Text(name, fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                labels10.forEachIndexed { idx, label ->
                    val currentVal = eq10Bands.getOrElse(idx) { 0f }.coerceIn(-8f, 8f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.width(48.dp), fontSize = 10.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                        Slider(
                            value = currentVal,
                            onValueChange = { onBand10Change(idx, it) },
                            valueRange = -8f..8f,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "%+.0f dB".format(currentVal),
                            modifier = Modifier.width(42.dp),
                            fontSize = 10.sp,
                            color = Color(0xFFFF8800),
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}
