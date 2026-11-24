package com.example.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.system.AudioOutputRoute
import com.example.system.CommunicationAudioRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "FilteredMicEngine"

/**
 * Captures microphone audio exclusively via AudioRecord(VOICE_COMMUNICATION) — never using
 * AudioPlaybackCapture or MediaProjection — applies hardware AEC/NS/AGC when supported plus
 * real-time VoiceIsolationDsp filtering, and emits clean 16 kHz WAV speech segments to the AI Agent.
 *
 * Designed for Android 13+ and budget/Unisoc HALs (such as Realme C35) where one or more
 * hardware AudioEffects may report unavailable or fail to initialize.
 */
class FilteredMicrophoneEngine(
    context: Context,
    private val commRouter: CommunicationAudioRouter
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var captureJob: Job? = null
    private val effectController = HardwareAudioEffectController()

    @Volatile
    private var activeAudioRecord: AudioRecord? = null

    private val _diagnostics = MutableStateFlow(AudioFilterDiagnostics())
    val diagnostics: StateFlow<AudioFilterDiagnostics> = _diagnostics.asStateFlow()

    val isCapturing: Boolean
        get() = _diagnostics.value.isCapturing

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts filtered microphone capture on Dispatchers.IO.
     *
     * @param continuousMode true when running inside VoiceAgentService foreground service;
     *                       false for single-utterance tap-to-speak capture.
     * @param isEchoGuardSuppressed returns true while TTS is actively speaking so output audio is gated.
     * @param onAmplitude emits filtered human-voice amplitude (0f..1f) for 3D orb visualization.
     * @param onSpeechSegmentReady invoked on Dispatchers.IO with filtered PCM + WAV ready for the AI Agent.
     * @param onCaptureError invoked if permission is missing or AudioRecord hardware cannot initialize.
     */
    @SuppressLint("MissingPermission")
    fun startCapture(
        continuousMode: Boolean,
        isEchoGuardSuppressed: () -> Boolean,
        onAmplitude: (Float) -> Unit,
        onSpeechSegmentReady: suspend (FilteredSpeechSegment) -> Unit,
        onCaptureError: (String) -> Unit
    ) {
        if (!hasRecordPermission()) {
            val msg = "Microphone permission (RECORD_AUDIO) is required for filtered voice capture."
            _diagnostics.value = _diagnostics.value.copy(
                isCapturing = false,
                continuousBackgroundMode = false,
                lastFailureReason = msg
            )
            onCaptureError(msg)
            return
        }

        // Prevent duplicate capture loops if already running in same mode
        if (captureJob?.isActive == true && _diagnostics.value.continuousBackgroundMode == continuousMode) {
            return
        }

        stopCapture()

        captureJob = scope.launch {
            val sampleRateHz = 16_000
            val frameSamples = 320 // 20 ms @ 16 kHz
            var audioRecord: AudioRecord? = null
            var acquiredCommMode = false
            var dsp: VoiceIsolationDsp? = null

            try {
                val route: AudioOutputRoute = commRouter.enterCommunicationMode()
                acquiredCommMode = true

                val minBufferBytes = AudioRecord.getMinBufferSize(
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                // Guard against ERROR (-1) / ERROR_BAD_VALUE (-2) on OEM HALs (e.g., Realme C35)
                val safeMinBytes = if (minBufferBytes > 0) minBufferBytes else frameSamples * 4
                val bufferSizeBytes = maxOf(safeMinBytes * 2, 4096)

                val (createdRecord, sourceLabelUsed) = createBestFilteredAudioRecord(sampleRateHz, bufferSizeBytes)
                audioRecord = createdRecord
                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    val errMsg = "AudioRecord(VOICE_COMMUNICATION) failed to initialize on this device."
                    _diagnostics.value = _diagnostics.value.copy(
                        isCapturing = false,
                        continuousBackgroundMode = false,
                        outputRouteLabel = route.name,
                        lastFailureReason = errMsg
                    )
                    onCaptureError(errMsg)
                    return@launch
                }

                activeAudioRecord = audioRecord

                val effectCaps = effectController.attachToSession(audioRecord.audioSessionId)
                val voiceDsp = VoiceIsolationDsp(
                    sampleRateHz = sampleRateHz,
                    frameSamples = frameSamples,
                    hardwareCapabilities = effectCaps
                )
                dsp = voiceDsp

                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    val errMsg = "AudioRecord did not enter RECORDSTATE_RECORDING (mic may be busy)."
                    _diagnostics.value = _diagnostics.value.copy(
                        isCapturing = false,
                        continuousBackgroundMode = false,
                        effects = effectCaps,
                        outputRouteLabel = route.name,
                        lastFailureReason = errMsg
                    )
                    onCaptureError(errMsg)
                    return@launch
                }

                var segmentsSent = _diagnostics.value.segmentsDispatchedCount
                _diagnostics.value = AudioFilterDiagnostics(
                    isCapturing = true,
                    continuousBackgroundMode = continuousMode,
                    audioSourceLabel = sourceLabelUsed,
                    sampleRateHz = sampleRateHz,
                    effects = effectCaps,
                    softwareDspActive = true,
                    outputRouteLabel = route.name,
                    segmentsDispatchedCount = segmentsSent,
                    lastFailureReason = null
                )

                val shortBuf = ShortArray(frameSamples)
                var consecutiveReadErrors = 0
                var uiPublishCounter = 0
                val captureStartedAtMs = System.currentTimeMillis()
                var sawAnySpeech = false

                while (currentCoroutineContext().isActive) {
                    val readCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        audioRecord.read(shortBuf, 0, frameSamples, AudioRecord.READ_BLOCKING)
                    } else {
                        audioRecord.read(shortBuf, 0, frameSamples)
                    }

                    if (readCount < 0) {
                        consecutiveReadErrors++
                        Log.w(TAG, "AudioRecord.read error code=$readCount (count=$consecutiveReadErrors)")
                        if (readCount == AudioRecord.ERROR_DEAD_OBJECT || consecutiveReadErrors >= 8) {
                            val errMsg = "Microphone stream interrupted (code $readCount)."
                            _diagnostics.value = _diagnostics.value.copy(lastFailureReason = errMsg)
                            onCaptureError(errMsg)
                            break
                        }
                        delay(25L)
                        continue
                    }

                    if (readCount == 0) {
                        delay(10L)
                        continue
                    }

                    consecutiveReadErrors = 0
                    val echoSuppressed = isEchoGuardSuppressed()
                    val frameResult = voiceDsp.processFrame(
                        inputFrame = shortBuf,
                        sampleCount = readCount,
                        echoGuardSuppressed = echoSuppressed
                    )
                    if (frameResult.isSpeechFrame) {
                        sawAnySpeech = true
                    }

                    onAmplitude(if (echoSuppressed) 0f else frameResult.normalizedAmplitude)

                    uiPublishCounter++
                    if (uiPublishCounter >= 4) { // ~80 ms UI diagnostics cadence
                        uiPublishCounter = 0
                        val currentRoute = commRouter.detectCurrentOutputRoute()
                        _diagnostics.value = _diagnostics.value.copy(
                            isCapturing = true,
                            continuousBackgroundMode = continuousMode,
                            rawRmsDb = VoiceIsolationDsp.rmsToDb(frameResult.rawRms),
                            filteredRmsDb = VoiceIsolationDsp.rmsToDb(frameResult.filteredRms),
                            noiseFloorDb = VoiceIsolationDsp.rmsToDb(frameResult.noiseFloorRms),
                            voiceConfidence = frameResult.voiceConfidence,
                            isSpeechDetected = frameResult.isSpeechFrame,
                            outputRouteLabel = currentRoute.name,
                            segmentsDispatchedCount = segmentsSent
                        )
                    }

                    if (!echoSuppressed) {
                        val completed = voiceDsp.feedForUtterance(frameResult)
                        if (completed != null) {
                            val wavBytes = AudioWavEncoder.encodePcm16MonoWav(
                                samples = completed.pcm16,
                                sampleRateHz = sampleRateHz
                            )
                            segmentsSent++
                            val snapshotDiag = _diagnostics.value.copy(
                                segmentsDispatchedCount = segmentsSent,
                                isSpeechDetected = false
                            )
                            _diagnostics.value = snapshotDiag

                            onSpeechSegmentReady(
                                FilteredSpeechSegment(
                                    pcm16 = completed.pcm16,
                                    wavBytes = wavBytes,
                                    durationMs = completed.durationMs,
                                    averageVoiceConfidence = completed.averageVoiceConfidence,
                                    peakNormalizedAmplitude = completed.peakNormalizedAmplitude,
                                    diagnostics = snapshotDiag
                                )
                            )

                            if (!continuousMode) {
                                break
                            }
                        } else if (!continuousMode && !sawAnySpeech &&
                            System.currentTimeMillis() - captureStartedAtMs >= 11_000L
                        ) {
                            onCaptureError("No speech detected. Tap mic to retry or type your command below.")
                            break
                        }
                    } else {
                        voiceDsp.flushPendingUtterance()
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO SecurityException during capture", se)
                val msg = "Microphone permission revoked or restricted by OS."
                _diagnostics.value = _diagnostics.value.copy(
                    isCapturing = false,
                    continuousBackgroundMode = false,
                    lastFailureReason = msg
                )
                onCaptureError(msg)
            } catch (t: Throwable) {
                Log.e(TAG, "FilteredMicrophoneEngine capture error", t)
                val msg = t.localizedMessage ?: "Filtered microphone capture failed."
                _diagnostics.value = _diagnostics.value.copy(
                    isCapturing = false,
                    continuousBackgroundMode = false,
                    lastFailureReason = msg
                )
                onCaptureError(msg)
            } finally {
                onAmplitude(0f)
                dsp?.reset()
                effectController.release()
                if (activeAudioRecord === audioRecord) {
                    activeAudioRecord = null
                }
                safeReleaseAudioRecord(audioRecord)
                if (acquiredCommMode) {
                    commRouter.exitCommunicationMode()
                }
                _diagnostics.value = _diagnostics.value.copy(
                    isCapturing = false,
                    continuousBackgroundMode = false,
                    isSpeechDetected = false,
                    voiceConfidence = 0f
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createBestFilteredAudioRecord(
        sampleRateHz: Int,
        bufferSizeBytes: Int
    ): Pair<AudioRecord?, String> {
        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.MIC to "MIC"
        )
        for ((audioSource, label) in candidates) {
            val record = buildAudioRecord(audioSource, sampleRateHz, bufferSizeBytes)
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                if (audioSource != MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    Log.w(TAG, "Fell back to AudioSource=$label after VOICE_COMMUNICATION failed to initialize")
                }
                return record to label
            }
            safeReleaseAudioRecord(record)
        }
        return null to "VOICE_COMMUNICATION"
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(
        audioSource: Int,
        sampleRateHz: Int,
        bufferSizeBytes: Int
    ): AudioRecord? {
        return runCatching {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()
        }.getOrElse { err ->
            Log.w(TAG, "AudioRecord.Builder failed for source=$audioSource (${err.message}); trying legacy constructor")
            runCatching {
                @Suppress("DEPRECATION")
                AudioRecord(
                    audioSource,
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeBytes
                )
            }.getOrNull()
        }
    }

    private fun safeReleaseAudioRecord(record: AudioRecord?) {
        if (record == null) return
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
        runCatching {
            record.release()
        }
    }

    fun stopCapture() {
        val job = captureJob
        captureJob = null
        val active = activeAudioRecord
        activeAudioRecord = null
        // Stop AudioRecord first so a blocking read() unblocks promptly on Android 13 / Unisoc HAL
        safeReleaseAudioRecord(active)
        job?.cancel()
        effectController.release()
        _diagnostics.value = _diagnostics.value.copy(
            isCapturing = false,
            continuousBackgroundMode = false,
            isSpeechDetected = false,
            voiceConfidence = 0f
        )
    }

    fun release() {
        stopCapture()
    }
}
