package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiMomentsDeleteLogicTest {

    @Test
    fun isSnsTable_matchesCaseInsensitive() {
        assertTrue(AntiMomentsDeleteLogic.isSnsTable("SnsInfo"))
        assertTrue(AntiMomentsDeleteLogic.isSnsTable("snsinfo"))
        assertFalse(AntiMomentsDeleteLogic.isSnsTable("message"))
    }

    @Test
    fun isDeleteUpdate_onlyZero() {
        assertTrue(AntiMomentsDeleteLogic.isDeleteUpdate(0))
        assertFalse(AntiMomentsDeleteLogic.isDeleteUpdate(2))
        assertFalse(AntiMomentsDeleteLogic.isDeleteUpdate(null))
    }

    @Test
    fun markDeletedDescription_prefixesOnce() {
        assertEquals("[已删除]hello", AntiMomentsDeleteLogic.markDeletedDescription("hello"))
        assertEquals("[已删除]hello", AntiMomentsDeleteLogic.markDeletedDescription("[已删除]hello"))
        assertEquals("[已删除]hello", AntiMomentsDeleteLogic.markDeletedDescription("(已删除)hello"))
        assertEquals("[已删除]", AntiMomentsDeleteLogic.markDeletedDescription(null))
    }

    @Test
    fun rewriteQuerySql_stripsVisibleFilter() {
        val sql = "select * from SnsInfo where (sourceType & 2 != 0 )  AND userName=?"
        val rewritten = AntiMomentsDeleteLogic.rewriteQuerySql(sql)
        assertNotNull(rewritten)
        assertFalse(rewritten!!.contains("sourceType & 2 != 0"))
        assertTrue(rewritten.contains("userName=?"))
    }

    @Test
    fun rewriteQuerySql_expandsSourceTypeInList() {
        val list =
            "(sourceType in (8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,72,328,74,330,76,332,78,334,88,344,90,346,92,348,94,350,136,392,138,394,140,396,142,398,152,408,154,410,156,412,158,414,200,456,202,458,204,460,206,462,216,472,218,474,220,476,222,478))"
        val sql = "select * from SnsInfo where $list"
        val rewritten = AntiMomentsDeleteLogic.rewriteQuerySql(sql)
        assertNotNull(rewritten)
        assertTrue(rewritten!!.contains("sourceType in (0,2,4,6,8,"))
    }

    @Test
    fun rewriteQuerySql_relaxesUserPageSnsIdBound() {
        val sql = "select * from SnsInfo WHERE SnsInfo.userName=? and (snsId >= 1)"
        val rewritten = AntiMomentsDeleteLogic.rewriteQuerySql(sql)
        assertNotNull(rewritten)
        assertTrue(rewritten!!.contains("(1=1 or snsId >="))
    }

    @Test
    fun rewriteQuerySql_returnsNullWhenUnrelated() {
        assertNull(AntiMomentsDeleteLogic.rewriteQuerySql("select * from message"))
        assertNull(AntiMomentsDeleteLogic.rewriteQuerySql(null))
    }

    @Test
    fun shouldBlockClearVisibleBit() {
        assertTrue(
            AntiMomentsDeleteLogic.shouldBlockClearVisibleBit(
                "SnsInfo",
                "UPDATE SnsInfo SET sourceType = sourceType & -3 where snsId=1"
            )
        )
        assertTrue(
            AntiMomentsDeleteLogic.shouldBlockClearVisibleBit(
                "SnsInfo",
                "update snsinfo set sourcetype=sourcetype&-3 where 1"
            )
        )
        assertFalse(
            AntiMomentsDeleteLogic.shouldBlockClearVisibleBit(
                "SnsInfo",
                "UPDATE SnsInfo SET likeFlag=1"
            )
        )
        assertFalse(
            AntiMomentsDeleteLogic.shouldBlockClearVisibleBit(
                "message",
                "UPDATE SnsInfo SET sourceType = sourceType & -3"
            )
        )
    }

    @Test
    fun shouldBlockDelete_onlySnsInfo() {
        assertTrue(AntiMomentsDeleteLogic.shouldBlockDelete("SnsInfo"))
        assertFalse(AntiMomentsDeleteLogic.shouldBlockDelete("message"))
    }
}
