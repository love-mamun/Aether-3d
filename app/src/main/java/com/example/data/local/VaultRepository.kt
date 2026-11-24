package com.example.data.local

import android.content.Context
import com.example.ai.AssistantPersona
import com.example.ai.SpeechLocaleOption
import kotlinx.coroutines.flow.Flow

enum class WakeTriggerMode(
    val title: String,
    val description: String
) {
    HYBRID_KWS_AND_BUTTON(
        title = "On-Device KWS + Button",
        description = "Silence-gated local wake engine (0 loops, pauses in music) + Assistant button"
    ),
    ASSISTANT_BUTTON_ONLY(
        title = "Button / OS Assistant Only",
        description = "Zero idle microphone access — trigger via Orb, Quick Tile, or Digital Assistant"
    ),
    CONTINUOUS_HANDSFREE(
        title = "Hands-Free Continuous",
        description = "Continuous passive speech listener (pauses automatically during music)"
    );

    companion object {
        fun fromStoredName(raw: String?): WakeTriggerMode {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: HYBRID_KWS_AND_BUTTON
        }
    }
}

enum class VisionTokenMode(
    val title: String,
    val description: String
) {
    OFFLINE_FIRST(
        title = "Offline Vision First (<50 MB)",
        description = "ML Kit Objects + Labels + OCR + UI Tree understand screen offline (~18 MB) & skip cloud pixels"
    ),
    HYBRID_COMPACT(
        title = "Hybrid Balanced",
        description = "Always compacts screen UI text & trims prompts; attaches <=512px crop when visually needed"
    ),
    CLOUD_VISION(
        title = "Full Cloud Vision",
        description = "Always attaches cropped screenshot pixels alongside offline ML Kit labels"
    );

    companion object {
        fun fromStoredName(raw: String?): VisionTokenMode {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: OFFLINE_FIRST
        }
    }
}

data class AssistantPreferences(
    val persona: AssistantPersona = AssistantPersona.AETHER,
    val speechLocale: SpeechLocaleOption = SpeechLocaleOption.EN_US,
    val ttsEnabled: Boolean = true,
    val continuousLoopEnabled: Boolean = false,
    val autonomousModeEnabled: Boolean = true,
    val splitScreenLaunchEnabled: Boolean = false,
    val hasCompletedOnboardingPrompt: Boolean = false,
    val nvidiaApiKey: String = "",
    val nvidiaModel: String = DEFAULT_NVIDIA_MODEL_ID,
    val preferNvidiaPrimary: Boolean = true,
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL_ID,
    val biometricVaultLockEnabled: Boolean = false,
    val wakeWordEnabled: Boolean = true,
    val wakeTriggerMode: WakeTriggerMode = WakeTriggerMode.HYBRID_KWS_AND_BUTTON,
    val kwsThreshold: Float = 0.25f,
    val bargeInEnabled: Boolean = true,
    val customWakeWord: String = "Hey Aether",
    val pauseWakeDuringMusic: Boolean = true,
    val voiceEngineMode: String = "AUTO", // AUTO | PIPER | SYSTEM
    val aetherVoiceId: String = "en_US-ryan-medium-int8",
    val ariaVoiceId: String = "en_US-amy-low-int8",
    val selectedEnglishVoiceId: String = "en_US-amy-low-int8",
    val selectedBanglaVoiceId: String = "bn_BD-mimic3-multi_low",
    val voiceSpeed: Float = 1.08f,
    val customAetherWavPath: String = "",
    val customAetherPitchFactor: Float = 1.0f,
    val customAetherSampleInfo: String = "",
    val bargeInMode: String = "ADAPTIVE", // ADAPTIVE | HEADSET_ONLY | TAP_ONLY
    val dailyBriefingEnabled: Boolean = false,
    val briefingHour: Int = 8,
    val briefingMinute: Int = 0,
    val filteredMicEnabled: Boolean = true,
    val visionTokenMode: VisionTokenMode = VisionTokenMode.OFFLINE_FIRST,
    val estimatedTokensSavedTotal: Long = 0L
) {
    fun voiceIdForPersona(target: AssistantPersona = persona): String {
        return when (target) {
            AssistantPersona.AETHER -> aetherVoiceId
            AssistantPersona.ARIA -> ariaVoiceId
        }
    }

    companion object {
        const val DEFAULT_NVIDIA_MODEL_ID = "nvidia/nemotron-3.5-bolt-e4b"
        const val DEFAULT_GEMINI_MODEL_ID = "gemini-2.5-flash"
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
    suspend fun getRecentLogsSnapshot(limit: Int = 20): List<CommandLogEntity> = commandLogDao.getRecentLogsSnapshot(limit)
    suspend fun deleteLog(id: Long) = commandLogDao.deleteLogById(id)
    suspend fun clearLogs() = commandLogDao.clearAllLogs()

    suspend fun getTopMemories(): List<MemoryNoteEntity> = memoryNoteDao.getTopMemoriesSnapshot()
    suspend fun saveMemory(content: String, category: String = "FACT"): Long {
        val clean = content.trim()
        if (clean.isBlank()) return 0L
        val existing = memoryNoteDao.getTopMemoriesSnapshot().firstOrNull {
            it.content.trim().equals(clean, ignoreCase = true)
        }
        if (existing != null) return existing.id
        return memoryNoteDao.insertMemory(MemoryNoteEntity(content = clean, category = category))
    }

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
        val personaName = prefs.getString("persona", AssistantPersona.AETHER.name)
        val persona = AssistantPersona.fromStoredName(personaName)

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

        val legacyEnVoice = prefs.getString("selected_en_voice_id", "en_US-amy-low-int8")
            ?: "en_US-amy-low-int8"
        val aetherVoice = prefs.getString("aether_voice_id", null)
            ?: if (legacyEnVoice == "en_US-ryan-medium-int8" || legacyEnVoice == "custom_aether_onnx") {
                legacyEnVoice
            } else {
                "en_US-ryan-medium-int8"
            }
        val ariaVoice = prefs.getString("aria_voice_id", null)
            ?: if (legacyEnVoice == "en_US-lessac-medium-int8" || legacyEnVoice == "en_US-amy-low-int8") {
                legacyEnVoice
            } else {
                "en_US-amy-low-int8"
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
            preferNvidiaPrimary = prefs.getBoolean("prefer_nvidia_primary", true),
            geminiApiKey = prefs.getString("gemini_api_key", "") ?: "",
            geminiModel = prefs.getString("gemini_model", AssistantPreferences.DEFAULT_GEMINI_MODEL_ID)
                ?.trim()
                ?.ifBlank { AssistantPreferences.DEFAULT_GEMINI_MODEL_ID }
                ?: AssistantPreferences.DEFAULT_GEMINI_MODEL_ID,
            biometricVaultLockEnabled = prefs.getBoolean("biometric_vault_lock", false),
            wakeWordEnabled = prefs.getBoolean("wake_word_enabled", true),
            wakeTriggerMode = WakeTriggerMode.fromStoredName(
                prefs.getString("wake_trigger_mode", WakeTriggerMode.HYBRID_KWS_AND_BUTTON.name)
            ),
            kwsThreshold = prefs.getFloat("kws_threshold", 0.25f).coerceIn(0.10f, 0.85f),
            bargeInEnabled = prefs.getBoolean("barge_in_enabled", true),
            customWakeWord = prefs.getString("custom_wake_word", "Hey Aether")
                ?.trim()
                ?.ifBlank { "Hey Aether" } ?: "Hey Aether",
            pauseWakeDuringMusic = prefs.getBoolean("pause_wake_during_music", true),
            voiceEngineMode = prefs.getString("voice_engine_mode", "AUTO") ?: "AUTO",
            aetherVoiceId = aetherVoice,
            ariaVoiceId = ariaVoice,
            selectedEnglishVoiceId = legacyEnVoice,
            selectedBanglaVoiceId = prefs.getString("selected_bn_voice_id", "bn_BD-mimic3-multi_low")
                ?: "bn_BD-mimic3-multi_low",
            voiceSpeed = prefs.getFloat("voice_speed", 1.08f).coerceIn(0.75f, 1.45f),
            customAetherWavPath = prefs.getString("custom_aether_wav_path", "") ?: "",
            customAetherPitchFactor = prefs.getFloat("custom_aether_pitch_factor", 1.0f).coerceIn(0.78f, 1.25f),
            customAetherSampleInfo = prefs.getString("custom_aether_sample_info", "") ?: "",
            bargeInMode = prefs.getString("barge_in_mode", "ADAPTIVE") ?: "ADAPTIVE",
            dailyBriefingEnabled = prefs.getBoolean("daily_briefing_enabled", false),
            briefingHour = prefs.getInt("briefing_hour", 8).coerceIn(0, 23),
            briefingMinute = prefs.getInt("briefing_minute", 0).coerceIn(0, 59),
            filteredMicEnabled = prefs.getBoolean("filtered_mic_enabled", true),
            visionTokenMode = VisionTokenMode.fromStoredName(
                prefs.getString("vision_token_mode", VisionTokenMode.OFFLINE_FIRST.name)
            ),
            estimatedTokensSavedTotal = prefs.getLong("estimated_tokens_saved_total", 0L).coerceAtLeast(0L)
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
            .putString("gemini_api_key", updated.geminiApiKey.trim())
            .putString(
                "gemini_model",
                updated.geminiModel.trim().ifBlank { AssistantPreferences.DEFAULT_GEMINI_MODEL_ID }
            )
            .putBoolean("biometric_vault_lock", updated.biometricVaultLockEnabled)
            .putBoolean("wake_word_enabled", updated.wakeWordEnabled)
            .putString("wake_trigger_mode", updated.wakeTriggerMode.name)
            .putFloat("kws_threshold", updated.kwsThreshold.coerceIn(0.10f, 0.85f))
            .putBoolean("barge_in_enabled", updated.bargeInEnabled)
            .putString("custom_wake_word", updated.customWakeWord.trim().ifBlank { "Hey Aether" })
            .putBoolean("pause_wake_during_music", updated.pauseWakeDuringMusic)
            .putString("voice_engine_mode", updated.voiceEngineMode)
            .putString("aether_voice_id", updated.aetherVoiceId)
            .putString("aria_voice_id", updated.ariaVoiceId)
            .putString("selected_en_voice_id", updated.voiceIdForPersona())
            .putString("selected_bn_voice_id", updated.selectedBanglaVoiceId)
            .putFloat("voice_speed", updated.voiceSpeed.coerceIn(0.75f, 1.45f))
            .putString("custom_aether_wav_path", updated.customAetherWavPath)
            .putFloat("custom_aether_pitch_factor", updated.customAetherPitchFactor.coerceIn(0.78f, 1.25f))
            .putString("custom_aether_sample_info", updated.customAetherSampleInfo)
            .putString("barge_in_mode", updated.bargeInMode)
            .putBoolean("daily_briefing_enabled", updated.dailyBriefingEnabled)
            .putInt("briefing_hour", updated.briefingHour.coerceIn(0, 23))
            .putInt("briefing_minute", updated.briefingMinute.coerceIn(0, 59))
            .putBoolean("filtered_mic_enabled", updated.filteredMicEnabled)
            .putString("vision_token_mode", updated.visionTokenMode.name)
            .putLong("estimated_tokens_saved_total", updated.estimatedTokensSavedTotal.coerceAtLeast(0L))
            .apply()
    }
}
