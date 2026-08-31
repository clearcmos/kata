package com.clearcmos.kata.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges org.json (which Android ships) and plain Kotlin collections, so the rest of the
 * codebase never touches JSONObject. Everything downstream works on Map/List/primitives,
 * which keeps the spec registry and the validator free of serialization concerns.
 */
object Json {
    fun toMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in obj.keys()) {
            out[key] = unwrap(obj.get(key))
        }
        return out
    }

    fun toList(arr: JSONArray): List<Any?> {
        val out = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(unwrap(arr.get(i)))
        }
        return out
    }

    private fun unwrap(value: Any?): Any? = when (value) {
        JSONObject.NULL, null -> null
        is JSONObject -> toMap(value)
        is JSONArray -> toList(value)
        else -> value
    }

    fun toJson(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) {
            obj.put(key, wrap(value))
        }
        return obj
    }

    fun toJson(list: List<Any?>): JSONArray {
        val arr = JSONArray()
        for (value in list) {
            arr.put(wrap(value))
        }
        return arr
    }

    private fun wrap(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            toJson(value as Map<String, Any?>)
        }
        is List<*> -> toJson(value)
        else -> value
    }
}
