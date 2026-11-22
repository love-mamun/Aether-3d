package com.example.briefing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.data.local.VaultRepository

class BriefingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: KIND_DAILY_BRIEFING
        val vault = VaultRepository(applicationContext)

        return when (kind) {
            KIND_REMINDER -> {
                val reminderId = inputData.getLong(KEY_REMINDER_ID, -1L)
                val title = inputData.getString(KEY_REMINDER_TITLE).orEmpty().ifBlank { "Scheduled Reminder" }
                val pending = vault.getPendingReminders()
                val stillActive = reminderId <= 0L || pending.any { it.id == reminderId && !it.isCompleted }
                if (stillActive) {
                    postBriefingNotification(
                        notifId = (4200 + (reminderId.coerceAtLeast(1L) % 500)).toInt(),
                        title = "Aether Reminder",
                        body = title,
                        triggerBriefing = false,
                        reminderTitle = title
                    )
                }
                Result.success()
            }
            else -> {
                val prefs = vault.loadPreferences()
                if (!prefs.dailyBriefingEnabled) {
                    return Result.success()
                }
                val pendingCount = vault.getPendingReminders().size
                val summary = if (pendingCount > 0) {
                    "Tap to hear your morning briefing & $pendingCount pending reminder(s)."
                } else {
                    "Tap to hear your live weather, telemetry & memory briefing."
                }
                postBriefingNotification(
                    notifId = 4100,
                    title = "Aether Daily Briefing Ready",
                    body = summary,
                    triggerBriefing = true,
                    reminderTitle = ""
                )
                Result.success()
            }
        }
    }

    private fun postBriefingNotification(
        notifId: Int,
        title: String,
        body: String,
        triggerBriefing: Boolean,
        reminderTitle: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aether Briefings & Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Proactive daily briefings and scheduled voice reminders"
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TRIGGER_BRIEFING, triggerBriefing)
            if (reminderTitle.isNotBlank()) {
                putExtra(EXTRA_REMINDER_TITLE, reminderTitle)
            }
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            notifId,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aether_notif)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(notifId, notif)
    }

    companion object {
        const val CHANNEL_ID = "aether_briefing_channel"
        const val KEY_KIND = "kind"
        const val KIND_DAILY_BRIEFING = "daily_briefing"
        const val KIND_REMINDER = "reminder"
        const val KEY_REMINDER_ID = "reminder_id"
        const val KEY_REMINDER_TITLE = "reminder_title"
        const val EXTRA_TRIGGER_BRIEFING = "aether_trigger_briefing"
        const val EXTRA_REMINDER_TITLE = "aether_reminder_title"
    }
}
