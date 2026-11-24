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
        "hey heater",
        "ei aether",
        "aether",
        "এথার",
        "হে এথার",
        "হেই এথার"
    )

    fun match(
        transcript: String,
        customWakeWord: String = "Hey Aether",
        persona: AssistantPersona = AssistantPersona.AETHER
    ): WakeWordMatch? {
        val raw = transcript.trim()
        if (raw.isBlank()) return null
        val lower = raw.lowercase()

        val personaPhrases = when (persona) {
            AssistantPersona.AETHER -> listOf("hey aether", "ok aether", "aether")
            AssistantPersona.ARIA -> listOf("hey aria", "ok aria", "aria", "আরিয়া", "হে আরিয়া")
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
