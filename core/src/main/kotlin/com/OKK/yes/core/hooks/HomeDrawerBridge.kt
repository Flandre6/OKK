package com.OKK.yes.core.hooks

import android.app.Activity

/**
 * 侧栏 → 模块设置 的桥（core 不依赖 loader）。
 * 由 [com.OKK.yes.loader.HookEntry] 在安装时赋值。
 */
object HomeDrawerBridge {
    @Volatile var openSettings: ((Activity) -> Unit)? = null
    @Volatile var openAbout: ((Activity) -> Unit)? = null
    @Volatile var openVirtualLocation: ((Activity) -> Unit)? = null
    @Volatile var openFloatingBar: ((Activity) -> Unit)? = null
    @Volatile var openTheme: ((Activity) -> Unit)? = null
}
