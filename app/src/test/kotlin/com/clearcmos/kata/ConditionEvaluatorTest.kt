package com.clearcmos.kata

import com.clearcmos.kata.engine.ConditionEvaluator
import com.clearcmos.kata.engine.DeviceReadings
import com.clearcmos.kata.model.Args
import com.clearcmos.kata.model.Step
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A device whose every reading is dictated by the test. */
private class FakeDevice(
    private val battery: Int? = 50,
    private val charging: Boolean = false,
    private val screenOn: Boolean = true,
    private val wifi: Boolean = true,
    private val ssid: String? = "home",
    private val dnd: Boolean = false,
    private val installed: Set<String> = setOf("com.example")
) : DeviceReadings {
    override fun batteryLevel() = battery

    override fun isCharging() = charging

    override fun isScreenOn() = screenOn

    override fun isWifiConnected() = wifi

    override fun wifiSsid() = ssid

    override fun isDndActive() = dnd

    override fun isAppInstalled(packageName: String) = packageName in installed
}

class ConditionEvaluatorTest {
    private fun evaluate(device: DeviceReadings, type: String, args: Map<String, Any?>) =
        ConditionEvaluator(device).evaluate(Step(type, Args(args)))

    @Test
    fun `battery thresholds compare in the right direction`() {
        assertTrue(evaluate(FakeDevice(battery = 10), "battery_below", mapOf("value" to 20)).matched)
        assertFalse(evaluate(FakeDevice(battery = 30), "battery_below", mapOf("value" to 20)).matched)
        assertTrue(evaluate(FakeDevice(battery = 90), "battery_above", mapOf("value" to 80)).matched)
        assertFalse(evaluate(FakeDevice(battery = 70), "battery_above", mapOf("value" to 80)).matched)
    }

    @Test
    fun `a threshold exactly on the boundary does not match`() {
        assertFalse(evaluate(FakeDevice(battery = 20), "battery_below", mapOf("value" to 20)).matched)
        assertFalse(evaluate(FakeDevice(battery = 80), "battery_above", mapOf("value" to 80)).matched)
    }

    @Test
    fun `an unreadable battery cannot satisfy a battery condition`() {
        // Null means "unknown", and a rule must not fire on a guess about power state.
        val result = evaluate(FakeDevice(battery = null), "battery_below", mapOf("value" to 20))
        assertFalse(result.matched)
        assertTrue(result.detail, result.detail.contains("unavailable"))
    }

    @Test
    fun `boolean conditions compare against the requested state`() {
        assertTrue(evaluate(FakeDevice(charging = true), "charging", mapOf("value" to true)).matched)
        assertFalse(evaluate(FakeDevice(charging = true), "charging", mapOf("value" to false)).matched)
        assertTrue(evaluate(FakeDevice(screenOn = false), "screen_on", mapOf("value" to false)).matched)
        assertTrue(evaluate(FakeDevice(dnd = true), "dnd_active", mapOf("value" to true)).matched)
        assertTrue(evaluate(FakeDevice(wifi = false), "wifi_connected", mapOf("value" to false)).matched)
    }

    @Test
    fun `ssid matching is exact`() {
        assertTrue(evaluate(FakeDevice(ssid = "home"), "wifi_ssid", mapOf("equals" to "home")).matched)
        assertFalse(evaluate(FakeDevice(ssid = "home"), "wifi_ssid", mapOf("equals" to "Home")).matched)
    }

    @Test
    fun `an unreadable ssid says so rather than silently failing`() {
        val result = evaluate(FakeDevice(ssid = null), "wifi_ssid", mapOf("equals" to "home"))
        assertFalse(result.matched)
        assertTrue(result.detail, result.detail.contains("location"))
    }

    @Test
    fun `app_installed reflects the package list`() {
        assertTrue(evaluate(FakeDevice(), "app_installed", mapOf("package" to "com.example")).matched)
        assertFalse(evaluate(FakeDevice(), "app_installed", mapOf("package" to "com.absent")).matched)
    }

    @Test
    fun `an unknown condition type never matches`() {
        val result = evaluate(FakeDevice(), "not_a_condition", emptyMap())
        assertFalse(result.matched)
        assertTrue(result.detail, result.detail.contains("unknown condition"))
    }
}
