package com.clearcmos.kata.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.clearcmos.kata.R
import com.clearcmos.kata.api.ApiToken
import com.clearcmos.kata.api.ControlApi
import com.clearcmos.kata.api.TinyHttpServer
import com.clearcmos.kata.triggers.TriggerRegistry
import com.clearcmos.kata.ui.MainActivity

/**
 * The engine's host process.
 *
 * A foreground service is the only way to keep broadcast registrations and a listening socket
 * alive on a modern phone, and on One UI it is still not sufficient on its own: the app also
 * needs to be excluded from "put unused apps to sleep". The persistent notification sits on a
 * MIN-importance channel so it collapses into the status bar rather than nagging.
 */
class KataService : Service() {
    private lateinit var engine: Engine
    private lateinit var registry: TriggerRegistry
    private var server: TinyHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Notifications.ensureChannels(this)
        startForeground(
            Notifications.FOREGROUND_ID,
            buildNotification("starting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        val store = Kata.store(this)
        engine = Kata.engine(this)
        registry = TriggerRegistry(this, store, engine)

        val api = ControlApi(this, engine)
        server = TinyHttpServer(PORT) { api.handle(it) }.also { it.start() }

        // Every mutation, from the API or from the phone, lands in the Store, so this is the
        // single place triggers get rebound. A rule edited here takes effect immediately
        // rather than at the next reboot.
        store.onChange { registry.refresh() }
        registry.refresh()
        updateNotification()
        Log.i(TAG, "engine up: ${store.enabled().size} enabled, token at ${externalTokenPath()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BOOT -> engine.onEvent(TriggerEvent("boot_completed"))
            ACTION_RESCHEDULE -> registry.scheduleAlarms()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        registry.stop()
        server?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(detail: String): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        return Notification
            .Builder(this, Notifications.CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_kata)
            .setContentTitle("kata")
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val store = Kata.store(this)
        val enabled = store.enabled().size
        val total = store.all().size
        val detail = "$enabled of $total automations armed, API on 127.0.0.1:$PORT"
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(Notifications.FOREGROUND_ID, buildNotification(detail))
    }

    private fun externalTokenPath(): String = getExternalFilesDir(null)?.absolutePath.orEmpty() + "/api-token"

    companion object {
        const val PORT = 8770

        /**
         * Whether the engine is up in this process. It resets with the process, which is the
         * answer the UI wants: a killed service is a stopped engine, however it died.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val TAG = "KataService"
        private const val ACTION_BOOT = "com.clearcmos.kata.BOOT"
        private const val ACTION_RESCHEDULE = "com.clearcmos.kata.RESCHEDULE"

        fun start(context: Context, bootEvent: Boolean = false) {
            val intent =
                Intent(context, KataService::class.java).apply {
                    if (bootEvent) action = ACTION_BOOT
                }
            context.startForegroundService(intent)
        }

        fun rescheduleAlarms(context: Context) {
            val intent = Intent(context, KataService::class.java).apply { action = ACTION_RESCHEDULE }
            context.startForegroundService(intent)
        }

        /** Warms the token so the CLI can read it even before the first API call. */
        fun token(context: Context): String = ApiToken.get(context)
    }
}
