package com.example.briefing

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object BriefingScheduler {

    private const val DAILY_BRIEFING_WORK_NAME = "aether_daily_briefing"

    fun scheduleDailyBriefing(
        context: Context,
        enabled: Boolean,
        hour: Int,
        minute: Int
    ) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            wm.cancelUniqueWork(DAILY_BRIEFING_WORK_NAME)
            return
        }

        val initialDelayMs = computeDailyBriefingInitialDelayMs(
            nowMillis = System.currentTimeMillis(),
            hour = hour,
            minute = minute
        )

        val request = PeriodicWorkRequestBuilder<BriefingWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(BriefingWorker.KEY_KIND, BriefingWorker.KIND_DAILY_BRIEFING)
                    .build()
            )
            .build()

        wm.enqueueUniquePeriodicWork(
            DAILY_BRIEFING_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleReminder(
        context: Context,
        reminderId: Long,
        title: String,
        triggerAtMillis: Long
    ) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val delayMs = computeReminderDelayMs(triggerAtMillis = triggerAtMillis, nowMillis = System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<BriefingWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(BriefingWorker.KEY_KIND, BriefingWorker.KIND_REMINDER)
                    .putLong(BriefingWorker.KEY_REMINDER_ID, reminderId)
                    .putString(BriefingWorker.KEY_REMINDER_TITLE, title)
                    .build()
            )
            .build()

        wm.enqueueUniqueWork(
            "aether_reminder_$reminderId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelReminder(context: Context, reminderId: Long) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("aether_reminder_$reminderId")
    }

    internal fun computeDailyBriefingInitialDelayMs(
        nowMillis: Long,
        hour: Int,
        minute: Int,
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): Long {
        val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val next = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return (next.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L)
    }

    internal fun computeReminderDelayMs(triggerAtMillis: Long, nowMillis: Long): Long {
        return (triggerAtMillis - nowMillis).coerceAtLeast(1_000L)
    }
}
