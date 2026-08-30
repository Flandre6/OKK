package com.OKK.yes.core.hooks.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 纯代码绘制的矢量图标 Drawable。
 * 显式启用 EVEN_ODD 填充法则，保证矢量路径内部掏空镂空正常渲染（消除黑块 Blob 异常）。
 */
class OkkIconDrawable(
    outlinedPathStr: String,
    filledPathStr: String
) : Drawable() {

    private val outlinedPath: Path = PathParser.createPathFromPathData(outlinedPathStr).apply {
        fillType = Path.FillType.EVEN_ODD
    }
    private val filledPath: Path = PathParser.createPathFromPathData(filledPathStr).apply {
        fillType = Path.FillType.EVEN_ODD
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val matrix = Matrix()
    private val drawPath = Path().apply {
        fillType = Path.FillType.EVEN_ODD
    }

    var isFilled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    var tintColor: Int = Color.BLACK
        set(value) {
            if (field != value) {
                field = value
                paint.color = value
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val srcPath = if (isFilled) filledPath else outlinedPath
        matrix.reset()
        val scaleX = b.width().toFloat() / 24f
        val scaleY = b.height().toFloat() / 24f
        matrix.postScale(scaleX, scaleY)
        matrix.postTranslate(b.left.toFloat(), b.top.toFloat())

        drawPath.reset()
        drawPath.fillType = Path.FillType.EVEN_ODD
        srcPath.transform(matrix, drawPath)
        canvas.drawPath(drawPath, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        // 1. 微信 (Chat Bubble)
        private const val CHAT_OUTLINED = "M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM20,16H5.17L4,17.17V4h16V16z"
        private const val CHAT_FILLED = "M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2z"

        // 2. 通讯录 (Contacts)
        private const val CONTACTS_OUTLINED = "M12,4c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3zM12,8c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1zM12,13c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4zM6.9,17c0.47,-0.74 2.82,-1.5 5.1,-1.5s4.63,0.76 5.1,1.5H6.9z"
        private const val CONTACTS_FILLED = "M12,4c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3zM12,13c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"

        // 3. 发现 (Discover / Compass)
        private const val DISCOVER_OUTLINED = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM7,17l3.2,-6.8L17,7l-3.2,6.8L7,17zM12,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5z"
        private const val DISCOVER_FILLED = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM7,17l3.2,-6.8L17,7l-3.2,6.8L7,17z"

        // 4. 我 (Me / Profile)
        private const val ME_OUTLINED = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,4c4.41,0 8,3.59 8,8 0,1.82 -0.61,3.49 -1.64,4.83C17.18,15.24 14.71,14.5 12,14.5s-5.18,0.74 -6.36,2.33C4.61,15.49 4,13.82 4,12c0,-4.41 3.59,-8 8,-8zM12,6c-1.93,0 -3.5,1.57 -3.5,3.5S10.07,13 12,13s3.5,-1.57 3.5,-3.5S13.93,6 12,6z"
        private const val ME_FILLED = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,6c1.93,0 3.5,1.57 3.5,3.5S13.93,13 12,13s-3.5,-1.57 -3.5,-3.5S10.07,6 12,6zm0,14.2c2.5,0 4.71,-1.28 6,-3.22 -0.03,-1.99 -4,-3.08 -6,-3.08s-5.97,1.09 -6,3.08c1.29,1.94 3.5,3.22 6,3.22z"

        fun createForTab(index: Int): OkkIconDrawable = when (index) {
            0 -> OkkIconDrawable(CHAT_OUTLINED, CHAT_FILLED)
            1 -> OkkIconDrawable(CONTACTS_OUTLINED, CONTACTS_FILLED)
            2 -> OkkIconDrawable(DISCOVER_OUTLINED, DISCOVER_FILLED)
            else -> OkkIconDrawable(ME_OUTLINED, ME_FILLED)
        }
    }
}

/**
 * 微信主界面悬浮胶囊底栏（View 100% 对齐 Compose 移植版）。
 *
 * 观感与动画完全对齐设置内 Compose FloatingBottomBar：
 * - 64dp 高度、24dp 两侧边距、32dp 外胶囊圆角、26dp 内衬指示块圆角
 * - 1dp 精准描边 + 柔和影深
 * - 22dp 掏空渲染 OkkIconDrawable (微信/通讯录/发现/我) + 11sp 标签文本
 * - 激活态 1.12x 缩放 + 切换 filled 实心图标 + 主题绿高亮
 * - 280ms FastOutSlowIn 弹簧切换 + Pager 零延迟跟手
 */
class OKKFloatingBottomBar(
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
        private const val BAR_H_DP = 64f
        private const val ANIM_MS = 240L
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun dpI(v: Float): Int = (v * density + 0.5f).toInt()

    private val night: Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    // 100% 对齐 WeKit (wcx FloatingBottomBar & ReplaceNavigationBar) 配色与度量
    private val trackColor =
        if (night) Color.parseColor("#EA191919") else Color.parseColor("#EAF7F7F7")
    private val indicatorColor =
        if (night) Color.parseColor("#3307C160") else Color.parseColor("#2407C160")
    private val activeColor =
        if (night) Color.parseColor("#34C759") else Color.parseColor("#07C160")
    private val inactiveColor =
        if (night) Color.parseColor("#999999") else Color.parseColor("#75181818")
    private val badgeColor = Color.parseColor("#FF3B30")
    private val trackStrokeColor =
        if (night) Color.parseColor("#20FFFFFF") else Color.parseColor("#14000000")
    private val softShadowColor =
        if (night) Color.parseColor("#40000000") else Color.parseColor("#1A000000")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = trackColor
        style = Paint.Style.FILL
    }
    private val trackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = trackStrokeColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1.0f)
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = indicatorColor
        style = Paint.Style.FILL
    }
    private val softShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = softShadowColor
        style = Paint.Style.FILL
    }

    private val trackRect = RectF()
    private val indicatorRect = RectF()
    private val shadowRect = RectF()

    private val labelViews = arrayOfNulls<TextView>(TAB_COUNT)
    private val iconViews = arrayOfNulls<ImageView>(TAB_COUNT)
    private val iconDrawables = arrayOfNulls<OkkIconDrawable>(TAB_COUNT)
    private val badgeViews = arrayOfNulls<TextView>(TAB_COUNT)
    private val tabCols = arrayOfNulls<View>(TAB_COUNT)

    /** 指示块中心位置（0..3） */
    private var indicatorPos = 0f
    private var animProgress = 1f
    private var animFrom = 0f
    private var animTo = 0f
    private var selectedIndex = 0
    private var animator: ValueAnimator? = null
    private var lastHomeTapUptime = 0L

    private val unread = IntArray(TAB_COUNT)
    private val friendDot = booleanArrayOf(false, false, false, false)

    // 标准 Miuix Decelerate 缓动曲线（与 Compose 100% 同跟手比例）
    private val fastEase = PathInterpolator(0.25f, 1.25f, 0.25f, 1.0f)
    private val argbEval = ArgbEvaluator()

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_NONE, null)
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false

        // 两侧 16dp 饱满边距
        val side = dpI(16f)
        setPadding(side, dpI(4f), side, dpI(12f))

        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpI(BAR_H_DP)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            setPadding(dpI(4f), dpI(4f), dpI(4f), dpI(4f))
            isClickable = false
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }
        for (i in 0 until TAB_COUNT) {
            tabRow.addView(buildTab(i))
        }
        addView(tabRow)
        // 初始化时设置选中项图标的缩放状态
        for (i in 0 until TAB_COUNT) {
            val active = i == selectedIndex
            iconViews[i]?.scaleX = if (active) 1.20f else 1.0f
            iconViews[i]?.scaleY = if (active) 1.20f else 1.0f
        }
        
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

        // 图标 + 文字 纵向居中组合
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val iconDrawable = OkkIconDrawable.createForTab(index)
        iconDrawables[index] = iconDrawable

        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpI(22f), dpI(22f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setImageDrawable(iconDrawable)
            isClickable = false
        }
        iconViews[index] = icon
        inner.addView(icon)

        val label = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpI(2f)
            }
            text = labels.getOrElse(index) { DEFAULT_LABELS.getOrElse(index) { "" } }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(inactiveColor)
            maxLines = 1
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            visibility = if (showLabels) VISIBLE else INVISIBLE
        }
        labelViews[index] = label
        inner.addView(label)
        col.addView(inner)

        val badge = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpI(4f)
                marginEnd = dpI(8f)
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

    @Volatile var isBarHidden: Boolean = false
        private set

    fun hideBar(animated: Boolean = true) {
        if (isBarHidden) return
        isBarHidden = true
        val targetY = (height.coerceAtLeast(dpI(BAR_H_DP)) + dpI(40f)).toFloat()
        if (animated) {
            animate().cancel()
            animate().translationY(targetY)
                .setDuration(220L)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0.2f, 1f))
                .start()
        } else {
            translationY = targetY
        }
    }

    fun showBar(animated: Boolean = true) {
        if (!isBarHidden) return
        isBarHidden = false
        if (animated) {
            animate().cancel()
            animate().translationY(0f)
                .setDuration(220L)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0.2f, 1f))
                .start()
        } else {
            translationY = 0f
        }
    }

    private fun updateIconScale(index: Int, active: Boolean) {
        val iv = iconViews[index] ?: return
        iv.animate().cancel()
        val target = if (active) 1.20f else 1.0f
        iv.animate()
            .scaleX(target).scaleY(target)
            .setDuration(if (active) 320L else 200L)
            .setInterpolator(if (active) android.view.animation.OvershootInterpolator(1.8f) else fastEase)
            .start()
    }

    private fun handleClick(index: Int) {
        val now = SystemClock.uptimeMillis()
        if (index == selectedIndex) {
            pulseTab(index)
            onTabReselect(index)
            lastHomeTapUptime = if (index == 0) now else 0L
            return
        }
        lastHomeTapUptime = if (index == 0) now else 0L
        pulseTab(index)
        
        val oldIndex = selectedIndex
        selectedIndex = index
        updateIconScale(oldIndex, false)
        
        animateTo(index.toFloat())
        onTabClick(index)
    }

    fun setSelectedIndex(index: Int, animate: Boolean = true) {
        if (isBarHidden) showBar(animated = true)
        val i = index.coerceIn(0, TAB_COUNT - 1)
        if (i != selectedIndex) {
            val oldIndex = selectedIndex
            selectedIndex = i
            updateIconScale(oldIndex, false)
            updateIconScale(selectedIndex, true)
        }
        if (abs(indicatorPos - i) < 0.01f) return
        
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
        // 当点击产生的平滑滑动动画正在运行时，不被 ViewPager 的单帧 onPageScrolled 打断打死
        if (animator?.isRunning == true) return
        indicatorPos = progress.coerceIn(0f, (TAB_COUNT - 1).toFloat())
        animFrom = indicatorPos
        animTo = indicatorPos
        animProgress = 1f
        val nearest = indicatorPos.roundToInt().coerceIn(0, TAB_COUNT - 1)
        if (nearest != selectedIndex) {
            val oldIndex = selectedIndex
            selectedIndex = nearest
            updateIconScale(oldIndex, false)
            updateIconScale(selectedIndex, true)
        }
        applySelectionVisuals(instant = true)
        invalidate()
    }

    fun setMainUnread(count: Int) {
        val c = max(0, count)
        if (unread[0] == c) return
        unread[0] = c
        refreshBadge(0)
    }

    fun setContactUnread(count: Int) {
        val c = max(0, count)
        if (unread[1] == c) return
        unread[1] = c
        refreshBadge(1)
    }

    fun setContactDot(show: Boolean) {
        if (friendDot[1] == show) return
        friendDot[1] = show
        refreshBadge(1)
    }

    fun setFriendUnread(count: Int) {
        val c = max(0, count)
        if (unread[2] == c) return
        unread[2] = c
        refreshBadge(2)
    }

    fun setFriendDot(show: Boolean) {
        if (friendDot[2] == show) return
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
        val dist = abs(target - start)
        val duration = (ANIM_MS + dist * 30L).toLong().coerceIn(240L, 380L)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = fastEase
            addUpdateListener {
                animProgress = it.animatedValue as Float
                indicatorPos = animFrom + (animTo - animFrom) * animProgress
                applySelectionVisuals(instant = false)
                invalidate()
            }
            start()
        }
    }

    private fun pulseTab(index: Int) {
        val iv = iconViews[index] ?: return
        iv.animate().cancel()
        iv.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(80L)
            .withEndAction {
                updateIconScale(index, true)
            }
            .start()
    }

    /**
     * 图标/文字颜色、字重随选中态平滑变化（对齐 Compose Crossfade）。
     * @param instant true=直接到位；false=按与指示块接近度插值
     */
    private fun applySelectionVisuals(instant: Boolean) {
        for (i in 0 until TAB_COUNT) {
            val tv = labelViews[i] ?: continue
            val iv = iconViews[i] ?: continue
            val iconDrawable = iconDrawables[i] ?: continue

            val proximity = (1f - min(1f, abs(indicatorPos - i))).coerceIn(0f, 1f)
            val t = proximity // 线性平滑渐变，消除二次方二次落差导致的后半段剧烈闪变

            val color = argbEval.evaluate(t, inactiveColor, activeColor) as Int
            tv.setTextColor(color)
            tv.typeface = if (t > 0.5f) {
                Typeface.create("sans-serif-medium", Typeface.BOLD)
            } else {
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            tv.alpha = 0.78f + 0.22f * t

            // 纯代码矢量 Drawable：切换 filled/outlined + 着色
            iconDrawable.isFilled = t > 0.5f
            iconDrawable.tintColor = color
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
            // 柔和自然落体阴影
            softShadowPaint.alpha = if (night) 30 else 16
            shadowRect.set(left, top + dp(2f), right, bottom + dp(4f))
            val sr = shadowRect.height() / 2f
            canvas.drawRoundRect(shadowRect, sr, sr, softShadowPaint)

            // 胶囊轨道背景与 1dp 精准边缘线描边
            trackRect.set(left, top, right, bottom)
            val trackR = trackRect.height() / 2f
            canvas.drawRoundRect(trackRect, trackR, trackR, trackPaint)
            canvas.drawRoundRect(trackRect, trackR, trackR, trackStrokePaint)

            // 内部平滑滑块指示器 (饱满填满，主题色 15% alpha)
            val innerPad = dp(2f)
            val contentW = right - left - innerPad * 2
            val tabW = contentW / TAB_COUNT
            val indInsetY = dp(2f)
            val indInsetX = dp(1f)

            // 100% 对齐 WeKit DampedDragAnimation 弹性凸起与动态拉伸
            val dist = abs(animTo - animFrom)
            val stretchAmt = kotlin.math.sin(animProgress * Math.PI.toFloat()) * dist * tabW * 0.38f
            val stretchH = kotlin.math.sin(animProgress * Math.PI.toFloat()) * dp(2.2f)

            val centerX = left + innerPad + indicatorPos * tabW + tabW / 2f
            val halfBase = (tabW - indInsetX * 2) / 2f
            val halfW = halfBase + stretchAmt / 2f

            val indLeft = (centerX - halfW).coerceAtLeast(left + innerPad + dp(1f))
            val indRight = (centerX + halfW).coerceAtMost(right - innerPad - dp(1f))
            val indTop = (top + indInsetY - stretchH / 2f).coerceAtLeast(top + dp(1f))
            val indBottom = (bottom - indInsetY + stretchH / 2f).coerceAtMost(bottom - dp(1f))

            indicatorRect.set(indLeft, indTop, indRight, indBottom)
            indicatorRect.set(indLeft, top + indInsetY, indRight, bottom - indInsetY)
            val indR = indicatorRect.height() / 2f
            canvas.drawRoundRect(indicatorRect, indR, indR, indicatorPaint)
        }
        super.dispatchDraw(canvas)
    }
}
