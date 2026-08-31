package com.clearcmos.kata.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.clearcmos.kata.R
import com.clearcmos.kata.api.Capabilities
import com.clearcmos.kata.databinding.ActivityAutomationBinding
import com.clearcmos.kata.databinding.ItemParamBinding
import com.clearcmos.kata.engine.Kata
import com.clearcmos.kata.engine.RunRecord
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.Json
import com.clearcmos.kata.model.SpecKind
import com.clearcmos.kata.model.Vocabulary
import com.google.android.material.snackbar.Snackbar
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * One automation: its parameters, a way to run it on the spot, and what its last runs did.
 *
 * "Run now" and "Dry run" exist here for the same reason they exist on the API. The difference
 * between a rule that never fired and a rule that fired and failed is the first thing anyone
 * needs, and guessing at it from the outside is hopeless.
 */
class AutomationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAutomationBinding
    private lateinit var automationId: String
    private val paramFields = mutableMapOf<String, ItemParamBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutomationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        Insets.applySystemBars(binding.root, binding.scroll)

        automationId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val automation = Kata.store(this).find(automationId)
        if (automation == null) {
            finish()
            return
        }

        title = automation.name
        binding.runNow.setOnClickListener { run(dryRun = false) }
        binding.dryRun.setOnClickListener { run(dryRun = true) }
        binding.saveParams.setOnClickListener { saveParams() }
        bind(automation)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        Kata.store(this).find(automationId)?.let { renderRuns() }
    }

    private fun bind(automation: Automation) {
        binding.description.text = automation.description.ifBlank { automation.id }
        binding.trigger.text = Json.toJson(automation.resolved().trigger.toMap()).toString()
        binding.definition.text = Json.toJson(automation.toMap()).toString(2)

        renderUnmet(automation)
        renderParams(automation)
        renderRuns()
    }

    private fun renderUnmet(automation: Automation) {
        val capabilities = Capabilities(this)
        val specs =
            listOfNotNull(Vocabulary.find(SpecKind.TRIGGER, automation.trigger.type)) +
                automation.conditions.mapNotNull { Vocabulary.find(SpecKind.CONDITION, it.type) } +
                automation.actions.mapNotNull { Vocabulary.find(SpecKind.ACTION, it.type) }
        val unmet = capabilities.unmet(specs.flatMap { it.requires }.distinct())
        if (unmet.isEmpty()) {
            binding.unmet.visibility = android.view.View.GONE
            return
        }
        binding.unmet.visibility = android.view.View.VISIBLE
        binding.unmet.setTextColor(getColor(R.color.status_warn))
        binding.unmet.text = unmet.joinToString("\n") { "${it.name.lowercase()}: ${capabilities.status(it).remedy}" }
    }

    private fun renderParams(automation: Automation) {
        binding.paramsContainer.removeAllViews()
        paramFields.clear()
        if (automation.params.isEmpty()) return

        binding.paramsHeader.visibility = android.view.View.VISIBLE
        binding.saveParams.visibility = android.view.View.VISIBLE
        for (param in automation.params) {
            val field = ItemParamBinding.inflate(layoutInflater, binding.paramsContainer, true)
            field.wrapper.hint = param.label
            field.value.setText(param.value)
            paramFields[param.key] = field
        }
    }

    private fun saveParams() {
        val store = Kata.store(this)
        paramFields.forEach { (key, field) ->
            store.setParam(
                automationId,
                key,
                field.value.text
                    ?.toString()
                    .orEmpty()
            )
        }
        Kata.store(this).find(automationId)?.let { bind(it) }
        Snackbar.make(binding.root, R.string.params_saved, Snackbar.LENGTH_SHORT).show()
    }

    private fun run(dryRun: Boolean) {
        val automation = Kata.store(this).find(automationId) ?: return
        binding.runNow.isEnabled = false
        binding.dryRun.isEnabled = false
        // fireNow blocks until the run finishes, so it never touches the main thread.
        thread {
            val record =
                runCatching {
                    Kata.engine(this).fireNow(automation, if (dryRun) "ui_dry_run" else "ui_manual", dryRun)
                }
            runOnUiThread {
                binding.runNow.isEnabled = true
                binding.dryRun.isEnabled = true
                renderRuns()
                val message =
                    record.getOrNull()
                        ?.let { getString(R.string.run_result, it.outcome, it.durationMs) }
                        ?: getString(R.string.run_failed, record.exceptionOrNull()?.message.orEmpty())
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun renderRuns() {
        val runs = Kata.runLog(this).recent(RUN_HISTORY, automationId)
        binding.runs.text =
            if (runs.isEmpty()) {
                getString(R.string.never_run)
            } else {
                runs.joinToString("\n\n") { format(it) }
            }
    }

    private fun format(record: RunRecord): String {
        val stamp =
            DateFormat
                .getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(Date(record.startedAt))
        val header = "$stamp  ${record.outcome}  (${record.durationMs}ms, via ${record.source})"
        val steps =
            record.steps.joinToString("\n") { step ->
                "  ${if (step.ok) "ok " else "ERR"} ${step.type}: ${step.detail}"
            }
        return listOf(header, steps).filter { it.isNotBlank() }.joinToString("\n")
    }

    companion object {
        const val EXTRA_ID = "automation_id"
        private const val RUN_HISTORY = 10
    }
}
