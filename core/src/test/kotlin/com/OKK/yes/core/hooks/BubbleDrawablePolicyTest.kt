package com.OKK.yes.core.hooks

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleDrawablePolicyTest {
    @Test
    fun customBubbleDrawableDoesNotContributeMinimumSize() {
        assertEquals(0, BubbleDrawablePolicy.minimumWidth())
        assertEquals(0, BubbleDrawablePolicy.minimumHeight())
        assertEquals(-1, BubbleDrawablePolicy.intrinsicWidth())
        assertEquals(-1, BubbleDrawablePolicy.intrinsicHeight())
    }

    @Test
    fun fallbackBubbleClassIsStrict() {
        assertTrue(BubbleDrawablePolicy.isTextBubbleClass("com.tencent.mm.ui.widget.MMNeat7extView"))
        assertFalse(BubbleDrawablePolicy.isTextBubbleClass("com.tencent.mm.ui.widget.MMTextView"))
        assertFalse(BubbleDrawablePolicy.isTextBubbleClass("android.widget.TextView"))
    }

    @Test
    fun customBubbleOnlyAppliesToPlainTextMessages() {
        assertTrue(BubbleDrawablePolicy.supportsCustomBubble(messageType = 1))
        assertFalse(BubbleDrawablePolicy.supportsCustomBubble(messageType = 49))
        assertFalse(BubbleDrawablePolicy.supportsCustomBubble(messageType = 3))
        assertFalse(BubbleDrawablePolicy.supportsCustomBubble(messageType = 47))
        assertFalse(BubbleDrawablePolicy.supportsCustomBubble(messageType = 10000))
    }

    @Test
    fun customBubbleAppliesToQuotedTextAppMessages() {
        assertTrue(
            BubbleDrawablePolicy.supportsCustomBubble(
                messageType = 49,
                content = """
                    <msg>
                      <appmsg>
                        <type>57</type>
                        <title>hello</title>
                        <refermsg>
                          <type>1</type>
                          <displayname>Alice</displayname>
                          <content>old message</content>
                        </refermsg>
                      </appmsg>
                    </msg>
                """.trimIndent()
            )
        )
    }

    @Test
    fun customBubbleAppliesToQuotedTextAppMessageStorageVariants() {
        assertTrue(
            BubbleDrawablePolicy.supportsCustomBubble(
                messageType = 822083633,
                content = "<msg><appmsg><type>57</type><refermsg><type>1</type><content>old</content></refermsg><title>reply</title></appmsg></msg>"
            )
        )
    }

    @Test
    fun quotedTextAppMessagesNeedPostBindBubbleRefresh() {
        val quoted = "<msg><appmsg><type>57</type><refermsg><type>1</type><content>old</content></refermsg><title>reply</title></appmsg></msg>"
        val file = "<msg><appmsg><type>6</type><appattach><fileext>zip</fileext></appattach></appmsg></msg>"

        assertTrue(BubbleDrawablePolicy.needsPostBindRefresh(822083633, quoted))
        assertFalse(BubbleDrawablePolicy.needsPostBindRefresh(1, "plain"))
        assertFalse(BubbleDrawablePolicy.needsPostBindRefresh(49, file))
    }

    @Test
    fun customBubbleDoesNotApplyToFileAppMessages() {
        assertFalse(
            BubbleDrawablePolicy.supportsCustomBubble(
                messageType = 49,
                content = """
                    <msg>
                      <appmsg>
                        <type>6</type>
                        <title>report.zip</title>
                        <appattach>
                          <totallen>1024</totallen>
                          <fileext>zip</fileext>
                        </appattach>
                      </appmsg>
                    </msg>
                """.trimIndent()
            )
        )
    }

    @Test
    fun backgroundReplacementRequiresTextBubbleAndPlainTextMessage() {
        assertTrue(
            BubbleDrawablePolicy.shouldReplaceBackground(
                className = "com.tencent.mm.ui.widget.MMNeat7extView",
                messageType = 1
            )
        )
        assertTrue(
            BubbleDrawablePolicy.shouldReplaceBackground(
                className = "com.tencent.mm.ui.widget.MMNeat7extView",
                messageType = 49,
                content = "<msg><appmsg><type>57</type><refermsg><type>1</type></refermsg></appmsg></msg>"
            )
        )
        assertFalse(
            BubbleDrawablePolicy.shouldReplaceBackground(
                className = "com.tencent.mm.ui.widget.MMNeat7extView",
                messageType = 49
            )
        )
        assertFalse(
            BubbleDrawablePolicy.shouldReplaceBackground(
                className = "android.widget.TextView",
                messageType = 1
            )
        )
    }

    @Test
    fun voiceAndCallMessagesSupportCustomBubbleOnNonTextHost() {
        // 语音(34)/语音通话(50/1000052)/视频通话(1000053)/content 识别：容器是 FrameLayout，不是 MMNeat，但应支持自定义气泡
        assertTrue(BubbleDrawablePolicy.supportsCustomBubble(0, "voip_content_voice"))
        assertTrue(BubbleDrawablePolicy.supportsCustomBubble(0, "voip_content_video"))
        assertTrue(BubbleDrawablePolicy.isNonTextBubbleType(0, "voip_content_voice"))
        for (type in intArrayOf(34, 50, 1000052, 1000053)) {
            assertTrue(
                "type=$type should support custom bubble",
                BubbleDrawablePolicy.supportsCustomBubble(type)
            )
            assertTrue(
                "type=$type should replace background on FrameLayout host",
                BubbleDrawablePolicy.shouldReplaceBackground(
                    className = "android.widget.FrameLayout",
                    messageType = type
                )
            )
        }
        // 但普通文本仍要求 MMNeat 宿主
        assertFalse(
            BubbleDrawablePolicy.shouldReplaceBackground(
                className = "android.widget.FrameLayout",
                messageType = 1
            )
        )
    }

    @Test
    fun asymmetricPaddingInsetsAreTreatedAsPresent() {
        assertTrue(BubbleDrawablePolicy.hasPaddingInsets(rectOf(53, 24, 38, 24)))
        assertFalse(BubbleDrawablePolicy.hasPaddingInsets(rectOf(0, 0, 0, 0)))
    }

    @Test
    fun missingNinePatchContentPaddingFallsBackToWechatLikeInsets() {
        val sent = BubbleDrawablePolicy.contentPaddingOrDefault(rectOf(0, 0, 0, 0), isSend = true, density = 2f)
        assertRect(sent, left = 35, top = 16, right = 25, bottom = 16)

        val received = BubbleDrawablePolicy.contentPaddingOrDefault(rectOf(0, 0, 0, 0), isSend = false, density = 2f)
        assertRect(received, left = 25, top = 16, right = 35, bottom = 16)
    }

    @Test
    fun existingNinePatchContentPaddingIsPreserved() {
        val padding = BubbleDrawablePolicy.contentPaddingOrDefault(rectOf(9, 8, 7, 6), isSend = true, density = 3f)

        assertRect(padding, left = 9, top = 8, right = 7, bottom = 6)
    }

    private fun rectOf(left: Int, top: Int, right: Int, bottom: Int): Rect {
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    private fun assertRect(rect: Rect, left: Int, top: Int, right: Int, bottom: Int) {
        assertEquals(left, rect.left)
        assertEquals(top, rect.top)
        assertEquals(right, rect.right)
        assertEquals(bottom, rect.bottom)
    }
}
