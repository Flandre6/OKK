package com.OKK.yes.core.hooks

/**
 * 「左滑删除存记录」纯策略层（可单测）。
 *
 * 功能语义（FeatureCatalog 定义）：**会话列表左滑删除仅在列表移除，保留聊天记录**。
 * 等价于微信原生的「不显示该聊天」：
 * - 仅移除 `rconversation` 行并通知列表观察者；
 * - **绝不**触碰 `message` 表与 `deleteMsgByTalker`；
 * - **绝不**调用确认后的永久删除 worker（`ConvDelLogic` / `doDeleteConversation`）。
 */
object SwipeDeleteKeepPolicy {

    /** 左滑删除采用的隐藏策略（按优先级排列）。 */
    enum class HideStrategy {
        /** 首选：`ConversationStorage.delChatContact(String)` — 缓存感知的「不显示该聊天」，不动消息。 */
        DEL_CHAT_CONTACT,

        /** 兜底：直接 `DELETE FROM rconversation`，仅在 delChatContact 不可用时使用。 */
        RAW_RCONVERSATION_DELETE,

        /** 两条路径都不可用。 */
        UNAVAILABLE
    }

    /**
     * 选择隐藏策略。
     *
     * 只要 `delChatContact` 可用就**必须**用它，永不降级到永久删除路径，
     * 从代码结构上杜绝「删除会话 + 删记录」的反向行为。
     */
    fun selectStrategy(hasDelChatContact: Boolean, hasStorageRef: Boolean): HideStrategy {
        if (hasDelChatContact && hasStorageRef) return HideStrategy.DEL_CHAT_CONTACT
        if (hasStorageRef) return HideStrategy.RAW_RCONVERSATION_DELETE
        return HideStrategy.UNAVAILABLE
    }

    /**
     * 左滑删除**是否允许删除聊天记录**。
     *
     * 恒为 `false`：作为防回退硬约束，任何把它改为 true、或绕过本策略直接调用
     * `deleteMsgByTalker` / `DELETE FROM message` / `doDeleteConversation` 的改动都是违规。
     */
    val shouldDeleteMessages: Boolean get() = false

    /** 兜底 SQL：仅删除会话行，保留消息表。 */
    fun fallbackHideSql(): Pair<String, String> =
        "DELETE FROM rconversation WHERE username = ?" to "talker"

    /** 是否属于被禁止的永久删除语句（用于单测/审计防回退断言）。 */
    fun isForbiddenDeleteStatement(sql: String?): Boolean {
        val s = sql?.lowercase() ?: return false
        return s.contains("delete from message") ||
            s.contains("delete from rconversation") && s.contains("delete from message")
    }
}
