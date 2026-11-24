package com.example.core

import java.util.Locale

data class ConversationTurn(
    val userPrompt: String,
    val assistantReply: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ExtractedMemoryFact(
    val content: String,
    val category: String
)

/**
 * Thread-safe multi-turn conversation buffer with race-safe Room seeding.
 * Never overwrites newer in-session turns if cold-start DB hydration completes slightly later.
 */
class ConversationTurnBuffer(
    private val maxCapacity: Int = 16
) {
    private val lock = Any()
    private val turns = ArrayDeque<ConversationTurn>(maxCapacity)

    @Volatile
    private var seededFromDb = false

    fun isSeeded(): Boolean = seededFromDb

    fun addTurn(
        userPrompt: String,
        assistantReply: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val cleanUser = normalizeTurnText(userPrompt)
        val cleanReply = normalizeTurnText(assistantReply)
        if (cleanUser.isBlank() || cleanReply.isBlank()) return
        if (isOpaqueAudioPlaceholder(cleanUser)) return

        synchronized(lock) {
            val duplicateLast = turns.lastOrNull()?.let {
                it.userPrompt.equals(cleanUser, ignoreCase = true) &&
                    it.assistantReply.equals(cleanReply, ignoreCase = true)
            } == true
            if (duplicateLast) return

            while (turns.size >= maxCapacity) {
                turns.removeFirst()
            }
            turns.addLast(
                ConversationTurn(
                    userPrompt = cleanUser,
                    assistantReply = cleanReply,
                    timestamp = timestamp
                )
            )
        }
    }

    /**
     * Merges [logsNewestFirst] (ordered newest -> oldest, matching SQL `ORDER BY timestamp DESC`)
     * ahead of any runtime turns recorded before DB load completed.
     */
    fun seedFromLogsIfNeeded(logsNewestFirst: List<ConversationTurn>) {
        synchronized(lock) {
            if (seededFromDb) return
            seededFromDb = true

            val existingRuntime = turns.toList()
            val chronologicalFromDb = logsNewestFirst
                .asReversed()
                .filter {
                    it.userPrompt.isNotBlank() &&
                        it.assistantReply.isNotBlank() &&
                        !isOpaqueAudioPlaceholder(it.userPrompt)
                }

            val merged = mutableListOf<ConversationTurn>()
            val seenKeys = HashSet<String>()

            for (turn in chronologicalFromDb + existingRuntime) {
                val cleanU = normalizeTurnText(turn.userPrompt)
                val cleanA = normalizeTurnText(turn.assistantReply)
                if (cleanU.isBlank() || cleanA.isBlank()) continue
                val key = "${cleanU.lowercase(Locale.ROOT)}||${cleanA.lowercase(Locale.ROOT)}"
                if (seenKeys.add(key)) {
                    merged.add(ConversationTurn(cleanU, cleanA, turn.timestamp))
                }
            }

            turns.clear()
            merged.takeLast(maxCapacity).forEach { turns.addLast(it) }
        }
    }

    fun recentPairsChronological(limit: Int = 10): List<Pair<String, String>> {
        val safeLimit = limit.coerceAtLeast(1)
        val snapshot = synchronized(lock) { turns.toList() }
        return snapshot.takeLast(safeLimit).map { it.userPrompt to it.assistantReply }
    }

    fun formatPromptSummary(limit: Int = 10): String {
        val pairs = recentPairsChronological(limit)
        if (pairs.isEmpty()) return ""
        return pairs.mapIndexed { idx, (u, a) ->
            "Turn ${idx + 1} User: ${u.take(380)}\nTurn ${idx + 1} Assistant: ${a.take(460)}"
        }.joinToString("\n")
    }

    fun size(): Int = synchronized(lock) { turns.size }

    private fun normalizeTurnText(raw: String): String {
        return raw.trim().replace(Regex("\\s+"), " ")
    }

    private fun isOpaqueAudioPlaceholder(text: String): Boolean {
        return text.startsWith("Voice Audio (", ignoreCase = true) ||
            text.startsWith("Filtered Voice (", ignoreCase = true)
    }
}

/**
 * Deterministic fact extractor supporting English, Banglish, and Bangla so key user facts
 * ("Amar nam Mamun", "Remember my PIN is 4590", "মনে রেখো কাল মিটিং আছে") are stored
 * reliably in SQLite even when fast edge/cloud LLMs omit a SAVE_MEMORY step.
 */
object ConversationFactExtractor {

    private val excludedSelfStates = setOf(
        "happy", "sad", "tired", "busy", "fine", "good", "great", "okay", "ok",
        "hungry", "sleepy", "angry", "ready", "here", "back", "going", "leaving",
        "bored", "sick", "sorry", "well", "home", "outside", "late", "early",
        "confused", "excited", "worried", "done", "free", "valo", "bhalo", "kharap"
    )

    private val questionIndicators = listOf(
        "?", "what ", "what's ", "whats ", "who ", "where ", "when ", "which ", "how ",
        "ki ", "kothay", "ke ", "কী", "কি", "কোথায়", "কে "
    )

    fun extractDurableFacts(userPrompt: String): List<ExtractedMemoryFact> {
        val clean = userPrompt.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 6) return emptyList()
        val lower = clean.lowercase(Locale.ROOT)
        if (looksPureQuestion(lower)) return emptyList()

        val results = mutableListOf<ExtractedMemoryFact>()

        // 1. Explicit "remember / mone rekho / মনে রেখো" directives
        val rememberRegexes = listOf(
            Regex("^(?:please\\s+)?(?:remember(?:\\s+that)?|don'?t\\s+forget(?:\\s+that)?|keep\\s+in\\s+mind(?:\\s+that)?|note\\s+that|save\\s+that)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:please\\s+)?(?:eta\\s+)?(?:mone\\s+rekho|mone\\s+rakhba|mone\\s+rakho|vule\\s+jeo\\s+na)(?:\\s+je)?\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:দয়া করে\\s+)?(?:এটা\\s+)?(?:মনে রেখো|মনে রাখবা|মনে রাখো|মনে রাখবেন|ভুলে যেও না)(?:\\s+যে)?\\s+(.+)$")
        )
        for (regex in rememberRegexes) {
            val captured = regex.find(clean)?.groupValues?.getOrNull(1)?.trim()
            if (!captured.isNullOrBlank() && captured.length >= 4) {
                results.add(ExtractedMemoryFact(content = cleanFirstPersonPrefix(captured), category = "FACT"))
                break
            }
        }

        // 2. User name declaration ("My name is X", "Amar nam X", "আমার নাম X")
        val nameRegexes = listOf(
            Regex("\\b(?:my name is|call me)\\s+([\\p{L}][\\p{L}\\s.'-]{1,35})", RegexOption.IGNORE_CASE),
            Regex("\\b(?:amar nam|amar name)\\s+(?:holo|hoilo|hoche|hocche|is)?\\s*([\\p{L}][\\p{L}\\s.'-]{1,35})", RegexOption.IGNORE_CASE),
            Regex("(?:আমার নাম|আমাকে)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M}\\s.'-]{1,35})(?:\\s+বলে ডাকবে)?")
        )
        for (regex in nameRegexes) {
            val name = regex.find(clean)?.groupValues?.getOrNull(1)
                ?.trim(' ', '.', ',', '!', '।')
                ?.takeIf { it.length in 2..36 }
            if (name != null && name.lowercase(Locale.ROOT) !in excludedSelfStates) {
                results.add(ExtractedMemoryFact(content = "User's name is $name", category = "IDENTITY"))
                break
            }
        }

        // 3. Careful "I am <ProperName>" only if short sentence and not an emotion/state or action
        if (results.none { it.category == "IDENTITY" }) {
            Regex("^(?:hi|hello|hey)?[,\\s]*(?:i am|i'm|ami)\\s+([A-Z][a-zA-Z.'-]{1,24})(?:\\s+today)?$", RegexOption.IGNORE_CASE)
                .find(clean)
                ?.let { match ->
                    val candidate = match.groupValues[1].trim()
                    val candidateLower = candidate.lowercase(Locale.ROOT)
                    if (candidateLower !in excludedSelfStates &&
                        !lower.contains("happy today") &&
                        !lower.contains("going") &&
                        !lower.contains("feeling") &&
                        candidate.firstOrNull()?.isUpperCase() == true
                    ) {
                        results.add(ExtractedMemoryFact(content = "User's name is $candidate", category = "IDENTITY"))
                    }
                }
        }

        // 4. Home / Location / Contact / Favorites
        val locationPatterns = listOf(
            Regex("\\b(?:i live in|my home is in|my city is|amar basa|amar basha|amar bari)\\s+(?:holo|in|at|hocche)?\\s*([\\p{L}\\d][\\p{L}\\d\\s,.'-]{1,45})", RegexOption.IGNORE_CASE),
            Regex("(?:আমার বাসা|আমার বাড়ি|আমি থাকি)\\s+([\\p{L}\\p{M}\\d\\s,.'-]{2,45})")
        )
        for (regex in locationPatterns) {
            val loc = regex.find(clean)?.groupValues?.getOrNull(1)
                ?.trim(' ', '.', ',', '!', '।')
                ?.takeIf { it.length in 2..48 }
            if (loc != null) {
                results.add(ExtractedMemoryFact(content = "User's home/location is $loc", category = "PREFERENCE"))
                break
            }
        }

        val favoriteMatch = Regex("\\bmy\\s+favorite\\s+([a-zA-Z ]{2,24})\\s+is\\s+([^.?!।]{2,50})", RegexOption.IGNORE_CASE)
            .find(clean)
        if (favoriteMatch != null) {
            val topic = favoriteMatch.groupValues[1].trim()
            val value = favoriteMatch.groupValues[2].trim()
            results.add(ExtractedMemoryFact(content = "User's favorite $topic is $value", category = "PREFERENCE"))
        }

        return results.distinctBy { it.content.lowercase(Locale.ROOT) }
    }

    fun deduplicateNewFacts(
        candidates: List<ExtractedMemoryFact>,
        existingContents: Collection<String>
    ): List<ExtractedMemoryFact> {
        if (candidates.isEmpty()) return emptyList()
        val normalizedExisting = existingContents
            .map { normalizeKey(it) }
            .filter { it.isNotBlank() }
            .toHashSet()
        val out = mutableListOf<ExtractedMemoryFact>()
        for (item in candidates) {
            val key = normalizeKey(item.content)
            if (key.isNotBlank() && normalizedExisting.add(key)) {
                out.add(item)
            }
        }
        return out
    }

    private fun normalizeKey(text: String): String {
        return text.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s.?!,।]+"), " ")
            .trim()
    }

    private fun looksPureQuestion(lower: String): Boolean {
        if (lower.endsWith("?")) return true
        val startsLikeQuestion = listOf(
            "what ", "what's ", "whats ", "who ", "where ", "when ", "which ", "how ",
            "amar nam ki", "amr nam ki", "আমার নাম কি", "আমার নাম কী",
            "amar basa kothay", "amr basa kothay", "আমার বাসা কোথায়"
        )
        if (startsLikeQuestion.any { lower.startsWith(it) || lower.contains(it) }) {
            return true
        }
        if (lower.contains("remember") && questionIndicators.any { lower.startsWith(it.trim()) }) {
            return true
        }
        return false
    }

    private fun cleanFirstPersonPrefix(raw: String): String {
        return raw.trim(' ', '.', ',', '!', '।')
    }
}
