package com.clearcmos.kata.engine

import android.content.Context

/**
 * Process-wide singletons.
 *
 * Receivers, the notification listener, the control API, and the UI all need the same Store
 * and Engine. A custom Application subclass would work too, but this keeps the wiring visible
 * at each use site and lets a receiver that woke a cold process build only what it needs.
 */
object Kata {
    @Volatile
    private var store: Store? = null

    @Volatile
    private var runLog: RunLog? = null

    @Volatile
    private var engine: Engine? = null

    fun store(context: Context): Store = store ?: synchronized(this) {
        store ?: Store(context.applicationContext).also { store = it }
    }

    fun runLog(context: Context): RunLog = runLog ?: synchronized(this) {
        runLog ?: RunLog(context.applicationContext).also { runLog = it }
    }

    fun engine(context: Context): Engine = engine ?: synchronized(this) {
        engine ?: Engine(
            context.applicationContext,
            store(context),
            runLog(context)
        ).also { engine = it }
    }
}
