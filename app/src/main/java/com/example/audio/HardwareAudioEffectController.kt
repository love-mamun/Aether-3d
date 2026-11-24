package com.example.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

private const val TAG = "HwAudioEffects"

/**
 * Safely probes, attaches, and releases Android hardware AudioEffects (AEC, NS, AGC)
 * on an AudioRecord session ID. Handles devices where effects report unavailable,
 * return null from create(), or throw on vendor HALs (e.g., Realme C35 / Unisoc / Android 13).
 */
class HardwareAudioEffectController {

    private var aec: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @Volatile
    var capabilities: AudioEffectCapabilities = AudioEffectCapabilities()
        private set

    fun attachToSession(audioSessionId: Int): AudioEffectCapabilities {
        release()
        if (audioSessionId <= 0) {
            capabilities = AudioEffectCapabilities(
                aecState = AudioEffectState.UNSUPPORTED,
                nsState = AudioEffectState.UNSUPPORTED,
                agcState = AudioEffectState.UNSUPPORTED
            )
            return capabilities
        }

        val (aecEffect, aecState) = tryEnableEffect(
            name = "AcousticEchoCanceler",
            isSupported = { AcousticEchoCanceler.isAvailable() },
            creator = { AcousticEchoCanceler.create(audioSessionId) }
        )
        aec = aecEffect

        val (nsEffect, nsState) = tryEnableEffect(
            name = "NoiseSuppressor",
            isSupported = { NoiseSuppressor.isAvailable() },
            creator = { NoiseSuppressor.create(audioSessionId) }
        )
        noiseSuppressor = nsEffect

        val (agcEffect, agcState) = tryEnableEffect(
            name = "AutomaticGainControl",
            isSupported = { AutomaticGainControl.isAvailable() },
            creator = { AutomaticGainControl.create(audioSessionId) }
        )
        agc = agcEffect

        capabilities = AudioEffectCapabilities(
            aecState = aecState,
            nsState = nsState,
            agcState = agcState
        )
        Log.i(
            TAG,
            "AudioEffect attachment session=$audioSessionId -> AEC=$aecState, NS=$nsState, AGC=$agcState"
        )
        return capabilities
    }

    private fun <T : AudioEffect> tryEnableEffect(
        name: String,
        isSupported: () -> Boolean,
        creator: () -> T?
    ): Pair<T?, AudioEffectState> {
        val supported = runCatching { isSupported() }.getOrElse { err ->
            Log.w(TAG, "$name.isAvailable() failed: ${err.message}")
            false
        }
        if (!supported) {
            return null to AudioEffectState.UNSUPPORTED
        }

        var created: T? = null
        return try {
            created = creator()
            if (created == null) {
                Log.w(TAG, "$name.create() returned null; falling back to software DSP")
                return null to AudioEffectState.INIT_FAILED
            }
            val result = created.setEnabled(true)
            val isEnabledNow = created.enabled
            if (result != AudioEffect.SUCCESS && !isEnabledNow) {
                Log.w(TAG, "$name.setEnabled(true) returned code=$result; releasing")
                runCatching { created.release() }
                null to AudioEffectState.INIT_FAILED
            } else if (!isEnabledNow) {
                Log.w(TAG, "$name reported enabled=false after setEnabled(true); releasing")
                runCatching { created.release() }
                null to AudioEffectState.INIT_FAILED
            } else {
                created to AudioEffectState.ACTIVE
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$name unsupported or failed on HAL: ${t.message}")
            runCatching { created?.release() }
            null to AudioEffectState.INIT_FAILED
        }
    }

    fun release() {
        runCatching { aec?.enabled = false }
        runCatching { aec?.release() }
        aec = null

        runCatching { noiseSuppressor?.enabled = false }
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

        runCatching { agc?.enabled = false }
        runCatching { agc?.release() }
        agc = null
    }
}
