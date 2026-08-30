package com.OKK.yes.core.startup

import java.util.concurrent.atomic.AtomicBoolean

class HookInstallCoordinator {
    private val dexKitPrepared = AtomicBoolean(false)
    private val hooksInstalled = AtomicBoolean(false)

    fun tryPrepareDexKit(): Boolean = dexKitPrepared.compareAndSet(false, true)

    fun tryInstallHooks(): Boolean = hooksInstalled.compareAndSet(false, true)

    fun reset() {
        dexKitPrepared.set(false)
        hooksInstalled.set(false)
    }
}
