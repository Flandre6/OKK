package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiMomentsCommentLogicTest {

    @Test
    fun table_name() {
        assertTrue(AntiMomentsCommentLogic.isCommentTable("SnsComment"))
        assertTrue(AntiMomentsCommentLogic.isCommentTable("snscomment"))
        assertFalse(AntiMomentsCommentLogic.isCommentTable("SnsInfo"))
    }

    @Test
    fun mark_deleted_sql() {
        assertTrue(
            AntiMomentsCommentLogic.isMarkDeletedSql(
                " update SnsComment set commentflag = 1 where snsID = 123 and talker = 'wxid'"
            )
        )
        assertTrue(
            AntiMomentsCommentLogic.isMarkDeletedSql(
                " update SnsComment set commentflag = 2 where snsID = 1"
            )
        )
        assertFalse(
            AntiMomentsCommentLogic.isMarkDeletedSql(
                " update SnsComment set isRead = 1 where snsID = 1"
            )
        )
    }

    @Test
    fun block_exec() {
        assertTrue(
            AntiMomentsCommentLogic.shouldBlockExecSql(
                "SnsComment",
                " update SnsComment set commentflag = 1 where snsID = 1"
            )
        )
        assertTrue(
            AntiMomentsCommentLogic.shouldBlockExecSql(
                null,
                "delete from SnsComment where snsID = 1 and commentSvrID = 2"
            )
        )
        assertFalse(
            AntiMomentsCommentLogic.shouldBlockExecSql("SnsInfo", "delete from SnsInfo where ...")
        )
    }

    @Test
    fun flag_update() {
        assertTrue(AntiMomentsCommentLogic.isDeleteFlagUpdate(1))
        assertTrue(AntiMomentsCommentLogic.isDeleteFlagUpdate(2))
        assertTrue(AntiMomentsCommentLogic.isDeleteFlagUpdate(3))
        assertFalse(AntiMomentsCommentLogic.isDeleteFlagUpdate(0))
        assertFalse(AntiMomentsCommentLogic.isDeleteFlagUpdate(null))
    }

    @Test
    fun intercepted_flag() {
        assertTrue(AntiMomentsCommentLogic.isWechatDeleted(1))
        assertTrue(AntiMomentsCommentLogic.isWechatDeleted(3))
        assertFalse(AntiMomentsCommentLogic.isWechatDeleted(0))
        assertFalse(AntiMomentsCommentLogic.isWechatDeleted(256))

        val restored = AntiMomentsCommentLogic.restoreFlagKeepIntercepted(1)
        assertEquals(256, restored)
        assertTrue(AntiMomentsCommentLogic.isIntercepted(restored))
        assertFalse(AntiMomentsCommentLogic.isWechatDeleted(restored))

        val keep = AntiMomentsCommentLogic.restoreFlagKeepIntercepted(1 or 4)
        assertEquals(4 or 256, keep)
    }

    @Test
    fun mark_deleted_content() {
        assertEquals("[已删除]", AntiMomentsCommentLogic.markDeletedContent(null))
        assertEquals("[已删除]", AntiMomentsCommentLogic.markDeletedContent(""))
        assertEquals("[已删除] hello", AntiMomentsCommentLogic.markDeletedContent("hello"))
        assertEquals("[已删除] hello", AntiMomentsCommentLogic.markDeletedContent("[已删除] hello"))
        assertEquals("[已删除]hello", AntiMomentsCommentLogic.markDeletedContent("(已删除)hello"))
        assertTrue(AntiMomentsCommentLogic.needsDeletedPrefix("hi"))
        assertFalse(AntiMomentsCommentLogic.needsDeletedPrefix("[已删除]hi"))
    }

    @Test
    fun prepend_proto_string_field() {
        // field 8, wire type 2: tag = (8<<3)|2 = 66 = 0x42
        val content = "hello".toByteArray(Charsets.UTF_8)
        val buf = java.io.ByteArrayOutputStream().apply {
            write(0x42)
            write(content.size)
            write(content)
        }.toByteArray()

        val out = AntiMomentsCommentLogic.prependProtoStringField(
            buf,
            AntiMomentsCommentLogic.ACTION_CONTENT_FIELD,
            "[已删除] "
        )!!
        // 解析回 field 8
        assertTrue(out[0] == 0x42.toByte())
        val len = out[1].toInt() and 0xff
        val text = String(out, 2, len, Charsets.UTF_8)
        assertEquals("[已删除] hello", text)

        // 幂等
        val out2 = AntiMomentsCommentLogic.prependProtoStringField(
            out,
            AntiMomentsCommentLogic.ACTION_CONTENT_FIELD,
            "[已删除] "
        )!!
        val len2 = out2[1].toInt() and 0xff
        val text2 = String(out2, 2, len2, Charsets.UTF_8)
        assertEquals("[已删除] hello", text2)
    }

    @Test
    fun inject_action_buf() {
        val content = "测试评论".toByteArray(Charsets.UTF_8)
        val buf = java.io.ByteArrayOutputStream().apply {
            write(0x42) // field 8
            write(content.size)
            write(content)
        }.toByteArray()
        val marked = AntiMomentsCommentLogic.injectDeletedMarkerIntoActionBuf(buf)
        val len = marked[1].toInt() and 0xff
        val text = String(marked, 2, len, Charsets.UTF_8)
        assertTrue(text.startsWith("[已删除]"))
        assertTrue(text.contains("测试评论"))
    }
}
