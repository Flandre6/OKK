package com.OKK.yes.core.hooks

/**
 * 首页侧栏「快捷」配置：最多 3 个，候选限定。
 * 存储键 [KEY_SHORTCUTS] = 逗号分隔 id，如 `qrcode,pay,favorite`
 */
object HomeDrawerConfig {
    const val KEY_SHORTCUTS = "home_drawer_shortcuts"
    const val MAX_SHORTCUTS = 3

    /** 侧栏签名（“OKK 快捷面板” 位置的副标题） */
    const val KEY_SIGNATURE = "home_drawer_signature"
    const val DEFAULT_SIGNATURE = "OKK 快捷面板"
    const val KEY_CUSTOM_STATUS = "home_status_custom"

    /** 「点击编辑签名」引导是否已展示过（只引导一次，用户修改后不再显示提示） */
    const val KEY_SIGNATURE_TIP_SHOWN = "home_drawer_signature_tip_shown"

    fun loadSignature(): String = runCatching {
        PublicConfigStore.getString(KEY_SIGNATURE, DEFAULT_SIGNATURE)
    }.getOrDefault(DEFAULT_SIGNATURE).ifBlank { DEFAULT_SIGNATURE }

    fun saveSignature(text: String) {
        // 签名由用户主动编辑，必须同步持久化，不能被旧进程的异步快照覆盖。
        PublicConfigStore.putPersisted(KEY_SIGNATURE, text.trim().ifBlank { DEFAULT_SIGNATURE })
        // 用户已修改过签名：引导提示不再展示
        markSignatureTipShown()
    }

    /** 引导提示是否已展示过（首次为 false，展示后或用户修改后为 true） */
    fun loadSignatureTipShown(): Boolean = runCatching {
        PublicConfigStore.getBoolean(KEY_SIGNATURE_TIP_SHOWN, false)
    }.getOrDefault(false)

    /** 标记引导提示已展示（幂等） */
    fun markSignatureTipShown() {
        PublicConfigStore.putPersisted(KEY_SIGNATURE_TIP_SHOWN, "true")
    }

    fun loadCustomStatus(): String = runCatching {
        PublicConfigStore.getString(KEY_CUSTOM_STATUS, "")
    }.getOrDefault("").trim()

    /** 空字符串表示恢复读取微信实际状态。 */
    fun saveCustomStatus(text: String) {
        PublicConfigStore.putPersisted(KEY_CUSTOM_STATUS, text.trim())
    }

    enum class Shortcut(
        val id: String,
        val title: String,
        val subtitle: String,
        val emoji: String
    ) {
        QRCODE("qrcode", "我的二维码", "展示个人二维码", "▦"),
        PAY("pay", "收付款", "付款码 / 收款", "¥"),
        SERVICE("service", "服务", "支付与服务", "◈"),
        FAVORITE("favorite", "收藏", "我的收藏", "★");

        companion object {
            fun fromId(id: String): Shortcut? =
                entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

            val defaultOrder: List<Shortcut> = listOf(QRCODE, PAY, FAVORITE)
        }
    }

    fun loadShortcuts(): List<Shortcut> {
        val raw = runCatching {
            PublicConfigStore.getString(KEY_SHORTCUTS, "")
        }.getOrDefault("")
        val parsed = raw.split(',')
            .mapNotNull { Shortcut.fromId(it) }
            .distinct()
            .take(MAX_SHORTCUTS)
        return parsed.ifEmpty { Shortcut.defaultOrder }
    }

    fun saveShortcuts(list: List<Shortcut>) {
        val clipped = list.distinct().take(MAX_SHORTCUTS)
        PublicConfigStore.put(
            KEY_SHORTCUTS,
            clipped.joinToString(",") { it.id },
            async = true
        )
    }

    fun toggleInList(current: List<Shortcut>, item: Shortcut): List<Shortcut> {
        return if (current.any { it == item }) {
            current.filter { it != item }
        } else if (current.size >= MAX_SHORTCUTS) {
            current // 已满，调用方提示
        } else {
            current + item
        }
    }
}
