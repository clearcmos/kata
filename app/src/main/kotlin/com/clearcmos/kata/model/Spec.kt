package com.clearcmos.kata.model

/**
 * The machine-readable vocabulary. Validation, the /capabilities endpoint, and the in-app
 * detail screen all read from these specs, so a type is described exactly once and an
 * authoring agent can discover the whole surface without reading source.
 */

enum class FieldType { STRING, INT, BOOL, TIME, ENUM, STRING_LIST, OBJECT }

/**
 * A runtime prerequisite that is not a plain manifest permission: either a user grant in
 * Settings, an adb grant, or a special-access toggle. [Capabilities] resolves each against
 * live device state so an agent learns what will actually work before it authors a rule.
 */
enum class Requirement {
    POST_NOTIFICATIONS,
    WRITE_SECURE_SETTINGS,
    WRITE_SYSTEM_SETTINGS,
    NOTIFICATION_LISTENER,
    DND_POLICY,
    LOCATION,
    BLUETOOTH,
    EXACT_ALARM,
    ACCESSIBILITY
}

data class FieldSpec(
    val name: String,
    val type: FieldType,
    val required: Boolean,
    val doc: String,
    val values: List<String> = emptyList(),
    val default: Any? = null
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("name", name)
        put("type", type.name.lowercase())
        put("required", required)
        put("doc", doc)
        if (values.isNotEmpty()) put("values", values)
        if (default != null) put("default", default)
    }
}

enum class SpecKind { TRIGGER, CONDITION, ACTION }

data class TypeSpec(
    val id: String,
    val kind: SpecKind,
    val doc: String,
    val fields: List<FieldSpec> = emptyList(),
    val requires: List<Requirement> = emptyList()
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("type", id)
        put("doc", doc)
        put("fields", fields.map { it.toMap() })
        if (requires.isNotEmpty()) put("requires", requires.map { it.name.lowercase() })
    }
}

private fun str(name: String, doc: String, required: Boolean = true, default: Any? = null) =
    FieldSpec(name, FieldType.STRING, required, doc, default = default)

private fun int(name: String, doc: String, required: Boolean = true, default: Any? = null) =
    FieldSpec(name, FieldType.INT, required, doc, default = default)

private fun bool(name: String, doc: String, required: Boolean = true, default: Any? = null) =
    FieldSpec(name, FieldType.BOOL, required, doc, default = default)

private fun time(name: String, doc: String, required: Boolean = true) = FieldSpec(name, FieldType.TIME, required, doc)

private fun enum(name: String, doc: String, values: List<String>, required: Boolean = true, default: Any? = null) =
    FieldSpec(name, FieldType.ENUM, required, doc, values = values, default = default)

private fun strList(name: String, doc: String, required: Boolean = false) =
    FieldSpec(name, FieldType.STRING_LIST, required, doc)

private fun obj(name: String, doc: String, required: Boolean = false) = FieldSpec(name, FieldType.OBJECT, required, doc)

private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

object Vocabulary {
    val triggers: List<TypeSpec> =
        listOf(
            TypeSpec(
                "manual",
                SpecKind.TRIGGER,
                "Never fires on its own. Run it from the app or POST /automations/{id}/fire."
            ),
            TypeSpec("boot_completed", SpecKind.TRIGGER, "Device finished booting, or this app was just reinstalled."),
            TypeSpec("power_connected", SpecKind.TRIGGER, "A charger was plugged in."),
            TypeSpec("power_disconnected", SpecKind.TRIGGER, "The charger was unplugged."),
            TypeSpec(
                "battery_level",
                SpecKind.TRIGGER,
                "Battery percentage crossed a threshold. Fires on the crossing, not repeatedly while past it.",
                listOf(
                    int("below", "Fire when the level drops to or under this percentage.", required = false),
                    int("above", "Fire when the level rises to or over this percentage.", required = false)
                )
            ),
            TypeSpec("screen_on", SpecKind.TRIGGER, "The display turned on."),
            TypeSpec("screen_off", SpecKind.TRIGGER, "The display turned off."),
            TypeSpec(
                "wifi_connected",
                SpecKind.TRIGGER,
                "Joined a Wi-Fi network. Omit ssid to fire on any network.",
                listOf(str("ssid", "Only fire for this network name.", required = false)),
                listOf(Requirement.LOCATION)
            ),
            TypeSpec("wifi_disconnected", SpecKind.TRIGGER, "Left a Wi-Fi network."),
            TypeSpec(
                "bluetooth_connected",
                SpecKind.TRIGGER,
                "A Bluetooth device connected. Omit device to fire for any.",
                listOf(str("device", "Device name or MAC address to match.", required = false)),
                listOf(Requirement.BLUETOOTH)
            ),
            TypeSpec(
                "bluetooth_disconnected",
                SpecKind.TRIGGER,
                "A Bluetooth device disconnected.",
                listOf(str("device", "Device name or MAC address to match.", required = false)),
                listOf(Requirement.BLUETOOTH)
            ),
            TypeSpec("headset_plugged", SpecKind.TRIGGER, "A wired headset was plugged in."),
            TypeSpec("headset_unplugged", SpecKind.TRIGGER, "A wired headset was unplugged."),
            TypeSpec(
                "airplane_mode",
                SpecKind.TRIGGER,
                "Airplane mode changed.",
                listOf(bool("enabled", "Only fire for this new state.", required = false))
            ),
            TypeSpec(
                "time_of_day",
                SpecKind.TRIGGER,
                "Fires at a wall-clock time, optionally only on some days.",
                listOf(
                    time("at", "24-hour time, HH:mm."),
                    strList("days", "Days to fire on: mon tue wed thu fri sat sun. Omit for every day.")
                ),
                listOf(Requirement.EXACT_ALARM)
            ),
            TypeSpec(
                "interval",
                SpecKind.TRIGGER,
                "Fires repeatedly. Doze can defer this while the screen is off.",
                listOf(int("minutes", "Minutes between fires; 1 or more.")),
                listOf(Requirement.EXACT_ALARM)
            ),
            TypeSpec(
                "notification_posted",
                SpecKind.TRIGGER,
                "A notification appeared. Matchers are ANDed; omit all to match every notification.",
                listOf(
                    str("package", "Only notifications from this package.", required = false),
                    str("title_contains", "Substring match on the title, case-insensitive.", required = false),
                    str("text_contains", "Substring match on the body, case-insensitive.", required = false)
                ),
                listOf(Requirement.NOTIFICATION_LISTENER)
            ),
            TypeSpec(
                "setting_changed",
                SpecKind.TRIGGER,
                "A system settings value changed. This is how you react to a Quick Settings tile " +
                    "or any other toggle that writes a setting, since Android has no broadcast for most of them.",
                listOf(
                    enum("scope", "Which settings table.", listOf("global", "secure", "system")),
                    str("key", "Setting name, for example adb_wifi_enabled."),
                    str("equals", "Only fire when the new value is exactly this.", required = false)
                )
            ),
            TypeSpec(
                "app_foreground",
                SpecKind.TRIGGER,
                "An app came to the foreground. Omit package to fire on every app switch.",
                listOf(str("package", "Only fire when this package comes forward.", required = false)),
                listOf(Requirement.ACCESSIBILITY)
            ),
            TypeSpec(
                "app_background",
                SpecKind.TRIGGER,
                "An app left the foreground. Omit package to fire on every app switch.",
                listOf(str("package", "Only fire when this package is the one being left.", required = false)),
                listOf(Requirement.ACCESSIBILITY)
            ),
            TypeSpec(
                "notification_removed",
                SpecKind.TRIGGER,
                "A notification was dismissed or cancelled.",
                listOf(
                    str("package", "Only notifications from this package.", required = false),
                    str("title_contains", "Substring match on the title, case-insensitive.", required = false)
                ),
                listOf(Requirement.NOTIFICATION_LISTENER)
            )
        )

    val conditions: List<TypeSpec> =
        listOf(
            TypeSpec(
                "time_between",
                SpecKind.CONDITION,
                "Now is inside a daily window. A window whose end is before its start wraps past midnight.",
                listOf(time("from", "24-hour start, HH:mm."), time("to", "24-hour end, HH:mm."))
            ),
            TypeSpec(
                "day_of_week",
                SpecKind.CONDITION,
                "Today is one of these days.",
                listOf(FieldSpec("days", FieldType.STRING_LIST, true, "Any of: ${DAYS.joinToString(" ")}."))
            ),
            TypeSpec(
                "battery_below",
                SpecKind.CONDITION,
                "Battery is under a percentage.",
                listOf(int("value", "Percentage, 0-100."))
            ),
            TypeSpec(
                "battery_above",
                SpecKind.CONDITION,
                "Battery is over a percentage.",
                listOf(int("value", "Percentage, 0-100."))
            ),
            TypeSpec(
                "charging",
                SpecKind.CONDITION,
                "Charger state matches.",
                listOf(bool("value", "true to require charging."))
            ),
            TypeSpec(
                "screen_on",
                SpecKind.CONDITION,
                "Display state matches.",
                listOf(bool("value", "true to require the screen on."))
            ),
            TypeSpec(
                "wifi_ssid",
                SpecKind.CONDITION,
                "Connected Wi-Fi network name matches.",
                listOf(str("equals", "Exact network name.")),
                listOf(Requirement.LOCATION)
            ),
            TypeSpec(
                "wifi_connected",
                SpecKind.CONDITION,
                "Wi-Fi connection state matches.",
                listOf(bool("value", "true to require a Wi-Fi connection."))
            ),
            TypeSpec(
                "dnd_active",
                SpecKind.CONDITION,
                "Do Not Disturb state matches.",
                listOf(bool("value", "true to require DND on."))
            ),
            TypeSpec(
                "app_foreground",
                SpecKind.CONDITION,
                "A package is in the foreground right now.",
                listOf(str("package", "Package name to require in front.")),
                listOf(Requirement.ACCESSIBILITY)
            ),
            TypeSpec(
                "app_installed",
                SpecKind.CONDITION,
                "A package is present on the device.",
                listOf(str("package", "Package name, for example com.termux."))
            )
        )

    val actions: List<TypeSpec> =
        listOf(
            TypeSpec(
                "notify",
                SpecKind.ACTION,
                "Post a notification.",
                listOf(
                    str("title", "Notification title."),
                    str("text", "Body text.", required = false),
                    int("id", "Reuse an id to replace an earlier notification.", required = false)
                ),
                listOf(Requirement.POST_NOTIFICATIONS)
            ),
            TypeSpec(
                "cancel_notification",
                SpecKind.ACTION,
                "Cancel a notification this app posted.",
                listOf(int("id", "The id passed to notify."))
            ),
            TypeSpec(
                "dnd",
                SpecKind.ACTION,
                "Set Do Not Disturb.",
                listOf(enum("mode", "Filter to apply.", listOf("off", "priority", "none", "alarms"))),
                listOf(Requirement.DND_POLICY)
            ),
            TypeSpec(
                "ringer_mode",
                SpecKind.ACTION,
                "Set the ringer.",
                listOf(enum("mode", "Ringer state.", listOf("normal", "vibrate", "silent"))),
                listOf(Requirement.DND_POLICY)
            ),
            TypeSpec(
                "volume",
                SpecKind.ACTION,
                "Set a volume stream as a percentage of its maximum.",
                listOf(
                    enum("stream", "Which stream.", listOf("music", "ring", "alarm", "notification", "call", "system")),
                    int("level", "0-100.")
                )
            ),
            TypeSpec(
                "media",
                SpecKind.ACTION,
                "Send a media key to whatever currently holds audio focus.",
                listOf(
                    enum("command", "Key to send.", listOf("play", "pause", "play_pause", "next", "previous", "stop"))
                )
            ),
            TypeSpec(
                "vibrate",
                SpecKind.ACTION,
                "Vibrate the device.",
                listOf(int("ms", "Duration in milliseconds.", required = false, default = 300))
            ),
            TypeSpec(
                "torch",
                SpecKind.ACTION,
                "Set the camera flash.",
                listOf(bool("on", "true to switch the torch on."))
            ),
            TypeSpec(
                "tts",
                SpecKind.ACTION,
                "Speak text aloud. The engine warms up on first use, so the first call can lag.",
                listOf(str("text", "What to say."))
            ),
            TypeSpec(
                "http_request",
                SpecKind.ACTION,
                "Call an HTTP endpoint. Blocks the run until it answers or times out; the status code lands in the run log.",
                listOf(
                    enum(
                        "method",
                        "HTTP verb.",
                        listOf("GET", "POST", "PUT", "DELETE", "PATCH"),
                        required = false,
                        default = "GET"
                    ),
                    str("url", "Absolute URL."),
                    obj("headers", "Header name to value."),
                    str("body", "Request body, sent as-is.", required = false),
                    int("timeout_ms", "Connect and read timeout.", required = false, default = 10000)
                )
            ),
            TypeSpec(
                "launch_app",
                SpecKind.ACTION,
                "Start an app's launcher activity.",
                listOf(str("package", "Package name."))
            ),
            TypeSpec(
                "start_activity",
                SpecKind.ACTION,
                "Start an activity from a URI, for example an intent: or https: URL.",
                listOf(str("uri", "URI to open."))
            ),
            TypeSpec(
                "broadcast",
                SpecKind.ACTION,
                "Send a broadcast intent. String extras only.",
                listOf(
                    str("action", "Intent action."),
                    obj("extras", "Extra name to string value."),
                    str("package", "Restrict delivery to this package.", required = false)
                )
            ),
            TypeSpec(
                "clipboard",
                SpecKind.ACTION,
                "Replace the clipboard contents.",
                listOf(str("text", "Text to copy."))
            ),
            TypeSpec(
                "secure_setting",
                SpecKind.ACTION,
                "Write Settings.Secure. Needs the adb grant.",
                listOf(str("key", "Setting name."), str("value", "New value.")),
                listOf(Requirement.WRITE_SECURE_SETTINGS)
            ),
            TypeSpec(
                "global_setting",
                SpecKind.ACTION,
                "Write Settings.Global. Needs the adb grant. This is how adb_wifi_enabled and similar are reached.",
                listOf(str("key", "Setting name."), str("value", "New value.")),
                listOf(Requirement.WRITE_SECURE_SETTINGS)
            ),
            TypeSpec(
                "system_setting",
                SpecKind.ACTION,
                "Write Settings.System, for example screen_brightness. Needs the Modify system settings toggle.",
                listOf(str("key", "Setting name."), str("value", "New value.")),
                listOf(Requirement.WRITE_SYSTEM_SETTINGS)
            ),
            TypeSpec(
                "wake_screen",
                SpecKind.ACTION,
                "Turn the display on briefly.",
                listOf(int("seconds", "How long to hold it on.", required = false, default = 5))
            ),
            TypeSpec(
                "wait",
                SpecKind.ACTION,
                "Pause before the next action.",
                listOf(int("ms", "Milliseconds; capped at 30000."))
            ),
            TypeSpec(
                "log",
                SpecKind.ACTION,
                "Write a line into the run record. Useful for confirming a branch was taken.",
                listOf(str("message", "Text to record."))
            ),
            TypeSpec(
                "ssh",
                SpecKind.ACTION,
                "Run a command on another machine over SSH, with the key kata generates on first " +
                    "use. Blocks until the command exits or the timeout passes, and records the exit " +
                    "status and output. A host that is off or unreachable fails this action without " +
                    "affecting anything else.",
                listOf(
                    str("host", "Hostname or IP address."),
                    str("user", "Login name."),
                    str("command", "Command to run. Runs without a login shell, so use absolute paths."),
                    int("port", "SSH port.", required = false, default = 22),
                    int("timeout_ms", "Connect and run timeout.", required = false, default = 8000)
                )
            ),
            TypeSpec(
                "global_action",
                SpecKind.ACTION,
                "Perform a system navigation action, the same ones the gesture bar and shade expose.",
                listOf(
                    enum(
                        "action",
                        "Which action.",
                        listOf(
                            "back", "home", "recents", "notifications", "quick_settings",
                            "lock_screen", "screenshot", "dismiss_shade", "power_dialog"
                        )
                    )
                ),
                listOf(Requirement.ACCESSIBILITY)
            ),
            TypeSpec(
                "tap_ui",
                SpecKind.ACTION,
                "Find something on screen and tap it. The escape hatch for anything with no API behind " +
                    "it, and brittle by nature: it matches what is drawn, so a vendor UI change can break " +
                    "it. Give one of text, content_description, or view_id.",
                listOf(
                    str("text", "Visible text to match.", required = false),
                    str("content_description", "Accessibility label to match.", required = false),
                    str("view_id", "Full view id, e.g. com.android.settings:id/switch_widget.", required = false),
                    bool(
                        "exact",
                        "Require the whole label to match rather than contain it.",
                        required = false,
                        default = false
                    ),
                    int("timeout_ms", "How long to wait for the target to appear.", required = false, default = 3000)
                ),
                listOf(Requirement.ACCESSIBILITY)
            ),
            TypeSpec(
                "set_enabled",
                SpecKind.ACTION,
                "Enable or disable another automation, so rules can arm and disarm each other.",
                listOf(str("id", "Target automation id."), bool("enabled", "New state."))
            )
        )

    val all: List<TypeSpec> = triggers + conditions + actions

    private val byKind: Map<SpecKind, Map<String, TypeSpec>> =
        all.groupBy { it.kind }.mapValues { (_, specs) -> specs.associateBy { it.id } }

    fun find(kind: SpecKind, id: String): TypeSpec? = byKind[kind]?.get(id)

    fun ids(kind: SpecKind): List<String> = byKind[kind]?.keys?.sorted().orEmpty()
}
