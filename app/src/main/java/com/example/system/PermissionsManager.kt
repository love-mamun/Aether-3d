package com.example.system

import android.Manifest
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
    val accessibilityEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val writeSettingsGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false
) {
    val grantedCount: Int
        get() = listOf(
            micGranted,
            notificationsGranted,
            cameraGranted,
            coarseLocationGranted,
            contactsGranted,
            accessibilityEnabled,
            overlayGranted,
            writeSettingsGranted,
            batteryUnrestricted
        ).count { it }

    val totalCount: Int get() = 9

    val allEnabled: Boolean get() = grantedCount == totalCount

    fun toItemList(): List<PermissionItemStatus> = listOf(
        PermissionItemStatus(
            id = "mic",
            title = "Microphone Voice Stream",
            description = "Real-time voice commands & 3D audio core",
            isGranted = micGranted,
            isSpecialSettings = false
        ),
        PermissionItemStatus(
            id = "notifications",
            title = "Agent Status Notifications",
            description = "Background multitasking & pause controls",
            isGranted = notificationsGranted,
            isSpecialSettings = false
        ),
        PermissionItemStatus(
            id = "camera",
            title = "Flashlight & Camera Hardware",
            description = "Torch control & camera command launches",
            isGranted = cameraGranted,
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
            id = "contacts",
            title = "Voice Contact Resolution",
            description = "Resolve names like 'Call Mom' or 'Message Rahul'",
            isGranted = contactsGranted,
            isSpecialSettings = false
        ),
        PermissionItemStatus(
            id = "overlay",
            title = "Floating 3D Orb Overlay",
            description = "Keep a draggable voice orb & status bubble over any app",
            isGranted = overlayGranted,
            isSpecialSettings = true
        ),
        PermissionItemStatus(
            id = "accessibility",
            title = "Trusted Screen-Vision Agent",
            description = "See active screen, tap buttons, scroll, type & OS keys",
            isGranted = accessibilityEnabled,
            isSpecialSettings = true
        ),
        PermissionItemStatus(
            id = "write_settings",
            title = "System Brightness & Rotation",
            description = "Adjust screen brightness and auto-rotate by voice",
            isGranted = writeSettingsGranted,
            isSpecialSettings = true
        ),
        PermissionItemStatus(
            id = "battery",
            title = "Unrestricted Background Mode",
            description = "Prevent OS sleep while voice agent runs in background",
            isGranted = batteryUnrestricted,
            isSpecialSettings = true
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
        val accessibility = isAccessibilityServiceEnabled()
        val overlay = Settings.canDrawOverlays(context)
        val writeSettings = Settings.System.canWrite(context)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

        return PermissionSnapshot(
            micGranted = mic,
            notificationsGranted = notif,
            cameraGranted = camera,
            coarseLocationGranted = location,
            contactsGranted = contacts,
            accessibilityEnabled = accessibility,
            overlayGranted = overlay,
            writeSettingsGranted = writeSettings,
            batteryUnrestricted = batteryUnrestricted
        )
    }

    fun runtimePermissionsToRequest(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
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

    fun buildSpecialSettingsIntent(permissionId: String): Intent {
        return when (permissionId) {
            "overlay" -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "write_settings" -> Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
            "battery" -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
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
