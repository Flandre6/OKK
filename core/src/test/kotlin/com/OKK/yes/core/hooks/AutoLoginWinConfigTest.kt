package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLoginWinConfigTest {
    @Test
    fun functionControlBitsMatchWa() {
        val all = AutoLoginWinOptions(
            enabled = true,
            syncMsg = true,
            showDevice = true,
            autoLoginDevice = true
        )
        assertEquals(0b111, all.functionControl())

        val onlySync = AutoLoginWinOptions(syncMsg = true, showDevice = false, autoLoginDevice = false)
        assertEquals(0b001, onlySync.functionControl())

        val onlyShow = AutoLoginWinOptions(syncMsg = false, showDevice = true, autoLoginDevice = false)
        assertEquals(0b010, onlyShow.functionControl())

        val onlyAuto = AutoLoginWinOptions(syncMsg = false, showDevice = false, autoLoginDevice = true)
        assertEquals(0b100, onlyAuto.functionControl())
    }

    @Test
    fun defaultOptionsSafe() {
        val d = AutoLoginWinOptions()
        assertFalse(d.enabled)
        assertTrue(d.syncMsg)
        assertTrue(d.showDevice)
        assertFalse(d.autoLoginDevice)
        assertTrue(d.autoClick)
        assertEquals(0b011, d.functionControl())
    }

    @Test
    fun intentExtraKeysStable() {
        assertEquals("intent.key.function.control", AutoLoginWinConfig.EXTRA_FUNCTION_CONTROL)
        assertEquals("intent.key.need.show.privacy.agreement", AutoLoginWinConfig.EXTRA_PRIVACY)
    }
}
