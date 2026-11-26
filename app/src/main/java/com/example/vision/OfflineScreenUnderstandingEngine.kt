package com.example.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.ai.ActionType
import com.example.ai.PlannedStep
import com.example.data.local.VisionTokenMode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "OfflineScreenEngine"

enum class ScreenSceneType {
    VIDEO_FEED,
    CHANNEL_PROFILE,
    VIDEO_PLAYER,
    SEARCH_RESULTS,
    CHAT_LIST,
    CHAT_CONVERSATION,
    POST_COMPOSER,
    AUDIENCE_OR_SHARE_STAGE,
    DIALOG_MODAL,
    SETTINGS_LIST,
    HOME_DESKTOP,
    MEDIA_OR_PHOTO,
    WEB_OR_BROWSER,
    UNKNOWN
}

data class DetectedVisualElement(
    val label: String,
    val confidence: Float,
    val centerXNorm: Int,
    val centerYNorm: Int,
    val widthNorm: Int,
    val heightNorm: Int,
    val isMediaBox: Boolean
)

data class ParsedScreenNode(
    val xNorm: Int,
    val yNorm: Int,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val viewIdSuffix: String,
    val label: String,
    val rawLine: String
)

data class OfflineResolvedAction(
    val step: PlannedStep,
    val subGoalMatched: String,
    val reason: String,
    val expectMoreSteps: Boolean
)

data class OfflineScreenAnalysis(
    val sceneType: ScreenSceneType,
    val packageName: String,
    val semanticConfidence: Float,
    val visualLabels: List<String>,
    val detectedObjects: List<DetectedVisualElement>,
    val compactUiSummary: String,
    val ocrLines: List<OcrLineItem>,
    val ocrSummary: String,
    val shouldAttachBitmapToCloud: Boolean,
    val bitmapSkipReason: String,
    val estimatedTokensSaved: Int,
    val summaryBadge: String
) {
    fun buildCompactVisionHeader(): String = buildString {
        append("OFFLINE SCREEN VISION (<50MB MLKit): Scene=${sceneType.name}")
        if (packageName.isNotBlank()) append(" | Pkg=$packageName")
        append(" | Conf=${(semanticConfidence * 100).roundToInt()}%")
        if (visualLabels.isNotEmpty()) {
            append(" | Labels=${visualLabels.take(5).joinToString(",")}")
        }
        if (detectedObjects.isNotEmpty()) {
            val boxes = detectedObjects.take(5).joinToString("; ") {
                "[${it.centerXNorm},${it.centerYNorm} ${it.widthNorm}x${it.heightNorm} ${it.label}]"
            }
            append("\nVISUAL OBJECT BOXES (0-1000 coords): $boxes")
        }
    }
}

class OfflineScreenUnderstandingEngine {

    /**
     * Runs bundled on-device ML Kit Image Labeling + Object Detection (<18MB combined with OCR)
     * on the given screen bitmap, then merges with Accessibility UI nodes + OCR to build a compact,
     * token-efficient screen representation.
     */
    suspend fun analyzeBitmapAndUi(
        bitmap: Bitmap?,
        packageName: String,
        rawUiSummary: String,
        nodeCount: Int,
        ocrLines: List<OcrLineItem>,
        ocrSummary: String,
        userPrompt: String,
        visionTokenMode: VisionTokenMode,
        forceCloudEscalation: Boolean = false
    ): OfflineScreenAnalysis {
        val (labels, objects) = if (bitmap != null) {
            coroutineScope {
                val labelsDeferred = async { runMlKitImageLabeling(bitmap) }
                val objectsDeferred = async { runMlKitObjectDetection(bitmap) }
                labelsDeferred.await() to objectsDeferred.await()
            }
        } else {
            emptyList<String>() to emptyList()
        }

        return buildOfflineAnalysis(
            packageName = packageName,
            rawUiSummary = rawUiSummary,
            nodeCount = nodeCount,
            ocrLines = ocrLines,
            ocrSummary = ocrSummary,
            visualLabels = labels,
            detectedObjects = objects,
            userPrompt = userPrompt,
            visionTokenMode = visionTokenMode,
            hasSourceBitmap = bitmap != null,
            forceCloudEscalation = forceCloudEscalation
        )
    }

    internal suspend fun runMlKitImageLabeling(bitmap: Bitmap): List<String> {
        return withTimeoutOrNull(1_600L) {
            suspendCancellableCoroutine { cont ->
                try {
                    val options = ImageLabelerOptions.Builder()
                        .setConfidenceThreshold(0.55f)
                        .build()
                    val labeler = ImageLabeling.getClient(options)
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    labeler.process(inputImage)
                        .addOnSuccessListener { labels ->
                            val top = labels
                                .sortedByDescending { it.confidence }
                                .map { it.text.trim() }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .take(6)
                            runCatching { labeler.close() }
                            if (cont.isActive) cont.resume(top)
                        }
                        .addOnFailureListener { err ->
                            Log.w(TAG, "ML Kit ImageLabeling failed: ${err.message}")
                            runCatching { labeler.close() }
                            if (cont.isActive) cont.resume(emptyList())
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "ML Kit ImageLabeling unavailable in runtime: ${t.message}")
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
        } ?: emptyList()
    }

    internal suspend fun runMlKitObjectDetection(bitmap: Bitmap): List<DetectedVisualElement> {
        val w = bitmap.width.coerceAtLeast(1).toFloat()
        val h = bitmap.height.coerceAtLeast(1).toFloat()
        return withTimeoutOrNull(1_800L) {
            suspendCancellableCoroutine { cont ->
                try {
                    val options = ObjectDetectorOptions.Builder()
                        .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                        .enableMultipleObjects()
                        .enableClassification()
                        .build()
                    val detector = ObjectDetection.getClient(options)
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    detector.process(inputImage)
                        .addOnSuccessListener { objects ->
                            val items = objects.take(8).map { obj ->
                                val box = obj.boundingBox
                                val cx = ((box.exactCenterX() / w) * 1000f).roundToInt().coerceIn(0, 1000)
                                val cy = ((box.exactCenterY() / h) * 1000f).roundToInt().coerceIn(0, 1000)
                                val wn = ((box.width().toFloat() / w) * 1000f).roundToInt().coerceIn(1, 1000)
                                val hn = ((box.height().toFloat() / h) * 1000f).roundToInt().coerceIn(1, 1000)
                                val bestCat = obj.labels.maxByOrNull { it.confidence }
                                DetectedVisualElement(
                                    label = bestCat?.text?.trim()?.ifBlank { "VisualCard" } ?: "VisualCard",
                                    confidence = bestCat?.confidence ?: 0.65f,
                                    centerXNorm = cx,
                                    centerYNorm = cy,
                                    widthNorm = wn,
                                    heightNorm = hn,
                                    isMediaBox = wn >= 240 && hn >= 90
                                )
                            }
                            runCatching { detector.close() }
                            if (cont.isActive) cont.resume(items)
                        }
                        .addOnFailureListener { err ->
                            Log.w(TAG, "ML Kit ObjectDetection failed: ${err.message}")
                            runCatching { detector.close() }
                            if (cont.isActive) cont.resume(emptyList())
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "ML Kit ObjectDetection unavailable in runtime: ${t.message}")
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
        } ?: emptyList()
    }

    companion object {
        const val OFFLINE_VISION_FOOTPRINT_LABEL = "~18 MB on-device (ML Kit Labels + Objects + OCR)"
        private const val ESTIMATED_CLOUD_IMAGE_TOKENS = 760

        fun buildOfflineAnalysis(
            packageName: String,
            rawUiSummary: String,
            nodeCount: Int,
            ocrLines: List<OcrLineItem>,
            ocrSummary: String,
            visualLabels: List<String>,
            detectedObjects: List<DetectedVisualElement>,
            userPrompt: String,
            visionTokenMode: VisionTokenMode,
            hasSourceBitmap: Boolean,
            forceCloudEscalation: Boolean = false
        ): OfflineScreenAnalysis {
            val parsedNodes = parseCompactNodes(rawUiSummary)
            val compactUi = compactAccessibilitySummary(rawUiSummary, maxNodes = 48)
            val sceneType = classifyScene(
                packageName = packageName,
                nodes = parsedNodes,
                ocrLines = ocrLines,
                visualLabels = visualLabels,
                detectedObjects = detectedObjects
            )
            val confidence = computeSemanticConfidence(
                nodeCount = maxOf(nodeCount, parsedNodes.size),
                ocrLineCount = ocrLines.size,
                objectCount = detectedObjects.size,
                labelCount = visualLabels.size,
                sceneType = sceneType
            )

            val (attachBitmap, skipReason) = evaluateCloudBitmapNeed(
                prompt = userPrompt,
                visionTokenMode = visionTokenMode,
                semanticConfidence = confidence,
                nodeCount = maxOf(nodeCount, parsedNodes.size),
                ocrLineCount = ocrLines.size,
                objectCount = detectedObjects.size,
                hasSourceBitmap = hasSourceBitmap,
                packageName = packageName,
                forceCloudEscalation = forceCloudEscalation
            )

            val uiCharsSaved = (rawUiSummary.length - compactUi.length).coerceAtLeast(0)
            val textTokensSaved = uiCharsSaved / 4
            val imageTokensSaved = if (hasSourceBitmap && !attachBitmap) ESTIMATED_CLOUD_IMAGE_TOKENS else 0
            val totalSaved = textTokensSaved + imageTokensSaved

            val badge = buildString {
                append("OFFLINE ${sceneType.name.replace('_', ' ')}")
                if (!attachBitmap && hasSourceBitmap) {
                    append(" · 0px (-${totalSaved} tok)")
                } else if (totalSaved > 0) {
                    append(" (-${totalSaved} tok)")
                }
            }

            return OfflineScreenAnalysis(
                sceneType = sceneType,
                packageName = packageName,
                semanticConfidence = confidence,
                visualLabels = visualLabels,
                detectedObjects = detectedObjects,
                compactUiSummary = compactUi,
                ocrLines = ocrLines,
                ocrSummary = ocrSummary,
                shouldAttachBitmapToCloud = attachBitmap,
                bitmapSkipReason = skipReason,
                estimatedTokensSaved = totalSaved,
                summaryBadge = badge
            )
        }

        fun compactAccessibilitySummary(rawUiSummary: String, maxNodes: Int = 48): String {
            if (rawUiSummary.isBlank()) return ""
            val lines = rawUiSummary.lines()
            val header = lines.firstOrNull()?.trim().orEmpty()
            val seenKeys = LinkedHashSet<String>()
            val compactLines = mutableListOf<String>()

            for (rawLine in lines.drop(1)) {
                val trimmed = rawLine.trim()
                if (trimmed.isBlank() || trimmed.startsWith("(No readable")) continue
                val parsed = parseSingleNodeLine(trimmed)
                if (parsed != null) {
                    // Drop generic unlabeled non-clickable layout wrappers
                    val genericLabels = setOf("ViewGroup", "FrameLayout", "LinearLayout", "RelativeLayout", "View", "node")
                    if (!parsed.isClickable && !parsed.isEditable &&
                        (parsed.label.isBlank() || parsed.label in genericLabels) &&
                        parsed.viewIdSuffix.isBlank()
                    ) {
                        continue
                    }
                    // Deduplicate closely positioned identical labels
                    val bucketKey = "${parsed.xNorm / 25}_${parsed.yNorm / 25}_${parsed.label.lowercase(Locale.ROOT)}"
                    if (!seenKeys.add(bucketKey)) continue

                    val flagPart = buildList {
                        if (parsed.isClickable) add("C")
                        if (parsed.isEditable) add("E")
                        if (parsed.isScrollable) add("S")
                        if (parsed.viewIdSuffix.isNotBlank()) add(parsed.viewIdSuffix.take(24))
                    }.joinToString(",")
                    val shortLabel = parsed.label.take(48)
                    val outLine = if (flagPart.isNotBlank()) {
                        "[${parsed.xNorm},${parsed.yNorm}|$flagPart] \"$shortLabel\""
                    } else {
                        "[${parsed.xNorm},${parsed.yNorm}] \"$shortLabel\""
                    }
                    compactLines.add(outLine)
                } else {
                    compactLines.add(trimmed.take(72))
                }
                if (compactLines.size >= maxNodes) break
            }

            return buildString {
                if (header.isNotBlank()) appendLine(header)
                compactLines.forEach { appendLine(it) }
            }.trim()
        }

        fun parseCompactNodes(uiSummary: String): List<ParsedScreenNode> {
            if (uiSummary.isBlank()) return emptyList()
            return uiSummary.lines().mapNotNull { parseSingleNodeLine(it.trim()) }
        }

        private val NODE_LINE_REGEX = Regex(
            """^\[(\d+),(\d+)(?:\|[a-z,A-Z0-9_=.-]*)?]\s*(?:\(([^)]*)\)\s*)?"(.*)"$"""
        )
        private val COMPACT_NODE_LINE_REGEX = Regex(
            """^\[(\d+),(\d+)\|([^]]*)]\s*"(.*)"$"""
        )

        private fun parseSingleNodeLine(line: String): ParsedScreenNode? {
            NODE_LINE_REGEX.matchEntire(line)?.let { m ->
                val x = m.groupValues[1].toIntOrNull() ?: return null
                val y = m.groupValues[2].toIntOrNull() ?: return null
                val flags = m.groupValues[3]
                val label = m.groupValues[4]
                val idMatch = Regex("""id=([^\s,]+)""").find(flags)?.groupValues?.getOrNull(1).orEmpty()
                return ParsedScreenNode(
                    xNorm = x,
                    yNorm = y,
                    isClickable = flags.contains("click", ignoreCase = true) || flags.split(',').any { it == "C" },
                    isEditable = flags.contains("edit", ignoreCase = true) || flags.split(',').any { it == "E" },
                    isScrollable = flags.contains("scroll", ignoreCase = true) || flags.split(',').any { it == "S" },
                    viewIdSuffix = idMatch,
                    label = label,
                    rawLine = line
                )
            }
            COMPACT_NODE_LINE_REGEX.matchEntire(line)?.let { m ->
                val x = m.groupValues[1].toIntOrNull() ?: return null
                val y = m.groupValues[2].toIntOrNull() ?: return null
                val flags = m.groupValues[3]
                val tokens = flags.split(',').map { it.trim() }
                val label = m.groupValues[4]
                val suffix = tokens.firstOrNull { it !in setOf("C", "E", "S") && it.isNotBlank() }.orEmpty()
                return ParsedScreenNode(
                    xNorm = x,
                    yNorm = y,
                    isClickable = "C" in tokens,
                    isEditable = "E" in tokens,
                    isScrollable = "S" in tokens,
                    viewIdSuffix = suffix,
                    label = label,
                    rawLine = line
                )
            }
            return null
        }

        fun classifyScene(
            packageName: String,
            nodes: List<ParsedScreenNode>,
            ocrLines: List<OcrLineItem>,
            visualLabels: List<String>,
            detectedObjects: List<DetectedVisualElement>
        ): ScreenSceneType {
            val pkg = packageName.lowercase(Locale.ROOT)
            val allText = buildString {
                nodes.forEach { append(it.label).append(' ').append(it.viewIdSuffix).append(' ') }
                ocrLines.forEach { append(it.text).append(' ') }
            }.lowercase(Locale.ROOT)

            if (nodes.any { it.isEditable && it.yNorm >= 500 } &&
                (pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("orca") ||
                    allText.contains("type a message") || allText.contains("message"))
            ) {
                return ScreenSceneType.CHAT_CONVERSATION
            }
            if ((pkg.contains("katana") || pkg.contains("facebook") || pkg.contains("lite") || pkg.contains("instagram")) &&
                (allText.contains("who can see") || allText.contains("post settings") ||
                    (allText.contains("next") && (allText.contains("audience") || allText.contains("share"))) ||
                    (allText.contains("share now") && !nodes.any { it.isEditable }))
            ) {
                return ScreenSceneType.AUDIENCE_OR_SHARE_STAGE
            }
            if ((pkg.contains("katana") || pkg.contains("facebook") || pkg.contains("lite") || pkg.contains("instagram")) &&
                (allText.contains("create post") || allText.contains("share") || allText.contains("on your mind") ||
                    allText.contains("write something") || allText.contains("মনে কী আছে")) &&
                nodes.any { it.isEditable }
            ) {
                return ScreenSceneType.POST_COMPOSER
            }
            if (pkg.contains("youtube") &&
                (allText.contains("minimize") || allText.contains("play/pause") ||
                    allText.contains("pause video") || allText.contains("seek") ||
                    allText.contains("enter fullscreen"))
            ) {
                return ScreenSceneType.VIDEO_PLAYER
            }
            if (nodes.any { it.isEditable && it.yNorm <= 260 } &&
                (allText.contains("search") || nodes.any { it.viewIdSuffix.contains("search", ignoreCase = true) })
            ) {
                if (nodes.count { it.isClickable && it.yNorm in 220..820 && it.label.length >= 8 } >= 2 ||
                    ocrLines.count { it.centerYNorm in 220..820 } >= 3
                ) {
                    return ScreenSceneType.SEARCH_RESULTS
                }
            }
            if ((allText.contains("subscribe") || allText.contains("videos") || allText.contains("playlists")) &&
                (allText.contains("shorts") || allText.contains("community") || allText.contains("home"))
            ) {
                return ScreenSceneType.CHANNEL_PROFILE
            }
            if (pkg.contains("youtube") || allText.contains("views") || allText.contains("subscriptions")) {
                return ScreenSceneType.VIDEO_FEED
            }
            if (pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("orca") ||
                allText.contains("chats") || allText.contains("unread")
            ) {
                return ScreenSceneType.CHAT_LIST
            }
            if (pkg.contains("settings") || allText.contains("network") || allText.contains("battery") || allText.contains("bluetooth")) {
                return ScreenSceneType.SETTINGS_LIST
            }
            if (pkg.contains("chrome") || pkg.contains("browser") || allText.contains("http") || allText.contains("www.")) {
                return ScreenSceneType.WEB_OR_BROWSER
            }
            if ((allText.contains("allow") && (allText.contains("deny") || allText.contains("don't allow") || allText.contains("cancel") || allText.contains("ok"))) ||
                allText.contains("not now") ||
                allText.contains("skip for now") ||
                allText.contains("keep editing") ||
                (allText.contains("save login") && allText.contains("save")) ||
                (allText.contains("turn on") && allText.contains("cancel"))
            ) {
                return ScreenSceneType.DIALOG_MODAL
            }
            if (visualLabels.any {
                    it.contains("photo", true) || it.contains("portrait", true) ||
                        it.contains("landscape", true) || it.contains("food", true)
                } || detectedObjects.any { it.isMediaBox }
            ) {
                return ScreenSceneType.MEDIA_OR_PHOTO
            }
            if (pkg.contains("launcher") || pkg.contains("nexuslauncher")) {
                return ScreenSceneType.HOME_DESKTOP
            }
            return ScreenSceneType.UNKNOWN
        }

        fun computeSemanticConfidence(
            nodeCount: Int,
            ocrLineCount: Int,
            objectCount: Int,
            labelCount: Int,
            sceneType: ScreenSceneType
        ): Float {
            var score = 0.15f
            score += when {
                nodeCount >= 14 -> 0.55f
                nodeCount >= 6 -> 0.42f
                nodeCount >= 2 -> 0.22f
                else -> 0f
            }
            score += when {
                ocrLineCount >= 6 -> 0.30f
                ocrLineCount >= 2 -> 0.18f
                else -> 0f
            }
            if (objectCount >= 1) score += 0.14f
            if (labelCount >= 2) score += 0.08f
            if (sceneType != ScreenSceneType.UNKNOWN) score += 0.10f
            return score.coerceIn(0f, 1f)
        }

        fun requiresRawCloudPixels(prompt: String): Boolean {
            val lower = prompt.lowercase(Locale.ROOT)
            val pixelDependentCues = listOf(
                "color", "colour", "shirt", "dress", "wearing", "photo", "picture", "image",
                "draw", "logo", "face", "who is", "handwriting", "qr", "describe this image",
                "chobi", "ছবি", "রং", "রঙ", "কে এটা", "কি দেখা যাচ্ছে"
            )
            return pixelDependentCues.any { lower.contains(it) }
        }

        fun isLiteOrCanvasApp(packageName: String): Boolean {
            val pkg = packageName.lowercase(Locale.ROOT)
            if (pkg.isBlank()) return false
            return pkg.contains(".lite") ||
                pkg.contains("mlite") ||
                pkg.contains("fblite") ||
                pkg.contains("opera.mini") ||
                pkg.contains("webview")
        }

        fun evaluateCloudBitmapNeed(
            prompt: String,
            visionTokenMode: VisionTokenMode,
            semanticConfidence: Float,
            nodeCount: Int,
            ocrLineCount: Int,
            objectCount: Int,
            hasSourceBitmap: Boolean,
            packageName: String = "",
            forceCloudEscalation: Boolean = false
        ): Pair<Boolean, String> {
            if (!hasSourceBitmap) return false to "No bitmap captured"
            if (visionTokenMode == VisionTokenMode.CLOUD_VISION) {
                return true to "Full Cloud Vision mode"
            }
            if (forceCloudEscalation) {
                return true to "Hybrid Vision escalation for stalled/complex UI"
            }
            if (requiresRawCloudPixels(prompt)) {
                return true to "Visual color/photo detail requested"
            }
            // Lite/WebView/Canvas apps often expose sparse or unlabelled container trees — attach <=512px crop
            if (isLiteOrCanvasApp(packageName) && nodeCount < 10) {
                return true to "Lite/canvas app ($packageName, $nodeCount nodes) — attaching compact crop"
            }
            if (visionTokenMode == VisionTokenMode.OFFLINE_FIRST) {
                val hasReadableContext = nodeCount >= 4 || ocrLineCount >= 3 || objectCount >= 1
                if (semanticConfidence >= 0.58f && hasReadableContext) {
                    return false to "Offline vision + compact UI/OCR confidence ${(semanticConfidence * 100).roundToInt()}%"
                }
            }
            if (visionTokenMode == VisionTokenMode.HYBRID_COMPACT &&
                semanticConfidence >= 0.78f &&
                (nodeCount >= 10 || ocrLineCount >= 6)
            ) {
                return false to "High UI/OCR confidence in Hybrid Compact mode"
            }
            return true to "Low structural confidence (${(semanticConfidence * 100).roundToInt()}%) — attaching compact crop"
        }

        fun extractSearchQueryFromPrompt(userPrompt: String): String {
            val patterns = listOf(
                Regex("""youtube\s+(?:a|e|te|on|in)?\s*([a-z0-9\u0980-\u09FF ._'-]{2,50}?)\s+(?:search|khuj|খুঁজ)""", RegexOption.IGNORE_CASE),
                Regex("""search\s+(?:for\s+)?['"]?([a-z0-9\u0980-\u09FF ._'-]{2,50}?)['"]?(?:\s+on|\s+in|\s+and|\s+then|\s+channel|$)""", RegexOption.IGNORE_CASE),
                Regex("""([a-z0-9\u0980-\u09FF ._'-]{2,45}?)\s+(?:search\s+kore|search\s+koro|সার্চ\s+করে)""", RegexOption.IGNORE_CASE)
            )
            for (regex in patterns) {
                val raw = regex.find(userPrompt)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val cleaned = raw
                    .replace(Regex("""^(open|khule|youtube|ইউটিউব|search|for)\s+""", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (cleaned.length >= 2) return cleaned
            }
            return ""
        }

        /**
         * Zero-token offline multi-step action resolver. Matches clear navigation sub-goals directly
         * against Accessibility UI nodes + ML Kit OCR + ML Kit Object Detection boxes without spending
         * any cloud LLM tokens.
         */
        fun tryResolveNextHopOffline(
            activeSubGoal: String,
            userPrompt: String,
            analysis: OfflineScreenAnalysis
        ): OfflineResolvedAction? {
            val subLower = activeSubGoal.lowercase(Locale.ROOT)
            val nodes = parseCompactNodes(analysis.compactUiSummary)
            val ocr = analysis.ocrLines

            // Skip generic "Open <App>" because Step 0 handles initial launch
            if (subLower.startsWith("open youtube") || subLower.startsWith("open whatsapp") ||
                subLower.startsWith("open facebook") || subLower == "open app"
            ) {
                return null
            }

            // 1. Search sub-goal: locate search bar / icon or extract query directly
            if (subLower.contains("search") || subLower.contains("খুঁজ") || subLower.contains("সার্চ")) {
                val query = extractSearchQueryFromPrompt(userPrompt)
                if (query.isNotBlank()) {
                    // Check if results for this query are ALREADY visible on screen!
                    val alreadyHasResults = analysis.sceneType == ScreenSceneType.SEARCH_RESULTS &&
                        (nodes.any { !it.isEditable && it.yNorm > 200 && it.label.contains(query.split(' ').first(), ignoreCase = true) } ||
                            ocr.any { it.centerYNorm > 200 && it.text.contains(query.split(' ').first(), ignoreCase = true) })
                    if (!alreadyHasResults) {
                        val searchField = nodes.firstOrNull {
                            it.isEditable && (it.yNorm <= 260 || it.viewIdSuffix.contains("search", true) || it.label.contains("search", true))
                        }
                        if (searchField != null) {
                            return OfflineResolvedAction(
                                step = PlannedStep(
                                    actionType = ActionType.UI_INPUT_TEXT,
                                    label = "Search \"$query\" offline",
                                    target = "search",
                                    textPayload = query,
                                    boolValue = true
                                ),
                                subGoalMatched = activeSubGoal,
                                reason = "Offline UI matched editable search bar",
                                expectMoreSteps = true
                            )
                        }
                        val searchIcon = nodes.firstOrNull {
                            it.yNorm <= 260 && (it.isClickable) &&
                                (it.label.contains("search", true) || it.viewIdSuffix.contains("search", true))
                        }
                        if (searchIcon != null) {
                            return OfflineResolvedAction(
                                step = PlannedStep(
                                    actionType = ActionType.UI_TAP_COORDINATES,
                                    label = "Tap search offline",
                                    intValue = searchIcon.xNorm,
                                    secondaryIntValue = searchIcon.yNorm
                                ),
                                subGoalMatched = activeSubGoal,
                                reason = "Offline UI matched top search icon",
                                expectMoreSteps = true
                            )
                        }
                    }
                }
            }

            // 2. Open Videos / Shorts / Playlists tab on channel or search
            if (subLower.contains("video section") || subLower.contains("videos tab") ||
                subLower.contains("open videos") || subLower.contains("ভিডিও")
            ) {
                val tabNode = nodes.firstOrNull {
                    it.yNorm in 150..560 &&
                        (it.label.equals("Videos", ignoreCase = true) ||
                            it.label.equals("ভিডিও", ignoreCase = true) ||
                            (it.label.contains("Videos", ignoreCase = true) && it.label.length <= 16))
                }
                if (tabNode != null) {
                    return OfflineResolvedAction(
                        step = PlannedStep(
                            actionType = ActionType.UI_TAP_COORDINATES,
                            label = "Tap Videos tab offline",
                            intValue = tabNode.xNorm,
                            secondaryIntValue = tabNode.yNorm
                        ),
                        subGoalMatched = activeSubGoal,
                        reason = "Offline UI matched 'Videos' tab",
                        expectMoreSteps = true
                    )
                }
                val ocrTab = ocr.firstOrNull {
                    it.centerYNorm in 150..560 && it.text.trim().equals("Videos", ignoreCase = true)
                }
                if (ocrTab != null) {
                    return OfflineResolvedAction(
                        step = PlannedStep(
                            actionType = ActionType.UI_TAP_COORDINATES,
                            label = "Tap Videos (OCR) offline",
                            intValue = ocrTab.centerXNorm,
                            secondaryIntValue = ocrTab.centerYNorm
                        ),
                        subGoalMatched = activeSubGoal,
                        reason = "Offline OCR matched 'Videos' tab",
                        expectMoreSteps = true
                    )
                }
            }

            // 3. Open target channel row (from YouTube search results)
            if (subLower.contains("channel") || subLower.contains("চ্যানেল")) {
                val queryWords = extractSearchQueryFromPrompt(userPrompt)
                    .lowercase(Locale.ROOT)
                    .split(Regex("\\s+"))
                    .filter { it.length >= 3 }
                if (analysis.sceneType != ScreenSceneType.CHANNEL_PROFILE && queryWords.isNotEmpty()) {
                    val channelNode = nodes.firstOrNull { node ->
                        !node.isEditable && node.yNorm in 180..780 &&
                            (node.label.contains("subscribers", ignoreCase = true) ||
                                node.label.contains("@") ||
                                (node.isClickable && queryWords.all { node.label.contains(it, ignoreCase = true) } &&
                                    !node.label.contains("ad ", ignoreCase = true)))
                    }
                    if (channelNode != null) {
                        return OfflineResolvedAction(
                            step = PlannedStep(
                                actionType = ActionType.UI_TAP_COORDINATES,
                                label = "Open channel offline",
                                intValue = channelNode.xNorm,
                                secondaryIntValue = channelNode.yNorm
                            ),
                            subGoalMatched = activeSubGoal,
                            reason = "Offline UI matched channel row",
                            expectMoreSteps = true
                        )
                    }
                    val channelOcr = ocr.firstOrNull { line ->
                        line.centerYNorm in 180..760 &&
                            (line.text.contains("subscribers", ignoreCase = true) ||
                                line.text.contains("@") ||
                                queryWords.all { line.text.contains(it, ignoreCase = true) })
                    }
                    if (channelOcr != null) {
                        return OfflineResolvedAction(
                            step = PlannedStep(
                                actionType = ActionType.UI_TAP_COORDINATES,
                                label = "Open channel (OCR) offline",
                                intValue = channelOcr.centerXNorm,
                                secondaryIntValue = channelOcr.centerYNorm
                            ),
                            subGoalMatched = activeSubGoal,
                            reason = "Offline OCR matched channel entry",
                            expectMoreSteps = true
                        )
                    }
                }
            }

            // 4. Play first/top video card
            if (subLower.contains("first video") || subLower.contains("target video") ||
                subLower.contains("play video") || subLower.contains("প্রথম ভিডিও")
            ) {
                if (analysis.sceneType == ScreenSceneType.VIDEO_PLAYER) {
                    return OfflineResolvedAction(
                        step = PlannedStep(
                            actionType = ActionType.SPEAK_ONLY,
                            label = "Video playing"
                        ),
                        subGoalMatched = activeSubGoal,
                        reason = "Offline vision confirmed video player active",
                        expectMoreSteps = false
                    )
                }

                val skipLabels = setOf(
                    "videos", "home", "shorts", "playlists", "community", "all",
                    "subscribe", "subscribed", "join", "share", "search", "filters",
                    "popular", "latest", "oldest", "subscriptions", "you", "library"
                )
                val topVideoNode = nodes
                    .filter {
                        it.yNorm in 240..790 &&
                            !it.isEditable &&
                            it.label.length >= 10 &&
                            it.label.lowercase(Locale.ROOT) !in skipLabels &&
                            !it.label.contains("subscribers", ignoreCase = true)
                    }
                    .minByOrNull { abs(it.yNorm - 420) }

                if (topVideoNode != null) {
                    return OfflineResolvedAction(
                        step = PlannedStep(
                            actionType = ActionType.UI_TAP_COORDINATES,
                            label = "Play first video offline",
                            intValue = topVideoNode.xNorm,
                            secondaryIntValue = topVideoNode.yNorm
                        ),
                        subGoalMatched = activeSubGoal,
                        reason = "Offline UI matched top video card",
                        expectMoreSteps = false
                    )
                }

                // Fallback: tap top ML Kit detected media/thumbnail object box in feed region!
                val topMediaObject = analysis.detectedObjects
                    .filter { it.isMediaBox && it.centerYNorm in 240..780 }
                    .minByOrNull { it.centerYNorm }
                if (topMediaObject != null) {
                    return OfflineResolvedAction(
                        step = PlannedStep(
                            actionType = ActionType.UI_TAP_COORDINATES,
                            label = "Tap video card (ML Kit) offline",
                            intValue = topMediaObject.centerXNorm,
                            secondaryIntValue = topMediaObject.centerYNorm
                        ),
                        subGoalMatched = activeSubGoal,
                        reason = "ML Kit Object Detection matched video thumbnail box",
                        expectMoreSteps = false
                    )
                }
            }

            // 5. Blocking popup / permission modal dismissal (Not now, Skip, Keep editing, Allow)
            val popupSafeLabels = listOf("not now", "skip for now", "skip", "keep editing", "got it", "allow", "while using the app")
            val popupNode = nodes.firstOrNull { node ->
                val l = node.label.trim().lowercase(Locale.ROOT)
                l.isNotBlank() &&
                    !l.contains("discard") &&
                    !l.contains("delete") &&
                    popupSafeLabels.any { l == it || l.contains(it) }
            }
            if (popupNode != null && analysis.sceneType == ScreenSceneType.DIALOG_MODAL) {
                return OfflineResolvedAction(
                    step = PlannedStep(
                        actionType = ActionType.UI_TAP_COORDINATES,
                        label = "Dismiss popup (${popupNode.label.take(18)})",
                        intValue = popupNode.xNorm,
                        secondaryIntValue = popupNode.yNorm
                    ),
                    subGoalMatched = activeSubGoal,
                    reason = "Offline popup solver dismissed '${popupNode.label}'",
                    expectMoreSteps = true
                )
            }

            // 6. Multi-screen Social / Form progression (Next / Continue -> Share / Post / Publish)
            if (subLower.contains("next") || subLower.contains("share") || subLower.contains("post") ||
                subLower.contains("publish") || subLower.contains("পোস্ট") || subLower.contains("শেয়ার")
            ) {
                val publishWords = listOf("share now", "post", "publish", "share", "পোস্ট করুন", "পোস্ট", "শেয়ার")
                val publishNode = nodes.firstOrNull { node ->
                    val l = node.label.trim().lowercase(Locale.ROOT)
                    ((node.yNorm <= 260 && node.xNorm >= 550) || node.yNorm >= 700) &&
                        publishWords.any { l == it }
                } ?: ocr.firstOrNull { line ->
                    val l = line.text.trim().lowercase(Locale.ROOT)
                    ((line.centerYNorm <= 260 && line.centerXNorm >= 550) || line.centerYNorm >= 700) &&
                        publishWords.any { l == it }
                }?.let {
                    ParsedScreenNode(
                        xNorm = it.centerXNorm,
                        yNorm = it.centerYNorm,
                        isClickable = true,
                        isEditable = false,
                        isScrollable = false,
                        viewIdSuffix = "",
                        label = it.text,
                        rawLine = ""
                    )
                }
                if (publishNode != null && analysis.sceneType != ScreenSceneType.POST_COMPOSER) {
                    return OfflineResolvedAction(
                        step = PlannedStep(
                            actionType = ActionType.UI_TAP_COORDINATES,
                            label = "Tap ${publishNode.label} offline",
                            intValue = publishNode.xNorm,
                            secondaryIntValue = publishNode.yNorm
                        ),
                        subGoalMatched = activeSubGoal,
                        reason = "Offline solver tapped final '${publishNode.label}'",
                        expectMoreSteps = false
                    )
                }

                val nextWords = listOf("next", "পরবর্তী", "continue", "done")
                val nextNode = nodes.firstOrNull { node ->
                    val l = node.label.trim().lowercase(Locale.ROOT)
                    ((node.yNorm <= 260 && node.xNorm >= 550) || node.yNorm >= 700) &&
                        nextWords.any { l == it }
                } ?: ocr.firstOrNull { line ->
                    val l = line.text.trim().lowercase(Locale.ROOT)
                    ((line.centerYNorm <= 260 && line.centerXNorm >= 550) || line.centerYNorm >= 700) &&
                        nextWords.any { l == it }
                }?.let {
                    ParsedScreenNode(
                        xNorm = it.centerXNorm,
                        yNorm = it.centerYNorm,
                        isClickable = true,
                        isEditable = false,
                        isScrollable = false,
                        viewIdSuffix = "",
                        label = it.text,
                        rawLine = ""
                    )
                }
                if (nextNode != null) {
                    return OfflineResolvedAction(
                        step = PlannedStep(
                            actionType = ActionType.UI_TAP_COORDINATES,
                            label = "Tap ${nextNode.label} offline",
                            intValue = nextNode.xNorm,
                            secondaryIntValue = nextNode.yNorm
                        ),
                        subGoalMatched = activeSubGoal,
                        reason = "Offline solver tapped intermediate '${nextNode.label}'",
                        expectMoreSteps = true
                    )
                }
            }

            return null
        }
    }
}
