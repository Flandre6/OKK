package com.OKK.yes.core.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomTabIconLogicTest {
    @Test
    fun isDefaultTabTitle() {
        assertTrue(BottomTabIconLogic.isDefaultTabTitle("微信"))
        assertTrue(BottomTabIconLogic.isDefaultTabTitle("通讯录"))
        assertTrue(BottomTabIconLogic.isDefaultTabTitle("发现"))
        assertTrue(BottomTabIconLogic.isDefaultTabTitle("我"))
        assertFalse(BottomTabIconLogic.isDefaultTabTitle("99+"))
        assertFalse(BottomTabIconLogic.isDefaultTabTitle(null))
    }
}
