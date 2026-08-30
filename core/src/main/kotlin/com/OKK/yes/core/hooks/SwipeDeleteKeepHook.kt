package com.OKK.yes.core.hooks

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.OKK.yes.core.compat.DexKitSupport
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 左划对话菜单 + 会话卡片背景染色系统（完全对齐 release 稳定版 q92 架构）。
 */
object SwipeDeleteKeepHook {
    private const val TAG = "OKK-SwipeMenu"
    private const val KEY = "swipe_delete_keep"

    const val VIEW_TAG_SWIPE_STATE = 0x7E000101
    const val VIEW_TAG_TOUCH_ATTACHED = 0x7E000102
    const val VIEW_TAG_WRAPPER = 0x7E000103
    const val VIEW_TAG_CONTENT = 0x7E000104
    const val VIEW_TAG_PANEL = 0x7E000105
    const val VIEW_TAG_CARD_APPLIED = 0x7E000106
    const val VIEW_TAG_PINNED = 0x7E000107

    private const val BUTTON_WIDTH_DP = 72
    private const val COLOR_DELETE = 0xFFFF3B30.toInt()

    @Volatile var isSwipingRow: Boolean = false
    private val rowViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    private val isInstalled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var convStorageClazz: Class<*>? = null
    private var delChatContactMethod: Method? = null
    private var storageRef: Any? = null
    private var doDeleteConvMethod: Method? = null
    private var deleteMsgByTalkerMethod: Method? = null

    private val hookedAdapters = LinkedHashSet<String>()
    private val classUsernameFieldMap = ConcurrentHashMap<Class<*>, Field>()
    private val classPinnedFieldMap = ConcurrentHashMap<Class<*>, Field>()
    private val noUsernameFieldClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    private val adapterGetItemMethodMap = ConcurrentHashMap<Class<*>, Method>()

    private var openStateRef: WeakReference<SwipeState>? = null
    private var openState: SwipeState?
        get() = openStateRef?.get()
        set(value) { openStateRef = value?.let { WeakReference(it) } }

    private val settleInterpolator = DecelerateInterpolator()

    fun isEnabled(): Boolean = PublicConfigStore.getBoolean(KEY, true)
    fun isCardBeautifyEnabled(): Boolean = PublicConfigStore.getBoolean("conv_card_enabled", true)
    fun isConversationCard(view: View): Boolean = view.getTag(VIEW_TAG_CARD_APPLIED) == true

    fun isTouchOnConversationRow(screenX: Float, screenY: Float): Boolean {
        synchronized(rowViews) {
            for (row in rowViews.keys) {
                if (row == null || !row.isShown) continue
                val root = row.rootView
                if (root == null || root.width == 0) continue
                val loc = IntArray(2)
                runCatching { row.getLocationOnScreen(loc) }.getOrNull() ?: continue
                if (screenX >= loc[0] && screenX <= loc[0] + row.width &&
                    screenY >= loc[1] && screenY <= loc[1] + row.height
                ) return true
            }
        }
        return false
    }

    fun install(ctx: Context, cl: ClassLoader, mp: String?) {
        if (!isEnabled()) return
        if (!isInstalled.compareAndSet(false, true)) return
        xlog("install requested enabled=true")

        Thread {
            resolveStorageAndWorker(ctx, cl, mp)
            hookConversationAdapters(ctx, cl, mp)
            xlog("install done enabled=true")
        }.start()
    }

    private fun xlog(msg: String) {
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }

    private fun resolveStorageAndWorker(ctx: Context, cl: ClassLoader, mp: String?) {
        convStorageClazz = runCatching {
            DexKitSupport.findClassByStrings(ctx, cl, mp, "MicroMsg.ConversationStorage", "delChatContact username:")
        }.getOrNull()

        if (convStorageClazz != null) {
            val delMethodList = DexKitSupport.findMethodsByStrings(ctx, cl, mp, "MicroMsg.ConversationStorage", "delChatContact username:")
            if (delMethodList.isNotEmpty()) {
                delChatContactMethod = delMethodList.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
                    ?: delMethodList.first()
            }
        }

        val workerClazz = runCatching {
            DexKitSupport.findClassByStrings(ctx, cl, mp, "MicroMsg.ConversationStorage", "doDeleteConversation username:")
        }.getOrNull()

        if (workerClazz != null) {
            val methods = DexKitSupport.findMethodsByStrings(ctx, cl, mp, "MicroMsg.ConversationStorage", "doDeleteConversation username:")
            doDeleteConvMethod = methods.firstOrNull { m ->
                val pts = m.parameterTypes
                pts.isNotEmpty() && pts[0] == String::class.java
            } ?: methods.firstOrNull()
        }

        // 解析并定位 deleteMsgByTalker 方法（用于级联清理被删除会话的本地聊天记录）
        runCatching {
            val m = DexKitSupport.findMethodByStrings(ctx, cl, mp, "MicroMsg.MsgInfoStorageLogic", "summerdel deleteMsgByTalker[")
            if (m != null) {
                deleteMsgByTalkerMethod = m
                xlog("resolved deleteMsgByTalker method: ${m.declaringClass.name}.${m.name}")
            }
        }

        if (convStorageClazz != null) {
            runCatching {
                for (ctor in convStorageClazz!!.declaredConstructors) {
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            storageRef = param.thisObject
                        }
                    })
                }
            }
        }
    }

    private fun hookConversationAdapters(ctx: Context, cl: ClassLoader, mp: String?) {
        runCatching {
            val cacheAdapterClazz = DexKitSupport.findClassByStrings(ctx, cl, mp, "MicroMsg.ConversationWithCacheAdapter", "[getView] position=")
            if (cacheAdapterClazz != null) {
                hookAdapter(cacheAdapterClazz, "ConversationWithCacheAdapter")
            }
        }

        runCatching {
            val mvvmAdapterClazz = DexKitSupport.findClassByStrings(ctx, cl, mp, "MicroMsg.ConversationAdapter.MvvmConversationAdapter")
            if (mvvmAdapterClazz != null) {
                hookAdapter(mvvmAdapterClazz, "MvvmConversationAdapter")
            }
        }
    }

    fun hookAdapter(adapterClazz: Class<*>, source: String) {
        val key = adapterClazz.name
        if (!hookedAdapters.add(key)) return
        val getView = adapterClazz.declaredMethods.firstOrNull { m ->
            m.name == "getView" && m.parameterTypes.size == 3
        } ?: adapterClazz.methods.firstOrNull { m ->
            m.name == "getView" && m.parameterTypes.size == 3
        } ?: return

        runCatching {
            getView.isAccessible = true
            XposedBridge.hookMethod(getView, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val view = param.result as? View ?: return
                    val position = param.args.getOrNull(0) as? Int ?: 0
                    rowViews[view] = true
                    val conversation = getAdapterItem(param.thisObject, position) ?: return

                    val isPinned = isPinnedConversation(conversation)
                    view.setTag(VIEW_TAG_PINNED, isPinned)

                    val talker = readUsernameField(conversation)
                    val ctx = view.context
                    var state = view.getTag(VIEW_TAG_SWIPE_STATE) as? SwipeState
                    if (state == null) {
                        state = SwipeState(
                            touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop,
                            flyOutThreshold = dpToPx(ctx, 220).toFloat()
                        )
                        view.setTag(VIEW_TAG_SWIPE_STATE, state)
                    }
                    if (!talker.isNullOrEmpty()) {
                        state.talker = talker
                    }
                    state.conversation = conversation

                    setUpRow(view, state)
                    rebindState(state, ctx)
                    resetRow(state)

                    attachSwipeListener(view, state)
                }
            })
            xlog("Successfully hooked getView: $source")
        }
    }

    private fun getAdapterItem(adapter: Any, position: Int): Any? {
        val clazz = adapter.javaClass
        var getItemM = adapterGetItemMethodMap[clazz]
        if (getItemM == null) {
            var cursor: Class<*>? = clazz
            while (cursor != null) {
                getItemM = cursor.declaredMethods.firstOrNull { it.name == "getItem" && it.parameterCount == 1 }
                if (getItemM != null) {
                    getItemM.isAccessible = true
                    adapterGetItemMethodMap[clazz] = getItemM
                    break
                }
                cursor = cursor.superclass
            }
        }
        return runCatching { getItemM?.invoke(adapter, position) }.getOrNull()
    }

    private fun readUsernameField(conversation: Any): String? {
        val clazz = conversation.javaClass
        if (noUsernameFieldClasses.contains(clazz)) return null

        val cachedField = classUsernameFieldMap[clazz]
        if (cachedField != null) {
            val value = runCatching { cachedField.get(conversation) as? String }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }

        var cursor: Class<*>? = clazz
        while (cursor != null) {
            for (f in cursor.declaredFields) {
                if (f.name == "field_username" && f.type == String::class.java) {
                    f.isAccessible = true
                    val v = runCatching { f.get(conversation) as? String }.getOrNull()
                    if (!v.isNullOrBlank()) {
                        classUsernameFieldMap[clazz] = f
                        return v
                    }
                }
            }
            cursor = cursor.superclass
        }
        cursor = clazz
        while (cursor != null) {
            for (f in cursor.declaredFields) {
                if (f.type != String::class.java) continue
                val v = runCatching { f.isAccessible = true; f.get(conversation) as? String }.getOrNull()
                if (!v.isNullOrBlank() && looksLikeTalker(v)) {
                    classUsernameFieldMap[clazz] = f
                    return v
                }
            }
            cursor = cursor.superclass
        }
        noUsernameFieldClasses.add(clazz)
        return null
    }

    private fun looksLikeTalker(s: String): Boolean = s.startsWith("wxid_") || s.startsWith("gh_") || s.contains("@chatroom") || s.contains("@im.chatroom") || s.endsWith("@app") || s.endsWith("@qqim") || s == "filehelper"

    /**
     * 判断是否置顶会话（完全对齐 release 稳定版 q92.f）
     */
    fun isPinnedConversation(conversation: Any): Boolean {
        val clazz = conversation.javaClass
        val cached = classPinnedFieldMap[clazz]
        if (cached != null) {
            val flag = runCatching { (cached.get(conversation) as? Number)?.toLong() }.getOrNull()
            if (flag != null) {
                val isPinned = (flag > 0L) && ((flag and 0x4000000000000000L != 0L) || (flag and -0x1000000000000000L != 0L))
                xlog("isPinnedConversation clazz=${clazz.simpleName} cached=${cached.name} flag=$flag -> pinned=$isPinned")
                return isPinned
            }
        }

        var cursor: Class<*>? = clazz
        while (cursor != null && cursor != Any::class.java) {
            for (f in cursor.declaredFields) {
                val name = f.name
                if (name == "field_flag" || name == "flag") {
                    f.isAccessible = true
                    val flag = runCatching { (f.get(conversation) as? Number)?.toLong() }.getOrNull()
                    if (flag != null) {
                        classPinnedFieldMap[clazz] = f
                        val isPinned = (flag > 0L) && ((flag and 0x4000000000000000L != 0L) || (flag and -0x1000000000000000L != 0L))
                        xlog("isPinnedConversation clazz=${clazz.simpleName} field=${f.name} flag=$flag -> pinned=$isPinned")
                        return isPinned
                    }
                }
            }
            cursor = cursor.superclass
        }

        // 兜底支持 wrapped 对象
        for (f in clazz.declaredFields) {
            if (f.type.name.contains("rconversation", ignoreCase = true) ||
                f.type.name.contains("conversation", ignoreCase = true) ||
                f.name.contains("conv", ignoreCase = true)
            ) {
                f.isAccessible = true
                val inner = runCatching { f.get(conversation) }.getOrNull()
                if (inner != null && inner !== conversation) {
                    if (isPinnedConversation(inner)) return true
                }
            }
        }
        return false
    }

    private class SwipeState(
        val touchSlop: Int,
        val flyOutThreshold: Float,
        var talker: String? = null,
        var conversation: Any? = null,
        var wrapper: View? = null,
        var content: View? = null,
        var panel: View? = null,
        var delBtn: View? = null,
        var activeButtons: List<View> = emptyList(),
        var revealWidth: Float = 0f,
        var startX: Float = 0f,
        var startY: Float = 0f,
        var dragBase: Float = 0f,
        var isDragging: Boolean = false,
        var isOpen: Boolean = false,
        var startedOpen: Boolean = false,
        var flungOut: Boolean = false
    )

    private fun dpToPx(ctx: Context, dp: Int): Int = (dp * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun setUpRow(row: View, s: SwipeState) {
        val existingWrapper = row.getTag(VIEW_TAG_WRAPPER) as? View
        if (existingWrapper != null) {
            s.wrapper = existingWrapper
            s.content = row.getTag(VIEW_TAG_CONTENT) as? View
            s.panel = row.getTag(VIEW_TAG_PANEL) as? View
            existingWrapper.visibility = View.VISIBLE
            applyCardSurfaceFromOutside(row, existingWrapper, s.content)
            applyTranslation(s, 0f)
            return
        }

        if (s.wrapper != null) {
            s.wrapper?.visibility = View.VISIBLE
            applyCardSurfaceFromOutside(row, s.wrapper, s.content)
            return
        }
        val group = row as? ViewGroup ?: return
        if (group.childCount == 0) return

        val children = mutableListOf<View>()
        for (i in 0 until group.childCount) {
            children.add(group.getChildAt(i))
        }
        group.removeAllViews()

        val contentContainer = FrameLayout(group.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            clipToOutline = true
        }
        for (child in children) {
            contentContainer.addView(child)
        }

        val wrapper = FrameLayout(group.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            clipChildren = true
            clipToPadding = false
        }

        val panel = buildActionPanel(group.context, s)

        wrapper.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        wrapper.addView(contentContainer)

        group.addView(wrapper)

        row.setTag(VIEW_TAG_WRAPPER, wrapper)
        row.setTag(VIEW_TAG_CONTENT, contentContainer)
        row.setTag(VIEW_TAG_PANEL, panel)

        s.wrapper = wrapper
        s.content = contentContainer
        s.panel = panel
        applyCardSurfaceFromOutside(row, wrapper, contentContainer)
        applyTranslation(s, 0f)
    }

    /**
     * 核心会话卡片美化与背景上色（完全对齐 release 稳定版 q92.b）
     */
    fun applyCardSurfaceFromOutside(row: View, wrapperView: View?, contentView: View?) {
        val context = row.context ?: return
        var content = contentView

        if (!isCardBeautifyEnabled()) {
            row.setPadding(0, 0, 0, 0)
            wrapperView?.setPadding(0, 0, 0, 0)
            row.background = null
            wrapperView?.background = null
            content?.background = null
            content?.elevation = 0f
            content?.setTag(VIEW_TAG_CARD_APPLIED, false)
            return
        }

        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val density = context.resources.displayMetrics.density
        val insetDp = PublicConfigStore.getInt("conv_card_inset_dp", 10).coerceIn(2, 24)
        val cornerDp = PublicConfigStore.getInt("conv_card_corner_dp", 12).coerceIn(0, 24)

        val insetPx = (insetDp * density + 0.5f).toInt()
        val verticalPadPx = (2f * density + 0.5f).toInt()
        val cornerRadius = cornerDp * density

        val isPinned = row.getTag(VIEW_TAG_PINNED) == true

        val cardBg = when {
            isPinned && night -> Color.parseColor("#38383E")
            !isPinned && night -> Color.parseColor("#1E1E1E")
            isPinned && !night -> Color.parseColor("#E6E8ED")
            else -> Color.parseColor("#FFFFFF")
        }
        val pressedBg = Color.parseColor(if (night) "#44444A" else "#DCDFE6")
        val strokeColor = when {
            isPinned && night -> Color.parseColor("#40FFFFFF")
            !isPinned && night -> Color.parseColor("#14FFFFFF")
            isPinned && !night -> Color.parseColor("#33000000")
            else -> Color.argb(14, 0, 0, 0)
        }
        val strokeWidth = (1f * density + 0.5f).toInt().coerceAtLeast(1)

        val normalDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(cardBg)
            setStroke(strokeWidth, strokeColor)
        }

        val pressedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(pressedBg)
            setStroke(strokeWidth, strokeColor)
        }

        val stateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
            addState(intArrayOf(), normalDrawable)
        }

        row.setPadding(0, verticalPadPx, 0, verticalPadPx)

        if (content == null) {
            val tagContent = row.getTag(VIEW_TAG_CONTENT)
            content = if (tagContent is View) tagContent else null
        }

        if (wrapperView == null || content == null) {
            if (wrapperView != null) {
                wrapperView.background = null
                wrapperView.setPadding(insetPx, 0, insetPx, 0)
            }
            row.background = stateList
            row.setPadding(insetPx, verticalPadPx, insetPx, verticalPadPx)
            if (row is ViewGroup) {
                row.outlineProvider = ViewOutlineProvider.BACKGROUND
                row.clipToOutline = true
                clearChildBackgrounds(row)
            }
            row.setTag(VIEW_TAG_CARD_APPLIED, true)
            return
        }

        row.background = null
        wrapperView.background = null
        wrapperView.setPadding(insetPx, 0, insetPx, 0)
        content.background = stateList
        content.elevation = if (!night) density * 1.5f else 0f
        if (content is ViewGroup) {
            content.outlineProvider = ViewOutlineProvider.BACKGROUND
            content.clipToOutline = true
            clearChildBackgrounds(content)
        }
        content.setTag(VIEW_TAG_CARD_APPLIED, true)
    }

    /**
     * 清除会话内容子视图背景（对齐 release 稳定版 q92.d）
     */
    fun clearChildBackgrounds(view: View) {
        if (view is ViewGroup) {
            val width = if (view.width > 0) view.width else view.resources.displayMetrics.widthPixels
            val count = view.childCount
            for (i in 0 until count) {
                val child = view.getChildAt(i)
                if (child.getTag(VIEW_TAG_CARD_APPLIED) != true) {
                    if (child is ViewGroup) {
                        val cw = child.width
                        if ((cw == 0 || cw >= width * 0.6f) && child.background != null) {
                            child.background = null
                        }
                        clearChildBackgrounds(child)
                    } else if (child !is ImageView && child.background != null && (child.width == 0 || child.width >= width * 0.6f)) {
                        child.background = null
                    }
                }
            }
        }
    }

    private fun buildActionPanel(ctx: Context, s: SwipeState): View {
        val panel = FrameLayout(ctx)
        val del = TextView(ctx).apply {
            text = "删除"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_DELETE)
            maxLines = 1
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.START }
            isClickable = true
            setOnClickListener { onAction(s, "DELETE") }
        }
        panel.addView(del)
        s.delBtn = del
        return panel
    }

    private fun rebindState(s: SwipeState, ctx: Context) {
        val active = mutableListOf<View>()
        s.delBtn?.let { active.add(it) }
        s.activeButtons = active
        s.revealWidth = dpToPx(ctx, BUTTON_WIDTH_DP * active.size).toFloat()
    }

    private fun applyTranslation(s: SwipeState, tx: Float) {
        val content = s.content ?: return
        content.translationX = tx
        val buttons = s.activeButtons
        if (buttons.isEmpty()) return

        var reveal = (-tx).coerceAtLeast(0f)
        s.panel?.visibility = if (reveal <= 0f) View.GONE else View.VISIBLE
        if (reveal <= 0f) return

        val rowW = (if ((s.panel?.width ?: 0) > 0) s.panel!!.width else content.width).toFloat()
        if (rowW <= 0f) return

        val n = buttons.size
        val stripLeft = rowW - reveal
        val btnW = reveal / n

        for (i in 0 until n - 1) {
            val btn = buttons[i]
            val w = btnW.toInt().coerceAtLeast(0)
            if (btn.layoutParams.width != w) {
                btn.layoutParams.width = w
                btn.requestLayout()
            }
            btn.translationX = stripLeft + i * btnW
        }

        val lastBtn = buttons[n - 1]
        val lw = (rowW - (stripLeft + (n - 1) * btnW)).toInt().coerceAtLeast(0)
        if (lastBtn.layoutParams.width != lw) {
            lastBtn.layoutParams.width = lw
            lastBtn.requestLayout()
        }
        lastBtn.translationX = stripLeft + (n - 1) * btnW
    }

    private fun resetRow(s: SwipeState) {
        s.isDragging = false
        s.isOpen = false
        s.flungOut = false
        s.dragBase = 0f
        isSwipingRow = false
        s.content?.animate()?.cancel()
        applyTranslation(s, 0f)
    }

    private fun settleOpen(s: SwipeState) {
        val content = s.content ?: return
        val target = -s.revealWidth
        val anim = ValueAnimator.ofFloat(content.translationX, target).apply {
            duration = 180
            interpolator = settleInterpolator
            addUpdateListener { va ->
                val v = va.animatedValue as Float
                applyTranslation(s, v)
            }
        }
        anim.start()
        s.isOpen = true
        s.dragBase = target
        openState = s
    }

    private fun settleClosed(s: SwipeState) {
        val content = s.content ?: return
        val anim = ValueAnimator.ofFloat(content.translationX, 0f).apply {
            duration = 180
            interpolator = settleInterpolator
            addUpdateListener { va ->
                val v = va.animatedValue as Float
                applyTranslation(s, v)
            }
        }
        anim.start()
        s.isOpen = false
        s.dragBase = 0f
        if (openState === s) openState = null
    }

    private fun onAction(s: SwipeState, action: String) {
        val talker = s.talker ?: return
        when (action) {
            "DELETE" -> {
                executeHideConversation(talker)
                settleClosed(s)
            }
        }
    }

    private fun executeHideConversation(talker: String) {
        try {
            var handled = false
            if (doDeleteConvMethod != null && storageRef != null) {
                val pts = doDeleteConvMethod!!.parameterTypes
                if (pts.size == 1) {
                    doDeleteConvMethod!!.invoke(storageRef, talker)
                    handled = true
                } else if (pts.size >= 2 && pts[1] == Int::class.javaPrimitiveType) {
                    doDeleteConvMethod!!.invoke(storageRef, talker, 0)
                    handled = true
                }
            }
            if (!handled && delChatContactMethod != null && storageRef != null) {
                delChatContactMethod!!.invoke(storageRef, talker)
                handled = true
            }

            // 级联清理本地聊天记录：微信原生删除群聊/联系人会话时会触发 deleteMsgByTalker。
            // 解决折叠群聊/普通会话删除后聊天记录依然残留的问题。
            runCatching {
                val m = deleteMsgByTalkerMethod
                if (m != null) {
                    m.isAccessible = true
                    val pts = m.parameterTypes
                    when (pts.size) {
                        2 -> {
                            if (pts[0] == String::class.java) {
                                m.invoke(null, talker, null)
                            } else if (List::class.java.isAssignableFrom(pts[0])) {
                                m.invoke(null, listOf(talker), null)
                            }
                        }
                        3 -> {
                            if (List::class.java.isAssignableFrom(pts[0])) {
                                m.invoke(null, listOf(talker), null, 0L)
                            } else if (pts[0] == String::class.java) {
                                m.invoke(null, talker, null, 0L)
                            }
                        }
                    }
                    xlog("deleteMsgByTalker invoked for talker=$talker")
                }
            }.onFailure {
                xlog("deleteMsgByTalker fail: ${it.message}")
            }

            // 同步通过 WCDB 执行 SQL 兜底级联删除，双保险
            runCatching {
                ContactDisplayNames.execSql("DELETE FROM message WHERE talker = ?", arrayOf(talker))
                ContactDisplayNames.execSql("DELETE FROM rconversation WHERE username = ?", arrayOf(talker))
            }

            xlog("executeHideConversation handled=$handled for talker=$talker")
        } catch (t: Throwable) {
            xlog("executeHideConversation error: ${t.message}")
        }
    }

    private class SwipeTouchListener(val original: View.OnTouchListener?) : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val state = v.getTag(VIEW_TAG_SWIPE_STATE) as? SwipeState
            if (state != null) {
                val handled = handleSwipe(v, state, event)
                if (handled) return true
            }
            return original?.onTouch(v, event) ?: false
        }
    }

    private fun attachSwipeListener(view: View, state: SwipeState) {
        val current = getAttachedTouchListener(view)
        if (current is SwipeTouchListener) return
        view.setOnTouchListener(SwipeTouchListener(current))
    }

    private var viewListenerInfoField: Field? = null
    private var listenerInfoOnTouchField: Field? = null
    private var fieldsInitialized = false

    private fun initTouchListenerFields() {
        if (fieldsInitialized) return
        fieldsInitialized = true
        runCatching {
            viewListenerInfoField = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }
            val infoClass = Class.forName("android.view.View\$ListenerInfo")
            listenerInfoOnTouchField = infoClass.getDeclaredField("mOnTouchListener").apply { isAccessible = true }
        }
    }

    private fun getAttachedTouchListener(view: View): View.OnTouchListener? {
        initTouchListenerFields()
        return runCatching {
            val info = viewListenerInfoField?.get(view) ?: return null
            listenerInfoOnTouchField?.get(info) as? View.OnTouchListener
        }.getOrNull()
    }

    private fun handleSwipe(v: View, s: SwipeState, event: MotionEvent): Boolean {
        val content = s.content ?: return false
        if (s.activeButtons.isEmpty()) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                s.startX = event.rawX
                s.startY = event.rawY
                s.isDragging = false
                s.dragBase = content.translationX
                s.startedOpen = s.isOpen
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - s.startX
                val dy = event.rawY - s.startY
                if (!s.isDragging && abs(dx) > s.touchSlop && abs(dx) > abs(dy)) {
                    if (dx < 0 || s.isOpen) {
                        s.isDragging = true
                        isSwipingRow = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.isPressed = false
                        v.cancelLongPress()
                    }
                }
                if (s.isDragging) {
                    val tx = (s.dragBase + dx).coerceAtMost(0f)
                    applyTranslation(s, tx)
                    true
                } else false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.parent?.requestDisallowInterceptTouchEvent(false)
                isSwipingRow = false
                if (!s.isDragging) {
                    if (s.isOpen) { settleClosed(s); return true }
                    return false
                }
                s.isDragging = false
                val tx = content.translationX
                if (tx <= -s.revealWidth * 0.35f) {
                    settleOpen(s)
                } else {
                    settleClosed(s)
                }
                true
            }
            else -> false
        }
    }
}
