package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.CoreMode
import com.example.core.AssistantUiState
import com.example.data.local.WakeTriggerMode
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.SmokedGlass
import com.example.ui.theme.SmokedGlassElevated
import com.example.ui.theme.SolarGold
import com.example.ui.theme.StealthEmerald
import com.example.ui.theme.VoidObsidian

@Composable
fun TacticalCornerReticles(
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val len = 26.dp.toPx()
        val inset = 14.dp.toPx()
        val stroke = 1.6.dp.toPx()
        val c = accentColor.copy(alpha = 0.42f)

        // Top-left
        drawLine(c, Offset(inset, inset), Offset(inset + len, inset), stroke)
        drawLine(c, Offset(inset, inset), Offset(inset, inset + len), stroke)
        // Top-right
        drawLine(c, Offset(size.width - inset, inset), Offset(size.width - inset - len, inset), stroke)
        drawLine(c, Offset(size.width - inset, inset), Offset(size.width - inset, inset + len), stroke)
        // Bottom-left
        drawLine(c, Offset(inset, size.height - inset), Offset(inset + len, size.height - inset), stroke)
        drawLine(c, Offset(inset, size.height - inset), Offset(inset, size.height - inset - len), stroke)
        // Bottom-right
        drawLine(c, Offset(size.width - inset, size.height - inset), Offset(size.width - inset - len, size.height - inset), stroke)
        drawLine(c, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - inset - len), stroke)
    }
}

@Composable
fun TopTelemetryBar(
    uiState: AssistantUiState,
    onCyclePersona: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenApiSettings: () -> Unit = {},
    onOpenVoiceStudio: () -> Unit = {},
    onOpenCameraLens: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val perms = uiState.permissionSnapshot

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Persona + Live Status Telemetry
        Surface(
            color = SmokedGlass,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("persona_cycle_button")
                .clickable(onClick = onCyclePersona)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = when {
                                uiState.coreMode == CoreMode.ERROR -> AlertCoral
                                uiState.coreMode == CoreMode.THINKING || uiState.coreMode == CoreMode.OBSERVING_SCREEN -> SolarGold
                                uiState.coreMode == CoreMode.MULTITASKING || uiState.isWakeWordArmed -> StealthEmerald
                                else -> accent
                            },
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = uiState.preferences.persona.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = HudTextPrimary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (uiState.telemetry.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                            contentDescription = "Battery level",
                            tint = HudTextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "${uiState.telemetry.batteryPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = HudTextSecondary
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Wifi,
                            contentDescription = "Network status",
                            tint = HudTextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = uiState.telemetry.networkLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = HudTextSecondary
                        )
                    }
                }
            }
        }

        // Right: Vision Lens + Compact Permissions Readiness + SQLite Vault
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = SmokedGlass,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("camera_lens_button")
                    .clickable(onClick = onOpenCameraLens)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = "Open Camera Vision Lens",
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            val allGranted = perms.allEnabled
            val permColor = if (allGranted) StealthEmerald else SolarGold
            Surface(
                color = SmokedGlass,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, permColor.copy(alpha = 0.55f)),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("permissions_button")
                    .clickable(onClick = onOpenPermissions)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (allGranted) Icons.Filled.VerifiedUser else Icons.Filled.Shield,
                        contentDescription = "Permissions Hub",
                        tint = permColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${perms.grantedCount}/${perms.totalCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = permColor
                    )
                }
            }

            val vaultLocked = uiState.preferences.biometricVaultLockEnabled && !uiState.isVaultUnlocked
            Surface(
                color = SmokedGlass,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    if (vaultLocked) SolarGold.copy(alpha = 0.55f) else accent.copy(alpha = 0.4f)
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("vault_button")
                    .clickable(onClick = onOpenVault)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (vaultLocked) Icons.Filled.Lock else Icons.Filled.FolderSpecial,
                        contentDescription = if (vaultLocked) "Open Encrypted Locked Vault" else "Open SQLite Vault",
                        tint = if (vaultLocked) SolarGold else accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "VAULT",
                        style = MaterialTheme.typography.labelMedium,
                        color = HudTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun CoreStatusAndReplyOverlay(
    uiState: AssistantUiState,
    onAbortTasks: () -> Unit,
    onConfirmSensitive: () -> Unit,
    onDismissSensitive: () -> Unit,
    onOpenApiSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Core Mode Status Badge + Wake-Word / Assistant Button / Macro Recording / Echo Shield Indicator
        val isButtonOnlyIdle = uiState.preferences.wakeTriggerMode == WakeTriggerMode.ASSISTANT_BUTTON_ONLY &&
            uiState.coreMode == CoreMode.IDLE
        val badgeText = when {
            uiState.isMacroRecording -> "REC MACRO · ${uiState.macroStepCount} STEPS"
            uiState.echoGuardActive -> "ECHO SHIELD · AEC GUARD"
            isButtonOnlyIdle -> "ASSISTANT READY · TAP ORB / TILE"
            uiState.isWakeWordArmed && uiState.coreMode == CoreMode.IDLE ->
                "SAY \"${uiState.preferences.customWakeWord.uppercase()}\" · ${uiState.kwsPackStatusLabel.uppercase()}"
            else -> uiState.coreMode.statusLabel
        }
        val badgeColor = when {
            uiState.isMacroRecording -> AlertCoral
            uiState.echoGuardActive -> StealthEmerald
            isButtonOnlyIdle -> accent
            uiState.isWakeWordArmed && uiState.coreMode == CoreMode.IDLE -> StealthEmerald
            else -> accent
        }
        Surface(
            color = SmokedGlass,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.42f))
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelMedium,
                color = badgeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }

        // Compact AI Model, Active Vision Source, Token Saver & Audio Filter Telemetry Chips (hidden when idle)
        val filterDiag = uiState.audioFilterDiagnostics
        val showVisionChip = uiState.screenVisionSourceLabel.isNotBlank() &&
            (uiState.coreMode == CoreMode.OBSERVING_SCREEN ||
                uiState.coreMode == CoreMode.MULTITASKING ||
                uiState.reActWaitingForLoad ||
                uiState.isDeepVisionActive)
        val showTokensSavedChip = uiState.estimatedTokensSavedSession > 0L
        if (uiState.activeAiModelLabel.isNotBlank() || filterDiag.isCapturing || showVisionChip || showTokensSavedChip) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showVisionChip) {
                    Surface(
                        color = SmokedGlass,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, SolarGold.copy(alpha = 0.55f)),
                        modifier = Modifier.testTag("screen_vision_source_chip")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Visibility,
                                contentDescription = "Active background vision source",
                                tint = SolarGold,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = uiState.screenVisionSourceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = SolarGold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (showTokensSavedChip) {
                    Surface(
                        color = SmokedGlass,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, StealthEmerald.copy(alpha = 0.55f)),
                        modifier = Modifier.testTag("tokens_saved_chip")
                    ) {
                        Text(
                            text = "SAVED ~${uiState.estimatedTokensSavedSession} TOK",
                            style = MaterialTheme.typography.labelSmall,
                            color = StealthEmerald,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
                if (filterDiag.isCapturing) {
                    Surface(
                        color = SmokedGlass,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (filterDiag.isSpeechDetected) StealthEmerald.copy(alpha = 0.65f) else accent.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.testTag("audio_filter_status_chip")
                    ) {
                        Text(
                            text = if (filterDiag.isSpeechDetected) {
                                "VOICE ISOLATED · ${filterDiag.effects.summaryBadge()}"
                            } else {
                                filterDiag.effects.summaryBadge()
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (filterDiag.isSpeechDetected) StealthEmerald else accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
                if (uiState.activeAiModelLabel.isNotBlank()) {
                    Surface(
                        color = SmokedGlass,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = uiState.activeAiModelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = HudTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Live Partial Transcript
        AnimatedVisibility(
            visible = uiState.partialTranscript.isNotBlank(),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut()
        ) {
            Surface(
                color = SmokedGlassElevated,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
            ) {
                Text(
                    text = "\"${uiState.partialTranscript}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HudTextPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // Guarded Sensitive Confirmation Card
        AnimatedVisibility(visible = uiState.pendingConfirmationSteps.isNotEmpty()) {
            Surface(
                color = SmokedGlassElevated,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, SolarGold.copy(alpha = 0.75f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "GUARDED CONFIRMATION REQUIRED",
                        style = MaterialTheme.typography.labelMedium,
                        color = SolarGold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = uiState.pendingConfirmationReply,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HudTextPrimary
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onConfirmSensitive,
                            colors = ButtonDefaults.buttonColors(containerColor = StealthEmerald, contentColor = VoidObsidian),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("confirm_sensitive_button")
                        ) {
                            Text("Confirm & Run")
                        }
                        OutlinedButton(
                            onClick = onDismissSensitive,
                            border = BorderStroke(1.dp, AlertCoral),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cancel_sensitive_button")
                        ) {
                            Text("Cancel", color = AlertCoral)
                        }
                    }
                }
            }
        }

        // Unified ReAct Multi-Step Progress Card
        val showReActCard = uiState.reActSubGoals.isNotEmpty() &&
            (uiState.coreMode == CoreMode.OBSERVING_SCREEN ||
                uiState.coreMode == CoreMode.MULTITASKING ||
                uiState.reActWaitingForLoad)
        AnimatedVisibility(visible = showReActCard) {
            Surface(
                color = SmokedGlassElevated,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, StealthEmerald.copy(alpha = 0.55f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("react_progress_card")
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val stepIndexDisplay = (uiState.reActActiveSubGoalIndex + 1).coerceAtMost(uiState.reActSubGoals.size)
                        Text(
                            text = "AUTONOMOUS GOAL ($stepIndexDisplay/${uiState.reActSubGoals.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = StealthEmerald
                        )
                        IconButton(
                            onClick = onAbortTasks,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("react_abort_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Abort autonomous goal",
                                tint = AlertCoral,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    if (uiState.reActWaitingForLoad) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .testTag("react_waiting_indicator")
                        ) {
                            CircularProgressIndicator(
                                color = SolarGold,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Waiting for screen to load...",
                                style = MaterialTheme.typography.labelMedium,
                                color = SolarGold
                            )
                        }
                    } else if (uiState.reActProgressLabel.isNotBlank()) {
                        Text(
                            text = uiState.reActProgressLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = HudTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        uiState.reActSubGoals.forEachIndexed { idx, goal ->
                            val isDone = idx < uiState.reActActiveSubGoalIndex
                            val isCurrent = idx == uiState.reActActiveSubGoalIndex
                            val pillColor = when {
                                isDone -> StealthEmerald
                                isCurrent -> SolarGold
                                else -> HudTextSecondary
                            }
                            Surface(
                                color = pillColor.copy(alpha = if (isCurrent) 0.16f else 0.08f),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, pillColor.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = "${idx + 1}. $goal",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = pillColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Holographic Live Task DAG Graph Card
        AnimatedVisibility(visible = uiState.activeTasks.isNotEmpty()) {
            LiveTaskGraphCard(
                tasks = uiState.activeTasks,
                coreMode = uiState.coreMode,
                matchedPlaybookLabel = uiState.matchedPlaybookLabel,
                lastAutoTriggerLabel = uiState.lastAutoTriggerLabel,
                onAbortTasks = onAbortTasks
            )
        }

        // Latest Response / Live Streaming / Error Card
        val errorMsg = uiState.errorMessage
        Surface(
            color = SmokedGlass,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(
                1.dp,
                if (errorMsg != null) AlertCoral.copy(alpha = 0.7f) else accent.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, VoidObsidian.copy(alpha = 0.25f))
                    ),
                    shape = RoundedCornerShape(18.dp)
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (errorMsg != null) {
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AlertCoral,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenApiSettings,
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("configure_nvidia_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = "Configure AI API Keys",
                            tint = accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Configure Gemini / NVIDIA API", color = accent)
                    }
                } else {
                    if (uiState.lastUserPrompt.isNotBlank()) {
                        Text(
                            text = "YOU: ${uiState.lastUserPrompt}",
                            style = MaterialTheme.typography.labelSmall,
                            color = HudTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    val nimReady = uiState.isNvidiaConfigured || uiState.preferences.nvidiaApiKey.isNotBlank()
                    val displayText = when {
                        uiState.streamingReply.isNotBlank() -> uiState.streamingReply
                        uiState.coreMode == CoreMode.THINKING -> if (uiState.preferences.preferNvidiaPrimary && nimReady) {
                            "Streaming from NVIDIA NIM..."
                        } else {
                            "Streaming neural plan..."
                        }
                        uiState.coreMode == CoreMode.OBSERVING_SCREEN -> "Observing screen..."
                        else -> uiState.latestReply
                    }
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = HudTextPrimary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
