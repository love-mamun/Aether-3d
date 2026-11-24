package com.example.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ai.AssistantPersona
import com.example.ai.SpeechLocaleOption
import com.example.audio.HardwareAudioEffectController
import com.example.voice.PiperNeuralVoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "VoiceAndTtsManager"
private const val ECHO_DECAY_GUARD_MS = 650L

internal object EchoGuardPolicy {
    fun isEchoGuardActive(nowElapsedMs: Long, echoGuardUntilMs: Long): Boolean {
        return nowElapsedMs < echoGuardUntilMs
    }

    fun shouldFinishUtteranceBatch(utteranceId: String?, activeBatchId: String?): Boolean {
        return activeBatchId != null && utteranceId == activeBatchId
    }

    fun shouldRunBargeInMonitor(
        bargeInEnabled: Boolean,
        bargeInMode: String,
        route: AudioOutputRoute
    ): Boolean {
        if (!bargeInEnabled) return false
        return when (bargeInMode.uppercase(Locale.US)) {
            "TAP_ONLY" -> false
            "HEADSET_ONLY" -> route == AudioOutputRoute.HEADSET_OR_BLE
            else -> true
        }
    }

    fun computeBargeInThreshold(
        route: AudioOutputRoute,
        baselineRms: Double
    ): Pair<Double, Int> {
        return when (route) {
            AudioOutputRoute.HEADSET_OR_BLE -> max(1800.0, baselineRms * 2.0) to 3
            AudioOutputRoute.LOUDSPEAKER -> max(3600.0, baselineRms * 2.2) to 6
        }
    }

    enum class StandbyDecision {
        START_WAKE_WORD,
        BUTTON_ONLY_IDLE,
        PAUSED_BY_USER,
        PAUSED_FOR_MUSIC,
        YIELDS_TO_FILTERED_MIC,
        BUSY_NOT_IDLE,
        DISABLED_OR_NO_PERMISSION
    }

    fun resolveBackgroundStandby(
        wakeWordEnabled: Boolean,
        micGranted: Boolean,
        backgroundServicePaused: Boolean,
        filteredMicCapturing: Boolean,
        pauseWakeDuringMusic: Boolean,
        musicPlaybackActive: Boolean,
        isCoreIdle: Boolean,
        wakeTriggerMode: com.example.data.local.WakeTriggerMode =
            com.example.data.local.WakeTriggerMode.HYBRID_KWS_AND_BUTTON
    ): StandbyDecision {
        if (wakeTriggerMode == com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY) {
            return StandbyDecision.BUTTON_ONLY_IDLE
        }
        if (backgroundServicePaused) return StandbyDecision.PAUSED_BY_USER
        if (filteredMicCapturing) return StandbyDecision.YIELDS_TO_FILTERED_MIC
        if (!wakeWordEnabled || !micGranted) return StandbyDecision.DISABLED_OR_NO_PERMISSION
        if (pauseWakeDuringMusic && musicPlaybackActive) return StandbyDecision.PAUSED_FOR_MUSIC
        if (!isCoreIdle) return StandbyDecision.BUSY_NOT_IDLE
        return StandbyDecision.START_WAKE_WORD
    }
}

internal data class SpeechErrorClassification(
    val userMessage: String,
    val isServiceOrDeviceFailure: Boolean,
    val shouldRetryBusy: Boolean = false
)

internal object SpeechErrorPolicy {
    fun classifyError(errorCode: Int): SpeechErrorClassification {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> SpeechErrorClassification(
                userMessage = "Audio recording error.",
                isServiceOrDeviceFailure = true
            )
            SpeechRecognizer.ERROR_CLIENT -> SpeechErrorClassification(
                userMessage = "Voice recognizer unavailable.",
                isServiceOrDeviceFailure = true
            )
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechErrorClassification(
                userMessage = "Microphone permission required.",
                isServiceOrDeviceFailure = false
            )
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechErrorClassification(
                userMessage = "Network unavailable for speech recognition.",
                isServiceOrDeviceFailure = true
            )
            SpeechRecognizer.ERROR_NO_MATCH -> SpeechErrorClassification(
                userMessage = "Didn't catch any speech. Tap mic or type below.",
                isServiceOrDeviceFailure = false
            )
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechErrorClassification(
                userMessage = "No speech detected. Tap mic or type below.",
                isServiceOrDeviceFailure = false
            )
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechErrorClassification(
                userMessage = "Speech recognizer busy, please retry.",
                isServiceOrDeviceFailure = true,
                shouldRetryBusy = true
            )
            SpeechRecognizer.ERROR_SERVER -> SpeechErrorClassification(
                userMessage = "Speech service temporarily unreachable.",
                isServiceOrDeviceFailure = true
            )
            10 -> SpeechErrorClassification( // ERROR_TOO_MANY_REQUESTS
                userMessage = "Speech service rate limited. Switching to filtered mic.",
                isServiceOrDeviceFailure = true
            )
            11 -> SpeechErrorClassification( // ERROR_SERVER_DISCONNECTED
                userMessage = "Speech service disconnected.",
                isServiceOrDeviceFailure = true
            )
            12, 13 -> SpeechErrorClassification( // LANGUAGE_NOT_SUPPORTED / LANGUAGE_UNAVAILABLE
                userMessage = "Selected voice language unavailable on this device.",
                isServiceOrDeviceFailure = true
            )
            14, 15 -> SpeechErrorClassification( // CANNOT_CHECK_SUPPORT / CANNOT_LISTEN_TO_DOWNLOAD_EVENTS
                userMessage = "On-device speech service unavailable.",
                isServiceOrDeviceFailure = true
            )
            else -> SpeechErrorClassification(
                userMessage = "Speech recognition error ($errorCode).",
                isServiceOrDeviceFailure = true
            )
        }
    }
}

private data class PendingSpeechRequest(
    val text: String,
    val persona: AssistantPersona,
    val localeOption: SpeechLocaleOption,
    val batchId: String
)

class VoiceAndTtsManager(
    context: Context,
    private val commRouter: CommunicationAudioRouter,
    private val piperManager: PiperNeuralVoiceManager,
    private val onAmplitudeChanged: (Float) -> Unit,
    private val onPartialSpeech: (String) -> Unit,
    private val onFinalSpeech: (String) -> Unit,
    private val onSpeechError: (String, Boolean) -> Unit,
    private val onSpeakingStateChanged: (Boolean) -> Unit,
    private val onSpeakingFinished: () -> Unit,
    private val onWakeWordTriggered: (WakeWordMatch) -> Unit = {},
    private val onBargeInDetected: () -> Unit = {},
    private val onEchoGuardChanged: (Boolean) -> Unit = {},
    private val continuousCaptureActiveProvider: () -> Boolean = { false }
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitFailed = false
    private var pendingSpeechRequest: PendingSpeechRequest? = null
    private var commModeAcquiredForOutput = false

    private var speakingPulseJob: Job? = null
    private var speakingSafetyJob: Job? = null
    private var piperSpeakJob: Job? = null
    private var bargeInMonitorJob: Job? = null
    private var wakeRearmJob: Job? = null
    private var recognizerRetryJob: Job? = null
    private var echoGuardClearJob: Job? = null
    private var retriedWithFallbackLocale = false
    private var retriedAfterBusy = false

    @Volatile
    private var activeUtteranceBatchId: String? = null
    @Volatile
    private var isSpeakingOutput: Boolean = false
    @Volatile
    private var echoGuardUntilMs: Long = 0L

    @Volatile
    private var isWakeWordMode = false
    @Volatile
    private var wakeWordEnabledConfig = false
    @Volatile
    private var bargeInEnabledConfig = true
    @Volatile
    private var bargeInModeConfig = "ADAPTIVE"
    @Volatile
    private var voiceEngineModeConfig = "AUTO"
    @Volatile
    private var selectedEnglishVoiceId = "en_US-ryan-medium-int8"
    @Volatile
    private var selectedBanglaVoiceId = "bn_BD-mimic3-multi_low"
    @Volatile
    private var voiceSpeedConfig = 1.08f
    @Volatile
    private var customAetherPitchConfig = 1.0f
    @Volatile
    private var pauseWakeDuringMusicConfig = true
    @Volatile
    private var wakeTriggerModeConfig = com.example.data.local.WakeTriggerMode.HYBRID_KWS_AND_BUTTON
    @Volatile
    private var kwsThresholdConfig = 0.25f
    @Volatile
    private var activeWakeLocale = SpeechLocaleOption.EN_US
    @Volatile
    private var activeWakeCustomPhrase = "Hey Aether"
    @Volatile
    private var activeWakePersona = AssistantPersona.AETHER

    val localWakeEngine = LocalWakeWordEngine(appContext, commRouter)

    init {
        runOnMain {
            initializeTts()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun initializeTts() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        ttsReady = false
        ttsInitFailed = false
        tts = TextToSpeech(appContext) { status ->
            runOnMain {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    ttsInitFailed = false
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            mainHandler.post {
                                val batchId = activeUtteranceBatchId ?: return@post
                                if (utteranceId == null || !utteranceId.startsWith(batchId)) {
                                    return@post
                                }
                                if (!isSpeakingOutput) {
                                    isSpeakingOutput = true
                                    onSpeakingStateChanged(true)
                                    startSpeakingCadencePulse()
                                }
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                if (!EchoGuardPolicy.shouldFinishUtteranceBatch(utteranceId, activeUtteranceBatchId)) {
                                    return@post
                                }
                                completeOutputSession(notifyFinished = true)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            handleTtsUtteranceError(utteranceId, -1)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            handleTtsUtteranceError(utteranceId, errorCode)
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            mainHandler.post {
                                val batchId = activeUtteranceBatchId ?: return@post
                                if (utteranceId != null && !utteranceId.startsWith(batchId)) {
                                    return@post
                                }
                                if (!interrupted && EchoGuardPolicy.shouldFinishUtteranceBatch(utteranceId, batchId)) {
                                    completeOutputSession(notifyFinished = true)
                                }
                            }
                        }
                    })

                    val queued = pendingSpeechRequest
                    pendingSpeechRequest = null
                    if (queued != null && activeUtteranceBatchId == queued.batchId) {
                        val useCommRoute = commRouter.shouldUseVoiceCommunicationForSpeechOutput(
                            continuousCaptureActiveProvider()
                        )
                        val route = acquireSpeechRoute(useCommRoute)
                        val hasBangla = queued.text.any { it in '\u0980'..'\u09FF' }
                        speakWithSystemTts(
                            clean = queued.text,
                            persona = queued.persona,
                            localeOption = queued.localeOption,
                            batchId = queued.batchId,
                            hasBanglaChars = hasBangla,
                            route = route,
                            useCommRoute = useCommRoute
                        )
                    }
                } else {
                    ttsReady = false
                    ttsInitFailed = true
                    Log.w(TAG, "TTS initialization failed with status=$status")
                    val queued = pendingSpeechRequest
                    pendingSpeechRequest = null
                    if (queued != null && activeUtteranceBatchId == queued.batchId) {
                        completeOutputSession(notifyFinished = true)
                    }
                }
            }
        }
    }

    private fun handleTtsUtteranceError(utteranceId: String?, errorCode: Int) {
        mainHandler.post {
            val batchId = activeUtteranceBatchId
            if (batchId != null && utteranceId != null && !utteranceId.startsWith(batchId)) {
                return@post
            }
            Log.w(TAG, "TTS utterance error code=$errorCode id=$utteranceId")
            completeOutputSession(notifyFinished = true)
        }
    }

    fun updateHandsFreeConfig(
        wakeWordEnabled: Boolean,
        bargeInEnabled: Boolean,
        customWakeWord: String,
        persona: AssistantPersona,
        localeOption: SpeechLocaleOption,
        voiceEngineMode: String = "AUTO",
        selectedVoiceId: String = "en_US-ryan-medium-int8",
        selectedBanglaId: String = "bn_BD-mimic3-multi_low",
        bargeInMode: String = "ADAPTIVE",
        voiceSpeed: Float = 1.08f,
        customAetherPitchFactor: Float = 1.0f,
        pauseWakeDuringMusic: Boolean = true,
        wakeTriggerMode: com.example.data.local.WakeTriggerMode =
            com.example.data.local.WakeTriggerMode.HYBRID_KWS_AND_BUTTON,
        kwsThreshold: Float = 0.25f
    ) {
        wakeWordEnabledConfig = wakeWordEnabled
        bargeInEnabledConfig = bargeInEnabled
        activeWakeCustomPhrase = customWakeWord
        activeWakePersona = persona
        activeWakeLocale = localeOption
        voiceEngineModeConfig = voiceEngineMode
        selectedEnglishVoiceId = selectedVoiceId
        selectedBanglaVoiceId = selectedBanglaId
        bargeInModeConfig = bargeInMode
        voiceSpeedConfig = voiceSpeed.coerceIn(0.75f, 1.45f)
        customAetherPitchConfig = customAetherPitchFactor.coerceIn(0.78f, 1.25f)
        pauseWakeDuringMusicConfig = pauseWakeDuringMusic
        wakeTriggerModeConfig = wakeTriggerMode
        kwsThresholdConfig = kwsThreshold.coerceIn(0.10f, 0.85f)
    }

    fun playWakeChime() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 55)
                tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 110)
                delay(160L)
                tone.release()
            }
        }
    }

    fun isSpeechRecognitionAvailable(): Boolean {
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }
                .getOrDefault(false)
        }
        return false
    }

    fun isEchoGuardActiveNow(): Boolean {
        return isSpeakingOutput || EchoGuardPolicy.isEchoGuardActive(SystemClock.elapsedRealtime(), echoGuardUntilMs)
    }

    private fun armEchoDecayGuard(durationMs: Long = ECHO_DECAY_GUARD_MS) {
        echoGuardUntilMs = SystemClock.elapsedRealtime() + durationMs
        onEchoGuardChanged(true)
        echoGuardClearJob?.cancel()
        echoGuardClearJob = scope.launch {
            delay(durationMs + 25L)
            if (!isSpeakingOutput && SystemClock.elapsedRealtime() >= echoGuardUntilMs) {
                onEchoGuardChanged(false)
            }
        }
    }

    private fun acquireSpeechRoute(useCommRoute: Boolean): AudioOutputRoute {
        return if (useCommRoute) {
            if (!commModeAcquiredForOutput) {
                commModeAcquiredForOutput = true
                commRouter.enterCommunicationMode()
            } else {
                commRouter.detectCurrentOutputRoute()
            }
        } else {
            if (commModeAcquiredForOutput) {
                commModeAcquiredForOutput = false
                commRouter.exitCommunicationMode()
            }
            commRouter.detectCurrentOutputRoute()
        }
    }

    private fun completeOutputSession(notifyFinished: Boolean) {
        val wasOutputActive = isSpeakingOutput || activeUtteranceBatchId != null || pendingSpeechRequest != null
        activeUtteranceBatchId = null
        pendingSpeechRequest = null
        isSpeakingOutput = false
        speakingSafetyJob?.cancel()
        stopBargeInMonitor()
        stopSpeakingCadencePulse()
        if (wasOutputActive) {
            armEchoDecayGuard(ECHO_DECAY_GUARD_MS)
        }
        if (commModeAcquiredForOutput) {
            commModeAcquiredForOutput = false
            commRouter.exitCommunicationMode()
        }
        if (wasOutputActive || notifyFinished) {
            onSpeakingStateChanged(false)
        }
        if (notifyFinished) {
            onSpeakingFinished()
        }
    }

    fun startWakeWordListening() {
        if (wakeTriggerModeConfig == com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY) {
            stopWakeWordListening()
            return
        }
        if (!wakeWordEnabledConfig) return
        val hasMic = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return

        runOnMain {
            val now = SystemClock.elapsedRealtime()
            if (isSpeakingOutput) {
                Log.d(TAG, "Ignoring wake-word arm while output is active.")
                return@runOnMain
            }
            if (pauseWakeDuringMusicConfig && commRouter.isMusicPlaybackActive()) {
                isWakeWordMode = true
                scheduleWakeRearm(1_800L)
                return@runOnMain
            }
            if (EchoGuardPolicy.isEchoGuardActive(now, echoGuardUntilMs)) {
                val remaining = (echoGuardUntilMs - now).coerceAtLeast(100L)
                isWakeWordMode = true
                scheduleWakeRearm(remaining + 60L)
                return@runOnMain
            }
            wakeRearmJob?.cancel()
            isWakeWordMode = true

            if (wakeTriggerModeConfig == com.example.data.local.WakeTriggerMode.HYBRID_KWS_AND_BUTTON) {
                destroyRecognizerQuietly()
                localWakeEngine.startPassiveMonitor(
                    customWakePhrase = activeWakeCustomPhrase,
                    persona = activeWakePersona,
                    threshold = kwsThresholdConfig,
                    pauseDuringMusic = pauseWakeDuringMusicConfig,
                    shouldSuspendProvider = {
                        !isWakeWordMode || isSpeakingOutput || EchoGuardPolicy.isEchoGuardActive(
                            SystemClock.elapsedRealtime(),
                            echoGuardUntilMs
                        ) || continuousCaptureActiveProvider()
                    },
                    onOfflineKeywordHit = { match ->
                        if (isWakeWordMode && !isEchoGuardActiveNow()) {
                            isWakeWordMode = false
                            onWakeWordTriggered(match)
                        }
                    },
                    onVoiceEnergySpeechGate = {
                        // Silence-gated single recognizer burst ONLY when speech energy detected
                        if (isWakeWordMode && !isEchoGuardActiveNow() && isSpeechRecognitionAvailable()) {
                            startRecognizerInternal(
                                localeOption = activeWakeLocale,
                                isWakePassive = true,
                                isLocaleFallbackRetry = false
                            )
                        } else if (isWakeWordMode) {
                            scheduleWakeRearm(900L)
                        }
                    }
                )
                return@runOnMain
            }

            if (!isSpeechRecognitionAvailable()) return@runOnMain
            startRecognizerInternal(
                localeOption = activeWakeLocale,
                isWakePassive = true,
                isLocaleFallbackRetry = false
            )
        }
    }

    fun stopWakeWordListening() {
        localWakeEngine.stopPassiveMonitor()
        runOnMain {
            wakeRearmJob?.cancel()
            if (isWakeWordMode) {
                isWakeWordMode = false
                destroyRecognizerQuietly()
                onAmplitudeChanged(0f)
            }
        }
    }

    fun startListening(localeOption: SpeechLocaleOption, isLocaleFallbackRetry: Boolean = false) {
        localWakeEngine.stopPassiveMonitor()
        runOnMain {
            wakeRearmJob?.cancel()
            recognizerRetryJob?.cancel()
            val wasWake = isWakeWordMode
            isWakeWordMode = false
            stopSpeakingInternal(notifyFinished = false)

            if (!isLocaleFallbackRetry) {
                retriedWithFallbackLocale = false
                retriedAfterBusy = false
            }

            if (wasWake) {
                destroyRecognizerQuietly()
            }

            if (!isSpeechRecognitionAvailable()) {
                onSpeechError(
                    "Speech recognition service unavailable on this device. Switching to filtered microphone.",
                    true
                )
                return@runOnMain
            }

            if (wasWake) {
                // Brief yield so system SpeechRecognizer binder finishes releasing passive session
                recognizerRetryJob = scope.launch {
                    delay(90L)
                    startRecognizerInternal(
                        localeOption = localeOption,
                        isWakePassive = false,
                        isLocaleFallbackRetry = isLocaleFallbackRetry
                    )
                }
            } else {
                startRecognizerInternal(
                    localeOption = localeOption,
                    isWakePassive = false,
                    isLocaleFallbackRetry = isLocaleFallbackRetry
                )
            }
        }
    }

    private fun destroyRecognizerQuietly() {
        val current = speechRecognizer
        speechRecognizer = null
        if (current != null) {
            runCatching { current.cancel() }
            runCatching { current.destroy() }
        }
    }

    private fun createBestSpeechRecognizer(): SpeechRecognizer {
        val hasStandard = SpeechRecognizer.isRecognitionAvailable(appContext)
        if (!hasStandard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)) {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(appContext)
    }

    private fun startRecognizerInternal(
        localeOption: SpeechLocaleOption,
        isWakePassive: Boolean,
        isLocaleFallbackRetry: Boolean
    ) {
        if (isWakePassive && isEchoGuardActiveNow()) {
            val now = SystemClock.elapsedRealtime()
            val wait = if (isSpeakingOutput) 800L else (echoGuardUntilMs - now + 80L).coerceAtLeast(250L)
            scheduleWakeRearm(wait)
            return
        }
        try {
            destroyRecognizerQuietly()
            val created = createBestSpeechRecognizer()
            speechRecognizer = created.apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!isWakePassive) {
                            onPartialSpeech("Listening...")
                        }
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {
                        if (!isWakePassive) {
                            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                            onAmplitudeChanged(normalized)
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        onAmplitudeChanged(if (isWakePassive) 0f else 0.1f)
                    }

                    override fun onError(error: Int) {
                        onAmplitudeChanged(0f)
                        if (isWakePassive && isWakeWordMode) {
                            val backoffMs = when (error) {
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                                SpeechRecognizer.ERROR_SERVER,
                                SpeechRecognizer.ERROR_NETWORK,
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 2_200L
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                    isWakeWordMode = false
                                    return
                                }
                                else -> 480L
                            }
                            scheduleWakeRearm(backoffMs)
                            return
                        }

                        val classified = SpeechErrorPolicy.classifyError(error)

                        if (classified.shouldRetryBusy && !retriedAfterBusy) {
                            retriedAfterBusy = true
                            Log.w(TAG, "SpeechRecognizer busy (code=$error); destroying and retrying after 250ms")
                            destroyRecognizerQuietly()
                            recognizerRetryJob?.cancel()
                            recognizerRetryJob = scope.launch {
                                delay(250L)
                                startRecognizerInternal(
                                    localeOption = localeOption,
                                    isWakePassive = false,
                                    isLocaleFallbackRetry = isLocaleFallbackRetry
                                )
                            }
                            return
                        }

                        if ((error == 12 || error == 13 || error == SpeechRecognizer.ERROR_CLIENT) &&
                            localeOption != SpeechLocaleOption.EN_US &&
                            !isLocaleFallbackRetry &&
                            !retriedWithFallbackLocale
                        ) {
                            retriedWithFallbackLocale = true
                            Log.w(TAG, "SpeechRecognizer error=$error for ${localeOption.languageTag}, retrying with en-US")
                            startRecognizerInternal(
                                localeOption = SpeechLocaleOption.EN_US,
                                isWakePassive = false,
                                isLocaleFallbackRetry = true
                            )
                            return
                        }

                        Log.w(TAG, "SpeechRecognizer active error=$error (${classified.userMessage})")
                        onSpeechError(classified.userMessage, classified.isServiceOrDeviceFailure)
                    }

                    override fun onResults(results: Bundle?) {
                        onAmplitudeChanged(0f)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val best = matches?.firstOrNull()?.trim().orEmpty()
                        if (isWakePassive && isWakeWordMode) {
                            if (isEchoGuardActiveNow()) {
                                scheduleWakeRearm(650L)
                                return
                            }
                            val hit = WakeWordDetector.match(
                                transcript = best,
                                customWakeWord = activeWakeCustomPhrase,
                                persona = activeWakePersona
                            )
                            if (hit != null) {
                                isWakeWordMode = false
                                onWakeWordTriggered(hit)
                            } else {
                                scheduleWakeRearm(350L)
                            }
                            return
                        }

                        if (best.isNotBlank()) {
                            onFinalSpeech(best)
                        } else {
                            onSpeechError("No speech recognized. Try again.", false)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (partial.isBlank()) return

                        if (isWakePassive && isWakeWordMode) {
                            if (isEchoGuardActiveNow()) return
                            val hit = WakeWordDetector.match(
                                transcript = partial,
                                customWakeWord = activeWakeCustomPhrase,
                                persona = activeWakePersona
                            )
                            if (hit != null && hit.trailingCommand.isBlank()) {
                                isWakeWordMode = false
                                runCatching { speechRecognizer?.cancel() }
                                onWakeWordTriggered(hit)
                            }
                            return
                        }
                        onPartialSpeech(partial)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeOption.languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                if (isWakePassive) {
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 900L)
                }
            }
            created.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer", e)
            if (isWakePassive) {
                scheduleWakeRearm(2_000L)
            } else {
                onSpeechError(
                    e.localizedMessage ?: "Could not start voice listener.",
                    true
                )
            }
        }
    }

    private fun scheduleWakeRearm(delayMs: Long) {
        wakeRearmJob?.cancel()
        wakeRearmJob = scope.launch {
            delay(delayMs)
            if (wakeTriggerModeConfig == com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY) {
                return@launch
            }
            val now = SystemClock.elapsedRealtime()
            if (isSpeakingOutput) {
                scheduleWakeRearm(600L)
                return@launch
            }
            if (pauseWakeDuringMusicConfig && commRouter.isMusicPlaybackActive()) {
                scheduleWakeRearm(1_800L)
                return@launch
            }
            if (EchoGuardPolicy.isEchoGuardActive(now, echoGuardUntilMs)) {
                delay((echoGuardUntilMs - now + 60L).coerceAtLeast(80L))
            }
            if (isWakeWordMode && wakeWordEnabledConfig && !isSpeakingOutput) {
                if (wakeTriggerModeConfig == com.example.data.local.WakeTriggerMode.HYBRID_KWS_AND_BUTTON) {
                    startWakeWordListening()
                } else {
                    startRecognizerInternal(
                        localeOption = activeWakeLocale,
                        isWakePassive = true,
                        isLocaleFallbackRetry = false
                    )
                }
            }
        }
    }

    fun stopListening() {
        localWakeEngine.stopPassiveMonitor()
        runOnMain {
            wakeRearmJob?.cancel()
            recognizerRetryJob?.cancel()
            isWakeWordMode = false
            destroyRecognizerQuietly()
            onAmplitudeChanged(0f)
        }
    }

    fun speak(
        text: String,
        persona: AssistantPersona,
        localeOption: SpeechLocaleOption
    ) {
        val clean = text.trim()
        if (clean.isBlank()) {
            runOnMain { completeOutputSession(notifyFinished = true) }
            return
        }

        localWakeEngine.stopPassiveMonitor()
        runOnMain {
            // Hard half-duplex lock: terminate passive wake-word or active SpeechRecognizer immediately
            wakeRearmJob?.cancel()
            recognizerRetryJob?.cancel()
            piperSpeakJob?.cancel()
            speakingSafetyJob?.cancel()
            stopBargeInMonitor()
            if (isWakeWordMode) {
                isWakeWordMode = false
            }
            destroyRecognizerQuietly()

            val batchId = UUID.randomUUID().toString()
            activeUtteranceBatchId = batchId
            val useCommRoute = commRouter.shouldUseVoiceCommunicationForSpeechOutput(
                continuousCaptureActiveProvider()
            )
            val route = acquireSpeechRoute(useCommRoute)

            val hasBanglaChars = clean.any { it in '\u0980'..'\u09FF' }
            val preferBangla = hasBanglaChars || localeOption == SpeechLocaleOption.BN_BD
            val preferPiper = voiceEngineModeConfig != "SYSTEM" &&
                piperManager.isNativeRuntimeLikelySupported()

            val installedPack = if (preferPiper) {
                piperManager.pickBestInstalledPack(
                    preferredId = if (preferBangla) selectedBanglaVoiceId else selectedEnglishVoiceId,
                    persona = persona,
                    preferBangla = preferBangla
                )
            } else {
                null
            }

            val effectiveSpeed = (persona.ttsRate * (voiceSpeedConfig / 1.08f)).coerceIn(0.75f, 1.45f)
            val effectivePitchFactor = if (persona == AssistantPersona.AETHER) customAetherPitchConfig else 1.0f

            if (installedPack != null) {
                isSpeakingOutput = true
                onSpeakingStateChanged(true)
                startBargeInMonitor(route)
                piperSpeakJob = scope.launch {
                    val spokeOk = piperManager.speakWithPack(
                        text = clean,
                        pack = installedPack,
                        speed = effectiveSpeed,
                        pitchFactor = effectivePitchFactor,
                        audioAttributes = commRouter.speechOutputAttributes(useCommRoute),
                        onAmplitude = { amp -> onAmplitudeChanged(amp) }
                    )
                    if (activeUtteranceBatchId == batchId) {
                        if (spokeOk) {
                            completeOutputSession(notifyFinished = true)
                        } else if (ttsReady) {
                            speakWithSystemTts(clean, persona, localeOption, batchId, hasBanglaChars, route, useCommRoute)
                        } else {
                            queuePendingSpeech(clean, persona, localeOption, batchId)
                        }
                    }
                }
                return@runOnMain
            }

            if (!ttsReady) {
                queuePendingSpeech(clean, persona, localeOption, batchId)
                return@runOnMain
            }
            speakWithSystemTts(clean, persona, localeOption, batchId, hasBanglaChars, route, useCommRoute)
        }
    }

    private fun queuePendingSpeech(
        clean: String,
        persona: AssistantPersona,
        localeOption: SpeechLocaleOption,
        batchId: String
    ) {
        pendingSpeechRequest = PendingSpeechRequest(clean, persona, localeOption, batchId)
        isSpeakingOutput = true
        onSpeakingStateChanged(true)
        if (ttsInitFailed || tts == null) {
            initializeTts()
        }
        speakingSafetyJob?.cancel()
        speakingSafetyJob = scope.launch {
            delay(6_000L)
            if (activeUtteranceBatchId == batchId && pendingSpeechRequest?.batchId == batchId) {
                Log.w(TAG, "TTS initialization wait timed out; releasing speaking state")
                completeOutputSession(notifyFinished = true)
            }
        }
    }

    private fun speakWithSystemTts(
        clean: String,
        persona: AssistantPersona,
        localeOption: SpeechLocaleOption,
        batchId: String,
        hasBanglaChars: Boolean,
        route: AudioOutputRoute,
        useCommRoute: Boolean
    ) {
        try {
            val effectiveTag = if (hasBanglaChars) "bn-BD" else localeOption.languageTag
            val targetLocale = Locale.forLanguageTag(effectiveTag)

            tts?.setAudioAttributes(commRouter.speechOutputAttributes(useCommRoute))
            val res = tts?.setLanguage(targetLocale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                val altLocale = if (hasBanglaChars || effectiveTag.startsWith("bn")) {
                    Locale.forLanguageTag("bn-IN")
                } else {
                    Locale.US
                }
                val altRes = tts?.setLanguage(altLocale)
                if (altRes == TextToSpeech.LANG_MISSING_DATA || altRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
            }

            selectBestNaturalVoice(targetLocale, persona)?.let { bestVoice ->
                tts?.voice = bestVoice
            }

            val pitchMultiplier = if (persona == AssistantPersona.AETHER) customAetherPitchConfig else 1.0f
            val speedRate = (persona.ttsRate * (voiceSpeedConfig / 1.08f)).coerceIn(0.75f, 1.45f)
            tts?.setPitch((persona.ttsPitch * pitchMultiplier).coerceIn(0.75f, 1.35f))
            tts?.setSpeechRate(speedRate)

            val sentences = clean.split(Regex("(?<=[.!?।])\\s+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf(clean) }

            var finalResult = TextToSpeech.SUCCESS
            sentences.forEachIndexed { index, sentence ->
                val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val utteranceId = if (index == sentences.lastIndex) {
                    batchId
                } else {
                    "${batchId}_part_$index"
                }
                val result = tts?.speak(sentence, queueMode, null, utteranceId) ?: TextToSpeech.ERROR
                if (result != TextToSpeech.SUCCESS) {
                    finalResult = result
                } else if (index < sentences.lastIndex) {
                    tts?.playSilentUtterance(110L, TextToSpeech.QUEUE_ADD, "${batchId}_pause_$index")
                }
            }

            if (finalResult != TextToSpeech.SUCCESS) {
                Log.w(TAG, "tts.speak returned error code=$finalResult")
                completeOutputSession(notifyFinished = true)
            } else {
                isSpeakingOutput = true
                onSpeakingStateChanged(true)
                startSpeakingCadencePulse()
                startBargeInMonitor(route)

                speakingSafetyJob?.cancel()
                val estimatedMs = (clean.length * 95L).coerceIn(6_000L, 48_000L)
                speakingSafetyJob = scope.launch {
                    delay(estimatedMs)
                    if (activeUtteranceBatchId == batchId) {
                        Log.w(TAG, "TTS safety timeout reached; releasing half-duplex output lock")
                        completeOutputSession(notifyFinished = true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak error", e)
            completeOutputSession(notifyFinished = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBargeInMonitor(route: AudioOutputRoute) {
        bargeInMonitorJob?.cancel()
        if (!EchoGuardPolicy.shouldRunBargeInMonitor(bargeInEnabledConfig, bargeInModeConfig, route)) {
            return
        }
        val hasMic = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return

        bargeInMonitorJob = scope.launch(Dispatchers.IO) {
            delay(450L)
            var record: AudioRecord? = null
            val effectController = HardwareAudioEffectController()
            try {
                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val safeMinBuf = if (minBuf > 0) minBuf else 2048
                val bufSize = (safeMinBuf * 2).coerceAtLeast(4096)

                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) return@launch

                val caps = effectController.attachToSession(record.audioSessionId)
                // On loudspeaker without hardware AEC, speaker playback self-triggers RMS barge-in;
                // rely on tap-to-interrupt or headset barge-in to avoid cutting off Aether's own voice.
                if (route == AudioOutputRoute.LOUDSPEAKER && !caps.isAecActive) {
                    return@launch
                }

                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@launch
                val shortBuf = ShortArray(bufSize / 2)

                // Calibrate residual speaker echo floor over ~300ms before arming trigger
                val calibrationSamples = mutableListOf<Double>()
                repeat(5) {
                    if (!isActive) return@launch
                    val n = record.read(shortBuf, 0, shortBuf.size)
                    if (n > 0) {
                        calibrationSamples.add(computeRms(shortBuf, n))
                    }
                    delay(55L)
                }
                val baselineRms = calibrationSamples.maxOrNull() ?: 900.0
                val (threshold, requiredFrames) = EchoGuardPolicy.computeBargeInThreshold(route, baselineRms)
                var loudFrames = 0

                while (isActive && isSpeakingOutput) {
                    val read = record.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        val rms = computeRms(shortBuf, read)
                        if (rms > threshold) {
                            loudFrames++
                            if (loudFrames >= requiredFrames) {
                                Log.i(TAG, "Barge-in triggered (route=$route, rms=${rms.toInt()}, thr=${threshold.toInt()})")
                                mainHandler.post {
                                    stopSpeakingInternal(notifyFinished = false)
                                    onBargeInDetected()
                                }
                                break
                            }
                        } else {
                            loudFrames = (loudFrames - 1).coerceAtLeast(0)
                        }
                    }
                    delay(55L)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Barge-in monitor stopped/unavailable: ${e.message}")
            } finally {
                effectController.release()
                runCatching {
                    if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                    record?.release()
                }
            }
        }
    }

    private fun computeRms(samples: ShortArray, count: Int): Double {
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        return sqrt(sumSq / count.coerceAtLeast(1))
    }

    private fun stopBargeInMonitor() {
        bargeInMonitorJob?.cancel()
        bargeInMonitorJob = null
    }

    private fun selectBestNaturalVoice(
        desiredLocale: Locale,
        persona: AssistantPersona
    ): android.speech.tts.Voice? {
        val voices = runCatching { tts?.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) return null
        val targetLang = desiredLocale.language.lowercase(Locale.US)
        val candidates = voices.filter { v ->
            v.locale?.language?.lowercase(Locale.US) == targetLang &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        if (candidates.isEmpty()) return null

        val prefersFemale = persona == AssistantPersona.ARIA
        return candidates.maxByOrNull { voice ->
            var score = 0
            val name = voice.name.lowercase(Locale.US)
            score += when {
                voice.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> 100
                voice.quality >= android.speech.tts.Voice.QUALITY_HIGH -> 75
                voice.quality >= android.speech.tts.Voice.QUALITY_NORMAL -> 40
                else -> 10
            }
            if (name.contains("neural") || name.contains("wavenet") || name.contains("-x-")) {
                score += 35
            }
            if (voice.locale?.country.equals(desiredLocale.country, ignoreCase = true)) {
                score += 20
            }
            val soundsFemale = name.contains("female") || name.contains("-sfg-") ||
                name.contains("-tpf-") || name.contains("-bnf-") || name.contains("-ban-")
            val soundsMale = name.contains("male") || name.contains("-iom-") ||
                name.contains("-tpd-") || name.contains("-bnm-")
            if (prefersFemale && soundsFemale) score += 18
            if (!prefersFemale && soundsMale) score += 18
            if (!voice.isNetworkConnectionRequired) score += 10
            score
        }
    }

    fun stopSpeaking() {
        runOnMain {
            stopSpeakingInternal(notifyFinished = false)
        }
    }

    private fun stopSpeakingInternal(notifyFinished: Boolean) {
        piperSpeakJob?.cancel()
        piperSpeakJob = null
        piperManager.stopPlayback()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        completeOutputSession(notifyFinished = notifyFinished)
    }

    private fun startSpeakingCadencePulse() {
        speakingPulseJob?.cancel()
        speakingPulseJob = scope.launch {
            var phase = 0f
            while (isActive) {
                phase += 0.45f
                val amp = (0.38f + 0.35f * kotlin.math.sin(phase) + 0.18f * kotlin.math.cos(phase * 2.3f))
                    .coerceIn(0.15f, 0.92f)
                onAmplitudeChanged(amp)
                delay(50L)
            }
        }
    }

    private fun stopSpeakingCadencePulse() {
        speakingPulseJob?.cancel()
        speakingPulseJob = null
        onAmplitudeChanged(0f)
    }

    fun release() {
        wakeRearmJob?.cancel()
        recognizerRetryJob?.cancel()
        echoGuardClearJob?.cancel()
        speakingSafetyJob?.cancel()
        piperSpeakJob?.cancel()
        piperManager.release()
        stopBargeInMonitor()
        stopSpeakingCadencePulse()
        if (commModeAcquiredForOutput) {
            commModeAcquiredForOutput = false
            commRouter.exitCommunicationMode()
        }
        runOnMain {
            try {
                destroyRecognizerQuietly()
                tts?.stop()
                tts?.shutdown()
                tts = null
            } catch (_: Exception) {
            }
        }
    }
}
