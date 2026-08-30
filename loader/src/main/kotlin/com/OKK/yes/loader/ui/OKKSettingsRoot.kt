// OKK 设置根：主界面 Pager 始终组合 + 悬浮底栏 + 分类下钻覆盖层（对齐 wcx MainPagerScreen + MiuixStackNavigator）

package com.OKK.yes.loader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置主界面。主界面 Pager 始终组合在下层（下钻时视差偏移 + 暗化），
 * 分类下钻作为覆盖层从右侧滑入（squircle 圆角 + 阴影）。
 */
@Composable
fun OKKSettingsRoot(
    onExitRoot: () -> Unit,
    onDarkChange: (Boolean) -> Unit = {},
    initialTab: MainTab = MainTab.Home
) {
    val stack = remember { mutableStateListOf<NavTarget>() }
    var featuresQuery by remember { mutableStateOf("") }
    var lastStackPopTime by remember { mutableStateOf(0L) }

    // 绝对防越界：在设置 Dialog 打开期间永远启用 BackHandler！
    // 拦截任何系统的侧滑手势或物理按键，逐级消费，决不误关 Dialog。
    // 结合 lastStackPopTime 800ms 安全窗口，彻底解决 MIUI/ColorOS 手势完结后延迟 450ms 补发的伪 onBackPressed 导致的误关。
    BackHandler(enabled = true) {
        val now = android.os.SystemClock.uptimeMillis()
        when {
            stack.isNotEmpty() -> {
                lastStackPopTime = now
                stack.removeAt(stack.lastIndex)
            }
            featuresQuery.isNotBlank() -> {
                featuresQuery = ""
            }
            else -> {
                if (now - lastStackPopTime > 800L) {
                    onExitRoot()
                }
            }
        }
    }

    val pagerState = rememberPagerState(
        initialPage = initialTab.ordinal.coerceIn(0, MainTab.entries.lastIndex),
        pageCount = { MainTab.entries.size }
    )
    val scope = rememberCoroutineScope()
    val currentTab by remember {
        derivedStateOf {
            MainTab.entries[pagerState.currentPage.coerceIn(0, MainTab.entries.lastIndex)]
        }
    }
    val positionProgress by remember {
        derivedStateOf {
            pagerState.currentPage + pagerState.currentPageOffsetFraction
        }
    }

    // 主界面内容（Pager + 底栏）——始终组合
    val mainContent: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = true
            ) { pageIdx ->
                when (MainTab.entries.getOrNull(pageIdx) ?: MainTab.Home) {
                    MainTab.Home -> HomePager()
                    MainTab.Features -> FeaturesPager(
                        query = featuresQuery,
                        onQueryChange = { featuresQuery = it },
                        onOpenCategory = { stack.add(NavTarget.Category(it)) }
                    )
                    MainTab.Logs -> LogsPager()
                    MainTab.Settings -> SettingsPager(onDarkChange = onDarkChange)
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .zIndex(1f)
            ) {
                FloatingBottomBar(
                    current = currentTab,
                    positionProgress = positionProgress,
                    onSelect = { tab ->
                        scope.launch {
                            runCatching { pagerState.animateScrollToPage(tab.ordinal) }
                        }
                    }
                )
            }
        }
    }

    MiuixStackNavigator(
        stack = stack.toList(),
        pop = {
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        },
        background = mainContent,
        content = { target ->
            when (target) {
                is NavTarget.Category -> CategoryDetailScreen(
                    category = target.category,
                    onBack = {
                        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    }
                )
            }
        }
    )
}
