package com.example.voice

import android.annotation.SuppressLint
import android.util.Log
import com.example.ai.ActionType
import com.example.ai.AssistantPersona
import com.example.ai.PlannedStep
import com.example.system.AndroidActionRouter
import com.example.system.CommunicationAudioRouter
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "FullDuplexLive"

/**
 * Real-time bidirectional voice mode. Attempts Firebase AI Logic Gemini Live native audio
 * first under MODE_IN_COMMUNICATION; if project billing/quota is unavailable, triggers seamless
 * fallback to Echo-Guarded Local Duplex so full conversation still works everywhere.
 */
@OptIn(PublicPreviewAPI::class)
class FullDuplexLiveManager(
    private val commRouter: CommunicationAudioRouter,
    private val actionRouter: AndroidActionRouter,
    private val splitScreenProvider: () -> Boolean,
    private val onTranscriptUpdate: (userText: String, modelText: String) -> Unit,
    private val onStateChanged: (active: Boolean, modeLabel: String) -> Unit,
    private val onFallbackToLocalDuplex: (reason: String) -> Unit
) {

    private var liveSession: LiveSession? = null
    private var sessionJob: Job? = null

    @Volatile
    var isNativeLiveActive: Boolean = false
        private set

    fun startNativeGeminiLive(
        scope: CoroutineScope,
        persona: AssistantPersona,
        deviceTelemetrySummary: String,
        memoriesSummary: String
    ) {
        stop()
        sessionJob = scope.launch(Dispatchers.IO) {
            try {
                commRouter.enterCommunicationMode()
                onStateChanged(true, "Gemini Live · Connecting...")

                val voiceName = when (persona) {
                    AssistantPersona.ARIA -> "Aoede"
                    AssistantPersona.AETHER -> "Puck"
                }

                val runActionTool = FunctionDeclaration(
                    name = "run_device_action",
                    description = "Execute an Android OS or web tool action such as launching an app, toggling flashlight, searching YouTube, checking weather, or saving a memory.",
                    parameters = mapOf(
                        "actionType" to Schema.enumeration(
                            values = ActionType.entries.map { it.name },
                            description = "ActionType identifier"
                        ),
                        "target" to Schema.string(description = "App, contact, city, or UI target"),
                        "textPayload" to Schema.string(description = "Optional message, query, or note")
                    ),
                    optionalParameters = listOf("target", "textPayload")
                )

                val config = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    speechConfig = SpeechConfig(voice = Voice(voiceName))
                    temperature = 0.6f
                }

                val sysContent = content {
                    text(
                        buildString {
                            appendLine(persona.stylePrompt)
                            appendLine("You are Aether 3D in Full-Duplex Live Voice mode supporting English and Bangla.")
                            appendLine("Keep spoken replies crisp and conversational. Use run_device_action whenever the user asks to control Android or query live tools.")
                            appendLine("DEVICE TELEMETRY: $deviceTelemetrySummary")
                            if (memoriesSummary.isNotBlank()) {
                                appendLine("PINNED MEMORIES:\n$memoriesSummary")
                            }
                        }
                    )
                }

                val liveModel = Firebase.ai.liveModel(
                    modelName = "gemini-2.5-flash-native-audio-preview-12-2025",
                    generationConfig = config,
                    tools = listOf(Tool.functionDeclarations(listOf(runActionTool))),
                    systemInstruction = sysContent
                )

                val session = liveModel.connect()
                liveSession = session
                isNativeLiveActive = true
                onStateChanged(true, "Gemini Live Audio")

                startAudioSessionGuarded(session)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Live unavailable (${e.message}); falling back to Local Echo-Guarded Duplex.")
                cleanupSession()
                onFallbackToLocalDuplex(e.localizedMessage ?: "Gemini Live unavailable")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startAudioSessionGuarded(session: LiveSession) {
        session.startAudioConversation(
            functionCallHandler = { call ->
                val actionName = call.args["actionType"]?.jsonPrimitive?.contentOrNull ?: ActionType.SPEAK_ONLY.name
                val target = call.args["target"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val textPayload = call.args["textPayload"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val actionType = ActionType.entries.firstOrNull { it.name == actionName } ?: ActionType.SPEAK_ONLY

                val result = runBlocking {
                    actionRouter.executeStep(
                        step = PlannedStep(
                            actionType = actionType,
                            label = actionName,
                            target = target,
                            textPayload = textPayload
                        ),
                        splitScreenEnabled = splitScreenProvider()
                    )
                }

                FunctionResponsePart(
                    name = call.name,
                    response = buildJsonObject {
                        put("success", JsonPrimitive(result.success))
                        put("detail", JsonPrimitive(result.toolOutputForSummary ?: result.detail))
                    },
                    id = call.id
                )
            },
            transcriptHandler = { userTx, modelTx ->
                val u = userTx?.text.orEmpty()
                val m = modelTx?.text.orEmpty()
                if (u.isNotBlank() || m.isNotBlank()) {
                    onTranscriptUpdate(u, m)
                }
            },
            goAwayHandler = {
                Log.i(TAG, "Gemini Live GoAway received")
            },
            enableInterruptions = true
        )
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        cleanupSession()
        onStateChanged(false, "")
    }

    private fun cleanupSession() {
        isNativeLiveActive = false
        val session = liveSession
        liveSession = null
        if (session != null) {
            runCatching { session.stopAudioConversation() }
            runCatching { runBlocking { session.close() } }
        }
        commRouter.exitCommunicationMode()
    }
}
