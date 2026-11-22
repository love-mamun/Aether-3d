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
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GeminiCommandService"

class GeminiCommandService(
    private val nvidiaFallback: NvidiaFallbackService = NvidiaFallbackService()
) {

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
            "reply" to Schema.string(
                description = "Natural, concise spoken reply matching the active persona in English or Bangla (বাংলা) as used by the user"
            ),
            "needsScreenObservation" to Schema.boolean(
                description = "Set true only if the request explicitly requires inspecting or interacting with the user's current screen and no screen snapshot was provided yet"
            ),
            "continueLoopAfterAction" to Schema.boolean(
                description = "Set true if executing the UI step opens a new screen that requires one more observe-act step to finish the user's multi-step goal"
            ),
            "steps" to Schema.array(
                items = stepSchema,
                description = "Ordered DAG of 1 to 6 executable actions. Use SPEAK_ONLY when purely answering a question."
            )
        ),
        optionalProperties = listOf("needsScreenObservation", "continueLoopAfterAction")
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
        preferNvidiaPrimary: Boolean = false,
        onStreamingReply: ((String) -> Unit)? = null,
        onActiveModelChanged: ((String) -> Unit)? = null
    ): Result<CommandPlan> {
        val fullInstruction = buildString {
            appendLine(persona.stylePrompt)
            appendLine("You are Aether 3D, a multitasking Android OS agent supporting Bangla (বাংলা) and English ONLY. Convert the user's voice/text request into a structured CommandPlan JSON.")
            appendLine("Rules:")
            appendLine("1. Support multitasking & Task DAG dependencies: assign short stepId ('s0', 's1'...) and populate dependsOn when one step relies on another (e.g. UI tap/input after LAUNCH_APP), while keeping independent tools (WEATHER_LOOKUP, WIKIPEDIA_LOOKUP, CURRENCY_CONVERT, SAVE_MEMORY, CREATE_ROUTINE, TOGGLE_TORCH, ADJUST_VOLUME, SET_BRIGHTNESS, SET_AUTO_ROTATE, SET_ALARM, SET_TIMER) parallel with isBackgroundParallel=true and empty dependsOn.")
            appendLine("2. CRITICAL FOR APP SEARCH ('open YouTube and search X', 'search X in Spotify/Maps/Play Store/Chrome'): emit SEARCH_IN_APP with target=app name (e.g. 'YouTube') and textPayload=exact query (e.g. 'Nova king entertainment') — DO NOT emit bare LAUNCH_APP alone when a search query is included! Only use bare LAUNCH_APP when no in-app search/action is requested.")
            appendLine("3. For screen questions ('what is on my screen', 'summarize this', 'tap X', 'scroll down', 'type Y'), use ACTIVE SCREEN UI TREE, ON-DEVICE OCR FALLBACK, or the attached image to answer directly or emit UI_TAP_TEXT, UI_TAP_COORDINATES (0-1000 scale), UI_INPUT_TEXT, UI_SCROLL, or GLOBAL_ACTION. If neither UI tree, OCR, nor image is attached yet and the command needs the screen, set needsScreenObservation=true.")
            appendLine("4. For calls/SMS/WhatsApp/Email, set target=contact name or phone/email, textPayload=message body, and isSensitive=true.")
            appendLine("5. For weather queries, emit WEATHER_LOOKUP with target=city name (or 'current' for local weather).")
            appendLine("6. For factual/encyclopedia queries needing live verification, emit WIKIPEDIA_LOOKUP with target=topic.")
            appendLine("7. For currency conversion, emit CURRENCY_CONVERT with target='FROM->TO' (e.g. 'USD->BDT' or 'USD->INR') and doubleValue=amount.")
            appendLine("8. For 'remember ...', emit SAVE_MEMORY with textPayload=fact to save.")
            appendLine("9. Match language cleanly: reply in Bangla if user wrote/spoke Bangla (or Banglish), otherwise English. If no device action is needed, emit a single SPEAK_ONLY step.")
            appendLine()
            appendLine("LIVE DEVICE TELEMETRY:")
            appendLine(deviceContextSummary)
            if (memoriesSummary.isNotBlank()) {
                appendLine("\nLOCAL SQLITE MEMORIES:\n$memoriesSummary")
            }
            if (routinesSummary.isNotBlank()) {
                appendLine("\nSAVED VOICE ROUTINES:\n$routinesSummary")
            }
            if (contactsSummary.isNotBlank()) {
                appendLine("\nQUICK CONTACTS:\n$contactsSummary")
            }
            if (installedAppsSummary.isNotBlank()) {
                appendLine("\nINSTALLED APPS SAMPLE:\n$installedAppsSummary")
            }
            if (!screenUiSummary.isNullOrBlank()) {
                appendLine("\nACTIVE SCREEN UI TREE:\n$screenUiSummary")
            }
            if (!ocrSummary.isNullOrBlank()) {
                appendLine("\n$ocrSummary")
            }
            appendLine("\nUSER COMMAND: $userPrompt")
        }

        suspend fun invokeModelStreaming(
            tierKey: String,
            model: GenerativeModel,
            promptText: String
        ): String {
            val sb = StringBuilder()
            withTimeout(45_000L) {
                val flow = if (screenBitmap != null) {
                    model.generateContentStream(
                        content {
                            image(screenBitmap)
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
        val fallbackPrompt = "$fullInstruction\n\nValid actionType values: $allowedActionTypes.\nRespond with a valid JSON object matching {\"reply\": string, \"needsScreenObservation\": boolean, \"continueLoopAfterAction\": boolean, \"steps\": [{\"stepId\": string, \"dependsOn\": [string], \"timeoutMs\": integer, \"actionType\": string, \"label\": string, \"target\": string, \"textPayload\": string, \"intValue\": integer, \"doubleValue\": number, \"boolValue\": boolean, \"isBackgroundParallel\": boolean, \"isSensitive\": boolean}]}."

        // Direct NVIDIA NIM streaming path when user enabled NVIDIA API directly, or Gemini project billing is unavailable
        if ((preferNvidiaPrimary || geminiBillingUnavailable) && nvidiaFallback.isConfigured()) {
            val directPrompt = if (screenBitmap != null && ocrSummary.isNullOrBlank()) {
                "$fallbackPrompt\n[Note: Image attached; use ACTIVE SCREEN UI TREE or ON-DEVICE OCR FALLBACK coordinates if available.]"
            } else {
                fallbackPrompt
            }
            val nvidiaResult = nvidiaFallback.generateCommandJson(
                systemPersonaPrompt = persona.stylePrompt,
                fullPrompt = directPrompt,
                onStreamPartial = onStreamingReply,
                onModelUsed = onActiveModelChanged
            )
            nvidiaResult.onSuccess { rawNvidia ->
                return Result.success(parseCommandPlanOrPlainReply(rawNvidia))
            }.onFailure { nvidiaErr ->
                Log.e(TAG, "Direct NVIDIA NIM request failed: ${nvidiaErr.message}", nvidiaErr)
                return Result.failure(
                    IllegalStateException(nvidiaErr.localizedMessage ?: "NVIDIA NIM request failed.")
                )
            }
        }

        // Ordered Multi-Model Gemini Chain with live streaming + cooldown bypass
        val geminiTiers = listOf(
            Triple("gemini-3.8-flash-schema", "Gemini 3.8 Flash", plannerModel to fullInstruction),
            Triple("gemini-3.8-flash-free", "Gemini 3.8 Flash", unconstrainedModel to fallbackPrompt),
            Triple("gemini-3.5-flash-lite", "Gemini 3.5 Lite", fallbackLiteModel to fallbackPrompt)
        )

        var primaryError: Exception? = null
        val nowMs = System.currentTimeMillis()
        for ((tierKey, displayLabel, modelPair) in geminiTiers) {
            val cooldownUntil = modelCooldownUntilMs[tierKey] ?: 0L
            if (cooldownUntil > nowMs) {
                Log.d(TAG, "Skipping $tierKey (in cooldown for ${(cooldownUntil - nowMs) / 1000}s)")
                continue
            }
            onActiveModelChanged?.invoke(displayLabel)
            try {
                val rawOut = invokeModelStreaming(tierKey, modelPair.first, modelPair.second)
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
                Log.e(TAG, "$tierKey timed out", e)
                modelCooldownUntilMs[tierKey] = System.currentTimeMillis() + 30_000L
                if (primaryError == null) primaryError = e
            } catch (e: Exception) {
                if (isBillingUnavailableError(e)) {
                    geminiBillingUnavailable = true
                    Log.w(TAG, "Gemini project free tier unavailable; routing to NVIDIA NIM fallback.")
                    primaryError = e
                    break
                }
                Log.e(TAG, "$tierKey failed (${e.javaClass.simpleName}): ${e.message}", e)
                if (primaryError == null) primaryError = e
                if (isNonRetryableGeminiError(e)) {
                    break
                } else {
                    modelCooldownUntilMs[tierKey] = System.currentTimeMillis() + 20_000L
                }
            }
        }

        // Final fallback tier: NVIDIA NIM multi-model SSE streaming
        if (nvidiaFallback.isConfigured()) {
            Log.w(TAG, "Gemini chain unavailable (${primaryError?.message}); routing to NVIDIA NIM streaming fallback.")
            val nvidiaResult = nvidiaFallback.generateCommandJson(
                systemPersonaPrompt = persona.stylePrompt,
                fullPrompt = fallbackPrompt,
                onStreamPartial = onStreamingReply,
                onModelUsed = onActiveModelChanged
            )
            nvidiaResult.onSuccess { rawNvidia ->
                return Result.success(parseCommandPlanOrPlainReply(rawNvidia))
            }.onFailure { nvidiaErr ->
                Log.e(TAG, "NVIDIA NIM fallback also failed: ${nvidiaErr.message}", nvidiaErr)
                return Result.failure(
                    IllegalStateException(
                        "${extractReadableError(primaryError)} | NVIDIA fallback: ${nvidiaErr.localizedMessage}"
                    )
                )
            }
        }

        val geminiErrMsg = extractReadableError(primaryError)
        val hint = if (!nvidiaFallback.isConfigured()) {
            " Tap Configure NVIDIA API & Key below to enter your NVIDIA API key directly in the app."
        } else ""
        return Result.failure(IllegalStateException("$geminiErrMsg$hint"))
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
        val raw = e?.cause?.localizedMessage?.takeIf { it.isNotBlank() }
            ?: e?.localizedMessage?.takeIf { it.isNotBlank() }
            ?: return "Gemini AI request failed. Check internet connection."
        val lower = raw.lowercase()
        if (lower.contains("free tier is not available") || lower.contains("set up billing")) {
            return "Gemini free tier is unavailable for this Firebase project. Set up billing at https://aistudio.google.com/billing"
        }
        return raw
    }

    suspend fun synthesizeToolSummary(
        userPrompt: String,
        persona: AssistantPersona,
        toolOutputs: List<String>,
        preferNvidiaPrimary: Boolean = false,
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
        if ((preferNvidiaPrimary || geminiBillingUnavailable) && nvidiaFallback.isConfigured()) {
            return nvidiaFallback.synthesizeSummary(persona.stylePrompt, prompt, onStreamPartial)
        }
        try {
            val sb = StringBuilder()
            withTimeout(25_000L) {
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
            Log.e(TAG, "Tool summary timed out", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to synthesize tool summary via Gemini", e)
        }
        return nvidiaFallback.synthesizeSummary(persona.stylePrompt, prompt, onStreamPartial)
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
                val obj = JSONObject(candidate)
                if (obj.has("reply") || obj.has("steps")) {
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
        val root = JSONObject(rawJson)
        val reply = root.optCleanString("reply", "On it.")
        val needsScreen = root.optBoolean("needsScreenObservation", false)
        val continueLoop = root.optBoolean("continueLoopAfterAction", false)
        val stepsArray = root.optJSONArray("steps") ?: JSONArray()

        val steps = buildList {
            for (i in 0 until stepsArray.length()) {
                val obj = stepsArray.optJSONObject(i) ?: continue
                val typeName = obj.optCleanString("actionType", ActionType.SPEAK_ONLY.name)
                val actionType = ActionType.entries.firstOrNull { it.name == typeName }
                    ?: ActionType.SPEAK_ONLY
                val depsArray = obj.optJSONArray("dependsOn")
                val deps = buildList {
                    if (depsArray != null) {
                        for (d in 0 until depsArray.length()) {
                            val depId = depsArray.optString(d, "").trim()
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
                        intValue = if (obj.isNull("intValue")) 0 else obj.optInt("intValue", 0),
                        secondaryIntValue = if (obj.isNull("secondaryIntValue")) 0 else obj.optInt("secondaryIntValue", 0),
                        doubleValue = if (obj.isNull("doubleValue")) 0.0 else obj.optDouble("doubleValue", 0.0).let { if (it.isNaN()) 0.0 else it },
                        boolValue = !obj.isNull("boolValue") && obj.optBoolean("boolValue", false),
                        isBackgroundParallel = !obj.isNull("isBackgroundParallel") && obj.optBoolean("isBackgroundParallel", false),
                        isSensitive = !obj.isNull("isSensitive") && obj.optBoolean("isSensitive", false),
                        stepId = obj.optCleanString("stepId", ""),
                        dependsOn = deps,
                        timeoutMs = if (obj.isNull("timeoutMs")) 0L else obj.optLong("timeoutMs", 0L).coerceAtLeast(0L)
                    )
                )
            }
        }

        return CommandPlan(
            reply = reply,
            needsScreenObservation = needsScreen,
            continueLoopAfterAction = continueLoop,
            steps = if (steps.isEmpty()) {
                listOf(PlannedStep(ActionType.SPEAK_ONLY, "Respond"))
            } else {
                steps
            }
        )
    }

    private fun JSONObject.optCleanString(key: String, fallback: String): String {
        if (!has(key) || isNull(key)) return fallback
        return optString(key, fallback).takeUnless { it == "null" } ?: fallback
    }
}
