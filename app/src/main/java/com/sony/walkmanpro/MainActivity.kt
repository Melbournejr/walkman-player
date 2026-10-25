package com.sony.walkmanpro

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri,
    val folderInfo: String
)

enum class WalkmanSkin(val label: String) {
    W995I("W995i"),
    W850I("W850i"),
    TPSL2("TPS-L2"),
    A306("NW-A306")
}

class MainActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private var bassBoost: BassBoost? = null
    private var equalizer: Equalizer? = null
    private var onTrackEndedCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WalkmanProV3Root(
                    onScanStorage = { scanMusicSafe(this@MainActivity) },
                    onParseManualUris = { uris -> parseManualFiles(this@MainActivity, uris) },
                    onParseFolderUri = { treeUri -> parseFolderTree(this@MainActivity, treeUri) },
                    onPlayTrack = { track, onEnd ->
                        onTrackEndedCallback = onEnd
                        playTrackSafe(this@MainActivity, track)
                    },
                    onTogglePlay = { togglePlaySafe() },
                    onSeekTo = { ms -> seekToSafe(ms) },
                    onSetBass = { level -> setMegaBassSafe(level) },
                    onSetEqBand = { band, gain -> setEqBandSafe(band, gain) },
                    getIsPlaying = {
                        try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
                    },
                    getPosition = {
                        try { mediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }
                    },
                    getDuration = {
                        try { (mediaPlayer?.duration ?: 1).coerceAtLeast(1) } catch (_: Exception) { 1 }
                    }
                )
            }
        }
    }

    private fun isExcludedFile(textToCheck: String, fileName: String): Boolean {
        val excludedKeywords = listOf(
            "record", "call@", "call_", "voice", "ghi am", "ghi_am", "cuoc goi",
            "ringtone", "notification", "alarm", "sound_recorder", "miui/sound_recorder",
            "audiorecorder", "call_rec", "ui/effect"
        )
        val lowerName = fileName.lowercase()
        return excludedKeywords.any { kw -> textToCheck.contains(kw) } ||
                lowerName.endsWith(".amr") ||
                lowerName.endsWith(".3gp") ||
                lowerName.endsWith(".awb")
    }

    private fun scanMusicSafe(context: Context): Pair<List<AudioTrack>, Int> {
        val validTracks = mutableListOf<AudioTrack>()
        val seenIds = mutableSetOf<Long>()
        var skippedCount = 0

        val volumes = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            volumes.add(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
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
                    " DESC"
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
                        if (seenIds.contains(id)) continue

                        val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                        val rawTitle = if (titleCol >= 0) cursor.getString(titleCol) ?: "" else ""
                        val title = rawTitle.ifBlank { displayName.substringBeforeLast(".") }.ifBlank { "Track #" }
                        val artist = if (artistCol >= 0) cursor.getString(artistCol) ?: "Walkman Audio" else "Walkman Audio"
                        val duration = if (durCol >= 0) cursor.getLong(durCol) else 0L
                        val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val isRing = if (ringCol >= 0) cursor.getInt(ringCol) != 0 else false
                        val isNotif = if (notifCol >= 0) cursor.getInt(notifCol) != 0 else false
                        val isAlarm = if (alarmCol >= 0) cursor.getInt(alarmCol) != 0 else false
                        val relPath = if (relPathCol >= 0) cursor.getString(relPathCol) ?: "" else ""

                        val checkText = "  ".lowercase()
                        val isShortSound = (duration in 1..14999) || (duration == 0L && sizeBytes in 1..120000)
                        val isSystemSound = isRing || isNotif || isAlarm || isExcludedFile(checkText, displayName)

                        if (isSystemSound || isShortSound) {
                            skippedCount++
                        } else {
                            seenIds.add(id)
                            val contentUri = ContentUris.withAppendedId(volumeUri, id)
                            validTracks.add(
                                AudioTrack(
                                    id = id,
                                    title = title,
                                    artist = if (artist.contains("<unknown>", true)) "Walkman Artist" else artist,
                                    duration = duration,
                                    uri = contentUri,
                                    folderInfo = relPath.ifEmpty { "Bộ nhớ máy" }
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
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIdx >= 0) {
                        fileName = cursor.getString(nameIdx) ?: fileName
                    }
                }
            } catch (_: Exception) {}

            var title = fileName.substringBeforeLast(".")
            var artist = "Chọn thủ công"
            var duration = 0L

            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { if (it.isNotBlank()) title = it }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { if (it.isNotBlank()) artist = it }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let { duration = it.toLongOrNull() ?: 0L }
                retriever.release()
            } catch (_: Exception) {}

            added.add(AudioTrack(System.currentTimeMillis() + added.size, title, artist, duration, uri, "File lẻ"))
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
                        if (isExcludedFile(lower, name) || file.length() in 1..100000) {
                            skipped++
                        } else {
                            added.add(
                                AudioTrack(
                                    id = System.currentTimeMillis() + added.size,
                                    title = name.substringBeforeLast("."),
                                    artist = dir.name ?: "Folder Music",
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

    private fun playTrackSafe(context: Context, track: AudioTrack) {
        try {
            mediaPlayer?.reset()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, track.uri)
                prepare()
                start()
                setOnCompletionListener { onTrackEndedCallback?.invoke() }
            }
            setupDspSafe(mediaPlayer!!.audioSessionId)
        } catch (e: Exception) {
            Toast.makeText(context, "Không thể phát file: ", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupDspSafe(sessionId: Int) {
        try {
            bassBoost?.release()
            equalizer?.release()
            if (sessionId != 0) {
                bassBoost = BassBoost(0, sessionId).apply { enabled = true }
                equalizer = Equalizer(0, sessionId).apply { enabled = true }
            }
        } catch (_: Exception) {}
    }

    private fun seekToSafe(ms: Int) {
        try {
            mediaPlayer?.let {
                it.seekTo(ms.coerceIn(0, it.duration))
            }
        } catch (_: Exception) {}
    }

    private fun setMegaBassSafe(level: Float) {
        try {
            bassBoost?.setStrength((level * 1000).toInt().toShort().coerceIn(0, 1000))
        } catch (_: Exception) {}
    }

    private fun setEqBandSafe(bandIndex: Int, gainDb: Float) {
        try {
            equalizer?.let { eq ->
                if (bandIndex < eq.numberOfBands) {
                    val range = eq.bandLevelRange
                    val targetMb = (gainDb * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                    eq.setBandLevel(bandIndex.toShort(), targetMb)
                }
            }
        } catch (_: Exception) {}
    }

    private fun togglePlaySafe(): Boolean {
        return try {
            mediaPlayer?.let {
                if (it.isPlaying) it.pause() else it.start()
                it.isPlaying
            } ?: false
        } catch (_: Exception) { false }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bassBoost?.release()
            equalizer?.release()
            mediaPlayer?.release()
        } catch (_: Exception) {}
    }
}

@Composable
fun WalkmanProV3Root(
    onScanStorage: () -> Pair<List<AudioTrack>, Int>,
    onParseManualUris: (List<Uri>) -> List<AudioTrack>,
    onParseFolderUri: (Uri) -> Pair<List<AudioTrack>, Int>,
    onPlayTrack: (AudioTrack, () -> Unit) -> Unit,
    onTogglePlay: () -> Boolean,
    onSeekTo: (Int) -> Unit,
    onSetBass: (Float) -> Unit,
    onSetEqBand: (Int, Float) -> Unit,
    getIsPlaying: () -> Boolean,
    getPosition: () -> Int,
    getDuration: () -> Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(0) }
    var currentSkin by remember { mutableStateOf(WalkmanSkin.W995I) }
    var playlist by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var skippedFilesCount by remember { mutableStateOf(0) }
    var isScanning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var isShuffle by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0) }
    var durMs by remember { mutableStateOf(1) }
    var megaBass by remember { mutableStateOf(0.6f) }
    var clearAudioEnabled by remember { mutableStateOf(true) }

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
            val nextIdx = if (isShuffle) (playlist.indices).random() else (currentIndex + 1) % playlist.size
            playAtIndex(nextIdx)
        }
        isPlaying = true
    }

    fun triggerSafeScan() {
        if (isScanning) return
        isScanning = true
        scope.launch(Dispatchers.IO) {
            val result = onScanStorage()
            withContext(Dispatchers.Main) {
                val existingManual = playlist.filter { it.folderInfo == "File lẻ" || it.folderInfo == "Thư mục" }
                playlist = (existingManual + result.first).distinctBy { it.uri.toString() }
                skippedFilesCount = result.second
                isScanning = false
                Toast.makeText(context, "Walkman 3.0: Tìm thấy  bài nhạc!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) triggerSafeScan()
        else Toast.makeText(context, "Hãy dùng nút 'CHỌN THƯ MỤC' hoặc '+ CHỌN FILE'!", Toast.LENGTH_LONG).show()
    }

    val manualFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            isScanning = true
            scope.launch(Dispatchers.IO) {
                val manualTracks = onParseManualUris(uris)
                withContext(Dispatchers.Main) {
                    playlist = (manualTracks + playlist).distinctBy { it.uri.toString() }
                    isScanning = false
                    Toast.makeText(context, "Đã thêm  file nhạc!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            isScanning = true
            scope.launch(Dispatchers.IO) {
                val res = onParseFolderUri(treeUri)
                withContext(Dispatchers.Main) {
                    playlist = (res.first + playlist).distinctBy { it.uri.toString() }
                    skippedFilesCount += res.second
                    isScanning = false
                    Toast.makeText(context, "Đã nạp  bài từ thư mục!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val hasPerm = ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) triggerSafeScan()
    }

    LaunchedEffect(isPlaying) {
        while (true) {
            isPlaying = getIsPlaying()
            if (isPlaying) {
                posMs = getPosition()
                durMs = getDuration().coerceAtLeast(1)
            }
            delay(250)
        }
    }

    val currentTrack = playlist.getOrNull(currentIndex)

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0F1D))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WalkmanSkin.values().forEach { skin ->
                    val selected = skin == currentSkin
                    Button(
                        onClick = { currentSkin = skin },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFFFF5500) else Color(0xFF1E293B)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(skin.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0A0F1D)) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.Album, null) },
                    label = { Text("Walkman 3.0") }
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.LibraryMusic, null) },
                    label = { Text("Nhạc ()") }
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Equalizer, null) },
                    label = { Text("Âm thanh DSP") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF060911))
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when (activeTab) {
                0 -> WalkmanV3MultiSkinPlayer(
                    skin = currentSkin,
                    trackTitle = currentTrack?.title ?: "CHƯA CÓ BÀI HÁT",
                    trackArtist = currentTrack?.artist ?: "Bấm sang tab 'Nhạc' để quét hoặc chọn file",
                    isPlaying = isPlaying,
                    isShuffle = isShuffle,
                    posMs = posMs,
                    durMs = durMs,
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
                    onPrev = {
                        if (playlist.isNotEmpty()) playAtIndex(currentIndex - 1)
                    },
                    onNext = {
                        if (playlist.isNotEmpty()) {
                            val next = if (isShuffle) (playlist.indices).random() else currentIndex + 1
                            playAtIndex(next)
                        }
                    }
                )
                1 -> SmartScannerLibraryView(
                    tracks = playlist,
                    skippedCount = skippedFilesCount,
                    isScanning = isScanning,
                    currentIndex = currentIndex,
                    onAutoScan = {
                        val hasPerm = ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) triggerSafeScan() else permLauncher.launch(requiredPermission)
                    },
                    onPickFiles = { manualFilesLauncher.launch(arrayOf("audio/*")) },
                    onPickFolder = { folderPickerLauncher.launch(null) },
                    onSelectTrack = { idx ->
                        playAtIndex(idx)
                        activeTab = 0
                    }
                )
                2 -> EqualizerDspTabView(
                    megaBass = megaBass,
                    clearAudio = clearAudioEnabled,
                    onClearAudioToggle = {
                        clearAudioEnabled = it
                        if (it) {
                            onSetBass(0.8f)
                            megaBass = 0.8f
                        }
                    },
                    onBassChange = {
                        megaBass = it
                        onSetBass(it)
                    },
                    onBandChange = onSetEqBand
                )
            }
        }
    }
}

@Composable
fun WalkmanV3MultiSkinPlayer(
    skin: WalkmanSkin,
    trackTitle: String,
    trackArtist: String,
    isPlaying: Boolean,
    isShuffle: Boolean,
    posMs: Int,
    durMs: Int,
    onSeek: (Int) -> Unit,
    onToggleShuffle: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val accentColor = when (skin) {
        WalkmanSkin.W995I -> Color(0xFFFF3B1D)
        WalkmanSkin.W850I -> Color(0xFFFF6600)
        WalkmanSkin.TPSL2 -> Color(0xFF38BDF8)
        WalkmanSkin.A306 -> Color(0xFFF59E0B)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "deg"
    )

    Column(
        modifier = Modifier
            .width(340.dp)
            .background(
                if (skin == WalkmanSkin.TPSL2) Color(0xFF162C54) else Color(0xFF151821),
                RoundedCornerShape(if (skin == WalkmanSkin.TPSL2) 16.dp else 28.dp)
            )
            .border(
                3.dp,
                if (skin == WalkmanSkin.TPSL2) Color(0xFF94A3B8) else accentColor.copy(alpha = 0.8f),
                RoundedCornerShape(if (skin == WalkmanSkin.TPSL2) 16.dp else 28.dp)
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (skin == WalkmanSkin.TPSL2 || skin == WalkmanSkin.A306) "SONY WALKMAN" else "SONY ERICSSON",
            fontSize = 11.sp,
            letterSpacing = 3.sp,
            color = Color.LightGray,
            fontWeight = FontWeight.Black
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Màn hình hiển thị tùy biến riêng cho từng dòng máy
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .background(Color(0xFF080B12), RoundedCornerShape(14.dp))
                .border(2.dp, Color(0xFF2E3646), RoundedCornerShape(14.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(skin.label, color = accentColor, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(if (isShuffle) "SHUFFLE ON" else "CLEAR AUDIO+", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            // Đồ họa trung tâm khác nhau theo từng máy
            when (skin) {
                WalkmanSkin.TPSL2 -> {
                    // Hai cuộn băng Cassette quay
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B), RoundedCornerShape(10.dp))
                            .padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CassetteReelView(if (isPlaying) angle else 0f)
                        Text(
                            String.format("%03d", (posMs / 1000) % 999),
                            color = Color(0xFFFDE68A),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp
                        )
                        CassetteReelView(if (isPlaying) angle else 0f)
                    }
                }
                WalkmanSkin.A306 -> {
                    // Đồng hồ Analog VU Meter + Hi-Res
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF271F14), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("HI-RES AUDIO • ANALOG VU", color = Color(0xFFFBBF24), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(8.dp))
                        Canvas(modifier = Modifier.fillMaxWidth().height(55.dp)) {
                            val needleAngle = if (isPlaying) ((angle % 60f) - 30f) else -35f
                            rotate(needleAngle, pivot = Offset(size.width / 2, size.height)) {
                                drawLine(
                                    color = Color(0xFFEF4444),
                                    start = Offset(size.width / 2, size.height),
                                    end = Offset(size.width / 2, 6f),
                                    strokeWidth = 5f
                                )
                            }
                        }
                    }
                }
                else -> {
                    // W995i & W850i Đĩa Quang Walkman xoay
                    Canvas(modifier = Modifier.size(105.dp)) {
                        rotate(if (isPlaying) angle else 0f) {
                            drawCircle(color = accentColor.copy(alpha = 0.25f), style = Stroke(width = 16f))
                            drawCircle(color = accentColor, radius = size.minDimension / 2.3f, style = Stroke(width = 5f))
                        }
                        drawCircle(color = Color.White, radius = 12f)
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(trackTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(trackArtist, color = accentColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // Thanh tua nhạc (Seekbar) + Thời gian
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = posMs.toFloat(),
                    onValueChange = { onSeek(it.toInt()) },
                    valueRange = 0f..durMs.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth().height(22.dp),
                    colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(":", fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                    Text(":", fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cụm phím điều khiển D-Pad + Nút Shake/Shuffle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(Icons.Default.Shuffle, null, tint = if (isShuffle) accentColor else Color.Gray)
            }

            Box(
                modifier = Modifier
                    .size(136.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF3A4152), Color(0xFF1B202B))))
                    .border(4.dp, if (isPlaying && skin == WalkmanSkin.W850I) accentColor else Color(0xFF475569), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onPrev, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                    Icon(Icons.Default.SkipPrevious, null, tint = Color.White)
                }
                IconButton(onClick = onNext, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                    Icon(Icons.Default.SkipNext, null, tint = Color.White)
                }
                Button(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    modifier = Modifier.size(58.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }

            IconButton(onClick = onNext) {
                Icon(Icons.Default.Vibration, null, tint = accentColor)
            }
        }
    }
}

@Composable
fun CassetteReelView(rotation: Float) {
    Canvas(modifier = Modifier.size(52.dp)) {
        drawCircle(color = Color(0xFFE2E8F0), style = Stroke(width = 5f))
        rotate(rotation) {
            for (i in 0 until 6) {
                rotate(i * 60f) {
                    drawLine(
                        color = Color(0xFFE2E8F0),
                        start = Offset(size.width / 2, size.height / 2 - 6f),
                        end = Offset(size.width / 2, 4f),
                        strokeWidth = 4f
                    )
                }
            }
        }
    }
}

@Composable
fun SmartScannerLibraryView(
    tracks: List<AudioTrack>,
    skippedCount: Int,
    isScanning: Boolean,
    currentIndex: Int,
    onAutoScan: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onSelectTrack: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141C2B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("THƯ VIỆN NHẠC WALKMAN 3.0", fontWeight = FontWeight.Black, color = Color(0xFFFF6600), fontSize = 14.sp)
                Text(
                    if (isScanning) "Đang quét và lọc dữ liệu âm thanh..."
                    else "Hiện có:  bài • Đã lọc bỏ  file hệ thống/ghi âm",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onAutoScan,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5500)),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Text("QUÉT TỰ ĐỘNG", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onPickFolder,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Text("CHỌN THƯ MỤC", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onPickFiles,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Text("+ CHỌN FILE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (tracks.isEmpty() && !isScanning) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Bấm 'QUÉT TỰ ĐỘNG', 'CHỌN THƯ MỤC' hoặc '+ CHỌN FILE' ở trên để nạp nhạc",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(tracks) { index, track ->
                    val isSelected = index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) Color(0xFF291A14) else Color(0xFF111622), RoundedCornerShape(10.dp))
                            .border(1.dp, if (isSelected) Color(0xFFFF5500) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { onSelectTrack(index) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(" • ", color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (track.duration > 0) {
                            Text(
                                ":",
                                color = Color.LightGray,
                                fontSize = 11.sp,
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
fun EqualizerDspTabView(
    megaBass: Float,
    clearAudio: Boolean,
    onClearAudioToggle: (Boolean) -> Unit,
    onBassChange: (Float) -> Unit,
    onBandChange: (Int, Float) -> Unit
) {
    val bands = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
    val bandValues = remember { mutableStateListOf(3f, 1f, 0f, 2f, 4f) }

    Column(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("BỘ XỬ LÝ ÂM THANH SONY DSP 3.0", fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color(0xFFFF6600))

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ClearAudio+™", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Text("Tự động tối ưu âm trường Sony Walkman", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(checked = clearAudio, onCheckedChange = onClearAudioToggle)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("MEGABASS / CLEARBASS", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text("+ dB", color = Color(0xFFFF6600), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = megaBass,
                    onValueChange = onBassChange,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFF5500), activeTrackColor = Color(0xFFFF5500))
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF121826))) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("EQUALIZER 5 BĂNG TẦN", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                bands.forEachIndexed { idx, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.width(55.dp), fontSize = 11.sp, color = Color.LightGray, fontFamily = FontFamily.Monospace)
                        Slider(
                            value = bandValues[idx],
                            onValueChange = {
                                bandValues[idx] = it
                                onBandChange(idx, it)
                            },
                            valueRange = -10f..10f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
