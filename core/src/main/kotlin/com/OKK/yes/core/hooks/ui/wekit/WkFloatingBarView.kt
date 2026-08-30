package com.OKK.yes.core.hooks.ui.wekit

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.OKK.yes.core.hooks.BottomTabConfig
import com.OKK.yes.core.hooks.ui.wekit.theme.WkInjectedTheme
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Explore
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlinedfilled.Contacts
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Person
import kotlin.math.floor

/**
 * WeKit 悬浮底栏的 OKK 桥接控制器：
 * FrameLayout 包裹 ComposeView，对外暴露与 ReplaceNavigationBar 1:1 状态机调配接口，
 * 内部渲染 1:1 照搬的 WeKit FloatingBottomBar（玻璃 pill + 拖拽弹簧 + 按压膨胀 + 视差折射）。
 */
@SuppressLint("ViewConstructor")
class WkFloatingBarView(
    context: Context,
    private val pagerView: View?,
    private val onTabClick: (Int) -> Unit,
    private val onTabReselect: (Int) -> Unit,
    private val onDiscoverLongPress: () -> Unit,
) : FrameLayout(context) {

    @Stable
    private data class NavItem(val outlined: ImageVector, val filled: ImageVector)

    private val navItems = listOf(
        NavItem(MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home),
        NavItem(MaterialSymbols.Outlined.Contacts, MaterialSymbols.OutlinedFilled.Contacts),
        NavItem(MaterialSymbols.Outlined.Explore, MaterialSymbols.OutlinedFilled.Explore),
        NavItem(MaterialSymbols.Outlined.Person, MaterialSymbols.OutlinedFilled.Person),
    )

    // ── WeKit 5-State 引擎（1:1 对标 ReplaceNavigationBar 的状态流动）──
    private val selectedIndexState = mutableIntStateOf(0)   // pager 实时 position
    private val scrollOffsetState = mutableFloatStateOf(0f) // 0..1 滑动 offset
    private val targetIndexState = mutableIntStateOf(0)     // 弹簧动画目标页
    private val settledIndexState = mutableIntStateOf(0)    // 图标实心/空心选中态
    private val isSwipingState = mutableStateOf(false)     // 是否手指拖拽中
    private var pageDidDrag = false

    // ── 未读角标 ──
    private val mainUnreadState = mutableIntStateOf(0)
    private val contactUnreadState = mutableIntStateOf(0)
    private val contactDotState = mutableStateOf(false)
    private val friendUnreadState = mutableIntStateOf(0)
    private val friendDotState = mutableStateOf(false)

    // ── 标签配置 ──
    private val labelsState = mutableStateOf(BottomTabConfig.DEFAULT_LABELS)
    private val showLabelsState = mutableStateOf(true)
    private val showBadgeState = mutableStateOf(true)

    @Volatile
    var isBarHidden: Boolean = false
        private set

    private val lifecycleOwner = XposedLifecycleOwner.create()

    init {
        val cv = ComposeView(context)
        cv.setWkLifecycleOwner(lifecycleOwner)
        cv.setContent { BarContent() }
        addView(cv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        clipChildren = false
        clipToPadding = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching {
            var p: Any? = parent
            while (p is View) {
                p.setWkLifecycleOwner(lifecycleOwner)
                p = p.parent
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  1:1 WeKit 状态同步接口（tabsAdapter 回调驱动）
    // ══════════════════════════════════════════════════════

    /** 1:1 对应 WeKit onPageScrolled */
    fun onPageScrolled(position: Int, positionOffset: Float) {
        selectedIndexState.intValue = position
        scrollOffsetState.floatValue = positionOffset
        if (positionOffset == 0f) {
            settledIndexState.intValue = position
        }
    }

    /** 1:1 对应 WeKit onPageSelected */
    fun onPageSelected(position: Int) {
        targetIndexState.intValue = position
        showBar(animated = true)
    }

    /** 1:1 对应 WeKit onPageScrollStateChanged */
    fun onPageScrollStateChanged(state: Int) {
        when (state) {
            1 -> { // DRAGGING: 手指拖拽 ViewPager
                pageDidDrag = true
                isSwipingState.value = true
            }
            2 -> { // SETTLING: 仅当拖拽引发的 settle 才保持 tracking
                isSwipingState.value = pageDidDrag
            }
            else -> { // IDLE
                isSwipingState.value = false
                pageDidDrag = false
            }
        }
    }

    // 兼容层 API
    fun setScrollProgress(progress: Float) {
        val pos = floor(progress).toInt().coerceIn(0, navItems.size - 1)
        onPageScrolled(pos, (progress - pos).coerceIn(0f, 1f))
    }

    fun setSelectedIndex(index: Int, animate: Boolean) {
        onPageSelected(index)
        if (!animate) onPageScrolled(index, 0f)
    }

    fun setSwiping(swiping: Boolean) {
        isSwipingState.value = swiping
    }

    fun showBar(animated: Boolean) {
        if (!isBarHidden) return
        isBarHidden = false
        if (animated) {
            animate().cancel()
            animate().translationY(0f).setDuration(220L).start()
        } else {
            animate().cancel()
            translationY = 0f
        }
    }

    fun hideBar(animated: Boolean) {
        if (isBarHidden) return
        isBarHidden = true
        val dy = (if (height > 0) height.toFloat() else dp(100f)) + dp(50f)
        if (animated) {
            animate().cancel()
            animate().translationY(dy).setDuration(220L).start()
        } else {
            animate().cancel()
            translationY = dy
        }
    }

    fun setMainUnread(count: Int) { mainUnreadState.intValue = count.coerceAtLeast(0) }
    fun setContactUnread(count: Int) { contactUnreadState.intValue = count.coerceAtLeast(0) }
    fun setContactDot(show: Boolean) { contactDotState.value = show }
    fun setFriendUnread(count: Int) { friendUnreadState.intValue = count.coerceAtLeast(0) }
    fun setFriendDot(show: Boolean) { friendDotState.value = show }

    fun updateLabels(labels: List<String>, showLabels: Boolean, showBadge: Boolean) {
        labelsState.value = labels
        showLabelsState.value = showLabels
        showBadgeState.value = showBadge
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ══════════════════════════════════════════════════════
    //  Compose 内容（1:1 照搬 ReplaceNavigationBar 的 floating 分支）
    // ══════════════════════════════════════════════════════

    @Composable
    private fun BarContent() {
        WkInjectedTheme {
            val isInDark = isSystemInDarkTheme()
            val backgroundColor = if (isInDark) Color(0xFF141414) else Color(0xFFF7F7F7)
            val activeColor = Color(0xFF07C160)
            val inactiveColor = if (isInDark) Color(0xFF999999) else Color(0xFF2C2C2C)
            val showLabels by showLabelsState
            val labels by labelsState

            Box(modifier = Modifier.fillMaxWidth()) {
                FloatingBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(bottom = 12.dp),
                    selectedIndex = { targetIndexState.intValue },
                    progress = { selectedIndexState.intValue + scrollOffsetState.floatValue },
                    isTracking = { isSwipingState.value },
                    onSelected = { onTabClick(it) },
                    onTabReselected = { index ->
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onTabReselect(index)
                    },
                    onTabReselectedLongPress = { index ->
                        if (index == 2) onDiscoverLongPress()
                    },
                    backdrop = pagerView?.let { rememberViewBackdrop(it) } ?: rememberLayerBackdrop(),
                    tabsCount = navItems.size,
                    isBlurEnabled = pagerView != null,
                    blurRadius = 4.dp,
                    colors = FloatingBottomBarDefaults.colors(
                        containerColor = backgroundColor,
                        indicatorColor = activeColor,
                        contentColor = inactiveColor,
                        activeContentColor = activeColor,
                    ),
                ) {
                    navItems.forEachIndexed { index, item ->
                        val isSelected = index == settledIndexState.intValue

                        FloatingBottomBarItem(
                            onClick = {
                                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                onTabClick(index)
                            },
                            modifier = Modifier
                                .then(
                                    if (index == 2) {
                                        Modifier.onLongPress {
                                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            onDiscoverLongPress()
                                        }
                                    } else {
                                        Modifier
                                    }
                                )
                                .defaultMinSize(minWidth = 76.dp),
                        ) {
                            BadgedBox(badge = { BadgeSlot(index) }) {
                                Crossfade(
                                    targetState = isSelected,
                                    animationSpec = tween(200),
                                    label = "navIconFloating",
                                ) { selected ->
                                    Icon(
                                        imageVector = if (selected) item.filled else item.outlined,
                                        contentDescription = labels.getOrElse(index) { "" },
                                        tint = if (selected) activeColor else inactiveColor,
                                    )
                                }
                            }
                            if (showLabels) {
                                Text(
                                    text = labels.getOrElse(index) { "" },
                                    color = if (isSelected) activeColor else inactiveColor,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BadgeSlot(index: Int) {
        if (!showBadgeState.value) return
        when (index) {
            0 -> {
                val count by mainUnreadState
                if (count > 0) {
                    Badge(containerColor = Color(0xFFFF3B30)) {
                        Text(
                            if (count <= 99) count.toString() else "99+",
                            color = Color.White, fontSize = 10.sp,
                        )
                    }
                }
            }
            1 -> {
                val count by contactUnreadState
                val dot by contactDotState
                if (count > 0) {
                    Badge(containerColor = Color(0xFFFF3B30)) {
                        Text(
                            if (count <= 99) count.toString() else "99+",
                            color = Color.White, fontSize = 10.sp,
                        )
                    }
                } else if (dot) {
                    Badge(containerColor = Color(0xFFFF3B30))
                }
            }
            2 -> {
                val count by friendUnreadState
                val dot by friendDotState
                if (count > 0) {
                    Badge(containerColor = Color(0xFFFF3B30)) {
                        Text(
                            if (count <= 99) count.toString() else "99+",
                            color = Color.White, fontSize = 10.sp,
                        )
                    }
                } else if (dot) {
                    Badge(containerColor = Color(0xFFFF3B30))
                }
            }
        }
    }

    private fun Modifier.onLongPress(block: () -> Unit): Modifier = pointerInput(block) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            block()
        }
    }
}
