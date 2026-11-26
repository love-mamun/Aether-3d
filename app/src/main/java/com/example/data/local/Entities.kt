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

@Entity(tableName = "app_playbooks")
data class AppPlaybookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appPackage: String,
    val appLabel: String,
    val intentPattern: String,
    val variableKeysJson: String = "[]",
    val stepsJson: String,
    val successCount: Int = 1,
    val failureCount: Int = 0,
    val avgDurationMs: Long = 0L,
    val isPinned: Boolean = false,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "smart_trigger_rules")
data class SmartTriggerRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerType: String, // BLUETOOTH_CONNECTED | WIFI_CONNECTED | BATTERY_LOW | CHARGER_CONNECTED
    val conditionValue: String = "",
    val actionMode: String = "SPEAK_NOTIFICATION_DIGEST", // SPEAK_NOTIFICATION_DIGEST | RUN_PROMPT_COMMAND
    val actionPayload: String = "",
    val cooldownMinutes: Int = 15,
    val isEnabled: Boolean = false,
    val lastTriggeredAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)
