package com.OKK.yes.core.hooks

import java.util.concurrent.ConcurrentHashMap

internal object SelfRevokeMessageRegistry {
    private const val TTL_MS = 7L * 24L * 60L * 60L * 1000L
    private val ids = ConcurrentHashMap<Long, Long>()

    fun mark(vararg messageIds: Long, now: Long = System.currentTimeMillis()) {
        prune(now)
        messageIds
            .asSequence()
            .filter { it > 0L }
            .forEach { ids[it] = now }
    }

    fun contains(vararg messageIds: Long, now: Long = System.currentTimeMillis()): Boolean {
        prune(now)
        return messageIds.any { it > 0L && ids.containsKey(it) }
    }

    private fun prune(now: Long) {
        ids.entries.removeIf { (_, timestamp) -> now - timestamp > TTL_MS }
    }

    fun clearForTest() {
        ids.clear()
    }
}
