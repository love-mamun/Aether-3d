package com.example.system

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.accessibility.AetherAccessibilityService

data class PermissionItemStatus(
    val id: String,
    val title: String,
    val description: String,
    val isGranted: Boolean,
    val isSpecialSettings: Boolean
)

data class PermissionSnapshot(
    val micGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val cameraGranted: Boolean = false,
    val coarseLocationGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val callPhoneGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val writeSettingsGranted: Boolean = false,
    val dndAccessGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val bluetoothConnectGranted: Boolean = false
) {
    val grantedCount: Int
        get() = listOf(
            micGranted,
            notificationsGranted,
            cameraGranted,
            coarseLocationGranted,
            contactsGranted,
            callPhoneGranted,
            accessibilityEnabled,
            overlayGranted,
            writeSettingsGranted,
            dndAccessGranted,
            batteryUnrestricted,
            notificationAccessGranted,
            bluetoothConnectGranted
        ).count { it }

    val totalCount: Int get() = 13

    val allEnabled: Boolean get() = grantedCount == totalCount

    fun toItemList(): List<PermissionItemStatus> = listOf(
        PermissionItemStatus(
            id = "mic",
            title = "Microphone Voice Stream",
            description = "Real-time voice commands & 'Hey Aether' wake listening",
            isGranted = micGranted,
            isSpecialSettings = false
        ),
        PermissionItemStatus(
            id = "notifications",
            title = "Agent Status Notifications",
            description = "Background wake standby & pause controls",
            isGranted = notificationsGranted,
            isSpecialSettings = false
        ),
        PermissionItemStatus(
            id = "camera",
            title = "Flashlight & Camera Hardware",
            description = "Offline torch toggle & camera launches",
            isGranted = cameraGranted,
            isSpecialSettings = false
        ),
        PermissionItemStatus(
            id = "call_phone",
            title = "Direct Voice Calling",
            description = "Place calls immediately instead of stopping on keypad",
            isGranted = callPhoneGranted,
            isSpecialSettings = false
        ),
        PermissionItemStatus(
            id = "contacts",
            title = "Voice Contact Resolution",
            description = "Resolve names like 'Call Ma' or 'Message Rahul'",
            isGranted = contactsGranted,
            isSpecialSettings = false
        ),
        PermissionItemStatus(
            id = "location",
            title = "Coarse Location Weather",
            description = "Automatic local weather without typing city",
            isGranted = coarseLocationGranted,
            isSpecialSettings = false
        ),
        PermissionItemStatus(
            id = "accessibility",
            title = "Trusted Screen-Vision Agent",
            description = "Complete WhatsApp messages, Facebook posts & YouTube playback",
            isGranted = accessibilityEnabled,
            isSpecialSettings = true
        ),
        PermissionItemStatus(
            id = "overlay",
            title = "Floating 3D Orb Overlay",
            description = "Keep a draggable voice orb & live status bubble over apps",
            isGranted = overlayGranted,
            isSpecialSettings = true
        ),
        PermissionItemStatus(
            id = "dnd_policy",
            title = "Do Not Disturb Control",
            description = "Toggle DND On/Off completely offline by voice",
            isGranted = dndAccessGranted,
            isSpecialSettings = true
        ),
        PermissionItemStatus(
            id = "write_settings",
            title = "System Brightness & Rotation",
            description = "Adjust screen brightness and auto-rotate offline",
            isGranted = writeSettingsGranted,
            isSpecialSettings = true
        ),
        PermissionItemStatus(
            id = "battery",
            title = "Unrestricted Background Mode",
            description = "Keep 'Hey Aether' responsive while screen/apps run",
            isGranted = batteryUnrestricted,
            isSpecialSettings = true
        ),
        PermissionItemStatus(
            id = "notification_listener",
            title = "Notification Copilot Hub",
            description = "Read unread message digests & send instant voice replies",
            isGranted = notificationAccessGranted,
            isSpecialSettings = true
        ),
        PermissionItemStatus(
            id = "bluetooth_connect",
            title = "Bluetooth Auto-Triggers",
            description = "Auto-brief unread alerts when earbuds or car audio connect",
            isGranted = bluetoothConnectGranted,
            isSpecialSettings = false
        )
    )
}

class PermissionsManager(private val context: Context) {

    fun checkSnapshot(): PermissionSnapshot {
        val mic = hasPermission(Manifest.permission.RECORD_AUDIO)
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val camera = hasPermission(Manifest.permission.CAMERA)
        val location = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val contacts = hasPermission(Manifest.permission.READ_CONTACTS)
        val callPhone = hasPermission(Manifest.permission.CALL_PHONE)
        val bluetoothConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
        val accessibility = isAccessibilityServiceEnabled()
        val overlay = Settings.canDrawOverlays(context)
        val writeSettings = Settings.System.canWrite(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val dndAccess = nm?.isNotificationPolicyAccessGranted == true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        val notificationAccess = isNotificationListenerEnabled()

        return PermissionSnapshot(
            micGranted = mic,
            notificationsGranted = notif,
            cameraGranted = camera,
            coarseLocationGranted = location,
            contactsGranted = contacts,
            callPhoneGranted = callPhone,
            accessibilityEnabled = accessibility,
            overlayGranted = overlay,
            writeSettingsGranted = writeSettings,
            dndAccessGranted = dndAccess,
            batteryUnrestricted = batteryUnrestricted,
            notificationAccessGranted = notificationAccess,
            bluetoothConnectGranted = bluetoothConnect
        )
    }

    fun isDefaultAssistantApp(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) &&
                rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
            ) {
                return true
            }
        }
        val assistantSetting = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant")
        }.getOrNull().orEmpty()
        val voiceInteractionSetting = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        }.getOrNull().orEmpty()
        return assistantSetting.contains(context.packageName, ignoreCase = true) ||
            voiceInteractionSetting.contains(context.packageName, ignoreCase = true)
    }

    fun buildRequestDefaultAssistantIntent(): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) &&
                !rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
            ) {
                return rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT)
            }
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    companion object {
        const val OS_HOTWORD_EXPLANATION =
            "Android hardware DSP hotword ('Hey Google' with zero mic indicator via CAPTURE_AUDIO_HOTWORD) is restricted to signature|privileged pre-installed system apps—neither Default Assistant nor Device Admin can unlock it. In 0-Mic mode Aether opens via your OS Assistant gesture/power hold or Orb; enable On-Device KWS if you want 'Hey Aether' hands-free in foreground."
    }

    fun runtimePermissionsToRequest(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return list.toTypedArray()
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        if (AetherAccessibilityService.isConnected()) return true
        val expected = "${context.packageName}/${AetherAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.contains(expected, ignoreCase = true)
    }

    fun isNotificationListenerEnabled(): Boolean {
        val expectedFlat = "${context.packageName}/com.example.service.AetherNotificationListenerService"
        val expectedShort = "${context.packageName}/.service.AetherNotificationListenerService"
        val enabledListeners = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
        }.getOrNull().orEmpty()
        return enabledListeners.contains(expectedFlat, ignoreCase = true) ||
            enabledListeners.contains(expectedShort, ignoreCase = true)
    }

    fun buildSpecialSettingsIntent(permissionId: String): Intent {
        return when (permissionId) {
            "overlay" -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "dnd_policy" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "write_settings" -> Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
            "battery" -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            "assistant_role" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            else -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
