package com.example.localmusicplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val albumId: Long,
    val duration: Long
)
