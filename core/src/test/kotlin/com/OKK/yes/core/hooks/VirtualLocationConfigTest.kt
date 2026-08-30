package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualLocationConfigTest {

    @Test
    fun fallbackCoords_inRange() {
        assertTrue(VirtualLocationConfig.FALLBACK_LATITUDE in -90.0..90.0)
        assertTrue(VirtualLocationConfig.FALLBACK_LONGITUDE in -180.0..180.0)
    }

    @Test
    fun legacyThailand_detectedAsUnset() {
        assertTrue(VirtualLocationConfig.isLegacyUnset(16.61953, 98.56146))
        assertFalse(VirtualLocationConfig.isLegacyUnset(39.9, 116.4))
        assertFalse(VirtualLocationConfig.isLegacyUnset(22.5, 114.0))
    }

    @Test
    fun fmt_usesUsLocale() {
        val s = VirtualLocationConfig.fmt(39.9042)
        assertTrue(s.contains('.'))
        assertEquals(6, s.substringAfter('.').length)
    }
}
