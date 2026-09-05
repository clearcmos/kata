package com.clearcmos.kata

import com.clearcmos.kata.model.Args
import com.clearcmos.kata.model.Automation
import com.clearcmos.kata.model.FieldType
import com.clearcmos.kata.model.Param
import com.clearcmos.kata.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ParamCarryTest {
    private fun withParams(vararg params: Param) = Automation(
        id = "rule",
        name = "Rule",
        trigger = Step("manual", Args.EMPTY),
        actions = listOf(Step("log", Args(mapOf("message" to "x")))),
        params = params.toList()
    )

    @Test
    fun `a value set on the phone survives a sync`() {
        val onDevice = withParams(Param("ssid", "Network", FieldType.STRING, "TestNet"))
        val fromRepo = withParams(Param("ssid", "Network", FieldType.STRING, "CHANGE-ME"))
        assertEquals("TestNet", fromRepo.carryDeviceState(onDevice).params.single().value)
    }

    @Test
    fun `the repo still owns the label and type`() {
        val onDevice = withParams(Param("ssid", "Old label", FieldType.STRING, "TestNet"))
        val fromRepo = withParams(Param("ssid", "New label", FieldType.STRING, "CHANGE-ME"))
        val merged = fromRepo.carryDeviceState(onDevice).params.single()
        assertEquals("New label", merged.label)
        assertEquals("TestNet", merged.value)
    }

    @Test
    fun `a newly declared param takes the repo value`() {
        val onDevice = withParams(Param("ssid", "Network", FieldType.STRING, "TestNet"))
        val fromRepo = withParams(
            Param("ssid", "Network", FieldType.STRING, "CHANGE-ME"),
            Param("volume", "Volume", FieldType.INT, "60")
        )
        val merged = fromRepo.carryDeviceState(onDevice).params.associate { it.key to it.value }
        assertEquals(mapOf("ssid" to "TestNet", "volume" to "60"), merged)
    }

    @Test
    fun `a param the repo dropped does not come back`() {
        val onDevice = withParams(
            Param("ssid", "Network", FieldType.STRING, "TestNet"),
            Param("gone", "Gone", FieldType.STRING, "value")
        )
        val fromRepo = withParams(Param("ssid", "Network", FieldType.STRING, "CHANGE-ME"))
        assertEquals(listOf("ssid"), fromRepo.carryDeviceState(onDevice).params.map { it.key })
    }

    @Test
    fun `a first install has nothing to carry`() {
        val fromRepo = withParams(Param("ssid", "Network", FieldType.STRING, "CHANGE-ME"))
        assertEquals("CHANGE-ME", fromRepo.carryDeviceState(null).params.single().value)
    }

    @Test
    fun `the armed flag follows the installed copy, even when parameters are reset`() {
        val fromRepo = withParams(Param("ssid", "Network", FieldType.STRING, "CHANGE-ME"))
        val onDevice = withParams(Param("ssid", "Network", FieldType.STRING, "TestNet")).copy(enabled = false)
        val kept = fromRepo.carryDeviceState(onDevice)
        assertFalse(kept.enabled)
        assertEquals("TestNet", kept.params.single().value)
        val reset = fromRepo.carryDeviceState(onDevice, keepParams = false)
        assertFalse(reset.enabled)
        assertEquals("CHANGE-ME", reset.params.single().value)
    }
}
