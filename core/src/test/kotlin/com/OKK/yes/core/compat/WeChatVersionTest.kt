package com.OKK.yes.core.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatVersionTest {

    @Test
    fun parseName_common() {
        assertEquals(Triple(8, 0, 69), WeChatVersion.parseName("8.0.69"))
        assertEquals(Triple(8, 0, 76), WeChatVersion.parseName("8.0.76"))
        assertEquals(Triple(8, 0, 72), WeChatVersion.parseName("8.0.72"))
    }

    @Test
    fun primaryRange_includes_69_to_79() {
        for (p in 69..79) {
            val v = WeChatVersion("8.0.$p", 3000L + p, WeChatVersion.Channel.CN)
            assertTrue("8.0.$p should be primary", v.inPrimaryRange)
            assertEquals("主适配", v.supportLabel)
        }
    }

    @Test
    fun cn79_3200_is_cn() {
        val v = WeChatVersion("8.0.79", 3200L, WeChatVersion.Channel.CN)
        assertTrue(v.inPrimaryRange)
        assertEquals("主适配", v.supportLabel)
        assertEquals("CN", v.channel.name)
    }

    @Test
    fun play77_is_primary_range() {
        val v = WeChatVersion("8.0.77", 3141L, WeChatVersion.Channel.PLAY)
        assertTrue(v.inPrimaryRange)
        assertEquals("主适配", v.supportLabel)
        assertEquals("PLAY", v.channel.name)
    }

    @Test
    fun cn76_3141_is_cn() {
        val v = WeChatVersion("8.0.76", 3141L, WeChatVersion.Channel.CN)
        assertTrue(v.inPrimaryRange)
        assertEquals("CN", v.channel.name)
    }

    @Test
    fun primaryRange_excludes_outside() {
        assertFalse(WeChatVersion("8.0.60", 1, WeChatVersion.Channel.CN).inPrimaryRange)
        assertFalse(WeChatVersion("8.0.85", 1, WeChatVersion.Channel.CN).inPrimaryRange)
        assertFalse(WeChatVersion("7.0.22", 1, WeChatVersion.Channel.CN).inPrimaryRange)
    }

    @Test
    fun summary_contains_channel() {
        val s = WeChatVersion("8.0.72", 3100, WeChatVersion.Channel.CN).summary()
        assertTrue(s.contains("8.0.72"))
        assertTrue(s.contains("CN"))
        assertTrue(s.contains("主适配"))
    }

    @Test
    fun classNames_stable_constants() {
        assertTrue(WeChatClassNames.MAIN_SETTINGS_UI.contains("MainSettingsUI"))
        assertTrue(WeChatClassNames.DO_REVOKE_LOG.contains("doRevokeMsg"))
        assertEquals("MicroMsg.SettingDataSource", WeChatClassNames.SETTING_DATA_SOURCE_TAG)
    }
}
