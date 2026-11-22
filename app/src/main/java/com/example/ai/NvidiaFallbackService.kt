package com.example.ai

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "NvidiaFallbackService"
private const val NVIDIA_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
private const val NVIDIA_MODELS_ENDPOINT = "https://integrate.api.nvidia.com/v1/models"
private const val DEFAULT_NVIDIA_MODEL = "nvidia/nemotron-3.5-bolt-e4b"
private const val EOL_NVIDIA_MODEL = "meta/llama-3.3-70b-instruct"
private const val PLACEHOLDER_VALUE = "NVIDIA_API_KEY_DEFAULT_VALUE"

private val FALLBACK_CHAT_MODELS = listOf(
    "nvidia/nemotron-3.5-bolt-e4b",
    "nvidia/nemotron-3.5-super-vl-120b-a12b",
    "qwen/qwen3-coder-480b-a35b-instruct",
    "meta/llama-3.1-8b-instruct"
)

class NvidiaFallbackService(
    private val apiKey: String = BuildConfig.NVIDIA_API_KEY,
    private val apiKeyProvider: (() -> String)? = null,
    private val modelProvider: (() -> String)? = null,
    private val onModelResolved: ((String) -> Unit)? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()
) {

    @Volatile
    private var cachedLiveModels: List<String> = emptyList()

    private fun resolveApiKey(overrideKey: String? = null): String {
        val overrideTrimmed = overrideKey?.trim().orEmpty()
        if (isValidKey(overrideTrimmed)) return overrideTrimmed
        val dynamicKey = apiKeyProvider?.invoke()?.trim().orEmpty()
        if (isValidKey(dynamicKey)) return dynamicKey
        val fallbackKey = apiKey.trim()
        return if (isValidKey(fallbackKey)) fallbackKey else ""
    }

    private fun resolveModel(): String {
        val candidate = modelProvider?.invoke()?.trim().orEmpty()
        return if (candidate.isBlank() || candidate == EOL_NVIDIA_MODEL) {
            DEFAULT_NVIDIA_MODEL
        } else {
            candidate
        }
    }

    private fun isValidKey(value: String): Boolean {
        return value.isNotEmpty() &&
            value != PLACEHOLDER_VALUE &&
            !value.equals("null", ignoreCase = true)
    }

    fun isConfigured(): Boolean = resolveApiKey().isNotEmpty()

    suspend fun fetchLiveChatModels(overrideKey: String? = null): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val resolvedKey = resolveApiKey(overrideKey)
            if (resolvedKey.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Enter an NVIDIA API key first to fetch live models.")
                )
            }
            fetchLiveChatModelsSync(resolvedKey)
        }

    private fun fetchLiveChatModelsSync(resolvedKey: String): Result<List<String>> {
        return try {
            val request = Request.Builder()
                .url(NVIDIA_MODELS_ENDPOINT)
                .header("Authorization", "Bearer $resolvedKey")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiErr = extractApiError(rawBody, response.code)
                    return Result.failure(IllegalStateException("Models lookup failed (${response.code}): $apiErr"))
                }
                val parsed = parseAndRankChatModels(rawBody)
                if (parsed.isNotEmpty()) {
                    cachedLiveModels = parsed
                }
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query live NVIDIA models list", e)
            Result.failure(IllegalStateException(e.localizedMessage ?: "Could not fetch NVIDIA models"))
        }
    }

    internal fun parseAndRankChatModels(rawModelsJson: String): List<String> {
        val root = JSONObject(rawModelsJson)
        val data = root.optJSONArray("data") ?: return emptyList()
        val excluded = listOf(
            "embed", "rerank", "parse", "guard", "shield", "reward", "safety",
            "clip", "ocr", "asr", "tts", "whisper", "diffusion", "flux", "sdxl",
            "cosmos", "canary", "parakeet", "fastconformer", "riva", "streampetr",
            "bevformer", "glint", "corrdiff", "fourcastnet", "evo2", "esmfold",
            "alphafold", "molmim", "bionemo", "genmol", "vista", "maisi"
        )
        val ids = buildList {
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id", "").orEmpty().trim()
                if (id.isBlank()) continue
                val lower = id.lowercase()
                if (excluded.any { lower.contains(it) }) continue
                add(id)
            }
        }
        return ids.sortedBy { id ->
            val l = id.lowercase()
            when {
                l.contains("nemotron-3.5-bolt") -> 0
                l.contains("nemotron-3.5-super") -> 1
                l.contains("nemotron") && l.contains("instruct") -> 2
                l.contains("nemotron") -> 3
                l.contains("llama-4") && l.contains("instruct") -> 4
                l.contains("qwen3") && l.contains("instruct") -> 5
                l.contains("llama-3") && l.contains("instruct") -> 6
                l.contains("deepseek") -> 7
                l.contains("instruct") || l.contains("chat") -> 8
                else -> 20
            }
        }
    }

    suspend fun generateCommandJson(
        systemPersonaPrompt: String,
        fullPrompt: String,
        onStreamPartial: ((String) -> Unit)? = null,
        onModelUsed: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(
                IllegalStateException("NVIDIA API key is not configured. Tap Configure NVIDIA API & Key in the app.")
            )
        }
        val sysPrompt = "$systemPersonaPrompt You MUST respond with a single valid JSON object matching {\"reply\": string, \"needsScreenObservation\": boolean, \"continueLoopAfterAction\": boolean, \"steps\": [{\"stepId\": string, \"dependsOn\": [string], \"timeoutMs\": integer, \"actionType\": string, \"label\": string, \"target\": string, \"textPayload\": string, \"intValue\": integer, \"secondaryIntValue\": integer, \"doubleValue\": number, \"boolValue\": boolean, \"isBackgroundParallel\": boolean, \"isSensitive\": boolean}]}. Use only valid ActionType names from the instructions."
        callChatCompletionWithAutoModelRecovery(
            systemPrompt = sysPrompt,
            userPrompt = fullPrompt,
            temperature = 0.25,
            maxTokens = 1024,
            stream = onStreamPartial != null,
            onStreamDeltaAccumulated = { rawAccum ->
                if (onStreamPartial != null) {
                    val replyText = StreamingReplyExtractor.extractPartialReply(rawAccum)
                    if (replyText.isNotBlank()) {
                        onStreamPartial(replyText)
                    }
                }
            },
            onModelUsed = onModelUsed
        )
    }

    suspend fun synthesizeSummary(
        systemPersonaPrompt: String,
        prompt: String,
        onStreamPartial: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        callChatCompletionWithAutoModelRecovery(
            systemPrompt = "$systemPersonaPrompt Respond cleanly in 2-3 concise spoken sentences.",
            userPrompt = prompt,
            temperature = 0.45,
            maxTokens = 320,
            stream = onStreamPartial != null,
            onStreamDeltaAccumulated = { rawAccum ->
                if (onStreamPartial != null && rawAccum.isNotBlank()) {
                    onStreamPartial(rawAccum.trim())
                }
            }
        ).getOrNull()
    }

    private fun callChatCompletionWithAutoModelRecovery(
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean = false,
        onStreamDeltaAccumulated: ((String) -> Unit)? = null,
        onModelUsed: ((String) -> Unit)? = null
    ): Result<String> {
        val resolvedKey = resolveApiKey()
        if (resolvedKey.isEmpty()) {
            return Result.failure(
                IllegalStateException("NVIDIA API key is not configured.")
            )
        }

        val initialModel = resolveModel()
        onModelUsed?.invoke("NVIDIA · ${initialModel.substringAfterLast('/')}")
        val firstAttempt = executeChatRequest(
            resolvedKey = resolvedKey,
            modelId = initialModel,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = stream,
            onStreamDeltaAccumulated = onStreamDeltaAccumulated
        )
        if (firstAttempt.result.isSuccess) {
            return firstAttempt.result
        }
        // If SSE streaming failed mid-way on a live model (not 410/404), retry non-streaming once on same model
        if (stream && firstAttempt.httpCode != 410 && firstAttempt.httpCode != 404) {
            val nonStreamRetry = executeChatRequest(
                resolvedKey = resolvedKey,
                modelId = initialModel,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = false,
                onStreamDeltaAccumulated = null
            )
            if (nonStreamRetry.result.isSuccess) return nonStreamRetry.result
            if (nonStreamRetry.httpCode != 410 && nonStreamRetry.httpCode != 404 && nonStreamRetry.httpCode != 429 && nonStreamRetry.httpCode < 500) {
                return nonStreamRetry.result
            }
        } else if (firstAttempt.httpCode != 410 && firstAttempt.httpCode != 404 && firstAttempt.httpCode != 429 && firstAttempt.httpCode < 500) {
            return firstAttempt.result
        }

        Log.w(TAG, "NVIDIA model '$initialModel' unavailable (HTTP ${firstAttempt.httpCode}); cycling multi-model fallback...")
        val liveModels = cachedLiveModels.ifEmpty {
            fetchLiveChatModelsSync(resolvedKey).getOrDefault(emptyList())
        }
        val candidates = (liveModels + FALLBACK_CHAT_MODELS)
            .distinct()
            .filter { it != initialModel && it != EOL_NVIDIA_MODEL }
            .take(5)

        var lastResult = firstAttempt.result
        for (candidate in candidates) {
            onModelUsed?.invoke("NVIDIA · ${candidate.substringAfterLast('/')}")
            val attempt = executeChatRequest(
                resolvedKey = resolvedKey,
                modelId = candidate,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = stream,
                onStreamDeltaAccumulated = onStreamDeltaAccumulated
            )
            if (attempt.result.isSuccess) {
                Log.i(TAG, "Auto-switched NVIDIA NIM model to active model '$candidate'")
                onModelResolved?.invoke(candidate)
                return attempt.result
            }
            lastResult = attempt.result
            if (attempt.httpCode != 410 && attempt.httpCode != 404 && attempt.httpCode != 429 && attempt.httpCode < 500) {
                return attempt.result
            }
        }
        return lastResult
    }

    private data class ChatAttempt(
        val httpCode: Int,
        val result: Result<String>
    )

    private fun executeChatRequest(
        resolvedKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
        onStreamDeltaAccumulated: ((String) -> Unit)?
    ): ChatAttempt {
        return try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }

            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
                put("stream", stream)
            }.toString()

            val request = Request.Builder()
                .url(NVIDIA_ENDPOINT)
                .header("Authorization", "Bearer $resolvedKey")
                .header("Accept", if (stream) "text/event-stream" else "application/json")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string().orEmpty()
                    val apiErr = extractApiError(rawBody, response.code)
                    Log.w(TAG, "NVIDIA NIM HTTP ${response.code} for '$modelId': $apiErr")
                    return ChatAttempt(
                        httpCode = response.code,
                        result = Result.failure(IllegalStateException("NVIDIA fallback error (${response.code}): $apiErr"))
                    )
                }

                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (stream && contentType.contains("text/event-stream")) {
                    val source = response.body?.source()
                        ?: return ChatAttempt(
                            httpCode = response.code,
                            result = Result.failure(IllegalStateException("Empty NVIDIA SSE stream"))
                        )
                    val accum = StringBuilder()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val delta = extractSseDeltaContent(data)
                        if (delta.isNotEmpty()) {
                            accum.append(delta)
                            onStreamDeltaAccumulated?.invoke(accum.toString())
                        }
                    }
                    val finalText = accum.toString().trim()
                    if (finalText.isBlank()) {
                        return ChatAttempt(
                            httpCode = response.code,
                            result = Result.failure(IllegalStateException("NVIDIA SSE stream returned empty text."))
                        )
                    }
                    return ChatAttempt(httpCode = response.code, result = Result.success(finalText))
                } else {
                    val rawBody = response.body?.string().orEmpty()
                    val content = extractAssistantContent(rawBody)
                    if (content.isBlank()) {
                        return ChatAttempt(
                            httpCode = response.code,
                            result = Result.failure(IllegalStateException("NVIDIA NIM returned an empty response."))
                        )
                    }
                    onStreamDeltaAccumulated?.invoke(content)
                    return ChatAttempt(httpCode = response.code, result = Result.success(content))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "NVIDIA NIM call failed", e)
            ChatAttempt(
                httpCode = -1,
                result = Result.failure(IllegalStateException(e.localizedMessage ?: "NVIDIA NIM request failed"))
            )
        }
    }

    internal fun extractSseDeltaContent(sseDataJson: String): String {
        return runCatching {
            val root = JSONObject(sseDataJson)
            val choices = root.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val choice = choices.optJSONObject(0) ?: return ""
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                delta.optString("content", "")
            } else {
                choice.optJSONObject("message")?.optString("content", "").orEmpty()
            }
        }.getOrDefault("")
    }

    internal fun extractAssistantContent(rawResponseJson: String): String {
        val root = JSONObject(rawResponseJson)
        val choices = root.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val firstChoice = choices.optJSONObject(0) ?: return ""
        val message = firstChoice.optJSONObject("message") ?: return ""
        return message.optString("content", "").trim()
    }

    internal fun extractApiError(rawBody: String, httpCode: Int): String {
        if (rawBody.isBlank()) return "HTTP $httpCode"
        return runCatching {
            val obj = JSONObject(rawBody)
            obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: obj.optString("detail").takeIf { it.isNotBlank() }
                ?: obj.optString("title").takeIf { it.isNotBlank() }
                ?: "HTTP $httpCode"
        }.getOrDefault("HTTP $httpCode")
    }
}
