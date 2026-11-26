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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ai.AssistantPersona
import com.example.ai.SpeechLocaleOption
import com.example.core.AssistantUiState
import com.example.data.local.SmartTriggerRuleEntity
import com.example.data.local.VisionTokenMode
import com.example.data.local.WakeTriggerMode
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.DeepCockpit
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.SmokedGlass
import com.example.ui.theme.SolarGold
import com.example.ui.theme.StealthEmerald
import com.example.ui.theme.VoidObsidian

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubSheet(
    uiState: AssistantUiState,
    onDismiss: () -> Unit,
    onSelectPersona: (AssistantPersona) -> Unit,
    onToggleWakeWord: () -> Unit,
    onUpdateWakeWordPhrase: (String) -> Unit,
    onTogglePauseWakeDuringMusic: () -> Unit,
    onToggleBargeIn: () -> Unit,
    onToggleContinuousLoop: () -> Unit,
    onToggleAutonomousMode: () -> Unit,
    onSelectLocale: (SpeechLocaleOption) -> Unit,
    onOpenVoiceStudio: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onSelectWakeTriggerMode: (WakeTriggerMode) -> Unit = {},
    onDownloadKwsPack: () -> Unit = {},
    onToggleDeepScreenVision: () -> Unit = {},
    onRequestDefaultAssistant: () -> Unit = {},
    onSelectVisionTokenMode: (VisionTokenMode) -> Unit = {},
    triggerRules: List<SmartTriggerRuleEntity> = emptyList(),
    onToggleTriggerRule: (Long, Boolean) -> Unit = { _, _ -> },
    onTestTriggerRule: (SmartTriggerRuleEntity) -> Unit = {},
    onDeleteTriggerRule: (Long) -> Unit = {},
    onAddTriggerRule: (String, String, String, String, String, Int) -> Unit = { _, _, _, _, _, _ -> }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepCockpit,
        contentColor = HudTextPrimary
    ) {
        SettingsHubContent(
            uiState = uiState,
            onDismiss = onDismiss,
            onSelectPersona = onSelectPersona,
            onToggleWakeWord = onToggleWakeWord,
            onUpdateWakeWordPhrase = onUpdateWakeWordPhrase,
            onTogglePauseWakeDuringMusic = onTogglePauseWakeDuringMusic,
            onToggleBargeIn = onToggleBargeIn,
            onToggleContinuousLoop = onToggleContinuousLoop,
            onToggleAutonomousMode = onToggleAutonomousMode,
            onSelectLocale = onSelectLocale,
            onOpenVoiceStudio = onOpenVoiceStudio,
            onOpenApiSettings = onOpenApiSettings,
            onOpenPermissions = onOpenPermissions,
            onSelectWakeTriggerMode = onSelectWakeTriggerMode,
            onDownloadKwsPack = onDownloadKwsPack,
            onToggleDeepScreenVision = onToggleDeepScreenVision,
            onRequestDefaultAssistant = onRequestDefaultAssistant,
            onSelectVisionTokenMode = onSelectVisionTokenMode,
            triggerRules = triggerRules,
            onToggleTriggerRule = onToggleTriggerRule,
            onTestTriggerRule = onTestTriggerRule,
            onDeleteTriggerRule = onDeleteTriggerRule,
            onAddTriggerRule = onAddTriggerRule
        )
    }
}

@Composable
fun SettingsHubContent(
    uiState: AssistantUiState,
    onDismiss: () -> Unit,
    onSelectPersona: (AssistantPersona) -> Unit,
    onToggleWakeWord: () -> Unit,
    onUpdateWakeWordPhrase: (String) -> Unit,
    onTogglePauseWakeDuringMusic: () -> Unit,
    onToggleBargeIn: () -> Unit,
    onToggleContinuousLoop: () -> Unit,
    onToggleAutonomousMode: () -> Unit,
    onSelectLocale: (SpeechLocaleOption) -> Unit,
    onOpenVoiceStudio: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectWakeTriggerMode: (WakeTriggerMode) -> Unit = {},
    onDownloadKwsPack: () -> Unit = {},
    onToggleDeepScreenVision: () -> Unit = {},
    onRequestDefaultAssistant: () -> Unit = {},
    onSelectVisionTokenMode: (VisionTokenMode) -> Unit = {},
    triggerRules: List<SmartTriggerRuleEntity> = emptyList(),
    onToggleTriggerRule: (Long, Boolean) -> Unit = { _, _ -> },
    onTestTriggerRule: (SmartTriggerRuleEntity) -> Unit = {},
    onDeleteTriggerRule: (Long) -> Unit = {},
    onAddTriggerRule: (String, String, String, String, String, Int) -> Unit = { _, _, _, _, _, _ -> }
) {
    val accent = MaterialTheme.colorScheme.primary
    val prefs = uiState.preferences
    var wakePhraseDraft by remember(prefs.customWakeWord) { mutableStateOf(prefs.customWakeWord) }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp)
            .testTag("settings_hub_sheet"),
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
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "AETHER CONTROL SETTINGS",
                        style = MaterialTheme.typography.titleMedium,
                        color = HudTextPrimary
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Done", color = accent)
                }
            }
        }

        // Persona Switcher (Aether vs Aria)
        item {
            Text(
                text = "Assistant Persona",
                style = MaterialTheme.typography.titleSmall,
                color = accent
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistantPersona.entries.forEach { option ->
                    val selected = prefs.persona == option
                    val subtitle = if (option == AssistantPersona.AETHER) {
                        if (prefs.customAetherSampleInfo.isNotBlank()) "Custom Voice Sample" else "Male · Command"
                    } else {
                        "Female · Companion"
                    }
                    Surface(
                        color = if (selected) accent.copy(alpha = 0.16f) else SmokedGlass,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (selected) accent else accent.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("persona_option_${option.name.lowercase()}")
                            .clickable { onSelectPersona(option) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = option.displayName,
                                tint = if (selected) accent else HudTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = option.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (selected) accent else HudTextPrimary
                                )
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = HudTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick Hub Navigation Cards
        item {
            SettingsActionCard(
                icon = Icons.Filled.GraphicEq,
                iconTint = StealthEmerald,
                title = "Neural Voice Studio & 1-Min Voice Clone",
                subtitle = if (prefs.customAetherSampleInfo.isNotBlank()) {
                    "Aether sample ready · ${prefs.customAetherSampleInfo}"
                } else {
                    "Import 1-min .wav / .onnx, switch Aether & Aria voices"
                },
                onClick = {
                    onDismiss()
                    onOpenVoiceStudio()
                },
                tag = "settings_open_voice_studio"
            )
            Spacer(Modifier.height(8.dp))
            val geminiReady = uiState.isGeminiKeyConfigured || prefs.geminiApiKey.isNotBlank()
            val nvidiaReady = uiState.isNvidiaConfigured || prefs.nvidiaApiKey.isNotBlank()
            SettingsActionCard(
                icon = Icons.Filled.Key,
                iconTint = if (geminiReady || nvidiaReady) StealthEmerald else SolarGold,
                title = "Gemini & NVIDIA API Keys",
                subtitle = when {
                    prefs.preferNvidiaPrimary && nvidiaReady -> "NVIDIA primary (${prefs.nvidiaModel}) · Gemini fallback"
                    geminiReady -> "Custom Gemini (${prefs.geminiModel}) · Firebase + NVIDIA fallback"
                    nvidiaReady -> "Gemini primary · NVIDIA fallback ready"
                    else -> "Enter Gemini (AIza...) & NVIDIA (nvapi-...) keys"
                },
                onClick = {
                    onDismiss()
                    onOpenApiSettings()
                },
                tag = "settings_open_api_keys"
            )
            Spacer(Modifier.height(8.dp))
            val snap = uiState.permissionSnapshot
            SettingsActionCard(
                icon = Icons.Filled.Security,
                iconTint = if (snap.allEnabled) StealthEmerald else SolarGold,
                title = "Permissions & Accessibility (${snap.grantedCount}/${snap.totalCount})",
                subtitle = if (snap.allEnabled) {
                    "All call, overlay, DND & automation permissions active"
                } else {
                    "Enable Accessibility, Call, DND & Overlay for full automation"
                },
                onClick = {
                    onDismiss()
                    onOpenPermissions()
                },
                tag = "settings_open_permissions"
            )
        }

        // Wake Word & Music Safety
        item {
            HorizontalDivider(color = accent.copy(alpha = 0.18f))
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Assistant Wake & Music Protection",
                style = MaterialTheme.typography.titleSmall,
                color = accent
            )
            Spacer(Modifier.height(6.dp))

            Text(
                text = "Wake Trigger Mode",
                style = MaterialTheme.typography.labelMedium,
                color = HudTextSecondary
            )
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WakeTriggerMode.entries.forEach { mode ->
                    val selected = prefs.wakeTriggerMode == mode
                    Surface(
                        color = if (selected) accent.copy(alpha = 0.15f) else SmokedGlass,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (selected) accent else accent.copy(alpha = 0.22f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wake_mode_${mode.name.lowercase()}")
                            .clickable { onSelectWakeTriggerMode(mode) }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) accent else HudTextPrimary
                            )
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = HudTextSecondary
                            )
                        }
                    }
                }
            }

            if (prefs.wakeTriggerMode == WakeTriggerMode.HYBRID_KWS_AND_BUTTON) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = SmokedGlass,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (uiState.kwsPackReady) StealthEmerald.copy(alpha = 0.45f) else accent.copy(alpha = 0.28f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "On-Device Sherpa KWS Pack (3.3 MB)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = HudTextPrimary
                                )
                                Text(
                                    text = uiState.kwsPackStatusLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (uiState.kwsPackReady) StealthEmerald else HudTextSecondary
                                )
                            }
                            if (!uiState.kwsPackReady) {
                                OutlinedButton(
                                    onClick = onDownloadKwsPack,
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                                    modifier = Modifier.testTag("kws_pack_download_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = "Download offline KWS pack",
                                        tint = accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Install", color = accent)
                                }
                            }
                        }
                        if (uiState.kwsDownloadProgress in 1..99) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { uiState.kwsDownloadProgress / 100f },
                                color = accent,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingsActionCard(
                icon = Icons.Filled.Assistant,
                iconTint = if (uiState.isDefaultAssistant) StealthEmerald else SolarGold,
                title = if (uiState.isDefaultAssistant) "Default Digital Assistant Active" else "Set as Default Digital Assistant",
                subtitle = if (uiState.isDefaultAssistant) {
                    if (prefs.wakeTriggerMode == WakeTriggerMode.ASSISTANT_BUTTON_ONLY) {
                        "0-Mic OS Trigger Ready · Launch via power/corner gesture + Floating Overlay"
                    } else {
                        "Active · Gesture + foreground Hands-Free Wake enabled"
                    }
                } else {
                    "Tap to grant Android Default Assistant role for floating OS overlay sheet"
                },
                onClick = onRequestDefaultAssistant,
                tag = "default_assistant_card"
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                color = SmokedGlass.copy(alpha = 0.85f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("os_hotword_explanation_banner")
            ) {
                Text(
                    text = com.example.system.PermissionsManager.OS_HOTWORD_EXPLANATION,
                    style = MaterialTheme.typography.bodySmall,
                    color = HudTextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(6.dp))
            SettingsSwitchRow(
                icon = Icons.Filled.Visibility,
                title = "Deep Screen Vision (MediaProjection)",
                subtitle = if (uiState.isDeepVisionActive) {
                    "Pixel-level screenshot fallback active when apps block Accessibility capture"
                } else {
                    "Optional user-consented capture when hidden UI trees block YouTube/WhatsApp/FB"
                },
                checked = uiState.isDeepVisionActive,
                onToggle = onToggleDeepScreenVision,
                tag = "deep_vision_switch"
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = accent.copy(alpha = 0.18f))
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Offline Screen Vision (<50 MB)",
                    style = MaterialTheme.typography.titleSmall,
                    color = accent
                )
                Text(
                    text = "Saved ~${prefs.estimatedTokensSavedTotal} tok",
                    style = MaterialTheme.typography.labelMedium,
                    color = StealthEmerald,
                    modifier = Modifier.testTag("settings_tokens_saved_label")
                )
            }
            Text(
                text = "ML Kit Objects + Labels + OCR (~18 MB bundled) understands screen offline & suppresses cloud image tokens",
                style = MaterialTheme.typography.bodySmall,
                color = HudTextSecondary
            )
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VisionTokenMode.entries.forEach { mode ->
                    val selected = prefs.visionTokenMode == mode
                    Surface(
                        color = if (selected) StealthEmerald.copy(alpha = 0.14f) else SmokedGlass,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (selected) StealthEmerald else accent.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vision_mode_${mode.name.lowercase()}")
                            .clickable { onSelectVisionTokenMode(mode) }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) StealthEmerald else HudTextPrimary
                            )
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = HudTextSecondary
                            )
                        }
                    }
                }
            }

            SettingsSwitchRow(
                title = "\"Hey Aether\" Wake Word Standby",
                subtitle = "Google-Assistant style hands-free mic activation (non-ducking, no YouTube stutter)",
                checked = prefs.wakeWordEnabled,
                onToggle = onToggleWakeWord,
                tag = "settings_wake_word_switch"
            )

            if (prefs.wakeWordEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = wakePhraseDraft,
                        onValueChange = { wakePhraseDraft = it },
                        label = { Text("Wake Phrase") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accent,
                            unfocusedBorderColor = accent.copy(alpha = 0.3f),
                            focusedTextColor = HudTextPrimary,
                            unfocusedTextColor = HudTextPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onUpdateWakeWordPhrase(wakePhraseDraft) },
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.6f))
                    ) {
                        Text("Apply", color = accent)
                    }
                }
            }

            SettingsSwitchRow(
                icon = Icons.Filled.MusicNote,
                title = "Pause Wake Word While Music Plays",
                subtitle = "Suspends standby mic automatically during YouTube/Spotify playback",
                checked = prefs.pauseWakeDuringMusic,
                onToggle = onTogglePauseWakeDuringMusic,
                tag = "settings_pause_music_switch"
            )

            SettingsSwitchRow(
                title = "Voice Barge-In Interruption",
                subtitle = "Interrupt spoken responses anytime with voice or tap",
                checked = prefs.bargeInEnabled,
                onToggle = onToggleBargeIn,
                tag = "settings_barge_in_switch"
            )

            SettingsSwitchRow(
                title = "Autonomous Trusted Actions",
                subtitle = "Auto-send WhatsApp messages, publish Facebook posts, and place calls",
                checked = prefs.autonomousModeEnabled,
                onToggle = onToggleAutonomousMode,
                tag = "settings_autonomous_switch"
            )

            SettingsSwitchRow(
                title = "Continuous Conversation Loop",
                subtitle = "Keep listening for follow-up questions automatically",
                checked = prefs.continuousLoopEnabled,
                onToggle = onToggleContinuousLoop,
                tag = "settings_continuous_loop_switch"
            )
        }

        // Smart Context Auto-Triggers (Bluetooth, Wi-Fi, Battery)
        item {
            HorizontalDivider(color = accent.copy(alpha = 0.18f))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = SolarGold,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Smart Context Auto-Triggers",
                    style = MaterialTheme.typography.titleSmall,
                    color = accent
                )
            }
            Text(
                text = "Run digests or commands automatically on Bluetooth headset, Wi-Fi, or battery thresholds with cooldown protection.",
                style = MaterialTheme.typography.bodySmall,
                color = HudTextSecondary
            )
            Spacer(Modifier.height(6.dp))

            var newRuleType by remember { mutableStateOf("WIFI_CONNECTED") }
            var newRulePrompt by remember { mutableStateOf("") }

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("smart_triggers_section")
            ) {
                triggerRules.forEach { rule ->
                    Surface(
                        color = SmokedGlass,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (rule.isEnabled) StealthEmerald.copy(alpha = 0.45f) else accent.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("trigger_rule_${rule.id}")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rule.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = HudTextPrimary
                                    )
                                    val condText = if (rule.conditionValue.isNotBlank()) " (${rule.conditionValue})" else ""
                                    Text(
                                        text = "${rule.triggerType}$condText · ${rule.cooldownMinutes}m cooldown",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = HudTextSecondary
                                    )
                                }
                                IconButton(
                                    onClick = { onTestTriggerRule(rule) },
                                    modifier = Modifier.testTag("test_trigger_${rule.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Test trigger rule",
                                        tint = SolarGold
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteTriggerRule(rule.id) },
                                    modifier = Modifier.testTag("delete_trigger_${rule.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = "Delete trigger rule",
                                        tint = AlertCoral
                                    )
                                }
                                Switch(
                                    checked = rule.isEnabled,
                                    onCheckedChange = { enabled -> onToggleTriggerRule(rule.id, enabled) }
                                )
                            }
                        }
                    }
                }

                // Quick Add Trigger Rule row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "BLUETOOTH_CONNECTED" to "Bluetooth",
                        "WIFI_CONNECTED" to "Wi-Fi",
                        "BATTERY_LOW" to "Battery <=20%"
                    ).forEach { (typeKey, label) ->
                        FilterChip(
                            selected = newRuleType == typeKey,
                            onClick = { newRuleType = typeKey },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newRulePrompt,
                        onValueChange = { newRulePrompt = it },
                        placeholder = { Text("Action prompt (or leave blank for Digest)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val mode = if (newRulePrompt.isBlank()) "SPEAK_NOTIFICATION_DIGEST" else "RUN_PROMPT_COMMAND"
                            val ruleName = when (newRuleType) {
                                "BLUETOOTH_CONNECTED" -> "BT Audio Rule"
                                "WIFI_CONNECTED" -> "Wi-Fi Connected Rule"
                                else -> "Battery Alert Rule"
                            }
                            val cond = if (newRuleType == "BATTERY_LOW") "20" else ""
                            onAddTriggerRule(ruleName, newRuleType, cond, mode, newRulePrompt, 15)
                            newRulePrompt = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian),
                        modifier = Modifier.testTag("add_trigger_rule_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add smart trigger rule")
                    }
                }
            }
        }

        // Speech Recognition Language
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
                    val selected = prefs.speechLocale == option
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectLocale(option) },
                        label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.22f),
                            selectedLabelColor = accent
                        )
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun SettingsActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        color = SmokedGlass,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, iconTint.copy(alpha = 0.42f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, color = HudTextPrimary)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = HudTextSecondary)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open",
                tint = HudTextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    tag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = HudTextSecondary,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = HudTextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = HudTextSecondary)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag(tag)
        )
    }
}
