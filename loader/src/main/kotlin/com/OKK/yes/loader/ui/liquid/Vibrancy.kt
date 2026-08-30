// 移植自 wcx com.Johnny.wcx.ui.content.liquid.Vibrancy
// Adapted from Kyant0/AndroidLiquidGlass (Apache 2.0)

package com.OKK.yes.loader.ui.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/** 毛玻璃 vibrancy 效果（增强对比度 + 饱和度）。 */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
