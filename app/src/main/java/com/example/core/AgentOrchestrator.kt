package com.example.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.accessibility.AetherAccessibilityService
import com.example.ai.ActionType
import com.example.ai.ActiveTaskItem
import com.example.ai.AssistantPersona
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
import com.example.audio.AudioFilterDiagnostics
import com.example.audio.FilteredMicrophoneEngine
import com.example.audio.FilteredSpeechSegment
import com.example.system.AndroidActionRouter
import com.example.system.CommunicationAudioRouter
import com.example.system.DeviceTelemetry
import com.example.system.DeviceTelemetryProvider
import com.example.system.OfflineCommandRouter
import com.example.system.PermissionSnapshot
import com.example.system.PermissionsManager
import com.example.system.VoiceAndTtsManager
import com.example.tools.FreeWebToolsRepository
import com.example.vision.ScreenVisionProcessor
import com.example.voice.CUSTOM_AETHER_VOICE_ID
import com.example.voice.FullDuplexLiveManager
import com.example.voice.PiperNeuralVoiceManager
import com.example.voice.VoicePackUiState
import com.example.voice.VoiceSampleAnalyzer
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
    val latestReply: String = "Aether 3D core online. Say \"Hey Aether\" or tap the orb below.",
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
    val isGeminiKeyConfigured: Boolean = false,
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
    val piperEngineStatus: String = "Checking voice engine...",
    val echoGuardActive: Boolean = false,
    val audioFilterDiagnostics: AudioFilterDiagnostics = AudioFilterDiagnostics(),
    val reActProgressLabel: String = "",
    val reActSubGoals: List<String> = emptyList(),
    val reActActiveSubGoalIndex: Int = 0,
    val reActWaitingForLoad: Boolean = false,
    val screenVisionSourceLabel: String = "",
    val isDeepVisionActive: Boolean = false,
    val kwsPackStatusLabel: String = "Silence-Gated Monitor",
    val kwsPackReady: Boolean = false,
    val kwsDownloadProgress: Int = 0,
    val isDefaultAssistant: Boolean = false,
    val estimatedTokensSavedSession: Long = 0L,
    val offlineVisionSummaryLabel: String = "",
    val unreadNotifications: List<com.example.notifications.UnreadNotificationCluster> = emptyList(),
    val notificationListenerConnected: Boolean = false,
    val cameraLensRequested: Boolean = false,
    val matchedPlaybookLabel: String = "",
    val lastAutoTriggerLabel: String = "",
    val lensCueState: com.example.camera.CameraLensCueState = com.example.camera.CameraLensCueState(),
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

    private val conversationBuffer = ConversationTurnBuffer(maxCapacity = 16)

    private val initialPrefs = vaultRepository.loadPreferences()
    private val _uiState = MutableStateFlow(
        AssistantUiState(
            preferences = initialPrefs,
            isSqlCipherEncrypted = vaultRepository.isSqlCipherEncrypted,
            encryptionBadgeLabel = vaultRepository.encryptionStatusLabel,
            isVaultUnlocked = !initialPrefs.biometricVaultLockEnabled,
            isGeminiKeyConfigured = initialPrefs.geminiApiKey.trim().length >= 16
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
    private val geminiService = GeminiCommandService(
        nvidiaFallback = nvidiaService,
        geminiApiKeyProvider = { _uiState.value.preferences.geminiApiKey },
        geminiModelProvider = { _uiState.value.preferences.geminiModel }
    )

    val commAudioRouter = CommunicationAudioRouter(appContext)
    val filteredMicEngine = FilteredMicrophoneEngine(appContext, commAudioRouter)
    val piperManager = PiperNeuralVoiceManager(appContext)
    val cameraLensEngine = com.example.camera.CameraXLensEngine(appContext)
    private val triggerMonitor = com.example.automation.DeviceContextTriggerMonitor(
        context = appContext,
        rulesProvider = { vaultRepository.getTriggerRulesSnapshot() },
        onRuleMatched = { rule, event -> handleTriggeredRule(rule, event) }
    )

    private val actionRouter = AndroidActionRouter(
        context = appContext,
        telemetryProvider = telemetryProvider,
        vaultRepository = vaultRepository,
        webTools = webTools
    )

    private val screenAutomationAgent = ScreenAutomationAgent(
        actionRouter = actionRouter,
        visionProcessor = visionProcessor,
        geminiService = geminiService
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

    @Volatile
    private var isForegroundListeningSession = false
    @Volatile
    private var usedFilteredFallbackThisTurn = false
    @Volatile
    private var suppressPassiveWakeUntilMs = 0L
    @Volatile
    private var lastSystemAssistPackage = ""
    @Volatile
    private var lastSystemAssistHint = ""
    @Volatile
    private var lastSystemAssistAtMs = 0L

    fun prepareExternalVoiceTrigger(graceWindowMs: Long = 5_000L) {
        suppressPassiveWakeUntilMs = android.os.SystemClock.elapsedRealtime() + graceWindowMs
        isForegroundListeningSession = true
        voiceManager.stopWakeWordListening()
        if (filteredMicEngine.isCapturing) {
            filteredMicEngine.stopCapture()
        }
        _uiState.update { it.copy(isWakeWordArmed = false) }
    }

    fun updateSystemAssistContext(packageName: String, extractedText: String) {
        val cleanPkg = packageName.trim()
        val cleanText = extractedText.trim()
        if (cleanPkg.isBlank() && cleanText.isBlank()) return
        lastSystemAssistPackage = cleanPkg
        lastSystemAssistHint = buildString {
            if (cleanPkg.isNotBlank()) append("Foreground App: $cleanPkg")
            if (cleanText.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("AssistStructure Snapshot:\n$cleanText")
            }
        }
        lastSystemAssistAtMs = android.os.SystemClock.elapsedRealtime()
    }

    val voiceManager: VoiceAndTtsManager = VoiceAndTtsManager(
        context = appContext,
        commRouter = commAudioRouter,
        piperManager = piperManager,
        onAmplitudeChanged = { amp -> _audioAmplitude.value = amp },
        onPartialSpeech = { partial ->
            _uiState.update { it.copy(partialTranscript = partial) }
        },
        onFinalSpeech = { text ->
            isForegroundListeningSession = false
            usedFilteredFallbackThisTurn = false
            _uiState.update { it.copy(partialTranscript = text, isWakeWordArmed = false) }
            submitCommand(
                prompt = text,
                sourceMode = if (_uiState.value.backgroundServiceActive) "BACKGROUND" else "FOREGROUND"
            )
        },
        onSpeechError = { errMsg, isServiceOrDeviceFailure ->
            val state = _uiState.value
            if (isForegroundListeningSession &&
                isServiceOrDeviceFailure &&
                state.preferences.filteredMicEnabled &&
                !usedFilteredFallbackThisTurn
            ) {
                usedFilteredFallbackThisTurn = true
                Log.w(TAG, "SpeechRecognizer failed ($errMsg); falling back seamlessly to FilteredMicrophoneEngine")
                startFilteredForegroundCapture("Filtered Mic active (AEC+NS+DSP) · Speak now...")
            } else {
                isForegroundListeningSession = false
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
                    resumeBackgroundCaptureIfNeeded()
                }
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
                resumeBackgroundCaptureIfNeeded()
            }
        },
        onWakeWordTriggered = { match ->
            scope.launch {
                voiceManager.playWakeChime()
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
                    startListening(initialBanner = "Hey ${_uiState.value.preferences.persona.displayName} · Listening...")
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
        },
        continuousCaptureActiveProvider = {
            filteredMicEngine.isCapturing
        }
    )

    init {
        syncVoiceHandsFreeConfig(_uiState.value.preferences)
        _uiState.update {
            it.copy(
                isNvidiaConfigured = nvidiaService.isConfigured(),
                isSqlCipherEncrypted = vaultRepository.isSqlCipherEncrypted,
                encryptionBadgeLabel = vaultRepository.encryptionStatusLabel,
                voicePacks = piperManager.packStates.value,
                piperEngineStatus = piperManager.engineStatusLabel.value
            )
        }
        scope.launch {
            piperManager.packStates.collect { packs ->
                _uiState.update { it.copy(voicePacks = packs) }
            }
        }
        scope.launch {
            piperManager.engineStatusLabel.collect { status ->
                _uiState.update { it.copy(piperEngineStatus = status) }
            }
        }
        scope.launch {
            filteredMicEngine.diagnostics.collect { diag ->
                _uiState.update { it.copy(audioFilterDiagnostics = diag) }
            }
        }
        scope.launch {
            voiceManager.localWakeEngine.uiState.collect { kwsState ->
                _uiState.update {
                    it.copy(
                        kwsPackStatusLabel = kwsState.statusLabel,
                        kwsPackReady = kwsState.status == com.example.system.KwsPackInstallStatus.READY,
                        kwsDownloadProgress = kwsState.progressPercent
                    )
                }
            }
        }
        scope.launch {
            com.example.vision.MediaProjectionScreenGrabber.isActive.collect { active ->
                _uiState.update {
                    it.copy(
                        isDeepVisionActive = active,
                        screenVisionSourceLabel = if (active) "PROJECTION READY" else if (it.screenVisionSourceLabel == "PROJECTION READY") "" else it.screenVisionSourceLabel
                    )
                }
            }
        }
        scope.launch {
            com.example.notifications.NotificationCopilotStore.clusters.collect { clusters ->
                _uiState.update {
                    it.copy(
                        unreadNotifications = clusters,
                        notificationListenerConnected = com.example.notifications.NotificationCopilotStore.isListenerConnected ||
                            it.permissionSnapshot.notificationAccessGranted
                    )
                }
            }
        }
        scope.launch {
            cameraLensEngine.cueState.collect { cue ->
                _uiState.update { it.copy(lensCueState = cue) }
            }
        }
        triggerMonitor.attachScopeEvaluator(scope) { work ->
            scope.launch(Dispatchers.IO) { work() }
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
            ensureConversationMemoryReady()
        }
        commAudioRouter.startMusicPlaybackWatcher { musicPlaying ->
            val prefs = _uiState.value.preferences
            if (prefs.pauseWakeDuringMusic) {
                if (musicPlaying) {
                    voiceManager.stopWakeWordListening()
                } else {
                    maybeArmWakeWord()
                }
            }
        }
    }

    private suspend fun ensureConversationMemoryReady() {
        if (conversationBuffer.isSeeded()) return
        val recentLogs = runCatching { vaultRepository.getRecentLogsSnapshot(20) }.getOrDefault(emptyList())
        conversationBuffer.seedFromLogsIfNeeded(
            recentLogs.map { log ->
                ConversationTurn(
                    userPrompt = log.userPrompt,
                    assistantReply = log.assistantReply,
                    timestamp = log.timestamp
                )
            }
        )
    }

    private suspend fun autoExtractAndPersistUserFacts(
        userPrompt: String,
        existingMemories: List<com.example.data.local.MemoryNoteEntity>
    ): List<com.example.data.local.MemoryNoteEntity> {
        val candidates = ConversationFactExtractor.extractDurableFacts(userPrompt)
        if (candidates.isEmpty()) return existingMemories
        val newFacts = ConversationFactExtractor.deduplicateNewFacts(
            candidates = candidates,
            existingContents = existingMemories.map { it.content }
        )
        if (newFacts.isEmpty()) return existingMemories
        val addedEntities = mutableListOf<com.example.data.local.MemoryNoteEntity>()
        for (fact in newFacts) {
            val insertedId = runCatching {
                vaultRepository.saveMemory(content = fact.content, category = fact.category)
            }.getOrDefault(0L)
            addedEntities.add(
                com.example.data.local.MemoryNoteEntity(
                    id = insertedId,
                    content = fact.content,
                    category = fact.category,
                    isPinned = true
                )
            )
        }
        return addedEntities + existingMemories
    }

    private fun recordConversationTurn(userText: String, assistantReply: String) {
        conversationBuffer.addTurn(userText, assistantReply)
    }

    private fun buildConversationHistorySummary(): String {
        return conversationBuffer.formatPromptSummary(limit = 10)
    }

    private fun buildConversationHistoryPairs(): List<Pair<String, String>> {
        return conversationBuffer.recentPairsChronological(limit = 10)
    }

    private fun syncVoiceHandsFreeConfig(prefs: AssistantPreferences) {
        voiceManager.updateHandsFreeConfig(
            wakeWordEnabled = prefs.wakeWordEnabled,
            bargeInEnabled = prefs.bargeInEnabled,
            customWakeWord = prefs.customWakeWord,
            persona = prefs.persona,
            localeOption = prefs.speechLocale,
            voiceEngineMode = prefs.voiceEngineMode,
            selectedVoiceId = prefs.voiceIdForPersona(),
            selectedBanglaId = prefs.selectedBanglaVoiceId,
            bargeInMode = prefs.bargeInMode,
            voiceSpeed = prefs.voiceSpeed,
            customAetherPitchFactor = prefs.customAetherPitchFactor,
            pauseWakeDuringMusic = prefs.pauseWakeDuringMusic,
            wakeTriggerMode = prefs.wakeTriggerMode,
            kwsThreshold = prefs.kwsThreshold
        )
    }

    fun downloadKwsModelPack() {
        voiceManager.localWakeEngine.downloadAndInstallKwsPack()
    }

    fun stopDeepScreenVision() {
        com.example.service.ScreenVisionService.stop(appContext)
    }

    fun ensureBackgroundStandbyListening() {
        val state = _uiState.value
        if (state.backgroundServicePaused) {
            voiceManager.stopWakeWordListening()
            return
        }
        if (filteredMicEngine.isCapturing) {
            filteredMicEngine.stopCapture()
        }
        maybeArmWakeWord()
    }

    fun maybeArmWakeWord() {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (isForegroundListeningSession || nowElapsed < suppressPassiveWakeUntilMs) {
            voiceManager.stopWakeWordListening()
            _uiState.update { it.copy(isWakeWordArmed = false) }
            return
        }
        val state = _uiState.value
        val prefs = state.preferences
        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val decision = com.example.system.EchoGuardPolicy.resolveBackgroundStandby(
            wakeWordEnabled = prefs.wakeWordEnabled,
            micGranted = hasMic,
            backgroundServicePaused = state.backgroundServicePaused,
            filteredMicCapturing = filteredMicEngine.isCapturing,
            pauseWakeDuringMusic = prefs.pauseWakeDuringMusic,
            musicPlaybackActive = commAudioRouter.isMusicPlaybackActive(),
            isCoreIdle = state.coreMode == CoreMode.IDLE,
            wakeTriggerMode = prefs.wakeTriggerMode
        )

        when (decision) {
            com.example.system.EchoGuardPolicy.StandbyDecision.START_WAKE_WORD -> {
                voiceManager.startWakeWordListening()
                _uiState.update { it.copy(isWakeWordArmed = true) }
            }
            com.example.system.EchoGuardPolicy.StandbyDecision.BUTTON_ONLY_IDLE,
            com.example.system.EchoGuardPolicy.StandbyDecision.PAUSED_BY_USER,
            com.example.system.EchoGuardPolicy.StandbyDecision.YIELDS_TO_FILTERED_MIC,
            com.example.system.EchoGuardPolicy.StandbyDecision.PAUSED_FOR_MUSIC,
            com.example.system.EchoGuardPolicy.StandbyDecision.DISABLED_OR_NO_PERMISSION -> {
                voiceManager.stopWakeWordListening()
                _uiState.update { it.copy(isWakeWordArmed = false) }
            }
            com.example.system.EchoGuardPolicy.StandbyDecision.BUSY_NOT_IDLE -> Unit
        }
    }

    fun refreshPermissionsAndTelemetry() {
        scope.launch {
            val wasDefaultAssistant = _uiState.value.isDefaultAssistant
            val (perms, telemetry, isDefaultAssist) = withContext(Dispatchers.IO) {
                Triple(
                    permissionsManager.checkSnapshot(),
                    telemetryProvider.readTelemetry(),
                    permissionsManager.isDefaultAssistantApp()
                )
            }
            val currentPrefs = _uiState.value.preferences
            val transitionedToDefaultAssist = !wasDefaultAssistant &&
                isDefaultAssist &&
                currentPrefs.autoZeroMicOnDefaultAssistant &&
                currentPrefs.wakeTriggerMode != com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY

            _uiState.update {
                it.copy(
                    permissionSnapshot = perms,
                    telemetry = telemetry,
                    isDefaultAssistant = isDefaultAssist,
                    isSqlCipherEncrypted = vaultRepository.isSqlCipherEncrypted,
                    encryptionBadgeLabel = vaultRepository.encryptionStatusLabel
                )
            }
            if (transitionedToDefaultAssist) {
                updatePreferences {
                    it.copy(
                        wakeTriggerMode = com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY,
                        wakeWordEnabled = false
                    )
                }
                _uiState.update { state ->
                    if (state.coreMode == CoreMode.IDLE) {
                        state.copy(
                            latestReply = "Default Digital Assistant active (0-Mic Standby). Hold Power/Home or swipe corner anytime to summon Aether."
                        )
                    } else state
                }
            } else {
                maybeArmWakeWord()
            }
        }
    }

    fun updatePreferences(transform: (AssistantPreferences) -> AssistantPreferences) {
        val updated = transform(_uiState.value.preferences)
        vaultRepository.savePreferences(updated)
        syncVoiceHandsFreeConfig(updated)
        _uiState.update { state ->
            state.copy(
                preferences = updated,
                isGeminiKeyConfigured = updated.geminiApiKey.trim().length >= 16,
                isNvidiaConfigured = nvidiaService.isConfigured(),
                isVaultUnlocked = if (!updated.biometricVaultLockEnabled) true else state.isVaultUnlocked
            )
        }
        maybeArmWakeWord()
    }

    private fun recordTokensSaved(delta: Int, sceneBadge: String = "") {
        if (delta <= 0 && sceneBadge.isBlank()) return
        val safeDelta = delta.coerceAtLeast(0).toLong()
        val currentPrefs = _uiState.value.preferences
        val updatedPrefs = if (safeDelta > 0L) {
            currentPrefs.copy(
                estimatedTokensSavedTotal = currentPrefs.estimatedTokensSavedTotal + safeDelta
            ).also { vaultRepository.savePreferences(it) }
        } else {
            currentPrefs
        }
        _uiState.update { state ->
            state.copy(
                preferences = updatedPrefs,
                estimatedTokensSavedSession = state.estimatedTokensSavedSession + safeDelta,
                offlineVisionSummaryLabel = sceneBadge.ifBlank { state.offlineVisionSummaryLabel }
            )
        }
    }

    fun unlockVault() {
        _uiState.update { it.copy(isVaultUnlocked = true) }
    }

    fun lockVault() {
        if (_uiState.value.preferences.biometricVaultLockEnabled) {
            _uiState.update { it.copy(isVaultUnlocked = false) }
        }
    }

    fun saveApiSettings(
        nvidiaKey: String,
        nvidiaModel: String,
        preferNvidiaPrimary: Boolean,
        geminiKey: String,
        geminiModel: String
    ) {
        val cleanNvidiaKey = nvidiaKey.trim()
        val cleanNvidiaModel = nvidiaModel.trim().ifBlank { AssistantPreferences.DEFAULT_NVIDIA_MODEL_ID }
        val cleanGeminiKey = geminiKey.trim()
        val cleanGeminiModel = geminiModel.trim().ifBlank { AssistantPreferences.DEFAULT_GEMINI_MODEL_ID }
        updatePreferences {
            it.copy(
                nvidiaApiKey = cleanNvidiaKey,
                nvidiaModel = cleanNvidiaModel,
                preferNvidiaPrimary = preferNvidiaPrimary,
                geminiApiKey = cleanGeminiKey,
                geminiModel = cleanGeminiModel
            )
        }
        val nvidiaOk = nvidiaService.isConfigured()
        val geminiKeyOk = cleanGeminiKey.length >= 16
        _uiState.update { state ->
            state.copy(
                isNvidiaConfigured = nvidiaOk,
                isGeminiKeyConfigured = geminiKeyOk,
                coreMode = if ((nvidiaOk || geminiKeyOk) && state.coreMode == CoreMode.ERROR) CoreMode.IDLE else state.coreMode,
                errorMessage = if (nvidiaOk || geminiKeyOk) null else state.errorMessage,
                latestReply = when {
                    preferNvidiaPrimary && nvidiaOk -> "Primary: NVIDIA ($cleanNvidiaModel) · Gemini ready as fallback."
                    geminiKeyOk -> "Primary: Custom Gemini Key ($cleanGeminiModel) · Firebase + NVIDIA fallback ready."
                    nvidiaOk -> "Gemini primary · NVIDIA ($cleanNvidiaModel) fallback ready."
                    else -> state.latestReply
                }
            )
        }
    }

    fun saveNvidiaSettings(apiKey: String, model: String, preferPrimary: Boolean) {
        val prefs = _uiState.value.preferences
        saveApiSettings(
            nvidiaKey = apiKey,
            nvidiaModel = model,
            preferNvidiaPrimary = preferPrimary,
            geminiKey = prefs.geminiApiKey,
            geminiModel = prefs.geminiModel
        )
    }

    suspend fun fetchLiveNvidiaModels(overrideKey: String? = null): Result<List<String>> {
        return nvidiaService.fetchLiveChatModels(overrideKey)
    }

    fun selectPersona(target: AssistantPersona) {
        updatePreferences { it.copy(persona = target) }
    }

    fun cyclePersona() {
        val entries = AssistantPersona.entries
        val current = _uiState.value.preferences.persona
        val next = entries[(entries.indexOf(current) + 1) % entries.size]
        updatePreferences { it.copy(persona = next) }
    }

    fun setBackgroundServiceState(active: Boolean, paused: Boolean = false) {
        _uiState.update {
            it.copy(backgroundServiceActive = active, backgroundServicePaused = paused)
        }
        if (active && !paused) {
            ensureBackgroundStandbyListening()
        } else if (paused) {
            voiceManager.stopWakeWordListening()
        }
    }

    private fun resumeBackgroundCaptureIfNeeded() {
        val state = _uiState.value
        if (state.backgroundServiceActive && !state.backgroundServicePaused) {
            ensureBackgroundStandbyListening()
        } else {
            maybeArmWakeWord()
        }
    }

    fun startFilteredBackgroundCapture() {
        val state = _uiState.value
        if (!state.preferences.filteredMicEnabled) return
        if (!filteredMicEngine.hasRecordPermission()) {
            _uiState.update {
                it.copy(errorMessage = "Microphone permission is required for background audio filtering.")
            }
            return
        }
        voiceManager.stopWakeWordListening()
        filteredMicEngine.startCapture(
            continuousMode = true,
            isEchoGuardSuppressed = {
                voiceManager.isEchoGuardActiveNow() ||
                    _uiState.value.coreMode == CoreMode.SPEAKING ||
                    _uiState.value.coreMode == CoreMode.THINKING
            },
            onAmplitude = { amp ->
                if (_uiState.value.coreMode != CoreMode.SPEAKING) {
                    _audioAmplitude.value = amp
                }
            },
            onSpeechSegmentReady = { segment ->
                submitFilteredAudioCommand(segment, sourceMode = "FILTERED_BG_AUDIO")
            },
            onCaptureError = { errMsg ->
                _uiState.update {
                    it.copy(
                        coreMode = CoreMode.ERROR,
                        errorMessage = errMsg
                    )
                }
            }
        )
    }

    fun stopFilteredBackgroundCapture() {
        filteredMicEngine.stopCapture()
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

        // Release any active passive wake listener or background capture first so foreground voice never hits mic contention
        suppressPassiveWakeUntilMs = android.os.SystemClock.elapsedRealtime() + 4_500L
        voiceManager.stopWakeWordListening()
        if (filteredMicEngine.isCapturing) {
            filteredMicEngine.stopCapture()
        }

        val prefs = _uiState.value.preferences
        isForegroundListeningSession = true
        usedFilteredFallbackThisTurn = false

        val shouldUseFilteredDirectly = prefs.filteredMicEnabled &&
            (!prefs.preferNvidiaPrimary || !voiceManager.isSpeechRecognitionAvailable())

        if (shouldUseFilteredDirectly) {
            usedFilteredFallbackThisTurn = true
            startFilteredForegroundCapture(initialBanner)
            return
        }

        _uiState.update {
            it.copy(
                coreMode = CoreMode.LISTENING,
                partialTranscript = initialBanner.ifBlank { "Listening..." },
                errorMessage = null,
                isWakeWordArmed = false
            )
        }
        voiceManager.startListening(prefs.speechLocale)
    }

    private fun startFilteredForegroundCapture(initialBanner: String = "") {
        _uiState.update {
            it.copy(
                coreMode = CoreMode.LISTENING,
                partialTranscript = initialBanner.ifBlank { "Filtered Voice Capture (AEC+NS+DSP)..." },
                errorMessage = null,
                isWakeWordArmed = false
            )
        }
        voiceManager.stopWakeWordListening()
        voiceManager.stopSpeaking()
        filteredMicEngine.startCapture(
            continuousMode = false,
            isEchoGuardSuppressed = { voiceManager.isEchoGuardActiveNow() },
            onAmplitude = { amp -> _audioAmplitude.value = amp },
            onSpeechSegmentReady = { segment ->
                isForegroundListeningSession = false
                submitFilteredAudioCommand(
                    segment = segment,
                    sourceMode = if (_uiState.value.backgroundServiceActive) "FILTERED_BG_AUDIO" else "FILTERED_FG_AUDIO"
                )
            },
            onCaptureError = { errMsg ->
                isForegroundListeningSession = false
                _uiState.update {
                    it.copy(
                        coreMode = CoreMode.ERROR,
                        partialTranscript = "",
                        errorMessage = errMsg
                    )
                }
                scope.launch {
                    delay(4_000L)
                    _uiState.update {
                        if (it.coreMode == CoreMode.ERROR) it.copy(coreMode = CoreMode.IDLE) else it
                    }
                    resumeBackgroundCaptureIfNeeded()
                }
            }
        )
    }

    fun stopListening() {
        isForegroundListeningSession = false
        usedFilteredFallbackThisTurn = false
        filteredMicEngine.stopCapture()
        voiceManager.stopListening()
        _uiState.update {
            if (it.coreMode == CoreMode.LISTENING) it.copy(coreMode = CoreMode.IDLE, partialTranscript = "") else it
        }
        resumeBackgroundCaptureIfNeeded()
    }

    fun abortAllTasks() {
        activeExecutionJob?.cancel()
        activeExecutionJob = null
        stopLiveDuplexMode()
        filteredMicEngine.stopCapture()
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
                selectVoicePack(packId)
            }
        }
    }

    fun selectVoicePack(packId: String, targetPersona: AssistantPersona? = null) {
        val isBangla = packId.startsWith("bn", ignoreCase = true)
        val persona = targetPersona ?: _uiState.value.preferences.persona
        updatePreferences { prefs ->
            when {
                isBangla -> prefs.copy(selectedBanglaVoiceId = packId, voiceEngineMode = "PIPER")
                persona == AssistantPersona.AETHER -> prefs.copy(aetherVoiceId = packId, selectedEnglishVoiceId = packId, voiceEngineMode = "PIPER")
                else -> prefs.copy(ariaVoiceId = packId, selectedEnglishVoiceId = packId, voiceEngineMode = "PIPER")
            }
        }
    }

    fun importAetherVoiceSample(uri: Uri) {
        scope.launch {
            _uiState.update { it.copy(latestReply = "Analyzing your voice sample for Aether...") }
            val res = VoiceSampleAnalyzer.importVoiceSampleFromUri(appContext, uri)
            res.onSuccess { imported ->
                updatePreferences { prefs ->
                    prefs.copy(
                        persona = AssistantPersona.AETHER,
                        customAetherWavPath = imported.savedPath,
                        customAetherPitchFactor = imported.analysis.calibratedPitchFactor,
                        customAetherSampleInfo = imported.analysis.summaryLabel
                    )
                }
                _uiState.update {
                    it.copy(latestReply = "Imported voice sample '${imported.fileName}'. ${imported.analysis.summaryLabel}")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(errorMessage = err.localizedMessage ?: "Failed importing voice sample.")
                }
            }
        }
    }

    fun previewImportedAetherSample() {
        val path = _uiState.value.preferences.customAetherWavPath
        if (path.isBlank()) return
        scope.launch {
            val played = VoiceSampleAnalyzer.playWavPreview(path)
            if (!played) {
                testVoiceSample(_uiState.value.preferences.aetherVoiceId)
            }
        }
    }

    fun importCustomOnnxModel(uri: Uri) {
        scope.launch {
            val res = piperManager.importCustomOnnxFromUri(uri)
            res.onSuccess { msg ->
                updatePreferences { prefs ->
                    val ready = piperManager.isPackInstalled(
                        piperManager.catalog.first { it.id == CUSTOM_AETHER_VOICE_ID }
                    )
                    if (ready) {
                        prefs.copy(
                            persona = AssistantPersona.AETHER,
                            aetherVoiceId = CUSTOM_AETHER_VOICE_ID,
                            voiceEngineMode = "PIPER"
                        )
                    } else prefs
                }
                _uiState.update { it.copy(latestReply = msg, errorMessage = null) }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "Custom ONNX import failed.") }
            }
        }
    }

    fun deleteVoicePack(packId: String) {
        piperManager.deletePack(packId)
    }

    fun testVoiceSample(packId: String? = null) {
        val prefs = _uiState.value.preferences
        val isBanglaPack = packId?.startsWith("bn", ignoreCase = true) == true
        val samplePrefs = if (packId != null) {
            when {
                isBanglaPack -> prefs.copy(selectedBanglaVoiceId = packId, voiceEngineMode = "PIPER")
                prefs.persona == AssistantPersona.AETHER -> prefs.copy(aetherVoiceId = packId, selectedEnglishVoiceId = packId, voiceEngineMode = "PIPER")
                else -> prefs.copy(ariaVoiceId = packId, selectedEnglishVoiceId = packId, voiceEngineMode = "PIPER")
            }
        } else {
            prefs
        }
        syncVoiceHandsFreeConfig(samplePrefs)
        val useBanglaSample = isBanglaPack || (packId == null && prefs.speechLocale == com.example.ai.SpeechLocaleOption.BN_BD)
        val sampleLocale = if (useBanglaSample) com.example.ai.SpeechLocaleOption.BN_BD else com.example.ai.SpeechLocaleOption.EN_US
        val sampleText = if (useBanglaSample) {
            "হ্যালো! আমি ${samplePrefs.persona.displayName}, এখন আপনার ভয়েস সম্পূর্ণ প্রস্তুত।"
        } else {
            "Hello! This is ${samplePrefs.persona.displayName} speaking clearly with fast neural voice synthesis."
        }
        voiceManager.speak(sampleText, samplePrefs.persona, sampleLocale)
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
            val briefingPrompt = if (reminderTitleOverride.isNotBlank()) "Reminder Alert: $reminderTitleOverride" else "Daily Briefing"
            withContext(Dispatchers.IO) {
                vaultRepository.insertLog(
                    CommandLogEntity(
                        userPrompt = briefingPrompt,
                        assistantReply = summaryText,
                        personaUsed = prefs.persona.displayName,
                        sourceMode = "BACKGROUND_BRIEFING",
                        hadScreenVision = false,
                        tasksSummary = "Briefing: DONE",
                        executionStatus = "EXECUTED"
                    )
                )
            }
            recordConversationTurn(briefingPrompt, summaryText)
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
        resumeBackgroundCaptureIfNeeded()
    }

    fun submitFilteredAudioCommand(
        segment: FilteredSpeechSegment,
        sourceMode: String = "FILTERED_AUDIO"
    ) {
        if (segment.wavBytes.isEmpty()) return
        activeExecutionJob?.cancel()
        voiceManager.stopWakeWordListening()
        voiceManager.stopSpeaking()

        val durSec = String.format(java.util.Locale.US, "%.1fs", segment.durationMs / 1000f)
        _uiState.update {
            it.copy(
                coreMode = CoreMode.THINKING,
                lastUserPrompt = "Voice Audio ($durSec · ${segment.diagnostics.effects.summaryBadge()})",
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
                val contextBundle = withContext(Dispatchers.IO) {
                    ensureConversationMemoryReady()
                    coroutineScope {
                        val routinesDeferred = async { vaultRepository.getRoutinesSnapshot() }
                        val memoriesDeferred = async { vaultRepository.getTopMemories() }
                        val contactsDeferred = async { vaultRepository.getContactsSnapshot() }
                        val appsDeferred = async { telemetryProvider.queryLauncherAppNames(120) }
                        val permsDeferred = async { permissionsManager.checkSnapshot() }
                        val telemetryDeferred = async { telemetryProvider.readTelemetry() }
                        val telemetry = telemetryDeferred.await()

                        ContextSnapshotBundle(
                            resolvedPrompt = "Voice command audio ($durSec)",
                            memoriesText = memoriesDeferred.await().joinToString("\n") { "- [${it.category}] ${it.content}" },
                            routinesText = routinesDeferred.await().joinToString("\n") { "- ${it.triggerPhrase}: ${it.instructionPrompt}" },
                            contactsText = contactsDeferred.await().joinToString("\n") {
                                "- ${it.aliasName} (${it.displayName}): phone=${it.phoneNumber}, email=${it.emailAddress}"
                            },
                            appsText = appsDeferred.await(),
                            historyText = buildConversationHistorySummary(),
                            historyPairs = buildConversationHistoryPairs(),
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

                val planResult = geminiService.planAudioCommand(
                    wavAudioBytes = segment.wavBytes,
                    persona = prefs.persona,
                    deviceContextSummary = contextBundle.telemetryText,
                    memoriesSummary = contextBundle.memoriesText,
                    routinesSummary = contextBundle.routinesText,
                    contactsSummary = contextBundle.contactsText,
                    installedAppsSummary = contextBundle.appsText,
                    conversationHistorySummary = contextBundle.historyText,
                    conversationTurns = contextBundle.historyPairs,
                    preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                    onStreamingReply = { partial ->
                        _uiState.update { it.copy(streamingReply = partial) }
                    },
                    onActiveModelChanged = { label ->
                        _uiState.update { it.copy(activeAiModelLabel = label) }
                    }
                )

                val plan = planResult.getOrElse { err ->
                    _uiState.update {
                        it.copy(
                            coreMode = CoreMode.ERROR,
                            streamingReply = "",
                            errorMessage = err.localizedMessage ?: "Filtered audio AI request failed."
                        )
                    }
                    resumeBackgroundCaptureIfNeeded()
                    return@launch
                }

                val heardText = plan.recognizedTranscript.ifBlank {
                    plan.steps.firstOrNull()?.label?.takeIf { it.isNotBlank() && it != "Respond" }
                        ?: "Filtered Voice ($durSec)"
                }
                if (plan.recognizedTranscript.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        val currentMems = vaultRepository.getTopMemories()
                        autoExtractAndPersistUserFacts(plan.recognizedTranscript, currentMems)
                    }
                }
                _uiState.update { it.copy(lastUserPrompt = heardText) }

                executePlanAndFollowUp(
                    userPrompt = heardText,
                    plan = plan,
                    contextBundle = contextBundle,
                    sourceMode = sourceMode,
                    hadScreenVision = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Filtered audio command execution error", e)
                _uiState.update {
                    it.copy(
                        coreMode = CoreMode.ERROR,
                        streamingReply = "",
                        errorMessage = e.localizedMessage ?: "Audio execution error"
                    )
                }
                resumeBackgroundCaptureIfNeeded()
            }
        }
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

                // Offline zero-cost deterministic router runs early so instant toggles don't hit playbooks
                val quickOfflineCheck = if (attachedImage == null) {
                    withContext(Dispatchers.IO) {
                        OfflineCommandRouter.tryMatch(effectivePrompt) {
                            telemetryProvider.queryLauncherAppMap()
                        }
                    }
                } else null
                if (quickOfflineCheck != null) {
                    _uiState.update { it.copy(activeAiModelLabel = quickOfflineCheck.label) }
                    val quickBundle = withContext(Dispatchers.IO) {
                        ensureConversationMemoryReady()
                        ContextSnapshotBundle(
                            resolvedPrompt = effectivePrompt,
                            memoriesText = "",
                            routinesText = "",
                            contactsText = "",
                            appsText = "",
                            historyText = buildConversationHistorySummary(),
                            historyPairs = buildConversationHistoryPairs(),
                            telemetryText = telemetryProvider.buildPromptTelemetrySummary(telemetryProvider.readTelemetry()),
                            perms = permissionsManager.checkSnapshot(),
                            telemetry = telemetryProvider.readTelemetry()
                        )
                    }
                    executePlanAndFollowUp(
                        userPrompt = effectivePrompt,
                        plan = quickOfflineCheck.plan,
                        contextBundle = quickBundle,
                        sourceMode = "${sourceMode}_OFFLINE",
                        hadScreenVision = false
                    )
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
                    ensureConversationMemoryReady()
                    coroutineScope {
                        val routinesDeferred = async { vaultRepository.getRoutinesSnapshot() }
                        val memoriesDeferred = async { vaultRepository.getTopMemories() }
                        val contactsDeferred = async { vaultRepository.getContactsSnapshot() }
                        val appsDeferred = async { telemetryProvider.queryLauncherAppNames(120) }
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
                        val existingMemories = memoriesDeferred.await()
                        val updatedMemories = autoExtractAndPersistUserFacts(effectivePrompt, existingMemories)
                        val telemetry = telemetryDeferred.await()

                        ContextSnapshotBundle(
                            resolvedPrompt = resolved,
                            memoriesText = updatedMemories.joinToString("\n") { "- [${it.category}] ${it.content}" },
                            routinesText = routines.joinToString("\n") { "- ${it.triggerPhrase}: ${it.instructionPrompt}" },
                            contactsText = contactsDeferred.await().joinToString("\n") {
                                "- ${it.aliasName} (${it.displayName}): phone=${it.phoneNumber}, email=${it.emailAddress}"
                            },
                            appsText = appsDeferred.await(),
                            historyText = buildConversationHistorySummary(),
                            historyPairs = buildConversationHistoryPairs(),
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

                // Offline zero-cost deterministic router for local hardware/calls/launches (< 30ms)
                if (attachedImage == null) {
                    val offlineMatch = withContext(Dispatchers.IO) {
                        OfflineCommandRouter.tryMatch(contextBundle.resolvedPrompt) {
                            telemetryProvider.queryLauncherAppMap()
                        }
                    }
                    if (offlineMatch != null) {
                        _uiState.update { it.copy(activeAiModelLabel = offlineMatch.label) }
                        executePlanAndFollowUp(
                            userPrompt = effectivePrompt,
                            plan = offlineMatch.plan,
                            contextBundle = contextBundle,
                            sourceMode = "${sourceMode}_OFFLINE",
                            hadScreenVision = false
                        )
                        return@launch
                    }
                }

                val accessibility = AetherAccessibilityService.currentInstance
                val projectionActive = com.example.vision.MediaProjectionScreenGrabber.isProjectionActive()
                val wantsScreen = attachedImage == null &&
                    (accessibility != null || projectionActive) &&
                    shouldInspectScreenProactively(contextBundle.resolvedPrompt)

                var screenSummary: String? = null
                var ocrSummary: String? = null
                var bitmapToSend: Bitmap? = null
                var ownsBitmapToSend = false
                var usedScreenVision = attachedImage != null

                _uiState.update {
                    it.copy(
                        reActProgressLabel = "",
                        reActSubGoals = emptyList(),
                        reActActiveSubGoalIndex = 0,
                        reActWaitingForLoad = false
                    )
                }

                if (wantsScreen) {
                    _uiState.update {
                        it.copy(
                            coreMode = CoreMode.OBSERVING_SCREEN,
                            lastUserPrompt = effectivePrompt,
                            errorMessage = null
                        )
                    }
                    val snap = accessibility?.captureFullScreenContext(
                        includeBitmap = true,
                        focusHint = contextBundle.resolvedPrompt
                    )
                    val fallbackBmp = if (snap == null && projectionActive) {
                        com.example.vision.MediaProjectionScreenGrabber.captureLatestBitmap()
                    } else null
                    val rawBmp = snap?.screenshot ?: fallbackBmp
                    val sourceTag = snap?.visionSource ?: if (fallbackBmp != null) "MEDIA_PROJECTION" else ""
                    screenSummary = snap?.uiSummary ?: if (fallbackBmp != null) {
                        "Captured via Deep Screen Vision (MediaProjection fallback)"
                    } else null
                    usedScreenVision = snap != null || fallbackBmp != null
                    if (rawBmp != null) {
                        val processed = visionProcessor.processScreenForVision(
                            sourceBitmap = rawBmp,
                            prompt = contextBundle.resolvedPrompt,
                            accessibilityNodeCount = snap?.nodeCount ?: 0,
                            forceOcrFallback = prefs.preferNvidiaPrimary || (snap == null),
                            anchorRect = snap?.suggestedAnchorRect,
                            packageName = snap?.packageName.orEmpty(),
                            rawUiSummary = screenSummary.orEmpty(),
                            visionTokenMode = prefs.visionTokenMode
                        )
                        bitmapToSend = processed.croppedBitmap
                        ownsBitmapToSend = processed.ownsCroppedBitmap
                        ocrSummary = processed.ocrSummary.ifBlank { null }
                        processed.offlineAnalysis?.let { offline ->
                            screenSummary = buildString {
                                appendLine(offline.buildCompactVisionHeader())
                                val body = offline.compactUiSummary.ifBlank { screenSummary.orEmpty() }
                                if (body.isNotBlank()) append(body)
                            }.trim()
                        }
                        if (ownsBitmapToSend || bitmapToSend == null) {
                            runCatching { rawBmp.recycle() }
                        }
                        recordTokensSaved(
                            delta = processed.tokensSaved,
                            sceneBadge = processed.offlineAnalysis?.summaryBadge.orEmpty()
                        )
                        val badge = processed.offlineAnalysis?.summaryBadge?.ifBlank { null }
                            ?: buildString {
                                append(processed.regionLabel)
                                if (processed.areaReductionPercent > 0) append(" (-${processed.areaReductionPercent}% px)")
                                if (processed.ocrLineCount > 0) append(" · OCR ${processed.ocrLineCount} lines")
                            }
                        _uiState.update {
                            it.copy(
                                visionInsightLabel = badge,
                                screenVisionSourceLabel = if (bitmapToSend == null) "OFFLINE (<50MB)" else sourceTag
                            )
                        }
                    } else if (sourceTag.isNotBlank()) {
                        val processedUiOnly = visionProcessor.processScreenForVision(
                            sourceBitmap = null,
                            prompt = contextBundle.resolvedPrompt,
                            accessibilityNodeCount = snap?.nodeCount ?: 0,
                            forceOcrFallback = false,
                            packageName = snap?.packageName.orEmpty(),
                            rawUiSummary = screenSummary.orEmpty(),
                            visionTokenMode = prefs.visionTokenMode
                        )
                        processedUiOnly.offlineAnalysis?.let { offline ->
                            screenSummary = buildString {
                                appendLine(offline.buildCompactVisionHeader())
                                append(offline.compactUiSummary.ifBlank { screenSummary.orEmpty() })
                            }.trim()
                        }
                        recordTokensSaved(
                            delta = processedUiOnly.tokensSaved,
                            sceneBadge = processedUiOnly.offlineAnalysis?.summaryBadge.orEmpty()
                        )
                        _uiState.update { it.copy(screenVisionSourceLabel = sourceTag) }
                    }
                } else if (attachedImage != null) {
                    val processed = visionProcessor.processScreenForVision(
                        sourceBitmap = attachedImage,
                        prompt = contextBundle.resolvedPrompt,
                        accessibilityNodeCount = 0,
                        forceOcrFallback = true,
                        visionTokenMode = com.example.data.local.VisionTokenMode.CLOUD_VISION
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

                // Inject recent System AssistStructure context when Accessibility overlay didn't produce a screen summary
                if (screenSummary.isNullOrBlank() &&
                    lastSystemAssistHint.isNotBlank() &&
                    (android.os.SystemClock.elapsedRealtime() - lastSystemAssistAtMs) <= 25_000L
                ) {
                    screenSummary = lastSystemAssistHint
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
                    conversationHistorySummary = contextBundle.historyText,
                    conversationTurns = contextBundle.historyPairs,
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
                            anchorRect = snap.suggestedAnchorRect,
                            packageName = snap.packageName,
                            rawUiSummary = snap.uiSummary,
                            visionTokenMode = prefs.visionTokenMode
                        )
                        val retryBmp = processed.croppedBitmap
                        val compactScreenSummary = buildString {
                            processed.offlineAnalysis?.buildCompactVisionHeader()?.let { appendLine(it) }
                            append(processed.offlineAnalysis?.compactUiSummary?.ifBlank { snap.uiSummary } ?: snap.uiSummary)
                        }.trim()
                        recordTokensSaved(
                            delta = processed.tokensSaved,
                            sceneBadge = processed.offlineAnalysis?.summaryBadge.orEmpty()
                        )
                        val badge = processed.offlineAnalysis?.summaryBadge?.ifBlank { null }
                            ?: buildString {
                                append(processed.regionLabel)
                                if (processed.areaReductionPercent > 0) append(" (-${processed.areaReductionPercent}% px)")
                                if (processed.ocrLineCount > 0) append(" · OCR ${processed.ocrLineCount} lines")
                            }
                        _uiState.update {
                            it.copy(
                                coreMode = CoreMode.THINKING,
                                visionInsightLabel = badge,
                                screenVisionSourceLabel = if (retryBmp == null) "OFFLINE (<50MB)" else snap.visionSource
                            )
                        }
                        planResult = geminiService.planCommand(
                            userPrompt = contextBundle.resolvedPrompt,
                            persona = prefs.persona,
                            deviceContextSummary = contextBundle.telemetryText,
                            memoriesSummary = contextBundle.memoriesText,
                            routinesSummary = contextBundle.routinesText,
                            contactsSummary = contextBundle.contactsText,
                            installedAppsSummary = contextBundle.appsText,
                            screenUiSummary = compactScreenSummary,
                            screenBitmap = retryBmp,
                            ocrSummary = processed.ocrSummary.ifBlank { null },
                            conversationHistorySummary = contextBundle.historyText,
                            conversationTurns = contextBundle.historyPairs,
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

                executePlanAndFollowUp(
                    userPrompt = effectivePrompt,
                    plan = plan,
                    contextBundle = contextBundle,
                    sourceMode = sourceMode,
                    hadScreenVision = usedScreenVision
                )
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

    private suspend fun executePlanAndFollowUp(
        userPrompt: String,
        plan: com.example.ai.CommandPlan,
        contextBundle: ContextSnapshotBundle,
        sourceMode: String,
        hadScreenVision: Boolean
    ) {
        val prefs = _uiState.value.preferences
        val accessibility = AetherAccessibilityService.currentInstance
        val normalizedSteps = normalizePlanSteps(plan.steps, hasAccessibility = accessibility != null)
        val hasDedicatedSocialOrMessage = normalizedSteps.any {
            it.actionType == ActionType.POST_SOCIAL || it.actionType == ActionType.SEND_APP_MESSAGE
        }

        // Multi-Hop ReAct Workflow (e.g. YouTube channel -> Videos -> first video, multi-screen nav)
        // Run any non-UI prerequisite steps through the unified DAG first instead of dropping them!
        val isMultiHop = accessibility != null &&
            !hasDedicatedSocialOrMessage &&
            MultiStepGoalClassifier.isMultiHopAppWorkflow(userPrompt, plan)
        if (isMultiHop) {
            val prereqSteps = normalizedSteps.filter { step ->
                step.actionType != ActionType.LAUNCH_APP &&
                    step.actionType != ActionType.OPEN_SETTINGS &&
                    step.actionType != ActionType.SEARCH_IN_APP &&
                    step.actionType != ActionType.UI_TAP_TEXT &&
                    step.actionType != ActionType.UI_TAP_COORDINATES &&
                    step.actionType != ActionType.UI_INPUT_TEXT &&
                    step.actionType != ActionType.UI_SCROLL &&
                    step.actionType != ActionType.GLOBAL_ACTION &&
                    step.actionType != ActionType.SPEAK_ONLY
            }
            if (prereqSteps.isNotEmpty()) {
                executeStepsPipeline(
                    userPrompt = userPrompt,
                    initialReply = plan.reply,
                    steps = prereqSteps,
                    bypassConfirmation = false,
                    sourceMode = sourceMode,
                    hadScreenVision = hadScreenVision,
                    deferFinalSpeech = true,
                    contextBundle = contextBundle
                )
            }

            val taskId = "react_workflow_0"
            val checklist = MultiStepGoalClassifier.deriveSubGoals(userPrompt, plan)
            val reactPlaceholderStep = normalizedSteps.firstOrNull()
                ?: PlannedStep(ActionType.LAUNCH_APP, "Multi-Step Goal", target = "")
            _uiState.update {
                it.copy(
                    coreMode = CoreMode.OBSERVING_SCREEN,
                    streamingReply = "",
                    latestReply = "Starting multi-step workflow (${checklist.size} steps)...",
                    reActSubGoals = checklist,
                    reActActiveSubGoalIndex = 0,
                    reActProgressLabel = "Preparing ${checklist.firstOrNull() ?: "workflow"}...",
                    reActWaitingForLoad = true,
                    activeTasks = it.activeTasks + ActiveTaskItem(
                        id = taskId,
                        step = reactPlaceholderStep,
                        status = TaskStatus.RUNNING,
                        detail = checklist.firstOrNull() ?: "Running..."
                    )
                )
            }
            val startMs = System.currentTimeMillis()
            val reactSummary = screenAutomationAgent.executeUnifiedReActGoal(
                userPrompt = contextBundle.resolvedPrompt,
                initialPlan = plan,
                persona = prefs.persona,
                deviceContextSummary = contextBundle.telemetryText,
                memoriesSummary = contextBundle.memoriesText,
                routinesSummary = contextBundle.routinesText,
                contactsSummary = contextBundle.contactsText,
                installedAppsSummary = contextBundle.appsText,
                preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                splitScreenEnabled = prefs.splitScreenLaunchEnabled,
                visionTokenMode = prefs.visionTokenMode,
                onReActProgress = { progress ->
                    updateTaskStatus(
                        taskId = taskId,
                        status = TaskStatus.RUNNING,
                        detail = progress.statusLabel,
                        elapsedMs = System.currentTimeMillis() - startMs,
                        timedOut = false
                    )
                    if (progress.tokensSavedDelta > 0 || progress.offlineSceneBadge.isNotBlank()) {
                        recordTokensSaved(progress.tokensSavedDelta, progress.offlineSceneBadge)
                    }
                    _uiState.update {
                        it.copy(
                            coreMode = if (progress.isWaitingForLoad) CoreMode.OBSERVING_SCREEN else CoreMode.MULTITASKING,
                            reActProgressLabel = progress.statusLabel,
                            reActSubGoals = progress.subGoals,
                            reActActiveSubGoalIndex = progress.activeSubGoalIndex,
                            reActWaitingForLoad = progress.isWaitingForLoad,
                            screenVisionSourceLabel = progress.visionSource,
                            latestReply = progress.statusLabel
                        )
                    }
                },
                onStepProgress = { _ -> },
                onStreamingReply = { partial -> _uiState.update { it.copy(streamingReply = partial) } },
                onActiveModelChanged = { label -> _uiState.update { it.copy(activeAiModelLabel = label) } }
            )
            val reactDurationMs = System.currentTimeMillis() - startMs
            distillAndPersistPlaybook(
                userPrompt = userPrompt,
                steps = normalizedSteps,
                durationMs = reactDurationMs
            )
            updateTaskStatus(
                taskId = taskId,
                status = TaskStatus.DONE,
                detail = reactSummary,
                elapsedMs = reactDurationMs,
                timedOut = false
            )
            withContext(Dispatchers.IO) {
                vaultRepository.insertLog(
                    CommandLogEntity(
                        userPrompt = userPrompt,
                        assistantReply = reactSummary,
                        personaUsed = prefs.persona.displayName,
                        sourceMode = sourceMode,
                        hadScreenVision = true,
                        tasksSummary = "ReAct Workflow: DONE",
                        executionStatus = "EXECUTED"
                    )
                )
            }
            recordConversationTurn(userPrompt, reactSummary)
            _uiState.update {
                it.copy(
                    streamingReply = "",
                    latestReply = reactSummary,
                    reActWaitingForLoad = false,
                    reActProgressLabel = "Completed",
                    reActActiveSubGoalIndex = (checklist.size - 1).coerceAtLeast(0),
                    coreMode = if (prefs.ttsEnabled) CoreMode.SPEAKING else CoreMode.IDLE
                )
            }
            if (prefs.ttsEnabled) {
                voiceManager.speak(reactSummary, prefs.persona, prefs.speechLocale)
            } else {
                maybeArmWakeWord()
                resumeBackgroundCaptureIfNeeded()
            }
            return
        }

        // Check self-learned App Playbook before heavy multi-screen social/media runs
        if (accessibility != null && normalizedSteps.any {
                it.actionType == ActionType.POST_SOCIAL || it.actionType == ActionType.LAUNCH_APP
            }
        ) {
            val playbooks = withContext(Dispatchers.IO) { vaultRepository.getPlaybooksSnapshot() }
            val matchedPlaybook = com.example.automation.PlaybookLearningEngine.findMatchingPlaybook(
                userPrompt = userPrompt,
                playbooks = playbooks
            )
            if (matchedPlaybook != null) {
                val pbSteps = com.example.automation.PlaybookLearningEngine.compilePlaybookToPlannedSteps(matchedPlaybook)
                if (pbSteps.isNotEmpty()) {
                    _uiState.update {
                        it.copy(matchedPlaybookLabel = "Playbook · ${matchedPlaybook.playbook.appLabel}")
                    }
                    val pbStart = System.currentTimeMillis()
                    executeStepsPipeline(
                        userPrompt = userPrompt,
                        initialReply = "Running learned playbook for ${matchedPlaybook.playbook.appLabel}...",
                        steps = pbSteps,
                        bypassConfirmation = true,
                        sourceMode = "PLAYBOOK_REPLAY",
                        hadScreenVision = false,
                        deferFinalSpeech = false,
                        contextBundle = contextBundle
                    )
                    val pbFailed = _uiState.value.activeTasks.any {
                        it.status == TaskStatus.FAILED || it.status == TaskStatus.TIMED_OUT
                    }
                    withContext(Dispatchers.IO) {
                        if (pbFailed) {
                            vaultRepository.recordPlaybookFailure(matchedPlaybook.playbook.id)
                        } else {
                            vaultRepository.recordPlaybookSuccess(
                                matchedPlaybook.playbook.id,
                                (System.currentTimeMillis() - pbStart).coerceAtLeast(150L)
                            )
                        }
                    }
                    if (!pbFailed) return
                }
            }
        }

        // Unified Chained Cross-App DAG Execution:
        // Runs background tools, SEND_APP_MESSAGE, POST_SOCIAL, YouTube PLAY_MEDIA, and UI steps together in one topological DAG.
        val willContinueLoop = plan.continueLoopAfterAction && accessibility != null
        val primaryAiReply = plan.reply.trim()
        executeStepsPipeline(
            userPrompt = userPrompt,
            initialReply = plan.reply,
            steps = normalizedSteps,
            bypassConfirmation = false,
            sourceMode = sourceMode,
            hadScreenVision = hadScreenVision,
            deferFinalSpeech = willContinueLoop,
            contextBundle = contextBundle
        )

        if (willContinueLoop) {
            _uiState.update { it.copy(coreMode = CoreMode.OBSERVING_SCREEN) }
            val loopSummary = screenAutomationAgent.runAutonomousScreenLoop(
                userPrompt = contextBundle.resolvedPrompt,
                persona = prefs.persona,
                deviceContextSummary = contextBundle.telemetryText,
                memoriesSummary = contextBundle.memoriesText,
                routinesSummary = contextBundle.routinesText,
                contactsSummary = contextBundle.contactsText,
                installedAppsSummary = contextBundle.appsText,
                preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                splitScreenEnabled = prefs.splitScreenLaunchEnabled,
                onStepProgress = { _ -> },
                onStreamingReply = {},
                onActiveModelChanged = { label ->
                    _uiState.update { it.copy(activeAiModelLabel = label) }
                }
            )
            val finalMessage = if (isExplicitScreenQuery(userPrompt)) {
                loopSummary?.ifBlank { null } ?: primaryAiReply.ifBlank { "Done." }
            } else {
                primaryAiReply.ifBlank { loopSummary?.ifBlank { null } ?: "Done." }
            }
            withContext(Dispatchers.IO) {
                vaultRepository.insertLog(
                    CommandLogEntity(
                        userPrompt = userPrompt,
                        assistantReply = finalMessage,
                        personaUsed = prefs.persona.displayName,
                        sourceMode = sourceMode,
                        hadScreenVision = true,
                        tasksSummary = _uiState.value.activeTasks.joinToString(" | ") { "${it.step.label}: ${it.status.name}" },
                        executionStatus = "EXECUTED"
                    )
                )
            }
            recordConversationTurn(userPrompt, finalMessage)
            _uiState.update {
                it.copy(
                    streamingReply = "",
                    latestReply = finalMessage,
                    coreMode = if (prefs.ttsEnabled) CoreMode.SPEAKING else CoreMode.IDLE
                )
            }
            if (prefs.ttsEnabled) {
                voiceManager.speak(finalMessage, prefs.persona, prefs.speechLocale)
            } else {
                maybeArmWakeWord()
                resumeBackgroundCaptureIfNeeded()
            }
        }
    }

    private fun isAppMessagingStep(step: PlannedStep): Boolean {
        if (step.actionType == ActionType.SEND_APP_MESSAGE) return true
        if (step.actionType != ActionType.SEND_MESSAGE) return false
        val appKeywords = listOf("whatsapp", "telegram", "messenger", "instagram", "signal", "viber")
        return appKeywords.any { kw ->
            step.target.contains(kw, ignoreCase = true) ||
                step.label.contains(kw, ignoreCase = true)
        }
    }

    private fun normalizePlanSteps(
        steps: List<PlannedStep>,
        hasAccessibility: Boolean
    ): List<PlannedStep> {
        if (!hasAccessibility) return steps
        return steps.map { step ->
            if (step.actionType != ActionType.SEND_APP_MESSAGE && isAppMessagingStep(step)) {
                step.copy(actionType = ActionType.SEND_APP_MESSAGE, isSensitive = false)
            } else {
                step
            }
        }
    }

    private suspend fun executeStepsPipeline(
        userPrompt: String,
        initialReply: String,
        steps: List<PlannedStep>,
        bypassConfirmation: Boolean,
        sourceMode: String,
        hadScreenVision: Boolean,
        deferFinalSpeech: Boolean = false,
        contextBundle: ContextSnapshotBundle? = null
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

        val lowerPrompt = userPrompt.lowercase()
        val preferBangla = userPrompt.any { it in '\u0980'..'\u09FF' } ||
            listOf("bristi", "abohawa", "ajke", "kemon", "hobe", "koro", "amar", "thakbe", "korbe")
                .any { lowerPrompt.contains(it) }
        val hasAccessibility = AetherAccessibilityService.currentInstance != null
        val isSingleYouTubeWorkflow = !MultiStepGoalClassifier.isMultiHopYouTubeWorkflow(userPrompt, null)
        val accessibilityErrorHint = java.util.concurrent.atomic.AtomicReference<String?>(null)

        val dagSummary = dagExecutor.executeDag(
            nodes = dagNodes,
            onTaskUpdate = { taskId, status, detail, elapsedMs, timedOut ->
                updateTaskStatus(taskId, status, detail, elapsedMs, timedOut)
            },
            onTorchStateChanged = { torchOn ->
                _uiState.update { it.copy(torchEnabled = torchOn) }
            },
            executor = { step ->
                val adjustedStep = if (step.actionType == ActionType.WEATHER_LOOKUP &&
                    step.textPayload.isBlank() &&
                    preferBangla
                ) {
                    step.copy(textPayload = "bn")
                } else {
                    step
                }
                val stepStartMs = System.currentTimeMillis()
                val telemetryText = contextBundle?.telemetryText ?: ""
                val memoriesText = contextBundle?.memoriesText ?: ""
                val routinesText = contextBundle?.routinesText ?: ""
                val contactsText = contextBundle?.contactsText ?: ""
                val appsText = contextBundle?.appsText ?: ""

                when {
                    adjustedStep.actionType == ActionType.SEND_APP_MESSAGE -> {
                        val outcome = screenAutomationAgent.executeAppMessageGoal(
                            step = adjustedStep,
                            originalPrompt = userPrompt,
                            persona = prefs.persona,
                            deviceContextSummary = telemetryText,
                            memoriesSummary = memoriesText,
                            routinesSummary = routinesText,
                            contactsSummary = contactsText,
                            installedAppsSummary = appsText,
                            preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                            splitScreenEnabled = prefs.splitScreenLaunchEnabled,
                            autoSend = prefs.autonomousModeEnabled,
                            onStepProgress = { progressLabel ->
                                updateTaskStatus(
                                    taskId = adjustedStep.stepId,
                                    status = TaskStatus.RUNNING,
                                    detail = progressLabel,
                                    elapsedMs = System.currentTimeMillis() - stepStartMs,
                                    timedOut = false
                                )
                                _uiState.update { it.copy(latestReply = progressLabel) }
                            },
                            onStreamingReply = { partial -> _uiState.update { it.copy(streamingReply = partial) } },
                            onActiveModelChanged = { label -> _uiState.update { it.copy(activeAiModelLabel = label) } }
                        )
                        if (outcome.needsAccessibilityPermission) {
                            accessibilityErrorHint.set(outcome.summary)
                        }
                        com.example.system.ActionExecutionResult(
                            success = outcome.success,
                            detail = outcome.summary,
                            toolOutputForSummary = outcome.summary
                        )
                    }

                    adjustedStep.actionType == ActionType.POST_SOCIAL -> {
                        val outcome = screenAutomationAgent.executeSocialPostGoal(
                            step = adjustedStep,
                            persona = prefs.persona,
                            deviceContextSummary = telemetryText,
                            memoriesSummary = memoriesText,
                            routinesSummary = routinesText,
                            contactsSummary = contactsText,
                            installedAppsSummary = appsText,
                            preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                            splitScreenEnabled = prefs.splitScreenLaunchEnabled,
                            autoPost = prefs.autonomousModeEnabled,
                            onStepProgress = { progressLabel ->
                                updateTaskStatus(
                                    taskId = adjustedStep.stepId,
                                    status = TaskStatus.RUNNING,
                                    detail = progressLabel,
                                    elapsedMs = System.currentTimeMillis() - stepStartMs,
                                    timedOut = false
                                )
                                _uiState.update { it.copy(latestReply = progressLabel) }
                            },
                            onStreamingReply = { partial -> _uiState.update { it.copy(streamingReply = partial) } },
                            onActiveModelChanged = { label -> _uiState.update { it.copy(activeAiModelLabel = label) } }
                        )
                        if (outcome.needsAccessibilityPermission) {
                            accessibilityErrorHint.set(outcome.summary)
                        }
                        com.example.system.ActionExecutionResult(
                            success = outcome.success,
                            detail = outcome.summary,
                            toolOutputForSummary = outcome.summary
                        )
                    }

                    hasAccessibility &&
                        isSingleYouTubeWorkflow &&
                        adjustedStep.actionType == ActionType.PLAY_MEDIA &&
                        (adjustedStep.target.contains("youtube", ignoreCase = true) ||
                            userPrompt.contains("youtube", ignoreCase = true)) -> {
                        val outcome = screenAutomationAgent.executeYouTubePlayGoal(
                            step = adjustedStep,
                            persona = prefs.persona,
                            deviceContextSummary = telemetryText,
                            memoriesSummary = memoriesText,
                            routinesSummary = routinesText,
                            contactsSummary = contactsText,
                            installedAppsSummary = appsText,
                            preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                            splitScreenEnabled = prefs.splitScreenLaunchEnabled,
                            onStepProgress = { progressLabel ->
                                updateTaskStatus(
                                    taskId = adjustedStep.stepId,
                                    status = TaskStatus.RUNNING,
                                    detail = progressLabel,
                                    elapsedMs = System.currentTimeMillis() - stepStartMs,
                                    timedOut = false
                                )
                                _uiState.update { it.copy(latestReply = progressLabel) }
                            },
                            onStreamingReply = { partial -> _uiState.update { it.copy(streamingReply = partial) } },
                            onActiveModelChanged = { label -> _uiState.update { it.copy(activeAiModelLabel = label) } }
                        )
                        com.example.system.ActionExecutionResult(
                            success = outcome.success,
                            detail = outcome.summary,
                            toolOutputForSummary = outcome.summary
                        )
                    }

                    else -> actionRouter.executeStep(adjustedStep, prefs.splitScreenLaunchEnabled)
                }
            }
        )

        val toolOutputs = dagSummary.toolOutputs
        val finalReply = if (toolOutputs.isNotEmpty()) {
            val onlySingleForegroundAgentOutput = dagNodes.size == 1 && toolOutputs.size == 1
            val onlyBackgroundOutputs = dagNodes.none {
                !it.step.isBackgroundParallel && it.step.actionType != ActionType.SPEAK_ONLY
            }
            if (onlySingleForegroundAgentOutput || (toolOutputs.size == 1 && onlyBackgroundOutputs)) {
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
            resolvePipelineFallbackReply(
                initialReply = initialReply,
                dagNodes = dagNodes,
                timedOutCount = dagSummary.timedOutCount,
                failedCount = dagSummary.failedCount,
                preferBangla = preferBangla,
                webTools = webTools
            )
        }

        val finalTasks = _uiState.value.activeTasks
        val allFailed = finalTasks.isNotEmpty() && finalTasks.all {
            it.status == TaskStatus.FAILED || it.status == TaskStatus.TIMED_OUT || it.status == TaskStatus.SKIPPED
        }
        val summaryStr = finalTasks.joinToString(" | ") { "${it.step.label}: ${it.status.name}" }
        val usedUiAgent = hadScreenVision || dagNodes.any {
            it.step.actionType == ActionType.POST_SOCIAL ||
                it.step.actionType == ActionType.SEND_APP_MESSAGE ||
                it.step.actionType == ActionType.UI_TAP_TEXT ||
                it.step.actionType == ActionType.UI_TAP_COORDINATES ||
                it.step.actionType == ActionType.UI_INPUT_TEXT
        }

        if (!deferFinalSpeech) {
            withContext(Dispatchers.IO) {
                vaultRepository.insertLog(
                    CommandLogEntity(
                        userPrompt = userPrompt,
                        assistantReply = finalReply,
                        personaUsed = prefs.persona.displayName,
                        sourceMode = sourceMode,
                        hadScreenVision = usedUiAgent,
                        tasksSummary = summaryStr,
                        executionStatus = if (allFailed) "FAILED" else "EXECUTED"
                    )
                )
            }
            recordConversationTurn(userPrompt, finalReply)
        }

        val shouldRequestLens = dagNodes.any { it.step.actionType == ActionType.OPEN_CAMERA_LENS }
        _uiState.update {
            it.copy(
                streamingReply = "",
                latestReply = finalReply,
                errorMessage = accessibilityErrorHint.get(),
                cameraLensRequested = if (shouldRequestLens) true else it.cameraLensRequested,
                coreMode = if (!deferFinalSpeech && prefs.ttsEnabled) CoreMode.SPEAKING else CoreMode.IDLE
            )
        }

        if (!deferFinalSpeech) {
            if (prefs.ttsEnabled) {
                voiceManager.speak(finalReply, prefs.persona, prefs.speechLocale)
            } else {
                maybeArmWakeWord()
                resumeBackgroundCaptureIfNeeded()
            }
        }

        // Persist learned playbook after successful multi-step UI or social execution
        if (!allFailed && sourceMode != "PLAYBOOK_REPLAY" &&
            dagNodes.any { it.step.actionType == ActionType.POST_SOCIAL || it.step.actionType == ActionType.SEND_APP_MESSAGE }
        ) {
            val totalElapsed = finalTasks.sumOf { it.elapsedMs }.coerceAtLeast(800L)
            distillAndPersistPlaybook(
                userPrompt = userPrompt,
                steps = dagNodes.map { it.step },
                durationMs = totalElapsed
            )
        }
    }

    // ---- Notification Copilot, CameraX Live Lens, Playbook Learning & Auto-Triggers ----

    fun speakNotificationDigest() {
        val prefs = _uiState.value.preferences
        val preferBangla = prefs.speechLocale == com.example.ai.SpeechLocaleOption.BN_BD
        val digest = com.example.notifications.NotificationCopilotStore.buildSpokenDigest(preferBangla = preferBangla)
        _uiState.update {
            it.copy(
                latestReply = digest,
                coreMode = if (prefs.ttsEnabled) CoreMode.SPEAKING else CoreMode.IDLE
            )
        }
        if (prefs.ttsEnabled) {
            voiceManager.speak(digest, prefs.persona, prefs.speechLocale)
        }
    }

    fun replyToNotificationCluster(clusterId: String, replyText: String) {
        val res = com.example.notifications.NotificationCopilotStore.dispatchRemoteReply(
            context = appContext,
            clusterId = clusterId,
            replyText = replyText
        )
        val prefs = _uiState.value.preferences
        res.onSuccess { confirmation ->
            _uiState.update {
                it.copy(
                    latestReply = confirmation,
                    errorMessage = null,
                    coreMode = if (prefs.ttsEnabled) CoreMode.SPEAKING else CoreMode.IDLE
                )
            }
            if (prefs.ttsEnabled) {
                voiceManager.speak(confirmation, prefs.persona, prefs.speechLocale)
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(errorMessage = err.localizedMessage ?: "Failed to send notification reply.")
            }
        }
    }

    fun dismissNotificationCluster(clusterId: String) {
        com.example.notifications.NotificationCopilotStore.dismissCluster(clusterId)
    }

    fun clearAllNotifications() {
        com.example.notifications.NotificationCopilotStore.clearAll()
    }

    fun clearCameraLensRequest() {
        _uiState.update { it.copy(cameraLensRequested = false) }
    }

    fun analyzeCameraLensFrame(bitmap: Bitmap, question: String) {
        val cleanQuestion = question.trim().ifBlank {
            "Identify what is in front of the camera, read any visible text cleanly, and answer briefly."
        }
        cameraLensEngine.setAnalyzingAnswer(true, cleanQuestion)
        scope.launch {
            val prefs = _uiState.value.preferences
            val processed = withContext(Dispatchers.Default) {
                visionProcessor.processScreenForVision(
                    sourceBitmap = bitmap,
                    prompt = cleanQuestion,
                    accessibilityNodeCount = 0,
                    forceOcrFallback = true,
                    visionTokenMode = com.example.data.local.VisionTokenMode.CLOUD_VISION,
                    forceCloudEscalation = true
                )
            }
            val visionPrompt = buildString {
                appendLine("LIVE CAMERA LENS QA:")
                appendLine("Analyze the attached camera snapshot and answer the user's question concisely in 1-3 natural sentences matching the user's language (English or Bangla).")
                val liveLabels = _uiState.value.lensCueState.liveLabels
                if (liveLabels.isNotEmpty()) {
                    appendLine("Detected scene objects: ${liveLabels.joinToString(", ")}")
                }
                appendLine("Question: $cleanQuestion")
            }
            val result = geminiService.planCommand(
                userPrompt = visionPrompt,
                persona = prefs.persona,
                deviceContextSummary = telemetryProvider.buildPromptTelemetrySummary(telemetryProvider.readTelemetry()),
                memoriesSummary = "",
                routinesSummary = "",
                contactsSummary = "",
                installedAppsSummary = "",
                screenUiSummary = "Camera Lens Live Capture",
                screenBitmap = processed.croppedBitmap ?: bitmap,
                ocrSummary = processed.ocrSummary.ifBlank { null },
                preferNvidiaPrimary = prefs.preferNvidiaPrimary,
                onActiveModelChanged = { lbl -> _uiState.update { it.copy(activeAiModelLabel = lbl) } }
            )
            if (processed.ownsCroppedBitmap) {
                runCatching { processed.croppedBitmap?.recycle() }
            }
            val answer = result.fold(
                onSuccess = { plan ->
                    plan.reply.ifBlank {
                        processed.ocrSummary.takeIf { it.isNotBlank() } ?: "Analyzed camera frame."
                    }
                },
                onFailure = { err ->
                    val ocrFallback = processed.ocrSummary
                    if (ocrFallback.isNotBlank()) {
                        "Offline OCR detected: $ocrFallback"
                    } else {
                        err.localizedMessage ?: "Could not analyze image right now."
                    }
                }
            )
            cameraLensEngine.publishVisionAnswer(cleanQuestion, answer)
            _uiState.update {
                it.copy(
                    lastUserPrompt = "Lens: $cleanQuestion",
                    latestReply = answer,
                    coreMode = if (prefs.ttsEnabled) CoreMode.SPEAKING else CoreMode.IDLE
                )
            }
            if (prefs.ttsEnabled) {
                voiceManager.speak(answer, prefs.persona, prefs.speechLocale)
            }
        }
    }

    fun replayPlaybook(playbook: com.example.data.local.AppPlaybookEntity) {
        val pbSteps = com.example.automation.PlaybookLearningEngine.compilePlaybookToPlannedSteps(
            com.example.automation.PlaybookMatch(
                playbook = playbook,
                steps = com.example.automation.PlaybookLearningEngine.decodeStepsJson(playbook.stepsJson),
                slotBindings = emptyMap(),
                confidenceScore = 1f
            )
        )
        if (pbSteps.isEmpty()) return
        activeExecutionJob?.cancel()
        activeExecutionJob = scope.launch {
            val start = System.currentTimeMillis()
            executeStepsPipeline(
                userPrompt = "Run Playbook: ${playbook.appLabel} (${playbook.intentPattern})",
                initialReply = "Running learned ${playbook.appLabel} playbook...",
                steps = pbSteps,
                bypassConfirmation = true,
                sourceMode = "PLAYBOOK_REPLAY",
                hadScreenVision = false
            )
            withContext(Dispatchers.IO) {
                vaultRepository.recordPlaybookSuccess(playbook.id, System.currentTimeMillis() - start)
            }
        }
    }

    private suspend fun distillAndPersistPlaybook(
        userPrompt: String,
        steps: List<PlannedStep>,
        durationMs: Long
    ) {
        val pbSteps = steps.mapNotNull { s ->
            when (s.actionType) {
                ActionType.LAUNCH_APP -> com.example.automation.PlaybookStep("LAUNCH", s.target)
                ActionType.POST_SOCIAL -> com.example.automation.PlaybookStep("LAUNCH", s.target.ifBlank { "Facebook" })
                ActionType.UI_TAP_TEXT -> com.example.automation.PlaybookStep("TAP_TEXT", s.target)
                ActionType.UI_INPUT_TEXT -> com.example.automation.PlaybookStep("INPUT", s.target, slotKey = "postText", literalFallback = s.textPayload)
                ActionType.UI_TAP_COORDINATES -> com.example.automation.PlaybookStep("TAP_COORDS", normX = s.intValue, normY = s.secondaryIntValue)
                ActionType.UI_SCROLL -> com.example.automation.PlaybookStep("SCROLL", s.target.ifBlank { "down" })
                else -> null
            }
        }
        if (pbSteps.isEmpty()) return
        val appHint = steps.firstOrNull { it.target.isNotBlank() }?.target ?: ""
        val candidate = com.example.automation.PlaybookLearningEngine.distillPlaybook(
            appPackage = "",
            appLabel = appHint,
            userPrompt = userPrompt,
            rawSteps = pbSteps,
            durationMs = durationMs
        )
        withContext(Dispatchers.IO) {
            val existing = vaultRepository.getPlaybooksSnapshot().firstOrNull {
                it.appPackage == candidate.appPackage && it.intentPattern == candidate.intentPattern
            }
            if (existing != null) {
                vaultRepository.recordPlaybookSuccess(existing.id, durationMs)
            } else {
                vaultRepository.savePlaybook(candidate)
            }
        }
    }

    fun testTriggerRuleNow(rule: com.example.data.local.SmartTriggerRuleEntity) {
        scope.launch {
            handleTriggeredRule(
                rule = rule,
                event = com.example.automation.ContextTriggerEvent(rule.triggerType, "Manual Test")
            )
        }
    }

    private suspend fun handleTriggeredRule(
        rule: com.example.data.local.SmartTriggerRuleEntity,
        event: com.example.automation.ContextTriggerEvent
    ) {
        withContext(Dispatchers.IO) {
            vaultRepository.recordTriggerFired(rule.id)
        }
        val label = "${rule.name} (${event.detailValue.ifBlank { event.type }})"
        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(lastAutoTriggerLabel = label) }
            if (rule.actionMode == "SPEAK_NOTIFICATION_DIGEST") {
                val clusters = _uiState.value.unreadNotifications
                if (clusters.isNotEmpty() || event.detailValue == "Manual Test") {
                    speakNotificationDigest()
                }
            } else if (rule.actionPayload.isNotBlank()) {
                submitCommand(
                    prompt = rule.actionPayload,
                    sourceMode = "AUTO_TRIGGER"
                )
            }
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

    private fun isExplicitScreenQuery(prompt: String): Boolean {
        val lower = prompt.lowercase()
        val phrases = listOf(
            "on my screen", "on the screen", "what is on screen", "what's on screen",
            "read screen", "read this screen", "explain screen", "see my screen",
            "amar screen", "screen e ki", "screen a ki", "এই স্ক্রিন", "স্ক্রিনে কি"
        )
        return phrases.any { lower.contains(it) }
    }

    private fun shouldInspectScreenProactively(prompt: String): Boolean {
        if (isExplicitScreenQuery(prompt)) return true
        val lower = prompt.lowercase()
        val screenUiActionPrefixes = listOf(
            "tap ", "click ", "scroll down", "scroll up", "press ", "tap on ",
            "click koro", "tap koro", "like koro", "subscribe", "comment koro",
            "first video", "prothom video", "channel a", "video section", "videos tab"
        )
        return screenUiActionPrefixes.any { lower.startsWith(it) || lower.contains(" $it") } ||
            lower.contains("this page") ||
            lower.contains("what am i looking") ||
            lower.contains("ei video") ||
            lower.contains("ei post")
    }

    private data class ContextSnapshotBundle(
        val resolvedPrompt: String,
        val memoriesText: String,
        val routinesText: String,
        val contactsText: String,
        val appsText: String,
        val historyText: String,
        val historyPairs: List<Pair<String, String>>,
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

        internal fun resolvePipelineFallbackReply(
            initialReply: String,
            dagNodes: List<ResolvedDagNode>,
            timedOutCount: Int,
            failedCount: Int,
            preferBangla: Boolean,
            webTools: FreeWebToolsRepository
        ): String {
            val hadWeatherLookup = dagNodes.any { it.step.actionType == ActionType.WEATHER_LOOKUP }
            if (hadWeatherLookup && (timedOutCount > 0 || failedCount > 0)) {
                return webTools.buildWeatherFailureReply(preferBangla)
            }
            if ((timedOutCount > 0 || failedCount > 0) &&
                dagNodes.any {
                    it.step.actionType == ActionType.WIKIPEDIA_LOOKUP ||
                        it.step.actionType == ActionType.CURRENCY_CONVERT ||
                        it.step.actionType == ActionType.WEB_SEARCH
                }
            ) {
                return if (preferBangla) {
                    "দুঃখিত, লাইভ তথ্য খুঁজতে বেশি সময় লেগেছে বা নেটওয়ার্ক পাওয়া যায়নি। আবার চেষ্টা করুন।"
                } else {
                    "Sorry, the live lookup timed out or lost connection. Please try again."
                }
            }
            return initialReply
        }
    }
}
