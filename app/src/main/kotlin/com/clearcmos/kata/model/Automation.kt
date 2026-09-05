package com.clearcmos.kata.model

/** One trigger, condition, or action: a type id plus its argument bag. */
data class Step(val type: String, val args: Args) {
    fun toMap(): Map<String, Any?> = mapOf("type" to type) + args.raw()

    companion object {
        fun fromMap(map: Map<String, Any?>): Step {
            val type =
                map["type"]?.toString()
                    ?: throw ArgError("every trigger, condition, and action needs a \"type\"")
            return Step(type, Args(map - "type"))
        }
    }
}

/**
 * A value the user can change from the phone without re-authoring the rule. Referenced from
 * any string field as ${params.key}; substitution happens before validation and before every
 * run, so editing one in the app takes effect on the next fire.
 */
data class Param(val key: String, val label: String, val type: FieldType, val value: String) {
    fun toMap(): Map<String, Any?> =
        mapOf("key" to key, "label" to label, "type" to type.name.lowercase(), "value" to value)

    companion object {
        fun fromMap(map: Map<String, Any?>): Param {
            val key = map["key"]?.toString() ?: throw ArgError("every param needs a \"key\"")
            val rawType = map["type"]?.toString()?.uppercase() ?: "STRING"
            val type =
                runCatching { FieldType.valueOf(rawType) }.getOrElse {
                    throw ArgError("param '$key' has unknown type '${map["type"]}'")
                }
            return Param(
                key = key,
                label = map["label"]?.toString() ?: key,
                type = type,
                value = map["value"]?.toString().orEmpty()
            )
        }
    }
}

data class Automation(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val trigger: Step,
    val conditions: List<Step> = emptyList(),
    val actions: List<Step> = emptyList(),
    val params: List<Param> = emptyList()
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("id", id)
        put("name", name)
        if (description.isNotEmpty()) put("description", description)
        put("enabled", enabled)
        put("trigger", trigger.toMap())
        if (conditions.isNotEmpty()) put("conditions", conditions.map { it.toMap() })
        put("actions", actions.map { it.toMap() })
        if (params.isNotEmpty()) put("params", params.map { it.toMap() })
    }

    /** This automation with every ${params.key} replaced by its current value. */
    fun resolved(): Automation {
        if (params.isEmpty()) return this
        val lookup = params.associate { it.key to it.value }
        val substitute = { text: String -> Params.substitute(text, lookup) }
        return copy(
            trigger = trigger.copy(args = trigger.args.mapStrings(substitute)),
            conditions = conditions.map { it.copy(args = it.args.mapStrings(substitute)) },
            actions = actions.map { it.copy(args = it.args.mapStrings(substitute)) }
        )
    }

    /**
     * This automation with each param's value taken from [previous] where the key still exists.
     *
     * Params are the values the phone owns: the whole point of declaring one is to change it
     * from the device without editing the repo. A sync therefore carries the definition (label,
     * type, and the default for a brand new key) but must not overwrite a value someone set on
     * the phone, or every push would silently undo their edits.
     */
    /**
     * Takes over what belongs to the phone from the copy already installed: the armed flag, and
     * parameter values unless [keepParams] is false. A sync from the repo must not re-arm a rule
     * someone disarmed on the device, any more than it should overwrite a host they typed in.
     * The file's own `enabled` only seeds a rule the phone has not seen before.
     */
    fun carryDeviceState(previous: Automation?, keepParams: Boolean = true): Automation {
        if (previous == null) return this
        val carried =
            if (!keepParams || params.isEmpty()) {
                params
            } else {
                val existing = previous.params.associate { it.key to it.value }
                params.map { param -> existing[param.key]?.let { param.copy(value = it) } ?: param }
            }
        return copy(enabled = previous.enabled, params = carried)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): Automation {
            val id = map["id"]?.toString() ?: throw ArgError("automation is missing \"id\"")
            val triggerMap =
                map["trigger"] as? Map<String, Any?>
                    ?: throw ArgError("automation '$id' is missing \"trigger\"")
            val conditions =
                (map["conditions"] as? List<Any?>)
                    .orEmpty()
                    .map { Step.fromMap(it as? Map<String, Any?> ?: throw ArgError("condition must be an object")) }
            val actions =
                (map["actions"] as? List<Any?>)
                    .orEmpty()
                    .map { Step.fromMap(it as? Map<String, Any?> ?: throw ArgError("action must be an object")) }
            val params =
                (map["params"] as? List<Any?>)
                    .orEmpty()
                    .map { Param.fromMap(it as? Map<String, Any?> ?: throw ArgError("param must be an object")) }
            return Automation(
                id = id,
                name = map["name"]?.toString() ?: id,
                description = map["description"]?.toString().orEmpty(),
                enabled = map["enabled"] as? Boolean ?: true,
                trigger = Step.fromMap(triggerMap),
                conditions = conditions,
                actions = actions,
                params = params
            )
        }
    }
}

object Params {
    private val PARAM_REFERENCE = Regex("""\$\{params\.([A-Za-z0-9_]+)\}""")
    private val VAR_REFERENCE = Regex("""\$\{vars\.([A-Za-z0-9_]+)\}""")

    fun substitute(text: String, values: Map<String, String>): String =
        PARAM_REFERENCE.replace(text) { match -> values[match.groupValues[1]] ?: match.value }

    /**
     * Replaces ${vars.name} from the live run scope.
     *
     * An unknown name is left in place rather than blanked. A rule that silently sent an empty
     * string where a value was expected would be far harder to diagnose than one that visibly
     * carries an unresolved placeholder into the run log.
     */
    fun substituteVars(text: String, values: Map<String, String>): String =
        VAR_REFERENCE.replace(text) { match -> values[match.groupValues[1]] ?: match.value }

    /** Parameter names referenced by [text] that [values] does not define. */
    fun unresolved(text: String, values: Set<String>): List<String> = PARAM_REFERENCE.findAll(text)
        .map { it.groupValues[1] }
        .filter { it !in values }
        .toList()

    /**
     * Whether [text] carries a variable reference, which only resolves while a run is in
     * progress. Validation has to defer on these: the value is not knowable at install time.
     */
    fun hasRuntimeReference(text: String): Boolean = VAR_REFERENCE.containsMatchIn(text)
}
