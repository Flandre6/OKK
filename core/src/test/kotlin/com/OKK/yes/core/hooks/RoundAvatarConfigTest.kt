package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class RoundAvatarConfigTest {
    @Test
    fun clampRadius_and_presets() {
        assertEquals(0.05f, RoundAvatarConfig.clampRadius(0f), 0.0001f)
        assertEquals(0.50f, RoundAvatarConfig.clampRadius(1f), 0.0001f)
        assertEquals(0.36f, RoundAvatarConfig.clampRadius(0.36f), 0.0001f)
        // 预设：正方 / 方圆 / 圆形
        assertEquals(0.05f, RoundAvatarConfig.PRESET_SQUARE, 0.0001f)
        assertEquals(0.36f, RoundAvatarConfig.DEFAULT_RADIUS, 0.0001f)
        assertEquals(0.36f, RoundAvatarConfig.PRESET_SOFT_CIRCLE, 0.0001f)
        assertEquals(0.50f, RoundAvatarConfig.PRESET_FULL_CIRCLE, 0.0001f)
    }
}
