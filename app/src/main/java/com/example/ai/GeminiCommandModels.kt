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
    AETHER(
        displayName = "AETHER",
        wakePhraseHint = "Hey Aether",
        stylePrompt = "You are AETHER, a fast, loyal, intelligent personal OS companion fluent in English and Bangla (বাংলা). Remember prior conversation turns naturally and reply clearly in 1-2 concise sentences matching the user's language.",
        ttsPitch = 0.96f,
        ttsRate = 1.05f,
        primaryRgb = floatArrayOf(0.0f, 0.90f, 1.0f),
        secondaryRgb = floatArrayOf(0.15f, 0.45f, 1.0f)
    ),
    ARIA(
        displayName = "ARIA",
        wakePhraseHint = "Hey Aria / Hey Aether",
        stylePrompt = "You are ARIA, a warm, perceptive, expressive personal OS companion fluent in Bangla (বাংলা) and English. Remember prior conversation turns naturally and reply smoothly in 1-2 concise sentences matching the user's language.",
        ttsPitch = 1.03f,
        ttsRate = 1.05f,
        primaryRgb = floatArrayOf(0.70f, 0.53f, 1.0f),
        secondaryRgb = floatArrayOf(1.0f, 0.28f, 0.68f)
    );

    companion object {
        fun fromStoredName(rawName: String?): AssistantPersona {
            return when (rawName?.trim()?.uppercase()) {
                "ARIA", "MAYRA" -> ARIA
                else -> AETHER
            }
        }
    }
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
    TOGGLE_DND,
    TOGGLE_WIFI,
    TOGGLE_BLUETOOTH,
    ADJUST_VOLUME,
    CONTROL_MEDIA,
    SET_BRIGHTNESS,
    SET_AUTO_ROTATE,
    OPEN_SETTINGS,
    DIAL_CONTACT,
    SEND_MESSAGE,
    SEND_APP_MESSAGE,
    POST_SOCIAL,
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
    GLOBAL_ACTION,
    READ_NOTIFICATIONS,
    REPLY_NOTIFICATION,
    OPEN_CAMERA_LENS
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
    val recipient: String = "",
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
    val needsScreenObservation: Boolean = false,
    val continueLoopAfterAction: Boolean = false,
    val steps: List<PlannedStep>,
    val recognizedTranscript: String = "",
    val subGoals: List<String> = emptyList()
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
