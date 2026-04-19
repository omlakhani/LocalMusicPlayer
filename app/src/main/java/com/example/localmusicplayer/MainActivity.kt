@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.localmusicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.example.localmusicplayer.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

sealed class Screen {
    object SongList : Screen()
    object Playlists : Screen()
    data class PlaylistDetail(val playlistId: Long, val playlistName: String) : Screen()
    object Player : Screen()
}

class MainActivity : ComponentActivity() {
    var musicService: MusicService? by mutableStateOf(null)
    var isBound by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, MusicService::class.java))
        checkPermissions()

        setContent {
            MainScreen(musicService)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, 100)
        } else {
            loadMusicAndInsert()
        }
    }

    private fun loadMusicAndInsert() {
        lifecycleScope.launch(Dispatchers.IO) {
            val songs = loadSongs(this@MainActivity)
            val dao = DatabaseProvider.getDatabase(this@MainActivity).musicDao()
            dao.insertSongs(songs.map { it.toEntity() })
        }
    }

    fun loadSongs(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val path = cursor.getString(pathIdx) ?: ""
                val targetFolder = "/Music/My App/"

                if (!path.contains(targetFolder, ignoreCase = true)) continue
                if (!path.endsWith(".mp3", ignoreCase = true)) continue

                val id = cursor.getLong(idIdx)
                songs.add(Song(
                    id = id,
                    title = cursor.getString(titleIdx) ?: "Unknown",
                    artist = cursor.getString(artistIdx) ?: "Unknown",
                    path = path,
                    albumId = cursor.getLong(albumIdIdx),
                    duration = cursor.getLong(durationIdx),
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                ))
            }
        }
        return songs
    }
}

@Composable
fun MainScreen(service: MusicService?) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.SongList) }

    if (service == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A1A24)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.Cyan)
        }
    } else {
        val context = LocalContext.current
        val dao = remember { DatabaseProvider.getDatabase(context).musicDao() }
        val playbackState by service.playbackState.collectAsState()

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            bottom = if (currentScreen != Screen.Player && playbackState.currentSong != null) 80.dp else 0.dp
                        )
                ) {
                    when (val screen = currentScreen) {
                        is Screen.SongList -> SongListScreen(
                            service = service,
                            dao = dao,
                            onSongClick = { currentScreen = Screen.Player },
                            onPlaylistClick = { currentScreen = Screen.Playlists }
                        )

                        is Screen.Playlists -> PlaylistScreen(
                            dao = dao,
                            onBack = { currentScreen = Screen.SongList },
                            onPlaylistClick = { id, name ->
                                currentScreen = Screen.PlaylistDetail(id, name)
                            }
                        )

                        is Screen.PlaylistDetail -> PlaylistDetailScreen(
                            playlistId = screen.playlistId,
                            playlistName = screen.playlistName,
                            dao = dao,
                            service = service,
                            onBack = { currentScreen = Screen.Playlists },
                            onSongClick = { currentScreen = Screen.Player }
                        )

                        is Screen.Player -> PlayerScreen(
                            playbackState = playbackState,
                            onPauseToggle = { service.togglePlayPause() },
                            onNext = { service.playNext() },
                            onPrevious = { service.playPrevious() },
                            onToggleShuffle = { service.toggleShuffle() },
                            onCycleRepeat = { service.toggleRepeat() },
                            onSeek = { position -> service.seekTo(position) },
                            onBack = { currentScreen = Screen.SongList }
                        )
                    }
                }

                if (currentScreen != Screen.Player && playbackState.currentSong != null) {
                    MiniPlayer(
                        playbackState = playbackState,
                        onTogglePlayPause = { service.togglePlayPause() },
                        onNext = { service.playNext() },
                        onSeek = { position -> service.seekTo(position) },
                        onClick = { currentScreen = Screen.Player },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(dao: MusicDao, onBack: () -> Unit, onPlaylistClick: (Long, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val playlistsState = remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        playlistsState.value = dao.getAllPlaylists()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlists", color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) } },
                actions = { IconButton(onClick = { showCreateDialog = true }) { Icon(Icons.Default.Add, "Create", tint = Color.Cyan) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(Color.Black)) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(playlistsState.value) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaylistClick(playlist.id, playlist.name) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = playlist.name,
                            color = Color.White,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = { onPlaylistClick(playlist.id, playlist.name) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Open",
                                tint = Color.Cyan
                            )
                        }

                        IconButton(onClick = {
                            scope.launch {
                                dao.deletePlaylistWithSongs(playlist.id)
                                playlistsState.value = dao.getAllPlaylists()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color.Gray
                            )
                        }
                    }
                    HorizontalDivider(
                        color = Color(0xFF1E1E1E),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Playlist") },
            text = { TextField(value = name, onValueChange = { name = it }, placeholder = { Text("Name") }) },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        scope.launch {
                            dao.insertPlaylist(PlaylistEntity(name = name))
                            playlistsState.value = dao.getAllPlaylists()
                            showCreateDialog = false
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun PlaylistDetailScreen(playlistId: Long, playlistName: String, dao: MusicDao, service: MusicService, onBack: () -> Unit, onSongClick: () -> Unit) {
    val songList = remember { mutableStateListOf<SongEntity>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun reload() {
        scope.launch {
            val loaded = dao.getSongsForPlaylist(playlistId)
            songList.clear()
            songList.addAll(loaded)
        }
    }

    fun saveOrder() {
        scope.launch {
            songList.forEachIndexed { index, song ->
                dao.updateSongPosition(playlistId, song.id, index)
            }
        }
    }

    fun moveUp(index: Int) {
        if (index > 0) {
            val item = songList.removeAt(index)
            songList.add(index - 1, item)
            saveOrder()
        }
    }

    fun moveDown(index: Int) {
        if (index < songList.size - 1) {
            val item = songList.removeAt(index)
            songList.add(index + 1, item)
            saveOrder()
        }
    }

    LaunchedEffect(playlistId) {
        reload()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(playlistName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songList, key = { _, song -> song.id }) { index, song ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                service.setQueue(songList.map { it.toSong() }, index)
                                onSongClick()
                            }
                    ) {
                        Text(
                            text = song.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = song.artist,
                            color = Color.Gray,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = {
                        scope.launch {
                            dao.removeSongFromPlaylist(playlistId, song.id)
                            reload()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = Color.Cyan
                        )
                    }

                    Column {
                        IconButton(onClick = { moveUp(index) }, enabled = index > 0, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                null,
                                tint = if (index > 0) Color.White else Color.Gray
                            )
                        }
                        IconButton(onClick = { moveDown(index) }, enabled = index < songList.size - 1, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                null,
                                tint = if (index < songList.size - 1) Color.White else Color.Gray
                            )
                        }
                    }
                }
                HorizontalDivider(
                    color = Color(0xFF1E1E1E),
                    thickness = 0.5.dp
                )
            }
        }
    }
}

@Composable
fun SongListScreen(service: MusicService, dao: MusicDao, onSongClick: () -> Unit, onPlaylistClick: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    val playlistsState = remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }
    var selectedSong by remember { mutableStateOf<SongEntity?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val songs = remember(searchQuery) {
        val all = (context as MainActivity).loadSongs(context)
        if (searchQuery.isBlank()) all else all.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }
    }

    LaunchedEffect(Unit) {
        playlistsState.value = dao.getAllPlaylists()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "My Music",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = "Playlists →",
                color = Color.Cyan,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onPlaylistClick() }.padding(8.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = Color.Gray
                )

                Spacer(modifier = Modifier.width(8.dp))

                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search songs or artists...",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(songs) { index, song ->
                SongRow(song = song.toEntity(), onClick = {
                    service.setQueue(songs, index)
                    onSongClick()
                }, actionIcon = Icons.Default.Add, onActionClick = { selectedSong = song.toEntity() })
            }
        }
    }

    if (selectedSong != null) {
        AlertDialog(
            onDismissRequest = { selectedSong = null },
            title = { Text("Add to Playlist") },
            text = {
                LazyColumn {
                    items(playlistsState.value) { playlist ->
                        Text(playlist.name, modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                val current = dao.getSongsForPlaylist(playlist.id)
                                dao.insertPlaylistSongCrossRef(PlaylistSongCrossRef(playlist.id, selectedSong!!.id, current.size))
                                selectedSong = null
                            }
                        }.padding(16.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedSong = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun MiniPlayer(
    playbackState: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSong = playbackState.currentSong ?: return
    val isPlaying = playbackState.isPlaying
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentPosition = playbackState.currentPosition
    val duration = playbackState.duration

    val albumArtBitmap = remember(currentSong.path) {
        getAlbumArt(currentSong.path)
    }
    val displayImage = albumArtBitmap ?: ImageBitmap.imageResource(id = android.R.drawable.ic_menu_report_image)

    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        label = "mini_progress"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.DarkGray.copy(alpha = 0.4f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .background(Color.Cyan)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1E1E))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            dragOffset += dragAmount
                        },
                        onDragEnd = {
                            if (dragOffset < -100) {
                                onClick()
                            }
                            dragOffset = 0f
                        }
                    )
                }
                .clickable { onClick() }
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = displayImage,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = currentSong.title,
                        color = Color.White,
                        maxLines = 1,
                        fontSize = 14.sp,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = currentSong.artist,
                        color = Color.LightGray,
                        maxLines = 1,
                        fontSize = 12.sp,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onTogglePlayPause) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Cyan
                        )
                    }

                    IconButton(onClick = onNext) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(
    playbackState: PlaybackState,
    onPauseToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit
) {
    val currentSong = playbackState.currentSong ?: return
    val isPlaying = playbackState.isPlaying
    val currentPosition = playbackState.currentPosition
    val duration = playbackState.duration

    val albumArtBitmap = remember(currentSong.path) {
        getAlbumArt(currentSong.path)
    }
    val displayImage = albumArtBitmap ?: ImageBitmap.imageResource(id = android.R.drawable.ic_menu_report_image)

    val rotation by animateFloatAsState(
        targetValue = if (isPlaying) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "rotation"
    )

    val playButtonScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.2f else 1f,
        label = "playButtonScale"
    )

    val animatedProgress by animateFloatAsState(
        targetValue = currentPosition.toFloat(),
        animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing),
        label = "progress"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            bitmap = displayImage,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(50.dp),
            contentScale = ContentScale.Crop,
            alpha = 0.4f
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Image(
                bitmap = displayImage,
                contentDescription = null,
                modifier = Modifier
                    .size(260.dp)
                    .graphicsLayer(rotationZ = if (isPlaying) rotation else 0f)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = currentSong.title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Text(
                text = currentSong.artist,
                color = Color.Cyan,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Slider(
                    value = animatedProgress,
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Cyan,
                        activeTrackColor = Color.Cyan,
                        inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDuration(currentPosition), color = Color.LightGray, fontSize = 12.sp)
                    Text(formatDuration(duration), color = Color.LightGray, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = null,
                        tint = if (playbackState.isShuffleEnabled) Color.Cyan else Color.White
                    )
                }

                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }

                IconButton(
                    onClick = onPauseToggle,
                    modifier = Modifier
                        .size(64.dp)
                        .scale(playButtonScale)
                        .background(Color.Cyan, shape = CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }

                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = null,
                        tint = if (playbackState.repeatMode != RepeatMode.OFF) Color.Cyan else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun SongRow(
    song: SongEntity,
    onClick: () -> Unit,
    actionIcon: ImageVector,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ContentUris.withAppendedId(
                    "content://media/external/audio/albumart".toUri(),
                    song.albumId
                ),
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = song.artist,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = formatDuration(song.duration),
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onActionClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = "Action",
                    tint = Color.Cyan
                )
            }
        }
        HorizontalDivider(
            color = Color(0xFF1E1E1E),
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

fun getAlbumArt(path: String): ImageBitmap? {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(path)
        val art = retriever.embeddedPicture
        retriever.release()

        art?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap()
        }
    } catch (e: Exception) {
        null
    }
}

fun formatDuration(duration: Long): String {
    val minutes = duration / 1000 / 60
    val seconds = (duration / 1000 % 60)
    return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
}
