// 微信主界面悬浮底栏（纯 Compose 三层解耦 + GPU 纯位移 + 果冻弹簧 + 0.97f 按压缩放反馈）

package com.OKK.yes.core.hooks.ui.wekit

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
class FloatingBottomBarColors(
    val containerColor: Color,
    val indicatorColor: Color,
    val contentColor: Color,
    val activeContentColor: Color
)

object FloatingBottomBarDefaults {
    @Composable
    fun colors(
        containerColor: Color = if (isSystemInDarkTheme()) Color(0xE6141414) else Color(0xE6F7F7F7),
        indicatorColor: Color = Color(0xFF07C160).copy(alpha = 0.18f),
        contentColor: Color = if (isSystemInDarkTheme()) Color(0xFF999999) else Color(0xFF555555),
        activeContentColor: Color = Color(0xFF07C160)
    ): FloatingBottomBarColors = FloatingBottomBarColors(
        containerColor = containerColor,
        indicatorColor = indicatorColor,
        contentColor = contentColor,
        activeContentColor = activeContentColor
    )
}

private val PillShape = CircleShape
private val PillHeight = 64.dp
private val IndicatorHeight = 44.dp
private const val SLIDER_WIDTH_RATIO = 0.82f

/**
 * 悬浮底栏子项包装容器：
 * 提供 0.97f 按压瞬时缩放物理反馈（120ms tween），
 * 搭配无边界水波或纯色微反馈，零侵入包裹任意图标与文字。
 */
@Composable
fun RowScope.FloatingBottomBarItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 按压缩放物理反馈
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        animationSpec = tween(durationMillis = 120),
        label = "itemPressScale"
    )

    Box(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            content = content
        )
    }
}

/**
 * 微信主界面悬浮胶囊底栏核心容器（三层解耦 + GPU 变换 + 果冻弹簧）：
 *
 * 1. 胶囊主壳：外层 8dp 浮岛阴影 + 90% 磨砂底色 + 1dp 轮廓描边；
 * 2. 滑块指示层（底层）：使用 graphicsLayer translationX 进行 GPU 变换，零重组零测量；
 *    滑动状态跟随手势 1:1 响应，切换态切入 LowBouncy 弹簧回弹；
 * 3. 内容层（顶层）：声明在滑块层之后，天然置顶于滑块之上，图标文字通透呈现。
 */
@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    tabsCount: Int,
    backdrop: Any? = null,
    onTabReselected: (index: Int) -> Unit = onSelected,
    onTabReselectedLongPress: (index: Int) -> Unit = {},
    progress: (() -> Float)? = null,
    isTracking: (() -> Boolean)? = null,
    isBlurEnabled: Boolean = false,
    blurRadius: Dp = 4.dp,
    colors: FloatingBottomBarColors = FloatingBottomBarDefaults.colors(),
    content: @Composable RowScope.() -> Unit
) {
    val density = LocalDensity.current
    val isInDark = isSystemInDarkTheme()

    val tabPositions = remember {
        mutableStateListOf<Float>().apply { repeat(tabsCount) { add(0f) } }
    }
    var tabWidthPx by remember { mutableFloatStateOf(0f) }

    val currentSelected = selectedIndex()
    val isFingerTracking = isTracking?.invoke() ?: false

    // 目标 X 坐标：若正在拖拽且有 progress 驱动，按连续浮点线性推导，否则取目标 Tab 位置
    val targetIndicatorX = run {
        if (progress != null && isFingerTracking) {
            val p = progress().coerceIn(0f, (tabsCount - 1).toFloat())
            val base = p.toInt()
            val fraction = p - base
            val start = tabPositions.getOrNull(base) ?: 0f
            val end = tabPositions.getOrNull((base + 1).coerceAtMost(tabsCount - 1)) ?: start
            start + (end - start) * fraction
        } else {
            tabPositions.getOrNull(currentSelected) ?: 0f
        }
    }

    // 果冻弹簧动画：手指拖拽跟踪时加速贴合，点选释放时果冻惯性回弹
    val animatedIndicatorX by animateFloatAsState(
        targetValue = targetIndicatorX,
        animationSpec = if (isFingerTracking) {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh
            )
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy, // 0.35f 明显微过冲回弹
                stiffness = Spring.StiffnessLow              // 200f 慢而柔和
            )
        },
        label = "wekitIndicatorOffset"
    )

    val strokeColor = if (isInDark) Color(0x33FFFFFF) else Color(0x1F000000)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(PillHeight),
        contentAlignment = Alignment.Center
    ) {
        // ===== 胶囊主壳 =====
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
                .background(colors.containerColor.copy(alpha = 0.90f))
                .border(
                    width = 1.dp,
                    color = strokeColor,
                    shape = PillShape
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            // ===== ① 滑块层（先声明，画在底层，GPU 纯位移）=====
            if (tabWidthPx > 0f) {
                val sliderWidthDp = with(density) { (tabWidthPx * SLIDER_WIDTH_RATIO).toDp() }
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = animatedIndicatorX
                        }
                        .size(width = sliderWidthDp, height = IndicatorHeight)
                        .clip(CircleShape)
                        .background(colors.indicatorColor)
                )
            }

            // ===== ② 内容层（后声明，画在顶层）=====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .onGloballyPositioned { coords ->
                        val totalW = coords.size.width.toFloat()
                        val singleW = (totalW / tabsCount).coerceAtLeast(0f)
                        if (tabWidthPx != singleW) {
                            tabWidthPx = singleW
                        }
                        val indicatorW = singleW * SLIDER_WIDTH_RATIO
                        val centerPadding = (singleW - indicatorW) / 2f
                        for (i in 0 until tabsCount) {
                            val startX = i * singleW + centerPadding
                            if (i < tabPositions.size && tabPositions[i] != startX) {
                                tabPositions[i] = startX
                            }
                        }
                    },
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}
