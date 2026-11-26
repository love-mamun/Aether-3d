package com.example.automation

import com.example.ai.ActionType
import com.example.ai.PlannedStep
import com.example.data.local.AppPlaybookEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class PlaybookStep(
    val actionKind: String, // "LAUNCH" | "TAP_TEXT" | "INPUT" | "TAP_COORDS" | "SCROLL"
    val anchorText: String = "",
    val sceneTag: String = "",
    val slotKey: String = "", // "", "postText", "query", "recipient"
    val literalFallback: String = "",
    val normX: Int = 500,
    val normY: Int = 500
)

data class PlaybookMatch(
    val playbook: AppPlaybookEntity,
    val steps: List<PlaybookStep>,
    val slotBindings: Map<String, String>,
    val confidenceScore: Float
)

/**
 * Self-learning UI Playbook distillation & matcher:
 * Converts successful multi-step screen runs into reusable parameterized step trajectories
 * so future runs skip repetitive cloud VLM round-trips and fall back to Vision only on drift.
 */
object PlaybookLearningEngine {

    private val postQuoteRegex = Regex("(?i)(?:post|write|likho|status)\\s+(?:that\\s+|je\\s+|on\\s+\\S+\\s+(?:that\\s+)?)?[\"']?([^\"']+)[\"']?$")
    private val playSearchRegex = Regex("(?i)(?:play|search|find|open|khujo)\\s+(.+?)(?:\\s+on\\s+(?:youtube|facebook|instagram|whatsapp))?$")

    fun distillPlaybook(
        appPackage: String,
        appLabel: String,
        userPrompt: String,
        rawSteps: List<PlaybookStep>,
        durationMs: Long
    ): AppPlaybookEntity {
        val cleanPkg = appPackage.trim().ifBlank { normalizePackageHint(appLabel, userPrompt) }
        val cleanLabel = appLabel.trim().ifBlank { inferAppLabel(cleanPkg, userPrompt) }
        val intentPattern = normalizeIntentPattern(userPrompt, cleanLabel)

        val parameterized = rawSteps.map { step ->
            if (step.actionKind == "INPUT" && step.slotKey.isBlank()) {
                val inferredSlot = when {
                    intentPattern.contains("post") -> "postText"
                    intentPattern.contains("message") || intentPattern.contains("chat") -> "messageText"
                    else -> "query"
                }
                step.copy(slotKey = inferredSlot)
            } else {
                step
            }
        }
        val varKeys = parameterized.mapNotNull { it.slotKey.takeIf { k -> k.isNotBlank() } }.distinct()

        return AppPlaybookEntity(
            appPackage = cleanPkg,
            appLabel = cleanLabel,
            intentPattern = intentPattern,
            variableKeysJson = encodeVariableKeys(varKeys),
            stepsJson = encodeStepsJson(parameterized),
            successCount = 1,
            failureCount = 0,
            avgDurationMs = durationMs.coerceAtLeast(100L),
            isPinned = false
        )
    }

    fun normalizeIntentPattern(userPrompt: String, appLabel: String = ""): String {
        val lower = userPrompt.lowercase(Locale.ROOT)
        val verb = when {
            lower.contains("post") || lower.contains("পোস্ট") || lower.contains("status") -> "post_social"
            lower.contains("message") || lower.contains("send") || lower.contains("মেসেজ") || lower.contains("পাঠাও") -> "send_message"
            lower.contains("channel") && lower.contains("video") -> "open_channel_video"
            lower.contains("play") || lower.contains("song") || lower.contains("video") || lower.contains("প্লে") -> "play_media"
            lower.contains("search") || lower.contains("find") || lower.contains("খুজ") -> "search_in_app"
            else -> "app_workflow"
        }
        val cleanApp = appLabel.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "generic" }
        return "$cleanApp:$verb"
    }

    fun findMatchingPlaybook(
        userPrompt: String,
        playbooks: List<AppPlaybookEntity>
    ): PlaybookMatch? {
        if (playbooks.isEmpty()) return null
        val lowerPrompt = userPrompt.lowercase(Locale.ROOT)
        val promptAppHint = inferAppLabel("", userPrompt).lowercase(Locale.ROOT)
        val promptPattern = normalizeIntentPattern(userPrompt, promptAppHint)

        val candidates = playbooks.mapNotNull { pb ->
            val pbSteps = decodeStepsJson(pb.stepsJson)
            if (pbSteps.isEmpty()) return@mapNotNull null
            val pbAppLower = pb.appLabel.lowercase(Locale.ROOT)
            val appMatches = (promptAppHint.isNotBlank() && pbAppLower == promptAppHint) ||
                lowerPrompt.contains(pbAppLower) ||
                (pb.appPackage.isNotBlank() && lowerPrompt.contains(pb.appPackage.substringAfterLast('.')))
            if (!appMatches) return@mapNotNull null

            val patternExact = pb.intentPattern == promptPattern
            val reliability = pb.successCount.toFloat() / (pb.successCount + pb.failureCount + 1).toFloat()
            val score = (if (patternExact) 0.7f else 0.35f) + (reliability * 0.3f)
            if (score < 0.55f) return@mapNotNull null

            PlaybookMatch(
                playbook = pb,
                steps = pbSteps,
                slotBindings = extractSlotBindings(userPrompt),
                confidenceScore = score
            )
        }
        return candidates.maxByOrNull { it.confidenceScore }
    }

    fun extractSlotBindings(userPrompt: String): Map<String, String> {
        val bindings = mutableMapOf<String, String>()
        val clean = userPrompt.trim()
        postQuoteRegex.find(clean)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
            bindings["postText"] = it
            bindings["messageText"] = it
        }
        playSearchRegex.find(clean)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
            bindings["query"] = it
        }
        if ("postText" !in bindings && "query" !in bindings) {
            bindings["postText"] = clean
            bindings["query"] = clean
        }
        return bindings
    }

    fun compilePlaybookToPlannedSteps(match: PlaybookMatch): List<PlannedStep> {
        return match.steps.mapIndexed { idx, step ->
            val id = "pb_${match.playbook.id}_$idx"
            val deps = if (idx == 0) emptyList() else listOf("pb_${match.playbook.id}_${idx - 1}")
            val resolvedPayload = if (step.slotKey.isNotBlank()) {
                match.slotBindings[step.slotKey].orEmpty().ifBlank { step.literalFallback }
            } else {
                step.literalFallback
            }

            when (step.actionKind) {
                "LAUNCH" -> PlannedStep(
                    stepId = id,
                    dependsOn = deps,
                    actionType = ActionType.LAUNCH_APP,
                    label = "Playbook: Open ${match.playbook.appLabel}",
                    target = match.playbook.appLabel
                )
                "INPUT" -> PlannedStep(
                    stepId = id,
                    dependsOn = deps,
                    actionType = ActionType.UI_INPUT_TEXT,
                    label = "Playbook: Type ${step.anchorText.ifBlank { "text" }}",
                    target = step.anchorText,
                    textPayload = resolvedPayload
                )
                "SCROLL" -> PlannedStep(
                    stepId = id,
                    dependsOn = deps,
                    actionType = ActionType.UI_SCROLL,
                    label = "Playbook: Scroll ${step.anchorText.ifBlank { "down" }}",
                    target = step.anchorText.ifBlank { "down" }
                )
                "TAP_COORDS" -> PlannedStep(
                    stepId = id,
                    dependsOn = deps,
                    actionType = ActionType.UI_TAP_COORDINATES,
                    label = "Playbook: Tap [${step.normX}, ${step.normY}]",
                    intValue = step.normX,
                    secondaryIntValue = step.normY
                )
                else -> PlannedStep(
                    stepId = id,
                    dependsOn = deps,
                    actionType = ActionType.UI_TAP_TEXT,
                    label = "Playbook: Tap ${step.anchorText.take(20)}",
                    target = step.anchorText
                )
            }
        }
    }

    fun encodeStepsJson(steps: List<PlaybookStep>): String {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("actionKind", s.actionKind)
                    put("anchorText", s.anchorText)
                    put("sceneTag", s.sceneTag)
                    put("slotKey", s.slotKey)
                    put("literalFallback", s.literalFallback)
                    put("normX", s.normX)
                    put("normY", s.normY)
                }
            )
        }
        return arr.toString()
    }

    fun decodeStepsJson(json: String): List<PlaybookStep> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                PlaybookStep(
                    actionKind = obj.optString("actionKind", "TAP_TEXT"),
                    anchorText = obj.optString("anchorText", ""),
                    sceneTag = obj.optString("sceneTag", ""),
                    slotKey = obj.optString("slotKey", ""),
                    literalFallback = obj.optString("literalFallback", ""),
                    normX = obj.optInt("normX", 500).coerceIn(0, 1000),
                    normY = obj.optInt("normY", 500).coerceIn(0, 1000)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun encodeVariableKeys(keys: List<String>): String = JSONArray(keys).toString()

    fun decodeVariableKeys(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { idx -> arr.optString(idx)?.takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    private fun normalizePackageHint(appLabel: String, userPrompt: String): String {
        val hay = "$appLabel $userPrompt".lowercase(Locale.ROOT)
        return when {
            hay.contains("facebook lite") || hay.contains("fb lite") -> "com.facebook.lite"
            hay.contains("facebook") -> "com.facebook.katana"
            hay.contains("youtube") || hay.contains("ইউটিউব") -> "com.google.android.youtube"
            hay.contains("whatsapp") -> "com.whatsapp"
            hay.contains("telegram") -> "org.telegram.messenger"
            hay.contains("instagram") -> "com.instagram.android"
            else -> appLabel.lowercase(Locale.ROOT).replace(" ", ".")
        }
    }

    private fun inferAppLabel(pkg: String, userPrompt: String): String {
        val hay = "$pkg $userPrompt".lowercase(Locale.ROOT)
        return when {
            hay.contains("facebook.lite") || hay.contains("facebook lite") || hay.contains("fb lite") -> "Facebook Lite"
            hay.contains("facebook") || hay.contains("katana") -> "Facebook"
            hay.contains("youtube") || hay.contains("ইউটিউব") -> "YouTube"
            hay.contains("whatsapp") -> "WhatsApp"
            hay.contains("telegram") -> "Telegram"
            hay.contains("instagram") -> "Instagram"
            else -> pkg.substringAfterLast('.').ifBlank { "App" }
        }
    }
}
