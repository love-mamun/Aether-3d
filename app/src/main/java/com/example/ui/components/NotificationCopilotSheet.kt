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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.notifications.UnreadNotificationCluster
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.DeepCockpit
import com.example.ui.theme.HudTextPrimary
import com.example.ui.theme.HudTextSecondary
import com.example.ui.theme.SmokedGlass
import com.example.ui.theme.SmokedGlassElevated
import com.example.ui.theme.SolarGold
import com.example.ui.theme.StealthEmerald
import com.example.ui.theme.VoidObsidian

@Composable
fun UnreadDigestPill(
    clusters: List<UnreadNotificationCluster>,
    listenerConnected: Boolean,
    onOpenCopilot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalCount = clusters.sumOf { it.messageCount }
    val latest = clusters.firstOrNull()
    val accent = MaterialTheme.colorScheme.primary
    val pillColor = if (totalCount > 0) SolarGold else accent

    Surface(
        color = SmokedGlass,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, pillColor.copy(alpha = 0.55f)),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag("unread_digest_pill")
            .clickable(onClick = onOpenCopilot)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (listenerConnected) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
                contentDescription = "Notification Copilot Hub",
                tint = pillColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            val label = when {
                totalCount > 0 && latest != null ->
                    "$totalCount UNREAD · ${latest.senderName} (${latest.appLabel})"
                !listenerConnected -> "ENABLE NOTIFICATION COPILOT"
                else -> "COPILOT · 0 UNREAD"
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = pillColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCopilotSheet(
    clusters: List<UnreadNotificationCluster>,
    listenerEnabled: Boolean,
    onDismiss: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSpeakDigest: () -> Unit,
    onSendReply: (clusterId: String, replyText: String) -> Unit,
    onDismissCluster: (clusterId: String) -> Unit,
    onClearAll: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepCockpit,
        contentColor = HudTextPrimary
    ) {
        NotificationCopilotContent(
            clusters = clusters,
            listenerEnabled = listenerEnabled,
            onClose = onDismiss,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onSpeakDigest = onSpeakDigest,
            onSendReply = onSendReply,
            onDismissCluster = onDismissCluster,
            onClearAll = onClearAll,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .navigationBarsPadding()
        )
    }
}

@Composable
fun NotificationCopilotContent(
    clusters: List<UnreadNotificationCluster>,
    listenerEnabled: Boolean,
    onClose: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSpeakDigest: () -> Unit,
    onSendReply: (clusterId: String, replyText: String) -> Unit,
    onDismissCluster: (clusterId: String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val replyDrafts = remember { mutableStateMapOf<String, String>() }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .testTag("notification_copilot_sheet")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "NOTIFICATION COPILOT",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Close", color = HudTextSecondary)
            }
        }

        if (!listenerEnabled) {
            Spacer(Modifier.height(6.dp))
            Surface(
                color = SolarGold.copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, SolarGold.copy(alpha = 0.55f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notification_access_banner")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Grant Notification Access so Aether can cluster WhatsApp, Messenger & SMS alerts and reply inline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HudTextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onRequestNotificationPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = SolarGold, contentColor = VoidObsidian),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("grant_notification_access_button")
                    ) {
                        Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Notification Listener Settings")
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSpeakDigest,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = VoidObsidian),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("speak_digest_button")
            ) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Speak Digest")
            }
            OutlinedButton(
                onClick = onClearAll,
                enabled = clusters.isNotEmpty(),
                border = BorderStroke(1.dp, AlertCoral.copy(alpha = 0.55f)),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("clear_all_notifications_button")
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all notifications", tint = AlertCoral, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear", color = AlertCoral)
            }
        }

        Spacer(Modifier.height(10.dp))
        if (clusters.isEmpty()) {
            Surface(
                color = SmokedGlass,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsOff,
                        contentDescription = null,
                        tint = HudTextSecondary,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "No unread notifications in volatile memory. Say \"Read my notifications\" anytime.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HudTextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(clusters, key = { it.clusterId }) { cluster ->
                    val draft = replyDrafts[cluster.clusterId].orEmpty()
                    Surface(
                        color = SmokedGlassElevated,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            1.dp,
                            if (cluster.hasQuickReply) StealthEmerald.copy(alpha = 0.45f) else accent.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("notif_cluster_card")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${cluster.senderName} · ${cluster.appLabel}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${cluster.messageCount} unread${if (cluster.hasQuickReply) " · Quick Reply Ready" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (cluster.hasQuickReply) StealthEmerald else HudTextSecondary
                                    )
                                }
                                IconButton(
                                    onClick = { onDismissCluster(cluster.clusterId) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Dismiss notification thread",
                                        tint = HudTextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            if (cluster.latestMessage.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = cluster.latestMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = HudTextPrimary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (cluster.hasQuickReply) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = draft,
                                        onValueChange = { replyDrafts[cluster.clusterId] = it },
                                        placeholder = { Text("Quick voice or typed reply...") },
                                        singleLine = true,
                                        leadingIcon = {
                                            Icon(Icons.Filled.Reply, contentDescription = null, tint = StealthEmerald, modifier = Modifier.size(16.dp))
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = StealthEmerald,
                                            unfocusedBorderColor = accent.copy(alpha = 0.35f),
                                            focusedTextColor = HudTextPrimary,
                                            unfocusedTextColor = HudTextPrimary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("notif_reply_input")
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            if (draft.isNotBlank()) {
                                                onSendReply(cluster.clusterId, draft)
                                                replyDrafts.remove(cluster.clusterId)
                                            }
                                        },
                                        enabled = draft.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = StealthEmerald, contentColor = VoidObsidian),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("notif_reply_send_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send notification reply",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
