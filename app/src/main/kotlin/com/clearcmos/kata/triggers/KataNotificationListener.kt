package com.clearcmos.kata.triggers

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.clearcmos.kata.engine.Kata
import com.clearcmos.kata.engine.TriggerEvent

/**
 * Feeds notification_posted and notification_removed.
 *
 * The platform binds this only after the user grants notification access in Settings, so the
 * class existing costs nothing until then. It reads every notification on the device, which is
 * why /capabilities reports its grant state explicitly rather than leaving it implicit.
 */
class KataNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        emit("notification_posted", sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        emit("notification_removed", sbn)
    }

    private fun emit(type: String, sbn: StatusBarNotification) {
        // Its own notifications would otherwise let a notify action retrigger the rule that posted it.
        if (sbn.packageName == packageName) return
        val extras = sbn.notification?.extras
        val facts =
            buildMap {
                put("package", sbn.packageName)
                extras?.getCharSequence(Notification.EXTRA_TITLE)?.let { put("title", it.toString()) }
                extras?.getCharSequence(Notification.EXTRA_TEXT)?.let { put("text", it.toString()) }
            }
        Kata.engine(applicationContext).onEvent(TriggerEvent(type, facts))
    }

    companion object {
        fun isEnabled(context: android.content.Context): Boolean {
            val flat =
                android.provider.Settings.Secure
                    .getString(
                        context.contentResolver,
                        "enabled_notification_listeners"
                    ).orEmpty()
            return flat.split(":").any { it.startsWith("${context.packageName}/") }
        }
    }
}
