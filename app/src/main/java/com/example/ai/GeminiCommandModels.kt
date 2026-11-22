package com.example.ai

enum class AssistantPersona(
    val displayName: String,
    val wakePhraseHint: String,
    val stylePrompt: String,
    val ttsPitch: Float,
    val ttsRate: Float,
    val primaryRgb: FloatArray,
    val secondaryRgb: FloatArray
) {
    JARVIS(
        displayName = "JARVIS",
        wakePhraseHint = "Hey Aether / Jarvis",
        stylePrompt = "You are JARVIS-inspired Aether 3D: crisp, tactical, composed personal OS strategist fluent in English and Bangla (বাংলা). Always reply naturally in the user's language (Bangla or English) in 1-3 concise sentences.",
        ttsPitch = 0.96f,
        ttsRate = 0.98f,
        primaryRgb = floatArrayOf(0.0f, 0.90f, 1.0f),
        secondaryRgb = floatArrayOf(0.15f, 0.45f, 1.0f)
    ),
    MAX(
        displayName = "MAX",
        wakePhraseHint = "Hey Max",
        stylePrompt = "You are MAX-inspired Aether 3D: energetic, smart tech companion fluent in Bangla (বাংলা) and English. Keep replies warm, confident, action-first, and match the user's language (Bangla or English).",
        ttsPitch = 1.0f,
        ttsRate = 1.01f,
        primaryRgb = floatArrayOf(1.0f, 0.70f, 0.0f),
        secondaryRgb = floatArrayOf(1.0f, 0.35f, 0.10f)
    ),
    MAYRA(
        displayName = "MAYRA",
        wakePhraseHint = "Hey Mayra",
        stylePrompt = "You are MAYRA-inspired Aether 3D: empathetic, warm, articulate personal OS assistant fluent in Bangla (বাংলা) and English. Reply clearly and naturally in whichever language the user uses.",
        ttsPitch = 1.04f,
        ttsRate = 0.97f,
        primaryRgb = floatArrayOf(0.70f, 0.53f, 1.0f),
        secondaryRgb = floatArrayOf(1.0f, 0.28f, 0.68f)
    ),
    STEALTH(
        displayName = "STEALTH",
        wakePhraseHint = "Hey Aether",
        stylePrompt = "You are STEALTH tactical mode fluent in Bangla (বাংলা) and English: ultra-minimal, fast operational confirmation, zero fluff.",
        ttsPitch = 0.94f,
        ttsRate = 1.04f,
        primaryRgb = floatArrayOf(0.0f, 0.90f, 0.46f),
        secondaryRgb = floatArrayOf(0.0f, 0.70f, 0.85f)
    )
}

enum class SpeechLocaleOption(val label: String, val languageTag: String) {
    EN_US("English", "en-US"),
    BN_BD("বাংলা (Bangla)", "bn-BD")
}

enum class CoreMode(val statusLabel: String) {
    IDLE("ONLINE · READY"),
    LISTENING("LISTENING · VOICE STREAM"),
    OBSERVING_SCREEN("SCANNING ACTIVE SCREEN"),
    THINKING("NEURAL PLANNING"),
    MULTITASKING("EXECUTING MULTITASK QUEUE"),
    SPEAKING("VOCALIZING RESPONSE"),
    ERROR("ATTENTION REQUIRED")
}

enum class ActionType {
    SPEAK_ONLY,
    LAUNCH_APP,
    SEARCH_IN_APP,
    SET_ALARM,
    SET_TIMER,
    TOGGLE_TORCH,
    ADJUST_VOLUME,
    CONTROL_MEDIA,
    SET_BRIGHTNESS,
    SET_AUTO_ROTATE,
    OPEN_SETTINGS,
    DIAL_CONTACT,
    SEND_MESSAGE,
    DRAFT_EMAIL,
    NAVIGATE_MAPS,
    PLAY_MEDIA,
    WEB_SEARCH,
    CALENDAR_EVENT,
    WEATHER_LOOKUP,
    WIKIPEDIA_LOOKUP,
    CURRENCY_CONVERT,
    SAVE_MEMORY,
    CREATE_ROUTINE,
    SCREEN_EXPLAIN,
    UI_TAP_TEXT,
    UI_TAP_COORDINATES,
    UI_INPUT_TEXT,
    UI_SCROLL,
    GLOBAL_ACTION
}

enum class TaskStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    TIMED_OUT,
    SKIPPED,
    NEEDS_CONFIRMATION
}

data class PlannedStep(
    val actionType: ActionType,
    val label: String,
    val target: String = "",
    val textPayload: String = "",
    val intValue: Int = 0,
    val secondaryIntValue: Int = 0,
    val doubleValue: Double = 0.0,
    val boolValue: Boolean = false,
    val isBackgroundParallel: Boolean = false,
    val isSensitive: Boolean = false,
    val stepId: String = "",
    val dependsOn: List<String> = emptyList(),
    val timeoutMs: Long = 0L
)

data class CommandPlan(
    val reply: String,
    val needsScreenObservation: Boolean,
    val continueLoopAfterAction: Boolean,
    val steps: List<PlannedStep>
)

data class ActiveTaskItem(
    val id: String,
    val step: PlannedStep,
    val status: TaskStatus,
    val detail: String = "",
    val dependsOn: List<String> = emptyList(),
    val elapsedMs: Long = 0L,
    val timedOut: Boolean = false
)
