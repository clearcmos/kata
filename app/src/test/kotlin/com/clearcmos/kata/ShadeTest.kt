package com.clearcmos.kata

import com.clearcmos.kata.engine.Shade
import com.clearcmos.kata.engine.ShadeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShadeTest {
    private fun entry(
        key: String,
        title: String = "t",
        text: String = "x",
        ongoing: Boolean = false,
        group: String = key,
        summary: Boolean = false
    ) = ShadeEntry(
        key,
        "com.example",
        "Example",
        title,
        text,
        0L,
        !ongoing,
        ongoing,
        false,
        true,
        null,
        null,
        group,
        summary
    )

    @Test
    fun `a summary is dropped while one of its children is shown`() {
        val shown = Shade.visible(
            listOf(entry("s", group = "g", summary = true), entry("c1", group = "g"), entry("c2", group = "g"))
        )
        assertEquals(listOf("c1", "c2"), shown.map { it.key })
    }

    @Test
    fun `a summary with no children left is kept`() {
        val shown = Shade.visible(listOf(entry("s", group = "g", summary = true), entry("other")))
        assertEquals(listOf("s", "other"), shown.map { it.key })
    }

    @Test
    fun `the order given is kept`() {
        assertEquals(listOf("b", "a"), Shade.visible(listOf(entry("b"), entry("a"))).map { it.key })
    }

    @Test
    fun `new text in a notification changes the signature`() {
        assertNotEquals(
            Shade.signature(listOf(entry("a", text = "1"))),
            Shade.signature(listOf(entry("a", text = "2")))
        )
    }

    @Test
    fun `an ongoing notification reposting new text does not`() {
        assertEquals(
            Shade.signature(listOf(entry("a", text = "10%", ongoing = true))),
            Shade.signature(listOf(entry("a", text = "11%", ongoing = true)))
        )
    }

    @Test
    fun `an ongoing notification appearing does`() {
        assertNotEquals(Shade.signature(emptyList()), Shade.signature(listOf(entry("a", ongoing = true))))
    }

    @Test
    fun `the count leaves out ongoing notifications`() {
        assertEquals(1, Shade.count(listOf(entry("a"), entry("b", ongoing = true))))
    }
}
