package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.OKK.yes.core.compat.WeChatClassNames
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 会话首页右下角悬浮快捷入口（参考逆向 WeKit AddMainScreenFab）。
 *
 * 逆向分析结论：
 * 1. WeKit 用 Compose 的 FloatingActionButton 挂在主页 rootView 右下角，点击展开 SmallFAB 菜单。
 * 2. WeKit 的 hook 点是 MainTabUI.doOnCreate（DexKit 字符串锚点），但该字符串在 8.0.76 的
 *    MainTabUI 方法中不构成 const-string 指令（已用 dex 扫描验证），因此 8.0.76 不沿用该锚点。
 * 3. 本实现改用 OKK 已验证的 registerLifecycleGuard + onLauncherTick 模式（HomeAvatarHook 同款）
 *    在首页（LauncherUI/MainTabUI）resume 时挂载悬浮按钮，进入聊天自动隐藏。
 *
 * UI 优化（08-03）：
 * - 主按钮展开时旋转 45°（+ → ×），带弹性动画
 * - 菜单项错峰入场（透明 + 上移 + 缩放），关闭时下移淡出
 * - 白色圆角 pill 菜单项 + 彩色圆点 + 阴影，对比度好
 * - 全屏 scrim 遮罩，点击外部关闭菜单
 * - 主按钮 + 菜单项带涟漪反馈（RippleDrawable）
 *
 * 菜单项类型：
 * - 预设微信页面（扫一扫/收付款/服务/收藏/朋友圈/视频号/设置/我的二维码/卡包/搜一搜/小程序/摇一摇/雷达/附近的人）
 * - 自定义页面（用户输入任意 Activity 类名，launcher=custom）
 * - 打开模块设置（core 不依赖 loader，通过 HomeDrawerBridge 桥接）
 */
object FloatingQuickEntryHook {
    private const val TAG = "OKK-FloatEntry"
    private const val KEY = "floating_quick_entry"
    private const val KEY_ITEMS = "floating_quick_entry_items"
    private const val KEY_CUSTOM = "floating_quick_entry_custom"
    const val KEY_ICON = "floating_quick_entry_icon"
    /** 显示范围：tab=只在 tab1234 首页显示（默认），all=所有页面都显示 */
    const val KEY_SCOPE = "floating_quick_entry_scope"
    private const val FAB_TAG = "achat_fab_root_v1"
    private const val FAB_BTN_TAG = "achat_fab_main"
    private const val MAIN_TAB_UI = "com.tencent.mm.ui.MainTabUI"
    private const val LAUNCHER = "com.tencent.mm.ui.LauncherUI"

    private val installed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val classLoaderRef = AtomicReference<ClassLoader?>(null)
    private val appContextRef = AtomicReference<Context?>(null)
    private val modulePathRef = AtomicReference<String?>(null)

    @Volatile private var hostRef: WeakReference<Activity>? = null
    @Volatile private var fabHost: ViewGroup? = null
    @Volatile private var fabRoot: FrameLayout? = null
    @Volatile private var fabBtn: View? = null
    @Volatile private var scrim: View? = null
    @Volatile private var menu: LinearLayout? = null
    @Volatile private var isChattingOpen = false

    /** 运行时健康上报去重：只在状态变化时上报，避免 ensureFab 高频调用刷屏 */
    @Volatile private var lastFabHealthOk: Boolean? = null
    private fun reportFabHealth(ok: Boolean, detail: String) {
        if (lastFabHealthOk == ok) return
        lastFabHealthOk = ok
        runCatching {
            com.OKK.yes.core.startup.FeatureHookRegistry.reportRuntime("悬浮快捷入口", ok, detail)
        }
    }
    @Volatile private var currentTab = 0
    @Volatile private var currentResumedName = ""
    @Volatile private var enabledCached = false
    @Volatile private var enabledAt = 0L
    private var onResumeHookDone = false

    // 拖动状态
    private var fabDownX = 0f
    private var fabDownY = 0f
    private var fabStartML = 0
    private var fabStartMT = 0
    private var fabDragging = false
    /** 取消过期的延迟 showFab（进聊天后 200/350ms 回调用） */
    @Volatile private var showToken = 0L
    /** 菜单缓存：entries 未变化时复用已构建的菜单项，避免每次展开重建造成卡顿 */
    @Volatile private var cachedMenuEntries: List<Entry>? = null

    private val ENABLE_TTL_MS = 4_000L

    /** 显示范围：all=所有页面显示；tab=仅 tab1234 首页显示（默认） */
    private fun scopeMode(): String {
        return runCatching { PublicConfigStore.getString(KEY_SCOPE, "tab") }
            .getOrDefault("tab")
    }

    private fun isAllPages(): Boolean = scopeMode() == "all"

    /** 预设入口（id / 名称 / launcher 标识） */
    data class Preset(val id: String, val name: String, val launcher: String)

    /** 自定义入口（id=custom_<n>，className 为任意 Activity 类名） */
    data class CustomEntry(val id: String, val name: String, val className: String)

    /** 渲染用入口（预设或自定义统一） */
    data class Entry(val id: String, val name: String, val launcher: String, val className: String? = null)

    /** 全部预设（含新增，用于配置面板展示） */
    val allPresets: List<Preset> = listOf(
        Preset("chat_tab", "微信首页", "chat_tab"),
        Preset("contact_tab", "通讯录", "contact_tab"),
        Preset("discover_tab", "发现", "discover_tab"),
        Preset("me_tab", "我", "me_tab"),
        Preset("qrcode", "扫一扫", "qrcode"),
        Preset("myqr", "我的二维码", "myqr"),
        Preset("pay", "收付款", "pay"),
        Preset("service", "服务", "service"),
        Preset("favorite", "收藏", "favorite"),
        Preset("moments", "朋友圈", "moments"),
        Preset("snspost", "发朋友圈", "snspost"),
        Preset("snsmsg", "朋友圈消息", "snsmsg"),
        Preset("channel", "视频号", "channel"),
        Preset("balance", "零钱钱包", "balance"),
        Preset("miniapp", "小程序", "miniapp"),
        Preset("addfriend", "添加朋友", "addfriend"),
        Preset("shake", "摇一摇", "shake"),
        Preset("brand", "公众号", "brand"),
        Preset("settings", "微信设置", "settings"),
        Preset("module", "OKK", "module")
    )

    /** 默认启用条目（保持原 8 项，避免默认菜单过长） */
    private val defaultItemIds = listOf(
        "qrcode", "pay", "service", "favorite", "moments", "channel", "settings", "module"
    )

    /** 每个预设的圆点/主题颜色（优雅微彩调，不再使用强对比突兀色） */
    private val presetColors = mapOf(
        "chat_tab" to "#07C160",
        "contact_tab" to "#07C160",
        "discover_tab" to "#07C160",
        "me_tab" to "#07C160",
        "qrcode" to "#07C160",
        "myqr" to "#07C160",
        "pay" to "#07C160",
        "service" to "#07C160",
        "favorite" to "#07C160",
        "moments" to "#07C160",
        "snspost" to "#07C160",
        "snsmsg" to "#07C160",
        "channel" to "#07C160",
        "balance" to "#07C160",
        "miniapp" to "#07C160",
        "addfriend" to "#07C160",
        "shake" to "#07C160",
        "brand" to "#07C160",
        "settings" to "#576B95",
        "module" to "#07C160"
    )

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        classLoaderRef.set(classLoader)
        appContextRef.set(context.applicationContext ?: context)
        modulePathRef.set(modulePath)
        enabledCached = isEnabled(force = true)
        xlog("install enabled=$enabledCached")
        // 始终注册生命周期守卫与聊天 Hook，开关状态在 showFab 时实时检查。
        // 这样设置里打开开关后无需重启即可生效（保存即生效）。
        registerLifecycleGuard(context)
        hookChattingState(classLoader)
        // 监听开关变化：开->关 立即隐藏，关->开 刷新缓存并尝试显示
        PublicConfigStore.addListener(KEY) {
            val now = isEnabled(force = true)
            xlog("config changed: enabled=$now")
            if (!now) {
                hideFab(immediate = true)
                closeMenu()
            } else {
                hostRef?.get()?.let { act -> showFab(act) }
            }
        }
        // 监听显示范围变化：切到 Tab 模式时若当前在聊天则隐藏，切到所有页面则显示
        PublicConfigStore.addListener(KEY_SCOPE) {
            hostRef?.get()?.let { act -> showFab(act) }
        }
        // 监听图标变化：重建 FAB 以应用新图标
        PublicConfigStore.addListener(KEY_ICON) {
            hostRef?.get()?.let { act -> rebuildFabIcon(act) }
        }
        // 启动常驻健康守护：按钮间歇性丢失时自动恢复（永久解决）
        startFabHealthWatchdog()
    }

    fun isEnabled(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && enabledAt > 0 && now - enabledAt < ENABLE_TTL_MS) {
            return enabledCached
        }
        enabledCached = runCatching {
            if (force) PublicConfigStore.reload(true)
            PublicConfigStore.getBoolean(KEY, true)
        }.getOrDefault(true)
        enabledAt = now
        return enabledCached
    }

    private fun hookChattingState(classLoader: ClassLoader) {
        // 8.0.76 聊天为 LauncherUI 内 fragment，onActivityResumed 不触发，需 Hook 聊天进入/退出
        // 进入聊天隐藏悬浮按钮，退出聊天（回到首页）恢复
        runCatching {
            val launcher = XposedHelpers.findClass(LAUNCHER, classLoader)
            XposedHelpers.findAndHookMethod(
                launcher,
                "startChatting",
                String::class.java,
                android.os.Bundle::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        isChattingOpen = true
                        invalidateShowToken()
                        if (!isAllPages()) hideFab(immediate = true)
                    }
                }
            )
            xlog("hooked startChatting hide fab")
        }.onFailure { xlog("startChatting hook: ${it.message}") }

        runCatching {
            val tabUi = XposedHelpers.findClass("com.tencent.mm.ui.NewChattingTabUI", classLoader)
            XposedHelpers.findAndHookMethod(
                tabUi,
                "r",
                String::class.java,
                android.os.Bundle::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        isChattingOpen = true
                        invalidateShowToken()
                        if (!isAllPages()) hideFab(immediate = true)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                tabUi,
                "q",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if ((param.args.getOrNull(0) as? Int) != 0) return
                        isChattingOpen = false
                        scheduleShowFab(200L)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                tabUi,
                "f",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        isChattingOpen = false
                        scheduleShowFab(200L)
                    }
                }
            )
            xlog("hooked NewChattingTabUI fab visibility")
        }.onFailure { xlog("NewChattingTabUI fab hook: ${it.message}") }
    }

    private fun registerLifecycleGuard(context: Context) {
        val app = (context as? android.app.Application)
            ?: (context.applicationContext as? android.app.Application)
            ?: return
        runCatching {
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    currentResumedName = activity.javaClass.name
                    val isHome = currentResumedName.contains("LauncherUI") ||
                        currentResumedName.contains("MainTabUI")
                    if (isHome) {
                        hostRef = WeakReference(activity)
                        onLauncherTick(activity)
                    } else if (isAllPages()) {
                        // 所有页面模式：非首页 activity 也显示悬浮按钮（挂到该 activity）
                        hostRef = WeakReference(activity)
                        // 上次按钮挂在旧 activity 的 decor 上，切页后需重建到新 activity
                        val host = fabHost
                        if (host != null && host.context !== activity &&
                            fabBtn?.isAttachedToWindow == false
                        ) {
                            detachCurrentFab()
                        }
                        onLauncherTick(activity)
                    } else {
                        invalidateShowToken()
                        hideFab(immediate = true)
                    }
                }
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
            xlog("lifecycle guard registered")
        }.onFailure { xlog("lifecycle guard fail: ${it.message}") }
    }

    private fun onLauncherTick(activity: Activity) {
        if (activity.isFinishing) return
        if (!isEnabled()) {
            invalidateShowToken()
            hideFab(immediate = true)
            return
        }
        if (isAllPages()) {
            // 所有页面模式：不探测聊天，直接显示（挂在当前 activity）
            showFab(activity)
            return
        }
        // 先探测再决定显示：避免进聊天后 350ms 延迟 show 把按钮画回来
        val token = nextShowToken()
        mainHandler.postDelayed({
            if (token != showToken) return@postDelayed
            if (activity.isFinishing) return@postDelayed
            val chatting = runCatching {
                ThemeWallpaperController.isChattingForeground(activity)
            }.getOrDefault(false)
            if (chatting) {
                isChattingOpen = true
                hideFab(immediate = true)
            } else {
                isChattingOpen = false
                showFab(activity)
            }
        }, 120L)
    }

    private fun nextShowToken(): Long {
        val t = System.currentTimeMillis()
        showToken = t
        return t
    }

    private fun invalidateShowToken() {
        showToken = 0L
    }

    private fun scheduleShowFab(delayMs: Long) {
        val token = nextShowToken()
        mainHandler.postDelayed({
            if (token != showToken) return@postDelayed
            if (isChattingOpen && !isAllPages()) return@postDelayed
            val act = hostRef?.get() ?: return@postDelayed
            if (act.isFinishing) return@postDelayed
            if (isAllPages()) {
                showFab(act)
                return@postDelayed
            }
            // 二次确认：仍在聊天前台则不显示
            val chatting = runCatching {
                ThemeWallpaperController.isChattingForeground(act)
            }.getOrDefault(false)
            if (chatting) {
                isChattingOpen = true
                hideFab(immediate = true)
                return@postDelayed
            }
            showFab(act)
        }, delayMs)
    }

    private fun isMenuOpen(): Boolean {
        return menu?.visibility == View.VISIBLE || fabRoot?.visibility == View.VISIBLE
    }

    /** 处理悬浮按钮拖动：菜单展开时禁止拖动只响应点击关闭；收起后可拖动 */
    private fun handleFabTouch(activity: Activity, event: MotionEvent): Boolean {
        val btn = fabBtn ?: return false
        // 菜单展开期间：不允许拖动，抬手仅切换关闭
        if (isMenuOpen()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fabDragging = false
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    closeMenu()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
                else -> return true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fabDownX = event.rawX
                fabDownY = event.rawY
                val lp = btn.layoutParams as? FrameLayout.LayoutParams
                fabStartML = lp?.leftMargin ?: 0
                fabStartMT = lp?.topMargin ?: 0
                fabDragging = false
                btn.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - fabDownX
                val dy = event.rawY - fabDownY
                val slop = dp(activity, 6)
                if (!fabDragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                    fabDragging = true
                }
                if (fabDragging) {
                    val lp = btn.layoutParams as? FrameLayout.LayoutParams ?: return true
                    val parent = btn.parent as? ViewGroup ?: return true
                    val size = btn.width.takeIf { it > 0 } ?: dp(activity, 52)
                    // 拖动时最多允许半个按钮出界，保证始终点得到
                    val half = size / 2
                    val maxL = (parentWidth(parent) - half).coerceAtLeast(-half)
                    val maxT = (parentHeight(parent) - half).coerceAtLeast(-half)
                    lp.leftMargin = (fabStartML + dx).toInt().coerceIn(-half, maxL)
                    lp.topMargin = (fabStartMT + dy).toInt().coerceIn(-half, maxT)
                    btn.layoutParams = lp
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                btn.parent?.requestDisallowInterceptTouchEvent(false)
                if (fabDragging) {
                    fabDragging = false
                    // 松手：完整吸附到最近左右边角（按钮完全在屏幕内）
                    snapToEdge(activity, btn)
                    return true
                }
                // 未拖动 → 正常点击（打开菜单）
                btn.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                btn.parent?.requestDisallowInterceptTouchEvent(false)
                fabDragging = false
                return true
            }
        }
        return false
    }

    private fun parentWidth(parent: ViewGroup): Int {
        return parent.width.takeIf { it > 0 }
            ?: parent.measuredWidth.takeIf { it > 0 }
            ?: parent.resources.displayMetrics.widthPixels
    }

    private fun parentHeight(parent: ViewGroup): Int {
        return parent.height.takeIf { it > 0 }
            ?: parent.measuredHeight.takeIf { it > 0 }
            ?: parent.resources.displayMetrics.heightPixels
    }

    /** 拖动结束后完整吸附到最近左右边缘，按钮完全在可见区域内 */
    private fun snapToEdge(activity: Activity, btn: View) {
        val parent = btn.parent as? ViewGroup ?: return
        val lp = btn.layoutParams as? FrameLayout.LayoutParams ?: return
        val size = btn.width.takeIf { it > 0 } ?: dp(activity, 52)
        val pw = parentWidth(parent)
        val ph = parentHeight(parent)
        val edge = dp(activity, 12)
        val topMin = edge
        val bottomPad = dp(activity, 88) // 避开底栏/手势区
        val topMax = (ph - size - bottomPad).coerceAtLeast(topMin)
        val leftEdge = edge
        val rightEdge = (pw - size - edge).coerceAtLeast(leftEdge)
        val cx = lp.leftMargin + size / 2
        lp.leftMargin = if (cx < pw / 2) leftEdge else rightEdge
        lp.topMargin = lp.topMargin.coerceIn(topMin, topMax)
        lp.gravity = Gravity.TOP or Gravity.START
        btn.layoutParams = lp
        xlog("fab snapped edge left=${lp.leftMargin} top=${lp.topMargin} pw=$pw ph=$ph")
    }

    /** 首次创建后按真实 parent 尺寸落到默认右下角（完整可见） */
    private fun placeFabDefault(activity: Activity, btn: View) {
        val parent = btn.parent as? ViewGroup ?: return
        val lp = btn.layoutParams as? FrameLayout.LayoutParams ?: return
        val size = lp.width.takeIf { it > 0 } ?: dp(activity, 52)
        fun apply() {
            val pw = parentWidth(parent)
            val ph = parentHeight(parent)
            val edge = dp(activity, 12)
            val bottomPad = dp(activity, 88)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = (pw - size - edge).coerceAtLeast(edge)
            lp.topMargin = (ph - size - bottomPad).coerceAtLeast(edge)
            btn.layoutParams = lp
            xlog("fab default place left=${lp.leftMargin} top=${lp.topMargin} pw=$pw ph=$ph size=$size")
        }
        if (parentWidth(parent) > 0 && parentHeight(parent) > 0) {
            apply()
        } else {
            parent.post { apply() }
        }
    }

    private fun showFab(activity: Activity) {
        if (!isEnabled()) return
        if (isChattingOpen && !isAllPages()) return
        val allPages = isAllPages()
        if (!allPages) {
            // 防御：当前前台必须是首页 LauncherUI/MainTabUI，否则不显示悬浮按钮
            // （查看图片/视频通话等独立 Activity 时，延迟 showFab 可能把按钮画回去）
            val resumed = currentResumedName
            if (resumed.isNotEmpty() &&
                !resumed.contains("LauncherUI") &&
                !resumed.contains("MainTabUI")
            ) return
            // 悬浮按钮仅在 4 个主 Tab（微信/通讯录/发现/我，index 0-3）显示
            if (currentTab < 0 || currentTab > 3) return
            // 实时再确认一次聊天前台，避免延迟 token 漏检
            val chattingNow = runCatching {
                ThemeWallpaperController.isChattingForeground(activity)
            }.getOrDefault(false)
            if (chattingNow) {
                isChattingOpen = true
                hideFab(immediate = true)
                return
            }
        }
        runCatching {
            if (!ensureFab(activity)) return
            val btn = fabBtn ?: return
            // 清掉同 host 上任何残留 FAB（防止拖动时出现双图标）
            purgeStaleFabs(btn.parent as? ViewGroup, keep = btn)
            if (btn.visibility != View.VISIBLE) {
                btn.animate().cancel()
                btn.visibility = View.VISIBLE
                btn.alpha = 0f
                btn.scaleX = 0.85f
                btn.scaleY = 0.85f
                btn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                    .start()
            }
            val parent = btn.parent as? ViewGroup
            if (parent != null) {
                if (parent.indexOfChild(btn) != parent.childCount - 1) btn.bringToFront()
            }
            startChatWatchdog(activity)
        }.onFailure { xlog("showFab fail: ${it.message}") }
    }

    private fun hideFab(immediate: Boolean = false) {
        invalidateShowToken()
        val btn = fabBtn
        if (btn != null) {
            btn.animate().cancel()
            if (immediate || btn.visibility != View.VISIBLE) {
                btn.alpha = 1f
                btn.scaleX = 1f
                btn.scaleY = 1f
                btn.visibility = View.GONE
            } else {
                btn.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(140L)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction {
                        btn.visibility = View.GONE
                        btn.alpha = 1f
                        btn.scaleX = 1f
                        btn.scaleY = 1f
                    }
                    .start()
            }
        }
        // 兜底：同 host 上所有 FAB 一并隐藏（避免旧实例残留）
        (btn?.parent as? ViewGroup)?.let { purgeStaleFabs(it, keep = btn, hideOnly = true) }
        stopChatWatchdog()
        closeMenu()
    }

    // 聊天前台看门狗：周期性探测当前是否处于聊天，进入聊天则隐藏悬浮按钮（8.0.76 聊天为 fragment，
    // onActivityResumed 与 startChatting hook 均不触发，只能靠视图树探测兜底）
    private val chatWatchdog = object : Runnable {
        override fun run() {
            if (!watchdogRunning.get()) return
            val act = hostRef?.get()
            if (act == null || act.isFinishing) {
                stopChatWatchdog()
                return
            }
            // 所有页面模式：不因聊天隐藏
            if (isAllPages()) {
                mainHandler.postDelayed(this, 700L)
                return
            }
            val chatting = runCatching { ThemeWallpaperController.isChattingForeground(act) }.getOrDefault(false)
            if (chatting) {
                isChattingOpen = true
                hideFab(immediate = true)
                return
            }
            // 按钮应可见时继续盯；已隐藏则停
            if (fabBtn?.visibility == View.VISIBLE) {
                mainHandler.postDelayed(this, 700L)
            } else {
                stopChatWatchdog()
            }
        }
    }
    private val watchdogRunning = AtomicBoolean(false)

    private fun startChatWatchdog(activity: Activity) {
        if (watchdogRunning.compareAndSet(false, true)) {
            mainHandler.postDelayed(chatWatchdog, 600L)
        }
    }

    private fun stopChatWatchdog() {
        if (watchdogRunning.compareAndSet(true, false)) {
            mainHandler.removeCallbacks(chatWatchdog)
        }
    }

    /** 悬浮按钮健康守护：周期检查应显示时按钮是否仍在 View 树且可见，掉了立即重新显示（永久解决间歇性丢失）。
     * 与 chatWatchdog 独立：chatWatchdog 只在按钮可见时跑（检测聊天），本守护常驻跑（掉了也恢复）。 */
    private val fabHealthRunning = AtomicBoolean(false)
    private val fabHealthWatchdog = object : Runnable {
        override fun run() {
            if (!fabHealthRunning.get()) return
            if (!isEnabled()) {
                mainHandler.postDelayed(this, 3000L)
                return
            }
            val act = hostRef?.get()
            if (act == null || act.isFinishing) {
                // 无前台引用：稍后重试（回到首页时 hostRef 会更新）
                mainHandler.postDelayed(this, 3000L)
                return
            }
            // 关键修复：8.0.76 聊天是 LauncherUI 内 fragment，进/出聊天都不触发 onActivityResumed，
            // 导致 isChattingOpen 在退出聊天后卡 true 永不重置 → 按钮永不恢复。
            // 这里每 2 秒实时探测聊天前台并同步 isChattingOpen，退出聊天后能正确识别回到首页。
            val chattingNow = if (isAllPages()) {
                false
            } else {
                runCatching { ThemeWallpaperController.isChattingForeground(act) }.getOrDefault(false)
            }
            isChattingOpen = chattingNow
            val shouldShow = if (isAllPages()) {
                true
            } else {
                val resumed = currentResumedName
                (resumed.contains("LauncherUI") || resumed.contains("MainTabUI")) &&
                    !chattingNow && currentTab in 0..3
            }
            val btnAlive = fabBtn?.let { it.isAttachedToWindow && it.visibility == View.VISIBLE } == true
            if (shouldShow && !btnAlive) {
                xlog("fab health: lost, re-show")
                // showFab 内部有完整守卫（开关/聊天/Tab/前台），误触发也会被拦下，安全
                showFab(act)
            }
            mainHandler.postDelayed(this, 2000L)
        }
    }
    private fun startFabHealthWatchdog() {
        if (fabHealthRunning.compareAndSet(false, true)) {
            mainHandler.postDelayed(fabHealthWatchdog, 2500L)
        }
    }
    private fun stopFabHealthWatchdog() {
        if (fabHealthRunning.compareAndSet(true, false)) {
            mainHandler.removeCallbacks(fabHealthWatchdog)
        }
    }

    /** 移除/隐藏 host 上残留的 FAB（拖动重建时防双图标） */
    private fun purgeStaleFabs(host: ViewGroup?, keep: View?, hideOnly: Boolean = false) {
        if (host == null) return
        val doomed = ArrayList<View>()
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i) ?: continue
            val tag = child.tag?.toString() ?: continue
            val isFab = tag == FAB_BTN_TAG || tag == FAB_TAG || child is FabIconView
            if (!isFab) continue
            if (keep != null && (child === keep || child === fabRoot)) continue
            doomed.add(child)
        }
        doomed.forEach { v ->
            if (hideOnly) {
                v.animate().cancel()
                v.visibility = View.GONE
            } else {
                runCatching { host.removeView(v) }
            }
        }
    }

    private fun detachCurrentFab() {
        runCatching {
            (fabBtn?.parent as? ViewGroup)?.removeView(fabBtn)
        }
        runCatching {
            (fabRoot?.parent as? ViewGroup)?.removeView(fabRoot)
        }
        fabBtn = null
        fabRoot = null
        menu = null
        scrim = null
        fabHost = null
    }

    /** 图标/样式变化时重建 FAB（保存即生效）：拆掉旧的，ensureFab 会用新配置重建。 */
    private fun rebuildFabIcon(activity: Activity) {
        closeMenu()
        detachCurrentFab()
        showFab(activity)
    }

    /** 创建悬浮按钮（独立小 overlay，始终可点）+ 全屏菜单层（GONE，仅展开时可见） */
    private fun ensureFab(activity: Activity): Boolean {
        val liveBtn = fabBtn
        val liveRoot = fabRoot
        val liveHost = fabHost
        if (liveHost?.isAttachedToWindow == true &&
            liveBtn?.isAttachedToWindow == true &&
            liveRoot?.isAttachedToWindow == true &&
            liveBtn.parent === liveHost &&
            liveRoot.parent === liveHost &&
            // 按钮必须挂在当前前台 activity 的窗口上，否则旧页面按钮不可见需重建
            (liveHost.context === activity || liveHost.isAttachedToWindow == false)
        ) {
            // 仍在同一 host：只清残留，不重建
            purgeStaleFabs(liveHost, keep = liveBtn)
            return true
        }
        // host 失效或属于旧 activity：拆掉旧引用重建
        if (liveHost != null && liveHost.context !== activity) {
            xlog("ensureFab: re-attach to new activity (old=${liveHost.context?.javaClass?.simpleName})")
        }
        detachCurrentFab()

        val decor = activity.window?.decorView as? ViewGroup
            ?: activity.findViewById(android.R.id.content) as? ViewGroup
            ?: run {
                reportFabHealth(false, "无可用窗口注入 FAB")
                return false
            }
        val host = findOverlayHost(decor)
        // 先清掉 decor/host 上历史残留（热重载/重建后常见）
        purgeStaleFabs(host, keep = null)
        if (host !== decor) purgeStaleFabs(decor, keep = null)

        val size = dp(activity, 52)
        return runCatching {
            // 1) 独立的小按钮 overlay（始终可见，不挡任何区域）
            val currentIcon = runCatching { PublicConfigStore.getString(KEY_ICON, "grid") }.getOrDefault("grid")
            val btn = FabIconView(activity).apply {
                tag = FAB_BTN_TAG
                iconStyle = currentIcon
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleMenu(activity) }
                setOnTouchListener { _, event -> handleFabTouch(activity, event) }
            }
            // Gravity.TOP|START：拖动改 left/top；初始先占位，再按真实 parent 尺寸落到右下角
            host.addView(btn, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(activity, 12)
                topMargin = dp(activity, 12)
            })
            if (Build.VERSION.SDK_INT >= 21) btn.elevation = dp(activity, 12).toFloat()
            fabBtn = btn
            placeFabDefault(activity, btn)

            // 2) 全屏菜单层（默认 GONE，展开时承载 scrim + 菜单）
            val root = FrameLayout(activity).apply {
                tag = FAB_TAG
                isClickable = true
                isFocusable = false
                setOnClickListener { closeMenu() }
                visibility = View.GONE
            }
            if (Build.VERSION.SDK_INT >= 21) root.elevation = dp(activity, 20).toFloat()

            val scrimView = View(activity).apply {
                tag = "achat_fab_scrim"
                setBackgroundColor(Color.parseColor("#4D000000"))
                alpha = 0f
                isClickable = true
                setOnClickListener { closeMenu() }
            }
            root.addView(scrimView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            val menuView = LinearLayout(activity).apply {
                tag = "achat_fab_menu"
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                visibility = View.GONE
            }
            root.addView(menuView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                marginEnd = dp(activity, 16)
                bottomMargin = dp(activity, 152)
            })

            host.addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            fabHost = host
            fabRoot = root
            menu = menuView
            scrim = scrimView
            xlog("fab created (single)")
            reportFabHealth(true, "FAB 已注入并显示")
            true
        }.getOrElse {
            reportFabHealth(false, "FAB 注入异常: ${it.javaClass.simpleName}")
            false
        }
    }

    private fun toggleMenu(activity: Activity) {
        val btn = fabBtn ?: return
        if (btn.visibility != View.VISIBLE) return
        val menuView = menu ?: return
        if (menuView.visibility == View.VISIBLE) {
            closeMenu()
        } else {
            openMenu(activity)
        }
    }

    /** 复位菜单子项可见性，避免上次动画中断后 alpha=0 导致条目缺失 */
    private fun resetMenuChildren(menuView: ViewGroup) {
        for (i in 0 until menuView.childCount) {
            val child = menuView.getChildAt(i) ?: continue
            child.animate().cancel()
            child.alpha = 1f
            child.translationY = 0f
            child.translationX = 0f
            child.scaleX = 1f
            child.scaleY = 1f
            child.visibility = View.VISIBLE
        }
    }

    private fun openMenu(activity: Activity) {
        val menuView = menu ?: return
        val btn = fabBtn ?: return
        val scrimView = scrim ?: return
        val root = fabRoot ?: return
        // 展开期间禁止拖动
        fabDragging = false

        // 取消上次未完成的展开/收起动画，避免半残状态
        scrimView.animate().cancel()
        btn.animate().cancel()
        menuView.animate().cancel()

        val host = (btn.parent as? ViewGroup) ?: root
        val parentW = parentWidth(host).coerceAtLeast(parentWidth(root)).coerceAtLeast(1)
        val parentH = parentHeight(host).coerceAtLeast(parentHeight(root)).coerceAtLeast(1)
        val edge = dp(activity, 12)
        val gap = dp(activity, 10)
        val size = btn.width.takeIf { it > 0 } ?: dp(activity, 52)

        // 可用宽度内构建菜单；entries 变化才重建，其余复用
        val maxMenuW = (parentW - edge * 2).coerceAtLeast(dp(activity, 120))
        val entries = loadEntries()
        if (cachedMenuEntries != entries || menuView.childCount != entries.size) {
            rebuildMenu(activity, menuView, maxMenuW, entries)
            cachedMenuEntries = entries
        }
        // 关键：子项完整可见（防止上次动画中断留下 alpha=0）
        resetMenuChildren(menuView)

        // 强制测量完整菜单尺寸（含全部子项）
        menuView.measure(
            View.MeasureSpec.makeMeasureSpec(maxMenuW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuW = menuView.measuredWidth.coerceIn(dp(activity, 96), maxMenuW)
        val menuH = menuView.measuredHeight.coerceAtLeast(
            (menuView.childCount * dp(activity, 48)).coerceAtLeast(dp(activity, 40))
        )

        val fabLeft = when {
            btn.width > 0 -> btn.left
            else -> (btn.layoutParams as? FrameLayout.LayoutParams)?.leftMargin ?: 0
        }
        val fabTop = when {
            btn.height > 0 -> btn.top
            else -> (btn.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: 0
        }
        val fabRight = fabLeft + size
        val fabCx = fabLeft + size / 2

        val mlp = (menuView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        // 显式固定宽高，避免 WRAP_CONTENT 在动画中被裁切
        mlp.width = menuW
        mlp.height = menuH
        mlp.gravity = Gravity.TOP or Gravity.START

        // 水平：贴 FAB 所在侧，整块菜单完整落在屏幕内（预留 edge）
        val preferRight = fabCx >= parentW / 2
        val maxLeft = (parentW - edge - menuW).coerceAtLeast(edge)
        mlp.leftMargin = if (preferRight) {
            (fabRight - menuW).coerceIn(edge, maxLeft)
        } else {
            fabLeft.coerceIn(edge, maxLeft)
        }

        // 垂直：优先 FAB 上方；不够则下方；再不够贴顶
        val above = fabTop - menuH - gap
        val below = fabTop + size + gap
        mlp.topMargin = when {
            above >= edge -> above
            below + menuH <= parentH - edge -> below
            else -> edge
        }
        if (mlp.topMargin + menuH > parentH - edge) {
            mlp.topMargin = (parentH - edge - menuH).coerceAtLeast(edge)
        }
        menuView.layoutParams = mlp

        // 关闭裁切，防止圆角阴影/右侧被父布局裁掉
        root.clipChildren = false
        root.clipToPadding = false
        (host as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        (menuView as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }

        root.visibility = View.VISIBLE
        root.bringToFront()
        btn.bringToFront()
        if (Build.VERSION.SDK_INT >= 21) {
            root.elevation = dp(activity, 20).toFloat()
            btn.elevation = dp(activity, 24).toFloat()
        }

        // 遮罩 + 按钮旋转
        scrimView.visibility = View.VISIBLE
        scrimView.alpha = 0f
        scrimView.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator(1.5f)).start()
        btn.animate().rotation(45f).setDuration(240).setInterpolator(OvershootInterpolator(1.15f)).start()

        // 整块菜单弹性展开动画
        menuView.visibility = View.VISIBLE
        menuView.alpha = 0f
        menuView.translationY = dp(activity, 18).toFloat()
        menuView.scaleX = 0.88f
        menuView.scaleY = 0.88f
        menuView.pivotX = menuW.toFloat()
        menuView.pivotY = menuH.toFloat()
        menuView.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()

        xlog("openMenu entries=${entries.size} child=${menuView.childCount} " +
            "menu=${menuW}x${menuH} at (${mlp.leftMargin},${mlp.topMargin}) parent=${parentW}x${parentH}")
    }

    private fun closeMenu() {
        val menuView = menu ?: return
        val btn = fabBtn ?: return
        val scrimView = scrim ?: return
        val root = fabRoot ?: return
        if (menuView.visibility != View.VISIBLE && root.visibility != View.VISIBLE) return

        // 取消所有进行中的动画
        scrimView.animate().cancel()
        btn.animate().cancel()
        menuView.animate().cancel()

        scrimView.animate().alpha(0f).setDuration(140).start()
        btn.animate().rotation(0f).setDuration(160).start()

        // 整块菜单淡出后统一隐藏并复位（不做逐项动画）
        menuView.animate()
            .alpha(0f)
            .translationY(dp(scrimView.context, 12).toFloat())
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(140)
            .withEndAction {
                runCatching {
                    resetMenuChildren(menuView)
                    scrimView.animate().cancel()
                    scrimView.alpha = 0f
                    scrimView.visibility = View.GONE
                    menuView.alpha = 1f
                    menuView.translationY = 0f
                    menuView.scaleX = 1f
                    menuView.scaleY = 1f
                    menuView.visibility = View.GONE
                    root.visibility = View.GONE
                    if (Build.VERSION.SDK_INT >= 21) {
                        btn.elevation = dp(btn.context, 12).toFloat()
                    }
                }
            }
            .start()
    }

    private fun rebuildMenu(activity: Activity, menuView: ViewGroup, maxMenuW: Int = Int.MAX_VALUE, entries: List<Entry> = loadEntries()) {
        menuView.removeAllViews()
        // 统一宽度：取所有入口名的最宽文字宽度（含 padding 与圆点），并限制不超过可用宽度
        var maxW = 0
        entries.forEach { entry ->
            val w = measureTextWidth(activity, entry.name, 13f)
            if (w > maxW) maxW = w
        }
        val contentPad = dp(activity, 14) + dp(activity, 16) + dp(activity, 8) + dp(activity, 16)
        val desired = maxW + contentPad
        val rowW = desired.coerceIn(dp(activity, 96), maxMenuW.coerceAtLeast(dp(activity, 96)))
        entries.forEach { entry ->
            val row = buildMenuRow(activity, entry, rowW)
            val lp = LinearLayout.LayoutParams(
                rowW,
                dp(activity, 40)
            ).apply {
                bottomMargin = dp(activity, 8)
            }
            menuView.addView(row, lp)
        }
    }

    /** 测量文字宽度（近似） */
    private fun measureTextWidth(activity: Activity, text: String, sp: Float): Int {
        val tv = TextView(activity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            includeFontPadding = false
            typeface = android.graphics.Typeface.DEFAULT
        }
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return tv.measuredWidth
    }

    /** 构建一个白色圆角 pill 菜单项：左侧精细图标徽章 + 名称（统一宽度） */
    private fun buildMenuRow(activity: Activity, entry: Entry, rowW: Int): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 10), 0, dp(activity, 14), 0)
            background = ripplePill(activity, "#FFFFFF")
            elevation = dp(activity, 4).toFloat()
            isClickable = true
            isFocusable = true
        }
        val badgeSize = dp(activity, 22)
        val badge = MenuEntryBadgeView(activity, entry.id).apply {
            isClickable = true
            isFocusable = true
        }
        row.addView(badge, LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
            marginEnd = dp(activity, 10)
        })
        val label = TextView(activity).apply {
            text = entry.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#191919"))
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
        }
        row.addView(label, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        ))

        val clickAction = View.OnClickListener {
            launch(activity, entry)
            closeMenu()
        }
        row.setOnClickListener(clickAction)
        badge.setOnClickListener(clickAction)
        label.setOnClickListener(clickAction)

        return row
    }

    private fun rippleCircle(context: Context, size: Int, color: String): android.graphics.drawable.Drawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        return rippleOf(context, content, mask)
    }

    private fun ripplePill(context: Context, color: String): android.graphics.drawable.Drawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 20).toFloat()
            setColor(Color.parseColor(color))
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 20).toFloat()
            setColor(Color.WHITE)
        }
        return rippleOf(context, content, mask)
    }

    private fun rippleOf(context: Context, content: android.graphics.drawable.Drawable, mask: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable {
        return if (Build.VERSION.SDK_INT >= 21) {
            RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33000000")),
                content,
                mask
            )
        } else {
            content
        }
    }

    private fun launch(activity: Activity, entry: Entry) {
        if (entry.launcher == "custom") {
            entry.className?.takeIf { it.isNotBlank() }?.let {
                WeChatPageLauncher.openClass(activity, it)
            }
            return
        }
        when (entry.launcher) {
            "chat_tab" -> switchToMainTab(activity, 0)
            "contact_tab" -> switchToMainTab(activity, 1)
            "discover_tab" -> switchToMainTab(activity, 2)
            "me_tab" -> switchToMainTab(activity, 3)
            "qrcode" -> WeChatPageLauncher.openClass(activity, "com.tencent.mm.plugin.scanner.ui.BaseScanUI")
            "myqr" -> WeChatPageLauncher.openQrCode(activity)
            "pay" -> WeChatPageLauncher.openOfflinePay(activity)
            "service" -> WeChatPageLauncher.openService(activity)
            "favorite" -> WeChatPageLauncher.openFavorite(activity)
            "moments" -> WeChatPageLauncher.openClass(activity, "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI")
            "snspost" -> WeChatPageLauncher.openClass(activity, "com.tencent.mm.plugin.sns.ui.SnsUploadUI")
            "snsmsg" -> WeChatPageLauncher.openClass(activity, "com.tencent.mm.plugin.sns.ui.SnsMsgUI")
            "channel" -> WeChatPageLauncher.openClass(activity, "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI")
            "balance" -> WeChatPageLauncher.openClass(activity, "com.tencent.mm.plugin.wallet.balance.ui.WalletBalanceManagerUI")
            "miniapp" -> WeChatPageLauncher.openClass(activity, "com.tencent.mm.plugin.appbrand.ui.AppBrandUI")
            "addfriend" -> WeChatPageLauncher.openClass(activity, "com.tencent.mm.plugin.subapp.ui.pluginapp.AddMoreFriendsUI")
            "brand" -> WeChatPageLauncher.openClass(activity, "com.tencent.mm.plugin.brandservice.ui.BrandServiceIndexUI")
            "shake" -> WeChatPageLauncher.openClass(activity, "com.tencent.mm.plugin.shake.ui.ShakeReportUI")
            "settings" -> WeChatPageLauncher.openClass(activity, WeChatClassNames.MAIN_SETTINGS_UI)
            "module" -> {
                val targetAct = hostRef?.get()?.takeIf {
                    !it.isFinishing && !it.isDestroyed &&
                        (android.os.Build.VERSION.SDK_INT < 19 || it.window?.decorView?.isAttachedToWindow == true)
                } ?: activity
                val open = HomeDrawerBridge.openSettings
                if (open != null) {
                    open(targetAct)
                } else {
                    runCatching {
                        val cls = Class.forName("com.OKK.yes.loader.ui.OKKSettingsDialog", false, targetAct.classLoader)
                        val m = cls.getMethod("show", Activity::class.java)
                        m.invoke(null, targetAct)
                    }.onFailure {
                        xlog("fallback open settings fail: ${it.message}")
                    }
                }
            }
        }
    }

    private fun switchToMainTab(activity: Activity, tabIndex: Int) {
        runCatching {
            val cl = activity.classLoader
            val intent = Intent(activity, XposedHelpers.findClass(LAUNCHER, cl))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            activity.startActivity(intent)
        }
        // 统一先发送 CLEAR_TOP | SINGLE_TOP 关闭当前聊天界面 / 二级 Activity，
        // 然后在主线程延迟 200ms 切换到目标 Tab，确保在聊天窗口内点击也能顺畅关闭聊天并跳转 Tab
        mainHandler.postDelayed({ BottomTabFloatingHook.navigateToTab(tabIndex) }, 200L)
    }

    /** 读取自定义条目（id|name|className 以 ; 连接；兼容旧格式 name|className） */
    fun loadCustomEntries(): List<CustomEntry> {
        val raw = runCatching {
            PublicConfigStore.getString(KEY_CUSTOM, "")
        }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapIndexedNotNull { index, seg ->
            val parts = seg.split("|")
            if (parts.size < 2) return@mapIndexedNotNull null
            val id = if (parts.size >= 3 && parts[0].isNotBlank()) parts[0] else "custom_$index"
            val name = parts[parts.size - 2].trim()
            val cls = parts[parts.size - 1].trim()
            if (name.isBlank() || cls.isBlank()) null else CustomEntry(id, name, cls)
        }
    }

    /** 编码自定义条目（含稳定 id） */
    fun encodeCustomEntries(list: List<CustomEntry>): String {
        return list.joinToString(";") { "${it.id}|${it.name}|${it.className}" }
    }

    /** 读取当前启用顺序的条目（预设 + 自定义） */
    fun loadEntries(): List<Entry> {
        return runCatching {
            val saved = PublicConfigStore.getString(KEY_ITEMS, "")
            var ids: List<String>
            if (saved.isBlank()) {
                ids = defaultItemIds
            } else {
                ids = saved.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
            val custom = loadCustomEntries()
            val customById = custom.associateBy { it.id }
            val presetById = allPresets.associateBy { it.id }
            val list = ids.mapNotNull { id ->
                customById[id]?.let { Entry(it.id, it.name, "custom", it.className) }
                    ?: presetById[id]?.let { Entry(it.id, it.name, it.launcher, null) }
            }
            if (list.isEmpty()) {
                defaultItemIds.mapNotNull { id ->
                    presetById[id]?.let { Entry(it.id, it.name, it.launcher, null) }
                }
            } else list
        }.getOrElse {
            allPresets.take(8).map { Entry(it.id, it.name, it.launcher, null) }
        }
    }

    private fun findOverlayHost(decor: ViewGroup): ViewGroup {
        if (decor is FrameLayout) return decor
        val content = decor.findViewById(android.R.id.content) as? ViewGroup
        return content ?: decor
    }

    private fun dp(ctx: Context, value: Int): Int {
        return (value * ctx.resources.displayMetrics.density).toInt()
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}

/** 菜单项左侧高质感图标徽章组件（自然融入微信调色，拒绝刺眼突兀） */
class MenuEntryBadgeView(context: Context, val entryId: String) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        if (r <= 0f) return

        val density = resources.displayMetrics.density

        val isSetting = entryId == "settings"
        val themeColor = if (isSetting) Color.parseColor("#576B95") else Color.parseColor("#07C160")
        val bgColor = if (isSetting) Color.parseColor("#12576B95") else Color.parseColor("#1407C160")

        // 1. 柔和圆角背景 (6dp)
        bgPaint.color = bgColor
        val corner = 6f * density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), corner, corner, bgPaint)

        // 2. 细微描边
        borderPaint.color = Color.parseColor("#1F07C160")
        borderPaint.strokeWidth = 0.8f * density
        canvas.drawRoundRect(0.4f * density, 0.4f * density, width - 0.4f * density, height - 0.4f * density, corner, corner, borderPaint)

        // 3. 图标线框设置
        iconPaint.color = themeColor
        fillPaint.color = themeColor
        iconPaint.strokeWidth = 1.3f * density

        val size = 5.5f * density

        when (entryId) {
            "qrcode" -> { // 扫一扫：4 角取景框
                val path = Path().apply {
                    val d = 2f * density
                    // 左上
                    moveTo(cx - size, cy - size + d)
                    lineTo(cx - size, cy - size)
                    lineTo(cx - size + d, cy - size)
                    // 右上
                    moveTo(cx + size - d, cy - size)
                    lineTo(cx + size, cy - size)
                    lineTo(cx + size, cy - size + d)
                    // 右下
                    moveTo(cx + size, cy + size - d)
                    lineTo(cx + size, cy + size)
                    lineTo(cx + size - d, cy + size)
                    // 左下
                    moveTo(cx - size + d, cy + size)
                    lineTo(cx - size, cy + size)
                    lineTo(cx - size, cy + size - d)
                }
                canvas.drawPath(path, iconPaint)
                canvas.drawCircle(cx, cy, 1f * density, fillPaint)
            }
            "pay", "balance" -> { // 付款卡片
                val w = 5.5f * density
                val h = 4.2f * density
                canvas.drawRoundRect(cx - w, cy - h, cx + w, cy + h, 1.2f * density, 1.2f * density, iconPaint)
                canvas.drawLine(cx - w, cy - 1.2f * density, cx + w, cy - 1.2f * density, iconPaint)
            }
            "service" -> { // 4 格功能块
                val s = 2.2f * density
                val g = 0.8f * density
                canvas.drawRoundRect(cx - g - s, cy - g - s, cx - g, cy - g, 0.6f * density, 0.6f * density, iconPaint)
                canvas.drawRoundRect(cx + g, cy - g - s, cx + g + s, cy - g, 0.6f * density, 0.6f * density, iconPaint)
                canvas.drawRoundRect(cx - g - s, cy + g, cx - g, cy + g + s, 0.6f * density, 0.6f * density, iconPaint)
                canvas.drawRoundRect(cx + g, cy + g, cx + g + s, cy + g + s, 0.6f * density, 0.6f * density, iconPaint)
            }
            "favorite" -> { // 五角星
                val path = Path().apply {
                    val outer = 5.5f * density
                    val inner = 2.4f * density
                    for (i in 0 until 10) {
                        val radius = if (i % 2 == 0) outer else inner
                        val angle = Math.toRadians((i * 36 - 90).toDouble())
                        val x = (cx + radius * Math.cos(angle)).toFloat()
                        val y = (cy + radius * Math.sin(angle)).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                canvas.drawPath(path, iconPaint)
            }
            "moments", "snspost", "snsmsg" -> { // 朋友圈圆环
                canvas.drawCircle(cx, cy, 4.8f * density, iconPaint)
                canvas.drawCircle(cx, cy, 1.8f * density, fillPaint)
            }
            "channel" -> { // 视频号播放标
                val path = Path().apply {
                    moveTo(cx - 2.8f * density, cy - 3.8f * density)
                    lineTo(cx + 3.8f * density, cy)
                    lineTo(cx - 2.8f * density, cy + 3.8f * density)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }
            "settings" -> { // 微信设置 齿轮
                canvas.drawCircle(cx, cy, 3.2f * density, iconPaint)
                val len = 5.2f * density
                canvas.drawLine(cx, cy - len, cx, cy + len, iconPaint)
                canvas.drawLine(cx - len, cy, cx + len, cy, iconPaint)
            }
            "module" -> { // OKK 极速闪电 + 品牌科技光圈
                val path = Path().apply {
                    moveTo(cx + 0.6f * density, cy - 4.5f * density)
                    lineTo(cx - 3.5f * density, cy + 0.5f * density)
                    lineTo(cx - 0.3f * density, cy + 0.5f * density)
                    lineTo(cx - 1.5f * density, cy + 4.5f * density)
                    lineTo(cx + 3.2f * density, cy - 0.5f * density)
                    lineTo(cx + 0f * density, cy - 0.5f * density)
                    close()
                }
                canvas.drawPath(path, fillPaint)
                canvas.drawCircle(cx, cy, 6.8f * density, iconPaint)
            }
            else -> { // 极简四角微星 (默认不突兀)
                val r1 = 5.2f * density
                val path = Path().apply {
                    moveTo(cx, cy - r1)
                    quadTo(cx, cy, cx + r1, cy)
                    quadTo(cx, cy, cx, cy + r1)
                    quadTo(cx, cy, cx - r1, cy)
                    quadTo(cx, cy, cx, cy - r1)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }
        }
    }
}

/** 悬浮按钮图标 Canvas 自绘组件（微信经典高质感绿，自然优雅不突兀） */
class FabIconView(context: Context) : View(context) {
    var iconStyle: String = "grid"
        set(value) { field = value; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy)
        if (radius <= 0f) return

        val density = resources.displayMetrics.density

        // 1. 微信原生高质感翡翠绿渐变（经典自然融入微信 UI，绝不突兀）
        bgPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            Color.parseColor("#07C160"), Color.parseColor("#059669"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 2. 绘制微光半透明内边框（提升视网膜立体感）
        borderPaint.strokeWidth = 1.2f * density
        borderPaint.color = Color.parseColor("#2DFFFFFF")
        canvas.drawCircle(cx, cy, radius - 0.6f * density, borderPaint)

        // 3. 图标绘制
        when (iconStyle) {
            "grid" -> { // 九宫格 / 4 块圆角方格矩阵（现代 App 启动器风格）
                val tileSize = 6f * density
                val corner = 2.2f * density
                val gap = 1.8f * density
                // 左上
                canvas.drawRoundRect(cx - gap - tileSize, cy - gap - tileSize, cx - gap, cy - gap, corner, corner, fillPaint)
                // 右上
                canvas.drawRoundRect(cx + gap, cy - gap - tileSize, cx + gap + tileSize, cy - gap, corner, corner, fillPaint)
                // 左下
                canvas.drawRoundRect(cx - gap - tileSize, cy + gap, cx - gap, cy + gap + tileSize, corner, corner, fillPaint)
                // 右下
                canvas.drawRoundRect(cx + gap, cy + gap, cx + gap + tileSize, cy + gap + tileSize, corner, corner, fillPaint)
            }
            "menu" -> { // 3 条平滑圆角横杠
                iconPaint.strokeWidth = 2.8f * density
                val len = 8.5f * density
                val off = 5.2f * density
                canvas.drawLine(cx - len, cy - off, cx + len, cy - off, iconPaint)
                canvas.drawLine(cx - len + 2f * density, cy, cx + len, cy, iconPaint)
                canvas.drawLine(cx - len, cy + off, cx + len - 2f * density, cy + off, iconPaint)
            }
            "sparkle" -> { // 璀璨 4 角星芒
                val r1 = 9.5f * density
                val path = Path().apply {
                    moveTo(cx, cy - r1)
                    quadTo(cx, cy, cx + r1, cy)
                    quadTo(cx, cy, cx, cy + r1)
                    quadTo(cx, cy, cx - r1, cy)
                    quadTo(cx, cy, cx, cy - r1)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }
            "lightning" -> { // 极速闪电
                val path = Path().apply {
                    moveTo(cx + 1f * density, cy - 9.5f * density)
                    lineTo(cx - 6.5f * density, cy + 1f * density)
                    lineTo(cx - 0.8f * density, cy + 1f * density)
                    lineTo(cx - 2.8f * density, cy + 9.5f * density)
                    lineTo(cx + 5.5f * density, cy - 1f * density)
                    lineTo(cx + 0f * density, cy - 1f * density)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }
            "logo" -> { // OKK 品牌标识
                textPaint.textSize = 12.5f * density
                val fontMetrics = textPaint.fontMetrics
                val baseLineY = cy - (fontMetrics.ascent + fontMetrics.descent) / 2f
                canvas.drawText("OKK", cx, baseLineY, textPaint)
            }
            else -> { // "plus" 几何加号
                iconPaint.strokeWidth = 3.2f * density
                val len = 8.5f * density
                canvas.drawLine(cx - len, cy, cx + len, cy, iconPaint)
                canvas.drawLine(cx, cy - len, cx, cy + len, iconPaint)
            }
        }
    }
}