package com.clearcmos.kata.engine

import android.content.Context
import android.util.Log
import com.clearcmos.kata.actions.ActionError
import com.clearcmos.kata.actions.ActionRunner
import com.clearcmos.kata.model.ArgError
import com.clearcmos.kata.model.Automation
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs automations. One single-threaded executor owns every run, so two triggers that land at
 * the same instant queue instead of interleaving. That makes `wait` safe, makes the run log
 * ordered, and removes a whole class of "it worked when I tested it alone" bugs.
 */
class Engine(context: Context, val store: Store, val runLog: RunLog) {
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kata-engine").apply { isDaemon = true }
        }
    private val device = DeviceState(context)
    private val conditions = ConditionEvaluator(device)
    private val actions = ActionRunner(context, store, device)

    /** Dispatches an event to every enabled automation whose trigger accepts it. */
    fun onEvent(event: TriggerEvent) {
        val matched = store.enabled().filter { TriggerMatcher.matches(it.resolved().trigger, event) }
        if (matched.isEmpty()) return
        Log.i(TAG, "${event.describe()} matched ${matched.size} automation(s)")
        matched.forEach { automation ->
            executor.execute { runCatching { run(automation, event.describe(), dryRun = false) } }
        }
    }

    /** Runs one automation and waits for the record, which is what the control API returns. */
    fun fireNow(automation: Automation, source: String, dryRun: Boolean): RunRecord {
        val future = executor.submit<RunRecord> { run(automation, source, dryRun) }
        return future.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun shutdown() {
        executor.shutdownNow()
        actions.shutdown()
    }

    private fun run(automation: Automation, source: String, dryRun: Boolean): RunRecord {
        val started = System.currentTimeMillis()
        val resolved = automation.resolved()
        val steps = ArrayList<StepResult>()
        var outcome = if (dryRun) RunRecord.OUTCOME_DRY_RUN else RunRecord.OUTCOME_RAN
        var error: String? = null

        for ((index, condition) in resolved.conditions.withIndex()) {
            val result =
                runCatching { conditions.evaluate(condition) }
                    .getOrElse { ConditionResult(false, it.message ?: it.javaClass.simpleName) }
            steps.add(StepResult(index, "condition:${condition.type}", result.matched, result.detail))
            if (!result.matched) {
                outcome = RunRecord.OUTCOME_SKIPPED
                break
            }
        }

        if (outcome != RunRecord.OUTCOME_SKIPPED) {
            for ((index, action) in resolved.actions.withIndex()) {
                if (dryRun) {
                    steps.add(StepResult(index, action.type, true, "would run: ${action.args.raw()}"))
                    continue
                }
                val result = runCatching { actions.execute(action) }
                if (result.isSuccess) {
                    steps.add(StepResult(index, action.type, true, result.getOrNull().orEmpty()))
                } else {
                    val cause = result.exceptionOrNull()
                    val message =
                        when (cause) {
                            is ActionError, is ArgError -> cause.message.orEmpty()
                            else -> "${cause?.javaClass?.simpleName}: ${cause?.message}"
                        }
                    steps.add(StepResult(index, action.type, false, message))
                    // Later actions usually assume the earlier ones landed, so a failure stops
                    // the sequence rather than continuing into an unintended state.
                    outcome = RunRecord.OUTCOME_FAILED
                    error = "actions[$index] (${action.type}): $message"
                    Log.w(TAG, "${automation.id} failed at actions[$index]: $message")
                    break
                }
            }
        }

        val record =
            RunRecord(
                automationId = automation.id,
                name = automation.name,
                startedAt = started,
                durationMs = System.currentTimeMillis() - started,
                source = source,
                outcome = outcome,
                steps = steps,
                error = error
            )
        runLog.record(record)
        return record
    }

    private companion object {
        const val TAG = "KataEngine"
        const val RUN_TIMEOUT_SECONDS = 60L
    }
}
