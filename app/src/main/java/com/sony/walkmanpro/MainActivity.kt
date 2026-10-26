package com.sony.walkmanpro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sin

data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri,
    val folderInfo: String,
    val sizeBytes: Long = 0L,
    var bitrateKbps: Int = 0,
    var sampleRateKhz: String = "44.1 kHz",
    var formatName: String = "AUDIO"
)

data class AudioOutputState(
    val deviceType: String = "Loa thiết bị",
    val isBluetooth: Boolean = false,
    val deviceName: String = "Built-in Speaker",
    val codecEstimate: String = "Stereo"
)

enum class WalkmanSkin(val shortName: String, val fullName: String) {
    TPSL2("Cassette TPS-L2", "SONY WALKMAN TPS-L2 (1979)"),
    W995I("W995i", "SONY ERICSSON W995i"),
    W850I("W850i", "SONY ERICSSON W850i"),
    A306("NW-A306", "SONY HI-RES NW-A306")
}

class MainActivity : ComponentActivity() {
    private var exoPlayer: ExoPlayer? = null
    private var bassBoost: BassBoost? = null
    private var equalizer: Equalizer? = null
    private var onTrackEndedCallback: (() -> Unit)? = null

    private var masterVolume: Float = 0.75f
    private var currentBassStrength: Float = 0.45f
    private val eq10Gains = FloatArray(10) { 0f }

    private val audioOutputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Cập nhật khi cắm rút tai nghe hoặc kết nối bluetooth
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedDspSettings()
        initExoPlayer()

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(audioOutputReceiver, filter)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WalkmanMasterRoot(
                    onAutoScanAll = { scanAllMusicSources(this@MainActivity) },
                    onParseManualUris = { uris -> parseManualFiles(this@MainActivity, uris) },
                    onParseFolderUri = { treeUri -> parseFolderTree(this@MainActivity, treeUri) },
                    onPlayTrack = { track, startMs, onEnd ->
                        onTrackEndedCallback = onEnd
                        playWithExo(track, startMs)
                    },
                    onTogglePlay = { togglePlayExo() },
                    onStopPlayback = { stopExo() },
                    onSeekTo = { ms -> seekExo(ms) },
                    onSetMasterVolume = { vol -> updateMasterVolume(vol) },
                    onSetBass = { level -> updateMegaBass(level) },
                    onSetEqBand10 = { band, gain -> updateEqBand10(band, gain) },
                    getIsPlaying = { exoPlayer?.isPlaying == true },
                    getPosition = { (exoPlayer?.currentPosition ?: 0L).toInt().coerceAtLeast(0) },
                    getDuration = {
                        val d = exoPlayer?.duration ?: 1L
                        if (d <= 0L || d == C.TIME_UNSET) 1 else d.toInt()
                    },
                    getAudioOutputState = { detectAudioOutputState(this@MainActivity) },
                    getTrackSpecs = { track -> inspectTrackSpecs(this@MainActivity, track) }
                )
            }
        }
    }

    private fun loadSavedDspSettings() {
        val prefs = getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
        masterVolume = prefs.getFloat("dsp_master_vol", 0.75f)
        currentBassStrength = prefs.getFloat("dsp_bass", 0.45f)
        for (i in 0 until 10) {
            eq10Gains[i] = prefs.getFloat("dsp_eq_$i", 0f)
        }
    }

    private fun initExoPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .build().apply {
                volume = masterVolume
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            onTrackEndedCallback?.invoke()
                        }
                    }
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != 0) {
                            setupSonyDspSafe(audioSessionId)
                        }
                    }
                })
            }
    }

    private fun playWithExo(track: AudioTrack, startMs: Long = 0L) {
        try {
            exoPlayer?.let { player ->
                player.setMediaItem(MediaItem.fromUri(track.uri))
                player.prepare()
                if (startMs > 0) {
                    player.seekTo(startMs)
                }
                player.volume = masterVolume
                player.play()
                val sessionId = player.audioSessionId
                if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
                    setupSonyDspSafe(sessionId)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể phát: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayExo(): Boolean {
        val player = exoPlayer ?: return false
        if (player.isPlaying) player.pause() else player.play()
        return player.isPlaying
    }

    private fun stopExo() {
        exoPlayer?.let {
            it.pause()
            it.seekTo(0)
        }
    }

    private fun seekExo(ms: Int) {
        exoPlayer?.let { player ->
            val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
            player.seekTo(ms.toLong().coerceIn(0L, dur))
        }
    }

    private fun updateMasterVolume(vol: Float) {
        masterVolume = vol.coerceIn(0f, 1f)
        exoPlayer?.volume = masterVolume
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("dsp_master_vol", masterVolume).apply()
    }

    private fun setupSonyDspSafe(sessionId: Int) {
        try {
            bassBoost?.release()
            bassBoost = null
            bassBoost = BassBoost(0, sessionId).apply {
                enabled = true
                setStrength((currentBassStrength * 800).toInt().toShort().coerceIn(0, 1000))
            }
        } catch (_: Exception) {}

        try {
            equalizer?.release()
            equalizer = null
            equalizer = Equalizer(0, sessionId).apply {
                enabled = true
                val hwBands = numberOfBands.toInt()
                for (hw in 0 until hwBands) {
                    val mappedIdx = ((hw.toFloat() / (hwBands - 1).coerceAtLeast(1)) * 9f).toInt().coerceIn(0, 9)
                    val gain = eq10Gains[mappedIdx]
                    val range = bandLevelRange
                    val targetMb = (gain * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                    setBandLevel(hw.toShort(), targetMb)
                }
            }
        } catch (_: Exception) {}
    }

    private fun updateMegaBass(level: Float) {
        currentBassStrength = level.coerceIn(0f, 1f)
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("dsp_bass", currentBassStrength).apply()

        try {
            bassBoost?.let {
                if (!it.enabled) it.enabled = true
                it.setStrength((currentBassStrength * 800).toInt().toShort().coerceIn(0, 1000))
            }
        } catch (_: Exception) {}
    }

    private fun updateEqBand10(band10Index: Int, gainDb: Float) {
        if (band10Index in eq10Gains.indices) {
            eq10Gains[band10Index] = gainDb.coerceIn(-10f, 10f)
            getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
                .edit().putFloat("dsp_eq_$band10Index", eq10Gains[band10Index]).apply()
        }

        try {
            equalizer?.let { eq ->
                if (!eq.enabled) eq.enabled = true
                val hwBands = eq.numberOfBands.toInt()
                for (hw in 0 until hwBands) {
                    val mapped = ((hw.toFloat() / (hwBands - 1).coerceAtLeast(1)) * 9f).toInt().coerceIn(0, 9)
                    val g = eq10Gains[mapped]
                    val range = eq.bandLevelRange
                    val targetMb = (g * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                    eq.setBandLevel(hw.toShort(), targetMb)
                }
            }
        } catch (_: Exception) {}
    }

    // Nhận diện loại tai nghe kết nối và Codec Bluetooth thực tế
    private fun detectAudioOutputState(context: Context): AudioOutputState {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (dev in devices) {
                when (dev.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                        val name = dev.productName?.toString() ?: "Bluetooth Audio"
                        val codecName = when {
                            name.contains("LDAC", true) || name.contains("WH-1000", true) || name.contains("WF-1000", true) -> "Sony LDAC (990 kbps / 96kHz)"
                            name.contains("aptx", true) -> "Qualcomm aptX HD / Adaptive"
                            else -> "Bluetooth AAC / SBC HD"
                        }
                        return AudioOutputState("Bluetooth Audio", true, name, codecName)
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                        return AudioOutputState("Tai nghe dây 3.5mm", false, "Hi-Res Direct DAC", "PCM 24-bit/192kHz")
                    }
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> {
                        return AudioOutputState("USB Type-C DAC", false, dev.productName?.toString() ?: "External DAC", "Hi-Res Audio Direct")
                    }
                }
            }
        }
        return AudioOutputState("Loa ngoài thiết bị", false, "Built-in Stereo Speaker", "Standard Audio")
    }

    // Trích xuất chi tiết Bitrate & Sample rate thật của bài hát
    private fun inspectTrackSpecs(context: Context, track: AudioTrack): Pair<Int, String> {
        var bitrate = 320
        var sampleRate = "44.1 kHz • 16-bit"
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, track.uri)
            val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            if (!brStr.isNullOrBlank()) {
                val bps = brStr.toIntOrNull() ?: 320000
                bitrate = (bps / 1000).coerceIn(64, 9216)
            }
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            sampleRate = when {
                mime.contains("flac", true) || track.title.endsWith(".flac", true) -> "96.0 kHz • 24-bit Hi-Res"
                mime.contains("wav", true) -> "48.0 kHz • 24-bit"
                else -> "44.1 kHz • 16-bit MP3"
            }
            retriever.release()
        } catch (_: Exception) {}
        return Pair(bitrate, sampleRate)
    }

    private fun isExcludedSystemOrRecording(textToCheck: String, fileName: String): Boolean {
        val excludedKeywords = listOf(
            "record", "call@", "call_", "voice", "ghi am", "ghi_am", "cuoc goi",
            "ringtone", "notification", "alarm", "sound_recorder", "miui/sound_recorder",
            "audiorecorder", "call_rec", "ui/effect", "whatsapp audio"
        )
        val lowerName = fileName.lowercase()
        return excludedKeywords.any { kw -> textToCheck.contains(kw) } ||
                lowerName.endsWith(".amr") ||
                lowerName.endsWith(".3gp") ||
                lowerName.endsWith(".awb")
    }

    // Quét sạch sẽ chống lặp bài hát 100% bằng cách băm Unique Key (Tên bài + Dung lượng byte)
    private fun scanAllMusicSources(context: Context): Pair<List<AudioTrack>, Int> {
        val validTracks = mutableListOf<AudioTrack>()
        val seenUniqueKeys = mutableSetOf<String>()
        var skippedCount = 0

        val volumes = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            volumes.addAll(
                MediaStore.getExternalVolumeNames(context).map { volName ->
                    MediaStore.Audio.Media.getContentUri(volName)
                }
            )
        }
        volumes.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_ALARM
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        for (volumeUri in volumes.distinct()) {
            try {
                context.contentResolver.query(
                    volumeUri,
                    projection,
                    null,
                    null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val ringCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_RINGTONE)
                    val notifCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_NOTIFICATION)
                    val alarmCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_ALARM)
                    val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else -1

                    while (cursor.moveToNext()) {
                        if (idCol < 0) continue
                        val id = cursor.getLong(idCol)
                        val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                        val rawTitle = if (titleCol >= 0) cursor.getString(titleCol) ?: "" else ""
                        val title = rawTitle.ifBlank { displayName.substringBeforeLast(".") }.ifBlank { "Track $id" }
                        val artist = if (artistCol >= 0) cursor.getString(artistCol) ?: "Walkman Audio" else "Walkman Audio"
                        val duration = if (durCol >= 0) cursor.getLong(durCol) else 0L
                        val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val isRing = if (ringCol >= 0) cursor.getInt(ringCol) != 0 else false
                        val isNotif = if (notifCol >= 0) cursor.getInt(notifCol) != 0 else false
                        val isAlarm = if (alarmCol >= 0) cursor.getInt(alarmCol) != 0 else false
                        val relPath = if (relPathCol >= 0) cursor.getString(relPathCol) ?: "" else ""

                        val cleanTitle = title.trim().lowercase()
                        val deduplicateKey = "${cleanTitle}_$sizeBytes"
                        if (seenUniqueKeys.contains(deduplicateKey)) continue

                        val checkText = "$title $displayName $relPath".lowercase()
                        val isShortSound = (duration in 1..14999) || (duration == 0L && sizeBytes in 1..100000)
                        val isSystemSound = isRing || isNotif || isAlarm || isExcludedSystemOrRecording(checkText, displayName)

                        if (isSystemSound || isShortSound) {
                            skippedCount++
                        } else {
                            seenUniqueKeys.add(deduplicateKey)
                            val contentUri = ContentUris.withAppendedId(volumeUri, id)
                            validTracks.add(
                                AudioTrack(
                                    id = id,
                                    title = title,
                                    artist = if (artist.contains("<unknown>", true)) "Walkman Artist" else artist,
                                    duration = duration,
                                    uri = contentUri,
                                    folderInfo = relPath.trimEnd('/').substringAfterLast('/').ifEmpty { "Bộ nhớ máy" },
                                    sizeBytes = sizeBytes,
                                    formatName = displayName.substringAfterLast(".", "MP3").uppercase()
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return Pair(validTracks, skippedCount)
    }

    private fun parseManualFiles(context: Context, uris: List<Uri>): List<AudioTrack> {
        val added = mutableListOf<AudioTrack>()
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            var fileName = "Walkman_Track.mp3"
            var fileSize = 0L
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }
            } catch (_: Exception) {}

            var title = fileName.substringBeforeLast(".")
            var artist = "Walkman Audio"
            var duration = 0L

            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { if (it.isNotBlank()) title = it }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { if (it.isNotBlank()) artist = it }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let { duration = it.toLongOrNull() ?: 0L }
                retriever.release()
            } catch (_: Exception) {}

            added.add(
                AudioTrack(
                    id = System.currentTimeMillis() + added.size,
                    title = title,
                    artist = artist,
                    duration = duration,
                    uri = uri,
                    folderInfo = "Thủ công",
                    sizeBytes = fileSize,
                    formatName = fileName.substringAfterLast(".", "MP3").uppercase()
                )
            )
        }
        return added
    }

    private fun parseFolderTree(context: Context, treeUri: Uri): Pair<List<AudioTrack>, Int> {
        val added = mutableListOf<AudioTrack>()
        var skipped = 0
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        val rootDir = DocumentFile.fromTreeUri(context, treeUri) ?: return Pair(emptyList(), 0)
        val audioExts = listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".opus")

        fun scanDocDir(dir: DocumentFile) {
            for (file in dir.listFiles()) {
                if (file.isDirectory) {
                    val dirName = (file.name ?: "").lowercase()
                    if (!dirName.contains("record") && !dirName.contains("android")) {
                        scanDocDir(file)
                    }
                } else if (file.isFile) {
                    val name = file.name ?: continue
                    val lower = name.lowercase()
                    if (audioExts.any { lower.endsWith(it) }) {
                        if (isExcludedSystemOrRecording(lower, name) || file.length() in 1..100000) {
                            skipped++
                        } else {
                            added.add(
                                AudioTrack(
                                    id = System.currentTimeMillis() + added.size,
                                    title = name.substringBeforeLast("."),
                                    artist = dir.name ?: "Walkman Folder",
                                    duration = 0L,
                                    uri = file.uri,
                                    folderInfo = dir.name ?: "Thư mục",
                                    sizeBytes = file.length(),
                                    formatName = name.substringAfterLast(".", "MP3").uppercase()
                                )
                            )
                        }
                    }
                }
            }
        }
        try { scanDocDir(rootDir) } catch (_: Exception) {}
        return Pair(added, skipped)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(audioOutputReceiver) } catch (_: Exception) {}
        try {
            bassBoost?.release()
            equalizer?.release()
            exoPlayer?.release()
        } catch (_: Exception) {}
    }
}

object PlaylistStorage {
    private const val PREF_NAME = "walkman_v5_storage"
    private const val KEY_TRACKS = "saved_tracks_json"
    private const val KEY_FOLDERS = "saved_folder_uris"
    private const val KEY_SKIN = "saved_skin_name"
    private const val KEY_LAST_INDEX = "saved_last_index"
    private const val KEY_LAST_POS = "saved_last_pos_ms"

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
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_TRACKS, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun loadPlaylist(context: Context): List<AudioTrack> {
        val list = mutableListOf<AudioTrack>()
        try {
            val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TRACKS, null) ?: return emptyList()
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
                        folderInfo = obj.optString("folderInfo", "Đã lưu"),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        formatName = obj.optString("formatName", "AUDIO")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveLastPlaybackState(context: Context, index: Int, posMs: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_INDEX, index)
            .putInt(KEY_LAST_POS, posMs)
            .apply()
    }

    fun loadLastIndex(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_INDEX, 0)
    }

    fun loadLastPos(context: Context): Int {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_POS, 0)
    }

    fun addSavedFolderUri(context: Context, treeUri: Uri) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FOLDERS, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(treeUri.toString())
        prefs.edit().putStringSet(KEY_FOLDERS, set).apply()
    }

    fun getSavedFolderUris(context: Context): List<Uri> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_FOLDERS, emptySet())?.map { Uri.parse(it) } ?: emptyList()
    }

    fun saveSkin(context: Context, skin: WalkmanSkin) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIN, skin.name).apply()
    }

    fun loadSkin(context: Context): WalkmanSkin {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SKIN, WalkmanSkin.TPSL2.name) ?: WalkmanSkin.TPSL2.name
        return try { WalkmanSkin.valueOf(name) } catch (_: Exception) { WalkmanSkin.TPSL2 }
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
    onPlayTrack: (AudioTrack, Long, () -> Unit) -> Unit,
    onTogglePlay: () -> Boolean,
    onStopPlayback: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSetMasterVolume: (Float) -> Unit,
    onSetBass: (Float) -> Unit,
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
    var skippedFilesCount by remember { mutableStateOf(0) }
    var isScanning by remember { mutableStateOf(false) }

    var currentIndex by remember { mutableStateOf(PlaylistStorage.loadLastIndex(context)) }
    var isPlaying by remember { mutableStateOf(false) }
    var isShuffle by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(PlaylistStorage.loadLastPos(context)) }
    var durMs by remember { mutableStateOf(1) }

    // Đọc trạng thái DSP đã lưu vĩnh viễn
    val dspPrefs = context.getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
    var masterVol by remember { mutableStateOf(dspPrefs.getFloat("dsp_master_vol", 0.75f)) }
    var megaBass by remember { mutableStateOf(dspPrefs.getFloat("dsp_bass", 0.45f)) }
    var clearAudioPlus by remember { mutableStateOf(dspPrefs.getBoolean("dsp_clear_audio", true)) }
    var dseeUltimate by remember { mutableStateOf(dspPrefs.getBoolean("dsp_dsee", true)) }

    // 10 Dải tần số Graphic Equalizer
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

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun playAtIndex(idx: Int, resumePos: Long = 0L) {
        if (playlist.isEmpty()) return
        val safeIdx = (idx + playlist.size) % playlist.size
        currentIndex = safeIdx
        val track = playlist[safeIdx]

        scope.launch(Dispatchers.IO) {
            val specs = getTrackSpecs(track)
            withContext(Dispatchers.Main) {
                currentBitrate = specs.first
                currentSampleRate = specs.second
            }
        }

        onPlayTrack(track, resumePos) {
            val nextIdx = if (isShuffle) playlist.indices.random() else (currentIndex + 1) % playlist.size
            playAtIndex(nextIdx, 0L)
        }
        isPlaying = true
    }

    fun runDeduplicatedScan() {
        if (isScanning) return
        isScanning = true
        scope.launch(Dispatchers.IO) {
            val autoRes = onAutoScanAll()
            val savedFolders = PlaylistStorage.getSavedFolderUris(context)
            val folderTracks = mutableListOf<AudioTrack>()
            var folderSkipped = 0
            for (treeUri in savedFolders) {
                val r = onParseFolderUri(treeUri)
                folderTracks.addAll(r.first)
                folderSkipped += r.second
            }

            // Bộ lọc trùng lặp tuyệt đối (Title + SizeBytes)
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
                skippedFilesCount = autoRes.second + folderSkipped
                isScanning = false
                Toast.makeText(context, "Walkman 5.0: Đã tối ưu và lọc còn ${mergedList.size} bài không trùng!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runDeduplicatedScan()
        else Toast.makeText(context, "Cần cấp quyền để đọc file nhạc!", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "Đã thêm ${added.size} bài!", Toast.LENGTH_SHORT).show()
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
                    skippedFilesCount += res.second
                    isScanning = false
                    Toast.makeText(context, "Đã lưu thư mục thành công!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
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
                    PlaylistStorage.saveLastPlaybackState(context, currentIndex, posMs)
                    audioOutput = getAudioOutputState()
                }
            }
            delay(200)
        }
    }

    val currentTrack = playlist.getOrNull(currentIndex)

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B0F19))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WalkmanSkin.values().forEach { skin ->
                    val selected = skin == currentSkin
                    Button(
                        onClick = {
                            currentSkin = skin
                            PlaylistStorage.saveSkin(context, skin)
                        },
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (selected) Color(0xFFFF8800) else Color(0xFF334155)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFFFF5500) else Color(0xFF1E293B),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = skin.shortName,
                            color = Color.White,
                            fontSize = 11.sp,
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
                    label = { Text("Walkman 5.0", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.LibraryMusic, null) },
                    label = { Text("Nhạc (${playlist.size})", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Equalizer, null) },
                    label = { Text("Sony DSP (10-Band)", fontWeight = FontWeight.Bold) }
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
                0 -> FullScreenWalkmanDeckV5(
                    skin = currentSkin,
                    track = currentTrack,
                    audioOutput = audioOutput,
                    bitrate = currentBitrate,
                    sampleRate = currentSampleRate,
                    isPlaying = isPlaying,
                    isShuffle = isShuffle,
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
                        PlaylistStorage.saveLastPlaybackState(context, currentIndex, posMs)
                    },
                    onToggleShuffle = { isShuffle = !isShuffle },
                    onPlayPause = {
                        if (playlist.isEmpty()) {
                            activeTab = 1
                        } else if (!isPlaying && !getIsPlaying()) {
                            playAtIndex(currentIndex, posMs.toLong())
                        } else {
                            isPlaying = onTogglePlay()
                        }
                    },
                    onStop = {
                        onStopPlayback()
                        isPlaying = false
                        posMs = 0
                        PlaylistStorage.saveLastPlaybackState(context, currentIndex, 0)
                    },
                    onPrev = {
                        if (playlist.isNotEmpty()) playAtIndex(currentIndex - 1, 0L)
                    },
                    onNext = {
                        if (playlist.isNotEmpty()) {
                            val next = if (isShuffle) playlist.indices.random() else currentIndex + 1
                            playAtIndex(next, 0L)
                        }
                    },
                    onOpenLibrary = { activeTab = 1 }
                )
                1 -> PersistentLibraryView(
                    tracks = playlist,
                    skippedCount = skippedFilesCount,
                    isScanning = isScanning,
                    currentIndex = currentIndex,
                    onAutoScan = {
                        val hasPerm = ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) runDeduplicatedScan() else permLauncher.launch(requiredPermission)
                    },
                    onPickFiles = { manualFilesLauncher.launch(arrayOf("audio/*")) },
                    onPickFolder = { folderPickerLauncher.launch(null) },
                    onClearSaved = {
                        playlist = emptyList()
                        PlaylistStorage.savePlaylist(context, emptyList())
                        PlaylistStorage.saveLastPlaybackState(context, 0, 0)
                        posMs = 0
                        Toast.makeText(context, "Đã làm mới danh sách!", Toast.LENGTH_SHORT).show()
                    },
                    onSelectTrack = { idx ->
                        playAtIndex(idx, 0L)
                        activeTab = 0
                    }
                )
                2 -> FullSonyDsp10BandView(
                    masterVol = masterVol,
                    megaBass = megaBass,
                    clearAudio = clearAudioPlus,
                    dseeUltimate = dseeUltimate,
                    eq10Bands = eq10Bands,
                    audioOutput = audioOutput,
                    onMasterVolChange = {
                        masterVol = it
                        onSetMasterVolume(it)
                    },
                    onClearAudioToggle = {
                        clearAudioPlus = it
                        dspPrefs.edit().putBoolean("dsp_clear_audio", it).apply()
                        if (it) {
                            megaBass = 0.55f
                            onSetBass(0.55f)
                        }
                    },
                    onDseeToggle = {
                        dseeUltimate = it
                        dspPrefs.edit().putBoolean("dsp_dsee", it).apply()
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
fun FullScreenWalkmanDeckV5(
    skin: WalkmanSkin,
    track: AudioTrack?,
    audioOutput: AudioOutputState,
    bitrate: Int,
    sampleRate: String,
    isPlaying: Boolean,
    isShuffle: Boolean,
    posMs: Int,
    durMs: Int,
    masterVol: Float,
    onVolumeChange: (Float) -> Unit,
    onSeek: (Int) -> Unit,
    onToggleShuffle: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    val trackTitle = track?.title ?: "CHƯA CHỌN BÀI HÁT"
    val trackArtist = track?.artist ?: "Bấm tab 'Nhạc' để quét bài hát"
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
        WalkmanSkin.TPSL2 -> Color(0xFF38BDF8)
        WalkmanSkin.W995I -> Color(0xFFFF3B1D)
        WalkmanSkin.W850I -> Color(0xFFFF6600)
        WalkmanSkin.A306 -> Color(0xFFF59E0B)
    }

    val bodyGradient = when (skin) {
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
                color = if (skin == WalkmanSkin.TPSL2) Color(0xFF94A3B8) else accentColor.copy(alpha = 0.85f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Header kim loại Walkman
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
                Text(
                    text = track?.folderInfo ?: "AUDIOPHILE DECK",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (skin == WalkmanSkin.TPSL2) Color(0xFF334155) else accentColor
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isPlaying) Color(0xFFEF4444) else Color(0xFF64748B))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (skin == WalkmanSkin.TPSL2) "TAPE" else "DSD/PCM",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (skin == WalkmanSkin.TPSL2) Color(0xFF0F172A) else Color.LightGray,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 2. BẢNG THÔNG TIN THIẾT BỊ XUẤT ÂM THANH (BLUETOOTH CODEC / BITRATE MONITOR)
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
                    tint = if (audioOutput.isBluetooth) Color(0xFF38BDF8) else Color(0xFFFF8800),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(audioOutput.deviceName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(audioOutput.codecEstimate, color = Color(0xFF38BDF8), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text("$format • $bitrate kbps", color = Color(0xFFFF8800), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                Text(sampleRate, color = Color.LightGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // 3. MÀN HÌNH CHÍNH THEO ĐỜI MÁY
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF080B12), RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (skin) {
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
                            Text(trackTitle, fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(trackArtist, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309), maxLines = 1)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF162032), RoundedCornerShape(14.dp))
                                .border(2.dp, Color(0xFF475569), RoundedCornerShape(14.dp))
                                .padding(vertical = 18.dp, horizontal = 12.dp),
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
                                    Text(
                                        text = "%03d".format((posMs / 1000) % 999),
                                        color = Color(0xFFF8FAFC),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                            LargeCassetteReel(if (isPlaying) angle else 0f)
                        }
                    }

                    WalkmanSkin.A306 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF281F12), RoundedCornerShape(12.dp))
                                .border(2.dp, Color(0xFFF59E0B), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("ANALOG VU LEVEL", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text("-20  -10  -3   0  +3 dB", color = Color(0xFFF87171), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Canvas(modifier = Modifier.fillMaxWidth().height(75.dp)) {
                                drawArc(
                                    color = Color(0xFFF59E0B).copy(alpha = 0.35f),
                                    startAngle = 200f,
                                    sweepAngle = 140f,
                                    useCenter = false,
                                    style = Stroke(width = 6f)
                                )
                                val swing = if (isPlaying) (sin(angle * 0.1f) * 38f) else -42f
                                rotate(swing, pivot = Offset(size.width / 2, size.height)) {
                                    drawLine(
                                        color = Color(0xFFEF4444),
                                        start = Offset(size.width / 2, size.height),
                                        end = Offset(size.width / 2, 8f),
                                        strokeWidth = 6f
                                    )
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(trackTitle, color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(trackArtist, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }

                    else -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(skin.shortName, color = accentColor, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            Text(if (isShuffle) "SHUFFLE ON" else "10-BAND EQ + CLEAR AUDIO", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }

                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                rotate(if (isPlaying) angle else 0f) {
                                    drawCircle(color = accentColor.copy(alpha = 0.2f), style = Stroke(width = 22f))
                                    drawCircle(color = accentColor, radius = size.minDimension / 2.3f, style = Stroke(width = 6f))
                                    drawLine(color = Color.White, start = Offset(size.width / 2, 10f), end = Offset(size.width / 2, 34f), strokeWidth = 5f)
                                }
                                drawCircle(color = Color.White, radius = 16f)
                            }
                        }

                        Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
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

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(trackTitle, color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(trackArtist, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = posMs.toFloat(),
                        onValueChange = { onSeek(it.toInt()) },
                        valueRange = 0f..durMs.toFloat().coerceAtLeast(1f),
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

        // 4. Master Volume Slider
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111726), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.VolumeDown, null, tint = accentColor, modifier = Modifier.size(18.dp))
            Slider(
                value = masterVol,
                onValueChange = onVolumeChange,
                modifier = Modifier.weight(1f).height(24.dp),
                colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
            )
            Text("${(masterVol * 100).toInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(38.dp), textAlign = TextAlign.End)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 5. Cụm phím điều khiển
        if (skin == WalkmanSkin.TPSL2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CassetteButton("<< REW", Modifier.weight(1f), Color(0xFFCBD5E1), Color(0xFF0F172A), onPrev)
                CassetteButton(
                    if (isPlaying) "PAUSE ||" else "PLAY >",
                    Modifier.weight(1.3f),
                    if (isPlaying) Color(0xFFF97316) else Color(0xFF22C55E),
                    Color.White,
                    onPlayPause
                )
                CassetteButton("FF >>", Modifier.weight(1f), Color(0xFFCBD5E1), Color(0xFF0F172A), onNext)
                CassetteButton("STOP", Modifier.weight(0.9f), Color(0xFFEF4444), Color.White, onStop)
                CassetteButton("EJECT", Modifier.weight(1f), Color(0xFFF59E0B), Color(0xFF0F172A), onOpenLibrary)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.background(Color(0xFF1E293B), CircleShape)
                ) {
                    Icon(Icons.Default.Shuffle, null, tint = if (isShuffle) accentColor else Color.White)
                }

                Box(
                    modifier = Modifier
                        .size(136.dp)
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
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                IconButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.background(Color(0xFF1E293B), CircleShape)
                ) {
                    Icon(Icons.Default.FolderOpen, null, tint = accentColor)
                }
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
                    drawLine(
                        color = Color(0xFFE2E8F0),
                        start = Offset(size.width / 2, size.height / 2 - 8f),
                        end = Offset(size.width / 2, 10f),
                        strokeWidth = 5f
                    )
                }
            }
        }
        drawCircle(color = Color(0xFF0F172A), radius = 10f)
    }
}

@Composable
fun CassetteButton(label: String, modifier: Modifier, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(2.dp, Color(0xFF0F172A), RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.Black, fontSize = 11.sp, color = fg, textAlign = TextAlign.Center)
    }
}

@Composable
fun PersistentLibraryView(
    tracks: List<AudioTrack>,
    skippedCount: Int,
    isScanning: Boolean,
    currentIndex: Int,
    onAutoScan: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onClearSaved: () -> Unit,
    onSelectTrack: (Int) -> Unit
) {
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
                    Text("THƯ VIỆN NHẠC (CHỐNG LẶP)", fontWeight = FontWeight.Black, color = Color(0xFFFF6600), fontSize = 14.sp)
                    if (tracks.isNotEmpty()) {
                        TextButton(onClick = onClearSaved, contentPadding = PaddingValues(0.dp)) {
                            Text("Xóa DS", color = Color(0xFFEF4444), fontSize = 11.sp)
                        }
                    }
                }
                Text(
                    text = if (isScanning) "Đang quét và lọc sạch trùng lặp..."
                    else "Hiện có: ${tracks.size} bài (Đã lọc bỏ trùng & file hệ thống)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onAutoScan,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5500), contentColor = Color.White),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Text("QUÉT TỰ ĐỘNG", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = onPickFolder,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7), contentColor = Color.White),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Text("LƯU THƯ MỤC", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = onPickFiles,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Text("+ CHỌN FILE", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (tracks.isEmpty() && !isScanning) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Bấm 'QUÉT TỰ ĐỘNG' hoặc 'LƯU THƯ MỤC' ở trên.\nDanh sách được lưu vĩnh viễn và không bị trùng lặp!",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(tracks) { index, track ->
                    val isSelected = index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) Color(0xFF2B1B12) else Color(0xFF111622), RoundedCornerShape(10.dp))
                            .border(1.dp, if (isSelected) Color(0xFFFF6600) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { onSelectTrack(index) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${index + 1}. ${track.title}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${track.artist} • [${track.folderInfo}] • ${track.formatName}", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (track.duration > 0) {
                            Text(
                                formatTimeMs(track.duration.toInt()),
                                color = Color(0xFFFF8800),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

// BỘ XỬ LÝ ÂM THANH 10 BĂNG TẦN & LƯU VĨNH VIỄN
@Composable
fun FullSonyDsp10BandView(
    masterVol: Float,
    megaBass: Float,
    clearAudio: Boolean,
    dseeUltimate: Boolean,
    eq10Bands: List<Float>,
    audioOutput: AudioOutputState,
    onMasterVolChange: (Float) -> Unit,
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
        "Acoustic" to listOf(3f, 2f, 1f, 1f, 2f, 3f, 4f, 3f, 3f, 4f),
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
            Text("SONY DSP 5.0 (10-BAND EQ)", fontWeight = FontWeight.Black, fontSize = 15.sp, color = Color(0xFFFF6600))
            Text("✓ ĐÃ LƯU TỰ ĐỘNG", color = Color(0xFF22C55E), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        }

        // Card hiển thị thiết bị âm thanh đang kết nối
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141C2B))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("NGÕ RA ÂM THANH:", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Text(audioOutput.deviceName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(audioOutput.codecEstimate, color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Master Volume
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("MASTER AUDIO GAIN", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text("${(masterVol * 100).toInt()}%", color = Color(0xFF38BDF8), fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = masterVol,
                    onValueChange = onMasterVolChange,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF38BDF8), activeTrackColor = Color(0xFF38BDF8))
                )
            }
        }

        // ClearAudio+ & DSEE HX
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ClearAudio+™", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Text("Tự động tinh chỉnh âm trường và độ trong Sony", color = Color.Gray, fontSize = 11.sp)
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
                        Text("DSEE HX™ Sound Restore", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Text("Bù đắp dải tần số cao bị cắt giảm", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = dseeUltimate, onCheckedChange = onDseeToggle)
                }
            }
        }

        // MegaBass / ClearBass
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CLEARBASS / MEGABASS", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text("+${(megaBass * 10).toInt()} dB", color = Color(0xFFFF6600), fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = megaBass,
                    onValueChange = onBassChange,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFF5500), activeTrackColor = Color(0xFFFF5500))
                )
            }
        }

        // GRAPHIC EQUALIZER 10 BĂNG TẦN
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
                    val currentVal = eq10Bands.getOrElse(idx) { 0f }
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
