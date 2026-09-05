package com.clearcmos.kata.engine

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.PowerManager
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Point-in-time reads of the device, shared by condition evaluation and /capabilities.
 *
 * Everything here is best-effort: a missing permission yields null rather than throwing, and
 * conditions treat null as "cannot satisfy" so a rule fails visibly instead of firing wrongly.
 */
/**
 * The device facts conditions are evaluated against.
 *
 * An interface so condition logic can be exercised on the JVM against a fake. Every reader
 * returns null rather than throwing when a permission is missing, and conditions treat null as
 * "cannot satisfy" so a rule fails visibly instead of firing on a guess.
 */
interface DeviceReadings {
    fun batteryLevel(): Int?

    fun isCharging(): Boolean

    fun isScreenOn(): Boolean

    fun isWifiConnected(): Boolean

    fun wifiSsid(): String?

    fun ipAddress(): String?

    fun isDndActive(): Boolean

    fun isAppInstalled(packageName: String): Boolean

    /** Audio devices connected now; empty when none, null when the app may not ask. */
    fun connectedBluetoothDevices(): List<BluetoothPeer>?
}

/** A remote Bluetooth device as a condition sees it. The name is null when the platform withholds it. */
data class BluetoothPeer(val address: String, val name: String?) {
    fun matches(wanted: String): Boolean =
        wanted.equals(address, ignoreCase = true) || wanted.equals(name, ignoreCase = true)

    fun label(): String = if (name == null) address else "$name ($address)"
}

class DeviceState(private val context: Context) : DeviceReadings {
    override fun batteryLevel(): Int? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }

    override fun isCharging(): Boolean {
        val manager = context.getSystemService(BatteryManager::class.java)
        if (manager != null) return manager.isCharging
        val status =
            context
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun isScreenOn(): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive ?: false

    override fun isWifiConnected(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Null when Wi-Fi is off, or when the SSID is redacted. Since Android 10 the platform
     * returns the literal "<unknown ssid>" instead of failing when the caller lacks location
     * permission, which would otherwise be indistinguishable from a network actually named that.
     */
    override fun wifiSsid(): String? {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = capabilities.transportInfo as? WifiInfo ?: return null
        val ssid = info.ssid?.trim('"').orEmpty()
        return ssid.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    }

    /**
     * The IPv4 address the device holds on Wi-Fi, or null when it has none.
     *
     * The Wi-Fi network specifically, never the active one. Those differ exactly when this is
     * asked: rejoining Wi-Fi leaves mobile data as the default route until the new link
     * validates, so reading the active network answers with the carrier's address for the first
     * moments of a join, which is precisely when a `wifi_connected` rule runs. A VPN is skipped
     * for the same reason, and because it reports the transports of the network beneath it.
     *
     * IPv4 only and deliberately so: this exists to identify a known LAN by its lease, and a
     * device's IPv6 addresses rotate under privacy extensions, so matching one would be a
     * condition that stops being true on its own.
     */
    override fun ipAddress(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val wifi =
            manager.allNetworks.firstOrNull { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } ?: return null
        return manager
            .getLinkProperties(wifi)
            ?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    override fun isDndActive(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    override fun isAppInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    }.isSuccess

    /**
     * Connected audio devices across the profiles a headset or earbuds use. Empty when the radio
     * is off, which is a true statement about what is connected. Profile proxies answer
     * asynchronously on the main thread, so this blocks its caller briefly; it is only ever
     * asked from the engine thread.
     */
    override fun connectedBluetoothDevices(): List<BluetoothPeer>? {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return emptyList()
        val peers = LinkedHashMap<String, BluetoothPeer>()
        for (profile in AUDIO_PROFILES) {
            val proxy = profileProxy(adapter, profile) ?: continue
            try {
                proxy.connectedDevices.forEach { peers.putIfAbsent(it.address, BluetoothPeer(it.address, it.name)) }
            } finally {
                adapter.closeProfileProxy(profile, proxy)
            }
        }
        return peers.values.toList()
    }

    private fun profileProxy(adapter: BluetoothAdapter, profile: Int): BluetoothProfile? {
        val ready = CountDownLatch(1)
        var proxy: BluetoothProfile? = null
        val listener =
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                    proxy = service
                    ready.countDown()
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }
        if (!adapter.getProfileProxy(context, listener, profile)) return null
        return if (ready.await(PROFILE_PROXY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) proxy else null
    }

    fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val AUDIO_PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO)
        const val PROFILE_PROXY_TIMEOUT_MS = 2000L

        // WifiManager.UNKNOWN_SSID without the dependency on WifiManager itself; the platform
        // substitutes this literal when the caller may not see the real name.
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
