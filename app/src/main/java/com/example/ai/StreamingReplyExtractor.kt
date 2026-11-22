package com.example.ai

/**
 * Extracts readable reply text incrementally from a partial JSON stream
 * (e.g. `{"reply":"Opening YouTube and...`) so the HUD streams natural text live
 * instead of raw JSON syntax.
 */
object StreamingReplyExtractor {

    private val replyKeyRegex = Regex("\"reply\"\\s*:\\s*\"")

    fun extractPartialReply(rawPartial: String): String {
        if (rawPartial.isBlank()) return ""
        val trimmed = rawPartial.trimStart()
        // If model responded in plain text rather than JSON, show directly
        if (!trimmed.startsWith("{") && !trimmed.startsWith("```")) {
            return trimmed.take(360)
        }
        val match = replyKeyRegex.find(rawPartial) ?: return ""
        var i = match.range.last + 1
        val out = StringBuilder()
        while (i < rawPartial.length) {
            val c = rawPartial[i]
            if (c == '\\' && i + 1 < rawPartial.length) {
                val next = rawPartial[i + 1]
                when (next) {
                    'n' -> out.append(' ')
                    'r' -> {}
                    't' -> out.append(' ')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    'u' -> {
                        if (i + 5 < rawPartial.length) {
                            val hex = rawPartial.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 4
                            }
                        }
                    }
                    else -> out.append(next)
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString().trim()
    }
}
