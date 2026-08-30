package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.OKK.yes.core.compat.DexKitSupport
import com.OKK.yes.core.hooks.ui.HomeSideDrawer
import com.OKK.yes.core.hooks.ui.wekit.WkFloatingBarView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信主界面悬浮底栏（极速高性能版）。
 *
 * 核心优化：
 * 1. 彻底移除了全局 TextView.setText 极低效 Hook，消除滑动与 Tab 切换卡顿；
 * 2. Hook WxViewPager.setCurrentItem(int, boolean) 将点击硬切置为平滑过渡；
 * 3. 完美挂载与微信原生底栏清空隐藏；
 * 4. 0 拦截高鲁棒、未读角标实时精准拦截。
 */
object BottomTabFloatingHook {
    private const val TAG = "OKK-FloatTab"
    private const val MAIN_TAB_UI = "com.tencent.mm.ui.MainTabUI"
    private const val BOTTOM_TAB = "com.tencent.mm.ui.LauncherUIBottomTabView"
    private const val WX_VIEW_PAGER = "com.tencent.mm.ui.mogic.WxViewPager"
    private const val DOUBLE_TAP_MS = 300L
    private const val TAG_FLOAT_BAR = 0x7E0F1001

    private val installed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isTabProgrammaticClick = ThreadLocal.withInitial { false }

    @Volatile private var floatingBar: WkFloatingBarView? = null
    @Volatile private var tabsAdapter: Any? = null
    @Volatile private var onTabClickMethod: Method? = null
    @Volatile private var originalTabClickListener: View.OnClickListener? = null
    @Volatile private var lastHomeTapUptime = 0L
    @Volatile private var latestMainUnread = 0
    @Volatile private var latestContactUnread = 0
    @Volatile private var latestFriendUnread = 0
    @Volatile private var latestContactDot = false
    @Volatile private var latestFriendDot = false
    @Volatile private var latestTabIndex = 0

    @Volatile private var launcherUiRef: WeakReference<Activity>? = null
    @Volatile private var mainTabUiRef: WeakReference<Any>? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var hostModulePath: String? = null

    private fun hideBarEnabled(): Boolean =
        BottomTabConfig.floatingEnabled() || BottomTabConfig.hideBar()

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        appContext = context.applicationContext ?: context
        hostModulePath = modulePath
        xlog("install floating=${BottomTabConfig.floatingEnabled()}")

        hookMainTabUi(classLoader)
        hookFrostedContentView(classLoader)
        hookNativeBottomTabVisibility()
        hookScrollHide(classLoader)
        hookUnreadMethods(classLoader)
        registerLifecycleGuard(context)
        hookTitleSetters(classLoader)

        val refresh: (String) -> Unit = {
            BottomTabConfig.invalidate()
            mainHandler.post {
                onBottomTabConfigChanged()
                applyLauncherTitleForIndex(latestTabIndex)
            }
        }
        PublicConfigStore.addListener("bottom_tab_floating", refresh)
        PublicConfigStore.addListener("bottom_tab_hide_bar", refresh)
        PublicConfigStore.addListener("bottom_tab_hide_title", refresh)
        PublicConfigStore.addListener("bottom_tab_floating_labels", refresh)
        PublicConfigStore.addListener("bottom_tab_floating_badge", refresh)
        PublicConfigStore.addListener("bottom_tab_title_chats", refresh)
        PublicConfigStore.addListener("bottom_tab_title_contacts", refresh)
        PublicConfigStore.addListener("bottom_tab_title_discover", refresh)
        PublicConfigStore.addListener("bottom_tab_title_me", refresh)
        xlog("ready")
    }

    private fun onBottomTabConfigChanged() {
        val act = (floatingBar?.context as? Activity)
            ?: launcherUiRef?.get()
            ?: return
        if (act.isFinishing) return

        if (!hideBarEnabled()) {
            removeFloatingBar()
            restoreNativeBar(act)
            return
        }

        removeFloatingBar()
        mainHandler.postDelayed({ ensureBarAttached(act) }, 100L)
    }

    private fun removeFloatingBar() {
        val bar = floatingBar ?: return
        runCatching { (bar.parent as? ViewGroup)?.removeView(bar) }
        floatingBar = null
    }

    private fun restoreNativeBar(act: Activity) {
        runCatching {
            val decor = act.window?.decorView as? ViewGroup ?: return@runCatching
            fun walk(v: View) {
                if (isBottomTabClass(v.javaClass)) {
                    v.visibility = View.VISIBLE
                    v.alpha = 1f
                    val lp = v.layoutParams
                    if (lp != null && lp.height == 0) {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        v.layoutParams = lp
                    }
                    var curr: View? = v.parent as? View
                    while (curr != null && curr != decor) {
                        curr.visibility = View.VISIBLE
                        val clp = curr.layoutParams
                        if (clp != null && clp.height == 0) {
                            clp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            curr.layoutParams = clp
                        }
                        curr = curr.parent as? View
                    }
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(decor)
        }
    }

    private fun registerLifecycleGuard(context: Context) {
        val app = (context as? android.app.Application)
            ?: (context.applicationContext as? android.app.Application)
            ?: return
        runCatching {
            app.registerActivityLifecycleCallbacks(
                object : android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        val cn = activity.javaClass.name
                        if (!cn.contains("LauncherUI") && !cn.contains("MainTabUI")) return
                        if (!hideBarEnabled()) return
                        for (delay in longArrayOf(150L, 500L, 1500L)) {
                            mainHandler.postDelayed({ ensureBarAttached(activity) }, delay)
                        }
                    }
                    override fun onActivityStarted(a: Activity) {}
                    override fun onActivityPaused(a: Activity) {}
                    override fun onActivityStopped(a: Activity) {}
                    override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                    override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                    override fun onActivityDestroyed(a: Activity) {}
                }
            )
        }
    }

    private fun ensureBarAttached(activity: Activity) {
        if (activity.isFinishing || !hideBarEnabled()) return
        attachWindowTouchListener(activity)

        val mt = mainTabUiRef?.get()
        if (mt != null) {
            runCatching {
                val vp = XposedHelpers.getObjectField(mt, "mViewPager") as? ViewGroup
                vp?.let { viewPager ->
                    (viewPager.parent as? ViewGroup)?.let { viewParent ->
                        viewParent.background = null
                        viewParent.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        viewPager.background = null
                        viewPager.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        
                        // 对于原生底栏 (LauncherUIBottomTabView)，我们可以继续 GONE 并设为 0 高度。
                        // 对于毛玻璃层 (FrostedContentView)，绝对不能直接 GONE 或改 LayoutParams height 为 0，
                        // 否则微信会触发 fallback 画出白底色带，必须通过 clearFrostedBottom 精确置 0 内部属性。
                        for (i in 0 until viewParent.childCount) {
                            val child = viewParent.getChildAt(i) ?: continue
                            val name = child.javaClass.name
                            if (isBottomTabClass(child.javaClass)) {
                                child.visibility = View.GONE
                                child.background = null
                                child.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                val lp = child.layoutParams
                                if (lp != null && lp.height != 0) {
                                    lp.height = 0
                                    child.layoutParams = lp
                                }
                            }
                        }
                        
                        clearFrostedBottom(viewParent)

                        var p: View? = viewParent
                        while (p != null && p.id != android.R.id.content) {
                            p.background = null
                            p.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            p = p.parent as? View
                        }
                    }
                    clearPageBottomSpacing(viewPager)
                }
            }
        }

        val bar = floatingBar
        if (bar != null && bar.isAttachedToWindow) {
            bar.bringToFront()
            return
        }
        if (mt != null) {
            runCatching { tryInject(mt) }
        }
    }

    private fun hookMainTabUi(classLoader: ClassLoader) {
        val mainClazz = runCatching {
            XposedHelpers.findClass(MAIN_TAB_UI, classLoader)
        }.getOrNull() ?: return

        val createHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // 始终注入：tabsAdapter 供悬浮快捷入口等切 Tab 使用，不依赖悬浮底栏是否开启
                tryInject(param.thisObject)
            }
        }

        var hooked = 0
        runCatching {
            XposedHelpers.findAndHookMethod(mainClazz, "d", createHook)
            hooked++
        }
        if (hooked == 0) {
            for (m in mainClazz.declaredMethods) {
                if (m.parameterTypes.isEmpty() && m.returnType == Void.TYPE) {
                    runCatching {
                        XposedBridge.hookMethod(m, createHook)
                        hooked++
                    }
                }
            }
        }

        val adapterClazz = runCatching {
            XposedHelpers.findClass("$MAIN_TAB_UI\$TabsAdapter", classLoader)
        }.getOrNull()
        if (adapterClazz != null) {
            hookPageCallbacks(adapterClazz)
        } else {
            for (c in mainClazz.declaredClasses) {
                if (c.simpleName.contains("TabsAdapter", true) ||
                    c.interfaces.any { it.name.contains("OnPageChangeListener") }
                ) {
                    hookPageCallbacks(c)
                }
            }
        }
    }

    private fun hookFrostedContentView(classLoader: ClassLoader) {
        val clazz = runCatching {
            XposedHelpers.findClass("com.tencent.mm.ui.FrostedContentView", classLoader)
        }.getOrNull() ?: return

        for (m in clazz.declaredMethods) {
            if (m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == Boolean::class.javaPrimitiveType) {
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (BottomTabConfig.floatingEnabled()) {
                                param.args[0] = false
                            }
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (BottomTabConfig.floatingEnabled()) {
                                zeroFrostedBottom(param.thisObject as? View)
                            }
                        }
                    })
                }
            }
        }

        // setBottomBlurAreaHeight(int)
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz,
                "setBottomBlurAreaHeight",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!BottomTabConfig.floatingEnabled()) return
                        val h = (param.args[0] as? Number)?.toInt() ?: return
                        if (h != 0) param.args[0] = 0
                    }
                }
            )
        }
    }

    private fun zeroFrostedBottom(frosted: View?) {
        if (frosted == null) return
        runCatching {
            XposedHelpers.callMethod(frosted, "setBottomBlurAreaHeight", 0)
        }
        for (name in listOf("bottomBlurAreaHeight", "m")) {
            runCatching {
                val f = frosted.javaClass.getDeclaredField(name)
                if (f.type == Int::class.javaPrimitiveType) {
                    f.isAccessible = true
                    f.setInt(frosted, 0)
                }
            }
        }
        runCatching { frosted.postInvalidate() }
    }

    private fun clearFrostedBottom(root: View?) {
        if (root == null || !BottomTabConfig.floatingEnabled()) return
        val stack = java.util.ArrayDeque<View>()
        stack.add(root)
        var steps = 0
        while (stack.isNotEmpty() && steps < 600) {
            steps++
            val v = stack.removeFirst()
            val cn = v.javaClass.name
            if (cn.endsWith("FrostedContentView")) {
                zeroFrostedBottom(v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { stack.add(it) }
                }
            }
        }
    }

    private val scrollHideHandler = Handler(Looper.getMainLooper())
    private val showBarRunnable: Runnable = object : Runnable {
        override fun run() {
            val activity = (floatingBar?.context as? Activity) ?: launcherUiRef?.get()
            if (activity != null && isMiniProgramPulldownVisible(activity)) {
                floatingBar?.hideBar(animated = false)
                scrollHideHandler.postDelayed(this, 500L)
            } else {
                floatingBar?.showBar(animated = true)
            }
        }
    }

    private fun notifyScrollMovement() {
        if (!BottomTabConfig.floatingEnabled()) return
        mainHandler.post {
            scrollHideHandler.removeCallbacks(showBarRunnable)
            floatingBar?.hideBar(animated = true)
        }
    }

    private fun hookNativeBottomTabVisibility() {
        val visibilityHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!hideBarEnabled()) return
                val v = param.thisObject as? View ?: return
                if (isNativeBottomTabView(v)) param.args[0] = View.GONE
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(View::class.java, "setVisibility", Int::class.javaPrimitiveType, visibilityHook)
        }
        val backgroundHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!hideBarEnabled()) return
                val v = param.thisObject as? View ?: return
                if (!isNativeBottomTabView(v)) return
                if (param.args[0] is Int) {
                    param.args[0] = android.graphics.Color.TRANSPARENT
                } else {
                    param.args[0] = null
                }
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundColor", Int::class.javaPrimitiveType, backgroundHook)
        }
        runCatching {
            XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundDrawable", android.graphics.drawable.Drawable::class.java, backgroundHook)
        }
    }

    private fun isNativeBottomTabView(view: View): Boolean {
        var c: Class<*>? = view.javaClass
        while (c != null) {
            if (c.name == BOTTOM_TAB || c.name.endsWith("LauncherUIBottomTabView")) return true
            c = c.superclass
        }
        return false
    }

    private fun notifyScrollIdle() {
        if (!BottomTabConfig.floatingEnabled()) return
        mainHandler.post {
            scrollHideHandler.removeCallbacks(showBarRunnable)
            scrollHideHandler.postDelayed(showBarRunnable, 500L)
        }
    }

    private fun isMiniProgramPulldownVisible(activity: Activity): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        val stack = java.util.ArrayDeque<View>()
        stack.add(decor)
        var steps = 0
        while (stack.isNotEmpty() && steps < 500) {
            steps++
            val view = stack.removeFirst()
            if (view.visibility != View.VISIBLE || view.alpha <= 0.05f) continue
            if (view is TextView) {
                val text = view.text?.toString()?.trim().orEmpty()
                if (text == "搜索小程序" || text == "最近使用的小程序" || text == "常用的小程序") {
                    return true
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    view.getChildAt(i)?.let { stack.add(it) }
                }
            }
        }
        return false
    }

    private class FloatingTouchCallback(
        private val delegate: android.view.Window.Callback,
        private val touchSlop: Float,
        private val displayMetrics: android.util.DisplayMetrics,
        private val activity: Activity
    ) : android.view.Window.Callback by delegate {
        private var downX = 0f
        private var downY = 0f
        private var isScrollingY = false

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            delegate.onWindowFocusChanged(hasFocus)
            if (hasFocus && BottomTabConfig.floatingEnabled() && !activity.isFinishing) {
                applyNavigationBarBlendColor(activity)
            }
        }

        override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean {
            if (event != null && BottomTabConfig.floatingEnabled()) {
                val screenHeight = displayMetrics.heightPixels
                val density = displayMetrics.density
                val bottomBarThreshold = screenHeight - (120 * density + 0.5f).toInt()

                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        isScrollingY = false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        val absX = Math.abs(dx)
                        val absY = Math.abs(dy)

                        // 只有在不在悬浮底栏区域 (downY < bottomBarThreshold) 时响应列表上下滚动隐藏底栏
                        if (downY < bottomBarThreshold) {
                            // 垂直上下滚动 -> 隐藏/显示底栏（不拦截左右手，删除会话等手势完全不受影响）
                            if (absY > touchSlop && absY > absX) {
                                if (!isScrollingY) {
                                    isScrollingY = true
                                }
                                notifyScrollMovement()
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (isScrollingY) {
                            isScrollingY = false
                            notifyScrollIdle()
                        }
                    }
                }
            }
            return delegate.dispatchTouchEvent(event)
        }
    }

    private fun attachWindowTouchListener(activity: Activity) {
        val window = activity.window ?: return
        val currentCallback = window.callback ?: return
        if (currentCallback is FloatingTouchCallback) return

        val touchSlop = android.view.ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
        val metrics = activity.resources.displayMetrics
        window.callback = FloatingTouchCallback(currentCallback, touchSlop, metrics, activity)
    }

    private fun hookScrollHide(classLoader: ClassLoader) {
        // 已彻底移除 AbsListView.onScrollChanged 全局 Hook，消除微信全应用界面卡顿与滑动高延迟
    }

    private fun hookWxViewPager(classLoader: ClassLoader) {
        val vpClazz = runCatching {
            XposedHelpers.findClass(WX_VIEW_PAGER, classLoader)
        }.getOrNull() ?: return

        runCatching {
            XposedBridge.hookAllMethods(
                vpClazz, "onInterceptTouchEvent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!hideBarEnabled()) return
                        val vp = param.thisObject as? View ?: return
                        val act = findActivityFrom(vp.context) ?: launcherUiRef?.get() ?: return
                        val actName = act.javaClass.name
                        if (!actName.contains("LauncherUI") && !actName.contains("MainTabUI")) return
                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        if (HomeSideDrawer.onEdgeIntercept(act, ev)) {
                            return
                        }
                        param.result = false
                    }
                }
            )
            XposedBridge.hookAllMethods(
                vpClazz, "onTouchEvent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!hideBarEnabled()) return
                        val vp = param.thisObject as? View ?: return
                        val act = findActivityFrom(vp.context) ?: launcherUiRef?.get() ?: return
                        val actName = act.javaClass.name
                        if (!actName.contains("LauncherUI") && !actName.contains("MainTabUI")) return
                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        if (HomeSideDrawer.onEdgeTouch(act, ev)) {
                            param.result = true
                            return
                        }
                        param.result = false
                    }
                }
            )
        }
    }

    private fun findActivityFrom(target: Any?): Activity? {
        if (target == null) return null
        if (target is Activity) return target
        var ctx: android.content.Context? = target as? android.content.Context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }

    private fun hookPageCallbacks(adapterClazz: Class<*>) {
        fun hook(name: String, after: (XC_MethodHook.MethodHookParam) -> Unit) {
            for (m in adapterClazz.declaredMethods) {
                if (m.name != name) continue
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!BottomTabConfig.floatingEnabled()) return
                            after(param)
                        }
                    })
                }
            }
        }
        hook("onPageScrolled") { param ->
            val position = (param.args.getOrNull(0) as? Number)?.toInt() ?: return@hook
            val offset = (param.args.getOrNull(1) as? Number)?.toFloat() ?: 0f
            floatingBar?.onPageScrolled(position, offset)
            if (offset == 0f) {
                applyLauncherTitleForIndex(position)
            }
        }
        hook("onPageSelected") { param ->
            val position = (param.args.getOrNull(0) as? Number)?.toInt() ?: return@hook
            floatingBar?.onPageSelected(position)
            applyLauncherTitleForIndex(position)
        }
        hook("onPageScrollStateChanged") { param ->
            val state = (param.args.getOrNull(0) as? Number)?.toInt() ?: return@hook
            floatingBar?.onPageScrollStateChanged(state)
            if (state != 0) {
                notifyScrollMovement()
            } else {
                notifyScrollIdle()
            }
        }
    }

    private fun tryInject(mainTabUi: Any) {
        mainTabUiRef = WeakReference(mainTabUi)
        val viewPager = runCatching {
            XposedHelpers.getObjectField(mainTabUi, "mViewPager") as? ViewGroup
        }.getOrNull() ?: return
        val adapter = runCatching {
            XposedHelpers.getObjectField(mainTabUi, "mTabsAdapter")
        }.getOrNull() ?: return
        val viewParent = viewPager.parent as? ViewGroup ?: return

        viewPager.post {
            doInject(mainTabUi, viewPager, adapter, viewParent)
        }
    }

    private fun applyPermanentSuppression(viewParent: ViewGroup) {
        // 使用 ViewTreeObserver 永久压制原生底栏和毛玻璃，对抗微信的异步恢复（有时有一时无的竞态）
        viewParent.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!hideBarEnabled()) {
                    viewParent.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
                for (i in 0 until viewParent.childCount) {
                    val child = viewParent.getChildAt(i) ?: continue
                    val cn = child.javaClass.name
                    if (isBottomTabClass(child.javaClass)) {
                        if (child.visibility != View.GONE) child.visibility = View.GONE
                        if (child.background != null) {
                            child.background = null
                            child.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                        val lp = child.layoutParams
                        if (lp != null && lp.height != 0) {
                            lp.height = 0
                            child.layoutParams = lp
                        }
                    } else if (cn.endsWith("FrostedContentView")) {
                        zeroFrostedBottom(child)
                    }
                }
                return true
            }
        })
    }

    private fun doInject(
        mainTabUi: Any,
        viewPager: ViewGroup,
        adapter: Any,
        viewParent: ViewGroup
    ) {
        tabsAdapter = adapter
        onTabClickMethod = findOnTabClick(adapter.javaClass)

        // 未开启悬浮底栏/隐藏底栏时，仅保留 tabsAdapter 供切 Tab 使用，
        // 不做任何视觉副作用（不改导航栏、不屏蔽原生底栏、不注入悬浮栏）。
        if (!hideBarEnabled()) return

        val activity = resolveActivity(mainTabUi, viewPager)
        if (activity != null) {
            launcherUiRef = WeakReference(activity)
            applyNavigationBarBlendColor(activity)
            activity.window?.decorView?.post {
                applyNavigationBarBlendColor(activity)
                applyLauncherTitleForIndex(latestTabIndex)
            }
            attachWindowTouchListener(activity)
        }

        applyPermanentSuppression(viewParent)

        val bottomTabGroup = findBottomTab(viewParent)
        if (bottomTabGroup != null) {
            originalTabClickListener = extractTabClickListener(bottomTabGroup)
                ?: findClickListener(bottomTabGroup)
                ?: findClickListenerDeep(bottomTabGroup)
            xlog("originalTabClickListener extracted: $originalTabClickListener")
            bottomTabGroup.visibility = View.GONE
            bottomTabGroup.background = null
            bottomTabGroup.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            runCatching {
                val lp = bottomTabGroup.layoutParams
                if (lp != null && lp.height != 0) {
                    lp.height = 0
                    bottomTabGroup.layoutParams = lp
                }
            }
            
            var curr: View? = bottomTabGroup.parent as? View
            while (curr != null && curr != viewParent) {
                curr.background = null
                curr.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                curr.visibility = View.GONE
                runCatching {
                    val lp = curr.layoutParams
                    if (lp != null && lp.height != 0) {
                        lp.height = 0
                        curr.layoutParams = lp
                    }
                }
                curr = curr.parent as? View
            }
        }

        // 清空父容器与 ViewPager 底部内边距及背景，确保背景色完整融合无色差
        runCatching {
            viewParent.background = null
            viewParent.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            viewParent.setPadding(viewParent.paddingLeft, viewParent.paddingTop, viewParent.paddingRight, 0)
            viewParent.clipChildren = false
            viewParent.clipToPadding = false

            // 扫荡原生底栏，磨砂层由 clearFrostedBottom 深度处理
            for (i in 0 until viewParent.childCount) {
                val child = viewParent.getChildAt(i) ?: continue
                val name = child.javaClass.name
                if (isBottomTabClass(child.javaClass)) {
                    child.visibility = View.GONE
                    child.background = null
                    child.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    val lp = child.layoutParams
                    if (lp != null && lp.height != 0) {
                        lp.height = 0
                        child.layoutParams = lp
                    }
                }
            }
            
            clearFrostedBottom(viewParent)
        }

        runCatching {
            viewPager.background = null
            viewPager.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            viewPager.setPadding(viewPager.paddingLeft, viewPager.paddingTop, viewPager.paddingRight, 0)
            viewPager.clipChildren = false
            viewPager.clipToPadding = false
        }

        // 向上清空 viewParent 直至 content 根节点的所有父 View 背景色，防止原生灰白背景透出
        runCatching {
            var p: View? = viewParent
            while (p != null && p.id != android.R.id.content) {
                p.background = null
                p.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                p = p.parent as? View
            }
        }

        // 拓展 ViewPager 充满屏幕底部，消除原底栏区域留空白/黑色灰块
        runCatching {
            val lp = viewPager.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.bottomMargin = 0
            }
            if (lp is android.widget.RelativeLayout.LayoutParams) {
                lp.removeRule(android.widget.RelativeLayout.ABOVE)
                lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
            if (lp is android.widget.LinearLayout.LayoutParams) {
                lp.weight = 1f
                lp.height = 0
            }
            if (lp is FrameLayout.LayoutParams) {
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            }
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            viewPager.layoutParams = lp
            viewPager.requestLayout()
        }

        clearPageBottomSpacing(viewPager)
        viewPager.post { clearPageBottomSpacing(viewPager) }
        viewPager.postDelayed({ clearPageBottomSpacing(viewPager) }, 300L)
        viewPager.postDelayed({ clearPageBottomSpacing(viewPager) }, 1000L)

        if (!BottomTabConfig.floatingEnabled()) {
            return
        }

        for (i in viewParent.childCount - 1 downTo 0) {
            if (viewParent.getChildAt(i).getTag(TAG_FLOAT_BAR) == true) {
                viewParent.removeViewAt(i)
            }
        }

        val ctx = activity ?: viewPager.context
        val bar = WkFloatingBarView(
            context = ctx,
            pagerView = viewPager,
            onTabClick = { handleTabClick(it) },
            onTabReselect = { handleTabClick(it) },
            onDiscoverLongPress = { openImproveSnsTimeline(ctx) }
        )
        bar.updateLabels(
            BottomTabConfig.floatingTabLabels(),
            BottomTabConfig.floatingLabels(),
            BottomTabConfig.floatingBadge()
        )
        bar.setTag(TAG_FLOAT_BAR, true)
        floatingBar = bar
        applyBadgeState()

        viewParent.clipChildren = false
        viewParent.clipToPadding = false

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        try {
            viewParent.addView(bar, lp)
            bar.bringToFront()
            xlog("Floating bottom bar injected successfully into viewParent")
            com.OKK.yes.core.startup.FeatureHookRegistry.reportRuntime("悬浮底栏", true, "底栏成功挂载至主视图容器")
        } catch (t: Throwable) {
            floatingBar = null
            xlog("addView fail: ${t.message}")
            com.OKK.yes.core.startup.FeatureHookRegistry.reportRuntime("悬浮底栏", false, "底栏挂载失败: ${t.javaClass.simpleName}")
        }
    }

    fun triggerClearUnread() {
        ConversationGroupingHook.markUnreadAsReadForActiveGroup()
        val ctx = floatingBar?.context ?: appContext
        if (ctx != null) {
            mainHandler.post {
                runCatching {
                    android.widget.Toast.makeText(ctx, "已将消息标记为已读", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        xlog("Triggered clear unread for active group")
    }

    private fun handleTabClick(index: Int) {
        val now = SystemClock.uptimeMillis()
        if (index == 0 && now - lastHomeTapUptime <= 500L) {
            triggerClearUnread()
            lastHomeTapUptime = now
            return
        }
        navigateToTab(index)
        lastHomeTapUptime = if (index == 0) now else 0L
    }

    fun navigateToTab(index: Int) {
        val adapter = tabsAdapter ?: return
        val m = onTabClickMethod ?: return
        isTabProgrammaticClick.set(true)
        try {
            m.isAccessible = true
            m.invoke(adapter, index)
        } catch (t: Throwable) {
            xlog("onTabClick fail: ${t.message}")
        } finally {
            isTabProgrammaticClick.set(false)
        }
        lastHomeTapUptime = if (index == 0) SystemClock.uptimeMillis() else 0L
        applyLauncherTitleForIndex(index)
    }

    private fun onTabReselected(index: Int) {
        val now = SystemClock.uptimeMillis()
        if (index == 0 && originalTabClickListener != null &&
            now - lastHomeTapUptime <= DOUBLE_TAP_MS
        ) {
            val probe = View(floatingBar?.context ?: return).apply { tag = 0 }
            runCatching {
                originalTabClickListener?.onClick(probe)
                originalTabClickListener?.onClick(probe)
            }
            lastHomeTapUptime = now
            return
        }
        navigateToTab(index)
        lastHomeTapUptime = if (index == 0) now else 0L
    }

    private fun hookUnreadMethods(classLoader: ClassLoader) {
        val clazz = runCatching {
            XposedHelpers.findClass(BOTTOM_TAB, classLoader)
        }.getOrNull() ?: return

        val ctx = appContext
        val modulePath = hostModulePath

        fun resolveByLog(vararg logStrings: String): Method? {
            if (ctx == null) return null
            val m = DexKitSupport.findMethodByStrings(ctx, classLoader, modulePath, *logStrings) ?: return null
            return if (clazz.isAssignableFrom(m.declaringClass) || m.declaringClass.isAssignableFrom(clazz)) m else null
        }

        fun hookIntMethod(m: Method?, apply: (Int) -> Unit): Boolean {
            m ?: return false
            if (m.parameterTypes.size != 1 || m.parameterTypes[0] != Int::class.javaPrimitiveType) return false
            return runCatching {
                m.isAccessible = true
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!BottomTabConfig.floatingEnabled()) return
                        apply((param.args[0] as? Number)?.toInt() ?: return)
                        param.result = null
                    }
                })
                true
            }.getOrDefault(false)
        }

        fun hookBoolMethod(m: Method?, apply: (Boolean) -> Unit): Boolean {
            m ?: return false
            if (m.parameterTypes.isEmpty() || m.parameterTypes[0] != Boolean::class.javaPrimitiveType) return false
            return runCatching {
                m.isAccessible = true
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!BottomTabConfig.floatingEnabled()) return
                        apply(param.args[0] as? Boolean ?: return)
                        param.result = null
                    }
                })
                true
            }.getOrDefault(false)
        }

        val mainMethod = resolveByLog("updateMainTabUnread %d")
        val friendMethod = resolveByLog("[updateFriendTabUnread] unread : ")
        val contactMethod = resolveByLog("[updateContactTabUnread] unread : ")
        val dotMethod = resolveByLog("[showFriendPoint] show : ")

        val mainOk = hookIntMethod(mainMethod) { updateMainUnread(it) }
        val contactOk = hookIntMethod(contactMethod) { updateContactUnread(it) }
        val friendOk = hookIntMethod(friendMethod) { updateFriendUnread(it) }
        val dotOk = hookBoolMethod(dotMethod) { updateFriendDot(it) }

        val effOk = mainOk && friendOk
        com.OKK.yes.core.startup.FeatureHookRegistry.reportEffective(
            "BottomTabFloating",
            effOk,
            if (effOk) "全部未读与红点 Hook 成功" else "主/发现 Tab 未读 Hook 未全匹配"
        )
    }

    private fun updateMainUnread(count: Int) {
        latestMainUnread = count.coerceAtLeast(0)
        applyBadgeState()
    }

    private fun updateContactUnread(count: Int) {
        latestContactUnread = count.coerceAtLeast(0)
        applyBadgeState()
    }

    private fun updateFriendUnread(count: Int) {
        latestFriendUnread = count.coerceAtLeast(0)
        applyBadgeState()
    }

    private fun updateFriendDot(show: Boolean) {
        latestFriendDot = show
        applyBadgeState()
    }

    private fun applyBadgeState() {
        val bar = floatingBar ?: return
        bar.setMainUnread(latestMainUnread)
        bar.setContactUnread(latestContactUnread)
        bar.setContactDot(latestContactUnread <= 0 && latestContactDot)
        bar.setFriendUnread(latestFriendUnread)
        bar.setFriendDot(latestFriendUnread <= 0 && latestFriendDot)
    }

    private fun hookTitleSetters(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "setTitle",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        val actName = act.javaClass.name
                        if (!actName.contains("LauncherUI") && !actName.contains("MainTabUI")) return

                        val text = param.args[0]?.toString() ?: return
                        val labels = BottomTabConfig.floatingTabLabels()
                        val defaultLabels = BottomTabConfig.DEFAULT_LABELS
                        val idx = defaultLabels.indexOf(text)
                        if (idx >= 0 && idx in labels.indices) {
                            param.args[0] = labels[idx]
                        }
                    }
                }
            )
        }

        // 深度监听所有在 LauncherUI 页面中对 TextView.setText 的调用，保持顶部标题与底栏自定义标题实时严格一致
        runCatching {
            XposedBridge.hookAllMethods(
                TextView::class.java,
                "setText",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        val ctx = tv.context ?: return
                        val act = findActivityFrom(ctx) ?: launcherUiRef?.get() ?: return
                        val actName = act.javaClass.name
                        if (!actName.contains("LauncherUI") && !actName.contains("MainTabUI")) return

                        val text = param.args.getOrNull(0)?.toString()?.trim() ?: return
                        if (text.isEmpty()) return

                        val labels = BottomTabConfig.floatingTabLabels()
                        val defaultLabels = BottomTabConfig.DEFAULT_LABELS
                        for (i in defaultLabels.indices) {
                            val def = defaultLabels[i]
                            if (text == def || text.startsWith("$def(") || text.startsWith("$def（")) {
                                val target = labels.getOrNull(i)
                                if (!target.isNullOrBlank() && target != def) {
                                    val replaced = text.replaceFirst(def, target)
                                    param.args[0] = replaced
                                }
                                break
                            }
                        }
                    }
                }
            )
        }
    }

    private fun findTitleTextView(root: View, defaultTitles: List<String>): TextView? {
        if (root is TextView) {
            val txt = root.text?.toString()?.trim() ?: ""
            if (txt.isNotEmpty() && (txt in defaultTitles || txt in BottomTabConfig.floatingTabLabels())) {
                return root
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findTitleTextView(root.getChildAt(i), defaultTitles)
                if (found != null) return found
            }
        }
        return null
    }

    private fun applyLauncherTitleForIndex(index: Int) {
        latestTabIndex = index
        val labels = BottomTabConfig.floatingTabLabels()
        if (index !in labels.indices) return
        val targetTitle = labels[index]
        if (targetTitle.isBlank()) return
        val act = launcherUiRef?.get()
            ?: (floatingBar?.context as? Activity)
            ?: return

        runCatching {
            val decor = act.window?.decorView as? ViewGroup ?: return@runCatching
            val tv = findTitleTextView(decor, BottomTabConfig.DEFAULT_LABELS)
                ?: (decor.findViewById(android.R.id.text1) as? TextView)
                ?: runCatching {
                    val bar = act.actionBar?.customView
                    bar?.findViewById<TextView>(android.R.id.text1)
                }.getOrNull()

            if (tv is TextView) {
                if (tv.text?.toString() != targetTitle) {
                    tv.text = targetTitle
                }
            }
        }
    }

    private fun openImproveSnsTimeline(ctx: Context) {
        runCatching {
            val clazz = XposedHelpers.findClass(
                "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI",
                ctx.classLoader
            )
            val intent = android.content.Intent(ctx, clazz)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.onFailure { xlog("open sns timeline fail: ${it.message}") }
    }

    private fun navigationBlendColor(activity: Activity): Int {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) 0xFF141414.toInt() else 0xFFF7F7F7.toInt()
    }

    private fun applyNavigationBarBlendColor(activity: Activity) {
        runCatching {
            val win = activity.window ?: return
            win.navigationBarColor = navigationBlendColor(activity)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                win.isNavigationBarContrastEnforced = false
            }
            @Suppress("DEPRECATION")
            win.decorView.systemUiVisibility = win.decorView.systemUiVisibility and
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION.inv()
        }
    }

    fun applyTransparentNavBar(activity: Activity) {
        applyNavigationBarBlendColor(activity)
    }

    private fun clearPageBottomSpacing(viewPager: ViewGroup) {
        runCatching {
            fun walk(v: View, depth: Int) {
                if (depth > 12) return
                if (v is ViewGroup) {
                    v.clipToPadding = false
                    v.clipChildren = false
                    if (v.paddingBottom != 0) {
                        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                    }
                    val lp = v.layoutParams
                    if (lp is ViewGroup.MarginLayoutParams && lp.bottomMargin != 0) {
                        lp.bottomMargin = 0
                        v.layoutParams = lp
                    }
                    for (i in 0 until v.childCount) {
                        walk(v.getChildAt(i), depth + 1)
                    }
                }
            }
            walk(viewPager, 0)
        }
    }

    private fun findBottomTab(parent: ViewGroup): ViewGroup? {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c is ViewGroup && isBottomTabClass(c.javaClass)) return c
        }
        return null
    }

    private fun isBottomTabClass(clazz: Class<*>): Boolean {
        var c: Class<*>? = clazz
        while (c != null) {
            if (c.name == BOTTOM_TAB || c.name.endsWith("LauncherUIBottomTabView")) return true
            c = c.superclass
        }
        return false
    }

    private fun findOnTabClick(adapterClass: Class<*>): Method? {
        var c: Class<*>? = adapterClass
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == "onTabClick" && m.parameterTypes.size == 1) return m
            }
            c = c.superclass
        }
        return null
    }

    private fun extractTabClickListener(v: View): View.OnClickListener? {
        var clazz: Class<*>? = v.javaClass
        while (clazz != null && clazz != Any::class.java && clazz != View::class.java) {
            for (f in clazz.declaredFields) {
                if (View.OnClickListener::class.java.isAssignableFrom(f.type)) {
                    runCatching {
                        f.isAccessible = true
                        val l = f.get(v) as? View.OnClickListener
                        if (l != null) return l
                    }
                }
            }
            clazz = clazz.superclass
        }
        findClickListener(v)?.let { return it }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val l = extractTabClickListener(v.getChildAt(i))
                if (l != null) return l
            }
        }
        return null
    }

    private fun findClickListener(view: View): View.OnClickListener? {
        return runCatching {
            val getListenerInfo = View::class.java.getDeclaredMethod("getListenerInfo")
            getListenerInfo.isAccessible = true
            val info = getListenerInfo.invoke(view) ?: return null
            val f = info.javaClass.getDeclaredField("mOnClickListener")
            f.isAccessible = true
            f.get(info) as? View.OnClickListener
        }.getOrNull()
    }

    private fun findClickListenerDeep(root: ViewGroup): View.OnClickListener? {
        findClickListener(root)?.let { return it }
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeFirst()
            findClickListener(v)?.let { return it }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
            }
        }
        return null
    }

    private fun resolveActivity(mainTabUi: Any, view: View): Activity? {
        runCatching {
            val a = XposedHelpers.getObjectField(mainTabUi, "f190271a")
            if (a is Activity) return a
        }
        var ctx = view.context
        var guard = 0
        while (ctx != null && guard++ < 8) {
            if (ctx is Activity) return ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        try { XposedBridge.log("[$TAG] $msg") } catch (_: Throwable) {}
    }
}
