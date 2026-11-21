package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_progress")
data class PlaybackProgress(
    @PrimaryKey
    val streamUrl: String,
    val channelId: Long,
    val channelName: String,
    val positionMs: Long,
    val durationMs: Long,
    val isLive: Boolean,
    val updatedAt: Long = System.currentTimeMillis()
)
