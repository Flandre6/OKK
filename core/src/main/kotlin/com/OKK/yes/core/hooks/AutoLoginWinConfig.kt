package com.OKK.yes.core.hooks

/**
 * PC/平板登录页自动勾选配置（PC/平板登录自动勾选）。
 *
 * functionControl 位掩码（微信 intent.key.function.control）：
 * - 0b001 同步最近消息
 * - 0b010 显示登录设备
 * - 0b100 自动登录设备
 */
data class AutoLoginWinOptions(
    val enabled: Boolean = false,
    val syncMsg: Boolean = true,
    val showDevice: Boolean = true,
    val autoLoginDevice: Boolean = false,
    /** initView 后自动点击登录按钮 */
    val autoClick: Boolean = true
) {
    fun functionControl(): Int {
        var v = 0
        if (syncMsg) v = v or AutoLoginWinConfig.BIT_SYNC_MSG
        if (showDevice) v = v or AutoLoginWinConfig.BIT_SHOW_DEVICE
        if (autoLoginDevice) v = v or AutoLoginWinConfig.BIT_AUTO_LOGIN
        return v
    }
}

object AutoLoginWinConfig {
    const val KEY_ENABLED = "auto_login_win_enabled"
    const val KEY_SYNC_MSG = "auto_login_win_sync_msg"
    const val KEY_SHOW_DEVICE = "auto_login_win_show_device"
    const val KEY_AUTO_DEVICE = "auto_login_win_auto_device"
    const val KEY_AUTO_CLICK = "auto_login_win_auto_click"

    const val BIT_SYNC_MSG = 0b001
    const val BIT_SHOW_DEVICE = 0b010
    const val BIT_AUTO_LOGIN = 0b100

    const val EXTRA_FUNCTION_CONTROL = "intent.key.function.control"
    const val EXTRA_PRIVACY = "intent.key.need.show.privacy.agreement"

    @Volatile
    private var lastLoad = 0L

    @Volatile
    private var cached = AutoLoginWinOptions()

    fun load(now: Long = System.currentTimeMillis()): AutoLoginWinOptions {
        if (now - lastLoad < 3_000L) return cached
        lastLoad = now
        cached = AutoLoginWinOptions(
            enabled = cfgBool(KEY_ENABLED, false),
            syncMsg = cfgBool(KEY_SYNC_MSG, true),
            showDevice = cfgBool(KEY_SHOW_DEVICE, true),
            autoLoginDevice = cfgBool(KEY_AUTO_DEVICE, false),
            autoClick = cfgBool(KEY_AUTO_CLICK, true)
        )
        return cached
    }

    fun invalidate() {
        lastLoad = 0L
    }

    private fun cfgBool(key: String, default: Boolean): Boolean {
        return PublicConfigStore.getBoolean(key, default)
    }
}
