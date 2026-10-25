package com.sony.walkmanpro

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import kotlinx.coroutines.delay

data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri,
    val path: String
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WalkmanProRoot(
                    onScanStorage = { minSec -> scanMusicFiltered(this, minSec) },
                    onPlayTrack = { track -> playTrack(this, track) },
                    onTogglePlay = { togglePlay() },
                    onSeek = { delta -> seekBy(delta) },
                    onSetBass = { level -> setMegaBass(level) },
                    onSetEqBand = { band, gain -> setEqBand(band, gain) },
                    getIsPlaying = { mediaPlayer?.isPlaying == true },
                    getPosition = { mediaPlayer?.currentPosition ?: 0 },
                    getDuration = { mediaPlayer?.duration ?: 1 }
                )
            }
        }
    }

    private fun scanMusicFiltered(context: Context, minDurationSec: Int): Pair<List<AudioTrack>, Int> {
        val validTracks = mutableListOf<AudioTrack>()
        var skippedCount = 0

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.IS_NOTIFICATION
        )

        val selection = " != 0 AND " +
                " == 0 AND " +
                " == 0 AND " +
                " == 0"

        val excludedKeywords = listOf(
            "record", "call", "voice", "ghi am", "ghi_am", "cuoc goi",
            "ringtone", "notification", "alarms", "whatsapp audio", "telegram", "zalo", "sound_recorder"
        )

        context.contentResolver.query(collection, projection, selection, null, " ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                val duration = cursor.getLong(durCol)
                val path = (cursor.getString(dataCol) ?: "").lowercase()

                val isRecordingOrSystem = excludedKeywords.any { kw ->
                    path.contains(kw) || title.lowercase().contains(kw)
                } || path.endsWith(".amr") || path.endsWith(".3gp")

                val isTooShort = duration < (minDurationSec * 1000L)

                if (isRecordingOrSystem || isTooShort) {
                    skippedCount++
                } else {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    validTracks.add(AudioTrack(id, title, artist, duration, uri, path))
                }
            }
        }
        return Pair(validTracks, skippedCount)
    }

    private fun playTrack(context: Context, track: AudioTrack) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(context, track.uri)
            prepare()
            start()
        }
        setupDsp(mediaPlayer!!.audioSessionId)
    }

    private fun setupDsp(sessionId: Int) {
        try {
            bassBoost?.release()
            equalizer?.release()
            bassBoost = BassBoost(0, sessionId).apply { enabled = true }
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
        } catch (_: Exception) {}
    }

    private fun setMegaBass(level: Float) {
        try {
            bassBoost?.setStrength((level * 1000).toInt().toShort().coerceIn(0, 1000))
        } catch (_: Exception) {}
    }

    private fun setEqBand(bandIndex: Int, gainDb: Float) {
        try {
            equalizer?.let { eq ->
                if (bandIndex < eq.numberOfBands) {
                    val mB = (gainDb * 100).toInt().toShort()
                    eq.setBandLevel(bandIndex.toShort(), mB)
                }
            }
        } catch (_: Exception) {}
    }

    private fun togglePlay(): Boolean {
        mediaPlayer?.let {
            if (it.isPlaying) it.pause() else it.start()
            return it.isPlaying
        }
        return false
    }

    private fun seekBy(deltaMs: Int) {
        mediaPlayer?.let {
            it.seekTo((it.currentPosition + deltaMs).coerceIn(0, it.duration))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bassBoost?.release()
        equalizer?.release()
        mediaPlayer?.release()
    }
}

@Composable
fun WalkmanProRoot(
    onScanStorage: (Int) -> Pair<List<AudioTrack>, Int>,
    onPlayTrack: (AudioTrack) -> Unit,
    onTogglePlay: () -> Boolean,
    onSeek: (Int) -> Unit,
    onSetBass: (Float) -> Unit,
    onSetEqBand: (Int, Float) -> Unit,
    getIsPlaying: () -> Boolean,
    getPosition: () -> Int,
    getDuration: () -> Int
) {
    var activeTab by remember { mutableStateOf(0) }
    var currentSkin by remember { mutableStateOf(WalkmanSkin.W995I) }
    var playlist by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var skippedFilesCount by remember { mutableStateOf(0) }
    var currentIndex by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0) }
    var durMs by remember { mutableStateOf(1) }
    var megaBass by remember { mutableStateOf(0.6f) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val res = onScanStorage(30)
            playlist = res.first
            skippedFilesCount = res.second
        }
    }

    LaunchedEffect(Unit) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permLauncher.launch(perm)
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
                modifier = Modifier.fillMaxWidth().background(Color(0xFF0A0F1D)).padding(10.dp),
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
                    label = { Text("Máy phát") }
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.LibraryMusic, null) },
                    label = { Text("Quét Nhạc ()") }
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Equalizer, null) },
                    label = { Text("Equalizer") }
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
                0 -> CleanWalkmanPlayerView(
                    skin = currentSkin,
                    trackTitle = currentTrack?.title ?: "CHƯA CHỌN BÀI HÁT",
                    trackArtist = currentTrack?.artist ?: "Bấm Tab 'Quét Nhạc' để nạp nhạc",
                    isPlaying = isPlaying,
                    posMs = posMs,
                    durMs = durMs,
                    onPlayPause = { isPlaying = onTogglePlay() },
                    onPrev = {
                        if (playlist.isNotEmpty()) {
                            currentIndex = (currentIndex - 1 + playlist.size) % playlist.size
                            onPlayTrack(playlist[currentIndex])
                            isPlaying = true
                        }
                    },
                    onNext = {
                        if (playlist.isNotEmpty()) {
                            currentIndex = (currentIndex + 1) % playlist.size
                            onPlayTrack(playlist[currentIndex])
                            isPlaying = true
                        }
                    }
                )
                1 -> SmartScannerLibraryView(
                    tracks = playlist,
                    skippedCount = skippedFilesCount,
                    currentIndex = currentIndex,
                    onRescan = {
                        val res = onScanStorage(30)
                        playlist = res.first
                        skippedFilesCount = res.second
                    },
                    onSelectTrack = { idx ->
                        currentIndex = idx
                        onPlayTrack(playlist[idx])
                        isPlaying = true
                        activeTab = 0
                    }
                )
                2 -> EqualizerDspTabView(
                    megaBass = megaBass,
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
fun CleanWalkmanPlayerView(
    skin: WalkmanSkin,
    trackTitle: String,
    trackArtist: String,
    isPlaying: Boolean,
    posMs: Int,
    durMs: Int,
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
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "deg"
    )

    Column(
        modifier = Modifier
            .width(330.dp)
            .background(Color(0xFF161922), RoundedCornerShape(28.dp))
            .border(3.dp, accentColor.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SONY ERICSSON", fontSize = 10.sp, letterSpacing = 3.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .background(Color(0xFF090C15), RoundedCornerShape(14.dp))
                .border(2.dp, Color(0xFF2E3646), RoundedCornerShape(14.dp))
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(skin.label, color = accentColor, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text("CLEAR AUDIO", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            Canvas(modifier = Modifier.size(110.dp)) {
                rotate(if (isPlaying) angle else 0f) {
                    drawCircle(color = accentColor.copy(alpha = 0.25f), style = Stroke(width = 16f))
                    drawCircle(color = accentColor, radius = size.minDimension / 2.3f, style = Stroke(width = 5f))
                }
                drawCircle(color = Color.White, radius = 14f)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(trackTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(trackArtist, color = accentColor, fontSize = 11.sp, maxLines = 1)
            }

            LinearProgressIndicator(
                progress = (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = accentColor,
                trackColor = Color.DarkGray
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF3A4152), Color(0xFF1B202B))))
                .border(4.dp, if (isPlaying && skin == WalkmanSkin.W850I) accentColor else Color(0xFF475569), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onPrev, modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) {
                Icon(Icons.Default.SkipPrevious, null, tint = Color.White)
            }
            IconButton(onClick = onNext, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
                Icon(Icons.Default.SkipNext, null, tint = Color.White)
            }
            Button(
                onClick = onPlayPause,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                modifier = Modifier.size(64.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
fun SmartScannerLibraryView(
    tracks: List<AudioTrack>,
    skippedCount: Int,
    currentIndex: Int,
    onRescan: () -> Unit,
    onSelectTrack: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141C2B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("QUÉT NHẠC THÔNG MINH", fontWeight = FontWeight.Black, color = Color(0xFFFF6600), fontSize = 14.sp)
                        Text("Đã tự động bỏ qua  file hệ thống & ghi âm", fontSize = 11.sp, color = Color.LightGray)
                    }
                    Button(
                        onClick = onRescan,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5500))
                    ) {
                        Text("QUÉT LẠI", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

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
                        Text(track.title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp, maxLines = 1)
                        Text(track.artist, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                    }
                    Text(":", color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun EqualizerDspTabView(
    megaBass: Float,
    onBassChange: (Float) -> Unit,
    onBandChange: (Int, Float) -> Unit
) {
    val bands = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
    val bandValues = remember { mutableStateListOf(3f, 1f, 0f, 2f, 4f) }

    Column(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("BỘ XỬ LÝ ÂM THANH SONY DSP", fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color(0xFFFF6600))

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
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
