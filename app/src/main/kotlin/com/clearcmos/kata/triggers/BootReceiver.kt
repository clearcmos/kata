package com.clearcmos.kata.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clearcmos.kata.engine.KataService

/**
 * Restarts the engine after a reboot or an app update. Without the MY_PACKAGE_REPLACED half,
 * every `adb install -r` would leave the engine dead until the app was opened by hand, which
 * is exactly when a broken automation looks like a broken rule.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                KataService.start(context, bootEvent = intent.action == Intent.ACTION_BOOT_COMPLETED)
        }
    }
}
