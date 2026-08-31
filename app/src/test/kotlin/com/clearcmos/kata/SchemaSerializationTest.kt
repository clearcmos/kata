package com.clearcmos.kata

import com.clearcmos.kata.model.FieldType
import com.clearcmos.kata.model.Json
import com.clearcmos.kata.model.SpecKind
import com.clearcmos.kata.model.Vocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The serialized schema is the contract an authoring agent reads, and nothing else checks it.
 */
class SchemaSerializationTest {
    @Test
    fun `an enum field serializes its own allowed values`() {
        // Regression: inside buildMap a bare `values` bound to the map's own values property, so
        // every enum published a self-referential list and an agent reading /schema learned
        // nothing about what the field would actually accept.
        val dnd = Vocabulary.find(SpecKind.ACTION, "dnd")!!
        val mode = dnd.fields.single { it.name == "mode" }
        assertEquals(FieldType.ENUM, mode.type)
        assertEquals(listOf("off", "priority", "none", "alarms"), mode.toMap()["values"])
    }

    @Test
    fun `every spec serializes to real JSON`() {
        val broken = Vocabulary.all.filter { spec ->
            Json.toJson(spec.toMap()).toString().isNullOrEmpty()
        }
        assertTrue("specs that fail to serialize: ${broken.map { it.id }}", broken.isEmpty())
    }

    @Test
    fun `the whole schema payload serializes`() {
        val payload = mapOf(
            "triggers" to Vocabulary.triggers.map { it.toMap() },
            "conditions" to Vocabulary.conditions.map { it.toMap() },
            "actions" to Vocabulary.actions.map { it.toMap() }
        )
        assertTrue(Json.toJson(payload).toString().isNotEmpty())
    }

    @Test
    fun `every enum field in the whole vocabulary round trips its values`() {
        for (spec in Vocabulary.all) {
            for (field in spec.fields.filter { it.type == FieldType.ENUM }) {
                assertEquals("${spec.id}.${field.name}", field.values, field.toMap()["values"])
            }
        }
    }
}
