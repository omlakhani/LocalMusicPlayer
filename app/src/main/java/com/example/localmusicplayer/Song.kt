package com.example.localmusicplayer

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

import com.example.localmusicplayer.data.SongEntity

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val albumId: Long,
    val duration: Long,
    val uri: Uri
) {
    val albumArtUri: Uri
        get() = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        )
}

fun Song.toEntity(): SongEntity {
    return SongEntity(
        id = this.id,
        title = this.title,
        artist = this.artist,
        path = this.path,
        albumId = this.albumId,
        duration = this.duration
    )
}

fun SongEntity.toSong(): Song {
    return Song(
        id = this.id,
        title = this.title,
        artist = this.artist,
        path = this.path,
        albumId = this.albumId,
        duration = this.duration,
        uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            this.id
        )
    )
}
