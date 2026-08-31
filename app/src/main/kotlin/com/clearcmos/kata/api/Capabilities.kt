package com.clearcmos.kata.api

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.clearcmos.kata.model.Requirement
import com.clearcmos.kata.model.Vocabulary
import com.clearcmos.kata.triggers.KataAccessibilityService
import com.clearcmos.kata.triggers.KataNotificationListener

/**
 * Resolves every declared [Requirement] against live device state.
 *
 * This is what makes the authoring loop closed rather than open. An agent reads this before
 * writing rules and learns that, say, DND access is ungranted on this phone, instead of
 * shipping a rule that installs cleanly, validates cleanly, and then fails at 22:00.
 */
class Capabilities(private val context: Context) {
    data class Status(val satisfied: Boolean, val remedy: String)

    fun status(requirement: Requirement): Status = when (requirement) {
        Requirement.POST_NOTIFICATIONS ->
            Status(
                granted(Manifest.permission.POST_NOTIFICATIONS),
                "Open kata and allow notifications, or: adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS"
            )

        Requirement.WRITE_SECURE_SETTINGS ->
            Status(
                granted(Manifest.permission.WRITE_SECURE_SETTINGS),
                "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
            )

        Requirement.WRITE_SYSTEM_SETTINGS ->
            Status(
                Settings.System.canWrite(context),
                "Settings > Apps > Special app access > Modify system settings > kata"
            )

        Requirement.NOTIFICATION_LISTENER ->
            Status(
                KataNotificationListener.isEnabled(context),
                "Settings > Notifications > Device and app notifications > kata"
            )

        Requirement.DND_POLICY ->
            Status(
                context
                    .getSystemService(NotificationManager::class.java)
                    ?.isNotificationPolicyAccessGranted == true,
                "Settings > Notifications > Do Not Disturb > App access > kata"
            )

        Requirement.LOCATION ->
            Status(
                granted(Manifest.permission.ACCESS_FINE_LOCATION),
                "Open kata and allow precise location; needed only to read the connected Wi-Fi SSID"
            )

        Requirement.BLUETOOTH ->
            Status(
                granted(Manifest.permission.BLUETOOTH_CONNECT),
                "Open kata and allow nearby devices; without it Bluetooth rules match on MAC address only"
            )

        Requirement.ACCESSIBILITY ->
            Status(
                KataAccessibilityService.isEnabled(context),
                "Settings > Accessibility > Installed apps > kata. Lets kata see which app is in " +
                    "front and tap on-screen controls; it can read screen content while on."
            )

        Requirement.EXACT_ALARM ->
            Status(
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true,
                "Settings > Apps > Special app access > Alarms and reminders > kata"
            )
    }

    fun unmet(requirements: List<Requirement>): List<Requirement> = requirements.filterNot { status(it).satisfied }

    /** The full payload behind GET /capabilities: device facts, grants, and annotated vocabulary. */
    fun snapshot(): Map<String, Any?> {
        val requirements =
            Requirement.entries.associate { requirement ->
                val status = status(requirement)
                requirement.name.lowercase() to
                    mapOf(
                        "satisfied" to status.satisfied,
                        "remedy" to status.remedy
                    )
            }
        return mapOf(
            "package" to pkg,
            "device" to
                mapOf(
                    "model" to Build.MODEL,
                    "manufacturer" to Build.MANUFACTURER,
                    "android_release" to Build.VERSION.RELEASE,
                    "sdk_int" to Build.VERSION.SDK_INT
                ),
            "requirements" to requirements,
            "triggers" to Vocabulary.triggers.map { annotate(it) },
            "conditions" to Vocabulary.conditions.map { annotate(it) },
            "actions" to Vocabulary.actions.map { annotate(it) }
        )
    }

    private fun annotate(spec: com.clearcmos.kata.model.TypeSpec): Map<String, Any?> {
        val missing = unmet(spec.requires)
        return spec.toMap() +
            mapOf(
                "available" to missing.isEmpty(),
                "missing" to missing.map { it.name.lowercase() }
            )
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val pkg: String get() = context.packageName
}
