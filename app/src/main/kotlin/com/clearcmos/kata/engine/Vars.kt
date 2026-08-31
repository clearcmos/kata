package com.clearcmos.kata.engine

import android.content.Context
import android.util.Log
import com.clearcmos.kata.model.Json
import java.io.File
import org.json.JSONObject

/**
 * Variables that outlive a single run.
 *
 * Kept separate from an automation's params: params are configuration a person edits, whereas
 * these are values a rule wrote for itself. Mixing them would mean a sync either clobbered
 * state or refused to update configuration.
 */
class VarStore(context: Context) {
    private val file = File(context.filesDir, "vars.json")
    private val lock = Any()
    private var cache: MutableMap<String, String>? = null

    fun all(): Map<String, String> = synchronized(lock) { LinkedHashMap(load()) }

    fun get(name: String): String? = synchronized(lock) { load()[name] }

    fun set(name: String, value: String) {
        synchronized(lock) {
            val current = load()
            current[name] = value
            persist(current)
        }
    }

    fun remove(name: String) {
        synchronized(lock) {
            val current = load()
            if (current.remove(name) != null) persist(current)
        }
    }

    fun clear() {
        synchronized(lock) { persist(mutableMapOf()) }
    }

    private fun load(): MutableMap<String, String> {
        cache?.let { return it }
        val loaded = LinkedHashMap<String, String>()
        if (file.exists()) {
            runCatching {
                Json.toMap(JSONObject(file.readText())).forEach { (key, value) ->
                    loaded[key] = value?.toString().orEmpty()
                }
            }.onFailure { Log.e(TAG, "vars.json unreadable, starting empty", it) }
        }
        cache = loaded
        return loaded
    }

    private fun persist(values: MutableMap<String, String>) {
        cache = values
        runCatching { file.writeText(Json.toJson(values.toMap<String, Any?>()).toString(2)) }
            .onFailure { Log.e(TAG, "could not persist vars", it) }
    }

    private companion object {
        const val TAG = "KataVars"
    }
}

/**
 * The variable scope for one run.
 *
 * Seeded from the trigger's facts, so a rule can use what fired it without any plumbing:
 * app_foreground publishes `package`, wifi_connected publishes `ssid`, setting_changed
 * publishes `key` and `value`. Each action's outputs are merged in as it completes, so a later
 * action sees what an earlier one produced.
 *
 * Run-scoped values shadow persisted ones, so a fact from this run always beats a stale value
 * left behind by a previous one.
 */
class RunVars(private val store: VarStore, seed: Map<String, String> = emptyMap()) {
    private val local = LinkedHashMap<String, String>(seed)

    fun snapshot(): Map<String, String> = store.all() + local

    fun get(name: String): String? = local[name] ?: store.get(name)

    fun put(name: String, value: String) {
        local[name] = value
    }

    fun putAll(values: Map<String, String>) {
        local.putAll(values)
    }

    fun persist(name: String, value: String) {
        local[name] = value
        store.set(name, value)
    }
}
