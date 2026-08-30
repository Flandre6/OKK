package com.OKK.yes.core.hooks.ui

import com.OKK.yes.core.hooks.HomeAvatarHook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.OKK.yes.core.hooks.BottomTabConfig
import com.OKK.yes.core.hooks.HomeDrawerBridge
import com.OKK.yes.core.hooks.HomeDrawerConfig
import com.OKK.yes.core.hooks.PublicConfigStore
import com.OKK.yes.core.hooks.VirtualLocationConfig
import com.OKK.yes.core.hooks.WeChatPageLauncher
import java.lang.ref.WeakReference

/**
 * 首页头像侧栏：快捷（可定制≤3）+ OKK + 帮助/关于。
 */
object HomeSideDrawer {
    private const val OVERLAY_TAG = "achat_home_side_drawer"
    private const val ANIM_MS = 340L
    private const val PANEL_MAX_DP = 300
    private const val TG_URL = "https://t.me/OKK_YES"

    @Volatile private var hostRef: WeakReference<Activity>? = null
    @Volatile private var overlay: FrameLayout? = null
    @Volatile private var panel: View? = null
    @Volatile private var scrim: View? = null
    @Volatile private var animating = false
    @Volatile private var avatarBinder: ((ImageView) -> Unit)? = null
    @Volatile private var titleProvider: (() -> String)? = null
    @Volatile private var statusProvider: (() -> String)? = null
    @Volatile private var onStatusClick: (() -> Unit)? = null

    /** 缓存面板内的昵称/签名控件：复用 overlay 时刷新文本（可能已变化） */
    @Volatile private var cachedTitleView: TextView? = null
    @Volatile private var cachedSignatureView: TextView? = null

    private fun refreshCachedProfileTexts() {
        val nick = titleProvider?.invoke()?.takeIf { it.isNotBlank() } ?: "我"
        cachedTitleView?.text = nick
        val sig = runCatching { HomeDrawerConfig.loadSignature() }.getOrDefault("")
        cachedSignatureView?.text = sig
    }

    // ── 系统手势排除：左缘专属区域，防止系统"返回"手势抢占右滑开抽屉 ──
    private const val GESTURE_EXCLUDE_DP = 64f
    @Volatile private var exclusionApplied = false
    @Volatile private var exclusionActivityRef: WeakReference<Activity>? = null

    private val exclusionLayoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        applyGestureExclusion(v)
    }

    private fun applyGestureExclusion(decor: View) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return
            val density = decor.resources.displayMetrics.density
            val excludeW = (GESTURE_EXCLUDE_DP * density).toInt().coerceIn(1, w)
            decor.setSystemGestureExclusionRects(listOf(android.graphics.Rect(0, 0, excludeW, h)))
        }
    }

    /**
     * 首页 tab0 且未进聊天时启用左缘手势排除，系统不再把该区域右滑当返回。
     * 进入聊天/切离首页时关闭，保证正常返回手势不受影响。
     */
    fun updateGestureExclusion(activity: Activity?, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        val act = activity ?: exclusionActivityRef?.get() ?: return
        exclusionActivityRef = WeakReference(act)
        runCatching {
            val decor = act.window?.decorView ?: return
            if (enabled) {
                decor.removeOnLayoutChangeListener(exclusionLayoutListener)
                decor.addOnLayoutChangeListener(exclusionLayoutListener)
                applyGestureExclusion(decor)
                exclusionApplied = true
            } else if (exclusionApplied) {
                decor.removeOnLayoutChangeListener(exclusionLayoutListener)
                decor.setSystemGestureExclusionRects(emptyList())
                exclusionApplied = false
            }
        }
    }

    private fun xlog(msg: String) {
        runCatching { android.util.Log.i("OKK-SideDrawer", msg) }
    }

    fun configure(
        bindAvatar: ((ImageView) -> Unit)?,
        title: (() -> String)?,
        status: (() -> String)? = null,
        onStatusClick: (() -> Unit)? = null
    ) {
        avatarBinder = bindAvatar
        titleProvider = title
        statusProvider = status
        this.onStatusClick = onStatusClick
    }

    fun isShowing(): Boolean = overlay?.parent != null && overlay?.visibility == View.VISIBLE

    fun toggle(activity: Activity) {
        xlog("toggle showing=${isShowing()} animating=$animating overlay=${overlay != null}")
        if (isShowing()) dismiss(animated = true) else show(activity)
    }

    fun show(activity: Activity) {
        if (activity.isFinishing) return
        if (!HomeAvatarHook.isEnabled()) {
            xlog("show abort: HomeAvatarHook disabled")
            return
        }
        if (animating) {
            xlog("show abort: animating=$animating")
            return
        }
        if (isShowing()) {
            xlog("show abort: already showing")
            return
        }
        hostRef = WeakReference(activity)
        xlog("show begin")

        val od = buildOverlay(activity) ?: return
        val side = od.panel
        val dim = od.dim
        val panelW = od.panelW

        animating = true
        side.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(side, View.TRANSLATION_X, -panelW.toFloat(), 0f),
                ObjectAnimator.ofFloat(dim, View.ALPHA, 0f, 0.42f)
            )
            duration = 280L
            interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    side.setLayerType(View.LAYER_TYPE_NONE, null)
                    animating = false
                }
            })
            start()
        }
    }

    // ── 边缘左滑/右滑跟手打开（配合 WxViewPager 拦截）────────────────
    @Volatile private var edgeActive = false
    @Volatile private var edgeStarted = false
    @Volatile private var edgeStartX = 0f
    @Volatile private var edgeStartY = 0f

    /**
     * onInterceptTouchEvent 阶段：DOWN 记录起点（不拦截），MOVE 判定水平滑动后拦截。
     * 只在屏幕左边缘（约 72dp 内）起手才接管，避免抢列表点击/滚动。
     * 返回 true = 本次 MOVE 需要拦截（交给我们处理）。
     */
    fun onEdgeIntercept(activity: Activity, ev: MotionEvent): Boolean {
        if (!HomeAvatarHook.isEnabled()) return false
        val actName = activity.javaClass.name
        if (!actName.contains("LauncherUI") && !actName.contains("MainTabUI")) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isShowing() || animating) return false
                // 残留状态清理：避免上次手势被中断后 edgeActive 卡 true 导致后续失效
                edgeActive = false
                edgeStarted = false
                val density = activity.resources.displayMetrics.density
                val sx = ev.rawX
                val sy = ev.rawY
                // 只在屏幕左边缘起手；避开头像/信息列区域（顶部 statusBar+60dp 内放行）
                if (sx > (72 * density + 0.5f).toInt()) return false
                if (sy < statusBarHeight(activity) + (60 * density + 0.5f).toInt()) return false
                edgeActive = true
                edgeStarted = false
                edgeStartX = sx
                edgeStartY = sy
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!edgeActive) return false
                val dx = ev.rawX - edgeStartX
                val dy = Math.abs(ev.rawY - edgeStartY)
                val density = activity.resources.displayMetrics.density
                val slop = (12 * density + 0.5f).toInt().toFloat()
                // 水平明显（dx 超过 slop 且 dx > dy）即开始跟手
                if (Math.abs(dx) > slop && Math.abs(dx) > dy) {
                    edgeStarted = true
                    return true
                }
                // 仅当纵向明确占主导（dy > 2*dx 且超过 slop）才判定为列表滚动而放弃
                if (dy > slop && dy > Math.abs(dx) * 2f) {
                    edgeActive = false
                    return false
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!edgeActive) return false
                edgeActive = false
                edgeStarted = false
                return false
            }
        }
        return false
    }

    /**
     * onTouchEvent 阶段（拦截后）：跟手拉出侧栏，松开按距离决定打开/回弹。
     * 返回 true 表示消费了事件（阻止 ViewPager 翻页）。
     */
    fun onEdgeTouch(activity: Activity, ev: MotionEvent): Boolean {
        if (!HomeAvatarHook.isEnabled()) return false
        val actName = activity.javaClass.name
        if (!actName.contains("LauncherUI") && !actName.contains("MainTabUI")) return false
        if (!edgeActive || !edgeStarted) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - edgeStartX
                val od = overlayData() ?: run {
                    // 首次跟手：构建面板并置于左侧外
                    val b = buildOverlay(activity) ?: run { edgeActive = false; return true }
                    b.panel.translationX = (-b.panelW).toFloat()
                    b.dim.alpha = 0f
                    return true
                }
                // 关键修复：复用缓存 overlay 时，上次关闭后 root 是 GONE，必须恢复 VISIBLE，
                // 否则第二次边缘滑动拖拽时什么都不显示（“第二次滑不动/打不开”）。
                if (overlay?.visibility != View.VISIBLE) {
                    overlay?.visibility = View.VISIBLE
                    overlay?.bringToFront()
                }
                val dist = Math.abs(dx)
                od.panel.translationX = (dist - od.panelW).coerceIn(-od.panelW.toFloat(), 0f)
                val p = (dist / od.panelW).coerceIn(0f, 1f)
                od.dim.alpha = (0.42f * p).coerceIn(0f, 0.42f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                edgeActive = false
                edgeStarted = false
                val dx = ev.rawX - edgeStartX
                val od = overlayData() ?: return true
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL ||
                    Math.abs(dx) < od.panelW * 0.28f
                ) {
                    // 拉出不足 / 被取消：回弹关闭
                    dismiss(animated = true)
                } else {
                    // 拉出足够：补完打开动画
                    val side = od.panel
                    val dim = od.dim
                    animating = true
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(side, View.TRANSLATION_X, side.translationX, 0f),
                            ObjectAnimator.ofFloat(dim, View.ALPHA, dim.alpha, 0.42f)
                        )
                        duration = 200L
                        interpolator = softOut()
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                animating = false
                            }
                        })
                        start()
                    }
                }
                return true
            }
        }
        return false
    }

    private fun overlayData(): OverlayData? {
        val p = panel ?: return null
        val d = scrim ?: return null
        val w = p.width.takeIf { it > 0 } ?: (p.layoutParams?.width ?: 0)
        if (w <= 0) return null
        return OverlayData(p, d, w)
    }

    private data class OverlayData(val panel: View, val dim: View, val panelW: Int)

    /** 构建侧栏 overlay（面板 + 遮罩），挂到 decorView，返回面板数据 */
    private fun buildOverlay(activity: Activity): OverlayData? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null

        // 复用已缓存的面板：同 Activity 内重复打开不再重建视图（消除打开卡顿）
        val old = overlay
        if (old != null && old.isAttachedToWindow && old.parent === decor) {
            val side = panel
            val dim = scrim
            val w = side?.width?.takeIf { it > 0 } ?: (side?.layoutParams?.width ?: 0)
            if (side != null && dim != null && w > 0) {
                old.visibility = View.VISIBLE
                old.bringToFront()
                side.translationX = -w.toFloat()
                dim.alpha = 0f
                // 昵称/签名可能已变化，复用后刷新一次
                refreshCachedProfileTexts()
                xlog("reuse cached overlay")
                return OverlayData(side, dim, w)
            }
        }

        findOverlay(decor)?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val screenW = activity.resources.displayMetrics.widthPixels
        val panelW = minOf((screenW * 0.78f).toInt(), dp(PANEL_MAX_DP))

        val root = FrameLayout(activity).apply {
            tag = OVERLAY_TAG
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= 21) elevation = dp(24).toFloat()
        }

        val dim = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            setOnClickListener { dismiss(animated = true) }
        }

        val side = buildPanel(activity, panelW, density).apply {
            layoutParams = FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
            }
            translationX = -panelW.toFloat()
            if (Build.VERSION.SDK_INT >= 21) elevation = dp(10).toFloat()
        }

        var dragStartX = 0f
        var dragging = false
        side.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = ev.rawX
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - dragStartX
                    if (!dragging && dx < -dp(8)) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging && dx < 0) {
                        v.translationX = dx.coerceAtLeast(-panelW.toFloat())
                        val p = (-v.translationX / panelW).coerceIn(0f, 1f)
                        dim.alpha = 0.42f * (1f - p)
                        return@setOnTouchListener true
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        if (v.translationX < -panelW * 0.25f) {
                            dismiss(animated = true)
                        } else {
                            v.animate().translationX(0f).setDuration(240)
                                .setInterpolator(softIn()).start()
                            dim.animate().alpha(0.42f).setDuration(240).start()
                        }
                        dragging = false
                        true
                    } else false
                }
                else -> false
            }
        }

        root.addView(dim)
        root.addView(side)
        decor.addView(root)
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP
            ) {
                dismiss(animated = true)
                true
            } else false
        }

        overlay = root
        panel = side
        scrim = dim
        return OverlayData(side, dim, panelW)
    }

    fun dismiss(animated: Boolean) {
        val root = overlay ?: return
        val side = panel
        val dim = scrim
        val panelW = side?.width?.takeIf { it > 0 } ?: (side?.layoutParams?.width ?: 0)

        // 隐藏而非销毁：overlay 视图缓存复用，下次打开不再重建面板（消除打开卡顿）
        fun hide() {
            runCatching {
                side?.animate()?.cancel()
                dim?.animate()?.cancel()
            }
            runCatching {
                side?.translationX = -panelW.toFloat()
                dim?.alpha = 0f
                root.visibility = View.GONE
            }
            animating = false
        }

        // 跳转入口：直接隐藏，不等关闭动画（体感延迟的主因）
        if (!animated || side == null || dim == null) {
            hide()
            return
        }
        // 打开动画中也可被打断关闭
        animating = true
        side.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(side, View.TRANSLATION_X, side.translationX, -panelW.toFloat()),
                ObjectAnimator.ofFloat(dim, View.ALPHA, dim.alpha, 0f)
            )
            duration = 180L
            interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    side.setLayerType(View.LAYER_TYPE_NONE, null)
                    hide()
                }
            })
            start()
        }
    }

    /**
     * 点菜单进其它页：先卸侧栏再立刻执行，避免 postDelayed(280) 卡顿。
     */
        /**
     * 点侧栏选项跳转其它页面：
     * 1. 给予 50ms 允许点击 Ripple 水波纹动画充分展现，消除“一卡一卡的”违和感；
     * 2. 开启 160ms 硬件加速退场动画；
     * 3. 侧栏滑出过半（90ms）时拉起目标页面，无缝衔接。
     */
    private fun navigate(activity: Activity, action: () -> Unit) {
        if (animating) return
        val side = panel
        val dim = scrim
        val panelW = side?.width?.takeIf { it > 0 } ?: (side?.layoutParams?.width ?: 0)

        val decor = activity.window?.decorView
        decor?.postDelayed({
            if (side != null && dim != null && panelW > 0) {
                animating = true
                side.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(side, View.TRANSLATION_X, side.translationX, -panelW.toFloat()),
                        ObjectAnimator.ofFloat(dim, View.ALPHA, dim.alpha, 0f)
                    )
                    duration = 160L
                    interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            side.setLayerType(View.LAYER_TYPE_NONE, null)
                            dismiss(animated = false)
                        }
                    })
                    start()
                }
            } else {
                dismiss(animated = false)
            }
        }, 50L)

        decor?.postDelayed({
            if (activity.isFinishing) return@postDelayed
            runCatching { action() }.onFailure {
                toast(activity, "打开失败")
            }
        }, 90L)
    }

    private fun buildPanel(activity: Activity, width: Int, density: Float): View {
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val night = isNight(activity)
        val bg = if (night) Color.parseColor("#1A1C1A") else Color.parseColor("#F7F8F6")
        val titleC = if (night) Color.parseColor("#F2F4F2") else Color.parseColor("#1C1F1C")
        val subC = if (night) Color.parseColor("#9AA39A") else Color.parseColor("#6B736C")
        val cardC = if (night) Color.parseColor("#242724") else Color.WHITE
        val accent = Color.parseColor("#2F8A4E")
        val divider = if (night) Color.parseColor("#22FFFFFF") else Color.parseColor("#0F000000")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            if (Build.VERSION.SDK_INT >= 21) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width + dp(18), view.height, dp(18).toFloat())
                    }
                }
                clipToOutline = true
            }
        }

        root.addView(
            View(activity),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeight(activity))
        )

        // 个人信息卡：头像、昵称、状态与编辑提示组成完整信息块。
        val profileCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(16), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(if (night) Color.parseColor("#222822") else Color.parseColor("#E5EFE6"))
                setStroke(dp(1), if (night) Color.parseColor("#2AFFFFFF") else Color.parseColor("#1F000000"))
            }
            if (Build.VERSION.SDK_INT >= 21) elevation = dp(2).toFloat()
        }
        val header = profileCard
        val avatarContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val avatar = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
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
        }
        avatarContainer.addView(avatar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(avatarContainer)
        xlog("panel avatar binder null=" + (avatarBinder == null))
        avatarBinder?.invoke(avatar)
        xlog("panel avatar bind invoked")

        val nameCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        nameCol.addView(TextView(activity).apply {
            text = titleProvider?.invoke() ?: "我"
            setTextColor(titleC)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
        })

        val signatureView = TextView(activity).apply {
            val tipShown = HomeDrawerConfig.loadSignatureTipShown()
            val sig = HomeDrawerConfig.loadSignature()
            text = if (tipShown) sig else "✎ 编辑签名 · $sig"
            if (!tipShown) HomeDrawerConfig.markSignatureTipShown()
            contentDescription = "编辑侧栏签名"
            setTextColor(if (night) Color.parseColor("#A8B5A8") else Color.parseColor("#5A6B5C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(if (night) Color.parseColor("#1E241E") else Color.parseColor("#D6E4D8"))
            }
            maxLines = 1
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showSignatureEditor(activity, this, night, titleC, subC, cardC, accent, bg)
            }
        }
        val sigWrapper = LinearLayout(activity).apply {
            setPadding(0, dp(6), 0, 0)
            addView(signatureView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        nameCol.addView(sigWrapper)
        cachedSignatureView = signatureView
        header.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 昵称/状态可能在首次打开时尚未解析完成，延迟二次刷新
        val titleView = nameCol.getChildAt(0) as? TextView
        cachedTitleView = titleView
        fun refreshProfileTexts() {
            val nick = titleProvider?.invoke()?.takeIf { it.isNotBlank() } ?: "我"
            titleView?.text = nick
        }
        root.post { refreshProfileTexts() }
        root.postDelayed({ refreshProfileTexts() }, 400)
        root.postDelayed({ refreshProfileTexts() }, 1200)

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(8)
        })

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        // ── 快捷（标题旁编辑图标）──
        sectionLabelWithAction(
            body, activity, "快捷", subC, accent, density,
            actionEmoji = "✎",
            actionDesc = "编辑快捷"
        ) {
            showShortcutPicker(activity, night, titleC, subC, cardC, accent, bg) {
                dismiss(animated = false)
                activity.window?.decorView?.post { show(activity) }
            }
        }
        val shortcuts = HomeDrawerConfig.loadShortcuts()
        val shortcutRows = shortcuts.map { s ->
            MenuRow(s.emoji, s.title, s.subtitle) {
                navigate(activity) {
                    if (!WeChatPageLauncher.openShortcut(activity, s)) {
                        toast(activity, "无法打开「${s.title}」")
                    }
                }
            }
        }
        menuCard(body, activity, cardC, titleC, subC, density, shortcutRows)

        // ── OKK ──
        sectionLabel(body, activity, "OKK", subC, density)
        val vlOn = runCatching {
            PublicConfigStore.getBoolean(VirtualLocationConfig.KEY_ENABLED, false)
        }.getOrDefault(false)
        val floatOn = runCatching {
            PublicConfigStore.getBoolean(BottomTabConfig.KEY_FLOATING, false)
        }.getOrDefault(false)
        menuCard(
            body, activity, cardC, titleC, subC, density,
            listOf(
                MenuRow("⚙", "模块设置", "全部功能开关 · 配置") {
                    navigate(activity) {
                        HomeDrawerBridge.openSettings?.invoke(activity)
                            ?: toast(activity, "设置入口未就绪")
                    }
                },
                MenuRow("🎨", "主题", "主界面壁纸 · 实时透明度") {
                    navigate(activity) {
                        HomeDrawerBridge.openTheme?.invoke(activity)
                            ?: toast(activity, "设置入口未就绪")
                    }
                },
                MenuRow("📍", "虚拟定位", if (vlOn) "已开启 · 点进配置" else "未开启 · 点进配置") {
                    navigate(activity) {
                        HomeDrawerBridge.openVirtualLocation?.invoke(activity)
                            ?: toast(activity, "设置入口未就绪")
                    }
                },
                MenuRow("▢", "悬浮底栏", if (floatOn) "已开启 · 点进配置" else "未开启 · 点进配置") {
                    navigate(activity) {
                        HomeDrawerBridge.openFloatingBar?.invoke(activity)
                            ?: toast(activity, "设置入口未就绪")
                    }
                }
            )
        )

        // ── 其它 ──
        sectionLabel(body, activity, "其它", subC, density)
        menuCard(
            body, activity, cardC, titleC, subC, density,
            listOf(
                MenuRow("💬", "帮助与反馈", "Telegram 群") {
                    navigate(activity) { openUrl(activity, TG_URL) }
                },
                MenuRow("ℹ", "关于", "模块设置 · 关于页") {
                    navigate(activity) {
                        HomeDrawerBridge.openAbout?.invoke(activity)
                            ?: toast(activity, "设置入口未就绪")
                    }
                }
            )
        )

        body.addView(TextView(activity).apply {
            text = "长按无 · 左滑关闭侧栏"
            setTextColor(subC)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        })

        scroll.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            View(activity).apply {
                setBackgroundColor(accent)
                alpha = 0.85f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        )
        return root
    }

    private data class MenuRow(
        val emoji: String,
        val title: String,
        val subtitle: String,
        val onClick: () -> Unit
    )

    /** 侧栏头部直接编辑签名，保存后同步落盘并即时刷新当前文本。 */
    private fun showSignatureEditor(
        activity: Activity,
        target: TextView,
        night: Boolean,
        titleC: Int,
        subC: Int,
        cardC: Int,
        accent: Int,
        pageBg: Int
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val input = android.widget.EditText(activity).apply {
            setText(HomeDrawerConfig.loadSignature())
            setSelectAllOnFocus(false)
            setSingleLine(true)
            maxLines = 1
            setTextColor(titleC)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(cardC)
                setStroke(dp(1), if (night) Color.parseColor("#22FFFFFF") else Color.parseColor("#18000000"))
            }
            setPadding(dp(12), 0, dp(12), 0)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(pageBg)
            }
            addView(TextView(activity).apply {
                text = "侧栏签名"
                setTextColor(titleC)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(activity).apply {
                text = "显示在昵称下方，点击侧边栏签名可随时修改"
                setTextColor(subC)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(5), 0, dp(14))
            })
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
        val actions = LinearLayout(activity).apply {
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        fun action(label: String, filled: Boolean, click: () -> Unit): TextView = TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setTextColor(if (filled) Color.WHITE else titleC)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(if (filled) accent else if (night) Color.parseColor("#22FFFFFF") else Color.parseColor("#10000000"))
            }
            isClickable = true
            setOnClickListener { click() }
        }
        actions.addView(action("取消", false) { dialog.dismiss() })
        actions.addView(action("保存", true) {
            val value = input.text?.toString().orEmpty().trim().ifBlank { HomeDrawerConfig.DEFAULT_SIGNATURE }
            HomeDrawerConfig.saveSignature(value)
            target.text = value
            toast(activity, "签名已保存")
            dialog.dismiss()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        root.addView(actions)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((activity.resources.displayMetrics.widthPixels * 0.82f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.45f)
        }
    }

    /** 侧栏编辑自定义状态 */
    private fun showStatusEditor(
        activity: Activity,
        target: TextView,
        night: Boolean,
        titleC: Int,
        subC: Int,
        cardC: Int,
        accent: Int,
        pageBg: Int
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val input = android.widget.EditText(activity).apply {
            setText(HomeDrawerConfig.loadCustomStatus())
            setSelectAllOnFocus(false)
            setSingleLine(true)
            maxLines = 1
            setTextColor(titleC)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(cardC)
                setStroke(dp(1), if (night) Color.parseColor("#22FFFFFF") else Color.parseColor("#18000000"))
            }
            setPadding(dp(12), 0, dp(12), 0)
            hint = "为空则读取微信实际状态"
            setHintTextColor(subC)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(pageBg)
            }
            addView(TextView(activity).apply {
                text = "自定义状态"
                setTextColor(titleC)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(activity).apply {
                text = "设置后优先显示，留空则自动读取微信真实状态"
                setTextColor(subC)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(5), 0, dp(14))
            })
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
        val actions = LinearLayout(activity).apply {
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        fun action(label: String, filled: Boolean, click: () -> Unit): TextView = TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setTextColor(if (filled) Color.WHITE else titleC)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(if (filled) accent else if (night) Color.parseColor("#22FFFFFF") else Color.parseColor("#10000000"))
            }
            isClickable = true
            setOnClickListener { click() }
        }
        actions.addView(action("清除", false) {
            input.setText("")
        })
        actions.addView(action("保存", true) {
            val value = input.text?.toString().orEmpty().trim()
            HomeDrawerConfig.saveCustomStatus(value)
            target.text = "微信用户 · ${statusProvider?.invoke()?.ifBlank { "在线" } ?: "在线"}"
            toast(activity, if (value.isBlank()) "已恢复读取微信状态" else "自定义状态已保存")
            dialog.dismiss()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        root.addView(actions)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((activity.resources.displayMetrics.widthPixels * 0.82f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.45f)
        }
    }

    private fun showShortcutPicker(
        activity: Activity,
        night: Boolean,
        titleC: Int,
        subC: Int,
        cardC: Int,
        accent: Int,
        pageBg: Int,
        onDone: () -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val all = HomeDrawerConfig.Shortcut.entries
        val selected = HomeDrawerConfig.loadShortcuts().toMutableList()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(pageBg)
            }
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }

        // 标题行
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(activity).apply {
            text = "编辑快捷"
            setTextColor(titleC)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        val countTv = TextView(activity).apply {
            text = "已选 ${selected.size}/${HomeDrawerConfig.MAX_SHORTCUTS} · 点选切换"
            setTextColor(subC)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
        }
        titles.addView(countTv)
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head)
        root.addView(View(activity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun refreshCount() {
            countTv.text = "已选 ${selected.size}/${HomeDrawerConfig.MAX_SHORTCUTS} · 点选切换"
        }

        fun paintRow(row: View, on: Boolean) {
            val card = row as LinearLayout
            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(cardC)
                if (on) {
                    setStroke(dp(1), accent)
                } else {
                    setStroke(dp(1), if (night) Color.parseColor("#22FFFFFF") else Color.parseColor("#0F000000"))
                }
            }
            val check = card.findViewWithTag<TextView>("check")
            check?.text = if (on) "✓" else ""
            check?.setTextColor(if (on) accent else subC)
            check?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (on) {
                    setColor(Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent)))
                } else {
                    setColor(if (night) Color.parseColor("#18FFFFFF") else Color.parseColor("#0A000000"))
                }
            }
        }

        all.forEach { s ->
            val on = selected.any { it == s }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = true
            }
            row.addView(TextView(activity).apply {
                text = s.emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(32), dp(32)))

            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            texts.addView(TextView(activity).apply {
                text = s.title
                setTextColor(titleC)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            })
            texts.addView(TextView(activity).apply {
                text = s.subtitle
                setTextColor(subC)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val check = TextView(activity).apply {
                tag = "check"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(check, LinearLayout.LayoutParams(dp(28), dp(28)))

            paintRow(row, on)
            row.setOnClickListener {
                val currentlyOn = selected.any { it == s }
                if (currentlyOn) {
                    if (selected.size <= 1) {
                        toast(activity, "至少保留 1 个快捷")
                        return@setOnClickListener
                    }
                    selected.removeAll { it == s }
                    paintRow(row, false)
                } else {
                    if (selected.size >= HomeDrawerConfig.MAX_SHORTCUTS) {
                        toast(activity, "最多选 ${HomeDrawerConfig.MAX_SHORTCUTS} 个")
                        return@setOnClickListener
                    }
                    selected.add(s)
                    paintRow(row, true)
                }
                refreshCount()
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
        }
        root.addView(list)

        // 底部按钮
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        fun btn(label: String, filled: Boolean, click: () -> Unit): TextView {
            return TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(18), dp(10), dp(18), dp(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    if (filled) {
                        setColor(accent)
                    } else {
                        setColor(if (night) Color.parseColor("#22FFFFFF") else Color.parseColor("#0F000000"))
                    }
                }
                setTextColor(if (filled) Color.WHITE else titleC)
                isClickable = true
                isFocusable = true
                setOnClickListener { click() }
            }
        }
        actions.addView(
            btn("取消", false) { dialog.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        )
        actions.addView(btn("保存", true) {
            if (selected.isEmpty()) {
                toast(activity, "至少保留 1 个快捷")
                return@btn
            }
            HomeDrawerConfig.saveShortcuts(selected.toList())
            toast(activity, "已保存 ${selected.size} 个快捷")
            dialog.dismiss()
            onDone()
        })
        root.addView(actions)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.86f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // 轻微遮罩
            setDimAmount(0.45f)
        }
        dialog.show()
    }

    private fun sectionLabel(
        parent: LinearLayout,
        ctx: Context,
        text: String,
        color: Int,
        density: Float
    ) {
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val night = isNight(ctx)
        val accent = if (night) Color.parseColor("#7FBF90") else Color.parseColor("#2F8A4E")
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(8))
        }
        row.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(accent)
            }
        }, LinearLayout.LayoutParams(dp(3), dp(12)).apply { rightMargin = dp(8) })
        row.addView(TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.04f
        })
        parent.addView(row)
    }

    /** 标题 + 右侧操作图标（用于「快捷」编辑） */
    private fun sectionLabelWithAction(
        parent: LinearLayout,
        ctx: Context,
        text: String,
        color: Int,
        accent: Int,
        density: Float,
        actionEmoji: String,
        actionDesc: String,
        onAction: () -> Unit
    ) {
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(2), dp(8))
        }
        row.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(accent)
            }
        }, LinearLayout.LayoutParams(dp(3), dp(12)).apply { rightMargin = dp(8) })
        row.addView(
            TextView(ctx).apply {
                this.text = text
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.04f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val btn = TextView(ctx).apply {
            this.text = actionEmoji
            contentDescription = actionDesc
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    Color.argb(24, Color.red(accent), Color.green(accent), Color.blue(accent))
                )
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onAction() }
        }
        if (Build.VERSION.SDK_INT >= 21) {
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            btn.background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                    Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))
                ),
                btn.background,
                mask
            )
        }
        row.addView(btn, LinearLayout.LayoutParams(dp(28), dp(28)))
        parent.addView(row)
    }

    private fun menuCard(
        parent: LinearLayout,
        ctx: Context,
        cardBg: Int,
        titleC: Int,
        subC: Int,
        density: Float,
        items: List<MenuRow>
    ) {
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val night = isNight(ctx)
        val accent = if (night) Color.parseColor("#7FBF90") else Color.parseColor("#2F8A4E")
        val strokeColor = if (night) Color.parseColor("#1AFFFFFF") else Color.parseColor("#12000000")

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(cardBg)
                setStroke(dp(1), strokeColor)
            }
            if (Build.VERSION.SDK_INT >= 21) elevation = dp(2).toFloat()
        }
        items.forEachIndexed { index, item ->
            if (index > 0) {
                card.addView(
                    View(ctx).apply { setBackgroundColor(if (night) Color.parseColor("#14FFFFFF") else Color.parseColor("#08000000")) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        leftMargin = dp(62)
                    }
                )
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                if (Build.VERSION.SDK_INT >= 21) {
                    val rippleColor = Color.parseColor(if (night) "#33FFFFFF" else "#1F000000")
                    val r = dp(16).toFloat()
                    val topR = if (index == 0) r else 0f
                    val botR = if (index == items.size - 1) r else 0f
                    val radii = floatArrayOf(topR, topR, topR, topR, botR, botR, botR, botR)
                    val mask = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadii = radii
                        setColor(Color.WHITE)
                    }
                    val content = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadii = radii
                        setColor(cardBg)
                    }
                    background = RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
                } else {
                    val out = TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                    if (out.resourceId != 0) setBackgroundResource(out.resourceId)
                }
                setOnClickListener { item.onClick() }
            }

            // 38dp 精致图标微章底座
            val iconBadge = TextView(ctx).apply {
                text = item.emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(11).toFloat()
                    setColor(Color.argb(22, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
            }
            row.addView(iconBadge, LinearLayout.LayoutParams(dp(38), dp(38)))

            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
            }
            texts.addView(TextView(ctx).apply {
                text = item.title
                setTextColor(titleC)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            texts.addView(TextView(ctx).apply {
                text = item.subtitle
                setTextColor(subC)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(ctx).apply {
                text = "›"
                setTextColor(subC)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            card.addView(row)
        }
        parent.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )
    }

    private fun openUrl(ctx: Context, url: String) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            toast(ctx, "无法打开链接")
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private fun softIn() = if (Build.VERSION.SDK_INT >= 21) {
        PathInterpolator(0.22f, 1f, 0.36f, 1f)
    } else DecelerateInterpolator(1.6f)

    private fun softOut() = if (Build.VERSION.SDK_INT >= 21) {
        PathInterpolator(0.4f, 0f, 0.2f, 1f)
    } else DecelerateInterpolator(1.2f)

    private fun isNight(ctx: Context): Boolean {
        val mode = ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun statusBarHeight(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) return ctx.resources.getDimensionPixelSize(id)
        return (28 * ctx.resources.displayMetrics.density).toInt()
    }

    private fun findOverlay(decor: ViewGroup): View? {
        for (i in 0 until decor.childCount) {
            if (decor.getChildAt(i).tag == OVERLAY_TAG) return decor.getChildAt(i)
        }
        return null
    }
}
