package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

private const val TAG = "GeminiCommandService"

class GeminiCommandService(
    private val nvidiaFallback: NvidiaFallbackService = NvidiaFallbackService(),
    private val geminiApiKeyProvider: (() -> String)? = null,
    private val geminiModelProvider: (() -> String)? = null
) {

    private val directGeminiClient = DirectGeminiRestClient(
        apiKeyProvider = { geminiApiKeyProvider?.invoke().orEmpty() },
        modelProvider = { geminiModelProvider?.invoke().orEmpty() }
    )

    @Volatile
    private var geminiBillingUnavailable = false

    private val modelCooldownUntilMs = mutableMapOf<String, Long>()

    private val stepSchema = Schema.obj(
        properties = mapOf(
            "stepId" to Schema.string(description = "Short step ID such as s0, s1, s2 for DAG dependencies"),
            "dependsOn" to Schema.array(
                items = Schema.string(description = "Prerequisite stepId that must succeed first"),
                description = "Optional prerequisite step IDs (empty array if independent)"
            ),
            "timeoutMs" to Schema.integer(description = "Optional max timeout in milliseconds (e.g. 8000)"),
            "actionType" to Schema.enumeration(
                values = ActionType.entries.map { it.name },
                description = "Exact action identifier to execute on Android or via tools"
            ),
            "label" to Schema.string(description = "Short 2-5 word HUD label for this step"),
            "target" to Schema.string(
                description = "App name, contact name/number, city/URL/query, settings panel (wifi/bluetooth/display/sound/battery/location/general), scroll direction (up/down), global action (HOME/BACK/RECENTS/NOTIFICATIONS/QUICK_SETTINGS/LOCK_SCREEN/TAKE_SCREENSHOT), or UI button text"
            ),
            "textPayload" to Schema.string(
                description = "Message body, alarm label, email body/subject, memory text, routine prompt, currency target code, or text to type into an active UI field"
            ),
            "recipient" to Schema.string(
                description = "Exact contact name or handle for SEND_APP_MESSAGE / SEND_MESSAGE (e.g. 'Ma', 'Mom', 'Rahul')"
            ),
            "intValue" to Schema.integer(
                description = "Hour (0-23) for alarm, duration seconds for timer, volume/brightness percent (0-100), or normalized X coordinate (0-1000) for UI_TAP_COORDINATES"
            ),
            "secondaryIntValue" to Schema.integer(
                description = "Minute (0-59) for alarm, or normalized Y coordinate (0-1000) for UI_TAP_COORDINATES"
            ),
            "doubleValue" to Schema.double(
                description = "Numeric amount for CURRENCY_CONVERT"
            ),
            "boolValue" to Schema.boolean(
                description = "True for ON/ENABLE (torch on, auto-rotate on), false for OFF/DISABLE"
            ),
            "isBackgroundParallel" to Schema.boolean(
                description = "True if this step can run concurrently in the background (weather, wiki, currency, memory save, torch, volume, brightness, alarm, timer); false for foreground UI or app launches"
            ),
            "isSensitive" to Schema.boolean(
                description = "True for dialing calls, sending messages/emails, locking screen, or tapping irreversible UI buttons"
            )
        ),
        optionalProperties = listOf(
            "stepId",
            "dependsOn",
            "timeoutMs",
            "target",
            "textPayload",
            "recipient",
            "intValue",
            "secondaryIntValue",
            "doubleValue",
            "boolValue",
            "isBackgroundParallel",
            "isSensitive"
        )
    )

    private val planSchema = Schema.obj(
        properties = mapOf(
            "transcript" to Schema.string(
                description = "Exact transcription of the user's spoken audio command when audio input is provided"
            ),
            "reply" to Schema.string(
                description = "Natural, concise spoken answer directly answering the user's question or confirming their requested action in English or Bangla. NEVER read aloud or describe screen UI elements, OCR text, or visible screen layout unless the user explicitly asked what is on their screen."
            ),
            "needsScreenObservation" to Schema.boolean(
                description = "Set true ONLY if performing the requested UI action requires observing/clicking the current screen and no screen snapshot is attached yet. Keep false for normal Q&A, chat, knowledge, or direct commands."
            ),
            "continueLoopAfterAction" to Schema.boolean(
                description = "Set true if executing the UI step opens a new screen that requires another observe-act ReAct step to finish the user's multi-step goal"
            ),
            "subGoals" to Schema.array(
                items = Schema.string(description = "Ordered short milestone label (e.g. 'Open YouTube', 'Search channel', 'Open Videos tab', 'Play first video')"),
                description = "Ordered sub-goal checklist whenever the command requires 2+ sequential UI navigation hops inside an app"
            ),
            "steps" to Schema.array(
                items = stepSchema,
                description = "Ordered DAG of 1 to 6 executable actions. Use SPEAK_ONLY when purely answering a question."
            )
        ),
        optionalProperties = listOf("transcript", "needsScreenObservation", "continueLoopAfterAction", "subGoals")
    )

    private val plannerModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.35f
                responseMimeType = "application/json"
                responseSchema = planSchema
            }
        )
    }

    private val unconstrainedModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.4f
            }
        )
    }

    private val fallbackLiteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                temperature = 0.4f
            }
        )
    }

    private val summaryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.45f
            }
        )
    }

    suspend fun planAudioCommand(
        wavAudioBytes: ByteArray,
        persona: AssistantPersona,
        deviceContextSummary: String,
        memoriesSummary: String,
        routinesSummary: String,
        contactsSummary: String,
        installedAppsSummary: String,
        conversationHistorySummary: String = "",
        conversationTurns: List<Pair<String, String>> = emptyList(),
        screenUiSummary: String? = null,
        screenBitmap: Bitmap? = null,
        ocrSummary: String? = null,
        preferNvidiaPrimary: Boolean = false,
        onStreamingReply: ((String) -> Unit)? = null,
        onActiveModelChanged: ((String) -> Unit)? = null
    ): Result<CommandPlan> {
        return planCommandInternal(
            userPrompt = "Listen carefully to the attached filtered microphone audio (16 kHz mono WAV). Ignore any faint residual background noise, transcribe the human speaker's command into the 'transcript' field, and produce the CommandPlan JSON.",
            persona = persona,
            deviceContextSummary = deviceContextSummary,
            memoriesSummary = memoriesSummary,
            routinesSummary = routinesSummary,
            contactsSummary = contactsSummary,
            installedAppsSummary = installedAppsSummary,
            conversationHistorySummary = conversationHistorySummary,
            conversationTurns = conversationTurns,
            screenUiSummary = screenUiSummary,
            screenBitmap = screenBitmap,
            ocrSummary = ocrSummary,
            audioWavBytes = wavAudioBytes,
            preferNvidiaPrimary = preferNvidiaPrimary,
            onStreamingReply = onStreamingReply,
            onActiveModelChanged = onActiveModelChanged
        )
    }

    suspend fun planCommand(
        userPrompt: String,
        persona: AssistantPersona,
        deviceContextSummary: String,
        memoriesSummary: String,
        routinesSummary: String,
        contactsSummary: String,
        installedAppsSummary: String,
        screenUiSummary: String?,
        screenBitmap: Bitmap?,
        ocrSummary: String? = null,
        conversationHistorySummary: String = "",
        conversationTurns: List<Pair<String, String>> = emptyList(),
        preferNvidiaPrimary: Boolean = true,
        compactLoopMode: Boolean = false,
        onStreamingReply: ((String) -> Unit)? = null,
        onActiveModelChanged: ((String) -> Unit)? = null
    ): Result<CommandPlan> {
        return planCommandInternal(
            userPrompt = userPrompt,
            persona = persona,
            deviceContextSummary = deviceContextSummary,
            memoriesSummary = memoriesSummary,
            routinesSummary = routinesSummary,
            contactsSummary = contactsSummary,
            installedAppsSummary = installedAppsSummary,
            conversationHistorySummary = conversationHistorySummary,
            conversationTurns = conversationTurns,
            screenUiSummary = screenUiSummary,
            screenBitmap = screenBitmap,
            ocrSummary = ocrSummary,
            audioWavBytes = null,
            preferNvidiaPrimary = preferNvidiaPrimary,
            compactLoopMode = compactLoopMode,
            onStreamingReply = onStreamingReply,
            onActiveModelChanged = onActiveModelChanged
        )
    }

    private suspend fun planCommandInternal(
        userPrompt: String,
        persona: AssistantPersona,
        deviceContextSummary: String,
        memoriesSummary: String,
        routinesSummary: String,
        contactsSummary: String,
        installedAppsSummary: String,
        conversationHistorySummary: String,
        conversationTurns: List<Pair<String, String>>,
        screenUiSummary: String?,
        screenBitmap: Bitmap?,
        ocrSummary: String?,
        audioWavBytes: ByteArray?,
        preferNvidiaPrimary: Boolean,
        compactLoopMode: Boolean = false,
        onStreamingReply: ((String) -> Unit)?,
        onActiveModelChanged: ((String) -> Unit)?
    ): Result<CommandPlan> {
        val fullInstruction = if (compactLoopMode) {
            buildString {
                appendLine("Aether 3D Screen Navigation Step. Output strictly JSON CommandPlan.")
                appendLine("Pick ONE concrete UI action (UI_TAP_COORDINATES, UI_TAP_TEXT, UI_INPUT_TEXT, UI_SCROLL, or GLOBAL_ACTION) to complete [CURRENT]. Set continueLoopAfterAction=true if more sub-goals remain, else SPEAK_ONLY with continueLoopAfterAction=false once final goal is visibly complete.")
                if (!screenUiSummary.isNullOrBlank()) {
                    appendLine("\nSCREEN CONTEXT:\n$screenUiSummary")
                }
                if (!ocrSummary.isNullOrBlank()) {
                    appendLine("\n$ocrSummary")
                }
                appendLine("\nSTEP GOAL: $userPrompt")
            }
        } else {
            buildString {
                appendLine(persona.stylePrompt)
                appendLine("You are Aether 3D, a multitasking Android OS agent supporting Bangla (বাংলা), Banglish, and English. Convert the user's request into a structured CommandPlan JSON.")
                appendLine("Rules:")
                appendLine("1. Support multitasking & Task DAG dependencies: assign short stepId ('s0', 's1'...) and populate dependsOn when a step relies on an earlier step, while keeping independent tools (WEATHER_LOOKUP, WIKIPEDIA_LOOKUP, CURRENCY_CONVERT, SAVE_MEMORY, CREATE_ROUTINE, TOGGLE_TORCH, TOGGLE_DND, ADJUST_VOLUME, SET_BRIGHTNESS, SET_ALARM, SET_TIMER) parallel with isBackgroundParallel=true.")
                appendLine("2. CRITICAL FOR FACEBOOK / SOCIAL MEDIA POSTS ('Facebook khule akta post koro I am happy today', 'post ... on Facebook/Instagram'): emit POST_SOCIAL with target='Facebook' (or app name), textPayload=exact post text (e.g. 'I am happy today'), isSensitive=false, continueLoopAfterAction=false! Our autonomous social UI driver will open Facebook, tap the composer box, type the post, and tap Post.")
                appendLine("3. CRITICAL FOR IN-APP MESSAGING ('WhatsApp a ma ke message koro ...', 'send message to Mom on WhatsApp/Telegram/Messenger'): emit SEND_APP_MESSAGE with target=app name ('WhatsApp'), recipient=contact name ('Ma'), textPayload=exact message body, isSensitive=false, continueLoopAfterAction=false!")
                appendLine("4. YOUTUBE PLAYBACK vs MULTI-HOP NAVIGATION: For a simple single song/video request ('play song X on YouTube', 'YouTube a X gan chalao'), use PLAY_MEDIA with target='YouTube', textPayload=query. HOWEVER, if the user asks for multiple UI hops (e.g. 'YouTube a novaking entertainment search kore channel a giye first video play koro', visiting a channel/profile, switching to Videos/Shorts tab, sorting, commenting, etc.), DO NOT use PLAY_MEDIA shortcut — emit LAUNCH_APP with target='YouTube', continueLoopAfterAction=true, and populate 'subGoals' in order (e.g. ['Open YouTube', 'Search novaking entertainment', 'Open channel', 'Open Videos section', 'Play first video']).")
                appendLine("5. HARDWARE TOGGLES: Use TOGGLE_TORCH (boolValue), TOGGLE_DND (boolValue), TOGGLE_WIFI (boolValue), TOGGLE_BLUETOOTH (boolValue), ADJUST_VOLUME (target='up'/'down'/'mute'/'set', intValue=percent), CONTROL_MEDIA (target='play'/'pause'/'next'/'previous').")
                appendLine("6. DIRECT PHONE CALLS ('call Ma', 'dial 017...', 'মা কে কল করো'): emit DIAL_CONTACT with target=contact name/number and isSensitive=false so calling runs directly.")
                appendLine("7. SILENT BACKGROUND SCREEN OBSERVATION: Whenever ACTIVE SCREEN UI TREE, OCR, or a screenshot is attached, use it SILENTLY in the background solely to choose tap/input/scroll targets (UI_TAP_TEXT, UI_TAP_COORDINATES, UI_INPUT_TEXT, UI_SCROLL). In 'reply', NEVER describe, summarize, or read aloud what is on the user's screen unless the user explicitly asked you to look at or explain the screen (e.g., 'what is on my screen', 'read this screen', 'screen e ki ache'). If no snapshot is attached yet and screen clicking is required, set needsScreenObservation=true.")
                appendLine("8. MEMORY CONTINUITY: Use RECENT CONVERSATION HISTORY and LOCAL SQLITE MEMORIES to resolve follow-ups ('him', 'her', 'that', 'amar ager kotha', 'amar nam ki', previous topic). Whenever the user shares any new personal fact ('my name is...', 'remember...', 'amar basa...'), ALSO emit a background SAVE_MEMORY step alongside SPEAK_ONLY.")
                appendLine("9. LIVE TOOLS & WEATHER/RAIN: Whenever the user asks about weather, temperature, or rain WITHOUT naming a city ('ajke ki bristi hobe?', 'will it rain today?', 'weather kemon?', 'ajker abohawa?'), NEVER ask the user for a city name — ALWAYS emit WEATHER_LOOKUP with target='current' and isBackgroundParallel=true (device GPS/Network/IP resolves location automatically). Also use WIKIPEDIA_LOOKUP (target=topic), CURRENCY_CONVERT (target='FROM->TO', doubleValue=amount).")
                appendLine("10. ANSWER ONLY WHAT THE USER ASKED: Reply in natural English or Bangla as used by the user. Answer their exact question or concisely confirm the action — do NOT narrate background screen observations or device telemetry unless asked. If no device action is needed, emit a single SPEAK_ONLY step.")
                appendLine("11. UNIFIED MULTI-STEP APP WORKFLOWS: Whenever any command requires multi-step screen navigation across YouTube, WhatsApp, Facebook, Chrome, Instagram, or Settings (beyond a simple send or post), emit an initial LAUNCH_APP step with continueLoopAfterAction=true and a clear 3-to-6 item 'subGoals' list so our Observe-Think-Act ReAct engine waits for screen loads and navigates each hop automatically.")
                appendLine()
                if (memoriesSummary.isNotBlank()) {
                    appendLine("LOCAL SQLITE MEMORIES (LONG-TERM USER FACTS — ALWAYS RECALL THESE):\n$memoriesSummary\n")
                }
                if (conversationHistorySummary.isNotBlank()) {
                    appendLine("RECENT CONVERSATION HISTORY (OLDEST TO NEWEST — USE FOR FOLLOW-UPS):\n$conversationHistorySummary\n")
                }
                appendLine("LIVE DEVICE TELEMETRY:")
                appendLine(deviceContextSummary)
                if (routinesSummary.isNotBlank()) {
                    appendLine("\nSAVED VOICE ROUTINES:\n$routinesSummary")
                }
                if (contactsSummary.isNotBlank()) {
                    appendLine("\nQUICK CONTACTS:\n$contactsSummary")
                }
                if (installedAppsSummary.isNotBlank()) {
                    appendLine("\nINSTALLED APPS:\n$installedAppsSummary")
                }
                if (!screenUiSummary.isNullOrBlank()) {
                    appendLine("\nACTIVE SCREEN UI TREE:\n$screenUiSummary")
                }
                if (!ocrSummary.isNullOrBlank()) {
                    appendLine("\n$ocrSummary")
                }
                appendLine("\nUSER COMMAND: $userPrompt")
            }
        }

        suspend fun invokeModelStreaming(
            tierKey: String,
            model: GenerativeModel,
            promptText: String,
            timeoutMs: Long
        ): String {
            val sb = StringBuilder()
            withTimeout(timeoutMs) {
                val flow = if (screenBitmap != null || audioWavBytes != null) {
                    model.generateContentStream(
                        content {
                            if (audioWavBytes != null) {
                                inlineData(audioWavBytes, "audio/wav")
                            }
                            if (screenBitmap != null) {
                                image(screenBitmap)
                            }
                            text(promptText)
                        }
                    )
                } else {
                    model.generateContentStream(promptText)
                }
                flow.collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        sb.append(piece)
                        if (onStreamingReply != null) {
                            val partial = StreamingReplyExtractor.extractPartialReply(sb.toString())
                            if (partial.isNotBlank()) {
                                onStreamingReply(partial)
                            }
                        }
                    }
                }
            }
            val result = sb.toString().trim()
            if (result.isNotBlank()) {
                modelCooldownUntilMs.remove(tierKey)
            }
            return result
        }

        val allowedActionTypes = ActionType.entries.joinToString(", ") { it.name }
        val fallbackPrompt = "$fullInstruction\n\nValid actionType values: $allowedActionTypes.\nRespond with a valid JSON object matching {\"reply\": string, \"needsScreenObservation\": boolean, \"continueLoopAfterAction\": boolean, \"steps\": [{\"stepId\": string, \"dependsOn\": [string], \"timeoutMs\": integer, \"actionType\": string, \"label\": string, \"target\": string, \"recipient\": string, \"textPayload\": string, \"intValue\": integer, \"secondaryIntValue\": integer, \"doubleValue\": number, \"boolValue\": boolean, \"isBackgroundParallel\": boolean, \"isSensitive\": boolean}]}."

        val directPrompt = if (screenBitmap != null && ocrSummary.isNullOrBlank()) {
            "$fallbackPrompt\n[Note: Screen context is for silent background action targeting only. Do NOT describe what is on screen in 'reply' unless the user explicitly asked about the screen; answer only what the user asked.]"
        } else {
            fallbackPrompt
        }

        val geminiTiers = listOf(
            Triple("gemini-3.8-flash-schema", "Gemini 3.8 Flash", Triple({ plannerModel }, fullInstruction, 60_000L)),
            Triple("gemini-3.8-flash-free", "Gemini 3.8 Flash", Triple({ unconstrainedModel }, fallbackPrompt, 45_000L)),
            Triple("gemini-3.5-flash-lite", "Gemini 3.5 Lite", Triple({ fallbackLiteModel }, fallbackPrompt, 45_000L))
        )

        return routePrimaryAndFallback(
            audioWavBytes = audioWavBytes,
            preferNvidiaPrimary = preferNvidiaPrimary,
            nvidiaConfigured = nvidiaFallback.isConfigured(),
            geminiBillingUnavailable = geminiBillingUnavailable && !directGeminiClient.isConfigured(),
            tryNvidia = {
                nvidiaFallback.generateCommandJson(
                    systemPersonaPrompt = persona.stylePrompt,
                    fullPrompt = directPrompt,
                    conversationHistory = conversationTurns,
                    onStreamPartial = onStreamingReply,
                    onModelUsed = onActiveModelChanged
                ).map { rawNvidia -> parseCommandPlanOrPlainReply(rawNvidia) }
            },
            tryGemini = {
                if (directGeminiClient.isConfigured()) {
                    onActiveModelChanged?.invoke("Gemini Key · ${directGeminiClient.currentModelId()}")
                    val customRes = directGeminiClient.generateJsonOrText(
                        prompt = fallbackPrompt,
                        conversationHistory = conversationTurns,
                        bitmap = screenBitmap,
                        audioWavBytes = audioWavBytes,
                        temperature = 0.35f,
                        expectJson = true
                    ).map { raw -> parseCommandPlanOrPlainReply(raw) }
                    if (customRes.isSuccess) {
                        return@routePrimaryAndFallback customRes
                    }
                    Log.w(TAG, "Direct Gemini API key call failed (${customRes.exceptionOrNull()?.message}); falling back to Firebase AI Logic.")
                }
                if (geminiBillingUnavailable) {
                    Result.failure(IllegalStateException("Firebase Gemini billing unavailable and direct key failed."))
                } else {
                    runGeminiChain(
                        geminiTiers = geminiTiers,
                        invokeModelStreaming = ::invokeModelStreaming,
                        onActiveModelChanged = onActiveModelChanged
                    )
                }
            },
            extractError = ::extractReadableError,
            onLogWarning = { msg -> runCatching { Log.w(TAG, msg) } }
        )
    }

    private suspend fun runGeminiChain(
        geminiTiers: List<Triple<String, String, Triple<() -> GenerativeModel, String, Long>>>,
        invokeModelStreaming: suspend (String, GenerativeModel, String, Long) -> String,
        onActiveModelChanged: ((String) -> Unit)?
    ): Result<CommandPlan> {
        var primaryError: Exception? = null
        val nowMs = System.currentTimeMillis()
        for ((index, tier) in geminiTiers.withIndex()) {
            val (tierKey, displayLabel, spec) = tier
            val (modelProvider, promptText, timeoutMs) = spec
            val hasMoreTiers = index < geminiTiers.lastIndex
            val cooldownUntil = modelCooldownUntilMs[tierKey] ?: 0L
            if (cooldownUntil > nowMs) {
                Log.d(TAG, "Skipping $tierKey (in cooldown for ${(cooldownUntil - nowMs) / 1000}s)")
                continue
            }
            onActiveModelChanged?.invoke(displayLabel)
            try {
                val model = modelProvider()
                val rawOut = invokeModelStreaming(tierKey, model, promptText, timeoutMs)
                if (rawOut.isNotBlank()) {
                    val parsed = if (tierKey.endsWith("-schema")) {
                        parseCommandPlan(rawOut)
                    } else {
                        parseCommandPlanOrPlainReply(rawOut)
                    }
                    return Result.success(parsed)
                }
                if (primaryError == null) {
                    primaryError = IllegalStateException("$displayLabel returned an empty response.")
                }
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                if (hasMoreTiers) {
                    Log.w(TAG, "$tierKey timed out after ${timeoutMs}ms; trying fallback tier")
                } else {
                    Log.w(TAG, "$tierKey timed out after ${timeoutMs}ms")
                }
                modelCooldownUntilMs[tierKey] = System.currentTimeMillis() + 30_000L
                if (primaryError == null) primaryError = e
            } catch (e: Exception) {
                if (isBillingUnavailableError(e)) {
                    geminiBillingUnavailable = true
                    Log.w(TAG, "Gemini project free tier unavailable.")
                    primaryError = e
                    break
                }
                if (hasMoreTiers && !isNonRetryableGeminiError(e)) {
                    Log.w(TAG, "$tierKey failed (${e.javaClass.simpleName}): ${e.message}; trying fallback tier")
                } else {
                    Log.e(TAG, "$tierKey failed (${e.javaClass.simpleName}): ${e.message}", e)
                }
                if (primaryError == null) primaryError = e
                if (isNonRetryableGeminiError(e)) {
                    break
                } else {
                    modelCooldownUntilMs[tierKey] = System.currentTimeMillis() + 20_000L
                }
            }
        }
        return Result.failure(
            primaryError ?: IllegalStateException("Gemini AI request failed. Check internet connection.")
        )
    }

    private fun isBillingUnavailableError(e: Exception): Boolean {
        val msg = ((e.message ?: "") + " " + (e.cause?.message ?: "")).lowercase()
        return msg.contains("free tier is not available") ||
            msg.contains("set up billing") ||
            msg.contains("upgrade to the paid tier") ||
            msg.contains("project-unavailable")
    }

    internal fun isNonRetryableGeminiError(e: Exception): Boolean {
        val msg = ((e.message ?: "") + " " + (e.cause?.message ?: "")).lowercase()
        return isBillingUnavailableError(e) ||
            msg.contains("default firebaseapp is not initialized") ||
            msg.contains("unknownhost") ||
            msg.contains("unable to resolve host") ||
            msg.contains("failed to connect") ||
            msg.contains("network is unreachable") ||
            msg.contains("403") ||
            msg.contains("app check") ||
            msg.contains("permission_denied") ||
            msg.contains("billing") ||
            msg.contains("resource_exhausted")
    }

    internal fun extractReadableError(e: Exception?): String {
        if (e is TimeoutCancellationException) {
            return "Gemini request timed out. Check your internet connection or configure your NVIDIA API key."
        }
        val raw = e?.cause?.localizedMessage?.takeIf { it.isNotBlank() }
            ?: e?.localizedMessage?.takeIf { it.isNotBlank() }
            ?: return "Gemini AI request failed. Check internet connection."
        val lower = raw.lowercase()
        if (lower.contains("default firebaseapp is not initialized")) {
            return "Firebase is not initialized yet for Gemini fallback. Configure an NVIDIA API key or restart the app."
        }
        if (lower.contains("free tier is not available") || lower.contains("set up billing")) {
            return "Gemini free tier is unavailable for this Firebase project. Set up billing at https://aistudio.google.com/billing"
        }
        if (lower.contains("timed out waiting for")) {
            return "Gemini request timed out. Check your internet connection or configure your NVIDIA API key."
        }
        return raw
    }

    suspend fun synthesizeToolSummary(
        userPrompt: String,
        persona: AssistantPersona,
        toolOutputs: List<String>,
        preferNvidiaPrimary: Boolean = true,
        onStreamPartial: ((String) -> Unit)? = null
    ): String? {
        if (toolOutputs.isEmpty()) return null
        val prompt = buildString {
            appendLine(persona.stylePrompt)
            appendLine("The user asked: \"$userPrompt\"")
            appendLine("Live tool outputs retrieved just now:")
            toolOutputs.forEachIndexed { idx, line -> appendLine("${idx + 1}. $line") }
            appendLine("Produce a crisp, natural spoken summary (max 2-3 sentences) combining these live facts accurately.")
        }
        if (preferNvidiaPrimary && nvidiaFallback.isConfigured()) {
            val primarySummary = nvidiaFallback.synthesizeSummary(persona.stylePrompt, prompt, onStreamPartial)
            if (!primarySummary.isNullOrBlank()) {
                return primarySummary
            }
            Log.w(TAG, "Primary NVIDIA tool summary unavailable; falling back to Gemini.")
        }
        if (directGeminiClient.isConfigured()) {
            val directSummary = directGeminiClient.generateJsonOrText(
                prompt = prompt,
                temperature = 0.45f,
                expectJson = false
            ).getOrNull()
            if (!directSummary.isNullOrBlank()) {
                onStreamPartial?.invoke(directSummary)
                return directSummary
            }
        }
        if (!geminiBillingUnavailable) {
            try {
                val sb = StringBuilder()
                withTimeout(45_000L) {
                    summaryModel.generateContentStream(prompt).collect { chunk ->
                        val piece = chunk.text.orEmpty()
                        if (piece.isNotEmpty()) {
                            sb.append(piece)
                            onStreamPartial?.invoke(sb.toString().trim())
                        }
                    }
                }
                val text = sb.toString().trim()
                if (text.isNotBlank()) return text
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                Log.w(TAG, "Tool summary via Gemini timed out")
            } catch (e: Exception) {
                if (isBillingUnavailableError(e)) {
                    geminiBillingUnavailable = true
                }
                Log.w(TAG, "Failed to synthesize tool summary via Gemini: ${e.message}")
            }
        }
        if (!preferNvidiaPrimary && nvidiaFallback.isConfigured()) {
            return nvidiaFallback.synthesizeSummary(persona.stylePrompt, prompt, onStreamPartial)
        }
        return null
    }

    internal fun parseCommandPlanOrPlainReply(rawOutput: String): CommandPlan {
        val trimmed = rawOutput.trim()
        val unwrapped = trimmed
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
        val firstBrace = unwrapped.indexOf('{')
        val lastBrace = unwrapped.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val candidate = unwrapped.substring(firstBrace, lastBrace + 1)
            runCatching {
                val obj = lenientJson.parseToJsonElement(candidate).jsonObject
                if (obj.containsKey("reply") || obj.containsKey("steps")) {
                    return parseCommandPlan(candidate)
                }
            }
        }
        return CommandPlan(
            reply = unwrapped.ifBlank { "Done." },
            needsScreenObservation = false,
            continueLoopAfterAction = false,
            steps = listOf(PlannedStep(ActionType.SPEAK_ONLY, "Respond"))
        )
    }

    internal fun parseCommandPlan(rawJson: String): CommandPlan {
        val root = lenientJson.parseToJsonElement(rawJson).jsonObject
        val transcript = root.optCleanString("transcript", "")
        val reply = root.optCleanString("reply", "On it.")
        val needsScreen = root.optBoolean("needsScreenObservation", false)
        val continueLoop = root.optBoolean("continueLoopAfterAction", false)
        val subGoalsArray = root["subGoals"] as? JsonArray
        val subGoals = buildList {
            if (subGoalsArray != null) {
                for (gElem in subGoalsArray) {
                    val prim = gElem as? JsonPrimitive ?: continue
                    val goalText = if (prim is JsonNull) "" else prim.content.trim()
                    if (goalText.isNotBlank() && goalText != "null") add(goalText)
                }
            }
        }
        val stepsArray = (root["steps"] as? JsonArray) ?: JsonArray(emptyList())

        val steps = buildList {
            for (elem in stepsArray) {
                val obj = elem as? JsonObject ?: continue
                val typeName = obj.optCleanString("actionType", ActionType.SPEAK_ONLY.name)
                val actionType = ActionType.entries.firstOrNull { it.name == typeName }
                    ?: ActionType.SPEAK_ONLY
                val depsArray = obj["dependsOn"] as? JsonArray
                val deps = buildList {
                    if (depsArray != null) {
                        for (depElem in depsArray) {
                            val prim = depElem as? JsonPrimitive ?: continue
                            val depId = if (prim is JsonNull) "" else prim.content.trim()
                            if (depId.isNotBlank() && depId != "null") add(depId)
                        }
                    }
                }
                add(
                    PlannedStep(
                        actionType = actionType,
                        label = obj.optCleanString("label", actionType.name.replace("_", " ")),
                        target = obj.optCleanString("target", ""),
                        textPayload = obj.optCleanString("textPayload", ""),
                        recipient = obj.optCleanString("recipient", ""),
                        intValue = obj.optInt("intValue", 0),
                        secondaryIntValue = obj.optInt("secondaryIntValue", 0),
                        doubleValue = obj.optDouble("doubleValue", 0.0).let { if (it.isNaN()) 0.0 else it },
                        boolValue = obj.optBoolean("boolValue", false),
                        isBackgroundParallel = obj.optBoolean("isBackgroundParallel", false),
                        isSensitive = obj.optBoolean("isSensitive", false),
                        stepId = obj.optCleanString("stepId", ""),
                        dependsOn = deps,
                        timeoutMs = obj.optLong("timeoutMs", 0L).coerceAtLeast(0L)
                    )
                )
            }
        }

        return CommandPlan(
            reply = reply,
            needsScreenObservation = needsScreen,
            continueLoopAfterAction = continueLoop,
            subGoals = subGoals,
            steps = if (steps.isEmpty()) {
                listOf(PlannedStep(ActionType.SPEAK_ONLY, "Respond"))
            } else {
                steps
            },
            recognizedTranscript = transcript
        )
    }

    private fun JsonObject.optCleanString(key: String, fallback: String): String {
        val prim = this[key] as? JsonPrimitive ?: return fallback
        if (prim is JsonNull) return fallback
        return prim.content.takeUnless { it == "null" } ?: fallback
    }

    private fun JsonObject.optBoolean(key: String, fallback: Boolean): Boolean {
        val prim = this[key] as? JsonPrimitive ?: return fallback
        if (prim is JsonNull) return fallback
        return prim.booleanOrNull ?: fallback
    }

    private fun JsonObject.optInt(key: String, fallback: Int): Int {
        val prim = this[key] as? JsonPrimitive ?: return fallback
        if (prim is JsonNull) return fallback
        return prim.intOrNull ?: prim.doubleOrNull?.toInt() ?: fallback
    }

    private fun JsonObject.optLong(key: String, fallback: Long): Long {
        val prim = this[key] as? JsonPrimitive ?: return fallback
        if (prim is JsonNull) return fallback
        return prim.longOrNull ?: prim.doubleOrNull?.toLong() ?: fallback
    }

    private fun JsonObject.optDouble(key: String, fallback: Double): Double {
        val prim = this[key] as? JsonPrimitive ?: return fallback
        if (prim is JsonNull) return fallback
        return prim.doubleOrNull ?: fallback
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        internal suspend fun routePrimaryAndFallback(
            audioWavBytes: ByteArray?,
            preferNvidiaPrimary: Boolean,
            nvidiaConfigured: Boolean,
            geminiBillingUnavailable: Boolean = false,
            tryNvidia: suspend () -> Result<CommandPlan>,
            tryGemini: suspend () -> Result<CommandPlan>,
            extractError: (Exception?) -> String = { e ->
                e?.localizedMessage?.takeIf { it.isNotBlank() } ?: "Gemini AI request failed. Check internet connection."
            },
            onLogWarning: (String) -> Unit = {}
        ): Result<CommandPlan> {
            var nvidiaPrimaryError: Throwable? = null
            if (audioWavBytes == null && preferNvidiaPrimary && nvidiaConfigured) {
                val nvidiaResult = tryNvidia()
                if (nvidiaResult.isSuccess) {
                    return nvidiaResult
                }
                nvidiaPrimaryError = nvidiaResult.exceptionOrNull()
                onLogWarning("Primary NVIDIA NIM request failed (${nvidiaPrimaryError?.message}); falling back to Gemini.")
            }

            val geminiResult = if (geminiBillingUnavailable) {
                Result.failure(IllegalStateException("Gemini free tier is unavailable for this Firebase project."))
            } else {
                tryGemini()
            }
            if (geminiResult.isSuccess) {
                return geminiResult
            }
            val geminiErr = geminiResult.exceptionOrNull() as? Exception

            if (audioWavBytes == null && !preferNvidiaPrimary && nvidiaConfigured) {
                onLogWarning("Gemini chain unavailable (${geminiErr?.message}); routing to NVIDIA NIM fallback.")
                val nvidiaFallbackRes = tryNvidia()
                if (nvidiaFallbackRes.isSuccess) {
                    return nvidiaFallbackRes
                }
                val nvidiaErr = nvidiaFallbackRes.exceptionOrNull()
                return Result.failure(
                    IllegalStateException(
                        "${extractError(geminiErr)} | NVIDIA fallback: ${nvidiaErr?.localizedMessage ?: nvidiaErr?.message}"
                    )
                )
            }

            val geminiErrMsg = extractError(geminiErr)
            if (nvidiaPrimaryError != null) {
                return Result.failure(
                    IllegalStateException(
                        "NVIDIA primary: ${nvidiaPrimaryError.localizedMessage ?: nvidiaPrimaryError.message} | Gemini fallback: $geminiErrMsg"
                    )
                )
            }
            val hint = if (!nvidiaConfigured) {
                " Tap Configure NVIDIA API & Key below to set your NVIDIA API key as primary AI."
            } else ""
            return Result.failure(IllegalStateException("$geminiErrMsg$hint"))
        }
    }
}
