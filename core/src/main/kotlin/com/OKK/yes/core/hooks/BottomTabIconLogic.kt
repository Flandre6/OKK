package com.OKK.yes.core.hooks

/**
 * 底栏标题相关轻量工具（单测友好）。
 * 当前产品只做「隐藏标题」，复杂改图标/改标题逻辑已收敛。
 */
object BottomTabIconLogic {
    private val DEFAULT_TITLES = setOf(
        "微信", "通讯录", "发现", "我",
        "WeChat", "Chats", "Contacts", "Discover", "Me"
    )

    fun isDefaultTabTitle(text: CharSequence?): Boolean {
        val t = text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return false
        return t in DEFAULT_TITLES
    }
}
