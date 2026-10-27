package com.clearcmos.kata.api

import com.clearcmos.kata.engine.ShadeEntry

/**
 * The phone's notification shade as the control API sees it: read it, clear from it, and open
 * an entry the way a tap in the shade would. The real one runs through the notification
 * listener; tests use a stub.
 */
interface NotificationShade {
    /** The shade's entries in its own order, or null while the listener is not bound. */
    fun entries(): List<ShadeEntry>?

    fun clear(key: String): ShadeOutcome

    fun clearAll(): ShadeOutcome

    fun open(key: String): ShadeOutcome

    /** The app's launcher icon as PNG bytes, or null for a package that is not installed. */
    fun appIcon(packageName: String): ByteArray?
}

sealed interface ShadeOutcome {
    data class Done(val facts: Map<String, Any?>) : ShadeOutcome

    data class Refused(val status: Int, val reason: String) : ShadeOutcome

    /** The listener is not bound: notification access is off, or the platform has not rebound it yet. */
    data object Unbound : ShadeOutcome
}
