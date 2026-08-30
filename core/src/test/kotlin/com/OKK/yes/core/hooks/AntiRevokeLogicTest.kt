package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiRevokeLogicTest {
    private val revokeXml = """
        <sysmsg type="revokemsg">
            <revokemsg>
                <session>wxid_friend</session>
                <newmsgid>456</newmsgid>
                <replacemsg><![CDATA[AAA撤回了一条消息]]></replacemsg>
            </revokemsg>
        </sysmsg>
    """.trimIndent()

    private val imagePath =
        """D:\Data\WeChat\data\xwechat_files\wxid_x\temp\RWTemp\a.jpg"""

    @Test
    fun defaultKeepsWechatSentenceWithRemark() {
        // 默认模板：直接用微信系统句（含备注 AAA）
        val action = AntiRevokeLogic.analyze(
            update = RevokeUpdate("message", 1L, 10000, revokeXml),
            original = OriginalMessage(3, imagePath, "wxid_friend", 1L, "wxid_friend"),
            noticeTemplate = "{name}撤回了一条消息"
        )
        assertEquals(
            RevokeAction.KeepRevokeNotice("AAA撤回了一条消息[已阻止]"),
            action
        )
    }

    @Test
    fun defaultKeepsEnglishWechatSentence() {
        val xml = """
            <sysmsg type="revokemsg">
              <replacemsg><![CDATA["Alice" recalled a message]]></replacemsg>
            </sysmsg>
        """.trimIndent()
        val action = AntiRevokeLogic.analyze(
            update = RevokeUpdate("message", 1L, 10000, xml),
            original = null
        )
        assertEquals(
            RevokeAction.KeepRevokeNotice("\"Alice\" recalled a message[已阻止]"),
            action
        )
    }

    @Test
    fun customTemplateFillsNameFromSentence() {
        val action = AntiRevokeLogic.analyze(
            update = RevokeUpdate("message", 1L, 10000, revokeXml),
            original = OriginalMessage(1, "hello", "wxid_friend", 1L),
            noticeTemplate = "{name} 撤了：{content}"
        )
        assertEquals(
            RevokeAction.KeepRevokeNotice("AAA 撤了：hello[已阻止]"),
            action
        )
    }

    @Test
    fun customTemplateNeverPutsPath() {
        val action = AntiRevokeLogic.analyze(
            update = RevokeUpdate("message", 1L, 10000, revokeXml),
            original = OriginalMessage(3, imagePath, "wxid_friend", 1L),
            noticeTemplate = "{name}撤回了 {content}"
        )
        val n = (action as RevokeAction.KeepRevokeNotice).content
        assertFalse(n.contains("xwechat_files"))
        assertFalse(n.contains(".jpg"))
        assertTrue(n.contains("AAA"))
    }

    @Test
    fun extractNameFromSentence() {
        assertEquals("AAA", AntiRevokeLogic.extractNameFromRevokeText("AAA撤回了一条消息"))
        assertEquals("AAA", AntiRevokeLogic.extractNameFromRevokeText(revokeXml))
        assertEquals("Alice", AntiRevokeLogic.extractNameFromRevokeText("\"Alice\" recalled a message"))
    }

    @Test
    fun ignoresSelfUnlessKeep() {
        assertEquals(
            RevokeAction.Ignore,
            AntiRevokeLogic.analyze(
                update = RevokeUpdate("message", 1L, 10000, "你撤回了一条消息"),
                original = null,
                keepSelf = false
            )
        )
    }
}
