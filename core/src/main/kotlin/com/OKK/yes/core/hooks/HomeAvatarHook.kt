package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.OKK.yes.core.compat.DexKitSupport
import com.OKK.yes.core.compat.WeChatSelfUser
import com.OKK.yes.core.hooks.ui.HomeSideDrawer
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 会话首页左上角头像 + 侧边栏。
 *
 * 优化要点：
 * 1. 缓存/用户名缓存，避免每次 resume 强刷。
 * 2. 先 [j1.loadBitmap] 同步取图，再走 AvatarDrawable 异步补全。
 * 3. 已绑定成功后 resume 只恢复可见性，不重复 bind，避免闪空白。
 * 4. 缓存一帧 Bitmap，重建 View 时秒贴。
 * 5. 自己用户名走 [WeChatSelfUser]，兼容 69–76 版本。
 */
object HomeAvatarHook {
    private const val TAG = "OKK-HomeAvatar"
    const val KEY = "home_avatar_entry"
    private const val VIEW_TAG = "achat_home_avatar_v3"
    private const val MAIN_TAB_UI = "com.tencent.mm.ui.MainTabUI"
    private const val LAUNCHER = "com.tencent.mm.ui.LauncherUI"
    private val AVATAR_FACTORY: String get() = com.OKK.yes.core.common.SecureStrings.d("xnRFxnRH1ylC2j1Z3yoC3jcCxzRJ0DRJx3RB3Dk=")
    private const val ENABLE_TTL_MS = 4_000L

    private val installed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var modulePath: String? = null
    @Volatile private var hostRef: WeakReference<Activity>? = null
    @Volatile private var avatarView: ImageView? = null
    @Volatile private var headerContainer: View? = null
    /** 头像可见性：false 时不显示左上角头像，但侧边栏（右滑手势）仍保留 */
    @Volatile private var avatarVisible: Boolean = false
    @Volatile private var statusView: TextView? = null
    @Volatile private var nicknameView: TextView? = null
    @Volatile private var cachedNickname: String = ""
    @Volatile private var cachedStatusText: String = ""
    @Volatile private var capturedStatusText: String = ""
    @Volatile private var currentTab: Int = 0
    @Volatile private var lastUsername: String = ""
    @Volatile private var boundUsername: String = ""
    @Volatile private var enabledCached: Boolean = false
    @Volatile private var enabledAt: Long = 0L
    // 缓存位图是否“可信真头像”：仅 loadBitmapSync 成功或 captureFromView 取到真实 Bitmap 时置 true
    // 防止 captureFromView 兜底把未加载完成的空白/灰图缓存后，侧边栏贴灰图导致“头像空白”。
    @Volatile private var cachedBmpTrusted: Boolean = false

    /** 运行时健康上报去重：只在状态变化时上报，避免 applyAvatar 高频调用刷屏 */
    @Volatile private var lastAvatarHealthOk: Boolean? = null
    @Volatile private var actionbarMissCount = 0
    private fun reportAvatarHealth(ok: Boolean, detail: String) {
        if (lastAvatarHealthOk == ok) return
        lastAvatarHealthOk = ok
        runCatching {
            com.OKK.yes.core.startup.FeatureHookRegistry.reportRuntime("首页头像/侧边栏入口", ok, detail)
        }
    }
    @Volatile private var factoryA: Method? = null
    @Volatile private var factoryC: Method? = null
    @Volatile private var loadBitmapM: Method? = null
    @Volatile private var applyToken: Int = 0
    @Volatile private var captureToken: Int = 0
    @Volatile private var lastApplyOkTime: Long = 0L
    @Volatile private var fullscreenOverlayOpen: Boolean = false
    @Volatile private var chattingProbeToken: Int = 0
    @Volatile private var dialogProbeToken: Int = 0
    @Volatile private var isChattingOpen: Boolean = false
    @Volatile private var lastExitChatTime: Long = 0L
    @Volatile private var vpHooked: Boolean = false
    @Volatile private var currentResumedName: String = ""

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        this.classLoader = classLoader
        this.appContext = context.applicationContext ?: context
        this.modulePath = modulePath
        warmReflect(classLoader)
        enabledCached = isEnabled(force = true)
        xlog("install enabled=$enabledCached")
        HomeSideDrawer.configure(
            bindAvatar = { iv ->
                val cl = classLoader ?: iv.context.classLoader
                val username = resolveSelfUsername(cl).ifBlank { lastUsername }
                if (username.isNotBlank()) {
                    runCatching {
                        val method = factoryA ?: resolveFactoryA(cl)
                        method?.invoke(null, iv, username)
                    }
                }
            },
            title = {
                cachedNickname.takeIf { it.isNotBlank() && it != "我" }
                    ?: runCatching { WeChatSelfUser.resolveNickname(classLoader, appContext, modulePath) }
                        .getOrNull()?.takeIf { it.isNotBlank() }
                    ?: "我"
            },
            status = { "在线" },
            onStatusClick = {
                hostRef?.get()?.let { act -> openStatusPage(act) }
            }
        )
        hookLauncherUi(classLoader)
        hookMainTabPage(classLoader)
        hookChattingState(classLoader)
        // 已按用户要求停用状态获取，状态行固定显示“在线”
        hookStatusGetter(classLoader)
        registerLifecycleGuard(context)
        hookDialogs(classLoader)
        // 启动延迟预取自己用户名 + bitmap
        mainHandler.postDelayed({ prefetchSelfAvatar(classLoader) }, 400)
        // 监听头像开关变化（保存即生效）：开->关 隐藏，关->开 重新应用
        PublicConfigStore.addListener(KEY) {
            enabledCached = isEnabled(force = true)
            if (!enabledCached) {
                setVisible(false, animated = false)
                HomeSideDrawer.dismiss(animated = false)
            } else {
                hostRef?.get()?.let { act -> onLauncherTick(act, soft = false) }
            }
        }
    }

    fun isEnabled(force: Boolean = false): Boolean {
        val now = SystemClock.uptimeMillis()
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

    /** 首页 tab0 且未进聊天时开启左缘手势排除，避免系统返回手势抢占右滑开抽屉 */
    private fun updateDrawerGestureExclusion(activity: Activity?) {
        val act = activity ?: hostRef?.get() ?: return
        val enabled = isEnabled(false) && currentTab == 0 && !isChattingOpen
        HomeSideDrawer.updateGestureExclusion(act, enabled)
    }

    private fun warmReflect(cl: ClassLoader) {
        runCatching {
            val factory = Class.forName(AVATAR_FACTORY, false, cl)
            factoryA = factory.getDeclaredMethod("a", ImageView::class.java, String::class.java)
                .also { it.isAccessible = true }
            factoryC = factory.methods.firstOrNull {
                it.name == "c" && it.parameterTypes.isEmpty() &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers)
            }?.also { it.isAccessible = true }
            xlog("warmReflect ok factoryA=${factoryA != null} factoryC=${factoryC != null}")
        }.onFailure { xlog("warmReflect fail: ${it.message}") }
    }

    private fun hookLauncherUi(cl: ClassLoader) {
        val launcher = runCatching { XposedHelpers.findClass(LAUNCHER, cl) }.getOrNull()
        if (launcher == null) {
            xlog("LauncherUI miss")
            return
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                launcher, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        hostRef?.get()?.let { onLauncherTick(it, soft = true) }
                    }
                }
            )
            xlog("hooked LauncherUI.onResume")
        }.onFailure { xlog("LauncherUI.onResume: ${it.message}") }

        runCatching {
            XposedHelpers.findAndHookMethod(
                launcher, "onWindowFocusChanged", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val hasFocus = (param.args.getOrNull(0) as? Boolean) ?: return
                        val act = param.thisObject as? Activity ?: return
                        if (hasFocus) {
                            xlog("launcher focus gained -> tick")
                            onLauncherTick(act, soft = true)
                        } else {
                            // 用户要求：首页右上角菜单弹出时（菜单 Dialog 抢焦点）头像保持显示，
                            // 因此 focus lost 不隐藏头像
                            xlog("launcher focus lost (keep avatar)")
                        }
                    }
                }
            )
            xlog("hooked LauncherUI.onWindowFocusChanged")
        }.onFailure { xlog("LauncherUI.onWindowFocusChanged: ${it.message}") }

        runCatching {
            XposedHelpers.findAndHookMethod(
                launcher, "onBackPressed",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        isChattingOpen = false
                        lastExitChatTime = SystemClock.uptimeMillis()
                        scheduleApply(act, soft = true)
                    }
                }
            )
            xlog("hooked LauncherUI.onBackPressed")
        }.onFailure { xlog("LauncherUI.onBackPressed: ${it.message}") }
    }

    private fun registerLifecycleGuard(context: Context) {
        val app = (context as? android.app.Application)
            ?: (context.applicationContext as? android.app.Application)
            ?: return
        runCatching {
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val prevName = currentResumedName
                    currentResumedName = activity.javaClass.name
                    val isHome = currentResumedName.contains("LauncherUI") ||
                        currentResumedName.contains("MainTabUI")
                    if (!isHome) {
                        if (currentResumedName.contains("chatting", ignoreCase = true) ||
                            currentResumedName.contains("ChattingUI")
                        ) {
                            isChattingOpen = true
                        }
                        setVisible(false)
                        HomeSideDrawer.dismiss(animated = false)
                    } else {
                        // 从非首页 Activity（如右上角菜单/查看图片等）回到首页时，
                        // 重置聊天状态，确保头像恢复显示，避免 isChattingOpen 卡 true 导致头像永久消失。
                        if (prevName.isNotEmpty() &&
                            !prevName.contains("LauncherUI") && !prevName.contains("MainTabUI")
                        ) {
                            xlog("home resume from $prevName -> reset chattingOpen")
                            isChattingOpen = false
                            ConversationGroupingHook.onChatExit()
                            applyAvatar(activity, soft = true)
                            setVisible(true, animated = false)
                        } else {
                            onLauncherTick(activity, soft = true)
                        }
                    }
                }
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
        }.onFailure { xlog("lifecycle guard fail: ${it.message}") }
    }

    private fun hookDialogs(classLoader: ClassLoader) {
        // 头像已改为 Toolbar 子视图，随首页自然显隐，无需 hook Dialog 控制显隐。
        // 保留空实现以兼容 install 调用。
    }

    private fun hookMainTabPage(cl: ClassLoader) {
        val mainTabUi = runCatching { XposedHelpers.findClass(MAIN_TAB_UI, cl) }.getOrNull() ?: return
        var tabsAdapter: Class<*>? = runCatching {
            XposedHelpers.findClass("$MAIN_TAB_UI\$TabsAdapter", cl)
        }.getOrNull()
        if (tabsAdapter == null) {
            tabsAdapter = mainTabUi.declaredClasses.firstOrNull { c ->
                c.simpleName.contains("TabsAdapter") ||
                    c.interfaces.any { it.name.contains("OnPageChangeListener") }
            }
        }
        if (tabsAdapter != null) {
            runCatching {
                for (m in tabsAdapter.declaredMethods) {
                    if (m.name == "onPageSelected") {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                currentTab = (param.args.getOrNull(0) as? Int) ?: 0
                                hostRef?.get()?.let { act ->
                                    updateDrawerGestureExclusion(act)
                                    onLauncherTick(act, soft = true)
                                }
                            }
                        })
                    }
                }
                xlog("hooked TabsAdapter.onPageSelected")
            }.onFailure { xlog("TabsAdapter.onPageSelected: ${it.message}") }
        }
        // MainTabUI.d 兜底（部分版本 tab 切换走 d）
        // MainTabUI.d: tab 切换时更新 currentTab，并借此拿到 MainTabUI 实例
        // 再 hook 其 ViewPager 边缘滑动（左缘右滑打开侧边栏）
        runCatching {
            XposedBridge.hookAllMethods(
                mainTabUi, "d",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        hookTabViewPagerSwipe(param.thisObject)
                        currentTab = (param.args.firstOrNull() as? Int) ?: currentTab
                        hostRef?.get()?.let { act ->
                            updateDrawerGestureExclusion(act)
                            onLauncherTick(act, soft = true)
                        }
                    }
                }
            )
            xlog("hooked MainTabUI.d -> edge swipe")
        }.onFailure { xlog("MainTabUI.d hook fail: ${it.message}") }
    }

    fun hookTabViewPagerSwipe(tabUi: Any?) {
        if (tabUi == null || vpHooked) return
        val vp = runCatching { findViewPagerField(tabUi) }.getOrNull() ?: return
        val vpClazz = vp.javaClass
        runCatching {
            XposedBridge.hookAllMethods(
                vpClazz, "onInterceptTouchEvent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        val vp2 = param.thisObject as? View ?: return
                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        val act = findActivityFrom(vp2.context) ?: return
                        val name = act.javaClass.name
                        if (!name.contains("LauncherUI") && !name.contains("MainTabUI")) return
                        if (HomeSideDrawer.onEdgeIntercept(act, ev)) {
                            param.result = true
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                vpClazz, "onTouchEvent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        val vp2 = param.thisObject as? View ?: return
                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        val act = findActivityFrom(vp2.context) ?: return
                        val name = act.javaClass.name
                        if (!name.contains("LauncherUI") && !name.contains("MainTabUI")) return
                        if (HomeSideDrawer.onEdgeTouch(act, ev)) {
                            param.result = true
                        }
                    }
                }
            )
            vpHooked = true
            xlog("hooked tab ViewPager $vpClazz edge intercept+touch")
        }.onFailure { xlog("hookTabViewPagerSwipe fail: ${it.message}") }
    }

    private fun findViewPagerField(tabUi: Any): View? {
        var cls: Class<*>? = tabUi.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (!View::class.java.isAssignableFrom(f.type)) continue
                val name = f.name
                if (!name.contains("ViewPager", ignoreCase = true) &&
                    !f.type.name.contains("ViewPager", ignoreCase = true)
                ) continue
                f.isAccessible = true
                val v = runCatching { f.get(tabUi) as? View }.getOrNull()
                if (v != null) return v
            }
            cls = cls.superclass
        }
        return null
    }

    private fun hookChattingState(classLoader: ClassLoader) {
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
                        updateDrawerGestureExclusion(hostRef?.get())
                        setVisible(false)
                        HomeSideDrawer.dismiss(animated = false)
                    }
                }
            )
            xlog("hooked LauncherUI.startChatting hide avatar")
        }.onFailure { xlog("LauncherUI.startChatting hide: ${it.message}") }

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
                        updateDrawerGestureExclusion(hostRef?.get())
                        setVisible(false)
                        HomeSideDrawer.dismiss(animated = false)
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
                        lastExitChatTime = SystemClock.uptimeMillis()
                        ThemeWallpaperController.clearChattingProbeCache()
                        ConversationGroupingHook.onChatExit()
                        val act = activityFromChattingTab(param.thisObject) ?: hostRef?.get() ?: return
                        updateDrawerGestureExclusion(act)
                        scheduleApply(act, soft = true)
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
                        lastExitChatTime = SystemClock.uptimeMillis()
                        ThemeWallpaperController.clearChattingProbeCache()
                        ConversationGroupingHook.onChatExit()
                        val act = activityFromChattingTab(param.thisObject) ?: hostRef?.get() ?: return
                        updateDrawerGestureExclusion(act)
                        scheduleApply(act, soft = true)
                    }
                }
            )
            xlog("hooked NewChattingTabUI avatar visibility")
        }.onFailure { xlog("NewChattingTabUI avatar visibility: ${it.message}") }
    }

    private fun activityFromChattingTab(tab: Any?): Activity? {
        if (tab == null) return null
        return runCatching {
            val f = tab.javaClass.getDeclaredField("f190364a")
            f.isAccessible = true
            f.get(tab) as? Activity
        }.getOrNull()
    }

    fun findActivityFrom(target: Any?): Activity? {
        if (target == null) return null
        if (target is Activity) return target
        var cls: Class<*>? = target.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (!Activity::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                val v = runCatching { f.get(target) as? Activity }.getOrNull()
                if (v != null) return v
            }
            cls = cls.superclass
        }
        return hostRef?.get()
    }

    fun onLauncherTick(activity: Activity, soft: Boolean) {
        if (activity.isFinishing) return
        hostRef = WeakReference(activity)
        updateDrawerGestureExclusion(activity)
        scheduleApply(activity, soft)
        // 350ms 后探测是否处于聊天前台（8.0.76 聊天为 LauncherUI 内 fragment，guard 不触发）
        val probeToken = ++chattingProbeToken
        mainHandler.postDelayed({
            if (probeToken != chattingProbeToken || activity.isFinishing()) return@postDelayed
            val chatting = ThemeWallpaperController.isChattingForeground(activity)
            if (chatting) {
                if (!isChattingOpen) {
                    xlog("chat probe -> open, hide")
                    isChattingOpen = true
                    updateDrawerGestureExclusion(activity)
                    setVisible(false)
                    HomeSideDrawer.dismiss(animated = false)
                }
            } else if (isChattingOpen) {
                xlog("chat probe -> exit, restore")
                isChattingOpen = false
                lastExitChatTime = SystemClock.uptimeMillis()
                updateDrawerGestureExclusion(activity)
                scheduleApply(activity, soft = true)
            }
        }, 350L)
    }

    private fun scheduleApply(activity: Activity, soft: Boolean) {
        val token = ++applyToken
        mainHandler.post {
            if (token != applyToken) return@post
            applyAvatar(activity, soft)
        }
        if (!soft) {
            mainHandler.postDelayed({
                if (token != applyToken || activity.isFinishing()) return@postDelayed
                applyAvatar(activity, soft = true)
            }, 200L)
        }
    }

    private fun applyAvatar(activity: Activity, soft: Boolean) {
        updateDrawerGestureExclusion(activity)
        if (!isEnabled(false) || currentTab != 0) {
            setVisible(false)
            return
        }

        val chatting = runCatching {
            ThemeWallpaperController.isChattingForegroundFresh(activity)
        }.getOrDefault(false)
        if (chatting) {
            isChattingOpen = true
            setVisible(false)
            return
        } else {
            isChattingOpen = false
        }

        runCatching {
            val root = ensureView(activity)
            val av = avatarView
            val nowMs = SystemClock.uptimeMillis()

            val cached = com.OKK.yes.core.utils.MemoryCacheManager.get("home_avatar")
            if (cached != null && !cached.isRecycled && cachedBmpTrusted && isValidAvatarBitmap(cached)) {
                if (av?.drawable == null || isPlaceholderOnly(av)) {
                    av?.setImageBitmap(cached)
                }
            } else if (av != null && (av.drawable == null || isPlaceholderOnly(av))) {
                bindAvatar(av, force = false)
            }

            val shouldBind = if (soft) {
                boundUsername.isEmpty() || av == null || av?.drawable == null || isPlaceholderOnly(av)
            } else true

            if (shouldBind && av != null) {
                bindAvatar(av, force = !soft)
            }
            av?.postInvalidate()
            setVisible(true)
            startChatWatchdog(activity)
            refreshHeaderTexts(activity)
            lastApplyOkTime = nowMs
            xlog("apply ok tab=$currentTab vis=${root.visibility}")
        }.onFailure { xlog("apply fail: ${it.message}") }
    }

    // 头像可见时定期探测聊天前台（8.0.76 聊天是 LauncherUI 内 fragment，无 onActivityResumed），
    // 发现聊天就隐藏头像，返回就恢复。复用 FloatingQuickEntry 的 watchdog 思路。
    private val chatWatchdog = object : Runnable {
        override fun run() {
            try {
                val act = hostRef?.get() ?: return
                if (!isEnabled(false)) return
                val chatting = runCatching { ThemeWallpaperController.isChattingForegroundFresh(act) }.getOrDefault(false)
                if (chatting) {
                    if (!isChattingOpen) {
                        xlog("chat watchdog -> hide")
                        isChattingOpen = true
                        setVisible(false)
                        HomeSideDrawer.dismiss(animated = false)
                    }
                } else {
                    if (isChattingOpen || (currentTab == 0 && headerContainer?.visibility != View.VISIBLE)) {
                        xlog("chat watchdog -> restore")
                        isChattingOpen = false
                        lastExitChatTime = SystemClock.uptimeMillis()
                        ConversationGroupingHook.onChatExit()
                        scheduleApply(act, soft = true)
                    }
                }
            } finally {
                mainHandler.postDelayed(this, 700L)
            }
        }
    }
    private var watchdogRunning = false

    private fun startChatWatchdog(activity: Activity) {
        if (watchdogRunning) return
        watchdogRunning = true
        mainHandler.removeCallbacks(chatWatchdog)
        mainHandler.postDelayed(chatWatchdog, 600L)
    }


    private fun isPlaceholderOnly(iv: ImageView): Boolean {
        val d = iv.drawable ?: return true
        if (d is GradientDrawable) return true
        if (d is BitmapDrawable) {
            val b = d.bitmap
            return b == null || b.isRecycled
        }
        return false
    }

    fun setVisible(show: Boolean, animated: Boolean = true) {
        val v = headerContainer ?: avatarView
        if (!avatarVisible) {
            v?.visibility = View.GONE
            return
        }
        if (v == null) return

        val target = if (show) View.VISIBLE else View.GONE
        val targetAlpha = if (show) 1f else 0f
        // 状态未变（可见性与透明度均一致）直接早退，避免重复动画/无效重绘
        if (v.visibility == target && v.alpha == targetAlpha) return
        val apply = {
            v.animate().cancel()
            if (!animated) {
                v.alpha = targetAlpha
                v.visibility = target
            } else if (show) {
                v.visibility = View.VISIBLE
                if (v.alpha != 1f) {
                    v.animate().alpha(1f).setDuration(160L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction { v.visibility = View.VISIBLE }
                        .start()
                }
            } else {
                v.animate().alpha(0f).setDuration(140L)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { v.visibility = View.GONE }
                    .start()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            mainHandler.post { apply() }
        }
    }

    private fun ensureView(activity: Activity): View {
        val decor = activity.window?.decorView as? ViewGroup
            ?: activity.findViewById(android.R.id.content) as? ViewGroup

        val toolbarContent = if (decor != null) findActionBarContent(decor) else null
        if (decor != null && toolbarContent == null) {
            xlog("actionbar not found yet, retry")
            actionbarMissCount++
            if (actionbarMissCount >= 8) reportAvatarHealth(false, "首页顶部栏定位失败，头像无法注入")
            mainHandler.postDelayed({ onLauncherTick(activity, soft = true) }, 300L)
            return headerContainer ?: View(activity)
        }

        // 容器已附着且仍在当前 toolbarContent 内：直接复用
        val old = headerContainer
        if (old != null && old.isAttachedToWindow && toolbarContent != null) {
            if (old.parent === toolbarContent) {
                return old
            }
            // 说明 toolbarContent 重新构建了，从旧 parent 移除
            runCatching { (old.parent as? ViewGroup)?.removeView(old) }
        } else if (old != null && !old.isAttachedToWindow) {
            runCatching { (old.parent as? ViewGroup)?.removeView(old) }
            headerContainer = null
            avatarView = null
            boundUsername = ""
        }
        if (toolbarContent == null) return old ?: View(activity)
        actionbarMissCount = 0
        val size = dp(activity, 38)

        val container = LinearLayout(activity).apply {
            tag = VIEW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
        }

        val avatar = ImageView(activity).apply {
            tag = "achat_home_avatar_v3_avatar"
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "ͷ��"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D0D0D0"))
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            setOnClickListener { HomeSideDrawer.toggle(activity) }
        }
        container.addView(avatar, LinearLayout.LayoutParams(size, size))
        avatarView = avatar
        paintCached(avatar)

        val nameCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { openStatusPage(activity) }
        }
        container.addView(nameCol, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 10) })

        val nick = TextView(activity).apply {
            tag = "achat_home_avatar_v3_nick"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(nicknameColor(activity))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(activity, 130)
            includeFontPadding = false
        }
        nameCol.addView(nick, LinearLayout.LayoutParams(-2, -2))
        nicknameView = nick

        // 状态行（绿点 + 状态文字），水平排列，点击进状态页
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { openStatusPage(activity) }
        }
        nameCol.addView(statusRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(activity, 2) })

        val dot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#34C759"))
            }
        }
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(activity, 7), dp(activity, 7)).apply {
            marginEnd = dp(activity, 4)
            gravity = Gravity.CENTER_VERTICAL
        })

        val st = TextView(activity).apply {
            tag = "achat_home_avatar_v3_status"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(statusColor(activity))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(activity, 130)
            includeFontPadding = false
        }
        statusRow.addView(st, LinearLayout.LayoutParams(-2, -2))
        statusView = st

        refreshHeaderTexts(activity)
        // 加到 Toolbar 的 RelativeLayout（id=ih），放在左侧（actionbar_up_indicator 区域），垂直居中
        val lp = RelativeLayout.LayoutParams(-2, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.CENTER_VERTICAL)
            marginStart = dp(activity, 14)
            leftMargin = marginStart
        }
        toolbarContent.addView(container, lp)
        headerContainer = container
        if (!avatarVisible) {
            container.visibility = View.GONE
        }
        xlog("avatar container created in actionbar")
        reportAvatarHealth(true, "头像已注入首页顶部栏")
        return container
    }

    /**
     * 找微信首页 ActionBar 的内容容器（Toolbar 内部的 RelativeLayout，id=ih）。
     * 优先按资源 id 名查找（8.0.x 相对稳定），fallback 递归找 androidx Toolbar 的内部容器。
     */
    private fun findActionBarContent(decor: ViewGroup): ViewGroup? {
        val pkg = decor.context.packageName
        fun resId(name: String): Int =
            decor.context.resources.getIdentifier(name, "id", pkg)
        // 1) 资源 id：Toolbar 内部 RelativeLayout id=ih
        resId("ih").takeIf { it != 0 }?.let { id ->
            decor.findViewById<ViewGroup>(id)?.let { return it }
        }
        // 2) Toolbar（id=ez）的第一个 ViewGroup 子（通常是 RelativeLayout）
        resId("ez").takeIf { it != 0 }?.let { id ->
            val tb = decor.findViewById<ViewGroup>(id)
            if (tb != null) {
                for (i in 0 until tb.childCount) {
                    val c = tb.getChildAt(i)
                    if (c is ViewGroup) return c
                }
                return tb
            }
        }
        // 3) 递归找 androidx.appcompat.widget.Toolbar 或其子类的内部容器
        var found: ViewGroup? = null
        fun walk(v: View, depth: Int) {
            if (found != null || depth > 10) return
            val cls = v.javaClass.name
            if (cls.contains("Toolbar") && v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    if (c is ViewGroup) { found = c; return }
                }
                found = v
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(decor, 0)
        return found
    }

    private fun collectHeaderViews(container: View) {
        avatarView = null
        nicknameView = null
        statusView = null
        fun walk(v: View) {
            when (v.tag) {
                "achat_home_avatar_v3_avatar" -> avatarView = v as? ImageView
                "achat_home_avatar_v3_nick" -> nicknameView = v as? TextView
                "achat_home_avatar_v3_status" -> statusView = v as? TextView
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(container)
    }

    private fun paintCached(iv: ImageView) {
        val bmp = com.OKK.yes.core.utils.MemoryCacheManager.get("home_avatar")
        if (bmp == null || bmp.isRecycled || !cachedBmpTrusted) return
        if (iv.drawable == null || isPlaceholderOnly(iv)) {
            iv.setImageBitmap(bmp)
        }
    }

    private fun findOverlayHost(decor: ViewGroup): ViewGroup {
        if (decor is FrameLayout) return decor
        val content = decor.findViewById(android.R.id.content) as? ViewGroup
        return content ?: decor
    }

    private fun findTagged(root: ViewGroup): View? {
        if (root.tag == VIEW_TAG) return root
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c.tag == VIEW_TAG) return c
            if (c is ViewGroup) {
                findTagged(c)?.let { return it }
            }
        }
        return null
    }

    private fun prefetchSelfAvatar(cl: ClassLoader) {
        val user = resolveSelfUsername(cl)
        if (user.isBlank()) {
            xlog("prefetch: username empty")
            return
        }
        lastUsername = user
        val bmp = loadBitmapSync(cl, user)
        if (bmp != null) rememberBitmap(bmp)
        xlog("prefetch: user=$user bmp=${bmp != null}")
        avatarView?.let { iv ->
            mainHandler.post {
                paintCached(iv)
                if (isPlaceholderOnly(iv)) bindAvatar(iv, force = true)
            }
        }
    }

    private fun bindAvatar(iv: ImageView, force: Boolean): Boolean {
        val cl = classLoader ?: iv.context.classLoader
        val username = resolveSelfUsername(cl).ifBlank { lastUsername }
        if (username.isBlank()) {
            xlog("username empty")
            return false
        }
        lastUsername = username

        // fast path: 仅当缓存是“可信真头像”时直接复用，避免贴污染灰图
        val cached = com.OKK.yes.core.utils.MemoryCacheManager.get("home_avatar")
        if (cachedBmpTrusted && boundUsername == username && cached != null && !cached.isRecycled && !isPlaceholderOnly(iv)) {
            iv.setImageBitmap(cached)
            iv.setOnClickListener {
                val act = hostRef?.get() ?: (iv.context as? Activity)
                if (act != null) HomeSideDrawer.toggle(act)
            }
            return true
        }

        // 1) 同步位图：立刻显示
        val sync = loadBitmapSync(cl, username)
        if (sync != null && !sync.isRecycled) {
            rememberBitmap(sync)
            if (force || isPlaceholderOnly(iv) || boundUsername != username) {
                iv.setImageBitmap(sync)
            }
        } else {
            paintCached(iv)
        }

        // 已绑定同一用户且有非占位图：不再走工厂（防闪）
        if (!force && boundUsername == username && !isPlaceholderOnly(iv)) {
            return true
        }

        // 2) AvatarDrawable 工厂绑定（内部异步加载，与微信自己头像一致）
        var bindErr = ""
        val ok = runCatching {
            val m = factoryA ?: resolveFactoryA(cl)
                ?: throw IllegalStateException("factoryA unresolved")
            m.invoke(null, iv, username)
            true
        }.getOrElse {
            bindErr = it.message ?: it.javaClass.simpleName
            false
        }

        if (ok) {
            boundUsername = username
            iv.setOnClickListener {
                val act = hostRef?.get() ?: (iv.context as? Activity)
                if (act != null) HomeSideDrawer.toggle(act)
            }
            // 稍后把画好的图抓进缓存
            val capToken = ++captureToken
            mainHandler.postDelayed({ if (capToken == captureToken) captureFromView(iv) }, 400)
            mainHandler.postDelayed({ if (capToken == captureToken) captureFromView(iv) }, 1200)
            xlog("bound user=$username force=$force")
            return true
        } else {
            xlog("factory bind fail user=$username err=$bindErr")
            // 失败重试一次：微信头像工厂可能在冷启动时尚未就绪
            mainHandler.postDelayed({
                if (isPlaceholderOnly(iv)) bindAvatar(iv, force = true)
            }, 800)
            return false
        }
    }

    // 解析 AvatarDrawable 工厂方法：优先 (ImageView,String)，兼容 ImageView 子类参数签名
    private fun resolveFactoryA(cl: ClassLoader): Method? {
        if (factoryA != null) return factoryA
        return runCatching {
            val factory = Class.forName(AVATAR_FACTORY, false, cl)
            factory.declaredMethods.firstOrNull { m ->
                m.name == "a" && m.parameterTypes.size == 2 &&
                    ImageView::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    m.parameterTypes[1] == String::class.java
            }?.also { it.isAccessible = true; factoryA = it }
        }.getOrNull()
    }

    private fun captureFromView(iv: ImageView) {
        runCatching {
            val d: Drawable = iv.drawable ?: return
            when (d) {
                is BitmapDrawable -> {
                    val b = d.bitmap
                    if (b != null && !b.isRecycled) rememberBitmap(b)
                }
                else -> {
                    // 反射 AvatarDrawable 内部 bitmap 字段
                    var c: Class<*>? = d.javaClass
                    while (c != null && c != Any::class.java) {
                        for (f in c.declaredFields) {
                            if (f.type != Bitmap::class.java) continue
                            f.isAccessible = true
                            val b = f.get(d) as? Bitmap
                            if (b != null && !b.isRecycled) {
                                rememberBitmap(b)
                                return
                            }
                        }
                        c = c.superclass
                    }
                    // 不再做“兜底画图缓存”：AvatarDrawable 尚未异步加载完成时 draw(canvas)
                    // 只会得到空白/灰图，会污染 cachedBmp 导致侧边栏头像空白。
                    // 拿不到内部 bitmap 就不缓存，让 factoryA 绑定自行显示。
                }
            }
        }
    }

    private fun isValidAvatarBitmap(bmp: Bitmap?): Boolean {
        if (bmp == null || bmp.isRecycled) return false
        val w = bmp.width
        val h = bmp.height
        if (w < 32 || h < 32) return false
        val cCenter = runCatching { bmp.getPixel(w / 2, h / 2) }.getOrDefault(0)
        if (Color.alpha(cCenter) < 10) return false
        val cTL = runCatching { bmp.getPixel(w / 4, h / 4) }.getOrDefault(0)
        val cTR = runCatching { bmp.getPixel(w * 3 / 4, h / 4) }.getOrDefault(0)
        val cBL = runCatching { bmp.getPixel(w / 4, h * 3 / 4) }.getOrDefault(0)
        val cBR = runCatching { bmp.getPixel(w * 3 / 4, h * 3 / 4) }.getOrDefault(0)
        if (cCenter == cTL && cCenter == cTR && cCenter == cBL && cCenter == cBR) {
            return false
        }
        return true
    }

    private fun rememberBitmap(bmp: Bitmap) {
        if (!isValidAvatarBitmap(bmp)) return
        com.OKK.yes.core.utils.MemoryCacheManager.put("home_avatar", bmp)
        cachedBmpTrusted = true
    }


    private fun loadBitmapSync(cl: ClassLoader, username: String): Bitmap? {
        return runCatching {
            val cMethod = factoryC ?: Class.forName(AVATAR_FACTORY, false, cl)
                .methods.firstOrNull {
                    it.name == "c" && it.parameterTypes.isEmpty() &&
                        java.lang.reflect.Modifier.isStatic(it.modifiers)
                }?.also {
                    it.isAccessible = true
                    factoryC = it
                } ?: return null
            val loader = cMethod.invoke(null) ?: return null
            val m = loadBitmapM ?: loader.javaClass.methods.firstOrNull {
                it.name == "loadBitmap" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            }?.also {
                it.isAccessible = true
                loadBitmapM = it
            } ?: return null
            val bmp = m.invoke(loader, username) as? Bitmap
            if (bmp != null && !bmp.isRecycled) bmp else null
        }.getOrNull()
    }

    private fun resolveSelfUsername(cl: ClassLoader): String {
        if (lastUsername.isNotBlank()) {
            return lastUsername
        }
        return WeChatSelfUser.resolve(cl, appContext, modulePath)
    }

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) return context.resources.getDimensionPixelSize(id)
        return dp(context, 28)
    }

    private fun refreshHeaderTexts(activity: Activity) {
        val cl = classLoader ?: activity.classLoader
        val nick = cachedNickname.ifBlank {
            runCatching {
                WeChatSelfUser.resolveNickname(cl, appContext, modulePath)
            }.getOrNull().orEmpty().also { cachedNickname = it }
        }.ifBlank { "我" }
        nicknameView?.text = nick

        val st = "在线" // 已按用户要求状态固定显示“在线”
        statusView?.text = st
    }

    private fun hookStatusGetter(cl: ClassLoader) {
        // 已按用户要求停用：状态行固定显示“在线”
    }

    /** 从状态项对象中启发式提取描述文本（方法名跨批次不稳定，枚举所有无参 String 方法分类）。 */
    private fun extractStatusText(item: Any): String? {
        return runCatching {
            val username = lastUsername
            val methods = item.javaClass.methods.filter {
                it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }
            var desc = ""
            var statusId = ""
            var fallbackText = ""
            for (m in methods) {
                val v = runCatching { m.invoke(item)?.toString()?.trim() }.getOrNull().orEmpty()
                if (v.isBlank() || v == "null" || v.length > 40) continue
                xlog("textstatus item method ${m.name}=${v.take(30)}")
                if (v.startsWith("wxid_") || v == username) continue // 排除用户名
                if (v.startsWith("v1_") || v.startsWith("text_state_")) continue // 状态ID/资源名，非描述
                if (isBase64ish(v)) continue // Base64 编码串（如 MTAwMA==）非描述
                if (v.all { it.isDigit() }) continue // 纯数字 → 图标ID
                val hasCJK = v.any { it.code > 127 }
                if (hasCJK) {
                    if (desc.isEmpty()) desc = v
                } else if (v.length in 1..20) {
                    if (statusId.isEmpty() && statusNameFromId(v).isNotBlank()) statusId = v
                    else if (fallbackText.isEmpty()) fallbackText = v
                }
            }
            desc.ifBlank {
                statusNameFromId(statusId).ifBlank {
                    fallbackText.ifBlank { null }
                }
            }
        }.getOrNull()
    }

    /** 判断字符串是否为 Base64 编码（微信状态 extInfo 常为 Base64(数字ID) 形式）。 */
    private fun isBase64ish(s: String): Boolean {
        if (s.length < 4 || s.contains(" ")) return false
        if (!s.all { it.isLetterOrDigit() || it == '=' || it == '+' || it == '/' }) return false
        return runCatching {
            val dec = String(android.util.Base64.decode(s, android.util.Base64.NO_WRAP), Charsets.UTF_8)
            dec.isNotBlank() && dec.length <= 20 && dec.all { it.isLetterOrDigit() || it == ' ' || it.code > 127 }
        }.getOrDefault(false)
    }

    /**
     * 解析并缓存状态文本。成功结果缓存；失败结果冷却 60s 内不重试，避免频繁 DexKit 全量扫描导致卡顿。
     * 优先使用微信自身调用 hook 捕获到的真实状态文本。
     */
    private fun resolveCachedStatusText(cl: ClassLoader, activity: Activity): String {
        if (cachedStatusText.isNotBlank()) return cachedStatusText
        capturedStatusText.takeIf { it.isNotBlank() }?.let {
            cachedStatusText = it
            return it
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastStatusFailAt < 60_000L) return "未设状态"
        val st = runCatching { resolveStatusText(cl, activity) }
            .getOrNull()?.trim().orEmpty()
        if (st.isNotBlank()) {
            cachedStatusText = st
        } else {
            lastStatusFailAt = now
        }
        return st.ifBlank { "未设状态" }
    }

    @Volatile private var lastStatusFailAt = 0L

    /**
     * 解析当前登录用户的微信状态文本。
     * 优先走微信自己的 TextStatus 存储服务：fe4.f0.f260343a.M(username) → pf4.k.g()/l()。
     * 旧 ContactStorage 字段只作为兜底，因为当前 Play/CN 混淆包经常没有稳定 ContactStorage 类名。
     */
    private fun resolveStatusText(cl: ClassLoader, activity: Activity): String {
        val username = WeChatSelfUser.resolve(cl, appContext, modulePath)
            .ifBlank { lastUsername }
        if (username.isBlank()) return ""

        return resolveStatusViaTextStatusService(cl, username).orEmpty()
    }

    private fun resolveStatusViaTextStatusService(cl: ClassLoader, username: String): String? {
        return runCatching {
            val serviceCls = resolveTextStatusServiceClass(cl) ?: return@runCatching null
            val inst = resolveTextStatusInstance(cl, serviceCls) ?: return@runCatching null

            // 方法名跨构建不稳定：按签名找 —— 1 个 String 参数、返回非原始类型（状态项对象）
            val methods = inst.javaClass.methods.filter { m ->
                m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == String::class.java &&
                    !m.returnType.isPrimitive &&
                    m.returnType != java.lang.Void.TYPE
            }
            xlog("textstatus candidate methods=${methods.map { it.name + "->" + it.returnType.simpleName }}")
            for (m in methods) {
                val item = runCatching { m.invoke(inst, username) }.getOrNull() ?: continue
                val desc = invokeStringNoArg(item, listOf("g", "getDescription", "description"))
                val statusId = invokeStringNoArg(item, listOf("l", "getStatusId", "statusId"))
                val iconId = invokeStringNoArg(item, listOf("h", "getIconId", "iconId"))
                val text = desc.takeIf { it.isNotBlank() }
                    ?: statusNameFromId(statusId).takeIf { it.isNotBlank() }
                    ?: statusNameFromId(iconId).takeIf { it.isNotBlank() }
                    ?: statusId.takeIf { it.isNotBlank() }
                if (!text.isNullOrBlank()) {
                    xlog("textstatus ok method=${m.name} desc=${desc.take(24)} statusId=${statusId.take(24)}")
                    return text
                }
            }
            null
        }.onFailure { xlog("textstatus service fail: ${it.javaClass.simpleName}:${it.message}") }.getOrNull()
    }

    /** 获取状态服务的实例：优先静态单例字段，其次 1 参构造（可空），最后无参构造。 */
    private fun resolveTextStatusInstance(cl: ClassLoader, serviceCls: Class<*>): Any? {
        runCatching {
            serviceCls.declaredFields.firstOrNull { f ->
                java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == serviceCls
            }?.apply { isAccessible = true }?.get(null)
        }.getOrNull()?.let { return it }
        val ctor1 = serviceCls.declaredConstructors.firstOrNull { it.parameterTypes.size == 1 }
        if (ctor1 != null) {
            val inst = runCatching {
                ctor1.apply { isAccessible = true }.newInstance(arrayOfNulls<Any>(1))
            }.getOrElse { e ->
                xlog("textstatus ctor1 fail: ${e.javaClass.simpleName}:${e.message}")
                null
            }
            if (inst != null) return inst
        }
        val inst0 = runCatching {
            serviceCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrElse { e ->
            xlog("textstatus ctor0 fail: ${e.javaClass.simpleName}:${e.message}")
            null
        }
        return inst0
    }

    /**
     * 定位微信 TextStatus 存储服务类（cn76 为 fe4.f0，其它构建批次混淆名不同）。
     * 先用 DexKit 日志串锚点定位，失败再回退已知混淆名。
     * 结果带缓存与失败冷却，避免每次刷新都全量扫描 dex 导致卡顿。
     */
    private val textStatusClsCache = AtomicReference<Pair<Long, Class<*>?>>(null)
    private fun resolveTextStatusServiceClass(cl: ClassLoader): Class<*>? {
        val now = SystemClock.uptimeMillis()
        textStatusClsCache.get()?.let { (t, c) ->
            if (now - t < (if (c != null) 10 * 60_000L else 60_000L)) return c
        }
        val ctx = appContext
        val result: Class<*>? = runCatching {
            if (ctx == null) return@runCatching null
            // 1. 拿所有含日志串的候选类（不同批次 anchor 不稳定，firstOrNull 可能踩错类）
            val candidates = DexKitSupport.findClassByStringsAll(ctx, cl, modulePath, "[getStatus] userName null or blank")
                .ifEmpty { DexKitSupport.findClassByStringsAll(ctx, cl, modulePath, "MicroMsg.StatusNativeLogicManager") }
                .ifEmpty { listOfNotNull(runCatching { Class.forName("fe4.f0", false, cl) }.getOrNull()) }
            if (candidates.isEmpty()) return@runCatching null
            // 诊断：打印每个候选的结构特征，便于跨批次定位代理单例类
            candidates.forEach { c ->
                val m1 = c.methods.filter { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java && !it.returnType.isPrimitive && it.returnType != java.lang.Void.TYPE }
                xlog("textstatus cand=${c.name} singleton=${hasSingletonField(c)} ctors=${c.declaredConstructors.map { it.parameterTypes.size }} ifaces=${c.interfaces.map { it.name }} m1str=${m1.map { it.name + "->" + it.returnType.simpleName }}")
            }
            // 2. 优先选本身就是静态单例代理的类（有自类型静态字段 + 1参String取状态方法）
            val proxy = candidates.firstOrNull { c ->
                hasSingletonField(c) && c.methods.any { m -> m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java && !m.returnType.isPrimitive && m.returnType != java.lang.Void.TYPE }
            }
            if (proxy != null) return@runCatching proxy
            // 3. 否则取第一个候选作为 anchor（l0），再尝试找同接口的静态单例代理类
            val anchor = candidates.first()
            val iface = anchor.interfaces.firstOrNull()
            if (iface != null) {
                val impls = DexKitSupport.findClassImplementing(ctx, cl, modulePath, iface.name)
                xlog("textstatus iface=${iface.name} impls=${impls.map { it.name }}")
                val p2 = impls.firstOrNull { it != anchor && hasSingletonField(it) }
                if (p2 != null) return@runCatching p2
            }
            anchor
        }.getOrNull()
        textStatusClsCache.set(now to result)
        xlog("textstatus class resolved=${result?.name ?: "null"}")
        return result
    }

    private fun hasSingletonField(cls: Class<*>): Boolean =
        cls.declaredFields.any { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == cls }

    private fun invokeStringNoArg(target: Any, names: List<String>): String {
        for (name in names) {
            val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == String::class.java }
                ?: target.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == String::class.java }
                ?: continue
            return runCatching { m.isAccessible = true; m.invoke(target)?.toString()?.trim().orEmpty() }.getOrDefault("")
        }
        return ""
    }

    private fun statusNameFromId(raw: String): String {
        val id = raw.lowercase().trim()
        if (id.isBlank()) return ""
        val idMap = mapOf(
            "sleep" to "睡觉", "work" to "搬砖", "play" to "玩游戏",
            "dog" to "遛狗", "cat" to "撸猫", "study" to "学习",
            "eat" to "吃饭", "coffee" to "喝咖啡", "music" to "听歌",
            "read" to "看书", "sport" to "运动", "travel" to "旅游",
            "walk" to "散步", "run" to "跑步", "sick" to "生病",
            "emo" to "emo", "busy" to "忙", "relax" to "发呆",
            "movie" to "看电影", "tv" to "追剧", "game" to "打游戏",
            "drink" to "喝酒", "car" to "开车", "fly" to "出差",
            "meeting" to "开会", "class" to "上课",
            "meizizi" to "美滋滋", "happy" to "美滋滋", "tired" to "疲惫",
            "bot" to "bot", "dash" to "冲", "break" to "裂开"
        )
        return idMap.entries.firstOrNull { (k, _) -> id == k || id.contains(k) }?.value.orEmpty()
    }

    /** 点击状态/昵称区域 → 直接进入微信状态页面 */
    private fun openStatusPage(activity: Activity) {
        val candidates = listOf(
            "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivityV2",
            "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivity",
            "com.tencent.mm.plugin.textstatus.ui.TextStatusNewActivity",
            "com.tencent.mm.plugin.textstatus.ui.TextStatusEditActivityV2",
            "com.tencent.mm.plugin.textstatus.ui.TextStatusEditActivity",
            "com.tencent.mm.plugin.textstatus.ui.flutter.StatusFlutterPublishActivity"
        )
        for ((idx, cls) in candidates.withIndex()) {
            val ok = runCatching {
                val intent = Intent().apply {
                    setClassName(activity, cls)
                    // 官方 o5.a() 启动 V2 时带 KEY_IS_ENTER=true
                    if (idx == 0) putExtra("KEY_IS_ENTER", true)
                }
                activity.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) {
                xlog("opened status page via $cls")
                return
            }
        }
        xlog("failed to open status page")
    }

    private fun isNight(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun nicknameColor(activity: Activity): Int {
        return if (isNight(activity)) Color.parseColor("#F0F0F0") else Color.parseColor("#191919")
    }

    private fun statusColor(activity: Activity): Int {
        return if (isNight(activity)) Color.parseColor("#AAAAAA") else Color.parseColor("#7F7F7F")
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("$TAG: $msg") }
    }
}

