package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.accessibility.AetherAccessibilityService
import com.example.ai.ActionType
import com.example.ai.ActiveTaskItem
import com.example.ai.CoreMode
import com.example.ai.GeminiCommandService
import com.example.ai.NvidiaFallbackService
import com.example.ai.PlannedStep
import com.example.ai.TaskStatus
import com.example.automation.ScreenMacroManager
import com.example.briefing.BriefingScheduler
import com.example.data.local.AssistantPreferences
import com.example.data.local.CommandLogEntity
import com.example.data.local.ReminderEntity
import com.example.data.local.ScreenMacroEntity
import com.example.data.local.VaultRepository
import com.example.system.AndroidActionRouter
import com.example.system.CommunicationAudioRouter
import com.example.system.DeviceTelemetry
import com.example.system.DeviceTelemetryProvider
import com.example.system.PermissionSnapshot
import com.example.system.PermissionsManager
import com.example.system.VoiceAndTtsManager
import com.example.tools.FreeWebToolsRepository
import com.example.vision.ScreenVisionProcessor
import com.example.voice.FullDuplexLiveManager
import com.example.voice.PiperNeuralVoiceManager
import com.example.voice.VoicePackUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AgentOrchestrator"

data class AssistantUiState(
    val coreMode: CoreMode = CoreMode.IDLE,
    val preferences: AssistantPreferences = AssistantPreferences(),
    val permissionSnapshot: PermissionSnapshot = PermissionSnapshot(),
    val telemetry: DeviceTelemetry = DeviceTelemetry(),
    val partialTranscript: String = "",
    val lastUserPrompt: String = "",
    val latestReply: String = "Aether 3D core online. Tap the sphere or type a command below.",
    val streamingReply: String = "",
    val activeAiModelLabel: String = "",
    val visionInsightLabel: String = "",
    val activeTasks: List<ActiveTaskItem> = emptyList(),
    val pendingConfirmationSteps: List<PlannedStep> = emptyList(),
    val pendingConfirmationReply: String = "",
    val torchEnabled: Boolean = false,
    val backgroundServiceActive: Boolean = false,
    val backgroundServicePaused: Boolean = false,
    val isNvidiaConfigured: Boolean = false,
    val isSqlCipherEncrypted: Boolean = false,
    val encryptionBadgeLabel: String = "Local SQLite Vault",
    val isVaultUnlocked: Boolean = true,
    val isWakeWordArmed: Boolean = false,
    val lastBargeInTimestamp: Long = 0L,
    val isLiveDuplexActive: Boolean = false,
    val liveDuplexLabel: String = "",
    val localDuplexLoopActive: Boolean = false,
    val isMacroRecording: Boolean = false,
    val macroStepCount: Int = 0,
    val voicePacks: List<VoicePackUiState> = emptyList(),
    val echoGuardActive: Boolean = false,
    val errorMessage: String? = null
)

class AgentOrchestrator private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val vaultRepository = VaultRepository(appContext)
    val permissionsManager = PermissionsManager(appContext)
    val telemetryProvider = DeviceTelemetryProvider(appContext)
    private val webTools = FreeWebToolsRepository()
    private val visionProcessor = ScreenVisionProcessor()
    private val dagExecutor = TaskDagExecutor()

    private val initialPrefs = vaultRepository.loadPreferences()
    private val _uiState = MutableStateFlow(
        AssistantUiState(
            preferences = initialPrefs,
            isSqlCipherEncrypted = vaultRepository.isSqlCipherEncrypted,
            encryptionBadgeLabel = vaultRepository.encryptionStatusLabel,
            isVaultUnlocked = !initialPrefs.biometricVaultLockEnabled
        )
    )
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    val nvidiaService = NvidiaFallbackService(
        apiKeyProvider = { _uiState.value.preferences.nvidiaApiKey },
        modelProvider = { _uiState.value.preferences.nvidiaModel },
        onModelResolved = { resolvedModel ->
            scope.launch {
                updatePreferences { it.copy(nvidiaModel = resolvedModel) }
            }
        }
    )
    private val geminiService = GeminiCommandService(nvidiaService)

    val commAudioRouter = CommunicationAudioRouter(appContext)
    val piperManager = PiperNeuralVoiceManager(appContext)

    private val actionRouter = AndroidActionRouter(
        context = appContext,
        telemetryProvider = telemetryProvider,
        vaultRepository = vaultRepository,
        webTools = webTools
    )

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private var activeExecutionJob: Job? = null

    val fullDuplexManager = FullDuplexLiveManager(
        commRouter = commAudioRouter,
        actionRouter = actionRouter,
        splitScreenProvider = { _uiState.value.preferences.splitScreenLaunchEnabled },
        onTranscriptUpdate = { userTx, modelTx ->
            _uiState.update { state ->
                state.copy(
                    partialTranscript = userTx.ifBlank { state.partialTranscript },
                    streamingReply = modelTx.ifBlank { state.streamingReply },
                    latestReply = modelTx.ifBlank { state.latestReply }
                )
            }
        },
        onStateChanged = { active, label ->
            _uiState.update { state ->
                state.copy(
                    isLiveDuplexActive = active,
                    liveDuplexLabel = label,
                    coreMode = if (active) CoreMode.SPEAKING else if (state.localDuplexLoopActive) state.coreMode else CoreMode.IDLE
                )
            }
        },
        onFallbackToLocalDuplex = { reason ->
            scope.launch {
                Log.i(TAG, "Switching to Echo-Guarded Local Duplex: $reason")
                _uiState.update {
                    it.copy(
                        isLiveDuplexActive = true,
                        localDuplexLoopActive = true,
                        liveDuplexLabel = "Echo-Guarded Duplex",
                        errorMessage = null
                    )
                }
                startListening(initialBanner = "Live Duplex active · Listening...")
            }
        }
    )

    val voiceManager: VoiceAndTtsManager = VoiceAndTtsManager(
        context = appContext,
        commRouter = commAudioRouter,
        piperManager = piperManager,
        onAmplitudeChanged = { amp -> _audioAmplitude.value = amp },
        onPartialSpeech = { partial ->
            _uiState.update { it.copy(partialTranscript = partial) }
        },
        onFinalSpeech = { text ->
            _uiState.update { it.copy(partialTranscript = text, isWakeWordArmed = false) }
            submitCommand(
                prompt = text,
                sourceMode = if (_uiState.value.backgroundServiceActive) "BACKGROUND" else "FOREGROUND"
            )
        },
        onSpeechError = { errMsg ->
            _uiState.update {
                it.copy(
                    coreMode = CoreMode.ERROR,
                    errorMessage = "$errMsg You can also type commands in the bar below.",
                    partialTranscript = "",
                    isWakeWordArmed = false
                )
            }
            scope.launch {
                delay(4_000L)
                _uiState.update {
                    if (it.coreMode == CoreMode.ERROR) it.copy(coreMode = CoreMode.IDLE) else it
                }
                maybeArmWakeWord()
            }
        },
        onSpeakingStateChanged = { speaking ->
            _uiState.update { state ->
                if (speaking) {
                    state.copy(coreMode = CoreMode.SPEAKING, isWakeWordArmed = false)
                } else if (state.coreMode == CoreMode.SPEAKING) {
                    state.copy(coreMode = CoreMode.IDLE)
                } else {
                    state
                }
            }
        },
        onSpeakingFinished = {
            val state = _uiState.value
            val prefs = state.preferences
            if ((prefs.continuousLoopEnabled || state.localDuplexLoopActive) && state.permissionSnapshot.micGranted) {
                scope.launch {
                    // Respect half-duplex acoustic echo decay window (~650ms) before opening mic
                    delay(700L)
                    val cur = _uiState.value
                    if (cur.coreMode == CoreMode.IDLE &&
                        (cur.preferences.continuousLoopEnabled || cur.localDuplexLoopActive) &&
                        !voiceManager.isEchoGuardActiveNow()
                    ) {
                        startListening(
                            initialBanner = if (cur.localDuplexLoopActive) "Live Duplex · Listening..." else ""
                        )
                    }
                }
            } else {
                maybeArmWakeWord()
            }
        },
        onWakeWordTriggered = { match ->
            scope.launch {
                if (match.trailingCommand.isNotBlank()) {
                    _uiState.update {
                        it.copy(
                            partialTranscript = match.trailingCommand,
                            isWakeWordArmed = false
                        )
                    }
                    submitCommand(
                        prompt = match.trailingCommand,
                        sourceMode = if (_uiState.value.backgroundServiceActive) "WAKE_BG" else "WAKE_WORD"
                    )
                } else {
                    startListening(initialBanner = "Wake word '${match.matchedPhrase}' · Listening...")
                }
            }
        },
        onBargeInDetected = {
            scope.launch {
                _uiState.update { it.copy(lastBargeInTimestamp = System.currentTimeMillis()) }
                startListening(initialBanner = "Barge-in active · Listening...")
            }
        },
        onEchoGuardChanged = { active ->
            _uiState.update { it.copy(echoGuardActive = active) }
        }
    )

    init {
        syncVoiceHandsFreeConfig(_uiState.value.preferences)
        _uiState.update {
            it.copy(
                isNvidiaConfigured = nvidiaService.isConfigured(),
                isSqlCipherEncrypted = vaultRepository.isSqlCipherEncrypted,
                encryptionBadgeLabel = vaultRepository.encryptionStatusLabel,
                voicePacks = piperManager.packStates.value
            )
        }
        scope.launch {
            piperManager.packStates.collect { packs ->
                _uiState.update { it.copy(voicePacks = packs) }
            }
        }
        ScreenMacroManager.onRecordingStateChanged = { recording, count ->
            _uiState.update {
                it.copy(isMacroRecording = recording, macroStepCount = count)
            }
        }
        if (initialPrefs.dailyBriefingEnabled) {
            BriefingScheduler.scheduleDailyBriefing(
                context = appContext,
                enabled = true,
                hour = initialPrefs.briefingHour,
                minute = initialPrefs.briefingMinute
            )
        }
        refreshPermissionsAndTelemetry()
        scope.launch(Dispatchers.IO) {
            runCatching { telemetryProvider.queryLauncherAppMap() }
        }
    }

    private fun syncVoiceHandsFreeConfig(prefs: AssistantPreferences) {
        voiceManager.updateHandsFreeConfig(
            wakeWordEnabled = prefs.wakeWordEnabled,
            bargeInEnabled = prefs.bargeInEnabled,
            customWakeWord = prefs.customWakeWord,
            persona = prefs.persona,
            localeOption = prefs.speechLocale,
            voiceEngineMode = prefs.voiceEngineMode,
            selectedVoiceId = prefs.selectedEnglishVoiceId,
            bargeInMode = prefs.bargeInMode
        )
    }

    fun maybeArmWakeWord() {
        val state = _uiState.value
        val prefs = state.preferences
        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (prefs.wakeWordEnabled && hasMic && state.coreMode == CoreMode.IDLE) {
            voiceManager.startWakeWordListening()
            _uiState.update { it.copy(isWakeWordArmed = true) }
        } else if (!prefs.wakeWordEnabled) {
            voiceManager.stopWakeWordListening()
            _uiState.update { it.copy(isWakeWordArmed = false) }
        }
    }

    fun refreshPermissionsAndTelemetry() {
        scope.launch {
            val (perms, telemetry) = withContext(Dispatchers.IO) {
                permissionsManager.checkSnapshot() to telemetryProvider.readTelemetry()
            }
            _uiState.update {
                it.copy(
                    permissionSnapshot = perms,
                    telemetry = telemetry,
                    isSqlCipherEncrypted = vaultRepository.isSqlCipherEncrypted,
                    encryptionBadgeLabel = vaultRepository.encryptionStatusLabel
                )
            }
            maybeArmWakeWord()
        }
    }

    fun updatePreferences(transform: (AssistantPreferences) -> AssistantPreferences) {
        val updated = transform(_uiState.value.preferences)
        vaultRepository.savePreferences(updated)
        syncVoiceHandsFreeConfig(updated)
        _uiState.update { state ->
            state.copy(
                preferences = updated,
                isVaultUnlocked = if (!updated.biometricVaultLockEnabled) true else state.isVaultUnlocked
            )
        }
        maybeArmWakeWord()
    }

    fun unlockVault() {
        _uiState.update { it.copy(isVaultUnlocked = true) }
    }

    fun lockVault() {
        if (_uiState.value.preferences.biometricVaultLockEnabled) {
            _uiState.update { it.copy(isVaultUnlocked = false) }
        }
    }

    fun saveNvidiaSettings(apiKey: String, model: String, preferPrimary: Boolean) {
        val cleanKey = apiKey.trim()
        val cleanModel = model.trim().ifBlank { AssistantPreferences.DEFAULT_NVIDIA_MODEL_ID }
        updatePreferences {
            it.copy(
                nvidiaApiKey = cleanKey,
                nvidiaModel = cleanModel,
                preferNvidiaPrimary = preferPrimary
            )
        }
        val configured = nvidiaService.isConfigured()
        _uiState.update { state ->
            state.copy(
                isNvidiaConfigured = configured,
                coreMode = if (configured && state.coreMode == CoreMode.ERROR) CoreMode.IDLE else state.coreMode,
                errorMessage = if (configured) null else state.errorMessage,
                latestReply = when {
                    configured && preferPrimary -> "NVIDIA NIM ($cleanModel) active as primary AI."
                    configured -> "NVIDIA NIM key ready for fallback & AI routing."
                    else -> state.latestReply
                }
            )
        }
    }

    suspend fun fetchLiveNvidiaModels(overrideKey: String? = null): Result<List<String>> {
        return nvidiaService.fetchLiveChatModels(overrideKey)
    }

    fun cyclePersona() {
        val entries = com.example.ai.AssistantPersona.entries
        val current = _uiState.value.preferences.persona
        val next = entries[(entries.indexOf(current) + 1) % entries.size]
        updatePreferences { it.copy(persona = next) }
    }

    fun setBackgroundServiceState(active: Boolean, paused: Boolean = false) {
        _uiState.update {
            it.copy(backgroundServiceActive = active, backgroundServicePaused = paused)
        }
    }

    fun startListening(initialBanner: String = "") {
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!micGranted) {
            _uiState.update {
                it.copy(
                    coreMode = CoreMode.ERROR,
                    errorMessage = "Microphone permission is required for voice. Grant Mic or type your command below."
                )
            }
            return
        }

        // Barge-in / active capture transitions cleanly from SPEAKING or passive wake-word
        _uiState.update {
            it.copy(
                coreMode = CoreMode.LISTENING,
                partialTranscript = initialBanner,
                errorMessage = null,
                isWakeWordArmed = false
            )
        }
        voiceManager.startListening(_uiState.value.preferences.speechLocale)
    }

    fun stopListening() {
        voiceManager.stopListening()
        _uiState.update {
            if (it.coreMode == CoreMode.LISTENING) it.copy(coreMode = CoreMode.IDLE) else it
        }
        maybeArmWakeWord()
    }

    fun abortAllTasks() {
        activeExecutionJob?.cancel()
        activeExecutionJob = null
        stopLiveDuplexMode()
        voiceManager.stopListening()
        voiceManager.stopSpeaking()
        _uiState.update {
            it.copy(
                coreMode = CoreMode.IDLE,
                streamingReply = "",
                localDuplexLoopActive = false,
                isLiveDuplexActive = false,
                liveDuplexLabel = "",
                activeTasks = it.activeTasks.map { item ->
                    if (item.status == TaskStatus.RUNNING || item.status == TaskStatus.QUEUED) {
                        item.copy(status = TaskStatus.FAILED, detail = "Aborted by user")
                    } else item
                },
                pendingConfirmationSteps = emptyList(),
                pendingConfirmationReply = ""
            )
        }
        maybeArmWakeWord()
    }

    // ---- Neural Voice Studio, Full-Duplex Live, Briefing & Screen Macro Controls ----

    fun downloadVoicePack(packId: String) {
        scope.launch {
            val res = piperManager.downloadAndInstallPack(packId)
            if (res.isSuccess) {
                updatePreferences {
                    it.copy(selectedEnglishVoiceId = packId, voiceEngineMode = "PIPER")
                }
            }
        }
    }

    fun selectVoicePack(packId: String) {
        updatePreferences { it.copy(selectedEnglishVoiceId = packId, voiceEngineMode = "PIPER") }
    }

    fun deleteVoicePack(packId: String) {
        piperManager.deletePack(packId)
    }

    fun testVoiceSample(packId: String? = null) {
        val prefs = _uiState.value.preferences
        val samplePrefs = if (packId != null) {
            prefs.copy(selectedEnglishVoiceId = packId, voiceEngineMode = "PIPER")
        } else {
            prefs
        }
        syncVoiceHandsFreeConfig(samplePrefs)
        val sampleText = if (prefs.speechLocale == com.example.ai.SpeechLocaleOption.BN_BD) {
            "হ্যালো! আমি ${prefs.persona.displayName}, এখন আপনার ভয়েস এবং ইকো প্রটেকশন সম্পূর্ণ প্রস্তুত।"
        } else {
            "Hello! This is ${prefs.persona.displayName} speaking through hardware echo-canceled neural audio."
        }
        voiceManager.speak(sampleText, samplePrefs.persona, samplePrefs.speechLocale)
        if (packId != null) {
            updatePreferences { samplePrefs }
        }
    }

    fun toggleLiveDuplexMode() {
        val cur = _uiState.value
        if (cur.isLiveDuplexActive) {
            stopLiveDuplexMode()
            return
        }
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            _uiState.update {
                it.copy(
                    coreMode = CoreMode.ERROR,
                    errorMessage = "Microphone permission is required for Live Duplex conversation."
                )
            }
            return
        }
        voiceManager.stopWakeWordListening()
        voiceManager.stopSpeaking()
        scope.launch {
            val (telemetryTxt, memoriesTxt) = withContext(Dispatchers.IO) {
                val tel = telemetryProvider.buildPromptTelemetrySummary(telemetryProvider.readTelemetry())
                val mem = vaultRepository.getTopMemories().joinToString("\n") { "- ${it.content}" }
                tel to mem
            }
            fullDuplexManager.startNativeGeminiLive(
                scope = scope,
                persona = _uiState.value.preferences.persona,
                deviceTelemetrySummary = telemetryTxt,
                memoriesSummary = memoriesTxt
            )
        }
    }

    fun stopLiveDuplexMode() {
        fullDuplexManager.stop()
        voiceManager.stopListening()
        _uiState.update {
            it.copy(
                isLiveDuplexActive = false,
                localDuplexLoopActive = false,
                liveDuplexLabel = "",
                coreMode = if (it.coreMode == CoreMode.SPEAKING || it.coreMode == CoreMode.LISTENING) CoreMode.IDLE else it.coreMode
            )
        }
        maybeArmWakeWord()
    }

    fun toggleDailyBriefing(enabled: Boolean, hour: Int, minute: Int) {
        updatePreferences {
            it.copy(
                dailyBriefingEnabled = enabled,
                briefingHour = hour.coerceIn(0, 23),
                briefingMinute = minute.coerceIn(0, 59)
            )
        }
        BriefingScheduler.scheduleDailyBriefing(appContext, enabled, hour, minute)
    }

    fun addReminder(title: String, delayMinutes: Int, repeatDaily: Boolean = false) {
        val clean = title.trim()
        if (clean.isBlank()) return
        scope.launch {
            val triggerAt = System.currentTimeMillis() + (delayMinutes.coerceAtLeast(1) * 60_000L)
            val id = withContext(Dispatchers.IO) {
                vaultRepository.saveReminder(
                    ReminderEntity(
                        title = clean,
                        triggerAtMillis = triggerAt,
                        repeatDaily = repeatDaily
                    )
                )
            }
            BriefingScheduler.scheduleReminder(appContext, id, clean, triggerAt)
        }
    }

    fun toggleReminderCompleted(id: Long, completed: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                vaultRepository.setReminderCompleted(id, completed)
            }
            if (completed) {
                BriefingScheduler.cancelReminder(appContext, id)
            }
        }
    }

    fun deleteReminder(id: Long) {
        BriefingScheduler.cancelReminder(appContext, id)
        scope.launch(Dispatchers.IO) {
            vaultRepository.deleteReminder(id)
        }
    }

    fun runDailyBriefing(reminderTitleOverride: String = "") {
        activeExecutionJob?.cancel()
        voiceManager.stopWakeWordListening()
        voiceManager.stopSpeaking()

        activeExecutionJob = scope.launch {
            val prefs = _uiState.value.preferences
            _uiState.update {
                it.copy(
                    coreMode = CoreMode.THINKING,
                    lastUserPrompt = if (reminderTitleOverride.isNotBlank()) "Reminder Alert" else "Daily Briefing",
                    errorMessage = null
                )
            }
            val summaryText = withContext(Dispatchers.IO) {
                if (reminderTitleOverride.isNotBlank()) {
                    "Reminder due now: $reminderTitleOverride."
                } else {
                    val telemetry = telemetryProvider.readTelemetry()
                    val coords = telemetryProvider.getLastCoarseCoordinates()
                    val weather = webTools.fetchWeather("current", coords?.first, coords?.second)
                    val reminders = vaultRepository.getPendingReminders().take(3)
                    val memories = vaultRepository.getTopMemories().filter { it.isPinned }.take(2)
                    buildString {
                        append("${prefs.persona.displayName} briefing. ")
                        append("Battery ${telemetry.batteryPercent}% (${telemetry.networkLabel}). ")
                        append("$weather ")
                        if (reminders.isNotEmpty()) {
                            append("Upcoming reminders: ${reminders.joinToString(", ") { it.title }}. ")
                        } else {
                            append("No overdue reminders. ")
                        }
                        if (memories.isNotEmpty()) {
                            append("Pinned note: ${memories.first().content}.")
                        }
                    }.trim()
                }
            }
            _uiState.update {
                it.copy(
                    latestReply = summaryText,
                    coreMode = if (prefs.ttsEnabled) CoreMode.SPEAKING else CoreMode.IDLE
                )
            }
            if (prefs.ttsEnabled) {
                voiceManager.speak(summaryText, prefs.persona, prefs.speechLocale)
            } else {
                maybeArmWakeWord()
            }
        }
    }

    fun toggleMacroRecording(customName: String = "") {
        if (ScreenMacroManager.isRecording) {
            val steps = ScreenMacroManager.stopAndDrainSteps()
            if (steps.isEmpty()) {
                _uiState.update {
                    it.copy(latestReply = "Macro recording stopped (0 interactions captured outside Aether).")
                }
                return
            }
            val baseName = customName.trim().ifBlank {
                val targetApp = steps.firstOrNull { it.appLabel.isNotBlank() }?.appLabel ?: "Screen"
                "$targetApp Macro"
            }
            val json = ScreenMacroManager.encodeStepsJson(steps)
            scope.launch {
                withContext(Dispatchers.IO) {
                    vaultRepository.saveMacro(
                        ScreenMacroEntity(
                            name = baseName,
                            voiceTrigger = baseName.lowercase(),
                            stepsJson = json,
                            stepCount = steps.size
                        )
                    )
                }
                _uiState.update {
                    it.copy(latestReply = "Saved visual macro '$baseName' (${steps.size} steps). Say '$baseName' anytime to replay.")
                }
            }
        } else {
            if (AetherAccessibilityService.currentInstance == null) {
                _uiState.update {
                    it.copy(
                        coreMode = CoreMode.ERROR,
                        errorMessage = "Enable Trusted Screen Agent (Accessibility) in Permissions Hub to record screen macros."
                    )
                }
                return
            }
            ScreenMacroManager.startRecording()
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    latestReply = "Recording visual screen macro... Switch to any app and tap/type, then tap Stop on the floating orb or Vault."
                )
            }
        }
    }

    fun replayMacro(macro: ScreenMacroEntity) {
        val rawSteps = ScreenMacroManager.decodeStepsJson(macro.stepsJson)
        val dagSteps = ScreenMacroManager.compileMacroToDagSteps(rawSteps)
        if (dagSteps.isEmpty()) return
        activeExecutionJob?.cancel()
        activeExecutionJob = scope.launch {
            withContext(Dispatchers.IO) { vaultRepository.recordMacroRun(macro.id) }
            executeStepsPipeline(
                userPrompt = "Replay macro: ${macro.name}",
                initialReply = "Replaying '${macro.name}' (${rawSteps.size} steps).",
                steps = dagSteps,
                bypassConfirmation = true,
                sourceMode = "MACRO_REPLAY",
                hadScreenVision = false
            )
        }
    }

    fun deleteMacro(id: Long) {
        scope.launch(Dispatchers.IO) {
            vaultRepository.deleteMacro(id)
        }
    }

    fun confirmPendingSensitiveSteps() {
        val pending = _uiState.value.pendingConfirmationSteps
        val reply = _uiState.value.pendingConfirmationReply
        if (pending.isEmpty()) return
        _uiState.update {
            it.copy(pendingConfirmationSteps = emptyList(), pendingConfirmationReply = "")
        }
        activeExecutionJob = scope.launch {
            executeStepsPipeline(
                userPrompt = _uiState.value.lastUserPrompt,
                initialReply = reply,
                steps = pending,
                bypassConfirmation = true,
                sourceMode = "FOREGROUND",
                hadScreenVision = false
            )
        }
    }

    fun dismissPendingConfirmation() {
        _uiState.update {
            it.copy(
                pendingConfirmationSteps = emptyList(),
                pendingConfirmationReply = "",
                coreMode = CoreMode.IDLE
            )
        }
        maybeArmWakeWord()
    }

    fun submitCommand(
        prompt: String,
        attachedImage: Bitmap? = null,
        sourceMode: String = "FOREGROUND"
    ) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank() && attachedImage == null) return
        val effectivePrompt = cleanPrompt.ifBlank { "Analyze this image and tell me what action to take." }

        activeExecutionJob?.cancel()
        voiceManager.stopWakeWordListening()
        voiceManager.stopSpeaking()

        _uiState.update {
            it.copy(
                coreMode = CoreMode.THINKING,
                lastUserPrompt = effectivePrompt,
                partialTranscript = "",
                streamingReply = "",
                visionInsightLabel = "",
                errorMessage = null,
                activeTasks = emptyList(),
                isWakeWordArmed = false
            )
        }

        activeExecutionJob = scope.launch {
            try {
                val prefs = _uiState.value.preferences

                // Check saved visual screen macros first for instant voice replay
                val savedMacros = withContext(Dispatchers.IO) { vaultRepository.getMacrosSnapshot() }
                val matchedMacro = savedMacros.firstOrNull { macro ->
                    macro.voiceTrigger.isNotBlank() && effectivePrompt.contains(macro.voiceTrigger, ignoreCase = true)
                }
                if (matchedMacro != null) {
                    replayMacro(matchedMacro)
                    return@launch
                }

                // Handle "morning briefing" / "daily briefing" immediately without blocking on LLM
                val lowerPrompt = effectivePrompt.lowercase()
                if (lowerPrompt == "morning briefing" || lowerPrompt == "daily briefing" || lowerPrompt == "give me my briefing") {
                    runDailyBriefing()
                    return@launch
                }

                // Load SQLite & telemetry context in parallel on Dispatchers.IO for zero lag
                val contextBundle = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val routinesDeferred = async { vaultRepository.getRoutinesSnapshot() }
                        val memoriesDeferred = async { vaultRepository.getTopMemories() }
                        val contactsDeferred = async { vaultRepository.getContactsSnapshot() }
                        val appsDeferred = async { telemetryProvider.queryLauncherAppNames(30) }
                        val permsDeferred = async { permissionsManager.checkSnapshot() }
                        val telemetryDeferred = async { telemetryProvider.readTelemetry() }

                        val routines = routinesDeferred.await()
                        val matchedRoutine = routines.firstOrNull { routine ->
                            effectivePrompt.contains(routine.triggerPhrase, ignoreCase = true)
                        }
                        val resolved = if (matchedRoutine != null) {
                            vaultRepository.recordRoutineUsed(matchedRoutine.id)
                            "${matchedRoutine.instructionPrompt} (Triggered routine: ${matchedRoutine.triggerPhrase})"
                        } else {
                            effectivePrompt
                        }
                        val telemetry = telemetryDeferred.await()

                        ContextSnapshotBundle(
                            resolvedPrompt = resolved,
                            memoriesText = memoriesDeferred.await().joinToString("\n") { "- [${it.category}] ${it.content}" },
                            routinesText = routines.joinToString("\n") { "- ${it.triggerPhrase}: ${it.instructionPrompt}" },
                            contactsText = contactsDeferred.await().joinToString("\n") {
                                "- ${it.aliasName} (${it.displayName}): phone=${it.phoneNumber}, email=${it.emailAddress}"
                            },
                            appsText = appsDeferred.await(),
                            telemetryText = telemetryProvider.buildPromptTelemetrySummary(telemetry),
                            perms = permsDeferred.await(),
                            telemetry = telemetry
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        permissionSnapshot = contextBundle.perms,
                        telemetry = contextBundle.telemetry
                    )
                }

                val accessibility = AetherAccessibilityService.currentInstance
                val wantsScreen = attachedImage == null && accessibility != null &&
                    shouldInspectScreenProactively(contextBundle.resolvedPrompt)

                var screenSummary: String? = null
                var ocrSummary: String? = null
                var bitmapToSend: Bitmap? = null
                var ownsBitmapToSend = false
                var usedScreenVision = attachedImage != null

                if (wantsScreen) {
                    _uiState.update {
                        it.copy(
                            coreMode = CoreMode.OBSERVING_SCREEN,
                            lastUserPrompt = effectivePrompt,
                            errorMessage = null
                        )
                    }
                    val snap = accessibility.captureFullScreenContext(
                        includeBitmap = true,
                        focusHint = contextBundle.resolvedPrompt
                    )
                    screenSummary = snap?.uiSummary
                    usedScreenVision = snap != null
                    if (snap?.screenshot != null) {
                        val processed = visionProcessor.processScreenForVision(
                            sourceBitmap = snap.screenshot,
                            prompt = contextBundle.resolvedPrompt,
                            accessibilityNodeCount = snap.nodeCount,
                            forceOcrFallback = prefs.preferNvidiaPrimary,
                            anchorRect = snap.suggestedAnchorRect
                        )
                        bitmapToSend = processed.croppedBitmap
                        ownsBitmapToSend = processed.ownsCroppedBitmap
                        ocrSummary = processed.ocrSummary.ifBlank { null }
                        if (ownsBitmapToSend) {
                            snap.screenshot.recycle()
                        }
                        val badge = buildString {
                            append(processed.regionLabel)
                            if (processed.areaReductionPercent > 0) append(" (-${processed.areaReductionPercent}% px)")
                            if (processed.ocrLineCount > 0) append(" · OCR ${processed.ocrLineCount} lines")
                        }
                        _uiState.update { it.copy(visionInsightLabel = badge) }
                    }
                } else if (attachedImage != null) {
                    val processed = visionProcessor.processScreenForVision(
                        sourceBitmap = attachedImage,
                        prompt = contextBundle.resolvedPrompt,
                        accessibilityNodeCount = 0,
                        forceOcrFallback = true
                    )
                    bitmapToSend = processed.croppedBitmap
                    ownsBitmapToSend = processed.ownsCroppedBitmap
                    ocrSummary = processed.ocrSummary.ifBlank { null }
                    val badge = buildString {
                        append(processed.regionLabel)
                        if (processed.ocrLineCount > 0) append(" · OCR ${processed.ocrLineCount} lines")
                    }
                    _uiState.update { it.copy(visionInsightLabel = badge) }
                }

                _uiState.update {
                    it.copy(
                        coreMode = CoreMode.THINKING,
                        lastUserPrompt = effectivePrompt,
                        partialTranscript = "",
                        errorMessage = null
                    )
                }

                var planResult = geminiService.planCommand(
                    userPrompt = contextBundle.resolvedPrompt,
                    persona = prefs.persona,
                    deviceContextSummary = contextBundle.telemetryText,
                    memoriesSummary = contextBundle.memoriesText,
                    routinesSummary = contextBundle.routinesText,
                    contactsSummary = contextBundle.contactsText,
                    installedAppsSummary = contextBundle.appsText,
                    screenUiSummary = screenSummary,
                    screenBitmap = bitmapToSend,
                    ocrSummary = ocrSummary,
                    preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                    onStreamingReply = { partial ->
                        _uiState.update { it.copy(streamingReply = partial) }
                    },
                    onActiveModelChanged = { label ->
                        _uiState.update { it.copy(activeAiModelLabel = label) }
                    }
                )

                var plan = planResult.getOrElse { err ->
                    if (ownsBitmapToSend) bitmapToSend?.recycle()
                    _uiState.update {
                        it.copy(
                            coreMode = CoreMode.ERROR,
                            streamingReply = "",
                            errorMessage = err.localizedMessage ?: "Gemini planning failed."
                        )
                    }
                    return@launch
                }

                if (plan.needsScreenObservation && !usedScreenVision && accessibility != null) {
                    _uiState.update { it.copy(coreMode = CoreMode.OBSERVING_SCREEN) }
                    val snap = accessibility.captureFullScreenContext(
                        includeBitmap = true,
                        focusHint = contextBundle.resolvedPrompt
                    )
                    if (snap != null) {
                        usedScreenVision = true
                        val processed = visionProcessor.processScreenForVision(
                            sourceBitmap = snap.screenshot,
                            prompt = contextBundle.resolvedPrompt,
                            accessibilityNodeCount = snap.nodeCount,
                            forceOcrFallback = prefs.preferNvidiaPrimary,
                            anchorRect = snap.suggestedAnchorRect
                        )
                        val retryBmp = processed.croppedBitmap
                        val badge = buildString {
                            append(processed.regionLabel)
                            if (processed.areaReductionPercent > 0) append(" (-${processed.areaReductionPercent}% px)")
                            if (processed.ocrLineCount > 0) append(" · OCR ${processed.ocrLineCount} lines")
                        }
                        _uiState.update {
                            it.copy(coreMode = CoreMode.THINKING, visionInsightLabel = badge)
                        }
                        planResult = geminiService.planCommand(
                            userPrompt = contextBundle.resolvedPrompt,
                            persona = prefs.persona,
                            deviceContextSummary = contextBundle.telemetryText,
                            memoriesSummary = contextBundle.memoriesText,
                            routinesSummary = contextBundle.routinesText,
                            contactsSummary = contextBundle.contactsText,
                            installedAppsSummary = contextBundle.appsText,
                            screenUiSummary = snap.uiSummary,
                            screenBitmap = retryBmp,
                            ocrSummary = processed.ocrSummary.ifBlank { null },
                            preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                            onStreamingReply = { partial ->
                                _uiState.update { it.copy(streamingReply = partial) }
                            },
                            onActiveModelChanged = { label ->
                                _uiState.update { it.copy(activeAiModelLabel = label) }
                            }
                        )
                        if (processed.ownsCroppedBitmap) retryBmp?.recycle()
                        snap.screenshot?.recycle()
                        plan = planResult.getOrElse { plan }
                    }
                } else if (ownsBitmapToSend) {
                    bitmapToSend?.recycle()
                }

                executeStepsPipeline(
                    userPrompt = effectivePrompt,
                    initialReply = plan.reply,
                    steps = plan.steps,
                    bypassConfirmation = false,
                    sourceMode = sourceMode,
                    hadScreenVision = usedScreenVision
                )

                // Optional autonomous follow-up step for multi-screen chained UI goals
                if (plan.continueLoopAfterAction && accessibility != null) {
                    delay(1_200L)
                    _uiState.update { it.copy(coreMode = CoreMode.OBSERVING_SCREEN) }
                    val nextSnap = accessibility.captureFullScreenContext(includeBitmap = false)
                    if (nextSnap != null) {
                        _uiState.update { it.copy(coreMode = CoreMode.THINKING) }
                        val followUp = geminiService.planCommand(
                            userPrompt = "Continue user goal: ${contextBundle.resolvedPrompt}. Previous steps completed.",
                            persona = prefs.persona,
                            deviceContextSummary = contextBundle.telemetryText,
                            memoriesSummary = contextBundle.memoriesText,
                            routinesSummary = contextBundle.routinesText,
                            contactsSummary = contextBundle.contactsText,
                            installedAppsSummary = contextBundle.appsText,
                            screenUiSummary = nextSnap.uiSummary,
                            screenBitmap = null,
                            preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                            onStreamingReply = { partial ->
                                _uiState.update { it.copy(streamingReply = partial) }
                            },
                            onActiveModelChanged = { label ->
                                _uiState.update { it.copy(activeAiModelLabel = label) }
                            }
                        )
                        followUp.getOrNull()?.let { nextPlan ->
                            val actionableFollowUp = nextPlan.steps.filter {
                                it.actionType != ActionType.SPEAK_ONLY && it.actionType != ActionType.SCREEN_EXPLAIN
                            }
                            if (actionableFollowUp.isNotEmpty()) {
                                executeStepsPipeline(
                                    userPrompt = effectivePrompt,
                                    initialReply = nextPlan.reply,
                                    steps = actionableFollowUp,
                                    bypassConfirmation = false,
                                    sourceMode = sourceMode,
                                    hadScreenVision = true
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Orchestrator execution error", e)
                _uiState.update {
                    it.copy(
                        coreMode = CoreMode.ERROR,
                        streamingReply = "",
                        errorMessage = e.localizedMessage ?: "Execution error"
                    )
                }
            }
        }
    }

    private suspend fun executeStepsPipeline(
        userPrompt: String,
        initialReply: String,
        steps: List<PlannedStep>,
        bypassConfirmation: Boolean,
        sourceMode: String,
        hadScreenVision: Boolean
    ) {
        val prefs = _uiState.value.preferences
        val guardedNeeded = !prefs.autonomousModeEnabled && !bypassConfirmation && steps.any { it.isSensitive }
        val dagNodes = dagExecutor.buildDag(steps)

        if (guardedNeeded) {
            _uiState.update {
                it.copy(
                    coreMode = CoreMode.IDLE,
                    streamingReply = "",
                    latestReply = initialReply,
                    pendingConfirmationSteps = steps,
                    pendingConfirmationReply = initialReply,
                    activeTasks = dagNodes.map { node ->
                        ActiveTaskItem(
                            id = node.id,
                            step = node.step,
                            status = TaskStatus.NEEDS_CONFIRMATION,
                            detail = "Awaiting confirmation",
                            dependsOn = node.dependencies
                        )
                    }
                )
            }
            if (prefs.ttsEnabled) {
                voiceManager.speak(
                    "Please confirm to proceed with ${steps.firstOrNull { it.isSensitive }?.label ?: "this action"}.",
                    prefs.persona,
                    prefs.speechLocale
                )
            }
            return
        }

        val initialTasks = dagExecutor.initialTaskItems(dagNodes)
        _uiState.update {
            it.copy(
                coreMode = CoreMode.MULTITASKING,
                streamingReply = "",
                activeTasks = initialTasks,
                latestReply = initialReply
            )
        }

        val dagSummary = dagExecutor.executeDag(
            nodes = dagNodes,
            onTaskUpdate = { taskId, status, detail, elapsedMs, timedOut ->
                updateTaskStatus(taskId, status, detail, elapsedMs, timedOut)
            },
            onTorchStateChanged = { torchOn ->
                _uiState.update { it.copy(torchEnabled = torchOn) }
            },
            executor = { step ->
                actionRouter.executeStep(step, prefs.splitScreenLaunchEnabled)
            }
        )

        val toolOutputs = dagSummary.toolOutputs
        val finalReply = if (toolOutputs.isNotEmpty()) {
            val onlyBackgroundOutputs = dagNodes.none {
                !it.step.isBackgroundParallel && it.step.actionType != ActionType.SPEAK_ONLY
            }
            if (toolOutputs.size == 1 && onlyBackgroundOutputs) {
                toolOutputs.first()
            } else {
                geminiService.synthesizeToolSummary(
                    userPrompt = userPrompt,
                    persona = prefs.persona,
                    toolOutputs = toolOutputs,
                    preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                    onStreamPartial = { partial ->
                        _uiState.update { it.copy(streamingReply = partial) }
                    }
                ) ?: toolOutputs.joinToString(" ")
            }
        } else {
            initialReply
        }

        val finalTasks = _uiState.value.activeTasks
        val allFailed = finalTasks.isNotEmpty() && finalTasks.all {
            it.status == TaskStatus.FAILED || it.status == TaskStatus.TIMED_OUT || it.status == TaskStatus.SKIPPED
        }
        val summaryStr = finalTasks.joinToString(" | ") { "${it.step.label}: ${it.status.name}" }

        withContext(Dispatchers.IO) {
            vaultRepository.insertLog(
                CommandLogEntity(
                    userPrompt = userPrompt,
                    assistantReply = finalReply,
                    personaUsed = prefs.persona.displayName,
                    sourceMode = sourceMode,
                    hadScreenVision = hadScreenVision,
                    tasksSummary = summaryStr,
                    executionStatus = if (allFailed) "FAILED" else "EXECUTED"
                )
            )
        }

        _uiState.update {
            it.copy(
                streamingReply = "",
                latestReply = finalReply,
                coreMode = if (prefs.ttsEnabled) CoreMode.SPEAKING else CoreMode.IDLE
            )
        }

        if (prefs.ttsEnabled) {
            voiceManager.speak(finalReply, prefs.persona, prefs.speechLocale)
        } else {
            maybeArmWakeWord()
        }
    }

    private fun updateTaskStatus(
        taskId: String,
        status: TaskStatus,
        detail: String,
        elapsedMs: Long,
        timedOut: Boolean
    ) {
        _uiState.update { state ->
            state.copy(
                activeTasks = state.activeTasks.map {
                    if (it.id == taskId) {
                        it.copy(
                            status = status,
                            detail = detail,
                            elapsedMs = elapsedMs,
                            timedOut = timedOut
                        )
                    } else {
                        it
                    }
                }
            )
        }
    }

    private fun shouldInspectScreenProactively(prompt: String): Boolean {
        val lower = prompt.lowercase()
        val keywords = listOf(
            "screen", "this page", "this app", "what am i looking", "read this",
            "summarize this", "tap ", "click ", "scroll ", "type ", "fill ", "select "
        )
        return keywords.any { lower.contains(it) }
    }

    private data class ContextSnapshotBundle(
        val resolvedPrompt: String,
        val memoriesText: String,
        val routinesText: String,
        val contactsText: String,
        val appsText: String,
        val telemetryText: String,
        val perms: PermissionSnapshot,
        val telemetry: DeviceTelemetry
    )

    companion object {
        @Volatile
        private var INSTANCE: AgentOrchestrator? = null

        fun getInstance(context: Context): AgentOrchestrator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentOrchestrator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
