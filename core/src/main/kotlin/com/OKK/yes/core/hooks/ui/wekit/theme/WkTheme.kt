package com.OKK.yes.core.hooks.ui.wekit.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.OKK.yes.core.hooks.PublicConfigStore

/**
 * 注入微信内的主题（简化版 InjectedUiTheme）：
 * 直接用 WeKit 的 lightScheme/darkScheme（primary = 0xFF5B8DEF 蓝），
 * 去掉 SeedResolver/ThemeSettings/HostInfo 等 WeKit 专有依赖。
 * 支持等比例 DPI 自适应缩放。
 */
@Composable
fun WkInjectedTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    val autoDpiOn = remember { PublicConfigStore.getBoolean("auto_dpi_scaling_enabled", true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = LocalConfiguration.current
    val currentDensity = LocalDensity.current

    val adaptiveDensity = remember(autoDpiOn, configuration.screenWidthDp, configuration.screenHeightDp, currentDensity) {
        if (autoDpiOn) {
            val dm = context.resources.displayMetrics
            val realWidthPx = minOf(dm.widthPixels, dm.heightPixels)
            val targetDensity = (realWidthPx / 425f).coerceIn(currentDensity.density * 0.90f, currentDensity.density * 1.35f)
            val scale = (targetDensity / currentDensity.density).coerceIn(0.95f, 1.35f)
            Density(
                density = currentDensity.density * scale,
                fontScale = currentDensity.fontScale * scale
            )
        } else {
            currentDensity
        }
    }

    CompositionLocalProvider(
        LocalDensity provides adaptiveDensity
    ) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme else lightScheme,
            content = content,
        )
    }
}
