package com.clearcmos.kata

import com.clearcmos.kata.model.Args
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.Params
import com.clearcmos.kata.model.Step
import com.clearcmos.kata.model.Validator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VarsAndRetryTest {
    private fun automation(actions: List<Step>) = Automation(
        id = "rule",
        name = "Rule",
        trigger = Step("manual", Args.EMPTY),
        actions = actions
    )

    @Test
    fun `variable references are substituted from the run scope`() {
        val text = Params.substituteVars("hello \${vars.name}", mapOf("name" to "world"))
        assertEquals("hello world", text)
    }

    @Test
    fun `an unknown variable is left visible rather than blanked`() {
        // A silent empty string would be far harder to diagnose than a visible placeholder.
        val text = Params.substituteVars("value=\${vars.missing}", emptyMap())
        assertEquals("value=\${vars.missing}", text)
    }

    @Test
    fun `params and vars are separate namespaces`() {
        assertEquals("\${vars.a}", Params.substitute("\${vars.a}", mapOf("a" to "param")))
        assertEquals("\${params.a}", Params.substituteVars("\${params.a}", mapOf("a" to "var")))
    }

    @Test
    fun `a runtime reference is detected`() {
        assertTrue(Params.hasRuntimeReference("level is \${vars.n}"))
        assertFalse(Params.hasRuntimeReference("level is \${params.n}"))
        assertFalse(Params.hasRuntimeReference("plain text"))
    }

    @Test
    fun `validation defers on a variable in a typed field`() {
        // volume.level is an int, but the value only exists mid-run, so it must not be rejected.
        val errors = Validator.validate(
            automation(listOf(Step("volume", Args(mapOf("stream" to "music", "level" to "\${vars.n}")))))
        )
        assertEquals(errors.toString(), emptyList<String>(), errors)
    }

    @Test
    fun `a literal bad value in the same field is still rejected`() {
        val errors = Validator.validate(
            automation(listOf(Step("volume", Args(mapOf("stream" to "music", "level" to "loud")))))
        )
        assertTrue(errors.toString(), errors.any { it.contains("whole number") })
    }

    @Test
    fun `retry is allowed on an idempotent action`() {
        val errors = Validator.validate(
            automation(listOf(Step("ping", Args(mapOf("host" to "example.test", "retry" to 2)))))
        )
        assertEquals(errors.toString(), emptyList<String>(), errors)
    }

    @Test
    fun `retry is refused on an action that is not idempotent`() {
        val errors = Validator.validate(
            automation(listOf(Step("vibrate", Args(mapOf("ms" to 100, "retry" to 2)))))
        )
        assertTrue(errors.toString(), errors.any { it.contains("not allowed on 'vibrate'") })
    }

    @Test
    fun `retry zero is fine everywhere`() {
        val errors = Validator.validate(
            automation(listOf(Step("vibrate", Args(mapOf("ms" to 100, "retry" to 0)))))
        )
        assertEquals(errors.toString(), emptyList<String>(), errors)
    }

    @Test
    fun `an absurd retry count is refused`() {
        val errors = Validator.validate(
            automation(listOf(Step("ping", Args(mapOf("host" to "example.test", "retry" to 99)))))
        )
        assertTrue(errors.toString(), errors.any { it.contains("between 0 and") })
    }

    @Test
    fun `tap_ui still requires a matcher`() {
        val errors = Validator.validate(automation(listOf(Step("tap_ui", Args.EMPTY))))
        assertTrue(errors.toString(), errors.any { it.contains("needs one of 'text'") })
    }
}
