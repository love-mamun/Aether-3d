package com.example.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.ai.AssistantPersona
import com.example.ai.CoreMode
import kotlin.math.abs
import kotlin.math.sin

class FloatingOrbOverlay(
    private val context: Context,
    private val onOrbTapped: () -> Unit,
    private val onVisionScanTapped: () -> Unit,
    private val onMacroRecordTapped: () -> Unit,
    private val onBriefingTapped: () -> Unit,
    private val onAbortTask: () -> Unit,
    private val onStopOverlay: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rootLayout: LinearLayout? = null
    private var orbView: HolographicOrbView? = null
    private var contentColumn: LinearLayout? = null
    private var bubbleLayout: LinearLayout? = null
    private var actionDrawer: LinearLayout? = null
    private var macroPill: TextView? = null
    private var abortBtnView: TextView? = null
    private var recBadgeView: TextView? = null
    private var statusTextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var drawerExpanded = false
    private var lastTapUpTime = 0L
    private var singleTapRunnable: Runnable? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing || !Settings.canDrawOverlays(context)) return

        val density = context.resources.displayMetrics.density
        val screenW = context.resources.displayMetrics.widthPixels
        val edgeMargin = (14f * density).toInt()
        val orbSizePx = (62f * density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = edgeMargin
            y = (220f * density).toInt()
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0.72f
        }

        val orbCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val orb = HolographicOrbView(context).apply {
            layoutParams = LinearLayout.LayoutParams(orbSizePx, orbSizePx)
        }

        val recBadge = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            background = GradientDrawable().apply {
                setColor(0xFFD50000.toInt())
                cornerRadius = 8f * density
            }
            setPadding((6f * density).toInt(), (1f * density).toInt(), (6f * density).toInt(), (1f * density).toInt())
            text = "REC"
            visibility = View.GONE
        }
        orbCol.addView(orb)
        orbCol.addView(recBadge)

        val bubbleBg = GradientDrawable().apply {
            setColor(0xE60C1322.toInt())
            cornerRadius = 18f * density
            setStroke((1f * density).toInt(), 0x6600E5FF)
        }

        val statusTv = TextView(context).apply {
            setTextColor(0xFFF0F6FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxWidth = (210f * density).toInt()
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            text = "Aether Ready · 2x tap menu"
        }

        val touchPadH = (10f * density).toInt()
        val minTapPx = (48f * density).toInt()

        val menuToggleBtn = TextView(context).apply {
            setTextColor(0xFF00E5FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "☰"
            gravity = Gravity.CENTER
            minWidth = minTapPx
            minHeight = minTapPx
            setPadding(touchPadH, 0, touchPadH, 0)
            contentDescription = "Toggle quick actions menu"
            setOnClickListener {
                drawerExpanded = !drawerExpanded
                actionDrawer?.visibility = if (drawerExpanded) View.VISIBLE else View.GONE
                container.alpha = 1.0f
            }
        }

        val abortBtn = TextView(context).apply {
            setTextColor(0xFFFF6E6E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            text = "ABORT"
            gravity = Gravity.CENTER
            minWidth = minTapPx
            minHeight = minTapPx
            setPadding(touchPadH, 0, touchPadH, 0)
            visibility = View.GONE
            contentDescription = "Abort active tasks"
            setOnClickListener { onAbortTask() }
        }

        val closeBtn = TextView(context).apply {
            setTextColor(0xFF9FB3C8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = "×"
            gravity = Gravity.CENTER
            minWidth = minTapPx
            minHeight = minTapPx
            setPadding(touchPadH, 0, touchPadH, 0)
            contentDescription = "Hide floating orb"
            setOnClickListener { onStopOverlay() }
        }

        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bubbleBg
            setPadding(
                (12f * density).toInt(),
                (7f * density).toInt(),
                (10f * density).toInt(),
                (7f * density).toInt()
            )
            visibility = View.GONE
            addView(statusTv)
            addView(abortBtn)
            addView(menuToggleBtn)
            addView(closeBtn)
            setOnClickListener {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                )
            }
        }

        fun makeActionPill(label: String, strokeHex: Int, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                setTextColor(0xFFF0F6FF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = GradientDrawable().apply {
                    setColor(0xEB10192D.toInt())
                    cornerRadius = 14f * density
                    setStroke((1f * density).toInt(), strokeHex)
                }
                setPadding(
                    (10f * density).toInt(),
                    (6f * density).toInt(),
                    (10f * density).toInt(),
                    (6f * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (5f * density).toInt()
                }
                setOnClickListener {
                    drawerExpanded = false
                    actionDrawer?.visibility = View.GONE
                    onClick()
                }
            }
        }

        val micPill = makeActionPill("Mic", 0x9900E5FF.toInt()) { onOrbTapped() }
        val visionPill = makeActionPill("Vision", 0x99FFB300.toInt()) { onVisionScanTapped() }
        val recMacroPill = makeActionPill("Rec Macro", 0x99FF5252.toInt()) { onMacroRecordTapped() }
        val briefingPill = makeActionPill("Briefing", 0x9900E676.toInt()) { onBriefingTapped() }

        val drawer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, (5f * density).toInt(), 0, 0)
            addView(micPill)
            addView(visionPill)
            addView(recMacroPill)
            addView(briefingPill)
        }

        val rightCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubble)
            addView(drawer)
        }

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((8f * density).toInt(), 1)
        }

        container.addView(orbCol)
        container.addView(spacer)
        container.addView(rightCol)

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var downTime = 0L

        orb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    container.alpha = 1.0f
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    if (isShowing && container.isAttachedToWindow) {
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val heldMs = System.currentTimeMillis() - downTime
                    if (moved) {
                        // Magnetic snap-to-edge spring animation
                        val targetEdgeX = if (params.x + orbSizePx / 2 > screenW / 2) {
                            (screenW - orbSizePx - edgeMargin).coerceAtLeast(edgeMargin)
                        } else {
                            edgeMargin
                        }
                        snapAnimator?.cancel()
                        snapAnimator = ValueAnimator.ofInt(params.x, targetEdgeX).apply {
                            duration = 280L
                            interpolator = OvershootInterpolator(1.05f)
                            addUpdateListener { anim ->
                                params.x = anim.animatedValue as Int
                                if (isShowing && container.isAttachedToWindow) {
                                    runCatching { windowManager.updateViewLayout(container, params) }
                                }
                            }
                            start()
                        }
                        if (!drawerExpanded) container.alpha = 0.72f
                    } else if (heldMs > 550L) {
                        runCatching {
                            context.startActivity(
                                Intent(context, MainActivity::class.java).addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                            )
                        }
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTapUpTime < 280L) {
                            // Double tap toggles 4-pill quick action drawer
                            singleTapRunnable?.let { mainHandler.removeCallbacks(it) }
                            singleTapRunnable = null
                            drawerExpanded = !drawerExpanded
                            drawer.visibility = if (drawerExpanded) View.VISIBLE else View.GONE
                            container.alpha = if (drawerExpanded) 1.0f else 0.72f
                            lastTapUpTime = 0L
                        } else {
                            lastTapUpTime = now
                            val pending = Runnable {
                                onOrbTapped()
                            }
                            singleTapRunnable = pending
                            mainHandler.postDelayed(pending, 290L)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            windowManager.addView(container, params)
            rootLayout = container
            orbView = orb
            contentColumn = rightCol
            bubbleLayout = bubble
            actionDrawer = drawer
            macroPill = recMacroPill
            abortBtnView = abortBtn
            recBadgeView = recBadge
            statusTextView = statusTv
            layoutParams = params
            isShowing = true
        }
    }

    fun updateState(
        coreMode: CoreMode,
        persona: AssistantPersona,
        message: String,
        showBubble: Boolean,
        isMacroRecording: Boolean = false,
        macroStepCount: Int = 0,
        visionSource: String = "",
        isWaitingForLoad: Boolean = false
    ) {
        if (!isShowing) return
        mainHandler.post {
            if (!isShowing) return@post
            orbView?.setVisualState(coreMode, persona, isMacroRecording)
            val displayMessage = when {
                isWaitingForLoad -> "⏳ Waiting for screen to load… ${message.take(45)}"
                visionSource.isNotBlank() &&
                    (coreMode == CoreMode.OBSERVING_SCREEN || coreMode == CoreMode.MULTITASKING) ->
                    "[$visionSource] $message"
                else -> message
            }
            statusTextView?.text = displayMessage
            val isRunningTask = coreMode == CoreMode.MULTITASKING ||
                coreMode == CoreMode.OBSERVING_SCREEN ||
                coreMode == CoreMode.THINKING
            abortBtnView?.visibility = if (isRunningTask) View.VISIBLE else View.GONE
            bubbleLayout?.visibility = if (showBubble || coreMode != CoreMode.IDLE || drawerExpanded) {
                View.VISIBLE
            } else {
                View.GONE
            }
            recBadgeView?.visibility = if (isMacroRecording) View.VISIBLE else View.GONE
            recBadgeView?.text = if (isMacroRecording) "REC · $macroStepCount" else "REC"
            macroPill?.text = if (isMacroRecording) "Stop ($macroStepCount)" else "Rec Macro"
            rootLayout?.alpha = if (coreMode != CoreMode.IDLE || drawerExpanded || isMacroRecording) 1.0f else 0.72f
        }
    }

    fun hide() {
        mainHandler.removeCallbacksAndMessages(null)
        singleTapRunnable = null
        snapAnimator?.cancel()
        snapAnimator = null
        mainHandler.post {
            orbView?.stopAnimation()
            rootLayout?.let { view ->
                if (view.isAttachedToWindow) {
                    runCatching { windowManager.removeViewImmediate(view) }
                        .onFailure { runCatching { windowManager.removeView(view) } }
                }
            }
            rootLayout = null
            orbView = null
            contentColumn = null
            bubbleLayout = null
            actionDrawer = null
            macroPill = null
            abortBtnView = null
            recBadgeView = null
            statusTextView = null
            layoutParams = null
            isShowing = false
        }
    }

    private var snapAnimator: ValueAnimator? = null

    private class HolographicOrbView(context: Context) : View(context) {
        private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.CYAN
        }
        private val portraitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val shaderMatrix = android.graphics.Matrix()

        private var accentColor: Int = 0xFF00E5FF.toInt()
        private var currentPersona: AssistantPersona = AssistantPersona.AETHER
        private var cachedBitmapPersona: AssistantPersona? = null
        private var cachedShaderRadius = 0f
        private var animating = false
        private var phase = 0f

        private val frameRunnable = object : Runnable {
            override fun run() {
                if (!animating) return
                phase += 0.16f
                invalidate()
                postDelayed(this, 45L)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            preparePortraitShader()
        }

        fun setVisualState(mode: CoreMode, persona: AssistantPersona, isMacroRecording: Boolean) {
            val personaChanged = currentPersona != persona
            currentPersona = persona
            if (personaChanged || portraitPaint.shader == null) {
                preparePortraitShader()
            }
            accentColor = when {
                isMacroRecording -> 0xFFFF1744.toInt()
                mode == CoreMode.ERROR -> 0xFFFF5252.toInt()
                mode == CoreMode.THINKING || mode == CoreMode.OBSERVING_SCREEN -> 0xFFFFB300.toInt()
                mode == CoreMode.MULTITASKING -> 0xFF00E676.toInt()
                else -> when (persona) {
                    AssistantPersona.AETHER -> 0xFF00E5FF.toInt()
                    AssistantPersona.ARIA -> 0xFFB388FF.toInt()
                }
            }
            val shouldAnimate = mode != CoreMode.IDLE || isMacroRecording
            if (shouldAnimate && !animating) {
                animating = true
                post(frameRunnable)
            } else if (!shouldAnimate && animating) {
                animating = false
                removeCallbacks(frameRunnable)
            }
            invalidate()
        }

        fun stopAnimation() {
            animating = false
            removeCallbacks(frameRunnable)
        }

        private fun preparePortraitShader() {
            if (width <= 0 || height <= 0) return
            val cx = width * 0.5f
            val cy = height * 0.5f
            val innerRadius = minOf(width, height) * 0.44f * 0.85f
            val diameter = (innerRadius * 2f).toInt().coerceAtLeast(16)
            if (cachedBitmapPersona == currentPersona && abs(cachedShaderRadius - innerRadius) < 1f && portraitPaint.shader != null) {
                return
            }
            runCatching {
                val resId = com.example.ui.components.avatarDrawableFor(currentPersona)
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                val raw = android.graphics.BitmapFactory.decodeResource(resources, resId, opts) ?: return
                val scaled = android.graphics.Bitmap.createScaledBitmap(raw, diameter, diameter, true)
                if (scaled !== raw) raw.recycle()
                val shader = android.graphics.BitmapShader(
                    scaled,
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP
                )
                shaderMatrix.reset()
                shaderMatrix.setTranslate(cx - innerRadius, cy - innerRadius)
                shader.setLocalMatrix(shaderMatrix)
                portraitPaint.shader = shader
                cachedBitmapPersona = currentPersona
                cachedShaderRadius = innerRadius
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width * 0.5f
            val cy = height * 0.5f
            val baseR = minOf(width, height) * 0.44f
            val pulse = if (animating) 1f + 0.07f * sin(phase) else 1f

            outerRingPaint.color = accentColor
            outerRingPaint.alpha = if (animating) 245 else 200
            canvas.drawCircle(cx, cy, baseR * pulse, outerRingPaint)

            val innerR = baseR * 0.85f
            if (portraitPaint.shader != null) {
                canvas.drawCircle(cx, cy, innerR, portraitPaint)
            }
        }
    }
}
