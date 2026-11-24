package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.CoreMode
import com.example.core.AssistantUiState
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.SmokedGlass
import com.example.ui.theme.SmokedGlassElevated
import com.example.ui.theme.StealthEmerald
import com.example.ui.theme.VoidObsidian
import kotlinx.coroutines.flow.StateFlow

private val QuickPrompts = listOf(
    "Flashlight on",
    "DND on",
    "Call Ma",
    "Open YouTube",
    "Weather here",
    "Battery status"
)

@Composable
fun VoiceDock(
    uiState: AssistantUiState,
    audioAmplitudeFlow: StateFlow<Float>,
    onMicClick: () -> Unit,
    onSubmitText: (String) -> Unit,
    onToggleBackgroundService: () -> Unit,
    onToggleTts: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onToggleLiveDuplex: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    var showTextBar by remember { mutableStateOf(true) }
    var textInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Live Full-Duplex Active Pill Banner
        AnimatedVisibility(visible = uiState.isLiveDuplexActive) {
            Surface(
                color = StealthEmerald.copy(alpha = 0.16f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, StealthEmerald.copy(alpha = 0.7f)),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("live_duplex_banner")
                    .clickable(onClick = onToggleLiveDuplex)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = "Live Full-Duplex active",
                        tint = StealthEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "LIVE DUPLEX · ${uiState.liveDuplexLabel.ifBlank { "Active" }} · Tap to End",
                        style = MaterialTheme.typography.labelMedium,
                        color = StealthEmerald
                    )
                }
            }
        }

        // Horizontal Quick-Command Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = if (uiState.isLiveDuplexActive) StealthEmerald else SmokedGlass,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (uiState.isLiveDuplexActive) StealthEmerald else accent.copy(alpha = 0.55f)),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("live_duplex_toggle")
                    .clickable(onClick = onToggleLiveDuplex)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = "Toggle Live Full-Duplex Conversation",
                        tint = if (uiState.isLiveDuplexActive) VoidObsidian else accent,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (uiState.isLiveDuplexActive) "END LIVE" else "LIVE CALL",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.isLiveDuplexActive) VoidObsidian else accent
                    )
                }
            }
            QuickPrompts.forEach { sample ->
                Surface(
                    color = SmokedGlass,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable { onSubmitText(sample) }
                ) {
                    Text(
                        text = sample,
                        style = MaterialTheme.typography.labelMedium,
                        color = HudTextPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Command Text Bar (ready out of the box, collapsible for pure 3D mode)
        AnimatedVisibility(visible = showTextBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = {
                        Text(
                            "Command ${uiState.preferences.persona.displayName} (Bangla / English)...",
                            color = HudTextSecondary
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (textInput.isNotBlank()) {
                                onSubmitText(textInput)
                                textInput = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = accent.copy(alpha = 0.35f),
                        focusedTextColor = HudTextPrimary,
                        unfocusedTextColor = HudTextPrimary,
                        focusedContainerColor = SmokedGlassElevated,
                        unfocusedContainerColor = SmokedGlass
                    ),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("command_input")
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = accent,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("send_command_button")
                        .clickable {
                            if (textInput.isNotBlank()) {
                                onSubmitText(textInput)
                                textInput = ""
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send text command",
                            tint = VoidObsidian
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Sculpted Arc Reactor Dock (Settings cleanly anchored at Bottom-Right corner)
        Surface(
            color = SmokedGlassElevated,
            shape = RoundedCornerShape(30.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { showTextBar = !showTextBar },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("keyboard_toggle_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Keyboard,
                        contentDescription = "Toggle text command input",
                        tint = if (showTextBar) accent else HudTextSecondary
                    )
                }

                IconButton(
                    onClick = onToggleTts,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("tts_toggle_button")
                ) {
                    Icon(
                        imageVector = if (uiState.preferences.ttsEnabled) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        contentDescription = "Toggle spoken responses",
                        tint = if (uiState.preferences.ttsEnabled) accent else HudTextSecondary
                    )
                }

                // Isolated Mic Pulse Composable (prevents dock/screen recomposition on RMS updates)
                val isListening = uiState.coreMode == CoreMode.LISTENING
                val isSpeaking = uiState.coreMode == CoreMode.SPEAKING
                val micBorderColor = when {
                    isListening -> accent
                    isSpeaking -> StealthEmerald
                    uiState.isWakeWordArmed -> StealthEmerald
                    else -> accent
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(68.dp)
                ) {
                    if (isListening || uiState.isWakeWordArmed) {
                        MicPulseHalo(
                            audioAmplitudeFlow = audioAmplitudeFlow,
                            accent = if (uiState.isWakeWordArmed && !isListening) StealthEmerald else accent
                        )
                    }
                    Surface(
                        color = if (isListening) accent else VoidObsidian,
                        shape = CircleShape,
                        border = BorderStroke(2.dp, micBorderColor),
                        modifier = Modifier
                            .size(56.dp)
                            .testTag("mic_button")
                            .clickable(onClick = onMicClick)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = when {
                                    isListening -> "Stop listening"
                                    isSpeaking -> "Barge in and speak"
                                    else -> "Start voice command"
                                },
                                tint = if (isListening) VoidObsidian else micBorderColor,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onToggleBackgroundService,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("background_agent_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.HeadsetMic,
                        contentDescription = "Toggle Background Voice & Floating Overlay",
                        tint = if (uiState.backgroundServiceActive) StealthEmerald else HudTextSecondary
                    )
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("bottom_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Open Settings",
                        tint = accent
                    )
                }
            }
        }
    }
}

@Composable
private fun MicPulseHalo(
    audioAmplitudeFlow: StateFlow<Float>,
    accent: Color
) {
    val audioAmp by audioAmplitudeFlow.collectAsStateWithLifecycle()
    val pulseTransition = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .size(70.dp)
            .scale(pulseScale + (audioAmp * 0.16f))
            .background(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.45f), Color.Transparent)
                ),
                shape = CircleShape
            )
    )
}
