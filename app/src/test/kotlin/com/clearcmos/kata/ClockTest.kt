package com.clearcmos.kata

import com.clearcmos.kata.engine.Clock
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {
    @Test
    fun `parses a well formed time`() {
        assertEquals(0, Clock.parseMinutes("00:00"))
        assertEquals(22 * 60 + 30, Clock.parseMinutes("22:30"))
        assertEquals(23 * 60 + 59, Clock.parseMinutes("23:59"))
    }

    @Test
    fun `rejects out of range and malformed times`() {
        assertNull(Clock.parseMinutes("24:00"))
        assertNull(Clock.parseMinutes("12:60"))
        assertNull(Clock.parseMinutes("noon"))
        assertNull(Clock.parseMinutes("7"))
    }

    @Test
    fun `a same day window is inclusive of start and exclusive of end`() {
        val from = 9 * 60
        val to = 17 * 60
        assertTrue(Clock.isBetween(from, from, to))
        assertTrue(Clock.isBetween(12 * 60, from, to))
        assertFalse(Clock.isBetween(to, from, to))
        assertFalse(Clock.isBetween(8 * 60, from, to))
    }

    @Test
    fun `a window whose end precedes its start wraps past midnight`() {
        val from = 22 * 60
        val to = 6 * 60
        assertTrue(Clock.isBetween(23 * 60, from, to))
        assertTrue(Clock.isBetween(2 * 60, from, to))
        assertTrue(Clock.isBetween(from, from, to))
        assertFalse(Clock.isBetween(12 * 60, from, to))
        assertFalse(Clock.isBetween(to, from, to))
    }

    @Test
    fun `the next occurrence of a past time is tomorrow`() {
        val now =
            Calendar.getInstance().apply {
                set(2026, Calendar.AUGUST, 30, 14, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
        val next = Calendar.getInstance().apply { timeInMillis = Clock.nextOccurrence(9 * 60, emptySet(), now) }
        assertEquals(31, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, next.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `the next occurrence of a future time is today`() {
        val now =
            Calendar.getInstance().apply {
                set(2026, Calendar.AUGUST, 30, 8, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
        val next = Calendar.getInstance().apply { timeInMillis = Clock.nextOccurrence(9 * 60, emptySet(), now) }
        assertEquals(30, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, next.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `a day filter skips forward to an allowed day`() {
        // 2026-08-30 is a Sunday.
        val now =
            Calendar.getInstance().apply {
                set(2026, Calendar.AUGUST, 30, 8, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
        val next =
            Calendar.getInstance().apply {
                timeInMillis = Clock.nextOccurrence(9 * 60, setOf("wed"), now)
            }
        assertEquals(Calendar.WEDNESDAY, next.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, next.get(Calendar.HOUR_OF_DAY))
    }
}
