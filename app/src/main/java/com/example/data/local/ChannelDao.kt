package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Channel
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY channelNumber ASC, name ASC")
    fun getChannelsForPlaylist(playlistId: Long): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND isFavorite = 1 ORDER BY name ASC")
    fun getFavoritesForPlaylist(playlistId: Long): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE isFavorite = 1 ORDER BY name ASC")
    fun getAllFavorites(): Flow<List<Channel>>

    @Query("SELECT DISTINCT category FROM channels WHERE playlistId = :playlistId ORDER BY category ASC")
    fun getCategoriesForPlaylist(playlistId: Long): Flow<List<String>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND category = :category ORDER BY channelNumber ASC, name ASC")
    fun getChannelsByCategory(playlistId: Long, category: String): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchChannels(playlistId: Long, query: String): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun getChannelById(id: Long): Channel?

    @Query("SELECT * FROM channels WHERE streamUrl = :streamUrl LIMIT 1")
    suspend fun getChannelByStreamUrl(streamUrl: String): Channel?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId")
    suspend fun getChannelsForPlaylistSync(playlistId: Long): List<Channel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<Channel>)

    @Update
    suspend fun updateChannel(channel: Channel)

    @Query("SELECT * FROM channels")
    suspend fun getAllChannelsSync(): List<Channel>

    @Query("UPDATE channels SET streamUrl = :streamUrl WHERE id = :channelId")
    suspend fun updateChannelStreamUrl(channelId: Long, streamUrl: String)

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE id = :channelId")
    suspend fun setFavorite(channelId: Long, isFavorite: Boolean)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannelsByPlaylist(playlistId: Long)
}
