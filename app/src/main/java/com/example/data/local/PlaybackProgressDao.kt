package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.PlaybackProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress ORDER BY updatedAt DESC")
    fun getAllProgress(): Flow<List<PlaybackProgress>>

    @Query("SELECT * FROM playback_progress WHERE streamUrl = :streamUrl LIMIT 1")
    suspend fun getProgressByStreamUrl(streamUrl: String): PlaybackProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: PlaybackProgress)

    @Query("DELETE FROM playback_progress WHERE streamUrl = :streamUrl")
    suspend fun deleteProgress(streamUrl: String)

    @Query("DELETE FROM playback_progress WHERE durationMs <= 0 OR isLive = 1")
    suspend fun deleteInvalidLiveOrZeroDurationProgress()
}
