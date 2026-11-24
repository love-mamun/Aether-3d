package com.example.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "CommAudioRouter"

enum class AudioOutputRoute {
    LOUDSPEAKER,
    HEADSET_OR_BLE
}

/**
 * Engages Android's communication audio pipeline (MODE_IN_COMMUNICATION + USAGE_VOICE_COMMUNICATION)
 * ONLY when active voice capture/speech requires hardware AEC, releasing back to MODE_NORMAL
 * cleanly so background YouTube/Spotify playback never stutters.
 */
class CommunicationAudioRouter(context: Context) {

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val activeClients = AtomicInteger(0)
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    @Volatile
    var isInCommunicationMode: Boolean = false
        private set

    fun isMusicPlaybackActive(): Boolean {
        val am = audioManager ?: return false
        if (runCatching { am.isMusicActive }.getOrDefault(false)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activeConfigs = runCatching { am.activePlaybackConfigurations }.getOrDefault(emptyList())
            return activeConfigs.any { cfg ->
                val usage = cfg.audioAttributes.usage
                usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
            }
        }
        return false
    }

    fun startMusicPlaybackWatcher(onMediaPlaybackChanged: (Boolean) -> Unit) {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback == null) {
            val cb = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    val mediaPlaying = configs.orEmpty().any { cfg ->
                        val usage = cfg.audioAttributes.usage
                        usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
                    } || runCatching { am.isMusicActive }.getOrDefault(false)
                    onMediaPlaybackChanged(mediaPlaying)
                }
            }
            playbackCallback = cb
            runCatching {
                am.registerAudioPlaybackCallback(cb, Handler(Looper.getMainLooper()))
            }
        }
    }

    fun stopMusicPlaybackWatcher() {
        val am = audioManager ?: return
        val cb = playbackCallback ?: return
        playbackCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { am.unregisterAudioPlaybackCallback(cb) }
        }
    }

    fun voiceCommunicationAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    fun assistantSpeechAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    fun shouldUseVoiceCommunicationForSpeechOutput(continuousMicActive: Boolean): Boolean {
        val am = audioManager ?: return false
        if (!continuousMicActive && !isInCommunicationMode) {
            return false
        }
        val voiceCallVol = runCatching { am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(1)
        val musicVol = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(1)
        if (voiceCallVol == 0 && musicVol > 0) {
            return false
        }
        return true
    }

    fun speechOutputAttributes(useCommunicationRoute: Boolean): AudioAttributes {
        return if (useCommunicationRoute) {
            voiceCommunicationAttributes()
        } else {
            assistantSpeechAttributes()
        }
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
        activeClients.incrementAndGet()
        val am = audioManager ?: return AudioOutputRoute.LOUDSPEAKER
        val route = detectCurrentOutputRoute()
        runCatching {
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
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
                val applied = preferred != null && runCatching { am.setCommunicationDevice(preferred) }.getOrDefault(false)
                if (!applied && route == AudioOutputRoute.LOUDSPEAKER) {
                    @Suppress("DEPRECATION")
                    runCatching { am.isSpeakerphoneOn = true }
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
        val remaining = activeClients.updateAndGet { cur -> (cur - 1).coerceAtLeast(0) }
        if (remaining > 0) {
            return
        }
        val am = audioManager ?: return
        if (!isInCommunicationMode) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { am.clearCommunicationDevice() }
            }
            @Suppress("DEPRECATION")
            runCatching { am.isSpeakerphoneOn = false }
            if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = AudioManager.MODE_NORMAL
            }
            isInCommunicationMode = false
        }.onFailure { err ->
            Log.w(TAG, "exitCommunicationMode warning: ${err.message}")
        }
    }
}
