package com.OKK.yes.loader

import android.app.Activity
import android.content.Context
import com.OKK.yes.loader.ui.OKKSettingsDialog

/**
 * 设置入口门面。所有设置 UI 打开入口统一组装并挂接到 Compose 版 [OKKSettingsDialog]。
 * 已彻底移除旧版 2400+ 行 View 布局与 fallback 代码。
 */
object EmbeddedSettingsUi {

    sealed class StartTarget {
        data object Home : StartTarget()
        data object Features : StartTarget()
        data object Diagnostics : StartTarget()
        data object Settings : StartTarget()
        data class FeatureDetail(val key: String) : StartTarget()
    }

    fun isShowing(): Boolean = OKKSettingsDialog.isShowing()

    fun show(host: Activity) = show(host, StartTarget.Features)

    fun show(host: Activity, start: StartTarget) {
        OKKSettingsDialog.show(host, start)
    }

    fun isWeChatDark(ctx: Context): Boolean {
        return runCatching {
            val uiMode = ctx.resources.configuration.uiMode
            (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }.getOrDefault(false)
    }
}
