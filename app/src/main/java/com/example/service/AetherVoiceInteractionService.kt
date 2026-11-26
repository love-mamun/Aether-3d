package com.example.service

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Android system VoiceInteractionService entry point when Aether 3D is selected
 * as the device Default Digital Assistant.
 *
 * Strictly avoids Android's privileged DSP AlwaysOnHotwordDetector API
 * (CAPTURE_AUDIO_HOTWORD is restricted to signature|privileged system apps),
 * so selecting Aether as Default Assistant NEVER opens a silent background
 * microphone stream or crashes. Activation happens cleanly via OS assistant
 * gesture / power-key hold / Quick Tile / Floating Orb, or opt-in foreground KWS.
 */
class AetherVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "Aether VoiceInteractionService bound cleanly (zero idle mic standby)")
    }

    companion object {
        private const val TAG = "AetherVoiceInteraction"
    }
}
