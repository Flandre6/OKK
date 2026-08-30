package com.OKK.yes.core.hooks

/**
 * Bottom tab config. Keep a short memory cache, but always load from the
 * public config file so WeChat restart does not fall back to stale XSP values.
 */
object BottomTabConfig {
    const val KEY_HIDE_TITLE = "bottom_tab_hide_title"
    /** 隐藏默认底栏（隐藏微信原生底栏，不影响悬浮底栏） */
    const val KEY_HIDE_BAR = "bottom_tab_hide_bar"
    const val KEY_FLOATING = "bottom_tab_floating"
    const val KEY_FLOATING_LABELS = "bottom_tab_floating_labels"
    const val KEY_FLOATING_BADGE = "bottom_tab_floating_badge"
    const val KEY_TITLE_CHATS = "bottom_tab_title_chats"
    const val KEY_TITLE_CONTACTS = "bottom_tab_title_contacts"
    const val KEY_TITLE_DISCOVER = "bottom_tab_title_discover"
    const val KEY_TITLE_ME = "bottom_tab_title_me"

    val DEFAULT_LABELS = listOf("微信", "通讯录", "发现", "我")

    @Volatile
    private var lastLoadTime = 0L

    @Volatile
    private var cachedHideTitle = false

    @Volatile
    private var cachedHideBar = false

    @Volatile
    private var cachedFloating = false

    @Volatile
    private var cachedFloatingLabels = true

    @Volatile
    private var cachedFloatingBadge = true

    @Volatile
    private var cachedLabels: List<String> = DEFAULT_LABELS

    fun hideTitle(now: Long = System.currentTimeMillis()): Boolean {
        ensureLoaded(now)
        // 悬浮底栏开启时由悬浮栏自绘文字，不再走原生隐标题
        if (cachedFloating) return false
        return cachedHideTitle
    }

    /** 隐藏默认底栏（隐藏微信原生底栏，与悬浮底栏解耦） */
    fun hideBar(now: Long = System.currentTimeMillis()): Boolean {
        ensureLoaded(now)
        return cachedHideBar
    }

    fun floatingEnabled(now: Long = System.currentTimeMillis()): Boolean {
        ensureLoaded(now)
        return cachedFloating
    }

    fun floatingLabels(now: Long = System.currentTimeMillis()): Boolean {
        ensureLoaded(now)
        return cachedFloatingLabels
    }

    fun floatingBadge(now: Long = System.currentTimeMillis()): Boolean {
        ensureLoaded(now)
        return cachedFloatingBadge
    }

    /** 四个底栏标题（可自定义，空则回退默认） */
    fun floatingTabLabels(now: Long = System.currentTimeMillis()): List<String> {
        ensureLoaded(now)
        return cachedLabels
    }

    private fun ensureLoaded(now: Long) {
        // 标题要尽快跟设置页一致，缓存别太久
        if (now - lastLoadTime < 800L && lastLoadTime > 0L) return
        lastLoadTime = now
        cachedHideTitle = PublicConfigStore.getBoolean(KEY_HIDE_TITLE, false)
        cachedHideBar = PublicConfigStore.getBoolean(KEY_HIDE_BAR, false)
        cachedFloating = PublicConfigStore.getBoolean(KEY_FLOATING, false)
        cachedFloatingLabels = PublicConfigStore.getBoolean(KEY_FLOATING_LABELS, true)
        cachedFloatingBadge = PublicConfigStore.getBoolean(KEY_FLOATING_BADGE, true)
        cachedLabels = listOf(
            PublicConfigStore.getString(KEY_TITLE_CHATS, DEFAULT_LABELS[0]).ifBlank { DEFAULT_LABELS[0] },
            PublicConfigStore.getString(KEY_TITLE_CONTACTS, DEFAULT_LABELS[1]).ifBlank { DEFAULT_LABELS[1] },
            PublicConfigStore.getString(KEY_TITLE_DISCOVER, DEFAULT_LABELS[2]).ifBlank { DEFAULT_LABELS[2] },
            PublicConfigStore.getString(KEY_TITLE_ME, DEFAULT_LABELS[3]).ifBlank { DEFAULT_LABELS[3] }
        )
    }

    /** 设置页改完标题后调用，强制下次读盘 */
    fun invalidate() {
        lastLoadTime = 0L
    }

    fun debugPrefsState(): String =
        "hideTitle=${hideTitle()} hideBar=${hideBar()} floating=${floatingEnabled()} " +
            "labels=${floatingLabels()} badge=${floatingBadge()} " +
            "titles=${floatingTabLabels()}"
}
