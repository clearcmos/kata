package com.clearcmos.kata

import com.clearcmos.kata.model.Sensitivity
import com.clearcmos.kata.model.SpecKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityTest {
    @Test
    fun `declared sensitive fields are masked`() {
        assertTrue(Sensitivity.isSensitive(SpecKind.ACTION, "http_request", "headers"))
        assertTrue(Sensitivity.isSensitive(SpecKind.ACTION, "http_request", "body"))
    }

    @Test
    fun `declared ordinary fields are printed`() {
        assertFalse(Sensitivity.isSensitive(SpecKind.ACTION, "http_request", "url"))
        assertFalse(Sensitivity.isSensitive(SpecKind.ACTION, "http_request", "method"))
        assertFalse(Sensitivity.isSensitive(SpecKind.ACTION, "notify", "title"))
    }

    @Test
    fun `an unknown key falls back to the heuristic and fails closed`() {
        // The point of the fallback: a field nobody declared must not print a credential.
        assertTrue(Sensitivity.isSensitive(SpecKind.ACTION, "http_request", "x_api_token"))
        assertTrue(Sensitivity.isSensitive(SpecKind.ACTION, "made_up_action", "password"))
        assertTrue(Sensitivity.isSensitive(SpecKind.ACTION, "made_up_action", "Authorization"))
    }

    @Test
    fun `structural keys that merely contain a token are not masked`() {
        assertFalse(Sensitivity.matchesHeuristic("keycode"))
        assertFalse(Sensitivity.matchesHeuristic("keyguard"))
    }

    @Test
    fun `redacting replaces only the sensitive values`() {
        val redacted = Sensitivity.redact(
            SpecKind.ACTION,
            "http_request",
            mapOf("url" to "https://example.test/hook", "headers" to mapOf("Authorization" to "Bearer hunter2"))
        )
        assertEquals("https://example.test/hook", redacted["url"])
        assertEquals(Sensitivity.REDACTED, redacted["headers"])
    }

    @Test
    fun `a described argument line never contains the secret`() {
        val line = Sensitivity.describe(
            SpecKind.ACTION,
            "http_request",
            mapOf("url" to "https://example.test", "body" to "password=hunter2")
        )
        assertFalse(line, line.contains("hunter2"))
        assertTrue(line, line.contains("https://example.test"))
    }
}
