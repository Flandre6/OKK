package com.OKK.yes.core.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookInstallCoordinatorTest {
    @Test
    fun `prepare dexkit only runs once`() {
        val coordinator = HookInstallCoordinator()

        assertTrue(coordinator.tryPrepareDexKit())
        assertFalse(coordinator.tryPrepareDexKit())
    }

    @Test
    fun `install hooks only runs once`() {
        val coordinator = HookInstallCoordinator()

        assertTrue(coordinator.tryInstallHooks())
        assertFalse(coordinator.tryInstallHooks())
    }

    @Test
    fun `reset clears install state`() {
        val coordinator = HookInstallCoordinator()
        coordinator.tryPrepareDexKit()
        coordinator.tryInstallHooks()

        coordinator.reset()

        assertTrue(coordinator.tryPrepareDexKit())
        assertTrue(coordinator.tryInstallHooks())
    }
}
