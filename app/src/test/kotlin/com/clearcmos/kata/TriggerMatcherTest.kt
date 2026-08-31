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
}
