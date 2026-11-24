package com.example.audio

enum class AudioEffectState(val label: String) {
    PENDING("Pending"),
    ACTIVE("HW Active"),
    UNSUPPORTED("Unsupported · SW DSP"),
    INIT_FAILED("HAL Fallback · SW DSP")
}

data class AudioEffectCapabilities(
    val aecState: AudioEffectState = AudioEffectState.PENDING,
    val nsState: AudioEffectState = AudioEffectState.PENDING,
    val agcState: AudioEffectState = AudioEffectState.PENDING
) {
    val isAecActive: Boolean get() = aecState == AudioEffectState.ACTIVE
    val isNsActive: Boolean get() = nsState == AudioEffectState.ACTIVE
    val isAgcActive: Boolean get() = agcState == AudioEffectState.ACTIVE

    val softwareNoiseSuppressionBoostNeeded: Boolean get() = !isNsActive
    val softwareAgcBoostNeeded: Boolean get() = !isAgcActive
    val softwareEchoSuppressionActive: Boolean get() = !isAecActive

    fun summaryBadge(): String {
        val hwParts = buildList {
            if (isAecActive) add("AEC")
            if (isNsActive) add("NS")
            if (isAgcActive) add("AGC")
        }
        return if (hwParts.isEmpty()) {
            "SW Voice DSP Active"
        } else {
            "${hwParts.joinToString("+")} + Voice DSP"
        }
    }
}

data class AudioFilterDiagnostics(
    val isCapturing: Boolean = false,
    val continuousBackgroundMode: Boolean = false,
    val audioSourceLabel: String = "VOICE_COMMUNICATION",
    val sampleRateHz: Int = 16_000,
    val effects: AudioEffectCapabilities = AudioEffectCapabilities(),
    val softwareDspActive: Boolean = true,
    val rawRmsDb: Float = -60f,
    val filteredRmsDb: Float = -60f,
    val noiseFloorDb: Float = -55f,
    val voiceConfidence: Float = 0f,
    val isSpeechDetected: Boolean = false,
    val outputRouteLabel: String = "LOUDSPEAKER",
    val segmentsDispatchedCount: Int = 0,
    val lastFailureReason: String? = null
)

class FilteredSpeechSegment(
    val pcm16: ShortArray,
    val wavBytes: ByteArray,
    val durationMs: Long,
    val averageVoiceConfidence: Float,
    val peakNormalizedAmplitude: Float,
    val diagnostics: AudioFilterDiagnostics
)
