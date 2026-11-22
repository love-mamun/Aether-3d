package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.CommandLogEntity
import com.example.data.local.MemoryNoteEntity
import com.example.data.local.QuickContactEntity
import com.example.data.local.ReminderEntity
import com.example.data.local.ScreenMacroEntity
import com.example.data.local.VoiceRoutineEntity
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.DeepCockpit
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.SmokedGlass
import com.example.ui.theme.SolarGold
import com.example.ui.theme.StealthEmerald
import com.example.ui.theme.VoidObsidian
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultBottomSheet(
    logs: List<CommandLogEntity>,
    memories: List<MemoryNoteEntity>,
    routines: List<VoiceRoutineEntity>,
    contacts: List<QuickContactEntity>,
    onDismiss: () -> Unit,
    onReplayCommand: (String) -> Unit,
    onDeleteLog: (Long) -> Unit,
    onClearLogs: () -> Unit,
    onAddMemory: (String) -> Unit,
    onTogglePinMemory: (MemoryNoteEntity) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onRunRoutine: (VoiceRoutineEntity) -> Unit,
    onSaveRoutine: (String, String, String) -> Unit,
    onDeleteRoutine: (Long) -> Unit,
    onPickSystemContact: () -> Unit,
    onAddManualContact: (String, String, String, String) -> Unit,
    onDeleteContact: (Long) -> Unit,
    isSqlCipherEncrypted: Boolean = false,
    encryptionBadgeLabel: String = "Local SQLite Vault",
    biometricLockEnabled: Boolean = false,
    isVaultUnlocked: Boolean = true,
    biometricError: String? = null,
    onRequestBiometricUnlock: () -> Unit = {},
    onToggleBiometricLock: () -> Unit = {},
    onLockVaultNow: () -> Unit = {},
    reminders: List<ReminderEntity> = emptyList(),
    dailyBriefingEnabled: Boolean = false,
    briefingHour: Int = 8,
    briefingMinute: Int = 0,
    onToggleDailyBriefing: (Boolean, Int, Int) -> Unit = { _, _, _ -> },
    onSpeakBriefingNow: () -> Unit = {},
    onAddReminder: (String, Int) -> Unit = { _, _ -> },
    onToggleReminderCompleted: (Long, Boolean) -> Unit = { _, _ -> },
    onDeleteReminder: (Long) -> Unit = {},
    macros: List<ScreenMacroEntity> = emptyList(),
    isMacroRecording: Boolean = false,
    macroStepCount: Int = 0,
    onToggleMacroRecording: (String) -> Unit = {},
    onReplayMacro: (ScreenMacroEntity) -> Unit = {},
    onDeleteMacro: (Long) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepCockpit,
        contentColor = HudTextPrimary
    ) {
        VaultWorkspaceContent(
            logs = logs,
            memories = memories,
            routines = routines,
            contacts = contacts,
            onClose = onDismiss,
            onReplayCommand = {
                onReplayCommand(it)
                onDismiss()
            },
            onDeleteLog = onDeleteLog,
            onClearLogs = onClearLogs,
            onAddMemory = onAddMemory,
            onTogglePinMemory = onTogglePinMemory,
            onDeleteMemory = onDeleteMemory,
            onRunRoutine = {
                onRunRoutine(it)
                onDismiss()
            },
            onSaveRoutine = onSaveRoutine,
            onDeleteRoutine = onDeleteRoutine,
            onPickSystemContact = onPickSystemContact,
            onAddManualContact = onAddManualContact,
            onDeleteContact = onDeleteContact,
            isSqlCipherEncrypted = isSqlCipherEncrypted,
            encryptionBadgeLabel = encryptionBadgeLabel,
            biometricLockEnabled = biometricLockEnabled,
            isVaultUnlocked = isVaultUnlocked,
            biometricError = biometricError,
            onRequestBiometricUnlock = onRequestBiometricUnlock,
            onToggleBiometricLock = onToggleBiometricLock,
            onLockVaultNow = onLockVaultNow,
            reminders = reminders,
            dailyBriefingEnabled = dailyBriefingEnabled,
            briefingHour = briefingHour,
            briefingMinute = briefingMinute,
            onToggleDailyBriefing = onToggleDailyBriefing,
            onSpeakBriefingNow = {
                onSpeakBriefingNow()
                onDismiss()
            },
            onAddReminder = onAddReminder,
            onToggleReminderCompleted = onToggleReminderCompleted,
            onDeleteReminder = onDeleteReminder,
            macros = macros,
            isMacroRecording = isMacroRecording,
            macroStepCount = macroStepCount,
            onToggleMacroRecording = onToggleMacroRecording,
            onReplayMacro = {
                onReplayMacro(it)
                onDismiss()
            },
            onDeleteMacro = onDeleteMacro,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .navigationBarsPadding()
        )
    }
}

@Composable
fun VaultWorkspaceContent(
    logs: List<CommandLogEntity>,
    memories: List<MemoryNoteEntity>,
    routines: List<VoiceRoutineEntity>,
    contacts: List<QuickContactEntity>,
    onClose: (() -> Unit)?,
    onReplayCommand: (String) -> Unit,
    onDeleteLog: (Long) -> Unit,
    onClearLogs: () -> Unit,
    onAddMemory: (String) -> Unit,
    onTogglePinMemory: (MemoryNoteEntity) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onRunRoutine: (VoiceRoutineEntity) -> Unit,
    onSaveRoutine: (String, String, String) -> Unit,
    onDeleteRoutine: (Long) -> Unit,
    onPickSystemContact: () -> Unit,
    onAddManualContact: (String, String, String, String) -> Unit,
    onDeleteContact: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isSqlCipherEncrypted: Boolean = false,
    encryptionBadgeLabel: String = "Local SQLite Vault",
    biometricLockEnabled: Boolean = false,
    isVaultUnlocked: Boolean = true,
    biometricError: String? = null,
    onRequestBiometricUnlock: () -> Unit = {},
    onToggleBiometricLock: () -> Unit = {},
    onLockVaultNow: () -> Unit = {},
    reminders: List<ReminderEntity> = emptyList(),
    dailyBriefingEnabled: Boolean = false,
    briefingHour: Int = 8,
    briefingMinute: Int = 0,
    onToggleDailyBriefing: (Boolean, Int, Int) -> Unit = { _, _, _ -> },
    onSpeakBriefingNow: () -> Unit = {},
    onAddReminder: (String, Int) -> Unit = { _, _ -> },
    onToggleReminderCompleted: (Long, Boolean) -> Unit = { _, _ -> },
    onDeleteReminder: (Long) -> Unit = {},
    macros: List<ScreenMacroEntity> = emptyList(),
    isMacroRecording: Boolean = false,
    macroStepCount: Int = 0,
    onToggleMacroRecording: (String) -> Unit = {},
    onReplayMacro: (ScreenMacroEntity) -> Unit = {},
    onDeleteMacro: (Long) -> Unit = {}
) {
    val accent = MaterialTheme.colorScheme.primary
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("History", "Memories", "Routines", "Contacts", "Briefing", "Macros")
    val isLocked = biometricLockEnabled && !isVaultUnlocked

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSqlCipherEncrypted) "ENCRYPTED PRIVACY VAULT" else "LOCAL SQLITE VAULT",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
                Spacer(Modifier.height(2.dp))
                Surface(
                    color = (if (isSqlCipherEncrypted) StealthEmerald else SolarGold).copy(alpha = 0.14f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(
                        1.dp,
                        (if (isSqlCipherEncrypted) StealthEmerald else SolarGold).copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.testTag("vault_encryption_badge")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = if (isSqlCipherEncrypted) Icons.Filled.VerifiedUser else Icons.Filled.Lock,
                            contentDescription = null,
                            tint = if (isSqlCipherEncrypted) StealthEmerald else SolarGold,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = encryptionBadgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSqlCipherEncrypted) StealthEmerald else SolarGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (biometricLockEnabled && isVaultUnlocked) {
                    IconButton(
                        onClick = onLockVaultNow,
                        modifier = Modifier.testTag("lock_vault_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Lock Vault Now",
                            tint = SolarGold
                        )
                    }
                } else if (!biometricLockEnabled) {
                    IconButton(
                        onClick = onToggleBiometricLock,
                        modifier = Modifier.testTag("enable_biometric_lock_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LockOpen,
                            contentDescription = "Enable Biometric Vault Lock",
                            tint = HudTextSecondary
                        )
                    }
                }
                if (onClose != null) {
                    TextButton(onClick = onClose) {
                        Text("Close", color = HudTextSecondary)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (isLocked) {
            VaultLockedCard(
                encryptionBadgeLabel = encryptionBadgeLabel,
                biometricError = biometricError,
                accent = accent,
                onUnlockClick = onRequestBiometricUnlock,
                onDisableLockClick = onToggleBiometricLock
            )
            return@Column
        }

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = DeepCockpit,
            contentColor = accent,
            edgePadding = 0.dp
        ) {
            tabs.forEachIndexed { idx, label ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    modifier = Modifier.testTag("vault_tab_${label.lowercase()}"),
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        when (selectedTab) {
            0 -> HistoryTab(
                logs = logs,
                accent = accent,
                onReplay = onReplayCommand,
                onDelete = onDeleteLog,
                onClearAll = onClearLogs
            )
            1 -> MemoriesTab(
                memories = memories,
                accent = accent,
                onAdd = onAddMemory,
                onTogglePin = onTogglePinMemory,
                onDelete = onDeleteMemory
            )
            2 -> RoutinesTab(
                routines = routines,
                accent = accent,
                onRun = onRunRoutine,
                onSave = onSaveRoutine,
                onDelete = onDeleteRoutine
            )
            3 -> ContactsTab(
                contacts = contacts,
                accent = accent,
                onPickSystemContact = onPickSystemContact,
                onAddManualContact = onAddManualContact,
                onDeleteContact = onDeleteContact
            )
            4 -> BriefingTab(
                reminders = reminders,
                dailyBriefingEnabled = dailyBriefingEnabled,
                briefingHour = briefingHour,
                briefingMinute = briefingMinute,
                accent = accent,
                onToggleDailyBriefing = onToggleDailyBriefing,
                onSpeakBriefingNow = onSpeakBriefingNow,
                onAddReminder = onAddReminder,
                onToggleReminderCompleted = onToggleReminderCompleted,
                onDeleteReminder = onDeleteReminder
            )
            5 -> MacrosTab(
                macros = macros,
                isMacroRecording = isMacroRecording,
                macroStepCount = macroStepCount,
                accent = accent,
                onToggleRecording = onToggleMacroRecording,
                onReplayMacro = onReplayMacro,
                onDeleteMacro = onDeleteMacro
            )
        }
    }
}

@Composable
private fun VaultLockedCard(
    encryptionBadgeLabel: String,
    biometricError: String?,
    accent: androidx.compose.ui.graphics.Color,
    onUnlockClick: () -> Unit,
    onDisableLockClick: () -> Unit
) {
    Surface(
        color = SmokedGlass,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .testTag("vault_locked_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = "Biometric Vault Lock",
                tint = accent,
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "VAULT LOCKED",
                style = MaterialTheme.typography.titleMedium,
                color = HudTextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Your private history, memories, routines, and quick contacts are sealed inside $encryptionBadgeLabel.",
                style = MaterialTheme.typography.bodyMedium,
                color = HudTextSecondary,
                textAlign = TextAlign.Center
            )
            if (!biometricError.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = biometricError,
                    style = MaterialTheme.typography.labelMedium,
                    color = AlertCoral,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onUnlockClick,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("biometric_unlock_button")
            ) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Unlock with Biometrics / PIN")
            }
            if (!biometricError.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDisableLockClick) {
                    Text("Disable Biometric Lock", color = HudTextSecondary)
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(
    logs: List<CommandLogEntity>,
    accent: androidx.compose.ui.graphics.Color,
    onReplay: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(logs, query) {
        if (query.isBlank()) logs else logs.filter {
            it.userPrompt.contains(query, ignoreCase = true) ||
                it.assistantReply.contains(query, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search command history...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (logs.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClearAll) {
                    Text("Clear", color = AlertCoral)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            EmptyVaultHint(
                icon = Icons.Filled.History,
                message = "No voice commands logged yet. Speak or type a command to populate your local SQLite history."
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.id }) { log ->
                    Surface(
                        color = SmokedGlass,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${log.personaUsed} · ${log.sourceMode}${if (log.hadScreenVision) " · VISION" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent
                                )
                                Text(
                                    text = formatTimestamp(log.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = HudTextSecondary
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = log.userPrompt,
                                style = MaterialTheme.typography.titleSmall,
                                color = HudTextPrimary
                            )
                            Text(
                                text = log.assistantReply,
                                style = MaterialTheme.typography.bodyMedium,
                                color = HudTextSecondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (log.tasksSummary.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = log.tasksSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StealthEmerald
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(onClick = { onReplay(log.userPrompt) }) {
                                    Icon(Icons.Filled.Replay, contentDescription = "Replay command", tint = accent)
                                }
                                IconButton(onClick = { onDelete(log.id) }) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete log", tint = AlertCoral)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoriesTab(
    memories: List<MemoryNoteEntity>,
    accent: androidx.compose.ui.graphics.Color,
    onAdd: (String) -> Unit,
    onTogglePin: (MemoryNoteEntity) -> Unit,
    onDelete: (Long) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Add private memory note...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        onAdd(draft)
                        draft = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Save memory")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (memories.isEmpty()) {
            EmptyVaultHint(
                icon = Icons.Filled.Psychology,
                message = "Say \"Remember my locker code is 4590\" or add notes here for persistent on-device AI context."
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(memories, key = { it.id }) { item ->
                    Surface(
                        color = SmokedGlass,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent
                                )
                                Text(
                                    text = item.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = HudTextPrimary
                                )
                            }
                            IconButton(onClick = { onTogglePin(item) }) {
                                Icon(
                                    imageVector = if (item.isPinned) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    contentDescription = "Pin memory",
                                    tint = accent
                                )
                            }
                            IconButton(onClick = { onDelete(item.id) }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete memory", tint = AlertCoral)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutinesTab(
    routines: List<VoiceRoutineEntity>,
    accent: androidx.compose.ui.graphics.Color,
    onRun: (VoiceRoutineEntity) -> Unit,
    onSave: (String, String, String) -> Unit,
    onDelete: (Long) -> Unit
) {
    var trigger by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = trigger,
            onValueChange = { trigger = it },
            placeholder = { Text("Trigger phrase (e.g., Gym Mode)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("Multi-step action prompt...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (trigger.isNotBlank() && prompt.isNotBlank()) {
                        onSave(trigger, "Custom voice macro", prompt)
                        trigger = ""
                        prompt = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian)
            ) {
                Text("Save")
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(routines, key = { it.id }) { routine ->
                Surface(
                    color = SmokedGlass,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRun(routine) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = routine.triggerPhrase,
                                style = MaterialTheme.typography.titleSmall,
                                color = accent
                            )
                            Text(
                                text = routine.instructionPrompt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = HudTextPrimary
                            )
                            Text(
                                text = "Runs: ${routine.usageCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = HudTextSecondary
                            )
                        }
                        IconButton(onClick = { onRun(routine) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Run routine", tint = StealthEmerald)
                        }
                        IconButton(onClick = { onDelete(routine.id) }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete routine", tint = AlertCoral)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsTab(
    contacts: List<QuickContactEntity>,
    accent: androidx.compose.ui.graphics.Color,
    onPickSystemContact: () -> Unit,
    onAddManualContact: (String, String, String, String) -> Unit,
    onDeleteContact: (Long) -> Unit
) {
    var alias by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onPickSystemContact,
            border = BorderStroke(1.dp, accent),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pick_contact_button")
        ) {
            Icon(Icons.Filled.Contacts, contentDescription = "Import contact", tint = accent)
            Spacer(Modifier.width(8.dp))
            Text("Import from Android Contacts (Zero Permission)", color = accent)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                placeholder = { Text("Alias (Mom)") },
                singleLine = true,
                modifier = Modifier.weight(0.9f)
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                placeholder = { Text("+91...") },
                singleLine = true,
                modifier = Modifier.weight(1.1f)
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    if (alias.isNotBlank() && phone.isNotBlank()) {
                        onAddManualContact(alias, alias, phone, "")
                        alias = ""
                        phone = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add contact alias")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (contacts.isEmpty()) {
            EmptyVaultHint(
                icon = Icons.Filled.Contacts,
                message = "Add quick aliases like 'Mom' or 'Boss' so voice commands can dial or message effortlessly."
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contacts, key = { it.id }) { contact ->
                    Surface(
                        color = SmokedGlass,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${contact.aliasName} (${contact.displayName})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = HudTextPrimary
                                )
                                Text(
                                    text = contact.phoneNumber.ifBlank { contact.emailAddress },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = accent
                                )
                            }
                            IconButton(onClick = { onDeleteContact(contact.id) }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete contact", tint = AlertCoral)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BriefingTab(
    reminders: List<ReminderEntity>,
    dailyBriefingEnabled: Boolean,
    briefingHour: Int,
    briefingMinute: Int,
    accent: androidx.compose.ui.graphics.Color,
    onToggleDailyBriefing: (Boolean, Int, Int) -> Unit,
    onSpeakBriefingNow: () -> Unit,
    onAddReminder: (String, Int) -> Unit,
    onToggleReminderCompleted: (Long, Boolean) -> Unit,
    onDeleteReminder: (Long) -> Unit
) {
    var reminderTitle by remember { mutableStateOf("") }
    var delayMins by remember { mutableIntStateOf(15) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = SmokedGlass,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Daily Spoken Briefing",
                            style = MaterialTheme.typography.titleSmall,
                            color = HudTextPrimary
                        )
                        val formattedTime = String.format(Locale.US, "%02d:%02d", briefingHour, briefingMinute)
                        Text(
                            text = "Scheduled $formattedTime daily (Weather + Reminders + Pinned Notes)",
                            style = MaterialTheme.typography.labelSmall,
                            color = HudTextSecondary
                        )
                    }
                    Switch(
                        checked = dailyBriefingEnabled,
                        onCheckedChange = { onToggleDailyBriefing(it, briefingHour, briefingMinute) }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onToggleDailyBriefing(dailyBriefingEnabled, (briefingHour + 23) % 24, briefingMinute) }
                    ) { Text("-1h", color = accent) }
                    OutlinedButton(
                        onClick = { onToggleDailyBriefing(dailyBriefingEnabled, (briefingHour + 1) % 24, briefingMinute) }
                    ) { Text("+1h", color = accent) }
                    Button(
                        onClick = onSpeakBriefingNow,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("speak_briefing_now_button")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Speak Now")
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = reminderTitle,
                onValueChange = { reminderTitle = it },
                placeholder = { Text("Add proactive reminder...") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (reminderTitle.isNotBlank()) {
                        onAddReminder(reminderTitle, delayMins)
                        reminderTitle = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add reminder")
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 15, 30, 60).forEach { mins ->
                FilterChip(
                    selected = delayMins == mins,
                    onClick = { delayMins = mins },
                    label = { Text("In ${mins}m") }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (reminders.isEmpty()) {
            EmptyVaultHint(
                icon = Icons.Filled.NotificationsActive,
                message = "No reminders yet. Add one above for proactive WorkManager alerts & morning briefings."
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(reminders, key = { it.id }) { rem ->
                    Surface(
                        color = SmokedGlass,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = rem.isCompleted,
                                onCheckedChange = { onToggleReminderCompleted(rem.id, it) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rem.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (rem.isCompleted) HudTextSecondary else HudTextPrimary
                                )
                                Text(
                                    text = "Due ${formatTimestamp(rem.triggerAtMillis)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent
                                )
                            }
                            IconButton(onClick = { onDeleteReminder(rem.id) }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete reminder", tint = AlertCoral)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MacrosTab(
    macros: List<ScreenMacroEntity>,
    isMacroRecording: Boolean,
    macroStepCount: Int,
    accent: androidx.compose.ui.graphics.Color,
    onToggleRecording: (String) -> Unit,
    onReplayMacro: (ScreenMacroEntity) -> Unit,
    onDeleteMacro: (Long) -> Unit
) {
    var macroName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = macroName,
            onValueChange = { macroName = it },
            placeholder = { Text("Optional macro name (e.g., Check Orders)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                onToggleRecording(macroName)
                if (isMacroRecording) macroName = ""
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isMacroRecording) AlertCoral else accent,
                contentColor = VoidObsidian
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("toggle_macro_recording_button")
        ) {
            Icon(
                imageVector = if (isMacroRecording) Icons.Filled.StopCircle else Icons.Filled.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isMacroRecording) {
                    "Stop & Save Macro ($macroStepCount steps captured)"
                } else {
                    "Start Recording Visual Screen Macro"
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        if (macros.isEmpty()) {
            EmptyVaultHint(
                icon = Icons.Filled.TouchApp,
                message = "Record cross-app taps & text steps visually (passwords always excluded), then replay with 1 tap or voice."
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(macros, key = { it.id }) { macro ->
                    Surface(
                        color = SmokedGlass,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onReplayMacro(macro) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = macro.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = accent
                                )
                                Text(
                                    text = "${macro.stepCount} UI steps · Replays: ${macro.runCount} · Say \"${macro.voiceTrigger}\"",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = HudTextSecondary
                                )
                            }
                            IconButton(onClick = { onReplayMacro(macro) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Replay macro", tint = StealthEmerald)
                            }
                            IconButton(onClick = { onDeleteMacro(macro.id) }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete macro", tint = AlertCoral)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyVaultHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = HudTextSecondary,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = HudTextSecondary
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    return SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(millis))
}
