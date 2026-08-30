package com.OKK.yes.core.hooks

object MessageDetailConfig {
    @Volatile
    private var lastLoadTime = 0L

    @Volatile
    private var cached = MessageDetailOptions()

    fun load(now: Long = System.currentTimeMillis()): MessageDetailOptions {
        if (now - lastLoadTime < 5_000L) return cached
        lastLoadTime = now
        val legacyMargin = cfgStr("detail_horizontal_margin", "0").toIntOrNull() ?: 0
        val left = cfgStr("detail_left_margin", legacyMargin.toString()).toIntOrNull()
            ?: legacyMargin
        val right = cfgStr("detail_right_margin", legacyMargin.toString()).toIntOrNull()
            ?: legacyMargin
        val light = MessageDetailFormatter.parseColor(
            cfgStr("detail_text_color_light", ""),
            MessageDetailFormatter.parseColor(
                cfgStr("detail_text_color", "#E6000000"),
                0xE6000000.toInt()
            )
        )
        val dark = MessageDetailFormatter.parseColor(
            cfgStr("detail_text_color_dark", ""),
            MessageDetailFormatter.parseColor(
                cfgStr("detail_text_color", "#CCFFFFFF"),
                0xCCFFFFFF.toInt()
            )
        )
        cached = MessageDetailOptions(
            enabled = cfgBool("detail_enabled", true),
            template = cfgStr("detail_template", "\${time} \${relativeTime}"),
            timePattern = cfgStr("detail_time_pattern", "MM-dd HH:mm:ss"),
            textSizeSp = cfgStr("detail_text_size", "12").toFloatOrNull() ?: 12f,
            horizontalMarginDp = legacyMargin,
            leftMarginDp = left,
            rightMarginDp = right,
            textColor = light,
            textColorLight = light,
            textColorDark = dark,
            clickToShow = cfgBool("detail_click_show", false)
        )
        return cached
    }

    private fun cfgBool(key: String, default: Boolean): Boolean {
        return PublicConfigStore.getBoolean(key, default)
    }

    private fun cfgStr(key: String, default: String): String {
        return PublicConfigStore.getString(key, default)
    }

    /** 配置变更后清除 5s 缓存，使新模板/设置立即生效 */
    fun invalidateCache() {
        lastLoadTime = 0L
        cached = MessageDetailOptions()
    }
}
