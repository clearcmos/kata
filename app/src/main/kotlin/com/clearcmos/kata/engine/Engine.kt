package com.clearcmos.kata.engine

import android.content.Context
import android.util.Log
import com.clearcmos.kata.actions.ActionError
import com.clearcmos.kata.actions.ActionExecutor
import com.clearcmos.kata.actions.ActionRunner
import com.clearcmos.kata.model.ArgError
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.Params
import com.clearcmos.kata.model.Sensitivity
import com.clearcmos.kata.model.SpecKind
import com.clearcmos.kata.model.Validator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs automations. One single-threaded executor owns every run, so two triggers that land at
 * the same instant queue instead of interleaving. That makes `wait` safe, makes the run log
 * ordered, and removes a whole class of "it worked when I tested it alone" bugs.
 */
class Engine(
    val store: Store,
    val runLog: RunLog,
    private val varStore: VarStore,
    private val conditions: ConditionEvaluator,
    private val actions: ActionExecutor
) {
    /** Assembles the real collaborators. The primary constructor is what tests use. */
    constructor(context: Context, store: Store, runLog: RunLog) : this(
        store = store,
        runLog = runLog,
        varStore = VarStore(context),
        conditions = ConditionEvaluator(DeviceState(context)),
        actions = ActionRunner(context, store, VarStore(context), DeviceState(context))
    )

    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kata-engine").apply { isDaemon = true }
        }

    /** Dispatches an event to every enabled automation whose trigger accepts it. */
    fun onEvent(event: TriggerEvent) {
        val matched = store.enabled().filter { TriggerMatcher.matches(it.resolved().trigger, event) }
        if (matched.isEmpty()) return
        Log.i(TAG, "${event.describe()} matched ${matched.size} automation(s)")
        matched.forEach { automation ->
            executor.execute {
                runCatching { run(automation, event.describe(), dryRun = false, seed = event.facts) }
            }
        }
    }

    /**
     * Runs one automation and waits for the record, which is what the control API returns.
     *
     * [seed] carries the trigger's facts. /simulate has to pass them or a simulated run would
     * see a different variable scope than the real one, which would make simulation a test of
     * something other than production behaviour.
     */
    fun fireNow(
        automation: Automation,
        source: String,
        dryRun: Boolean,
        seed: Map<String, String> = emptyMap()
    ): RunRecord {
        val future = executor.submit<RunRecord> { run(automation, source, dryRun, seed) }
        return future.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun vars(): VarStore = varStore

    fun shutdown() {
        executor.shutdownNow()
        (actions as? ActionRunner)?.shutdown()
    }

    private fun run(
        automation: Automation,
        source: String,
        dryRun: Boolean,
        seed: Map<String, String> = emptyMap()
    ): RunRecord {
        val started = System.currentTimeMillis()
        val resolved = automation.resolved()
        // Trigger facts seed the scope, so a rule can use what fired it with no plumbing:
        // app_foreground gives ${vars.package}, setting_changed gives ${vars.key} and value.
        val vars = RunVars(varStore, seed)
        val steps = ArrayList<StepResult>()
        var outcome = if (dryRun) RunRecord.OUTCOME_DRY_RUN else RunRecord.OUTCOME_RAN
        var error: String? = null

        for ((index, condition) in resolved.conditions.withIndex()) {
            val live = condition.copy(args = condition.args.mapStrings { Params.substituteVars(it, vars.snapshot()) })
            val result =
                runCatching { conditions.evaluate(live) }
                    .getOrElse { ConditionResult(false, it.message ?: it.javaClass.simpleName) }
            steps.add(StepResult(index, "condition:${condition.type}", result.matched, result.detail))
            if (!result.matched) {
                outcome = RunRecord.OUTCOME_SKIPPED
                break
            }
        }

        if (outcome != RunRecord.OUTCOME_SKIPPED) {
            for ((index, action) in resolved.actions.withIndex()) {
                // Substituted per action rather than once up front, because an earlier action's
                // outputs are only in scope by the time a later one runs.
                val live = action.copy(args = action.args.mapStrings { Params.substituteVars(it, vars.snapshot()) })
                if (dryRun) {
                    val shown = Sensitivity.describe(SpecKind.ACTION, action.type, live.args.raw())
                    steps.add(StepResult(index, action.type, true, "would run: $shown"))
                    continue
                }

                val attempts = 1 + live.args.int(Validator.RETRY_FIELD, 0).coerceIn(0, Validator.MAX_RETRIES)
                var lastMessage = ""
                var succeeded = false
                for (attempt in 1..attempts) {
                    val result = runCatching { actions.execute(live) }
                    if (result.isSuccess) {
                        val produced = result.getOrThrow()
                        vars.putAll(produced.outputs)
                        val note = if (attempt > 1) " (attempt $attempt)" else ""
                        steps.add(StepResult(index, action.type, true, produced.detail + note))
                        succeeded = true
                        break
                    }
                    val cause = result.exceptionOrNull()
                    lastMessage = when (cause) {
                        is ActionError, is ArgError -> cause.message.orEmpty()
                        else -> "${cause?.javaClass?.simpleName}: ${cause?.message}"
                    }
                    if (attempt < attempts) Thread.sleep(RETRY_BACKOFF_MS)
                }

                if (!succeeded) {
                    val tried = if (attempts > 1) " after $attempts attempts" else ""
                    steps.add(StepResult(index, action.type, false, lastMessage + tried))
                    // Later actions usually assume the earlier ones landed, so a failure stops
                    // the sequence rather than continuing into an unintended state.
                    outcome = RunRecord.OUTCOME_FAILED
                    error = "actions[$index] (${action.type}): $lastMessage"
                    Log.w(TAG, "${automation.id} failed at actions[$index]: $lastMessage")
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
        const val RETRY_BACKOFF_MS = 400L
    }
}
