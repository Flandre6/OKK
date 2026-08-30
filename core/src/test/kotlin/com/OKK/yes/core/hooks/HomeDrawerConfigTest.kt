package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDrawerConfigTest {
    @Test
    fun maxThreeAndDistinct() {
        val list = listOf(
            HomeDrawerConfig.Shortcut.QRCODE,
            HomeDrawerConfig.Shortcut.PAY,
            HomeDrawerConfig.Shortcut.SERVICE,
            HomeDrawerConfig.Shortcut.FAVORITE
        )
        val clipped = list.distinct().take(HomeDrawerConfig.MAX_SHORTCUTS)
        assertEquals(3, clipped.size)
    }

    @Test
    fun fromId() {
        assertEquals(HomeDrawerConfig.Shortcut.QRCODE, HomeDrawerConfig.Shortcut.fromId("qrcode"))
        assertEquals(HomeDrawerConfig.Shortcut.PAY, HomeDrawerConfig.Shortcut.fromId("pay"))
    }

    @Test
    fun toggleRespectsMax() {
        var cur = listOf(
            HomeDrawerConfig.Shortcut.QRCODE,
            HomeDrawerConfig.Shortcut.PAY,
            HomeDrawerConfig.Shortcut.FAVORITE
        )
        val next = HomeDrawerConfig.toggleInList(cur, HomeDrawerConfig.Shortcut.SERVICE)
        assertEquals(3, next.size)
        assertTrue(next.none { it == HomeDrawerConfig.Shortcut.SERVICE })
    }
}
