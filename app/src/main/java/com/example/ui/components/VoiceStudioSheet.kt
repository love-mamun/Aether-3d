package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.AssistantPersona
import com.example.audio.AudioEffectState
import com.example.core.AssistantUiState
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.DeepCockpit
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.SmokedGlass
import com.example.ui.theme.SolarGold
import com.example.ui.theme.StealthEmerald
import com.example.ui.theme.VoidObsidian
import com.example.voice.VoicePackStatus
import com.example.voice.VoicePackUiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceStudioBottomSheet(
    uiState: AssistantUiState,
    onDismiss: () -> Unit,
    onSelectEngineMode: (String) -> Unit,
    onSelectBargeInMode: (String) -> Unit,
    onToggleFilteredMic: () -> Unit = {},
    onDownloadPack: (String) -> Unit,
    onSelectPackForPersona: (String, AssistantPersona) -> Unit = { _, _ -> },
    onSelectPack: (String) -> Unit = {},
    onDeletePack: (String) -> Unit,
    onTestSample: (String?) -> Unit,
    onSetVoiceSpeed: (Float) -> Unit = {},
    onImportVoiceSampleClick: () -> Unit = {},
    onPreviewImportedSampleClick: () -> Unit = {},
    onImportCustomOnnxClick: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepCockpit,
        contentColor = HudTextPrimary
    ) {
        VoiceStudioContent(
            uiState = uiState,
            onClose = onDismiss,
            onSelectEngineMode = onSelectEngineMode,
            onSelectBargeInMode = onSelectBargeInMode,
            onToggleFilteredMic = onToggleFilteredMic,
            onDownloadPack = onDownloadPack,
            onSelectPackForPersona = onSelectPackForPersona,
            onSelectPack = onSelectPack,
            onDeletePack = onDeletePack,
            onTestSample = onTestSample,
            onSetVoiceSpeed = onSetVoiceSpeed,
            onImportVoiceSampleClick = onImportVoiceSampleClick,
            onPreviewImportedSampleClick = onPreviewImportedSampleClick,
            onImportCustomOnnxClick = onImportCustomOnnxClick,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
        )
    }
}

@Composable
fun VoiceStudioContent(
    uiState: AssistantUiState,
    onClose: (() -> Unit)?,
    onSelectEngineMode: (String) -> Unit,
    onSelectBargeInMode: (String) -> Unit,
    onDownloadPack: (String) -> Unit,
    onSelectPack: (String) -> Unit,
    onDeletePack: (String) -> Unit,
    onTestSample: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onToggleFilteredMic: () -> Unit = {},
    onSelectPackForPersona: (String, AssistantPersona) -> Unit = { id, _ -> onSelectPack(id) },
    onSetVoiceSpeed: (Float) -> Unit = {},
    onImportVoiceSampleClick: () -> Unit = {},
    onPreviewImportedSampleClick: () -> Unit = {},
    onImportCustomOnnxClick: () -> Unit = {}
) {
    val accent = MaterialTheme.colorScheme.primary
    val prefs = uiState.preferences
    var targetPersona by remember(prefs.persona) { mutableStateOf(prefs.persona) }

    LazyColumn(
        modifier = modifier.testTag("voice_studio_sheet"),
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
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = "Neural Voice Studio",
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "NEURAL VOICE STUDIO",
                            style = MaterialTheme.typography.titleMedium,
                            color = HudTextPrimary
                        )
                        Text(
                            text = uiState.piperEngineStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.piperEngineStatus.contains("Piper Native", ignoreCase = true)) {
                                StealthEmerald
                            } else {
                                SolarGold
                            }
                        )
                    }
                }
                if (onClose != null) {
                    TextButton(onClick = onClose) {
                        Text("Done", color = accent)
                    }
                }
            }
        }

        // 1-Minute User Voice Sample & Custom ONNX Importer for Aether
        item {
            val sampleReady = prefs.customAetherWavPath.isNotBlank()
            Surface(
                color = SmokedGlass,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (sampleReady) StealthEmerald.copy(alpha = 0.65f) else accent.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_voice_clone_card")
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.AudioFile,
                            contentDescription = "Your 1-Min Voice Clone",
                            tint = if (sampleReady) StealthEmerald else accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Aether Personal Voice Profile (1-Min Sample / ONNX)",
                                style = MaterialTheme.typography.titleSmall,
                                color = HudTextPrimary
                            )
                            Text(
                                text = if (prefs.customAetherSampleInfo.isNotBlank()) {
                                    prefs.customAetherSampleInfo
                                } else {
                                    "Import your 1-min English .wav sample to calibrate Aether's pitch, or import a custom .onnx / .tar.bz2 voice model."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (sampleReady) StealthEmerald else HudTextSecondary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onImportVoiceSampleClick,
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_wav_sample_button")
                        ) {
                            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (sampleReady) "Change .wav" else "Import .wav", maxLines = 1)
                        }

                        if (sampleReady) {
                            OutlinedButton(
                                onClick = onPreviewImportedSampleClick,
                                border = BorderStroke(1.dp, StealthEmerald.copy(alpha = 0.7f)),
                                modifier = Modifier.testTag("preview_wav_sample_button")
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = StealthEmerald)
                                Spacer(Modifier.width(4.dp))
                                Text("Preview", color = StealthEmerald, maxLines = 1)
                            }
                        }

                        OutlinedButton(
                            onClick = onImportCustomOnnxClick,
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                            modifier = Modifier.testTag("import_onnx_model_button")
                        ) {
                            Text("Load .onnx", color = accent, maxLines = 1)
                        }
                    }
                }
            }
        }

        // Filtered Mic Status Card
        item {
            val diag = uiState.audioFilterDiagnostics
            val effects = diag.effects
            Surface(
                color = StealthEmerald.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, StealthEmerald.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("audio_filter_diagnostics_card")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = "Microphone Audio Filtering",
                            tint = StealthEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Filtered Mic · AudioRecord (${diag.audioSourceLabel})",
                                style = MaterialTheme.typography.titleSmall,
                                color = StealthEmerald
                            )
                            Text(
                                text = "Suppresses YouTube, music & speaker bleed during voice capture.",
                                style = MaterialTheme.typography.bodySmall,
                                color = HudTextSecondary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = prefs.filteredMicEnabled,
                            onCheckedChange = { onToggleFilteredMic() },
                            modifier = Modifier.testTag("filtered_mic_switch")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EffectStatusBadge("AEC", effects.aecState, Modifier.weight(1f))
                        EffectStatusBadge("NS", effects.nsState, Modifier.weight(1f))
                        EffectStatusBadge("AGC", effects.agcState, Modifier.weight(1f))
                    }
                    if (diag.isCapturing) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Live 16 kHz · Noise Floor ${diag.noiseFloorDb.toInt()} dB · Voice ${(diag.voiceConfidence * 100).toInt()}% · Route: ${diag.outputRouteLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (diag.isSpeechDetected) StealthEmerald else HudTextSecondary
                        )
                    } else if (!diag.lastFailureReason.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = diag.lastFailureReason,
                            style = MaterialTheme.typography.labelSmall,
                            color = SolarGold
                        )
                    }
                }
            }
        }

        // Speech Speed & Engine Mode
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Voice Speed (${String.format(Locale.US, "%.2fx", prefs.voiceSpeed)})",
                    style = MaterialTheme.typography.titleSmall,
                    color = HudTextPrimary
                )
                TextButton(onClick = { onSetVoiceSpeed(1.08f) }) {
                    Text("Reset 1.08x", style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }
            Slider(
                value = prefs.voiceSpeed.coerceIn(0.8f, 1.4f),
                onValueChange = onSetVoiceSpeed,
                valueRange = 0.8f..1.4f,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
            )

            Text(
                text = "Voice Engine Mode",
                style = MaterialTheme.typography.titleSmall,
                color = HudTextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AUTO" to "Auto Hybrid", "PIPER" to "Piper Neural", "SYSTEM" to "System HD").forEach { (mode, label) ->
                    FilterChip(
                        selected = prefs.voiceEngineMode == mode,
                        onClick = { onSelectEngineMode(mode) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.22f),
                            selectedLabelColor = accent
                        )
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Streaming sentence-by-sentence neural synthesis for instant response start.",
                style = MaterialTheme.typography.labelSmall,
                color = HudTextSecondary
            )
        }

        item {
            HorizontalDivider(color = accent.copy(alpha = 0.18f))
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Barge-In Interruption Mode",
                style = MaterialTheme.typography.titleSmall,
                color = HudTextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "ADAPTIVE" to "Adaptive AEC",
                    "HEADSET_ONLY" to "Headset Only",
                    "TAP_ONLY" to "Tap Orb Only"
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = prefs.bargeInMode == mode,
                        onClick = { onSelectBargeInMode(mode) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.22f),
                            selectedLabelColor = accent
                        )
                    )
                }
            }
        }

        // Persona-Scoped Voice Assignment
        item {
            HorizontalDivider(color = accent.copy(alpha = 0.18f))
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Assign Voice Pack For:",
                    style = MaterialTheme.typography.titleSmall,
                    color = accent
                )
                OutlinedButton(
                    onClick = { onTestSample(null) },
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = accent)
                    Spacer(Modifier.width(4.dp))
                    Text("Test Active", style = MaterialTheme.typography.labelMedium, color = accent)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistantPersona.entries.forEach { p ->
                    FilterChip(
                        selected = targetPersona == p,
                        onClick = { targetPersona = p },
                        label = {
                            val activeName = if (p == AssistantPersona.AETHER) {
                                "Aether (${prefs.aetherVoiceId.substringAfter("-").substringBefore("-")})"
                            } else {
                                "Aria (${prefs.ariaVoiceId.substringAfter("-").substringBefore("-")})"
                            }
                            Text(activeName, style = MaterialTheme.typography.labelMedium)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.22f),
                            selectedLabelColor = accent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        items(uiState.voicePacks, key = { it.pack.id }) { packState ->
            val isBangla = packState.pack.languageTag.startsWith("bn", ignoreCase = true)
            val isPackSelected = if (isBangla) {
                prefs.selectedBanglaVoiceId == packState.pack.id
            } else if (targetPersona == AssistantPersona.AETHER) {
                prefs.aetherVoiceId == packState.pack.id
            } else {
                prefs.ariaVoiceId == packState.pack.id
            }
            val ownerBadges = buildList {
                if (isBangla && prefs.selectedBanglaVoiceId == packState.pack.id) add("Bangla")
                if (!isBangla && prefs.aetherVoiceId == packState.pack.id) add("Aether")
                if (!isBangla && prefs.ariaVoiceId == packState.pack.id) add("Aria")
            }
            NeuralVoicePackCard(
                item = packState,
                isSelected = isPackSelected,
                ownerLabel = ownerBadges.joinToString(" · "),
                targetPersonaLabel = if (isBangla) "Bangla" else targetPersona.displayName,
                accent = accent,
                onDownload = { onDownloadPack(packState.pack.id) },
                onSelect = { onSelectPackForPersona(packState.pack.id, targetPersona) },
                onDelete = { onDeletePack(packState.pack.id) },
                onTest = { onTestSample(packState.pack.id) }
            )
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun NeuralVoicePackCard(
    item: VoicePackUiState,
    isSelected: Boolean,
    ownerLabel: String,
    targetPersonaLabel: String,
    accent: androidx.compose.ui.graphics.Color,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    val pack = item.pack
    Surface(
        color = SmokedGlass,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            when {
                item.status == VoicePackStatus.READY && isSelected -> StealthEmerald.copy(alpha = 0.75f)
                item.status == VoicePackStatus.READY -> accent.copy(alpha = 0.4f)
                else -> accent.copy(alpha = 0.2f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_pack_${pack.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = HudTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sizeNote = if (pack.isBundledAsset) "Starter Offline" else "~${pack.downloadSizeMb.toInt()} MB"
                    val ownerTag = if (ownerLabel.isNotBlank()) " · Active: $ownerLabel" else ""
                    Text(
                        text = "${pack.languageTag} · ${pack.genderHint} · $sizeNote$ownerTag",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected && item.status == VoicePackStatus.READY) StealthEmerald else HudTextSecondary
                    )
                }

                when (item.status) {
                    VoicePackStatus.READY -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected voice",
                                    tint = StealthEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            } else {
                                TextButton(onClick = onSelect) {
                                    Text("Use ($targetPersonaLabel)", color = accent)
                                }
                            }
                            IconButton(onClick = onTest) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Test sample", tint = accent)
                            }
                            if (!pack.isBundledAsset) {
                                IconButton(onClick = onDelete) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete voice pack", tint = AlertCoral)
                                }
                            }
                        }
                    }
                    VoicePackStatus.NOT_INSTALLED, VoicePackStatus.ERROR -> {
                        if (pack.downloadUrl.isBlank()) {
                            Text(
                                text = "Import Above",
                                style = MaterialTheme.typography.labelSmall,
                                color = HudTextSecondary
                            )
                        } else {
                            Button(
                                onClick = onDownload,
                                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian)
                            ) {
                                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Get")
                            }
                        }
                    }
                    VoicePackStatus.DOWNLOADING, VoicePackStatus.EXTRACTING -> {
                        Text(
                            text = if (item.status == VoicePackStatus.EXTRACTING) "Unpacking..." else "${item.progressPercent}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = SolarGold
                        )
                    }
                }
            }

            if (item.status == VoicePackStatus.DOWNLOADING || item.status == VoicePackStatus.EXTRACTING) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (item.progressPercent / 100f).coerceIn(0f, 1f) },
                    color = accent,
                    trackColor = VoidObsidian,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
            if (!item.errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = AlertCoral
                )
            }
        }
    }
}

@Composable
private fun EffectStatusBadge(
    shortName: String,
    state: AudioEffectState,
    modifier: Modifier = Modifier
) {
    val badgeColor = when (state) {
        AudioEffectState.ACTIVE -> StealthEmerald
        AudioEffectState.PENDING -> HudTextSecondary
        AudioEffectState.UNSUPPORTED, AudioEffectState.INIT_FAILED -> SolarGold
    }
    Surface(
        color = VoidObsidian.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                text = shortName,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor
            )
            Text(
                text = state.label,
                style = MaterialTheme.typography.labelSmall,
                color = HudTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
