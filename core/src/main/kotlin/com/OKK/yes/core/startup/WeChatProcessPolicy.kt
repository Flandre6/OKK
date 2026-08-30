package com.OKK.yes.core.startup

object WeChatProcessPolicy {
    private const val WECHAT_PACKAGE = "com.tencent.mm"

    /**
     * 只挂主进程。
     * 注意：部分 LSPosed / 微信多 Application 场景下 [isFirstApplication] 可能为 false，
     * 若强制要求会导致整模块不加载、设置入口全无。因此不再依赖该标志。
     */
    fun shouldHandle(
        packageName: String,
        processName: String,
        isFirstApplication: Boolean
    ): Boolean {
        if (packageName != WECHAT_PACKAGE) return false
        // 主进程：processName 等于包名
        if (processName == WECHAT_PACKAGE) return true
        // 兼容极少数 ROM 上报 processName 为空
        if (processName.isBlank()) return isFirstApplication
        return false
    }
}
