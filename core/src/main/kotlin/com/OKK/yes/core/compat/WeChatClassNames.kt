package com.OKK.yes.core.compat

import com.OKK.yes.core.common.SecureStrings

/**
 * 跨 8.0.69–8.0.76（国服/Play）**稳定**的类名 / 特征字符串。
 *
 * 混淆包名（iy0 / fy3 / hy3 / v05 等）随版本漂移，禁止写死为唯一路径。
 * 运行时优先 DexKit + 本表稳定名，再尝试 [obfuscatedFallbacks]。
 *
 * 反逆向：所有反射目标字符串统一走 [SecureStrings.d] 运行时解码，明文不落 DEX。
 */
object WeChatClassNames {
    private fun s(e: String): String = SecureStrings.d(e)

    // ── UI（全版本 class 表均命中）──────────────────────────────────────────
    val LAUNCHER_UI: String get() = s("E3nBP0TQNFnSFgLaLwLeNwLHNEnQNEnHdEHcOQ==")
    val MAIN_SETTINGS_UI: String get() =
        s("+g9f1DRFxy5J4DRF0hcCxD9C7D1C2i5Y1ikC2i8C1DRFxy5JwHRC2j1Z3yoC3jcCxzRJ0DRJx3RB3Dk=")
    val LEGACY_SETTINGS_UI: String get() =
        s("ZeYpS90zWMc/f509QtouWNYpAtovAtQ0RccuScB0Qto9Wd8qAt43Asc0SdA0Scd0Qdw5")
    val SETTING_GROUP_PERSONAL: String get() =
        s("Q9U0Zd87QtwpXtYKXMY1XvQ9QtouWNYJAsA9QtouWNYpAsQ/Quw9QtouWNYpAtovAtQ0RccuScB0Qto9Wd8qAt43Asc0SdA0Scd0Qdw5")
    val SETTINGS_PERSONAL_INFO: String get() =
        s("ZeY1St0TQNI0Q8AoSeMpS90zWMc/f509QtouWNYpAtovAtQ0RccuScB0Qto9Wd8qAt43Asc0SdA0Scd0Qdw5")
    val CONTACT_INFO_UI: String get() =
        s("E3ncPEL6Lk/SLkLcGQLaLwLWNkXVNV7DdELaPVnfKgLeNwLHNEnQNEnHdEHcOQ==")
    val CHAT_FOOTER: String get() =
        s("KEnHNUP1Lk3bGQLHO0TQdEXGdEfXKULaPVnfKgLeNwLHNEnQNEnHdEHcOQ==")
    val MM_NEAT_TEXT: String get() =
        s("W9YzesciSYQuTdYUYf50WNY9SNotAtovAt43Asc0SdA0Scd0Qdw5")
    val X2C_TEXT: String get() =
        s("xD9F5S5U1g5vgQIC0GhUnS1J2iwC3jcCxzRJ0DRJx3RB3Dk=")
    val SELF_QR: String get() =
        s("+g9J1zVv4QtK3z9/nT1C2i5Y1ikC2i8C1DRFxy5JwHRC2j1Z3yoC3jcCxzRJ0DRJx3RB3Dk=")
    val SELF_QR_COLORFUL: String get() =
        s("E3nWPkPwCH3VNkngNlnVKEPfNW+dPULaLljWKQLaLwLUNEXHLknAdELaPVnfKgLeNwLHNEnQNEnHdEHcOQ==")
    val OFFLINE_PAY: String get() =
        s("+g9J0DRNwS5C9j9C2jZK1RVY1jZA0g0C2i8C1jRF3zxK3HRC2j1Z3yoC3jcCxzRJ0DRJx3RB3Dk=")
    val FAVORITE: String get() =
        s("+g9U1j5C+j9Y2ihDxTtqnTNZnSxN1XRC2j1Z3yoC3jcCxzRJ0DRJx3RB3Dk=")
    val MALL_INDEX: String get() =
        s("aFr6D1TWPkL6NkDSFwLaLwLfNk3edELaPVnfKgLeNwLHNEnQNEnHdEHcOQ==")
    val PREFERENCE: String get() =
        s("1jlC1ihJ1T9e43RJ0DRJwT9K1ihcnT9f0jgC2i8C3jcCxzRJ0DRJx3RB3Dk=")
    val ICON_PREFERENCE: String get() =
        s("SdA0ScE/StYofN01T/p0SdA0ScE/StYoXJ0/X9I4AtovAt43Asc0SdA0Scd0Qdw5")

    // ── 特征字符串（DexKit / 日志）────────────────────────────────────────
    val DO_REVOKE_LOG: String get() =
        s("X5ZnWNYdX9p6X5ZnXtYxQNIuDNd/EdcTS8AXWsEJQN4iDNQpYdYxQ8U/ftw+")
    val SETTING_DATA_SOURCE_TAG: String get() =
        s("1jlexjV/0i5N9z1C2i5Y1gkC1Clh3ChP2hc=")
    val SETTING_DATA_SIZE_LOG: String get() =
        s("k2AM1iBFwHoA0i5N13pY1j0=")
    val SETTING_KEY_PERSONAL: String get() =
        s("Q9U0Zd87QtwpXtYKc90zTf4FXMY1XvQ9QtouWNYJ")

    /**
     * 8.0.69 分析树里的混淆兜底（仅当 DexKit / 稳定名失败时尝试）。
     * 其它版本这些类名大概率不存在，必须 catch。
     */
    object Obfuscated69 {
        private fun s(e: String): String = SecureStrings.d(e)
        val DO_REVOKE: String get() = s("xnQcyjM=")
        val SETTING_ITEM_BASE: String get() = s("2nQfyjw=")
        val SETTING_GROUP_BASE: String get() = s("1nQfyiI=")
        val SETTING_DATA_SOURCE: String get() = s("13QfyjI=")
        val SETTING_ROW_WRAPPER: String get() = s("1nQfyjI=")
        val PLUGIN_HUB: String get() = s("33QZgyw=")
    }
}
