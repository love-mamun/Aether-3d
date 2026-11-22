package com.example.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
}

class VoiceAndTtsManager(
    context: Context,
    private val commRouter: CommunicationAudioRouter,
    private val piperManager: PiperNeuralVoiceManager,
    private val onAmplitudeChanged: (Float) -> Unit,
    private val onPartialSpeech: (String) -> Unit,
    private val onFinalSpeech: (String) -> Unit,
    private val onSpeechError: (String) -> Unit,
    private val onSpeakingStateChanged: (Boolean) -> Unit,
    private val onSpeakingFinished: () -> Unit,
    private val onWakeWordTriggered: (WakeWordMatch) -> Unit = {},
    private val onBargeInDetected: () -> Unit = {},
    private val onEchoGuardChanged: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakingPulseJob: Job? = null
    private var speakingSafetyJob: Job? = null
    private var piperSpeakJob: Job? = null
    private var bargeInMonitorJob: Job? = null
    private var wakeRearmJob: Job? = null
    private var echoGuardClearJob: Job? = null
    private var retriedWithFallbackLocale = false

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
    private var selectedEnglishVoiceId = "en_US-lessac-medium-int8"
    @Volatile
    private var activeWakeLocale = SpeechLocaleOption.EN_US
    @Volatile
    private var activeWakeCustomPhrase = "Hey Aether"
    @Volatile
    private var activeWakePersona = AssistantPersona.JARVIS

    init {
        mainHandler.post {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setAudioAttributes(commRouter.voiceCommunicationAttributes())
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
                                    startBargeInMonitor(commRouter.detectCurrentOutputRoute())
                                }
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                if (!EchoGuardPolicy.shouldFinishUtteranceBatch(utteranceId, activeUtteranceBatchId)) {
                                    // Intermediate sentence finished; keep half-duplex lock armed!
                                    return@post
                                }
                                completeOutputSession(notifyFinished = true)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                val batchId = activeUtteranceBatchId
                                if (batchId != null && utteranceId != null && !utteranceId.startsWith(batchId)) {
                                    return@post
                                }
                                completeOutputSession(notifyFinished = true)
                            }
                        }
                    })
                } else {
                    Log.w(TAG, "TTS initialization failed with status=$status")
                }
            }
        }
    }

    fun updateHandsFreeConfig(
        wakeWordEnabled: Boolean,
        bargeInEnabled: Boolean,
        customWakeWord: String,
        persona: AssistantPersona,
        localeOption: SpeechLocaleOption,
        voiceEngineMode: String = "AUTO",
        selectedVoiceId: String = "en_US-lessac-medium-int8",
        bargeInMode: String = "ADAPTIVE"
    ) {
        wakeWordEnabledConfig = wakeWordEnabled
        bargeInEnabledConfig = bargeInEnabled
        activeWakeCustomPhrase = customWakeWord
        activeWakePersona = persona
        activeWakeLocale = localeOption
        voiceEngineModeConfig = voiceEngineMode
        selectedEnglishVoiceId = selectedVoiceId
        bargeInModeConfig = bargeInMode
    }

    fun isSpeechRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(appContext)
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

    private fun completeOutputSession(notifyFinished: Boolean) {
        activeUtteranceBatchId = null
        isSpeakingOutput = false
        speakingSafetyJob?.cancel()
        stopBargeInMonitor()
        stopSpeakingCadencePulse()
        armEchoDecayGuard(ECHO_DECAY_GUARD_MS)
        commRouter.exitCommunicationMode()
        onSpeakingStateChanged(false)
        if (notifyFinished) {
            onSpeakingFinished()
        }
    }

    fun startWakeWordListening() {
        if (!wakeWordEnabledConfig || !isSpeechRecognitionAvailable()) return
        val hasMic = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return

        mainHandler.post {
            val now = SystemClock.elapsedRealtime()
            if (isSpeakingOutput) {
                Log.d(TAG, "Ignoring wake-word arm while output is active.")
                return@post
            }
            if (EchoGuardPolicy.isEchoGuardActive(now, echoGuardUntilMs)) {
                val remaining = (echoGuardUntilMs - now).coerceAtLeast(100L)
                isWakeWordMode = true
                scheduleWakeRearm(remaining + 60L)
                return@post
            }
            wakeRearmJob?.cancel()
            isWakeWordMode = true
            startRecognizerInternal(
                localeOption = activeWakeLocale,
                isWakePassive = true,
                isLocaleFallbackRetry = false
            )
        }
    }

    fun stopWakeWordListening() {
        mainHandler.post {
            wakeRearmJob?.cancel()
            if (isWakeWordMode) {
                isWakeWordMode = false
                runCatching { speechRecognizer?.cancel() }
                onAmplitudeChanged(0f)
            }
        }
    }

    fun startListening(localeOption: SpeechLocaleOption, isLocaleFallbackRetry: Boolean = false) {
        mainHandler.post {
            wakeRearmJob?.cancel()
            isWakeWordMode = false
            stopSpeaking()
            if (!isLocaleFallbackRetry) {
                retriedWithFallbackLocale = false
            }
            if (!isSpeechRecognitionAvailable()) {
                onSpeechError("Speech recognition service unavailable on this device. Use the command bar below.")
                return@post
            }
            startRecognizerInternal(
                localeOption = localeOption,
                isWakePassive = false,
                isLocaleFallbackRetry = isLocaleFallbackRetry
            )
        }
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
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
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

                        if ((error == 12 || error == 13 || error == SpeechRecognizer.ERROR_CLIENT) && !isLocaleFallbackRetry && !retriedWithFallbackLocale) {
                            retriedWithFallbackLocale = true
                            Log.w(TAG, "SpeechRecognizer error=$error for ${localeOption.languageTag}, retrying with en-US")
                            startListening(SpeechLocaleOption.EN_US, isLocaleFallbackRetry = true)
                            return
                        }
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                            SpeechRecognizer.ERROR_CLIENT -> "Voice recognizer unavailable. Type your command below."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Network unavailable for speech recognition."
                            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch any speech. Tap mic or type below."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy, please retry."
                            SpeechRecognizer.ERROR_SERVER -> "Speech service temporarily unreachable."
                            12, 13 -> "Selected voice language unavailable on this device."
                            else -> "Speech recognition error ($error)."
                        }
                        onSpeechError(msg)
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
                            onSpeechError("No speech recognized. Try again.")
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
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer", e)
            if (isWakePassive) {
                scheduleWakeRearm(2_000L)
            } else {
                onSpeechError(e.localizedMessage ?: "Could not start voice listener.")
            }
        }
    }

    private fun scheduleWakeRearm(delayMs: Long) {
        wakeRearmJob?.cancel()
        wakeRearmJob = scope.launch {
            delay(delayMs)
            val now = SystemClock.elapsedRealtime()
            if (isSpeakingOutput) {
                scheduleWakeRearm(600L)
                return@launch
            }
            if (EchoGuardPolicy.isEchoGuardActive(now, echoGuardUntilMs)) {
                delay((echoGuardUntilMs - now + 60L).coerceAtLeast(80L))
            }
            if (isWakeWordMode && wakeWordEnabledConfig && !isSpeakingOutput) {
                startRecognizerInternal(
                    localeOption = activeWakeLocale,
                    isWakePassive = true,
                    isLocaleFallbackRetry = false
                )
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            wakeRearmJob?.cancel()
            isWakeWordMode = false
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
            }
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
            mainHandler.post { completeOutputSession(notifyFinished = true) }
            return
        }

        mainHandler.post {
            // Hard half-duplex lock: terminate passive wake-word or active SpeechRecognizer immediately
            wakeRearmJob?.cancel()
            piperSpeakJob?.cancel()
            speakingSafetyJob?.cancel()
            stopBargeInMonitor()
            if (isWakeWordMode) {
                isWakeWordMode = false
            }
            runCatching { speechRecognizer?.cancel() }

            val batchId = UUID.randomUUID().toString()
            activeUtteranceBatchId = batchId
            val route = commRouter.enterCommunicationMode()

            val hasBanglaChars = clean.any { it in '\u0980'..'\u09FF' }
            val preferPiper = !hasBanglaChars &&
                localeOption != SpeechLocaleOption.BN_BD &&
                voiceEngineModeConfig != "SYSTEM"

            val installedPack = if (preferPiper) {
                piperManager.pickBestInstalledPack(selectedEnglishVoiceId, persona)
            } else {
                null
            }

            if (installedPack != null) {
                isSpeakingOutput = true
                onSpeakingStateChanged(true)
                startBargeInMonitor(route)
                piperSpeakJob = scope.launch {
                    val spokeOk = piperManager.speakWithPack(
                        text = clean,
                        pack = installedPack,
                        speed = persona.ttsRate,
                        audioAttributes = commRouter.voiceCommunicationAttributes(),
                        onAmplitude = { amp -> onAmplitudeChanged(amp) }
                    )
                    if (activeUtteranceBatchId == batchId) {
                        if (spokeOk) {
                            completeOutputSession(notifyFinished = true)
                        } else if (ttsReady) {
                            speakWithSystemTts(clean, persona, localeOption, batchId, hasBanglaChars, route)
                        } else {
                            completeOutputSession(notifyFinished = true)
                        }
                    }
                }
                return@post
            }

            if (!ttsReady) {
                completeOutputSession(notifyFinished = true)
                return@post
            }
            speakWithSystemTts(clean, persona, localeOption, batchId, hasBanglaChars, route)
        }
    }

    private fun speakWithSystemTts(
        clean: String,
        persona: AssistantPersona,
        localeOption: SpeechLocaleOption,
        batchId: String,
        hasBanglaChars: Boolean,
        route: AudioOutputRoute
    ) {
        try {
            val effectiveTag = if (hasBanglaChars) "bn-BD" else localeOption.languageTag
            val targetLocale = Locale.forLanguageTag(effectiveTag)

            tts?.setAudioAttributes(commRouter.voiceCommunicationAttributes())
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

            tts?.setPitch(persona.ttsPitch)
            tts?.setSpeechRate(persona.ttsRate)

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
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            var agc: AutomaticGainControl? = null
            try {
                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) return@launch
                val bufSize = (minBuf * 2).coerceAtLeast(2048)

                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) return@launch

                val sessionId = record.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = runCatching {
                        AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    }.getOrNull()
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = runCatching {
                        NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                    }.getOrNull()
                }
                if (AutomaticGainControl.isAvailable()) {
                    agc = runCatching {
                        AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                    }.getOrNull()
                }

                record.startRecording()
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
                                    stopSpeaking()
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
                runCatching { agc?.release() }
                runCatching { ns?.release() }
                runCatching { aec?.release() }
                runCatching {
                    record?.stop()
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

        val prefersFemale = persona == AssistantPersona.MAYRA
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
        mainHandler.post {
            piperSpeakJob?.cancel()
            piperSpeakJob = null
            piperManager.stopPlayback()
            try {
                tts?.stop()
            } catch (_: Exception) {
            }
            completeOutputSession(notifyFinished = false)
        }
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
        echoGuardClearJob?.cancel()
        speakingSafetyJob?.cancel()
        piperSpeakJob?.cancel()
        piperManager.release()
        stopBargeInMonitor()
        stopSpeakingCadencePulse()
        commRouter.exitCommunicationMode()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
                tts?.stop()
                tts?.shutdown()
                tts = null
            } catch (_: Exception) {
            }
        }
    }
}
