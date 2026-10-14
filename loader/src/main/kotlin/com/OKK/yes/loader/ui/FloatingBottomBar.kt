// 悬浮胶囊底部导航栏（GPU 纯位移滑块 + 果冻回弹 + 按压缩放反馈 + 纯 Compose 三层解耦）

package com.OKK.yes.loader.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Article
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlinedfilled.Article
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Tune
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 主页底部导航目标 */
enum class MainTab(val label: String) {
    Home("主页"),
    Features("功能"),
    Logs("日志"),
    Settings("设置")
}

private data class NavIcon(val outlined: ImageVector, val filled: ImageVector)

private fun MainTab.icon(): NavIcon = when (this) {
    MainTab.Home -> NavIcon(MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home)
    MainTab.Features -> NavIcon(MaterialSymbols.Outlined.Tune, MaterialSymbols.OutlinedFilled.Tune)
    MainTab.Logs -> NavIcon(MaterialSymbols.Outlined.Article, MaterialSymbols.OutlinedFilled.Article)
    MainTab.Settings -> NavIcon(MaterialSymbols.Outlined.Settings, MaterialSymbols.OutlinedFilled.Settings)
}

private val PillShape = CircleShape
private val PillHeight = 64.dp
private val IndicatorHeight = 44.dp
private const val SLIDER_WIDTH_RATIO = 0.82f

@Composable
fun FloatingBottomBar(
    current: MainTab,
    positionProgress: Float,
    onSelect: (MainTab) -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val tabs = MainTab.entries
    val tabCount = tabs.size

    val containerBg = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f)
    val strokeColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val indicatorColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)
    val activeTint = MiuixTheme.colorScheme.primary
    val inactiveTint = MiuixTheme.colorScheme.onSurfaceVariantSummary

    // 记录每个 Tab 的起始 X 坐标
    val tabPositions = remember {
        mutableStateListOf<Float>().apply { repeat(tabCount) { add(0f) } }
    }
    var tabWidthPx by remember { mutableFloatStateOf(0f) }

    // 目标 X 计算：支持与 Pager 滑动动态插值，或直接落到目标 Tab 中心
    val targetIndicatorX = run {
        val clampedProgress = positionProgress.coerceIn(0f, (tabCount - 1).toFloat())
        val baseIndex = clampedProgress.toInt()
        val fraction = clampedProgress - baseIndex
        val startX = tabPositions.getOrNull(baseIndex) ?: 0f
        val nextX = tabPositions.getOrNull((baseIndex + 1).coerceAtMost(tabCount - 1)) ?: startX
        startX + (nextX - startX) * fraction
    }

    // 果冻弹簧动画：低阻尼 + 柔和刚度
    val animatedIndicatorX by animateFloatAsState(
        targetValue = targetIndicatorX,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy, // 0.35f
            stiffness = Spring.StiffnessLow              // 200f
        ),
        label = "indicatorOffset"
    )

    // 外层：水平留边 + 阴影与手势层隔离
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(PillHeight),
        contentAlignment = Alignment.Center
    ) {
        // 胶囊主壳
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PillHeight)
                .shadow(
                    elevation = 8.dp,
                    shape = PillShape,
                    clip = false
                )
                .clip(PillShape)
                .background(containerBg)
                .border(
                    width = 1.dp,
                    color = strokeColor,
                    shape = PillShape
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            // ① 滑块层（底层：率先声明，GPU 纯变换）
            if (tabWidthPx > 0f) {
                val sliderWidthDp = with(density) { (tabWidthPx * SLIDER_WIDTH_RATIO).toDp() }
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = animatedIndicatorX
                        }
                        .size(width = sliderWidthDp, height = IndicatorHeight)
                        .clip(CircleShape)
                        .background(indicatorColor)
                )
            }

            // ② 内容层（顶层：后声明）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = tab == current
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    // 按压瞬时缩放反馈：120ms tween 极速响应
                    val pressScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.97f else 1.0f,
                        animationSpec = tween(durationMillis = 120),
                        label = "tabPressScale"
                    )

                    // 激活态图标微膨胀
                    val iconActiveScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "tabIconActiveScale"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .onGloballyPositioned { coordinates ->
                                val itemWidth = coordinates.size.width.toFloat()
                                val indicatorWidth = itemWidth * SLIDER_WIDTH_RATIO
                                val startOffset = coordinates.positionInParent().x +
                                        (itemWidth - indicatorWidth) / 2f

                                // 去抖比对，防止无限布局重组循环
                                if (tabWidthPx != itemWidth) {
                                    tabWidthPx = itemWidth
                                }
                                if (index < tabPositions.size && tabPositions[index] != startOffset) {
                                    tabPositions[index] = startOffset
                                }
                            }
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                if (tab != current) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onSelect(tab)
                                }
                            }
                            .graphicsLayer {
                                scaleX = pressScale
                                scaleY = pressScale
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val icon = tab.icon()
                            Box(
                                modifier = Modifier.graphicsLayer {
                                    scaleX = iconActiveScale
                                    scaleY = iconActiveScale
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Crossfade(
                                    targetState = isSelected,
                                    animationSpec = tween(180),
                                    label = "navIcon"
                                ) { selected ->
                                    Icon(
                                        imageVector = if (selected) icon.filled else icon.outlined,
                                        contentDescription = tab.label,
                                        tint = if (isSelected) activeTint else inactiveTint,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Text(
                                text = tab.label,
                                color = if (isSelected) activeTint else inactiveTint,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
