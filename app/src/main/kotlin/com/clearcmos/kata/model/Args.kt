package com.clearcmos.kata.model

/**
 * Typed, failure-loud access to a trigger/condition/action argument bag.
 *
 * Every accessor throws [ArgError] naming the field and what was expected. Those messages
 * travel back over the control API verbatim, so an authoring agent gets a correction it can
 * act on rather than a stack trace.
 */
class ArgError(message: String) : IllegalArgumentException(message)

class Args(private val map: Map<String, Any?>) {
    val keys: Set<String> get() = map.keys

    fun has(key: String): Boolean = map[key] != null

    fun raw(): Map<String, Any?> = map

    fun optString(key: String): String? = map[key]?.toString()

    fun string(key: String): String = optString(key) ?: throw ArgError("missing required string field '$key'")

    fun optInt(key: String): Int? {
        val value = map[key] ?: return null
        return when (value) {
            is Number -> value.toInt()
            is String ->
                value.toIntOrNull()
                    ?: throw ArgError("field '$key' must be a number, got \"$value\"")
            else -> throw ArgError("field '$key' must be a number")
        }
    }

    fun int(key: String): Int = optInt(key) ?: throw ArgError("missing required number field '$key'")

    fun int(key: String, default: Int): Int = optInt(key) ?: default

    fun optLong(key: String): Long? {
        val value = map[key] ?: return null
        return when (value) {
            is Number -> value.toLong()
            is String ->
                value.toLongOrNull()
                    ?: throw ArgError("field '$key' must be a number, got \"$value\"")
            else -> throw ArgError("field '$key' must be a number")
        }
    }

    fun long(key: String, default: Long): Long = optLong(key) ?: default

    fun bool(key: String, default: Boolean): Boolean {
        val value = map[key] ?: return default
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> throw ArgError("field '$key' must be a boolean")
        }
    }

    fun bool(key: String): Boolean {
        map[key] ?: throw ArgError("missing required boolean field '$key'")
        return bool(key, false)
    }

    fun stringList(key: String): List<String> {
        val value = map[key] ?: return emptyList()
        return when (value) {
            is List<*> -> value.mapNotNull { it?.toString() }
            is String -> listOf(value)
            else -> throw ArgError("field '$key' must be a list of strings")
        }
    }

    fun stringMap(key: String): Map<String, String> {
        val value = map[key] ?: return emptyMap()
        if (value !is Map<*, *>) throw ArgError("field '$key' must be an object")
        return value.entries.associate { (k, v) -> k.toString() to v?.toString().orEmpty() }
    }

    /** Applies [transform] to every string leaf, used for parameter substitution. */
    fun mapStrings(transform: (String) -> String): Args = Args(mapValue(map, transform))

    private fun mapValue(value: Any?, transform: (String) -> String): Any? = when (value) {
        is String -> transform(value)
        is Map<*, *> ->
            value.entries.associate { (k, v) -> k.toString() to mapValue(v, transform) }
        is List<*> -> value.map { mapValue(it, transform) }
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapValue(map: Map<String, Any?>, transform: (String) -> String): Map<String, Any?> =
        mapValue(map as Any?, transform) as Map<String, Any?>

    companion object {
        val EMPTY = Args(emptyMap())
    }
}
