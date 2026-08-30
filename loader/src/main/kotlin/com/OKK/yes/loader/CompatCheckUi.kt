package com.OKK.yes.loader

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.Dialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.OKK.yes.core.compat.CompatReport
import com.OKK.yes.core.compat.CompatReportStore
import com.OKK.yes.core.compat.FeatureCompatProbe
import com.OKK.yes.core.compat.FeatureProbeCatalog
import com.OKK.yes.core.compat.ProbeLevel
import com.OKK.yes.core.compat.ProbeResult
import com.OKK.yes.core.startup.FeatureHookRegistry
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * 首次 / 换版本：主界面弹出进度（如 3/22）→ 扫描 → 安装 → 结果列表。
 */
object CompatCheckUi {
    private const val TAG = "OKK-CompatUi"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installed = AtomicBoolean(false)
    /** 本进程是否已完成一次交互式扫描（进程重启后复位，重装微信会新进程） */
    private val sessionDone = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private val pollTicks = AtomicInteger(0)
    private var lastActivityRef: WeakReference<Activity>? = null
    private var appContext: Context? = null
    private var classLoader: ClassLoader? = null

    fun install(appContext: Context, classLoader: ClassLoader) {
        // 允许同一进程重复 install 时刷新 pending（一般只装一次）
        val first = installed.compareAndSet(false, true)
        this.appContext = appContext.applicationContext ?: appContext
        this.classLoader = classLoader
        // 每次需要交互扫描时复位 session，避免「装过一次就再也不弹」
        if (CompatReportStore.pendingInteractiveScan) {
            sessionDone.set(false)
            scanning.set(false)
        }
        xlog(
            "install first=$first interactive=${CompatReportStore.pendingInteractiveScan} " +
                "pendingDialog=${CompatReportStore.pendingDialog}"
        )
        if (first) {
            hookActivityLifecycle()
            registerAppCallbacks(appContext)
        }
        startPolling()
        mainHandler.postDelayed({ tryStart("post-1.2s") }, 1200)
        mainHandler.postDelayed({ tryStart("post-3s") }, 3000)
        mainHandler.postDelayed({ tryStart("post-6s") }, 6000)
    }

    private fun hookActivityLifecycle() {
        val after = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                onActivityVisible(act, "hook:${param.method.name}")
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", after)
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.getOrNull(0) != true) return
                        val act = param.thisObject as? Activity ?: return
                        onActivityVisible(act, "focus")
                    }
                }
            )
        }
    }

    private fun registerAppCallbacks(appContext: Context) {
        val app = appContext as? Application
            ?: (appContext.applicationContext as? Application)
            ?: return
        runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    onActivityVisible(activity, "lifecycle")
                }

                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {
                    onActivityVisible(a, "started")
                }

                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
        }
    }

    private fun startPolling() {
        pollTicks.set(0)
        val r = object : Runnable {
            override fun run() {
                if (sessionDone.get()) return
                if (!CompatReportStore.pendingInteractiveScan && !CompatReportStore.pendingDialog) return
                val n = pollTicks.incrementAndGet()
                if (n > 40) return
                findForegroundActivity()?.let { onActivityVisible(it, "poll") }
                mainHandler.postDelayed(this, 500)
            }
        }
        mainHandler.postDelayed(r, 600)
    }

    private fun onActivityVisible(activity: Activity, reason: String) {
        lastActivityRef = WeakReference(activity)
        if (!isMainUi(activity)) return
        tryStart(reason)
    }

    private fun tryStart(reason: String) {
        if (sessionDone.get()) return
        if (!CompatReportStore.pendingInteractiveScan && !CompatReportStore.pendingDialog) return
        val act = lastActivityRef?.get()?.takeIf { isMainUi(it) && !it.isFinishing }
            ?: findForegroundActivity()?.takeIf { isMainUi(it) }
            ?: return
        if (!scanning.compareAndSet(false, true)) return
        xlog("start interactive scan ($reason)")
        runInteractiveScan(act)
    }

    private fun runInteractiveScan(activity: Activity) {
        val ctx = appContext ?: activity.applicationContext
        val cl = classLoader ?: activity.classLoader
        val mp = ModulePathHolder.modulePath
        val total = FeatureProbeCatalog.all.size

        val progress = ProgressDialog(activity, total)
        mainHandler.post {
            runCatching {
                progress.show()
                xlog("progress shown async")
            }.onFailure { xlog("progress show fail: ${it.message}") }
        }

        Thread {
            val report = try {
                FeatureCompatProbe.run(
                    context = ctx,
                    classLoader = cl,
                    modulePath = mp,
                    forceDialog = true,
                    onProgress = { index, tot, title ->
                        mainHandler.post {
                            progress.update(index, tot, title)
                        }
                    },
                    onResult = { index, result ->
                        mainHandler.post {
                            progress.onResult(index, result)
                        }
                    }
                )
            } catch (t: Throwable) {
                xlog("probe crash: ${t.message}")
                mainHandler.post {
                    progress.dismissSafe()
                    Toast.makeText(activity, "适配检查失败: ${t.message}", Toast.LENGTH_LONG).show()
                    scanning.set(false)
                }
                return@Thread
            }

            // 按结果安装业务 Hook
            FeatureInstallGateway.installFeaturesOnce("after-interactive-probe")
            val merged = mergeRuntimeResult(report)

            mainHandler.post {
                progress.dismissSafe()
                sessionDone.set(true)
                CompatReportStore.pendingInteractiveScan = false
                CompatReportStore.pendingDialog = false
                CompatReportStore.markDialogShown(merged.fingerprint)
                CompatReportStore.save(merged)
                runCatching {
                    ResultDialog(activity, merged).show()
                }.onFailure {
                    Toast.makeText(
                        activity,
                        "适配完成 ${merged.summaryLine()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                scanning.set(false)
                xlog("interactive done ${merged.summaryLine()}")
            }
        }.start()
    }

    fun recheckAndShow(activity: Activity, classLoader: ClassLoader, modulePath: String?) {
        if (scanning.get()) {
            Toast.makeText(activity, "正在检查中…", Toast.LENGTH_SHORT).show()
            return
        }
        scanning.set(true)
        sessionDone.set(false)
        CompatReportStore.pendingInteractiveScan = true
        val total = FeatureProbeCatalog.all.size
        val progress = ProgressDialog(activity, total)
        progress.show()
        Thread {
            val report = FeatureCompatProbe.run(
                activity.applicationContext,
                classLoader,
                modulePath,
                forceDialog = true,
                onProgress = { index, tot, title ->
                    mainHandler.post { progress.update(index, tot, title) }
                },
                onResult = { index, result ->
                    mainHandler.post { progress.onResult(index, result) }
                }
            )
            // 手动重扫不重复 install（已装过）；仅更新报告
            val merged = mergeRuntimeResult(report)
            mainHandler.post {
                progress.dismissSafe()
                CompatReportStore.markDialogShown(merged.fingerprint)
                CompatReportStore.pendingInteractiveScan = false
                CompatReportStore.pendingDialog = false
                CompatReportStore.save(merged)
                ResultDialog(activity, merged).show()
                Toast.makeText(
                    activity,
                    "报告已更新（安装策略下次启动生效）",
                    Toast.LENGTH_LONG
                ).show()
                scanning.set(false)
                sessionDone.set(true)
            }
        }.start()
    }

    private fun isMainUi(activity: Activity): Boolean {
        val n = activity.javaClass.name
        if (n == "com.tencent.mm.ui.LauncherUI") return true
        if (n.endsWith(".LauncherUI")) return true
        return n.contains("LauncherUI") && n.contains("tencent.mm")
    }

    private fun findForegroundActivity(): Activity? {
        return runCatching {
            val atClz = Class.forName("android.app.ActivityThread")
            val at = atClz.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
            val field = at.javaClass.getDeclaredField("mActivities")
            field.isAccessible = true
            val activities = field.get(at) as? Map<*, *> ?: return null
            for (record in activities.values) {
                if (record == null) continue
                val paused = runCatching {
                    val f = record.javaClass.getDeclaredField("paused")
                    f.isAccessible = true
                    f.getBoolean(record)
                }.getOrDefault(true)
                if (paused) continue
                val act = runCatching {
                    val f = record.javaClass.getDeclaredField("activity")
                    f.isAccessible = true
                    f.get(record) as? Activity
                }.getOrNull()
                if (act != null && !act.isFinishing) return act
            }
            null
        }.getOrNull()
    }

    // ── 进度弹窗 ──────────────────────────────────────────────────────────

    private interface ProgressSink {
        fun update(index: Int, tot: Int, title: String)
        fun onResult(index: Int, result: ProbeResult)
        fun dismissSafe()
    }

    /** 采样微信主界面背景亮度，判断深色/浅色主题（比系统 uiMode 更贴合微信实际主题） */
    private fun detectNight(activity: Activity): Boolean {
        val fallback = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return runCatching {
            val root = activity.window.decorView
            root.buildDrawingCache(true)
            val bmp = root.drawingCache
            if (bmp == null || bmp.isRecycled) {
                root.destroyDrawingCache()
                return fallback
            }
            var sum = 0.0
            var n = 0
            for (fy in floatArrayOf(0.28f, 0.45f, 0.62f)) {
                for (fx in floatArrayOf(0.25f, 0.5f, 0.75f)) {
                    val x = (bmp.width * fx).toInt().coerceIn(0, bmp.width - 1)
                    val y = (bmp.height * fy).toInt().coerceIn(0, bmp.height - 1)
                    val c = bmp.getPixel(x, y)
                    sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                    n++
                }
            }
            root.destroyDrawingCache()
            (sum / n) < 115.0
        }.getOrElse { fallback }
    }

    private class ProgressDialog(
        private val host: Activity,
        private val total: Int
    ) : Dialog(host, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth), ProgressSink {

        private val isNight = detectNight(host)

        private val accent = Color.parseColor("#07C160")
        private val accentLight = Color.parseColor("#10B981")
        private val accentSoft = if (isNight) Color.parseColor("#1A2E26") else Color.parseColor("#E6F4EA")
        private val textMain = if (isNight) Color.parseColor("#F3F4F6") else Color.parseColor("#111827")
        private val textSub = if (isNight) Color.parseColor("#9CA3AF") else Color.parseColor("#6B7280")
        private val warnColor = Color.parseColor("#F59E0B")
        private val failColor = Color.parseColor("#EF4444")
        private val pendingStroke = if (isNight) Color.parseColor("#4B5563") else Color.parseColor("#D1D5DB")
        private val cardStart = if (isNight) Color.parseColor("#24252C") else Color.parseColor("#FFFFFF")
        private val cardEnd = if (isNight) Color.parseColor("#1A1B20") else Color.parseColor("#F9FAFB")
        private val cardStroke = if (isNight) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")
        private val listBg = if (isNight) Color.parseColor("#14151A") else Color.parseColor("#F3F4F6")
        private val trackColor = if (isNight) Color.parseColor("#2A2B33") else Color.parseColor("#E5E7EB")

        private lateinit var tvPercent: TextView
        private lateinit var tvCurrent: TextView
        private lateinit var wave: WaveProgressView
        private lateinit var dots: DotsLoadingView
        private lateinit var scroll: ScrollView
        private lateinit var listContainer: LinearLayout
        private val rowIconBoxes = ArrayList<FrameLayout>()
        private val rowBars = ArrayList<View>()
        private val rowStatus = ArrayList<TextView>()
        private val rowViews = ArrayList<View>()
        private var lastScrollTarget = -1

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setCancelable(false)
            setCanceledOnTouchOutside(false)

            // 设置窗口入场淡入淡出动效
            window?.setWindowAnimations(android.R.style.Animation_Dialog)
            window?.setDimAmount(0.45f)

            val pad = dp(24)
            val card = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(cardStart, cardEnd)
                ).apply {
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), cardStroke)
                }
                elevation = dp(16).toFloat()
            }

            // ── 头部：精美 OKK 徽章 + 双层标题 + 百分比 ──
            val headerRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // 精美双层 LOGO 徽章
            val badgeBox = FrameLayout(host).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(accentLight, accent)
                ).apply {
                    cornerRadius = dp(14).toFloat()
                }
            }
            badgeBox.addView(TextView(host).apply {
                text = "OKK"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
            headerRow.addView(badgeBox)

            val titleCol = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleCol.addView(label("功能适配检测", 17, true, textMain))
            titleCol.addView(space(2))
            titleCol.addView(label("OKK 探针实时深度匹配中", 11, false, textSub))
            headerRow.addView(titleCol)

            val percentCol = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            tvPercent = label("0%", 20, true, accent)
            percentCol.addView(tvPercent)
            percentCol.addView(label("已完成", 11, false, textSub))
            headerRow.addView(percentCol)

            card.addView(headerRow)
            card.addView(space(18))

            // ── 极光波浪进度条 ──
            wave = WaveProgressView(host).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(24)
                )
                setTrackColor(trackColor)
            }
            card.addView(wave)
            card.addView(space(16))

            // ── 功能检测列表 ──
            scroll = ScrollView(host).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(280)
                )
                isVerticalScrollBarEnabled = false
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(listBg)
                }
            }
            listContainer = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            for (i in 0 until total.coerceAtLeast(1)) {
                val title = FeatureProbeCatalog.titles.getOrNull(i) ?: "功能 ${i + 1}"
                listContainer.addView(buildRow(title))
            }
            scroll.addView(listContainer)
            card.addView(scroll)
            card.addView(space(16))

            // ── 底部状态行 ──
            val bottomRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            dots = DotsLoadingView(host).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(16))
            }
            bottomRow.addView(dots)
            tvCurrent = label("准备开始检测…", 12, false, textSub).apply {
                setPadding(dp(8), 0, 0, 0)
            }
            bottomRow.addView(tvCurrent)
            card.addView(bottomRow)

            val wrap = FrameLayout(host).apply {
                setPadding(dp(20), dp(20), dp(20), dp(20))
                addView(card)
            }
            setContentView(wrap)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }

        private fun buildRow(title: String): View {
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            val bar = View(host).apply {
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(16)).apply {
                    marginEnd = dp(10)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(accent)
                }
                visibility = View.INVISIBLE
            }
            row.addView(bar)
            rowBars.add(bar)

            val iconBox = FrameLayout(host).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            iconBox.addView(makePendingIcon())
            row.addView(iconBox)
            rowIconBoxes.add(iconBox)

            row.addView(label(title, 13, false, textMain).apply {
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            val status = label("", 11, false, textSub)
            row.addView(status)
            rowStatus.add(status)

            rowViews.add(row)
            return row
        }

        private fun makePendingIcon(): View = View(host).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(1.5f).toInt(), pendingStroke)
            }
            layoutParams = FrameLayout.LayoutParams(dp(10), dp(10)).apply {
                gravity = Gravity.CENTER
            }
        }

        override fun update(index: Int, tot: Int, title: String) {
            if (!isShowing) return
            runCatching {
                wave.progress = if (tot > 0) index.toFloat() / tot else 0f
                tvPercent.text = if (tot > 0) "${index * 100 / tot}%" else "0%"
                tvCurrent.text = "正在检测：$title"
                val cur = index - 1
                if (cur in rowIconBoxes.indices) setRowRunning(cur)
                if (listContainer.childCount > 0) {
                    val target = listContainer.getChildAt(
                        cur.coerceIn(0, listContainer.childCount - 1)
                    )
                    val topY = scroll.scrollY
                    val needScroll =
                        target.top < topY || target.top + target.height > topY + scroll.height
                    if (needScroll) {
                        val targetY = (target.top - scroll.height / 3).coerceAtLeast(0)
                        if (kotlin.math.abs(targetY - lastScrollTarget) > 8) {
                            lastScrollTarget = targetY
                            scroll.smoothScrollTo(0, targetY)
                        }
                    }
                }
            }
        }

        override fun onResult(index: Int, result: ProbeResult) {
            if (!isShowing) return
            mainHandler.postDelayed({
                runCatching {
                    if (!isShowing) return@runCatching
                    setRowDone(index - 1, result.level)
                }
            }, 100L)
        }

        private fun setRowRunning(index: Int) {
            val box = rowIconBoxes.getOrNull(index) ?: return
            box.removeAllViews()
            val spin = ProgressBar(host, null, android.R.attr.progressBarStyleSmall).apply {
                layoutParams = FrameLayout.LayoutParams(dp(16), dp(16)).apply {
                    gravity = Gravity.CENTER
                }
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            box.addView(spin)
            rowBars.getOrNull(index)?.visibility = View.VISIBLE
            rowViews.getOrNull(index)?.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(accentSoft)
            }
            rowStatus.getOrNull(index)?.apply {
                text = "检测中"
                setTextColor(accent)
            }
        }

        private fun setRowDone(index: Int, level: ProbeLevel) {
            val box = rowIconBoxes.getOrNull(index) ?: return
            box.removeAllViews()
            rowBars.getOrNull(index)?.visibility = View.INVISIBLE
            rowViews.getOrNull(index)?.background = null
            val (mark, color, labelText) = when (level) {
                ProbeLevel.OK -> Triple("✓", accent, "适配")
                ProbeLevel.PARTIAL -> Triple("!", warnColor, "关注")
                ProbeLevel.FAIL -> Triple("×", failColor, "不适配")
            }
            val icon = TextView(host).apply {
                text = mark
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                    gravity = Gravity.CENTER
                }
            }
            box.addView(icon)
            rowStatus.getOrNull(index)?.apply {
                text = labelText
                setTextColor(if (level == ProbeLevel.OK) textSub else color)
            }
            icon.scaleX = 0f
            icon.scaleY = 0f
            icon.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(android.view.animation.OvershootInterpolator()).start()
        }

        override fun dismissSafe() {
            runCatching {
                wave.stop()
                dots.stop()
                if (isShowing) dismiss()
            }
        }

        private fun label(t: String, sp: Int, bold: Boolean, c: Int) =
            TextView(host).apply {
                text = t
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
                setTextColor(c)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        private fun space(h: Int) = View(host).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(h))
        }

        private fun dp(v: Float) = (v * host.resources.displayMetrics.density + 0.5f).toInt()
        private fun dp(v: Int) = (v * host.resources.displayMetrics.density + 0.5f).toInt()
    }

    /** 动态波浪线进度条 */
    private class WaveProgressView(context: Context) : View(context) {
        private val trackPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#E5E7EB")
        }
        private val wavePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val headPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
        }
        private val headGlowPaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#5910B981")
        }
        private var phase = 0f
        private var displayProgress = 0f
        private var targetProgress = 0f
        private var progressAnimator: ValueAnimator? = null
        private val waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        var progress: Float
            get() = targetProgress
            set(value) {
                val v = value.coerceIn(0f, 1f)
                if (v == targetProgress) return
                targetProgress = v
                progressAnimator?.cancel()
                progressAnimator = ValueAnimator.ofFloat(displayProgress, v).apply {
                    duration = 260
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        displayProgress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }

        fun setTrackColor(color: Int) {
            trackPaint.color = color
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0) {
                trackPaint.strokeWidth = h * 0.08f
                wavePaint.strokeWidth = h * 0.14f
                wavePaint.shader = android.graphics.LinearGradient(
                    0f, 0f, w.toFloat(), 0f,
                    Color.parseColor("#34D399"), Color.parseColor("#07C160"),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            waveAnimator.start()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            waveAnimator.cancel()
            progressAnimator?.cancel()
        }

        fun stop() {
            waveAnimator.cancel()
            progressAnimator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val centerY = h / 2f
            val inset = wavePaint.strokeWidth
            canvas.drawLine(inset, centerY, w - inset, centerY, trackPaint)
            val fillW = w * displayProgress.coerceIn(0f, 1f)
            if (fillW <= 1f) return
            val amp = h * 0.24f
            val waveLen = (w / 4.2f).coerceAtLeast(36f)
            val shift = phase * waveLen
            val path = Path()
            var x = 0f
            path.moveTo(0f, centerY + amp * sin(2 * PI * (x - shift) / waveLen).toFloat())
            while (x <= fillW) {
                val y = centerY + amp * sin(2 * PI * (x - shift) / waveLen).toFloat()
                path.lineTo(x, y)
                x += 4f
            }
            canvas.drawPath(path, wavePaint)
            val headY = centerY + amp * sin(2 * PI * (fillW - shift) / waveLen).toFloat()
            canvas.drawCircle(fillW.coerceIn(inset, w - inset), headY, wavePaint.strokeWidth * 0.75f, headGlowPaint)
            canvas.drawCircle(fillW.coerceIn(inset, w - inset), headY, wavePaint.strokeWidth * 0.4f, headPaint)
        }
    }

    /** 三个跳动的加载小圆点 */
    private class DotsLoadingView(context: Context) : View(context) {
        private val paint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#07C160")
        }
        private var phase = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator.cancel()
        }

        fun stop() = animator.cancel()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = h * 0.16f
            val gap = w / 4f
            for (i in 0..2) {
                val cx = gap * (i + 1)
                val bounce = sin(2 * PI * (phase + i * 0.18f)).toFloat()
                val cy = h / 2f - bounce * h * 0.22f
                val alpha = (140 + (100 * (bounce + 1f) / 2f)).toInt().coerceIn(0, 255)
                paint.alpha = alpha
                canvas.drawCircle(cx, cy, r, paint)
            }
        }
    }

    private fun mergeRuntimeResult(report: CompatReport): CompatReport {
        val runtime = FeatureHookRegistry.snapshot().associateBy { it.name }
        val merged = report.results.map { result ->
            when (val record = runtime[result.id]) {
                null -> result
                else -> when (record.status) {
                    FeatureHookRegistry.Status.OK -> result
                    FeatureHookRegistry.Status.PARTIAL -> result.copy(
                        level = if (result.level == ProbeLevel.FAIL) ProbeLevel.FAIL else ProbeLevel.PARTIAL,
                        detail = mergeDetail(result.detail, "运行时部分生效", record.detail)
                    )
                    FeatureHookRegistry.Status.FAIL -> result.copy(
                        level = ProbeLevel.FAIL,
                        detail = mergeDetail(result.detail, "运行时安装失败", record.detail)
                    )
                    FeatureHookRegistry.Status.SKIP -> result.copy(
                        level = ProbeLevel.FAIL,
                        detail = mergeDetail(result.detail, "本次未安装", record.detail)
                    )
                }
            }
        }
        return report.copy(results = merged)
    }

    private fun mergeDetail(base: String, prefix: String, extra: String): String {
        val suffix = listOf(prefix, extra).filter { it.isNotBlank() }.joinToString(" · ")
        return if (base.isBlank()) suffix else "$base · $suffix"
    }

    // ── 结果弹窗 ──────────────────────────────────────────────────────────

    private class ResultDialog(
        private val host: Activity,
        private val report: CompatReport
    ) : Dialog(host, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth) {

        private val isNight = detectNight(host)

        private val accent = Color.parseColor("#07C160")
        private val accentLight = Color.parseColor("#10B981")
        private val accentSoft = if (isNight) Color.parseColor("#1A2E26") else Color.parseColor("#E6F4EA")
        private val textMain = if (isNight) Color.parseColor("#F3F4F6") else Color.parseColor("#111827")
        private val textSub = if (isNight) Color.parseColor("#9CA3AF") else Color.parseColor("#6B7280")
        private val warnColor = Color.parseColor("#F59E0B")
        private val failColor = Color.parseColor("#EF4444")
        private val cardStart = if (isNight) Color.parseColor("#24252C") else Color.parseColor("#FFFFFF")
        private val cardEnd = if (isNight) Color.parseColor("#1A1B20") else Color.parseColor("#F9FAFB")
        private val cardStroke = if (isNight) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")
        private val listBg = if (isNight) Color.parseColor("#14151A") else Color.parseColor("#F3F4F6")

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setCancelable(true)
            setCanceledOnTouchOutside(true)

            window?.setWindowAnimations(android.R.style.Animation_Dialog)
            window?.setDimAmount(0.45f)

            val pad = dp(24)
            val card = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(cardStart, cardEnd)
                ).apply {
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), cardStroke)
                }
                elevation = dp(16).toFloat()
            }

            // ── 头部：渐变标志性徽章 + 标题/环境信息 ──
            val headerRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val isPerfect = report.failCount == 0 && report.partialCount == 0
            val headerBgColor = if (isPerfect) accent else if (report.failCount > 0) failColor else warnColor
            val headerBgLight = if (isPerfect) accentLight else if (report.failCount > 0) Color.parseColor("#F87171") else Color.parseColor("#FBBF24")

            val iconBox = FrameLayout(host).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(headerBgLight, headerBgColor)
                ).apply { shape = GradientDrawable.OVAL }
            }
            iconBox.addView(TextView(host).apply {
                text = if (isPerfect) "✓" else if (report.failCount > 0) "×" else "!"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
            headerRow.addView(iconBox)

            val titleCol = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleCol.addView(tv("适配测试完成", 18, true, textMain))
            titleCol.addView(space(2))
            titleCol.addView(tv(report.wechatSummary, 12, false, textSub))
            headerRow.addView(titleCol)

            card.addView(headerRow)
            card.addView(space(18))

            // ── 三列均分统计徽章卡 ──
            val statsRow = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statsRow.addView(statBadge("✓ ${report.okCount} 正常", accent, accentSoft, true, 1f))
            statsRow.addView(spaceW(6))
            statsRow.addView(statBadge("! ${report.partialCount} 关注", warnColor, withAlpha(warnColor, isNight), false, 1f))
            statsRow.addView(spaceW(6))
            statsRow.addView(statBadge("× ${report.failCount} 不兼容", failColor, withAlpha(failColor, isNight), false, 1f))
            card.addView(statsRow)
            card.addView(space(16))

            // ── 功能列表（圆角容器） ──
            val scroll = ScrollView(host).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(280)
                )
                isVerticalScrollBarEnabled = false
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(listBg)
                }
            }
            val list = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            report.results.forEach { r ->
                list.addView(buildResultRow(r))
            }
            scroll.addView(list)
            card.addView(scroll)
            card.addView(space(18))

            // ── 底部：重启微信主按钮 + 辅助按钮 ──
            card.addView(primaryBtn("重启微信生效") { restartWeChat() })
            card.addView(space(10))
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(ghostBtn("复制报告") {
                val text = buildString {
                    appendLine(report.wechatSummary)
                    appendLine(report.summaryLine())
                    report.results.forEach {
                        appendLine("${it.level}\t${it.title}\t${it.detail}")
                    }
                }
                val cm = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("OKK-compat", text))
                Toast.makeText(host, "已复制报告剪贴板", Toast.LENGTH_SHORT).show()
            })
            row.addView(spaceW(10))
            row.addView(ghostBtn("稍后") { dismiss() })
            card.addView(row)

            val wrap = FrameLayout(host).apply {
                setPadding(dp(20), dp(20), dp(20), dp(20))
                addView(card)
            }
            setContentView(wrap)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }

        private fun withAlpha(color: Int, night: Boolean): Int {
            val a = if (night) 0x2A else 0x18
            return (a shl 24) or (color and 0xFFFFFF)
        }

        private fun statBadge(text: String, color: Int, bgColor: Int, filled: Boolean, weight: Float): TextView {
            return TextView(host).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                if (filled) {
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(color)
                    }
                } else {
                    setTextColor(color)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(bgColor)
                    }
                }
            }
        }

        private fun buildResultRow(r: ProbeResult): View {
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            val (mark, color, labelText) = when (r.level) {
                ProbeLevel.OK -> Triple("✓", accent, "适配")
                ProbeLevel.PARTIAL -> Triple("!", warnColor, "关注")
                ProbeLevel.FAIL -> Triple("×", failColor, "不适配")
            }
            row.addView(TextView(host).apply {
                text = mark
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            })
            val textCol = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(tv(r.title, 13, r.level != ProbeLevel.OK, textMain))
            if (r.level != ProbeLevel.OK && r.detail.isNotBlank()) {
                textCol.addView(tv(r.detail, 11, false, textSub))
            }
            row.addView(textCol)
            row.addView(tv(labelText, 11, false, if (r.level == ProbeLevel.OK) textSub else color))
            return row
        }

        private fun primaryBtn(label: String, onClick: () -> Unit): TextView {
            return TextView(host).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(accentLight, accent)
                ).apply { cornerRadius = dp(16).toFloat() }
                setOnClickListener { onClick() }
            }
        }

        /** 冷启动重启：使用 AlarmClockInfo 系统最高优先级闹钟穿透 BAL 限制 */
        private fun restartWeChat() {
            Toast.makeText(host, "正在重启微信...", Toast.LENGTH_SHORT).show()
            val ctx = host.applicationContext ?: host
            val pkg = ctx.packageName
            val launcherClass = "com.tencent.mm.ui.LauncherUI"

            runCatching {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(pkg, launcherClass)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getActivity(ctx, 65535, intent, flags)

                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val triggerAt = System.currentTimeMillis() + 800L
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
            }

            dismiss()
            val h = Handler(Looper.getMainLooper())
            h.postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(0)
            }, 100L)
        }

        private fun ghostBtn(label: String, onClick: () -> Unit): TextView {
            return TextView(host).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(textMain)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(11), dp(12), dp(11))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), cardStroke)
                }
                setOnClickListener { onClick() }
            }
        }

        private fun tv(text: String, sp: Int, bold: Boolean, color: Int) =
            TextView(host).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        private fun space(h: Int) = View(host).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(h))
        }

        private fun spaceW(w: Int) = View(host).apply {
            layoutParams = LinearLayout.LayoutParams(dp(w), 1)
        }

        private fun dp(v: Float) = (v * host.resources.displayMetrics.density + 0.5f).toInt()
        private fun dp(v: Int) = (v * host.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
