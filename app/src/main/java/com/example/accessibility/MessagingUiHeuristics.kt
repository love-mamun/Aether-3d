package com.example.accessibility

import java.util.Locale

data class UiNodeCandidate(
    val index: Int,
    val text: String,
    val contentDesc: String,
    val hint: String,
    val viewIdSuffix: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val cxNorm: Int,
    val cyNorm: Int
) {
    val combinedLabel: String
        get() = listOf(text, contentDesc, hint)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
}

data class ParsedAppMessageTarget(
    val appName: String,
    val recipientName: String
)

internal object MessagingUiHeuristics {

    private val NON_SEND_HINTS = listOf("voice", "audio", "mic", "record", "attach", "camera", "emoji", "sticker")
    private val KNOWN_MESSAGING_APPS = listOf(
        "whatsapp business",
        "whatsapp",
        "telegram",
        "messenger",
        "instagram",
        "signal",
        "viber",
        "imo",
        "messages",
        "sms"
    )

    fun parseAppAndRecipient(
        target: String,
        explicitRecipient: String
    ): ParsedAppMessageTarget {
        val cleanExplicit = explicitRecipient.trim()
        val rawTarget = target.trim()

        if (rawTarget.contains("|")) {
            val parts = rawTarget.split("|", limit = 2)
            val appPart = parts.getOrNull(0)?.trim().orEmpty()
            val recPart = cleanExplicit.ifBlank { parts.getOrNull(1)?.trim().orEmpty() }
            return ParsedAppMessageTarget(
                appName = appPart.ifBlank { "WhatsApp" },
                recipientName = recPart
            )
        }

        if (cleanExplicit.isNotBlank()) {
            val cleanedApp = rawTarget.ifBlank { "WhatsApp" }
            return ParsedAppMessageTarget(cleanedApp, cleanExplicit)
        }

        val lower = rawTarget.lowercase(Locale.US)
        for (knownApp in KNOWN_MESSAGING_APPS) {
            if (lower == knownApp) {
                return ParsedAppMessageTarget(rawTarget, "")
            }
            if (lower.startsWith(knownApp)) {
                val remainder = rawTarget.substring(knownApp.length)
                    .trim(' ', ':', '-', '|', ',')
                return ParsedAppMessageTarget(
                    appName = rawTarget.substring(0, knownApp.length).trim(),
                    recipientName = remainder
                )
            }
            if (lower.contains(knownApp)) {
                val stripped = rawTarget.replace(Regex(Regex.escape(knownApp), RegexOption.IGNORE_CASE), "")
                    .trim(' ', ':', '-', '|', ',')
                return ParsedAppMessageTarget(
                    appName = knownApp.replaceFirstChar { it.uppercase() },
                    recipientName = stripped
                )
            }
        }
        return ParsedAppMessageTarget(appName = "WhatsApp", recipientName = rawTarget)
    }

    fun pickSearchTrigger(nodes: List<UiNodeCandidate>): UiNodeCandidate? {
        val scored = nodes.mapNotNull { node ->
            val id = node.viewIdSuffix.lowercase(Locale.US)
            val label = node.combinedLabel.lowercase(Locale.US)

            // Explicitly skip standalone floating Meta AI / Gemini FAB buttons
            if (id.contains("meta_ai") || id.contains("ai_fab") ||
                (label == "meta ai" || (label.contains("meta ai") && !label.contains("search")))
            ) {
                return@mapNotNull null
            }

            var score = 0
            if (id == "menuitem_search" || id == "search" || id == "search_bar_text" ||
                id.contains("search_btn") || id.contains("search_button")
            ) {
                score += 120
            } else if (id.contains("search")) {
                score += 80
            }

            if (label == "search" || label == "search…" || label == "search...") {
                score += 100
            } else if (label.contains("search") || label.contains("খুঁজুন")) {
                score += 65
            }

            if (score == 0) return@mapNotNull null
            if (node.isClickable || node.isEditable) score += 25
            if (node.cyNorm in 30..300) score += 20
            if (node.cyNorm > 500) score -= 40
            node to score
        }
        return scored.maxByOrNull { it.second }?.first
    }

    fun pickSearchInputBox(nodes: List<UiNodeCandidate>): UiNodeCandidate? {
        val editables = nodes.filter { it.isEditable }
        if (editables.isEmpty()) return null
        return editables.mapNotNull { node ->
            val id = node.viewIdSuffix.lowercase(Locale.US)
            val label = node.combinedLabel.lowercase(Locale.US)
            var score = 0
            if (id.contains("search")) score += 100
            if (label.contains("search")) score += 80
            if (node.cyNorm in 30..360) score += 50
            if (id.contains("entry") || id.contains("composer") || label.contains("message")) {
                score -= 90
            }
            if (score <= 0 && node.cyNorm >= 450) return@mapNotNull null
            node to score
        }.maxByOrNull { it.second }?.first
            ?: editables.firstOrNull { it.cyNorm < 360 }
    }

    fun pickContactRow(nodes: List<UiNodeCandidate>, contactQuery: String): UiNodeCandidate? {
        val clean = contactQuery.trim().lowercase(Locale.US)
        if (clean.isBlank()) return null

        val queryTokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }

        val scored = nodes.mapNotNull { node ->
            // Never click an editable input box or top navigation chrome
            if (node.isEditable) return@mapNotNull null
            if (node.cyNorm < 150 || node.cyNorm > 935) return@mapNotNull null

            val id = node.viewIdSuffix.lowercase(Locale.US)
            if (id.contains("search_src_text") || id.contains("toolbar")) return@mapNotNull null

            val candidateTexts = listOf(node.text, node.contentDesc)
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (candidateTexts.isEmpty()) return@mapNotNull null

            val bestText = candidateTexts.first().lowercase(Locale.US)
            if (bestText.contains("ask meta ai") && !bestText.contains(clean)) return@mapNotNull null

            var score = 0
            for (rawText in candidateTexts) {
                val lower = rawText.lowercase(Locale.US)
                when {
                    lower == clean -> score = maxOf(score, 150)
                    Regex("\\b${Regex.escape(clean)}\\b").containsMatchIn(lower) -> score = maxOf(score, 120)
                    lower.startsWith(clean) -> score = maxOf(score, 105)
                    lower.contains(clean) -> score = maxOf(score, 85)
                    queryTokens.isNotEmpty() && queryTokens.all { lower.contains(it) } -> score = maxOf(score, 75)
                }
            }
            if (score == 0) return@mapNotNull null

            if (id.contains("contact") || id.contains("row") || id.contains("title") || id.contains("name")) {
                score += 25
            }
            // Prefer list rows in the left/center column over narrow right-edge timestamps/icons
            if (node.cxNorm in 100..750) score += 15
            node to score
        }

        return scored.maxWithOrNull(
            compareBy<Pair<UiNodeCandidate, Int>> { it.second }
                .thenByDescending { -it.first.cyNorm }
        )?.first
    }

    fun pickBottomComposer(nodes: List<UiNodeCandidate>): UiNodeCandidate? {
        val editables = nodes.filter { it.isEditable }
        if (editables.isEmpty()) return null

        return editables.mapNotNull { node ->
            val id = node.viewIdSuffix.lowercase(Locale.US)
            val label = node.combinedLabel.lowercase(Locale.US)
            var score = 0

            if (id == "entry" || id.contains("entry") || id.contains("composer") ||
                id.contains("message_edit") || id.contains("chat_edit") || id.contains("input")
            ) {
                score += 120
            }
            if (label.contains("message") || label.contains("type a message") ||
                label.contains("write a message") || label.contains("chat")
            ) {
                score += 80
            }
            if (node.cyNorm >= 480) {
                score += 70
            } else if (node.cyNorm < 340) {
                score -= 120
            }
            if (id.contains("search") || label.contains("search")) {
                score -= 150
            }

            if (score <= 0) return@mapNotNull null
            node to score
        }.maxByOrNull { it.second }?.first
    }

    fun pickSendButton(nodes: List<UiNodeCandidate>): UiNodeCandidate? {
        val clickables = nodes.filter { it.isClickable }
        val scored = clickables.mapNotNull { node ->
            val id = node.viewIdSuffix.lowercase(Locale.US)
            val label = node.combinedLabel.lowercase(Locale.US)

            if (NON_SEND_HINTS.any { hint -> id.contains(hint) || label.contains(hint) }) {
                return@mapNotNull null
            }

            var score = 0
            if (id == "send" || id == "send_btn" || id == "send_button" || id == "iv_send" || id == "btn_send") {
                score += 160
            } else if (id.contains("send")) {
                score += 120
            }

            if (label == "send" || label == "send message") {
                score += 140
            } else if (label.startsWith("send") || label.contains("পাঠান")) {
                score += 95
            }

            // Positional fallback: composer is typically active and Send button sits on bottom-right
            if (score == 0 && node.cxNorm >= 830 && node.cyNorm in 470..960 && !node.isEditable) {
                score += 45
            }

            if (score <= 0) return@mapNotNull null
            if (node.cyNorm >= 450) score += 20
            if (node.cxNorm >= 780) score += 15
            node to score
        }
        return scored.maxByOrNull { it.second }?.first
    }

    fun computeScreenSignature(packageName: String, nodes: List<UiNodeCandidate>): String {
        val visibleLabels = nodes.asSequence()
            .filter { it.cyNorm in 140..940 && it.combinedLabel.isNotBlank() }
            .map { "${it.combinedLabel.take(32)}@${it.cyNorm / 25}" }
            .distinct()
            .take(18)
            .joinToString("|")
        return "$packageName#$visibleLabels"
    }
}
