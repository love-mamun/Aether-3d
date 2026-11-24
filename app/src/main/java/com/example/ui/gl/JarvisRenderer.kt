package com.example.ui.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.ai.AssistantPersona
import com.example.ai.CoreMode
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class JarvisRenderer : GLSurfaceView.Renderer {

    private var lineProgram = 0
    private var particleProgram = 0

    // Cached Line Program Handles
    private var lineMvpLoc = -1
    private var lineModelLoc = -1
    private var lineColorLoc = -1
    private var lineAlphaLoc = -1
    private var lineFadeLoc = -1
    private var linePosAttr = -1

    // Cached Particle Program Handles
    private var partMvpLoc = -1
    private var partTimeLoc = -1
    private var partAudioLoc = -1
    private var partColorLoc = -1
    private var partAlphaLoc = -1
    private var partPosAttr = -1
    private var partPhaseAttr = -1

    // Holographic Orbital Stage Meshes
    private lateinit var innerRingMesh: MeshBuffer
    private lateinit var outerRingMesh: MeshBuffer
    private lateinit var radarArcMesh: MeshBuffer
    private lateinit var spectrumBarMesh: MeshBuffer
    private lateinit var floorGridMesh: MeshBuffer
    private lateinit var particlesMesh: MeshBuffer

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var startTimeMs = System.currentTimeMillis()
    private var lastFrameTimeMs = System.currentTimeMillis()

    @Volatile var targetAudioLevel = 0f
    @Volatile var targetMode = CoreMode.IDLE
    @Volatile var targetPersona = AssistantPersona.AETHER

    @Volatile var userYawOffset = 0f
    @Volatile var userPitchOffset = 0f
    @Volatile var yawVelocity = 0f
    @Volatile var pitchVelocity = 0f
    @Volatile var userZoomFactor = 1f
    @Volatile var tiltParallaxX = 0f
    @Volatile var tiltParallaxY = 0f

    private var smoothAudio = 0f
    private var smoothSpin = 1f
    private val currentPrimary = floatArrayOf(0f, 0.90f, 1.0f)
    private val currentSecondary = floatArrayOf(0.15f, 0.45f, 1.0f)
    private var smoothCameraDistance = 4.1f
    private var ringAngleA = 0f
    private var ringAngleB = 0f
    private var ringAngleC = 0f

    private val amberColor = floatArrayOf(1.0f, 0.70f, 0.0f)
    private val orangeColor = floatArrayOf(1.0f, 0.35f, 0.0f)
    private val cyanScanColor = floatArrayOf(0.0f, 0.90f, 1.0f)
    private val mintColor = floatArrayOf(0.0f, 1.0f, 0.85f)
    private val emeraldColor = floatArrayOf(0.0f, 0.92f, 0.55f)
    private val coralColor = floatArrayOf(1.0f, 0.32f, 0.32f)
    private val darkCoralColor = floatArrayOf(0.65f, 0.1f, 0.1f)

    fun addOrbitDrag(dx: Float, dy: Float) {
        userYawOffset += dx * 0.35f
        userPitchOffset = (userPitchOffset + dy * 0.25f).coerceIn(-55f, 55f)
        yawVelocity = dx * 0.22f
        pitchVelocity = dy * 0.14f
    }

    fun applyZoomScale(scaleFactor: Float) {
        userZoomFactor = (userZoomFactor / scaleFactor).coerceIn(0.65f, 1.65f)
    }

    fun resetCamera() {
        userYawOffset = 0f
        userPitchOffset = 0f
        yawVelocity = 0f
        pitchVelocity = 0f
        userZoomFactor = 1f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.016f, 0.027f, 0.055f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        lineProgram = ShaderUtils.createProgram(
            ShaderUtils.LINE_VERTEX_SHADER,
            ShaderUtils.LINE_FRAGMENT_SHADER
        )
        if (lineProgram != 0) {
            lineMvpLoc = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
            lineModelLoc = GLES20.glGetUniformLocation(lineProgram, "uModelMatrix")
            lineColorLoc = GLES20.glGetUniformLocation(lineProgram, "uColor")
            lineAlphaLoc = GLES20.glGetUniformLocation(lineProgram, "uAlpha")
            lineFadeLoc = GLES20.glGetUniformLocation(lineProgram, "uFadeRadius")
            linePosAttr = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        }

        particleProgram = ShaderUtils.createProgram(
            ShaderUtils.PARTICLE_VERTEX_SHADER,
            ShaderUtils.PARTICLE_FRAGMENT_SHADER
        )
        if (particleProgram != 0) {
            partMvpLoc = GLES20.glGetUniformLocation(particleProgram, "uMVPMatrix")
            partTimeLoc = GLES20.glGetUniformLocation(particleProgram, "uTime")
            partAudioLoc = GLES20.glGetUniformLocation(particleProgram, "uAudioLevel")
            partColorLoc = GLES20.glGetUniformLocation(particleProgram, "uColor")
            partAlphaLoc = GLES20.glGetUniformLocation(particleProgram, "uAlpha")
            partPosAttr = GLES20.glGetAttribLocation(particleProgram, "aPosition")
            partPhaseAttr = GLES20.glGetAttribLocation(particleProgram, "aPhase")
        }

        innerRingMesh = GeometryBuilder.buildSegmentedArcRing(radius = 1.24f, segments = 48, gapEvery = 6)
        outerRingMesh = GeometryBuilder.buildSegmentedArcRing(radius = 1.48f, segments = 48, gapEvery = 8)
        radarArcMesh = GeometryBuilder.buildSegmentedArcRing(radius = 1.72f, segments = 40, gapEvery = 5)
        spectrumBarMesh = GeometryBuilder.buildUnitPrismTriangles()
        floorGridMesh = GeometryBuilder.buildPerspectiveFloorGrid(halfExtent = 3.6f, step = 0.9f, yOffset = -1.35f)
        particlesMesh = GeometryBuilder.buildOrbitalParticles(85)

        startTimeMs = System.currentTimeMillis()
        lastFrameTimeMs = startTimeMs
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.coerceAtLeast(1).toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 44f, ratio, 0.2f, 30f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.currentTimeMillis()
        val dt = ((now - lastFrameTimeMs) / 1000f).coerceIn(0.001f, 0.05f)
        lastFrameTimeMs = now
        val elapsed = ((now - startTimeMs) / 1000f) % 62.831853f

        smoothAudio += (targetAudioLevel - smoothAudio) * 0.24f

        val targetP: FloatArray
        val targetS: FloatArray
        val targetSpin: Float
        val targetDist: Float

        when (targetMode) {
            CoreMode.IDLE -> {
                targetP = targetPersona.primaryRgb
                targetS = targetPersona.secondaryRgb
                targetSpin = 1.0f
                targetDist = 4.15f
            }
            CoreMode.LISTENING -> {
                targetP = targetPersona.primaryRgb
                targetS = mintColor
                targetSpin = 1.85f
                targetDist = 3.70f
            }
            CoreMode.OBSERVING_SCREEN -> {
                targetP = cyanScanColor
                targetS = amberColor
                targetSpin = 2.8f
                targetDist = 3.85f
            }
            CoreMode.THINKING -> {
                targetP = amberColor
                targetS = orangeColor
                targetSpin = 3.4f
                targetDist = 3.90f
            }
            CoreMode.MULTITASKING -> {
                targetP = emeraldColor
                targetS = targetPersona.primaryRgb
                targetSpin = 3.8f
                targetDist = 3.85f
            }
            CoreMode.SPEAKING -> {
                targetP = targetPersona.primaryRgb
                targetS = mintColor
                targetSpin = 2.0f
                targetDist = 3.65f
            }
            CoreMode.ERROR -> {
                targetP = coralColor
                targetS = darkCoralColor
                targetSpin = 0.7f
                targetDist = 4.20f
            }
        }

        for (i in 0..2) {
            currentPrimary[i] += (targetP[i] - currentPrimary[i]) * 0.09f
            currentSecondary[i] += (targetS[i] - currentSecondary[i]) * 0.09f
        }
        smoothSpin += (targetSpin - smoothSpin) * 0.07f
        smoothCameraDistance += ((targetDist * userZoomFactor) - smoothCameraDistance) * 0.09f

        userYawOffset += yawVelocity
        userPitchOffset = (userPitchOffset + pitchVelocity).coerceIn(-55f, 55f)
        yawVelocity *= 0.90f
        pitchVelocity *= 0.90f

        ringAngleA += dt * 24f * smoothSpin
        ringAngleB -= dt * 32f * smoothSpin
        ringAngleC += dt * 18f * smoothSpin

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val camYawRad = Math.toRadians((userYawOffset + tiltParallaxX * 14f).toDouble())
        val camPitchRad = Math.toRadians((10f + userPitchOffset + tiltParallaxY * 10f).toDouble())
        val camX = (smoothCameraDistance * cos(camPitchRad) * sin(camYawRad)).toFloat()
        val camY = (smoothCameraDistance * sin(camPitchRad)).toFloat() - 0.15f
        val camZ = (smoothCameraDistance * cos(camPitchRad) * cos(camYawRad)).toFloat()

        // Frame avatar bust cleanly in upper-middle open cockpit above VoiceDock/HUD cards
        Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, 0f, -0.38f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 1. 3D Perspective Holographic Floor Grid
        Matrix.setIdentityM(modelMatrix, 0)
        drawLines(floorGridMesh, currentPrimary, 0.22f, 4.2f, 1.4f)

        // 2. 28-Bar 3D Radial Voice Spectrum Halo framing the character
        drawAudioSpectrumRing(elapsed)

        // 3. Tri-Axial Holographic Halo & Scanner Rings orbiting the 3D Avatar
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, -0.15f, 0f)
        Matrix.rotateM(modelMatrix, 0, 22f, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, ringAngleA, 0f, 1f, 0.2f)
        drawLines(innerRingMesh, currentPrimary, 0.58f, 0f, 2.0f)

        Matrix.setIdentityM(modelMatrix, 0)
        val scanOffset = if (targetMode == CoreMode.OBSERVING_SCREEN) 0.45f * sin(elapsed * 4.5f) else -0.15f
        Matrix.translateM(modelMatrix, 0, 0f, scanOffset, 0f)
        Matrix.rotateM(modelMatrix, 0, if (targetMode == CoreMode.OBSERVING_SCREEN) 0f else -46f, 0.2f, 0f, 1f)
        Matrix.rotateM(modelMatrix, 0, ringAngleB, 0.1f, 1f, 0f)
        drawLines(outerRingMesh, currentSecondary, 0.54f, 0f, 1.8f)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, -0.15f, 0f)
        Matrix.rotateM(modelMatrix, 0, 68f, 1f, 0.3f, 0f)
        Matrix.rotateM(modelMatrix, 0, ringAngleC, 0f, 1f, 0f)
        drawLines(radarArcMesh, currentPrimary, 0.38f, 0f, 1.5f)

        // 4. 140 Orbital Data Particles
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, elapsed * 10f * smoothSpin, 0f, 1f, 0.1f)
        drawParticles(elapsed)
    }

    private fun drawAudioSpectrumRing(elapsed: Float) {
        if (lineProgram == 0) return
        GLES20.glUseProgram(lineProgram)

        spectrumBarMesh.buffer.position(0)
        GLES20.glEnableVertexAttribArray(linePosAttr)
        GLES20.glVertexAttribPointer(linePosAttr, 3, GLES20.GL_FLOAT, false, 12, spectrumBarMesh.buffer)
        GLES20.glUniform3fv(lineColorLoc, 1, currentPrimary, 0)
        GLES20.glUniform1f(lineAlphaLoc, 0.78f)
        GLES20.glUniform1f(lineFadeLoc, 0f)

        val barCount = 16
        val ringRadius = 1.16f
        for (i in 0 until barCount) {
            val angleDeg = (i.toFloat() / barCount) * 360f + elapsed * 14f
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val wave = 0.5f + 0.5f * sin(elapsed * 5.2f + i * 0.65f)
            val height = 0.06f + (smoothAudio * 0.44f + 0.06f) * wave

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, 0f, -0.52f, 0f)
            Matrix.rotateM(modelMatrix, 0, 10f, 1f, 0f, 0.3f)
            Matrix.translateM(
                modelMatrix,
                0,
                (cos(angleRad) * ringRadius).toFloat(),
                -height * 0.5f,
                (sin(angleRad) * ringRadius).toFloat()
            )
            Matrix.rotateM(modelMatrix, 0, -angleDeg, 0f, 1f, 0f)
            Matrix.scaleM(modelMatrix, 0, 1.25f, height, 1.25f)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

            GLES20.glUniformMatrix4fv(lineMvpLoc, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(lineModelLoc, 1, false, modelMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, spectrumBarMesh.vertexCount)
        }
        GLES20.glDisableVertexAttribArray(linePosAttr)
    }

    private fun drawLines(
        mesh: MeshBuffer,
        color: FloatArray,
        alpha: Float,
        fadeRadius: Float,
        lineWidth: Float
    ) {
        if (lineProgram == 0) return
        GLES20.glUseProgram(lineProgram)
        GLES20.glLineWidth(lineWidth)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(lineMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(lineModelLoc, 1, false, modelMatrix, 0)
        GLES20.glUniform3fv(lineColorLoc, 1, color, 0)
        GLES20.glUniform1f(lineAlphaLoc, alpha)
        GLES20.glUniform1f(lineFadeLoc, fadeRadius)

        mesh.buffer.position(0)
        GLES20.glEnableVertexAttribArray(linePosAttr)
        GLES20.glVertexAttribPointer(linePosAttr, 3, GLES20.GL_FLOAT, false, 12, mesh.buffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, mesh.vertexCount)
        GLES20.glDisableVertexAttribArray(linePosAttr)
    }

    private fun drawParticles(elapsed: Float) {
        if (particleProgram == 0) return
        GLES20.glUseProgram(particleProgram)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(partMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(partTimeLoc, elapsed)
        GLES20.glUniform1f(partAudioLoc, smoothAudio)
        GLES20.glUniform3fv(partColorLoc, 1, currentPrimary, 0)
        GLES20.glUniform1f(partAlphaLoc, 0.85f)

        particlesMesh.buffer.position(0)
        GLES20.glEnableVertexAttribArray(partPosAttr)
        GLES20.glVertexAttribPointer(partPosAttr, 3, GLES20.GL_FLOAT, false, 16, particlesMesh.buffer)

        particlesMesh.buffer.position(3)
        GLES20.glEnableVertexAttribArray(partPhaseAttr)
        GLES20.glVertexAttribPointer(partPhaseAttr, 1, GLES20.GL_FLOAT, false, 16, particlesMesh.buffer)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, particlesMesh.vertexCount)

        GLES20.glDisableVertexAttribArray(partPosAttr)
        GLES20.glDisableVertexAttribArray(partPhaseAttr)
    }
}
