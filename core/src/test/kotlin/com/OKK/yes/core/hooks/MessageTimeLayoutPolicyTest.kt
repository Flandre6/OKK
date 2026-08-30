package com.OKK.yes.core.hooks

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTimeLayoutPolicyTest {
    @Test
    fun onlyUsesVerticalContainersForTimeRows() {
        assertTrue(MessageTimeLayoutPolicy.canHostTime(LinearLayout.VERTICAL))
        assertFalse(MessageTimeLayoutPolicy.canHostTime(LinearLayout.HORIZONTAL))
    }

    @Test
    fun timeRowWrapsContentAndFollowsBubbleSide() {
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, MessageTimeLayoutPolicy.layoutWidth())
        assertEquals(Gravity.START, MessageTimeLayoutPolicy.gravity(isSend = false))
        assertEquals(Gravity.END, MessageTimeLayoutPolicy.gravity(isSend = true))
    }

    @Test
    fun bubbleClusterFollowsMessageSide() {
        assertEquals(Gravity.START, MessageTimeLayoutPolicy.bubbleClusterGravity(isSend = false))
        assertEquals(Gravity.END, MessageTimeLayoutPolicy.bubbleClusterGravity(isSend = true))
    }

    @Test
    fun skipsSystemRecallNoticeRows() {
        assertTrue(MessageTimeLayoutPolicy.shouldShowForMessageType(1))
        assertFalse(MessageTimeLayoutPolicy.shouldShowForMessageType(10000))
    }

    @Test
    fun skipsSelfRecallNoticeTextEvenWhenMessageTypeLooksNormal() {
        assertFalse(MessageTimeLayoutPolicy.shouldShowForMessage(3, "", "\u4f60\u64a4\u56de\u4e86\u4e00\u6761\u6d88\u606f \u91cd\u65b0\u7f16\u8f91"))
        assertFalse(MessageTimeLayoutPolicy.shouldShowForMessage(1, "\u4f60\u64a4\u56de\u4e86\u4e00\u6761\u6d88\u606f", ""))
        assertTrue(MessageTimeLayoutPolicy.shouldShowForMessage(1, "\u666e\u901a\u6d88\u606f", "\u666e\u901a\u6d88\u606f"))
    }

    @Test
    fun skipsPatPatSystemNoticeRows() {
        assertFalse(MessageTimeLayoutPolicy.shouldShowForMessage(1, "", "\"Youye08\" \u62cd\u4e86\u62cd\u6211\u7684\u80a9\u8180\u8bf4\uff0c\u540c\u5fd7\u8f9b\u82e6\u4e86"))
        assertFalse(MessageTimeLayoutPolicy.shouldShowForMessage(1, "<patmsg><template>\"Youye08\" \u62cd\u4e86\u62cd\u6211</template></patmsg>", ""))
        assertFalse(MessageTimeLayoutPolicy.shouldShowForMessage(922746929, "", ""))
        assertTrue(MessageTimeLayoutPolicy.shouldShowForMessage(1, "\u6211\u62cd\u4e86\u4e00\u5f20\u7167\u7247", "\u6211\u62cd\u4e86\u4e00\u5f20\u7167\u7247"))
    }

    @Test
    fun recognizesSystemNoticeTextForLateMmNeatBindingCleanup() {
        assertTrue(MessageTimeLayoutPolicy.isSystemNoticeText("\u4f60\u64a4\u56de\u4e86\u4e00\u6761\u6d88\u606f \u91cd\u65b0\u7f16\u8f91"))
        assertTrue(MessageTimeLayoutPolicy.isSystemNoticeText("\"Youye08\" \u62cd\u4e86\u62cd\u6211\u7684\u80a9\u8180\u8bf4\uff0c\u540c\u5fd7\u8f9b\u82e6\u4e86"))
        assertFalse(MessageTimeLayoutPolicy.isSystemNoticeText("\u666e\u901a\u6d88\u606f"))
    }

    @Test
    fun recognizesCustomDetailTimeTextForRecallCleanup() {
        assertTrue(MessageTimeLayoutPolicy.isCustomDetailTimeText("07-06 \u5468\u4e00 21:52:42 \u521a\u521a"))
        assertTrue(MessageTimeLayoutPolicy.isCustomDetailTimeText("07-06 \u5468\u4e00 21:52:42 5\u5206\u949f\u524d"))
        assertTrue(MessageTimeLayoutPolicy.isCustomDetailTimeText("14:25:17 3\u5c0f\u65f6\u524d"))
        assertTrue(MessageTimeLayoutPolicy.isCustomDetailTimeText("\u6628\u5929 14:00"))
        assertTrue(MessageTimeLayoutPolicy.isCustomDetailTimeText("\u524d\u5929 10:30"))
        assertTrue(MessageTimeLayoutPolicy.isCustomDetailTimeText("03-15 10:30"))
        assertTrue(MessageTimeLayoutPolicy.isCustomDetailTimeText("\u521a\u521a"))
        assertFalse(MessageTimeLayoutPolicy.isCustomDetailTimeText("21:52"))
        assertFalse(MessageTimeLayoutPolicy.isCustomDetailTimeText("\u4f60\u64a4\u56de\u4e86\u4e00\u6761\u6d88\u606f"))
    }

    @Test
    fun insertsTimeAlwaysBelowAnchorEvenWhenRecycledAbove() {
        // 无时间 → 插在锚点后
        assertEquals(1, MessageTimeLayoutPolicy.insertIndexBelowAnchor(1, 0, -1))
        // 时间已在锚点后紧邻 → 不搬
        assertEquals(-1, MessageTimeLayoutPolicy.insertIndexBelowAnchor(2, 0, 1))
        // 时间在锚点前（表情复用常见）→ 需搬到锚点后
        assertEquals(1, MessageTimeLayoutPolicy.insertIndexBelowAnchor(2, 1, 0))
        // 锚点 0，时间在 2 → 搬到 1（紧邻下方）
        assertEquals(1, MessageTimeLayoutPolicy.insertIndexBelowAnchor(3, 0, 2))
    }

    @Test
    fun mediaLikeMessageTypesIncludeEmoji() {
        assertTrue(MessageTimeLayoutPolicy.isMediaLikeMessageType(47))
        assertTrue(MessageTimeLayoutPolicy.isMediaLikeMessageType(3))
        assertFalse(MessageTimeLayoutPolicy.isMediaLikeMessageType(1))
    }
}
