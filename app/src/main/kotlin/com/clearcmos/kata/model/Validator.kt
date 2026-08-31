package com.clearcmos.kata.model

/**
 * Turns a parsed automation into a list of human-readable problems.
 *
 * Every message names the path it applies to (`actions[1].level`) and says what was expected.
 * These strings are what an authoring agent sees when an install is rejected, so they are the
 * feedback loop; vagueness here costs a round trip.
 */
object Validator {
    const val RETRY_FIELD = "retry"
    const val MAX_RETRIES = 5

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]*$")
    private val TIME_PATTERN = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
    private val DAYS = setOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    fun validate(automation: Automation): List<String> {
        val errors = ArrayList<String>()

        if (!ID_PATTERN.matches(automation.id)) {
            errors.add(
                "id '${automation.id}' must be lowercase letters, digits, hyphen or underscore, " +
                    "starting with a letter or digit"
            )
        }
        if (automation.name.isBlank()) errors.add("name must not be blank")
        if (automation.actions.isEmpty()) errors.add("actions must contain at least one action")

        val paramKeys = automation.params.map { it.key }
        paramKeys
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { errors.add("params defines '$it' more than once") }

        checkParamReferences(automation, paramKeys.toSet(), errors)

        val resolved = automation.resolved()
        // Any escaping exception would collapse the whole report down to one message, so each
        // step is fenced off: one unparseable step must not hide the problems in the others.
        fence(errors, "trigger") { checkStep(resolved.trigger, SpecKind.TRIGGER, "trigger", errors) }
        resolved.conditions.forEachIndexed { i, step ->
            fence(errors, "conditions[$i]") { checkStep(step, SpecKind.CONDITION, "conditions[$i]", errors) }
        }
        resolved.actions.forEachIndexed { i, step ->
            fence(errors, "actions[$i]") { checkStep(step, SpecKind.ACTION, "actions[$i]", errors) }
        }
        return errors
    }

    private inline fun fence(errors: MutableList<String>, path: String, block: () -> Unit) {
        runCatching(block).onFailure { errors.add("$path could not be checked: ${it.message}") }
    }

    private fun checkParamReferences(automation: Automation, defined: Set<String>, errors: MutableList<String>) {
        val steps =
            listOf("trigger" to automation.trigger) +
                automation.conditions.mapIndexed { i, s -> "conditions[$i]" to s } +
                automation.actions.mapIndexed { i, s -> "actions[$i]" to s }
        for ((path, step) in steps) {
            for (text in collectStrings(step.args.raw())) {
                for (name in Params.unresolved(text, defined)) {
                    errors.add("$path references \${params.$name}, which is not declared in params")
                }
            }
        }
    }

    private fun collectStrings(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is Map<*, *> -> value.values.flatMap { collectStrings(it) }
        is List<*> -> value.flatMap { collectStrings(it) }
        else -> emptyList()
    }

    private fun checkStep(step: Step, kind: SpecKind, path: String, errors: MutableList<String>) {
        val spec = Vocabulary.find(kind, step.type)
        if (spec == null) {
            val label = kind.name.lowercase()
            val hint = suggest(step.type, Vocabulary.ids(kind))
            errors.add("$path has unknown $label type '${step.type}'$hint")
            return
        }

        val known = spec.fields.associateBy { it.name }
        var wellTyped = true
        for (key in step.args.keys) {
            if (key == RETRY_FIELD && kind == SpecKind.ACTION) {
                checkRetry(step, spec, path, errors)
                continue
            }
            if (key !in known) {
                val hint = suggest(key, spec.fields.map { it.name })
                errors.add("$path.$key is not a field of '${step.type}'$hint")
            }
        }
        for (field in spec.fields) {
            if (field.required && !step.args.has(field.name)) {
                errors.add("$path is missing required field '${field.name}' (${field.doc})")
                wellTyped = false
                continue
            }
            if (!step.args.has(field.name)) continue
            if (!checkField(step, field, path, errors)) wellTyped = false
        }
        // Cross-field rules read values as numbers and enums. Running them over a field that
        // already failed its type check, or one that is still a runtime placeholder, would
        // either throw or pile a confusing second error on top of the real one.
        val deferred = step.args.raw().values.any { it is String && Params.hasRuntimeReference(it) }
        if (wellTyped && !deferred) checkStepInvariants(step, path, errors)
    }

    /** Returns false when the value is unusable, so cross-field checks know to stand down. */
    private fun checkField(step: Step, field: FieldSpec, path: String, errors: MutableList<String>): Boolean {
        val where = "$path.${field.name}"
        val raw = step.args.raw()[field.name]
        // A ${vars.x} reference is filled in mid-run, so its type cannot be judged here. Checking
        // it would reject every rule that chains one action's output into the next.
        if (raw is String && Params.hasRuntimeReference(raw)) return true
        val problem: String? = when (field.type) {
            FieldType.INT ->
                if (runCatching { step.args.int(field.name) }.isFailure) {
                    "$where must be a whole number, got \"$raw\""
                } else {
                    null
                }
            FieldType.BOOL ->
                if (raw !is Boolean && raw?.toString()?.lowercase() !in setOf("true", "false")) {
                    "$where must be true or false, got \"$raw\""
                } else {
                    null
                }
            FieldType.TIME ->
                if (!TIME_PATTERN.matches(raw?.toString().orEmpty())) {
                    "$where must be a 24-hour time like 07:30, got \"$raw\""
                } else {
                    null
                }
            FieldType.ENUM -> {
                val value = raw?.toString().orEmpty()
                if (field.values.none { it.equals(value, ignoreCase = true) }) {
                    "$where must be one of ${field.values.joinToString(", ")}, got \"$value\""
                } else {
                    null
                }
            }
            FieldType.STRING_LIST ->
                if (raw !is List<*> && raw !is String) "$where must be a list of strings" else null
            FieldType.OBJECT ->
                if (raw !is Map<*, *>) "$where must be an object" else null
            FieldType.STRING ->
                if (raw?.toString().isNullOrBlank()) "$where must not be empty" else null
        }
        problem?.let { errors.add(it) }
        return problem == null
    }

    /**
     * `retry` is accepted on any action but refused on one that is not idempotent, because
     * retrying a toggle undoes the first attempt instead of repeating it.
     */
    private fun checkRetry(step: Step, spec: TypeSpec, path: String, errors: MutableList<String>) {
        val count = runCatching { step.args.optInt(RETRY_FIELD) }.getOrNull()
        if (count == null) {
            errors.add("$path.$RETRY_FIELD must be a whole number")
            return
        }
        if (count !in 0..MAX_RETRIES) {
            errors.add("$path.$RETRY_FIELD must be between 0 and $MAX_RETRIES")
            return
        }
        if (count > 0 && spec.retrySafety != RetrySafety.IDEMPOTENT) {
            errors.add(
                "$path.$RETRY_FIELD is not allowed on '${step.type}': running it twice does not " +
                    "repeat the first attempt. Only idempotent actions can be retried."
            )
        }
    }

    /** Cross-field rules that a per-field spec cannot express. */
    private fun checkStepInvariants(step: Step, path: String, errors: MutableList<String>) {
        when (step.type) {
            "battery_level" ->
                if (!step.args.has("below") && !step.args.has("above")) {
                    errors.add("$path needs at least one of 'below' or 'above'")
                }
            "interval" ->
                if ((step.args.optInt("minutes") ?: 1) < 1) {
                    errors.add("$path.minutes must be 1 or more")
                }
            "volume" -> {
                val level = step.args.optInt("level")
                if (level != null && level !in 0..100) errors.add("$path.level must be between 0 and 100")
            }
            "battery_below", "battery_above" -> {
                val value = step.args.optInt("value")
                if (value != null && value !in 0..100) errors.add("$path.value must be between 0 and 100")
            }
            "day_of_week", "time_of_day" -> {
                val bad = step.args.stringList("days").filter { it.lowercase() !in DAYS }
                if (bad.isNotEmpty()) {
                    errors.add(
                        "$path.days has unknown ${if (bad.size == 1) "day" else "days"} ${bad.joinToString(", ")}"
                    )
                }
            }
            "tap_ui" ->
                if (!step.args.has("text") && !step.args.has("content_description") && !step.args.has("view_id")) {
                    errors.add("$path needs one of 'text', 'content_description', or 'view_id'")
                }

            "http_request" ->
                step.args.optString("url")?.let {
                    if (!it.startsWith("http://") && !it.startsWith("https://")) {
                        errors.add("$path.url must start with http:// or https://")
                    }
                }
        }
    }

    /** " (did you mean 'x'?)" when a close match exists, otherwise the full list for short vocabularies. */
    private fun suggest(input: String, candidates: List<String>): String {
        if (candidates.isEmpty()) return ""
        val best = candidates.minByOrNull { distance(input.lowercase(), it.lowercase()) } ?: return ""
        return if (distance(input.lowercase(), best.lowercase()) <= 3) {
            " (did you mean '$best'?)"
        } else {
            " (known: ${candidates.sorted().joinToString(", ")})"
        }
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }
}
