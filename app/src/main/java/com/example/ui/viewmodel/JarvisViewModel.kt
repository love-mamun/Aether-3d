package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.CoreMode
import com.example.ai.SpeechLocaleOption
import com.example.core.AgentOrchestrator
import com.example.core.AssistantUiState
import com.example.data.local.AppPlaybookEntity
import com.example.data.local.CommandLogEntity
import com.example.data.local.MemoryNoteEntity
import com.example.data.local.QuickContactEntity
import com.example.data.local.ReminderEntity
import com.example.data.local.ScreenMacroEntity
import com.example.data.local.SmartTriggerRuleEntity
import com.example.data.local.VisionTokenMode
import com.example.data.local.VoiceRoutineEntity
import com.example.data.local.WakeTriggerMode
import com.example.service.ScreenVisionService
import com.example.service.VoiceAgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val orchestrator = AgentOrchestrator.getInstance(application)
    private val vault = orchestrator.vaultRepository

    val uiState: StateFlow<AssistantUiState> = orchestrator.uiState
    val audioAmplitude: StateFlow<Float> = orchestrator.audioAmplitude
    val permissionsManager = orchestrator.permissionsManager
    val cameraLensEngine = orchestrator.cameraLensEngine

    val historyLogs: StateFlow<List<CommandLogEntity>> = vault.recentLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryNoteEntity>> = vault.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val routines: StateFlow<List<VoiceRoutineEntity>> = vault.allRoutines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val quickContacts: StateFlow<List<QuickContactEntity>> = vault.allContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reminders: StateFlow<List<ReminderEntity>> = vault.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val macros: StateFlow<List<ScreenMacroEntity>> = vault.allMacros
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playbooks: StateFlow<List<AppPlaybookEntity>> = vault.allPlaybooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val triggerRules: StateFlow<List<SmartTriggerRuleEntity>> = vault.allTriggerRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refreshPermissionsAndTelemetry() {
        orchestrator.refreshPermissionsAndTelemetry()
    }

    fun onMicTapped() {
        val currentMode = uiState.value.coreMode
        when (currentMode) {
            CoreMode.LISTENING -> orchestrator.stopListening()
            CoreMode.SPEAKING -> orchestrator.startListening(initialBanner = "Barge-in · Listening...")
            else -> orchestrator.startListening()
        }
    }

    fun submitTextCommand(text: String) {
        orchestrator.submitCommand(prompt = text, sourceMode = "FOREGROUND")
    }

    fun submitImageCommand(imageUri: Uri, prompt: String) {
        viewModelScope.launch {
            val bitmap = decodeDownscaledBitmap(imageUri)
            if (bitmap != null) {
                orchestrator.submitCommand(
                    prompt = prompt.ifBlank { "Describe this image and extract any actionable info." },
                    attachedImage = bitmap,
                    sourceMode = "FOREGROUND"
                )
            }
        }
    }

    fun selectPersona(persona: com.example.ai.AssistantPersona) = orchestrator.selectPersona(persona)

    fun cyclePersona() = orchestrator.cyclePersona()

    fun setSpeechLocale(option: SpeechLocaleOption) {
        orchestrator.updatePreferences { it.copy(speechLocale = option) }
    }

    fun toggleTts() {
        val next = !uiState.value.preferences.ttsEnabled
        if (!next) orchestrator.voiceManager.stopSpeaking()
        orchestrator.updatePreferences { it.copy(ttsEnabled = next) }
    }

    fun toggleContinuousLoop() {
        orchestrator.updatePreferences { it.copy(continuousLoopEnabled = !it.continuousLoopEnabled) }
    }

    fun toggleAutonomousMode() {
        orchestrator.updatePreferences { it.copy(autonomousModeEnabled = !it.autonomousModeEnabled) }
    }

    fun toggleSplitScreenLaunch() {
        orchestrator.updatePreferences { it.copy(splitScreenLaunchEnabled = !it.splitScreenLaunchEnabled) }
    }

    fun toggleWakeWord() {
        orchestrator.updatePreferences { it.copy(wakeWordEnabled = !it.wakeWordEnabled) }
    }

    fun updateWakeWordPhrase(phrase: String) {
        val clean = phrase.trim()
        if (clean.isNotBlank()) {
            orchestrator.updatePreferences { it.copy(customWakeWord = clean) }
        }
    }

    fun togglePauseWakeDuringMusic() {
        orchestrator.updatePreferences { it.copy(pauseWakeDuringMusic = !it.pauseWakeDuringMusic) }
    }

    fun setWakeTriggerMode(mode: WakeTriggerMode) {
        val wakeEnabled = mode != WakeTriggerMode.ASSISTANT_BUTTON_ONLY
        orchestrator.updatePreferences {
            it.copy(
                wakeTriggerMode = mode,
                wakeWordEnabled = wakeEnabled
            )
        }
    }

    fun downloadKwsPack() {
        orchestrator.downloadKwsModelPack()
    }

    fun stopDeepScreenVision() {
        orchestrator.stopDeepScreenVision()
        runCatching { ScreenVisionService.stop(getApplication()) }
    }

    fun setVisionTokenMode(mode: VisionTokenMode) {
        orchestrator.updatePreferences { it.copy(visionTokenMode = mode) }
    }

    fun startVoiceFromExternalTrigger() {
        orchestrator.prepareExternalVoiceTrigger()
        val snapshot = permissionsManager.checkSnapshot()
        if (!snapshot.micGranted) {
            refreshPermissionsAndTelemetry()
            return
        }
        val mode = uiState.value.coreMode
        if (mode != CoreMode.LISTENING) {
            orchestrator.startListening(initialBanner = "Assistant triggered · Listening...")
        }
    }

    fun toggleBargeIn() {
        orchestrator.updatePreferences { it.copy(bargeInEnabled = !it.bargeInEnabled) }
    }

    fun setVoiceEngineMode(mode: String) {
        orchestrator.updatePreferences { it.copy(voiceEngineMode = mode) }
    }

    fun setVoiceSpeed(speed: Float) {
        orchestrator.updatePreferences { it.copy(voiceSpeed = speed.coerceIn(0.8f, 1.4f)) }
    }

    fun setBargeInMode(mode: String) {
        orchestrator.updatePreferences { it.copy(bargeInMode = mode) }
    }

    fun toggleFilteredMicMode() {
        val next = !uiState.value.preferences.filteredMicEnabled
        orchestrator.updatePreferences { it.copy(filteredMicEnabled = next) }
        if (!next) {
            orchestrator.stopFilteredBackgroundCapture()
        } else if (uiState.value.backgroundServiceActive && !uiState.value.backgroundServicePaused) {
            orchestrator.startFilteredBackgroundCapture()
        }
    }

    fun downloadVoicePack(packId: String) = orchestrator.downloadVoicePack(packId)

    fun selectVoicePack(
        packId: String,
        targetPersona: com.example.ai.AssistantPersona? = null
    ) = orchestrator.selectVoicePack(packId, targetPersona)

    fun importAetherVoiceSample(uri: Uri) = orchestrator.importAetherVoiceSample(uri)

    fun previewImportedAetherSample() = orchestrator.previewImportedAetherSample()

    fun importCustomOnnxModel(uri: Uri) = orchestrator.importCustomOnnxModel(uri)

    fun deleteVoicePack(packId: String) = orchestrator.deleteVoicePack(packId)

    fun testVoiceSample(packId: String? = null) = orchestrator.testVoiceSample(packId)

    fun toggleLiveDuplexMode() = orchestrator.toggleLiveDuplexMode()

    fun toggleDailyBriefing(enabled: Boolean, hour: Int, minute: Int) =
        orchestrator.toggleDailyBriefing(enabled, hour, minute)

    fun addReminder(title: String, delayMinutes: Int, repeatDaily: Boolean = false) =
        orchestrator.addReminder(title, delayMinutes, repeatDaily)

    fun toggleReminderCompleted(id: Long, completed: Boolean) =
        orchestrator.toggleReminderCompleted(id, completed)

    fun deleteReminder(id: Long) = orchestrator.deleteReminder(id)

    fun runDailyBriefing(reminderTitleOverride: String = "") =
        orchestrator.runDailyBriefing(reminderTitleOverride)

    fun toggleMacroRecording(customName: String = "") =
        orchestrator.toggleMacroRecording(customName)

    fun replayMacro(macro: ScreenMacroEntity) = orchestrator.replayMacro(macro)

    fun deleteMacro(id: Long) = orchestrator.deleteMacro(id)

    // Notification Copilot
    fun speakNotificationDigest() = orchestrator.speakNotificationDigest()

    fun replyToNotification(clusterId: String, replyText: String) =
        orchestrator.replyToNotificationCluster(clusterId, replyText)

    fun dismissNotification(clusterId: String) =
        orchestrator.dismissNotificationCluster(clusterId)

    fun clearAllNotifications() = orchestrator.clearAllNotifications()

    // CameraX Live Vision Lens
    fun analyzeCameraLensBitmap(bitmap: Bitmap, question: String) =
        orchestrator.analyzeCameraLensFrame(bitmap, question)

    fun analyzeCameraLensUri(uri: Uri, question: String) {
        viewModelScope.launch {
            val bitmap = decodeDownscaledBitmap(uri)
            if (bitmap != null) {
                orchestrator.analyzeCameraLensFrame(bitmap, question)
            }
        }
    }

    fun clearCameraLensRequest() = orchestrator.clearCameraLensRequest()

    // App Playbooks
    fun togglePlaybookPinned(playbook: AppPlaybookEntity) {
        viewModelScope.launch { vault.togglePlaybookPinned(playbook.id, !playbook.isPinned) }
    }

    fun replayPlaybook(playbook: AppPlaybookEntity) = orchestrator.replayPlaybook(playbook)

    fun deletePlaybook(id: Long) {
        viewModelScope.launch { vault.deletePlaybook(id) }
    }

    // Smart Context Auto-Triggers
    fun toggleTriggerRule(id: Long, enabled: Boolean) {
        viewModelScope.launch { vault.setTriggerRuleEnabled(id, enabled) }
    }

    fun addTriggerRule(
        name: String,
        triggerType: String,
        conditionValue: String,
        actionMode: String,
        actionPayload: String,
        cooldownMinutes: Int = 15
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            vault.saveTriggerRule(
                SmartTriggerRuleEntity(
                    name = name.trim(),
                    triggerType = triggerType,
                    conditionValue = conditionValue.trim(),
                    actionMode = actionMode,
                    actionPayload = actionPayload.trim(),
                    cooldownMinutes = cooldownMinutes.coerceIn(1, 180),
                    isEnabled = true
                )
            )
        }
    }

    fun testTriggerRule(rule: SmartTriggerRuleEntity) = orchestrator.testTriggerRuleNow(rule)

    fun deleteTriggerRule(id: Long) {
        viewModelScope.launch { vault.deleteTriggerRule(id) }
    }

    fun toggleBiometricVaultLock() {
        val next = !uiState.value.preferences.biometricVaultLockEnabled
        orchestrator.updatePreferences { it.copy(biometricVaultLockEnabled = next) }
        if (next) {
            orchestrator.lockVault()
        } else {
            orchestrator.unlockVault()
        }
    }

    fun unlockVault() = orchestrator.unlockVault()

    fun lockVault() = orchestrator.lockVault()

    fun markOnboardingSeen() {
        orchestrator.updatePreferences { it.copy(hasCompletedOnboardingPrompt = true) }
    }

    fun saveApiSettings(
        nvidiaKey: String,
        nvidiaModel: String,
        preferPrimary: Boolean,
        geminiKey: String,
        geminiModel: String
    ) {
        orchestrator.saveApiSettings(nvidiaKey, nvidiaModel, preferPrimary, geminiKey, geminiModel)
    }

    fun saveNvidiaSettings(apiKey: String, model: String, preferPrimary: Boolean) {
        orchestrator.saveNvidiaSettings(apiKey, model, preferPrimary)
    }

    fun isNvidiaConfigured(): Boolean = orchestrator.nvidiaService.isConfigured()

    suspend fun fetchLiveNvidiaModels(overrideKey: String? = null): Result<List<String>> =
        orchestrator.fetchLiveNvidiaModels(overrideKey)

    fun toggleBackgroundService() {
        val app = getApplication<Application>()
        if (uiState.value.backgroundServiceActive) {
            VoiceAgentService.stop(app)
        } else {
            VoiceAgentService.start(app)
        }
    }

    fun abortAllTasks() = orchestrator.abortAllTasks()

    fun confirmSensitiveSteps() = orchestrator.confirmPendingSensitiveSteps()

    fun dismissSensitiveSteps() = orchestrator.dismissPendingConfirmation()

    // SQLite Vault CRUD
    fun addMemory(content: String, category: String = "FACT") {
        if (content.isBlank()) return
        viewModelScope.launch { vault.saveMemory(content.trim(), category) }
    }

    fun toggleMemoryPinned(note: MemoryNoteEntity) {
        viewModelScope.launch { vault.toggleMemoryPinned(note.id, !note.isPinned) }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch { vault.deleteMemory(id) }
    }

    fun saveRoutine(trigger: String, desc: String, prompt: String) {
        if (trigger.isBlank() || prompt.isBlank()) return
        viewModelScope.launch { vault.saveRoutine(trigger.trim(), desc.trim(), prompt.trim()) }
    }

    fun runRoutine(routine: VoiceRoutineEntity) {
        orchestrator.submitCommand(prompt = routine.triggerPhrase, sourceMode = "FOREGROUND")
    }

    fun deleteRoutine(id: Long) {
        viewModelScope.launch { vault.deleteRoutine(id) }
    }

    fun addQuickContact(alias: String, displayName: String, phone: String, email: String) {
        if (alias.isBlank()) return
        viewModelScope.launch {
            vault.saveQuickContact(
                QuickContactEntity(
                    aliasName = alias.trim(),
                    displayName = displayName.trim().ifBlank { alias.trim() },
                    phoneNumber = phone.trim(),
                    emailAddress = email.trim()
                )
            )
        }
    }

    fun importPickedContact(contactUri: Uri) {
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                var name = ""
                var phone = ""
                resolver.query(contactUri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                        val hasPhoneIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                        if (nameIdx >= 0) name = cursor.getString(nameIdx).orEmpty()
                        val contactId = if (idIdx >= 0) cursor.getString(idIdx) else null
                        val hasPhone = if (hasPhoneIdx >= 0) cursor.getInt(hasPhoneIdx) > 0 else false
                        if (hasPhone && contactId != null) {
                            resolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(contactId),
                                null
                            )?.use { pCursor ->
                                if (pCursor.moveToFirst()) {
                                    phone = pCursor.getString(0).orEmpty()
                                }
                            }
                        }
                    }
                }
                if (name.isNotBlank()) QuickContactEntity(
                    aliasName = name.substringBefore(" "),
                    displayName = name,
                    phoneNumber = phone
                ) else null
            }
            if (resolved != null) {
                vault.saveQuickContact(resolved)
            }
        }
    }

    fun deleteQuickContact(id: Long) {
        viewModelScope.launch { vault.deleteQuickContact(id) }
    }

    fun deleteLog(id: Long) {
        viewModelScope.launch { vault.deleteLog(id) }
    }

    fun clearLogs() {
        viewModelScope.launch { vault.clearLogs() }
    }

    private suspend fun decodeDownscaledBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val resolver = getApplication<Application>().contentResolver
            val raw = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@withContext null

            if (raw.width > 1024) {
                val targetH = (raw.height * (1024f / raw.width)).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(raw, 1024, targetH, true)
                if (scaled !== raw) raw.recycle()
                scaled
            } else {
                raw
            }
        } catch (_: Exception) {
            null
        }
    }
}
