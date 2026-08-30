package com.OKK.yes.core.hooks

/**
 * 朋友圈防删除纯逻辑层（可单测）。
 *
 * 微信通过 [SnsInfo.sourceType] 位标记控制时间线可见性：
 * - bit1 (`sourceType & 2`)：出现在朋友圈时间线
 * - 删除时常见：`sourceType = 0`，或 `sourceType = sourceType & -3` 清掉 bit1
 * - 查询时常见：`WHERE (sourceType & 2 != 0)` 过滤已删除
 *
 * 策略：
 * 1. update 时若 sourceType 被写成 0，改回 2，并给正文加「[已删除]」前缀
 * 2. rawQuery 去掉 sourceType 可见性过滤，让已删除仍能查出
 * 3. 拦截清 bit1 的 execSQL
 */
object AntiMomentsDeleteLogic {
    const val SNS_TABLE = "SnsInfo"
    const val VISIBLE_SOURCE_TYPE = 2
    const val DELETED_PREFIX = "[已删除]"
    /** 旧版圆括号前缀，读时兼容并统一成方括号 */
    private const val LEGACY_DELETED_PREFIX = "(已删除)"

    /** 时间线可见性过滤（删除后不再满足） */
    private val SOURCE_TYPE_VISIBLE_FILTER = "(sourceType & 2 != 0 )  AND"

    /**
     * 微信 rawQuery 里常见的 sourceType in (...) 列表。
     * 在原列表前插入 0/2/4/6，保证被改写为 0 或 2 的动态仍可被查出。
     */
    private val SOURCE_TYPE_IN_LIST =
        "(sourceType in (8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,72,328,74,330,76,332,78,334,88,344,90,346,92,348,94,350,136,392,138,394,140,396,142,398,152,408,154,410,156,412,158,414,200,456,202,458,204,460,206,462,216,472,218,474,220,476,222,478))"

    private val SOURCE_TYPE_IN_LIST_EXPANDED =
        "(sourceType in (0,2,4,6,8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,72,328,74,330,76,332,78,334,88,344,90,346,92,348,94,350,136,392,138,394,140,396,142,398,152,408,154,410,156,412,158,414,200,456,202,458,204,460,206,462,216,472,218,474,220,476,222,478))"

    private val CLEAR_VISIBLE_BIT_SQL = Regex(
        """UPDATE\s+SnsInfo\s+SET\s+sourceType\s*=\s*sourceType\s*&\s*-3""",
        RegexOption.IGNORE_CASE
    )

    fun isSnsTable(table: String?): Boolean {
        return table.equals(SNS_TABLE, ignoreCase = true)
    }

    /** update ContentValues 是否表示“删除朋友圈” */
    fun isDeleteUpdate(sourceType: Int?): Boolean {
        return sourceType != null && sourceType == 0
    }

    /** 将删除更新改写为保留可见，并标记文案（content 字节由 Hook 层处理 protobuf） */
    fun restoreSourceTypeOnDelete(): Int = VISIBLE_SOURCE_TYPE

    fun markDeletedDescription(original: String?): String {
        val text = original.orEmpty()
        if (text.startsWith(DELETED_PREFIX)) return text
        // 旧数据「(已删除)」统一成「[已删除]」
        if (text.startsWith(LEGACY_DELETED_PREFIX)) {
            return DELETED_PREFIX + text.removePrefix(LEGACY_DELETED_PREFIX)
        }
        return DELETED_PREFIX + text
    }

    fun needsDeletedPrefix(description: String?): Boolean {
        if (description == null) return false
        return !description.startsWith(DELETED_PREFIX) &&
            !description.startsWith(LEGACY_DELETED_PREFIX)
    }

    /**
     * 改写 rawQuery SQL，让已删除动态仍出现在时间线/个人主页。
     * 返回 null 表示无需修改。
     */
    fun rewriteQuerySql(sql: String?): String? {
        if (sql.isNullOrBlank()) return null
        var next = sql
        var changed = false

        if (next.contains(SOURCE_TYPE_VISIBLE_FILTER)) {
            next = next.replace(SOURCE_TYPE_VISIBLE_FILTER, "")
            changed = true
        }
        // 兼容无多余空格的变体
        val compactFilter = "(sourceType & 2 != 0)  AND"
        if (next.contains(compactFilter)) {
            next = next.replace(compactFilter, "")
            changed = true
        }
        val compactFilter2 = "(sourceType & 2 != 0) AND"
        if (next.contains(compactFilter2)) {
            next = next.replace(compactFilter2, "")
            changed = true
        }

        if (next.contains(SOURCE_TYPE_IN_LIST)) {
            next = next.replace(SOURCE_TYPE_IN_LIST, SOURCE_TYPE_IN_LIST_EXPANDED)
            changed = true
        }

        // 个人主页：放宽 snsId 下界，避免分页把旧动态裁掉
        if (next.contains("WHERE SnsInfo.userName=", ignoreCase = true) &&
            next.contains("(snsId >=")
        ) {
            next = next.replace("(snsId >=", "(1=1 or snsId >=")
            changed = true
        }

        return if (changed) next else null
    }

    /** 是否应拦截清可见位的 execSQL */
    fun shouldBlockClearVisibleBit(table: String?, sql: String?): Boolean {
        if (!isSnsTable(table)) return false
        val statement = sql.orEmpty()
        if (CLEAR_VISIBLE_BIT_SQL.containsMatchIn(statement)) return true
        // 兼容无空格/小写变体
        return statement.contains("sourceType = sourceType & -3", ignoreCase = true) ||
            statement.contains("sourceType=sourceType&-3", ignoreCase = true)
    }

    /** 是否应拦截对 SnsInfo 的物理 delete */
    fun shouldBlockDelete(table: String?): Boolean = isSnsTable(table)
}
