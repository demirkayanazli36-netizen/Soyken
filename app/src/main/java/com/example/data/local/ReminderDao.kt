package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.ProgramReminder
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE programId LIKE 'ch_%' ORDER BY startTime ASC")
    fun getAllReminders(): Flow<List<ProgramReminder>>

    @Query("SELECT * FROM reminders WHERE programId LIKE 'ch_%' ORDER BY startTime ASC")
    suspend fun getAllRemindersSync(): List<ProgramReminder>

    @Query("SELECT EXISTS(SELECT 1 FROM reminders WHERE programId = :programId)")
    suspend fun hasReminder(programId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ProgramReminder)

    @Delete
    suspend fun deleteReminder(reminder: ProgramReminder)

    @Query("DELETE FROM reminders WHERE programId = :programId")
    suspend fun deleteReminderById(programId: String): Int

    @Query("DELETE FROM reminders WHERE startTime <= :cutoffMillis")
    suspend fun deleteExpiredReminders(cutoffMillis: Long)

    @Query("DELETE FROM reminders WHERE programId NOT LIKE 'ch_%'")
    suspend fun deleteLegacyProgramReminders()
}
