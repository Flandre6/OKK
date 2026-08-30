package com.OKK.yes.core.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeatureHookRegistryTest {

    @Before
    fun setUp() {
        FeatureHookRegistry.clear()
    }

    @Test
    fun isolated_fail_does_not_block_next() {
        val ok1 = FeatureHookRegistry.installIsolated("A") { /* ok */ }
        val ok2 = FeatureHookRegistry.installIsolated("B") {
            error("version mismatch")
        }
        val ok3 = FeatureHookRegistry.installIsolated("C") { /* ok */ }

        assertTrue(ok1)
        assertFalse(ok2)
        assertTrue(ok3)
        assertEquals(listOf("A", "C"), FeatureHookRegistry.okNames())
        assertEquals(listOf("B"), FeatureHookRegistry.failNames())
        assertTrue(FeatureHookRegistry.summaryLine().contains("ok=2"))
        assertTrue(FeatureHookRegistry.summaryLine().contains("fail=1"))
    }
}
