package com.example.system

import com.example.ai.ActionType
import com.example.ai.CommandPlan
import com.example.ai.PlannedStep
import java.util.Locale

data class OfflineMatchResult(
    val plan: CommandPlan,
    val label: String
)

/**
 * Deterministic zero-cost local router for offline/instant Android hardware & app controls.
 * Intentionally avoids intercepting compound multi-step commands (e.g., "Facebook khule post koro...",
 * "WhatsApp a message koro...") so those always flow to full AI + Screen Automation.
 */
object OfflineCommandRouter {

    private val multiStepMarkers = listOf(
        "message", "msg", "post", "comment", "search", "find", "play",
        "song", "video", "music", "send", "reply", "explain", "what is on",
        "screen", "remember", "remind", "weather", "news", "wiki",
        "মেসেজ", "পাঠাও", "পোস্ট", "লিখ", "খুজ", "খুঁজ", "প্লে", "গান", "ভিডিও", "সার্চ"
    )

    fun tryMatch(
        rawPrompt: String,
        installedAppsResolver: () -> Map<String, String> = { emptyMap() }
    ): OfflineMatchResult? {
        val clean = rawPrompt.trim()
        if (clean.isBlank()) return null
        val lower = clean.lowercase(Locale.ROOT)

        // Never intercept compound or multi-clause commands containing post/message/search goals
        val hasMultiStepIntent = multiStepMarkers.any { marker ->
            lower.contains(marker)
        }
        val hasCompoundConnector = lower.contains(" and ") ||
            lower.contains(" then ") ||
            lower.contains(" এবং ") ||
            lower.contains(" তারপর ")
        if (hasCompoundConnector) return null

        // 1. Flashlight / Torch
        if (matchesAny(lower, "flash", "torch", "লাইট", "টর্চ", "ফ্ল্যাশ")) {
            val turnOff = matchesAny(lower, "off", "disable", "stop", "বন্ধ", "অফ", "bondho", "of")
            val turnOn = matchesAny(lower, "on", "enable", "start", "চালু", "অন", "chalu", "jalo")
            if (turnOff xor turnOn) {
                val enable = turnOn
                val reply = if (enable) "Flashlight turned ON." else "Flashlight turned OFF."
                return OfflineMatchResult(
                    label = if (enable) "Offline · Torch ON" else "Offline · Torch OFF",
                    plan = CommandPlan(
                        reply = reply,
                        steps = listOf(
                            PlannedStep(
                                actionType = ActionType.TOGGLE_TORCH,
                                label = if (enable) "Flashlight ON" else "Flashlight OFF",
                                boolValue = enable,
                                isBackgroundParallel = true
                            )
                        )
                    )
                )
            }
        }

        // 2. Do Not Disturb (DND)
        if (matchesAny(lower, "dnd", "do not disturb", "ডিএনডি", "ডু নট ডিস্টার্ব", "silent mode")) {
            val turnOff = matchesAny(lower, "off", "disable", "stop", "বন্ধ", "অফ", "bondho")
            val turnOn = matchesAny(lower, "on", "enable", "start", "চালু", "অন", "chalu")
            val enable = if (turnOff && !turnOn) false else true
            val reply = if (enable) "Do Not Disturb enabled." else "Do Not Disturb disabled."
            return OfflineMatchResult(
                label = if (enable) "Offline · DND ON" else "Offline · DND OFF",
                plan = CommandPlan(
                    reply = reply,
                    steps = listOf(
                        PlannedStep(
                            actionType = ActionType.TOGGLE_DND,
                            label = if (enable) "DND ON" else "DND OFF",
                            boolValue = enable,
                            isBackgroundParallel = true
                        )
                    )
                )
            )
        }

        // 3. Wi-Fi quick toggle / panel
        if (matchesAny(lower, "wifi", "wi-fi", "ওয়াইফাই", "ওয়াইফাই")) {
            val turnOff = matchesAny(lower, "off", "disable", "বন্ধ", "অফ", "bondho")
            val enable = !turnOff
            val reply = if (turnOff) "Opening Wi-Fi controls to turn Wi-Fi OFF." else "Opening Wi-Fi controls."
            return OfflineMatchResult(
                label = "Offline · Wi-Fi",
                plan = CommandPlan(
                    reply = reply,
                    steps = listOf(
                        PlannedStep(
                            actionType = ActionType.TOGGLE_WIFI,
                            label = if (enable) "Wi-Fi ON" else "Wi-Fi OFF",
                            target = "wifi",
                            boolValue = enable
                        )
                    )
                )
            )
        }

        // 4. Bluetooth quick toggle / settings
        if (matchesAny(lower, "bluetooth", "ব্লুটুথ") && !hasMultiStepIntent) {
            val turnOff = matchesAny(lower, "off", "disable", "বন্ধ", "অফ", "bondho")
            return OfflineMatchResult(
                label = "Offline · Bluetooth",
                plan = CommandPlan(
                    reply = "Opening Bluetooth controls.",
                    steps = listOf(
                        PlannedStep(
                            actionType = ActionType.TOGGLE_BLUETOOTH,
                            label = "Bluetooth",
                            target = "bluetooth",
                            boolValue = !turnOff
                        )
                    )
                )
            )
        }

        // 5. Volume controls
        if (matchesAny(lower, "volume", "ভলিউম", "সাউন্ড") && !hasMultiStepIntent) {
            val pctMatch = Regex("\\b(\\d{1,3})\\b").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val targetMode = when {
                matchesAny(lower, "mute", "silent", "মিউট") -> "mute"
                matchesAny(lower, "up", "increase", "high", "বাড়াও", "বাড়াও", "bara") -> "up"
                matchesAny(lower, "down", "decrease", "low", "কমাও", "কমা", "koma") -> "down"
                else -> "set"
            }
            val pct = (pctMatch ?: 60).coerceIn(0, 100)
            return OfflineMatchResult(
                label = "Offline · Volume",
                plan = CommandPlan(
                    reply = "Adjusting volume.",
                    steps = listOf(
                        PlannedStep(
                            actionType = ActionType.ADJUST_VOLUME,
                            label = "Volume",
                            target = targetMode,
                            intValue = pct,
                            isBackgroundParallel = true
                        )
                    )
                )
            )
        }

        // 6. Media playback buttons (pause / resume / next / previous)
        val exactMedia = when {
            lower in setOf("pause", "pause music", "pause song", "stop music", "stop song", "গান বন্ধ করো") -> "pause"
            lower in setOf("resume", "resume music", "play music", "continue music") -> "play"
            lower in setOf("next song", "next track", "skip song", "skip track", "পরের গান") -> "next"
            lower in setOf("previous song", "previous track", "prev track", "আগের গান") -> "previous"
            else -> null
        }
        if (exactMedia != null) {
            return OfflineMatchResult(
                label = "Offline · Media",
                plan = CommandPlan(
                    reply = "Media: $exactMedia.",
                    steps = listOf(
                        PlannedStep(
                            actionType = ActionType.CONTROL_MEDIA,
                            label = "Media $exactMedia",
                            target = exactMedia,
                            isBackgroundParallel = true
                        )
                    )
                )
            )
        }

        // 7. Global OS actions (Home, Back, Screenshot, Lock Screen)
        val globalAction = when {
            lower in setOf("go home", "home screen", "হোম স্ক্রিন") -> "HOME"
            lower in setOf("go back", "back", "পিছনে যাও") -> "BACK"
            lower in setOf("take screenshot", "screenshot", "স্ক্রিনশট") -> "TAKE_SCREENSHOT"
            lower in setOf("lock screen", "lock phone", "লক করো") -> "LOCK_SCREEN"
            else -> null
        }
        if (globalAction != null) {
            return OfflineMatchResult(
                label = "Offline · $globalAction",
                plan = CommandPlan(
                    reply = "Executing $globalAction.",
                    steps = listOf(
                        PlannedStep(
                            actionType = ActionType.GLOBAL_ACTION,
                            label = globalAction,
                            target = globalAction
                        )
                    )
                )
            )
        }

        // 8. Direct phone calls ("call Ma", "dial 0171...", "মা কে কল করো")
        if (!hasMultiStepIntent && !lower.contains("whatsapp") && !lower.contains("messenger") && !lower.contains("telegram")) {
            val callTarget = extractDirectCallTarget(clean, lower)
            if (!callTarget.isNullOrBlank()) {
                return OfflineMatchResult(
                    label = "Offline · Call $callTarget",
                    plan = CommandPlan(
                        reply = "Calling $callTarget now.",
                        steps = listOf(
                            PlannedStep(
                                actionType = ActionType.DIAL_CONTACT,
                                label = "Call $callTarget",
                                target = callTarget,
                                isSensitive = false
                            )
                        )
                    )
                )
            }
        }

        // 8.5 Unread Notification Digest ("read my notifications", "unread messages", "নোটিফিকেশন পড়ো")
        if (matchesAny(lower, "notification", "notifications", "unread message", "unread alert", "নোটিফিকেশন", "মেসেজ পড়ো", "মেসেজ পড়ো") &&
            !lower.contains("reply") && !lower.contains("send") && !lower.contains("রিপ্লাই") &&
            !lower.contains(" and ") && !lower.contains(" then ") && !lower.contains(" ar ")
        ) {
            val preferBangla = clean.any { it in '\u0980'..'\u09FF' }
            return OfflineMatchResult(
                label = "Offline · Unread Digest",
                plan = CommandPlan(
                    reply = "Checking unread notifications.",
                    steps = listOf(
                        PlannedStep(
                            actionType = ActionType.READ_NOTIFICATIONS,
                            label = "Unread Notification Digest",
                            textPayload = if (preferBangla) "bn" else "en",
                            isBackgroundParallel = true
                        )
                    )
                )
            )
        }

        // 8.6 Live Camera Vision Lens ("open camera lens", "vision lens", "ক্যামেরা লেন্স")
        if (matchesAny(lower, "camera lens", "vision lens", "live lens", "look through camera", "ক্যামেরা লেন্স")) {
            return OfflineMatchResult(
                label = "Offline · Vision Lens",
                plan = CommandPlan(
                    reply = "Opening Live Vision Lens.",
                    steps = listOf(
                        PlannedStep(
                            actionType = ActionType.OPEN_CAMERA_LENS,
                            label = "Open Vision Lens",
                            target = clean
                        )
                    )
                )
            )
        }

        // 9. Simple pure app launch ("open YouTube", "open Dial", "Facebook khulo") — only when single goal!
        if (!hasMultiStepIntent && !lower.contains(" and ") && !lower.contains(" ar ") && !lower.contains(" করে ")) {
            val openMatch = Regex("^(?:open|launch|start|খুলো|খুল|ওপেন)\\s+(.+)$", RegexOption.IGNORE_CASE)
                .find(clean)
            val suffixOpenMatch = Regex("^(.+?)\\s+(?:খুলো|খুল|khulo|kholo|open)$", RegexOption.IGNORE_CASE)
                .find(clean)
            val appQuery = (openMatch?.groupValues?.getOrNull(1) ?: suffixOpenMatch?.groupValues?.getOrNull(1))
                ?.trim()
                ?.removeSuffix("app")
                ?.trim()
                .orEmpty()

            if (appQuery.isNotBlank() && appQuery.length <= 32) {
                val installed = installedAppsResolver()
                val directKnown = appQuery.lowercase(Locale.ROOT) in setOf(
                    "dial", "dialer", "phone", "camera", "settings", "clock", "browser", "chrome",
                    "youtube", "facebook", "whatsapp", "instagram", "telegram", "maps", "spotify"
                )
                val matchesInstalled = installed.keys.any {
                    it.equals(appQuery, ignoreCase = true) || it.contains(appQuery, ignoreCase = true)
                }
                if (directKnown || matchesInstalled) {
                    return OfflineMatchResult(
                        label = "Offline · Launch $appQuery",
                        plan = CommandPlan(
                            reply = "Opening $appQuery.",
                            steps = listOf(
                                PlannedStep(
                                    actionType = ActionType.LAUNCH_APP,
                                    label = "Open $appQuery",
                                    target = appQuery
                                )
                            )
                        )
                    )
                }
            }
        }

        return null
    }

    private fun extractDirectCallTarget(clean: String, lower: String): String? {
        Regex("^(?:call|dial|phone|কল করো|ফোন করো|কল দাও)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(clean)
            ?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        Regex("^(.+?)\\s+(?:কে কল করো|কে ফোন করো|কে কল দাও|ke call koro|ke phone koro)$", RegexOption.IGNORE_CASE)
            .find(clean)
            ?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        if (lower.startsWith("call ") && clean.length > 5) {
            return clean.substring(5).trim()
        }
        return null
    }

    private fun matchesAny(lower: String, vararg tokens: String): Boolean {
        return tokens.any { tok -> lower.contains(tok) }
    }
}
