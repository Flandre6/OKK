package com.OKK.yes.core.hooks

data class AppFeatureOptions(
    val antiRevoke: Boolean = true,
    val revokeNotice: Boolean = true,
    /** anti_recall_keep_self：自己撤回的消息是否也拦截保留 */
    val keepSelfRevoke: Boolean = false,
    /** anti_recall_notice_text，变量 {name} {content} */
    val revokeNoticeTemplate: String = "{name}撤回了一条消息",
    val mediaProtect: Boolean = true,
    val antiMomentsDelete: Boolean = true,
    val swipeQuote: Boolean = true,
    val swipeRepeat: Boolean = false,
    val customBubble: Boolean = true,
    val settingsEntry: Boolean = true
)

object AppFeatureConfig {
    @Volatile
    private var lastLoadTime = 0L

    @Volatile
    private var cached = AppFeatureOptions()

    @Volatile
    private var listenerRegistered = false

    /** 注册配置变化监听（保存即生效）：任一相关 key 变化后立即失效缓存，下次 load 重读。 */
    private fun ensureListener() {
        if (listenerRegistered) return
        listenerRegistered = true
        val keys = listOf(
            "anti_revoke", "revoke_notice_enabled", "anti_revoke_keep_self",
            "anti_revoke_notice_text", "media_protect_enabled", "anti_moments_delete",
            "swipe_quote", "bubble_enabled", "settings_entry_enabled"
        )
        keys.forEach { k ->
            PublicConfigStore.addListener(k) { lastLoadTime = 0L }
        }
    }

    fun load(now: Long = System.currentTimeMillis()): AppFeatureOptions {
        ensureListener()
        if (now - lastLoadTime < 5_000L) return cached
        lastLoadTime = now
        // 微信进程优先公共 properties；XSP 常读不到
        cached = AppFeatureOptions(
            antiRevoke = cfgBool("anti_revoke", true),
            revokeNotice = cfgBool("revoke_notice_enabled", true),
            keepSelfRevoke = cfgBool("anti_revoke_keep_self", false),
            revokeNoticeTemplate = cfgStr(
                "anti_revoke_notice_text",
                "{name}撤回了一条消息"
            ),
            mediaProtect = cfgBool("media_protect_enabled", true),
            antiMomentsDelete = cfgBool("anti_moments_delete", true),
            swipeQuote = cfgBool("swipe_quote", true),
            swipeRepeat = false,
            customBubble = cfgBool("bubble_enabled", true),
            settingsEntry = cfgBool("settings_entry_enabled", true)
        )
        return cached
    }

    private fun cfgBool(key: String, default: Boolean): Boolean {
        // 公共 properties 优先（微信进程 XSP 常读不到）
        // PublicConfigStore 的 defaults 会补全缺键，settings_entry 默认 true
        return PublicConfigStore.getBoolean(key, default)
    }

    private fun cfgStr(key: String, default: String): String {
        return PublicConfigStore.getString(key, default)
    }
}
