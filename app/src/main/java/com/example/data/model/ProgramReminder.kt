package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class ProgramReminder(
    @PrimaryKey
    val programId: String,
    val channelId: Long,
    val channelName: String,
    val programTitle: String,
    val startTime: Long
)
