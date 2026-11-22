package com.example.system

import com.example.ai.AssistantPersona

data class WakeWordMatch(
    val matchedPhrase: String,
    val trailingCommand: String
)

object WakeWordDetector {

    private val baseVariants = listOf(
        "hey aether",
        "ok aether",
        "hi aether",
        "hey ether",
        "ok ether",
        "hey either",
        "hey arthur",
        "aether",
        "এথার",
        "হে এথার",
        "জারভিস"
    )

    fun match(
        transcript: String,
        customWakeWord: String = "Hey Aether",
        persona: AssistantPersona = AssistantPersona.JARVIS
    ): WakeWordMatch? {
        val raw = transcript.trim()
        if (raw.isBlank()) return null
        val lower = raw.lowercase()

        val personaPhrases = when (persona) {
            AssistantPersona.JARVIS -> listOf("hey jarvis", "ok jarvis", "jarvis")
            AssistantPersona.MAX -> listOf("hey max", "ok max")
            AssistantPersona.MAYRA -> listOf("hey mayra", "ok mayra", "mayra", "hey myra", "myra")
            AssistantPersona.STEALTH -> listOf("hey stealth", "stealth")
        }

        val allPhrases = buildList {
            val cleanCustom = customWakeWord.trim().lowercase()
            if (cleanCustom.length >= 3) add(cleanCustom)
            addAll(personaPhrases)
            addAll(baseVariants)
        }.distinct().sortedByDescending { it.length }

        for (phrase in allPhrases) {
            val regex = Regex("(^|[\\s,.:;!?])(${Regex.escape(phrase)})([\\s,.:;!?]+(.*))?$")
            val m = regex.find(lower)
            if (m != null) {
                val endIndex = m.groups[2]?.range?.last?.plus(1) ?: continue
                val remainder = raw.substring(endIndex.coerceAtMost(raw.length))
                    .trimStart(' ', ',', '.', ':', ';', '!', '?')
                    .trim()
                return WakeWordMatch(
                    matchedPhrase = raw.substring(
                        m.groups[2]!!.range.first,
                        (m.groups[2]!!.range.last + 1).coerceAtMost(raw.length)
                    ),
                    trailingCommand = remainder
                )
            }
        }
        return null
    }
}
