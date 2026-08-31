package com.clearcmos.kata

import com.clearcmos.kata.model.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test
    fun `nested objects and arrays become plain collections`() {
        val map = Json.toMap(JSONObject("""{"a":1,"b":{"c":"x"},"d":[1,2,{"e":true}]}"""))
        assertEquals(1, map["a"])
        assertEquals(mapOf("c" to "x"), map["b"])
        @Suppress("UNCHECKED_CAST")
        val list = map["d"] as List<Any?>
        assertEquals(listOf(1, 2, mapOf("e" to true)), list)
    }

    @Test
    fun `JSON null becomes a Kotlin null rather than a sentinel object`() {
        // JSONObject.NULL leaking into the engine would compare unequal to null everywhere.
        val map = Json.toMap(JSONObject("""{"a":null}"""))
        assertTrue(map.containsKey("a"))
        assertNull(map["a"])
    }

    @Test
    fun `a round trip preserves structure`() {
        // Equality, not key order: org.json backs JSONObject with a hash map, so the order keys
        // are written in is arbitrary. Nothing depends on it, and the repo is the source of
        // truth for rules rather than the file on the device.
        val original = mapOf(
            "id" to "rule",
            "enabled" to true,
            "count" to 3,
            "nested" to mapOf("k" to "v"),
            "list" to listOf("a", "b")
        )
        assertEquals(original, Json.toMap(JSONObject(Json.toJson(original).toString())))
    }

    @Test
    fun `a null value survives a round trip`() {
        val round = Json.toMap(JSONObject(Json.toJson(mapOf("a" to null)).toString()))
        assertTrue(round.containsKey("a"))
        assertNull(round["a"])
    }

    @Test
    fun `lists round trip through the array form`() {
        val original = listOf(mapOf("id" to "a"), mapOf("id" to "b"))
        assertEquals(original, Json.toList(JSONArray(Json.toJson(original).toString())))
    }

    @Test
    fun `an empty object and array are handled`() {
        assertEquals(emptyMap<String, Any?>(), Json.toMap(JSONObject("{}")))
        assertEquals(emptyList<Any?>(), Json.toList(JSONArray("[]")))
    }
}
