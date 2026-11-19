package com.sony.walkmanpro

import android.Manifest
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.sony.walkmanpro.audio.decoder.DsdHeader
import com.sony.walkmanpro.playback.PlaybackController
import com.sony.walkmanpro.playback.isDsd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val DapChassisBlack = Color(0xFF090A0D)
private val DapBodyDark = Color(0xFF151820)
private val DapGlassBezel = Color(0xFF050608)
private val PanelSurface = Color(0xFF171A22)
private val ElevatedMetallic = Color(0xFF222733)
private val BezelBorder = Color(0xFF2D3342)
private val ChampagneGold = Color(0xFFC8B588)
private val ChampagneGoldDim = Color(0xFF827350)
private val EchoCrimsonRed = Color(0xFFE11D48)
private val EchoProgressRed = Color(0xFFDC2626)
private val EchoCyanSpec = Color(0xFF38BDF8)
private val RetroStripeOrange = Color(0xFFEA580C)
private val RetroStripeGold = Color(0xFFEAB308)
private val RetroStripeTeal = Color(0xFF0D9488)
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
    val deviceType: String = "Unknown",
    val isBluetooth: Boolean = false,
    val deviceName: String = "Unknown",
    val codecEstimate: String = "Unknown",
    val batteryPercent: Int = 0,
    val connectedDevice: String? = null,
    val supportedRates: List<Int> = emptyList()
)

private data class TrackSpecs(
    val bitrateKbps: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: String? = null
) {
    val description get() = listOf(sampleRate?.let { formatRate(it) } ?: "Unknown", bitDepth ?: "Unknown")
        .joinToString(" | ")
}

private fun formatRate(hz: Int) = if (hz >= 1_000_000) "${hz / 1_000_000.0} MHz" else "${hz / 1000.0} kHz"

private fun pcmEncodingLabel(encoding: Int) = when (encoding) {
    C.ENCODING_PCM_8BIT -> "PCM 8-bit"
    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "PCM 16-bit"
    C.ENCODING_PCM_24BIT -> "PCM 24-bit"
    C.ENCODING_PCM_32BIT -> "PCM 32-bit"
    C.ENCODING_PCM_FLOAT -> "PCM float"
    else -> "Unknown"
}

@UnstableApi
class MainActivity : ComponentActivity() {
    private val backgroundHandler = Handler(Looper.getMainLooper())
    private var playback by mutableStateOf<PlaybackController?>(null)
    private var serviceBound = false
    private var screenStarted by mutableStateOf(false)
    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playback = (binder as? WalkmanMediaSessionService.PlaybackBinder)?.playback
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceBound = bindService(Intent(this, WalkmanMediaSessionService::class.java),
            playbackConnection, Context.BIND_AUTO_CREATE)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = DapChassisBlack,
                    surface = PanelSurface,
                    primary = ChampagneGold,
                    onPrimary = DapChassisBlack,
                    onBackground = TextPure,
                    onSurface = TextPure
                )
            ) {
                val controller = playback
                if (controller == null) {
                    Box(Modifier.fillMaxSize().background(DapChassisBlack))
                } else {
                    SnowskyEchoDapApp(
                        playback = controller,
                        screenStarted = screenStarted,
                        onAutoScanAll = { scanAllMusicSources(this@MainActivity) },
                        onParseManualUris = { uris -> parseManualFiles(this@MainActivity, uris) },
                        onParseFolderUri = { treeUri -> parseFolderTree(this@MainActivity, treeUri) },
                        onSetKeepScreenOn = ::setDeskScreenKeepOn,
                        onToggleOrientation = ::toggleScreenOrientation,
                        getOutputState = { detectAudioOutputState(controller.dsdRoute) },
                        getTrackSpecs = { inspectTrackSpecs(this@MainActivity, it) }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        screenStarted = true
    }

    override fun onStop() {
        playback?.persist()
        screenStarted = false
        super.onStop()
    }

    override fun onDestroy() {
        if (serviceBound) unbindService(playbackConnection)
        backgroundHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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

    private fun setDeskScreenKeepOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun detectAudioOutputState(route: AudioDeviceInfo?): AudioOutputState {
        val battery = getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val audioManager = getSystemService(AudioManager::class.java)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val external = outputs.filter { it.type in externalOutputTypes }
        val candidate = route ?: external.singleOrNull()
        val label = when (candidate?.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB DAC"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphones"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            else -> "Unknown"
        }
        val connected = external.mapNotNull { it.productName?.toString() }.distinct().joinToString()
        return AudioOutputState(label, label == "Bluetooth", route?.productName?.toString() ?: "Unknown",
            "Unknown", battery, connected.ifEmpty { null },
            candidate?.sampleRates?.filter { it > 0 }?.distinct()?.sorted().orEmpty())
    }

    private companion object {
        val externalOutputTypes = setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
    }

    private fun inspectTrackSpecs(context: Context, track: AudioTrack): TrackSpecs {
        if (track.isDsd()) {
            val header = runCatching {
                context.contentResolver.openInputStream(track.uri)?.use(DsdHeader::read)
            }.getOrNull() ?: return TrackSpecs()
            val bitrate = if (header.compressed) null else header.sampleRate * header.channels / 1000
            return TrackSpecs(bitrate, header.sampleRate, if (header.compressed) "1-bit DST" else "1-bit")
        }

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, track.uri, null)
            val format = (0 until extractor.trackCount).asSequence().map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return TrackSpecs()
            val encoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING)
            val bits = when (encoding) {
                C.ENCODING_PCM_8BIT -> "8-bit"
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16-bit"
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED, C.ENCODING_PCM_24BIT -> "24-bit"
                android.media.AudioFormat.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT -> "32-bit"
                C.ENCODING_PCM_FLOAT -> "32-bit float"
                else -> format.intOrNull("bits-per-sample")?.let { "$it-bit" }
            }
            TrackSpecs(format.intOrNull(MediaFormat.KEY_BIT_RATE)?.div(1000),
                format.intOrNull(MediaFormat.KEY_SAMPLE_RATE), bits)
        } catch (_: Exception) {
            TrackSpecs()
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String) =
        if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null

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
                        val rawArtist = if (artistCol >= 0) c.getString(artistCol) ?: "" else ""
                        val artist = if (rawArtist.isBlank() || rawArtist.contains("<unknown>", true)) "Unknown Artist" else rawArtist
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
            added.add(AudioTrack(System.currentTimeMillis() + added.size, fn.substringBeforeLast("."), "Unknown Artist", 0L, uri, "Music", sz, fn.substringAfterLast(".", "WAV").uppercase()))
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
                            added.add(AudioTrack(System.currentTimeMillis() + added.size, n.substringBeforeLast("."), dir.name ?: "Unknown Artist", 0L, f.uri, dir.name ?: "Music", f.length(), n.substringAfterLast(".", "WAV").uppercase()))
                        }
                    }
                }
            }
        }
        try { scanDir(rootDir) } catch (_: Throwable) {}
        return Pair(added, skipped)
    }
}

fun formatTimeMs(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val hrs = totalSec / 3600
    val min = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return "%d:%02d:%02d".format(hrs, min, sec)
}

@Composable
@UnstableApi
private fun SnowskyEchoDapApp(
    playback: PlaybackController,
    screenStarted: Boolean,
    onAutoScanAll: () -> Pair<List<AudioTrack>, Int>,
    onParseManualUris: (List<Uri>) -> List<AudioTrack>,
    onParseFolderUri: (Uri) -> Pair<List<AudioTrack>, Int>,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onToggleOrientation: () -> Unit,
    getOutputState: () -> AudioOutputState,
    getTrackSpecs: (AudioTrack) -> TrackSpecs
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by playback.state.collectAsStateWithLifecycle()

    var currentScreen by remember { mutableStateOf(AppScreen.NOW_PLAYING) }
    val playlist = state.playlist
    val playMode = state.mode
    val currentIndex = state.index
    val posMs = state.position
    val durMs = state.duration
    val sleepTimerRemainingSec = state.sleepRemaining
    val currentTrack = state.track
    var favoriteTitles by remember { mutableStateOf(PlaylistStorage.loadFavorites(context)) }
    var isScanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    var otaAutoCheck by remember { mutableStateOf(OtaUpdateManager.isAutoCheckEnabled(context)) }
    var otaBeta by remember { mutableStateOf(OtaUpdateManager.isBetaEnabled(context)) }
    var otaIsChecking by remember { mutableStateOf(false) }
    var otaAvailableRelease by remember { mutableStateOf<OtaReleaseInfo?>(null) }
    var otaIsDownloading by remember { mutableStateOf(false) }
    var otaInstallJob by remember { mutableStateOf<Job?>(null) }
    var otaDownloadPercent by remember { mutableStateOf(0) }
    var otaDownloadedMb by remember { mutableStateOf(0f) }
    var otaTotalMb by remember { mutableStateOf(0f) }
    var otaDownloadedFile by remember { mutableStateOf<File?>(null) }
    var otaErrorMsg by remember { mutableStateOf<String?>(null) }

    val dspPrefs = context.getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
    var masterVol by remember { mutableStateOf(dspPrefs.getFloat("dsp_master_vol", 0.75f)) }
    var pureDsdMode by remember { mutableStateOf(dspPrefs.getBoolean("dsp_pure_dsd_mode", false)) }
    var megaBass by remember { mutableStateOf(dspPrefs.getFloat("dsp_bass", 0.25f)) }
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

    val eq10Bands = remember {
        mutableStateListOf(
            dspPrefs.getFloat("dsp_eq_0", 0.0f),
            dspPrefs.getFloat("dsp_eq_1", 0.0f),
            dspPrefs.getFloat("dsp_eq_2", 0.0f),
            dspPrefs.getFloat("dsp_eq_3", 0.0f),
            dspPrefs.getFloat("dsp_eq_4", 0.0f),
            dspPrefs.getFloat("dsp_eq_5", 0.0f),
            dspPrefs.getFloat("dsp_eq_6", 0.0f),
            dspPrefs.getFloat("dsp_eq_7", 0.0f),
            dspPrefs.getFloat("dsp_eq_8", 0.0f),
            dspPrefs.getFloat("dsp_eq_9", 0.0f)
        )
    }

    var audioOutput by remember { mutableStateOf(getOutputState()) }
    var trackSpecs by remember(currentTrack?.uri) { mutableStateOf(TrackSpecs()) }
    var pipeline by remember(currentTrack?.uri) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val currentBitrate = trackSpecs.bitrateKbps ?: 0
    val currentSampleRate = trackSpecs.description

    LaunchedEffect(currentTrack?.uri) {
        currentTrack?.let { track -> trackSpecs = withContext(Dispatchers.IO) { getTrackSpecs(track) } }
    }
    LaunchedEffect(screenStarted, currentScreen, currentTrack?.uri, state.wantsPlayback) {
        if (!screenStarted) return@LaunchedEffect
        do {
            audioOutput = getOutputState()
            if (currentScreen == AppScreen.AUDIO_LAB) {
                val dsd = playback.dsdOutput
                val pcm = playback.pcmFormat
                val source = playback.pcmSource
                pipeline = listOf(
                    "Decoder" to when {
                        dsd != null -> "FFmpeg ${FFmpegNativeDsdDecoder.nativeGetFFmpegVersion()}"
                        source != null -> "Media3 ${source.sampleMimeType ?: ""}".trim()
                        else -> "Unknown"
                    },
                    "Decoder PCM rate" to (dsd?.decoderRate?.let(::formatRate) ?: pcm?.sampleRate?.let(::formatRate) ?: "Unknown"),
                    "DSP input" to when {
                        dsd != null -> "PCM float • ${formatRate(dsd.sampleRate)}"
                        pcm != null -> "${pcmEncodingLabel(pcm.pcmEncoding)} • ${pcm.channelCount} ch"
                        else -> "Unknown"
                    },
                    "Requested output rate" to (dsd?.sampleRate?.let(::formatRate) ?: pcm?.sampleRate?.let(::formatRate) ?: "Unknown"),
                    "Hardware output rate" to "Unknown",
                    "App resampling" to if (dsd != null) {
                        if (dsd.decoderRate == dsd.sampleRate) "Same rate" else "${formatRate(dsd.decoderRate)} → ${formatRate(dsd.sampleRate)}"
                    } else if (pcm != null) "Same nominal rate" else "Unknown",
                    "DSD conversion" to if (dsd == null) "—" else if (dsd.sourceChannels > 2) "DSD → PCM • stereo downmix" else "DSD → PCM"
                )
            }
            delay(1_000)
        } while (true)
    }

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

    fun scanLibrary(load: () -> List<AudioTrack>) {
        if (isScanning) return
        isScanning = true
        scanJob = scope.launch {
            try {
                val added = withContext(Dispatchers.IO) { load() }
                val latest = playback.state.value.playlist
                val merged = (added + latest).distinctBy { "${it.title.trim().lowercase()}_${it.sizeBytes}" }
                val encoded = withContext(Dispatchers.IO) { PlaylistStorage.encodePlaylist(merged) }
                PlaylistStorage.savePlaylist(context, encoded)
                playback.replacePlaylist(merged)
                Toast.makeText(context, "Đã cập nhật ${merged.size} bài hát", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, "Không thể cập nhật thư viện.", Toast.LENGTH_SHORT).show()
            } finally {
                isScanning = false
            }
        }
    }

    fun runDeduplicatedScan() = scanLibrary {
        val folders = PlaylistStorage.getSavedFolderUris(context).flatMap { onParseFolderUri(it).first }
        folders + onAutoScanAll().first
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runDeduplicatedScan()
    }

    val manualFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scanLibrary { onParseManualUris(uris) }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scanLibrary {
            PlaylistStorage.addSavedFolderUri(context, uri)
            onParseFolderUri(uri).first
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
        if (otaAutoCheck) {
            delay(1500)
            triggerOtaCheck(showToastWhenUpToDate = false)
        }
    }

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
                AppScreen.NOW_PLAYING -> ExactReferenceCassetteScreen(
                    track = currentTrack,
                    trackIndex = currentIndex,
                    totalTracks = playlist.size,
                    formatName = currentTrack?.formatName ?: "—",
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
                    isPlaying = state.isPlaying && screenStarted,
                    playMode = playMode,
                    posMs = posMs,
                    durMs = durMs,
                    onSeek = playback::seek,
                    onToggleShuffle = {
                        playback.changeMode(if (playMode == PlayMode.SHUFFLE) PlayMode.SEQUENTIAL else PlayMode.SHUFFLE)
                    },
                    onToggleRepeat = {
                        playback.changeMode(when (playMode) {
                            PlayMode.SEQUENTIAL -> PlayMode.REPEAT_ONE
                            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
                            PlayMode.SHUFFLE -> PlayMode.SEQUENTIAL
                        })
                    },
                    onPlayPause = {
                        if (playlist.isEmpty()) currentScreen = AppScreen.LIBRARY else playback.toggle()
                    },
                    onPrev = { playback.advance(-1) },
                    onNext = { playback.advance(1) },
                    onToggleOrientation = onToggleOrientation,
                    onOpenLibrary = { currentScreen = AppScreen.LIBRARY },
                    onOpenAudioLab = {
                        audioOutput = getOutputState()
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
                        scanJob?.cancel()
                        PlaylistStorage.savePlaylist(context, "[]")
                        playback.replacePlaylist(emptyList())
                    },
                    onSelectTrack = { track ->
                        val realIndex = playlist.indexOfFirst { it.uri == track.uri }
                        if (realIndex >= 0) {
                            playback.select(realIndex)
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
                    pipeline = pipeline,
                    otaBeta = otaBeta,
                    onOtaBetaToggle = { enabled ->
                        otaBeta = enabled
                        OtaUpdateManager.setBetaEnabled(context, enabled)
                    },
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
                        playback.setDirect(it)
                    },
                    onMasterVolChange = {
                        masterVol = it
                        playback.setVolume(it)
                    },
                    onBassChange = {
                        megaBass = it
                        playback.setBass(it)
                    },
                    onClearAudioToggle = {
                        clearAudioPlus = it
                        playback.setClarity(it)
                    },
                    onDseeToggle = {
                        dseeUltimate = it
                        playback.setTreble(it)
                    },
                    onVinylToggle = {
                        vinylProcessor = it
                        playback.setVinyl(it)
                    },
                    onPhaseInvertToggle = {
                        phaseInvert = it
                        playback.setInvert(it)
                    },
                    onBalanceChange = { rawBal ->
                        val snapped = if (abs(rawBal) < 0.03f) 0f else rawBal.coerceIn(-0.5f, 0.5f)
                        channelBalance = snapped
                        playback.setBalance(snapped)
                    },
                    onBand10Change = { idx, gain ->
                        eq10Bands[idx] = gain
                        playback.setEq(idx, gain)
                    },
                    onResetEq = {
                        for (i in 0 until 10) {
                            eq10Bands[i] = 0f
                            playback.setEq(i, 0f)
                        }
                    },
                    onCrossfadeToggle = {
                        crossfadeEnabled = it
                        playback.setFade(it)
                    },
                    onTapeSpeedChange = {
                        tapeSpeed = it
                        playback.setSpeed(it)
                    },
                    onKeepScreenOnToggle = {
                        keepScreenOnDeskMode = it
                        onSetKeepScreenOn(it)
                    },
                    onSetSleepMinutes = playback::setSleepMinutes
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
                            }.onFailure { err ->
                                otaErrorMsg = err.localizedMessage ?: "Lỗi tải gói cập nhật APK."
                            }
                        }
                    }
                },
                onInstallDownloaded = { file ->
                    if (otaInstallJob?.isActive != true) {
                        otaErrorMsg = null
                        otaInstallJob = scope.launch {
                            OtaUpdateManager.triggerApkInstallation(context, file, release).onFailure { err ->
                                otaErrorMsg = err.localizedMessage
                            }
                        }
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
                            text = if (isDownloading) "Đang tải..." else "Tải APK",
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
 * MÀN HÌNH NOW PLAYING (CẢ LANDSCAPE & PORTRAIT ĐỀU ĐẶT DẢI NÚT BẤM VECTOR 3D Ở HÀNG NGANG PHÍA DƯỚI)
 * =============================================================================================
 */
@Composable
fun ExactReferenceCassetteScreen(
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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val safeMaxDur = durMs.toFloat().coerceAtLeast(1f)
    val safeCurrPos = posMs.toFloat().coerceIn(0f, safeMaxDur)
    val progressFraction = (safeCurrPos / safeMaxDur).coerceIn(0f, 1f)

    // Giữ nguyên 100% logic vật lý chuyển động băng
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
    val specLineStr = "$formatName | $sampleRateText | " + if (bitrateKbps > 0) "${bitrateKbps}kbps" else "—"
    val trackTitleStr = track?.title ?: "Chưa chọn bài hát"
    val trackArtistStr = track?.artist?.takeIf { !it.contains("<unknown>", true) } ?: "Unknown Artist"

    if (isLandscape) {
        // =====================================================================================
        // CHẾ ĐỘ NGANG (LANDSCAPE):
        // - Phía trên: Cuốn băng Cassette lớn nằm chính giữa màn hình (giữ đúng tỷ lệ chuẩn 1.58:1)
        // - Phía dưới: Toàn bộ nút điều khiển Vector 3D xếp thành 1 hàng ngang cân đối, dễ bấm!
        // =====================================================================================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0C0E13), Color(0xFF07080B), Color(0xFF040507))
                    )
                )
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Phần trên: Khung máy chứa băng Cassette lớn ở trung tâm
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1.62f, matchHeightConstraintsFirst = true)
                        .shadow(22.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF2B303A), Color(0xFF1B1E25), Color(0xFF101217))
                            )
                        )
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF525A6B), Color(0xFF282D38), Color(0xFF12151B))
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(7.dp)
                ) {
                    RetroVectorCassetteUnit(
                        trackTitleStr = trackTitleStr,
                        trackArtistStr = trackArtistStr,
                        trackOrderStr = trackOrderStr,
                        specLineStr = specLineStr,
                        masterVolumePercent = masterVolumePercent,
                        audioOutput = audioOutput,
                        playMode = playMode,
                        isFavorite = isFavorite,
                        isPlaying = isPlaying,
                        posMs = posMs,
                        durMs = durMs,
                        progressFraction = progressFraction,
                        cumulativeTapeDistance = cumulativeTapeDistance,
                        onSeek = onSeek,
                        onPlayPause = onPlayPause,
                        onPrev = onPrev,
                        onNext = onNext,
                        onToggleShuffle = onToggleShuffle,
                        onToggleRepeat = onToggleRepeat,
                        onToggleFavorite = onToggleFavorite,
                        onOpenLibrary = onOpenLibrary,
                        onOpenAudioLab = onOpenAudioLab
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Phần dưới: Hàng nút bấm Vector 3D nằm ngang ngay dưới Cassette
            Vector3DTransportDeck(
                isLandscape = true,
                isPlaying = isPlaying,
                playMode = playMode,
                onToggleShuffle = onToggleShuffle,
                onPrev = onPrev,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onToggleRepeat = onToggleRepeat,
                onOpenLibrary = onOpenLibrary,
                onOpenAudioLab = onOpenAudioLab,
                onToggleOrientation = onToggleOrientation
            )
        }
    } else {
        // =====================================================================================
        // CHẾ ĐỘ DỌC (PORTRAIT):
        // - Khung thân máy DAP cân đối ở trên + Hàng nút bấm Vector 3D nổi khối ở dưới
        // =====================================================================================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0C0E13), Color(0xFF07080B), Color(0xFF040507))
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Gờ nút cứng trên đỉnh máy
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .padding(bottom = 0.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFFFB7185), EchoCrimsonRed, Color(0xFF9F1239))))
                            .clickable { onPlayPause() }
                    )
                    Box(
                        modifier = Modifier
                            .width(70.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFF475063), Color(0xFF1B1E24))))
                            .clickable { onPrev() }
                    )
                    Box(
                        modifier = Modifier
                            .width(46.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFF475063), Color(0xFF1B1E24))))
                            .clickable { onNext() }
                    )
                    Box(
                        modifier = Modifier
                            .width(46.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFF475063), Color(0xFF1B1E24))))
                            .clickable { onOpenLibrary() }
                    )
                    Box(
                        modifier = Modifier
                            .width(46.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFF475063), Color(0xFF1B1E24))))
                            .clickable { onOpenAudioLab() }
                    )
                }

                // Khung thân máy DAP
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .shadow(24.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF2B2F37), Color(0xFF1E2128), Color(0xFF14161B))
                            )
                        )
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF525A6B), Color(0xFF252830), Color(0xFF121418))
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    // Màn hình kính đen chứa Cassette
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.83f)
                            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(DapGlassBezel)
                            .border(1.5.dp, Color(0xFF14161B), RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        RetroVectorCassetteUnit(
                            trackTitleStr = trackTitleStr,
                            trackArtistStr = trackArtistStr,
                            trackOrderStr = trackOrderStr,
                            specLineStr = specLineStr,
                            masterVolumePercent = masterVolumePercent,
                            audioOutput = audioOutput,
                            playMode = playMode,
                            isFavorite = isFavorite,
                            isPlaying = isPlaying,
                            posMs = posMs,
                            durMs = durMs,
                            progressFraction = progressFraction,
                            cumulativeTapeDistance = cumulativeTapeDistance,
                            onSeek = onSeek,
                            onPlayPause = onPlayPause,
                            onPrev = onPrev,
                            onNext = onNext,
                            onToggleShuffle = onToggleShuffle,
                            onToggleRepeat = onToggleRepeat,
                            onToggleFavorite = onToggleFavorite,
                            onOpenLibrary = onOpenLibrary,
                            onOpenAudioLab = onOpenAudioLab
                        )
                    }

                    // Cằm máy vát kim loại sạch sẽ (tích hợp 3 nút truy cập nhanh Library / Rotate / Audio Lab)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.17f)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF1B1E24), Color(0xFF22262E), Color(0xFF181B20))
                                )
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    listOf(Color(0xFF101216), Color(0xFF323742))
                                ),
                                shape = RoundedCornerShape(5.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onOpenLibrary, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = "Library",
                                    tint = ColdSilver.copy(alpha = 0.75f),
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            IconButton(onClick = onToggleOrientation, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    imageVector = Icons.Default.ScreenRotation,
                                    contentDescription = "Landscape",
                                    tint = ColdSilver.copy(alpha = 0.65f),
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            IconButton(onClick = onOpenAudioLab, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Audio Lab",
                                    tint = ChampagneGold.copy(alpha = 0.85f),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Hàng nút bấm Vector 3D ở chế độ dọc
            Vector3DTransportDeck(
                isLandscape = false,
                isPlaying = isPlaying,
                playMode = playMode,
                onToggleShuffle = onToggleShuffle,
                onPrev = onPrev,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onToggleRepeat = onToggleRepeat,
                onOpenLibrary = onOpenLibrary,
                onOpenAudioLab = onOpenAudioLab,
                onToggleOrientation = onToggleOrientation
            )
        }
    }
}

/**
 * =============================================================================================
 * BỘ KHUNG BĂNG CASSETTE RETRO VECTOR CHÂN THỰC
 * =============================================================================================
 */
@Composable
fun RetroVectorCassetteUnit(
    trackTitleStr: String,
    trackArtistStr: String,
    trackOrderStr: String,
    specLineStr: String,
    masterVolumePercent: Int,
    audioOutput: AudioOutputState,
    playMode: PlayMode,
    isFavorite: Boolean,
    isPlaying: Boolean,
    posMs: Int,
    durMs: Int,
    progressFraction: Float,
    cumulativeTapeDistance: Float,
    onSeek: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenAudioLab: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(12.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF353A45), Color(0xFF262A33), Color(0xFF1B1E25)),
                    start = Offset(0f, 0f),
                    end = Offset(900f, 700f)
                )
            )
            .border(
                width = 1.6.dp,
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF60687A), Color(0xFF333844), Color(0xFF191C22))
                ),
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = Offset(14.dp.toPx(), 1.dp.toPx()),
                end = Offset(w - 14.dp.toPx(), 1.dp.toPx()),
                strokeWidth = 1.2.dp.toPx()
            )

            drawRoundRect(
                color = Color.Black.copy(alpha = 0.45f),
                topLeft = Offset(7.dp.toPx(), 7.dp.toPx()),
                size = Size(w - 14.dp.toPx(), h - 14.dp.toPx()),
                cornerRadius = CornerRadius(7.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.08f),
                topLeft = Offset(8.dp.toPx(), 8.dp.toPx()),
                size = Size(w - 16.dp.toPx(), h - 16.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(width = 0.9.dp.toPx())
            )

            val lblLeft = w * 0.055f
            val lblRight = w * 0.945f
            val lblTop = h * 0.13f
            val lblBottom = h * 0.81f
            val cut = 12.dp.toPx()

            val labelPath = Path().apply {
                moveTo(lblLeft + cut, lblTop)
                lineTo(lblRight - cut, lblTop)
                lineTo(lblRight, lblTop + cut)
                lineTo(lblRight, lblBottom - cut * 0.6f)
                lineTo(lblRight - cut * 0.6f, lblBottom)
                lineTo(lblLeft + cut * 0.6f, lblBottom)
                lineTo(lblLeft, lblBottom - cut * 0.6f)
                lineTo(lblLeft, lblTop + cut)
                close()
            }

            drawPath(
                path = labelPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF222630), Color(0xFF2B303C), Color(0xFF1E222B)),
                    startY = lblTop,
                    endY = lblBottom
                )
            )

            clipPath(labelPath) {
                val stripeY = lblBottom - 26.dp.toPx()
                drawRect(
                    color = RetroStripeOrange.copy(alpha = 0.22f),
                    topLeft = Offset(lblLeft, stripeY),
                    size = Size(lblRight - lblLeft, 2.5.dp.toPx())
                )
                drawRect(
                    color = RetroStripeGold.copy(alpha = 0.18f),
                    topLeft = Offset(lblLeft, stripeY + 3.5.dp.toPx()),
                    size = Size(lblRight - lblLeft, 2.dp.toPx())
                )
                drawRect(
                    color = RetroStripeTeal.copy(alpha = 0.18f),
                    topLeft = Offset(lblLeft, stripeY + 6.5.dp.toPx()),
                    size = Size(lblRight - lblLeft, 2.dp.toPx())
                )
            }

            drawPath(
                path = labelPath,
                color = Color(0xFF495163),
                style = Stroke(width = 1.1.dp.toPx())
            )

            val redDotY = h * 0.90f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFB91C1C).copy(alpha = 0.60f), Color.Transparent),
                    center = Offset(w * 0.11f, redDotY),
                    radius = 11.dp.toPx()
                ),
                radius = 11.dp.toPx(),
                center = Offset(w * 0.11f, redDotY)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFB91C1C).copy(alpha = 0.60f), Color.Transparent),
                    center = Offset(w * 0.89f, redDotY),
                    radius = 11.dp.toPx()
                ),
                radius = 11.dp.toPx(),
                center = Offset(w * 0.89f, redDotY)
            )
        }

        CassetteCornerScrew(Modifier.align(Alignment.TopStart).padding(10.dp))
        CassetteCornerScrew(Modifier.align(Alignment.TopEnd).padding(10.dp))
        CassetteCornerScrew(Modifier.align(Alignment.BottomStart).padding(10.dp))
        CassetteCornerScrew(Modifier.align(Alignment.BottomEnd).padding(10.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 6.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // A. THANH TRẠNG THÁI HUD TRÊN CÙNG
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "$masterVolumePercent",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Icon(
                        imageVector = if (audioOutput.isBluetooth) Icons.Default.BluetoothAudio else Icons.Default.Headphones,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )

                    Text(
                        text = trackOrderStr,
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { onOpenLibrary() }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playMode == PlayMode.SHUFFLE) EchoCyanSpec else Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .size(13.dp)
                            .clickable { onToggleShuffle() }
                    )

                    Box(
                        modifier = Modifier
                            .border(1.dp, Color.White, RoundedCornerShape(2.dp))
                            .clickable { onToggleRepeat() }
                            .padding(horizontal = 3.dp, vertical = 0.5.dp)
                    ) {
                        Text(
                            text = playMode.badgeCode,
                            color = Color.White,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.SdStorage,
                        contentDescription = "Library",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .size(13.dp)
                            .clickable { onOpenLibrary() }
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onOpenAudioLab() }
                    ) {
                        Row(
                            modifier = Modifier
                                .border(1.2.dp, Color.White, RoundedCornerShape(2.dp))
                               .padding(horizontal = 2.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(1.2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val activeBars = ((audioOutput.batteryPercent + 15) / 25).coerceIn(1, 4)
                            for (b in 1..4) {
                                Box(
                                    modifier = Modifier
                                        .width(2.5.dp)
                                        .height(6.dp)
                                        .background(if (b <= activeBars) Color.White else Color.White.copy(alpha = 0.2f))
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(1.8.dp)
                                .height(4.dp)
                                .background(Color.White)
                        )
                    }
                }
            }

            // B. TÊN BÀI HÁT TRÊN NHÃN BĂNG
            Text(
                text = trackTitleStr,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                letterSpacing = 0.5.sp,
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .padding(vertical = 1.dp)
            )

            // C. CỬA SỔ CASSETTE TRUNG TÂM
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.74f)
                    .weight(1f)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                when {
                                    offset.x < size.width * 0.33f -> onPrev()
                                    offset.x > size.width * 0.67f -> onNext()
                                    else -> onPlayPause()
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val centerY = h * 0.50f
                    val pillHeight = (h * 0.88f).coerceAtMost(w * 0.45f)
                    val pillTop = centerY - pillHeight / 2f
                    val pillCorner = pillHeight / 2f

                    val leftX = pillCorner * 0.98f
                    val rightX = w - pillCorner * 0.98f

                    val hubRadius = pillHeight * 0.38f
                    val minSpoolR = hubRadius * 1.06f
                    val maxSpoolR = pillHeight * 0.88f
                    val minArea = minSpoolR * minSpoolR
                    val maxArea = maxSpoolR * maxSpoolR

                    val leftSpoolR = sqrt(maxArea - progressFraction * (maxArea - minArea))
                    val rightSpoolR = sqrt(minArea + progressFraction * (maxArea - minArea))

                    val leftAngle = -((cumulativeTapeDistance * (maxSpoolR / leftSpoolR.coerceAtLeast(1f))) % 360f)
                    val rightAngle = -((cumulativeTapeDistance * (maxSpoolR / rightSpoolR.coerceAtLeast(1f))) % 360f)

                    drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to Color(0xFF060709),
                            0.76f to Color(0xFF0A0C10).copy(alpha = 0.92f),
                            0.94f to Color(0xFF151820).copy(alpha = 0.65f),
                            1.0f to Color.Transparent,
                            center = Offset(leftX, centerY),
                            radius = leftSpoolR.coerceAtLeast(1f)
                        ),
                        radius = leftSpoolR,
                        center = Offset(leftX, centerY)
                    )

                    drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to Color(0xFF060709),
                            0.76f to Color(0xFF0A0C10).copy(alpha = 0.92f),
                            0.94f to Color(0xFF151820).copy(alpha = 0.65f),
                            1.0f to Color.Transparent,
                            center = Offset(rightX, centerY),
                            radius = rightSpoolR.coerceAtLeast(1f)
                        ),
                        radius = rightSpoolR,
                        center = Offset(rightX, centerY)
                    )

                    val pillPath = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = 0f,
                                top = pillTop,
                                right = w,
                                bottom = pillTop + pillHeight,
                                cornerRadius = CornerRadius(pillCorner, pillCorner)
                            )
                        )
                    }

                    drawPath(
                        path = pillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFF111319), Color(0xFF21252E), Color(0xFF111319)),
                            startY = pillTop,
                            endY = pillTop + pillHeight
                        )
                    )

                    clipPath(pillPath) {
                        listOf(
                            Triple(leftX, leftSpoolR, leftAngle),
                            Triple(rightX, rightSpoolR, rightAngle)
                        ).forEach { (cx, spoolR, angle) ->
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.7f),
                                radius = spoolR + 3.dp.toPx(),
                                center = Offset(cx + 2.dp.toPx(), centerY + 2.dp.toPx())
                            )

                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0xFF2B1E1A), Color(0xFF140D0B), Color(0xFF241815)),
                                    center = Offset(cx, centerY),
                                    radius = spoolR.coerceAtLeast(1f)
                                ),
                                radius = spoolR,
                                center = Offset(cx, centerY)
                            )

                            rotate(angle * 0.4f - 25f, pivot = Offset(cx, centerY)) {
                                drawCircle(
                                    brush = Brush.sweepGradient(
                                        0.00f to Color.Transparent,
                                        0.15f to Color(0xFF6E4B3E).copy(alpha = 0.38f),
                                        0.30f to Color.Transparent,
                                        0.50f to Color.Transparent,
                                        0.65f to Color(0xFF6E4B3E).copy(alpha = 0.38f),
                                        0.80f to Color.Transparent,
                                        1.00f to Color.Transparent,
                                        center = Offset(cx, centerY)
                                    ),
                                    radius = spoolR,
                                    center = Offset(cx, centerY)
                                )
                            }

                            val thick = (spoolR - hubRadius).coerceAtLeast(0f)
                            val rings = (thick / 2.2.dp.toPx()).toInt().coerceIn(2, 18)
                            for (r in 1..rings) {
                                val rr = hubRadius + thick * (r.toFloat() / (rings + 1))
                                drawCircle(
                                    color = if (r % 3 == 0) Color.White.copy(alpha = 0.11f) else Color.Black.copy(alpha = 0.45f),
                                    radius = rr,
                                    center = Offset(cx, centerY),
                                    style = Stroke(width = 0.8.dp.toPx())
                                )
                            }

                            drawCircle(
                                color = Color(0xFF4A342B),
                                radius = spoolR,
                                center = Offset(cx, centerY),
                                style = Stroke(width = 1.4.dp.toPx())
                            )
                        }

                        val tapeY = centerY + hubRadius * 0.78f
                        drawLine(
                            color = Color(0xFF4D342B),
                            start = Offset(leftX, tapeY),
                            end = Offset(rightX, tapeY),
                            strokeWidth = 2.6.dp.toPx()
                        )
                        val shimmerStep = 18.dp.toPx()
                        val shimmerShift = (cumulativeTapeDistance * 0.95f) % shimmerStep
                        var sx = leftX + shimmerShift
                        while (sx < rightX - 6.dp.toPx()) {
                            drawLine(
                                color = ChampagneGold.copy(alpha = 0.55f),
                                start = Offset(sx, tapeY),
                                end = Offset(sx + 6.dp.toPx(), tapeY),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            sx += shimmerStep
                        }
                    }

                    val scaleLeft = leftX + hubRadius * 1.06f
                    val scaleRight = rightX - hubRadius * 1.06f
                    val scaleHeight = pillHeight * 0.48f
                    val scaleTop = centerY - scaleHeight / 2f

                    if (scaleRight > scaleLeft + 10f) {
                        drawRoundRect(
                            color = Color(0xFF1F232C).copy(alpha = 0.94f),
                            topLeft = Offset(scaleLeft, scaleTop),
                            size = Size(scaleRight - scaleLeft, scaleHeight),
                            cornerRadius = CornerRadius(3.5.dp.toPx())
                        )
                        drawRoundRect(
                            color = Color(0xFF5A6376),
                            topLeft = Offset(scaleLeft, scaleTop),
                            size = Size(scaleRight - scaleLeft, scaleHeight),
                            cornerRadius = CornerRadius(3.5.dp.toPx()),
                            style = Stroke(width = 1.1.dp.toPx())
                        )

                        drawLine(
                            color = Color.White.copy(alpha = 0.55f),
                            start = Offset(scaleLeft + 4.dp.toPx(), centerY),
                            end = Offset(scaleRight - 4.dp.toPx(), centerY),
                            strokeWidth = 0.9.dp.toPx()
                        )
                        val numTicks = 14
                        for (t in 0..numTicks) {
                            val tx = scaleLeft + 5.dp.toPx() + (scaleRight - scaleLeft - 10.dp.toPx()) * (t.toFloat() / numTicks)
                            val th = if (t % 7 == 0) 6.5.dp.toPx() else if (t % 2 == 0) 4.2.dp.toPx() else 2.4.dp.toPx()
                            drawLine(
                                color = Color.White.copy(alpha = 0.80f),
                                start = Offset(tx, centerY - th),
                                end = Offset(tx, centerY + th),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        val redIndicatorX = (scaleLeft + scaleRight) / 2f
                        drawLine(
                            color = EchoProgressRed,
                            start = Offset(redIndicatorX, scaleTop + 2.dp.toPx()),
                            end = Offset(redIndicatorX, scaleTop + scaleHeight - 2.dp.toPx()),
                            strokeWidth = 2.dp.toPx()
                        )
                    }

                    listOf(leftX to leftAngle, rightX to rightAngle).forEach { (cx, angle) ->
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.70f),
                            radius = hubRadius + 2.5.dp.toPx(),
                            center = Offset(cx + 1.dp.toPx(), centerY + 2.dp.toPx())
                        )

                        drawCircle(
                            brush = Brush.linearGradient(
                                colors = listOf(Color.White, Color(0xFF94A3B8)),
                                start = Offset(cx - hubRadius, centerY - hubRadius),
                                end = Offset(cx + hubRadius, centerY + hubRadius)
                            ),
                            radius = hubRadius,
                            center = Offset(cx, centerY)
                        )

                        val whiteWheelR = hubRadius * 0.88f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White, Color(0xFFE2E8F0)),
                                center = Offset(cx, centerY),
                                radius = whiteWheelR
                            ),
                            radius = whiteWheelR,
                            center = Offset(cx, centerY)
                        )

                        val redHubR = hubRadius * 0.52f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFFFB7185), EchoProgressRed, Color(0xFF991B1B)),
                                center = Offset(cx, centerY),
                                radius = redHubR
                            ),
                            radius = redHubR,
                            center = Offset(cx, centerY)
                        )

                        val centerHoleR = hubRadius * 0.22f
                        rotate(angle, pivot = Offset(cx, centerY)) {
                            for (spoke in 0 until 6) {
                                rotate(spoke * 60f, pivot = Offset(cx, centerY)) {
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(cx, centerY - centerHoleR),
                                        end = Offset(cx, centerY - whiteWheelR * 0.92f),
                                        strokeWidth = 2.4.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                }
                                rotate(spoke * 60f + 30f, pivot = Offset(cx, centerY)) {
                                    drawCircle(
                                        color = Color(0xFF14171E),
                                        radius = hubRadius * 0.115f,
                                        center = Offset(cx, centerY - hubRadius * 0.69f)
                                    )
                                }
                            }
                        }

                        drawCircle(
                            color = Color(0xFF101218),
                            radius = centerHoleR,
                            center = Offset(cx, centerY)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = centerHoleR,
                            center = Offset(cx, centerY),
                            style = Stroke(width = 1.3.dp.toPx())
                        )
                        rotate(angle, pivot = Offset(cx, centerY)) {
                            for (tooth in 0 until 6) {
                                rotate(tooth * 60f, pivot = Offset(cx, centerY)) {
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(cx, centerY - centerHoleR),
                                        end = Offset(cx, centerY - centerHoleR * 0.62f),
                                        strokeWidth = 1.8.dp.toPx()
                                    )
                                }
                            }
                        }
                    }

                    drawRoundRect(
                        color = Color(0xFF0E1015),
                        topLeft = Offset(0f, pillTop),
                        size = Size(w, pillHeight),
                        cornerRadius = CornerRadius(pillCorner, pillCorner),
                        style = Stroke(width = 3.8.dp.toPx())
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFF707C91), Color(0xFF2D3340), Color(0xFF5A6478))
                        ),
                        topLeft = Offset(1.dp.toPx(), pillTop + 1.dp.toPx()),
                        size = Size(w - 2.dp.toPx(), pillHeight - 2.dp.toPx()),
                        cornerRadius = CornerRadius(pillCorner, pillCorner),
                        style = Stroke(width = 1.8.dp.toPx())
                    )
                }
            }

            // D. HÀNG CA SĨ + THỜI GIAN + THANH TIẾN TRÌNH ĐỎ CÓ CON CHẠY TRẮNG ĐỨNG
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .padding(bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonOutline,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = trackArtistStr,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = (if (isPlaying) "▶ " else "⏸ ") + formatTimeMs(posMs),
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = formatTimeMs(durMs),
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .pointerInput(durMs) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onSeek((frac * durMs).toInt())
                            }
                        }
                        .pointerInput(durMs) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onSeek((frac * durMs).toInt())
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val cy = size.height / 2f
                        val thumbX = (w * progressFraction).coerceIn(2.dp.toPx(), w - 2.dp.toPx())

                        drawLine(
                            color = Color.White.copy(alpha = 0.22f),
                            start = Offset(0f, cy),
                            end = Offset(w, cy),
                            strokeWidth = 2.6.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = EchoProgressRed,
                            start = Offset(0f, cy),
                            end = Offset(thumbX, cy),
                            strokeWidth = 2.6.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(thumbX - 2.dp.toPx(), cy - 4.5.dp.toPx()),
                            size = Size(4.dp.toPx(), 9.dp.toPx()),
                            cornerRadius = CornerRadius(1.dp.toPx())
                        )
                    }
                }
            }

            // E. ĐẾ BĂNG HÌNH THANG VÁT CHÉO
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .height(34.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val topInset = w * 0.055f

                    val trapPath = Path().apply {
                        moveTo(topInset, 0f)
                        lineTo(w - topInset, 0f)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }

                    drawPath(
                        path = trapPath,
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFF323743), Color(0xFF20242C))
                        )
                    )
                    drawPath(
                        path = trapPath,
                        color = Color(0xFF5A6478),
                        style = Stroke(width = 1.4.dp.toPx())
                    )

                    val holeY = h * 0.74f
                    drawCircle(
                        color = Color(0xFF0A0C10),
                        radius = 4.2.dp.toPx(),
                        center = Offset(w * 0.10f, holeY)
                    )
                    drawCircle(
                        color = Color(0xFF0A0C10),
                        radius = 4.2.dp.toPx(),
                        center = Offset(w * 0.90f, holeY)
                    )
                    drawRoundRect(
                        color = Color(0xFF0A0C10),
                        topLeft = Offset(w * 0.21f, holeY - 3.dp.toPx()),
                        size = Size(12.dp.toPx(), 6.dp.toPx()),
                        cornerRadius = CornerRadius(1.5.dp.toPx())
                    )
                    drawRoundRect(
                        color = Color(0xFF0A0C10),
                        topLeft = Offset(w * 0.79f - 12.dp.toPx(), holeY - 3.dp.toPx()),
                        size = Size(12.dp.toPx(), 6.dp.toPx()),
                        cornerRadius = CornerRadius(1.5.dp.toPx())
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = specLineStr,
                        color = EchoCyanSpec,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )

                    Box(
                        modifier = Modifier
                            .border(1.dp, Color(0xFFA855F7), RoundedCornerShape(2.dp))
                            .padding(horizontal = 3.dp, vertical = 0.2.dp)
                    ) {
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
                        tint = if (isFavorite) EchoProgressRed else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier
                            .size(13.dp)
                            .clickable { onToggleFavorite() }
                    )
                }
            }
        }

        // Lớp kính phản quang chéo
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val glarePath = Path().apply {
                moveTo(w * 0.38f, -10f)
                lineTo(w * 1.02f, -10f)
                lineTo(w * 1.02f, h + 10f)
                lineTo(w * 0.50f, h + 10f)
                close()
            }
            drawPath(
                path = glarePath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.075f),
                        Color.White.copy(alpha = 0.02f),
                        Color.Transparent
                    ),
                    start = Offset(w * 0.40f, 0f),
                    end = Offset(w * 0.85f, h)
                )
            )
        }
    }
}

@Composable
fun CassetteCornerScrew(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.5.dp)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFCBD5E1), Color(0xFF475569), Color(0xFF1E293B))
                )
            )
            .border(0.8.dp, Color(0xFF090B10), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(4.5.dp)
                .height(1.dp)
                .background(Color(0xFF090B10))
        )
    }
}

/**
 * =============================================================================================
 * BÀN PHÍM ĐIỀU KHIỂN VECTOR 3D HÀNG NGANG (DÙNG CHUNG CHO CẢ PORTRAIT & LANDSCAPE)
 * - Khi ở Landscape: Hiển thị gọn gàng thành 1 hàng ngang phía dưới Cassette (gồm cả Library, Audio Lab, Rotate và 5 phím chính).
 * - Nút Play/Pause ở trung tâm nổi bật nhất với viền Vàng Champagne / Đỏ Crimson và độ nổi 3D cao.
 * =============================================================================================
 */
@Composable
fun Vector3DTransportDeck(
    isLandscape: Boolean,
    isPlaying: Boolean,
    playMode: PlayMode,
    onToggleShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeat: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenAudioLab: () -> Unit,
    onToggleOrientation: () -> Unit
) {
    val isShuffle = playMode == PlayMode.SHUFFLE
    val isRepeatOne = playMode == PlayMode.REPEAT_ONE

    val deckHeight = if (isLandscape) 60.dp else 68.dp

    // Khung đế kim loại đúc chìm chứa các hốc nút bấm 3D
    Box(
        modifier = Modifier
            .fillMaxWidth(if (isLandscape) 0.92f else 1f)
            .height(deckHeight)
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A0C10), Color(0xFF12151C), Color(0xFF07080B))
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF3E4658), Color(0xFF1E222B), Color(0xFF2D3342))
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLandscape) {
                // Nút mở Thư viện khi xoay ngang
                Vector3DHardwareKey(
                    modifier = Modifier.weight(0.75f),
                    isLatched = false,
                    isHeroPlayKey = false,
                    ledColor = null,
                    onClick = onOpenLibrary
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Library",
                        tint = ColdSilver,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Phím 1: SHUFFLE
            Vector3DHardwareKey(
                modifier = Modifier.weight(0.85f),
                isLatched = isShuffle,
                isHeroPlayKey = false,
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

            // Phím 2: PREVIOUS / REW
            Vector3DHardwareKey(
                modifier = Modifier.weight(1.05f),
                isLatched = false,
                isHeroPlayKey = false,
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

            // Phím 3: PLAY / PAUSE (NỔI BẬT NHẤT TRUNG TÂM, VIỀN VÀNG CHAMPAGNE + ACCENT ĐỎ/XANH)
            Vector3DHardwareKey(
                modifier = Modifier.weight(1.55f),
                isLatched = isPlaying,
                isHeroPlayKey = true,
                ledColor = if (isPlaying) LedPlayGreen else EchoCrimsonRed,
                onClick = onPlayPause
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = if (isPlaying) ChampagneGold else Color.White,
                        modifier = Modifier.size(27.dp)
                    )
                }
            }

            // Phím 4: NEXT / FFWD
            Vector3DHardwareKey(
                modifier = Modifier.weight(1.05f),
                isLatched = false,
                isHeroPlayKey = false,
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
            Vector3DHardwareKey(
                modifier = Modifier.weight(0.85f),
                isLatched = isRepeatOne,
                isHeroPlayKey = false,
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

            if (isLandscape) {
                // Nút mở Audio Lab khi xoay ngang
                Vector3DHardwareKey(
                    modifier = Modifier.weight(0.75f),
                    isLatched = false,
                    isHeroPlayKey = false,
                    ledColor = ChampagneGold,
                    onClick = onOpenAudioLab
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Audio Lab",
                        tint = ChampagneGold,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Nút xoay dọc lại màn hình
                Vector3DHardwareKey(
                    modifier = Modifier.weight(0.70f),
                    isLatched = false,
                    isHeroPlayKey = false,
                    ledColor = null,
                    onClick = onToggleOrientation
                ) {
                    Icon(
                        imageVector = Icons.Default.ScreenRotation,
                        contentDescription = "Rotate",
                        tint = ColdSilver,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

/**
 * NÚT BẤM VẬT LÝ PHONG CÁCH VECTOR 3D (SCULPTED 3D BEVELED KEYCAP)
 * - Có hốc âm (Recessed Socket), thành vát 3D (3D Extruded Bottom Wall), viền highlight kim loại,
 *   bóng đổ mềm và hiệu ứng lún sâu xuống bằng lò xo (Spring Press Effect) khi chạm tay.
 */
@Composable
fun Vector3DHardwareKey(
    modifier: Modifier = Modifier,
    isLatched: Boolean,
    isHeroPlayKey: Boolean = false,
    ledColor: Color?,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Phản hồi nhấn: Lún sâu tức thì khi ngón tay chạm vào (isPressed) hoặc giữ lún nhẹ khi đang chốt (isLatched)
    val targetTravelDp = when {
        isPressed -> 4.8.dp
        isLatched -> 2.8.dp
        else -> 0.dp
    }
    val targetExtrusionDp = when {
        isPressed -> 1.0.dp
        isLatched -> 2.5.dp
        else -> 5.5.dp
    }

    val pressOffsetDp by animateDpAsState(
        targetValue = targetTravelDp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
        label = "vector3d_press_offset"
    )
    val bottomExtrusionDp by animateDpAsState(
        targetValue = targetExtrusionDp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
        label = "vector3d_bottom_extrusion"
    )

    // Màu sắc mặt phím 3D (Nút Play chính giữa có tông Vàng Đen / Đỏ Crimson nổi bật hơn hẳn)
    val faceBrush = when {
        isHeroPlayKey && isPressed -> Brush.verticalGradient(
            listOf(Color(0xFF2B2218), Color(0xFF3D2E1E), Color(0xFF1F170F))
        )
        isHeroPlayKey && isLatched -> Brush.verticalGradient(
            listOf(Color(0xFF423623), Color(0xFF292115), Color(0xFF1B150D))
        )
        isHeroPlayKey -> Brush.verticalGradient(
            listOf(Color(0xFF4F412B), Color(0xFF33291A), Color(0xFF211A10))
        )
        isPressed -> Brush.verticalGradient(
            listOf(Color(0xFF161922), Color(0xFF202532), Color(0xFF14171F))
        )
        isLatched -> Brush.verticalGradient(
            listOf(Color(0xFF282E3D), Color(0xFF1C202B), Color(0xFF14171F))
        )
        else -> Brush.verticalGradient(
            listOf(Color(0xFF384052), Color(0xFF252B38), Color(0xFF181C26))
        )
    }

    // Màu thành vát 3D dưới đáy phím (3D Side/Bottom Bevel Wall)
    val sideWallBrush = if (isHeroPlayKey) {
        Brush.verticalGradient(listOf(Color(0xFF6B5839), Color(0xFF2E2517)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF1B202B), Color(0xFF0B0D12)))
    }

    val topBevelColor = when {
        isHeroPlayKey -> ChampagneGold.copy(alpha = if (isPressed) 0.45f else 0.90f)
        isLatched -> ChampagneGold.copy(alpha = 0.70f)
        else -> Color.White.copy(alpha = if (isPressed) 0.12f else 0.32f)
    }

    val outerBorderColor = when {
        isHeroPlayKey -> ChampagneGold
        isLatched -> ChampagneGold.copy(alpha = 0.80f)
        else -> Color(0xFF495369)
    }

    // Lớp 1: Hốc rãnh âm trên mặt máy (Recessed Key Socket)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF030406))
            .border(1.dp, Color(0xFF1A1E28), RoundedCornerShape(11.dp))
            .padding(2.dp)
    ) {
        // Lớp 2: Khối thân nút 3D dịch chuyển xuống khi nhấn
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pressOffsetDp)
                .shadow(if (isPressed) 1.dp else 6.dp, RoundedCornerShape(9.dp))
                .clip(RoundedCornerShape(9.dp))
                .background(sideWallBrush)
        ) {
            // Lớp 3: Mặt trên của nút (Keycap Top Face) nằm phía trên thành đáy 3D
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomExtrusionDp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(faceBrush)
                    .border(
                        width = if (isHeroPlayKey) 1.4.dp else 1.dp,
                        color = outerBorderColor,
                        shape = RoundedCornerShape(9.dp)
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
            ) {
                // Vẽ các chi tiết Vector 3D: Đường vát sáng đỉnh phím, đường vát chéo 2 bên và mặt lõm xúc giác ở tâm
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Đường highlight vát sáng ở mép trên cùng của nút (Top Bevel Specular Highlight)
                    drawLine(
                        color = topBevelColor,
                        start = Offset(8.dp.toPx(), 1.2.dp.toPx()),
                        end = Offset(w - 8.dp.toPx(), 1.2.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Lõm tròn nhẹ ở tâm phím Play/Pause tạo cảm giác phím bấm cơ học cao cấp (Concave Finger Dish)
                    if (isHeroPlayKey) {
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.28f),
                                    Color.Transparent
                                ),
                                center = Offset(w / 2f, h / 2f),
                                radius = w * 0.45f
                            ),
                            topLeft = Offset(w * 0.12f, h * 0.16f),
                            size = Size(w * 0.76f, h * 0.68f),
                            cornerRadius = CornerRadius(6.dp.toPx())
                        )
                    }
                }

                // Nội dung trên mặt phím: Đèn LED trạng thái + Icon + Gờ xúc giác
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Đèn LED chỉ thị trên đỉnh phím
                    if (ledColor != null) {
                        val activeLed = isLatched || isHeroPlayKey
                        Box(
                            modifier = Modifier
                                .width(if (isHeroPlayKey) 16.dp else 8.dp)
                                .height(3.5.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (activeLed) ledColor else Color(0xFF1D222D)
                                )
                                .border(
                                    width = 0.5.dp,
                                    color = if (activeLed) Color.White.copy(alpha = 0.5f) else Color(0xFF2D3444),
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    } else {
                        Spacer(modifier = Modifier.height(3.5.dp))
                    }

                    // Biểu tượng chính của nút
                    iconContent()

                    // Gờ khía chống trượt 3D ở mép dưới nút (Tactile Grip Grooves)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val grooveCount = if (isHeroPlayKey) 3 else 2
                        for (g in 1..grooveCount) {
                            Box(
                                modifier = Modifier
                                    .width(if (isHeroPlayKey) 8.dp else 6.dp)
                                    .height(1.5.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(
                                        if (isHeroPlayKey) ChampagneGold.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.18f)
                                    )
                            )
                        }
                    }
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
    pipeline: List<Pair<String, String>>,
    otaBeta: Boolean,
    onOtaBetaToggle: (Boolean) -> Unit,
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
                            text = "Direct Sound",
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
                            uncheckedTrackColor = DapBodyDark
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
                        text = if (pureDsdMode) "DSP Bypass" else "DSP Enabled",
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
                                        .background(DapBodyDark)
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
                            Text("+${(megaBass * 6).roundToInt()} dB", color = ChampagneGold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
                        title = "Clarity & Stereo Width",
                        subtitle = "Tối ưu độ tách bạch và không gian Stereo",
                        checked = clearAudio,
                        enabled = !pureDsdMode,
                        onCheckedChange = onClearAudioToggle
                    )

                    AudioLabSwitchRow(
                        title = "Treble Detail",
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

                AudioLabSwitchRow(
                    title = "Nhận bản beta",
                    subtitle = "Cho phép kiểm tra bản phát hành thử nghiệm",
                    checked = otaBeta,
                    enabled = true,
                    onCheckedChange = onOtaBetaToggle
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
                    title = "PCM Fade-in",
                    subtitle = "Tăng âm lượng dần khi bắt đầu bài PCM",
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
                audioOutput.connectedDevice?.let { AudioLabInfoRow("Connected device", it) }
                if (audioOutput.supportedRates.isNotEmpty()) {
                    AudioLabInfoRow("Reported device rates", audioOutput.supportedRates.joinToString { formatRate(it) })
                }
                AudioLabInfoRow("Audio Codec", audioOutput.codecEstimate)
                AudioLabInfoRow("Playing File", currentTrack?.title ?: "None")
                val bitrateLabel = if (currentBitrate > 0) "$currentBitrate kbps" else "Unknown bitrate"
                AudioLabInfoRow("Source", "${currentTrack?.formatName ?: "Unknown"} • $currentSampleRate • $bitrateLabel")
                pipeline.forEach { (label, value) -> AudioLabInfoRow(label, value) }
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
                uncheckedTrackColor = DapBodyDark
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


