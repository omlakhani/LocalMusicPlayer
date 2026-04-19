package com.example.localmusicplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.example.localmusicplayer.data.DatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RepeatMode {
    OFF, ALL, ONE
}

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val currentIndex: Int = -1,
    val queue: List<Song> = emptyList(),
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isShuffleEnabled: Boolean = false
)

class MusicService : Service() {

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var mediaSessionCompat: MediaSessionCompat

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // ACTIVE QUEUE STATE (User requested variables)
    var isShuffle = false
    var repeatMode = 0 // 0 = off, 1 = repeat all, 2 = repeat one
    private var currentIndex: Int = -1
    private var currentQueue: List<Song> = emptyList()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = MusicBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY_PAUSE" -> togglePlayPause()
            "NEXT" -> playNext()
            "PREV" -> playPrevious()
            "SHUFFLE" -> toggleShuffle()
            "REPEAT" -> toggleRepeat()
        }
        return START_STICKY
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "music_channel",
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _playbackState.update { it.copy(isPlaying = playing) }
                updateNotification()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    playNext()
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
        mediaSessionCompat = MediaSessionCompat(this, "LocalMusicPlayer").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { togglePlayPause() }
                override fun onPause() { togglePlayPause() }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrevious() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
            })
        }

        restoreLastSong()
        startPositionUpdater()
    }

    private fun startPositionUpdater() {
        serviceScope.launch {
            while (true) {
                if (player.isPlaying) {
                    _playbackState.update { it.copy(
                        currentPosition = player.currentPosition,
                        duration = player.duration.coerceAtLeast(0L)
                    ) }
                }
                delay(500)
            }
        }
    }

    private fun getAction(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        val song = _playbackState.value.currentSong ?: return
        
        val notification = NotificationCompat.Builder(this, "music_channel")
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .addAction(if (isShuffle) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle, "Shuffle", getAction("SHUFFLE"))
            .addAction(R.drawable.ic_prev, "Previous", getAction("PREV"))
            .addAction(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, "Play", getAction("PLAY_PAUSE"))
            .addAction(R.drawable.ic_next, "Next", getAction("NEXT"))
            .addAction(when (repeatMode) { 
                1 -> R.drawable.ic_repeat_all
                2 -> R.drawable.ic_repeat_one
                else -> R.drawable.ic_repeat_off 
            }, "Repeat", getAction("REPEAT"))
            .setStyle(MediaNotificationCompat.MediaStyle()
                .setMediaSession(mediaSessionCompat.sessionToken)
                .setShowActionsInCompactView(1, 2, 3))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setOngoing(player.isPlaying)
            .build()

        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (player.isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    player.currentPosition,
                    1f
                )
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .build()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
    }

    fun setQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        currentQueue = songs
        currentIndex = startIndex
        play(currentIndex)
    }

    private fun play(index: Int) {
        if (currentQueue.isEmpty() || index !in currentQueue.indices) return
        currentIndex = index
        val song = currentQueue[currentIndex]

        val mediaItem = MediaItem.Builder()
            .setUri(song.uri)
            .setMediaId(song.id.toString())
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        mediaSessionCompat.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
                .build()
        )

        _playbackState.update { it.copy(
            queue = currentQueue,
            currentIndex = currentIndex,
            currentSong = song,
            isPlaying = true,
            isShuffleEnabled = isShuffle,
            repeatMode = when(repeatMode) { 1 -> RepeatMode.ALL; 2 -> RepeatMode.ONE; else -> RepeatMode.OFF }
        ) }
        updateNotification()

        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_song_id", song.id).apply()
    }

    fun playNext() {
        if (currentQueue.isEmpty()) return

        if (repeatMode == 2) {
            play(currentIndex)
            return
        }

        currentIndex = if (isShuffle) {
            (currentQueue.indices).random()
        } else {
            if (currentIndex + 1 >= currentQueue.size) {
                if (repeatMode == 1) 0 else {
                    stopPlayback()
                    return
                }
            } else {
                currentIndex + 1
            }
        }

        play(currentIndex)
    }

    fun playPrevious() {
        if (currentQueue.isEmpty()) return

        currentIndex = if (isShuffle) {
            (currentQueue.indices).random()
        } else {
            if (currentIndex - 1 < 0) {
                if (repeatMode == 1) currentQueue.lastIndex else 0
            } else {
                currentIndex - 1
            }
        }

        play(currentIndex)
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun toggleShuffle() {
        isShuffle = !isShuffle
        _playbackState.update { it.copy(isShuffleEnabled = isShuffle) }
        updateNotification()
    }

    fun toggleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        _playbackState.update { it.copy(repeatMode = when(repeatMode) { 1 -> RepeatMode.ALL; 2 -> RepeatMode.ONE; else -> RepeatMode.OFF }) }
        updateNotification()
    }

    private fun stopPlayback() {
        player.pause()
        player.seekTo(0, 0)
        _playbackState.update { it.copy(isPlaying = false, currentPosition = 0) }
        updateNotification()
    }

    private fun restoreLastSong() {
        val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        val savedId = prefs.getLong("last_song_id", -1)
        if (savedId != -1L) {
            serviceScope.launch {
                try {
                    val db = DatabaseProvider.getDatabase(this@MusicService)
                    val songs = db.musicDao().getAllSongs().map { it.toSong() }
                    val index = songs.indexOfFirst { it.id == savedId }
                    if (index != -1) {
                        withContext(Dispatchers.Main) {
                            currentQueue = songs
                            currentIndex = index
                            val song = currentQueue[currentIndex]
                            
                            val mediaItem = MediaItem.Builder()
                                .setUri(song.uri)
                                .setMediaId(song.id.toString())
                                .build()
                            player.setMediaItem(mediaItem)
                            player.prepare()
                            
                            _playbackState.update { it.copy(
                                queue = currentQueue,
                                currentIndex = currentIndex,
                                currentSong = song
                            ) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MusicService", "Error restoring song", e)
                }
            }
        }
    }

    fun seekTo(position: Long) { try { player.seekTo(position) } catch(e: Exception) {} }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        try {
            mediaSession.release()
            player.release()
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.e("MusicService", "Error in onDestroy", e)
        }
        super.onDestroy()
    }
}
