package com.clearcmos.kata

import com.clearcmos.kata.engine.Store
import com.clearcmos.kata.model.Args
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.FieldType
import com.clearcmos.kata.model.Param
import com.clearcmos.kata.model.Step
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun rule(id: String, enabled: Boolean = true, params: List<Param> = emptyList()) = Automation(
        id = id,
        name = id,
        enabled = enabled,
        trigger = Step("manual", Args.EMPTY),
        actions = listOf(Step("log", Args(mapOf("message" to "x")))),
        params = params
    )

    @Test
    fun `an empty store reads as empty rather than failing`() {
        assertEquals(emptyList<Automation>(), Store(temp.root).all())
    }

    @Test
    fun `automations survive a reload`() {
        Store(temp.root).replaceAll(listOf(rule("a"), rule("b")))
        assertEquals(listOf("a", "b"), Store(temp.root).all().map { it.id })
    }

    @Test
    fun `replaceAll is a replacement, not a merge`() {
        val store = Store(temp.root)
        store.replaceAll(listOf(rule("a"), rule("b")))
        store.replaceAll(listOf(rule("c")))
        assertEquals(listOf("c"), store.all().map { it.id })
    }

    @Test
    fun `upsert replaces in place and keeps ordering`() {
        val store = Store(temp.root)
        store.replaceAll(listOf(rule("a"), rule("b"), rule("c")))
        store.upsert(rule("b", enabled = false))
        assertEquals(listOf("a", "b", "c"), store.all().map { it.id })
        assertFalse(store.find("b")!!.enabled)
    }

    @Test
    fun `upsert appends an unseen id`() {
        val store = Store(temp.root)
        store.replaceAll(listOf(rule("a")))
        store.upsert(rule("z"))
        assertEquals(listOf("a", "z"), store.all().map { it.id })
    }

    @Test
    fun `delete reports whether it removed anything`() {
        val store = Store(temp.root)
        store.replaceAll(listOf(rule("a")))
        assertTrue(store.delete("a"))
        assertFalse(store.delete("a"))
        assertNull(store.find("a"))
    }

    @Test
    fun `setParam only touches a declared key`() {
        val store = Store(temp.root)
        store.replaceAll(listOf(rule("a", params = listOf(Param("host", "Host", FieldType.STRING, "old")))))
        assertTrue(store.setParam("a", "host", "new"))
        assertEquals("new", store.find("a")!!.params.single().value)
        assertFalse(store.setParam("a", "undeclared", "x"))
        assertFalse(store.setParam("missing-rule", "host", "x"))
    }

    @Test
    fun `enabled filters out disabled rules`() {
        val store = Store(temp.root)
        store.replaceAll(listOf(rule("on"), rule("off", enabled = false)))
        assertEquals(listOf("on"), store.enabled().map { it.id })
    }

    @Test
    fun `change listeners fire on every mutation`() {
        val store = Store(temp.root)
        var count = 0
        store.onChange { count++ }
        store.replaceAll(listOf(rule("a")))
        store.upsert(rule("b"))
        store.setEnabled("a", false)
        store.delete("b")
        assertEquals(4, count)
    }

    @Test
    fun `a corrupt file reads as empty instead of taking the engine down`() {
        // The rule set is recoverable from the repo, so reporting empty keeps the API and UI
        // usable enough to say so. Throwing here would kill the service on startup.
        File(temp.root, "automations.json").writeText("{ not json at all")
        assertEquals(emptyList<Automation>(), Store(temp.root).all())
    }

    @Test
    fun `a write leaves no temp file behind`() {
        val store = Store(temp.root)
        store.replaceAll(listOf(rule("a")))
        assertFalse(File(temp.root, "automations.json.tmp").exists())
    }
}
