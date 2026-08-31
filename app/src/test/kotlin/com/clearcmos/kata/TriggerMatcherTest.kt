package com.clearcmos.kata

import com.clearcmos.kata.engine.TriggerEvent
import com.clearcmos.kata.engine.TriggerMatcher
import com.clearcmos.kata.model.Args
import com.clearcmos.kata.model.Step
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerMatcherTest {
    @Test
    fun `a type mismatch never matches`() {
        assertFalse(TriggerMatcher.matches(Step("screen_on", Args.EMPTY), TriggerEvent("screen_off")))
    }

    @Test
    fun `an unfiltered trigger matches any event of its type`() {
        assertTrue(
            TriggerMatcher.matches(
                Step("wifi_connected", Args.EMPTY),
                TriggerEvent("wifi_connected", mapOf("ssid" to "anything"))
            )
        )
    }

    @Test
    fun `an ssid filter matches only its network`() {
        val trigger = Step("wifi_connected", Args(mapOf("ssid" to "home")))
        assertTrue(TriggerMatcher.matches(trigger, TriggerEvent("wifi_connected", mapOf("ssid" to "home"))))
        assertFalse(TriggerMatcher.matches(trigger, TriggerEvent("wifi_connected", mapOf("ssid" to "cafe"))))
    }

    @Test
    fun `a bluetooth filter matches on name or address, case insensitively`() {
        val trigger = Step("bluetooth_connected", Args(mapOf("device" to "Car Audio")))
        assertTrue(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent("bluetooth_connected", mapOf("device_name" to "car audio"))
            )
        )
        val byAddress = Step("bluetooth_connected", Args(mapOf("device" to "AA:BB:CC:DD:EE:FF")))
        assertTrue(
            TriggerMatcher.matches(
                byAddress,
                TriggerEvent("bluetooth_connected", mapOf("device_address" to "aa:bb:cc:dd:ee:ff"))
            )
        )
    }

    @Test
    fun `battery_level fires on the way past a threshold, not while beyond it`() {
        val trigger = Step("battery_level", Args(mapOf("below" to 20)))
        assertTrue(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent("battery_level", mapOf("level" to "19", "prev_level" to "21"))
            )
        )
        assertFalse(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent("battery_level", mapOf("level" to "18", "prev_level" to "19"))
            )
        )
    }

    @Test
    fun `battery_level above fires on the way up`() {
        val trigger = Step("battery_level", Args(mapOf("above" to 80)))
        assertTrue(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent("battery_level", mapOf("level" to "81", "prev_level" to "79"))
            )
        )
        assertFalse(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent("battery_level", mapOf("level" to "85", "prev_level" to "82"))
            )
        )
    }

    @Test
    fun `notification matchers are ANDed and case insensitive`() {
        val trigger =
            Step(
                "notification_posted",
                Args(mapOf("package" to "com.example", "title_contains" to "BUILD"))
            )
        assertTrue(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent("notification_posted", mapOf("package" to "com.example", "title" to "Build failed"))
            )
        )
        assertFalse(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent("notification_posted", mapOf("package" to "com.other", "title" to "Build failed"))
            )
        )
        assertFalse(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent("notification_posted", mapOf("package" to "com.example", "title" to "Deploy ok"))
            )
        )
    }

    @Test
    fun `an app filter matches only its package`() {
        val trigger = Step("app_foreground", Args(mapOf("package" to "com.instagram.android")))
        assertTrue(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent("app_foreground", mapOf("package" to "com.instagram.android"))
            )
        )
        assertFalse(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent("app_foreground", mapOf("package" to "com.android.chrome"))
            )
        )
    }

    @Test
    fun `an unfiltered app trigger fires on every switch`() {
        assertTrue(
            TriggerMatcher.matches(
                Step("app_foreground", Args.EMPTY),
                TriggerEvent("app_foreground", mapOf("package" to "anything"))
            )
        )
    }

    @Test
    fun `foreground and background are distinct events`() {
        val leaving = Step("app_background", Args(mapOf("package" to "com.instagram.android")))
        assertTrue(
            TriggerMatcher.matches(
                leaving,
                TriggerEvent("app_background", mapOf("package" to "com.instagram.android"))
            )
        )
        assertFalse(
            TriggerMatcher.matches(
                leaving,
                TriggerEvent("app_foreground", mapOf("package" to "com.instagram.android"))
            )
        )
    }

    @Test
    fun `setting_changed matches on scope and key, not just type`() {
        val trigger = Step(
            "setting_changed",
            Args(mapOf("scope" to "global", "key" to "adb_wifi_enabled"))
        )
        assertTrue(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent(
                    "setting_changed",
                    mapOf("scope" to "global", "key" to "adb_wifi_enabled", "value" to "1")
                )
            )
        )
        // Every watcher receives every setting_changed event, so a different key must not match.
        assertFalse(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent(
                    "setting_changed",
                    mapOf("scope" to "global", "key" to "screen_brightness", "value" to "1")
                )
            )
        )
        assertFalse(
            TriggerMatcher.matches(
                trigger,
                TriggerEvent(
                    "setting_changed",
                    mapOf("scope" to "secure", "key" to "adb_wifi_enabled", "value" to "1")
                )
            )
        )
    }

    @Test
    fun `setting_changed equals filters on the new value`() {
        val trigger = Step(
            "setting_changed",
            Args(mapOf("scope" to "global", "key" to "adb_wifi_enabled", "equals" to "1"))
        )
        val on = mapOf("scope" to "global", "key" to "adb_wifi_enabled", "value" to "1")
        val off = mapOf("scope" to "global", "key" to "adb_wifi_enabled", "value" to "0")
        assertTrue(TriggerMatcher.matches(trigger, TriggerEvent("setting_changed", on)))
        assertFalse(TriggerMatcher.matches(trigger, TriggerEvent("setting_changed", off)))
    }
}
