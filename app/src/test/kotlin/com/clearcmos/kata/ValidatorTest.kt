package com.clearcmos.kata

import com.clearcmos.kata.model.Args
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.FieldType
import com.clearcmos.kata.model.Param
import com.clearcmos.kata.model.Step
import com.clearcmos.kata.model.Validator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorTest {
    private fun automation(
        id: String = "night-mode",
        trigger: Step = Step("wifi_connected", Args(mapOf("ssid" to "home"))),
        conditions: List<Step> = emptyList(),
        actions: List<Step> = listOf(Step("log", Args(mapOf("message" to "hi")))),
        params: List<Param> = emptyList()
    ) = Automation(
        id = id,
        name = "Night mode",
        trigger = trigger,
        conditions = conditions,
        actions = actions,
        params = params
    )

    @Test
    fun `a well formed automation has no problems`() {
        assertEquals(emptyList<String>(), Validator.validate(automation()))
    }

    @Test
    fun `an unknown action type suggests the closest real one`() {
        val errors =
            Validator.validate(
                automation(actions = listOf(Step("notifyy", Args(mapOf("title" to "x")))))
            )
        assertTrue(errors.toString(), errors.any { it.contains("did you mean 'notify'") })
    }

    @Test
    fun `a misspelled field is reported rather than ignored`() {
        val errors =
            Validator.validate(
                automation(actions = listOf(Step("notify", Args(mapOf("titel" to "x")))))
            )
        assertTrue(errors.toString(), errors.any { it.contains("actions[0].titel is not a field") })
        assertTrue(errors.toString(), errors.any { it.contains("missing required field 'title'") })
    }

    @Test
    fun `an enum outside its allowed set names the allowed values`() {
        val errors =
            Validator.validate(
                automation(actions = listOf(Step("dnd", Args(mapOf("mode" to "loud")))))
            )
        assertTrue(errors.toString(), errors.any { it.contains("must be one of off, priority, none, alarms") })
    }

    @Test
    fun `a malformed time is rejected`() {
        val errors =
            Validator.validate(
                automation(trigger = Step("time_of_day", Args(mapOf("at" to "25:00"))))
            )
        assertTrue(errors.toString(), errors.any { it.contains("24-hour time") })
    }

    @Test
    fun `battery_level needs at least one bound`() {
        val errors = Validator.validate(automation(trigger = Step("battery_level", Args.EMPTY)))
        assertTrue(errors.toString(), errors.any { it.contains("at least one of 'below' or 'above'") })
    }

    @Test
    fun `an undeclared parameter reference is caught`() {
        val errors =
            Validator.validate(
                automation(actions = listOf(Step("log", Args(mapOf("message" to "\${params.missing}")))))
            )
        assertTrue(errors.toString(), errors.any { it.contains("not declared in params") })
    }

    @Test
    fun `a declared parameter substitutes before validation`() {
        val errors =
            Validator.validate(
                automation(
                    actions = listOf(Step("volume", Args(mapOf("stream" to "music", "level" to "\${params.vol}")))),
                    params = listOf(Param("vol", "Volume", FieldType.INT, "40"))
                )
            )
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `a parameter that resolves to an out of range value still fails`() {
        val errors =
            Validator.validate(
                automation(
                    actions = listOf(Step("volume", Args(mapOf("stream" to "music", "level" to "\${params.vol}")))),
                    params = listOf(Param("vol", "Volume", FieldType.INT, "400"))
                )
            )
        assertTrue(errors.toString(), errors.any { it.contains("between 0 and 100") })
    }

    @Test
    fun `an id with spaces is rejected`() {
        val errors = Validator.validate(automation(id = "Night Mode"))
        assertTrue(errors.toString(), errors.any { it.contains("must be lowercase") })
    }

    @Test
    fun `an automation with no actions is rejected`() {
        val errors = Validator.validate(automation(actions = emptyList()))
        assertTrue(errors.toString(), errors.any { it.contains("at least one action") })
    }

    @Test
    fun `one unusable field does not hide the other problems`() {
        // Regression: an unresolved parameter in an int field used to throw out of the
        // validator, so the report came back as that single message and every other mistake
        // in the automation stayed invisible until the author fixed it and resubmitted.
        val errors = Validator.validate(
            automation(
                trigger = Step("wifi_conected", Args(mapOf("ssid" to "home"))),
                actions = listOf(
                    Step("notify", Args(mapOf("titel" to "hi"))),
                    Step("volume", Args(mapOf("stream" to "music", "level" to "\${params.nope}")))
                )
            )
        )
        assertTrue(errors.toString(), errors.any { it.contains("unknown trigger type 'wifi_conected'") })
        assertTrue(errors.toString(), errors.any { it.contains("actions[0].titel is not a field") })
        assertTrue(errors.toString(), errors.any { it.contains("not declared in params") })
        assertTrue(errors.toString(), errors.any { it.contains("actions[1].level must be a whole number") })
    }

    @Test
    fun `a broken enum does not also trip the range check`() {
        val errors = Validator.validate(
            automation(actions = listOf(Step("volume", Args(mapOf("stream" to "speaker", "level" to 50)))))
        )
        assertEquals(errors.toString(), 1, errors.size)
        assertTrue(errors.toString(), errors.first().contains("must be one of music"))
    }

    @Test
    fun `tap_ui needs at least one matcher`() {
        val errors = Validator.validate(
            automation(actions = listOf(Step("tap_ui", Args(mapOf("timeout_ms" to 1000)))))
        )
        assertTrue(errors.toString(), errors.any { it.contains("needs one of 'text'") })
    }

    @Test
    fun `tap_ui with a single matcher is accepted`() {
        val errors = Validator.validate(
            automation(actions = listOf(Step("tap_ui", Args(mapOf("text" to "Wi-Fi")))))
        )
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `an unknown global action names the allowed set`() {
        val errors = Validator.validate(
            automation(actions = listOf(Step("global_action", Args(mapOf("action" to "reboot")))))
        )
        assertTrue(errors.toString(), errors.any { it.contains("must be one of back, home, recents") })
    }
}
