package com.example.ui.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.TextureView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10

private const val TAG = "GLTextureView"

/**
 * TextureView-backed OpenGL ES 2.0 host that renders a [GLSurfaceView.Renderer]
 * into a SurfaceTexture without spawning a child SurfaceView SurfaceSyncGroup.
 */
class GLTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private var renderer: GLSurfaceView.Renderer? = null
    private var glThread: GLRenderThread? = null

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    fun setRenderer(renderer: GLSurfaceView.Renderer) {
        this.renderer = renderer
        if (isAvailable && glThread == null) {
            surfaceTexture?.let { startThread(it, width, height) }
        }
    }

    fun onResume() {
        glThread?.setPaused(false)
    }

    fun onPause() {
        glThread?.setPaused(true)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startThread(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        glThread?.onWindowResize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        stopThread()
        super.onDetachedFromWindow()
    }

    private fun startThread(surface: SurfaceTexture, width: Int, height: Int) {
        val currentRenderer = renderer ?: return
        stopThread()
        glThread = GLRenderThread(currentRenderer, surface, width, height).apply {
            start()
        }
    }

    private fun stopThread() {
        val thread = glThread ?: return
        glThread = null
        thread.requestExitAndWait()
    }

    private class GLRenderThread(
        private val renderer: GLSurfaceView.Renderer,
        private val surfaceTexture: SurfaceTexture,
        initialWidth: Int,
        initialHeight: Int
    ) : Thread("AetherGLThread") {

        private val lock = Object()
        private var shouldExit = false
        private var exited = false
        private var paused = false
        private var sizeChanged = true
        private var width = initialWidth.coerceAtLeast(1)
        private var height = initialHeight.coerceAtLeast(1)

        private var egl: EGL10? = null
        private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE
        private var eglConfig: EGLConfig? = null

        override fun run() {
            try {
                if (!initEgl()) return
                val gl = eglContext.gl as? GL10
                renderer.onSurfaceCreated(gl, eglConfig)

                while (true) {
                    var w = 1
                    var h = 1
                    var resize = false

                    synchronized(lock) {
                        while (paused && !shouldExit) {
                            lock.wait(50L)
                        }
                        if (shouldExit) return
                        if (sizeChanged) {
                            w = width
                            h = height
                            resize = true
                            sizeChanged = false
                        }
                    }

                    if (resize) {
                        renderer.onSurfaceChanged(gl, w, h)
                    }

                    val frameStartMs = System.currentTimeMillis()
                    renderer.onDrawFrame(gl)
                    egl?.eglSwapBuffers(eglDisplay, eglSurface)

                    val frameElapsed = System.currentTimeMillis() - frameStartMs
                    val remainingMs = 16L - frameElapsed
                    if (remainingMs > 0L) {
                        synchronized(lock) {
                            if (!shouldExit && !paused) {
                                lock.wait(remainingMs)
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Normal shutdown
            } catch (e: Exception) {
                Log.e(TAG, "GL render loop error", e)
            } finally {
                releaseEgl()
                synchronized(lock) {
                    exited = true
                    lock.notifyAll()
                }
            }
        }

        fun setPaused(pause: Boolean) {
            synchronized(lock) {
                paused = pause
                lock.notifyAll()
            }
        }

        fun onWindowResize(newWidth: Int, newHeight: Int) {
            synchronized(lock) {
                width = newWidth.coerceAtLeast(1)
                height = newHeight.coerceAtLeast(1)
                sizeChanged = true
                lock.notifyAll()
            }
        }

        fun requestExitAndWait() {
            synchronized(lock) {
                shouldExit = true
                lock.notifyAll()
                while (!exited) {
                    try {
                        lock.wait(100L)
                    } catch (_: InterruptedException) {
                        currentThread().interrupt()
                        break
                    }
                }
            }
        }

        private fun initEgl(): Boolean {
            val eglInstance = EGLContext.getEGL() as? EGL10 ?: return false
            egl = eglInstance

            val display = eglInstance.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (display === EGL10.EGL_NO_DISPLAY) return false
            eglDisplay = display

            val version = IntArray(2)
            if (!eglInstance.eglInitialize(display, version)) return false

            val config = chooseEglConfig(eglInstance, display) ?: return false
            eglConfig = config

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL10.EGL_NONE
            )
            val context = eglInstance.eglCreateContext(
                display,
                config,
                EGL10.EGL_NO_CONTEXT,
                contextAttribs
            )
            if (context == null || context === EGL10.EGL_NO_CONTEXT) return false
            eglContext = context

            val surface = eglInstance.eglCreateWindowSurface(display, config, surfaceTexture, null)
            if (surface == null || surface === EGL10.EGL_NO_SURFACE) return false
            eglSurface = surface

            return eglInstance.eglMakeCurrent(display, surface, surface, context)
        }

        private fun chooseEglConfig(eglInstance: EGL10, display: EGLDisplay): EGLConfig? {
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)

            val preferredSpec = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_NONE
            )
            if (eglInstance.eglChooseConfig(display, preferredSpec, configs, 1, numConfig) &&
                numConfig[0] > 0 && configs[0] != null
            ) {
                return configs[0]
            }

            val fallbackSpec = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_NONE
            )
            if (eglInstance.eglChooseConfig(display, fallbackSpec, configs, 1, numConfig) &&
                numConfig[0] > 0
            ) {
                return configs[0]
            }
            return null
        }

        private fun releaseEgl() {
            val eglInstance = egl ?: return
            if (eglDisplay !== EGL10.EGL_NO_DISPLAY) {
                eglInstance.eglMakeCurrent(
                    eglDisplay,
                    EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_CONTEXT
                )
                if (eglSurface !== EGL10.EGL_NO_SURFACE) {
                    eglInstance.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL10.EGL_NO_SURFACE
                }
                if (eglContext !== EGL10.EGL_NO_CONTEXT) {
                    eglInstance.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL10.EGL_NO_CONTEXT
                }
                eglInstance.eglTerminate(eglDisplay)
                eglDisplay = EGL10.EGL_NO_DISPLAY
            }
        }
    }
}
