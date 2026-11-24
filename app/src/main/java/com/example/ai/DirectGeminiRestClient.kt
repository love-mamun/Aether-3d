package com.example.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "DirectGeminiRest"

/**
 * Calls Google Gemini Developer REST API (`generativelanguage.googleapis.com`) directly when the
 * user supplies their own Gemini API key in Settings, before falling back to Firebase AI Logic.
 */
class DirectGeminiRestClient(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    fun isConfigured(): Boolean = apiKeyProvider().trim().length >= 16

    fun currentModelId(): String =
        modelProvider().trim().ifBlank { "gemini-2.5-flash" }

    suspend fun generateJsonOrText(
        prompt: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        bitmap: Bitmap? = null,
        audioWavBytes: ByteArray? = null,
        temperature: Float = 0.35f,
        expectJson: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Custom Gemini API key is empty."))
        }
        val primaryModel = currentModelId()
        val candidates = listOf(primaryModel, "gemini-2.5-flash", "gemini-2.0-flash").distinct()

        var lastError: Exception? = null
        for (model in candidates) {
            val res = executeRequest(
                apiKey = apiKey,
                modelId = model,
                prompt = prompt,
                conversationHistory = conversationHistory,
                bitmap = bitmap,
                audioWavBytes = audioWavBytes,
                temperature = temperature,
                expectJson = expectJson
            )
            if (res.isSuccess) return@withContext res
            lastError = res.exceptionOrNull() as? Exception
            val msg = (lastError?.message ?: "").lowercase()
            if (msg.contains("api key not valid") || msg.contains("400") || msg.contains("403")) {
                break
            }
        }
        Result.failure(lastError ?: IllegalStateException("Direct Gemini API request failed."))
    }

    internal fun buildGenerateContentPayload(
        prompt: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        audioBase64: String? = null,
        imageBase64: String? = null,
        temperature: Float = 0.35f,
        expectJson: Boolean = true
    ): String {
        val recentHistory = conversationHistory.takeLast(10)
        return buildJsonObject {
            put("contents", buildJsonArray {
                for ((pastUser, pastModel) in recentHistory) {
                    val u = pastUser.trim().take(400)
                    val m = pastModel.trim().take(480)
                    if (u.isNotBlank() && m.isNotBlank()) {
                        add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", u) })
                            })
                        })
                        add(buildJsonObject {
                            put("role", "model")
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", m) })
                            })
                        })
                    }
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        if (!audioBase64.isNullOrEmpty()) {
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", "audio/wav")
                                    put("data", audioBase64)
                                })
                            })
                        }
                        if (!imageBase64.isNullOrEmpty()) {
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", "image/jpeg")
                                    put("data", imageBase64)
                                })
                            })
                        }
                        add(buildJsonObject { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", temperature.toDouble())
                if (expectJson) {
                    put("responseMimeType", "application/json")
                }
            })
        }.toString()
    }

    private fun executeRequest(
        apiKey: String,
        modelId: String,
        prompt: String,
        conversationHistory: List<Pair<String, String>>,
        bitmap: Bitmap?,
        audioWavBytes: ByteArray?,
        temperature: Float,
        expectJson: Boolean
    ): Result<String> {
        return try {
            val audioB64 = if (audioWavBytes != null && audioWavBytes.isNotEmpty()) {
                Base64.encodeToString(audioWavBytes, Base64.NO_WRAP)
            } else null
            val imgB64 = if (bitmap != null) {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            } else null

            val body = buildGenerateContentPayload(
                prompt = prompt,
                conversationHistory = conversationHistory,
                audioBase64 = audioB64,
                imageBase64 = imgB64,
                temperature = temperature,
                expectJson = expectJson
            )

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = extractError(raw, resp.code)
                    Log.w(TAG, "Direct Gemini HTTP ${resp.code} ($modelId): $errMsg")
                    return Result.failure(IllegalStateException("Gemini API (${resp.code}): $errMsg"))
                }
                val text = extractCandidateText(raw)
                if (text.isBlank()) {
                    Result.failure(IllegalStateException("Gemini API returned empty text."))
                } else {
                    Result.success(text)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct Gemini exception: ${e.message}")
            Result.failure(e)
        }
    }

    internal fun extractCandidateText(rawJson: String): String {
        return runCatching {
            val root = Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(rawJson)
                .jsonObject
            val candidates = root["candidates"] as? JsonArray ?: return ""
            val firstCand = candidates.firstOrNull() as? JsonObject ?: return ""
            val content = firstCand["content"] as? JsonObject ?: return ""
            val parts = content["parts"] as? JsonArray ?: return ""
            buildString {
                for (part in parts) {
                    val prim = (part as? JsonObject)?.get("text") as? JsonPrimitive ?: continue
                    if (prim !is JsonNull && prim.content.isNotEmpty()) {
                        append(prim.content)
                    }
                }
            }.trim()
        }.getOrDefault("")
    }

    private fun extractError(rawBody: String, code: Int): String {
        return runCatching {
            val root = Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(rawBody)
                .jsonObject
            val errObj = root["error"] as? JsonObject ?: return "HTTP $code"
            val msg = errObj["message"] as? JsonPrimitive ?: return "HTTP $code"
            if (msg !is JsonNull && msg.content.isNotBlank()) msg.content else "HTTP $code"
        }.getOrDefault("HTTP $code")
    }
}
