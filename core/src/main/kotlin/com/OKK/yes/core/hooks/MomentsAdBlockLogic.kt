package com.OKK.yes.core.hooks

/**
 * 朋友圈去广告纯逻辑层（可单测）。
 *
 * 拦截本地 SQLite 中已缓存的 AdSnsInfo 查询：
 * - 微信时间线通过 `SnsInfoStorage.getAdCursorForTimeLine` 执行带有 `UNION ... from AdSnsInfo where ...` 的查询；
 * - 以及单独的 `select *,rowid from AdSnsInfo where ...` 查询；
 * 将所有的 `FROM AdSnsInfo WHERE` 改写为 `FROM AdSnsInfo WHERE (1=0) AND`，
 * 使本地数据库直接返回 0 行广告记录。
 */
object MomentsAdBlockLogic {
    private val AD_SNS_INFO_WHERE_REGEX = Regex("(?i)\\bfrom\\s+AdSnsInfo\\s+where\\b\\s*")
    private val AD_SNS_INFO_PLAIN_REGEX = Regex("(?i)\\bfrom\\s+AdSnsInfo\\b")

    fun rewriteSql(sql: String?): String? {
        if (sql.isNullOrBlank()) return null
        if (!sql.contains("AdSnsInfo", ignoreCase = true)) return null

        var next = sql
        var changed = false
        if (AD_SNS_INFO_WHERE_REGEX.containsMatchIn(next)) {
            next = AD_SNS_INFO_WHERE_REGEX.replace(next, "FROM AdSnsInfo WHERE (1=0) AND ")
            changed = true
        } else if (AD_SNS_INFO_PLAIN_REGEX.containsMatchIn(next)) {
            next = AD_SNS_INFO_PLAIN_REGEX.replace(next, "FROM (SELECT * FROM AdSnsInfo WHERE 1=0) AS AdSnsInfo")
            changed = true
        }
        return if (changed) next else null
    }
}
