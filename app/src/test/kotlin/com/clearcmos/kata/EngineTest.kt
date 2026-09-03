package com.clearcmos.kata

import com.clearcmos.kata.actions.ActionError
import com.clearcmos.kata.actions.ActionExecutor
import com.clearcmos.kata.actions.ActionOutcome
import com.clearcmos.kata.engine.ConditionEvaluator
import com.clearcmos.kata.engine.DeviceReadings
import com.clearcmos.kata.engine.Engine
import com.clearcmos.kata.engine.RunLog
import com.clearcmos.kata.engine.RunRecord
import com.clearcmos.kata.engine.Store
import com.clearcmos.kata.engine.TriggerEvent
import com.clearcmos.kata.engine.VarStore
import com.clearcmos.kata.model.Args
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.Step
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class StubDevice(private val charging: Boolean = false) : DeviceReadings {
    override fun batteryLevel() = 50

    override fun isCharging() = charging

    override fun isScreenOn() = true

    override fun isWifiConnected() = true

    override fun wifiSsid() = "home"

    override fun ipAddress() = "192.0.2.13"

    override fun isDndActive() = false

    override fun isAppInstalled(packageName: String) = true
}

/** Records what it was asked to do and answers however the test dictates. */
private class StubActions(private val behaviour: (Step, Int) -> ActionOutcome) : ActionExecutor {
    val calls = CopyOnWriteArrayList<Step>()

    override fun execute(step: Step): ActionOutcome {
        calls.add(step)
        return behaviour(step, calls.count { it.type == step.type })
    }
}

class EngineTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var engine: Engine
    private lateinit var actions: StubActions

    private fun build(
        device: DeviceReadings = StubDevice(),
        behaviour: (Step, Int) -> ActionOutcome = { _, _ -> ActionOutcome("done") }
    ): Engine {
        actions = StubActions(behaviour)
        engine = Engine(
            store = Store(temp.root),
            runLog = RunLog(temp.root),
            varStore = VarStore(temp.root),
            conditions = ConditionEvaluator(device),
            actions = actions
        )
        return engine
    }

    @After
    fun stop() {
        if (::engine.isInitialized) engine.shutdown()
    }

    private fun rule(
        id: String = "r",
        conditions: List<Step> = emptyList(),
        steps: List<Step> = listOf(Step("log", Args(mapOf("message" to "hi"))))
    ) = Automation(id = id, name = id, trigger = Step("manual", Args.EMPTY), conditions = conditions, actions = steps)

    @Test
    fun `a plain run executes every action in order`() {
        val engine = build()
        val record = engine.fireNow(
            rule(steps = listOf(Step("log", Args(mapOf("message" to "a"))), Step("vibrate", Args(mapOf("ms" to 1))))),
            "test",
            dryRun = false
        )
        assertEquals(RunRecord.OUTCOME_RAN, record.outcome)
        assertEquals(listOf("log", "vibrate"), actions.calls.map { it.type })
    }

    @Test
    fun `a failing condition skips the actions entirely`() {
        val engine = build(device = StubDevice(charging = false))
        val record = engine.fireNow(
            rule(conditions = listOf(Step("charging", Args(mapOf("value" to true))))),
            "test",
            dryRun = false
        )
        assertEquals(RunRecord.OUTCOME_SKIPPED, record.outcome)
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `a dry run evaluates conditions but runs nothing`() {
        val engine = build()
        val record = engine.fireNow(rule(), "test", dryRun = true)
        assertEquals(RunRecord.OUTCOME_DRY_RUN, record.outcome)
        assertTrue(actions.calls.isEmpty())
        assertTrue(record.steps.single().detail.startsWith("would run:"))
    }

    @Test
    fun `a dry run never prints a sensitive argument`() {
        val engine = build()
        val record = engine.fireNow(
            rule(
                steps = listOf(
                    Step(
                        "http_request",
                        Args(
                            mapOf(
                                "url" to "https://example.test",
                                "headers" to mapOf("Authorization" to "Bearer s3cret")
                            )
                        )
                    )
                )
            ),
            "test",
            dryRun = true
        )
        val detail = record.steps.single().detail
        assertFalse(detail, detail.contains("s3cret"))
        assertTrue(detail, detail.contains("<redacted>"))
    }

    @Test
    fun `a failing action stops the sequence`() {
        // Later actions generally assume the earlier ones landed, so continuing would walk into
        // an unintended state rather than merely losing one step.
        val engine = build(behaviour = { step, _ ->
            if (step.type == "vibrate") throw ActionError("nope") else ActionOutcome("ok")
        })
        val record = engine.fireNow(
            rule(
                steps = listOf(
                    Step("log", Args(mapOf("message" to "a"))),
                    Step("vibrate", Args(mapOf("ms" to 1))),
                    Step("log", Args(mapOf("message" to "never")))
                )
            ),
            "test",
            dryRun = false
        )
        assertEquals(RunRecord.OUTCOME_FAILED, record.outcome)
        assertEquals(listOf("log", "vibrate"), actions.calls.map { it.type })
        assertTrue(record.error!!, record.error!!.contains("actions[1]"))
    }

    @Test
    fun `an action's outputs are visible to the next action`() {
        val engine = build(behaviour = { step, _ ->
            if (step.type == "ping") ActionOutcome("pinged", mapOf("ping_ms" to "12")) else ActionOutcome("logged")
        })
        engine.fireNow(
            rule(
                steps = listOf(
                    Step("ping", Args(mapOf("host" to "h"))),
                    Step("log", Args(mapOf("message" to "took \${vars.ping_ms}ms")))
                )
            ),
            "test",
            dryRun = false
        )
        assertEquals("took 12ms", actions.calls.last().args.optString("message"))
    }

    @Test
    fun `trigger facts seed the variable scope`() {
        val engine = build()
        engine.fireNow(
            rule(steps = listOf(Step("log", Args(mapOf("message" to "app \${vars.package}"))))),
            "test",
            dryRun = false,
            seed = mapOf("package" to "com.example")
        )
        assertEquals("app com.example", actions.calls.single().args.optString("message"))
    }

    @Test
    fun `a retry re-runs the action and reports the attempt that worked`() {
        val engine = build(behaviour = { _, attempt ->
            if (attempt < 3) throw ActionError("flaky") else ActionOutcome("ok at last")
        })
        val record = engine.fireNow(
            rule(steps = listOf(Step("ping", Args(mapOf("host" to "h", "retry" to 2))))),
            "test",
            dryRun = false
        )
        assertEquals(RunRecord.OUTCOME_RAN, record.outcome)
        assertEquals(3, actions.calls.size)
        assertTrue(record.steps.single().detail, record.steps.single().detail.contains("attempt 3"))
    }

    @Test
    fun `an exhausted retry reports how many attempts were made`() {
        val engine = build(behaviour = { _, _ -> throw ActionError("always down") })
        val record = engine.fireNow(
            rule(steps = listOf(Step("ping", Args(mapOf("host" to "h", "retry" to 2))))),
            "test",
            dryRun = false
        )
        assertEquals(RunRecord.OUTCOME_FAILED, record.outcome)
        assertEquals(3, actions.calls.size)
        assertTrue(record.steps.single().detail, record.steps.single().detail.contains("after 3 attempts"))
    }

    @Test
    fun `without a retry an action is attempted once`() {
        val engine = build(behaviour = { _, _ -> throw ActionError("down") })
        engine.fireNow(rule(steps = listOf(Step("ping", Args(mapOf("host" to "h"))))), "test", dryRun = false)
        assertEquals(1, actions.calls.size)
    }

    @Test
    fun `every run is written to the log`() {
        val engine = build()
        engine.fireNow(rule("a"), "test", dryRun = false)
        engine.fireNow(rule("b"), "test", dryRun = false)
        assertEquals(listOf("b", "a"), RunLog(temp.root).recent().map { it.automationId })
    }

    @Test
    fun `onEvent only runs automations whose trigger matches`() {
        val engine = build()
        val store = engine.store
        store.replaceAll(
            listOf(
                rule("wifi").copy(trigger = Step("wifi_connected", Args(mapOf("ssid" to "home")))),
                rule("other").copy(trigger = Step("wifi_connected", Args(mapOf("ssid" to "cafe"))))
            )
        )
        engine.onEvent(TriggerEvent("wifi_connected", mapOf("ssid" to "home")))
        Thread.sleep(300)
        assertEquals(listOf("wifi"), RunLog(temp.root).recent().map { it.automationId })
    }

    @Test
    fun `a disabled automation is not run by an event`() {
        val engine = build()
        engine.store.replaceAll(
            listOf(rule("off").copy(trigger = Step("screen_on", Args.EMPTY), enabled = false))
        )
        engine.onEvent(TriggerEvent("screen_on"))
        Thread.sleep(300)
        assertTrue(RunLog(temp.root).recent().isEmpty())
    }
}
