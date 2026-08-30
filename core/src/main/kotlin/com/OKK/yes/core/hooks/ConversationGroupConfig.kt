package com.OKK.yes.core.hooks

import com.OKK.yes.core.hooks.PublicConfigStore

/**
 * 会话分组配置模型与 SQL 谓词生成。
 *
 * 旧版 OKK 原生分组方案核心：分组列表持久化在 achat_config.properties 的
 * conversation_groups 键，构建 / 编辑分组时通过 [buildPredicate] 生成注入到
 * 微信首页 rconversation 查询的 WHERE 谓词。
 */
object ConversationGroupConfig {

    // ── 预设分组 ID ──
    const val ID_ALL = "all"
    const val ID_UNREAD = "preset_unread"
    const val ID_FRIENDS = "preset_friends"
    const val ID_GROUPS = "preset_groups"
    const val ID_SERVICES = "preset_services"
    const val ID_OFFICIALS = "preset_officials"
    const val ID_OPENIM = "preset_openim"

    const val KEY_GROUPS = "conversation_groups"
    const val KEY_ORDER = "conversation_group_order"
    const val KEY_SELECTED = "conversation_group_selected"
    const val KEY_ENABLED = "conversation_grouping_enabled"

    /** 未读数统计模式 */
    enum class UnreadMode { NORMAL, ALL, MUTED, NONE }

    /**
     * 单个分组的完整数据模型。
     * parentId 支持多级嵌套（HChat 兼容）；auto* 为自动归拢规则。
     */
    data class ChatGroup(
        val id: String,
        val name: String,
        val members: List<String> = emptyList(),
        val order: Int = 0,
        val avatarPath: String = "",
        val unreadCountMode: String = "NORMAL",
        val showUnreadCount: Boolean = true,
        val previewLatestMessage: Boolean = true,
        val roundAvatar: Boolean = false,
        val showEmpty: Boolean = true,
        val autoEnable: Boolean = false,
        val autoAllGroups: Boolean = false,
        val autoNewGroups: Boolean = false,
        val autoMutedGroups: Boolean = false,
        val autoOfficialAccounts: Boolean = false,
        val autoEnterpriseGroups: Boolean = false,
        val autoGroupLabelIds: List<Long> = emptyList(),
        val parentId: String? = null,
        val type: String = "MANUAL",
        val whereClause: String = "",
    ) {
        fun isPreset(): Boolean = id in PRESET_IDS
    }

    val PRESET_IDS = setOf(
        ID_ALL, ID_UNREAD, ID_FRIENDS, ID_GROUPS, ID_SERVICES, ID_OFFICIALS, ID_OPENIM
    )

    /** 预设标签信息结构 */
    data class PresetTagInfo(
        val id: String,
        val name: String,
        val desc: String,
        val defaultEnabled: Boolean = true,
        val canToggle: Boolean = true
    )

    /** 所有预设标签列表 */
    val ALL_PRESET_TAGS = listOf(
        PresetTagInfo(ID_ALL, "全部", "显示所有聊天会话", defaultEnabled = true, canToggle = false),
        PresetTagInfo(ID_FRIENDS, "好友", "已添加的好友单聊", defaultEnabled = true),
        PresetTagInfo(ID_GROUPS, "群聊", "所有微信群聊", defaultEnabled = true),
        PresetTagInfo(ID_SERVICES, "服务号", "认证服务号消息", defaultEnabled = true),
        PresetTagInfo(ID_OFFICIALS, "公众号", "订阅号与公众号", defaultEnabled = true),
        PresetTagInfo(ID_UNREAD, "未读", "包含未读消息的会话", defaultEnabled = false),
        PresetTagInfo(ID_OPENIM, "企业微信", "企业微信个人与群会话", defaultEnabled = false)
    )

    /** 判断预设标签是否开启 */
    fun isPresetEnabled(id: String): Boolean {
        if (id == ID_ALL) return true
        val tag = ALL_PRESET_TAGS.firstOrNull { it.id == id } ?: return true
        return PublicConfigStore.getBoolean("preset_enabled_$id", tag.defaultEnabled)
    }

    /** 设置预设标签开启状态 */
    fun setPresetEnabled(id: String, enabled: Boolean) {
        if (id == ID_ALL) return
        PublicConfigStore.putBoolean("preset_enabled_$id", enabled, async = false)
    }

    /** 默认预设分组列表（按开关过滤，顺序即 tab 顺序） */
    fun presetGroups(): List<ChatGroup> = listOf(
        ChatGroup(ID_ALL, "全部"),
        ChatGroup(ID_FRIENDS, "好友", type = "PRESET_FRIENDS"),
        ChatGroup(ID_GROUPS, "群聊", type = "PRESET_GROUPS"),
        ChatGroup(ID_SERVICES, "服务号", type = "PRESET_SERVICES"),
        ChatGroup(ID_OFFICIALS, "公众号", type = "PRESET_OFFICIALS"),
        ChatGroup(ID_UNREAD, "未读", type = "PRESET_UNREAD"),
        ChatGroup(ID_OPENIM, "企业微信", type = "PRESET_OPENIM")
    ).filter { isPresetEnabled(it.id) }

    /** 当前分组列表（预设 + 自定义），按 order 排序 */
    fun loadGroups(): List<ChatGroup> {
        val raw = PublicConfigStore.getString(KEY_GROUPS, "")
        val custom = if (raw.isBlank()) emptyList<ChatGroup>() else parseGroups(raw)
        // 过滤清理非法测试数据（如名为 "1" 且无成员的脏数据）
        val validCustom = custom.filter { it.name.trim() != "1" || it.members.isNotEmpty() }
        val orderRaw = PublicConfigStore.getString(KEY_ORDER, "")
        val order = orderRaw.split(",").filter { it.isNotBlank() }
        val all = presetGroups() + validCustom
        return if (order.isEmpty()) all else {
            val byId = all.associateBy { it.id }
            order.mapNotNull { byId[it] } + all.filter { it.id !in order }
        }
    }

    /** 解析 conversation_groups JSON 字符串 */
    fun parseGroups(raw: String): List<ChatGroup> {
        return runCatching {
            org.json.JSONArray(raw).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    parseGroup(arr.getJSONObject(i))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseGroup(o: org.json.JSONObject): ChatGroup {
        fun jstr(k: String): String = runCatching { o.getString(k) }.getOrDefault(o.optString(k))
        fun jbool(k: String): Boolean = o.optBoolean(k)
        fun jint(k: String): Int = o.optInt(k)
        fun jlongs(k: String): List<Long> = runCatching {
            val a = o.getJSONArray(k); (0 until a.length()).map { a.getLong(it) }
        }.getOrDefault(emptyList())
        val members = runCatching {
            val a = o.getJSONArray("members")
            (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        return ChatGroup(
            id = jstr("id"),
            name = jstr("name"),
            members = members,
            order = jint("order"),
            avatarPath = jstr("avatarPath"),
            unreadCountMode = jstr("unreadCountMode"),
            showUnreadCount = jbool("showUnreadCount"),
            previewLatestMessage = jbool("previewLatestMessage"),
            roundAvatar = jbool("roundAvatar"),
            showEmpty = jbool("showEmpty"),
            autoEnable = jbool("autoEnable"),
            autoAllGroups = jbool("autoAllGroups"),
            autoNewGroups = jbool("autoNewGroups"),
            autoMutedGroups = jbool("autoMutedGroups"),
            autoOfficialAccounts = jbool("autoOfficialAccounts"),
            autoEnterpriseGroups = jbool("autoEnterpriseGroups"),
            autoGroupLabelIds = jlongs("autoGroupLabelIds"),
            parentId = o.optString("parentId").takeIf { it.isNotBlank() },
            type = jstr("type").ifBlank { "MANUAL" },
            whereClause = jstr("whereClause"),
        )
    }

    /** 序列化单个分组为 JSON 对象字符串 */
    fun toJson(group: ChatGroup): String {
        val o = org.json.JSONObject()
        o.put("id", group.id)
        o.put("name", group.name)
        o.put("members", org.json.JSONArray(group.members))
        o.put("order", group.order)
        o.put("avatarPath", group.avatarPath)
        o.put("unreadCountMode", group.unreadCountMode)
        o.put("showUnreadCount", group.showUnreadCount)
        o.put("previewLatestMessage", group.previewLatestMessage)
        o.put("roundAvatar", group.roundAvatar)
        o.put("showEmpty", group.showEmpty)
        o.put("autoEnable", group.autoEnable)
        o.put("autoAllGroups", group.autoAllGroups)
        o.put("autoNewGroups", group.autoNewGroups)
        o.put("autoMutedGroups", group.autoMutedGroups)
        o.put("autoOfficialAccounts", group.autoOfficialAccounts)
        o.put("autoEnterpriseGroups", group.autoEnterpriseGroups)
        o.put("autoGroupLabelIds", org.json.JSONArray(group.autoGroupLabelIds))
        group.parentId?.let { o.put("parentId", it) }
        o.put("type", group.type)
        o.put("whereClause", group.whereClause)
        return o.toString()
    }

    /** 保存自定义分组列表（不覆盖预设） */
    fun saveGroups(custom: List<ChatGroup>) {
        val arr = org.json.JSONArray()
        custom.forEach { arr.put(org.json.JSONObject(toJson(it))) }
        PublicConfigStore.put(KEY_GROUPS, arr.toString(), async = false)
    }

    /** 保存分组顺序（含预设），同步写盘 */
    fun saveOrder(groups: List<ChatGroup>) {
        val order = groups.joinToString(",") { it.id }
        PublicConfigStore.put(KEY_ORDER, order, async = false)
    }

    /** 当前选中分组 id */
    fun selectedGroupId(): String {
        val s = PublicConfigStore.getString(KEY_SELECTED, ID_ALL)
        return if (s.isBlank()) ID_ALL else s
    }

    fun setSelectedGroupId(id: String) {
        PublicConfigStore.put(KEY_SELECTED, id, async = false)
    }

    fun isEnabled(): Boolean = PublicConfigStore.getBoolean(KEY_ENABLED, true)

    /**
     * 根据分组生成注入到 rconversation 查询的 WHERE 谓词。
     * 全部(ID_ALL)返回 null 表示不改写查询。
     */
    fun buildPredicate(group: ChatGroup): String? {
        if (group.id == ID_ALL) return null
        return when (group.id) {
            ID_UNREAD -> "(rconversation.unReadCount > 0 OR rconversation.unReadMuteCount > 0)"
            // 好友：排除群聊、企业微信、公众号服务号，允许普通好友及消息折叠
            ID_FRIENDS -> "(rconversation.username NOT LIKE 'gh_%' AND rconversation.username!='officialaccounts' AND rconversation.username!='service_officialaccounts' AND rconversation.username NOT LIKE '%@chatroom' AND rconversation.username NOT LIKE '%@im.chatroom' AND rconversation.username NOT LIKE '%@openim' AND (rconversation.parentRef IS NULL OR rconversation.parentRef='' OR rconversation.parentRef='message_fold' OR rconversation.parentRef='conversationboxservice'))"
            // 群聊：所有 @chatroom 群
            ID_GROUPS -> "(rconversation.username LIKE '%@chatroom')"
            // 服务号：service_officialaccounts 文件夹或 bizinfo 类型为服务号的账号
            ID_SERVICES -> "(rconversation.parentRef = 'service_officialaccounts' OR rconversation.username IN (SELECT username FROM bizinfo WHERE username LIKE 'gh_%' AND (type = 1 OR type = 251658241)))"
            // 公众号：officialaccounts 订阅号文件夹或 bizinfo 类型为订阅号(type=0)的账号
            ID_OFFICIALS -> "(rconversation.parentRef = 'officialaccounts' OR rconversation.username IN (SELECT username FROM bizinfo WHERE username LIKE 'gh_%' AND type = 0))"
            // 企业微信：企业微信单聊与群聊
            ID_OPENIM -> "(rconversation.username LIKE '%@openim' OR rconversation.username LIKE '%@im.chatroom')"
            else -> buildCustomPredicate(group)
        }
    }

    /** 自定义分组谓词：手动成员 + 自动归拢规则 */
    private fun buildCustomPredicate(group: ChatGroup): String {
        when (group.type) {
            "PRESET_UNREAD" -> return "(rconversation.unReadCount > 0 OR rconversation.unReadMuteCount > 0)"
            "PRESET_FRIENDS" -> return "(rconversation.username NOT LIKE 'gh_%' AND rconversation.username!='officialaccounts' AND rconversation.username!='service_officialaccounts' AND rconversation.username NOT LIKE '%@chatroom' AND rconversation.username NOT LIKE '%@im.chatroom' AND rconversation.username NOT LIKE '%@openim' AND (rconversation.parentRef IS NULL OR rconversation.parentRef='' OR rconversation.parentRef='message_fold' OR rconversation.parentRef='conversationboxservice'))"
            "PRESET_GROUPS" -> return "(rconversation.username LIKE '%@chatroom')"
            "PRESET_SERVICES" -> return "(rconversation.parentRef = 'service_officialaccounts' OR rconversation.username IN (SELECT username FROM bizinfo WHERE username LIKE 'gh_%' AND (type = 1 OR type = 251658241)))"
            "PRESET_OFFICIALS" -> return "(rconversation.parentRef = 'officialaccounts' OR rconversation.username IN (SELECT username FROM bizinfo WHERE username LIKE 'gh_%' AND type = 0))"
            "PRESET_OPENIM" -> return "(rconversation.username LIKE '%@openim' OR rconversation.username LIKE '%@im.chatroom')"
            "SQL" -> if (group.whereClause.isNotBlank()) return "(${group.whereClause})"
        }
        val clauses = mutableListOf<String>()
        if (group.members.isNotEmpty()) {
            val inList = group.members.joinToString(",") { "'${it.replace("'", "''")}'" }
            clauses.add("rconversation.username IN ($inList)")
        }
        if (group.autoAllGroups) {
            clauses.add("rconversation.username LIKE '%@chatroom'")
        }
        if (group.autoOfficialAccounts) {
            clauses.add("rconversation.parentRef = 'officialaccounts' OR rconversation.username IN (SELECT username FROM bizinfo WHERE username LIKE 'gh_%' AND type = 0)")
        }
        if (clauses.isEmpty()) return "1=1"
        return clauses.joinToString(" OR ") { "($it)" }
    }
    fun groupById(id: String): ChatGroup? = loadGroups().firstOrNull { it.id == id }
}