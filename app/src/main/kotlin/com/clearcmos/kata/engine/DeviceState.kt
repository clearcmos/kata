package com.clearcmos.kata.engine

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Point-in-time reads of the device, shared by condition evaluation and /capabilities.
 *
 * Everything here is best-effort: a missing permission yields null rather than throwing, and
 * conditions treat null as "cannot satisfy" so a rule fails visibly instead of firing wrongly.
 */
class DeviceState(private val context: Context) {
    fun batteryLevel(): Int? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }

    fun isCharging(): Boolean {
        val manager = context.getSystemService(BatteryManager::class.java)
        if (manager != null) return manager.isCharging
        val status =
            context
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun isScreenOn(): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive ?: false

    fun isWifiConnected(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Null when Wi-Fi is off, or when the SSID is redacted. Since Android 10 the platform
     * returns the literal "<unknown ssid>" instead of failing when the caller lacks location
     * permission, which would otherwise be indistinguishable from a network actually named that.
     */
    fun wifiSsid(): String? {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = capabilities.transportInfo as? WifiInfo ?: return null
        val ssid = info.ssid?.trim('"').orEmpty()
        return ssid.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    }

    fun isDndActive(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    fun isAppInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    }.isSuccess

    fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        // WifiManager.UNKNOWN_SSID without the dependency on WifiManager itself; the platform
        // substitutes this literal when the caller may not see the real name.
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
