package com.clearcmos.kata.engine

import com.clearcmos.kata.model.Step
import com.clearcmos.kata.triggers.KataAccessibilityService

/** The outcome of one condition, carrying why it decided that so the run log can show it. */
data class ConditionResult(val matched: Boolean, val detail: String)

class ConditionEvaluator(private val device: DeviceReadings) {
    fun evaluate(step: Step): ConditionResult {
        val args = step.args
        return when (step.type) {
            "time_between" -> {
                val from = Clock.parseMinutes(args.string("from"))
                val to = Clock.parseMinutes(args.string("to"))
                if (from == null || to == null) {
                    ConditionResult(false, "malformed time window")
                } else {
                    val now = Clock.nowMinutes()
                    val inside = Clock.isBetween(now, from, to)
                    ConditionResult(
                        inside,
                        "${format(
                            now
                        )} ${if (inside) "inside" else "outside"} ${args.string("from")}-${args.string("to")}"
                    )
                }
            }

            "day_of_week" -> {
                val days = args.stringList("days").map { it.lowercase() }.toSet()
                val today = Clock.dayName()
                ConditionResult(today in days, "today is $today, want ${days.joinToString("/")}")
            }

            "battery_below" -> {
                val level =
                    device.batteryLevel()
                        ?: return ConditionResult(false, "battery level unavailable")
                val want = args.int("value")
                ConditionResult(level < want, "battery $level%, want below $want%")
            }

            "battery_above" -> {
                val level =
                    device.batteryLevel()
                        ?: return ConditionResult(false, "battery level unavailable")
                val want = args.int("value")
                ConditionResult(level > want, "battery $level%, want above $want%")
            }

            "charging" -> {
                val want = args.bool("value")
                val actual = device.isCharging()
                ConditionResult(actual == want, "charging=$actual, want $want")
            }

            "screen_on" -> {
                val want = args.bool("value")
                val actual = device.isScreenOn()
                ConditionResult(actual == want, "screen_on=$actual, want $want")
            }

            "wifi_ssid" -> {
                val want = args.string("equals")
                val actual =
                    device.wifiSsid()
                        ?: return ConditionResult(false, "ssid unreadable; grant location to match on it")
                ConditionResult(actual == want, "ssid=$actual, want $want")
            }

            "ip_address" -> {
                val want = args.string("equals")
                val actual =
                    device.ipAddress()
                        ?: return ConditionResult(false, "no IPv4 address on Wi-Fi")
                ConditionResult(actual == want, "ip=$actual, want $want")
            }

            "wifi_connected" -> {
                val want = args.bool("value")
                val actual = device.isWifiConnected()
                ConditionResult(actual == want, "wifi_connected=$actual, want $want")
            }

            "dnd_active" -> {
                val want = args.bool("value")
                val actual = device.isDndActive()
                ConditionResult(actual == want, "dnd=$actual, want $want")
            }

            "app_foreground" -> {
                val want = args.string("package")
                val actual = KataAccessibilityService.currentPackage
                    ?: return ConditionResult(false, "foreground app unknown; enable kata under Accessibility")
                ConditionResult(actual == want, "foreground=$actual, want $want")
            }

            "app_installed" -> {
                val pkg = args.string("package")
                val installed = device.isAppInstalled(pkg)
                ConditionResult(installed, "$pkg ${if (installed) "installed" else "not installed"}")
            }

            else -> ConditionResult(false, "unknown condition type '${step.type}'")
        }
    }

    private fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}
