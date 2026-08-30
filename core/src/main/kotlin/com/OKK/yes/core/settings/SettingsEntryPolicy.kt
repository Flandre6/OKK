package com.OKK.yes.core.settings

object SettingsEntryPolicy {
    const val entryKey = "abc_module_settings_entry"
    const val entryTitle = "OKK"
    const val entrySubtitle = ""

    const val modernMainSettingsClass = "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
    const val modernBaseSettingsClass = "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI"
    const val legacySettingsClass = "com.tencent.mm.plugin.setting.ui.setting.SettingsUI"

    fun shouldInjectInto(className: String): Boolean {
        // 明确排除：群聊信息 / 单聊详情 / 联系人资料 等
        if (className.contains("ChatroomInfo", ignoreCase = true)) return false
        if (className.contains("ContactInfo", ignoreCase = true)) return false
        if (className.contains("SingleChatInfo", ignoreCase = true)) return false
        if (className.contains("ChatInfoUI", ignoreCase = true)) return false

        if (className == modernMainSettingsClass || className == legacySettingsClass) return true
        if (className == modernBaseSettingsClass) return true
        // 兼容微信改包路径：仅主设置相关
        if (className.endsWith(".MainSettingsUI")) return true
        if (className.endsWith(".SettingsUI") && className.contains("plugin.setting") &&
            !className.contains("Chatroom", ignoreCase = true)
        ) {
            return true
        }
        return false
    }
}
