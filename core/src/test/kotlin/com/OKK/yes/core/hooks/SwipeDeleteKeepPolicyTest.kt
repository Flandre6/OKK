package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeDeleteKeepPolicyTest {

    @Test
    fun neverDeletesChatMessages() {
        // 防回退硬约束：功能契约是「仅列表移除，保留聊天记录」
        assertFalse(SwipeDeleteKeepPolicy.shouldDeleteMessages)
    }

    @Test
    fun prefersDelChatContactWheneverAvailable() {
        // 有 delChatContact + storageRef 时必须走隐藏路径，绝不走永久删除
        assertEquals(
            SwipeDeleteKeepPolicy.HideStrategy.DEL_CHAT_CONTACT,
            SwipeDeleteKeepPolicy.selectStrategy(hasDelChatContact = true, hasStorageRef = true)
        )
        // 即使没有 storageRef 也不会退化成删除消息
        assertEquals(
            SwipeDeleteKeepPolicy.HideStrategy.RAW_RCONVERSATION_DELETE,
            SwipeDeleteKeepPolicy.selectStrategy(hasDelChatContact = false, hasStorageRef = true)
        )
    }

    @Test
    fun fallsBackToRawRowDeleteNotMessageDelete() {
        val strategy = SwipeDeleteKeepPolicy.selectStrategy(
            hasDelChatContact = false,
            hasStorageRef = true
        )
        assertEquals(SwipeDeleteKeepPolicy.HideStrategy.RAW_RCONVERSATION_DELETE, strategy)

        val (sql, _) = SwipeDeleteKeepPolicy.fallbackHideSql()
        assertTrue(sql.contains("rconversation"))
        assertFalse("兜底 SQL 不得触碰 message 表", sql.lowercase().contains("message"))
    }

    @Test
    fun unavailableWhenNothingResolved() {
        assertEquals(
            SwipeDeleteKeepPolicy.HideStrategy.UNAVAILABLE,
            SwipeDeleteKeepPolicy.selectStrategy(hasDelChatContact = false, hasStorageRef = false)
        )
    }

    @Test
    fun forbiddenDeleteStatementDetection() {
        assertTrue(SwipeDeleteKeepPolicy.isForbiddenDeleteStatement("DELETE FROM message WHERE talker = ?"))
        assertFalse(SwipeDeleteKeepPolicy.isForbiddenDeleteStatement("DELETE FROM rconversation WHERE username = ?"))
        assertFalse(SwipeDeleteKeepPolicy.isForbiddenDeleteStatement(null))
    }
}
