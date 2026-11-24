package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "MediaProjectionGrabber"

/**
 * Tier-3 Android 14+ compliant screen vision fallback using MediaProjection.
 * Maintains an active VirtualDisplay + ImageReader while the user-consented
 * [com.example.service.ScreenVisionService] is running so background screenshots
 * remain available even when Accessibility takeScreenshot() is throttled or returns null.
 */
object MediaProjectionScreenGrabber {

    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth: Int = 720
    private var captureHeight: Int = 1280
    private var captureDensity: Int = 320

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by OS/user.")
            releaseLockedUnsafe()
        }
    }

    fun isProjectionActive(): Boolean = _isActive.value && mediaProjection != null && imageReader != null

    fun attachProjection(projection: MediaProjection, context: Context) {
        synchronized(this) {
            releaseLockedUnsafe()
            val metrics = context.resources.displayMetrics
            val rawW = metrics.widthPixels.coerceAtLeast(540)
            val rawH = metrics.heightPixels.coerceAtLeast(960)
            // Capture at scaled width <= 900px to keep memory & Gemini upload tiny
            val scale = if (rawW > 900) 900f / rawW.toFloat() else 1f
            captureWidth = (rawW * scale).toInt().coerceAtLeast(360)
            captureHeight = (rawH * scale).toInt().coerceAtLeast(640)
            captureDensity = metrics.densityDpi.coerceAtLeast(160)

            // Android 14+ requires registering callback BEFORE createVirtualDisplay()
            projection.registerCallback(projectionCallback, mainHandler)
            val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            val display = projection.createVirtualDisplay(
                "AetherScreenVision",
                captureWidth,
                captureHeight,
                captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )

            mediaProjection = projection
            imageReader = reader
            virtualDisplay = display
            _isActive.value = true
            Log.d(TAG, "VirtualDisplay ready (${captureWidth}x${captureHeight})")
        }
    }

    suspend fun captureLatestBitmap(maxWaitMs: Long = 450L): Bitmap? = mutex.withLock {
        val reader = imageReader ?: return null
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() <= deadline) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) {
                try {
                    val planes = image.planes
                    if (planes.isEmpty()) return null
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * captureWidth
                    val paddedWidth = captureWidth + if (pixelStride > 0) rowPadding / pixelStride else 0
                    val rawBmp = Bitmap.createBitmap(
                        paddedWidth.coerceAtLeast(captureWidth),
                        captureHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    rawBmp.copyPixelsFromBuffer(buffer)
                    val cropped = if (rawBmp.width != captureWidth) {
                        Bitmap.createBitmap(rawBmp, 0, 0, captureWidth, captureHeight).also {
                            rawBmp.recycle()
                        }
                    } else {
                        rawBmp
                    }
                    return cropped
                } catch (e: Exception) {
                    Log.w(TAG, "Failed extracting MediaProjection frame: ${e.message}")
                    return null
                } finally {
                    image.close()
                }
            }
            delay(60L)
        }
        return null
    }

    fun release() {
        synchronized(this) {
            releaseLockedUnsafe()
        }
    }

    private fun releaseLockedUnsafe() {
        _isActive.value = false
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        val proj = mediaProjection
        mediaProjection = null
        if (proj != null) {
            runCatching { proj.unregisterCallback(projectionCallback) }
            runCatching { proj.stop() }
        }
    }
}
