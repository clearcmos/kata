package com.clearcmos.kata.engine

/**
 * One notification as the phone's shade shows it, reduced to what another machine needs to
 * mirror it: enough to draw a row, and the key to clear or open it by.
 */
data class ShadeEntry(
    val key: String,
    val packageName: String,
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val clearable: Boolean,
    val ongoing: Boolean,
    val silent: Boolean,
    val openable: Boolean,
    /**
     * The conversation as the app's web client names it, and its kind, where the app says so
     * (Messenger). The tag and shortcut only carry an id that exists on the phone alone.
     */
    val threadId: String?,
    val threadType: String?,
    /** The platform's group key: an app's own group, the system's auto-group, or the key itself. */
    val group: String,
    val groupSummary: Boolean
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "key" to key,
        "package" to packageName,
        "app" to app,
        "title" to title,
        "text" to text,
        "posted_at" to postedAt,
        "clearable" to clearable,
        "ongoing" to ongoing,
        "silent" to silent,
        "openable" to openable,
        "thread_id" to threadId,
        "thread_type" to threadType
    )
}

/** The pure half of mirroring the shade; the listener supplies the entries. */
object Shade {
    /**
     * The entries the shade lists, in the order given. A group's summary is dropped while any
     * of its children is present, because the shade folds the summary into them and it usually
     * says no more than "3 new messages". A summary with no children left is kept.
     */
    fun visible(entries: List<ShadeEntry>): List<ShadeEntry> {
        val withChildren = entries.filterNot { it.groupSummary }.map { it.group }.toSet()
        return entries.filterNot { it.groupSummary && it.group in withChildren }
    }

    /**
     * What has to differ for the shade to count as changed. An ongoing notification counts by
     * its key alone: media players, downloads, and navigation repost theirs every few seconds
     * with new text, and mirroring each repost would cost a network round trip apiece.
     */
    fun signature(entries: List<ShadeEntry>): String = entries.joinToString("\n") {
        if (it.ongoing) it.key else "${it.key}\t${it.title}\t${it.text}"
    }

    /** What a badge counts: notifications a person reads and clears, not ongoing status. */
    fun count(entries: List<ShadeEntry>): Int = entries.count { !it.ongoing }
}
