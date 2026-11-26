package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.ai.CoreMode
import com.example.core.AgentOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VoiceAgentService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObserverJob: Job? = null
    private var isPaused = false
    private var floatingOverlay: FloatingOrbOverlay? = null
    private var overlayDismissedByUser = false
    private var lastNotificationStatus: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning = true
        val orchestrator = AgentOrchestrator.getInstance(this)
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val wakeTriggerMode = orchestrator.uiState.value.preferences.wakeTriggerMode

        // Satisfy Android O+ startForegroundService contract IMMEDIATELY on every start path
        val initialNotif = buildNotification(orchestrator)
        val fgsType = resolveForegroundServiceType(
            sdkInt = Build.VERSION.SDK_INT,
            hasMic = hasMic,
            wakeTriggerMode = wakeTriggerMode
        )
        val startedFg = try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotif, fgsType)
            true
        } catch (se: SecurityException) {
            Log.w("VoiceAgentService", "Primary FGS start rejected ($fgsType), retrying specialUse fallback", se)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        initialNotif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    true
                }.getOrDefault(false)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("VoiceAgentService", "startForeground failed", e)
            false
        }

        if (!startedFg) {
            orchestrator.stopFilteredBackgroundCapture()
            orchestrator.setBackgroundServiceState(active = false, paused = false)
            isServiceRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SPEAK -> {
                isPaused = false
                orchestrator.setBackgroundServiceState(active = true, paused = false)
                orchestrator.startListening()
            }
            ACTION_TOGGLE_PAUSE -> {
                isPaused = !isPaused
                orchestrator.setBackgroundServiceState(active = true, paused = isPaused)
                if (isPaused) {
                    orchestrator.stopListening()
                }
            }
            ACTION_SHOW_OVERLAY -> {
                overlayDismissedByUser = false
                orchestrator.setBackgroundServiceState(active = true, paused = isPaused)
                ensureOverlayAttached(orchestrator)
            }
            ACTION_STOP -> {
                floatingOverlay?.hide()
                orchestrator.abortAllTasks()
                orchestrator.setBackgroundServiceState(active = false, paused = false)
                isServiceRunning = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                isPaused = false
                overlayDismissedByUser = false
                orchestrator.setBackgroundServiceState(active = true, paused = false)
            }
        }

        val allowPassiveMic = hasMic &&
            wakeTriggerMode != com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY
        if (isPaused || !allowPassiveMic) {
            orchestrator.stopFilteredBackgroundCapture()
            orchestrator.voiceManager.stopWakeWordListening()
        } else {
            orchestrator.ensureBackgroundStandbyListening()
        }

        ensureOverlayAttached(orchestrator)

        if (stateObserverJob == null) {
            stateObserverJob = serviceScope.launch {
                orchestrator.uiState.collectLatest { state ->
                    if (!state.backgroundServiceActive) return@collectLatest

                    val activeCount = state.activeTasks.size
                    val doneCount = state.activeTasks.count { it.status.name == "DONE" }
                    val notifKey = "${isPaused}|${state.preferences.persona.name}|${state.preferences.wakeTriggerMode.name}|${state.coreMode.name}|$doneCount/$activeCount|${state.isWakeWordArmed}|${state.audioFilterDiagnostics.isCapturing}"
                    if (notifKey != lastNotificationStatus) {
                        lastNotificationStatus = notifKey
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(orchestrator)) }
                    }

                    if (!overlayDismissedByUser && Settings.canDrawOverlays(this@VoiceAgentService)) {
                        ensureOverlayAttached(orchestrator)
                        val reactStep = if (state.reActSubGoals.isNotEmpty()) {
                            val idx = (state.reActActiveSubGoalIndex + 1).coerceAtMost(state.reActSubGoals.size)
                            "Step $idx/${state.reActSubGoals.size} · ${state.reActProgressLabel.ifBlank { state.latestReply }}"
                        } else {
                            ""
                        }
                        val bubbleText = when {
                            state.isMacroRecording -> "Recording Macro (${state.macroStepCount} steps)..."
                            state.isLiveDuplexActive -> "Live Duplex · ${state.liveDuplexLabel}"
                            state.partialTranscript.isNotBlank() -> state.partialTranscript
                            state.errorMessage != null -> state.errorMessage
                            reactStep.isNotBlank() &&
                                (state.coreMode == CoreMode.MULTITASKING || state.coreMode == CoreMode.OBSERVING_SCREEN || state.reActWaitingForLoad) ->
                                reactStep
                            state.coreMode == CoreMode.MULTITASKING || state.coreMode == CoreMode.OBSERVING_SCREEN ->
                                state.latestReply.ifBlank { "Running step $doneCount/$activeCount..." }
                            state.coreMode == CoreMode.IDLE -> state.latestReply
                            else -> state.coreMode.statusLabel
                        }
                        floatingOverlay?.updateState(
                            coreMode = state.coreMode,
                            persona = state.preferences.persona,
                            message = bubbleText,
                            showBubble = state.coreMode != CoreMode.IDLE || state.isMacroRecording || state.isLiveDuplexActive || state.lastUserPrompt.isNotBlank() || state.reActWaitingForLoad,
                            isMacroRecording = state.isMacroRecording,
                            macroStepCount = state.macroStepCount,
                            visionSource = state.screenVisionSourceLabel,
                            isWaitingForLoad = state.reActWaitingForLoad
                        )
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun ensureOverlayAttached(orchestrator: AgentOrchestrator) {
        if (overlayDismissedByUser || !Settings.canDrawOverlays(this)) return
        if (floatingOverlay == null) {
            floatingOverlay = FloatingOrbOverlay(
                context = this,
                onOrbTapped = {
                    val mode = orchestrator.uiState.value.coreMode
                    if (mode == CoreMode.LISTENING) {
                        orchestrator.stopListening()
                    } else {
                        orchestrator.startListening()
                    }
                },
                onVisionScanTapped = {
                    orchestrator.submitCommand(
                        prompt = "See my screen and explain or help with what is visible",
                        sourceMode = "OVERLAY_VISION"
                    )
                },
                onMacroRecordTapped = {
                    orchestrator.toggleMacroRecording()
                },
                onBriefingTapped = {
                    orchestrator.runDailyBriefing()
                },
                onAbortTask = {
                    orchestrator.abortAllTasks()
                },
                onStopOverlay = {
                    overlayDismissedByUser = true
                    floatingOverlay?.hide()
                }
            )
        }
        floatingOverlay?.show()
    }

    override fun onDestroy() {
        isServiceRunning = false
        floatingOverlay?.hide()
        floatingOverlay = null
        val orchestrator = AgentOrchestrator.getInstance(this)
        orchestrator.stopFilteredBackgroundCapture()
        orchestrator.setBackgroundServiceState(active = false, paused = false)
        stateObserverJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(orchestrator: AgentOrchestrator): Notification {
        val state = orchestrator.uiState.value
        val activeCount = state.activeTasks.size
        val doneCount = state.activeTasks.count { it.status.name == "DONE" }

        val standbyLabel = when {
            state.preferences.wakeTriggerMode == com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY ->
                "0-Mic Assistant & Orb Ready"
            state.audioFilterDiagnostics.isCapturing -> "Filtered Mic Active"
            state.isWakeWordArmed -> "Say \"${state.preferences.customWakeWord}\""
            else -> "Standby Ready"
        }

        val statusText = when {
            isPaused -> "Agent Paused — Tap Resume or Speak"
            activeCount > 0 && state.coreMode == CoreMode.MULTITASKING ->
                "Working ($doneCount/$activeCount): ${state.latestReply.ifBlank { state.activeTasks.firstOrNull { it.status.name == "RUNNING" }?.step?.label ?: "Running" }}"
            else -> "${state.preferences.persona.displayName} · $standbyLabel"
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val speakPending = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceAgentService::class.java).setAction(ACTION_SPEAK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pausePending = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceAgentService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopPending = PendingIntent.getService(
            this,
            3,
            Intent(this, VoiceAgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aether_notif)
            .setContentTitle(getString(R.string.fg_service_title))
            .setContentText(statusText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.action_speak), speakPending)
            .addAction(0, getString(if (isPaused) R.string.action_resume else R.string.action_pause), pausePending)
            .addAction(0, getString(R.string.action_stop), stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fg_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.fg_service_channel_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "aether_agent_channel"
        const val NOTIFICATION_ID = 3001
        const val ACTION_SPEAK = "com.example.action.SPEAK"
        const val ACTION_TOGGLE_PAUSE = "com.example.action.TOGGLE_PAUSE"
        const val ACTION_SHOW_OVERLAY = "com.example.action.SHOW_OVERLAY"
        const val ACTION_STOP = "com.example.action.STOP"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        internal fun resolveForegroundServiceType(
            sdkInt: Int,
            hasMic: Boolean,
            wakeTriggerMode: com.example.data.local.WakeTriggerMode
        ): Int {
            val useMicType = hasMic &&
                wakeTriggerMode != com.example.data.local.WakeTriggerMode.ASSISTANT_BUTTON_ONLY
            return when {
                useMicType && sdkInt >= Build.VERSION_CODES.R ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else -> 0
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, VoiceAgentService::class.java).setAction(ACTION_SHOW_OVERLAY)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { err ->
                Log.w("VoiceAgentService", "Unable to start foreground service: ${err.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceAgentService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
