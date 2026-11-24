package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandLogDao {
    @Query("SELECT * FROM command_logs ORDER BY timestamp DESC LIMIT 100")
    fun observeRecentLogs(): Flow<List<CommandLogEntity>>

    @Query("SELECT * FROM command_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogsSnapshot(limit: Int = 20): List<CommandLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: CommandLogEntity): Long

    @Query("DELETE FROM command_logs WHERE id = :id")
    suspend fun deleteLogById(id: Long)

    @Query("DELETE FROM command_logs")
    suspend fun clearAllLogs()
}

@Dao
interface MemoryNoteDao {
    @Query("SELECT * FROM memory_notes ORDER BY isPinned DESC, createdAt DESC")
    fun observeAllMemories(): Flow<List<MemoryNoteEntity>>

    @Query("SELECT * FROM memory_notes ORDER BY isPinned DESC, createdAt DESC LIMIT 40")
    suspend fun getTopMemoriesSnapshot(): List<MemoryNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(note: MemoryNoteEntity): Long

    @Query("UPDATE memory_notes SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("DELETE FROM memory_notes WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)
}

@Dao
interface VoiceRoutineDao {
    @Query("SELECT * FROM voice_routines ORDER BY usageCount DESC, createdAt DESC")
    fun observeAllRoutines(): Flow<List<VoiceRoutineEntity>>

    @Query("SELECT * FROM voice_routines")
    suspend fun getAllRoutinesSnapshot(): List<VoiceRoutineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: VoiceRoutineEntity): Long

    @Query("UPDATE voice_routines SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun incrementUsage(id: Long)

    @Query("DELETE FROM voice_routines WHERE id = :id")
    suspend fun deleteRoutineById(id: Long)
}

@Dao
interface QuickContactDao {
    @Query("SELECT * FROM quick_contacts ORDER BY aliasName ASC")
    fun observeAllContacts(): Flow<List<QuickContactEntity>>

    @Query("SELECT * FROM quick_contacts")
    suspend fun getAllContactsSnapshot(): List<QuickContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: QuickContactEntity): Long

    @Query("DELETE FROM quick_contacts WHERE id = :id")
    suspend fun deleteContactById(id: Long)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY isCompleted ASC, triggerAtMillis ASC")
    fun observeAllReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isCompleted = 0 ORDER BY triggerAtMillis ASC")
    suspend fun getPendingRemindersSnapshot(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity): Long

    @Query("UPDATE reminders SET isCompleted = :completed WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminderById(id: Long)
}

@Dao
interface ScreenMacroDao {
    @Query("SELECT * FROM screen_macros ORDER BY runCount DESC, createdAt DESC")
    fun observeAllMacros(): Flow<List<ScreenMacroEntity>>

    @Query("SELECT * FROM screen_macros ORDER BY createdAt DESC")
    suspend fun getAllMacrosSnapshot(): List<ScreenMacroEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMacro(macro: ScreenMacroEntity): Long

    @Query("UPDATE screen_macros SET runCount = runCount + 1, lastRunAt = :now WHERE id = :id")
    suspend fun recordMacroRun(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM screen_macros WHERE id = :id")
    suspend fun deleteMacroById(id: Long)
}
