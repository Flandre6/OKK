package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 全局主题壁纸：使用旧版稳定 overlay，保证壁纸完整显示。
 * 聊天页强制隐藏/清理，避免覆盖聊天内容。
 */
object ThemeWallpaperController {
    private const val TAG = "OKK-ThemeWp"
    private const val OVERLAY_TAG = "achat_global_bg_overlay_v4"
    private const val PAGE_BG_TAG = "achat_page_bg_under_content_v1"
    private const val TAG_PATH = 0x55020011

    // 聊天前台探测节流：同一 Activity 1s 内复用结果，避免高频 focus/resume 触发昂贵的视图树遍历
    private val chattingProbeLock = Any()
    @Volatile private var lastChattingProbeActivity: Activity? = null
    @Volatile private var lastChattingProbeResult = false
    @Volatile private var lastChattingProbeTime = 0L
    private const val TAG_HOME_PATH = 0x55020012
    private const val TAG_DECOR_PATH = 0x55020013
    private const val TAG_PAGE_PATH = 0x55020014
    private const val UNDER_WALLPAPER_TAG = "achat_under_wallpaper_v1"
    private const val TAG_UNDER_WALLPAPER = 0x55020015
    private const val ELEVATION = 50_000f
    private val mainHandler = Handler(Looper.getMainLooper())

    private val overlays = WeakHashMap<Activity, WeakReference<ImageView>>()
    private val keepers = WeakHashMap<Activity, KeepOnTop>()
    private val tracked = WeakHashMap<Activity, Boolean>()
    private val rowCleaners = WeakHashMap<ViewGroup, ViewTreeObserver.OnPreDrawListener>()
    private val forceVisibleUntil = WeakHashMap<Activity, Long>()
    private val suppressForChatUntil = WeakHashMap<Activity, Long>()
    @Volatile private var lastLauncher: WeakReference<Activity>? = null
    @Volatile private var launcherHomeRootId = 0
    @Volatile private var launcherPrepareViewId = 0
    @Volatile private var launcherRootMissLogged = false

    private val blacklistExact = setOf(
        "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI",
        "com.tencent.mm.ui.chatting.gallery.ImageGalleryGridUI",
        "com.tencent.mm.plugin.sns.ui.SnsOnlineVideoActivity",
        "com.tencent.mm.plugin.sns.ui.SnsBrowseUI",
        "com.tencent.mm.plugin.sns.ui.SnsGalleryUI",
        "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
        "com.tencent.mm.plugin.voip.ui.VideoActivity",
        "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI",
        "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
        "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI",
        "com.tencent.mm.plugin.location_soso.SoSoProxyUI"
    )

    private val cachedChattingMethods = java.util.concurrent.ConcurrentHashMap<Class<*>, List<java.lang.reflect.Method>>()

    fun installListener() {
        ThemeWallpaperConfig.addListener {
            mainHandler.post { refreshAll() }
        }
        // 也响应功能列表里直接改 PublicConfigStore 的开关/路径（保存即生效）
        PublicConfigStore.addListener(ThemeWallpaperConfig.KEY_ENABLED) {
            ThemeWallpaperConfig.invalidate()
            ThemeWallpaperConfig.reloadIfNeeded(force = true)
            mainHandler.post { refreshAll() }
        }
        PublicConfigStore.addListener(ThemeWallpaperConfig.KEY_PATH) {
            ThemeWallpaperConfig.invalidate()
            ThemeWallpaperConfig.reloadIfNeeded(force = true)
            mainHandler.post { refreshAll() }
        }
        PublicConfigStore.addListener(ThemeWallpaperConfig.KEY_ALPHA) {
            ThemeWallpaperConfig.invalidate()
            ThemeWallpaperConfig.reloadIfNeeded(force = true)
            mainHandler.post { refreshAll() }
        }
    }

    fun isTargetActivity(activity: Activity): Boolean {
        val n = activity.javaClass.name
        if (n in blacklistExact) return false
        // 独立聊天 Activity
        if (n.contains("ChattingUI") && !n.contains("LauncherUI")) return false
        if (n.contains("LauncherUI")) return true
        if (n.contains("com.tencent.mm") && n.contains("setting", ignoreCase = true)) return true
        if (n.contains("SettingsUI") || n.contains("MainSettingsUI")) return true
        return false
    }

    /** Launcher 内嵌聊天时隐藏壁纸 */
    fun isChattingForeground(activity: Activity): Boolean {
        // 1s 节流：同一 Activity 重复探测直接复用上次结果（高频 focus/resume 时显著减少视图树遍历开销）
        val now = SystemClock.uptimeMillis()
        if (lastChattingProbeActivity === activity && now - lastChattingProbeTime < 1000L) {
            return lastChattingProbeResult
        }
        val result = isChattingForegroundSlow(activity)
        synchronized(chattingProbeLock) {
            lastChattingProbeActivity = activity
            lastChattingProbeResult = result
            lastChattingProbeTime = now
        }
        return result
    }

    /** 清空 1s 节流缓存，用于 Activity 切换时即时响应 */
    fun clearChattingProbeCache() {
        synchronized(chattingProbeLock) {
            lastChattingProbeActivity = null
            lastChattingProbeResult = false
            lastChattingProbeTime = 0L
        }
    }

    /** 绕过 1s 节流缓存，强制重新探测。用于退出聊天后需要立即感知的调用方（如会话分组）。 */
    fun isChattingForegroundFresh(activity: Activity): Boolean {
        val result = isChattingForegroundSlow(activity)
        synchronized(chattingProbeLock) {
            lastChattingProbeActivity = activity
            lastChattingProbeResult = result
            lastChattingProbeTime = SystemClock.uptimeMillis()
        }
        return result
    }

    private fun isChattingForegroundSlow(activity: Activity): Boolean {
        return runCatching {
            val isLauncher = activity.javaClass.name.contains("LauncherUI")
            launcherChattingOpen(activity)?.let { return it }
            if (isLauncher) {
                currentFragmentIsChatting(activity)?.let { return it }
                // 兜底：视图树探测可见的 ChattingUI 布局（首页聊天关闭时该布局不可见 → false）
                val root = activity.window?.decorView ?: return false
                return findClassNameContains(root, "ChattingUILayout") ||
                    findClassNameContains(root, "chatting.view")
            }

            // 方法反射缓存：避免每次卡顿式搜索全部 methods 列表
            val methods = cachedChattingMethods.getOrPut(activity.javaClass) {
                activity.javaClass.methods.filter {
                    it.parameterTypes.isEmpty() &&
                        (it.returnType == Boolean::class.javaPrimitiveType ||
                            it.returnType == Boolean::class.java) &&
                        it.name in setOf("isChattingForeground", "isMainTab", "getChattingBoolean")
                }
            }
            for (m in methods) {
                val v = runCatching { m.invoke(activity) as? Boolean }.getOrNull() ?: continue
                if (m.name == "isChattingForeground" && v) return true
                if (m.name == "isMainTab" && !v) return true
            }
            // 视图树探测
            val root = activity.window?.decorView ?: return false
            return findClassNameContains(root, "ChattingUILayout") ||
                findClassNameContains(root, "chatting.view")
        }.getOrDefault(false)
    }

    private fun launcherChattingOpen(activity: Activity): Boolean? {
        if (!activity.javaClass.name.contains("LauncherUI")) return null
        return runCatching {
            val field = activity.javaClass.getDeclaredField("chattingTabUI").apply {
                isAccessible = true
            }
            val tab = field.get(activity) ?: return@runCatching false
            val closedField = tab.javaClass.getDeclaredField("f190374k").apply {
                isAccessible = true
            }
            val closed = closedField.getBoolean(tab)
            !closed
        }.getOrNull()
    }

    private fun currentFragmentIsChatting(activity: Activity): Boolean? {
        return runCatching {
            val method = activity.javaClass.methods.firstOrNull {
                it.name == "getCurrentFragmet" && it.parameterTypes.isEmpty()
            } ?: return@runCatching null
            val fragment = method.invoke(activity) ?: return@runCatching false
            fragment.javaClass.name.contains("chatting", ignoreCase = true)
        }.getOrNull()
    }

    private fun findClassNameContains(v: View, part: String): Boolean {
        return findClassNameContainsDepth(v, part, 0)
    }

    private fun findClassNameContainsDepth(v: View, part: String, depth: Int): Boolean {
        if (depth > 10) return false
        if (v.javaClass.name.contains(part, ignoreCase = true) && v.visibility == View.VISIBLE &&
            v.width > 0 && v.height > v.resources.displayMetrics.heightPixels / 3
        ) {
            return true
        }
        if (v is ViewGroup) {
            val n = v.childCount.coerceAtMost(12)
            for (i in 0 until n) {
                if (findClassNameContainsDepth(v.getChildAt(i), part, depth + 1)) return true
            }
        }
        return false
    }

    fun onActivityResume(activity: Activity) {
        if (!isTargetActivity(activity)) {
            hideOverlay(activity)
            return
        }
        rememberActivity(activity)
        applyBackground(activity)
        BottomTabFloatingHook.applyTransparentNavBar(activity)
    }

    fun rememberActivity(activity: Activity) {
        if (!isTargetActivity(activity)) return
        tracked[activity] = true
        if (activity.javaClass.name.contains("LauncherUI")) {
            lastLauncher = WeakReference(activity)
        }
    }

    fun resolveLauncherUi(): Activity? {
        lastLauncher?.get()?.takeIf { !it.isFinishing }?.let { return it }
        return runCatching {
            val clazz = Class.forName("com.tencent.mm.ui.LauncherUI")
            val m = clazz.methods.firstOrNull {
                it.name == "getInstance" &&
                    it.parameterTypes.isEmpty() &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers)
            } ?: return null
            m.invoke(null) as? Activity
        }.getOrNull()
    }

    fun forceApplyNow(): String {
        return runCatching {
            ThemeWallpaperConfig.reloadIfNeeded(force = true)
            if (!ThemeWallpaperConfig.isEnabled()) {
                hideAll()
                return@runCatching "未启用：请打开「启用壁纸」"
            }
            if (ThemeWallpaperConfig.bitmap() == null) {
                return@runCatching "无图片：请先选择壁纸"
            }
            val launcher = resolveLauncherUi()
            if (launcher != null) rememberActivity(launcher)
            if (launcher == null && tracked.isEmpty()) {
                return@runCatching "请回到微信首页再点应用"
            }
            var n = 0
            val list = buildList {
                launcher?.let { add(it) }
                tracked.keys.filter { !it.isFinishing && it !== launcher }.forEach { add(it) }
            }
            for (a in list) {
                if (applyBackground(a)) n++
            }
            writeDebug("forceApply n=$n a=${ThemeWallpaperConfig.alpha()} key=${ThemeWallpaperConfig.imageKey()}")
            "已应用 $n 处 · 壁纸透明度${(ThemeWallpaperConfig.alpha() * 100).toInt()}%"
        }.getOrElse { "应用失败：${it.message}" }
    }

    fun refreshAll() {
        resolveLauncherUi()?.let { rememberActivity(it) }
        for (a in tracked.keys.toList()) {
            if (a.isFinishing) {
                tracked.remove(a)
                continue
            }
            applyBackground(a)
        }
    }

    fun restoreAfterChatClose(activity: Activity) {
        if (!isTargetActivity(activity)) return
        rememberActivity(activity)
        suppressForChatUntil.remove(activity)
        forceVisibleUntil[activity] = SystemClock.uptimeMillis() + 1600L
        writeDebug("restoreAfterChatClose ${activity.javaClass.simpleName}")
        applyBackground(activity)
        disableLauncherPrepareView(activity)
        for (delay in longArrayOf(80L, 240L, 650L, 1400L)) {
            activity.window?.decorView?.postDelayed({
                if (!activity.isFinishing && ThemeWallpaperConfig.isEnabled()) {
                    applyBackground(activity)
                    disableLauncherPrepareView(activity)
                }
            }, delay)
        }
    }

    fun prepareForChatClose(activity: Activity) {
        if (!isTargetActivity(activity)) return
        rememberActivity(activity)
        forceVisibleUntil[activity] = SystemClock.uptimeMillis() + 1600L
        applyBackground(activity)
        disableLauncherPrepareView(activity)
    }

    fun prepareForHomeReveal(activity: Activity) {
        if (!isTargetActivity(activity)) return
        rememberActivity(activity)
        suppressForChatUntil.remove(activity)
        forceVisibleUntil[activity] = SystemClock.uptimeMillis() + 1600L
        applyBackground(activity)
        disableLauncherPrepareView(activity)
    }

    fun prepareForChatOpenSnapshot(activity: Activity) {
        if (!isTargetActivity(activity)) return
        rememberActivity(activity)
        suppressForChatUntil[activity] = SystemClock.uptimeMillis() + 10_000L
        xlog("prepareForChatOpenSnapshot ${activity.javaClass.simpleName}")
        hideForChatOpen(activity)
        activity.window?.decorView?.post {
            if (!activity.isFinishing && ThemeWallpaperConfig.isEnabled()) {
                hideForChatOpen(activity)
            }
        }
    }

    fun hideForChatOpen(activity: Activity) {
        if (!isTargetActivity(activity)) return
        rememberActivity(activity)
        forceVisibleUntil.remove(activity)
        suppressForChatUntil[activity] = SystemClock.uptimeMillis() + 10_000L
        hideOverlay(activity)
        removeUnderlyingWallpapers(activity)
        clearLauncherDecorWallpaper(activity)
        clearLauncherContainerWallpaper(activity)
        clearPageWallpapers(activity)
    }

    fun setAlphaLive(alpha: Float) {
        val clamped = ThemeWallpaperConfig.clampAlpha(alpha)
        val paintAlpha = (clamped * 255f).toInt().coerceIn(60, 255)
        val bmp = ThemeWallpaperConfig.bitmap()
        mainHandler.post {
            for (act in tracked.keys.toList()) {
                if (act.isFinishing) continue
                val decor = act.window?.decorView as? ViewGroup ?: continue
                if (!isChattingForeground(act) && bmp != null && !bmp.isRecycled) {
                    runCatching {
                        act.window?.setBackgroundDrawable(CenterCropBitmapDrawable(bmp, paintAlpha))
                    }
                    decor.background = CenterCropBitmapDrawable(bmp, paintAlpha)
                }
            }
        }
    }

    fun applyBackground(activity: Activity): Boolean {
        if (activity.isFinishing) return false
        if (!isTargetActivity(activity)) {
            clearLauncherDecorWallpaper(activity)
            clearLauncherContainerWallpaper(activity)
            hideOverlay(activity)
            return false
        }
        ThemeWallpaperConfig.reloadIfNeeded(force = false)
        if (!ThemeWallpaperConfig.isEnabled()) {
            clearLauncherDecorWallpaper(activity)
            clearLauncherContainerWallpaper(activity)
            clearPageWallpapers(activity)
            hideOverlay(activity)
            return false
        }
        if (isSuppressedForChat(activity)) {
            hideOverlay(activity)
            removeUnderlyingWallpapers(activity)
            clearLauncherDecorWallpaper(activity)
            clearLauncherContainerWallpaper(activity)
            clearPageWallpapers(activity)
            return false
        }
        // 聊天页完全不注入主题壁纸。否则容易破坏聊天列表/输入栏层级。
        if (activity.javaClass.name.contains("LauncherUI") && isChattingForeground(activity)) {
            hideOverlay(activity)
            removeUnderlyingWallpapers(activity)
            clearLauncherDecorWallpaper(activity)
            clearLauncherContainerWallpaper(activity)
            clearPageWallpapers(activity)
            return false
        }
        val bmp = ThemeWallpaperConfig.bitmap()
        if (bmp == null || bmp.isRecycled) {
            clearLauncherDecorWallpaper(activity)
            clearLauncherContainerWallpaper(activity)
            clearPageWallpapers(activity)
            hideOverlay(activity)
            return false
        }

        return try {
            val pathKey = ThemeWallpaperConfig.imageKey()
            clearLauncherDecorWallpaper(activity)
            clearLauncherContainerWallpaper(activity)
            clearPageWallpapers(activity)
            val decor = activity.window?.decorView as? ViewGroup ?: return false

            xlog("applyBackground for ${activity.javaClass.simpleName} isChatting=${isChattingForeground(activity)}")

            val userA = ThemeWallpaperConfig.alpha().coerceIn(0.15f, 1f)
            val paintAlpha = (userA * 255f).toInt().coerceIn(60, 255)
            val key = "$pathKey:$paintAlpha"

            // 1. window 固定全屏壁纸底（最底层，不随列表滚动）
            runCatching {
                activity.window?.setBackgroundDrawable(CenterCropBitmapDrawable(bmp, paintAlpha))
            }

            // 2. decor 固定全屏壁纸底
            if (decor.getTag(TAG_DECOR_PATH) != key) {
                decor.background = CenterCropBitmapDrawable(bmp, paintAlpha)
                decor.setTag(TAG_DECOR_PATH, key)
            }

            // 3. 彻底移除所有顶层遮罩（消除文字被灰蒙版盖住洗淡的根源）
            removeLauncherOverlay(activity, decor)

            // 4. content 透明，露出底层壁纸
            activity.findViewById<View>(android.R.id.content)?.let { c ->
                c.setBackgroundColor(Color.TRANSPARENT)
            }

            // 5. 定向清理微信首页容器实色背景（打通壁纸透视通道）
            clearSpecificLauncherBackgrounds(decor, 0)
            scheduleSpecificClear(decor)

            true
        } catch (t: Throwable) {
            xlog("apply fail: ${t.message}")
            false
        }
    }

    private fun isForceVisible(activity: Activity): Boolean {
        val until = forceVisibleUntil[activity] ?: return false
        return until > SystemClock.uptimeMillis()
    }

    private fun isSuppressedForChat(activity: Activity): Boolean {
        val until = suppressForChatUntil[activity] ?: return false
        if (until > SystemClock.uptimeMillis()) return true
        suppressForChatUntil.remove(activity)
        return false
    }

    private fun installDecorBackgroundCleaner(decor: ViewGroup) {
        if (rowCleaners.containsKey(decor)) return
        val cleaner = ViewTreeObserver.OnPreDrawListener {
            if (ThemeWallpaperConfig.isEnabled() && decor.isAttachedToWindow) {
                clearSpecificLauncherBackgrounds(decor, 0)
            }
            true
        }
        decor.viewTreeObserver.addOnPreDrawListener(cleaner)
        rowCleaners[decor] = cleaner
    }

    private fun clearSpecificLauncherBackgrounds(view: View, depth: Int) {
        if (depth > 14) return
        if (view !is ViewGroup) return
        if (isProtectedRow(view) || view.tag == PAGE_BG_TAG) return
        
        val name = view.javaClass.simpleName
        val fullName = view.javaClass.name
        if (name == "FirstScreenFrameLayout" || 
            name == "CustomViewPager" || 
            name == "ConversationListView" || 
            name == "WxRecyclerView" || 
            name == "PullDownListView" ||
            name == "MMWeUIBounceView" ||
            name == "FrostedContentView" ||
            name == "MainUIView" ||
            name == "DynamicBgContainer" ||
            name == "AppBrandDesktopDragView" ||
            name == "GradientColorBackgroundView" ||
            fullName.contains("ConversationListView") ||
            fullName.contains("CustomViewPager") ||
            fullName.contains("FrostedContentView") ||
            fullName.contains("MMWeUIBounceView")) {
            if (view.background != null && view.background !is CenterCropBitmapDrawable) {
                view.background = null
            }
            (view as? android.widget.ListView)?.let { lv ->
                lv.cacheColorHint = Color.TRANSPARENT
                lv.selector = ColorDrawable(Color.TRANSPARENT)
            }
        }
        
        val n = view.childCount.coerceAtMost(60)
        for (i in 0 until n) {
            clearSpecificLauncherBackgrounds(view.getChildAt(i), depth + 1)
        }
    }

    private fun scheduleSpecificClear(decor: ViewGroup) {
        val delays = longArrayOf(150L, 400L, 900L, 2000L)
        for (delay in delays) {
            decor.postDelayed({
                if (ThemeWallpaperConfig.isEnabled() && decor.isAttachedToWindow) {
                    clearSpecificLauncherBackgrounds(decor, 0)
                }
            }, delay)
        }
    }

    private fun installUnderlyingWallpapers(root: ViewGroup, bmp: Bitmap, pathKey: String): Boolean {
        val containers = ArrayList<ViewGroup>()
        val fallbacks = ArrayList<ViewGroup>()
        fun visit(view: View, depth: Int) {
            if (depth > 16 || view !is ViewGroup) return
            val name = view.javaClass.name
            if (name.contains("ChattingUILayout") || name.contains("chatting", ignoreCase = true)) {
                return
            }
            if (name.contains("MainUIView")) {
                containers += view
            } else if (name.contains("MMWeUIBounceView")) {
                containers += view
            } else if (name.contains("FrostedContentView")) {
                containers += view
            }
            val n = view.childCount.coerceAtMost(80)
            for (i in 0 until n) {
                val child = view.getChildAt(i)
                if (child.tag != UNDER_WALLPAPER_TAG) visit(child, depth + 1)
            }
        }
        visit(root, 0)
        if (containers.isEmpty()) containers += fallbacks
        if (containers.isEmpty()) return false
        val alpha = ThemeWallpaperConfig.clampAlpha(ThemeWallpaperConfig.alpha())
        val key = "$pathKey:${ThemeWallpaperConfig.formatAlpha(alpha)}"
        var installed = 0
        for (container in containers.distinct()) {
            val wallpaper = findUnderlyingWallpaper(container) ?: ImageView(container.context).apply {
                tag = UNDER_WALLPAPER_TAG
                setTag(TAG_UNDER_WALLPAPER, true)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                container.addView(this, 0)
            }
            if (wallpaper.getTag(TAG_PATH) != key) {
                wallpaper.setImageBitmap(bmp)
                wallpaper.imageAlpha = (alpha * 255f).toInt().coerceIn(1, 255)
                wallpaper.setTag(TAG_PATH, key)
            }
            wallpaper.visibility = View.VISIBLE
            if (container.indexOfChild(wallpaper) != 0) {
                container.removeView(wallpaper)
                container.addView(wallpaper, 0, wallpaper.layoutParams)
            }
            // 一次性定向清理：只清容器自身与盖在 index0 壁纸之上的列表/滚动外壳背景。
            // 不做父链回溯、不做全树递归、不用持续 OnPreDraw，避免冷启动卡顿与闪退。
            clearUnderlyingBlocking(container)
            container.invalidate()
            installed++
        }
        if (installed > 0) {
            xlog("underlying wallpaper installed containers=$installed")
            // 微信会在触摸/滚动时动态恢复列表实色背景，这里用有限次数的延迟重清理应对，
            // 而不是持续 OnPreDraw（后者在冷启动时会卡顿甚至触发 MIUI 看门狗闪退）。
            scheduleUnderlyingReclear(root, containers)
        }
        return installed > 0
    }

    /** 清理 index0 壁纸之上的遮挡背景：容器自身 + 直接的列表/滚动/弹跳外壳。保护会话卡片与文字行。 */
    private fun clearUnderlyingBlocking(container: ViewGroup) {
        if (container.background != null && container.background !is CenterCropBitmapDrawable) {
            container.background = null
        }
        val n = container.childCount.coerceAtMost(60)
        for (i in 0 until n) {
            val child = container.getChildAt(i)
            if (child.tag == UNDER_WALLPAPER_TAG) continue
            if (isProtectedRow(child)) continue
            clearListShellBackground(child, 0)
        }
    }

    /** 会话卡片/文字行受保护，不被清理背景，保证文字始终有不透明底板。 */
    private fun isProtectedRow(view: View): Boolean {
        if (SwipeDeleteKeepHook.isConversationCard(view)) return true
        if (view.tag == PAGE_BG_TAG) return true
        return runCatching {
            view.getTag(com.OKK.yes.core.R.id.abc_tag_conv_row_styled) == true
        }.getOrDefault(false)
    }

    /** 只清列表外壳（ListView/RecyclerView/Bounce/PullDown/Frosted）的实色背景，避免盖住 index0 壁纸。 */
    private fun clearListShellBackground(view: View, depth: Int) {
        if (depth > 3) return
        if (isProtectedRow(view)) return
        val name = view.javaClass.name
        val isShell = name.contains("ListView") || name.contains("RecyclerView") ||
            name.contains("MMWeUIBounceView") || name.contains("PullDown") ||
            name.contains("FrostedContentView")
        if (isShell && view.background != null && view.background !is CenterCropBitmapDrawable) {
            view.background = null
        }
        // 列表自身的 cacheColorHint / selector 设为透明，防止点击/滚动时实色块盖住壁纸。
        (view as? android.widget.ListView)?.let { lv ->
            runCatching {
                lv.cacheColorHint = Color.TRANSPARENT
                lv.selector = ColorDrawable(Color.TRANSPARENT)
            }
        }
        if (view is ViewGroup) {
            val n = view.childCount.coerceAtMost(40)
            for (i in 0 until n) {
                clearListShellBackground(view.getChildAt(i), depth + 1)
            }
        }
    }

    /** 有限次数的延迟重清理，应对微信延迟恢复背景；不用持续 OnPreDraw 以避免卡顿/闪退。 */
    private fun scheduleUnderlyingReclear(root: ViewGroup, containers: List<ViewGroup>) {
        for (delay in longArrayOf(300L, 900L, 2000L)) {
            root.postDelayed({
                if (!ThemeWallpaperConfig.isEnabled()) return@postDelayed
                for (container in containers) {
                    if (container.isAttachedToWindow) {
                        clearUnderlyingBlocking(container)
                        container.invalidate()
                    }
                }
            }, delay)
        }
    }

    /** 收集 decor 下所有底层壁纸 ImageView，用于实时调透明度。 */
    private fun findUnderlyingWallpapers(root: ViewGroup): List<ImageView> {
        val out = ArrayList<ImageView>()
        fun visit(v: View, depth: Int) {
            if (depth > 16 || v !is ViewGroup) return
            val n = v.childCount.coerceAtMost(80)
            for (i in 0 until n) {
                val c = v.getChildAt(i)
                if (c is ImageView && c.tag == UNDER_WALLPAPER_TAG) out += c
                else visit(c, depth + 1)
            }
        }
        visit(root, 0)
        return out
    }

    private fun findUnderlyingWallpaper(parent: ViewGroup): ImageView? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is ImageView && child.tag == UNDER_WALLPAPER_TAG) return child
        }
        return null
    }

    private fun removeUnderlyingWallpapers(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        fun visit(view: View) {
            if (view !is ViewGroup) return
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child is ImageView && child.tag == UNDER_WALLPAPER_TAG) {
                    child.setImageDrawable(null)
                    view.removeViewAt(i)
                } else {
                    visit(child)
                }
            }
        }
        visit(decor)
    }

    private fun applyMainTabWallpaper(root: ViewGroup, bmp: Bitmap, pathKey: String): Boolean {
        val pages = findMainTabPages(root)
        if (pages.isEmpty()) return false
        val alpha = ThemeWallpaperConfig.clampAlpha(ThemeWallpaperConfig.alpha())
        val key = "$pathKey:${ThemeWallpaperConfig.formatAlpha(alpha)}"
        var applied = 0
        for (page in pages) {
            if (page.getTag(TAG_PAGE_PATH) != key) {
                page.background = CenterCropBitmapDrawable(bmp, (alpha * 255f).toInt().coerceIn(1, 255))
                page.setTag(TAG_PAGE_PATH, key)
            }
            findScrollableContent(page)?.let { scroll ->
                scroll.background = null
            }
            page.invalidate()
            applied++
        }
        if (applied > 0) xlog("main tab wallpaper applied pages=$applied")
        return applied > 0
    }

    private fun findMainTabPages(root: ViewGroup): List<ViewGroup> {
        val result = ArrayList<ViewGroup>()
        fun visit(view: View, depth: Int) {
            if (depth > 14 || view !is ViewGroup) return
            val name = view.javaClass.name
            if (name.contains("FrostedContentView") && findScrollableContent(view) != null) {
                result += view
                return
            }
            val n = view.childCount.coerceAtMost(80)
            for (i in 0 until n) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        return result
    }

    private fun findScrollableContent(root: ViewGroup): ViewGroup? {
        fun visit(view: View, depth: Int): ViewGroup? {
            if (depth > 10 || view !is ViewGroup) return null
            val name = view.javaClass.name
            if (name.contains("RecyclerView") || name.contains("ListView")) return view
            val n = view.childCount.coerceAtMost(80)
            for (i in 0 until n) {
                visit(view.getChildAt(i), depth + 1)?.let { return it }
            }
            return null
        }
        return visit(root, 0)
    }

    private fun installRowBackgroundCleaner(scroll: ViewGroup) {
        if (rowCleaners.containsKey(scroll)) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            cleanVisibleRows(scroll)
            true
        }
        rowCleaners[scroll] = listener
        runCatching { scroll.viewTreeObserver.addOnPreDrawListener(listener) }
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                rowCleaners.remove(scroll)?.let { l ->
                    runCatching {
                        if (scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnPreDrawListener(l)
                    }
                }
            }
        })
    }

    private fun cleanVisibleRows(scroll: ViewGroup) {
        val n = scroll.childCount.coerceAtMost(120)
        for (i in 0 until n) {
            val row = scroll.getChildAt(i)
            makeConversationRowTransparent(row, 0)
        }
        scroll.invalidate()
    }

    private fun makeConversationRowTransparent(view: View, depth: Int) {
        if (depth > 3) return
        if (view is TextView || view is ImageView || view.tag == PAGE_BG_TAG || SwipeDeleteKeepHook.isConversationCard(view)) return
        if (view is ViewGroup) {
            view.background = null
            val n = view.childCount.coerceAtMost(40)
            for (i in 0 until n) makeConversationRowTransparent(view.getChildAt(i), depth + 1)
        }
    }

    private fun applyLauncherWallpaperOnly(activity: Activity, bmp: Bitmap, pathKey: String): Boolean {
        // 与 applyLauncherContainerWallpaper 不同：只把壁纸铺到首页根容器最底层 background，
        // 绝不清理任何文字/卡片背景，保证字体固定清晰、不受壁纸透明度影响。
        val root = findLauncherHomeRoot(activity) ?: run {
            if (!launcherRootMissLogged) {
                launcherRootMissLogged = true
                xlog("home container not found k7n=$launcherHomeRootId o7q=$launcherPrepareViewId")
            }
            return false
        }
        val alpha = ThemeWallpaperConfig.clampAlpha(ThemeWallpaperConfig.alpha())
        val key = "$pathKey:${ThemeWallpaperConfig.formatAlpha(alpha)}"
        if (root.foreground != null) root.foreground = null
        if (root.getTag(TAG_HOME_PATH) != key) {
            val drawable = CenterCropBitmapDrawable(bmp, (alpha * 255f).toInt().coerceIn(1, 255))
            root.background = drawable
            root.foreground = null
            root.setTag(TAG_HOME_PATH, key)
            xlog("home wallpaper only applied ${activity.javaClass.simpleName} root=${root.javaClass.name}")
        }
        val cleared = makeWallpaperVisibleBehind(root)
        val pages = applyPageWallpapers(root, bmp, key)
        if (cleared > 0 || pages > 0) {
            xlog("home bottom wallpaper visible cleared=$cleared pages=$pages root=${root.javaClass.name}")
        }
        root.invalidate()
        return true
    }

    private fun applyLauncherContainerWallpaper(activity: Activity, bmp: Bitmap, pathKey: String): Boolean {
        val root = findLauncherHomeRoot(activity) ?: run {
            if (!launcherRootMissLogged) {
                launcherRootMissLogged = true
                xlog("home container not found k7n=$launcherHomeRootId o7q=$launcherPrepareViewId")
            }
            return false
        }
        val alpha = ThemeWallpaperConfig.clampAlpha(ThemeWallpaperConfig.alpha())
        val key = "$pathKey:${ThemeWallpaperConfig.formatAlpha(alpha)}"
        if (root.foreground != null) root.foreground = null
        if (root.getTag(TAG_HOME_PATH) != key) {
            val drawable = CenterCropBitmapDrawable(bmp, (alpha * 255f).toInt().coerceIn(1, 255))
            root.background = drawable
            root.foreground = null
            root.setTag(TAG_HOME_PATH, key)
            xlog("home container wallpaper applied ${activity.javaClass.simpleName} root=${root.javaClass.name}")
        }
        makeWallpaperVisibleBehind(root)
        val pages = applyPageWallpapers(root, bmp, key)
        if (pages > 0) {
            xlog("page wallpaper applied count=$pages root=${root.javaClass.name}")
        }
        root.invalidate()
        return true
    }

    private fun applyPageWallpapers(root: View, bmp: Bitmap, key: String): Int {
        if (root !is ViewGroup) return 0
        var count = 0
        fun visit(view: View, depth: Int) {
            if (depth > 16) return
            if (view is ViewGroup) {
                val name = view.javaClass.name
                if (name.contains("FrostedContentView") ||
                    name.contains("MMWeUIBounceView")
                ) {
                    if (installPageWallpaper(view, bmp, key)) count++
                }
                val n = view.childCount.coerceAtMost(64)
                for (i in 0 until n) {
                    val child = view.getChildAt(i)
                    if (child.tag == PAGE_BG_TAG) continue
                    visit(child, depth + 1)
                }
            }
        }
        visit(root, 0)
        return count
    }

    private fun installPageWallpaper(parent: ViewGroup, bmp: Bitmap, key: String): Boolean {
        val alpha = ThemeWallpaperConfig.clampAlpha(ThemeWallpaperConfig.alpha())
        val pageKey = "$key:${ThemeWallpaperConfig.formatAlpha(alpha)}"
        if (parent.getTag(TAG_PAGE_PATH) != pageKey) {
            parent.background = CenterCropBitmapDrawable(bmp, (alpha * 255f).toInt().coerceIn(1, 255))
            parent.setTag(TAG_PAGE_PATH, pageKey)
        }
        val bg = findPageWallpaper(parent) ?: ImageView(parent.context).apply {
            tag = PAGE_BG_TAG
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.CENTER_CROP
            parent.addView(
                this,
                0,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        if (bg.getTag(TAG_PAGE_PATH) != pageKey) {
            bg.setImageBitmap(bmp)
            bg.imageAlpha = (alpha * 255f).toInt().coerceIn(1, 255)
            bg.setTag(TAG_PAGE_PATH, pageKey)
        }
        bg.visibility = View.VISIBLE
        if (parent.indexOfChild(bg) != 0) {
            parent.removeView(bg)
            parent.addView(
                bg,
                0,
                bg.layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        val cleared = makeWallpaperVisibleAbove(bg, parent)
        val rowCleared = clearListItemBackgrounds(parent)
        scheduleWallpaperRevealRefresh(parent)
        if (cleared > 0) {
            xlog("page wallpaper cleared backgrounds=$cleared parent=${parent.javaClass.name}")
        }
        if (rowCleared > 0) {
            xlog("page wallpaper cleared list rows=$rowCleared parent=${parent.javaClass.name}")
        }
        return true
    }

    private fun scheduleWallpaperRevealRefresh(parent: ViewGroup) {
        val refresh = Runnable {
            clearListItemBackgrounds(parent)
            makeWallpaperVisibleAbove(findPageWallpaper(parent) ?: parent, parent)
            invalidateTree(parent, 0)
        }
        parent.post(refresh)
        parent.postDelayed(refresh, 120L)
        parent.postDelayed(refresh, 360L)
        parent.postDelayed(refresh, 900L)
    }

    private fun invalidateTree(view: View, depth: Int) {
        if (depth > 8) return
        view.invalidate()
        if (view is ViewGroup) {
            val n = view.childCount.coerceAtMost(80)
            for (i in 0 until n) invalidateTree(view.getChildAt(i), depth + 1)
        }
    }

    private fun findPageWallpaper(parent: ViewGroup): ImageView? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is ImageView && child.tag == PAGE_BG_TAG) return child
        }
        return null
    }

    private fun makeWallpaperVisibleBehind(root: View): Int {
        if (root !is ViewGroup) return 0
        var cleared = 0
        fun visit(view: View, depth: Int) {
            if (depth > 10) return
            if (view !== root && view is ViewGroup && shouldClearBackground(view)) {
                view.background = null
                cleared++
            }
            if (view is ViewGroup) {
                val n = view.childCount.coerceAtMost(80)
                for (i in 0 until n) {
                    visit(view.getChildAt(i), depth + 1)
                }
            }
        }
        visit(root, 0)
        return cleared
    }

    private fun makeWallpaperVisibleAbove(bg: View, parent: ViewGroup): Int {
        var cleared = 0
        var start = parent.indexOfChild(bg) + 1
        if (start < 0) start = 0
        val end = parent.childCount.coerceAtMost(80)
        for (i in start until end) {
            cleared += clearBlockingBackgrounds(parent.getChildAt(i), 0)
        }
        return cleared
    }

    private fun clearBlockingBackgrounds(view: View, depth: Int): Int {
        if (depth > 10) return 0
        if (view.tag == PAGE_BG_TAG || SwipeDeleteKeepHook.isConversationCard(view)) return 0
        var cleared = 0
        if (view is ViewGroup && shouldClearBackground(view)) {
            view.background = null
            cleared++
        } else if (view !is TextView && view !is ImageView && view.background != null) {
            // 普通 View (如分割线、占位层等) 如果也是实色背景，清除以防遮挡
            val bg = view.background
            if (bg !is CenterCropBitmapDrawable) {
                view.background = null
                cleared++
            }
        }
        if (view is ViewGroup) {
            val n = view.childCount.coerceAtMost(80)
            for (i in 0 until n) {
                cleared += clearBlockingBackgrounds(view.getChildAt(i), depth + 1)
            }
        }
        return cleared
    }

    private fun applyReadableConversationRows(root: View): Int {
        if (root !is ViewGroup) return 0
        val night = (root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val color = if (night) 0xFF1E1E1E.toInt() else Color.WHITE
        val density = root.resources.displayMetrics.density
        val radius = 16f * density
        var styled = 0

        fun hasConversationText(view: View, depth: Int): Boolean {
            if (depth > 4) return false
            if (view is TextView && view.text?.isNotBlank() == true) return true
            if (view !is ViewGroup) return false
            for (i in 0 until view.childCount.coerceAtMost(32)) {
                if (hasConversationText(view.getChildAt(i), depth + 1)) return true
            }
            return false
        }

        fun visit(view: View, depth: Int) {
            if (depth > 12 || view !is ViewGroup) return
            val name = view.javaClass.name
            if (name.contains("RecyclerView") || name.contains("ListView")) {
                val count = view.childCount.coerceAtMost(80)
                for (i in 0 until count) {
                    val row = view.getChildAt(i)
                    val heightDp = if (row.height > 0) row.height / density else 0f
                    val rowName = row.javaClass.name
                    val excluded = rowName.contains("Header") || rowName.contains("Tabs") ||
                        rowName.contains("AppBrand") || rowName.contains("PullDown") ||
                        rowName.contains("Banner") || rowName.contains("Search")
                    if (!excluded && heightDp in 45f..110f && hasConversationText(row, 0)) {
                        row.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = radius
                            setColor(color)
                        }
                        styled++
                    }
                }
            }
            for (i in 0 until view.childCount.coerceAtMost(80)) visit(view.getChildAt(i), depth + 1)
        }

        visit(root, 0)
        return styled
    }

    private fun clearListItemBackgrounds(root: View): Int {
        if (root !is ViewGroup) return 0
        var cleared = 0
        fun clearRow(row: View, depth: Int) {
            if (depth > 4) return
            if (row is TextView || row is ImageView || row.tag == PAGE_BG_TAG || SwipeDeleteKeepHook.isConversationCard(row)) return
            if (row.background != null) {
                row.background = null
                cleared++
            }
            if (row is ViewGroup) {
                val n = row.childCount.coerceAtMost(32)
                for (i in 0 until n) clearRow(row.getChildAt(i), depth + 1)
            }
        }
        fun visit(view: View, depth: Int) {
            if (depth > 12) return
            if (view is ViewGroup) {
                val name = view.javaClass.name
                if (name.contains("RecyclerView") || name.contains("ListView")) {
                    if (view.background != null) {
                        view.background = null
                        cleared++
                    }
                    val n = view.childCount.coerceAtMost(80)
                    for (i in 0 until n) {
                        val child = view.getChildAt(i)
                        val dm = child.resources.displayMetrics
                        if (child.width >= dm.widthPixels / 2 && child.height in 1..(dm.heightPixels / 3)) {
                            clearRow(child, 0)
                        }
                    }
                }
                val n = view.childCount.coerceAtMost(80)
                for (i in 0 until n) visit(view.getChildAt(i), depth + 1)
            }
        }
        visit(root, 0)
        return cleared
    }

    private fun shouldClearBackground(view: View): Boolean {
        if (view is TextView) return false
        if (view is ImageView) return false
        val drawable = view.background ?: return false
        if (drawable is CenterCropBitmapDrawable) return false
        // 只要是 ViewGroup 统统允许清空，防止微信内部任意层级的中间布局阻断了壁纸透出
        return view is ViewGroup
    }

    private fun applyLauncherDecorWallpaper(activity: Activity, bmp: Bitmap, pathKey: String) {
        val decor = activity.window?.decorView ?: return
        val alpha = ThemeWallpaperConfig.clampAlpha(ThemeWallpaperConfig.alpha())
        val key = "$pathKey:${ThemeWallpaperConfig.formatAlpha(alpha)}"
        if (decor.getTag(TAG_DECOR_PATH) == key && decor.background is CenterCropBitmapDrawable) return
        decor.background = CenterCropBitmapDrawable(bmp, (alpha * 255f).toInt().coerceIn(1, 255))
        decor.setTag(TAG_DECOR_PATH, key)
        xlog("launcher decor wallpaper applied ${activity.javaClass.simpleName}")
    }

    private fun applyDecorWallpaper(activity: Activity, bmp: Bitmap, pathKey: String) {
        val decor = activity.window?.decorView ?: return
        val alpha = ThemeWallpaperConfig.clampAlpha(ThemeWallpaperConfig.alpha())
        val key = "$pathKey:${ThemeWallpaperConfig.formatAlpha(alpha)}"
        if (decor.getTag(TAG_DECOR_PATH) == key) return
        decor.background = CenterCropBitmapDrawable(bmp, (alpha * 255f).toInt().coerceIn(1, 255))
        decor.setTag(TAG_DECOR_PATH, key)
        xlog("decor wallpaper applied ${activity.javaClass.simpleName}")
    }

    private fun clearLauncherContainerWallpaper(activity: Activity) {
        if (!activity.javaClass.name.contains("LauncherUI")) return
        val root = findLauncherHomeRoot(activity) ?: return
        if (root.getTag(TAG_HOME_PATH) != null) {
            root.background = null
            root.foreground = null
            root.setTag(TAG_HOME_PATH, null)
        }
        clearPageWallpapers(root)
    }

    private fun clearPageWallpapers(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        detachRowCleaners()
        clearPageWallpapers(decor)
    }

    private fun detachRowCleaners() {
        for ((scroll, listener) in rowCleaners.entries.toList()) {
            runCatching {
                if (scroll.viewTreeObserver.isAlive) {
                    scroll.viewTreeObserver.removeOnPreDrawListener(listener)
                }
            }
        }
        rowCleaners.clear()
    }

    private fun clearPageWallpapers(root: View) {
        if (root !is ViewGroup) return
        if (root.getTag(TAG_PAGE_PATH) != null) {
            root.background = null
            root.setTag(TAG_PAGE_PATH, null)
        }
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child is ImageView && child.tag == PAGE_BG_TAG) {
                child.setImageDrawable(null)
                root.removeViewAt(i)
            } else {
                clearPageWallpapers(child)
            }
        }
    }

    private fun clearLauncherDecorWallpaper(activity: Activity) {
        if (!activity.javaClass.name.contains("LauncherUI")) return
        val decor = activity.window?.decorView ?: return
        if (decor.getTag(TAG_DECOR_PATH) != null) {
            decor.background = null
            decor.setTag(TAG_DECOR_PATH, null)
        }
    }

    private fun disableLauncherPrepareView(activity: Activity) {
        if (!activity.javaClass.name.contains("LauncherUI")) return
        val prepareId = resolveWechatId(activity, "o7q").also { launcherPrepareViewId = it }
        if (prepareId == 0) return
        val prepare = activity.findViewById<View>(prepareId) ?: return
        runCatching {
            (prepare as? ImageView)?.setImageDrawable(null)
            prepare.visibility = View.GONE
            (prepare.tag as? View)?.let { home ->
                home.visibility = View.VISIBLE
                home.invalidate()
            }
            xlog("launcher prepareView cleared")
        }
    }

    private fun findLauncherHomeRoot(activity: Activity): View? {
        val homeId = resolveWechatId(activity, "k7n").also { launcherHomeRootId = it }
        if (homeId != 0) {
            activity.findViewById<View>(homeId)?.let { return it }
        }

        val prepareId = resolveWechatId(activity, "o7q").also { launcherPrepareViewId = it }
        if (prepareId != 0) {
            val prepareView = activity.findViewById<View>(prepareId)
            (prepareView?.tag as? View)?.let { return it }
        }
        return null
    }

    private fun resolveWechatId(activity: Activity, name: String): Int {
        return runCatching {
            val clazz = Class.forName("com.tencent.mm.R\$id", false, activity.classLoader)
            clazz.getDeclaredField(name).getInt(null)
        }.getOrElse {
            activity.resources.getIdentifier(name, "id", "com.tencent.mm")
        }
    }

    private fun createOverlay(activity: Activity, decor: ViewGroup): ImageView {
        return ImageView(activity).apply {
            tag = OVERLAY_TAG
            background = null
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.CENTER_CROP
            // 关键事件穿透：不消费 touch
            setOnTouchListener { _, _ -> false }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            elevation = ELEVATION
            translationZ = ELEVATION
            // 追加到末尾（不要 index=0）
            decor.addView(this)
            xlog("overlay created ${activity.javaClass.simpleName} children=${decor.childCount}")
        }
    }

    private fun keepFront(activity: Activity, overlay: ImageView) {
        runCatching {
            val parent = overlay.parent as? ViewGroup ?: return
            // 移到最后一个 child
            val last = parent.childCount - 1
            if (parent.indexOfChild(overlay) != last && last >= 0) {
                parent.removeView(overlay)
                parent.addView(overlay)
            } else {
                parent.bringChildToFront(overlay)
            }
            overlay.elevation = ELEVATION
            overlay.translationZ = ELEVATION
            overlay.bringToFront()
            // 悬浮底栏等可能 elevation 很高，再抬一次
            overlay.invalidate()
        }
    }

    /** 布局变化时持续置顶，对抗微信/莫奈/悬浮底栏后续 addView */
    private fun installKeeper(activity: Activity, decor: ViewGroup, overlay: ImageView) {
        keepers[activity]?.detach()
        val keeper = KeepOnTop(activity, decor, overlay)
        keepers[activity] = keeper
        keeper.attach()
    }

    private class KeepOnTop(
        private val activityRef: WeakReference<Activity>,
        private val decor: ViewGroup,
        private val overlay: ImageView
    ) : ViewTreeObserver.OnPreDrawListener, ViewGroup.OnHierarchyChangeListener {

        constructor(activity: Activity, decor: ViewGroup, overlay: ImageView) : this(
            WeakReference(activity), decor, overlay
        )

        private var attached = false

        fun attach() {
            if (attached) return
            attached = true
            runCatching {
                decor.viewTreeObserver.addOnPreDrawListener(this)
                decor.setOnHierarchyChangeListener(this)
            }
        }

        fun detach() {
            attached = false
            runCatching {
                if (decor.viewTreeObserver.isAlive) {
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                }
                decor.setOnHierarchyChangeListener(null)
            }
        }

        override fun onPreDraw(): Boolean {
            val act = activityRef.get()
            if (act == null || act.isFinishing || !ThemeWallpaperConfig.isEnabled()) {
                return true
            }
            if (act.javaClass.name.contains("LauncherUI") && isForceVisible(act)) {
                if (overlay.parent !== decor) return true
                showOverlay(act, overlay)
                return true
            }
            // 内嵌聊天则隐藏
            if (act.javaClass.name.contains("LauncherUI") && isChattingForeground(act)) {
                hideOverlay(act)
                return true
            }
            if (overlay.parent !== decor) return true
            showOverlay(act, overlay)
            // 仅当不在最顶时调整，减少开销
            val last = decor.childCount - 1
            if (last >= 0 && decor.getChildAt(last) !== overlay) {
                keepFront(act, overlay)
            }
            return true
        }

        override fun onChildViewAdded(parent: View?, child: View?) {
            if (child === overlay) return
            val act = activityRef.get() ?: return
            if (!ThemeWallpaperConfig.isEnabled()) return
            mainHandler.post {
                if (!act.isFinishing && (isForceVisible(act) || !isChattingForeground(act))) keepFront(act, overlay)
            }
        }

        override fun onChildViewRemoved(parent: View?, child: View?) {}
    }

    private fun removeLauncherOverlay(activity: Activity, decor: ViewGroup) {
        keepers.remove(activity)?.detach()
        overlays.remove(activity)?.get()?.let { overlay ->
            overlay.animate().cancel()
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        clearExistingOverlay(decor)
    }

    private fun findOverlay(decor: ViewGroup): ImageView? {
        for (i in 0 until decor.childCount) {
            val c = decor.getChildAt(i)
            if (c is ImageView && (c.tag == OVERLAY_TAG || c.tag == "achat_global_bg_overlay" ||
                    c.tag == "achat_global_bg_overlay_v4")
            ) {
                return c
            }
        }
        return null
    }

    private fun clearExistingOverlay(decor: ViewGroup) {
        for (i in decor.childCount - 1 downTo 0) {
            val c = decor.getChildAt(i)
            if (c is ImageView && (c.tag == OVERLAY_TAG || c.tag == "achat_global_bg_overlay" ||
                    c.tag == "achat_global_bg_overlay_v4")
            ) {
                c.animate().cancel()
                decor.removeViewAt(i)
            }
        }
    }

    private fun showOverlay(activity: Activity, overlay: ImageView) {
        val target = ThemeWallpaperConfig.clampAlpha(ThemeWallpaperConfig.alpha())
        overlay.animate().cancel()
        overlay.alpha = target
        overlay.visibility = View.VISIBLE
        keepFront(activity, overlay)
    }

    private fun hideOverlay(activity: Activity) {
        runCatching {
            val overlay = overlays[activity]?.get() ?: return
            overlay.animate().cancel()
            overlay.alpha = 0f
            overlay.visibility = View.GONE
        }
    }

    private fun hideAll() {
        for (a in tracked.keys.toList()) {
            clearLauncherDecorWallpaper(a)
            clearLauncherContainerWallpaper(a)
            clearPageWallpapers(a)
            hideOverlay(a)
        }
    }

    private fun writeDebug(msg: String) {
        runCatching {
            File(
                "/storage/emulated/0/Android/media/com.tencent.mm/OKK",
                "theme_debug.txt"
            ).writeText("${System.currentTimeMillis()}\n$msg\n")
        }
        xlog(msg)
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("$TAG: $msg") }
    }

    private class CenterCropBitmapDrawable(
        private val bitmap: Bitmap,
        initialAlpha: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = initialAlpha
        }
        private val dst = RectF()

        override fun draw(canvas: Canvas) {
            if (bitmap.isRecycled) return
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
            val scale = maxOf(
                b.width().toFloat() / bitmap.width.toFloat(),
                b.height().toFloat() / bitmap.height.toFloat()
            )
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = b.left + (b.width() - w) / 2f
            val top = b.top + (b.height() - h) / 2f
            dst.set(left, top, left + w, top + h)
            val save = canvas.save()
            canvas.clipRect(b)
            canvas.drawBitmap(bitmap, null, dst, paint)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
