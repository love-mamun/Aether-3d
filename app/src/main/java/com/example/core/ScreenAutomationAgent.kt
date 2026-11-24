package com.example.core

import android.graphics.Bitmap
import com.example.accessibility.AetherAccessibilityService
import com.example.accessibility.MessagingUiHeuristics
import com.example.ai.ActionType
import com.example.ai.AssistantPersona
import com.example.ai.CommandPlan
import com.example.ai.GeminiCommandService
import com.example.ai.PlannedStep
import com.example.data.local.VisionTokenMode
import com.example.system.ActionExecutionResult
import com.example.system.AndroidActionRouter
import com.example.vision.OcrLineItem
import com.example.vision.OfflineScreenUnderstandingEngine
import com.example.vision.ScreenVisionProcessor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.Locale

data class AppMessageOutcome(
    val success: Boolean,
    val summary: String,
    val needsAccessibilityPermission: Boolean = false
)

data class ReActProgressUpdate(
    val stepIndex: Int,
    val maxSteps: Int,
    val subGoals: List<String>,
    val activeSubGoalIndex: Int,
    val statusLabel: String,
    val isWaitingForLoad: Boolean,
    val visionSource: String,
    val tokensSavedDelta: Int = 0,
    val offlineSceneBadge: String = ""
)

internal object MultiStepGoalClassifier {
    private val multiHopMarkers = listOf(
        "channel", "chaneel", "চ্যানেল",
        "videos tab", "video section", "videos section", "ভিডিও সেকশন", "ভিডিও ট্যাব",
        "first video", "1st video", "prothom video", "প্রথম ভিডিও", "latest video",
        "giye", "গিয়ে", "গিয়ে", "tarpor", "তারপর", "then ", "and then",
        "click kore", "ক্লিক করে", "open kore", "খুলে তারপর",
        "comment", "কমেন্ট", "like", "subscribe", "সাবস্ক্রাইব",
        "profile", "প্রোফাইল", "story", "stories", "reels", "shorts tab"
    )

    fun isMultiHopYouTubeWorkflow(userPrompt: String, plan: CommandPlan? = null): Boolean {
        val lower = userPrompt.trim().lowercase(Locale.ROOT)
        if (!lower.contains("youtube") && !lower.contains("ইউটিউব") && !lower.contains("yt ")) {
            if (plan == null) return false
        }
        val markerHits = multiHopMarkers.count { lower.contains(it) }
        if (markerHits >= 1 && (lower.contains("youtube") || lower.contains("ইউটিউব") || lower.contains("channel"))) {
            return true
        }
        if (plan != null && plan.subGoals.size >= 3) return true
        return false
    }

    fun isMultiHopAppWorkflow(userPrompt: String, plan: CommandPlan): Boolean {
        if (plan.subGoals.size >= 2) return true
        if (plan.continueLoopAfterAction) return true
        if (isMultiHopYouTubeWorkflow(userPrompt, plan)) return true
        val lower = userPrompt.trim().lowercase(Locale.ROOT)
        return multiHopMarkers.count { lower.contains(it) } >= 2
    }

    fun deriveSubGoals(userPrompt: String, plan: CommandPlan): List<String> {
        if (plan.subGoals.size >= 2) return plan.subGoals.take(6)
        val lower = userPrompt.lowercase(Locale.ROOT)
        if (lower.contains("youtube") || lower.contains("ইউটিউব")) {
            return buildList {
                add("Open YouTube")
                add("Search query")
                if (lower.contains("channel") || lower.contains("চ্যানেল")) add("Open channel")
                if (lower.contains("video")) add("Open Videos section")
                add("Play target video")
            }
        }
        return listOf("Open app", "Navigate & wait for content", "Complete action")
    }
}

internal object ScreenLoopGuard {
    const val MAX_SCREEN_LOOP_ITERATIONS = 12
    const val MAX_SCROLL_SWEEPS = 6
    const val MAX_CONSECUTIVE_STALLS = 4

    fun evaluateStall(
        previousSignature: String,
        newSignature: String,
        currentStallCount: Int
    ): Pair<Int, Boolean> {
        val nextCount = if (previousSignature.isNotBlank() && previousSignature == newSignature) {
            currentStallCount + 1
        } else {
            0
        }
        return nextCount to (nextCount >= MAX_CONSECUTIVE_STALLS)
    }

    fun expectedPackageKeywords(appName: String): List<String> {
        val clean = appName.trim().lowercase(Locale.US)
        return when {
            clean.contains("whatsapp") -> listOf("whatsapp")
            clean.contains("facebook") -> listOf("katana", "facebook", "lite")
            clean.contains("youtube") -> listOf("youtube")
            clean.contains("telegram") -> listOf("telegram", "nekogram")
            clean.contains("messenger") -> listOf("orca", "messenger")
            clean.contains("instagram") -> listOf("instagram")
            clean.contains("signal") -> listOf("signal")
            clean.contains("viber") -> listOf("viber")
            clean.contains("sms") || clean.contains("message") -> listOf("messaging", "mms", "sms")
            clean.isNotBlank() -> listOf(clean.replace(" ", ""))
            else -> emptyList()
        }
    }

    fun findBestOcrContactMatch(
        lines: List<OcrLineItem>,
        contactQuery: String,
        minY: Int = 165,
        maxY: Int = 920
    ): OcrLineItem? {
        val clean = contactQuery.trim().lowercase(Locale.US)
        if (clean.isBlank()) return null
        val band = lines.filter { it.centerYNorm in minY..maxY }
        return band.firstOrNull { it.text.trim().equals(clean, ignoreCase = true) }
            ?: band.firstOrNull { Regex("\\b${Regex.escape(clean)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.text) }
            ?: band.firstOrNull { it.text.contains(clean, ignoreCase = true) && !it.text.contains("ask meta ai", ignoreCase = true) }
    }
}

class ScreenAutomationAgent(
    private val actionRouter: AndroidActionRouter,
    private val visionProcessor: ScreenVisionProcessor,
    private val geminiService: GeminiCommandService
) {

    suspend fun executeAppMessageGoal(
        step: PlannedStep,
        originalPrompt: String,
        persona: AssistantPersona,
        deviceContextSummary: String,
        memoriesSummary: String,
        routinesSummary: String,
        contactsSummary: String,
        installedAppsSummary: String,
        preferNvidiaPrimary: Boolean,
        splitScreenEnabled: Boolean,
        autoSend: Boolean,
        onStepProgress: (String) -> Unit,
        onStreamingReply: (String) -> Unit,
        onActiveModelChanged: (String) -> Unit
    ): AppMessageOutcome {
        val parsed = MessagingUiHeuristics.parseAppAndRecipient(
            target = step.target,
            explicitRecipient = step.recipient
        )
        val appName = parsed.appName.ifBlank { "WhatsApp" }
        val contactQuery = parsed.recipientName.trim()
        val messageText = step.textPayload.trim()

        val accessibility = AetherAccessibilityService.currentInstance
            ?: return AppMessageOutcome(
                success = false,
                needsAccessibilityPermission = true,
                summary = "Enable Trusted Screen Agent (Accessibility) in Permissions Hub so Aether can search for '$contactQuery', type your message, and tap Send automatically."
            )

        if (contactQuery.isBlank() || messageText.isBlank()) {
            val missing = if (contactQuery.isBlank()) "contact name" else "message text"
            return AppMessageOutcome(
                success = false,
                summary = "Missing $missing to send in $appName."
            )
        }

        // Step 1: Launch target app & wait for window settle
        onStepProgress("Launching $appName...")
        val launchRes = actionRouter.launchAppByName(appName, splitScreenEnabled)
        if (!launchRes.success) {
            return AppMessageOutcome(success = false, summary = launchRes.detail)
        }
        val pkgKeywords = ScreenLoopGuard.expectedPackageKeywords(appName)
        accessibility.waitForForegroundPackage(pkgKeywords, timeoutMs = 4_200L)
        delay(650L)
        currentCoroutineContext().ensureActive()

        // Step 2: Check if app opened directly onto a different conversation
        var inTargetChat = accessibility.isConversationOpenFor(contactQuery)
        if (!inTargetChat && accessibility.hasBottomMessageComposer()) {
            onStepProgress("Returning to $appName chat list...")
            accessibility.performSystemGlobalAction("BACK")
            delay(650L)
        }

        // Step 3: In-app search bar lookup (Search First)
        if (!inTargetChat) {
            onStepProgress("Searching '$contactQuery' in $appName...")
            val searched = accessibility.triggerAndTypeInAppSearch(contactQuery)
            if (searched) {
                delay(950L)
                currentCoroutineContext().ensureActive()
                if (accessibility.clickContactRowInApp(contactQuery)) {
                    delay(750L)
                    inTargetChat = waitForComposerOpen(accessibility, timeoutMs = 1_800L)
                } else {
                    // Try ML Kit OCR in case custom list view omitted Accessibility text
                    val tappedViaOcr = tryTapContactViaOcr(accessibility, contactQuery)
                    if (tappedViaOcr) {
                        delay(750L)
                        inTargetChat = waitForComposerOpen(accessibility, timeoutMs = 1_800L)
                    }
                }
            }
        }

        // Step 4: Scroll Fallback if Search did not enter chat
        if (!inTargetChat) {
            // Back out of search results screen cleanly before scrolling main list
            val sigNow = accessibility.captureUiSignature()
            if (sigNow.contains("search", ignoreCase = true) ||
                accessibility.currentForegroundPackage()?.lowercase()?.let { p ->
                    pkgKeywords.any { p.contains(it) }
                } == true
            ) {
                if (!accessibility.hasBottomMessageComposer()) {
                    // Only navigate back if we are inside search results rather than top list
                    val nodeSnap = accessibility.captureFullScreenContext(includeBitmap = false)
                    val looksLikeSearchScreen = nodeSnap?.uiSummary?.contains("id=search_src_text", ignoreCase = true) == true ||
                        nodeSnap?.uiSummary?.contains("Search...", ignoreCase = true) == true
                    if (looksLikeSearchScreen) {
                        accessibility.performSystemGlobalAction("BACK")
                        delay(650L)
                    }
                }
            }

            var prevSig = ""
            var stallCount = 0
            for (sweep in 1..ScreenLoopGuard.MAX_SCROLL_SWEEPS) {
                currentCoroutineContext().ensureActive()
                onStepProgress("Scanning chat list ($sweep/${ScreenLoopGuard.MAX_SCROLL_SWEEPS}) for '$contactQuery'...")

                if (accessibility.clickContactRowInApp(contactQuery) ||
                    tryTapContactViaOcr(accessibility, contactQuery)
                ) {
                    delay(750L)
                    if (waitForComposerOpen(accessibility, timeoutMs = 1_800L)) {
                        inTargetChat = true
                        break
                    }
                }

                val sigBefore = accessibility.captureUiSignature()
                val (nextStalls, stalled) = ScreenLoopGuard.evaluateStall(prevSig, sigBefore, stallCount)
                stallCount = nextStalls
                if (stalled) break
                prevSig = sigBefore

                accessibility.scrollScreen("down")
                delay(850L)
            }
        }

        // Step 5: AI Vision Rescue if heuristics couldn't open the chat yet (up to 2 vision turns)
        if (!inTargetChat) {
            repeat(2) { attempt ->
                currentCoroutineContext().ensureActive()
                onStepProgress("Using screen vision to locate '$contactQuery' (${attempt + 1}/2)...")
                val outcome = runSingleVisionStep(
                    goalInstruction = "We are inside $appName trying to open the chat for '$contactQuery' so we can send '$messageText'. Inspect the current screen and emit UI_TAP_TEXT or UI_TAP_COORDINATES or UI_INPUT_TEXT to open '$contactQuery' chat.",
                    persona = persona,
                    deviceContextSummary = deviceContextSummary,
                    memoriesSummary = memoriesSummary,
                    routinesSummary = routinesSummary,
                    contactsSummary = contactsSummary,
                    installedAppsSummary = installedAppsSummary,
                    preferNvidiaPrimary = preferNvidiaPrimary,
                    splitScreenEnabled = splitScreenEnabled,
                    onStreamingReply = onStreamingReply,
                    onActiveModelChanged = onActiveModelChanged
                )
                if (outcome.executedAnyAction) {
                    delay(850L)
                    if (waitForComposerOpen(accessibility, timeoutMs = 1_600L)) {
                        inTargetChat = true
                        return@repeat
                    }
                } else {
                    return@repeat
                }
            }
        }

        if (!inTargetChat) {
            return AppMessageOutcome(
                success = false,
                summary = "Opened $appName, searched and scrolled the chat list, but couldn't find '$contactQuery'."
            )
        }

        // Step 6: Type message in bottom chat composer
        currentCoroutineContext().ensureActive()
        onStepProgress("Typing message to '$contactQuery'...")
        val typed = accessibility.inputInBottomMessageComposer(messageText) ||
            accessibility.inputText("message", messageText, submitSearch = false)
        if (!typed) {
            return AppMessageOutcome(
                success = false,
                summary = "Opened chat with '$contactQuery' in $appName, but couldn't type into the message box."
            )
        }

        if (!autoSend) {
            return AppMessageOutcome(
                success = true,
                summary = "Typed message for '$contactQuery' in $appName and left it ready to send."
            )
        }

        // Step 7: Allow mic->send icon morph and tap Send automatically
        delay(380L)
        currentCoroutineContext().ensureActive()
        onStepProgress("Tapping Send in $appName...")
        var sentTapped = accessibility.clickMessagingSendButton()
        if (!sentTapped) {
            val visionSend = runSingleVisionStep(
                goalInstruction = "The message '$messageText' is typed in the bottom composer for '$contactQuery' in $appName. Tap the Send icon button on the bottom right now.",
                persona = persona,
                deviceContextSummary = deviceContextSummary,
                memoriesSummary = memoriesSummary,
                routinesSummary = routinesSummary,
                contactsSummary = contactsSummary,
                installedAppsSummary = installedAppsSummary,
                preferNvidiaPrimary = preferNvidiaPrimary,
                splitScreenEnabled = splitScreenEnabled,
                onStreamingReply = onStreamingReply,
                onActiveModelChanged = onActiveModelChanged
            )
            sentTapped = visionSend.executedAnyAction
        }

        if (!sentTapped) {
            return AppMessageOutcome(
                success = false,
                summary = "Typed '$messageText' for '$contactQuery' in $appName, but couldn't tap Send."
            )
        }

        delay(500L)
        val cleared = accessibility.isBottomMessageComposerEmpty()
        val finalMsg = if (cleared) {
            "Sent '$messageText' to $contactQuery on $appName."
        } else {
            "Message entered and Send tapped for $contactQuery on $appName."
        }
        return AppMessageOutcome(success = true, summary = finalMsg)
    }

    suspend fun executeSocialPostGoal(
        step: PlannedStep,
        persona: AssistantPersona,
        deviceContextSummary: String,
        memoriesSummary: String,
        routinesSummary: String,
        contactsSummary: String,
        installedAppsSummary: String,
        preferNvidiaPrimary: Boolean,
        splitScreenEnabled: Boolean,
        autoPost: Boolean,
        onStepProgress: (String) -> Unit,
        onStreamingReply: (String) -> Unit,
        onActiveModelChanged: (String) -> Unit
    ): AppMessageOutcome {
        val appName = step.target.ifBlank { "Facebook" }
        val postBody = step.textPayload.trim()
        val accessibility = AetherAccessibilityService.currentInstance
            ?: return AppMessageOutcome(
                success = false,
                needsAccessibilityPermission = true,
                summary = "Enable Trusted Screen Agent (Accessibility) so Aether can open $appName, write '$postBody', and post it automatically."
            )

        if (postBody.isBlank()) {
            return AppMessageOutcome(false, "Tell me what text you want to post on $appName.")
        }

        // 1/4: Launch Facebook & wait for feed to clear splash screen
        onStepProgress("1/4 · Opening $appName...")
        val launchRes = actionRouter.launchAppByName(appName, splitScreenEnabled)
        if (!launchRes.success) {
            return AppMessageOutcome(false, launchRes.detail)
        }
        val pkgKeywords = ScreenLoopGuard.expectedPackageKeywords(appName)
        accessibility.waitForNonSplashUi(pkgKeywords, timeoutMs = 6_500L)
        delay(800L)
        currentCoroutineContext().ensureActive()

        // 2/4: Tap 'What's on your mind?' / 'Create post' composer trigger
        onStepProgress("2/4 · Opening post composer...")
        var composerOpened = false
        if (accessibility.clickBestFacebookComposer()) {
            delay(1_100L)
            composerOpened = true
        } else {
            val visionOpen = runSingleVisionStep(
                goalInstruction = "We are inside $appName. Tap the 'What's on your mind?' or 'Create post' button/box to open the post composer.",
                persona = persona,
                deviceContextSummary = deviceContextSummary,
                memoriesSummary = memoriesSummary,
                routinesSummary = routinesSummary,
                contactsSummary = contactsSummary,
                installedAppsSummary = installedAppsSummary,
                preferNvidiaPrimary = preferNvidiaPrimary,
                splitScreenEnabled = splitScreenEnabled,
                onStreamingReply = onStreamingReply,
                onActiveModelChanged = onActiveModelChanged
            )
            if (visionOpen.executedAnyAction) {
                delay(1_100L)
                composerOpened = true
            }
        }

        if (!composerOpened) {
            return AppMessageOutcome(
                success = false,
                summary = "Opened $appName, but couldn't locate the 'What's on your mind?' post box."
            )
        }

        // 3/4: Enter the post text
        currentCoroutineContext().ensureActive()
        onStepProgress("3/4 · Writing post: \"$postBody\"...")
        val typed = accessibility.inputSocialComposerText(postBody)
        if (!typed) {
            val visionType = runSingleVisionStep(
                goalInstruction = "We are in $appName post composer. Use UI_INPUT_TEXT to type '$postBody' into the post text box.",
                persona = persona,
                deviceContextSummary = deviceContextSummary,
                memoriesSummary = memoriesSummary,
                routinesSummary = routinesSummary,
                contactsSummary = contactsSummary,
                installedAppsSummary = installedAppsSummary,
                preferNvidiaPrimary = preferNvidiaPrimary,
                splitScreenEnabled = splitScreenEnabled,
                onStreamingReply = onStreamingReply,
                onActiveModelChanged = onActiveModelChanged
            )
            if (!visionType.executedAnyAction) {
                return AppMessageOutcome(
                    success = false,
                    summary = "Opened $appName composer, but couldn't type the post text."
                )
            }
        }

        if (!autoPost) {
            return AppMessageOutcome(true, "Wrote '$postBody' in $appName composer ready to post.")
        }

        // 4/4: Tap Post / Publish automatically
        delay(550L)
        currentCoroutineContext().ensureActive()
        onStepProgress("4/4 · Publishing post on $appName...")
        var published = accessibility.clickBestPublishPostButton()
        if (!published) {
            val visionPost = runSingleVisionStep(
                goalInstruction = "The post text '$postBody' is entered in $appName composer. Tap the blue 'Post' or 'Share' button at top-right now.",
                persona = persona,
                deviceContextSummary = deviceContextSummary,
                memoriesSummary = memoriesSummary,
                routinesSummary = routinesSummary,
                contactsSummary = contactsSummary,
                installedAppsSummary = installedAppsSummary,
                preferNvidiaPrimary = preferNvidiaPrimary,
                splitScreenEnabled = splitScreenEnabled,
                onStreamingReply = onStreamingReply,
                onActiveModelChanged = onActiveModelChanged
            )
            published = visionPost.executedAnyAction
        }

        return if (published) {
            delay(500L)
            AppMessageOutcome(true, "Posted '$postBody' on $appName.")
        } else {
            AppMessageOutcome(false, "Typed '$postBody' on $appName, tap Post to finish.")
        }
    }

    suspend fun executeYouTubePlayGoal(
        step: PlannedStep,
        persona: AssistantPersona,
        deviceContextSummary: String,
        memoriesSummary: String,
        routinesSummary: String,
        contactsSummary: String,
        installedAppsSummary: String,
        preferNvidiaPrimary: Boolean,
        splitScreenEnabled: Boolean,
        onStepProgress: (String) -> Unit,
        onStreamingReply: (String) -> Unit,
        onActiveModelChanged: (String) -> Unit
    ): AppMessageOutcome {
        val query = step.textPayload.ifBlank { step.target }.trim()
        onStepProgress("1/3 · Opening YouTube search for '$query'...")
        val launchRes = actionRouter.executeStep(
            step.copy(actionType = ActionType.PLAY_MEDIA, target = "YouTube", textPayload = query),
            splitScreenEnabled = splitScreenEnabled
        )
        if (!launchRes.success) {
            return AppMessageOutcome(false, launchRes.detail)
        }

        val accessibility = AetherAccessibilityService.currentInstance
            ?: return AppMessageOutcome(true, launchRes.detail)

        onStepProgress("2/3 · Waiting for YouTube results...")
        accessibility.waitForNonSplashUi(listOf("youtube"), timeoutMs = 6_000L)
        delay(1_100L)
        currentCoroutineContext().ensureActive()

        onStepProgress("3/3 · Playing top result for '$query'...")
        if (accessibility.clickFirstYouTubeVideoResult()) {
            return AppMessageOutcome(true, "Playing '$query' on YouTube.")
        }

        val visionPlay = runSingleVisionStep(
            goalInstruction = "YouTube search results for '$query' are visible. Tap the first video thumbnail or title in the middle of the screen to play it.",
            persona = persona,
            deviceContextSummary = deviceContextSummary,
            memoriesSummary = memoriesSummary,
            routinesSummary = routinesSummary,
            contactsSummary = contactsSummary,
            installedAppsSummary = installedAppsSummary,
            preferNvidiaPrimary = preferNvidiaPrimary,
            splitScreenEnabled = splitScreenEnabled,
            onStreamingReply = onStreamingReply,
            onActiveModelChanged = onActiveModelChanged
        )
        return if (visionPlay.executedAnyAction) {
            AppMessageOutcome(true, "Playing '$query' on YouTube.")
        } else {
            AppMessageOutcome(true, "Opened YouTube search results for '$query'.")
        }
    }

    suspend fun runAutonomousScreenLoop(
        userPrompt: String,
        persona: AssistantPersona,
        deviceContextSummary: String,
        memoriesSummary: String,
        routinesSummary: String,
        contactsSummary: String,
        installedAppsSummary: String,
        preferNvidiaPrimary: Boolean,
        splitScreenEnabled: Boolean,
        onStepProgress: (String) -> Unit,
        onStreamingReply: (String) -> Unit,
        onActiveModelChanged: (String) -> Unit
    ): String? {
        val accessibility = AetherAccessibilityService.currentInstance ?: return null
        var previousSig = ""
        var stallCount = 0
        val completedStepNotes = mutableListOf<String>()

        accessibility.waitForNonSplashUi(emptyList(), timeoutMs = 5_000L)

        for (stepIdx in 1..ScreenLoopGuard.MAX_SCREEN_LOOP_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            delay(1_050L)
            onStepProgress("Step $stepIdx/${ScreenLoopGuard.MAX_SCREEN_LOOP_ITERATIONS} · Observing screen...")

            val currentSig = accessibility.captureUiSignature()
            val (nextStall, stalled) = ScreenLoopGuard.evaluateStall(previousSig, currentSig, stallCount)
            stallCount = nextStall
            if (stalled && stepIdx > 1) {
                break
            }
            previousSig = currentSig

            val turnResult = runSingleVisionStep(
                goalInstruction = buildString {
                    appendLine("Goal: $userPrompt")
                    if (completedStepNotes.isNotEmpty()) {
                        appendLine("Steps already executed: ${completedStepNotes.joinToString(" -> ")}")
                    }
                    appendLine("Inspect the current screen SILENTLY to execute the next UI action (UI_TAP_TEXT, UI_TAP_COORDINATES, UI_INPUT_TEXT, UI_SCROLL, GLOBAL_ACTION) and set continueLoopAfterAction=true ONLY if more steps remain, or emit SPEAK_ONLY with continueLoopAfterAction=false when finished. Do NOT describe screen UI elements in 'reply' unless the goal itself asks what is on screen.")
                },
                persona = persona,
                deviceContextSummary = deviceContextSummary,
                memoriesSummary = memoriesSummary,
                routinesSummary = routinesSummary,
                contactsSummary = contactsSummary,
                installedAppsSummary = installedAppsSummary,
                preferNvidiaPrimary = preferNvidiaPrimary,
                splitScreenEnabled = splitScreenEnabled,
                onStreamingReply = onStreamingReply,
                onActiveModelChanged = onActiveModelChanged
            )

            if (turnResult.executedLabels.isNotEmpty()) {
                onStepProgress("Step $stepIdx · ${turnResult.executedLabels.last()}")
            }
            completedStepNotes.addAll(turnResult.executedLabels)
            if (!turnResult.shouldContinue || !turnResult.executedAnyAction) {
                return turnResult.planReply.takeIf { it.isNotBlank() }
                    ?: completedStepNotes.lastOrNull()
            }
        }
        return completedStepNotes.lastOrNull()
    }

    suspend fun executeUnifiedReActGoal(
        userPrompt: String,
        initialPlan: CommandPlan,
        persona: AssistantPersona,
        deviceContextSummary: String,
        memoriesSummary: String,
        routinesSummary: String,
        contactsSummary: String,
        installedAppsSummary: String,
        preferNvidiaPrimary: Boolean,
        splitScreenEnabled: Boolean,
        visionTokenMode: VisionTokenMode = VisionTokenMode.OFFLINE_FIRST,
        onReActProgress: (ReActProgressUpdate) -> Unit,
        onStepProgress: (String) -> Unit,
        onStreamingReply: (String) -> Unit,
        onActiveModelChanged: (String) -> Unit
    ): String {
        val accessibility = AetherAccessibilityService.currentInstance
            ?: return "Enable Trusted Screen Agent (Accessibility) in Permissions so Aether can observe and control apps across steps."

        val checklist = MultiStepGoalClassifier.deriveSubGoals(userPrompt, initialPlan)
        var activeSubGoalIdx = 0
        val completedStepNotes = mutableListOf<String>()

        fun emitProgress(
            stepIndex: Int,
            statusLabel: String,
            waitingForLoad: Boolean,
            visionSource: String = "TREE",
            tokensSavedDelta: Int = 0,
            offlineSceneBadge: String = ""
        ) {
            onStepProgress(statusLabel)
            onReActProgress(
                ReActProgressUpdate(
                    stepIndex = stepIndex,
                    maxSteps = ScreenLoopGuard.MAX_SCREEN_LOOP_ITERATIONS,
                    subGoals = checklist,
                    activeSubGoalIndex = activeSubGoalIdx.coerceIn(0, (checklist.size - 1).coerceAtLeast(0)),
                    statusLabel = statusLabel,
                    isWaitingForLoad = waitingForLoad,
                    visionSource = visionSource,
                    tokensSavedDelta = tokensSavedDelta,
                    offlineSceneBadge = offlineSceneBadge
                )
            )
        }

        // Kick off initial LAUNCH_APP step if plan already starts with a clean app launch (avoid YouTube search shortcut URL)
        val initialLaunch = initialPlan.steps.firstOrNull {
            it.actionType == ActionType.LAUNCH_APP || it.actionType == ActionType.OPEN_SETTINGS
        }
        val targetAppHint = initialLaunch?.target?.takeIf { it.isNotBlank() }
            ?: when {
                userPrompt.contains("youtube", ignoreCase = true) || userPrompt.contains("ইউটিউব") -> "YouTube"
                userPrompt.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
                userPrompt.contains("facebook", ignoreCase = true) -> "Facebook"
                else -> ""
            }

        if (targetAppHint.isNotBlank()) {
            emitProgress(0, "Opening $targetAppHint...", waitingForLoad = true, visionSource = "APP")
            val launchRes = actionRouter.launchAppByName(targetAppHint, splitScreenEnabled)
            if (launchRes.success) {
                completedStepNotes.add(launchRes.detail)
                accessibility.waitForForegroundPackage(
                    ScreenLoopGuard.expectedPackageKeywords(targetAppHint),
                    timeoutMs = 4_200L
                )
                accessibility.waitForContentReady(timeoutMs = 3_600L)
                activeSubGoalIdx = (activeSubGoalIdx + 1).coerceAtMost((checklist.size - 1).coerceAtLeast(0))
            }
        }

        var previousSig = ""
        var stallCount = 0

        for (stepIdx in 1..ScreenLoopGuard.MAX_SCREEN_LOOP_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            delay(850L)

            val preSnap = accessibility.captureFullScreenContext(includeBitmap = false)
            if (preSnap?.isLoading == true) {
                emitProgress(
                    stepIdx,
                    "Waiting for screen to load...",
                    waitingForLoad = true,
                    visionSource = preSnap.visionSource
                )
                accessibility.waitForContentReady(timeoutMs = 3_500L)
            }

            emitProgress(
                stepIdx,
                "Step $stepIdx/${ScreenLoopGuard.MAX_SCREEN_LOOP_ITERATIONS} · Observing ${checklist.getOrElse(activeSubGoalIdx) { "screen" }}...",
                waitingForLoad = false,
                visionSource = preSnap?.visionSource ?: "TREE"
            )

            val currentSig = accessibility.captureUiSignature()
            val (nextStall, stalled) = ScreenLoopGuard.evaluateStall(previousSig, currentSig, stallCount)
            stallCount = nextStall
            previousSig = currentSig
            if (stalled && stepIdx > 2) {
                break
            }

            val subGoalBlock = checklist.mapIndexed { idx, label ->
                val marker = when {
                    idx < activeSubGoalIdx -> "[DONE]"
                    idx == activeSubGoalIdx -> "[CURRENT]"
                    else -> "[PENDING]"
                }
                "${idx + 1}. $marker $label"
            }.joinToString("\n")

            val activeSubGoal = checklist.getOrElse(activeSubGoalIdx) { userPrompt }
            val turnResult = runSingleVisionStep(
                goalInstruction = buildString {
                    appendLine("ORIGINAL USER GOAL: $userPrompt")
                    appendLine("ORDERED SUB-GOAL CHECKLIST:")
                    appendLine(subGoalBlock)
                    if (completedStepNotes.isNotEmpty()) {
                        appendLine("COMPLETED: ${completedStepNotes.joinToString(" -> ")}")
                    }
                    appendLine(
                        "Execute ONLY the next UI action (UI_TAP_TEXT, UI_TAP_COORDINATES, UI_INPUT_TEXT, UI_SCROLL, or GLOBAL_ACTION) to complete [CURRENT]. " +
                            "Set continueLoopAfterAction=true until ALL sub-goals are complete, else SPEAK_ONLY with continueLoopAfterAction=false."
                    )
                },
                persona = persona,
                deviceContextSummary = deviceContextSummary,
                memoriesSummary = memoriesSummary,
                routinesSummary = routinesSummary,
                contactsSummary = contactsSummary,
                installedAppsSummary = installedAppsSummary,
                preferNvidiaPrimary = preferNvidiaPrimary,
                splitScreenEnabled = splitScreenEnabled,
                onStreamingReply = onStreamingReply,
                onActiveModelChanged = onActiveModelChanged,
                visionTokenMode = visionTokenMode,
                rawUserPrompt = userPrompt,
                activeSubGoal = activeSubGoal,
                compactLoopMode = true
            )

            if (turnResult.executedLabels.isNotEmpty()) {
                completedStepNotes.addAll(turnResult.executedLabels)
                if (activeSubGoalIdx < checklist.lastIndex) {
                    activeSubGoalIdx++
                }
                emitProgress(
                    stepIdx,
                    "Step $stepIdx · ${turnResult.executedLabels.last()}",
                    waitingForLoad = turnResult.shouldContinue,
                    visionSource = turnResult.visionSource,
                    tokensSavedDelta = turnResult.tokensSaved,
                    offlineSceneBadge = turnResult.offlineSceneBadge
                )
            } else if (turnResult.tokensSaved > 0 || turnResult.offlineSceneBadge.isNotBlank()) {
                emitProgress(
                    stepIdx,
                    "Step $stepIdx · ${turnResult.planReply.ifBlank { "Verified on screen" }}",
                    waitingForLoad = false,
                    visionSource = turnResult.visionSource,
                    tokensSavedDelta = turnResult.tokensSaved,
                    offlineSceneBadge = turnResult.offlineSceneBadge
                )
            }

            if (!turnResult.shouldContinue || !turnResult.executedAnyAction) {
                return turnResult.planReply.takeIf { it.isNotBlank() }
                    ?: completedStepNotes.lastOrNull()
                    ?: "Completed multi-step workflow."
            }
        }
        return completedStepNotes.lastOrNull()
            ?: "Finished available screen steps."
    }

    private suspend fun waitForComposerOpen(
        accessibility: AetherAccessibilityService,
        timeoutMs: Long
    ): Boolean {
        val attempts = (timeoutMs / 250L).toInt().coerceAtLeast(1)
        repeat(attempts) {
            if (accessibility.hasBottomMessageComposer()) return true
            delay(250L)
        }
        return false
    }

    private suspend fun tryTapContactViaOcr(
        accessibility: AetherAccessibilityService,
        contactQuery: String
    ): Boolean {
        val snap = accessibility.captureFullScreenContext(includeBitmap = true, focusHint = contactQuery)
            ?: return false
        val bmp = snap.screenshot ?: return false
        return try {
            val ocr = visionProcessor.extractOcrWithCoordinates(bitmap = bmp)
            val hit = ScreenLoopGuard.findBestOcrContactMatch(ocr.lines, contactQuery) ?: return false
            accessibility.tapNormalizedCoordinates(hit.centerXNorm, hit.centerYNorm)
        } finally {
            runCatching { bmp.recycle() }
        }
    }

    private data class VisionTurnResult(
        val executedAnyAction: Boolean,
        val shouldContinue: Boolean,
        val executedLabels: List<String>,
        val planReply: String,
        val visionSource: String = "TREE",
        val tokensSaved: Int = 0,
        val offlineSceneBadge: String = ""
    )

    private suspend fun runSingleVisionStep(
        goalInstruction: String,
        persona: AssistantPersona,
        deviceContextSummary: String,
        memoriesSummary: String,
        routinesSummary: String,
        contactsSummary: String,
        installedAppsSummary: String,
        preferNvidiaPrimary: Boolean,
        splitScreenEnabled: Boolean,
        onStreamingReply: (String) -> Unit,
        onActiveModelChanged: (String) -> Unit,
        visionTokenMode: VisionTokenMode = VisionTokenMode.OFFLINE_FIRST,
        rawUserPrompt: String = goalInstruction,
        activeSubGoal: String = "",
        compactLoopMode: Boolean = true
    ): VisionTurnResult {
        val accessibility = AetherAccessibilityService.currentInstance
            ?: return VisionTurnResult(false, false, emptyList(), "")
        val snap = accessibility.captureFullScreenContext(includeBitmap = true, focusHint = goalInstruction)
            ?: return VisionTurnResult(false, false, emptyList(), "")

        var processedBitmap: Bitmap? = null
        var ownsProcessedBitmap = false
        try {
            val processed = visionProcessor.processScreenForVision(
                sourceBitmap = snap.screenshot,
                prompt = rawUserPrompt,
                accessibilityNodeCount = snap.nodeCount,
                forceOcrFallback = visionTokenMode == VisionTokenMode.OFFLINE_FIRST || snap.nodeCount < 8,
                anchorRect = snap.suggestedAnchorRect,
                packageName = snap.packageName,
                rawUiSummary = snap.uiSummary,
                visionTokenMode = visionTokenMode
            )
            processedBitmap = processed.croppedBitmap
            ownsProcessedBitmap = processed.ownsCroppedBitmap
            val offlineAnalysis = processed.offlineAnalysis
            val sceneBadge = offlineAnalysis?.summaryBadge.orEmpty()

            // Try 0-token offline resolution first when in OFFLINE_FIRST mode!
            if (visionTokenMode == VisionTokenMode.OFFLINE_FIRST &&
                activeSubGoal.isNotBlank() &&
                offlineAnalysis != null
            ) {
                val offlineMatch = OfflineScreenUnderstandingEngine.tryResolveNextHopOffline(
                    activeSubGoal = activeSubGoal,
                    userPrompt = rawUserPrompt,
                    analysis = offlineAnalysis
                )
                if (offlineMatch != null) {
                    if (offlineMatch.step.actionType == ActionType.SPEAK_ONLY) {
                        onActiveModelChanged("Offline Vision (<50MB) · 0 tok")
                        return VisionTurnResult(
                            executedAnyAction = true,
                            shouldContinue = false,
                            executedLabels = listOf(offlineMatch.reason),
                            planReply = offlineMatch.reason,
                            visionSource = "OFFLINE · 0 TOK",
                            tokensSaved = processed.tokensSaved + 900,
                            offlineSceneBadge = sceneBadge
                        )
                    }
                    val offlineRes = actionRouter.executeStep(offlineMatch.step, splitScreenEnabled)
                    if (offlineRes.success) {
                        onActiveModelChanged("Offline Vision (<50MB) · 0 tok")
                        return VisionTurnResult(
                            executedAnyAction = true,
                            shouldContinue = offlineMatch.expectMoreSteps,
                            executedLabels = listOf("${offlineRes.detail} (0 tokens)"),
                            planReply = offlineMatch.reason,
                            visionSource = "OFFLINE · 0 TOK",
                            tokensSaved = processed.tokensSaved + 950,
                            offlineSceneBadge = sceneBadge
                        )
                    }
                }
            }

            val slimUiSummary = buildString {
                offlineAnalysis?.buildCompactVisionHeader()?.let { appendLine(it) }
                val body = offlineAnalysis?.compactUiSummary?.ifBlank { snap.uiSummary } ?: snap.uiSummary
                append(body)
            }.trim()

            val plan: CommandPlan = geminiService.planCommand(
                userPrompt = goalInstruction,
                persona = persona,
                deviceContextSummary = deviceContextSummary,
                memoriesSummary = memoriesSummary,
                routinesSummary = routinesSummary,
                contactsSummary = contactsSummary,
                installedAppsSummary = installedAppsSummary,
                screenUiSummary = slimUiSummary,
                screenBitmap = processedBitmap,
                ocrSummary = processed.ocrSummary.ifBlank { null },
                preferNvidiaPrimary = preferNvidiaPrimary,
                compactLoopMode = compactLoopMode,
                onStreamingReply = {},
                onActiveModelChanged = onActiveModelChanged
            ).getOrNull() ?: return VisionTurnResult(
                executedAnyAction = false,
                shouldContinue = false,
                executedLabels = emptyList(),
                planReply = "",
                visionSource = snap.visionSource,
                tokensSaved = processed.tokensSaved,
                offlineSceneBadge = sceneBadge
            )

            val loopSavedBonus = if (compactLoopMode) 360 else 0
            val stepTokensSaved = processed.tokensSaved + loopSavedBonus

            val actionable = plan.steps.filter {
                it.actionType != ActionType.SPEAK_ONLY && it.actionType != ActionType.SCREEN_EXPLAIN
            }
            if (actionable.isEmpty()) {
                return VisionTurnResult(
                    executedAnyAction = false,
                    shouldContinue = false,
                    executedLabels = emptyList(),
                    planReply = plan.reply,
                    visionSource = if (processedBitmap == null) "OFFLINE+CLOUD TEXT" else snap.visionSource,
                    tokensSaved = stepTokensSaved,
                    offlineSceneBadge = sceneBadge
                )
            }

            val notes = mutableListOf<String>()
            var anySuccess = false
            for (s in actionable) {
                val res: ActionExecutionResult = actionRouter.executeStep(s, splitScreenEnabled)
                if (res.success) {
                    anySuccess = true
                    notes.add(res.detail)
                }
            }
            return VisionTurnResult(
                executedAnyAction = anySuccess,
                shouldContinue = plan.continueLoopAfterAction && anySuccess,
                executedLabels = notes,
                planReply = plan.reply,
                visionSource = if (processedBitmap == null) "OFFLINE+CLOUD TEXT" else snap.visionSource,
                tokensSaved = stepTokensSaved,
                offlineSceneBadge = sceneBadge
            )
        } finally {
            if (ownsProcessedBitmap) processedBitmap?.recycle()
            snap.screenshot?.recycle()
        }
    }
}
