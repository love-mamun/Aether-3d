package com.example.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.example.notifications.NotificationCopilotStore

/**
 * Passive NotificationListenerService powering Aether's Notification Copilot & Voice Reply Hub.
 * Filters noise/system/ongoing notifications and routes conversation previews into volatile RAM only.
 */
class AetherNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationCopilotStore.setListenerConnected(true)
        runCatching {
            activeNotifications?.forEach { ingestStatusBarNotification(it) }
        }
    }

    override fun onListenerDisconnected() {
        NotificationCopilotStore.setListenerConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        ingestStatusBarNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        NotificationCopilotStore.removeByKey(sbn.key)
    }

    private fun ingestStatusBarNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName.orEmpty()
        if (NotificationCopilotStore.shouldIgnoreNotificationPackage(pkg, packageName)) return

        val notification = sbn.notification ?: return
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val isMediaTransport = notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.category == Notification.CATEGORY_SERVICE ||
            notification.category == Notification.CATEGORY_PROGRESS
        if (isOngoing || isGroupSummary || isMediaTransport) return

        val extras = notification.extras
        val messagingStyle = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()

        val lastStyledMsg = messagingStyle?.messages?.lastOrNull()
        val sender = lastStyledMsg?.person?.name?.toString()?.trim()
            ?: messagingStyle?.conversationTitle?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: ""

        val preview = lastStyledMsg?.text?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
            ?: ""

        val replyHandle = NotificationCopilotStore.extractRemoteReplyHandle(notification)
        val isConversationCategory = notification.category == Notification.CATEGORY_MESSAGE ||
            notification.category == Notification.CATEGORY_EMAIL ||
            notification.category == Notification.CATEGORY_SOCIAL ||
            replyHandle != null ||
            messagingStyle != null

        // Keep copilot focused on real actionable communications & alerts with readable body text
        if (!isConversationCategory && preview.isBlank()) return
        if (sender.isBlank() && preview.isBlank()) return

        val appLabel = runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))

        NotificationCopilotStore.upsertExternalNotification(
            key = sbn.key,
            packageName = pkg,
            appLabel = appLabel,
            senderName = sender.ifBlank { appLabel },
            previewText = preview,
            timestamp = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            replyIntent = replyHandle?.actionIntent,
            remoteInputs = replyHandle?.remoteInputs.orEmpty()
        )
    }
}
