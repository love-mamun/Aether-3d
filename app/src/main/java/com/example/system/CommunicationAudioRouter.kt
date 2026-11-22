package com.example.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

private const val TAG = "CommAudioRouter"

enum class AudioOutputRoute {
    LOUDSPEAKER,
    HEADSET_OR_BLE
}

/**
 * Engages Android's communication audio pipeline (MODE_IN_COMMUNICATION + USAGE_VOICE_COMMUNICATION)
 * identical to VoIP/tactical apps (Messenger, imo, Free Fire), enabling platform HAL Acoustic Echo
 * Cancellation between Aether's AudioTrack/TTS output and AudioRecord(VOICE_COMMUNICATION) input.
 */
class CommunicationAudioRouter(context: Context) {

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile
    var isInCommunicationMode: Boolean = false
        private set

    fun voiceCommunicationAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    fun detectCurrentOutputRoute(): AudioOutputRoute {
        val am = audioManager ?: return AudioOutputRoute.LOUDSPEAKER
        val outputs = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrDefault(emptyArray())

        val hasHeadset = outputs.any { dev ->
            dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        return if (hasHeadset) AudioOutputRoute.HEADSET_OR_BLE else AudioOutputRoute.LOUDSPEAKER
    }

    fun enterCommunicationMode(): AudioOutputRoute {
        val am = audioManager ?: return AudioOutputRoute.LOUDSPEAKER
        val route = detectCurrentOutputRoute()
        runCatching {
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = am.availableCommunicationDevices
                val preferred = when (route) {
                    AudioOutputRoute.HEADSET_OR_BLE -> commDevices.firstOrNull { dev ->
                        dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                    AudioOutputRoute.LOUDSPEAKER -> commDevices.firstOrNull { dev ->
                        dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                }
                if (preferred != null) {
                    am.setCommunicationDevice(preferred)
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = (route == AudioOutputRoute.LOUDSPEAKER)
            }
            isInCommunicationMode = true
        }.onFailure { err ->
            Log.w(TAG, "enterCommunicationMode warning: ${err.message}")
        }
        return route
    }

    fun exitCommunicationMode() {
        val am = audioManager ?: return
        if (!isInCommunicationMode) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = AudioManager.MODE_NORMAL
            }
            isInCommunicationMode = false
        }.onFailure { err ->
            Log.w(TAG, "exitCommunicationMode warning: ${err.message}")
        }
    }
}
