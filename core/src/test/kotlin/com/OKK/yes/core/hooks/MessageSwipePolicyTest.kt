package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSwipePolicyTest {
    @Test
    fun startsDraggingOnlyForClearLeftSwipe() {
        assertTrue(MessageSwipePolicy.shouldStartDrag(deltaX = -18f, deltaY = 4f, touchSlop = 8))
        assertTrue(MessageSwipePolicy.shouldStartDrag(deltaX = -7f, deltaY = 0f, touchSlop = 8))
        assertFalse(MessageSwipePolicy.shouldStartDrag(deltaX = -4f, deltaY = 0f, touchSlop = 8))
        assertFalse(MessageSwipePolicy.shouldStartDrag(deltaX = -18f, deltaY = 24f, touchSlop = 8))
        assertFalse(MessageSwipePolicy.shouldStartDrag(deltaX = 18f, deltaY = 4f, touchSlop = 8))
    }

    @Test
    fun clampsRowTranslationAndTriggersQuotePastThreshold() {
        assertEquals(-120f, MessageSwipePolicy.clampTranslation(deltaX = -160f, maxDistance = 120), 0.01f)
        assertEquals(-36f, MessageSwipePolicy.clampTranslation(deltaX = -36f, maxDistance = 120), 0.01f)
        assertEquals(0f, MessageSwipePolicy.clampTranslation(deltaX = 24f, maxDistance = 120), 0.01f)
        assertTrue(MessageSwipePolicy.shouldTriggerQuote(finalDeltaX = -41f, threshold = 40))
        assertFalse(MessageSwipePolicy.shouldTriggerQuote(finalDeltaX = -39f, threshold = 40))
    }

    @Test
    fun dualDirectionRespectsAllowFlags() {
        // 仅右滑复读
        assertTrue(
            MessageSwipePolicy.shouldStartDrag(
                deltaX = 18f, deltaY = 2f, touchSlop = 8,
                allowLeft = false, allowRight = true
            )
        )
        assertFalse(
            MessageSwipePolicy.shouldStartDrag(
                deltaX = -18f, deltaY = 2f, touchSlop = 8,
                allowLeft = false, allowRight = true
            )
        )
        assertEquals(
            90f,
            MessageSwipePolicy.clampTranslation(90f, 120, allowLeft = false, allowRight = true),
            0.01f
        )
        assertEquals(
            0f,
            MessageSwipePolicy.clampTranslation(-90f, 120, allowLeft = false, allowRight = true),
            0.01f
        )
        assertEquals(
            MessageSwipePolicy.Direction.Right,
            MessageSwipePolicy.resolveAction(50f, 40, allowLeft = true, allowRight = true)
        )
        assertEquals(
            MessageSwipePolicy.Direction.Left,
            MessageSwipePolicy.resolveAction(-50f, 40, allowLeft = true, allowRight = true)
        )
        assertEquals(
            MessageSwipePolicy.Direction.None,
            MessageSwipePolicy.resolveAction(50f, 40, allowLeft = true, allowRight = false)
        )
    }

    @Test
    fun quoteMethodScorePrefersQuoteNamesAndRejectsGetters() {
        assertTrue(
            MessageSwipePolicy.quoteMethodScore("quoteMsg", 1, true) >
                MessageSwipePolicy.quoteMethodScore("v0", 1, true)
        )
        assertTrue(MessageSwipePolicy.quoteMethodScore("getMsg", 1, true) < 0)
        assertTrue(MessageSwipePolicy.quoteMethodScore("isReady", 1, true) < 0)
        assertTrue(MessageSwipePolicy.quoteMethodScore("v0", 1, true) > 0)
        assertTrue(MessageSwipePolicy.quoteMethodScore("D", 1, true) > 0)
    }
}
