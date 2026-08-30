// Dialog 版屏幕栈导航器（优雅丝滑推入/滑出手势导航）
// 主界面层在下层（轻微视差 + 渐变暗色遮罩），顶部覆盖层平滑滑入与滑出。

package com.OKK.yes.loader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 导航目标。 */
sealed interface NavTarget {
    data class Category(val category: FeatureCategory) : NavTarget
}

private const val PARALLAX_RATIO = -0.18f
private const val MASK_ALPHA = 0.40f

@Composable
fun MiuixStackNavigator(
    stack: List<NavTarget>,
    pop: () -> Unit,
    background: @Composable () -> Unit,
    content: @Composable (NavTarget) -> Unit
) {
    val hasOverlay = stack.isNotEmpty()
    val corner = LocalConfiguration.current.screenWidthDp.dp * 0.04f

    // 记住最后一次非 null 的 target，确保退场动画播放时内容依然完整存在
    // 用 LaunchedEffect 记录，避免组合期间写状态导致重组抖动
    val lastTarget = remember { mutableStateOf<NavTarget?>(null) }
    androidx.compose.runtime.LaunchedEffect(stack) {
        stack.lastOrNull()?.let { lastTarget.value = it }
    }

    // 高品质软弹簧 spec
    val floatSpringSpec = spring<Float>(
        dampingRatio = 0.85f,
        stiffness = 400f
    )
    val offsetSpringSpec = spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = 0.85f,
        stiffness = 400f
    )

    // 1. 下层主页视差位移
    val parallax by animateFloatAsState(
        targetValue = if (hasOverlay) PARALLAX_RATIO else 0f,
        animationSpec = floatSpringSpec,
        label = "parallax"
    )

    // 2. 遮罩渐变
    val maskColor by animateColorAsState(
        targetValue = if (hasOverlay) Color.Black.copy(alpha = MASK_ALPHA) else Color.Transparent,
        animationSpec = tween(280),
        label = "mask"
    )

    Box(Modifier.fillMaxSize()) {
        // ── 下层主页面 ──
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = size.width * parallax
                }
        ) {
            background()
        }

        // ── 遮罩层 ──
        if (maskColor.alpha > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(maskColor)
            )
        }

        // ── 上层覆盖页进出动画 ──
        AnimatedVisibility(
            visible = hasOverlay,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = offsetSpringSpec
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = offsetSpringSpec
            )
        ) {
            val currentTarget = stack.lastOrNull() ?: lastTarget.value
            if (currentTarget != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .shadow(elevation = 16.dp, shape = RoundedCornerShape(corner), clip = false)
                        .clip(RoundedCornerShape(corner))
                        .background(MiuixTheme.colorScheme.background)
                ) {
                    content(currentTarget)
                }
            }
        }
    }
}
