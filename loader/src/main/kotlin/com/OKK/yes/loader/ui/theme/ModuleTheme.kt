// OKK Compose 设置主题：Material3 + MIUIX 双主题（对齐 wcx ModuleTheme）

package com.OKK.yes.loader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

// OKK 品牌绿（与旧 Palette 一致）
private val OkkGreen = Color(0xFF2F8A4E)
private val OkkGreenLight = Color(0xFF7FBF90)

private val LightColors = lightColorScheme(
    primary = OkkGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F0E1),
    onPrimaryContainer = Color(0xFF0F2A18),
    secondary = Color(0xFF4A5B4F),
    onSecondary = Color.White,
    background = Color(0xFFF7F7F8),
    onBackground = Color(0xFF1A1C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFECEEEC),
    onSurfaceVariant = Color(0xFF5C635D),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F1F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    outline = Color(0xFFC5CBC5),
    outlineVariant = Color(0xFFE6E8E6)
)

private val DarkColors = darkColorScheme(
    primary = OkkGreenLight,
    onPrimary = Color(0xFF0C1A11),
    primaryContainer = Color(0xFF244032),
    onPrimaryContainer = Color(0xFFD9F0E1),
    secondary = Color(0xFFB8C7BB),
    onSecondary = Color(0xFF121812),
    background = Color(0xFF0E0F0E),
    onBackground = Color(0xFFE8ECE8),
    surface = Color(0xFF1A1D1A),
    onSurface = Color(0xFFE8ECE8),
    surfaceVariant = Color(0xFF262A26),
    onSurfaceVariant = Color(0xFFA3ABA4),
    surfaceContainer = Color(0xFF1A1D1A),
    surfaceContainerHigh = Color(0xFF242824),
    surfaceContainerLowest = Color(0xFF121412),
    outline = Color(0xFF4C544D),
    outlineVariant = Color(0xFF2E332E)
)

private val OkkShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val OkkTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun OKKTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val autoDpiOn = androidx.compose.runtime.remember {
        com.OKK.yes.core.hooks.PublicConfigStore.getBoolean("auto_dpi_scaling_enabled", true)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val currentDensity = androidx.compose.ui.platform.LocalDensity.current

    // 精准读取设备物理参数与 DisplayMetrics，实现等比例完美适配大屏与各种 DPI 设备
    val adaptiveDensity = androidx.compose.runtime.remember(
        autoDpiOn,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        currentDensity
    ) {
        if (autoDpiOn) {
            val dm = context.resources.displayMetrics
            // 计算屏幕物理宽度（像素）
            val realWidthPx = minOf(dm.widthPixels, dm.heightPixels)
            // 以 425dp 标准宽度逆推，比例更加舒适适中（小一号，不局促也不过大）
            val targetDensity = (realWidthPx / 425f).coerceIn(currentDensity.density * 0.90f, currentDensity.density * 1.35f)
            val scale = (targetDensity / currentDensity.density).coerceIn(0.95f, 1.35f)

            androidx.compose.ui.unit.Density(
                density = currentDensity.density * scale,
                fontScale = currentDensity.fontScale * scale
            )
        } else {
            currentDensity
        }
    }

    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides adaptiveDensity
    ) {
        // 用 Monet 种子色把 MIUIX 主色拉到 OKK 绿
        val controller = ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
            keyColor = if (darkTheme) OkkGreenLight else OkkGreen,
            isDark = darkTheme
        )

        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            shapes = OkkShapes,
            typography = OkkTypography
        ) {
            MiuixTheme(controller = controller) {
                CompositionLocalProvider(
                    LocalContentColor provides MiuixTheme.colorScheme.onBackground
                ) {
                    content()
                }
            }
        }
    }
}
