package com.example.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.data.local.VisionTokenMode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val TAG = "ScreenVisionProcessor"

data class ScreenCropSpec(
    val rect: Rect,
    val regionLabel: String,
    val areaReductionPercent: Int
)

data class OcrLineItem(
    val text: String,
    val centerXNorm: Int,
    val centerYNorm: Int
)

data class OcrExtractionResult(
    val lines: List<OcrLineItem>,
    val summaryText: String
)

data class ProcessedVisionContext(
    val croppedBitmap: Bitmap?,
    val ownsCroppedBitmap: Boolean,
    val regionLabel: String,
    val areaReductionPercent: Int,
    val ocrSummary: String,
    val ocrLineCount: Int,
    val offlineAnalysis: OfflineScreenAnalysis? = null,
    val tokensSaved: Int = 0
)

class ScreenVisionProcessor(
    private val offlineEngine: OfflineScreenUnderstandingEngine = OfflineScreenUnderstandingEngine()
) {

    /**
     * Computes a token-efficient crop region based on user prompt cues (top/bottom/center/dialog)
     * or trims system status/navigation bars when no specific region is requested.
     */
    fun computeTargetCropRect(
        width: Int,
        height: Int,
        prompt: String,
        anchorRect: Rect? = null
    ): ScreenCropSpec {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val lower = prompt.lowercase()

        if (anchorRect != null && !anchorRect.isEmpty) {
            val padX = (safeW * 0.14f).roundToInt()
            val padY = (safeH * 0.14f).roundToInt()
            val clamped = Rect(
                (anchorRect.left - padX).coerceIn(0, safeW - 1),
                (anchorRect.top - padY).coerceIn(0, safeH - 1),
                (anchorRect.right + padX).coerceIn(1, safeW),
                (anchorRect.bottom + padY).coerceIn(1, safeH)
            )
            if (clamped.width() >= safeW / 6 && clamped.height() >= safeH / 10) {
                return buildCropSpec(safeW, safeH, clamped, "Focused UI Region")
            }
        }

        val (topRatio, bottomRatio, leftRatio, rightRatio, label) = when {
            lower.contains("top") || lower.contains("header") || lower.contains("status") ||
                lower.contains("notification") || lower.contains("উপরে") ->
                CropRatios(0.0f, 0.42f, 0.0f, 1.0f, "Top Region")

            lower.contains("bottom") || lower.contains("footer") || lower.contains("dock") ||
                lower.contains("nav") || lower.contains("নিচে") ->
                CropRatios(0.56f, 1.0f, 0.0f, 1.0f, "Bottom Region")

            lower.contains("center") || lower.contains("middle") || lower.contains("dialog") ||
                lower.contains("popup") || lower.contains("modal") || lower.contains("card") ||
                lower.contains("মাঝখানে") ->
                CropRatios(0.20f, 0.80f, 0.04f, 0.96f, "Center Focus")

            lower.contains("upper") ->
                CropRatios(0.03f, 0.56f, 0.0f, 1.0f, "Upper Half")

            lower.contains("lower") ->
                CropRatios(0.44f, 0.96f, 0.0f, 1.0f, "Lower Half")

            else ->
                // Smart content frame: strip OS status & gesture nav bars (~9% height saved immediately)
                CropRatios(0.035f, 0.945f, 0.0f, 1.0f, "Active Viewport")
        }

        val rect = Rect(
            (safeW * leftRatio).roundToInt().coerceIn(0, safeW - 2),
            (safeH * topRatio).roundToInt().coerceIn(0, safeH - 2),
            (safeW * rightRatio).roundToInt().coerceIn(1, safeW),
            (safeH * bottomRatio).roundToInt().coerceIn(1, safeH)
        )
        return buildCropSpec(safeW, safeH, rect, label)
    }

    private fun buildCropSpec(fullW: Int, fullH: Int, rect: Rect, label: String): ScreenCropSpec {
        val fullArea = (fullW.toLong() * fullH.toLong()).coerceAtLeast(1L)
        val cropArea = (rect.width().toLong() * rect.height().toLong()).coerceAtLeast(1L)
        val reduction = (((fullArea - cropArea) * 100L) / fullArea).toInt().coerceIn(0, 95)
        return ScreenCropSpec(rect = rect, regionLabel = label, areaReductionPercent = reduction)
    }

    fun cropAndScaleRegion(
        source: Bitmap,
        spec: ScreenCropSpec,
        maxDimension: Int = 720
    ): Bitmap {
        val r = spec.rect
        val safeLeft = r.left.coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val safeTop = r.top.coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val safeWidth = r.width().coerceIn(1, (source.width - safeLeft).coerceAtLeast(1))
        val safeHeight = r.height().coerceIn(1, (source.height - safeTop).coerceAtLeast(1))

        val cropped = if (safeLeft == 0 && safeTop == 0 && safeWidth == source.width && safeHeight == source.height) {
            source
        } else {
            Bitmap.createBitmap(source, safeLeft, safeTop, safeWidth, safeHeight)
        }

        val maxSide = maxOf(cropped.width, cropped.height)
        return if (maxSide > maxDimension) {
            val scale = maxDimension.toFloat() / maxSide.toFloat()
            val targetW = (cropped.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (cropped.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
            if (cropped !== source && scaled !== cropped) {
                cropped.recycle()
            }
            scaled
        } else {
            cropped
        }
    }

    suspend fun processScreenForVision(
        sourceBitmap: Bitmap?,
        prompt: String,
        accessibilityNodeCount: Int,
        forceOcrFallback: Boolean,
        anchorRect: Rect? = null,
        packageName: String = "",
        rawUiSummary: String = "",
        visionTokenMode: VisionTokenMode = VisionTokenMode.OFFLINE_FIRST
    ): ProcessedVisionContext = withContext(Dispatchers.Default) {
        if (sourceBitmap == null) {
            val offlineOnly = offlineEngine.analyzeBitmapAndUi(
                bitmap = null,
                packageName = packageName,
                rawUiSummary = rawUiSummary,
                nodeCount = accessibilityNodeCount,
                ocrLines = emptyList(),
                ocrSummary = "",
                userPrompt = prompt,
                visionTokenMode = visionTokenMode
            )
            return@withContext ProcessedVisionContext(
                croppedBitmap = null,
                ownsCroppedBitmap = false,
                regionLabel = "UI Tree Only",
                areaReductionPercent = 100,
                ocrSummary = "",
                ocrLineCount = 0,
                offlineAnalysis = offlineOnly,
                tokensSaved = offlineOnly.estimatedTokensSaved
            )
        }

        val maxDim = when (visionTokenMode) {
            VisionTokenMode.CLOUD_VISION -> 720
            VisionTokenMode.HYBRID_COMPACT, VisionTokenMode.OFFLINE_FIRST -> 512
        }
        val spec = computeTargetCropRect(sourceBitmap.width, sourceBitmap.height, prompt, anchorRect)
        val cropped = runCatching { cropAndScaleRegion(sourceBitmap, spec, maxDimension = maxDim) }.getOrNull() ?: sourceBitmap
        val ownsCropped = cropped !== sourceBitmap

        val shouldRunOcr = forceOcrFallback ||
            visionTokenMode == VisionTokenMode.OFFLINE_FIRST ||
            accessibilityNodeCount < 8 ||
            wantsTextExtraction(prompt)

        val ocr = if (shouldRunOcr) {
            extractOcrWithCoordinates(
                bitmap = cropped,
                cropRectInFullScreen = spec.rect,
                fullScreenWidth = sourceBitmap.width,
                fullScreenHeight = sourceBitmap.height
            )
        } else {
            OcrExtractionResult(emptyList(), "")
        }

        val offlineAnalysis = offlineEngine.analyzeBitmapAndUi(
            bitmap = cropped,
            packageName = packageName,
            rawUiSummary = rawUiSummary,
            nodeCount = accessibilityNodeCount,
            ocrLines = ocr.lines,
            ocrSummary = ocr.summaryText,
            userPrompt = prompt,
            visionTokenMode = visionTokenMode
        )

        val bitmapForCloud: Bitmap?
        val ownsFinalBitmap: Boolean
        if (offlineAnalysis.shouldAttachBitmapToCloud) {
            bitmapForCloud = cropped
            ownsFinalBitmap = ownsCropped
        } else {
            if (ownsCropped) {
                runCatching { cropped.recycle() }
            }
            bitmapForCloud = null
            ownsFinalBitmap = false
        }

        ProcessedVisionContext(
            croppedBitmap = bitmapForCloud,
            ownsCroppedBitmap = ownsFinalBitmap,
            regionLabel = if (bitmapForCloud == null) "Offline Vision (<50MB)" else spec.regionLabel,
            areaReductionPercent = if (bitmapForCloud == null) 100 else spec.areaReductionPercent,
            ocrSummary = ocr.summaryText,
            ocrLineCount = ocr.lines.size,
            offlineAnalysis = offlineAnalysis,
            tokensSaved = offlineAnalysis.estimatedTokensSaved
        )
    }

    suspend fun extractOcrWithCoordinates(
        bitmap: Bitmap,
        cropRectInFullScreen: Rect = Rect(0, 0, bitmap.width, bitmap.height),
        fullScreenWidth: Int = bitmap.width.coerceAtLeast(1),
        fullScreenHeight: Int = bitmap.height.coerceAtLeast(1)
    ): OcrExtractionResult {
        return withTimeoutOrNull(2_500L) {
            suspendCancellableCoroutine { cont ->
                try {
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    recognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            val items = mutableListOf<OcrLineItem>()
                            val bitmapW = bitmap.width.coerceAtLeast(1).toFloat()
                            val bitmapH = bitmap.height.coerceAtLeast(1).toFloat()
                            val cropW = cropRectInFullScreen.width().coerceAtLeast(1).toFloat()
                            val cropH = cropRectInFullScreen.height().coerceAtLeast(1).toFloat()

                            for (block in visionText.textBlocks) {
                                for (line in block.lines) {
                                    val txt = line.text.trim()
                                    if (txt.isBlank()) continue
                                    val box = line.boundingBox
                                    val (cxNorm, cyNorm) = if (box != null) {
                                        val relX = box.exactCenterX() / bitmapW
                                        val relY = box.exactCenterY() / bitmapH
                                        val fullX = cropRectInFullScreen.left + relX * cropW
                                        val fullY = cropRectInFullScreen.top + relY * cropH
                                        val nx = ((fullX / fullScreenWidth.toFloat()) * 1000f).roundToInt().coerceIn(0, 1000)
                                        val ny = ((fullY / fullScreenHeight.toFloat()) * 1000f).roundToInt().coerceIn(0, 1000)
                                        nx to ny
                                    } else {
                                        500 to 500
                                    }
                                    items.add(OcrLineItem(txt.take(80), cxNorm, cyNorm))
                                    if (items.size >= 60) break
                                }
                                if (items.size >= 60) break
                            }
                            runCatching { recognizer.close() }
                            val formatted = formatOcrSummary(items)
                            if (cont.isActive) {
                                cont.resume(OcrExtractionResult(items, formatted))
                            }
                        }
                        .addOnFailureListener { err ->
                            Log.w(TAG, "ML Kit OCR failed: ${err.message}")
                            runCatching { recognizer.close() }
                            if (cont.isActive) cont.resume(OcrExtractionResult(emptyList(), ""))
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "ML Kit OCR unavailable in runtime: ${t.message}")
                    if (cont.isActive) cont.resume(OcrExtractionResult(emptyList(), ""))
                }
            }
        } ?: OcrExtractionResult(emptyList(), "")
    }

    internal fun formatOcrSummary(items: List<OcrLineItem>): String {
        if (items.isEmpty()) return ""
        return buildString {
            appendLine("ON-DEVICE OCR FALLBACK (${items.size} lines, 0-1000 coords):")
            items.forEach { item ->
                appendLine("[${item.centerXNorm},${item.centerYNorm}] \"${item.text}\"")
            }
        }.trim()
    }

    private fun wantsTextExtraction(prompt: String): Boolean {
        val l = prompt.lowercase()
        return l.contains("read") || l.contains("text") || l.contains("ocr") ||
            l.contains("price") || l.contains("code") || l.contains("number") ||
            l.contains("summarize") || l.contains("translate") || l.contains("what does it say")
    }

    private data class CropRatios(
        val top: Float,
        val bottom: Float,
        val left: Float,
        val right: Float,
        val label: String
    )
}
