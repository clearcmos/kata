package com.clearcmos.kata

import com.clearcmos.kata.model.Args
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.FieldType
import com.clearcmos.kata.model.Requirement
import com.clearcmos.kata.model.RetrySafety
import com.clearcmos.kata.model.SpecKind
import com.clearcmos.kata.model.Step
import com.clearcmos.kata.model.Vocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vocabulary is the contract an authoring agent reads. These assert its internal
 * consistency, because a malformed spec produces confusing validation errors rather than an
 * obvious failure, and nothing else would catch it.
 */
class VocabularyTest {
    @Test
    fun `type ids are unique within their kind`() {
        for (kind in SpecKind.entries) {
            val ids = Vocabulary.all.filter { it.kind == kind }.map { it.id }
            assertEquals("duplicate ids in $kind", ids.distinct().size, ids.size)
        }
    }

    @Test
    fun `every type documents itself`() {
        val undocumented = Vocabulary.all.filter { it.doc.isBlank() }
        assertTrue("undocumented: ${undocumented.map { it.id }}", undocumented.isEmpty())
    }

    @Test
    fun `every field documents itself`() {
        val bad = Vocabulary.all.flatMap { spec -> spec.fields.map { spec.id to it } }
            .filter { (_, field) -> field.doc.isBlank() }
        assertTrue("undocumented fields: ${bad.map { "${it.first}.${it.second.name}" }}", bad.isEmpty())
    }

    @Test
    fun `enum fields declare their allowed values`() {
        val bad = Vocabulary.all.flatMap { spec -> spec.fields.map { spec.id to it } }
            .filter { (_, field) -> field.type == FieldType.ENUM && field.values.isEmpty() }
        assertTrue("enum without values: ${bad.map { "${it.first}.${it.second.name}" }}", bad.isEmpty())
    }

    @Test
    fun `an enum default is one of its own values`() {
        val bad = Vocabulary.all.flatMap { spec -> spec.fields.map { spec.id to it } }
            .filter { (_, f) -> f.type == FieldType.ENUM && f.default != null && f.default !in f.values }
        assertTrue("default outside values: ${bad.map { "${it.first}.${it.second.name}" }}", bad.isEmpty())
    }

    @Test
    fun `a required field never carries a default`() {
        // A default on a required field is contradictory: one of the two is a lie.
        val bad = Vocabulary.all.flatMap { spec -> spec.fields.map { spec.id to it } }
            .filter { (_, field) -> field.required && field.default != null }
        assertTrue("required with default: ${bad.map { "${it.first}.${it.second.name}" }}", bad.isEmpty())
    }

    @Test
    fun `only actions declare retry safety and outputs`() {
        val nonActions = Vocabulary.triggers + Vocabulary.conditions
        assertTrue(nonActions.all { it.outputs.isEmpty() })
        assertTrue(Vocabulary.actions.any { it.retrySafety == RetrySafety.IDEMPOTENT })
        assertTrue(Vocabulary.actions.any { it.retrySafety == RetrySafety.NEVER })
    }

    @Test
    fun `every declared output name is usable as a variable`() {
        val pattern = Regex("^[A-Za-z0-9_]+$")
        val bad = Vocabulary.actions.flatMap { spec -> spec.outputs.keys.map { spec.id to it } }
            .filterNot { pattern.matches(it.second) }
        assertTrue("unusable output names: $bad", bad.isEmpty())
    }

    @Test
    fun `the serialized form carries what an authoring agent needs`() {
        val map = Vocabulary.find(SpecKind.ACTION, "http_request")!!.toMap()
        assertEquals("http_request", map["type"])
        assertEquals("never", map["retry_safety"])
        @Suppress("UNCHECKED_CAST")
        val fields = map["fields"] as List<Map<String, Any?>>
        val headers = fields.single { it["name"] == "headers" }
        assertEquals(true, headers["sensitive"])
        val url = fields.single { it["name"] == "url" }
        assertFalse(url.containsKey("sensitive"))
    }

    @Test
    fun `requirements are collected across trigger, conditions and actions`() {
        val automation = Automation(
            id = "r",
            name = "r",
            trigger = Step("app_foreground", Args.EMPTY),
            conditions = listOf(Step("wifi_ssid", Args(mapOf("equals" to "home")))),
            actions = listOf(Step("dnd", Args(mapOf("mode" to "priority"))))
        )
        val requirements = Vocabulary.requirementsOf(automation)
        assertTrue(Requirement.ACCESSIBILITY in requirements)
        assertTrue(Requirement.LOCATION in requirements)
        assertTrue(Requirement.DND_POLICY in requirements)
    }

    @Test
    fun `a rule needing nothing reports no requirements`() {
        val automation = Automation(
            id = "r",
            name = "r",
            trigger = Step("boot_completed", Args.EMPTY),
            actions = listOf(Step("log", Args(mapOf("message" to "x"))))
        )
        assertEquals(emptyList<Requirement>(), Vocabulary.requirementsOf(automation))
    }

    @Test
    fun `requirements are deduplicated across steps`() {
        val automation = Automation(
            id = "r",
            name = "r",
            trigger = Step("app_foreground", Args.EMPTY),
            actions = listOf(
                Step("global_action", Args(mapOf("action" to "home"))),
                Step(
                    "tap_ui",
                    Args(
                        mapOf(
                            "text" to "x"
                        )
                    )
                )
            )
        )
        assertEquals(listOf(Requirement.ACCESSIBILITY), Vocabulary.requirementsOf(automation))
    }
}
