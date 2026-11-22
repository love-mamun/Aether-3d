package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "command_logs")
data class CommandLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userPrompt: String,
    val assistantReply: String,
    val personaUsed: String,
    val sourceMode: String,
    val hadScreenVision: Boolean,
    val tasksSummary: String,
    val executionStatus: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_notes")
data class MemoryNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val category: String = "FACT",
    val isPinned: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "voice_routines")
data class VoiceRoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerPhrase: String,
    val description: String,
    val instructionPrompt: String,
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "quick_contacts")
data class QuickContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val aliasName: String,
    val displayName: String,
    val phoneNumber: String = "",
    val emailAddress: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val triggerAtMillis: Long,
    val repeatDaily: Boolean = false,
    val includeInBriefing: Boolean = true,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "screen_macros")
data class ScreenMacroEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val voiceTrigger: String,
    val stepsJson: String,
    val stepCount: Int = 0,
    val runCount: Int = 0,
    val lastRunAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)
