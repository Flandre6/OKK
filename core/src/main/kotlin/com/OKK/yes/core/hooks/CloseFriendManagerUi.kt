package com.OKK.yes.core.hooks

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.OKK.yes.core.hooks.ContactDisplayNames.ContactItem
import com.OKK.yes.core.hooks.ui.wekit.ContactsSelectorContent
import com.OKK.yes.core.hooks.ui.wekit.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 密友名单管理 UI 入口（core 侧）。
 *
 * 优化特性：
 * 1. 仅限好友：彻底移除群聊、公众号与系统号，纯净展示好友；
 * 2. 毫秒级极速读取：单表直查 rcontact + 1分钟内存级快速缓存，打开瞬间即显；
 * 3. 复用 ContactsSelectorContent 精简版（隐藏非好友分类页签，保留多维排序与搜索）。
 */
object CloseFriendManagerUi {

    /**
     * 打开密友名单选择对话框。
     * @param onUpdated 名单保存后回调（设置页可用来刷新"当前 N 人"显示）。
     */
    fun showMemberSelector(ctx: Context, onUpdated: () -> Unit = {}) {
        showComposeDialog(ctx) {
            // 优先读取内存已缓存的好友列表，实现 0ms 瞬间打开
            val initialList = remember {
                val cached = ContactDisplayNames.getCachedFriendsIfPresent()
                if (cached != null && cached.isNotEmpty()) {
                    mergeWithSavedMembers(cached)
                } else {
                    emptyList()
                }
            }

            var allContacts by remember { mutableStateOf(initialList) }
            var loading by remember { mutableStateOf(initialList.isEmpty()) }

            LaunchedEffect(Unit) {
                val friends = withContext(Dispatchers.IO) {
                    val rawList = runCatching { ContactDisplayNames.queryFriendsOnlyFast() }
                        .getOrDefault(emptyList())
                    mergeWithSavedMembers(rawList)
                }
                allContacts = friends
                loading = false
            }

            if (loading) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "正在快速读取好友…",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                ContactsSelectorContent(
                    title = "管理密友名单",
                    allContacts = allContacts,
                    initialSelectedWxIds = CloseFriendStore.ids(),
                    showCategories = false, // 密友只需要好友，隐藏群聊与公众号分类
                    onDismiss = { onDismiss() },
                    onConfirm = { selected ->
                        val nameById = allContacts.associate { it.username to it.displayName }
                        val validSelected = selected.filter { ContactDisplayNames.isRealFriendUsername(it) }
                        CloseFriendStore.setMembers(
                            validSelected.map { id ->
                                CloseFriendStore.Member(id, nameById[id] ?: id)
                            }
                        )
                        onUpdated()
                        onDismiss()
                    }
                )
            }
        }
    }

    private fun mergeWithSavedMembers(rawList: List<ContactItem>): List<ContactItem> {
        val filtered = rawList.filter { ContactDisplayNames.isRealFriendUsername(it.username) }.toMutableList()
        val existingUsernames = filtered.map { it.username }.toSet()
        for (m in CloseFriendStore.members()) {
            if (ContactDisplayNames.isRealFriendUsername(m.username) && m.username !in existingUsernames) {
                val resolved = ContactDisplayNames.resolve(m.username)
                val name = when {
                    m.displayName.isNotBlank() && m.displayName != m.username -> m.displayName
                    !resolved.isNullOrEmpty() && resolved != "对方" -> resolved
                    else -> m.username
                }
                filtered.add(
                    ContactItem(
                        username = m.username,
                        displayName = name,
                        alias = "",
                        type = 1,
                        verifyFlag = 0,
                        encryptUsername = "",
                        conversationTime = 0L,
                        avatarUrl = ""
                    )
                )
            }
        }
        return filtered
    }
}
