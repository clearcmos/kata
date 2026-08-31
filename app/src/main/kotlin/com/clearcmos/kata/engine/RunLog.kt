package com.clearcmos.kata.engine

import android.content.Context
import android.util.Log
import com.clearcmos.kata.model.Json
import java.io.File
import org.json.JSONArray

data class StepResult(val index: Int, val type: String, val ok: Boolean, val detail: String) {
    fun toMap(): Map<String, Any?> = mapOf("index" to index, "type" to type, "ok" to ok, "detail" to detail)
}

/**
 * What happened on one fire. `outcome` separates the three ways a run ends that look alike
 * from the outside: it ran, its conditions said no, or an action failed. Without that split,
 * "my automation did nothing" is unanswerable.
 */
data class RunRecord(
    val automationId: String,
    val name: String,
    val startedAt: Long,
    val durationMs: Long,
    val source: String,
    val outcome: String,
    val steps: List<StepResult>,
    val error: String? = null
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("automation_id", automationId)
        put("name", name)
        put("started_at", startedAt)
        put("duration_ms", durationMs)
        put("source", source)
        put("outcome", outcome)
        put("steps", steps.map { it.toMap() })
        if (error != null) put("error", error)
    }

    companion object {
        const val OUTCOME_RAN = "ran"
        const val OUTCOME_SKIPPED = "skipped_conditions"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_DRY_RUN = "dry_run"
    }
}

/** A capped, persisted ring of recent runs. This is the only way to debug a rule after the fact. */
class RunLog(context: Context) {
    private val file = File(context.filesDir, "runs.json")
    private val lock = Any()
    private val records = ArrayDeque<RunRecord>()
    private var loaded = false

    fun record(record: RunRecord) {
        synchronized(lock) {
            ensureLoaded()
            records.addFirst(record)
            while (records.size > CAPACITY) records.removeLast()
            persist()
        }
    }

    fun recent(limit: Int = 50, automationId: String? = null): List<RunRecord> = synchronized(lock) {
        ensureLoaded()
        records
            .asSequence()
            .filter { automationId == null || it.automationId == automationId }
            .take(limit)
            .toList()
    }

    fun clear() {
        synchronized(lock) {
            ensureLoaded()
            records.clear()
            persist()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            Json.toList(JSONArray(file.readText())).forEach { entry ->
                @Suppress("UNCHECKED_CAST")
                val map = entry as? Map<String, Any?> ?: return@forEach
                records.addLast(
                    RunRecord(
                        automationId = map["automation_id"]?.toString().orEmpty(),
                        name = map["name"]?.toString().orEmpty(),
                        startedAt = (map["started_at"] as? Number)?.toLong() ?: 0L,
                        durationMs = (map["duration_ms"] as? Number)?.toLong() ?: 0L,
                        source = map["source"]?.toString().orEmpty(),
                        outcome = map["outcome"]?.toString().orEmpty(),
                        steps =
                        (map["steps"] as? List<*>).orEmpty().mapNotNull { step ->
                            @Suppress("UNCHECKED_CAST")
                            val s = step as? Map<String, Any?> ?: return@mapNotNull null
                            StepResult(
                                index = (s["index"] as? Number)?.toInt() ?: 0,
                                type = s["type"]?.toString().orEmpty(),
                                ok = s["ok"] as? Boolean ?: false,
                                detail = s["detail"]?.toString().orEmpty()
                            )
                        },
                        error = map["error"]?.toString()
                    )
                )
            }
        }.onFailure { Log.e(TAG, "runs.json unreadable, starting empty", it) }
    }

    private fun persist() {
        runCatching {
            file.writeText(Json.toJson(records.map { it.toMap() }).toString())
        }.onFailure { Log.e(TAG, "could not persist run log", it) }
    }

    private companion object {
        const val TAG = "KataRunLog"
        const val CAPACITY = 200
    }
}
