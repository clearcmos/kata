package com.clearcmos.kata

import com.clearcmos.kata.engine.RunVars
import com.clearcmos.kata.engine.VarStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VarStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a persisted value survives a reload`() {
        VarStore(temp.root).set("counter", "7")
        assertEquals("7", VarStore(temp.root).get("counter"))
    }

    @Test
    fun `removing and clearing work`() {
        val store = VarStore(temp.root)
        store.set("a", "1")
        store.set("b", "2")
        store.remove("a")
        assertNull(store.get("a"))
        assertEquals("2", store.get("b"))
        store.clear()
        assertEquals(emptyMap<String, String>(), store.all())
    }

    @Test
    fun `a corrupt file reads as empty`() {
        File(temp.root, "vars.json").writeText("not json")
        assertEquals(emptyMap<String, String>(), VarStore(temp.root).all())
    }

    @Test
    fun `run scope is seeded from the trigger facts`() {
        val vars = RunVars(VarStore(temp.root), mapOf("package" to "com.example"))
        assertEquals("com.example", vars.get("package"))
    }

    @Test
    fun `a run scoped value shadows a stale persisted one`() {
        // A fact from this run must beat whatever a previous run left behind, or a rule would
        // act on last week's value while looking correct.
        val store = VarStore(temp.root)
        store.set("ssid", "old-network")
        val vars = RunVars(store, mapOf("ssid" to "current-network"))
        assertEquals("current-network", vars.get("ssid"))
        assertEquals("current-network", vars.snapshot()["ssid"])
    }

    @Test
    fun `outputs merged during a run are visible to later steps`() {
        val vars = RunVars(VarStore(temp.root))
        vars.putAll(mapOf("status" to "200", "body" to "ok"))
        assertEquals("200", vars.get("status"))
        assertEquals("ok", vars.snapshot()["body"])
    }

    @Test
    fun `persist writes through to the store and stays visible in the run`() {
        val store = VarStore(temp.root)
        RunVars(store).persist("seen", "yes")
        assertEquals("yes", store.get("seen"))
        assertEquals("yes", VarStore(temp.root).get("seen"))
    }

    @Test
    fun `run scoped values do not leak into the persisted store`() {
        val store = VarStore(temp.root)
        RunVars(store).put("ephemeral", "value")
        assertNull(VarStore(temp.root).get("ephemeral"))
    }
}
