package com.clearcmos.kata.engine

import java.util.Calendar

/** Wall-clock helpers shared by time_between conditions and time_of_day triggers. */
object Clock {
    private val DAY_NAMES =
        mapOf(
            Calendar.MONDAY to "mon",
            Calendar.TUESDAY to "tue",
            Calendar.WEDNESDAY to "wed",
            Calendar.THURSDAY to "thu",
            Calendar.FRIDAY to "fri",
            Calendar.SATURDAY to "sat",
            Calendar.SUNDAY to "sun"
        )

    /** "HH:mm" to minutes past midnight, or null if malformed. */
    fun parseMinutes(text: String): Int? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun nowMinutes(calendar: Calendar = Calendar.getInstance()): Int =
        calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

    fun dayName(calendar: Calendar = Calendar.getInstance()): String =
        DAY_NAMES[calendar.get(Calendar.DAY_OF_WEEK)] ?: "mon"

    /**
     * Inclusive of [from], exclusive of [to]. A window whose end is at or before its start is
     * read as wrapping past midnight, so 22:00-06:00 means the night rather than an empty set.
     */
    fun isBetween(now: Int, from: Int, to: Int): Boolean =
        if (from <= to) now >= from && now < to else now >= from || now < to

    /** Epoch millis of the next occurrence of [minutes] past midnight, on one of [days]. */
    fun nextOccurrence(minutes: Int, days: Set<String>, from: Calendar = Calendar.getInstance()): Long {
        val candidate =
            (from.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, minutes / 60)
                set(Calendar.MINUTE, minutes % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        if (!candidate.after(from)) candidate.add(Calendar.DAY_OF_YEAR, 1)
        if (days.isEmpty()) return candidate.timeInMillis
        // At most seven hops: one of the seven day names must match.
        repeat(7) {
            if (dayName(candidate) in days) return candidate.timeInMillis
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return candidate.timeInMillis
    }
}
