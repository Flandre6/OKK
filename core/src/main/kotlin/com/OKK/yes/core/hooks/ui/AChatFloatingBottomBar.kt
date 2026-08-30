package com.OKK.yes.core.hooks.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * 微信主界面悬浮胶囊底栏（View）。
 *
 * 观感目标：
 * - 半透明柔白轨道 + 浅描边
 * - 柔蓝指示块，滑动时带轻微拉伸（更圆滑）
 * - 标题颜色/字重/缩放平滑过渡
 * - 根布局不拦截空白触摸
 */
class AChatFloatingBottomBar(
    context: Context,
    private val labels: List<String> = DEFAULT_LABELS,
    private val showLabels: Boolean = true,
    private val showBadge: Boolean = true,
    private val onTabClick: (index: Int) -> Unit,
    private val onTabReselect: (index: Int) -> Unit = onTabClick
) : FrameLayout(context) {

    companion object {
        val DEFAULT_LABELS = listOf("微信", "通讯录", "发现", "我")
        private const val TAB_COUNT = 4
        private const val DOUBLE_TAP_MS = 300L
        private const val BAR_H_DP = 50f
        /** 滑动总时长：偏长更柔 */
        private const val ANIM_MS = 420L
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun dpI(v: Float): Int = (v * density + 0.5f).toInt()

    private val night: Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    // 更柔：半透明轨道、浅指示块、不刺眼的蓝
    // 轨道略实一点，避免半透明叠在微信底白条上「透出第二层」
    private val trackColor =
        if (night) Color.parseColor("#F21C1C1E") else Color.parseColor("#F7FFFFFF")
    private val indicatorColor =
        if (night) Color.parseColor("#33A8C7FF") else Color.parseColor("#332B7FFF")
    private val indicatorHighlight =
        if (night) Color.parseColor("#18FFFFFF") else Color.parseColor("#22FFFFFF")
    private val activeColor =
        if (night) Color.parseColor("#8BB8FF") else Color.parseColor("#3A7AFA")
    private val inactiveColor =
        if (night) Color.parseColor("#99EBEBF5") else Color.parseColor("#8A3C3C43")
    private val badgeColor = Color.parseColor("#FF453A")
    private val trackStrokeColor =
        if (night) Color.parseColor("#1AFFFFFF") else Color.parseColor("#0F000000")
    private val softShadowColor =
        if (night) Color.parseColor("#40000000") else Color.parseColor("#14000000")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = trackColor
        style = Paint.Style.FILL
    }
    private val trackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = trackStrokeColor
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = indicatorColor
        style = Paint.Style.FILL
    }
    private val indicatorHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = indicatorHighlight
        style = Paint.Style.FILL
    }
    private val softShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = softShadowColor
        style = Paint.Style.FILL
    }

    private val trackRect = RectF()
    private val indicatorRect = RectF()
    private val highlightRect = RectF()
    private val shadowRect = RectF()

    private val labelViews = arrayOfNulls<TextView>(TAB_COUNT)
    private val badgeViews = arrayOfNulls<TextView>(TAB_COUNT)
    private val tabCols = arrayOfNulls<View>(TAB_COUNT)

    /** 指示块中心位置（0..3） */
    private var indicatorPos = 0f
    /** 滑动进度 0..1，用于拉伸与标签过渡 */
    private var animProgress = 1f
    private var animFrom = 0f
    private var animTo = 0f
    private var selectedIndex = 0
    private var animator: ValueAnimator? = null
    private var lastHomeTapUptime = 0L

    private val unread = IntArray(TAB_COUNT)
    private val friendDot = booleanArrayOf(false, false, false, false)
    // 柔出：快进缓出，末端几乎无回弹，整体更圆滑
    private val softEase = PathInterpolator(0.2f, 0.9f, 0.2f, 1f)
    private val argbEval = ArgbEvaluator()

    init {
        setWillNotDraw(false)
        // 不用 SOFTWARE 图层（部分 OEM 会整屏发黑），阴影用手绘半透明层
        setLayerType(LAYER_TYPE_NONE, null)
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false

        // 侧边距 → 胶囊略短于全宽；上下留白更柔
        val side = dpI(28f)
        setPadding(side, dpI(8f), side, dpI(14f))

        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpI(BAR_H_DP)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            setPadding(dpI(5f), dpI(3f), dpI(5f), dpI(3f))
            isClickable = false
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }
        for (i in 0 until TAB_COUNT) {
            tabRow.addView(buildTab(i))
        }
        addView(tabRow)
        applySelectionVisuals(instant = true)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private fun buildTab(index: Int): View {
        val col = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            isClickable = true
            isFocusable = true
            setOnClickListener { handleClick(index) }
            minimumWidth = dpI(56f)
            clipChildren = false
            clipToPadding = false
        }
        tabCols[index] = col

        val label = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            text = labels.getOrElse(index) { DEFAULT_LABELS.getOrElse(index) { "" } }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(inactiveColor)
            maxLines = 1
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            visibility = if (showLabels) VISIBLE else INVISIBLE
        }
        labelViews[index] = label
        col.addView(label)

        val badge = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpI(3f)
                marginEnd = dpI(5f)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dpI(16f)
            minHeight = dpI(16f)
            visibility = GONE
            includeFontPadding = false
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9f)
                setColor(badgeColor)
            }
            setPadding(dpI(4f), dpI(1f), dpI(4f), dpI(1f))
        }
        badgeViews[index] = badge
        col.addView(badge)
        return col
    }

    fun updateLabels(newLabels: List<String>) {
        for (i in 0 until TAB_COUNT) {
            labelViews[i]?.text = newLabels.getOrElse(i) {
                DEFAULT_LABELS.getOrElse(i) { "" }
            }
        }
    }

    private fun handleClick(index: Int) {
        val now = SystemClock.uptimeMillis()
        if (index == selectedIndex) {
            // 轻点反馈：轻微缩放回弹
            pulseTab(index)
            onTabReselect(index)
            lastHomeTapUptime = if (index == 0) now else 0L
            return
        }
        lastHomeTapUptime = if (index == 0) now else 0L
        selectedIndex = index
        animateTo(index.toFloat())
        onTabClick(index)
    }

    fun setSelectedIndex(index: Int, animate: Boolean = true) {
        val i = index.coerceIn(0, TAB_COUNT - 1)
        if (i == selectedIndex && abs(indicatorPos - i) < 0.01f) return
        selectedIndex = i
        if (animate) animateTo(i.toFloat()) else {
            animator?.cancel()
            indicatorPos = i.toFloat()
            animFrom = i.toFloat()
            animTo = i.toFloat()
            animProgress = 1f
            applySelectionVisuals(instant = true)
            invalidate()
        }
    }

    fun setScrollProgress(progress: Float) {
        animator?.cancel()
        indicatorPos = progress.coerceIn(0f, (TAB_COUNT - 1).toFloat())
        animFrom = indicatorPos
        animTo = indicatorPos
        animProgress = 1f
        val nearest = indicatorPos.roundToInt().coerceIn(0, TAB_COUNT - 1)
        if (nearest != selectedIndex) {
            selectedIndex = nearest
        }
        applySelectionVisuals(instant = true)
        invalidate()
    }

    fun setMainUnread(count: Int) {
        unread[0] = max(0, count)
        refreshBadge(0)
    }

    fun setFriendUnread(count: Int) {
        unread[2] = max(0, count)
        refreshBadge(2)
    }

    fun setFriendDot(show: Boolean) {
        friendDot[2] = show
        refreshBadge(2)
    }

    private fun refreshBadge(index: Int) {
        if (!showBadge) return
        val badge = badgeViews[index] ?: return
        val c = unread[index]
        when {
            c > 0 -> {
                badge.visibility = VISIBLE
                badge.text = if (c > 99) "99+" else c.toString()
                val minW = when {
                    c > 99 -> dpI(26f)
                    c > 9 -> dpI(20f)
                    else -> dpI(16f)
                }
                badge.minWidth = minW
                badge.minHeight = dpI(16f)
                badge.setPadding(
                    if (c > 9) dpI(5f) else dpI(4f),
                    dpI(1f),
                    if (c > 9) dpI(5f) else dpI(4f),
                    dpI(1f)
                )
                badge.requestLayout()
            }
            friendDot[index] -> {
                badge.visibility = VISIBLE
                badge.text = ""
                badge.minWidth = dpI(8f)
                badge.minHeight = dpI(8f)
                badge.setPadding(0, 0, 0, 0)
                badge.requestLayout()
            }
            else -> badge.visibility = GONE
        }
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        val start = indicatorPos
        if (abs(start - target) < 0.001f) {
            indicatorPos = target
            animProgress = 1f
            applySelectionVisuals(instant = true)
            invalidate()
            return
        }
        animFrom = start
        animTo = target
        animProgress = 0f
        // 距离越远略加长，更顺
        val dist = abs(target - start)
        val duration = (ANIM_MS + dist * 40L).toLong().coerceIn(360L, 520L)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = softEase
            addUpdateListener {
                animProgress = it.animatedValue as Float
                // 位置用同一 ease；拉伸用 sin 曲线在中段最宽
                indicatorPos = animFrom + (animTo - animFrom) * animProgress
                applySelectionVisuals(instant = false)
                invalidate()
            }
            start()
        }
    }

    /** 轻点当前 Tab：轻微缩放回弹 */
    private fun pulseTab(index: Int) {
        val v = labelViews[index] ?: return
        v.animate().cancel()
        v.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(90L)
            .withEndAction {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(softEase)
                    .start()
            }
            .start()
    }

    /**
     * 标签颜色/字重/缩放随选中态平滑变化。
     * @param instant true=直接到位；false=按 animProgress 插值
     */
    private fun applySelectionVisuals(instant: Boolean) {
        for (i in 0 until TAB_COUNT) {
            val tv = labelViews[i] ?: continue
            val selected = i == selectedIndex
            // 与指示块的接近度 0..1，用于滑动过程中柔和变色
            val proximity = 1f - min(1f, abs(indicatorPos - i))
            val t = if (instant) {
                if (selected) 1f else 0f
            } else {
                // 接近选中位时逐渐变蓝
                proximity * proximity
            }
            val color = argbEval.evaluate(t, inactiveColor, activeColor) as Int
            tv.setTextColor(color)
            tv.typeface = if (selected || t > 0.55f) {
                Typeface.create("sans-serif-medium", Typeface.BOLD)
            } else {
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            tv.alpha = 0.72f + 0.28f * t
            val scale = 1f + 0.04f * t
            tv.scaleX = scale
            tv.scaleY = scale
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.2f + 0.8f * t)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = paddingTop + paddingBottom + dpI(BAR_H_DP)
        var w = MeasureSpec.getSize(widthMeasureSpec)
        if (w <= 0) w = (parent as? View)?.width ?: 0
        if (w <= 0) w = resources.displayMetrics.widthPixels
        if (minimumWidth < w) minimumWidth = w
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = top + dp(BAR_H_DP)
        if (right > left && bottom > top) {
            // 单层极轻阴影，避免叠出「胶囊后面还有一层白底」的突兀感
            softShadowPaint.alpha = if (night) 36 else 18
            shadowRect.set(left, top + dp(1.2f), right, bottom + dp(2.2f))
            val sr = shadowRect.height() / 2f
            canvas.drawRoundRect(shadowRect, sr, sr, softShadowPaint)

            // 轨道（略提高不透明度，单层更干净）
            trackRect.set(left, top, right, bottom)
            val trackR = trackRect.height() / 2f
            canvas.drawRoundRect(trackRect, trackR, trackR, trackPaint)
            canvas.drawRoundRect(trackRect, trackR, trackR, trackStrokePaint)

            // 指示块：中段轻微拉伸，更像液体滑动
            val innerPad = dp(4f)
            val contentW = right - left - innerPad * 2
            val tabW = contentW / TAB_COUNT
            val indInsetY = dp(3.5f)
            val indInsetX = dp(3f)

            // sin 拉伸：0→1 过程中段最宽
            val stretchAmt = sin(animProgress * Math.PI.toFloat()) *
                abs(animTo - animFrom) * tabW * 0.22f

            val centerX = left + innerPad + indicatorPos * tabW + tabW / 2f
            val halfBase = (tabW - indInsetX * 2) / 2f
            val halfW = halfBase + stretchAmt / 2f

            val indLeft = (centerX - halfW).coerceAtLeast(left + innerPad + dp(1f))
            val indRight = (centerX + halfW).coerceAtMost(right - innerPad - dp(1f))
            indicatorRect.set(indLeft, top + indInsetY, indRight, bottom - indInsetY)
            val indR = indicatorRect.height() / 2f
            canvas.drawRoundRect(indicatorRect, indR, indR, indicatorPaint)

            // 顶部高光条，增加柔和玻璃感
            highlightRect.set(
                indicatorRect.left + dp(4f),
                indicatorRect.top + dp(1.5f),
                indicatorRect.right - dp(4f),
                indicatorRect.top + indicatorRect.height() * 0.38f
            )
            val hr = highlightRect.height() / 2f
            if (highlightRect.height() > 1f) {
                canvas.drawRoundRect(highlightRect, hr, hr, indicatorHighlightPaint)
            }
        }
        super.dispatchDraw(canvas)
    }
}
