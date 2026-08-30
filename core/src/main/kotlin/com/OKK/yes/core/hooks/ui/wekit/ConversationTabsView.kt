package com.OKK.yes.core.hooks.ui.wekit

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.OKK.yes.core.hooks.ConversationGroupConfig
import com.OKK.yes.core.hooks.ConversationGroupConfig.ChatGroup
import com.OKK.yes.core.hooks.ui.wekit.theme.WkInjectedTheme
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Swap_vert
import kotlinx.coroutines.launch

/**
 * 对应 WeKit 风格的 Compose 消息会话分组 Tab 控件（1:1 WeKit 实现）。
 */
class ConversationTabsView(
    context: Context,
    private val onTabSelected: (String) -> Unit,
    private val onCreateGroup: () -> Unit,
    private val onPresetSettings: () -> Unit,
    private val onEditGroup: (ChatGroup) -> Unit,
    private val onDeleteGroup: (ChatGroup) -> Unit,
) : android.widget.FrameLayout(context) {

    private val lifecycleOwner = XposedLifecycleOwner.create()

    private val groupsState = mutableStateOf(ConversationGroupConfig.loadGroups())
    private val selectedGroupIdState = mutableStateOf(ConversationGroupConfig.selectedGroupId())

    init {
        val cv = ComposeView(context).apply {
            setWkLifecycleOwner(lifecycleOwner)
            setContent {
                WkInjectedTheme {
                    var groups by groupsState
                    var selectedId by selectedGroupIdState

                    val containerColor = if (isSystemInDarkTheme()) Color(0xFF111111) else Color(0xFFF4F5F4)
                    ConversationTabs(
                        groups = groups,
                        selectedGroupId = selectedId,
                        onTabSelected = { id ->
                            selectedId = id
                            onTabSelected(id)
                        },
                        onCreateGroup = onCreateGroup,
                        onPresetSettings = onPresetSettings,
                        onEditGroup = onEditGroup,
                        onDeleteGroup = onDeleteGroup,
                        containerColor = containerColor,
                        onReorder = { newOrderIds ->
                            val current = ConversationGroupConfig.loadGroups()
                            val byId = current.associateBy { it.id }
                            val reordered = newOrderIds.mapNotNull { byId[it] }
                            val missing = current.filterNot { it.id in newOrderIds }
                            val full = reordered + missing
                            ConversationGroupConfig.saveOrder(full)
                            groups = ConversationGroupConfig.loadGroups()
                        }
                    )
                }
            }
        }
        addView(cv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun refreshState() {
        groupsState.value = ConversationGroupConfig.loadGroups()
        selectedGroupIdState.value = ConversationGroupConfig.selectedGroupId()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching {
            var p: Any? = parent
            while (p is android.view.View) {
                p.setWkLifecycleOwner(lifecycleOwner)
                p = p.parent
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTabs(
    groups: List<ChatGroup>,
    selectedGroupId: String,
    onTabSelected: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onPresetSettings: () -> Unit,
    onEditGroup: (ChatGroup) -> Unit,
    onDeleteGroup: (ChatGroup) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
) {
    var menuForGroupId by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf(groups.map { it.id }) }

    LaunchedEffect(groups, sortMode) {
        if (!sortMode) order = groups.map { it.id }
    }

    val orderedGroups = remember(order, groups) {
        val byId = groups.associateBy { it.id }
        order.mapNotNull { byId[it] }
    }

    val count = orderedGroups.size
    val useCenter = count <= 5

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
    ) {
        if (sortMode) {
            SortableTabsRow(
                groups = orderedGroups,
                onMove = { from, to ->
                    order = order.toMutableList().apply { add(to, removeAt(from)) }
                }
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = if (useCenter) Arrangement.Center else Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(orderedGroups, key = { it.id }) { group ->
                    val isAllTab = group.id == ConversationGroupConfig.ID_ALL
                    Box(modifier = if (useCenter) Modifier.padding(horizontal = 4.dp) else Modifier) {
                        GroupTab(
                            label = group.name,
                            selected = selectedGroupId == group.id,
                            onClick = { onTabSelected(group.id) },
                            onLongClick = { menuForGroupId = group.id }
                        )

                        DropdownMenu(
                            expanded = menuForGroupId == group.id,
                            onDismissRequest = { menuForGroupId = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建分组") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Add,
                                        contentDescription = "新建分组",
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    menuForGroupId = null
                                    onCreateGroup()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("预设标签") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Check,
                                        contentDescription = "预设标签",
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    menuForGroupId = null
                                    onPresetSettings()
                                }
                            )
                            if (!isAllTab) {
                                DropdownMenuItem(
                                    text = { Text("编辑") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Edit,
                                            contentDescription = "编辑",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        menuForGroupId = null
                                        onEditGroup(group)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("排序") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Swap_vert,
                                        contentDescription = "排序",
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    menuForGroupId = null
                                    order = groups.map { it.id }
                                    sortMode = true
                                }
                            )
                            if (!isAllTab) {
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Delete,
                                            contentDescription = "删除",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        menuForGroupId = null
                                        onDeleteGroup(group)
                                    }
                                )
                            }
                        }
                    }
                }

            }
        }

        if (sortMode) {
            val buttonBg = if (isSystemInDarkTheme()) Color(0xFF2C2C2C) else Color(0xFFEDEDED)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .background(buttonBg, CircleShape)
            ) {
                IconButton(
                    onClick = {
                        onReorder(order)
                        sortMode = false
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors()
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Check,
                        contentDescription = "保存排序",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isInDark = isSystemInDarkTheme()
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        if (isInDark) Color(0xFF2C2C2C) else Color(0xFFE2E4E0)
    }
    val contentColor = if (selected) {
        Color.White
    } else {
        if (isInDark) Color(0xFFCCCCCC) else Color(0xFF333333)
    }

    Surface(
        color = backgroundColor,
        contentColor = contentColor,
        shape = CircleShape
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 18.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SortableTabsRow(
    groups: List<ChatGroup>,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var draggingIndex by remember { mutableIntStateOf(-1) }
    var initialOffset by remember { mutableIntStateOf(0) }
    var draggedDelta by remember { mutableFloatStateOf(0f) }

    var settleIndex by remember { mutableIntStateOf(-1) }
    val settleAnim = remember { Animatable(0f) }

    fun offsetForIndex(index: Int): Float {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            ?: return 0f
        return initialOffset + draggedDelta - item.offset
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            offset.x.toInt() in it.offset..it.offset + it.size
                        }
                        if (hit != null) {
                            draggingIndex = hit.index
                            initialOffset = hit.offset
                            draggedDelta = 0f
                        }
                    },
                    onDragEnd = {
                        val landed = draggingIndex
                        val from = offsetForIndex(landed)
                        draggingIndex = -1
                        if (landed >= 0) scope.launch {
                            settleIndex = landed
                            settleAnim.snapTo(from)
                            settleAnim.animateTo(
                                0f,
                                spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                            settleIndex = -1
                        }
                    },
                    onDragCancel = { draggingIndex = -1 },
                    onDrag = { change, amount ->
                        change.consume()
                        if (draggingIndex < 0) return@detectDragGesturesAfterLongPress
                        draggedDelta += amount.x
                        val info = listState.layoutInfo
                        val cur = info.visibleItemsInfo.firstOrNull { it.index == draggingIndex }
                            ?: return@detectDragGesturesAfterLongPress
                        val center = (cur.offset + offsetForIndex(draggingIndex) + cur.size / 2f).toInt()
                        val target = info.visibleItemsInfo.firstOrNull { other ->
                            other.index != draggingIndex && center in other.offset..other.offset + other.size
                        }
                        if (target != null) {
                            onMove(draggingIndex, target.index)
                            draggingIndex = target.index
                        }
                        val edge = 72
                        when {
                            center < info.viewportStartOffset + edge && listState.canScrollBackward ->
                                scope.launch { listState.scrollBy(-28f) }

                            center > info.viewportEndOffset - edge && listState.canScrollForward ->
                                scope.launch { listState.scrollBy(28f) }
                        }
                    }
                )
            },
        contentPadding = PaddingValues(start = 12.dp, end = 72.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(groups.size, key = { groups[it].id }) { index ->
            val group = groups[index]
            val dragging = index == draggingIndex
            val settling = index == settleIndex

            JiggleTab(
                label = group.name,
                dragging = dragging || settling,
                phaseIndex = index,
                dragOffsetX = {
                    when {
                        dragging -> offsetForIndex(index)
                        settling -> settleAnim.value
                        else -> 0f
                    }
                },
                modifier = Modifier
                    .zIndex(if (dragging || settling) 1f else 0f)
                    .then(if (dragging || settling) Modifier else Modifier.animateItem())
            )
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { if (draggingIndex >= 0) draggedDelta else Float.NaN }.collect { delta ->
            if (delta.isNaN()) return@collect
            val info = listState.layoutInfo
            val cur = info.visibleItemsInfo.firstOrNull { it.index == draggingIndex } ?: return@collect
            val center = cur.offset + offsetForIndex(draggingIndex) + cur.size / 2f
            val edge = 64
            when {
                center < info.viewportStartOffset + edge && listState.canScrollBackward ->
                    scope.launch { listState.scrollBy(-12f) }

                center > info.viewportEndOffset - edge && listState.canScrollForward ->
                    scope.launch { listState.scrollBy(12f) }
            }
        }
    }
}

@Composable
private fun JiggleTab(
    label: String,
    dragging: Boolean,
    phaseIndex: Int,
    dragOffsetX: () -> Float,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "jiggle")
    val rotation by infiniteTransition.animateFloat(
        initialValue = -1.8f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 140, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = androidx.compose.animation.core.StartOffset(offsetMillis = (phaseIndex * 35) % 140)
        ),
        label = "rotation"
    )

    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dragging) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scale"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = CircleShape,
        modifier = modifier
            .graphicsLayer {
                translationX = dragOffsetX()
                scaleX = scale
                scaleY = scale
                rotationZ = if (dragging) 0f else rotation
            }
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .padding(horizontal = 16.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
