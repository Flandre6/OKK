// 共享脚手架：MIUIX Scaffold + 毛玻璃折叠顶栏 + 虚拟化分组卡片（对齐 wcx）

package com.OKK.yes.loader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 页面统一底栏避让（悬浮胶囊高度 + 安全区） */
val ContentBottomInset = 108.dp

/**
 * MIUIX 列表页脚手架：毛玻璃折叠顶栏 + LazyColumn。
 * 对齐 wcx MiuixListScaffold：blur + vibrancy + layerBackdrop + scrollEndHaptic + overScroll。
 */
@Composable
fun MiuixListScaffold(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    spacing: Dp = 0.dp,
    content: LazyListScope.() -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier
                    .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.92f)),
                color = Color.Transparent,
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = { navigationIcon?.invoke() },
                actions = { actions?.invoke() },
            )
        },
        popupHost = {},
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(spacing),
            overscrollEffect = null,
            content = content,
        )
    }
}

@Composable
fun BackIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = MaterialSymbols.Outlined.Arrow_back,
            contentDescription = "返回",
            tint = MiuixTheme.colorScheme.onBackground
        )
    }
}

/**
 * 分组卡片虚拟化：每行独立 LazyColumn item，squircle 圆角按 index/count 分配。
 * 对齐 wcx groupedCardItem — 保持 LazyColumn 虚拟化，不把所有行塞进一个 Card。
 */
@Composable
fun Modifier.groupedCardItem(index: Int, count: Int): Modifier {
    val r = CardDefaults.CornerRadius
    val z = 0.dp
    val top = index == 0
    val bottom = index == count - 1
    return fillMaxWidth()
        .squircleSurface(
            color = MiuixTheme.colorScheme.surfaceContainer,
            topStart = if (top) r else z,
            topEnd = if (top) r else z,
            bottomEnd = if (bottom) r else z,
            bottomStart = if (bottom) r else z,
        )
}

/** 分类左侧小图标底 */
@Composable
fun CategoryIconBadge(
    icon: ImageVector,
    tint: Color = MiuixTheme.colorScheme.onBackground,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .padding(end = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

fun pageListBottomSpacer(extra: Dp = ContentBottomInset): PaddingValues =
    PaddingValues(bottom = extra)

/** 无列表时的空页背景 */
@Composable
fun PageBackground(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
    ) {
        content()
    }
}

/**
 * MIUIX 小标题（对齐 wcx MiuixSmallTitle）。
 * 用于分区标题：界面 / 调试 / 关于 / 重置 等。
 */
@Composable
fun MiuixSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MiuixTheme.colorScheme.onBackgroundVariant,
) {
    Text(
        modifier = modifier.padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = textColor,
    )
}
