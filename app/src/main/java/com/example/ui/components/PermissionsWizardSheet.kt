package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ai.SpeechLocaleOption
import com.example.core.AssistantUiState
import com.example.system.PermissionItemStatus
import com.example.ui.theme.DeepCockpit
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.SmokedGlass
import com.example.ui.theme.SolarGold
import com.example.ui.theme.StealthEmerald
import com.example.ui.theme.VoidObsidian

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsWizardSheet(
    uiState: AssistantUiState,
    onDismiss: () -> Unit,
    onRequestAllRuntimePermissions: () -> Unit,
    onLaunchSpecialSettings: (String) -> Unit,
    onToggleAutonomousMode: () -> Unit,
    onToggleContinuousLoop: () -> Unit,
    onToggleSplitScreen: () -> Unit,
    onToggleWakeWord: () -> Unit = {},
    onToggleBargeIn: () -> Unit = {},
    onToggleBiometricVaultLock: () -> Unit = {},
    onSelectLocale: (SpeechLocaleOption) -> Unit,
    onOpenNvidiaApiSettings: () -> Unit = {},
    onOpenVoiceStudio: () -> Unit = {},
    onToggleDeepScreenVision: () -> Unit = {},
    onRequestDefaultAssistant: () -> Unit = { onLaunchSpecialSettings("assistant_role") }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = MaterialTheme.colorScheme.primary
    val snapshot = uiState.permissionSnapshot
    val items = snapshot.toItemList()
    val nextPendingSpecial = items.firstOrNull { it.isSpecialSettings && !it.isGranted }
    val hasPendingRuntime = items.any { !it.isSpecialSettings && !it.isGranted }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepCockpit,
        contentColor = HudTextPrimary
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = "Trusted Permissions Hub",
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "TRUSTED PERMISSIONS HUB",
                                style = MaterialTheme.typography.titleMedium,
                                color = HudTextPrimary
                            )
                            Text(
                                text = "${snapshot.grantedCount} of ${snapshot.totalCount} capabilities active",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (snapshot.allEnabled) StealthEmerald else SolarGold
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Done", color = accent)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { snapshot.grantedCount.toFloat() / snapshot.totalCount.toFloat() },
                    color = if (snapshot.allEnabled) StealthEmerald else accent,
                    trackColor = SmokedGlass,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )
            }

            // 1-Tap Guided Enable-All CTA
            if (!snapshot.allEnabled) {
                item {
                    Button(
                        onClick = {
                            if (hasPendingRuntime) {
                                onRequestAllRuntimePermissions()
                            } else if (nextPendingSpecial != null) {
                                onLaunchSpecialSettings(nextPendingSpecial.id)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = VoidObsidian
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("enable_all_permissions_button")
                    ) {
                        Text(
                            text = when {
                                hasPendingRuntime -> "Enable All Runtime Permissions"
                                nextPendingSpecial != null -> "Enable Next: ${nextPendingSpecial.title}"
                                else -> "All Permissions Active"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }

            items(items, key = { it.id }) { item ->
                PermissionRowCard(
                    item = item,
                    accent = accent,
                    onClick = {
                        if (item.isSpecialSettings) {
                            onLaunchSpecialSettings(item.id)
                        } else if (!item.isGranted) {
                            onRequestAllRuntimePermissions()
                        }
                    }
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = accent.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
                    Text(
                        text = "AI ENGINES · GEMINI & NVIDIA",
                        style = MaterialTheme.typography.titleMedium,
                        color = accent
                    )
                Spacer(Modifier.height(6.dp))
                val keySet = uiState.isNvidiaConfigured || uiState.preferences.nvidiaApiKey.isNotBlank() ||
                    uiState.isGeminiKeyConfigured || uiState.preferences.geminiApiKey.isNotBlank()
                val directMode = uiState.preferences.preferNvidiaPrimary &&
                    (uiState.isNvidiaConfigured || uiState.preferences.nvidiaApiKey.isNotBlank())
                Surface(
                    color = SmokedGlass,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        if (directMode) StealthEmerald.copy(alpha = 0.6f) else accent.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sheet_nvidia_api_card")
                        .clickable(onClick = onOpenNvidiaApiSettings)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = "Configure Gemini & NVIDIA API Keys",
                            tint = if (keySet) StealthEmerald else SolarGold,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gemini & NVIDIA API Keys",
                                style = MaterialTheme.typography.titleSmall,
                                color = HudTextPrimary
                            )
                            Text(
                                text = when {
                                    directMode -> "NVIDIA Primary · Gemini Fallback (${uiState.preferences.nvidiaModel})"
                                    uiState.isGeminiKeyConfigured -> "Custom Gemini Key (${uiState.preferences.geminiModel})"
                                    keySet -> "Gemini Main · NVIDIA Fallback (${uiState.preferences.nvidiaModel})"
                                    else -> "Tap to enter Gemini or NVIDIA API key"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = HudTextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Edit API settings",
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val readyPackCount = uiState.voicePacks.count { it.status == com.example.voice.VoicePackStatus.READY }
                Surface(
                    color = SmokedGlass,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        if (readyPackCount > 0) StealthEmerald.copy(alpha = 0.55f) else accent.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sheet_voice_studio_card")
                        .clickable(onClick = onOpenVoiceStudio)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = "Open Neural Voice Studio",
                            tint = if (readyPackCount > 0) StealthEmerald else accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Neural Voice Studio & Echo Shield",
                                style = MaterialTheme.typography.titleSmall,
                                color = HudTextPrimary
                            )
                            Text(
                                text = if (readyPackCount > 0) {
                                    "$readyPackCount Piper pack active (${uiState.piperEngineStatus})"
                                } else {
                                    "Preparing bundled Piper neural voice & echo shield"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = HudTextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open Voice Studio",
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "HANDS-FREE, SECURITY & VOICE",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
            }

            item {
                ToggleRow(
                    title = "Wake Word (\"${uiState.preferences.customWakeWord}\")",
                    subtitle = "Passively listen for \"Hey Aether\" or \"Aria\" and activate hands-free",
                    checked = uiState.preferences.wakeWordEnabled,
                    onCheckedChange = { onToggleWakeWord() },
                    tag = "wake_word_switch"
                )
                ToggleRow(
                    title = "Voice Barge-In Interruption",
                    subtitle = "Speak mid-reply or tap orb to immediately interrupt TTS and issue next command",
                    checked = uiState.preferences.bargeInEnabled,
                    onCheckedChange = { onToggleBargeIn() },
                    tag = "barge_in_switch"
                )
                ToggleRow(
                    title = "Biometric Vault Lock (${uiState.encryptionBadgeLabel})",
                    subtitle = "Require fingerprint, face, or device PIN to open encrypted memories & logs",
                    checked = uiState.preferences.biometricVaultLockEnabled,
                    onCheckedChange = { onToggleBiometricVaultLock() },
                    tag = "biometric_vault_switch"
                )
                ToggleRow(
                    title = "Autonomous Trusted Mode",
                    subtitle = "Execute calls, messages & UI taps immediately without extra confirmation",
                    checked = uiState.preferences.autonomousModeEnabled,
                    onCheckedChange = { onToggleAutonomousMode() },
                    tag = "autonomous_mode_switch"
                )
                ToggleRow(
                    title = "Hands-Free Follow-Up Loop",
                    subtitle = "Auto-resume microphone listening after spoken reply completes",
                    checked = uiState.preferences.continuousLoopEnabled,
                    onCheckedChange = { onToggleContinuousLoop() },
                    tag = "continuous_loop_switch"
                )
                ToggleRow(
                    title = "Split-Screen App Launching",
                    subtitle = "Open launched apps adjacent to 3D cockpit when supported",
                    checked = uiState.preferences.splitScreenLaunchEnabled,
                    onCheckedChange = { onToggleSplitScreen() },
                    tag = "split_screen_switch"
                )
                ToggleRow(
                    title = "Deep Screen Vision (MediaProjection Fallback)",
                    subtitle = "Optional pixel capture fallback when apps block Accessibility screenshots",
                    checked = uiState.isDeepVisionActive,
                    onCheckedChange = { onToggleDeepScreenVision() },
                    tag = "wizard_deep_vision_switch"
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = SmokedGlass,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        if (uiState.isDefaultAssistant) StealthEmerald.copy(alpha = 0.55f) else accent.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wizard_default_assistant_card")
                        .clickable(onClick = onRequestDefaultAssistant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (uiState.isDefaultAssistant) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = "Default Digital Assistant Status",
                            tint = if (uiState.isDefaultAssistant) StealthEmerald else SolarGold,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Default Digital Assistant Role",
                                style = MaterialTheme.typography.titleSmall,
                                color = HudTextPrimary
                            )
                            Text(
                                text = if (uiState.isDefaultAssistant) {
                                    "Active — summon Aether via OS corner/power assistant trigger"
                                } else {
                                    "Tap to set Aether as your system Default Assistant"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = HudTextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Configure Default Assistant",
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Voice Recognition Language",
                    style = MaterialTheme.typography.titleSmall,
                    color = HudTextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpeechLocaleOption.entries.forEach { option ->
                        val selected = uiState.preferences.speechLocale == option
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectLocale(option) },
                            label = {
                                Text(option.label, style = MaterialTheme.typography.labelMedium)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent.copy(alpha = 0.22f),
                                selectedLabelColor = accent
                            )
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PermissionRowCard(
    item: PermissionItemStatus,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Surface(
        color = SmokedGlass,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (item.isGranted) StealthEmerald.copy(alpha = 0.45f) else accent.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (item.isGranted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (item.isGranted) "Granted" else "Not granted",
                tint = if (item.isGranted) StealthEmerald else SolarGold,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = HudTextPrimary
                )
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HudTextSecondary
                )
            }
            if (!item.isGranted && item.isSpecialSettings) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open Android settings",
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = HudTextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = HudTextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            modifier = Modifier.testTag(tag)
        )
    }
}
