package com.clearcmos.kata

import com.clearcmos.kata.actions.ActionExecutor
import com.clearcmos.kata.actions.ActionOutcome
import com.clearcmos.kata.api.CapabilityReporter
import com.clearcmos.kata.api.ControlApi
import com.clearcmos.kata.api.HttpRequest
import com.clearcmos.kata.engine.ConditionEvaluator
import com.clearcmos.kata.engine.DeviceReadings
import com.clearcmos.kata.engine.Engine
import com.clearcmos.kata.engine.RunLog
import com.clearcmos.kata.engine.Store
import com.clearcmos.kata.engine.VarStore
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.Json
import com.clearcmos.kata.model.Requirement
import com.clearcmos.kata.model.Step
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class AllOffDevice : DeviceReadings {
    override fun batteryLevel() = 50

    override fun isCharging() = false

    override fun isScreenOn() = true

    override fun isWifiConnected() = true

    override fun wifiSsid() = "home"

    override fun isDndActive() = false

    override fun isAppInstalled(packageName: String) = true
}

private class StubCapabilities(private val unmet: List<Requirement> = emptyList()) : CapabilityReporter {
    override fun unmetFor(automation: Automation) = unmet

    override fun statusRemedy(requirement: Requirement) = "do the thing"

    override fun snapshot() = mapOf("device" to mapOf("model" to "test"))
}

class ControlApiTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var engine: Engine
    private lateinit var api: ControlApi

    private val token = "correct-horse"

    @Before
    fun setUp() {
        engine = Engine(
            store = Store(temp.root),
            runLog = RunLog(temp.root),
            varStore = VarStore(temp.root),
            conditions = ConditionEvaluator(AllOffDevice()),
            actions = object : ActionExecutor {
                override fun execute(step: Step) = ActionOutcome("stubbed")
            }
        )
        api = ControlApi(engine, { token }, StubCapabilities())
    }

    @After
    fun tearDown() = engine.shutdown()

    private fun call(
        method: String,
        path: String,
        body: String = "",
        headers: Map<String, String> = mapOf("x-kata-token" to token),
        query: Map<String, String> = emptyMap()
    ) = api.handle(HttpRequest(method, path, query, headers, body))

    private fun json(body: String) = Json.toMap(JSONObject(body))

    private val simpleRule = """{"id":"a","name":"A","trigger":{"type":"manual"},
        "actions":[{"type":"log","message":"hi"}]}"""

    @Test
    fun `health needs no token`() {
        val response = call("GET", "/health", headers = emptyMap())
        assertEquals(200, response.status)
        assertEquals(true, json(response.body)["ok"])
    }

    @Test
    fun `every other route refuses a missing or wrong token`() {
        assertEquals(401, call("GET", "/automations", headers = emptyMap()).status)
        assertEquals(401, call("GET", "/automations", headers = mapOf("x-kata-token" to "wrong")).status)
    }

    @Test
    fun `a bearer authorization header is accepted`() {
        val response = call("GET", "/automations", headers = mapOf("authorization" to "Bearer $token"))
        assertEquals(200, response.status)
    }

    @Test
    fun `an unknown route is a 404`() {
        assertEquals(404, call("GET", "/nope").status)
    }

    @Test
    fun `install then read back`() {
        assertEquals(200, call("POST", "/automations", simpleRule).status)
        val listed = json(call("GET", "/automations").body)

        @Suppress("UNCHECKED_CAST")
        val automations = listed["automations"] as List<Map<String, Any?>>
        assertEquals(listOf("a"), automations.map { it["id"] })
        assertEquals("a", json(call("GET", "/automations/a").body)["id"])
    }

    @Test
    fun `an invalid automation is refused with every problem listed`() {
        val response = call(
            "POST",
            "/automations",
            """{"id":"Bad Id","name":"x","trigger":{"type":"nope"},"actions":[{"type":"notify"}]}"""
        )
        assertEquals(422, response.status)
        @Suppress("UNCHECKED_CAST")
        val problems = json(response.body)["problems"] as List<String>
        assertTrue(problems.toString(), problems.any { it.contains("must be lowercase") })
        assertTrue(problems.toString(), problems.any { it.contains("unknown trigger type") })
        assertTrue(problems.toString(), problems.any { it.contains("missing required field 'title'") })
    }

    @Test
    fun `a bulk install is atomic - one bad rule installs nothing`() {
        // A partially applied sync would leave the device matching neither the repo nor any
        // intended state, and the difference is invisible from the device.
        call("PUT", "/automations", """{"automations":[$simpleRule]}""")
        val response = call(
            "PUT",
            "/automations",
            """{"automations":[{"id":"good","name":"G","trigger":{"type":"manual"},
                "actions":[{"type":"log","message":"x"}]},
               {"id":"bad","name":"B","trigger":{"type":"manual"},"actions":[]}]}"""
        )
        assertEquals(422, response.status)
        // The previously installed set is untouched.
        assertEquals(listOf("a"), engine.store.all().map { it.id })
    }

    @Test
    fun `a duplicate id in one payload is refused`() {
        val response = call(
            "PUT",
            "/automations",
            """{"automations":[$simpleRule,$simpleRule]}"""
        )
        assertEquals(422, response.status)
    }

    @Test
    fun `enable and disable flip the stored rule`() {
        call("POST", "/automations", simpleRule)
        assertEquals(200, call("POST", "/automations/a/disable").status)
        assertFalse(engine.store.find("a")!!.enabled)
        assertEquals(200, call("POST", "/automations/a/enable").status)
        assertTrue(engine.store.find("a")!!.enabled)
    }

    @Test
    fun `acting on an unknown id is a 404`() {
        assertEquals(404, call("GET", "/automations/ghost").status)
        assertEquals(404, call("POST", "/automations/ghost/enable").status)
        assertEquals(404, call("DELETE", "/automations/ghost").status)
    }

    @Test
    fun `delete removes the rule`() {
        call("POST", "/automations", simpleRule)
        assertEquals(200, call("DELETE", "/automations/a").status)
        assertTrue(engine.store.all().isEmpty())
    }

    @Test
    fun `a sync carries forward a parameter edited on the phone`() {
        val withParam = """{"id":"p","name":"P","trigger":{"type":"manual"},
            "actions":[{"type":"log","message":"x"}],
            "params":[{"key":"host","label":"Host","type":"string","value":"repo-value"}]}"""
        call("PUT", "/automations", """{"automations":[$withParam]}""")
        call("POST", "/automations/p/params", """{"key":"host","value":"phone-value"}""")
        call("PUT", "/automations", """{"automations":[$withParam]}""")
        assertEquals("phone-value", engine.store.find("p")!!.params.single().value)
    }

    @Test
    fun `reset_params restores the repo value`() {
        val withParam = """{"id":"p","name":"P","trigger":{"type":"manual"},
            "actions":[{"type":"log","message":"x"}],
            "params":[{"key":"host","label":"Host","type":"string","value":"repo-value"}]}"""
        call("PUT", "/automations", """{"automations":[$withParam]}""")
        call("POST", "/automations/p/params", """{"key":"host","value":"phone-value"}""")
        call("PUT", "/automations", """{"automations":[$withParam]}""", query = mapOf("reset_params" to "true"))
        assertEquals("repo-value", engine.store.find("p")!!.params.single().value)
    }

    @Test
    fun `setting an undeclared param is a 404`() {
        call("POST", "/automations", simpleRule)
        assertEquals(404, call("POST", "/automations/a/params", """{"key":"nope","value":"x"}""").status)
    }

    @Test
    fun `validate reports without installing`() {
        val response = call("POST", "/validate", simpleRule)
        assertEquals(200, response.status)
        assertEquals(true, json(response.body)["valid"])
        assertTrue(engine.store.all().isEmpty())
    }

    @Test
    fun `validate surfaces unmet requirements alongside a valid rule`() {
        val gated = ControlApi(engine, { token }, StubCapabilities(listOf(Requirement.DND_POLICY)))
        val response = gated.handle(
            HttpRequest("POST", "/validate", emptyMap(), mapOf("x-kata-token" to token), simpleRule)
        )

        @Suppress("UNCHECKED_CAST")
        val unmet = json(response.body)["unmet"] as List<String>
        assertTrue(unmet.toString(), unmet.single().contains("dnd_policy"))
    }

    @Test
    fun `fire runs the rule and returns the record`() {
        call("POST", "/automations", simpleRule)
        val response = call("POST", "/automations/a/fire", """{"dry_run":false}""")
        assertEquals(200, response.status)
        @Suppress("UNCHECKED_CAST")
        val run = json(response.body)["run"] as Map<String, Any?>
        assertEquals("ran", run["outcome"])
    }

    @Test
    fun `simulate reports what matched and what did not`() {
        call(
            "PUT",
            "/automations",
            """{"automations":[
                {"id":"m","name":"M","trigger":{"type":"wifi_connected","ssid":"home"},
                 "actions":[{"type":"log","message":"x"}]},
                {"id":"n","name":"N","trigger":{"type":"wifi_connected","ssid":"cafe"},
                 "actions":[{"type":"log","message":"x"}]}]}"""
        )
        val body = json(call("POST", "/simulate", """{"type":"wifi_connected","facts":{"ssid":"home"}}""").body)
        assertEquals(listOf("m"), body["matched"])
        assertEquals(listOf("n"), body["skipped"])
    }

    @Test
    fun `runs can be read back and cleared`() {
        call("POST", "/automations", simpleRule)
        call("POST", "/automations/a/fire", """{"dry_run":false}""")
        @Suppress("UNCHECKED_CAST")
        val runs = json(call("GET", "/runs").body)["runs"] as List<Map<String, Any?>>
        assertEquals(1, runs.size)
        assertEquals(200, call("DELETE", "/runs").status)
        @Suppress("UNCHECKED_CAST")
        val after = json(call("GET", "/runs").body)["runs"] as List<Map<String, Any?>>
        assertTrue(after.isEmpty())
    }

    @Test
    fun `schema exposes the whole vocabulary`() {
        val body = json(call("GET", "/schema").body)
        for (kind in listOf("triggers", "conditions", "actions")) {
            @Suppress("UNCHECKED_CAST")
            val list = body[kind] as List<Map<String, Any?>>
            assertTrue(kind, list.isNotEmpty())
        }
    }

    @Test
    fun `malformed json is reported rather than crashing the route`() {
        val response = call("POST", "/automations", "{ not json")
        assertTrue(response.status.toString(), response.status in setOf(422, 500))
    }

    @Test
    fun `an automation missing its trigger is refused`() {
        val response = call("POST", "/automations", """{"id":"x","name":"X","actions":[]}""")
        assertEquals(422, response.status)
    }

    @Test
    fun `a fired rule that is unknown is a 404`() {
        assertEquals(404, call("POST", "/automations/ghost/fire").status)
    }

    @Test
    fun `capabilities are served`() {
        assertEquals(200, call("GET", "/capabilities").status)
        assertTrue(json(call("GET", "/capabilities").body).containsKey("device"))
    }

    @Test
    fun `a step in a stored rule keeps its shape through a round trip`() {
        call("POST", "/automations", simpleRule)
        val stored: Automation = engine.store.find("a")!!
        assertEquals(Step("log", stored.actions.single().args).type, "log")
        assertEquals("hi", stored.actions.single().args.optString("message"))
    }
}
