package com.clearcmos.kata.model

/**
 * Decides which argument values may be printed.
 *
 * Run records are persisted to disk and served over the control API, and a dry run writes every
 * argument into one. Without this, an Authorization header or a request body typed into an
 * automation ends up in cleartext in both places.
 *
 * Resolution order for one argument:
 *  1. the field's declared [FieldSpec.sensitive] flag, when the type and field are known, then
 *  2. the name heuristic in [SENSITIVE_TOKENS].
 *
 * An unknown action type or an unrecognised key therefore falls through to the heuristic and is
 * masked rather than printed. That direction is deliberate: over-masking a structural field is
 * a readability annoyance, printing a credential is not recoverable.
 *
 * Concept adapted from OpenTasker's ActionArgumentSensitivity (MIT).
 */
object Sensitivity {
    const val REDACTED = "<redacted>"

    /**
     * Substrings that make any argument sensitive, whether or not its action is registered.
     * Deliberately broad, and matched case-insensitively against the key.
     */
    private val SENSITIVE_TOKENS = listOf(
        "authorization",
        "body",
        "cookie",
        "credential",
        "header",
        "passphrase",
        "password",
        "secret",
        "token",
        "key"
    )

    /** Keys that contain a listed token but are structural, not secret. */
    private val EXEMPT_KEYS = setOf("keyguard", "key_event", "keycode")

    fun isSensitive(kind: SpecKind, type: String, field: String): Boolean {
        val declared = Vocabulary.find(kind, type)?.fields?.firstOrNull { it.name == field }
        if (declared != null) return declared.sensitive
        return matchesHeuristic(field)
    }

    fun matchesHeuristic(field: String): Boolean {
        val lower = field.lowercase()
        if (lower in EXEMPT_KEYS) return false
        return SENSITIVE_TOKENS.any { lower.contains(it) }
    }

    /** A printable copy of [args] with sensitive values replaced. */
    fun redact(kind: SpecKind, type: String, args: Map<String, Any?>): Map<String, Any?> =
        args.mapValues { (key, value) ->
            if (isSensitive(kind, type, key)) REDACTED else value
        }

    /** A one-line rendering of an action's arguments, safe to persist and to serve. */
    fun describe(kind: SpecKind, type: String, args: Map<String, Any?>): String =
        redact(kind, type, args).entries.joinToString(", ") { "${it.key}=${it.value}" }
}
