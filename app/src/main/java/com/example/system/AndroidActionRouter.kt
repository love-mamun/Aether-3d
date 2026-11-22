package com.example.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.example.accessibility.AetherAccessibilityService
import com.example.ai.ActionType
import com.example.ai.PlannedStep
import com.example.data.local.VaultRepository
import com.example.tools.FreeWebToolsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale

data class ActionExecutionResult(
    val success: Boolean,
    val detail: String,
    val toolOutputForSummary: String? = null,
    val torchStateChanged: Boolean? = null
)

class AndroidActionRouter(
    private val context: Context,
    private val telemetryProvider: DeviceTelemetryProvider,
    private val vaultRepository: VaultRepository,
    private val webTools: FreeWebToolsRepository
) {

    suspend fun executeStep(
        step: PlannedStep,
        splitScreenEnabled: Boolean
    ): ActionExecutionResult {
        return when (step.actionType) {
            ActionType.SPEAK_ONLY, ActionType.SCREEN_EXPLAIN ->
                ActionExecutionResult(true, "Response ready")

            ActionType.WEATHER_LOOKUP -> {
                val coords = telemetryProvider.getLastCoarseCoordinates()
                val weather = webTools.fetchWeather(
                    cityQuery = step.target,
                    fallbackLat = coords?.first,
                    fallbackLon = coords?.second
                )
                ActionExecutionResult(true, weather, toolOutputForSummary = weather)
            }

            ActionType.WIKIPEDIA_LOOKUP -> {
                val summary = webTools.fetchWikipediaSummary(step.target.ifBlank { step.textPayload })
                ActionExecutionResult(true, summary, toolOutputForSummary = summary)
            }

            ActionType.CURRENCY_CONVERT -> {
                val parts = step.target.split("->", "to", "/", " ").map { it.trim() }.filter { it.isNotBlank() }
                val from = parts.getOrNull(0) ?: "USD"
                val to = parts.getOrNull(1) ?: step.textPayload.ifBlank { "INR" }
                val res = webTools.convertCurrency(step.doubleValue, from, to)
                ActionExecutionResult(true, res, toolOutputForSummary = res)
            }

            ActionType.SAVE_MEMORY -> {
                val fact = step.textPayload.ifBlank { step.target }.trim()
                if (fact.isBlank()) {
                    ActionExecutionResult(false, "Nothing specified to save.")
                } else {
                    vaultRepository.saveMemory(fact, "FACT")
                    ActionExecutionResult(true, "Saved to SQLite Memory")
                }
            }

            ActionType.CREATE_ROUTINE -> {
                val trigger = step.target.ifBlank { "Custom Routine" }
                val prompt = step.textPayload.ifBlank { step.target }
                vaultRepository.saveRoutine(
                    triggerPhrase = trigger,
                    description = "Voice-created routine",
                    instructionPrompt = prompt
                )
                ActionExecutionResult(true, "Saved routine '$trigger' to SQLite")
            }

            ActionType.TOGGLE_TORCH -> toggleTorch(step.boolValue)

            ActionType.ADJUST_VOLUME -> adjustVolume(step.intValue, step.target)

            ActionType.CONTROL_MEDIA -> controlMedia(step.target)

            ActionType.SET_BRIGHTNESS -> setBrightness(step.intValue)

            ActionType.SET_AUTO_ROTATE -> setAutoRotate(step.boolValue)

            ActionType.LAUNCH_APP -> launchApp(step.target, splitScreenEnabled)

            ActionType.SEARCH_IN_APP -> searchInApp(
                appName = step.target,
                query = step.textPayload.ifBlank { step.target },
                splitScreenEnabled = splitScreenEnabled
            )

            ActionType.SET_ALARM -> setAlarm(step.intValue, step.secondaryIntValue, step.textPayload)

            ActionType.SET_TIMER -> setTimer(step.intValue, step.textPayload)

            ActionType.OPEN_SETTINGS -> openSettings(step.target)

            ActionType.DIAL_CONTACT -> dialContact(step.target)

            ActionType.SEND_MESSAGE -> sendMessage(step.target, step.textPayload)

            ActionType.DRAFT_EMAIL -> draftEmail(step.target, step.textPayload)

            ActionType.NAVIGATE_MAPS -> navigateMaps(step.target.ifBlank { step.textPayload })

            ActionType.PLAY_MEDIA -> playMedia(step.target.ifBlank { step.textPayload }, splitScreenEnabled)

            ActionType.WEB_SEARCH -> webSearch(step.target.ifBlank { step.textPayload })

            ActionType.CALENDAR_EVENT -> createCalendarEvent(step.target, step.textPayload)

            ActionType.UI_TAP_TEXT -> {
                val svc = AetherAccessibilityService.currentInstance
                    ?: return ActionExecutionResult(false, "Enable Trusted Screen Agent in Permissions")
                val ok = svc.clickNodeByText(step.target)
                ActionExecutionResult(ok, if (ok) "Tapped '${step.target}'" else "Control '${step.target}' not found on screen")
            }

            ActionType.UI_TAP_COORDINATES -> {
                val svc = AetherAccessibilityService.currentInstance
                    ?: return ActionExecutionResult(false, "Enable Trusted Screen Agent in Permissions")
                val ok = svc.tapNormalizedCoordinates(step.intValue, step.secondaryIntValue)
                ActionExecutionResult(ok, if (ok) "Tapped [${step.intValue}, ${step.secondaryIntValue}]" else "Gesture tap failed")
            }

            ActionType.UI_INPUT_TEXT -> {
                val svc = AetherAccessibilityService.currentInstance
                    ?: return ActionExecutionResult(false, "Enable Trusted Screen Agent in Permissions")
                val ok = svc.inputText(step.target, step.textPayload, submitSearch = true)
                ActionExecutionResult(ok, if (ok) "Typed '${step.textPayload}'" else "No editable field found")
            }

            ActionType.UI_SCROLL -> {
                val svc = AetherAccessibilityService.currentInstance
                    ?: return ActionExecutionResult(false, "Enable Trusted Screen Agent in Permissions")
                val dir = step.target.ifBlank { "down" }
                val ok = svc.scrollScreen(dir)
                ActionExecutionResult(ok, if (ok) "Scrolled $dir" else "Could not scroll")
            }

            ActionType.GLOBAL_ACTION -> {
                val svc = AetherAccessibilityService.currentInstance
                    ?: return ActionExecutionResult(false, "Enable Trusted Screen Agent in Permissions")
                val action = step.target.ifBlank { "HOME" }
                val ok = svc.performSystemGlobalAction(action)
                ActionExecutionResult(ok, if (ok) "Executed OS action: $action" else "Unsupported OS action ($action)")
            }
        }
    }

    private fun toggleTorch(turnOn: Boolean): ActionExecutionResult {
        return try {
            val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camMgr.cameraIdList.firstOrNull { id ->
                camMgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ActionExecutionResult(false, "No flashlight hardware available")
            camMgr.setTorchMode(cameraId, turnOn)
            ActionExecutionResult(
                success = true,
                detail = if (turnOn) "Flashlight ON" else "Flashlight OFF",
                torchStateChanged = turnOn
            )
        } catch (e: Exception) {
            ActionExecutionResult(false, "Flashlight error: ${e.localizedMessage ?: "unavailable"}")
        }
    }

    private fun adjustVolume(percent: Int, target: String): ActionExecutionResult {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = when {
                target.contains("ring", ignoreCase = true) -> AudioManager.STREAM_RING
                target.contains("alarm", ignoreCase = true) -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }
            val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
            val current = audio.getStreamVolume(stream)
            val newVol = when {
                target.contains("mute", ignoreCase = true) -> 0
                target.contains("up", ignoreCase = true) || target.contains("increase", ignoreCase = true) ->
                    (current + (max / 5).coerceAtLeast(1)).coerceAtMost(max)
                target.contains("down", ignoreCase = true) || target.contains("decrease", ignoreCase = true) ->
                    (current - (max / 5).coerceAtLeast(1)).coerceAtLeast(0)
                else -> ((percent.coerceIn(0, 100) / 100f) * max).toInt()
            }
            audio.setStreamVolume(stream, newVol, AudioManager.FLAG_SHOW_UI)
            val actualPct = ((newVol.toFloat() / max) * 100f).toInt()
            ActionExecutionResult(true, "Volume set to $actualPct%")
        } catch (e: Exception) {
            ActionExecutionResult(false, "Volume control failed: ${e.localizedMessage}")
        }
    }

    private fun controlMedia(command: String): ActionExecutionResult {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val keyCode = when {
                command.contains("next", ignoreCase = true) || command.contains("skip", ignoreCase = true) ->
                    KeyEvent.KEYCODE_MEDIA_NEXT
                command.contains("prev", ignoreCase = true) || command.contains("back", ignoreCase = true) ->
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS
                command.contains("stop", ignoreCase = true) -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            ActionExecutionResult(true, "Media command sent")
        } catch (e: Exception) {
            ActionExecutionResult(false, "Media control failed: ${e.localizedMessage}")
        }
    }

    private fun setBrightness(percent: Int): ActionExecutionResult {
        if (!Settings.System.canWrite(context)) {
            openSettings("display")
            return ActionExecutionResult(false, "Grant System Write Settings in Permissions (opened Display Settings)")
        }
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val clamped = percent.coerceIn(5, 100)
            val value = ((clamped / 100f) * 255f).toInt().coerceIn(10, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            ActionExecutionResult(true, "Brightness set to $clamped%")
        } catch (e: Exception) {
            ActionExecutionResult(false, "Brightness change failed: ${e.localizedMessage}")
        }
    }

    private fun setAutoRotate(enabled: Boolean): ActionExecutionResult {
        if (!Settings.System.canWrite(context)) {
            return ActionExecutionResult(false, "Grant System Write Settings in Permissions for auto-rotate")
        }
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (enabled) 1 else 0
            )
            ActionExecutionResult(true, if (enabled) "Auto-rotate ON" else "Auto-rotate OFF")
        } catch (e: Exception) {
            ActionExecutionResult(false, "Auto-rotate update failed")
        }
    }

    private fun launchApp(appName: String, splitScreenEnabled: Boolean): ActionExecutionResult {
        val clean = appName.trim()
        if (clean.equals("camera", ignoreCase = true)) {
            return startActivitySafe(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), splitScreenEnabled, "Opened Camera")
        }
        val appMap = telemetryProvider.queryLauncherAppMap()
        val match = appMap.entries.firstOrNull { it.key.equals(clean, ignoreCase = true) }
            ?: appMap.entries.firstOrNull { it.key.contains(clean, ignoreCase = true) }
            ?: appMap.entries.firstOrNull { clean.contains(it.key, ignoreCase = true) }

        if (match != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(match.value)
            if (launchIntent != null) {
                return startActivitySafe(launchIntent, splitScreenEnabled, "Launched ${match.key}")
            }
        }

        return when {
            clean.contains("setting", ignoreCase = true) -> openSettings("")
            clean.contains("phone", ignoreCase = true) || clean.contains("dialer", ignoreCase = true) ->
                startActivitySafe(Intent(Intent.ACTION_DIAL), splitScreenEnabled, "Opened Dialer")
            clean.contains("clock", ignoreCase = true) || clean.contains("alarm", ignoreCase = true) ->
                startActivitySafe(Intent(AlarmClock.ACTION_SHOW_ALARMS), splitScreenEnabled, "Opened Clock")
            clean.contains("browser", ignoreCase = true) || clean.contains("chrome", ignoreCase = true) ->
                startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")), splitScreenEnabled, "Opened Browser")
            else -> ActionExecutionResult(false, "App '$clean' not found on device")
        }
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): ActionExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        val formatted = String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        return startActivitySafe(intent, false, "Alarm intent dispatched ($formatted)")
    }

    private fun setTimer(seconds: Int, label: String): ActionExecutionResult {
        val safeSec = seconds.coerceAtLeast(5)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, safeSec)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return startActivitySafe(intent, false, "Timer set (${safeSec}s)")
    }

    private fun openSettings(panel: String): ActionExecutionResult {
        val action = when {
            panel.contains("wifi", ignoreCase = true) -> Settings.ACTION_WIFI_SETTINGS
            panel.contains("bluetooth", ignoreCase = true) -> Settings.ACTION_BLUETOOTH_SETTINGS
            panel.contains("display", ignoreCase = true) -> Settings.ACTION_DISPLAY_SETTINGS
            panel.contains("sound", ignoreCase = true) || panel.contains("volume", ignoreCase = true) ->
                Settings.ACTION_SOUND_SETTINGS
            panel.contains("battery", ignoreCase = true) -> Intent.ACTION_POWER_USAGE_SUMMARY
            panel.contains("location", ignoreCase = true) -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            panel.contains("access", ignoreCase = true) -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return startActivitySafe(Intent(action), false, "Opened ${panel.ifBlank { "System" }} Settings")
    }

    private suspend fun dialContact(target: String): ActionExecutionResult {
        val phone = resolvePhoneNumber(target)
        val uri = if (phone != null) Uri.parse("tel:$phone") else Uri.parse("tel:")
        return startActivitySafe(Intent(Intent.ACTION_DIAL, uri), false, "Dialer ready for ${phone ?: target}")
    }

    private suspend fun sendMessage(target: String, body: String): ActionExecutionResult {
        val isWhatsApp = target.contains("whatsapp", ignoreCase = true) || body.contains("whatsapp", ignoreCase = true)
        val cleanTarget = target.replace("whatsapp", "", ignoreCase = true).trim()
        val phone = resolvePhoneNumber(cleanTarget)
        if (isWhatsApp) {
            val encodedText = URLEncoder.encode(body, "UTF-8")
            val digits = phone?.filter { it.isDigit() }.orEmpty()
            val url = if (digits.isNotBlank()) {
                "https://wa.me/$digits?text=$encodedText"
            } else {
                "https://api.whatsapp.com/send?text=$encodedText"
            }
            return startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url)), false, "Opened WhatsApp draft")
        }
        val uri = Uri.parse("smsto:${phone ?: ""}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body)
        }
        return startActivitySafe(intent, false, "SMS composer opened")
    }

    private suspend fun draftEmail(target: String, body: String): ActionExecutionResult {
        val email = resolveEmail(target) ?: ""
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
            putExtra(Intent.EXTRA_TEXT, body)
        }
        return startActivitySafe(intent, false, "Email composer opened")
    }

    private fun navigateMaps(destination: String): ActionExecutionResult {
        val encoded = URLEncoder.encode(destination, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
        return startActivitySafe(intent, false, "Maps route opened for '$destination'")
    }

    private suspend fun searchInApp(
        appName: String,
        query: String,
        splitScreenEnabled: Boolean
    ): ActionExecutionResult {
        val cleanApp = appName.trim().lowercase()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return launchApp(appName, splitScreenEnabled)
        }
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

        // 1. Direct deep-link / package-targeted searches for popular apps (works even without Accessibility)
        when {
            cleanApp.contains("youtube music") || cleanApp.contains("yt music") -> {
                val ytMusicIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://music.youtube.com/search?q=$encoded")
                ).apply { setPackage("com.google.android.apps.youtube.music") }
                if (ytMusicIntent.resolveActivity(context.packageManager) != null) {
                    return startActivitySafe(ytMusicIntent, splitScreenEnabled, "Searched YouTube Music: $cleanQuery")
                }
            }
            cleanApp.contains("youtube") -> {
                val ytAppIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=$encoded")
                ).apply { setPackage("com.google.android.youtube") }
                if (ytAppIntent.resolveActivity(context.packageManager) != null) {
                    return startActivitySafe(ytAppIntent, splitScreenEnabled, "Opened YouTube & searched '$cleanQuery'")
                }
                val ytSearchAction = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", cleanQuery)
                }
                if (ytSearchAction.resolveActivity(context.packageManager) != null) {
                    return startActivitySafe(ytSearchAction, splitScreenEnabled, "Opened YouTube & searched '$cleanQuery'")
                }
                return startActivitySafe(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")),
                    splitScreenEnabled,
                    "Opened YouTube search: $cleanQuery"
                )
            }
            cleanApp.contains("spotify") -> {
                val spIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")).apply {
                    setPackage("com.spotify.music")
                }
                if (spIntent.resolveActivity(context.packageManager) != null) {
                    return startActivitySafe(spIntent, splitScreenEnabled, "Searched Spotify: $cleanQuery")
                }
            }
            cleanApp.contains("map") -> {
                return navigateMaps(cleanQuery)
            }
            cleanApp.contains("play store") || cleanApp.contains("store") -> {
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encoded"))
                if (marketIntent.resolveActivity(context.packageManager) != null) {
                    return startActivitySafe(marketIntent, splitScreenEnabled, "Searched Play Store: $cleanQuery")
                }
            }
            cleanApp.contains("chrome") || cleanApp.contains("browser") || cleanApp.contains("google") -> {
                return webSearch(cleanQuery)
            }
        }

        // 2. Universal double-task chaining for any other installed app: Launch + Wait + Accessibility Search
        val launchRes = launchApp(appName, splitScreenEnabled)
        if (!launchRes.success) return launchRes

        val svc = AetherAccessibilityService.currentInstance
            ?: return ActionExecutionResult(
                true,
                "${launchRes.detail}. Enable Trusted Screen Agent to auto-type '$cleanQuery'."
            )
        kotlinx.coroutines.delay(1600L)
        val searched = svc.performSearchInForegroundApp(cleanQuery)
        return if (searched) {
            ActionExecutionResult(true, "${launchRes.detail} & searched '$cleanQuery'")
        } else {
            ActionExecutionResult(true, "${launchRes.detail} (tap search bar to enter '$cleanQuery')")
        }
    }

    private fun playMedia(query: String, splitScreenEnabled: Boolean): ActionExecutionResult {
        val clean = query.trim()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        // Prefer direct YouTube app search when video/YouTube is available
        val ytUrlIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        ).apply { setPackage("com.google.android.youtube") }
        if (clean.contains("youtube", ignoreCase = true) && ytUrlIntent.resolveActivity(context.packageManager) != null) {
            return startActivitySafe(ytUrlIntent, splitScreenEnabled, "Opened YouTube search: $clean")
        }
        val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(android.app.SearchManager.QUERY, clean)
        }
        if (mediaIntent.resolveActivity(context.packageManager) != null) {
            return startActivitySafe(mediaIntent, splitScreenEnabled, "Media search launched: $clean")
        }
        if (ytUrlIntent.resolveActivity(context.packageManager) != null) {
            return startActivitySafe(ytUrlIntent, splitScreenEnabled, "Opened YouTube search: $clean")
        }
        val ytUrl = "https://www.youtube.com/results?search_query=$encoded"
        return startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(ytUrl)), splitScreenEnabled, "Opened YouTube search: $clean")
    }

    private fun webSearch(query: String): ActionExecutionResult {
        val url = if (query.startsWith("http://") || query.startsWith("https://")) {
            query
        } else {
            "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
        }
        return startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url)), false, "Opened web search")
    }

    private fun createCalendarEvent(title: String, desc: String): ActionExecutionResult {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title.ifBlank { "Aether Reminder" })
            if (desc.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, desc)
        }
        return startActivitySafe(intent, false, "Calendar event draft opened")
    }

    private suspend fun resolvePhoneNumber(nameOrNumber: String): String? {
        val clean = nameOrNumber.trim()
        if (clean.isBlank()) return null
        if (clean.count { it.isDigit() } >= 5) return clean

        val quick = vaultRepository.getContactsSnapshot().firstOrNull {
            it.aliasName.equals(clean, ignoreCase = true) ||
                it.displayName.contains(clean, ignoreCase = true)
        }
        if (quick != null && quick.phoneNumber.isNotBlank()) return quick.phoneNumber

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$clean%"),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(1)
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun resolveEmail(nameOrEmail: String): String? {
        val clean = nameOrEmail.trim()
        if (clean.contains("@")) return clean
        val quick = vaultRepository.getContactsSnapshot().firstOrNull {
            it.aliasName.equals(clean, ignoreCase = true) ||
                it.displayName.contains(clean, ignoreCase = true)
        }
        return quick?.emailAddress?.takeIf { it.isNotBlank() }
    }

    private fun startActivitySafe(
        intent: Intent,
        splitScreenEnabled: Boolean,
        successDetail: String
    ): ActionExecutionResult {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (splitScreenEnabled) {
                intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            }
            context.startActivity(intent)
            ActionExecutionResult(true, successDetail)
        } catch (e: Exception) {
            ActionExecutionResult(false, "No app available to handle action (${e.localizedMessage ?: "error"})")
        }
    }
}
