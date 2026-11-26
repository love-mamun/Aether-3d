package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "CameraXLensEngine"

data class CameraLensCueState(
    val liveLabels: List<String> = emptyList(),
    val liveOcrPreview: String = "",
    val isCameraBound: Boolean = false,
    val isCapturing: Boolean = false,
    val isAnalyzingAnswer: Boolean = false,
    val lensFacingBack: Boolean = true,
    val torchEnabled: Boolean = false,
    val latestQuestion: String = "",
    val latestAnswer: String = "",
    val cameraError: String? = null
)

/**
 * Coordinates CameraX Preview, throttled on-device ML Kit live scene/OCR cues,
 * and on-demand high-resolution bitmap capture for Aether's Live Vision Lens.
 */
class CameraXLensEngine(private val context: Context) {

    private val _cueState = MutableStateFlow(CameraLensCueState())
    val cueState: StateFlow<CameraLensCueState> = _cueState.asStateFlow()

    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val imageLabeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.58f)
                .build()
        )
    }

    private var imageCapture: ImageCapture? = null
    private var boundCamera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var lastAnalyzedAtMs = 0L

    fun bindCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        useBackCamera: Boolean = _cueState.value.lensFacingBack
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().apply {
                        setAnalyzer(analyzerExecutor) { proxy ->
                            analyzeLiveFrame(proxy)
                        }
                    }

                val selector = if (useBackCamera) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                val cam = provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    capture,
                    analysis
                )
                boundCamera = cam
                _cueState.update {
                    it.copy(
                        isCameraBound = true,
                        lensFacingBack = useBackCamera,
                        torchEnabled = false,
                        cameraError = null
                    )
                }
            }.onFailure { err ->
                Log.w(TAG, "CameraX bind failure: ${err.message}", err)
                _cueState.update {
                    it.copy(
                        isCameraBound = false,
                        cameraError = err.localizedMessage ?: "Camera preview unavailable on this device."
                    )
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun toggleTorch() {
        val cam = boundCamera ?: return
        val next = !_cueState.value.torchEnabled
        runCatching {
            if (cam.cameraInfo.hasFlashUnit()) {
                cam.cameraControl.enableTorch(next)
                _cueState.update { it.copy(torchEnabled = next) }
            }
        }
    }

    fun setAnalyzingAnswer(analyzing: Boolean, question: String = "") {
        _cueState.update {
            it.copy(
                isAnalyzingAnswer = analyzing,
                latestQuestion = if (question.isNotBlank()) question else it.latestQuestion
            )
        }
    }

    fun publishVisionAnswer(question: String, answer: String) {
        _cueState.update {
            it.copy(
                isCapturing = false,
                isAnalyzingAnswer = false,
                latestQuestion = question,
                latestAnswer = answer,
                cameraError = null
            )
        }
    }

    fun captureSnapshotBitmap(
        onCaptured: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture ?: run {
            onError("Camera is still initializing.")
            return
        }
        _cueState.update { it.copy(isCapturing = true, cameraError = null) }
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = runCatching { imageProxyToBitmap(image) }.getOrNull()
                    image.close()
                    _cueState.update { it.copy(isCapturing = false) }
                    if (bitmap != null) {
                        onCaptured(bitmap)
                    } else {
                        onError("Could not decode camera frame.")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    val msg = exception.localizedMessage ?: "Camera capture failed."
                    _cueState.update { it.copy(isCapturing = false, cameraError = msg) }
                    onError(msg)
                }
            }
        )
    }

    fun unbind() {
        runCatching { cameraProvider?.unbindAll() }
        boundCamera = null
        imageCapture = null
        _cueState.update {
            it.copy(isCameraBound = false, torchEnabled = false)
        }
    }

    fun release() {
        unbind()
        analyzerExecutor.shutdownNow()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeLiveFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalyzedAtMs < 950L) {
            proxy.close()
            return
        }
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        lastAnalyzedAtMs = now
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        var pendingTasks = 2
        fun finishOne() {
            pendingTasks--
            if (pendingTasks <= 0) {
                proxy.close()
            }
        }

        textRecognizer.process(input)
            .addOnSuccessListener { result ->
                val text = result.text.trim().replace("\n", " ").take(120)
                _cueState.update { it.copy(liveOcrPreview = text) }
            }
            .addOnCompleteListener { finishOne() }

        imageLabeler.process(input)
            .addOnSuccessListener { labels ->
                val top = labels.take(4).map { "${it.text} ${(it.confidence * 100).toInt()}%" }
                _cueState.update { it.copy(liveLabels = top) }
            }
            .addOnCompleteListener { finishOne() }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes.firstOrNull()?.buffer ?: return null
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rotation = image.imageInfo.rotationDegrees
        val maxSide = 1024
        val scale = if (maxOf(raw.width, raw.height) > maxSide) {
            maxSide.toFloat() / maxOf(raw.width, raw.height).toFloat()
        } else {
            1f
        }
        if (rotation == 0 && scale >= 0.99f) return raw
        val matrix = Matrix().apply {
            if (rotation != 0) postRotate(rotation.toFloat())
            if (scale < 0.99f) postScale(scale, scale)
        }
        val transformed = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (transformed !== raw) raw.recycle()
        return transformed
    }
}
