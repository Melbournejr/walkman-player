package com.sony.walkmanpro

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
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
    val folderInfo: String
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
    private var virtualizer: Virtualizer? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var onTrackEndedCallback: (() -> Unit)? = null

    private var masterVolume: Float = 0.72f
    private var dynamicNormalizer: Boolean = true
    private var currentBassStrength: Float = 0.4f
    private var currentSurroundStrength: Float = 0.3f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initExoPlayer()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WalkmanMasterRoot(
                    onAutoScanAll = { scanAllMusicSources(this@MainActivity) },
                    onParseManualUris = { uris -> parseManualFiles(this@MainActivity, uris) },
                    onParseFolderUri = { treeUri -> parseFolderTree(this@MainActivity, treeUri) },
                    onPlayTrack = { track, onEnd ->
                        onTrackEndedCallback = onEnd
                        playWithExo(track)
                    },
                    onTogglePlay = { togglePlayExo() },
                    onStopPlayback = { stopExo() },
                    onSeekTo = { ms -> seekExo(ms) },
                    onSetMasterVolume = { vol -> updateMasterVolume(vol) },
                    onSetBass = { level -> updateMegaBass(level) },
                    onSetSurround = { level -> updateSurround(level) },
                    onSetNormalizer = { enabled ->
                        dynamicNormalizer = enabled
                        updateMasterVolume(masterVolume)
                    },
                    onSetEqBand = { band, gain -> updateEqBand(band, gain) },
                    getIsPlaying = { exoPlayer?.isPlaying == true },
                    getPosition = { (exoPlayer?.currentPosition ?: 0L).toInt().coerceAtLeast(0) },
                    getDuration = {
                        val d = exoPlayer?.duration ?: 1L
                        if (d <= 0L || d == C.TIME_UNSET) 1 else d.toInt()
                    }
                )
            }
        }
    }

    private fun initExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            volume = masterVolume * 0.85f
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        onTrackEndedCallback?.invoke()
                    }
                }
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != 0) {
                        setupSonyDsp(audioSessionId)
                    }
                }
            })
        }
    }

    private fun playWithExo(track: AudioTrack) {
        try {
            exoPlayer?.let { player ->
                player.setMediaItem(MediaItem.fromUri(track.uri))
                player.prepare()
                updateMasterVolume(masterVolume)
                player.play()
                if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET && player.audioSessionId != 0) {
                    setupSonyDsp(player.audioSessionId)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể phát bài này: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayExo(): Boolean {
        val player = exoPlayer ?: return false
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
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

    // Chống vỡ tiếng & quá tải âm lượng: Tự động hạ Headroom khi bật EQ/MegaBass
    private fun updateMasterVolume(vol: Float) {
        masterVolume = vol.coerceIn(0f, 1f)
        val headroomFactor = if (dynamicNormalizer) 0.78f else 0.92f
        exoPlayer?.volume = (masterVolume * headroomFactor).coerceIn(0f, 1f)
    }

    private fun setupSonyDsp(sessionId: Int) {
        try {
            bassBoost?.release()
            virtualizer?.release()
            equalizer?.release()
            loudnessEnhancer?.release()

            bassBoost = BassBoost(0, sessionId).apply {
                enabled = true
                setStrength((currentBassStrength * 750).toInt().toShort().coerceIn(0, 1000))
            }
            virtualizer = Virtualizer(0, sessionId).apply {
                enabled = true
                setStrength((currentSurroundStrength * 800).toInt().toShort().coerceIn(0, 1000))
            }
            equalizer = Equalizer(0, sessionId).apply {
                enabled = true
            }
        } catch (_: Exception) {}
    }

    private fun updateMegaBass(level: Float) {
        currentBassStrength = level.coerceIn(0f, 1f)
        try {
            // Giới hạn trần 750/1000 để âm trầm sâu, ấm mà không bị rè hay đẩy âm lượng quá to
            bassBoost?.setStrength((currentBassStrength * 750).toInt().toShort().coerceIn(0, 1000))
        } catch (_: Exception) {}
    }

    private fun updateSurround(level: Float) {
        currentSurroundStrength = level.coerceIn(0f, 1f)
        try {
            virtualizer?.setStrength((currentSurroundStrength * 850).toInt().toShort().coerceIn(0, 1000))
        } catch (_: Exception) {}
    }

    private fun updateEqBand(bandIndex: Int, gainDb: Float) {
        try {
            equalizer?.let { eq ->
                if (bandIndex < eq.numberOfBands) {
                    val range = eq.bandLevelRange
                    // Giới hạn biên độ EQ an toàn (-8dB đến +8dB) để tránh clipping
                    val safeDb = gainDb.coerceIn(-8f, 8f)
                    val targetMb = (safeDb * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                    eq.setBandLevel(bandIndex.toShort(), targetMb)
                }
            }
        } catch (_: Exception) {}
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

    // Quét nhạc đa tầng: MediaStore + Quét trực tiếp thư mục (Music, Download, SnapTube...)
    private fun scanAllMusicSources(context: Context): Pair<List<AudioTrack>, Int> {
        val validTracks = mutableListOf<AudioTrack>()
        val seenKeys = mutableSetOf<String>()
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
                        val title = rawTitle.ifBlank { displayName.substringBeforeLast(".") }.ifBlank { "Walkman Track $id" }
                        val artist = if (artistCol >= 0) cursor.getString(artistCol) ?: "Walkman Audio" else "Walkman Audio"
                        val duration = if (durCol >= 0) cursor.getLong(durCol) else 0L
                        val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val isRing = if (ringCol >= 0) cursor.getInt(ringCol) != 0 else false
                        val isNotif = if (notifCol >= 0) cursor.getInt(notifCol) != 0 else false
                        val isAlarm = if (alarmCol >= 0) cursor.getInt(alarmCol) != 0 else false
                        val relPath = if (relPathCol >= 0) cursor.getString(relPathCol) ?: "" else ""

                        val uniqueKey = "${title.lowercase()}_${sizeBytes}"
                        if (seenKeys.contains(uniqueKey)) continue

                        val checkText = "$title $displayName $relPath".lowercase()
                        val isShortSound = (duration in 1..14999) || (duration == 0L && sizeBytes in 1..100000)
                        val isSystemSound = isRing || isNotif || isAlarm || isExcludedSystemOrRecording(checkText, displayName)

                        if (isSystemSound || isShortSound) {
                            skippedCount++
                        } else {
                            seenKeys.add(uniqueKey)
                            val contentUri = ContentUris.withAppendedId(volumeUri, id)
                            validTracks.add(
                                AudioTrack(
                                    id = id,
                                    title = title,
                                    artist = if (artist.contains("<unknown>", true)) "Walkman Artist" else artist,
                                    duration = duration,
                                    uri = contentUri,
                                    folderInfo = relPath.trimEnd('/').substringAfterLast('/').ifEmpty { "Bộ nhớ máy" }
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Quét bổ sung trực tiếp các thư mục phổ biến (SnapTube, Music, Download) nếu hệ thống chưa lập chỉ mục
        try {
            val extRoot = Environment.getExternalStorageDirectory()
            val targetFolders = listOf(
                File(extRoot, "Music"),
                File(extRoot, "Download"),
                File(extRoot, "snaptube/download/SnapTube Audio"),
                File(extRoot, "SnapTube Audio"),
                File(extRoot, "Audio")
            )
            val audioExts = listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg")
            for (folder in targetFolders) {
                if (folder.exists() && folder.isDirectory) {
                    folder.walkTopDown().maxDepth(3).forEach { file ->
                        if (file.isFile && audioExts.any { file.name.lowercase().endsWith(it) }) {
                            val key = "${file.nameWithoutExtension.lowercase()}_${file.length()}"
                            if (!seenKeys.contains(key)) {
                                if (isExcludedSystemOrRecording(file.absolutePath.lowercase(), file.name) || file.length() < 100000) {
                                    skippedCount++
                                } else {
                                    seenKeys.add(key)
                                    validTracks.add(
                                        AudioTrack(
                                            id = System.currentTimeMillis() + validTracks.size,
                                            title = file.nameWithoutExtension,
                                            artist = folder.name,
                                            duration = 0L,
                                            uri = Uri.fromFile(file),
                                            folderInfo = folder.name
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return Pair(validTracks, skippedCount)
    }

    private fun parseManualFiles(context: Context, uris: List<Uri>): List<AudioTrack> {
        val added = mutableListOf<AudioTrack>()
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            var fileName = "Walkman_Track.mp3"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIdx >= 0) {
                        fileName = cursor.getString(nameIdx) ?: fileName
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

            added.add(AudioTrack(System.currentTimeMillis() + added.size, title, artist, duration, uri, "Thủ công"))
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
                                    folderInfo = dir.name ?: "Thư mục"
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
        try {
            bassBoost?.release()
            virtualizer?.release()
            equalizer?.release()
            loudnessEnhancer?.release()
            exoPlayer?.release()
        } catch (_: Exception) {}
    }
}

// Lưu trữ vĩnh viễn danh sách nhạc và thư mục đã chọn vào bộ nhớ máy
object PlaylistStorage {
    private const val PREF_NAME = "walkman_v4_storage"
    private const val KEY_TRACKS = "saved_tracks_json"
    private const val KEY_FOLDERS = "saved_folder_uris"
    private const val KEY_SKIN = "saved_skin_name"

    fun savePlaylist(context: Context, tracks: List<AudioTrack>) {
        try {
            val arr = JSONArray()
            tracks.take(1500).forEach { t ->
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("duration", t.duration)
                    put("uri", t.uri.toString())
                    put("folderInfo", t.folderInfo)
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
                        folderInfo = obj.optString("folderInfo", "Đã lưu")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
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
    onPlayTrack: (AudioTrack, () -> Unit) -> Unit,
    onTogglePlay: () -> Boolean,
    onStopPlayback: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSetMasterVolume: (Float) -> Unit,
    onSetBass: (Float) -> Unit,
    onSetSurround: (Float) -> Unit,
    onSetNormalizer: (Boolean) -> Unit,
    onSetEqBand: (Int, Float) -> Unit,
    getIsPlaying: () -> Boolean,
    getPosition: () -> Int,
    getDuration: () -> Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(0) }
    var currentSkin by remember { mutableStateOf(PlaylistStorage.loadSkin(context)) }
    var playlist by remember { mutableStateOf(PlaylistStorage.loadPlaylist(context)) }
    var skippedFilesCount by remember { mutableStateOf(0) }
    var isScanning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var isShuffle by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0) }
    var durMs by remember { mutableStateOf(1) }

    // Trạng thái Sony DSP đầy đủ như bản đầu + Chống vỡ tiếng
    var masterVol by remember { mutableStateOf(0.72f) }
    var megaBass by remember { mutableStateOf(0.45f) }
    var surroundVpt by remember { mutableStateOf(0.30f) }
    var clearAudioPlus by remember { mutableStateOf(true) }
    var dseeUltimate by remember { mutableStateOf(true) }
    var dynamicNorm by remember { mutableStateOf(true) }
    val eqBands = remember { mutableStateListOf(2f, 1f, 0f, 1.5f, 3f) }

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun playAtIndex(idx: Int) {
        if (playlist.isEmpty()) return
        val safeIdx = (idx + playlist.size) % playlist.size
        currentIndex = safeIdx
        onPlayTrack(playlist[safeIdx]) {
            val nextIdx = if (isShuffle) playlist.indices.random() else (currentIndex + 1) % playlist.size
            playAtIndex(nextIdx)
        }
        isPlaying = true
    }

    fun runFullScan() {
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
            val merged = (folderTracks + autoRes.first + playlist).distinctBy { "${it.title}_${it.folderInfo}" }
            PlaylistStorage.savePlaylist(context, merged)
            withContext(Dispatchers.Main) {
                playlist = merged
                skippedFilesCount = autoRes.second + folderSkipped
                isScanning = false
                Toast.makeText(context, "Đã nạp và lưu ${merged.size} bài hát!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runFullScan()
        else Toast.makeText(context, "Hãy dùng nút 'CHỌN THƯ MỤC' để lưu thư mục nhạc cố định!", Toast.LENGTH_LONG).show()
    }

    val manualFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            isScanning = true
            scope.launch(Dispatchers.IO) {
                val added = onParseManualUris(uris)
                val updated = (added + playlist).distinctBy { it.uri.toString() }
                PlaylistStorage.savePlaylist(context, updated)
                withContext(Dispatchers.Main) {
                    playlist = updated
                    isScanning = false
                    Toast.makeText(context, "Đã thêm và lưu ${added.size} bài!", Toast.LENGTH_SHORT).show()
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
                val updated = (res.first + playlist).distinctBy { "${it.title}_${it.folderInfo}" }
                PlaylistStorage.savePlaylist(context, updated)
                withContext(Dispatchers.Main) {
                    playlist = updated
                    skippedFilesCount += res.second
                    isScanning = false
                    Toast.makeText(context, "Đã lưu vĩnh viễn ${res.first.size} bài từ thư mục!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Tự động quét bổ sung nếu lần đầu mở mà danh sách trống
    LaunchedEffect(Unit) {
        val hasPerm = ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        if (hasPerm && playlist.isEmpty()) {
            runFullScan()
        }
    }

    LaunchedEffect(isPlaying) {
        while (true) {
            isPlaying = getIsPlaying()
            if (isPlaying) {
                posMs = getPosition()
                durMs = getDuration().coerceAtLeast(1)
            }
            delay(200)
        }
    }

    val currentTrack = playlist.getOrNull(currentIndex)

    Scaffold(
        topBar = {
            // Thanh chọn 4 giao diện Walkman: Chữ Trắng Sáng rõ nét trên nền tương phản cao
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
                    label = { Text("Walkman 4.0", fontWeight = FontWeight.Bold) }
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
                    label = { Text("Sony DSP", fontWeight = FontWeight.Bold) }
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
                0 -> FullScreenWalkmanDeck(
                    skin = currentSkin,
                    trackTitle = currentTrack?.title ?: "CHƯA CHỌN BÀI HÁT",
                    trackArtist = currentTrack?.artist ?: "Bấm sang tab 'Nhạc' để quét hoặc chọn thư mục",
                    folderName = currentTrack?.folderInfo ?: "STEREO CASSETTE DECK",
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
                    },
                    onToggleShuffle = { isShuffle = !isShuffle },
                    onPlayPause = {
                        if (playlist.isEmpty()) {
                            activeTab = 1
                        } else if (!isPlaying && posMs == 0 && currentTrack != null) {
                            playAtIndex(currentIndex)
                        } else {
                            isPlaying = onTogglePlay()
                        }
                    },
                    onStop = {
                        onStopPlayback()
                        isPlaying = false
                        posMs = 0
                    },
                    onPrev = {
                        if (playlist.isNotEmpty()) playAtIndex(currentIndex - 1)
                    },
                    onNext = {
                        if (playlist.isNotEmpty()) {
                            val next = if (isShuffle) playlist.indices.random() else currentIndex + 1
                            playAtIndex(next)
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
                        if (hasPerm) runFullScan() else permLauncher.launch(requiredPermission)
                    },
                    onPickFiles = { manualFilesLauncher.launch(arrayOf("audio/*")) },
                    onPickFolder = { folderPickerLauncher.launch(null) },
                    onClearSaved = {
                        playlist = emptyList()
                        PlaylistStorage.savePlaylist(context, emptyList())
                        Toast.makeText(context, "Đã làm mới danh sách!", Toast.LENGTH_SHORT).show()
                    },
                    onSelectTrack = { idx ->
                        playAtIndex(idx)
                        activeTab = 0
                    }
                )
                2 -> FullSonyDspStudioView(
                    masterVol = masterVol,
                    megaBass = megaBass,
                    surroundVpt = surroundVpt,
                    clearAudio = clearAudioPlus,
                    dseeUltimate = dseeUltimate,
                    dynamicNorm = dynamicNorm,
                    eqBands = eqBands,
                    onMasterVolChange = {
                        masterVol = it
                        onSetMasterVolume(it)
                    },
                    onClearAudioToggle = {
                        clearAudioPlus = it
                        if (it) {
                            megaBass = 0.55f
                            surroundVpt = 0.40f
                            onSetBass(0.55f)
                            onSetSurround(0.40f)
                        }
                    },
                    onDseeToggle = { dseeUltimate = it },
                    onDynamicNormToggle = {
                        dynamicNorm = it
                        onSetNormalizer(it)
                    },
                    onBassChange = {
                        megaBass = it
                        onSetBass(it)
                    },
                    onSurroundChange = {
                        surroundVpt = it
                        onSetSurround(it)
                    },
                    onBandChange = { idx, gain ->
                        eqBands[idx] = gain
                        onSetEqBand(idx, gain)
                    },
                    onApplyPreset = { presetValues ->
                        presetValues.forEachIndexed { i, v ->
                            eqBands[i] = v
                            onSetEqBand(i, v)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun FullScreenWalkmanDeck(
    skin: WalkmanSkin,
    trackTitle: String,
    trackArtist: String,
    folderName: String,
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

    // Toàn màn hình 100% (fillMaxSize) không còn bị thu nhỏ
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
            .background(bodyGradient, RoundedCornerShape(24.dp))
            .border(
                width = 3.dp,
                color = if (skin == WalkmanSkin.TPSL2) Color(0xFF94A3B8) else accentColor.copy(alpha = 0.85f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Header kim loại chính hãng theo từng đời máy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (skin == WalkmanSkin.TPSL2) Color(0xFFD0D7E2) else Color(0xFF1E2433),
                    RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = skin.fullName,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    color = if (skin == WalkmanSkin.TPSL2) Color(0xFF0F172A) else Color.White
                )
                Text(
                    text = folderName,
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
                    text = if (skin == WalkmanSkin.TPSL2) "BATT" else "HI-RES DSP",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (skin == WalkmanSkin.TPSL2) Color(0xFF0F172A) else Color.LightGray,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Khu vực Màn hình chính / Cửa sổ băng Cassette (Tự co giãn lấp đầy chiều dọc màn hình)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF080B12), RoundedCornerShape(18.dp))
                .border(2.dp, Color(0xFF334155), RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (skin) {
                    WalkmanSkin.TPSL2 -> {
                        // GIAO DIỆN BĂNG CASSETTE CỔ ĐIỂN SONY TPS-L2
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

                        // Hai trục cuộn băng Cassette lớn + Bộ đếm cơ học 3 số
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF162032), RoundedCornerShape(14.dp))
                                .border(2.dp, Color(0xFF475569), RoundedCornerShape(14.dp))
                                .padding(vertical = 20.dp, horizontal = 12.dp),
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
                        // GIAO DIỆN ĐỒNG HỒ KIM ANALOG VU METER & SPECTRUM HI-RES
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF281F12), RoundedCornerShape(12.dp))
                                .border(2.dp, Color(0xFFF59E0B), RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("ANALOG VU LEVEL", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text("-20  -10  -3   0  +3 dB", color = Color(0xFFF87171), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Canvas(modifier = Modifier.fillMaxWidth().height(85.dp)) {
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
                        // GIAO DIỆN W995i & W850i WALKMAN KÈM SÓNG SPECTRUM ĐỘNG
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(skin.shortName, color = accentColor, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            Text(if (isShuffle) "SHUFFLE ON" else "MEGABASS + CLEAR AUDIO", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }

                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                rotate(if (isPlaying) angle else 0f) {
                                    drawCircle(color = accentColor.copy(alpha = 0.2f), style = Stroke(width = 22f))
                                    drawCircle(color = accentColor, radius = size.minDimension / 2.3f, style = Stroke(width = 6f))
                                    drawLine(color = Color.White, start = Offset(size.width / 2, 10f), end = Offset(size.width / 2, 34f), strokeWidth = 5f)
                                }
                                drawCircle(color = Color.White, radius = 16f)
                            }
                        }

                        // Dải đèn nháy Spectrum Visualizer
                        Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
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

                // Thanh tua nhạc (Seekbar) + Thời gian hiển thị rõ số 00:00
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = posMs.toFloat(),
                        onValueChange = { onSeek(it.toInt()) },
                        valueRange = 0f..durMs.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTimeMs(posMs), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                        Text(formatTimeMs(durMs), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Thanh chỉnh Âm lượng Master trực tiếp (Tránh bị quá to)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111726), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
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

        Spacer(modifier = Modifier.height(10.dp))

        // 4. Cụm phím bấm vật lý: Nếu là TPS-L2 thì hiện dải phím cơ học Cassette, còn lại hiện D-Pad
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
                        .size(142.dp)
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
                        modifier = Modifier.size(60.dp),
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
            .height(54.dp)
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
                    Text("THƯ VIỆN NHẠC (TỰ ĐỘNG LƯU)", fontWeight = FontWeight.Black, color = Color(0xFFFF6600), fontSize = 14.sp)
                    if (tracks.isNotEmpty()) {
                        TextButton(onClick = onClearSaved, contentPadding = PaddingValues(0.dp)) {
                            Text("Xóa DS", color = Color(0xFFEF4444), fontSize = 11.sp)
                        }
                    }
                }
                Text(
                    text = if (isScanning) "Đang quét bộ nhớ và lưu danh sách..."
                    else "Hiện có: ${tracks.size} bài • Đã lọc bỏ: $skippedCount file hệ thống/ghi âm",
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
                    "Bấm 'QUÉT TỰ ĐỘNG' hoặc 'LƯU THƯ MỤC' ở trên.\nDanh sách sẽ được lưu vĩnh viễn cho các lần mở sau!",
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
                            Text("${track.artist} • [${track.folderInfo}]", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
fun FullSonyDspStudioView(
    masterVol: Float,
    megaBass: Float,
    surroundVpt: Float,
    clearAudio: Boolean,
    dseeUltimate: Boolean,
    dynamicNorm: Boolean,
    eqBands: List<Float>,
    onMasterVolChange: (Float) -> Unit,
    onClearAudioToggle: (Boolean) -> Unit,
    onDseeToggle: (Boolean) -> Unit,
    onDynamicNormToggle: (Boolean) -> Unit,
    onBassChange: (Float) -> Unit,
    onSurroundChange: (Float) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    onApplyPreset: (List<Float>) -> Unit
) {
    val bands = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
    val presets = listOf(
        "Heavy" to listOf(5f, 3f, -1f, 3f, 4f),
        "Pop" to listOf(2f, 4f, 2f, 1f, 3f),
        "Jazz" to listOf(3f, 1f, -2f, 2f, 4f),
        "Unique" to listOf(4f, -2f, 3f, -1f, 5f),
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("TỔ HỢP XỬ LÝ ÂM THANH SONY DSP 4.0", fontWeight = FontWeight.Black, fontSize = 15.sp, color = Color(0xFFFF6600))

        // 1. Kiểm soát Âm lượng & Chống vỡ tiếng (Dynamic Normalizer)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("MASTER PRE-AMP VOLUME", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text("${(masterVol * 100).toInt()}%", color = Color(0xFF38BDF8), fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = masterVol,
                    onValueChange = onMasterVolChange,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF38BDF8), activeTrackColor = Color(0xFF38BDF8))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dynamic Normalizer™", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Text("Chống đẩy âm lượng quá lớn & cân bằng bài hát", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = dynamicNorm, onCheckedChange = onDynamicNormToggle)
                }
            }
        }

        // 2. Các công nghệ Sony độc quyền (ClearAudio+, DSEE HX)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ClearAudio+™", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Text("Tái tạo âm trường chuẩn máy nghe nhạc Sony", color = Color.Gray, fontSize = 11.sp)
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
                        Text("DSEE Ultimate™ Upscaling", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Text("Khôi phục dải âm cao bị nén của file MP3", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = dseeUltimate, onCheckedChange = onDseeToggle)
                }
            }
        }

        // 3. MegaBass & VPT Surround
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

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("VPT SURROUND STUDIO (ÂM VÒM 3D)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text("${(surroundVpt * 100).toInt()}%", color = Color(0xFFA855F7), fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = surroundVpt,
                    onValueChange = onSurroundChange,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFA855F7), activeTrackColor = Color(0xFFA855F7))
                )
            }
        }

        // 4. Equalizer 5 băng tần + Presets
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SONY 5-BAND GRAPHIC EQUALIZER", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (name, vals) ->
                        OutlinedButton(
                            onClick = { onApplyPreset(vals) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Text(name, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                bands.forEachIndexed { idx, label ->
                    val currentVal = eqBands.getOrElse(idx) { 0f }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.width(52.dp), fontSize = 11.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                        Slider(
                            value = currentVal,
                            onValueChange = { onBandChange(idx, it) },
                            valueRange = -8f..8f,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "%+.0f dB".format(currentVal),
                            modifier = Modifier.width(46.dp),
                            fontSize = 11.sp,
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
