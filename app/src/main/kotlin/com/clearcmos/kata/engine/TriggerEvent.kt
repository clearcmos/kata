package com.clearcmos.kata.engine

import com.clearcmos.kata.model.Step

/**
 * Something that happened, plus whatever the source knew about it.
 *
 * Facts are flat strings so the same event can arrive from a real broadcast or from
 * POST /simulate with no second code path. Simulation that goes through a different matcher
 * than production would test nothing.
 */
data class TriggerEvent(val type: String, val facts: Map<String, String> = emptyMap()) {
    fun describe(): String =
        if (facts.isEmpty()) type else "$type ${facts.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
}

/** Decides whether an automation's trigger filter accepts a given event of the same type. */
object TriggerMatcher {
    fun matches(trigger: Step, event: TriggerEvent): Boolean {
        if (trigger.type != event.type) return false
        val args = trigger.args
        return when (trigger.type) {
            "wifi_connected" ->
                args.optString("ssid")?.let { it == event.facts["ssid"] } ?: true

            "bluetooth_connected", "bluetooth_disconnected" ->
                args.optString("device")?.let { wanted ->
                    wanted.equals(event.facts["device_name"], ignoreCase = true) ||
                        wanted.equals(event.facts["device_address"], ignoreCase = true)
                } ?: true

            "airplane_mode" ->
                if (args.has("enabled")) {
                    args.bool("enabled", false).toString() == event.facts["enabled"]
                } else {
                    true
                }

            // Edge-triggered. prev_level is what makes this fire once on the way past a
            // threshold instead of on every battery broadcast while the level sits beyond it.
            "battery_level" -> {
                val level = event.facts["level"]?.toIntOrNull() ?: return false
                val previous = event.facts["prev_level"]?.toIntOrNull()
                val below = args.optInt("below")
                val above = args.optInt("above")
                val crossedDown = below != null && level <= below && (previous == null || previous > below)
                val crossedUp = above != null && level >= above && (previous == null || previous < above)
                crossedDown || crossedUp
            }

            // Every watcher receives every setting_changed event, so the key and scope are part
            // of matching rather than something the registry can filter on its own.
            "setting_changed" -> {
                if (args.optString("key") != event.facts["key"]) return false
                if (args.optString("scope") != event.facts["scope"]) return false
                args.optString("equals")?.let { it == event.facts["value"] } ?: true
            }

            "app_foreground", "app_background" ->
                args.optString("package")?.let { it == event.facts["package"] } ?: true

            "notification_posted", "notification_removed" ->
                matchesNotification(trigger, event)

            else -> true
        }
    }

    private fun matchesNotification(trigger: Step, event: TriggerEvent): Boolean {
        val args = trigger.args
        args.optString("package")?.let { if (it != event.facts["package"]) return false }
        args.optString("title_contains")?.let {
            if (event.facts["title"]?.contains(it, ignoreCase = true) != true) return false
        }
        args.optString("text_contains")?.let {
            if (event.facts["text"]?.contains(it, ignoreCase = true) != true) return false
        }
        return true
    }
}
