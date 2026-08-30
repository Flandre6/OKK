package com.OKK.yes.core.hooks.ui.wekit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.OKK.yes.core.hooks.ContactDisplayNames.ContactItem
import com.OKK.yes.core.hooks.WeChatAvatarHelper
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Compare_arrows
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Sort_by_alpha
import com.composables.icons.materialsymbols.outlined.Swap_vert
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/** 分类 Filter 定义 */
private enum class SelectorCategory(val displayName: String, val icon: ImageVector) {
    ALL("全部", MaterialSymbols.Outlined.Search),
    FRIENDS("好友", MaterialSymbols.Outlined.Person),
    GROUPS("群聊", MaterialSymbols.Outlined.Groups),
    OFFICIALS("公众号", MaterialSymbols.Outlined.Chat)
}

/** 排序模式定义 */
private enum class SelectorSortMode(val displayName: String, val icon: ImageVector) {
    ALPHABETICAL("A-Z", MaterialSymbols.Outlined.Sort_by_alpha),
    LAST_MESSAGE_TIME("新-旧", MaterialSymbols.Outlined.Schedule)
}

/**
 * 微信头像 Compose 封装（绑定 WeChatAvatarHelper）
 */
@Composable
private fun ContactAvatar(username: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply {
                    cornerRadius = 6f * ctx.resources.displayMetrics.density
                    setColor(0x2007C160)
                }
            }
        },
        update = { iv ->
            WeChatAvatarHelper.bindAvatar(iv, username)
        },
        modifier = modifier
    )
}

/**
 * 1:1 对标 WeKit 样式的 Compose 联系人/对话选择弹窗界面。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ContactsSelectorContent(
    title: String,
    allContacts: List<ContactItem>,
    initialSelectedWxIds: Set<String>,
    showCategories: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedWxIds by remember { mutableStateOf(initialSelectedWxIds) }
    var filtersExpanded by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf(SelectorCategory.ALL) }
    var sortMode by remember { mutableStateOf(SelectorSortMode.LAST_MESSAGE_TIME) }
    var sortReversed by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }

    // 各分类统计数据
    val categoryCounts = remember(allContacts) {
        val friends = allContacts.count { !it.username.endsWith("@chatroom") && !it.username.startsWith("gh_") }
        val groups = allContacts.count { it.username.endsWith("@chatroom") }
        val officials = allContacts.count { it.username.startsWith("gh_") }
        mapOf(
            SelectorCategory.ALL to allContacts.size,
            SelectorCategory.FRIENDS to friends,
            SelectorCategory.GROUPS to groups,
            SelectorCategory.OFFICIALS to officials
        )
    }

    // 搜索与 Filter 过滤
    val filteredContacts = remember(allContacts, searchQuery, selectedCategory, showCategories) {
        allContacts.filter { item ->
            val matchesCategory = if (!showCategories) true else when (selectedCategory) {
                SelectorCategory.ALL -> true
                SelectorCategory.FRIENDS -> !item.username.endsWith("@chatroom") && !item.username.startsWith("gh_")
                SelectorCategory.GROUPS -> item.username.endsWith("@chatroom")
                SelectorCategory.OFFICIALS -> item.username.startsWith("gh_")
            }
            val matchesSearch = searchQuery.isBlank() ||
                    item.displayName.contains(searchQuery, ignoreCase = true) ||
                    item.username.contains(searchQuery, ignoreCase = true) ||
                    item.alias.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    // 预排序：仅依赖过滤结果与排序模式/方向，避免切换选中时重复执行重量级 Collator 排序（大量联系人时卡顿）。
    val sortedContacts = remember(filteredContacts, sortMode, sortReversed) {
        if (sortMode == SelectorSortMode.ALPHABETICAL) {
            filteredContacts.sortedWith { c1, c2 ->
                val r = chinaCollator.compare(c1.displayName, c2.displayName)
                if (sortReversed) -r else r
            }
        } else {
            // 新-旧：保持数据源顺序（默认即按最近对话倒序），反转时逆序
            if (sortReversed) filteredContacts.reversed() else filteredContacts
        }
    }

    // 分组：仅在选中集合变化时做 O(n) partition/分组，不再触发重排序。
    val groupedContacts = remember(sortedContacts, selectedWxIds) {
        val (selected, rest) = sortedContacts.partition { it.username in selectedWxIds }
        if (sortMode == SelectorSortMode.ALPHABETICAL) {
            val grouped = rest.groupBy { item ->
                val name = item.displayName.trim()
                if (name.isEmpty()) "#"
                else {
                    val first = name.first().uppercaseChar()
                    if (first in 'A'..'Z') first.toString() else "#"
                }
            }.toSortedMap { k1, k2 ->
                when {
                    k1 == k2 -> 0
                    k1 == "#" -> 1
                    k2 == "#" -> -1
                    else -> if (sortReversed) k2.compareTo(k1) else k1.compareTo(k2)
                }
            }
            linkedMapOf<String, List<ContactItem>>().apply {
                if (selected.isNotEmpty()) put("已选", selected)
                putAll(grouped)
            }
        } else {
            val header = if (sortReversed) "旧-新" else "新-旧"
            linkedMapOf<String, List<ContactItem>>().apply {
                if (selected.isNotEmpty()) put("已选", selected)
                if (rest.isNotEmpty()) put(header, rest)
            }
        }
    }

    // 索引映射
    val sectionIndices = remember(groupedContacts) {
        val map = mutableMapOf<String, Int>()
        var flatIndex = 0
        groupedContacts.forEach { (header, list) ->
            map[header] = flatIndex
            flatIndex += 1 + list.size
        }
        map
    }

    val darkTheme = isSystemInDarkTheme()
    val cardBg = if (darkTheme) Color(0xFF1E1F24) else Color.White
    val chipSelectedBg = if (darkTheme) Color(0xFF344579) else Color(0xFFDBE3FB)
    val chipSelectedContent = if (darkTheme) Color(0xFFE6EEFF) else Color(0xFF344579)

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardBg,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // 顶栏 Header
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 搜索行 + 折叠 Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(if (!showCategories) "搜索好友昵称或微信号" else "搜索昵称或微信号", fontSize = 13.5.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Search,
                            contentDescription = "Search",
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = { filtersExpanded = !filtersExpanded }) {
                    Icon(
                        imageVector = if (filtersExpanded) MaterialSymbols.Outlined.Expand_less else MaterialSymbols.Outlined.Expand_more,
                        contentDescription = if (filtersExpanded) "折叠" else "展开"
                    )
                }
            }

            // 可折叠 Filter 工具栏
            AnimatedVisibility(
                visible = filtersExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    // Row 1: 分类 Chips（如果仅选好友则隐藏分类栏）
                    if (showCategories) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(SelectorCategory.entries) { category ->
                                val isSel = selectedCategory == category
                                val count = categoryCounts[category] ?: 0
                                FilterChip(
                                    selected = isSel,
                                    onClick = { selectedCategory = category },
                                    label = { Text("${category.displayName} ($count)", fontSize = 12.5.sp) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = category.icon,
                                            contentDescription = category.displayName,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = chipSelectedBg,
                                        selectedLabelColor = chipSelectedContent,
                                        selectedLeadingIconColor = chipSelectedContent
                                    )
                                )
                            }
                        }
                    }

                    // Row 2: 排序 Chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                    ) {
                        SelectorSortMode.entries.forEach { mode ->
                            val isSel = sortMode == mode
                            FilterChip(
                                selected = isSel,
                                onClick = { sortMode = mode },
                                label = {
                                    val labelTxt = if (mode == SelectorSortMode.LAST_MESSAGE_TIME) {
                                        if (sortReversed) "旧-新" else "新-旧"
                                    } else {
                                        if (sortReversed) "Z-A" else "A-Z"
                                    }
                                    Text(labelTxt, fontSize = 12.5.sp)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = mode.icon,
                                        contentDescription = mode.displayName,
                                        modifier = Modifier.size(15.dp)
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = chipSelectedBg,
                                    selectedLabelColor = chipSelectedContent,
                                    selectedLeadingIconColor = chipSelectedContent
                                )
                            )
                        }

                        FilterChip(
                            selected = sortReversed,
                            onClick = { sortReversed = !sortReversed },
                            label = {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Swap_vert,
                                    contentDescription = "反转方向",
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipSelectedBg,
                                selectedLeadingIconColor = chipSelectedContent
                            )
                        )
                    }

                    // Row 3: 批量选中 Chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val currentFilteredUsernames = filteredContacts.map { it.username }
                                selectedWxIds = selectedWxIds + currentFilteredUsernames
                            },
                            label = { Text("全选", fontSize = 12.5.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Select_all,
                                    contentDescription = "全选",
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        )

                        FilterChip(
                            selected = false,
                            onClick = {
                                val currentFilteredUsernames = filteredContacts.map { it.username }.toSet()
                                selectedWxIds = selectedWxIds - currentFilteredUsernames
                            },
                            label = { Text("全不选", fontSize = 12.5.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Deselect,
                                    contentDescription = "全不选",
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        )

                        FilterChip(
                            selected = false,
                            onClick = {
                                val currentFilteredUsernames = filteredContacts.map { it.username }.toSet()
                                val newSel = selectedWxIds.toMutableSet()
                                for (uname in currentFilteredUsernames) {
                                    if (uname in newSel) newSel.remove(uname) else newSel.add(uname)
                                }
                                selectedWxIds = newSel
                            },
                            label = { Text("反选", fontSize = 12.5.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Compare_arrows,
                                    contentDescription = "反选",
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            // 联系人列表 + 右侧 Alphabet Index Bar
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    groupedContacts.forEach { (header, itemsInGroup) ->
                        stickyHeader(key = "header_$header") {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = if (darkTheme) Color(0xFF26282E) else Color(0xFFF3F3F5)
                            ) {
                                Text(
                                    text = header,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        items(
                            items = itemsInGroup,
                            key = { it.username }
                        ) { contact ->
                            val isChecked = contact.username in selectedWxIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedWxIds = if (isChecked) {
                                            selectedWxIds - contact.username
                                        } else {
                                            selectedWxIds + contact.username
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                ContactAvatar(
                                    username = contact.username,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = contact.displayName,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 14.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val subTxt = contact.alias.ifBlank { contact.username }
                                    Text(
                                        text = subTxt,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 侧边字母快速跳转栏
                if (sortMode == SelectorSortMode.ALPHABETICAL) {
                    val alphabet = remember { listOf("已选") + ('A'..'Z').map { it.toString() } + "#" }
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(start = 4.dp, end = 2.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        alphabet.forEach { letter ->
                            val hasKey = sectionIndices.containsKey(letter)
                            Text(
                                text = if (letter == "已选") "✓" else letter,
                                fontSize = 10.5.sp,
                                fontWeight = if (hasKey) FontWeight.Bold else FontWeight.Normal,
                                color = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier
                                    .clickable(enabled = hasKey) {
                                        sectionIndices[letter]?.let { idx ->
                                            coroutineScope.launch { listState.scrollToItem(idx) }
                                        }
                                    }
                                    .padding(vertical = 1.5.dp, horizontal = 2.dp)
                            )
                        }
                    }
                }
            }

            // 底部 Action 按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = { onConfirm(selectedWxIds) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3A82F6),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "确定 (${selectedWxIds.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
