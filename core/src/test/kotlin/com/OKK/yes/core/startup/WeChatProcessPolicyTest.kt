package com.OKK.yes.core.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatProcessPolicyTest {
    @Test
    fun `accepts main wechat process`() {
        assertTrue(
            WeChatProcessPolicy.shouldHandle(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm",
                isFirstApplication = true
            )
        )
    }

    @Test
    fun `rejects secondary process`() {
        assertFalse(
            WeChatProcessPolicy.shouldHandle(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm:tools",
                isFirstApplication = true
            )
        )
    }

    @Test
    fun `rejects non wechat package`() {
        assertFalse(
            WeChatProcessPolicy.shouldHandle(
                packageName = "com.example.demo",
                processName = "com.example.demo",
                isFirstApplication = true
            )
        )
    }

    @Test
    fun `accepts main wechat even when isFirstApplication is false`() {
        // LSPosed + 多 Application 时 firstApp 可能为 false，不能因此拒载
        assertTrue(
            WeChatProcessPolicy.shouldHandle(
                packageName = "com.tencent.mm",
                processName = "com.tencent.mm",
                isFirstApplication = false
            )
        )
    }
}
