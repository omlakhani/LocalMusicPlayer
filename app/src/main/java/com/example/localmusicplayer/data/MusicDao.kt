package com.example.localmusicplayer.data

import androidx.room.*

@Dao
interface MusicDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("SELECT * FROM songs")
    suspend fun getAllSongs(): List<SongEntity>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists")
    suspend fun getAllPlaylists(): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongCrossRef(crossRef: PlaylistSongCrossRef)

    @Transaction
    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN PlaylistSongCrossRef
        ON songs.id = PlaylistSongCrossRef.songId
        WHERE PlaylistSongCrossRef.playlistId = :playlistId
        ORDER BY PlaylistSongCrossRef.position ASC
    """)
    suspend fun getSongsForPlaylist(playlistId: Long): List<SongEntity>

    @Query("""
        UPDATE PlaylistSongCrossRef
        SET position = :position
        WHERE playlistId = :playlistId AND songId = :songId
    """)
    suspend fun updateSongPosition(
        playlistId: Long,
        songId: Long,
        position: Int
    )

    @Query("""
        DELETE FROM PlaylistSongCrossRef
        WHERE playlistId = :playlistId AND songId = :songId
    """)
    suspend fun removeSongFromPlaylist(
        playlistId: Long,
        songId: Long
    )
    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM PlaylistSongCrossRef WHERE playlistId = :playlistId")
    suspend fun deletePlaylistSongs(playlistId: Long)

    @Transaction
    suspend fun deletePlaylistWithSongs(playlistId: Long) {
        deletePlaylistSongs(playlistId)
        deletePlaylist(playlistId)
    }
}
