package com.example.data.local

import android.content.Context
import com.example.ai.AssistantPersona
import com.example.ai.SpeechLocaleOption
import kotlinx.coroutines.flow.Flow

data class AssistantPreferences(
    val persona: AssistantPersona = AssistantPersona.JARVIS,
    val speechLocale: SpeechLocaleOption = SpeechLocaleOption.EN_US,
    val ttsEnabled: Boolean = true,
    val continuousLoopEnabled: Boolean = false,
    val autonomousModeEnabled: Boolean = true,
    val splitScreenLaunchEnabled: Boolean = false,
    val hasCompletedOnboardingPrompt: Boolean = false,
    val nvidiaApiKey: String = "",
    val nvidiaModel: String = DEFAULT_NVIDIA_MODEL_ID,
    val preferNvidiaPrimary: Boolean = false,
    val biometricVaultLockEnabled: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val bargeInEnabled: Boolean = true,
    val customWakeWord: String = "Hey Aether",
    val voiceEngineMode: String = "AUTO", // AUTO | PIPER | SYSTEM
    val selectedEnglishVoiceId: String = "en_US-lessac-medium-int8",
    val bargeInMode: String = "ADAPTIVE", // ADAPTIVE | HEADSET_ONLY | TAP_ONLY
    val dailyBriefingEnabled: Boolean = false,
    val briefingHour: Int = 8,
    val briefingMinute: Int = 0
) {
    companion object {
        const val DEFAULT_NVIDIA_MODEL_ID = "nvidia/nemotron-3.5-bolt-e4b"
        private const val EOL_NVIDIA_MODEL_ID = "meta/llama-3.3-70b-instruct"
    }
}

class VaultRepository(context: Context) {
    private val db = AetherDatabase.getInstance(context)
    private val commandLogDao = db.commandLogDao()
    private val memoryNoteDao = db.memoryNoteDao()
    private val voiceRoutineDao = db.voiceRoutineDao()
    private val quickContactDao = db.quickContactDao()
    private val reminderDao = db.reminderDao()
    private val screenMacroDao = db.screenMacroDao()

    private val prefs = context.applicationContext.getSharedPreferences(
        "aether_3d_prefs",
        Context.MODE_PRIVATE
    )

    val isSqlCipherEncrypted: Boolean get() = AetherDatabase.isSqlCipherActive
    val encryptionStatusLabel: String get() = AetherDatabase.encryptionBadgeLabel

    val recentLogs: Flow<List<CommandLogEntity>> = commandLogDao.observeRecentLogs()
    val allMemories: Flow<List<MemoryNoteEntity>> = memoryNoteDao.observeAllMemories()
    val allRoutines: Flow<List<VoiceRoutineEntity>> = voiceRoutineDao.observeAllRoutines()
    val allContacts: Flow<List<QuickContactEntity>> = quickContactDao.observeAllContacts()
    val allReminders: Flow<List<ReminderEntity>> = reminderDao.observeAllReminders()
    val allMacros: Flow<List<ScreenMacroEntity>> = screenMacroDao.observeAllMacros()

    suspend fun insertLog(log: CommandLogEntity): Long = commandLogDao.insertLog(log)
    suspend fun deleteLog(id: Long) = commandLogDao.deleteLogById(id)
    suspend fun clearLogs() = commandLogDao.clearAllLogs()

    suspend fun getTopMemories(): List<MemoryNoteEntity> = memoryNoteDao.getTopMemoriesSnapshot()
    suspend fun saveMemory(content: String, category: String = "FACT"): Long =
        memoryNoteDao.insertMemory(MemoryNoteEntity(content = content, category = category))

    suspend fun toggleMemoryPinned(id: Long, pinned: Boolean) = memoryNoteDao.setPinned(id, pinned)
    suspend fun deleteMemory(id: Long) = memoryNoteDao.deleteMemoryById(id)

    suspend fun getRoutinesSnapshot(): List<VoiceRoutineEntity> = voiceRoutineDao.getAllRoutinesSnapshot()
    suspend fun saveRoutine(triggerPhrase: String, description: String, instructionPrompt: String): Long =
        voiceRoutineDao.insertRoutine(
            VoiceRoutineEntity(
                triggerPhrase = triggerPhrase,
                description = description,
                instructionPrompt = instructionPrompt
            )
        )

    suspend fun recordRoutineUsed(id: Long) = voiceRoutineDao.incrementUsage(id)
    suspend fun deleteRoutine(id: Long) = voiceRoutineDao.deleteRoutineById(id)

    suspend fun getContactsSnapshot(): List<QuickContactEntity> = quickContactDao.getAllContactsSnapshot()
    suspend fun saveQuickContact(contact: QuickContactEntity): Long = quickContactDao.insertContact(contact)
    suspend fun deleteQuickContact(id: Long) = quickContactDao.deleteContactById(id)

    suspend fun getPendingReminders(): List<ReminderEntity> = reminderDao.getPendingRemindersSnapshot()
    suspend fun saveReminder(reminder: ReminderEntity): Long = reminderDao.insertReminder(reminder)
    suspend fun setReminderCompleted(id: Long, completed: Boolean) = reminderDao.setCompleted(id, completed)
    suspend fun deleteReminder(id: Long) = reminderDao.deleteReminderById(id)

    suspend fun getMacrosSnapshot(): List<ScreenMacroEntity> = screenMacroDao.getAllMacrosSnapshot()
    suspend fun saveMacro(macro: ScreenMacroEntity): Long = screenMacroDao.insertMacro(macro)
    suspend fun recordMacroRun(id: Long) = screenMacroDao.recordMacroRun(id)
    suspend fun deleteMacro(id: Long) = screenMacroDao.deleteMacroById(id)

    fun loadPreferences(): AssistantPreferences {
        val personaName = prefs.getString("persona", AssistantPersona.JARVIS.name)
        val persona = AssistantPersona.entries.firstOrNull { it.name == personaName }
            ?: AssistantPersona.JARVIS

        val localeName = prefs.getString("locale", SpeechLocaleOption.EN_US.name)
        val locale = SpeechLocaleOption.entries.firstOrNull { it.name == localeName }
            ?: SpeechLocaleOption.EN_US

        val storedModel = prefs.getString("nvidia_model", AssistantPreferences.DEFAULT_NVIDIA_MODEL_ID)
            ?.trim()
            .orEmpty()
        val migratedModel = if (storedModel.isBlank() || storedModel == "meta/llama-3.3-70b-instruct") {
            AssistantPreferences.DEFAULT_NVIDIA_MODEL_ID
        } else {
            storedModel
        }

        return AssistantPreferences(
            persona = persona,
            speechLocale = locale,
            ttsEnabled = prefs.getBoolean("tts_enabled", true),
            continuousLoopEnabled = prefs.getBoolean("continuous_loop", false),
            autonomousModeEnabled = prefs.getBoolean("autonomous_mode", true),
            splitScreenLaunchEnabled = prefs.getBoolean("split_screen", false),
            hasCompletedOnboardingPrompt = prefs.getBoolean("onboarding_done", false),
            nvidiaApiKey = prefs.getString("nvidia_api_key", "") ?: "",
            nvidiaModel = migratedModel,
            preferNvidiaPrimary = prefs.getBoolean("prefer_nvidia_primary", false),
            biometricVaultLockEnabled = prefs.getBoolean("biometric_vault_lock", false),
            wakeWordEnabled = prefs.getBoolean("wake_word_enabled", false),
            bargeInEnabled = prefs.getBoolean("barge_in_enabled", true),
            customWakeWord = prefs.getString("custom_wake_word", "Hey Aether")
                ?.trim()
                ?.ifBlank { "Hey Aether" } ?: "Hey Aether",
            voiceEngineMode = prefs.getString("voice_engine_mode", "AUTO") ?: "AUTO",
            selectedEnglishVoiceId = prefs.getString("selected_en_voice_id", "en_US-lessac-medium-int8")
                ?: "en_US-lessac-medium-int8",
            bargeInMode = prefs.getString("barge_in_mode", "ADAPTIVE") ?: "ADAPTIVE",
            dailyBriefingEnabled = prefs.getBoolean("daily_briefing_enabled", false),
            briefingHour = prefs.getInt("briefing_hour", 8).coerceIn(0, 23),
            briefingMinute = prefs.getInt("briefing_minute", 0).coerceIn(0, 59)
        )
    }

    fun savePreferences(updated: AssistantPreferences) {
        prefs.edit()
            .putString("persona", updated.persona.name)
            .putString("locale", updated.speechLocale.name)
            .putBoolean("tts_enabled", updated.ttsEnabled)
            .putBoolean("continuous_loop", updated.continuousLoopEnabled)
            .putBoolean("autonomous_mode", updated.autonomousModeEnabled)
            .putBoolean("split_screen", updated.splitScreenLaunchEnabled)
            .putBoolean("onboarding_done", updated.hasCompletedOnboardingPrompt)
            .putString("nvidia_api_key", updated.nvidiaApiKey.trim())
            .putString(
                "nvidia_model",
                updated.nvidiaModel.trim().ifBlank { AssistantPreferences.DEFAULT_NVIDIA_MODEL_ID }
            )
            .putBoolean("prefer_nvidia_primary", updated.preferNvidiaPrimary)
            .putBoolean("biometric_vault_lock", updated.biometricVaultLockEnabled)
            .putBoolean("wake_word_enabled", updated.wakeWordEnabled)
            .putBoolean("barge_in_enabled", updated.bargeInEnabled)
            .putString("custom_wake_word", updated.customWakeWord.trim().ifBlank { "Hey Aether" })
            .putString("voice_engine_mode", updated.voiceEngineMode)
            .putString("selected_en_voice_id", updated.selectedEnglishVoiceId)
            .putString("barge_in_mode", updated.bargeInMode)
            .putBoolean("daily_briefing_enabled", updated.dailyBriefingEnabled)
            .putInt("briefing_hour", updated.briefingHour.coerceIn(0, 23))
            .putInt("briefing_minute", updated.briefingMinute.coerceIn(0, 59))
            .apply()
    }
}
