package com.example.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class UnreadNotificationCluster(
    val clusterId: String,
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val senderName: String,
    val latestMessage: String,
    val messageCount: Int,
    val timestamp: Long,
    val hasQuickReply: Boolean
)

internal data class RemoteReplyActionHandle(
    val actionIntent: PendingIntent,
    val remoteInputs: List<RemoteInput>,
    val primaryResultKey: String
)

internal data class NotificationEntryRecord(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val senderName: String,
    val previewText: String,
    val timestamp: Long,
    val replyHandle: RemoteReplyActionHandle?
)

/**
 * Ephemeral in-memory Notification Copilot ring buffer & RemoteInput dispatcher.
 * Third-party message previews stay strictly in volatile RAM and are cleared on reply, dismiss, or reboot.
 */
object NotificationCopilotStore {

    private const val MAX_STORED_ENTRIES = 40
    private val lock = Any()
    private val entriesByKey = linkedMapOf<String, NotificationEntryRecord>()

    private val _clusters = MutableStateFlow<List<UnreadNotificationCluster>>(emptyList())
    val clusters: StateFlow<List<UnreadNotificationCluster>> = _clusters.asStateFlow()

    @Volatile
    var isListenerConnected: Boolean = false
        private set

    fun setListenerConnected(connected: Boolean) {
        isListenerConnected = connected
        if (!connected) {
            synchronized(lock) {
                entriesByKey.clear()
                emitSnapshotLocked()
            }
        }
    }

    internal fun upsertEntry(record: NotificationEntryRecord) {
        if (record.senderName.isBlank() && record.previewText.isBlank()) return
        synchronized(lock) {
            entriesByKey[record.key] = record
            if (entriesByKey.size > MAX_STORED_ENTRIES) {
                val oldestKey = entriesByKey.entries.minByOrNull { it.value.timestamp }?.key
                if (oldestKey != null) entriesByKey.remove(oldestKey)
            }
            emitSnapshotLocked()
        }
    }

    fun upsertExternalNotification(
        key: String,
        packageName: String,
        appLabel: String,
        senderName: String,
        previewText: String,
        timestamp: Long,
        replyIntent: PendingIntent? = null,
        remoteInputs: List<RemoteInput> = emptyList()
    ) {
        val handle = if (replyIntent != null && remoteInputs.isNotEmpty()) {
            RemoteReplyActionHandle(
                actionIntent = replyIntent,
                remoteInputs = remoteInputs,
                primaryResultKey = remoteInputs.first().resultKey
            )
        } else {
            null
        }
        upsertEntry(
            NotificationEntryRecord(
                key = key,
                packageName = packageName.trim(),
                appLabel = appLabel.trim().ifBlank { packageName.substringAfterLast('.') },
                senderName = senderName.trim().ifBlank { appLabel },
                previewText = previewText.trim(),
                timestamp = timestamp,
                replyHandle = handle
            )
        )
    }

    fun removeByKey(key: String) {
        synchronized(lock) {
            if (entriesByKey.remove(key) != null) {
                emitSnapshotLocked()
            }
        }
    }

    fun dismissCluster(clusterId: String) {
        synchronized(lock) {
            val keysToRemove = entriesByKey.values
                .filter { buildClusterId(it.packageName, it.senderName) == clusterId }
                .map { it.key }
            if (keysToRemove.isNotEmpty()) {
                keysToRemove.forEach { entriesByKey.remove(it) }
                emitSnapshotLocked()
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            entriesByKey.clear()
            emitSnapshotLocked()
        }
    }

    fun findClusterByQuery(query: String): UnreadNotificationCluster? {
        val clean = query.trim().lowercase(Locale.ROOT)
        val snapshot = _clusters.value
        if (snapshot.isEmpty()) return null
        if (clean.isBlank()) return snapshot.firstOrNull { it.hasQuickReply } ?: snapshot.first()
        return snapshot.firstOrNull { it.senderName.equals(clean, ignoreCase = true) }
            ?: snapshot.firstOrNull { it.senderName.lowercase(Locale.ROOT).contains(clean) }
            ?: snapshot.firstOrNull { it.appLabel.lowercase(Locale.ROOT).contains(clean) }
            ?: snapshot.firstOrNull { clean.contains(it.senderName.lowercase(Locale.ROOT)) }
    }

    fun buildSpokenDigest(preferBangla: Boolean = false, maxSenders: Int = 5): String {
        val snapshot = _clusters.value
        if (snapshot.isEmpty()) {
            return if (preferBangla) {
                "আপনার কোনো অপঠিত মেসেজ বা নোটিফিকেশন নেই।"
            } else {
                "You have no unread message notifications right now."
            }
        }
        val top = snapshot.take(maxSenders)
        val totalMsgs = snapshot.sumOf { it.messageCount }
        val replyReadyCount = snapshot.count { it.hasQuickReply }
        val details = top.joinToString("; ") { c ->
            val preview = c.latestMessage.take(90)
            if (preview.isNotBlank()) {
                "${c.senderName} on ${c.appLabel}: \"$preview\""
            } else {
                "${c.messageCount} alert from ${c.senderName} on ${c.appLabel}"
            }
        }
        return if (preferBangla) {
            val extra = if (snapshot.size > top.size) " আরও ${snapshot.size - top.size}টি থ্রেড আছে।" else ""
            val replyHint = if (replyReadyCount > 0) " আপনি বললেই সরাসরি ভয়েস রিপ্লাই পাঠানো যাবে।" else ""
            "আপনার ${totalMsgs}টি অপঠিত নোটিফিকেশন আছে। $details।$extra$replyHint"
        } else {
            val extra = if (snapshot.size > top.size) " Plus ${snapshot.size - top.size} more threads." else ""
            val replyHint = if (replyReadyCount > 0) " Say reply with your message to answer directly." else ""
            "You have $totalMsgs unread notifications across ${snapshot.size} threads. $details.$extra$replyHint"
        }
    }

    fun dispatchRemoteReply(
        context: Context,
        clusterId: String,
        replyText: String
    ): Result<String> {
        val cleanReply = replyText.trim()
        if (cleanReply.isBlank()) {
            return Result.failure(IllegalArgumentException("Reply text cannot be empty."))
        }
        val targetRecord = synchronized(lock) {
            entriesByKey.values
                .filter { buildClusterId(it.packageName, it.senderName) == clusterId && it.replyHandle != null }
                .maxByOrNull { it.timestamp }
        } ?: return Result.failure(IllegalStateException("Quick reply is unavailable for this notification thread."))

        val handle = targetRecord.replyHandle
            ?: return Result.failure(IllegalStateException("RemoteInput handle missing."))

        return runCatching {
            val localIntent = Intent()
            val resultsBundle = Bundle()
            handle.remoteInputs.forEach { input ->
                resultsBundle.putCharSequence(input.resultKey, cleanReply)
            }
            RemoteInput.addResultsToIntent(
                handle.remoteInputs.toTypedArray(),
                localIntent,
                resultsBundle
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                localIntent.clipData = ClipData.newIntent("RemoteReply", Intent())
            }
            handle.actionIntent.send(context, 0, localIntent)
            dismissCluster(clusterId)
            "Replied to ${targetRecord.senderName} on ${targetRecord.appLabel}: \"$cleanReply\""
        }
    }

    private fun buildClusterId(packageName: String, sender: String): String {
        return "${packageName.lowercase(Locale.ROOT)}::${sender.trim().lowercase(Locale.ROOT)}"
    }

    private fun emitSnapshotLocked() {
        val grouped = entriesByKey.values.groupBy { buildClusterId(it.packageName, it.senderName) }
        val result = grouped.map { (clusterId, list) ->
            val latest = list.maxByOrNull { it.timestamp } ?: list.first()
            UnreadNotificationCluster(
                clusterId = clusterId,
                notificationKey = latest.key,
                packageName = latest.packageName,
                appLabel = latest.appLabel,
                senderName = latest.senderName,
                latestMessage = latest.previewText,
                messageCount = list.size,
                timestamp = latest.timestamp,
                hasQuickReply = list.any { it.replyHandle != null }
            )
        }.sortedByDescending { it.timestamp }
        _clusters.value = result
    }

    fun shouldIgnoreNotificationPackage(pkg: String, selfPackage: String): Boolean {
        if (pkg.isBlank() || pkg == selfPackage) return true
        val lower = pkg.lowercase(Locale.ROOT)
        return lower == "android" ||
            lower == "com.android.systemui" ||
            lower == "com.android.providers.downloads"
    }

    internal fun extractRemoteReplyHandle(notification: Notification): RemoteReplyActionHandle? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val inputs = action.remoteInputs
            if (inputs != null && inputs.isNotEmpty() && action.actionIntent != null) {
                return RemoteReplyActionHandle(
                    actionIntent = action.actionIntent,
                    remoteInputs = inputs.toList(),
                    primaryResultKey = inputs.first().resultKey
                )
            }
        }
        return null
    }
}
