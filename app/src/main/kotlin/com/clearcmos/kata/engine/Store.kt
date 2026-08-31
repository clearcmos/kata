package com.clearcmos.kata.engine

import android.content.Context
import android.util.Log
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.Json
import java.io.File
import org.json.JSONArray

/**
 * The on-device copy of the automation set.
 *
 * Source of truth lives in the repo on the workstation; this is the materialized copy the
 * engine runs. Writes are atomic (temp file plus rename) because a half-written rule set that
 * survives a reboot would silently disable automations with no obvious cause.
 */
class Store(context: Context) {
    private val file = File(context.filesDir, "automations.json")
    private val lock = Any()
    private var cache: List<Automation>? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun all(): List<Automation> = synchronized(lock) {
        cache ?: load().also { cache = it }
    }

    fun enabled(): List<Automation> = all().filter { it.enabled }

    fun find(id: String): Automation? = all().firstOrNull { it.id == id }

    /** Replaces the whole set, which is what a repo sync does. */
    fun replaceAll(automations: List<Automation>) {
        synchronized(lock) {
            persist(automations)
            cache = automations
        }
        notifyChanged()
    }

    /** Inserts or replaces one automation, preserving the order of the rest. */
    fun upsert(automation: Automation) {
        synchronized(lock) {
            val current = (cache ?: load()).toMutableList()
            val index = current.indexOfFirst { it.id == automation.id }
            if (index >= 0) current[index] = automation else current.add(automation)
            persist(current)
            cache = current
        }
        notifyChanged()
    }

    fun delete(id: String): Boolean {
        val removed: Boolean
        synchronized(lock) {
            val current = (cache ?: load()).toMutableList()
            removed = current.removeAll { it.id == id }
            if (removed) {
                persist(current)
                cache = current
            }
        }
        if (removed) notifyChanged()
        return removed
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val automation = find(id) ?: return false
        if (automation.enabled == enabled) return true
        upsert(automation.copy(enabled = enabled))
        return true
    }

    fun setParam(id: String, key: String, value: String): Boolean {
        val automation = find(id) ?: return false
        if (automation.params.none { it.key == key }) return false
        val params = automation.params.map { if (it.key == key) it.copy(value = value) else it }
        upsert(automation.copy(params = params))
        return true
    }

    fun onChange(listener: () -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    private fun notifyChanged() {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { runCatching { it() }.onFailure { e -> Log.e(TAG, "listener failed", e) } }
    }

    private fun load(): List<Automation> {
        if (!file.exists()) return emptyList()
        return runCatching {
            Json.toList(JSONArray(file.readText())).mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                (entry as? Map<String, Any?>)?.let { Automation.fromMap(it) }
            }
        }.getOrElse {
            // A corrupt file must not take the engine down; the rules are recoverable from
            // the repo, and reporting empty keeps the API and UI usable to say so.
            Log.e(TAG, "automations.json unreadable, starting empty", it)
            emptyList()
        }
    }

    private fun persist(automations: List<Automation>) {
        val text = Json.toJson(automations.map { it.toMap() }).toString(2)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    private companion object {
        const val TAG = "KataStore"
    }
}
