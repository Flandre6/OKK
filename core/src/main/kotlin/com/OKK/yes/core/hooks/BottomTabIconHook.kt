package com.OKK.yes.core.hooks

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 微信首页底栏：只隐藏标题文字。
 *
 * 注意：禁止在 hide 路径里再调用 setText/setVisibility 触发已 hook 方法，
 * 否则会递归把微信卡死。
 */
object BottomTabIconHook {
    private const val TAG = "OKK-BottomTab"
    private const val BOTTOM_TAB_CLASS = "com.tencent.mm.ui.LauncherUIBottomTabView"

    private val installed = AtomicBoolean(false)
    private val applying = AtomicBoolean(false)
    private val trackedBars: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val titleViews: MutableSet<TextView> = Collections.newSetFromMap(WeakHashMap())
    private val hideLogCount = AtomicInteger(0)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install hideTitle=${BottomTabConfig.hideTitle()}")
        hookBottomTabContainer(classLoader)
    }

    private fun hookBottomTabContainer(classLoader: ClassLoader) {
        val clazz = runCatching {
            XposedHelpers.findClass(BOTTOM_TAB_CLASS, classLoader)
        }.getOrNull()
        if (clazz == null) {
            xlog("class not found: $BOTTOM_TAB_CLASS")
            return
        }

        clazz.declaredConstructors.forEach { ctor ->
            runCatching {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val bar = param.thisObject as? ViewGroup ?: return
                        scheduleHide(bar)
                    }
                })
            }
        }

        // 仅 hook 底栏类自己的 onAttachedToWindow，避免全局 View 钩子
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val bar = param.thisObject as? ViewGroup ?: return
                        scheduleHide(bar)
                    }
                }
            )
        }.onFailure {
            // 若未 override，回退到 View.onAttachedToWindow 但严格过滤类名
            runCatching {
                XposedHelpers.findAndHookMethod(
                    View::class.java,
                    "onAttachedToWindow",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val v = param.thisObject as? ViewGroup ?: return
                            if (!isBottomTabClass(v.javaClass)) return
                            scheduleHide(v)
                        }
                    }
                )
            }
        }

        xlog("hooked $BOTTOM_TAB_CLASS constructors/attach")
    }

    private fun scheduleHide(bar: ViewGroup) {
        if (!BottomTabConfig.hideTitle()) return
        synchronized(trackedBars) {
            if (!trackedBars.add(bar)) return
        }
        bar.post {
            if (BottomTabConfig.hideTitle()) hideTitlesOn(bar)
        }
        // 只延迟一次再扫（原先 0/300/1000 三次全树遍历会卡主线程）
        bar.postDelayed({
            if (BottomTabConfig.hideTitle()) hideTitlesOn(bar)
        }, 400L)
    }

    private fun hideTitlesOn(bar: ViewGroup) {
        if (!BottomTabConfig.hideTitle()) return
        if (!applying.compareAndSet(false, true)) return
        try {
            var count = 0

            // 1) resource id = icon_tv
            walk(bar) { view ->
                if (view !is TextView) return@walk
                val entry = resourceEntry(view)
                if (entry == "icon_tv" && BottomTabIconLogic.isDefaultTabTitle(view.text)) {
                    if (hideTitleView(view)) count++
                }
            }

            // 日志降频：原先每次 hide 都 XposedBridge.log，设置页开关时刷爆主线程
            val n = hideLogCount.incrementAndGet()
            if (count > 0 && n <= 3) {
                xlog("hideTitles count=$count (#$n)")
            }
        } catch (t: Throwable) {
            xlog("hideTitles error: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            applying.set(false)
        }
    }

    /**
     * 只改 visibility / alpha，**不要** setText（会 re-enter hook 导致栈溢出）。
     */
    private fun hideTitleView(tv: TextView): Boolean {
        if (!isBottomAreaText(tv)) return false
        if (tv.visibility == View.GONE && tv.alpha == 0f) {
            titleViews.add(tv)
            return false
        }
        titleViews.add(tv)
        return try {
            tv.visibility = View.GONE
            tv.alpha = 0f
            tv.isClickable = false
            tv.isFocusable = false
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isBottomAreaText(tv: TextView): Boolean {
        if (!tv.isShown && tv.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        return runCatching {
            tv.getLocationOnScreen(location)
            val screenHeight = tv.resources.displayMetrics.heightPixels
            val y = location[1]
            val h = if (tv.height > 0) tv.height else tv.measuredHeight
            y > screenHeight * 0.88f && h in 1..(screenHeight * 0.08f).toInt()
        }.getOrDefault(false)
    }

    private fun isBottomTabClass(clazz: Class<*>): Boolean {
        var c: Class<*>? = clazz
        while (c != null) {
            if (c.name == BOTTOM_TAB_CLASS) return true
            c = c.superclass
        }
        return false
    }

    private fun resourceEntry(view: View): String {
        if (view.id == View.NO_ID) return ""
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
    }

    private fun walk(root: View, block: (View) -> Unit) {
        block(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                walk(root.getChildAt(i), block)
            }
        }
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
