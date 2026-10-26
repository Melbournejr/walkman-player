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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

data class LyricLine(val timeMs: Long, val text: String)

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
    }

    private var exoPlayer: ExoPlayer? = null
    private val sonyPcmProcessor = SonyRealTimeDspProcessor()
    private var onTrackEndedCallback: (() -> Unit)? = null
    private var masterVolume: Float = 0.75f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedDspSettings()
        initCustomDspExoPlayer()

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
                    onSetVinyl = { enabled -> updateVinylEffect(enabled) },
                    onSetClearAudio = { enabled -> updateClearAudio(enabled) },
                    onSetDsee = { enabled -> updateDsee(enabled) },
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

    private fun initCustomDspExoPlayer() {
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(sonyPcmProcessor))
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                volume = masterVolume
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            onTrackEndedCallback?.invoke()
                        }
                    }
                })
            }
    }

    private fun loadSavedDspSettings() {
        val prefs = getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
        masterVolume = prefs.getFloat("dsp_master_vol", 0.75f)
        sonyPcmProcessor.megaBassLevel = prefs.getFloat("dsp_bass", 0.45f)
        sonyPcmProcessor.vinylEnabled = prefs.getBoolean("dsp_vinyl", true)
        sonyPcmProcessor.clearAudioEnabled = prefs.getBoolean("dsp_clear_audio", true)
        sonyPcmProcessor.dseeEnabled = prefs.getBoolean("dsp_dsee", true)
        for (i in 0 until 10) {
            sonyPcmProcessor.eq10Gains[i] = prefs.getFloat("dsp_eq_$i", 0f)
        }
    }

    private fun playWithExo(track: AudioTrack, startMs: Long = 0L) {
        try {
            val player = exoPlayer ?: return
            player.setMediaItem(MediaItem.fromUri(track.uri))
            player.prepare()
            if (startMs > 0) player.seekTo(startMs)
            player.volume = masterVolume
            player.play()
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

    private fun updateMegaBass(level: Float) {
        sonyPcmProcessor.megaBassLevel = level.coerceIn(0f, 1f)
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("dsp_bass", sonyPcmProcessor.megaBassLevel).apply()
    }

    private fun updateVinylEffect(enabled: Boolean) {
        sonyPcmProcessor.vinylEnabled = enabled
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dsp_vinyl", enabled).apply()
    }

    private fun updateClearAudio(enabled: Boolean) {
        sonyPcmProcessor.clearAudioEnabled = enabled
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dsp_clear_audio", enabled).apply()
    }

    private fun updateDsee(enabled: Boolean) {
        sonyPcmProcessor.dseeEnabled = enabled
        getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dsp_dsee", enabled).apply()
    }

    private fun updateEqBand10(band10Index: Int, gainDb: Float) {
        if (band10Index in 0..9) {
            sonyPcmProcessor.eq10Gains[band10Index] = gainDb.coerceIn(-10f, 10f)
            getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
                .edit().putFloat("dsp_eq_$band10Index", sonyPcmProcessor.eq10Gains[band10Index]).apply()
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

    private fun inspectTrackSpecs(context: Context, track: AudioTrack): Pair<Int, String> {
        var bitrate = 320
        var sampleRate = "44.1 kHz • 16-bit"
        val lowerTitle = track.title.lowercase()
        val lowerFmt = track.formatName.lowercase()

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, track.uri)
            val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            if (!brStr.isNullOrBlank()) {
                val bps = brStr.toIntOrNull() ?: 320000
                bitrate = (bps / 1000).coerceIn(64, 18432)
            }
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            sampleRate = when {
                lowerTitle.endsWith(".dsf") || lowerTitle.endsWith(".dff") || lowerFmt.contains("dsd") -> "DSD 11.2 MHz (DSD256)"
                lowerTitle.contains("384k") || mime.contains("x-flac") -> "PCM 384.0 kHz • 32-bit"
                lowerTitle.endsWith(".flac") || lowerFmt == "flac" -> "FLAC 192.0 kHz • 24-bit Hi-Res"
                lowerTitle.endsWith(".mqa") || lowerFmt == "mqa" -> "MQA Studio 352.8 kHz"
                lowerTitle.endsWith(".wav") -> "PCM 96.0 kHz • 24-bit"
                else -> "44.1 kHz • 16-bit Stereo"
            }
            retriever.release()
        } catch (_: Exception) {}
        return Pair(bitrate, sampleRate)
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
            } catch (_: Exception) {}
        }
        return Pair(valid, skipped)
    }

    private fun parseManualFiles(context: Context, uris: List<Uri>): List<AudioTrack> {
        val added = mutableListOf<AudioTrack>()
        for (uri in uris) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
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
            } catch (_: Exception) {}
            added.add(AudioTrack(System.currentTimeMillis() + added.size, fn.substringBeforeLast("."), "Audiophile Direct", 0L, uri, "File chọn lẻ", sz, fn.substringAfterLast(".", "DSD/PCM").uppercase()))
        }
        return added
    }

    private fun parseFolderTree(context: Context, treeUri: Uri): Pair<List<AudioTrack>, Int> {
        val added = mutableListOf<AudioTrack>()
        var skipped = 0
        try { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
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
        try { scanDir(rootDir) } catch (_: Exception) {}
        return Pair(added, skipped)
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }
}

// HỆ THỐNG TỰ TÌM VÀ ĐỒNG BỘ LỜI BÀI HÁT (FIIO STYLE SYNCED LYRICS ENGINE)
object LyricsEngine {
    fun fetchLyricsForTrack(context: Context, track: AudioTrack): List<LyricLine> {
        val prefs = context.getSharedPreferences("walkman_lyrics_cache", Context.MODE_PRIVATE)
        val cacheKey = "lrc_${track.title.lowercase().trim()}"
        val cached = prefs.getString(cacheKey, null)
        if (!cached.isNullOrBlank()) {
            return parseLrcText(cached)
        }

        // Tự động làm sạch tên bài hát để tìm kiếm chính xác trên LRCLIB
        val cleanQuery = track.title
            .replace(Regex("\\[.*?\\]|\\(.*?\\)"), "")
            .replace(Regex("(?i)(official|video|audio|mv|lyrics|hd|4k|snaptube|mp3|flac)"), "")
            .replace("_", " ")
            .replace("|", " ")
            .trim()

        try {
            val encoded = URLEncoder.encode(cleanQuery.take(65), "UTF-8")
            val url = URL("https://lrclib.net/api/search?q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "SonyWalkmanPro/7.0")
            }
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(response)
                if (arr.length() > 0) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val synced = obj.optString("syncedLyrics", "")
                        if (synced.isNotBlank() && synced != "null") {
                            prefs.edit().putString(cacheKey, synced).apply()
                            return parseLrcText(synced)
                        }
                    }
                    val plain = arr.getJSONObject(0).optString("plainLyrics", "")
                    if (plain.isNotBlank() && plain != "null") {
                        prefs.edit().putString(cacheKey, plain).apply()
                        return parseLrcText(plain)
                    }
                }
            }
        } catch (_: Exception) {}

        return listOf(
            LyricLine(0L, "♪ ${track.title} ♪"),
            LyricLine(3000L, "Nghệ sĩ: ${track.artist}"),
            LyricLine(6000L, "Thư mục: ${track.folderInfo}"),
            LyricLine(10000L, "(Bản nhạc Mixtape / Liên khúc hoặc chưa có lời .LRC trên hệ thống)"),
            LyricLine(15000L, "Đang phát qua bộ xử lý âm thanh Sony Real-Time PCM DSP...")
        )
    }

    private fun parseLrcText(raw: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
        val lines = raw.lines()
        var fallbackTime = 0L

        for (line in lines) {
            val match = regex.find(line.trim())
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val msStr = match.groupValues[3]
                val ms = (msStr.toLongOrNull() ?: 0L) * (if (msStr.length == 2) 10L else 1L)
                val content = match.groupValues[4].trim()
                if (content.isNotEmpty()) {
                    result.add(LyricLine(min * 60000L + sec * 1000L + ms, content))
                }
            } else if (line.isNotBlank() && !line.startsWith("[")) {
                result.add(LyricLine(fallbackTime, line.trim()))
                fallbackTime += 4000L
            }
        }
        return result.sortedBy { it.timeMs }
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
        } catch (_: Exception) {}
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
        } catch (_: Exception) {}
        return list
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
        return try { PlayMode.valueOf(name) } catch (_: Exception) { PlayMode.SEQUENTIAL }
    }

    fun saveLastPlaybackState(context: Context, index: Int, posMs: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_LAST_INDEX, index).putInt(KEY_LAST_POS, posMs).apply()
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
        return try { WalkmanSkin.valueOf(name) } catch (_: Exception) { WalkmanSkin.WM1ZM2 }
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
    onSetVinyl: (Boolean) -> Unit,
    onSetClearAudio: (Boolean) -> Unit,
    onSetDsee: (Boolean) -> Unit,
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
    var isPlaying by remember { mutableStateOf(false) }
    var showLyricsOverlay by remember { mutableStateOf(false) }
    var currentLyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var isFetchingLyrics by remember { mutableStateOf(false) }

    var posMs by remember { mutableStateOf(PlaylistStorage.loadLastPos(context)) }
    var durMs by remember { mutableStateOf(1) }

    val dspPrefs = context.getSharedPreferences("walkman_dsp_prefs", Context.MODE_PRIVATE)
    var masterVol by remember { mutableStateOf(dspPrefs.getFloat("dsp_master_vol", 0.75f)) }
    var megaBass by remember { mutableStateOf(dspPrefs.getFloat("dsp_bass", 0.45f)) }
    var vinylProcessor by remember { mutableStateOf(dspPrefs.getBoolean("dsp_vinyl", true)) }
    var clearAudioPlus by remember { mutableStateOf(dspPrefs.getBoolean("dsp_clear_audio", true)) }
    var dseeUltimate by remember { mutableStateOf(dspPrefs.getBoolean("dsp_dsee", true)) }

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

    fun playAtIndex(idx: Int, resumePos: Long = 0L) {
        if (playlist.isEmpty()) return
        val safeIdx = (idx + playlist.size) % playlist.size
        currentIndex = safeIdx
        val track = playlist[safeIdx]

        isFetchingLyrics = true
        scope.launch(Dispatchers.IO) {
            val specs = getTrackSpecs(track)
            val lrcs = LyricsEngine.fetchLyricsForTrack(context, track)
            withContext(Dispatchers.Main) {
                currentBitrate = specs.first
                currentSampleRate = specs.second
                currentLyrics = lrcs
                isFetchingLyrics = false
            }
        }

        onPlayTrack(track, resumePos) {
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

    // Lắng nghe nút bấm từ Thanh Trạng Thái (Control Center / Notification / Màn hình khóa)
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
                            playAtIndex(next, 0L)
                        }
                    }
                    cmd == "PREV" -> {
                        if (playlist.isNotEmpty()) playAtIndex(currentIndex - 1, 0L)
                    }
                    cmd.startsWith("SEEK:") -> {
                        val target = cmd.substringAfter("SEEK:").toIntOrNull() ?: 0
                        posMs = target
                        onSeekTo(target)
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
        playlist.getOrNull(currentIndex)?.let { t ->
            withContext(Dispatchers.IO) {
                currentLyrics = LyricsEngine.fetchLyricsForTrack(context, t)
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
                    PlaylistStorage.saveLastPlaybackState(context, currentIndex, posMs)
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
                    label = { Text("Walkman 7.0", fontWeight = FontWeight.Bold) }
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
                    label = { Text("Real PCM DSP", fontWeight = FontWeight.Bold) }
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
                0 -> FullScreenWalkmanDeckV7(
                    skin = currentSkin,
                    track = currentTrack,
                    audioOutput = audioOutput,
                    bitrate = currentBitrate,
                    sampleRate = currentSampleRate,
                    isPlaying = isPlaying,
                    playMode = playMode,
                    isFavorite = isCurrentFavorite,
                    showLyrics = showLyricsOverlay,
                    lyrics = currentLyrics,
                    isFetchingLyrics = isFetchingLyrics,
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
                        updateSystemMediaNotification(isPlaying)
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
                    onToggleLyrics = { showLyricsOverlay = !showLyricsOverlay },
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
                    onStop = {
                        onStopPlayback()
                        isPlaying = false
                        posMs = 0
                        PlaylistStorage.saveLastPlaybackState(context, currentIndex, 0)
                        updateSystemMediaNotification(false)
                    },
                    onPrev = {
                        if (playlist.isNotEmpty()) playAtIndex(currentIndex - 1, 0L)
                    },
                    onNext = {
                        if (playlist.isNotEmpty()) {
                            val next = if (playMode == PlayMode.SHUFFLE) playlist.indices.random() else currentIndex + 1
                            playAtIndex(next, 0L)
                        }
                    }
                )
                1 -> FiioStyleCategorizedLibraryView(
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
                            playAtIndex(realIndex, 0L)
                            activeTab = 0
                        }
                    }
                )
                2 -> FullSonyDsp10BandAndVinylView(
                    masterVol = masterVol,
                    megaBass = megaBass,
                    vinylProcessor = vinylProcessor,
                    clearAudio = clearAudioPlus,
                    dseeUltimate = dseeUltimate,
                    eq10Bands = eq10Bands,
                    audioOutput = audioOutput,
                    onMasterVolChange = {
                        masterVol = it
                        onSetMasterVolume(it)
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
fun FullScreenWalkmanDeckV7(
    skin: WalkmanSkin,
    track: AudioTrack?,
    audioOutput: AudioOutputState,
    bitrate: Int,
    sampleRate: String,
    isPlaying: Boolean,
    playMode: PlayMode,
    isFavorite: Boolean,
    showLyrics: Boolean,
    lyrics: List<LyricLine>,
    isFetchingLyrics: Boolean,
    posMs: Int,
    durMs: Int,
    masterVol: Float,
    onVolumeChange: (Float) -> Unit,
    onSeek: (Int) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLyrics: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
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
        // 1. Header + Nút bật/tắt Lời bài hát FiiO Lyrics
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
                    text = "📁 ${track?.folderInfo ?: "SIGNATURE SERIES"}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }
            Button(
                onClick = onToggleLyrics,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showLyrics) accentColor else Color(0xFF0F172A),
                    contentColor = if (showLyrics && skin == WalkmanSkin.WM1ZM2) Color.Black else Color.White
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(if (showLyrics) "ĐÓNG LỜI" else "LỜI BÀI HÁT (LRC)", fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }

        // 2. BẢNG THÔNG TIN DAC / CODEC / BITRATE
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

        // 3. MÀN HÌNH CHÍNH (Hoặc Màn hình Lời bài hát chạy tự động kiểu FiiO)
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
                if (showLyrics) {
                    // MÀN HÌNH LỜI BÀI HÁT ĐỒNG BỘ (SYNCED LYRICS LRC)
                    val activeLineIdx = lyrics.indexOfLast { it.timeMs <= posMs }.coerceAtLeast(0)
                    val listState = rememberLazyListState()

                    LaunchedEffect(activeLineIdx) {
                        if (lyrics.isNotEmpty()) {
                            listState.animateScrollToItem((activeLineIdx - 1).coerceAtLeast(0))
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isFetchingLyrics) "ĐANG TẢI LỜI BÀI HÁT..." else "FIIO / WALKMAN SYNCED LYRICS",
                            color = accentColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(lyrics) { idx, line ->
                                val isCurrent = idx == activeLineIdx
                                Text(
                                    text = line.text,
                                    color = if (isCurrent) accentColor else Color.Gray,
                                    fontSize = if (isCurrent) 15.sp else 12.sp,
                                    fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSeek(line.timeMs.toInt()) }
                                        .padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                } else {
                    when (skin) {
                        WalkmanSkin.WM1ZM2 -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1D1709), RoundedCornerShape(12.dp))
                                    .border(2.dp, Color(0xFFEAB308), RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("DSD REMASTER • S-MASTER HX™ DIGITAL AMP", color = Color(0xFFEAB308), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Spacer(modifier = Modifier.height(6.dp))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("CH-L", color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                        Canvas(modifier = Modifier.size(width = 120.dp, height = 65.dp)) {
                                            drawArc(color = Color(0xFFEAB308).copy(alpha = 0.35f), startAngle = 190f, sweepAngle = 160f, useCenter = false, style = Stroke(width = 4f))
                                            val swingL = if (isPlaying) (sin(angle * 0.12f) * 36f) else -38f
                                            rotate(swingL, pivot = Offset(size.width / 2, size.height)) {
                                                drawLine(color = Color(0xFFEF4444), start = Offset(size.width / 2, size.height), end = Offset(size.width / 2, 6f), strokeWidth = 5f)
                                            }
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("CH-R", color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                        Canvas(modifier = Modifier.size(width = 120.dp, height = 65.dp)) {
                                            drawArc(color = Color(0xFFEAB308).copy(alpha = 0.35f), startAngle = 190f, sweepAngle = 160f, useCenter = false, style = Stroke(width = 4f))
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
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("SONY CHF-60 • NORMAL POSITION", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color(0xFF92400E), fontFamily = FontFamily.Monospace)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF162032), RoundedCornerShape(14.dp))
                                    .border(2.dp, Color(0xFF475569), RoundedCornerShape(14.dp))
                                    .padding(vertical = 14.dp, horizontal = 12.dp),
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
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(125.dp)) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    rotate(if (isPlaying) angle else 0f) {
                                        drawCircle(color = accentColor.copy(alpha = 0.2f), style = Stroke(width = 20f))
                                        drawCircle(color = accentColor, radius = size.minDimension / 2.3f, style = Stroke(width = 6f))
                                        drawLine(color = Color.White, start = Offset(size.width / 2, 10f), end = Offset(size.width / 2, 30f), strokeWidth = 5f)
                                    }
                                    drawCircle(color = Color.White, radius = 14f)
                                }
                            }
                        }
                    }
                }

                // Tiêu đề bài hát & Ca sĩ
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(trackTitle, color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(trackArtist, color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }

                // Thanh tua nhạc (Seekbar)
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

        Spacer(modifier = Modifier.height(6.dp))

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
                modifier = Modifier.weight(1f).height(22.dp),
                colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
            )
            Text("${(masterVol * 100).toInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(38.dp), textAlign = TextAlign.End)
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 5. Cụm phím điều khiển + Chế độ phát (Tuần tự / Lặp 1 bài / Xáo trộn) + Nút Thả tim Yêu thích
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Nút chuyển đổi 3 chế độ phát
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

            // Cụm D-Pad phát nhạc
            Box(
                modifier = Modifier
                    .size(132.dp)
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
                    modifier = Modifier.size(56.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = if (skin == WalkmanSkin.WM1ZM2) Color.Black else Color.White, modifier = Modifier.size(30.dp))
                }
            }

            // Nút Yêu thích (Favorite Heart)
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
    Canvas(modifier = Modifier.size(68.dp)) {
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

// THƯ VIỆN NHẠC 3 CHẾ ĐỘ PHÂN LOẠI: TẤT CẢ BÀI HÁT • YÊU THÍCH • THEO THƯ MỤC LƯU TRONG MÁY
@Composable
fun FiioStyleCategorizedLibraryView(
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
    var subTab by remember { mutableStateOf(0) } // 0: Tất cả, 1: Yêu thích, 2: Thư mục
    var selectedFolderFilter by remember { mutableStateOf<String?>(null) }

    val foldersMap = remember(tracks) {
        tracks.groupBy { it.folderInfo }
    }

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

                // 3 Nút quét & nạp nhạc
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

                // Thanh chuyển 3 chế độ hiển thị: Tất cả | Yêu thích | Thư mục
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

        // Nếu đang ở Tab Thư mục và chưa bấm vào thư mục cụ thể -> Hiện danh sách các Thư mục trong máy
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
                            Text("${track.artist} • 📁 ${track.folderInfo} • ${track.formatName}", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
fun FullSonyDsp10BandAndVinylView(
    masterVol: Float,
    megaBass: Float,
    vinylProcessor: Boolean,
    clearAudio: Boolean,
    dseeUltimate: Boolean,
    eq10Bands: List<Float>,
    audioOutput: AudioOutputState,
    onMasterVolChange: (Float) -> Unit,
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
            Text("SONY REAL-TIME PCM DSP 7.0", fontWeight = FontWeight.Black, fontSize = 15.sp, color = Color(0xFFEAB308))
            Text("✓ ACTIVE ON USB-DAC", color = Color(0xFF22C55E), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
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
                        Text("Vinyl Processor™ (Real PCM Warmth)", fontWeight = FontWeight.Black, color = Color(0xFFEAB308), fontSize = 14.sp)
                        Text("Lọc thông thấp ấm + cộng hưởng cần kim đĩa than và bão hòa đèn điện tử trực tiếp trên luồng PCM", color = Color.LightGray, fontSize = 11.sp)
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
                        Text("Mở rộng sân khấu âm thanh 1.38x và làm nổi bật giọng hát", color = Color.Gray, fontSize = 11.sp)
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
                        Text("Tái tạo hài âm tần số cao bị mất cho độ chi tiết vượt trội", color = Color.Gray, fontSize = 11.sp)
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
                    value = megaBass,
                    onValueChange = onBassChange,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFF5500), activeTrackColor = Color(0xFFFF5500))
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SONY 10-BAND GRAPHIC EQUALIZER (PCM ENGINE)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)

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
