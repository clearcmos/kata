package com.clearcmos.kata.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clearcmos.kata.R
import com.clearcmos.kata.api.Capabilities
import com.clearcmos.kata.databinding.ActivityMainBinding
import com.clearcmos.kata.databinding.ItemAutomationBinding
import com.clearcmos.kata.engine.Kata
import com.clearcmos.kata.engine.KataService
import com.clearcmos.kata.engine.RunRecord
import com.clearcmos.kata.model.Automation
import java.text.DateFormat
import java.util.Date

/**
 * The phone-side surface: what is installed, what is armed, and what actually happened.
 *
 * Authoring lives on the workstation, so there is deliberately no rule builder here. What the
 * phone is better at is observation and a fast on/off, and those are what this screen keeps.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = AutomationAdapter()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        title = getString(R.string.automations_title)
        Insets.applySystemBars(binding.root, binding.list)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.startEngine.setOnClickListener {
            KataService.start(this)
            awaitEngine()
        }

        KataService.start(this)
        requestMissingRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // startForegroundService returns before the service has run onCreate, so the very first
        // paint after a cold start read isRunning while it was still false and then never
        // re-rendered. The screen sat on "Engine stopped" while the engine was serving requests.
        if (!KataService.isRunning) awaitEngine()
    }

    /** Re-renders as soon as the engine reports itself up, or gives up and shows the truth. */
    private fun awaitEngine(attempts: Int = ENGINE_START_ATTEMPTS) {
        if (KataService.isRunning || attempts <= 0) {
            refresh()
            return
        }
        binding.root.postDelayed({ awaitEngine(attempts - 1) }, ENGINE_POLL_MS)
    }

    private fun requestMissingRuntimePermissions() {
        val wanted =
            listOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions.launch(wanted.toTypedArray())
    }

    private fun refresh() {
        val store = Kata.store(this)
        // Alphabetical by name, not store order: the store keeps the order rules were pushed
        // in, which is a fact about the sync and not something a reader can predict.
        val automations = store.all().sortedBy { it.name.lowercase() }
        val runLog = Kata.runLog(this)

        val running = KataService.isRunning
        binding.engineStatus.text =
            getString(if (running) R.string.engine_running else R.string.engine_stopped)
        binding.engineStatus.setTextColor(getColor(if (running) R.color.status_ok else R.color.status_bad))
        binding.startEngine.visibility = if (running) android.view.View.GONE else android.view.View.VISIBLE
        binding.apiStatus.text =
            getString(
                R.string.api_status,
                KataService.PORT.toString(),
                KataService.token(this).take(TOKEN_PREVIEW)
            )

        // Scoped to the armed rules. A standing list of every ungranted capability is noise
        // when nothing needs one, and noise is what stops the line being read on the day a
        // rule really is blocked.
        val capabilities = Capabilities(this)
        val unmet = capabilities.unmetAcross(automations.filter { it.enabled })
        binding.grantStatus.text =
            if (unmet.isEmpty()) {
                getString(R.string.all_grants_ok)
            } else {
                getString(R.string.grants_needed_list, unmet.joinToString(", ") { it.name.lowercase() })
            }
        binding.grantStatus.setTextColor(
            getColor(if (unmet.isEmpty()) R.color.status_ok else R.color.status_warn)
        )

        binding.empty.visibility = if (automations.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        adapter.submit(automations, automations.associate { it.id to runLog.recent(1, it.id).firstOrNull() })
    }

    private inner class AutomationAdapter : RecyclerView.Adapter<AutomationHolder>() {
        private var items: List<Automation> = emptyList()
        private var lastRuns: Map<String, RunRecord?> = emptyMap()

        // The list is small and always replaced wholesale; a DiffUtil pass would be more
        // machinery than the screen needs.
        @SuppressLint("NotifyDataSetChanged")
        fun submit(automations: List<Automation>, runs: Map<String, RunRecord?>) {
            items = automations
            lastRuns = runs
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AutomationHolder =
            AutomationHolder(ItemAutomationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: AutomationHolder, position: Int) {
            holder.bind(items[position], lastRuns[items[position].id])
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class AutomationHolder(private val row: ItemAutomationBinding) : RecyclerView.ViewHolder(row.root) {
        fun bind(automation: Automation, lastRun: RunRecord?) {
            row.name.text = automation.name
            row.trigger.text = summarise(automation)
            row.lastRun.text = describe(lastRun)
            row.lastRun.setTextColor(
                getColor(
                    when (lastRun?.outcome) {
                        RunRecord.OUTCOME_FAILED -> R.color.status_bad
                        RunRecord.OUTCOME_RAN -> R.color.status_ok
                        else -> R.color.status_warn
                    }
                )
            )

            // Rebinding a recycled row would otherwise fire the old listener and flip the wrong rule.
            row.enabled.setOnCheckedChangeListener(null)
            row.enabled.isChecked = automation.enabled
            row.enabled.setOnCheckedChangeListener { _, checked ->
                Kata.store(this@MainActivity).setEnabled(automation.id, checked)
            }
            row.root.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, AutomationActivity::class.java)
                        .putExtra(AutomationActivity.EXTRA_ID, automation.id)
                )
            }
        }

        private fun summarise(automation: Automation): String {
            val trigger = automation.resolved().trigger
            val args =
                trigger.args
                    .raw()
                    .entries
                    .joinToString(" ") { "${it.key}=${it.value}" }
            return if (args.isEmpty()) trigger.type else "${trigger.type} $args"
        }

        private fun describe(record: RunRecord?): String {
            if (record == null) return getString(R.string.never_run)
            val stamp =
                DateFormat
                    .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(record.startedAt))
            return getString(R.string.last_run, record.outcome, stamp)
        }
    }

    private companion object {
        const val ENGINE_POLL_MS = 200L
        const val ENGINE_START_ATTEMPTS = 15
        const val TOKEN_PREVIEW = 8
    }
}
