package com.example.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class DspFrameResult(
    val processedFrame: ShortArray,
    val rawRms: Float,
    val filteredRms: Float,
    val noiseFloorRms: Float,
    val voiceConfidence: Float,
    val isSpeechFrame: Boolean,
    val normalizedAmplitude: Float
)

/**
 * Pure-Kotlin real-time Voice Isolation DSP pipeline designed to suppress YouTube,
 * music, gaming SFX, and speaker playback bleed while prioritizing clear human speech.
 *
 * Operates on 16 kHz mono 16-bit PCM frames (320 samples = 20 ms per frame):
 * 1. 2nd-order Butterworth High-Pass Filter (~135 Hz) removes speaker bass & rumble.
 * 2. 2nd-order Butterworth Low-Pass Filter (~3450 Hz) removes HF gaming/music hiss.
 * 3. Adaptive noise floor estimator + speech-band / ZCR voice confidence scoring.
 * 4. Smooth downward noise gate attenuating background playback up to -18 dB.
 * 5. Voice-only Software AGC + soft-knee limiter (boosted automatically if HW AGC is missing).
 */
class VoiceIsolationDsp(
    val sampleRateHz: Int = 16_000,
    val frameSamples: Int = 320,
    private val hardwareCapabilities: AudioEffectCapabilities = AudioEffectCapabilities()
) {

    private val highPass = BiquadFilter.highPass(sampleRateHz.toDouble(), cutoffHz = 135.0, q = 0.7071)
    private val lowPass = BiquadFilter.lowPass(sampleRateHz.toDouble(), cutoffHz = 3450.0, q = 0.7071)

    private var adaptiveNoiseFloor = 140.0
    private var smoothedGateGain = 0.25f
    private var softwareAgcGain = 1.0f

    // Pre-roll + VAD utterance segmentation state
    private val preRollCapacity = 15 // ~300ms
    private val preRollFrames = ArrayDeque<ShortArray>(preRollCapacity + 1)
    private val activeUtterance = ArrayList<Short>()
    private var inSpeech = false
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    private var speechConfidenceSum = 0f
    private var speechFrameCount = 0
    private var utterancePeakAmp = 0f

    private val maxUtteranceSamples = sampleRateHz * 12 // 12 seconds max segment
    private val minUtteranceSamples = (sampleRateHz * 0.35f).toInt() // 350 ms minimum speech
    private val onsetFramesRequired = 3 // 60 ms clear speech onset
    private val hangoverSilenceFrames = 45 // 900 ms post-speech silence

    fun reset() {
        highPass.reset()
        lowPass.reset()
        adaptiveNoiseFloor = 140.0
        smoothedGateGain = 0.25f
        softwareAgcGain = 1.0f
        preRollFrames.clear()
        activeUtterance.clear()
        inSpeech = false
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
        speechConfidenceSum = 0f
        speechFrameCount = 0
        utterancePeakAmp = 0f
    }

    fun processFrame(
        inputFrame: ShortArray,
        sampleCount: Int = inputFrame.size,
        echoGuardSuppressed: Boolean = false
    ): DspFrameResult {
        val count = min(sampleCount, inputFrame.size).coerceAtLeast(1)
        val bandFiltered = FloatArray(count)

        var rawSumSq = 0.0
        var bandSumSq = 0.0
        var outOfBandSumSq = 0.0
        var zeroCrossings = 0
        var prevSample = 0f

        for (i in 0 until count) {
            val raw = inputFrame[i].toDouble()
            rawSumSq += raw * raw

            val hp = highPass.process(raw)
            val bp = lowPass.process(hp).toFloat()
            bandFiltered[i] = bp
            bandSumSq += bp.toDouble() * bp.toDouble()

            val rejected = raw - bp.toDouble()
            outOfBandSumSq += rejected * rejected

            if (i > 0 && ((bp >= 0f && prevSample < 0f) || (bp < 0f && prevSample >= 0f))) {
                zeroCrossings++
            }
            prevSample = bp
        }

        val rawRms = sqrt(rawSumSq / count).toFloat()
        val bandRms = sqrt(bandSumSq / count).toFloat()
        val outOfBandRms = sqrt(outOfBandSumSq / count).toFloat()
        val zcr = zeroCrossings.toFloat() / count.toFloat()

        // Speech at 16 kHz typically concentrates energy in 135-3450 Hz with moderate ZCR (0.025..0.32)
        val bandFocusRatio = (bandRms / (bandRms + outOfBandRms + 1f)).coerceIn(0f, 1f)
        val zcrVoiceScore = when {
            zcr in 0.022f..0.32f -> 1.0f
            zcr in 0.012f..0.44f -> 0.60f
            else -> 0.15f
        }

        val snrThreshold = adaptiveNoiseFloor * if (hardwareCapabilities.softwareNoiseSuppressionBoostNeeded) 1.60 else 1.45
        val energyRatioScore = ((bandRms - adaptiveNoiseFloor * 0.80) / (adaptiveNoiseFloor * 1.6 + 35.0))
            .toFloat()
            .coerceIn(0f, 1f)

        val voiceConfidence = if (echoGuardSuppressed) {
            0f
        } else {
            (0.45f * energyRatioScore + 0.35f * bandFocusRatio + 0.20f * zcrVoiceScore).coerceIn(0f, 1f)
        }

        val isLikelyVoice = !echoGuardSuppressed &&
            bandRms > max(125.0, snrThreshold) &&
            bandFocusRatio >= 0.40f &&
            voiceConfidence >= 0.42f

        // Adapt background noise floor only when frame is NOT active human voice
        if (!isLikelyVoice && !echoGuardSuppressed) {
            val targetFloor = bandRms.toDouble().coerceIn(55.0, 4500.0)
            val rate = if (targetFloor < adaptiveNoiseFloor) 0.14 else 0.025
            adaptiveNoiseFloor += (targetFloor - adaptiveNoiseFloor) * rate
        }

        // Smooth noise gate attenuation to reduce YouTube/music/gaming bleed without clicks
        val floorAtten = if (hardwareCapabilities.softwareNoiseSuppressionBoostNeeded) 0.10f else 0.16f
        val targetGateGain = when {
            echoGuardSuppressed -> 0.05f
            isLikelyVoice -> 1.0f
            voiceConfidence > 0.35f -> 0.35f
            else -> floorAtten
        }
        val gateAlpha = if (targetGateGain > smoothedGateGain) 0.45f else 0.14f
        smoothedGateGain += (targetGateGain - smoothedGateGain) * gateAlpha

        // Software Voice-Focus AGC: boost quiet clear speech only, never amplify background noise
        val maxSwAgc = if (hardwareCapabilities.softwareAgcBoostNeeded) 2.8f else 1.45f
        val targetSpeechRms = 3400f
        val desiredAgc = if (isLikelyVoice && bandRms > 120f) {
            (targetSpeechRms / bandRms).coerceIn(0.85f, maxSwAgc)
        } else {
            1.0f
        }
        softwareAgcGain += (desiredAgc - softwareAgcGain) * 0.12f

        val totalGain = smoothedGateGain * softwareAgcGain
        val output = ShortArray(count)
        var filteredSumSq = 0.0
        for (i in 0 until count) {
            val scaled = bandFiltered[i] * totalGain
            // Soft-knee limiter prevents harsh digital clipping on loud voice peaks
            val limited = if (abs(scaled) > 24000f) {
                (24000f * tanh((scaled / 24000f).toDouble())).toFloat()
            } else {
                scaled
            }
            val clamped = limited.toInt().coerceIn(-32767, 32767).toShort()
            output[i] = clamped
            filteredSumSq += clamped.toDouble() * clamped.toDouble()
        }

        val filteredRms = sqrt(filteredSumSq / count).toFloat()
        val normalizedAmp = if (isLikelyVoice || filteredRms > adaptiveNoiseFloor * 1.4) {
            (filteredRms / 6500f).coerceIn(0f, 1f)
        } else {
            (filteredRms / 18000f).coerceIn(0f, 0.08f)
        }

        return DspFrameResult(
            processedFrame = output,
            rawRms = rawRms,
            filteredRms = filteredRms,
            noiseFloorRms = adaptiveNoiseFloor.toFloat(),
            voiceConfidence = voiceConfidence,
            isSpeechFrame = isLikelyVoice,
            normalizedAmplitude = normalizedAmp
        )
    }

    /**
     * Feeds a processed frame into the VAD utterance segmenter.
     * Returns a completed PCM16 utterance array when clear human speech finishes, or null while listening.
     */
    fun feedForUtterance(frameResult: DspFrameResult): CompletedUtterancePcm? {
        val frame = frameResult.processedFrame

        if (!inSpeech) {
            if (preRollFrames.size >= preRollCapacity) {
                preRollFrames.removeFirst()
            }
            preRollFrames.addLast(frame)

            if (frameResult.isSpeechFrame) {
                consecutiveSpeechFrames++
                if (consecutiveSpeechFrames >= onsetFramesRequired) {
                    inSpeech = true
                    consecutiveSilenceFrames = 0
                    speechConfidenceSum = frameResult.voiceConfidence
                    speechFrameCount = 1
                    utterancePeakAmp = frameResult.normalizedAmplitude
                    activeUtterance.clear()
                    for (pre in preRollFrames) {
                        for (s in pre) activeUtterance.add(s)
                    }
                    preRollFrames.clear()
                }
            } else {
                consecutiveSpeechFrames = 0
            }
            return null
        }

        // Currently inside active human speech utterance
        for (s in frame) {
            activeUtterance.add(s)
        }
        if (frameResult.normalizedAmplitude > utterancePeakAmp) {
            utterancePeakAmp = frameResult.normalizedAmplitude
        }

        if (frameResult.isSpeechFrame) {
            consecutiveSilenceFrames = 0
            speechConfidenceSum += frameResult.voiceConfidence
            speechFrameCount++
        } else {
            consecutiveSilenceFrames++
        }

        val reachedMaxLen = activeUtterance.size >= maxUtteranceSamples
        val reachedSilenceEnd = consecutiveSilenceFrames >= hangoverSilenceFrames

        if (!reachedMaxLen && !reachedSilenceEnd) {
            return null
        }

        return finalizeCurrentUtterance()
    }

    fun flushPendingUtterance(): CompletedUtterancePcm? {
        if (!inSpeech) return null
        return finalizeCurrentUtterance()
    }

    private fun finalizeCurrentUtterance(): CompletedUtterancePcm? {
        val capturedSize = activeUtterance.size
        val avgConf = if (speechFrameCount > 0) speechConfidenceSum / speechFrameCount else 0f
        val peakAmp = utterancePeakAmp

        val pcm = if (capturedSize >= minUtteranceSamples && speechFrameCount >= onsetFramesRequired) {
            ShortArray(capturedSize) { idx -> activeUtterance[idx] }
        } else {
            null
        }

        activeUtterance.clear()
        inSpeech = false
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
        speechConfidenceSum = 0f
        speechFrameCount = 0
        utterancePeakAmp = 0f

        return pcm?.let {
            val durationMs = (it.size * 1000L) / sampleRateHz
            CompletedUtterancePcm(
                pcm16 = it,
                durationMs = durationMs,
                averageVoiceConfidence = avgConf,
                peakNormalizedAmplitude = peakAmp
            )
        }
    }

    companion object {
        fun rmsToDb(rms: Float): Float {
            val safe = max(rms, 1f)
            return (20.0 * ln(safe / 32767.0) / ln(10.0)).toFloat().coerceIn(-90f, 0f)
        }
    }
}

data class CompletedUtterancePcm(
    val pcm16: ShortArray,
    val durationMs: Long,
    val averageVoiceConfidence: Float,
    val peakNormalizedAmplitude: Float
)

private class BiquadFilter(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x0: Double): Double {
        val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0
        return y0
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    companion object {
        fun highPass(sampleRateHz: Double, cutoffHz: Double, q: Double): BiquadFilter {
            val w0 = 2.0 * PI * cutoffHz / sampleRateHz
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = ((1.0 + cosW0) / 2.0) / a0
            val b1 = (-(1.0 + cosW0)) / a0
            val b2 = ((1.0 + cosW0) / 2.0) / a0
            val a1 = (-2.0 * cosW0) / a0
            val a2 = (1.0 - alpha) / a0
            return BiquadFilter(b0, b1, b2, a1, a2)
        }

        fun lowPass(sampleRateHz: Double, cutoffHz: Double, q: Double): BiquadFilter {
            val w0 = 2.0 * PI * cutoffHz / sampleRateHz
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = ((1.0 - cosW0) / 2.0) / a0
            val b1 = (1.0 - cosW0) / a0
            val b2 = ((1.0 - cosW0) / 2.0) / a0
            val a1 = (-2.0 * cosW0) / a0
            val a2 = (1.0 - alpha) / a0
            return BiquadFilter(b0, b1, b2, a1, a2)
        }
    }
}
