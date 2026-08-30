// 悬浮胶囊底部导航栏（实时跟手滑动 Pill + 图标缩放高亮 + 毛玻璃精致美化）

package com.OKK.yes.loader.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

private val PillShape = RoundedCornerShape(32.dp)
private val InnerPillShape = RoundedCornerShape(26.dp)
private val PillHeight = 64.dp

@Composable
fun FloatingBottomBar(
    current: MainTab,
    positionProgress: Float,
    onSelect: (MainTab) -> Unit
) {
    val containerBg = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)
    val strokeColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    // 外层固定高度 + 柔和胶囊 + 细腻双层高光边框与影深
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(PillHeight)
            .shadow(elevation = 16.dp, shape = PillShape, clip = false)
            .clip(PillShape)
            .background(containerBg)
            .border(BorderStroke(1.dp, strokeColor), PillShape)
    ) {
        BoxWithConstraints(modifier = Modifier.matchParentSize()) {
            val tabCount = MainTab.entries.size
            val tabWidth = maxWidth / tabCount

            // 像素级跟手滑动 Pill 指示块（随 Pager 拖拽/翻页无延迟偏移）
            Box(
                modifier = Modifier
                    .offset(x = tabWidth * positionProgress.coerceIn(0f, (tabCount - 1).toFloat()))
                    .width(tabWidth)
                    .padding(5.dp)
                    .height(PillHeight - 10.dp)
                    .clip(InnerPillShape)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f))
            )

            // Tab 内容项
            Row(
                modifier = Modifier.matchParentSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MainTab.entries.forEach { tab ->
                    val active = tab == current
                    val scale by animateFloatAsState(
                        targetValue = if (active) 1.12f else 1.0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f),
                        label = "tabScale"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(PillHeight - 10.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(tab) }
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val icon = tab.icon()
                        Box(
                            modifier = Modifier.scale(scale),
                            contentAlignment = Alignment.Center
                        ) {
                            Crossfade(
                                targetState = active,
                                animationSpec = tween(180),
                                label = "navIcon"
                            ) { selected ->
                                Icon(
                                    imageVector = if (selected) icon.filled else icon.outlined,
                                    contentDescription = tab.label,
                                    tint = if (active) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Text(
                            text = tab.label,
                            color = if (active) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
