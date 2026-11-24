package com.example.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

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

    @Volatile
    private var cachedCoordinates: Pair<Double, Double>? = null

    @Volatile
    private var cachedCoordsTimestampMs: Long = 0L

    @Volatile
    private var cachedCityLabel: String? = null

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
        val locLabel = if (hasLocationPermission() || cachedCoordinates != null) {
            "Auto-Location Ready"
        } else {
            "Network IP Location"
        }
        return "Local Time: $nowStr | Battery: ${preRead.batteryPercent}% (${if (preRead.isCharging) "Charging" else "Discharging"}) | Network: ${preRead.networkLabel} | Location: $locLabel | Media Volume: ${preRead.mediaVolumePercent}%"
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun getLastCoarseCoordinates(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return cachedCoordinates
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return cachedCoordinates

        return try {
            val candidates = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(LocationManager.FUSED_PROVIDER)
                }
                add(LocationManager.NETWORK_PROVIDER)
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.PASSIVE_PROVIDER)
                runCatching { lm.getProviders(true) }.getOrDefault(emptyList()).forEach { add(it) }
            }.distinct()

            var bestLoc: Location? = null
            for (provider in candidates) {
                val enabled = runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
                if (!enabled && provider != LocationManager.PASSIVE_PROVIDER) continue
                @Suppress("MissingPermission")
                val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
                if (bestLoc == null || loc.time > bestLoc.time) {
                    bestLoc = loc
                }
            }
            if (bestLoc != null) {
                val coords = bestLoc.latitude to bestLoc.longitude
                cachedCoordinates = coords
                cachedCoordsTimestampMs = bestLoc.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                coords
            } else {
                cachedCoordinates
            }
        } catch (_: SecurityException) {
            cachedCoordinates
        }
    }

    suspend fun getFreshOrLastCoordinates(): Pair<Double, Double>? {
        val last = getLastCoarseCoordinates()
        val ageMs = System.currentTimeMillis() - cachedCoordsTimestampMs
        if (last != null && ageMs in 0L..900_000L) {
            return last
        }
        if (!hasLocationPermission()) return last
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return last

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val providersToTry = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                add(LocationManager.GPS_PROVIDER)
            }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

            if (providersToTry.isNotEmpty()) {
                val winner = withTimeoutOrNull(2_200L) {
                    val deferred = CompletableDeferred<Location?>()
                    val cancelSignals = mutableListOf<CancellationSignal>()
                    kotlinx.coroutines.coroutineScope {
                        providersToTry.forEach { provider ->
                            launch {
                                val signal = CancellationSignal()
                                synchronized(cancelSignals) { cancelSignals.add(signal) }
                                val loc = runCatching {
                                    suspendCancellableCoroutine<Location?> { cont ->
                                        cont.invokeOnCancellation { signal.cancel() }
                                        try {
                                            @Suppress("MissingPermission")
                                            lm.getCurrentLocation(
                                                provider,
                                                signal,
                                                ContextCompat.getMainExecutor(context)
                                            ) { resolved ->
                                                if (cont.isActive) cont.resume(resolved)
                                            }
                                        } catch (_: Exception) {
                                            if (cont.isActive) cont.resume(null)
                                        }
                                    }
                                }.getOrNull()
                                if (loc != null && !deferred.isCompleted) {
                                    deferred.complete(loc)
                                }
                            }
                        }
                        val firstHit = deferred.await()
                        synchronized(cancelSignals) {
                            cancelSignals.forEach { runCatching { it.cancel() } }
                        }
                        firstHit
                    }
                }
                if (winner != null) {
                    val coords = winner.latitude to winner.longitude
                    cachedCoordinates = coords
                    cachedCoordsTimestampMs = System.currentTimeMillis()
                    return coords
                }
            }
        }
        return last
    }

    suspend fun reverseGeocodeCityNameFast(lat: Double, lon: Double): String? {
        val hit = withTimeoutOrNull(1_400L) {
            withContext(Dispatchers.IO) {
                reverseGeocodeCityName(lat, lon)
            }
        }
        if (!hit.isNullOrBlank()) {
            cachedCityLabel = hit
            return hit
        }
        return cachedCityLabel
    }

    @Suppress("DEPRECATION")
    fun reverseGeocodeCityName(lat: Double, lon: Double): String? {
        return runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val list = Geocoder(context, Locale.ENGLISH).getFromLocation(lat, lon, 1)
            val addr = list?.firstOrNull() ?: return@runCatching null
            listOfNotNull(
                addr.locality ?: addr.subAdminArea ?: addr.adminArea,
                addr.countryName
            ).filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
                .ifBlank { null }
        }.getOrNull()
    }

    fun queryLauncherAppMap(forceRefresh: Boolean = false): Map<String, String> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedAppMap.isNotEmpty() && now - lastAppCacheMs < 120_000L) {
            return cachedAppMap
        }
        val pm = context.packageManager
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            0
        }
        val map = linkedMapOf<String, String>()

        fun collectFromIntent(intent: Intent) {
            val resolved = runCatching { pm.queryIntentActivities(intent, flags) }.getOrDefault(emptyList())
            for (info in resolved) {
                val label = info.loadLabel(pm).toString().trim()
                val pkg = info.activityInfo?.packageName ?: continue
                if (label.isNotBlank() && pkg != context.packageName && !map.containsKey(label)) {
                    map[label] = pkg
                }
            }
        }

        collectFromIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        // Also discover dedicated Dial/Phone apps declared via ACTION_DIAL even if OEM uses custom category
        collectFromIntent(Intent(Intent.ACTION_DIAL))

        cachedAppMap = map
        lastAppCacheMs = now
        return map
    }

    fun findBestAppMatch(query: String): Pair<String, String>? {
        val clean = query.trim().lowercase(Locale.US)
        if (clean.isBlank()) return null
        val map = queryLauncherAppMap().ifEmpty { queryLauncherAppMap(forceRefresh = true) }
        val aliasKeywords = when (clean) {
            "fb" -> listOf("facebook")
            "yt" -> listOf("youtube")
            "wa" -> listOf("whatsapp")
            "dial", "dialer", "phone", "call" -> listOf("dial", "phone", "contacts", "truecaller", "call")
            else -> listOf(clean)
        }

        val scored = map.entries.mapNotNull { (label, pkg) ->
            val lLower = label.lowercase(Locale.US)
            val pLower = pkg.lowercase(Locale.US)
            var score = 0
            for (kw in aliasKeywords) {
                when {
                    lLower == kw -> score = maxOf(score, 200)
                    lLower.startsWith("$kw ") || lLower.startsWith(kw) -> score = maxOf(score, 140)
                    Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(lLower) -> score = maxOf(score, 120)
                    lLower.contains(kw) -> score = maxOf(score, 90)
                    pLower.substringAfterLast('.') == kw -> score = maxOf(score, 105)
                    pLower.contains(kw) -> score = maxOf(score, 65)
                }
            }
            if (score > 0) (label to pkg) to score else null
        }
        return scored.maxByOrNull { it.second }?.first
    }

    fun queryLauncherAppNames(limit: Int = 120): String {
        return queryLauncherAppMap().keys.take(limit).joinToString(", ")
    }
}
