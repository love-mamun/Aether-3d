package com.example.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceTelemetry(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val networkLabel: String = "ONLINE",
    val mediaVolumePercent: Int = 50,
    val installedAppCount: Int = 0
)

class DeviceTelemetryProvider(private val context: Context) {

    @Volatile
    private var cachedAppMap: Map<String, String> = emptyMap()

    @Volatile
    private var lastAppCacheMs: Long = 0L

    fun readTelemetry(): DeviceTelemetry {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
        val netLabel = when {
            caps == null -> "OFFLINE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "ONLINE"
        }

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val curVol = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 5
        val maxVol = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
        val volPct = ((curVol.toFloat() / maxVol) * 100f).toInt()

        val apps = queryLauncherAppMap()

        return DeviceTelemetry(
            batteryPercent = pct,
            isCharging = charging,
            networkLabel = netLabel,
            mediaVolumePercent = volPct,
            installedAppCount = apps.size
        )
    }

    fun buildPromptTelemetrySummary(preRead: DeviceTelemetry = readTelemetry()): String {
        val nowStr = SimpleDateFormat("EEE, dd MMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return "Local Time: $nowStr | Battery: ${preRead.batteryPercent}% (${if (preRead.isCharging) "Charging" else "Discharging"}) | Network: ${preRead.networkLabel} | Media Volume: ${preRead.mediaVolumePercent}%"
    }

    fun getLastCoarseCoordinates(): Pair<Double, Double>? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            for (provider in providers) {
                if (lm.isProviderEnabled(provider)) {
                    @Suppress("MissingPermission")
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null) return loc.latitude to loc.longitude
                }
            }
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun queryLauncherAppMap(): Map<String, String> {
        val now = System.currentTimeMillis()
        if (cachedAppMap.isNotEmpty() && now - lastAppCacheMs < 300_000L) {
            return cachedAppMap
        }
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val map = linkedMapOf<String, String>()
        for (info in resolved) {
            val label = info.loadLabel(pm).toString().trim()
            val pkg = info.activityInfo?.packageName ?: continue
            if (label.isNotBlank() && pkg != context.packageName) {
                map[label] = pkg
            }
        }
        cachedAppMap = map
        lastAppCacheMs = now
        return map
    }

    fun queryLauncherAppNames(limit: Int = 30): String {
        return queryLauncherAppMap().keys.take(limit).joinToString(", ")
    }
}
