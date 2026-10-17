package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseFriendSqlRewriteTest {

    @Test
    fun looksLikeConversationListQuery_matchesHomepageList() {
        val sqlA = "select unReadCount, status, isSend, conversationTime, username, content, msgType, flag, digest, digestUser from rconversation where parentRef is null order by flag desc"
        assertTrue(CloseFriendSqlLogic.looksLikeConversationListQuery(sqlA))

        val sqlD = "select unReadCount, status, isSend, conversationTime, rconversation.username, content, rconversation.msgType, rconversation.flag, rconversation.digest, rconversation.digestUser, rconversation.hasTrunc, rcontact.nickname from rconversation,rcontact where rconversation.username = rcontact.username and parentRef is null order by flag desc, conversationTime desc"
        assertTrue(CloseFriendSqlLogic.looksLikeConversationListQuery(sqlD))
    }

    @Test
    fun looksLikeConversationListQuery_rejectsSingleLookups() {
        // 单个会话详情查询，绝对不能改写，否则导致 isPlacedTop 置顶判断失败
        val sqlParam = "SELECT * FROM rconversation WHERE username = ?"
        assertFalse(CloseFriendSqlLogic.looksLikeConversationListQuery(sqlParam))

        val sqlLiteral = "SELECT * FROM rconversation WHERE username=\"wxid_user123\""
        assertFalse(CloseFriendSqlLogic.looksLikeConversationListQuery(sqlLiteral))

        val sqlSingle = "select * from rconversation where rconversation.username='gh_12345'"
        assertFalse(CloseFriendSqlLogic.looksLikeConversationListQuery(sqlSingle))
    }

    @Test
    fun looksLikeConversationListQuery_rejectsAggregatesAndNonSelect() {
        assertFalse(CloseFriendSqlLogic.looksLikeConversationListQuery("SELECT count(*) FROM rconversation"))
        assertFalse(CloseFriendSqlLogic.looksLikeConversationListQuery("SELECT sum(unReadCount) FROM rconversation"))
        assertFalse(CloseFriendSqlLogic.looksLikeConversationListQuery("UPDATE rconversation SET unReadCount = 0"))
        assertFalse(CloseFriendSqlLogic.looksLikeConversationListQuery("DELETE FROM rconversation WHERE username = 'test'"))
    }

    @Test
    fun looksLikeConversationListQuery_rejectsFolderAndBox() {
        val folderSql = "SELECT * FROM rconversation WHERE parentRef = 'wekit_folder_123' order by flag desc"
        assertFalse(CloseFriendSqlLogic.looksLikeConversationListQuery(folderSql))

        val boxSql = "SELECT conversationTime, unReadCount, flag FROM rconversation WHERE parentRef = 'conversationboxservice'"
        assertFalse(CloseFriendSqlLogic.looksLikeConversationListQuery(boxSql))
    }

    @Test
    fun extractTableAlias_correctAliases() {
        assertEquals("rconversation", CloseFriendSqlLogic.extractTableAlias("select * from rconversation where 1=1", "rconversation"))
        assertEquals("c", CloseFriendSqlLogic.extractTableAlias("select * from rconversation c where 1=1", "rconversation"))
        assertEquals("c", CloseFriendSqlLogic.extractTableAlias("select * from rconversation as c where 1=1", "rconversation"))
        assertEquals("rconversation", CloseFriendSqlLogic.extractTableAlias("select * from rconversation, rcontact where 1=1", "rconversation"))
        assertEquals("c", CloseFriendSqlLogic.extractTableAlias("select * from rcontact r left join rconversation c on r.username = c.username", "rconversation"))
    }

    @Test
    fun buildExcludeCondition_addsAliasPrefix() {
        val ids = setOf("wxid_a", "wxid_b")
        val condDefault = CloseFriendSqlLogic.buildExcludeCondition(ids, "rconversation")
        assertEquals("rconversation.username NOT IN ('wxid_a','wxid_b')", condDefault)

        val condAlias = CloseFriendSqlLogic.buildExcludeCondition(ids, "c")
        assertEquals("c.username NOT IN ('wxid_a','wxid_b')", condAlias)
    }

    @Test
    fun injectCondition_preservesOrderByFlagDesc() {
        val sql = "select unReadCount, conversationTime, username, flag from rconversation where parentRef is null order by flag desc, conversationTime desc"
        val condition = "(rconversation.username NOT IN ('wxid_hidden'))"
        val rewritten = CloseFriendSqlLogic.injectCondition(sql, condition)

        assertTrue(rewritten != null)
        assertTrue(rewritten!!.contains("AND $condition"))
        assertTrue(rewritten.endsWith("order by flag desc, conversationTime desc"))
    }

    @Test
    fun injectCondition_handlesNoWhereClause() {
        val sql = "select unReadCount, conversationTime, username, flag from rconversation order by flag desc"
        val condition = "(rconversation.username NOT IN ('wxid_hidden'))"
        val rewritten = CloseFriendSqlLogic.injectCondition(sql, condition)

        assertTrue(rewritten != null)
        assertTrue(rewritten!!.contains("WHERE $condition"))
        assertTrue(rewritten.endsWith("order by flag desc"))
    }
}
