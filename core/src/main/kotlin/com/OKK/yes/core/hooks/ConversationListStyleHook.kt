package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import com.OKK.yes.core.R
import com.OKK.yes.core.compat.DexKitSupport
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话列表卡片美化 Hook（完全对齐 release 稳定版 xy/q92 架构）。
 */
object ConversationListStyleHook {
    private const val TAG = "OKK-ConvListStyle"

    const val KEY_ENABLED = "conv_card_enabled"
    const val KEY_INSET_DP = "conv_card_inset_dp"
    const val KEY_CORNER_DP = "conv_card_corner_dp"

    private const val DEFAULT_INSET_DP = 10
    private const val DEFAULT_CORNER_DP = 12

    private val installed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedAdapters = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var activeListViewRef: WeakReference<ListView>? = null

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install start")

        for (hostName in listOf("com.tencent.mm.ui.conversation.MainUI", "com.tencent.mm.ui.LauncherUI")) {
            runCatching {
                val clazz = classLoader.loadClass(hostName)
                XposedHelpers.findAndHookMethod(clazz, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val host = param.thisObject ?: return
                        mainHandler.post { applyStyleFromHost(host) }
                    }
                })
                xlog("hooked $hostName.onResume")
            }
        }

        for (listName in listOf("com.tencent.mm.ui.conversation.ConversationListView", "com.tencent.mm.ui.conversation.ConversationWithAppBrandListView")) {
            runCatching {
                val clazz = classLoader.loadClass(listName)
                XposedHelpers.findAndHookMethod(clazz, "onAttachedToWindow", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.thisObject as? ListView ?: return
                        applyStyleToListView(list)
                    }
                })
                XposedHelpers.findAndHookMethod(clazz, "setAdapter", ListAdapter::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.thisObject as? ListView ?: return
                        applyStyleToListView(list)
                    }
                })
                val touchHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args.getOrNull(0) as? MotionEvent ?: return
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            val list = param.thisObject as? ListView
                            if (list != null && list.getTag(R.id.abc_tag_conv_list_styled) != true) {
                                applyStyleToListView(list)
                            }
                        }
                    }
                }
                runCatching { XposedHelpers.findAndHookMethod(clazz, "dispatchTouchEvent", MotionEvent::class.java, touchHook) }
                runCatching { XposedHelpers.findAndHookMethod(clazz, "onTouchEvent", MotionEvent::class.java, touchHook) }
                xlog("hooked $listName onAttached & setAdapter")
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(ListView::class.java, "layoutChildren", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.thisObject as? ListView ?: return
                    if (isConversationListView(list)) {
                        val ctx = list.context ?: return
                        val enabled = isEnabled()
                        val density = ctx.resources.displayMetrics.density
                        decorateExistingChildren(list, enabled, ctx, density)
                    }
                }
            })
        }

        // 挂钩 ConversationWithCacheAdapter 与 MvvmConversationAdapter
        runCatching {
            val b2 = DexKitSupport.findClassByStrings(context, classLoader, modulePath, "MicroMsg.ConversationWithCacheAdapter", "[getView] position=")
            if (b2 != null) hookAdapter(b2)
        }
        runCatching {
            val b3 = DexKitSupport.findClassByStrings(context, classLoader, modulePath, "MicroMsg.ConversationAdapter.MvvmConversationAdapter")
            if (b3 != null) hookAdapter(b3)
        }

        val refresh: (String) -> Unit = {
            mainHandler.post { applyToActiveList() }
        }
        PublicConfigStore.addListener(KEY_ENABLED, refresh)
        PublicConfigStore.addListener(KEY_INSET_DP, refresh)
        PublicConfigStore.addListener(KEY_CORNER_DP, refresh)

        xlog("install done")
    }

    fun isEnabled(): Boolean = PublicConfigStore.getBoolean(KEY_ENABLED, true)
    fun getInsetDp(): Int = PublicConfigStore.getInt(KEY_INSET_DP, DEFAULT_INSET_DP).coerceIn(2, 24)
    fun getCornerDp(): Int = PublicConfigStore.getInt(KEY_CORNER_DP, DEFAULT_CORNER_DP).coerceIn(0, 24)

    private fun hookAdapter(adapterClazz: Class<*>) {
        if (!hookedAdapters.add(adapterClazz.name)) return
        val getView = adapterClazz.declaredMethods.firstOrNull { m ->
            m.name == "getView" && m.parameterTypes.size == 3
        } ?: adapterClazz.methods.firstOrNull { m ->
            m.name == "getView" && m.parameterTypes.size == 3
        } ?: return

        runCatching {
            getView.isAccessible = true
            XposedBridge.hookMethod(getView, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.result as? View ?: return
                    val pos = param.args.getOrNull(0) as? Int ?: 0
                    val item = runCatching {
                        val getItemMethod = param.thisObject.javaClass.methods.firstOrNull { it.name == "getItem" && it.parameterCount == 1 }
                            ?: param.thisObject.javaClass.getDeclaredMethod("getItem", Int::class.javaPrimitiveType)
                        getItemMethod?.isAccessible = true
                        getItemMethod?.invoke(param.thisObject, pos)
                    }.getOrNull()

                    val isPinned = if (item != null) SwipeDeleteKeepHook.isPinnedConversation(item) else false
                    xlog("hookAdapter after getView: pos=$pos itemClass=${item?.javaClass?.name} isPinned=$isPinned")
                    onAdapterGetView(view, item)
                }
            })
            xlog("hooked adapter getView: ${adapterClazz.name}")
        }
    }

    private fun onAdapterGetView(view: View, conversationItem: Any?) {
        val context = view.context ?: return
        val activityName = getActivityName(context)
        if (isIgnoredActivity(activityName)) return

        val parent = view.parent as? ListView
        if (parent == null || isConversationListView(parent)) {
            if (conversationItem != null) {
                val isPinned = SwipeDeleteKeepHook.isPinnedConversation(conversationItem)
                view.setTag(SwipeDeleteKeepHook.VIEW_TAG_PINNED, isPinned)
            }
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            styleConversationRow(view, isEnabled(), night, context.resources.displayMetrics.density)
        }
    }

    fun applyStyleToListView(list: ListView) {
        val context = list.context ?: return
        val activityName = getActivityName(context)
        if (isIgnoredActivity(activityName)) return

        val name = list.javaClass.name
        if (name.contains("ConversationListView", ignoreCase = true) || name.contains("ConversationWithAppBrandListView", ignoreCase = true)) {
            activeListViewRef = WeakReference(list)
            val enabled = isEnabled()
            val density = context.resources.displayMetrics.density
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val parseColor = if (night) Color.parseColor("#111111") else Color.parseColor("#EDEDED")

            if (list.paddingLeft != 0 || list.paddingRight != 0) {
                list.setPadding(0, list.paddingTop, 0, list.paddingBottom)
            }
            list.isScrollingCacheEnabled = false
            list.selector = ColorDrawable(0)
            list.overScrollMode = View.OVER_SCROLL_NEVER

            if (isThemeWallpaperActive()) {
                list.background = null
                list.cacheColorHint = 0
            } else {
                list.setBackgroundColor(parseColor)
                list.cacheColorHint = parseColor
                val activity = getActivity(context)
                activity?.window?.decorView?.setBackgroundColor(parseColor)
            }

            if (list.getTag(R.id.abc_tag_conv_item_card) != true) {
                list.setTag(R.id.abc_tag_conv_item_card, true)
                list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View?, child: View?) {
                        if (child != null) {
                            val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                            styleConversationRow(child, enabled, isNight, density)
                        }
                    }
                    override fun onChildViewRemoved(parent: View?, child: View?) {}
                })
            }
            decorateExistingChildren(list, enabled, context, density)
            list.setTag(R.id.abc_tag_conv_list_styled, true)
        }
    }

    private fun decorateExistingChildren(list: ListView, enabled: Boolean, context: Context, density: Float) {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val count = list.childCount
        for (i in 0 until count) {
            val child = list.getChildAt(i) ?: continue
            styleConversationRow(child, enabled, night, density)
        }
    }

    /**
     * 对应 release 稳定版 xy.j / q92.b
     */
    fun styleConversationRow(row: View, enabled: Boolean, night: Boolean, density: Float) {
        val parent = row.parent as? ListView
        if (parent != null && !isConversationListView(parent)) return
        if (row.getTag(R.id.abc_tag_conv_group_header) != null) return
        if (row.tag == ConversationGroupingHook.TAB_BAR_TAG) return

        val isApplied = row.getTag(R.id.abc_tag_conv_row_styled) == true
        val wrapper = row.getTag(SwipeDeleteKeepHook.VIEW_TAG_WRAPPER) as? View
        val content = row.getTag(SwipeDeleteKeepHook.VIEW_TAG_CONTENT) as? View

        if (!enabled) {
            if (isApplied) {
                val origPadding = row.getTag(R.id.abc_tag_conv_row_orig_padding) as? IntArray
                if (origPadding != null) {
                    row.setPadding(origPadding[0], row.paddingTop, origPadding[1], row.paddingBottom)
                    row.setTag(R.id.abc_tag_conv_row_orig_padding, null)
                }
                row.background = null
                SwipeDeleteKeepHook.applyCardSurfaceFromOutside(row, wrapper, content)
                row.setTag(R.id.abc_tag_conv_row_styled, false)
            }
            return
        }

        if (wrapper != null || content != null) {
            SwipeDeleteKeepHook.applyCardSurfaceFromOutside(row, wrapper, content)
            row.setTag(R.id.abc_tag_conv_row_styled, true)
            row.setTag(R.id.abc_tag_conv_item_card, night)
            return
        }

        val lastNight = row.getTag(R.id.abc_tag_conv_item_card) as? Boolean
        val insetDp = getInsetDp()
        val insetPx = (insetDp * density + 0.5f).toInt()

        if (isApplied && lastNight == night && row.background != null && row.paddingLeft >= insetPx) {
            return
        }

        if (!isConversationRow(row)) {
            if (row.background != null && !isApplied) {
                row.background = null
            }
            return
        }

        SwipeDeleteKeepHook.applyCardSurfaceFromOutside(row, null, null)
        row.setTag(R.id.abc_tag_conv_row_styled, true)
        row.setTag(R.id.abc_tag_conv_item_card, night)
    }

    private fun isConversationRow(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view !is ViewGroup) return false
        val ctx = view.context
        if (ctx != null && isIgnoredActivity(getActivityName(ctx))) return false
        if (view.getTag(R.id.abc_tag_conv_group_header) != null) return false
        if (view.tag == ConversationGroupingHook.TAB_BAR_TAG) return false

        val className = view.javaClass.name
        if (className.contains("Tabs") ||
            className.contains("AppBrandDesktop") ||
            className.contains("PullDownHeader") ||
            className.contains("BannerView") ||
            className.contains("FoldBanner") ||
            className.contains("FilterView") ||
            className.contains("EmptyView") ||
            className.contains("FooterView") ||
            className.contains("SpaceView") ||
            className.contains("BottomTab") ||
            className.contains("AccountInfo") ||
            className.contains("ProfileHeader") ||
            className.contains("SelfHeader")
        ) {
            return false
        }

        // 检查子视图中的微信号/状态等，过滤非会话项
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val desc = child.contentDescription?.toString() ?: ""
            val text = (child as? TextView)?.text?.toString() ?: ""
            if (text.contains("微信号") || text.contains("状态") || desc.contains("状态") || text.contains("赞赏")) {
                return false
            }
        }

        val displayMetrics = view.resources.displayMetrics
        val density = displayMetrics.density
        if (view.height > displayMetrics.heightPixels / 3) return false
        val heightDp = if (view.height > 0) view.height / density else 70f
        if (heightDp > 140f || heightDp < 35f) {
            return false
        }

        return hasText(view, 0)
    }

    private fun hasText(view: View, depth: Int): Boolean {
        if (depth > 4) return false
        if (view is TextView && !view.text.isNullOrBlank()) return true
        if (view is ViewGroup) {
            val count = view.childCount.coerceAtMost(20)
            for (i in 0 until count) {
                if (hasText(view.getChildAt(i), depth + 1)) return true
            }
        }
        return false
    }

    fun isConversationListView(listView: ListView): Boolean {
        val name = listView.javaClass.name
        if (name.contains("ConversationListView", ignoreCase = true) || name.contains("ConversationWithAppBrandListView", ignoreCase = true)) {
            return true
        }
        if (name.contains("PlusSubMenu", ignoreCase = true) ||
            name.contains("Preference", ignoreCase = true) ||
            name.contains("Menu", ignoreCase = true) ||
            name.contains("Address", ignoreCase = true) ||
            name.contains("Setting", ignoreCase = true)
        ) {
            return false
        }
        return listView.getTag(R.id.abc_tag_conv_list_styled) == true
    }

    private fun isIgnoredActivity(name: String): Boolean {
        return name.contains("ContactInfoUI", ignoreCase = true) ||
                name.contains("UserProfileUI", ignoreCase = true) ||
                name.contains("ChatroomInfoUI", ignoreCase = true) ||
                name.contains("ContactWidget", ignoreCase = true) ||
                name.contains("plugin.profile", ignoreCase = true) ||
                name.contains("MoreTabUI", ignoreCase = true) ||
                name.contains("FindMoreFriendsUI", ignoreCase = true) ||
                name.contains("AddressUI", ignoreCase = true)
    }

    private fun getActivityName(context: Context): String {
        return getActivity(context)?.javaClass?.name ?: ""
    }

    private fun getActivity(context: Context): Activity? {
        if (context is Activity) return context
        if (context is ContextWrapper) {
            val base = context.baseContext
            if (base is Activity) return base
        }
        return null
    }

    private fun isThemeWallpaperActive(): Boolean {
        return ThemeWallpaperConfig.isEnabled()
    }

    private fun applyToActiveList() {
        val list = activeListViewRef?.get() ?: return
        applyStyleToListView(list)
        list.invalidate()
    }

    private fun applyStyleFromHost(host: Any) {
        val list = findListView(host) ?: return
        applyStyleToListView(list)
    }

    private fun findListView(host: Any): ListView? {
        if (host is Activity) {
            val decor = host.window?.decorView as? ViewGroup
            decor?.let { walkFind(it)?.let { list -> return list } }
        }
        var cursor: Class<*>? = host.javaClass
        var depth = 0
        while (cursor != null && depth < 6) {
            for (f in cursor.declaredFields) {
                runCatching {
                    f.isAccessible = true
                    val v = f.get(host)
                    if (v is ListView && (v.isShown || v.isAttachedToWindow)) return v
                }
            }
            cursor = cursor.superclass
            depth++
        }
        return null
    }

    private fun walkFind(view: View): ListView? {
        if (view is ListView && (view.isShown || view.isAttachedToWindow)) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = walkFind(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun xlog(msg: String) {
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
