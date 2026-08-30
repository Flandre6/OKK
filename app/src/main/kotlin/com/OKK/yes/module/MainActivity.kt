package com.OKK.yes

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.OKK.yes.core.hooks.PublicConfigStore
import com.OKK.yes.core.hooks.RoundAvatarConfig
import com.OKK.yes.core.hooks.VirtualLocationConfig
import com.OKK.yes.loader.EmbeddedSettingsUi
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.util.Locale

/**
 * AChat 设置主界面（对齐 HChat 2.9.6 信息架构）。
 *
 * 原理：
 * 1. **分类目录 → 功能列表 → 详情配置** 三级导航，避免首页堆满开关
 * 2. 列表行只展示标题/说明/状态；复杂项进弹窗，底部「保存」批量写盘
 * 3. 开关用 [SharedPreferences.apply] + 后台同步公共配置，减轻主线程卡顿
 * 4. 微信进程侧用 XSharedPreferences / 公共 properties 读取
 */
class MainActivity : AppCompatActivity() {

    private object Keys {
        const val PREFS = "abc_prefs"

        const val ANTI_REVOKE = "anti_revoke"
        const val REVOKE_NOTICE = "revoke_notice_enabled"
        const val ANTI_REVOKE_KEEP_SELF = "anti_revoke_keep_self"
        const val ANTI_REVOKE_NOTICE_TEXT = "anti_revoke_notice_text"
        const val MEDIA_PROTECT = "media_protect_enabled"
        const val ANTI_MOMENTS_DELETE = "anti_moments_delete"
        /** 底栏自定义总开关（对齐 WA） */
        const val BOTTOM_TAB_ENABLED = "bottom_tab_enabled"
        const val BOTTOM_TAB_MOD_ICON = "bottom_tab_mod_icon"
        const val BOTTOM_TAB_MOD_TITLE = "bottom_tab_mod_title"
        const val BOTTOM_TAB_HIDE_TITLE = "bottom_tab_hide_title"
        const val BOTTOM_TAB_HIDE_BAR = "bottom_tab_hide_bar"
        const val BOTTOM_TAB_TITLE_CHATS = "bottom_tab_title_chats"
        const val BOTTOM_TAB_TITLE_CONTACTS = "bottom_tab_title_contacts"
        const val BOTTOM_TAB_TITLE_DISCOVER = "bottom_tab_title_discover"
        const val BOTTOM_TAB_TITLE_ME = "bottom_tab_title_me"
        /** 旧版仅换图标开关，读配置时兼容 */
        const val BOTTOM_TAB_ICON_LEGACY = "bottom_tab_icon_enabled"
        const val SWIPE_QUOTE = "swipe_quote"
        const val SWIPE_REPEAT = "swipe_repeat"
        /** 输入为空时删除键取消引用（对齐 HChat quote_delete_clear） */
        const val QUOTE_DELETE_CLEAR = "quote_delete_clear"
        const val DETAIL_ENABLED = "detail_enabled"
        const val DETAIL_TEMPLATE = "detail_template"
        const val DETAIL_TIME_PATTERN = "detail_time_pattern"
        const val DETAIL_TEXT_SIZE = "detail_text_size"
        const val DETAIL_HORIZONTAL_MARGIN = "detail_horizontal_margin"
        const val DETAIL_LEFT_MARGIN = "detail_left_margin"
        const val DETAIL_RIGHT_MARGIN = "detail_right_margin"
        const val DETAIL_TEXT_COLOR = "detail_text_color"
        const val DETAIL_TEXT_COLOR_LIGHT = "detail_text_color_light"
        const val DETAIL_TEXT_COLOR_DARK = "detail_text_color_dark"
        const val DETAIL_CLICK_SHOW = "detail_click_show"
        const val INPUT_STATS_ENABLED = "input_stats_enabled"
        const val INPUT_STATS_COUNT_SEND = "input_stats_count_send"
        const val INPUT_STATS_TEMPLATE = "input_stats_template"
        const val BUBBLE_ENABLED = "bubble_enabled"
        const val ROUND_AVATAR = "round_avatar_enabled"
        const val ROUND_AVATAR_RADIUS = "round_avatar_radius"
        /** 虚拟定位（对齐 WAuxiliary LocationHook） */
        const val VIRTUAL_LOCATION = "virtual_location_enabled"
        const val VIRTUAL_LOCATION_LAT = "virtual_location_latitude"
        const val VIRTUAL_LOCATION_LON = "virtual_location_longitude"
        /** PC 自动登录（对齐 WAuxiliary AutoLoginWinHook） */
        const val AUTO_LOGIN_WIN = "auto_login_win_enabled"
        const val AUTO_LOGIN_WIN_SYNC = "auto_login_win_sync_msg"
        const val AUTO_LOGIN_WIN_SHOW = "auto_login_win_show_device"
        const val AUTO_LOGIN_WIN_DEVICE = "auto_login_win_auto_device"
        const val AUTO_LOGIN_WIN_CLICK = "auto_login_win_auto_click"
        const val SETTINGS_ENTRY = "settings_entry_enabled"
        const val NIGHT_MODE = "night_mode"
        /** true=跟随微信深色；false=用 night_mode 手动 */
        const val NIGHT_MODE_FOLLOW = "night_mode_follow"
        /** 设置页 UI：悬浮底栏样式（仅 AChat 壳） */
        const val FLOATING_NAV = "ui_floating_nav"

        const val UI_DEFAULTS_FLAG = "ui_defaults_20260712_applied"
    }

    private data class FieldRow(val view: View, val input: EditText)

    private data class FeatureDef(
        val title: String,
        val summary: String,
        val key: String,
        val defaultValue: Boolean = true,
        val configurable: Boolean = false
    )

    /** 对齐 HChat 底栏：实用 / 状态 / 设置 */
    private enum class Tab(val label: String, val icon: String) {
        Utility("实用", "▦"),
        Plugin("状态", "◎"),
        Settings("设置", "⚙")
    }

    /**
     * 对齐 HChat `r3` 中文分组：注册 key 归类到展示分类。
     * 首页只显示分类与数量，点进二级再列功能。
     */
    private enum class FeatureCategory(
        val title: String,
        val hint: String
    ) {
        Chat("聊天", "防撤回 · 左滑 · 消息底部"),
        Protect("消息保护", "媒体缓存 · 朋友圈防删"),
        /** 已并入消息保护，保留枚举避免旧导航状态崩溃，列表恒为空 */
        Moments("朋友圈", "已并入消息保护"),
        Beauty("美化", "气泡 · 圆形头像"),
        Assist("辅助", "虚拟定位 · PC 登录"),
        Interface("界面", "底栏 · 设置入口")
    }

    /**
     * 实用 Tab 导航栈（对齐 HChat：目录 → 列表 → 详情，不用弹窗）。
     * Home → Category → FeatureDetail
     */
    private sealed class UtilityNav {
        data object Home : UtilityNav()
        data class Category(val cat: FeatureCategory) : UtilityNav()
        data class FeatureDetail(val cat: FeatureCategory, val featureKey: String) : UtilityNav()
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var scrollView: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var navBar: LinearLayout
    /** 详情页固定底栏（重置/保存），在 ScrollView 外，保证可点 */
    private lateinit var actionBarHost: LinearLayout
    private var selectedTab = Tab.Utility
    private var utilityNav: UtilityNav = UtilityNav.Home
    private var searchQuery: String = ""

    /** 虚拟定位详情页输入框引用（地图选点回填） */
    private var locationLatInput: EditText? = null
    private var locationLonInput: EditText? = null

    // 软件壳配色（HChat / AChat 原版观感）
    private var pageBackground = Color.parseColor("#F7F8FA")
    private var surfaceColor = Color.WHITE
    private var fieldColor = Color.parseColor("#F0F1F3")
    private var titleColor = Color.parseColor("#050505")
    private var primaryText = Color.parseColor("#111111")
    private var secondaryText = Color.parseColor("#72757C")
    private var sectionText = Color.parseColor("#7F8594")
    private val accent = Color.parseColor("#07C160")
    private var selectedContainer = Color.parseColor("#EAF7EF")
    private var outline = Color.parseColor("#EBEDF0")
    private var switchOffTrack = Color.parseColor("#D8DAD9")
    private var tipBackground = Color.parseColor("#F0F7F3")

    // ── lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Keys.PREFS, MODE_PRIVATE)
        ensureDefaultPrefs()
        // 与嵌入页同步主题配置（公共 properties）
        runCatching {
            PublicConfigStore.ensureDefaults(this)
            val follow = PublicConfigStore.getBoolean(Keys.NIGHT_MODE_FOLLOW, true)
            val night = if (follow) {
                isHostDark()
            } else {
                PublicConfigStore.getBoolean(Keys.NIGHT_MODE, false)
            }
            prefs.edit()
                .putBoolean(Keys.NIGHT_MODE_FOLLOW, follow)
                .putBoolean(Keys.NIGHT_MODE, night)
                .apply()
        }
        applyPalette()
        ensureStorageAccess()
        makePrefsReadable()
        // 每次打开设置页，把圆形头像配置强制同步到公共文件（微信侧可读）
        syncRoundAvatarPublic()
        applySystemBars()
        setContentView(buildScreen())
        applyNavChrome()
        installBackHandler()
        renderContent()
        renderNavigation()
    }

    override fun onResume() {
        super.onResume()
        // 从「所有文件访问」设置页返回后，再同步一次公共配置
        if (::prefs.isInitialized) {
            syncRoundAvatarPublic()
            // 微信内地图选点结果（公共文件）
            consumeMapPickResultIfAny()
        }
    }

    /** 读取微信进程写回的地图选点结果 */
    private fun consumeMapPickResultIfAny() {
        val pair = VirtualLocationConfig.readMapPickResult(consume = true) ?: return
        val (lat, lon) = pair
        val latStr = String.format(Locale.US, "%.6f", lat)
        val lonStr = String.format(Locale.US, "%.6f", lon)
        locationLatInput?.setText(latStr)
        locationLonInput?.setText(lonStr)
        prefs.edit()
            .putBoolean(Keys.VIRTUAL_LOCATION, true)
            .putString(Keys.VIRTUAL_LOCATION_LAT, latStr)
            .putString(Keys.VIRTUAL_LOCATION_LON, lonStr)
            .apply()
        VirtualLocationConfig.writePublic(true, lat, lon, context = this, async = true)
        makePrefsReadable()
        toast("已选点 $latStr, $lonStr")
        // 若当前在详情页，刷新展示
        if (utilityNav is UtilityNav.FeatureDetail &&
            (utilityNav as UtilityNav.FeatureDetail).featureKey == Keys.VIRTUAL_LOCATION
        ) {
            renderContent()
        }
    }

    /**
     * 系统返回 / 手势返回：详情 → 分类 → 实用首页，
     * 仅在实用首页再退出。避免子级页直接 finish。
     */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!navigateUp()) {
                        // 首页：禁用本回调后走系统默认 finish
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )
    }

    /** @return true 已处理为返回上一级 */
    private fun navigateUp(): Boolean {
        if (selectedTab != Tab.Utility) return false
        return when (val nav = utilityNav) {
            is UtilityNav.FeatureDetail -> {
                utilityNav = UtilityNav.Category(nav.cat)
                renderContent()
                true
            }
            is UtilityNav.Category -> {
                utilityNav = UtilityNav.Home
                searchQuery = ""
                renderContent()
                true
            }
            UtilityNav.Home -> false
        }
    }

    /** 申请存储权限，保证 /sdcard/AChat/achat_config.properties 可写 */
    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }.onFailure {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val need = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (need.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, need.toTypedArray(), 0x0A11)
            }
        }
    }

    private fun syncRoundAvatarPublic() {
        // 异步，不阻塞 onCreate/onResume（否则打开设置页背后微信会黑）
        runCatching {
            val radius = RoundAvatarConfig.clampRadius(
                prefs.getString(Keys.ROUND_AVATAR_RADIUS, "0.36")?.toFloatOrNull()
                    ?: RoundAvatarConfig.DEFAULT_RADIUS
            )
            RoundAvatarConfig.writePublic(
                enabled = bool(Keys.ROUND_AVATAR, false),
                radius = radius,
                context = this,
                async = true
            )
        }
    }

    // ── screen shell ───────────────────────────────────────────────────────

    private fun buildScreen(): View {
        // 内容滚动区 + 详情固定操作栏 + 贴底导航
        // 关键：重置/保存 必须在 ScrollView 外，否则点击常被滚动/焦点吞掉
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground)
            addView(
                ScrollView(this@MainActivity).apply {
                    scrollView = this
                    clipToPadding = false
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    isFillViewport = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                    isSmoothScrollingEnabled = true
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            content = this
                            orientation = LinearLayout.VERTICAL
                            // 软件壳：左右边距 + 圆角卡片
                            setPadding(dp(16), dp(12), dp(16), dp(20))
                            clipChildren = false
                            clipToPadding = false
                        },
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            // 详情页固定「重置 / 保存」（默认隐藏）
            addView(
                LinearLayout(this@MainActivity).apply {
                    actionBarHost = this
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(pageBackground)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    elevation = 0f
                    visibility = View.GONE
                    isClickable = false
                    isFocusable = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            // 悬浮圆角底栏（软件壳）
            addView(
                FrameLayout(this@MainActivity).apply {
                    setBackgroundColor(pageBackground)
                    setPadding(0, dp(4), 0, dp(12))
                    elevation = 0f
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            navBar = this
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            background = rounded(surfaceColor, dp(28))
                            elevation = 0f
                        },
                        FrameLayout.LayoutParams(
                            dp(268),
                            dp(56),
                            Gravity.CENTER_HORIZONTAL
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun renderContent() {
        content.removeAllViews()
        clearActionBar()
        content.addView(header())
        when (selectedTab) {
            Tab.Utility -> renderUtilityPage()
            Tab.Plugin -> renderStatusPage()
            Tab.Settings -> renderAboutPage()
        }
        scrollView.post { scrollView.scrollTo(0, 0) }
    }

    private fun clearActionBar() {
        if (!::actionBarHost.isInitialized) return
        actionBarHost.removeAllViews()
        actionBarHost.visibility = View.GONE
    }

    // ── pages（HChat 式：目录 → 列表 → 详情页，无弹窗）────────────────────

    private fun renderUtilityPage() {
        when (val nav = utilityNav) {
            UtilityNav.Home -> renderUtilityHome()
            is UtilityNav.Category -> renderCategoryPage(nav.cat)
            is UtilityNav.FeatureDetail -> renderFeatureDetailPage(nav.cat, nav.featureKey)
        }
    }

    /** 首页：分类目录（软件壳 / HChat 实用 Tab） */
    private fun renderUtilityHome() {
        section("实用功能")
        val cats = FeatureCategory.entries.mapNotNull { cat ->
            val items = featuresIn(cat)
            if (items.isEmpty()) return@mapNotNull null
            val on = items.count { featureOn(it) }
            categoryRow(
                title = cat.title,
                summary = "${items.size} 项 · 已开 $on",
                hint = cat.hint
            ) {
                utilityNav = UtilityNav.Category(cat)
                renderContent()
            }
        }
        card(*cats.toTypedArray())
        tipCard(
            title = "导航",
            body = "点分类进入功能列表。\n开关即时写盘；改完建议强停微信。"
        )
    }

    /** 二级：某分类下的功能列表（系统返回上一级） */
    private fun renderCategoryPage(cat: FeatureCategory) {
        val q = searchQuery.trim()
        val items = featuresIn(cat).filter { f ->
            q.isEmpty() ||
                f.title.contains(q, true) ||
                f.summary.contains(q, true)
        }
        if (items.isEmpty()) {
            tipCard(title = "", body = "无匹配功能")
            return
        }
        card(
            *items.map { f -> featureListRow(f) }.toTypedArray()
        )
        if (cat == FeatureCategory.Beauty) {
            tipCard(
                title = "素材路径",
                body = "/Android/media/com.tencent.mm/AChat/\nleft.9.png · right.9.png"
            )
        }
    }

    /**
     * 只登记 **已实现 Hook** 的功能，不做占位/半成品。
     * 右滑复读等未做稳的项不进列表。
     */
    private fun featuresIn(cat: FeatureCategory): List<FeatureDef> = when (cat) {
        FeatureCategory.Chat -> listOf(
            FeatureDef("防撤回", "拦截撤回，保留原消息与提示", Keys.ANTI_REVOKE, configurable = true),
            FeatureDef("左滑引用", "扩大左滑区域，滑动后引用回复", Keys.SWIPE_QUOTE),
            FeatureDef("删除键清引用", "输入为空时按删除取消引用", Keys.QUOTE_DELETE_CLEAR, defaultValue = false),
            FeatureDef("消息底部", "消息旁显示时间与自定义字段", Keys.DETAIL_ENABLED, configurable = true),
            FeatureDef("输入框统计", "输入框显示今日发送数", Keys.INPUT_STATS_ENABLED, configurable = true)
        )
        FeatureCategory.Protect -> listOf(
            FeatureDef("媒体保护", "尽量保留图/语音/视频缓存不被删", Keys.MEDIA_PROTECT),
            FeatureDef("朋友圈防删除", "对方删除后本地时间线仍可见", Keys.ANTI_MOMENTS_DELETE)
        )
        // 朋友圈并入消息保护，避免单独一类只有 1 项
        FeatureCategory.Moments -> emptyList()
        FeatureCategory.Beauty -> listOf(
            FeatureDef("气泡皮肤", "自定义 left/right .9 气泡", Keys.BUBBLE_ENABLED),
            FeatureDef("圆形头像", "可调圆度 · 推荐方圆 0.36", Keys.ROUND_AVATAR, defaultValue = false, configurable = true)
        )
        FeatureCategory.Assist -> listOf(
            FeatureDef(
                "虚拟定位",
                "将腾讯定位结果改为指定经纬度",
                Keys.VIRTUAL_LOCATION,
                defaultValue = false,
                configurable = true
            ),
            FeatureDef(
                "PC 自动登录",
                "登录页自动勾选并点登录",
                Keys.AUTO_LOGIN_WIN,
                defaultValue = false,
                configurable = true
            )
        )
        FeatureCategory.Interface -> listOf(
            FeatureDef("隐藏底栏标题", "底栏只留图标、去掉文字", Keys.BOTTOM_TAB_HIDE_TITLE),
            FeatureDef("微信设置入口", "设置页插入 AChat 入口", Keys.SETTINGS_ENTRY)
        )
    }

    private fun allFeatures(): List<FeatureDef> =
        FeatureCategory.entries.flatMap { featuresIn(it) }

    private fun featureOn(f: FeatureDef): Boolean = bool(f.key, f.defaultValue)

    private fun featureListRow(f: FeatureDef): View {
        val on = featureOn(f)
        // 可配置项：进详情；纯开关：列表直接拨
        return if (f.configurable) {
            navRow(
                title = f.title,
                summary = f.summary,
                trailing = if (on) "已开启" else "已关闭",
                trailingOn = on
            ) { openFeatureDetail(f) }
        } else {
            switchRow(
                title = f.title,
                summary = f.summary,
                key = f.key,
                defaultValue = f.defaultValue,
                onChanged = { /* 列表内刷新状态文案可选 */ }
            )
        }
    }

    private fun openFeatureDetail(f: FeatureDef) {
        val cat = FeatureCategory.entries.firstOrNull { cat ->
            featuresIn(cat).any { it.key == f.key }
        } ?: FeatureCategory.Chat
        utilityNav = UtilityNav.FeatureDetail(cat, f.key)
        renderContent()
    }

    /** 三级：功能详情菜单（整页，非弹窗） */
    private fun renderFeatureDetailPage(cat: FeatureCategory, featureKey: String) {
        val feature = featuresIn(cat).firstOrNull { it.key == featureKey }
            ?: allFeatures().firstOrNull { it.key == featureKey }
        val title = feature?.title ?: "功能配置"
        val summary = feature?.summary ?: cat.hint

        // 标题已在顶栏；正文只放配置
        tipCard(title = "", body = summary)

        when (featureKey) {
            Keys.ANTI_REVOKE -> renderAntiRevokeDetail()
            Keys.DETAIL_ENABLED -> renderMessageDetailPage()
            Keys.INPUT_STATS_ENABLED -> renderInputStatsDetail()
            Keys.ROUND_AVATAR -> renderRoundAvatarDetail()
            Keys.VIRTUAL_LOCATION -> renderVirtualLocationDetail()
            Keys.AUTO_LOGIN_WIN -> renderAutoLoginWinDetail()
            else -> {
                tipCard(title = "列表开关", body = "此项无下级菜单，系统返回上一级即可拨开关。")
            }
        }
    }

    /**
     * 状态 Tab：按分类汇总，不逐项堆砌。
     * 点分类可跳到实用页对应列表。
     */
    private fun renderStatusPage() {
        val features = allFeatures()
        val onCount = features.count { featureOn(it) }
        val offCount = features.size - onCount

        section("总览")
        card(
            infoRow("已开启", "全部功能开关", "$onCount / ${features.size}"),
            infoRow("已关闭", "可在实用页分类里开启", "$offCount 项", accentWhenOn = false),
            infoRow("生效", "改完后强停微信更稳妥", "XSP")
        )

        section("分类状态")
        val catRows = FeatureCategory.entries.mapNotNull { cat ->
            val items = featuresIn(cat)
            if (items.isEmpty()) return@mapNotNull null
            val on = items.count { featureOn(it) }
            val allOn = on == items.size
            val noneOn = on == 0
            val badge = when {
                noneOn -> "全关"
                allOn -> "全开"
                else -> "$on/${items.size}"
            }
            val names = items.filter { featureOn(it) }.joinToString("、") { it.title }
            val summary = when {
                noneOn -> cat.hint
                names.length <= 28 -> names
                else -> names.take(26) + "…"
            }
            categoryStatusRow(
                title = cat.title,
                summary = summary,
                badge = badge,
                accentWhenOn = !noneOn
            ) {
                selectedTab = Tab.Utility
                utilityNav = UtilityNav.Category(cat)
                searchQuery = ""
                renderContent()
                renderNavigation()
            }
        }
        card(*catRows.toTypedArray())

        section("运行时")
        card(
            infoRow("Hook", "DexKit · SQLite · 气泡", "就绪"),
            infoRow(
                "气泡素材",
                bubbleAssetStatus(),
                if (bool(Keys.BUBBLE_ENABLED)) "启用" else "关闭",
                accentWhenOn = bool(Keys.BUBBLE_ENABLED)
            )
        )
    }

    /** 状态页分类行：徽章 + 箭头，点击进实用对应分类 */
    private fun categoryStatusRow(
        title: String,
        summary: String,
        badge: String,
        accentWhenOn: Boolean,
        action: () -> Unit
    ): View {
        return baseRow(title, summary).apply {
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground()
            setOnClickListener { action() }
            addView(
                statusBadge(badge, accentWhenOn),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dp(8)
                }
            )
            addView(
                text("›", 18f, secondaryText, false).apply {
                    includeFontPadding = true
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dp(4)
                }
            )
        }
    }

    private fun renderAboutPage() {
        val follow = bool(Keys.NIGHT_MODE_FOLLOW, true)
        val wxDark = isHostDark()
        section("界面")
        card(
            switchRow(
                title = "跟随微信深色",
                summary = if (wxDark) "当前宿主：深色" else "当前宿主：浅色",
                key = Keys.NIGHT_MODE_FOLLOW,
                defaultValue = true,
                onChanged = {
                    if (!bool(Keys.NIGHT_MODE_FOLLOW, true)) {
                        // 切到手动时，把手动值设为当前宿主深浅
                        val dark = isHostDark()
                        prefs.edit().putBoolean(Keys.NIGHT_MODE, dark).apply()
                        PublicConfigStore.putBoolean(Keys.NIGHT_MODE, dark, async = true)
                    }
                    PublicConfigStore.putBoolean(
                        Keys.NIGHT_MODE_FOLLOW,
                        bool(Keys.NIGHT_MODE_FOLLOW, true),
                        async = true
                    )
                    refreshChrome()
                }
            ),
            switchRow(
                title = "夜间模式",
                summary = if (follow) "已跟随微信（关闭上方跟随后可手动）" else "手动指定设置页配色",
                key = Keys.NIGHT_MODE,
                defaultValue = resolveNight(),
                enabled = !follow
            ),
            switchRow(
                title = "悬浮底栏",
                summary = "底栏使用悬浮圆角样式（对齐 HChat 壳）",
                key = Keys.FLOATING_NAV,
                defaultValue = true,
                onChanged = { applyNavChrome(); renderNavigation() }
            )
        )

        section("关于")
        card(
            infoRow("版本", "AChat Module", "1.1.7"),
            infoRow("简介", "微信功能增强模块", "LSPosed"),
            infoRow("作用域", "仅主进程注入", "com.tencent.mm"),
            infoRow("方案", "DexKit + Xposed Hook", "运行中"),
            infoRow("UI 参考", "分类 · 列表 · 详情菜单", "HChat 2.9.6")
        )

        section("维护")
        card(
            actionRow(
                title = "恢复默认开关",
                summary = "重置功能开关为默认，并保留文案模板",
                actionLabel = "重置"
            ) { confirmResetDefaults() }
        )
    }

    // ── navigation / header ────────────────────────────────────────────────

    private fun renderNavigation() {
        navBar.removeAllViews()
        // 与嵌入页一致：固定胶囊尺寸，三格均分，避免选中态参差
        val idle = if (resolveNight()) Color.parseColor("#8E8E8E") else Color.parseColor("#5C6066")
        val pillW = dp(72)
        val pillH = dp(48)
        Tab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val color = if (selected) accent else idle
            navBar.addView(
                FrameLayout(this).apply {
                    isClickable = true
                    isFocusable = true
                    foreground = selectableItemBackground()
                    setOnClickListener {
                        if (selectedTab == tab) return@setOnClickListener
                        selectedTab = tab
                        if (tab != Tab.Utility) {
                            utilityNav = UtilityNav.Home
                            searchQuery = ""
                        }
                        renderContent()
                        renderNavigation()
                    }
                    val pill = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = if (selected) {
                            rounded(selectedContainer, dp(22))
                        } else {
                            ColorDrawable(Color.TRANSPARENT)
                        }
                        addView(
                            navText(tab.icon, 16f, color, true).apply {
                                gravity = Gravity.CENTER
                                includeFontPadding = false
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                dp(20)
                            )
                        )
                        addView(
                            navText(tab.label, 10f, color, selected).apply {
                                gravity = Gravity.CENTER
                                includeFontPadding = false
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                dp(14)
                            ).apply { topMargin = dp(2) }
                        )
                    }
                    addView(
                        pill,
                        FrameLayout.LayoutParams(pillW, pillH, Gravity.CENTER)
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
    }

    private fun navText(value: String, size: Float, color: Int, medium: Boolean): TextView {
        return text(value, size, color, medium).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = true
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    private fun header(): View {
        val title = when (selectedTab) {
            Tab.Utility -> when (val nav = utilityNav) {
                UtilityNav.Home -> "AChat"
                is UtilityNav.Category -> nav.cat.title
                is UtilityNav.FeatureDetail -> {
                    featuresIn(nav.cat).firstOrNull { it.key == nav.featureKey }?.title
                        ?: "详情"
                }
            }
            Tab.Plugin -> "状态"
            Tab.Settings -> "设置"
        }
        val sub = when (selectedTab) {
            Tab.Utility -> when (val nav = utilityNav) {
                UtilityNav.Home -> "分类目录 · 点分类进入"
                is UtilityNav.Category -> nav.cat.hint
                is UtilityNav.FeatureDetail -> "功能配置"
            }
            Tab.Plugin -> "当前配置与运行状态"
            Tab.Settings -> "界面外观与模块信息"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(title, 26f, titleColor, true).apply {
                        includeFontPadding = false
                        maxLines = 1
                    })
                    addView(text(sub, 13f, secondaryText, false).apply {
                        includeFontPadding = false
                        setPadding(0, dp(6), 0, 0)
                        maxLines = 2
                    })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (selectedTab == Tab.Utility && utilityNav is UtilityNav.Home) {
                addView(
                    TextView(this@MainActivity).apply {
                        text = "LSPosed"
                        textSize = 11f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(accent)
                        gravity = Gravity.CENTER
                        background = rounded(selectedContainer, dp(12))
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                    }
                )
            }
        }
    }

    private fun applyNavChrome() {
        if (!::navBar.isInitialized) return
        navBar.background = rounded(surfaceColor, dp(28))
        navBar.elevation = 0f
    }

    // ── HChat 式列表组件 ───────────────────────────────────────────────────

    private fun searchBar(): View {
        val input = EditText(this).apply {
            setText(searchQuery)
            hint = "搜索 AChat 功能"
            textSize = 14f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setSingleLine(true)
            includeFontPadding = true
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(4), 0, dp(4))
            minHeight = dp(40)
            setOnEditorActionListener { _, _, _ ->
                searchQuery = text?.toString().orEmpty()
                if (searchQuery.isNotBlank()) {
                    val hit = FeatureCategory.entries.firstOrNull { cat ->
                        featuresIn(cat).any {
                            it.title.contains(searchQuery, true) ||
                                it.summary.contains(searchQuery, true)
                        }
                    }
                    if (hit != null) utilityNav = UtilityNav.Category(hit)
                }
                renderContent()
                true
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(fieldColor, dp(16))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minimumHeight = dp(48)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
            addView(
                text("⌕", 16f, secondaryText, false).apply {
                    setPadding(0, 0, dp(10), 0)
                    includeFontPadding = true
                }
            )
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun backRow(label: String, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground()
            setOnClickListener { action() }
            addView(text("‹ $label", 15f, accent, true))
        }
    }

    private fun categoryRow(
        title: String,
        summary: String,
        hint: String,
        action: () -> Unit
    ): View {
        val sub = when {
            summary.isNotBlank() && hint.isNotBlank() && summary != hint -> "$summary · $hint"
            summary.isNotBlank() -> summary
            else -> hint
        }
        return baseRow(title, sub).apply {
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground()
            setOnClickListener { action() }
            addView(
                text("›", 22f, Color.parseColor("#C7C7CC"), false).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dp(4)
                }
            )
        }
    }

    private fun navRow(
        title: String,
        summary: String,
        trailing: String,
        trailingOn: Boolean,
        action: () -> Unit
    ): View {
        return baseRow(title, summary).apply {
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground()
            setOnClickListener { action() }
            addView(
                statusBadge(trailing, trailingOn),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dp(8)
                }
            )
            addView(
                text("›", 18f, secondaryText, false).apply {
                    includeFontPadding = true
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dp(4)
                }
            )
        }
    }

    // ── feature detail pages（下一级菜单，非弹窗）──────────────────────────

    /** 防撤回：开关即时写；模板点「保存」 */
    private fun renderAntiRevokeDetail() {
        section("开关")
        card(
            switchRow("防撤回", "拦截撤回指令，保留原消息", Keys.ANTI_REVOKE, true) {
                renderContent()
            },
            switchRow(
                "保留自己撤回",
                "自己撤回的消息也拦截保留",
                Keys.ANTI_REVOKE_KEEP_SELF,
                false,
                enabled = bool(Keys.ANTI_REVOKE, true)
            ),
            switchRow(
                "显示撤回提示",
                "在会话中追加提示文案",
                Keys.REVOKE_NOTICE,
                true,
                enabled = bool(Keys.ANTI_REVOKE, true)
            )
        )

        section("提示文案")
        val template = fieldRow(
            "模板",
            Keys.ANTI_REVOKE_NOTICE_TEXT,
            "{name}撤回了一条消息"
        )
        card(template.view)
        section("插入变量")
        card(
            variableChips(
                template.input,
                listOf(
                    "撤回者" to "{name}",
                    "文字内容" to "{content}"
                )
            )
        )
        tipCard(
            title = "说明",
            body = "默认：沿用微信系统句（已是备注/昵称），只加 [已阻止]。\n" +
                "自定义模板才用变量：\n" +
                "  {name} = 备注，没有则昵称\n" +
                "  {content} = 仅文字，图片不写路径\n" +
                "改完点「保存」并强停微信。"
        )
        pageActionBar(
            onReset = {
                prefs.edit()
                    .putBoolean(Keys.ANTI_REVOKE, true)
                    .putBoolean(Keys.ANTI_REVOKE_KEEP_SELF, false)
                    .putBoolean(Keys.REVOKE_NOTICE, true)
                    .putString(Keys.ANTI_REVOKE_NOTICE_TEXT, "{name}撤回了一条消息")
                    .apply()
                makePrefsReadable()
                renderContent()
                toast("已恢复默认")
            },
            onSave = {
                prefs.edit()
                    .putString(
                        Keys.ANTI_REVOKE_NOTICE_TEXT,
                        template.input.text.toString().ifBlank {
                            "{name}撤回了一条消息"
                        }
                    )
                    .apply()
                makePrefsReadable()
                toast("已保存，请强停微信")
            }
        )
    }

    /** 消息底部：开关即时；字段点保存 */
    private fun renderMessageDetailPage() {
        section("开关")
        card(
            switchRow("显示消息底部", "在消息旁显示时间与自定义字段", Keys.DETAIL_ENABLED, true) {
                renderContent()
            },
            switchRow(
                "点击气泡才显示",
                "默认隐藏，点气泡切换可见",
                Keys.DETAIL_CLICK_SHOW,
                false,
                enabled = bool(Keys.DETAIL_ENABLED, true)
            )
        )

        section("格式")
        val template = fieldRow("文本格式", Keys.DETAIL_TEMPLATE, "\${time} \${relativeTime}")
        val timePattern = fieldRow("时间格式", Keys.DETAIL_TIME_PATTERN, "MM-dd 周一 HH:mm:ss")
        val textSizeField = fieldRow("字体大小", Keys.DETAIL_TEXT_SIZE, "12")
        card(template.view, timePattern.view, textSizeField.view)
        section("插入变量")
        card(
            variableChips(
                template.input,
                listOf(
                    "时间" to "\${time}",
                    "相对时间" to "\${relativeTime}",
                    "类型" to "\${type}",
                    "msgId" to "\${msgId}",
                    "msgSvrId" to "\${msgSvrId}"
                )
            )
        )

        section("边距与颜色")
        val leftMargin = fieldRow(
            "左边距(dp)",
            Keys.DETAIL_LEFT_MARGIN,
            prefs.getString(Keys.DETAIL_HORIZONTAL_MARGIN, "0") ?: "0"
        )
        val rightMargin = fieldRow(
            "右边距(dp)",
            Keys.DETAIL_RIGHT_MARGIN,
            prefs.getString(Keys.DETAIL_HORIZONTAL_MARGIN, "0") ?: "0"
        )
        val colorLight = fieldRow(
            "浅色文字色",
            Keys.DETAIL_TEXT_COLOR_LIGHT,
            prefs.getString(Keys.DETAIL_TEXT_COLOR_LIGHT, null)
                ?: prefs.getString(Keys.DETAIL_TEXT_COLOR, "#CC000000")
                ?: "#CC000000"
        )
        val colorDark = fieldRow(
            "深色文字色",
            Keys.DETAIL_TEXT_COLOR_DARK,
            prefs.getString(Keys.DETAIL_TEXT_COLOR_DARK, null)
                ?: prefs.getString(Keys.DETAIL_TEXT_COLOR, "#CCFFFFFF")
                ?: "#CCFFFFFF"
        )
        card(leftMargin.view, rightMargin.view, colorLight.view, colorDark.view)
        pageActionBar(
            onReset = {
                saveDetailPrefs(
                    enabled = true,
                    clickShow = false,
                    template = "\${time} \${relativeTime}",
                    timePattern = "MM-dd 周一 HH:mm:ss",
                    textSize = "12",
                    leftMargin = "0",
                    rightMargin = "0",
                    colorLight = "#CC000000",
                    colorDark = "#CCFFFFFF"
                )
                toast("已恢复默认")
            },
            onSave = {
                saveDetailPrefs(
                    enabled = bool(Keys.DETAIL_ENABLED, true),
                    clickShow = bool(Keys.DETAIL_CLICK_SHOW, false),
                    template = template.input.text.toString().ifBlank { "\${time} \${relativeTime}" },
                    timePattern = timePattern.input.text.toString().ifBlank { "MM-dd 周一 HH:mm:ss" },
                    textSize = textSizeField.input.text.toString().ifBlank { "12" },
                    leftMargin = leftMargin.input.text.toString().ifBlank { "0" },
                    rightMargin = rightMargin.input.text.toString().ifBlank { "0" },
                    colorLight = colorLight.input.text.toString().ifBlank { "#CC000000" },
                    colorDark = colorDark.input.text.toString().ifBlank { "#CCFFFFFF" }
                )
                toast("已保存，请强停微信")
            }
        )
    }

    private fun saveDetailPrefs(
        enabled: Boolean,
        clickShow: Boolean,
        template: String,
        timePattern: String,
        textSize: String,
        leftMargin: String,
        rightMargin: String,
        colorLight: String,
        colorDark: String
    ) {
        prefs.edit()
            .putBoolean(Keys.DETAIL_ENABLED, enabled)
            .putBoolean(Keys.DETAIL_CLICK_SHOW, clickShow)
            .putString(Keys.DETAIL_TEMPLATE, template)
            .putString(Keys.DETAIL_TIME_PATTERN, timePattern)
            .putString(Keys.DETAIL_TEXT_SIZE, textSize)
            .putString(Keys.DETAIL_LEFT_MARGIN, leftMargin)
            .putString(Keys.DETAIL_RIGHT_MARGIN, rightMargin)
            .putString(Keys.DETAIL_HORIZONTAL_MARGIN, leftMargin)
            .putString(Keys.DETAIL_TEXT_COLOR_LIGHT, colorLight)
            .putString(Keys.DETAIL_TEXT_COLOR_DARK, colorDark)
            .putString(Keys.DETAIL_TEXT_COLOR, colorLight)
            .apply()
        makePrefsReadable()
        // 不整页刷新，避免输入框失焦；仅写盘
    }

    private fun renderInputStatsDetail() {
        section("开关")
        card(
            switchRow("输入框显示统计", "在输入框显示今日发送数等", Keys.INPUT_STATS_ENABLED, true) {
                renderContent()
            },
            switchRow(
                "统计当天发送数",
                "计入当日发送消息",
                Keys.INPUT_STATS_COUNT_SEND,
                true,
                enabled = bool(Keys.INPUT_STATS_ENABLED, true)
            )
        )
        section("文案")
        val template = fieldRow("输入框文案", Keys.INPUT_STATS_TEMPLATE, "今日已发\${totalMsg}条")
        card(template.view)
        section("插入变量")
        card(
            variableChips(
                template.input,
                listOf(
                    "总条数" to "\${totalMsg}",
                    "文字条" to "\${textMsg}",
                    "字数" to "\${textWord}",
                    "表情" to "\${emojiMsg}",
                    "转账" to "\${transferMsg}",
                    "红包" to "\${redBagMsg}",
                    "文件" to "\${fileMsg}"
                )
            )
        )
        pageActionBar(
            onReset = {
                saveInputStatsPrefs(true, true, "今日已发\${totalMsg}条")
                renderContent()
                toast("已恢复默认")
            },
            onSave = {
                saveInputStatsPrefs(
                    bool(Keys.INPUT_STATS_ENABLED, true),
                    bool(Keys.INPUT_STATS_COUNT_SEND, true),
                    template.input.text.toString().ifBlank { "今日已发\${totalMsg}条" }
                )
                toast("已保存")
            }
        )
    }

    private fun saveInputStatsPrefs(enabled: Boolean, countSend: Boolean, template: String) {
        prefs.edit()
            .putBoolean(Keys.INPUT_STATS_ENABLED, enabled)
            .putBoolean(Keys.INPUT_STATS_COUNT_SEND, countSend)
            .putString(Keys.INPUT_STATS_TEMPLATE, template)
            .apply()
        makePrefsReadable()
    }

    /**
     * 虚拟定位（对齐 WAuxiliary）。
     * UI：纬度/经度 +「选点」→ 调微信 [RedirectUI] 内置地图。
     */
    private fun renderVirtualLocationDetail() {
        val defLat = "16.61953"
        val defLon = "98.56146"
        section("开关")
        card(
            switchRow(
                "启用虚拟定位",
                "改写腾讯定位 SDK 返回的经纬度",
                Keys.VIRTUAL_LOCATION,
                false
            ) {
                renderContent()
            }
        )
        section("坐标")
        val latField = fieldRow("纬度 latitude", Keys.VIRTUAL_LOCATION_LAT, defLat)
        val lonField = fieldRow("经度 longitude", Keys.VIRTUAL_LOCATION_LON, defLon)
        locationLatInput = latField.input
        locationLonInput = lonField.input
        card(latField.view, lonField.view)

        // 对齐 WA：选点 + Tip（无城市推荐）
        section("地图选点")
        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(
                TextView(this@MainActivity).apply {
                    text = "选点"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = rounded(accent, dp(12))
                    setPadding(dp(12), dp(14), dp(12), dp(14))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { launchWeChatMapPick() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "Tip: 开启功能后才可使用地图选点（调用微信内置地图）"
                    textSize = 12f
                    setTextColor(secondaryText)
                    setPadding(0, dp(10), 0, 0)
                }
            )
        }
        card(pickRow)

        tipCard(
            title = "说明",
            body = "桌面无图标，从微信「我→设置→AChat」进入。\n" +
                "选点：在微信内打开内置地图（RedirectUI）。\n" +
                "保存后强停微信再测发送位置/附近的人。"
        )

        pageActionBar(
            onReset = {
                prefs.edit()
                    .putBoolean(Keys.VIRTUAL_LOCATION, false)
                    .putString(Keys.VIRTUAL_LOCATION_LAT, defLat)
                    .putString(Keys.VIRTUAL_LOCATION_LON, defLon)
                    .apply()
                VirtualLocationConfig.writePublic(
                    false,
                    defLat.toDouble(),
                    defLon.toDouble(),
                    context = this,
                    async = true
                )
                makePrefsReadable()
                renderContent()
                toast("已恢复默认")
            },
            onSave = {
                val lat = latField.input.text.toString().toDoubleOrNull()
                val lon = lonField.input.text.toString().toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    toast("经纬度无效")
                    return@pageActionBar
                }
                val enabled = bool(Keys.VIRTUAL_LOCATION, false)
                prefs.edit()
                    .putBoolean(Keys.VIRTUAL_LOCATION, enabled)
                    .putString(
                        Keys.VIRTUAL_LOCATION_LAT,
                        String.format(Locale.US, "%.6f", lat)
                    )
                    .putString(
                        Keys.VIRTUAL_LOCATION_LON,
                        String.format(Locale.US, "%.6f", lon)
                    )
                    .apply()
                VirtualLocationConfig.writePublic(enabled, lat, lon, context = this, async = true)
                makePrefsReadable()
                toast("已保存，请强停微信")
            }
        )
    }

    /**
     * 选点：写公共请求 → 打开微信主界面 →
     * 微信进程 [WeChatMapPickBridge] 拉起 RedirectUI → 结果写回公共文件。
     */
    private fun launchWeChatMapPick() {
        if (!bool(Keys.VIRTUAL_LOCATION, false)) {
            toast("请先开启虚拟定位")
            return
        }
        // 先把当前开关坐标同步到公共配置
        val lat = locationLatInput?.text?.toString()?.toDoubleOrNull()
            ?: prefs.getString(Keys.VIRTUAL_LOCATION_LAT, "16.61953")?.toDoubleOrNull()
            ?: VirtualLocationConfig.DEFAULT_LATITUDE
        val lon = locationLonInput?.text?.toString()?.toDoubleOrNull()
            ?: prefs.getString(Keys.VIRTUAL_LOCATION_LON, "98.56146")?.toDoubleOrNull()
            ?: VirtualLocationConfig.DEFAULT_LONGITUDE
        VirtualLocationConfig.writePublic(true, lat, lon, context = this, async = false)
        VirtualLocationConfig.requestMapPick()

        // 打开微信，让桥接在 onResume 时拉起地图
        val launched = runCatching {
            val launch = packageManager.getLaunchIntentForPackage("com.tencent.mm")
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (launched) {
            toast("已请求选点，请在微信地图中选择位置")
        } else {
            toast("无法打开微信，请手动打开微信后再试")
        }
    }

    /**
     * 圆形弧度 0.05~0.50；默认/推荐 **0.36 方圆**。
     * 预设：正方 0.05 / 方圆 0.36 / 圆形 0.50
     */
    /** 对齐 WA AutoLoginWinHook */
    private fun renderAutoLoginWinDetail() {
        section("开关")
        card(
            switchRow(
                "启用 PC 自动登录",
                "其它设备请求登录时自动处理",
                Keys.AUTO_LOGIN_WIN,
                false
            ) { renderContent() }
        )
        section("勾选项")
        card(
            switchRow("同步最近消息", "functionControl 0b001", Keys.AUTO_LOGIN_WIN_SYNC, true),
            switchRow("显示登录设备", "functionControl 0b010", Keys.AUTO_LOGIN_WIN_SHOW, true),
            switchRow("自动登录设备", "functionControl 0b100", Keys.AUTO_LOGIN_WIN_DEVICE, false),
            switchRow("自动点击登录", "initView 后点登录按钮", Keys.AUTO_LOGIN_WIN_CLICK, true)
        )
        tipCard(
            title = "说明",
            body = "对齐 WAuxiliary AutoLoginWinHook。\n" +
                "目标：ExtDeviceWXLoginUI\n" +
                "改完强停微信，用 PC 扫码登录验证。"
        )
        pageActionBar(
            onReset = {
                prefs.edit()
                    .putBoolean(Keys.AUTO_LOGIN_WIN, false)
                    .putBoolean(Keys.AUTO_LOGIN_WIN_SYNC, true)
                    .putBoolean(Keys.AUTO_LOGIN_WIN_SHOW, true)
                    .putBoolean(Keys.AUTO_LOGIN_WIN_DEVICE, false)
                    .putBoolean(Keys.AUTO_LOGIN_WIN_CLICK, true)
                    .apply()
                makePrefsReadable()
                PublicConfigStore.putBoolean(Keys.AUTO_LOGIN_WIN, false, true)
                PublicConfigStore.putBoolean(Keys.AUTO_LOGIN_WIN_SYNC, true, true)
                PublicConfigStore.putBoolean(Keys.AUTO_LOGIN_WIN_SHOW, true, true)
                PublicConfigStore.putBoolean(Keys.AUTO_LOGIN_WIN_DEVICE, false, true)
                PublicConfigStore.putBoolean(Keys.AUTO_LOGIN_WIN_CLICK, true, true)
                renderContent()
                toast("已恢复默认")
            },
            onSave = {
                val en = bool(Keys.AUTO_LOGIN_WIN, false)
                val sync = bool(Keys.AUTO_LOGIN_WIN_SYNC, true)
                val show = bool(Keys.AUTO_LOGIN_WIN_SHOW, true)
                val dev = bool(Keys.AUTO_LOGIN_WIN_DEVICE, false)
                val click = bool(Keys.AUTO_LOGIN_WIN_CLICK, true)
                PublicConfigStore.putAll(
                    mapOf(
                        Keys.AUTO_LOGIN_WIN to en.toString(),
                        Keys.AUTO_LOGIN_WIN_SYNC to sync.toString(),
                        Keys.AUTO_LOGIN_WIN_SHOW to show.toString(),
                        Keys.AUTO_LOGIN_WIN_DEVICE to dev.toString(),
                        Keys.AUTO_LOGIN_WIN_CLICK to click.toString()
                    ),
                    async = true
                )
                makePrefsReadable()
                toast("已保存，请强停微信")
            }
        )
    }

    private fun renderRoundAvatarDetail() {
        section("开关")
        card(
            switchRow("启用圆形头像", "可调圆度 · 推荐方圆 0.36", Keys.ROUND_AVATAR, false) {
                // 开关变更时同步公共配置
                val r = RoundAvatarConfig.clampRadius(
                    prefs.getString(Keys.ROUND_AVATAR_RADIUS, "0.36")?.toFloatOrNull()
                        ?: RoundAvatarConfig.DEFAULT_RADIUS
                )
                RoundAvatarConfig.writePublic(
                    enabled = bool(Keys.ROUND_AVATAR, false),
                    radius = r,
                    context = this,
                    async = true
                )
                renderContent()
            }
        )

        section("圆度")
        val current = RoundAvatarConfig.clampRadius(
            prefs.getString(Keys.ROUND_AVATAR_RADIUS, "0.36")?.toFloatOrNull()
                ?: RoundAvatarConfig.DEFAULT_RADIUS
        )
        val radiusLabel = text(arcTitle(current), 13f, primaryText, true).apply {
            includeFontPadding = false
            setPadding(dp(16), dp(14), dp(16), dp(4))
        }
        val seek = SeekBar(this).apply {
            max = 45
            progress = radiusToProgress(current)
            minHeight = dp(36)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    radiusLabel.text = arcTitle(progressToRadius(progress))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        fun applyPreset(arc: Float) {
            seek.progress = radiusToProgress(arc)
            radiusLabel.text = arcTitle(arc)
        }
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
            listOf(
                "正方" to RoundAvatarConfig.PRESET_SQUARE,
                "方圆" to RoundAvatarConfig.PRESET_SOFT_CIRCLE,
                "圆形" to RoundAvatarConfig.PRESET_FULL_CIRCLE
            ).forEachIndexed { index, (name, arc) ->
                val btn = Button(this@MainActivity).apply {
                    text = name
                    textSize = 12f
                    isAllCaps = false
                    minHeight = dp(36)
                    setOnClickListener { applyPreset(arc) }
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (index > 0) lp.marginStart = dp(8)
                addView(btn, lp)
            }
        }
        val scaleHints = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(4))
            addView(
                text("正方 0.05", 11f, secondaryText, false),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                text("圆形 0.50", 11f, secondaryText, false).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        content.addView(
            MaterialCardView(this).apply {
                radius = dp(10).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(surfaceColor)
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(radiusLabel)
                        addView(seek)
                        addView(scaleHints)
                        addView(presets)
                    }
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        )
        tipCard(
            title = "说明",
            body = "像素半径 = 短边 × 弧度。\n预设：正方 0.05 · 方圆 0.36（推荐）· 圆形 0.50。"
        )
        pageActionBar(
            onReset = {
                saveRoundAvatar(enabled = true, radius = RoundAvatarConfig.PRESET_SOFT_CIRCLE)
                renderContent()
                toast("已恢复方圆 0.36")
            },
            onSave = {
                saveRoundAvatar(
                    enabled = bool(Keys.ROUND_AVATAR, false),
                    radius = progressToRadius(seek.progress)
                )
            }
        )
    }

    /** 写 SharedPreferences + 异步公共配置（微信进程可读） */
    private fun saveRoundAvatar(enabled: Boolean, radius: Float) {
        val clamped = RoundAvatarConfig.clampRadius(radius)
        val text = formatRadius(clamped)
        prefs.edit()
            .putBoolean(Keys.ROUND_AVATAR, enabled)
            .putString(Keys.ROUND_AVATAR_RADIUS, text)
            .apply()
        RoundAvatarConfig.writePublic(enabled, clamped, context = this, async = true)
        makePrefsReadable()
        toast("已保存弧度 $text · ${arcLabel(clamped)}，请强停微信")
    }

    /**
     * 详情页「重置 / 保存」：挂到 [actionBarHost]（ScrollView 外固定栏），
     * 不要塞进 content，否则滚动/焦点会吞点击。
     */
    private fun pageActionBar(onReset: () -> Unit, onSave: () -> Unit) {
        if (!::actionBarHost.isInitialized) return
        actionBarHost.removeAllViews()
        actionBarHost.setBackgroundColor(pageBackground)
        actionBarHost.visibility = View.VISIBLE
        actionBarHost.addView(
            pageActionButton("重置", secondaryText, fieldColor) {
                hideKeyboard()
                onReset()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        )
        actionBarHost.addView(
            pageActionButton("保存", Color.WHITE, accent) {
                hideKeyboard()
                onSave()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    private fun hideKeyboard() {
        val focus = currentFocus
        focus?.clearFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow((focus ?: window.decorView).windowToken, 0)
    }

    private fun pageActionButton(
        label: String,
        textColor: Int,
        bg: Int,
        action: () -> Unit
    ): TextView {
        // 用 TextView + 明确 clickable，避免 AppCompat Button 主题/inset 吃掉点击
        return TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            includeFontPadding = true
            background = rounded(bg, dp(12))
            elevation = 0f
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            isEnabled = true
            setPadding(dp(12), dp(14), dp(12), dp(14))
            foreground = selectableItemBackground()
            setOnClickListener { action() }
        }
    }

    /**
     * 可点击变量芯片：点一下插入到目标输入框光标处。
     * @param items (显示名 to 插入文本)
     */
    private fun variableChips(target: EditText, items: List<Pair<String, String>>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            // 自动换行多行 chip
            var row = newChipRow()
            addView(row)
            items.forEachIndexed { index, (label, token) ->
                if (index > 0 && index % 3 == 0) {
                    row = newChipRow()
                    addView(row)
                }
                val chip = chipButton(label) {
                    target.requestFocus()
                    insertAtCursor(target, token)
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index % 3 != 0) leftMargin = dp(8)
                    topMargin = if (index >= 3) dp(8) else 0
                }
                row.addView(chip, lp)
            }
        }
    }

    private fun newChipRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun chipButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(accent)
            background = rounded(selectedContainer, dp(10))
            minHeight = dp(40)
            minimumHeight = dp(40)
            // 防止 Material Button 默认 inset 裁字
            minWidth = 0
            minimumWidth = 0
            includeFontPadding = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            stateListAnimator = null
            elevation = 0f
            setOnClickListener { action() }
        }
    }

    private fun insertAtCursor(edit: EditText, token: String) {
        // 保证可编辑且有焦点
        edit.isEnabled = true
        edit.isFocusable = true
        edit.isFocusableInTouchMode = true
        edit.requestFocus()
        val editable = edit.text
        if (editable == null) {
            edit.setText(token)
            edit.setSelection(token.length)
            return
        }
        val start = edit.selectionStart.let { if (it < 0) editable.length else it }
        val end = edit.selectionEnd.let { if (it < 0) start else it }
        val s = minOf(start, end).coerceIn(0, editable.length)
        val e = maxOf(start, end).coerceIn(0, editable.length)
        editable.replace(s, e, token)
        val newPos = (s + token.length).coerceAtMost(editable.length)
        edit.setSelection(newPos)
    }

    private fun confirmResetDefaults() {
        MaterialAlertDialogBuilder(this)
            .setTitle("恢复默认开关")
            .setMessage("将重置全部功能开关为默认开启，消息底部与输入框文案模板保持不变。")
            .setNegativeButton("取消", null)
            .setPositiveButton("重置") { _, _ ->
                prefs.edit()
                    .putBoolean(Keys.ANTI_REVOKE, true)
                    .putBoolean(Keys.REVOKE_NOTICE, true)
                    .putBoolean(Keys.ANTI_REVOKE_KEEP_SELF, false)
                    .putString(Keys.ANTI_REVOKE_NOTICE_TEXT, "{name}撤回了一条消息")
                    .putBoolean(Keys.MEDIA_PROTECT, true)
                    .putBoolean(Keys.ANTI_MOMENTS_DELETE, true)
                    .putBoolean(Keys.BOTTOM_TAB_HIDE_TITLE, true)
                    .putBoolean(Keys.SWIPE_QUOTE, true)
                    // 右滑复读未做稳，不开放 UI，强制关
                    .putBoolean(Keys.SWIPE_REPEAT, false)
                    .putBoolean(Keys.DETAIL_ENABLED, true)
                    .putBoolean(Keys.DETAIL_CLICK_SHOW, false)
                    .putBoolean(Keys.INPUT_STATS_ENABLED, true)
                    .putBoolean(Keys.INPUT_STATS_COUNT_SEND, true)
                    .putBoolean(Keys.BUBBLE_ENABLED, true)
                    .putBoolean(Keys.ROUND_AVATAR, false)
                    .putString(Keys.ROUND_AVATAR_RADIUS, "0.36")
                    .putBoolean(Keys.SETTINGS_ENTRY, true)
                    .apply()
                makePrefsReadable()
                renderContent()
                toast("已恢复默认开关")
            }
            .show()
    }

    // ── summary helpers ────────────────────────────────────────────────────

    private fun roundAvatarSummary(): String {
        val r = prefs.getString(Keys.ROUND_AVATAR_RADIUS, "0.36") ?: "0.36"
        val label = arcLabel(r.toFloatOrNull() ?: 0.36f)
        return "弧度 $r · $label"
    }

    /** SeekBar 0..45 → arc 0.05..0.50 */
    private fun progressToRadius(progress: Int): Float {
        return RoundAvatarConfig.clampRadius(0.05f + progress / 100f)
    }

    private fun radiusToProgress(radius: Float): Int {
        return ((RoundAvatarConfig.clampRadius(radius) - 0.05f) * 100f).toInt().coerceIn(0, 45)
    }

    private fun formatRadius(value: Float): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun arcLabel(arc: Float): String {
        return when {
            arc <= 0.08f -> "正方"
            arc < 0.30f -> "微圆"
            arc in 0.33f..0.39f -> "方圆"
            arc < 0.48f -> "接近圆形"
            else -> "圆形"
        }
    }

    private fun arcTitle(arc: Float): String {
        return "圆形弧度  ${formatRadius(arc)}  ·  ${arcLabel(arc)}"
    }

    private fun detailSummary(): String {
        if (!bool(Keys.DETAIL_ENABLED, true)) return "时间与自定义字段 · 点击进入"
        val pattern = prefs.getString(Keys.DETAIL_TIME_PATTERN, "MM-dd 周一 HH:mm:ss")
            ?: "MM-dd 周一 HH:mm:ss"
        val size = prefs.getString(Keys.DETAIL_TEXT_SIZE, "12") ?: "12"
        return "格式 $pattern · ${size}sp"
    }

    private fun inputStatsSummary(): String {
        if (!bool(Keys.INPUT_STATS_ENABLED, true)) return "今日发送数 · 点击进入"
        val template = prefs.getString(Keys.INPUT_STATS_TEMPLATE, "今日已发\${totalMsg}条")
            ?: "今日已发\${totalMsg}条"
        return if (template.length > 26) template.take(26) + "…" else template
    }

    private fun bubbleAssetStatus(): String {
        return "外部目录 /Android/media/com.tencent.mm/AChat"
    }

    // ── row builders ───────────────────────────────────────────────────────

    private fun fieldRow(label: String, key: String, defaultValue: String): FieldRow {
        val input = EditText(this).apply {
            setText(prefs.getString(key, defaultValue) ?: defaultValue)
            textSize = 14f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setSingleLine(true)
            // 保留 font padding，避免中文上下被裁
            includeFontPadding = true
            background = rounded(fieldColor, dp(10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(48)
            isFocusable = true
            isFocusableInTouchMode = true
            isHorizontalScrollBarEnabled = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            addView(text(label, 11f, sectionText, true).apply {
                includeFontPadding = true
                setPadding(dp(2), 0, 0, dp(6))
            })
            // WRAP_CONTENT + minHeight，不再写死高度裁字
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return FieldRow(row, input)
    }

    /** 状态徽章：单行省略，避免挤掉左侧标题 */
    private fun statusBadge(value: String, on: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (on) accent else secondaryText)
            gravity = Gravity.CENTER
            includeFontPadding = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = rounded(if (on) selectedContainer else fieldColor, dp(12))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            // 限制最大宽度，给左侧文案留空间
            maxWidth = dp(96)
        }
    }

    private fun hintText(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 10.5f
            setTextColor(secondaryText)
            setPadding(dp(2), dp(6), dp(2), 0)
        }
    }

    /**
     * 有下级配置的功能行：列表上显示状态徽章；
     * 整行点击进入下一级详情菜单。
     */
    private fun configRow(
        title: String,
        summary: String,
        status: String,
        action: () -> Unit
    ): View {
        return baseRow(title, summary).apply {
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground()
            setOnClickListener { action() }
            addView(
                statusBadge(status, status.contains("开启")),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dp(10)
                }
            )
        }
    }

    private fun switchRow(
        title: String,
        summary: String,
        key: String,
        defaultValue: Boolean,
        enabled: Boolean = true,
        onChanged: (() -> Unit)? = null
    ): View {
        val switch = SwitchMaterial(this).apply {
            isChecked = bool(key, defaultValue)
            isEnabled = enabled
            thumbTintList = switchThumbTint()
            trackTintList = switchTrackTint()
            minWidth = dp(52)
            minimumWidth = dp(52)
            scaleX = 0.92f
            scaleY = 0.92f
            setOnCheckedChangeListener { _, checked ->
                if (!enabled) return@setOnCheckedChangeListener
                prefs.edit().apply {
                    putBoolean(key, checked)
                    if (key == Keys.ANTI_REVOKE && !checked) {
                        putBoolean(Keys.REVOKE_NOTICE, false)
                    }
                    if (key == Keys.ANTI_REVOKE && checked && !prefs.contains(Keys.REVOKE_NOTICE)) {
                        putBoolean(Keys.REVOKE_NOTICE, true)
                    }
                }.apply()
                makePrefsReadable()
                when (key) {
                    Keys.NIGHT_MODE -> {
                        PublicConfigStore.putBoolean(Keys.NIGHT_MODE, checked, async = true)
                        PublicConfigStore.putBoolean(Keys.NIGHT_MODE_FOLLOW, false, async = true)
                        prefs.edit().putBoolean(Keys.NIGHT_MODE_FOLLOW, false).apply()
                        refreshChrome()
                    }
                    Keys.NIGHT_MODE_FOLLOW -> {
                        PublicConfigStore.putBoolean(Keys.NIGHT_MODE_FOLLOW, checked, async = true)
                        onChanged?.invoke()
                    }
                    else -> onChanged?.invoke()
                }
            }
        }
        return baseRow(title, summary, enabled).apply {
            addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dp(10)
                }
            )
            isClickable = enabled
            isFocusable = enabled
            if (enabled) {
                foreground = selectableItemBackground()
                setOnClickListener { switch.isChecked = !switch.isChecked }
            }
        }
    }

    private fun infoRow(
        title: String,
        summary: String,
        value: String,
        accentWhenOn: Boolean = true
    ): View {
        return baseRow(title, summary).apply {
            addView(
                statusBadge(value, accentWhenOn),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dp(10)
                }
            )
        }
    }

    private fun actionRow(
        title: String,
        summary: String,
        actionLabel: String,
        action: () -> Unit
    ): View {
        return baseRow(title, summary).apply {
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground()
            setOnClickListener { action() }
            addView(
                statusBadge(actionLabel, true),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = dp(10)
                }
            )
        }
    }

    private fun tipCard(title: String, body: String) {
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(tipBackground, dp(14))
                setPadding(dp(16), dp(14), dp(16), dp(14))
                if (title.isNotBlank()) {
                    addView(text(title, 13f, accent, true).apply {
                        includeFontPadding = false
                        maxLines = 2
                    })
                }
                addView(text(body, 12f, secondaryText, false).apply {
                    includeFontPadding = false
                    setPadding(0, if (title.isNotBlank()) dp(6) else 0, 0, 0)
                    setLineSpacing(dp(3).toFloat(), 1f)
                })
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )
    }

    private fun baseRow(title: String, summary: String, enabled: Boolean = true): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(14), dp(14), dp(14))
            alpha = if (enabled) 1f else 0.38f
            clipChildren = false
            clipToPadding = false
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        text(title, 16f, primaryText, true).apply {
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            includeFontPadding = false
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    if (summary.isNotBlank()) {
                        addView(
                            text(summary, 12f, secondaryText, false).apply {
                                maxLines = 2
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                includeFontPadding = false
                                setPadding(0, dp(4), 0, 0)
                                setLineSpacing(dp(1).toFloat(), 1f)
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        )
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(4)
                }
            )
        }
    }

    private fun section(value: String) {
        content.addView(
            text(value, 13f, sectionText, true).apply {
                includeFontPadding = false
                setPadding(dp(4), 0, dp(4), 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(8)
            }
        )
    }

    private fun card(vararg rows: View) {
        // 圆角白卡片（软件壳）
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(surfaceColor, dp(16))
                clipChildren = false
                clipToPadding = false
                rows.forEachIndexed { index, row ->
                    addView(row)
                    if (index < rows.lastIndex) addView(divider())
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(outline)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
            }
        }
    }

    private fun text(value: String, size: Float, color: Int, medium: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            // 默认保留 font padding，减少中文上下裁切
            includeFontPadding = true
            letterSpacing = 0f
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    // ── palette / prefs ────────────────────────────────────────────────────

    private fun switchThumbTint(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, Color.parseColor("#F7F7F7"))
        )
    }

    private fun switchTrackTint(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, switchOffTrack)
        )
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val typedArray = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return try {
            typedArray.getDrawable(0)
        } finally {
            typedArray.recycle()
        }
    }

    private fun refreshChrome() {
        applyPalette()
        applySystemBars()
        setContentView(buildScreen())
        applyNavChrome()
        // 返回回调已在 onCreate 注册，重建视图后只需刷新内容
        renderContent()
        renderNavigation()
    }

    private fun isHostDark(): Boolean {
        return runCatching { EmbeddedSettingsUi.isWeChatDark(this) }.getOrElse {
            val nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            nightMask == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun resolveNight(): Boolean {
        return if (bool(Keys.NIGHT_MODE_FOLLOW, true)) {
            isHostDark()
        } else {
            bool(Keys.NIGHT_MODE, false)
        }
    }

    private fun applyPalette() {
        val night = resolveNight()
        if (night) {
            pageBackground = Color.parseColor("#111111")
            surfaceColor = Color.parseColor("#1E1E1E")
            fieldColor = Color.parseColor("#2A2A2A")
            titleColor = Color.parseColor("#F5F5F5")
            primaryText = Color.parseColor("#E8E8E8")
            secondaryText = Color.parseColor("#9A9A9A")
            sectionText = Color.parseColor("#8A8A8A")
            selectedContainer = Color.parseColor("#123A28")
            outline = Color.parseColor("#2C2C2C")
            switchOffTrack = Color.parseColor("#3A3D42")
            tipBackground = Color.parseColor("#1A2B22")
        } else {
            pageBackground = Color.parseColor("#F7F8FA")
            surfaceColor = Color.WHITE
            fieldColor = Color.parseColor("#F0F1F3")
            titleColor = Color.parseColor("#050505")
            primaryText = Color.parseColor("#111111")
            secondaryText = Color.parseColor("#72757C")
            sectionText = Color.parseColor("#7F8594")
            selectedContainer = Color.parseColor("#EAF7EF")
            outline = Color.parseColor("#EBEDF0")
            switchOffTrack = Color.parseColor("#D8DAD9")
            tipBackground = Color.parseColor("#F0F7F3")
        }
    }

    private fun applySystemBars() {
        window.statusBarColor = pageBackground
        window.navigationBarColor = pageBackground
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (resolveNight()) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun makePrefsReadable() {
        // chmod 放到后台，避免开关/保存时主线程卡顿（背后微信会话变黑）
        val dataDir = applicationInfo.dataDir
        val enabled = bool(Keys.ROUND_AVATAR, false)
        val radius = RoundAvatarConfig.clampRadius(
            prefs.getString(Keys.ROUND_AVATAR_RADIUS, "0.36")?.toFloatOrNull()
                ?: RoundAvatarConfig.DEFAULT_RADIUS
        )
        val appCtx = applicationContext
        Thread({
            runCatching {
                val file = File(dataDir, "shared_prefs/${Keys.PREFS}.xml")
                file.parentFile?.setExecutable(true, false)
                file.parentFile?.setReadable(true, false)
                file.setReadable(true, false)
                file.setWritable(true, false)
            }
            // 公共配置异步写 1~2 路径即可（内部已 async）
            runCatching {
                RoundAvatarConfig.writePublic(
                    enabled = enabled,
                    radius = radius,
                    context = appCtx,
                    async = true
                )
            }
        }, "achat-prefs-io").apply {
            isDaemon = true
            start()
        }
    }

    private fun ensureDefaultPrefs() {
        val editor = prefs.edit()
        var changed = false

        fun putDefault(key: String, value: Boolean) {
            if (!prefs.contains(key)) {
                editor.putBoolean(key, value)
                changed = true
            }
        }

        putDefault(Keys.ANTI_REVOKE, true)
        putDefault(Keys.REVOKE_NOTICE, true)
        putDefault(Keys.ANTI_REVOKE_KEEP_SELF, false)
        putDefault(Keys.MEDIA_PROTECT, true)
        putDefault(Keys.ANTI_MOMENTS_DELETE, true)
        putDefault(Keys.NIGHT_MODE_FOLLOW, true)
        putDefault(Keys.NIGHT_MODE, false)
        putDefault(Keys.AUTO_LOGIN_WIN, false)
        putDefault(Keys.AUTO_LOGIN_WIN_SYNC, true)
        putDefault(Keys.AUTO_LOGIN_WIN_SHOW, true)
        putDefault(Keys.AUTO_LOGIN_WIN_DEVICE, false)
        putDefault(Keys.AUTO_LOGIN_WIN_CLICK, true)
        putDefault(Keys.QUOTE_DELETE_CLEAR, false)
        // 隐藏底栏标题：默认开启
        putDefault(Keys.BOTTOM_TAB_HIDE_TITLE, true)
        putDefault(Keys.SWIPE_QUOTE, true)
        // 右滑复读：未做稳，默认关且不进功能列表
        putDefault(Keys.SWIPE_REPEAT, false)
        // 确保半成品开关不会被旧配置误开后仍显示在 UI（UI 已不登记）
        if (prefs.getBoolean(Keys.SWIPE_REPEAT, false)) {
            editor.putBoolean(Keys.SWIPE_REPEAT, false)
            changed = true
        }
        putDefault(Keys.DETAIL_ENABLED, true)
        putDefault(Keys.DETAIL_CLICK_SHOW, false)
        putDefault(Keys.INPUT_STATS_ENABLED, true)
        putDefault(Keys.INPUT_STATS_COUNT_SEND, true)
        putDefault(Keys.BUBBLE_ENABLED, true)
        putDefault(Keys.ROUND_AVATAR, false)
        putDefault(Keys.VIRTUAL_LOCATION, false)
        putDefault(Keys.SETTINGS_ENTRY, true)
        putDefault(Keys.NIGHT_MODE, false)
        putDefault(Keys.FLOATING_NAV, true)
        if (!prefs.contains(Keys.VIRTUAL_LOCATION_LAT)) {
            editor.putString(Keys.VIRTUAL_LOCATION_LAT, "16.61953")
            changed = true
        }
        if (!prefs.contains(Keys.VIRTUAL_LOCATION_LON)) {
            editor.putString(Keys.VIRTUAL_LOCATION_LON, "98.56146")
            changed = true
        }
        if (!prefs.contains(Keys.ANTI_REVOKE_NOTICE_TEXT)) {
            // 默认：走微信系统句（备注/昵称），模板仅自定义时生效
            editor.putString(Keys.ANTI_REVOKE_NOTICE_TEXT, "{name}撤回了一条消息")
            changed = true
        }
        if (!prefs.contains(Keys.DETAIL_TEXT_COLOR_LIGHT)) {
            editor.putString(Keys.DETAIL_TEXT_COLOR_LIGHT, "#CC000000")
            changed = true
        }
        if (!prefs.contains(Keys.DETAIL_TEXT_COLOR_DARK)) {
            editor.putString(Keys.DETAIL_TEXT_COLOR_DARK, "#CCFFFFFF")
            changed = true
        }
        if (!prefs.contains(Keys.ROUND_AVATAR_RADIUS)) {
            // 默认方圆 0.36（用户确认最好看）
            editor.putString(Keys.ROUND_AVATAR_RADIUS, "0.36")
            changed = true
        } else {
            val old = prefs.getString(Keys.ROUND_AVATAR_RADIUS, null)?.toFloatOrNull()
            if (old != null && old < RoundAvatarConfig.MIN_RADIUS) {
                editor.putString(
                    Keys.ROUND_AVATAR_RADIUS,
                    String.format(Locale.US, "%.2f", RoundAvatarConfig.MIN_RADIUS)
                )
                changed = true
            }
        }

        if (!prefs.getBoolean(Keys.UI_DEFAULTS_FLAG, false)) {
            // 一次性迁移：防撤回与撤回提醒解耦后，若防撤回开着则保证提醒也有默认值
            if (prefs.getBoolean(Keys.ANTI_REVOKE, true) && !prefs.contains(Keys.REVOKE_NOTICE)) {
                editor.putBoolean(Keys.REVOKE_NOTICE, true)
            }
            editor.putBoolean(Keys.UI_DEFAULTS_FLAG, true)
            changed = true
        }

        if (changed) editor.commit()
    }

    private fun bool(key: String, default: Boolean = true): Boolean =
        prefs.getBoolean(key, default)

    private fun writeBool(key: String, value: Boolean) {
        // apply 异步落盘，立刻返回；chmod/公共配置在后台做
        prefs.edit().putBoolean(key, value).apply()
        makePrefsReadable()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
