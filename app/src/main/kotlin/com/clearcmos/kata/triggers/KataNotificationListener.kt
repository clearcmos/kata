package com.clearcmos.kata.triggers

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.clearcmos.kata.api.ShadeOutcome
import com.clearcmos.kata.engine.Kata
import com.clearcmos.kata.engine.Shade
import com.clearcmos.kata.engine.ShadeEntry
import com.clearcmos.kata.engine.TriggerEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds notification_posted, notification_removed, and notifications_changed, and backs the
 * control API's view of the shade.
 *
 * The platform binds this only after the user grants notification access in Settings, so the
 * class existing costs nothing until then. It reads every notification on the device, which is
 * why /capabilities reports its grant state explicitly rather than leaving it implicit.
 */
class KataNotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var checkPending = false

    // Read from the API's worker threads as well as the main thread.
    private val appLabels = ConcurrentHashMap<String, String>()

    override fun onListenerConnected() {
        connected = this
        // The engine may have started before the platform bound this, and its start-up
        // announcement then found nothing to read.
        if (wanted()) announce(force = true)
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        emit("notification_posted", sbn)
        scheduleCheck()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        emit("notification_removed", sbn)
        scheduleCheck()
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

    // -- notifications_changed -----------------------------------------------------------

    private fun wanted(): Boolean = Kata.store(applicationContext).enabled().any {
        it.trigger.type ==
            "notifications_changed"
    }

    /**
     * Checks the shade once, a moment after the first change of a burst. The window opens on
     * the first event and is not pushed back by later ones: a media player reposting every
     * second would otherwise hold a real change back for as long as it played.
     */
    private fun scheduleCheck() {
        if (checkPending || !wanted()) return
        checkPending = true
        handler.postDelayed({
            checkPending = false
            announce(force = false)
        }, SETTLE_MS)
    }

    // -- the shade ----------------------------------------------------------------------

    fun snapshot(): List<ShadeEntry> {
        val ranking = currentRanking
        val order = ranking?.orderedKeys.orEmpty().withIndex().associate { (index, key) -> key to index }
        val entries =
            activeNotifications
                .orEmpty()
                .filter { it.packageName != packageName }
                // One UI's Now Bar posts a System UI companion for each ongoing activity, titled
                // with its channel name ("MediaOngoingActivity"). It draws in the Now Bar, not
                // the shade, and is told apart only by this Samsung extra.
                .filterNot { sbn -> sbn.notification.extras.keySet().any { it.startsWith(SAMSUNG_ONGOING_ACTIVITY) } }
                .sortedBy { order[it.key] ?: Int.MAX_VALUE }
                .mapNotNull { sbn ->
                    val rank = Ranking()
                    val ranked = ranking?.getRanking(sbn.key, rank) == true
                    val hidden =
                        ranked &&
                            (
                                rank.isSuspended ||
                                    rank.suppressedVisualEffects and
                                    NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST != 0
                                )
                    if (hidden) null else entryOf(sbn, ranked && rank.importance <= NotificationManager.IMPORTANCE_LOW)
                }
        return Shade.visible(entries)
    }

    private fun entryOf(sbn: StatusBarNotification, silent: Boolean): ShadeEntry {
        val notification = sbn.notification
        val extras = notification.extras
        fun text(key: String) = extras.getCharSequence(key)?.toString()?.trim().orEmpty()
        val title = text(Notification.EXTRA_TITLE).ifEmpty { text(Notification.EXTRA_CONVERSATION_TITLE) }
        val body =
            text(Notification.EXTRA_BIG_TEXT)
                .ifEmpty { text(Notification.EXTRA_TEXT) }
                .ifEmpty {
                    extras
                        .getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                        .orEmpty()
                        .joinToString("\n")
                }
        val ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        val thread = messengerThread(extras)
        return ShadeEntry(
            key = sbn.key,
            packageName = sbn.packageName,
            app = text(EXTRA_SUBSTITUTE_APP_NAME).ifEmpty { appLabel(sbn.packageName) },
            title = title.take(MAX_TITLE),
            text = body.take(MAX_TEXT),
            postedAt = notification.`when`.takeIf { it > 0 } ?: sbn.postTime,
            clearable = sbn.isClearable,
            ongoing = ongoing,
            silent = silent,
            openable = notification.contentIntent != null,
            threadId = thread?.get(MESSENGER_THREAD_ID)?.toString(),
            threadType = thread?.get(MESSENGER_THREAD_TYPE)?.toString(),
            group = sbn.groupKey,
            groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        )
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }

    private fun find(key: String): StatusBarNotification? = activeNotifications.orEmpty().firstOrNull { it.key == key }

    fun clear(key: String): ShadeOutcome {
        val sbn = find(key) ?: return gone()
        if (!sbn.isClearable) return ShadeOutcome.Refused(422, "this notification is ongoing and cannot be cleared")
        cancelNotification(key)
        return ShadeOutcome.Done(mapOf("cleared" to key))
    }

    fun clearAll(): ShadeOutcome {
        val clearable = snapshot().count { it.clearable }
        cancelAllNotifications()
        return ShadeOutcome.Done(mapOf("cleared" to clearable))
    }

    /**
     * Does what a tap in the shade does: sends the content intent, then clears the entry if it
     * asked to be cleared on a tap. Since Android 14 a sender has to opt in to lend its own
     * background-start privilege to a PendingIntent, and "Appear on top" is that privilege
     * here; without it the platform drops the launch while send() still returns normally.
     */
    fun open(key: String): ShadeOutcome {
        val sbn = find(key) ?: return gone()
        val intent =
            sbn.notification.contentIntent ?: return ShadeOutcome.Refused(422, "this notification has nothing to open")
        if (!Settings.canDrawOverlays(this)) {
            return ShadeOutcome.Refused(
                409,
                "kata needs Appear on top to open apps: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
            )
        }
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode)
        try {
            intent.send(this, 0, null, null, null, null, options.toBundle())
        } catch (e: PendingIntent.CanceledException) {
            return ShadeOutcome.Refused(410, "the app withdrew this notification's action")
        }
        if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) cancelNotification(key)
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return ShadeOutcome.Done(mapOf("opened" to key, "locked" to locked))
    }

    /**
     * Messenger's own data map on a message notification, which names the thread as its web
     * client does. Another app's extras can hold anything, so a map that does not read is none.
     */
    private fun messengerThread(extras: Bundle): Map<*, *>? = try {
        extras.getSerializable(MESSENGER_DATA, HashMap::class.java)
    } catch (e: RuntimeException) {
        null
    }

    private fun gone() = ShadeOutcome.Refused(404, "the phone no longer shows this notification")

    companion object {
        /** Mirrors are rarely read within less; a burst of posts lands in one announcement. */
        private const val SETTLE_MS = 1_500L
        private const val MAX_TITLE = 200
        private const val MAX_TEXT = 600

        /** Notification.EXTRA_SUBSTITUTE_APP_NAME is a system API; its value is public. */
        private const val EXTRA_SUBSTITUTE_APP_NAME = "android.substName"
        private const val SAMSUNG_ONGOING_ACTIVITY = "android.ongoingActivityNoti"
        private const val MESSENGER_DATA = "extra_notification_data_map"
        private const val MESSENGER_THREAD_ID = "consistent_thread_fbid"
        private const val MESSENGER_THREAD_TYPE = "thread_key_type"

        @Volatile
        var connected: KataNotificationListener? = null
            private set

        private var lastSignature: String? = null

        /**
         * Publishes notifications_changed when the shade differs from the last announcement,
         * or regardless when `force`, which the engine start and each Wi-Fi join use so a
         * mirror learns the count it would otherwise wait for the next change to hear.
         */
        @Synchronized
        fun announce(force: Boolean) {
            val listener = connected ?: return
            val entries = runCatching { listener.snapshot() }.getOrNull() ?: return
            val signature = Shade.signature(entries)
            if (!force && signature == lastSignature) return
            lastSignature = signature
            Kata
                .engine(listener.applicationContext)
                .onEvent(TriggerEvent("notifications_changed", mapOf("count" to Shade.count(entries).toString())))
        }

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
