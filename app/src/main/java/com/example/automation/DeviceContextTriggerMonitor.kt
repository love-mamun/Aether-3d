package com.example.automation

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.local.SmartTriggerRuleEntity
import java.util.Locale

private const val TAG = "DeviceTriggerMonitor"

data class ContextTriggerEvent(
    val type: String, // BLUETOOTH_CONNECTED | WIFI_CONNECTED | BATTERY_LOW | CHARGER_CONNECTED
    val detailValue: String = ""
)

/**
 * Monitors Bluetooth headset/device connections, Wi-Fi transitions, and Battery thresholds
 * with cooldown gating so Aether can deliver non-intrusive proactive briefings & rules.
 */
class DeviceContextTriggerMonitor(
    private val context: Context,
    private val rulesProvider: suspend () -> List<SmartTriggerRuleEntity>,
    private val onRuleMatched: suspend (SmartTriggerRuleEntity, ContextTriggerEvent) -> Unit
) {

    private var registered = false
    private var lastBatteryPct: Int = 100
    private var wasCharging: Boolean = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val hasBtPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true
                    if (!hasBtPerm) return
                    val deviceName = runCatching {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.name.orEmpty()
                    }.getOrDefault("")
                    dispatchEvent(ContextTriggerEvent("BLUETOOTH_CONNECTED", deviceName))
                }

                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        dispatchEvent(ContextTriggerEvent("BLUETOOTH_CONNECTED", "Audio Headset"))
                    }
                }

                Intent.ACTION_POWER_CONNECTED -> {
                    dispatchEvent(ContextTriggerEvent("CHARGER_CONNECTED", "Power adapter"))
                }

                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    val pct = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else lastBatteryPct

                    if (!charging && pct < lastBatteryPct) {
                        dispatchEvent(ContextTriggerEvent("BATTERY_LOW", pct.toString()))
                    }
                    if (charging && !wasCharging) {
                        dispatchEvent(ContextTriggerEvent("CHARGER_CONNECTED", "$pct%"))
                    }
                    lastBatteryPct = pct
                    wasCharging = charging
                }
            }
        }
    }

    @Volatile
    var onEventCallback: ((ContextTriggerEvent) -> Unit)? = null

    private fun dispatchEvent(event: ContextTriggerEvent) {
        onEventCallback?.invoke(event)
    }

    fun start() {
        if (registered) return
        registered = true
        runCatching {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            ContextCompat.registerReceiver(
                context,
                systemReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure { err ->
            Log.w(TAG, "System trigger receiver registration warning: ${err.message}")
        }

        registerWifiCallback()
    }

    fun attachScopeEvaluator(
        @Suppress("UNUSED_PARAMETER") scope: kotlinx.coroutines.CoroutineScope,
        launchBlock: (suspend () -> Unit) -> Unit
    ) {
        onEventCallback = { event ->
            launchBlock {
                val rules = rulesProvider()
                val matches = evaluateRulesForEvent(rules, event, System.currentTimeMillis())
                matches.forEach { rule ->
                    onRuleMatched(rule, event)
                }
            }
        }
        start()
    }

    private fun registerWifiCallback() {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    dispatchEvent(ContextTriggerEvent("WIFI_CONNECTED", "Wi-Fi"))
                }
            }
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        }.onFailure { err ->
            Log.w(TAG, "Wi-Fi callback registration skipped: ${err.message}")
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        onEventCallback = null
        runCatching { context.unregisterReceiver(systemReceiver) }
        networkCallback?.let { cb ->
            runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
    }

    companion object {
        /**
         * Pure rule matcher with cooldown and condition matching (easy to verify in JVM/Robolectric tests).
         */
        fun evaluateRulesForEvent(
            rules: List<SmartTriggerRuleEntity>,
            event: ContextTriggerEvent,
            nowMillis: Long
        ): List<SmartTriggerRuleEntity> {
            return rules.filter { rule ->
                if (!rule.isEnabled) return@filter false
                if (!rule.triggerType.equals(event.type, ignoreCase = true)) return@filter false

                val cooldownMs = rule.cooldownMinutes.coerceAtLeast(1) * 60_000L
                if (rule.lastTriggeredAt > 0L && (nowMillis - rule.lastTriggeredAt) < cooldownMs) {
                    return@filter false
                }

                val cond = rule.conditionValue.trim()
                when (rule.triggerType.uppercase(Locale.ROOT)) {
                    "BATTERY_LOW" -> {
                        val threshold = cond.toIntOrNull()?.coerceIn(5, 95) ?: 20
                        val currentPct = event.detailValue.toIntOrNull() ?: return@filter false
                        currentPct <= threshold
                    }
                    "BLUETOOTH_CONNECTED", "WIFI_CONNECTED" -> {
                        cond.isBlank() || event.detailValue.contains(cond, ignoreCase = true)
                    }
                    else -> true
                }
            }
        }
    }
}
