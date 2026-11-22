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
    val suggestedAnchorRect: Rect? = null
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

    suspend fun captureFullScreenContext(
        includeBitmap: Boolean,
        focusHint: String = ""
    ): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty().ifBlank { "unknown" }
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)

        val lines = mutableListOf<String>()
        collectNodes(root, screenW, screenH, lines, maxNodes = 90)
        val uiText = buildString {
            appendLine("Foreground package: $pkg (${screenW}x${screenH})")
            if (lines.isEmpty()) {
                appendLine("(No readable UI nodes exposed)")
            } else {
                lines.forEach { appendLine(it) }
            }
        }

        val anchorRect = if (focusHint.isNotBlank()) {
            findMatchingRegionRect(root, focusHint, screenW, screenH)
        } else {
            null
        }

        val bitmap = if (includeBitmap) {
            withTimeoutOrNull(3_000L) { captureScreenBitmapInternal() }
        } else {
            null
        }
        return ScreenSnapshot(
            packageName = pkg,
            uiSummary = uiText,
            screenshot = bitmap,
            nodeCount = lines.size,
            suggestedAnchorRect = anchorRect
        )
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

    suspend fun performSearchInForegroundApp(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // If an editable field isn't immediately open, tap the Search icon/bar first
        val editableNow = findNodeByPredicate(root) { it.isEditable }
        if (editableNow == null) {
            val searchTrigger = findNodeByPredicate(root) { node ->
                val txt = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val viewId = node.viewIdResourceName.orEmpty()
                txt.contains("search", ignoreCase = true) ||
                    desc.contains("search", ignoreCase = true) ||
                    viewId.contains("search", ignoreCase = true)
            }
            if (searchTrigger != null) {
                clickSelfOrParent(searchTrigger)
                kotlinx.coroutines.delay(650L)
            }
        }
        return inputText(targetHint = "search", textToType = query, submitSearch = true)
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
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
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

        if (label.isNotBlank() || node.isClickable || node.isEditable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val cx = ((rect.centerX().toFloat() / screenW) * 1000f).toInt().coerceIn(0, 1000)
            val cy = ((rect.centerY().toFloat() / screenH) * 1000f).toInt().coerceIn(0, 1000)
            val flags = buildList {
                if (node.isClickable) add("click")
                if (node.isEditable) add("edit")
                if (node.isScrollable) add("scroll")
            }.joinToString(",")
            val displayLabel = label.take(60).ifBlank { node.className?.toString()?.substringAfterLast('.') ?: "node" }
            out.add("[$cx,$cy] ${if (flags.isNotBlank()) "($flags) " else ""}\"$displayLabel\"")
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
