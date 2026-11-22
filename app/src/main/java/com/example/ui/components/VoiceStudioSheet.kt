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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceStudioBottomSheet(
    uiState: AssistantUiState,
    onDismiss: () -> Unit,
    onSelectEngineMode: (String) -> Unit,
    onSelectBargeInMode: (String) -> Unit,
    onDownloadPack: (String) -> Unit,
    onSelectPack: (String) -> Unit,
    onDeletePack: (String) -> Unit,
    onTestSample: (String?) -> Unit
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
            onDownloadPack = onDownloadPack,
            onSelectPack = onSelectPack,
            onDeletePack = onDeletePack,
            onTestSample = onTestSample,
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
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val prefs = uiState.preferences

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
                            text = "On-demand Piper VITS (< 25 MB) + Hybrid Bangla",
                            style = MaterialTheme.typography.labelSmall,
                            color = HudTextSecondary
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

        item {
            Surface(
                color = StealthEmerald.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, StealthEmerald.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerifiedUser,
                        contentDescription = "Hardware Echo Shield",
                        tint = StealthEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "VoIP Echo Shield Active (MODE_IN_COMMUNICATION)",
                            style = MaterialTheme.typography.titleSmall,
                            color = StealthEmerald
                        )
                        Text(
                            text = "Hardware AEC + NoiseSuppressor + 650ms decay guard prevent Aether from hearing its own voice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = HudTextSecondary
                        )
                    }
                }
            }
        }

        item {
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
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Bangla (bn-BD) uses 0 MB tuned Android/Gemini neural voice automatically so total download stays compact.",
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

        item {
            HorizontalDivider(color = accent.copy(alpha = 0.18f))
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "English Neural Voice Packs",
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
        }

        items(uiState.voicePacks, key = { it.pack.id }) { packState ->
            NeuralVoicePackCard(
                item = packState,
                isSelected = prefs.selectedEnglishVoiceId == packState.pack.id,
                accent = accent,
                onDownload = { onDownloadPack(packState.pack.id) },
                onSelect = { onSelectPack(packState.pack.id) },
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
                    Text(
                        text = "${pack.languageTag} · ${pack.genderHint} · ~${pack.downloadSizeMb} MB",
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
                                    Text("Use", color = accent)
                                }
                            }
                            IconButton(onClick = onTest) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Test sample", tint = accent)
                            }
                            IconButton(onClick = onDelete) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete voice pack", tint = AlertCoral)
                            }
                        }
                    }
                    VoicePackStatus.NOT_INSTALLED, VoicePackStatus.ERROR -> {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian)
                        ) {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Get")
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
