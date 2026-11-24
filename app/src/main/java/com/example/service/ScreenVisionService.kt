package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.vision.MediaProjectionScreenGrabber

/**
 * Foreground service strictly scoped to [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION].
 * Started ONLY after explicit user consent from MediaProjectionManager.createScreenCaptureIntent().
 */
class ScreenVisionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VISION) {
            MediaProjectionScreenGrabber.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val notification = buildNotification()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)

        if (intent?.action == ACTION_START_VISION) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (resultCode == Activity.RESULT_OK && resultData != null) {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mgr.getMediaProjection(resultCode, resultData)
                if (projection != null) {
                    MediaProjectionScreenGrabber.attachProjection(projection, applicationContext)
                } else {
                    stopSelf()
                }
            } else {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MediaProjectionScreenGrabber.release()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.screen_vision_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.screen_vision_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            210,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            211,
            Intent(this, ScreenVisionService::class.java).apply { action = ACTION_STOP_VISION },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.screen_vision_title))
            .setContentText(getString(R.string.screen_vision_subtitle))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_stop_vision), stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "aether_screen_vision_channel"
        const val NOTIFICATION_ID = 4202
        const val ACTION_START_VISION = "com.example.action.START_SCREEN_VISION"
        const val ACTION_STOP_VISION = "com.example.action.STOP_SCREEN_VISION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun startWithConsent(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenVisionService::class.java).apply {
                action = ACTION_START_VISION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenVisionService::class.java).apply {
                action = ACTION_STOP_VISION
            }
            context.startService(intent)
        }
    }
}
