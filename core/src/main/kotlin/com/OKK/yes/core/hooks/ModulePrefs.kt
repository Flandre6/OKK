package com.OKK.yes.core.hooks

import de.robv.android.xposed.XSharedPreferences
import java.util.concurrent.atomic.AtomicReference

/**
 * 模块配置统一入口（内存读，避免热路径反复 XSP/IO）。
 *
 * 重要：微信进程里 XSP 经常 **读不到** 模块私有 prefs。
 * 此时 [isReadable] 为 false，调用方应走公共文件等兜底，
 * **不能**把 getBoolean 的默认值当成用户真实配置（圆形头像曾因此永久失效）。
 */
object ModulePrefs {
    private const val PACKAGE_NAME = "com.OKK.yes"
    private const val PREFS_NAME = "abc_prefs"

    /** 热路径缓存 TTL */
    private const val CACHE_TTL_MS = 5_000L

    private data class Snapshot(
        val loadedAt: Long,
        /** XSP 是否真正读到了文件内容 */
        val readable: Boolean,
        val map: Map<String, Any?>
    )

    private val snapshot = AtomicReference<Snapshot?>(null)

    /** 最近一次快照是否来自可读的 XSP（false = 应走公共配置兜底） */
    fun isReadable(): Boolean = currentSnapshot().readable

    fun getBoolean(key: String, default: Boolean): Boolean {
        val snap = currentSnapshot()
        if (!snap.readable) return default
        val v = snap.map[key]
        return when (v) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> default
        }
    }

    fun getString(key: String, default: String): String {
        val snap = currentSnapshot()
        if (!snap.readable) return default
        val v = snap.map[key]
        return (v as? String) ?: default
    }

    fun getFloat(key: String, default: Float): Float {
        val snap = currentSnapshot()
        if (!snap.readable) return default
        val v = snap.map[key]
        return when (v) {
            is Float -> v
            is Double -> v.toFloat()
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: default
            else -> default
        }
    }

    fun getInt(key: String, default: Int): Int {
        val snap = currentSnapshot()
        if (!snap.readable) return default
        val v = snap.map[key]
        return when (v) {
            is Int -> v
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    fun contains(key: String): Boolean {
        val snap = currentSnapshot()
        return snap.readable && snap.map.containsKey(key)
    }

    fun invalidate() {
        snapshot.set(null)
    }

    private fun currentSnapshot(now: Long = System.currentTimeMillis()): Snapshot {
        val old = snapshot.get()
        if (old != null && now - old.loadedAt < CACHE_TTL_MS) {
            return old
        }
        val fresh = loadSnapshot(now)
        snapshot.set(fresh)
        return fresh
    }

    private fun loadSnapshot(now: Long): Snapshot {
        // 优先公共配置（嵌入设置只写这里，微信进程一定可读）
        runCatching {
            PublicConfigStore.reload(force = true)
            val pub = PublicConfigStore.snapshot()
            if (pub.isNotEmpty() && (
                    pub.containsKey("anti_revoke") ||
                        pub.containsKey("virtual_location_enabled") ||
                        pub.containsKey("round_avatar_enabled") ||
                        pub.containsKey("bubble_enabled")
                    )
            ) {
                return Snapshot(
                    loadedAt = now,
                    readable = true,
                    map = pub.mapValues { (_, v) ->
                        when {
                            v.equals("true", true) -> true
                            v.equals("false", true) -> false
                            else -> v
                        }
                    }
                )
            }
        }

        return runCatching {
            val prefs = XSharedPreferences(PACKAGE_NAME, PREFS_NAME)
            prefs.reload()
            val readable = prefs.contains("anti_revoke") ||
                prefs.contains("round_avatar_enabled") ||
                prefs.contains("virtual_location_enabled") ||
                prefs.contains("bubble_enabled")

            if (!readable) {
                return@runCatching Snapshot(loadedAt = now, readable = false, map = emptyMap())
            }

            Snapshot(
                loadedAt = now,
                readable = true,
                map = linkedMapOf(
                    "anti_revoke" to prefs.getBoolean("anti_revoke", true),
                    "revoke_notice_enabled" to prefs.getBoolean("revoke_notice_enabled", true),
                    "anti_revoke_keep_self" to prefs.getBoolean("anti_revoke_keep_self", false),
                    "anti_revoke_notice_text" to prefs.getString(
                        "anti_revoke_notice_text",
                        "{name}撤回了一条消息"
                    ),
                    "media_protect_enabled" to prefs.getBoolean("media_protect_enabled", true),
                    "anti_moments_delete" to prefs.getBoolean("anti_moments_delete", true),
                    "swipe_quote" to prefs.getBoolean("swipe_quote", true),
                    "swipe_repeat" to prefs.getBoolean("swipe_repeat", false),
                    "bubble_enabled" to prefs.getBoolean("bubble_enabled", true),
                    "settings_entry_enabled" to prefs.getBoolean("settings_entry_enabled", true),
                    "bottom_tab_hide_title" to prefs.getBoolean("bottom_tab_hide_title", false),
                    "fold_banner_fixed" to prefs.getBoolean("fold_banner_fixed", true),
                    "bottom_tab_floating" to prefs.getBoolean("bottom_tab_floating", false),
                    "bottom_tab_floating_labels" to prefs.getBoolean("bottom_tab_floating_labels", true),
                    "bottom_tab_floating_badge" to prefs.getBoolean("bottom_tab_floating_badge", true),
                    "bottom_tab_title_chats" to prefs.getString("bottom_tab_title_chats", "微信"),
                    "bottom_tab_title_contacts" to prefs.getString("bottom_tab_title_contacts", "通讯录"),
                    "bottom_tab_title_discover" to prefs.getString("bottom_tab_title_discover", "发现"),
                    "bottom_tab_title_me" to prefs.getString("bottom_tab_title_me", "我"),
                    "detail_enabled" to prefs.getBoolean("detail_enabled", true),
                    "detail_template" to prefs.getString("detail_template", "\${time} \${relativeTime}"),
                    "detail_time_pattern" to prefs.getString("detail_time_pattern", "MM-dd HH:mm:ss"),
                    "detail_text_size" to prefs.getString("detail_text_size", "12"),
                    "detail_horizontal_margin" to prefs.getString("detail_horizontal_margin", "0"),
                    "detail_left_margin" to prefs.getString("detail_left_margin", "0"),
                    "detail_right_margin" to prefs.getString("detail_right_margin", "0"),
                    "detail_text_color" to prefs.getString("detail_text_color", "#CCFFFFFF"),
                    "detail_text_color_light" to prefs.getString("detail_text_color_light", "#CC000000"),
                    "detail_text_color_dark" to prefs.getString("detail_text_color_dark", "#CCFFFFFF"),
                    "detail_click_show" to prefs.getBoolean("detail_click_show", false),
                    "input_stats_enabled" to prefs.getBoolean("input_stats_enabled", true),
                    "input_stats_count_send" to prefs.getBoolean("input_stats_count_send", true),
                    "input_stats_template" to prefs.getString("input_stats_template", "今日已发\${totalMsg}条"),
                    "round_avatar_enabled" to prefs.getBoolean("round_avatar_enabled", false),
                    "round_avatar_radius" to prefs.getString("round_avatar_radius", "0.36"),
                    "virtual_location_enabled" to prefs.getBoolean("virtual_location_enabled", false),
                    "virtual_location_latitude" to prefs.getString(
                        "virtual_location_latitude",
                        ""
                    ),
                    "virtual_location_longitude" to prefs.getString(
                        "virtual_location_longitude",
                        ""
                    )
                )
            )
        }.getOrElse {
            snapshot.get() ?: Snapshot(loadedAt = now, readable = false, map = emptyMap())
        }
    }
}
