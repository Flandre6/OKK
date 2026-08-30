package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 密友名单存储与状态管理。
 *
 * 功能特性：
 *  - 名单以 JSON 数组持久化在 PublicConfigStore 的 close_friend_list 键；
 *  - 五个开关：close_friend_enabled（总开关）、close_friend_hide_conversation（主页会话）、
 *    close_friend_hide_contact（通讯录）、close_friend_hide_sns（朋友圈）、close_friend_hide_search（全局搜索）；
 *  - 临时显现状态机（isTempUnhidden）：长按标题中心一键临时显现，倒计时结束自动重新隐藏；
 *  - 密友详情与未读聚合查询（queryCloseFriendDetails）：聚合 rconversation 与 rcontact 数据；
 *  - 快速跳转聊天（openChatting）与查看资料页（openContactInfo）。
 */
object CloseFriendStore {

    const val KEY_ENABLED = "close_friend_enabled"
    const val KEY_HIDE_CONVERSATION = "close_friend_hide_conversation"
    const val KEY_HIDE_CONTACT = "close_friend_hide_contact"
    const val KEY_HIDE_SNS = "close_friend_hide_sns"
    const val KEY_HIDE_SEARCH = "close_friend_hide_search"
    const val KEY_LIST = "close_friend_list"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 临时显现状态机 */
    @Volatile private var tempUnhidden = false
    @Volatile private var tempUnhideExpiryTime = 0L
    private val tempUnhideRunnable = Runnable {
        if (tempUnhidden && System.currentTimeMillis() >= tempUnhideExpiryTime) {
            setTempUnhidden(false)
        }
    }

    /** 临时显现状态监听器 */
    private val tempUnhideListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addTempUnhideListener(listener: (Boolean) -> Unit) {
        tempUnhideListeners.add(listener)
    }

    fun removeTempUnhideListener(listener: (Boolean) -> Unit) {
        tempUnhideListeners.remove(listener)
    }

    /** 是否处于临时显现状态 */
    fun isTempUnhidden(): Boolean {
        if (!tempUnhidden) return false
        if (System.currentTimeMillis() >= tempUnhideExpiryTime) {
            tempUnhidden = false
            notifyTempUnhideChanged(false)
            return false
        }
        return true
    }

    /** 获取临时显现剩余秒数 */
    fun getTempUnhideRemainingSeconds(): Long {
        if (!tempUnhidden) return 0L
        val remain = (tempUnhideExpiryTime - System.currentTimeMillis()) / 1000L
        return if (remain > 0) remain else 0L
    }

    /** 切换临时显现模式（默认 5 分钟） */
    fun setTempUnhidden(enabled: Boolean, durationMs: Long = 5 * 60 * 1000L) {
        tempUnhidden = enabled
        mainHandler.removeCallbacks(tempUnhideRunnable)
        if (enabled) {
            tempUnhideExpiryTime = System.currentTimeMillis() + durationMs
            mainHandler.postDelayed(tempUnhideRunnable, durationMs)
        } else {
            tempUnhideExpiryTime = 0L
        }
        notifyTempUnhideChanged(enabled)
        // 触发列表即时热刷新
        mainHandler.post {
            runCatching { ConversationGroupingHook.forceReloadConversations() }
        }
    }

    private fun notifyTempUnhideChanged(enabled: Boolean) {
        tempUnhideListeners.forEach { runCatching { it(enabled) } }
    }

    /** 单个密友成员 */
    data class Member(val username: String, val displayName: String)

    /** 密友详情（含未读数与最新消息） */
    data class CloseFriendDetail(
        val username: String,
        val displayName: String,
        val unreadCount: Int = 0,
        val digest: String = "",
        val conversationTime: Long = 0L,
        val avatarUrl: String = ""
    )

    // ── 开关 ──

    fun isEnabled(): Boolean =
        PublicConfigStore.getBoolean(KEY_ENABLED, false)

    fun setEnabled(v: Boolean) =
        PublicConfigStore.putBoolean(KEY_ENABLED, v, async = false)

    /** 主页会话隐藏生效 = 总开关开 且 子开关开 且 非临时显现 */
    fun hideConversation(): Boolean {
        if (isTempUnhidden()) return false
        return isEnabled() && PublicConfigStore.getBoolean(KEY_HIDE_CONVERSATION, true)
    }

    /** 通讯录隐藏生效 = 总开关开 且 子开关开 且 非临时显现 */
    fun hideContact(): Boolean {
        if (isTempUnhidden()) return false
        return isEnabled() && PublicConfigStore.getBoolean(KEY_HIDE_CONTACT, true)
    }

    /** 朋友圈隐藏生效 = 总开关开 且 子开关开 且 非临时显现 */
    fun hideSns(): Boolean {
        if (isTempUnhidden()) return false
        return isEnabled() && PublicConfigStore.getBoolean(KEY_HIDE_SNS, true)
    }

    /** 搜索隐藏生效 = 总开关开 且 子开关开 且 非临时显现 */
    fun hideSearch(): Boolean {
        if (isTempUnhidden()) return false
        return isEnabled() && PublicConfigStore.getBoolean(KEY_HIDE_SEARCH, true)
    }

    // ── 名单 ──

    fun members(): List<Member> {
        val raw = PublicConfigStore.getString(KEY_LIST, "")
        if (raw.isBlank()) return emptyList()
        return parseMembers(raw).filter { ContactDisplayNames.isRealFriendUsername(it.username) }
    }

    fun ids(): Set<String> = members().mapTo(LinkedHashSet()) { it.username }

    fun contains(username: String?): Boolean =
        !username.isNullOrBlank() && ContactDisplayNames.isRealFriendUsername(username) && username in ids()

    fun setMembers(list: List<Member>) {
        val arr = org.json.JSONArray()
        list.distinctBy { it.username }
            .filter { ContactDisplayNames.isRealFriendUsername(it.username) }
            .forEach { m ->
                arr.put(org.json.JSONObject().apply {
                    put("username", m.username)
                    put("displayName", m.displayName)
                })
            }
        PublicConfigStore.putPersisted(KEY_LIST, arr.toString())
    }

    fun add(username: String, displayName: String) {
        if (username.isBlank() || contains(username)) return
        setMembers(members() + Member(username, displayName))
    }

    fun remove(username: String) {
        setMembers(members().filter { it.username != username })
    }

    // ── 密友未读与聊天摘要聚合查询 ──

    fun queryCloseFriendDetails(): List<CloseFriendDetail> {
        val currentMembers = members()
        if (currentMembers.isEmpty()) return emptyList()

        val memberMap = currentMembers.associateBy { it.username }
        val idListStr = currentMembers.joinToString(",") { "'" + it.username.replace("'", "''") + "'" }

        val sql = """
            SELECT 
                r.username,
                IFNULL(r.conRemark, '') AS conRemark,
                IFNULL(r.nickname, '') AS nickname,
                IFNULL(r.alias, '') AS alias,
                IFNULL(c.unReadCount, 0) AS unReadCount,
                IFNULL(c.digest, '') AS digest,
                IFNULL(c.conversationTime, 0) AS conversationTime,
                IFNULL(i.reserved2, '') AS avatarUrl
            FROM rcontact r
            LEFT JOIN rconversation c ON r.username = c.username
            LEFT JOIN img_flag i ON r.username = i.username
            WHERE r.username IN ($idListStr)
        """.trimIndent()

        val rows = ContactDisplayNames.execRawQuery(sql)
        val details = ArrayList<CloseFriendDetail>()

        for (row in rows) {
            val username = row["username"]?.toString() ?: continue
            val conRemark = row["conRemark"]?.toString().orEmpty().trim()
            val nickname = row["nickname"]?.toString().orEmpty().trim()
            val alias = row["alias"]?.toString().orEmpty().trim()
            val storedName = memberMap[username]?.displayName.orEmpty()

            // 优先备注 > 昵称 > 别名 > 历史存储名 > ContactDisplayNames 解析 > 兜底
            val resolvedName = ContactDisplayNames.resolve(username)
            val displayName = when {
                conRemark.isNotEmpty() -> conRemark
                nickname.isNotEmpty() -> nickname
                alias.isNotEmpty() -> alias
                !storedName.isNullOrEmpty() && storedName != username -> storedName
                !resolvedName.isNullOrEmpty() && resolvedName != "对方" -> resolvedName
                else -> username
            }

            val unreadCount = row["unReadCount"]?.toString()?.toIntOrNull() ?: 0
            val digest = row["digest"]?.toString().orEmpty()
            val conversationTime = row["conversationTime"]?.toString()?.toLongOrNull() ?: 0L
            val avatarUrl = row["avatarUrl"]?.toString().orEmpty()

            details.add(
                CloseFriendDetail(
                    username = username,
                    displayName = displayName,
                    unreadCount = unreadCount,
                    digest = digest,
                    conversationTime = conversationTime,
                    avatarUrl = avatarUrl
                )
            )
        }

        // 兜底补齐数据库未查到的成员（如未入库的临时密友）
        val queriedIds = details.map { it.username }.toSet()
        for (m in currentMembers) {
            if (m.username !in queriedIds) {
                val resolvedName = ContactDisplayNames.resolve(m.username)
                val finalName = when {
                    m.displayName.isNotBlank() && m.displayName != m.username -> m.displayName
                    !resolvedName.isNullOrEmpty() && resolvedName != "对方" -> resolvedName
                    else -> m.username
                }
                details.add(
                    CloseFriendDetail(
                        username = m.username,
                        displayName = finalName
                    )
                )
            }
        }

        // 按未读数优先、再按最近会话时间倒序排列
        return details.sortedWith(
            compareByDescending<CloseFriendDetail> { it.unreadCount > 0 }
                .thenByDescending { it.conversationTime }
        )
    }

    // ── 快捷跳转 ──

    /** 打开与指定密友的微信聊天窗口 */
    fun openChatting(context: Context, username: String) {
        runCatching {
            val intent = Intent().apply {
                setClassName(context, "com.tencent.mm.ui.chatting.ChattingUI")
                putExtra("Chat_User", username)
                putExtra("finish_direct", true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            if (context is Activity) {
                runCatching {
                    XposedHelpers.callMethod(context, "startChatting", username, null, true)
                }
            }
        }
    }

    /** 打开指定密友的微信资料页 */
    fun openContactInfo(context: Context, username: String) {
        runCatching {
            val intent = Intent().apply {
                setClassName(context, "com.tencent.mm.plugin.profile.ui.ContactInfoUI")
                putExtra("Contact_User", username)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    // ── SQL 谓词 ──

    /**
     * 生成主页会话隐藏的 SQL 排除谓词：username NOT IN ('a','b')。
     */
    fun buildConversationExcludePredicate(): String? {
        if (!hideConversation()) return null
        val ids = ids()
        if (ids.isEmpty()) return null
        val inList = ids.joinToString(",") { "'${it.replace("'", "''")}'" }
        return "username NOT IN ($inList)"
    }

    private fun parseMembers(raw: String): List<Member> = runCatching {
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val username = o.optString("username").trim()
            if (username.isEmpty()) null
            else Member(username, o.optString("displayName"))
        }
    }.getOrDefault(emptyList())
}
