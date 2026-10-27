package com.clearcmos.kata.triggers

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.clearcmos.kata.api.NotificationShade
import com.clearcmos.kata.api.ShadeOutcome
import com.clearcmos.kata.engine.ShadeEntry
import java.io.ByteArrayOutputStream

/** The control API's shade, read and changed through the bound notification listener. */
class ListenerShade(private val context: Context) : NotificationShade {
    override fun entries(): List<ShadeEntry>? = KataNotificationListener.connected?.snapshot()

    override fun clear(key: String): ShadeOutcome =
        KataNotificationListener.connected?.clear(key) ?: ShadeOutcome.Unbound

    override fun clearAll(): ShadeOutcome = KataNotificationListener.connected?.clearAll() ?: ShadeOutcome.Unbound

    override fun open(key: String): ShadeOutcome = KataNotificationListener.connected?.open(key) ?: ShadeOutcome.Unbound

    override fun appIcon(packageName: String): ByteArray? = runCatching {
        val bitmap = context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    private companion object {
        /** Sharp at the largest size a desktop list row draws it, and a few kilobytes each. */
        const val ICON_PX = 96
    }
}
