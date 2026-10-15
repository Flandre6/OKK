package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentsAdBlockLogicTest {

    @Test
    fun ignoreNullOrBlankOrUnrelatedSql() {
        assertNull(MomentsAdBlockLogic.rewriteSql(null))
        assertNull(MomentsAdBlockLogic.rewriteSql(""))
        assertNull(MomentsAdBlockLogic.rewriteSql("   "))
        assertNull(MomentsAdBlockLogic.rewriteSql("SELECT * FROM SnsInfo WHERE createTime > 100"))
        assertNull(MomentsAdBlockLogic.rewriteSql("SELECT * FROM rconversation WHERE unReadCount > 0"))
    }

    @Test
    fun rewriteTimelineUnionSqlWithWhere() {
        val original = "select *,rowid from SnsInfo where (sourceType & 2 != 0 ) AND createTime >= 1000 UNION select *,rowid from AdSnsInfo where createTime > 1000 and (1=1) limit 10"
        val rewritten = MomentsAdBlockLogic.rewriteSql(original)
        assertTrue(rewritten != null)
        assertTrue(rewritten!!.contains("FROM AdSnsInfo WHERE (1=0) AND createTime > 1000"))
    }

    @Test
    fun rewriteSingleAdSnsInfoQuery() {
        val original = "select *,rowid from AdSnsInfo  where AdSnsInfo.snsId=123456"
        val rewritten = MomentsAdBlockLogic.rewriteSql(original)
        assertTrue(rewritten != null)
        assertTrue(rewritten!!.contains("FROM AdSnsInfo WHERE (1=0) AND AdSnsInfo.snsId=123456"))
    }

    @Test
    fun rewriteAdSnsInfoWithoutWhere() {
        val original = "select count(*) from AdSnsInfo"
        val rewritten = MomentsAdBlockLogic.rewriteSql(original)
        assertTrue(rewritten != null)
        assertTrue(rewritten!!.contains("FROM (SELECT * FROM AdSnsInfo WHERE 1=0) AS AdSnsInfo"))
    }
}
