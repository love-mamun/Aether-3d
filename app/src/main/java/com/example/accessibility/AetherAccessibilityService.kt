package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.vision.MediaProjectionScreenGrabber
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "AetherAccessibility"

data class ScreenSnapshot(
    val packageName: String,
    val uiSummary: String,
    val screenshot: Bitmap?,
    val nodeCount: Int = 0,
    val suggestedAnchorRect: Rect? = null,
    val visionSource: String = "TREE",
    val isLoading: Boolean = false
)

class AetherAccessibilityService : AccessibilityService() {

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentInstance = this
        Log.d(TAG, "AetherAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !com.example.automation.ScreenMacroManager.isRecording) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == applicationContext.packageName) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            return
        }

        val source = event.source
        if (event.isPassword || source?.isPassword == true) {
            // Never capture password fields
            return
        }

        val label = listOf(
            source?.text?.toString()?.trim().orEmpty(),
            source?.contentDescription?.toString()?.trim().orEmpty(),
            source?.hintText?.toString()?.trim().orEmpty(),
            event.text.joinToString(" ").trim(),
            event.contentDescription?.toString()?.trim().orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty().take(60)

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)
        val rect = Rect()
        if (source != null) {
            source.getBoundsInScreen(rect)
        }
        val normX = if (!rect.isEmpty) ((rect.centerX().toFloat() / screenW) * 1000f).toInt().coerceIn(0, 1000) else 500
        val normY = if (!rect.isEmpty) ((rect.centerY().toFloat() / screenH) * 1000f).toInt().coerceIn(0, 1000) else 500

        val appLabel = runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))

        val kind = if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) "INPUT" else "CLICK"
        val typedPayload = if (kind == "INPUT") {
            source?.text?.toString()?.trim()
                ?.ifBlank { event.text.joinToString(" ").trim() }
                .orEmpty()
        } else {
            ""
        }

        com.example.automation.ScreenMacroManager.recordInteraction(
            com.example.automation.RecordedMacroStep(
                packageName = pkg,
                appLabel = appLabel,
                actionKind = kind,
                label = label,
                textPayload = typedPayload,
                normX = normX,
                normY = normY
            )
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "AetherAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        if (currentInstance === this) {
            currentInstance = null
        }
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun findBestTargetRoot(): AccessibilityNodeInfo? {
        val myPkg = applicationContext.packageName
        val active = rootInActiveWindow
        val activePkg = active?.packageName?.toString().orEmpty()
        if (active != null && activePkg != myPkg) {
            return active
        }
        val appWindows = runCatching { windows }.getOrDefault(emptyList())
            .filter { w ->
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    w.root != null &&
                    w.root?.packageName?.toString() != myPkg
            }
        return appWindows.firstOrNull { it.isFocused || it.isActive }?.root
            ?: appWindows.firstOrNull()?.root
            ?: active
    }

    suspend fun captureFullScreenContext(
        includeBitmap: Boolean,
        focusHint: String = ""
    ): ScreenSnapshot? {
        val root = findBestTargetRoot()
        val projectionAvailable = MediaProjectionScreenGrabber.isProjectionActive()
        if (root == null && !projectionAvailable) return null

        val pkg = root?.packageName?.toString().orEmpty().ifBlank { "unknown" }
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)

        val lines = mutableListOf<String>()
        if (root != null) {
            collectNodes(root, screenW, screenH, lines, maxNodes = 100)
        }

        // Also capture relevant secondary interactive overlays (dialogs/popups) if distinct
        val extraRoots = runCatching { windows }.getOrDefault(emptyList())
            .mapNotNull { it.root }
            .filter { wRoot ->
                val wPkg = wRoot.packageName?.toString().orEmpty()
                wPkg.isNotBlank() &&
                    wPkg != applicationContext.packageName &&
                    wPkg != "com.android.systemui" &&
                    wRoot !== root
            }
            .take(2)
        for (extraRoot in extraRoots) {
            if (lines.size >= 115) break
            collectNodes(extraRoot, screenW, screenH, lines, maxNodes = 115)
        }

        val isLoading = root != null && detectScreenLoadingState(root, screenW, screenH)
        val uiText = buildString {
            appendLine("Foreground package: $pkg (${screenW}x${screenH})${if (isLoading) " [LOADING]" else ""}")
            if (lines.isEmpty()) {
                appendLine("(No readable UI nodes exposed)")
            } else {
                lines.forEach { appendLine(it) }
            }
        }

        val anchorRect = if (focusHint.isNotBlank() && root != null) {
            findMatchingRegionRect(root, focusHint, screenW, screenH)
        } else {
            null
        }

        var visionSource = if (lines.isNotEmpty()) "TREE" else "NONE"
        val bitmap: Bitmap? = if (includeBitmap) {
            val a11yShot = withTimeoutOrNull(2_400L) { captureScreenBitmapInternal() }
            if (a11yShot != null) {
                visionSource = if (lines.isNotEmpty()) "A11Y+SHOT" else "A11Y_SHOT"
                a11yShot
            } else if (projectionAvailable) {
                val mpShot = MediaProjectionScreenGrabber.captureLatestBitmap()
                if (mpShot != null) {
                    visionSource = if (lines.isNotEmpty()) "TREE+PROJECTION" else "MEDIA_PROJECTION"
                }
                mpShot
            } else {
                null
            }
        } else {
            null
        }

        return ScreenSnapshot(
            packageName = pkg,
            uiSummary = uiText,
            screenshot = bitmap,
            nodeCount = lines.size,
            suggestedAnchorRect = anchorRect,
            visionSource = visionSource,
            isLoading = isLoading
        )
    }

    private fun detectScreenLoadingState(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int
    ): Boolean {
        var hasSpinner = false
        var midContentCount = 0
        var totalNodes = 0

        fun inspect(node: AccessibilityNodeInfo) {
            if (totalNodes > 90) return
            totalNodes++
            val cls = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            if (cls.contains("ProgressBar", ignoreCase = true) ||
                id.contains("progress") ||
                id.contains("spinner") ||
                id.contains("shimmer") ||
                id.contains("skeleton") ||
                id.contains("loading")
            ) {
                hasSpinner = true
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                val cy = ((rect.centerY().toFloat() / screenH) * 1000f).toInt().coerceIn(0, 1000)
                val hasLabel = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
                if (hasLabel && cy in 200..790) {
                    midContentCount++
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                inspect(child)
            }
        }
        inspect(root)
        return hasSpinner || midContentCount < 2
    }

    suspend fun waitForContentReady(timeoutMs: Long = 3_500L): Boolean {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val root = findBestTargetRoot()
            if (root != null && !detectScreenLoadingState(root, screenW, screenH)) {
                return true
            }
            kotlinx.coroutines.delay(280L)
        }
        return false
    }

    private fun findMatchingRegionRect(
        root: AccessibilityNodeInfo,
        prompt: String,
        screenW: Int,
        screenH: Int
    ): Rect? {
        val stopWords = setOf(
            "the", "this", "that", "what", "read", "screen", "see", "look",
            "tap", "click", "open", "on", "my", "in", "at", "top", "bottom", "center"
        )
        val keywords = prompt.lowercase()
            .split(Regex("[^a-z0-9\\u0980-\\u09FF]+"))
            .filter { it.length >= 3 && it !in stopWords }
            .take(5)
        if (keywords.isEmpty()) return null

        val matchNode = findNodeByPredicate(root) { node ->
            val label = "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}".lowercase()
            label.isNotBlank() && keywords.any { kw -> label.contains(kw) }
        } ?: return null

        val r = Rect()
        matchNode.getBoundsInScreen(r)
        if (r.isEmpty) return null
        // Map from full display bounds into scaled screenshot coordinate space (max 900w)
        val scale = if (screenW > 900) 900f / screenW.toFloat() else 1f
        return Rect(
            (r.left * scale).toInt().coerceIn(0, screenW),
            (r.top * scale).toInt().coerceIn(0, screenH),
            (r.right * scale).toInt().coerceIn(0, screenW),
            (r.bottom * scale).toInt().coerceIn(0, screenH)
        )
    }

    private suspend fun captureScreenBitmapInternal(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hwBuffer = screenshot.hardwareBuffer
                                val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                val soft = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                wrapped?.recycle()
                                hwBuffer.close()

                                val scaled = if (soft != null && soft.width > 900) {
                                    val targetH = (soft.height * (900f / soft.width)).toInt().coerceAtLeast(1)
                                    val resized = Bitmap.createScaledBitmap(soft, 900, targetH, true)
                                    if (resized !== soft) soft.recycle()
                                    resized
                                } else {
                                    soft
                                }
                                if (cont.isActive) cont.resume(scaled)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed converting screenshot buffer", e)
                                if (cont.isActive) cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed with code=$errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot threw exception", e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun clickNodeByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = query.trim()
        if (target.isBlank()) return false

        val directMatches = root.findAccessibilityNodeInfosByText(target)
        for (node in directMatches) {
            if (clickSelfOrParent(node)) return true
        }
        return findNodeByPredicate(root) { node ->
            val label = "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}"
            label.contains(target, ignoreCase = true)
        }?.let { clickSelfOrParent(it) } ?: false
    }

    fun inputText(targetHint: String, textToType: String, submitSearch: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = if (targetHint.isNotBlank()) {
            findNodeByPredicate(root) { node ->
                node.isEditable && (
                    node.text?.toString()?.contains(targetHint, ignoreCase = true) == true ||
                        node.contentDescription?.toString()?.contains(targetHint, ignoreCase = true) == true ||
                        node.hintText?.toString()?.contains(targetHint, ignoreCase = true) == true
                    )
            }
        } else {
            null
        } ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findNodeByPredicate(root) { it.isEditable }

        val node = targetNode ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        }
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setOk && submitSearch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return setOk
    }

    fun currentForegroundPackage(): String? = rootInActiveWindow?.packageName?.toString()?.trim()

    suspend fun waitForForegroundPackage(
        expectedKeywords: List<String>,
        timeoutMs: Long = 4_200L
    ): Boolean {
        val cleanKeys = expectedKeywords.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < timeoutMs) {
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()?.trim()?.lowercase().orEmpty()
            val matches = pkg.isNotBlank() && (cleanKeys.isEmpty() || cleanKeys.any { pkg.contains(it) })
            if (matches && root != null && root.childCount > 0) {
                return true
            }
            kotlinx.coroutines.delay(180L)
        }
        return false
    }

    suspend fun waitForNonSplashUi(
        expectedKeywords: List<String> = emptyList(),
        timeoutMs: Long = 6_500L
    ): Boolean {
        val cleanKeys = expectedKeywords.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < timeoutMs) {
            val (pkg, items) = collectIndexedCandidates(maxNodes = 60)
            val pkgLower = pkg.lowercase()
            val pkgOk = cleanKeys.isEmpty() || (pkgLower.isNotBlank() && cleanKeys.any { pkgLower.contains(it) })
            val meaningfulNodes = items.count { (c, _) ->
                c.text.isNotBlank() || c.contentDesc.isNotBlank() || c.isEditable || c.isClickable
            }
            if (pkgOk && meaningfulNodes >= 4) {
                return true
            }
            kotlinx.coroutines.delay(250L)
        }
        return false
    }

    fun clickNodeByAnyText(candidates: List<String>, minYNorm: Int = 0, maxYNorm: Int = 1000): Boolean {
        val (_, items) = collectIndexedCandidates()
        for (query in candidates) {
            val clean = query.trim()
            if (clean.isBlank()) continue
            val exact = items.firstOrNull { (c, _) ->
                c.cyNorm in minYNorm..maxYNorm &&
                    (c.text.equals(clean, ignoreCase = true) ||
                        c.contentDesc.equals(clean, ignoreCase = true) ||
                        c.hint.equals(clean, ignoreCase = true))
            }
            if (exact != null && clickSelfOrParent(exact.second)) return true
        }
        for (query in candidates) {
            val clean = query.trim()
            if (clean.isBlank()) continue
            val partial = items.firstOrNull { (c, _) ->
                c.cyNorm in minYNorm..maxYNorm &&
                    (c.text.contains(clean, ignoreCase = true) ||
                        c.contentDesc.contains(clean, ignoreCase = true) ||
                        c.hint.contains(clean, ignoreCase = true))
            }
            if (partial != null && clickSelfOrParent(partial.second)) return true
        }
        return false
    }

    fun clickBestFacebookComposer(): Boolean {
        val prompts = listOf(
            "What's on your mind?",
            "What’s on your mind?",
            "Write something here",
            "Create a public post",
            "Create post",
            "আপনার মনে কী আছে",
            "কী ভাবছেন"
        )
        if (clickNodeByAnyText(prompts, minYNorm = 80, maxYNorm = 650)) {
            return true
        }
        val (_, items) = collectIndexedCandidates()
        val editableOrHint = items.firstOrNull { (c, _) ->
            c.cyNorm in 120..520 && (c.isEditable || c.hint.isNotBlank())
        }
        return editableOrHint?.let { clickSelfOrParent(it.second) } ?: false
    }

    fun inputSocialComposerText(postText: String): Boolean {
        val (_, items) = collectIndexedCandidates()
        val bestEditable = items.firstOrNull { (c, _) ->
            c.isEditable && (
                c.hint.contains("mind", ignoreCase = true) ||
                    c.hint.contains("say", ignoreCase = true) ||
                    c.hint.contains("post", ignoreCase = true) ||
                    c.hint.contains("write", ignoreCase = true) ||
                    c.cyNorm in 120..700
                )
        } ?: items.firstOrNull { it.first.isEditable }
        if (bestEditable != null) {
            return setTextOnNode(bestEditable.second, postText, submitEnter = false)
        }
        return inputText("", postText, submitSearch = false)
    }

    fun clickBestPublishPostButton(): Boolean {
        return clickNodeByAnyText(
            listOf("POST", "Post", "Publish", "Share now", "Share", "পোস্ট করুন", "পোস্ট"),
            minYNorm = 40,
            maxYNorm = 950
        )
    }

    fun clickFirstYouTubeVideoResult(): Boolean {
        val (_, items) = collectIndexedCandidates()
        val skipWords = listOf(
            "search", "filter", "all", "shorts", "videos", "unwatched",
            "watched", "home", "subscriptions", "you", "library", "cast", "notifications"
        )
        val candidate = items.firstOrNull { (c, _) ->
            c.cyNorm in 200..780 &&
                c.isClickable &&
                !c.isEditable &&
                (c.contentDesc.length >= 12 || c.text.length >= 12) &&
                skipWords.none { sw ->
                    c.text.equals(sw, ignoreCase = true) ||
                        c.contentDesc.equals(sw, ignoreCase = true)
                }
        }
        return candidate?.let { clickSelfOrParent(it.second) } ?: false
    }

    fun captureUiSignature(): String {
        val (pkg, items) = collectIndexedCandidates()
        val candidates = items.map { it.first }
        return MessagingUiHeuristics.computeScreenSignature(pkg, candidates)
    }

    suspend fun performSearchInForegroundApp(query: String): Boolean {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return false

        val (_, initialItems) = collectIndexedCandidates()
        val existingSearchBox = MessagingUiHeuristics.pickSearchInputBox(initialItems.map { it.first })
        if (existingSearchBox == null) {
            val trigger = MessagingUiHeuristics.pickSearchTrigger(initialItems.map { it.first })
            if (trigger != null) {
                val ref = initialItems.getOrNull(trigger.index)?.second
                if (ref != null) {
                    clickSelfOrParent(ref)
                    kotlinx.coroutines.delay(750L)
                }
            }
        }

        val (_, afterTapItems) = collectIndexedCandidates()
        val searchBox = MessagingUiHeuristics.pickSearchInputBox(afterTapItems.map { it.first })
        val entered = if (searchBox != null) {
            val node = afterTapItems.getOrNull(searchBox.index)?.second
            node != null && setTextOnNode(node, cleanQuery, submitEnter = true)
        } else {
            inputText(targetHint = "search", textToType = cleanQuery, submitSearch = true)
        }
        if (!entered) return false

        // Wait briefly and check if an explicit autocomplete suggestion matching query needs a tap
        kotlinx.coroutines.delay(550L)
        val (_, postTypeItems) = collectIndexedCandidates()
        val autoSuggest = postTypeItems.firstOrNull { (c, _) ->
            !c.isEditable &&
                c.cyNorm in 140..420 &&
                c.isClickable &&
                (c.text.equals(cleanQuery, ignoreCase = true) ||
                    c.text.contains(cleanQuery, ignoreCase = true))
        }
        if (autoSuggest != null) {
            clickSelfOrParent(autoSuggest.second)
        }
        return true
    }

    suspend fun triggerAndTypeInAppSearch(query: String): Boolean {
        return performSearchInForegroundApp(query)
    }

    fun clickContactRowInApp(contactName: String): Boolean {
        val (_, items) = collectIndexedCandidates()
        val candidate = MessagingUiHeuristics.pickContactRow(items.map { it.first }, contactName)
            ?: return false
        val node = items.getOrNull(candidate.index)?.second ?: return false
        return clickSelfOrParent(node)
    }

    fun hasBottomMessageComposer(): Boolean {
        val (_, items) = collectIndexedCandidates()
        return MessagingUiHeuristics.pickBottomComposer(items.map { it.first }) != null
    }

    fun isBottomMessageComposerEmpty(): Boolean {
        val (_, items) = collectIndexedCandidates()
        val composer = MessagingUiHeuristics.pickBottomComposer(items.map { it.first }) ?: return false
        val text = composer.text.trim()
        val hint = composer.hint.trim()
        return text.isBlank() || (hint.isNotBlank() && text.equals(hint, ignoreCase = true))
    }

    fun isConversationOpenFor(contactName: String): Boolean {
        val clean = contactName.trim()
        val (_, items) = collectIndexedCandidates()
        val candidates = items.map { it.first }
        val hasComposer = MessagingUiHeuristics.pickBottomComposer(candidates) != null
        if (!hasComposer) return false
        if (clean.isBlank()) return true
        return candidates.any { node ->
            node.cyNorm in 15..220 &&
                !node.isEditable &&
                (node.text.contains(clean, ignoreCase = true) ||
                    node.contentDesc.contains(clean, ignoreCase = true))
        }
    }

    fun inputInBottomMessageComposer(messageText: String): Boolean {
        val (_, items) = collectIndexedCandidates()
        val candidate = MessagingUiHeuristics.pickBottomComposer(items.map { it.first }) ?: return false
        val node = items.getOrNull(candidate.index)?.second ?: return false
        return setTextOnNode(node, messageText, submitEnter = false)
    }

    fun clickMessagingSendButton(): Boolean {
        val (_, items) = collectIndexedCandidates()
        val candidate = MessagingUiHeuristics.pickSendButton(items.map { it.first }) ?: return false
        val node = items.getOrNull(candidate.index)?.second ?: return false
        return clickSelfOrParent(node)
    }

    private fun setTextOnNode(
        node: AccessibilityNodeInfo,
        textToType: String,
        submitEnter: Boolean
    ): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        }
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setOk && submitEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return setOk
    }

    private fun collectIndexedCandidates(
        maxNodes: Int = 140
    ): Pair<String, List<Pair<UiNodeCandidate, AccessibilityNodeInfo>>> {
        val root = rootInActiveWindow ?: return "" to emptyList()
        val pkg = root.packageName?.toString()?.trim().orEmpty()
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)
        val results = mutableListOf<Pair<UiNodeCandidate, AccessibilityNodeInfo>>()

        fun walk(node: AccessibilityNodeInfo) {
            if (results.size >= maxNodes) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = node.hintText?.toString()?.trim().orEmpty()
            val viewIdSuffix = node.viewIdResourceName?.substringAfterLast('/')?.trim().orEmpty()
            val interesting = text.isNotBlank() || desc.isNotBlank() || hint.isNotBlank() ||
                node.isClickable || node.isEditable || node.isScrollable

            if (interesting && !rect.isEmpty) {
                val cx = ((rect.centerX().toFloat() / screenW) * 1000f).toInt().coerceIn(0, 1000)
                val cy = ((rect.centerY().toFloat() / screenH) * 1000f).toInt().coerceIn(0, 1000)
                val candidate = UiNodeCandidate(
                    index = results.size,
                    text = text,
                    contentDesc = desc,
                    hint = hint,
                    viewIdSuffix = viewIdSuffix,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    cxNorm = cx,
                    cyNorm = cy
                )
                results.add(candidate to node)
            }
            for (i in 0 until node.childCount) {
                if (results.size >= maxNodes) break
                val child = node.getChild(i) ?: continue
                walk(child)
            }
        }
        walk(root)
        return pkg to results
    }

    fun scrollScreen(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val forward = !direction.contains("up", ignoreCase = true)
        val scrollable = findNodeByPredicate(root) { it.isScrollable }
        if (scrollable != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollable.performAction(action)) return true
        }
        // Fallback swipe gesture
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels * 0.5f
        val startY = if (forward) metrics.heightPixels * 0.72f else metrics.heightPixels * 0.30f
        val endY = if (forward) metrics.heightPixels * 0.28f else metrics.heightPixels * 0.74f
        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun tapNormalizedCoordinates(normX: Int, normY: Int): Boolean {
        val metrics = resources.displayMetrics
        val clampedX = (normX.coerceIn(0, 1000) / 1000f) * metrics.widthPixels
        val clampedY = (normY.coerceIn(0, 1000) / 1000f) * metrics.heightPixels
        val path = Path().apply { moveTo(clampedX, clampedY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun performSystemGlobalAction(actionName: String): Boolean {
        val normalized = actionName.trim().uppercase()
        val code = when {
            normalized.contains("HOME") -> GLOBAL_ACTION_HOME
            normalized.contains("BACK") -> GLOBAL_ACTION_BACK
            normalized.contains("RECENT") -> GLOBAL_ACTION_RECENTS
            normalized.contains("NOTIF") -> GLOBAL_ACTION_NOTIFICATIONS
            normalized.contains("QUICK") -> GLOBAL_ACTION_QUICK_SETTINGS
            normalized.contains("LOCK") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                GLOBAL_ACTION_LOCK_SCREEN
            normalized.contains("SCREENSHOT") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
        return performGlobalAction(code)
    }

    private fun clickSelfOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                break
            }
            current = current.parent
            depth++
        }
        // Fallback: dispatch tap gesture directly on node bounds even if OEM ignores ACTION_CLICK
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 55L))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun findNodeByPredicate(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findNodeByPredicate(child, predicate)
            if (match != null) return match
        }
        return null
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
        out: MutableList<String>,
        maxNodes: Int
    ) {
        if (out.size >= maxNodes) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = node.hintText?.toString()?.trim().orEmpty()
        val label = listOf(text, desc, hint).firstOrNull { it.isNotBlank() }.orEmpty()

        if (label.isNotBlank() || node.isClickable || node.isEditable || !node.viewIdResourceName.isNullOrBlank()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val cx = ((rect.centerX().toFloat() / screenW) * 1000f).toInt().coerceIn(0, 1000)
            val cy = ((rect.centerY().toFloat() / screenH) * 1000f).toInt().coerceIn(0, 1000)
            val zone = when {
                cy < 260 -> "top"
                cy > 700 -> "bottom"
                else -> "mid"
            }
            val viewIdSuffix = node.viewIdResourceName?.substringAfterLast('/')?.trim().orEmpty()
            val flags = buildList {
                if (node.isClickable) add("click")
                if (node.isEditable) add("edit")
                if (node.isScrollable) add("scroll")
                if (viewIdSuffix.isNotBlank()) add("id=$viewIdSuffix")
            }.joinToString(",")
            val displayLabel = label.take(60).ifBlank { node.className?.toString()?.substringAfterLast('.') ?: "node" }
            out.add("[$cx,$cy|$zone] ${if (flags.isNotBlank()) "($flags) " else ""}\"$displayLabel\"")
        }

        for (i in 0 until node.childCount) {
            if (out.size >= maxNodes) break
            val child = node.getChild(i) ?: continue
            collectNodes(child, screenW, screenH, out, maxNodes)
        }
    }

    companion object {
        @Volatile
        var currentInstance: AetherAccessibilityService? = null
            private set

        fun isConnected(): Boolean = currentInstance != null
    }
}
