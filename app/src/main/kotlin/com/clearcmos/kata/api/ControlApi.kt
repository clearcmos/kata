package com.clearcmos.kata.api

import android.content.Context
import android.util.Log
import com.clearcmos.kata.engine.Engine
import com.clearcmos.kata.engine.TriggerEvent
import com.clearcmos.kata.engine.TriggerMatcher
import com.clearcmos.kata.model.ArgError
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.Json
import com.clearcmos.kata.model.Validator
import com.clearcmos.kata.model.Vocabulary
import org.json.JSONObject

/**
 * The whole authoring surface, as HTTP over loopback.
 *
 * Every mutating route answers with what it accepted or exactly why it refused, and every
 * route that can run a rule answers with the resulting run record. That return path is the
 * point of the design: an agent can install, dry-run, simulate a trigger, and read the outcome
 * without a human watching the phone.
 */
class ControlApi(
    private val engine: Engine,
    private val expectedToken: () -> String,
    private val capabilities: CapabilityReporter
) {
    /** Assembles the real collaborators; the primary constructor is what tests use. */
    constructor(context: Context, engine: Engine) : this(
        engine = engine,
        expectedToken = { ApiToken.get(context) },
        capabilities = Capabilities(context)
    )

    private val store get() = engine.store
    private val runLog get() = engine.runLog

    fun handle(request: HttpRequest): HttpResponse {
        if (request.path == "/health") return ok(mapOf("ok" to true, "service" to "kata"))

        val expected = expectedToken()
        val presented =
            request.headers["x-kata-token"]
                ?: request.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        if (presented != expected) {
            return error(401, "missing or wrong X-Kata-Token header")
        }

        return try {
            route(request)
        } catch (e: ArgError) {
            error(422, e.message ?: "invalid request")
        } catch (e: Exception) {
            Log.e(TAG, "route failed", e)
            error(500, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        val segments =
            request.path
                .trim('/')
                .split('/')
                .filter { it.isNotEmpty() }
        return when {
            segments == listOf("capabilities") && request.method == "GET" ->
                ok(capabilities.snapshot())

            segments == listOf("schema") && request.method == "GET" ->
                ok(
                    mapOf(
                        "triggers" to Vocabulary.triggers.map { it.toMap() },
                        "conditions" to Vocabulary.conditions.map { it.toMap() },
                        "actions" to Vocabulary.actions.map { it.toMap() }
                    )
                )

            segments == listOf("automations") && request.method == "GET" ->
                ok(mapOf("automations" to store.all().map { it.toMap() }))

            segments == listOf("automations") && request.method == "PUT" ->
                replaceAll(request)

            segments == listOf("automations") && request.method == "POST" ->
                upsert(request)

            segments.size == 2 && segments[0] == "automations" && request.method == "GET" ->
                store.find(segments[1])?.let { ok(it.toMap()) } ?: notFound(segments[1])

            segments.size == 2 && segments[0] == "automations" && request.method == "DELETE" ->
                if (store.delete(segments[1])) {
                    ok(mapOf("deleted" to segments[1]))
                } else {
                    notFound(segments[1])
                }

            segments.size == 3 && segments[0] == "automations" && segments[2] in setOf("enable", "disable") ->
                setEnabled(segments[1], segments[2] == "enable")

            segments.size == 3 && segments[0] == "automations" && segments[2] == "fire" ->
                fire(segments[1], request)

            segments.size == 3 && segments[0] == "automations" && segments[2] == "params" ->
                setParam(segments[1], request)

            segments == listOf("validate") && request.method == "POST" ->
                validateOnly(request)

            segments == listOf("simulate") && request.method == "POST" ->
                simulate(request)

            // Persisted variables are state a rule wrote for itself, so they are the one thing
            // on the device that cannot be rebuilt from the repo. They need a way out, and back.
            segments == listOf("vars") && request.method == "GET" ->
                ok(mapOf("vars" to engine.vars().all()))

            segments == listOf("vars") && request.method == "PUT" -> {
                val incoming = Json.toMap(JSONObject(request.body).getJSONObject("vars"))
                val store = engine.vars()
                store.clear()
                incoming.forEach { (name, value) -> store.set(name, value?.toString().orEmpty()) }
                ok(mapOf("restored" to incoming.size))
            }

            segments == listOf("vars") && request.method == "DELETE" -> {
                engine.vars().clear()
                ok(mapOf("cleared" to true))
            }

            segments == listOf("runs") && request.method == "GET" ->
                ok(
                    mapOf(
                        "runs" to
                            runLog
                                .recent(
                                    limit = request.query["limit"]?.toIntOrNull() ?: 50,
                                    automationId = request.query["id"]
                                ).map { it.toMap() }
                    )
                )

            segments == listOf("runs") && request.method == "DELETE" -> {
                runLog.clear()
                ok(mapOf("cleared" to true))
            }

            else -> error(404, "no route for ${request.method} ${request.path}")
        }
    }

    // -- routes -------------------------------------------------------------------------

    private fun replaceAll(request: HttpRequest): HttpResponse {
        val body = JSONObject(request.body)
        val raw = Json.toList(body.getJSONArray("automations"))
        val parsed = ArrayList<Automation>(raw.size)
        val problems = LinkedHashMap<String, List<String>>()

        raw.forEachIndexed { index, entry ->
            @Suppress("UNCHECKED_CAST")
            val map = entry as? Map<String, Any?>
            if (map == null) {
                problems["automations[$index]"] = listOf("must be an object")
                return@forEachIndexed
            }
            val automation =
                try {
                    Automation.fromMap(map)
                } catch (e: ArgError) {
                    problems["automations[$index]"] = listOf(e.message ?: "unparseable")
                    return@forEachIndexed
                }
            val errors = Validator.validate(automation)
            if (errors.isEmpty()) parsed.add(automation) else problems[automation.id] = errors
        }

        val duplicates =
            parsed
                .groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        duplicates.forEach { problems[it] = listOf("id appears more than once in this set") }

        // All or nothing: a partially applied sync leaves the phone matching neither the repo
        // nor any intended state, and the difference is invisible from the device.
        if (problems.isNotEmpty()) {
            return HttpResponse(
                422,
                Json.toJson(mapOf("error" to "nothing installed", "problems" to problems)).toString()
            )
        }

        val resetParams = request.query["reset_params"] == "true"
        val installed = if (resetParams) {
            parsed
        } else {
            parsed.map { it.carryParamValues(store.find(it.id)) }
        }
        store.replaceAll(installed)
        return ok(
            mapOf(
                "installed" to installed.size,
                "ids" to installed.map { it.id },
                "params_reset" to resetParams
            )
        )
    }

    private fun upsert(request: HttpRequest): HttpResponse {
        val automation = Automation.fromMap(Json.toMap(JSONObject(request.body)))
        val errors = Validator.validate(automation)
        if (errors.isNotEmpty()) {
            return HttpResponse(
                422,
                Json.toJson(mapOf("error" to "validation failed", "problems" to errors)).toString()
            )
        }
        val installed = if (request.query["reset_params"] == "true") {
            automation
        } else {
            automation.carryParamValues(store.find(automation.id))
        }
        store.upsert(installed)
        return ok(mapOf("installed" to installed.id, "unmet" to unmetFor(installed)))
    }

    private fun validateOnly(request: HttpRequest): HttpResponse {
        val automation =
            try {
                Automation.fromMap(Json.toMap(JSONObject(request.body)))
            } catch (e: ArgError) {
                return ok(mapOf("valid" to false, "problems" to listOf(e.message)))
            }
        val errors = Validator.validate(automation)
        return ok(
            mapOf(
                "valid" to errors.isEmpty(),
                "problems" to errors,
                "unmet" to unmetFor(automation)
            )
        )
    }

    private fun setEnabled(id: String, enabled: Boolean): HttpResponse {
        if (!store.setEnabled(id, enabled)) return notFound(id)
        return ok(mapOf("id" to id, "enabled" to enabled))
    }

    private fun setParam(id: String, request: HttpRequest): HttpResponse {
        val body = Json.toMap(JSONObject(request.body))
        val key = body["key"]?.toString() ?: throw ArgError("body needs \"key\"")
        val value = body["value"]?.toString() ?: throw ArgError("body needs \"value\"")
        if (!store.setParam(id, key, value)) {
            return error(404, "automation '$id' has no param '$key'")
        }
        return ok(mapOf("id" to id, "key" to key, "value" to value))
    }

    private fun fire(id: String, request: HttpRequest): HttpResponse {
        val automation = store.find(id) ?: return notFound(id)
        val dryRun =
            request.body.isNotEmpty() &&
                runCatching { JSONObject(request.body).optBoolean("dry_run", false) }.getOrDefault(false)
        val record = engine.fireNow(automation, source = if (dryRun) "dry_run" else "manual", dryRun = dryRun)
        return ok(mapOf("run" to record.toMap()))
    }

    private fun simulate(request: HttpRequest): HttpResponse {
        val body = Json.toMap(JSONObject(request.body))
        val type = body["type"]?.toString() ?: throw ArgError("body needs \"type\"")

        @Suppress("UNCHECKED_CAST")
        val facts =
            (body["facts"] as? Map<String, Any?>)
                .orEmpty()
                .entries
                .associate { (k, v) -> k to v?.toString().orEmpty() }
        val dryRun = body["dry_run"] as? Boolean ?: false

        val event = TriggerEvent(type, facts)
        val matched = store.enabled().filter { TriggerMatcher.matches(it.resolved().trigger, event) }
        val records =
            matched.map { engine.fireNow(it, "simulate:${event.describe()}", dryRun, event.facts) }
        return ok(
            mapOf(
                "event" to event.describe(),
                "matched" to matched.map { it.id },
                "skipped" to store.all().filterNot { it in matched }.map { it.id },
                "runs" to records.map { it.toMap() }
            )
        )
    }

    // -- helpers ------------------------------------------------------------------------

    private fun unmetFor(automation: Automation): List<String> = capabilities.unmetFor(automation).map { requirement ->
        "${requirement.name.lowercase()}: ${capabilities.statusRemedy(requirement)}"
    }

    private fun ok(payload: Map<String, Any?>) = HttpResponse(200, Json.toJson(payload).toString())

    private fun error(status: Int, message: String) =
        HttpResponse(status, Json.toJson(mapOf("error" to message)).toString())

    private fun notFound(id: String) = error(404, "no automation with id '$id'")

    private companion object {
        const val TAG = "KataControlApi"
    }
}
