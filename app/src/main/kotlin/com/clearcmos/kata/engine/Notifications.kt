package com.clearcmos.kata.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Two channels on purpose. The engine's own persistent notification is unavoidable for a
 * foreground service, so it sits on a MIN-importance channel the user can silence without
 * also silencing the notifications their automations post.
 */
object Notifications {
    const val CHANNEL_ENGINE = "kata_engine"
    const val CHANNEL_AUTOMATION = "kata_automation"
    const val FOREGROUND_ID = 1

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ENGINE,
                "Engine status",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "The persistent notification that keeps the automation engine running."
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AUTOMATION,
                "Automation notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications posted by your automations."
            }
        )
    }
}
