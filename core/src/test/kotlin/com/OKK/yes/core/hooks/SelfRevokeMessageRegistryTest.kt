package com.OKK.yes.core.hooks

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfRevokeMessageRegistryTest {
    @After
    fun tearDown() {
        SelfRevokeMessageRegistry.clearForTest()
    }

    @Test
    fun remembersAnyPositiveMessageId() {
        SelfRevokeMessageRegistry.mark(0L, 1001L, 2002L, now = 1_000L)

        assertTrue(SelfRevokeMessageRegistry.contains(1001L, now = 1_100L))
        assertTrue(SelfRevokeMessageRegistry.contains(2002L, now = 1_100L))
        assertFalse(SelfRevokeMessageRegistry.contains(0L, -1L, now = 1_100L))
    }

    @Test
    fun prunesExpiredMessageIds() {
        SelfRevokeMessageRegistry.mark(1001L, now = 1_000L)

        val afterEightDays = 1_000L + 8L * 24L * 60L * 60L * 1000L
        assertFalse(SelfRevokeMessageRegistry.contains(1001L, now = afterEightDays))
    }
}
