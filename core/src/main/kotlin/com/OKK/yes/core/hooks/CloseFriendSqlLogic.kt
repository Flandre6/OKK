package com.OKK.yes.core.hooks

/**
 * 密友功能 SQL 改写纯逻辑层（无 Android UI 依赖，可直接在纯 JVM 单测运行）。
 */
object CloseFriendSqlLogic {

    private val SQL_KEYWORDS = setOf(
        "where", "order", "group", "limit", "left", "right", "inner", "join", "on", ",", ";"
    )

    private val SINGLE_USER_WHERE_REGEX = Regex("""(?i)\b(?:rconversation\.)?username\s*=\s*[?'"]""")

    /**
     * 判断是否为微信主页会话列表展示查询。
     *
     * 关键约束：
     * 1. 必须针对主页会话展示（包含 conversationtime 与 unreadcount/flag/digest 等关键列）；
     * 2. 绝对不能拦截单会话详情查询（如 WHERE username = ?），否则会导致微信 ConversationStorage.isPlacedTop
     *    内部调用 p(username) 返回 null，从而导致置顶联系人/群聊被自动取消置顶！
     * 3. 排除嵌套文件夹、盒子容器与聚合计数。
     */
    fun looksLikeConversationListQuery(sql: String, lower: String = sql.lowercase()): Boolean {
        if (!lower.trimStart().startsWith("select")) return false
        if (!lower.contains("from rconversation") && !lower.contains("from  rconversation")) return false
        if (lower.contains("wekit_folder_") || lower.contains("conversationboxservice")) return false

        // 排除单人/单会话精准定位查询（单会话详情绝对不能过滤，否则导致微信 isPlacedTop 置顶判断全部失效）
        // 注意：使用正则区分参数/字面值赋值与联表条件，不能误伤 "where rconversation.username = rcontact.username" 这类 JOIN 条件
        if (SINGLE_USER_WHERE_REGEX.containsMatchIn(sql)) {
            return false
        }

        // 排除聚合统计计数
        val trimmed = lower.trimStart()
        if (trimmed.startsWith("select count(") || trimmed.startsWith("select distinct count(") ||
            trimmed.startsWith("select sum(") || trimmed.startsWith("select max(")
        ) {
            return false
        }

        // 必须具备主页会话展示的关键字段：conversationtime，以及 unreadcount / flag / digest
        return lower.contains("conversationtime") &&
            (lower.contains("unreadcount") || lower.contains("flag") || lower.contains("digest"))
    }

    /**
     * 动态解析 SQL 中的表别名（例如 FROM rconversation c 或 JOIN rconversation AS c）。
     */
    fun extractTableAlias(sql: String, tableName: String): String {
        val lower = sql.lowercase()
        val patterns = listOf("from $tableName", "join $tableName")
        for (pattern in patterns) {
            val idx = lower.indexOf(pattern)
            if (idx >= 0) {
                val after = sql.substring(idx + pattern.length).trimStart()
                val tokens = after.split(Regex("\\s+"))
                if (tokens.isNotEmpty()) {
                    val first = tokens[0].lowercase()
                    if (first == "as" && tokens.size > 1) {
                        val candidate = tokens[1].replace(",", "").replace(";", "").trim()
                        if (candidate.isNotBlank() && candidate !in SQL_KEYWORDS) {
                            return candidate
                        }
                    } else if (first !in SQL_KEYWORDS) {
                        val candidate = first.replace(",", "").replace(";", "").trim()
                        if (candidate.isNotBlank()) {
                            return candidate
                        }
                    }
                }
            }
        }
        return tableName
    }

    /**
     * 在 ORDER BY / GROUP BY / LIMIT 之前把条件 AND 进 WHERE。
     * 保证 ORDER BY flag desc, conversationTime desc 原样保留在 SQL 末尾，维持置顶会话顺序不变。
     */
    fun injectCondition(sql: String, condition: String): String? {
        val lower = sql.lowercase()
        val insertion = listOf(" order by ", " group by ", " limit ")
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        return if (insertion == null) {
            if (lower.contains(" where ")) "$sql AND $condition"
            else "$sql WHERE $condition"
        } else {
            val head = sql.substring(0, insertion)
            val tail = sql.substring(insertion)
            if (head.lowercase().contains("where")) "$head AND $condition$tail"
            else "$head WHERE $condition$tail"
        }
    }

    /**
     * 生成排除指定好友名单的 SQL 条件表达式。
     * 带上表别名前缀（如 rconversation.username 或 c.username），避免联表时发生 "ambiguous column name: username"。
     */
    fun buildExcludeCondition(ids: Set<String>, alias: String = "rconversation"): String? {
        if (ids.isEmpty()) return null
        val inList = ids.joinToString(",") { "'${it.replace("'", "''")}'" }
        val prefix = if (alias.isNotBlank()) "$alias." else ""
        return "${prefix}username NOT IN ($inList)"
    }
}
