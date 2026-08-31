package com.clearcmos.kata

import com.clearcmos.kata.engine.RunLog
import com.clearcmos.kata.engine.RunRecord
import com.clearcmos.kata.engine.StepResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun record(id: String, outcome: String = RunRecord.OUTCOME_RAN) = RunRecord(
        automationId = id,
        name = id,
        startedAt = 1_700_000_000_000,
        durationMs = 5,
        source = "manual",
        outcome = outcome,
        steps = listOf(StepResult(0, "log", true, "hello")),
        error = if (outcome == RunRecord.OUTCOME_FAILED) "boom" else null
    )

    @Test
    fun `most recent run comes back first`() {
        val log = RunLog(temp.root)
        log.record(record("first"))
        log.record(record("second"))
        assertEquals(listOf("second", "first"), log.recent().map { it.automationId })
    }

    @Test
    fun `records survive a reload with their steps and error intact`() {
        RunLog(temp.root).record(record("a", RunRecord.OUTCOME_FAILED))
        val reloaded = RunLog(temp.root).recent().single()
        assertEquals("a", reloaded.automationId)
        assertEquals(RunRecord.OUTCOME_FAILED, reloaded.outcome)
        assertEquals("boom", reloaded.error)
        assertEquals(1, reloaded.steps.size)
        assertEquals("hello", reloaded.steps.single().detail)
    }

    @Test
    fun `the ring is capped so the log cannot grow without bound`() {
        val log = RunLog(temp.root)
        repeat(250) { log.record(record("run-$it")) }
        val all = log.recent(limit = 1000)
        assertEquals(200, all.size)
        // Oldest entries are the ones dropped.
        assertEquals("run-249", all.first().automationId)
        assertEquals("run-50", all.last().automationId)
    }

    @Test
    fun `filtering by automation returns only that rule`() {
        val log = RunLog(temp.root)
        log.record(record("a"))
        log.record(record("b"))
        log.record(record("a"))
        assertEquals(2, log.recent(automationId = "a").size)
        assertTrue(log.recent(automationId = "a").all { it.automationId == "a" })
    }

    @Test
    fun `limit is respected`() {
        val log = RunLog(temp.root)
        repeat(10) { log.record(record("r$it")) }
        assertEquals(3, log.recent(limit = 3).size)
    }

    @Test
    fun `clear empties the log and the file`() {
        val log = RunLog(temp.root)
        log.record(record("a"))
        log.clear()
        assertEquals(emptyList<RunRecord>(), log.recent())
        assertEquals(emptyList<RunRecord>(), RunLog(temp.root).recent())
    }

    @Test
    fun `a corrupt file reads as empty instead of throwing`() {
        File(temp.root, "runs.json").writeText("[[[ truncated")
        assertEquals(emptyList<RunRecord>(), RunLog(temp.root).recent())
    }
}
