package com.OKK.yes.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsEntryPolicyTest {
    @Test
    fun `matches modern and legacy wechat settings pages`() {
        assertTrue(
            SettingsEntryPolicy.shouldInjectInto(
                "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
            )
        )
        assertTrue(
            SettingsEntryPolicy.shouldInjectInto(
                "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"
            )
        )
    }

    @Test
    fun `rejects unrelated wechat pages`() {
        assertFalse(SettingsEntryPolicy.shouldInjectInto("com.tencent.mm.ui.LauncherUI"))
        assertFalse(SettingsEntryPolicy.shouldInjectInto("com.tencent.mm.ui.chatting.ChattingUI"))
    }

    @Test
    fun `exposes stable entry text`() {
        assertEquals("abc_module_settings_entry", SettingsEntryPolicy.entryKey)
        assertEquals("OKK", SettingsEntryPolicy.entryTitle)
        assertEquals("消息增强", SettingsEntryPolicy.entrySubtitle)
    }
}
