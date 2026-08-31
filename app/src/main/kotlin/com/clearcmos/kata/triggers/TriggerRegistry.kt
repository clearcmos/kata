package com.clearcmos.kata.triggers

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.util.Log
import androidx.core.net.toUri
import com.clearcmos.kata.engine.Clock
import com.clearcmos.kata.engine.Engine
import com.clearcmos.kata.engine.Store
import com.clearcmos.kata.engine.TriggerEvent

/**
 * Binds Android's event sources to the engine, registering only what the current rule set
 * actually uses.
 *
 * Selective registration is not a micro-optimisation here: ACTION_BATTERY_CHANGED alone fires
 * every few seconds, and holding a network callback open costs wakeups. A device with three
 * time-based rules should not be listening to the battery at all.
 */
class TriggerRegistry(private val context: Context, private val store: Store, private val engine: Engine) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private var systemReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastBatteryLevel: Int? = null
    private var announcedWifi: Network? = null
    private val scheduledAlarmIds = mutableSetOf<String>()

    /** Tears down and rebuilds every registration to match the current rule set. */
    fun refresh() {
        val needed = store.enabled().map { it.trigger.type }.toSet()
        Log.i(TAG, "refreshing registrations for ${needed.sorted()}")
        registerSystemReceiver(needed)
        registerNetworkCallback(needed)
        scheduleAlarms()
    }

    fun stop() {
        systemReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        systemReceiver = null
        networkCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
        networkCallback = null
        cancelAlarms()
    }

    // -- broadcasts ---------------------------------------------------------------------

    private fun registerSystemReceiver(needed: Set<String>) {
        systemReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        systemReceiver = null

        val filter = IntentFilter()
        var any = false

        fun want(type: String, vararg actions: String) {
            if (type in needed) {
                actions.forEach { filter.addAction(it) }
                any = true
            }
        }
        want("power_connected", Intent.ACTION_POWER_CONNECTED)
        want("power_disconnected", Intent.ACTION_POWER_DISCONNECTED)
        want("screen_on", Intent.ACTION_SCREEN_ON)
        want("screen_off", Intent.ACTION_SCREEN_OFF)
        want("battery_level", Intent.ACTION_BATTERY_CHANGED)
        want("headset_plugged", Intent.ACTION_HEADSET_PLUG)
        want("headset_unplugged", Intent.ACTION_HEADSET_PLUG)
        want("airplane_mode", Intent.ACTION_AIRPLANE_MODE_CHANGED)
        want("bluetooth_connected", BluetoothDevice.ACTION_ACL_CONNECTED)
        want("bluetooth_disconnected", BluetoothDevice.ACTION_ACL_DISCONNECTED)
        if (!any) return

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = handleSystemBroadcast(intent)
            }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        systemReceiver = receiver
    }

    private fun handleSystemBroadcast(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> engine.onEvent(TriggerEvent("power_connected"))
            Intent.ACTION_POWER_DISCONNECTED -> engine.onEvent(TriggerEvent("power_disconnected"))
            Intent.ACTION_SCREEN_ON -> engine.onEvent(TriggerEvent("screen_on"))
            Intent.ACTION_SCREEN_OFF -> engine.onEvent(TriggerEvent("screen_off"))

            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level < 0 || scale <= 0) return
                val percent = level * 100 / scale
                val previous = lastBatteryLevel
                if (previous == percent) return
                lastBatteryLevel = percent
                engine.onEvent(
                    TriggerEvent(
                        "battery_level",
                        buildMap {
                            put("level", percent.toString())
                            previous?.let { put("prev_level", it.toString()) }
                        }
                    )
                )
            }

            Intent.ACTION_HEADSET_PLUG -> {
                val plugged = intent.getIntExtra("state", -1) == 1
                engine.onEvent(TriggerEvent(if (plugged) "headset_plugged" else "headset_unplugged"))
            }

            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                val enabled = intent.getBooleanExtra("state", false)
                engine.onEvent(TriggerEvent("airplane_mode", mapOf("enabled" to enabled.toString())))
            }

            BluetoothDevice.ACTION_ACL_CONNECTED ->
                emitBluetooth("bluetooth_connected", intent)

            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                emitBluetooth("bluetooth_disconnected", intent)
        }
    }

    private fun emitBluetooth(type: String, intent: Intent) {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val facts =
            buildMap {
                device?.address?.let { put("device_address", it) }
                // Reading the name needs BLUETOOTH_CONNECT; without it the address still matches.
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    runCatching { device?.name }.getOrNull()?.let { put("device_name", it) }
                }
            }
        engine.onEvent(TriggerEvent(type, facts))
    }

    // -- wifi ---------------------------------------------------------------------------

    private fun registerNetworkCallback(needed: Set<String>) {
        networkCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
        networkCallback = null
        if ("wifi_connected" !in needed && "wifi_disconnected" !in needed) return

        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    // onAvailable arrives before the SSID is populated, so the join is announced
                    // here instead; otherwise every ssid filter would miss on the first callback.
                    if (announcedWifi == network) return
                    announcedWifi = network
                    val ssid = (capabilities.transportInfo as? WifiInfo)?.ssid?.trim('"')
                    val facts =
                        if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") {
                            emptyMap()
                        } else {
                            mapOf("ssid" to ssid)
                        }
                    engine.onEvent(TriggerEvent("wifi_connected", facts))
                }

                override fun onLost(network: Network) {
                    if (announcedWifi != network) return
                    announcedWifi = null
                    engine.onEvent(TriggerEvent("wifi_disconnected"))
                }
            }
        runCatching { connectivityManager?.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.e(TAG, "could not register network callback", it) }
    }

    // -- alarms -------------------------------------------------------------------------

    fun scheduleAlarms() {
        cancelAlarms()
        val manager = alarmManager ?: return
        for (automation in store.enabled()) {
            val trigger = automation.resolved().trigger
            val at =
                when (trigger.type) {
                    "time_of_day" -> {
                        val minutes = Clock.parseMinutes(trigger.args.optString("at").orEmpty()) ?: continue
                        val days =
                            trigger.args
                                .stringList("days")
                                .map { it.lowercase() }
                                .toSet()
                        Clock.nextOccurrence(minutes, days)
                    }
                    "interval" -> {
                        val minutes = (trigger.args.optInt("minutes") ?: continue).coerceAtLeast(1)
                        System.currentTimeMillis() + minutes * 60_000L
                    }
                    else -> continue
                }
            val pending = pendingIntentFor(automation.id, trigger.type)
            // setExactAndAllowWhileIdle is the only variant Doze will not defer past its window.
            if (manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                Log.w(TAG, "exact alarms unavailable; ${automation.id} may drift")
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
            scheduledAlarmIds.add(automation.id)
        }
        Log.i(TAG, "scheduled ${scheduledAlarmIds.size} alarm(s)")
    }

    private fun cancelAlarms() {
        val manager = alarmManager ?: return
        scheduledAlarmIds.forEach { id ->
            manager.cancel(pendingIntentFor(id, "time_of_day"))
            manager.cancel(pendingIntentFor(id, "interval"))
        }
        scheduledAlarmIds.clear()
    }

    private fun pendingIntentFor(id: String, triggerType: String): PendingIntent {
        val intent =
            Intent(context, AlarmReceiver::class.java).apply {
                // Distinct data makes each automation's PendingIntent a distinct alarm; without it
                // the extras would be overwritten and only the last rule scheduled would survive.
                data = "kata://alarm/$id".toUri()
                putExtra(AlarmReceiver.EXTRA_AUTOMATION_ID, id)
                putExtra(AlarmReceiver.EXTRA_TRIGGER_TYPE, triggerType)
            }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val TAG = "KataTriggers"
    }
}
