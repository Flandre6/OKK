package com.OKK.yes.core.hooks

import android.content.Context
import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原「折叠置顶聊天」Hook。
 * 为避免破坏微信原生的置顶折叠机制（导致置顶聊天与折叠条全部消失），
 * 此处完全放行微信原生逻辑，不进行任何隐藏或视图篡改。
 */
object FoldBannerPinHook {
    private const val TAG = "OKK-FoldBanner"
    const val KEY = "fold_banner_fixed"

    private val installed = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install: clean passthrough, keeping native WeChat fold behavior")
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("$TAG: $msg") }
    }
}
