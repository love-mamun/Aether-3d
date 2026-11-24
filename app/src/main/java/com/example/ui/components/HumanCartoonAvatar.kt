package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ai.AssistantPersona
import com.example.ai.CoreMode
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sin

/**
 * Photorealistic 3D-perspective Holographic Human Portrait Avatar.
 * Hardware-accelerated GPU Image + isolated audio-reactive aura halo for zero-lag 60fps rendering.
 */
@Composable
fun HumanCartoonAvatar(
    coreMode: CoreMode,
    persona: AssistantPersona,
    audioAmplitudeFlow: StateFlow<Float>,
    headYawDeg: Float,
    headPitchDeg: Float,
    zoomScale: Float,
    modifier: Modifier = Modifier
) {
    val accent = when (coreMode) {
        CoreMode.ERROR -> Color(0xFFFF5252)
        CoreMode.THINKING, CoreMode.OBSERVING_SCREEN -> Color(0xFFFFB300)
        CoreMode.MULTITASKING -> Color(0xFF00E676)
        else -> when (persona) {
            AssistantPersona.AETHER -> Color(0xFF00E5FF)
            AssistantPersona.ARIA -> Color(0xFFB388FF)
        }
    }

    val infinite = rememberInfiniteTransition(label = "portraitLife")
    val breathPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breathPhase"
    )

    val breathY = sin(breathPhase) * 4f
    val clampedYaw = headYawDeg.coerceIn(-24f, 24f)
    val clampedPitch = headPitchDeg.coerceIn(-18f, 18f)
    val scaleFactor = (1f / zoomScale.coerceIn(0.65f, 1.65f)).coerceIn(0.78f, 1.32f)

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .testTag("human_cartoon_avatar")
    ) {
        val outerSize = (minOf(maxWidth, maxHeight) * 0.56f).coerceIn(160.dp, 248.dp)
        val portraitSize = outerSize * 0.83f
        val yLift = (maxHeight * 0.05f).coerceIn(12.dp, 42.dp)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(outerSize)
                .graphicsLayer {
                    cameraDistance = 16f * density
                    rotationY = clampedYaw
                    rotationX = -clampedPitch
                    translationY = breathY - yLift.toPx()
                    scaleX = scaleFactor
                    scaleY = scaleFactor
                }
        ) {
            // Isolated audio-reactive glow ring (prevents image/parent recomposition on RMS updates)
            AudioReactivePortraitHalo(
                audioAmplitudeFlow = audioAmplitudeFlow,
                coreMode = coreMode,
                accent = accent
            )

            // Crisp photorealistic portrait medallion
            Box(
                modifier = Modifier
                    .size(portraitSize)
                    .clip(CircleShape)
                    .background(Color(0xFF070C18))
                    .border(
                        width = 2.5.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.92f),
                                Color.White.copy(alpha = 0.45f),
                                accent.copy(alpha = 0.75f)
                            )
                        ),
                        shape = CircleShape
                    )
            ) {
                Crossfade(
                    targetState = avatarDrawableFor(persona),
                    animationSpec = tween(260),
                    label = "personaCrossfade"
                ) { drawableRes ->
                    Image(
                        painter = painterResource(id = drawableRes),
                        contentDescription = "${persona.displayName} realistic AI avatar portrait",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Subtle bottom holographic vignette for seamless cockpit blending
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    Color(0x88050A14)
                                )
                            )
                        )
                )
            }

            // Live voice equalizer / persona nameplate pill anchored on bottom rim
            Surface(
                color = Color(0xD90A101E),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-8).dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    if (coreMode == CoreMode.SPEAKING || coreMode == CoreMode.LISTENING) {
                        MiniVoiceBars(audioAmplitudeFlow = audioAmplitudeFlow, accent = accent)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                    }
                    Text(
                        text = persona.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFF3F7FF)
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioReactivePortraitHalo(
    audioAmplitudeFlow: StateFlow<Float>,
    coreMode: CoreMode,
    accent: Color
) {
    val audioAmp by audioAmplitudeFlow.collectAsStateWithLifecycle()
    val isActive = coreMode != CoreMode.IDLE
    val haloAlpha = if (isActive) (0.36f + audioAmp * 0.42f).coerceIn(0.25f, 0.85f) else 0.24f

    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = size.minDimension * (0.44f + audioAmp * 0.055f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = haloAlpha),
                    accent.copy(alpha = haloAlpha * 0.3f),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
}

@Composable
private fun MiniVoiceBars(
    audioAmplitudeFlow: StateFlow<Float>,
    accent: Color
) {
    val amp by audioAmplitudeFlow.collectAsStateWithLifecycle()
    val weights = listOf(0.55f, 1.0f, 0.72f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(14.dp)
    ) {
        weights.forEach { w ->
            val h = (4f + (amp * 10f * w)).coerceIn(4f, 14f).dp
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
    }
}

@DrawableRes
fun avatarDrawableFor(persona: AssistantPersona): Int = when (persona) {
    AssistantPersona.AETHER -> R.drawable.img_avatar_jarvis
    AssistantPersona.ARIA -> R.drawable.img_avatar_mayra
}
