package com.example.ui.gl

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.ai.AssistantPersona
import com.example.ai.CoreMode
import com.example.ui.components.HumanCartoonAvatar
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

@Composable
fun Aether3DViewport(
    coreMode: CoreMode,
    persona: AssistantPersona,
    audioAmplitudeFlow: StateFlow<Float>,
    onCoreTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember { JarvisRenderer() }
    var avatarYaw by remember { mutableFloatStateOf(0f) }
    var avatarPitch by remember { mutableFloatStateOf(0f) }
    var avatarZoom by remember { mutableFloatStateOf(1f) }
    var glTextureView by remember { mutableStateOf<GLTextureView?>(null) }
    var wasPaused by remember { mutableStateOf(false) }

    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val rotSensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    val sensorListener = remember(renderer) {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.values.size < 2) return
                if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    renderer.tiltParallaxX = event.values[1].coerceIn(-0.4f, 0.4f)
                    renderer.tiltParallaxY = event.values[0].coerceIn(-0.4f, 0.4f)
                } else {
                    renderer.tiltParallaxX = (-event.values[0] / 25f).coerceIn(-0.4f, 0.4f)
                    renderer.tiltParallaxY = (event.values[1] / 25f).coerceIn(-0.4f, 0.4f)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    LaunchedEffect(coreMode, persona) {
        renderer.targetMode = coreMode
        renderer.targetPersona = persona
    }

    // Collect amplitude directly into renderer without triggering Compose recomposition
    LaunchedEffect(audioAmplitudeFlow) {
        audioAmplitudeFlow.collectLatest { amp ->
            renderer.targetAudioLevel = amp
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (wasPaused) {
            glTextureView?.onResume()
            wasPaused = false
        }
        if (rotSensor != null) {
            sensorManager?.registerListener(sensorListener, rotSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        sensorManager?.unregisterListener(sensorListener)
        if (!wasPaused) {
            glTextureView?.onPause()
            wasPaused = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            sensorManager?.unregisterListener(sensorListener)
            if (!wasPaused) {
                glTextureView?.onPause()
                wasPaused = true
            }
            glTextureView = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    renderer.addOrbitDrag(pan.x, pan.y)
                    avatarYaw = (avatarYaw + pan.x * 0.14f).coerceIn(-28f, 28f)
                    avatarPitch = (avatarPitch + pan.y * 0.10f).coerceIn(-20f, 20f)
                    if (zoom != 1f) {
                        renderer.applyZoomScale(zoom)
                        avatarZoom = (avatarZoom / zoom).coerceIn(0.65f, 1.65f)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onCoreTapped() },
                    onDoubleTap = {
                        renderer.resetCamera()
                        avatarYaw = 0f
                        avatarPitch = 0f
                        avatarZoom = 1f
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                GLTextureView(ctx).apply {
                    setRenderer(renderer)
                }.also { glTextureView = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        HumanCartoonAvatar(
            coreMode = coreMode,
            persona = persona,
            audioAmplitudeFlow = audioAmplitudeFlow,
            headYawDeg = avatarYaw + (renderer.tiltParallaxX * 18f),
            headPitchDeg = avatarPitch + (renderer.tiltParallaxY * 14f),
            zoomScale = avatarZoom
        )
    }
}
