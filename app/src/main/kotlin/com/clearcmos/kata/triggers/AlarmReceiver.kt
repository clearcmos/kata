package com.clearcmos.kata.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.clearcmos.kata.engine.Kata
import com.clearcmos.kata.engine.KataService
import com.clearcmos.kata.engine.TriggerEvent

/**
 * Where time_of_day and interval land. Each alarm names one automation, so a rule that was
 * disabled or deleted between scheduling and firing is simply dropped here.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_AUTOMATION_ID) ?: return
        val triggerType = intent.getStringExtra(EXTRA_TRIGGER_TYPE) ?: return
        Log.i(TAG, "alarm fired for $id ($triggerType)")

        val automation = Kata.store(context).find(id)
        if (automation == null || !automation.enabled) {
            Log.i(TAG, "$id is gone or disabled; not rescheduling")
            return
        }
        Kata.engine(context).onEvent(TriggerEvent(triggerType, mapOf("automation_id" to id)))
        // Alarms are one-shot so they survive Doze; the next one is armed only after this fires.
        KataService.rescheduleAlarms(context)
    }

    companion object {
        const val EXTRA_AUTOMATION_ID = "automation_id"
        const val EXTRA_TRIGGER_TYPE = "trigger_type"
        private const val TAG = "KataAlarm"
    }
}
