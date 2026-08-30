package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.OKK.yes.core.compat.DexKitSupport
import com.OKK.yes.core.hooks.ui.StyledDialogs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 聊天快捷工具栏 Hook。
 *
 * 完整对齐 WeKit / wcx 真实实现：
 * 1. 定位并 Hook initAppGrid 方法（如 AppPanel.n(AppPanel)）的 before 与 after。
 * 2. 在 before 中，计算并调用 OnMeasureListener（g.a(width, height)），为 AppPanel 提前设置真实 measured 尺寸（gridWidth/gridHeight），
 *    从而突破 `if (appPanel.f173902t == 0 || appPanel.f173903u == 0) return;` 阻断！
 * 3. 在 after 中，从 AppPanel 的子路径 (0, 0, 0) 取出真实 GridViews，提取 OnItemClickListener 与 OnItemLongClickListener。
 * 4. 遍历 Adapter 项，读取真实 Tag 中的 TextView 文本，保存真实 indexInGrid。
 * 5. 工具栏点击时，相册走 position 0，系统拍摄走 OnItemLongClick(null, null, 0, 0)，
 *    其余工具统一点派发 menuItem.onClickListener.onItemClick(gridView, itemView, menuItem.indexInGrid, 0)。
 */
object ChatToolbarHook {
    private const val TAG = "OKK-ChatToolbar"
    private const val TOOLBAR_TAG = "okk_chat_toolbar_container"
    const val KEY_ENABLED = "chat_toolbar_enabled"
    const val KEY_ORDER = "chat_toolbar_order"
    const val KEY_ENABLED_ITEMS = "chat_toolbar_enabled_items"
    const val KEY_DISPLAY_MODE = "chat_toolbar_display_mode"

    const val DEFAULT_ORDER = "相册,拍摄,红包,转账,语音通话,视频通话,位置,文件,收藏,个人名片,接龙"
    const val DEFAULT_ENABLED = "相册,拍摄,红包,转账,语音通话,视频通话,位置,文件,收藏,个人名片,接龙"

    private const val GRID_INIT_WATCHDOG_DELAY_MS = 300L
    private const val SNAPSHOT_DEBOUNCE_MS = 200L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val installed = AtomicBoolean(false)
    private val activeFooters = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val footerPanels = Collections.synchronizedMap(WeakHashMap<ViewGroup, ViewGroup>())
    private val panelTools = Collections.synchronizedMap(WeakHashMap<ViewGroup, PanelTools>())

    @Volatile private var initAppGridMethod: Method? = null
    @Volatile private var measurerConstructor: Constructor<*>? = null
    @Volatile private var measurerMethod: Method? = null

    private data class MenuItem(
        val name: String,
        val onClickListener: AdapterView.OnItemClickListener,
        val onLongClickListener: AdapterView.OnItemLongClickListener?,
        val gridView: WeakReference<GridView>,
        val itemView: WeakReference<View>,
        val indexInGrid: Int
    )

    private class PanelTools {
        @Volatile var tools: List<MenuItem> = emptyList()
        @Volatile var lastSnapshotTime: Long = 0L
        @Volatile var refreshScheduled: Boolean = false
    }

    fun isEnabled(): Boolean = PublicConfigStore.getBoolean(KEY_ENABLED, true)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return

        val configListener: (String) -> Unit = {
            mainHandler.post { refreshAllFooters() }
        }
        PublicConfigStore.addListener(KEY_ENABLED, configListener)
        PublicConfigStore.addListener(KEY_ORDER, configListener)
        PublicConfigStore.addListener(KEY_ENABLED_ITEMS, configListener)
        PublicConfigStore.addListener(KEY_DISPLAY_MODE, configListener)
        PublicConfigStore.addListener("night_mode", configListener)
        PublicConfigStore.addListener("night_mode_follow", configListener)

        hookAppPanel(context, classLoader, modulePath)
        hookChatFooter(classLoader)
    }

    private fun hookAppPanel(context: Context, classLoader: ClassLoader, modulePath: String?) {
        runCatching {
            // 1. 定位 initAppGrid 方法 (例如 AppPanel.n(AppPanel))
            val initMethod = DexKitSupport.findMethodByStrings(
                context,
                classLoader,
                modulePath,
                "MicroMsg.AppPanel",
                "initAppGrid()"
            ) ?: runCatching {
                val appPanelClazz = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.chat.AppPanel", classLoader)
                appPanelClazz.declaredMethods.firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 && it.parameterTypes[0] == appPanelClazz
                }
            }.getOrNull()

            if (initMethod == null) {
                xlog("initAppGrid method not found")
                return
            }

            initAppGridMethod = initMethod
            initMethod.isAccessible = true

            // 2. 定位 OnMeasureListener (即 g.a(int, int))
            val onMeasureMethod = DexKitSupport.findMethodByStrings(
                context,
                classLoader,
                modulePath,
                "MicroMsg.AppPanel",
                "onMeasure width: %d, heigth:%d, isMeasured:%b, gridWidth:%d, gridHeight:%d"
            )

            if (onMeasureMethod != null) {
                measurerMethod = onMeasureMethod
                onMeasureMethod.isAccessible = true
                val measurerClass = onMeasureMethod.declaringClass
                measurerConstructor = measurerClass.declaredConstructors.firstOrNull()?.apply { isAccessible = true }
                xlog("located onMeasure method: ${measurerClass.name}#${onMeasureMethod.name}")
            }

            // 3. Hook initAppGrid 方法的 after (完全对齐 WeKit，不在 before 里反复 preMeasure)
            XposedBridge.hookMethod(initMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val appPanel = appPanelFromHookParam(param) ?: return
                    snapshotToolsDebounced(appPanel)
                }
            })

            xlog("hooked initAppGrid method: ${initMethod.declaringClass.name}#${initMethod.name}")
        }.onFailure { xlog("hookAppPanel fail: ${it.message}") }
    }

    /**
     * 完全对齐 WeKit：在 initAppGrid 执行前，通过 Measurer 注入真实的屏幕宽高与面板高度，
     * 解决 AppPanel 初始 width/height 为 0 导致 initAppGrid 提前返回的问题。
     */
    private fun preMeasureAppPanel(appPanel: ViewGroup) {
        val ctor = measurerConstructor ?: return
        val mth = measurerMethod ?: return
        val width = appPanel.width.takeIf { it > 0 } ?: appPanel.resources.displayMetrics.widthPixels
        val height = appPanel.height.takeIf { it > 0 } ?: (if (appPanel.resources.displayMetrics.widthPixels < appPanel.resources.displayMetrics.heightPixels) 215 else 158 * appPanel.resources.displayMetrics.density.toInt())
        runCatching {
            val measurer = ctor.newInstance(appPanel)
            mth.invoke(measurer, width, height)
        }
    }

    private fun hookChatFooter(classLoader: ClassLoader) {
        runCatching {
            val footerClazz = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.chat.ChatFooter", classLoader)
            XposedBridge.hookAllConstructors(footerClazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val footer = param.thisObject as? ViewGroup ?: return
                    activeFooters.add(footer)
                    mainHandler.postDelayed({
                        bindFooterPanel(footer)
                        injectToolbarIntoFooter(footer)
                    }, 50)
                }
            })
            runCatching {
                XposedBridge.hookAllMethods(footerClazz, "onConfigurationChanged", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val footer = param.thisObject as? ViewGroup ?: return
                        mainHandler.post { injectToolbarIntoFooter(footer) }
                    }
                })
            }
            xlog("hooked ChatFooter constructors and configuration change")
        }.onFailure { xlog("ChatFooter hook fail: ${it.message}") }
    }

    private fun appPanelFromHookParam(param: XC_MethodHook.MethodHookParam): ViewGroup? {
        (param.thisObject as? ViewGroup)?.let {
            if (it.javaClass.name.contains("AppPanel")) return it
        }
        return param.args.firstOrNull { it is ViewGroup && it.javaClass.name.contains("AppPanel") } as? ViewGroup
    }

    private fun refreshAllFooters() {
        for (footer in activeFooters) {
            runCatching { injectToolbarIntoFooter(footer) }
                .onFailure { xlog("refresh footer error: ${it.message}") }
        }
    }

    private fun toolsOf(appPanel: ViewGroup): PanelTools = synchronized(panelTools) {
        panelTools.getOrPut(appPanel) { PanelTools() }
    }

    private fun bindFooterPanel(footer: ViewGroup): ViewGroup? {
        val cached = footerPanels[footer]
        if (cached != null) return cached
        val appPanel = getAppPanel(footer)
        if (appPanel == null) {
            return null
        }
        footerPanels[footer] = appPanel
        return appPanel
    }

    private fun snapshotToolsDebounced(appPanel: ViewGroup) {
        val state = toolsOf(appPanel)
        val now = System.currentTimeMillis()
        val elapsed = now - state.lastSnapshotTime
        if (state.lastSnapshotTime == 0L || elapsed >= SNAPSHOT_DEBOUNCE_MS) {
            snapshotTools(appPanel)
            return
        }
        if (state.refreshScheduled) return
        state.refreshScheduled = true
        mainHandler.postDelayed({
            state.refreshScheduled = false
            snapshotTools(appPanel)
        }, (SNAPSHOT_DEBOUNCE_MS - elapsed).coerceAtLeast(1L))
    }

    private fun snapshotTools(appPanel: ViewGroup) {
        runCatching {
            val grids = findAppPanelGrids(appPanel)
            if (grids.isEmpty()) return@runCatching

            val tools = mutableListOf<MenuItem>()
            for (grid in grids) {
                val adapter = grid.adapter ?: continue
                val onClick = findAdapterClickListener(grid) ?: continue
                val onLongClick = findAdapterLongClickListener(grid)

                for (i in 0 until adapter.count) {
                    val itemView = runCatching { adapter.getView(i, null, grid) }.getOrNull() ?: continue
                    val title = extractTitleFromItemView(itemView, i)
                    if (title.isBlank() || title.startsWith("工具")) continue
                    tools.add(
                        MenuItem(
                            name = title,
                            onClickListener = onClick,
                            onLongClickListener = onLongClick,
                            gridView = WeakReference(grid),
                            itemView = WeakReference(itemView),
                            indexInGrid = i
                        )
                    )
                }
            }

            if (tools.isEmpty()) return@runCatching
            toolsOf(appPanel).apply {
                this.tools = tools.distinctBy { it.name }
                this.lastSnapshotTime = System.currentTimeMillis()
            }
            xlog("snapshot panel=${System.identityHashCode(appPanel)} count=${tools.size}")
        }.onFailure { xlog("snapshotTools error: ${it.message}") }
    }

    private fun scheduleGridInitWatchdog(appPanel: ViewGroup) {
        mainHandler.postDelayed({
            val state = toolsOf(appPanel)
            if (state.tools.isNotEmpty()) return@postDelayed
            if (findChildByPath(appPanel, 0, 0, 0) == null) return@postDelayed

            xlog("grid was never initialized for this chat footer, forcing initAppGrid")
            forceInitAppGrid(appPanel)
            snapshotTools(appPanel)
        }, GRID_INIT_WATCHDOG_DELAY_MS)
    }

    private fun forceInitAppGrid(appPanel: ViewGroup) {
        runCatching {
            preMeasureAppPanel(appPanel)
            val method = initAppGridMethod ?: return
            method.isAccessible = true
            if (Modifier.isStatic(method.modifiers)) {
                method.invoke(null, appPanel)
            } else {
                method.invoke(appPanel)
            }
            xlog("forced initAppGrid for panel=${System.identityHashCode(appPanel)}")
        }.onFailure { xlog("forceInitAppGrid fail: ${it.message}") }
    }

    private fun findAppPanelGrids(appPanel: ViewGroup): List<GridView> {
        val flipper = findChildByPath(appPanel, 0, 0, 0) as? ViewGroup
        if (flipper != null) {
            val grids = (0 until flipper.childCount).mapNotNull { flipper.getChildAt(it) as? GridView }
            if (grids.isNotEmpty()) return grids
        }
        val fallback = mutableListOf<GridView>()
        findAllViews(appPanel, GridView::class.java, fallback)
        return fallback
    }

    private fun findAdapterClickListener(grid: GridView): AdapterView.OnItemClickListener? {
        grid.onItemClickListener?.let { return it }
        return findFieldValue(grid, AdapterView.OnItemClickListener::class.java)
    }

    private fun findAdapterLongClickListener(grid: GridView): AdapterView.OnItemLongClickListener? {
        grid.onItemLongClickListener?.let { return it }
        return findFieldValue(grid, AdapterView.OnItemLongClickListener::class.java)
    }

    private fun <T> findFieldValue(owner: Any, targetClass: Class<T>): T? {
        var clazz: Class<*>? = owner.javaClass
        while (clazz != null) {
            for (f in clazz.declaredFields) {
                val value = runCatching {
                    f.isAccessible = true
                    f.get(owner)
                }.getOrNull()
                if (targetClass.isInstance(value)) return targetClass.cast(value)
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun getAppPanel(footer: ViewGroup): ViewGroup? {
        runCatching { XposedHelpers.getObjectField(footer, "mAppPanel") as? ViewGroup }.getOrNull()?.let { return it }
        runCatching { XposedHelpers.callMethod(footer, "getAppPanel") as? ViewGroup }.getOrNull()?.let { return it }
        findAppPanelInFields(footer)?.let { return it }
        return findAppPanelInTree(footer)
    }

    private fun findAppPanelInFields(owner: Any): ViewGroup? {
        var clazz: Class<*>? = owner.javaClass
        while (clazz != null) {
            for (f in clazz.declaredFields) {
                val value = runCatching {
                    f.isAccessible = true
                    f.get(owner)
                }.getOrNull()
                if (value is ViewGroup && value.javaClass.name.contains("AppPanel")) return value
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun findAppPanelInTree(root: ViewGroup): ViewGroup? {
        if (root.javaClass.name.contains("AppPanel")) return root
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ViewGroup) {
                if (child.javaClass.name.contains("AppPanel")) return child
                findAppPanelInTree(child)?.let { return it }
            }
        }
        return null
    }

    private fun extractTitleFromItemView(itemView: View?, index: Int): String {
        if (itemView != null) {
            runCatching {
                val holder = itemView.tag
                if (holder != null) {
                    val holderText = findFieldValue(holder, TextView::class.java)?.text?.toString()
                    if (!holderText.isNullOrBlank()) return holderText
                }
            }
            val textViews = mutableListOf<TextView>()
            if (itemView is ViewGroup) {
                findAllViews(itemView, TextView::class.java, textViews)
            } else if (itemView is TextView) {
                textViews.add(itemView)
            }
            val found = textViews.firstOrNull { it.text.isNotBlank() }?.text?.toString()
            if (!found.isNullOrBlank()) return found
        }
        return when (index) {
            0 -> "相册"
            1 -> "拍摄"
            2 -> "语音通话"
            3 -> "视频通话"
            4 -> "位置"
            5 -> "红包"
            6 -> "转账"
            7 -> "语音输入"
            8 -> "收藏"
            9 -> "个人名片"
            10 -> "文件"
            11 -> "接龙"
            else -> "工具$index"
        }
    }

    private fun injectToolbarIntoFooter(footer: ViewGroup) {
        val targetContainer = findChildByPath(footer, 0, 1) as? ViewGroup
            ?: findChildByPath(footer, 0) as? ViewGroup
            ?: footer

        val existingView = targetContainer.findViewWithTag<View>(TOOLBAR_TAG)
        if (existingView != null) {
            targetContainer.removeView(existingView)
        }
        if (!isEnabled()) return

        val appPanel = bindFooterPanel(footer)

        val ctx = footer.context
        val autoDpiOn = PublicConfigStore.getBoolean("auto_dpi_scaling_enabled", true)
        val dm = ctx.resources.displayMetrics
        val density = dm.density
        val realWidthPx = minOf(dm.widthPixels, dm.heightPixels).toFloat()

        // DPI 自适应缩放因子：结合屏幕物理宽度与基准 density 进行平滑缩放
        val scale = if (autoDpiOn) {
            val widthScale = (realWidthPx / 1080f).coerceIn(0.95f, 1.30f)
            (1.0f + (widthScale - 1.0f) * 0.8f).coerceIn(1.0f, 1.25f)
        } else 1.0f

        val activeItems = activeToolbarItems()
        if (activeItems.isEmpty()) return

        val scrollView = HorizontalScrollView(ctx).apply {
            tag = TOOLBAR_TAG
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * density * scale).toInt()
            ).apply {
                setMargins(0, (3 * density * scale).toInt(), 0, (3 * density * scale).toInt())
            }
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (10 * density * scale).toInt(),
                (1 * density * scale).toInt(),
                (10 * density * scale).toInt(),
                (1 * density * scale).toInt()
            )
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val iconMap = mapOf(
            "相册" to "🖼️",
            "拍摄" to "📷",
            "红包" to "🧧",
            "转账" to "💰",
            "语音通话" to "📞",
            "视频通话" to "📹",
            "位置" to "📍",
            "文件" to "📁",
            "收藏" to "⭐",
            "个人名片" to "📇",
            "接龙" to "📝"
        )
        val displayMode = PublicConfigStore.getString(KEY_DISPLAY_MODE, "icon_and_text")
        val isNight = isNightMode(ctx)
        val chipBgColor = if (isNight) Color.parseColor("#33FFFFFF") else Color.parseColor("#15000000")
        val chipStrokeColor = if (isNight) Color.parseColor("#20FFFFFF") else Color.parseColor("#10000000")
        val chipTextColor = if (isNight) Color.parseColor("#F0F0F0") else Color.parseColor("#1A1A1A")

        for (itemKey in activeItems) {
            val icon = iconMap[itemKey] ?: "⚡"
            val displayLabel = when (displayMode) {
                "icon_only" -> icon
                "text_only" -> itemKey
                else -> "$icon  $itemKey"
            }
            val chip = TextView(ctx).apply {
                text = displayLabel
                textSize = (if (displayMode == "icon_only") 18f else 14f) * scale
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(chipTextColor)
                gravity = Gravity.CENTER
                minHeight = (34 * density * scale).toInt()
                setPadding(
                    (13 * density * scale).toInt(),
                    (6 * density * scale).toInt(),
                    (13 * density * scale).toInt(),
                    (6 * density * scale).toInt()
                )
                background = GradientDrawable().apply {
                    setColor(chipBgColor)
                    setStroke((0.8f * density * scale).toInt().coerceAtLeast(1), chipStrokeColor)
                    cornerRadius = 17 * density * scale
                }
                setOnClickListener { handleToolbarItemClick(ctx, footer, itemKey) }
            }
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (7 * density * scale).toInt()
            })
        }

        scrollView.addView(row)
        targetContainer.addView(scrollView, 0)
    }

    private fun isNightMode(ctx: Context): Boolean {
        if (runCatching { PublicConfigStore.getBoolean("night_mode", false) }.getOrDefault(false)) {
            return true
        }
        return StyledDialogs.isNight(ctx)
    }

    private fun activeToolbarItems(): List<String> {
        val supportedItems = DEFAULT_ORDER.split(",").toSet()
        val orderStr = PublicConfigStore.getString(KEY_ORDER, DEFAULT_ORDER)
        val enabledStr = PublicConfigStore.getString(KEY_ENABLED_ITEMS, DEFAULT_ENABLED)
        val saved = orderStr.split(",").map { it.trim() }.filter { it.isNotEmpty() && it in supportedItems }.distinct()
        val orderList = saved + DEFAULT_ORDER.split(",").filter { it !in saved }
        val enabledSet = enabledStr.split(",").map { it.trim() }.filter { it.isNotEmpty() && it in supportedItems }.toSet()
        return orderList.filter { it in enabledSet }
    }

    private fun handleToolbarItemClick(context: Context, footer: ViewGroup, itemKey: String) {
        val appPanel = getAppPanel(footer) ?: footerPanels[footer]
        if (appPanel == null) {
            return
        }

        var state = toolsOf(appPanel)
        if (state.tools.isEmpty()) {
            preMeasureAppPanel(appPanel)
            forceInitAppGrid(appPanel)
            snapshotTools(appPanel)
            state = toolsOf(appPanel)
        }

        val tools = state.tools
        val firstTool = tools.firstOrNull()

        when (itemKey) {
            "相册" -> {
                var albumTool = tools.firstOrNull { sameToolName(it.name, "相册") } ?: firstTool
                if (albumTool != null) {
                    var grid = albumTool.gridView.get()
                    if (grid == null) {
                        snapshotTools(appPanel)
                        albumTool = toolsOf(appPanel).tools.firstOrNull { sameToolName(it.name, "相册") } ?: firstTool
                        grid = albumTool?.gridView?.get()
                    }
                    if (grid != null && albumTool != null) {
                        val item = resolveItemView(grid, albumTool)
                        if (item != null) {
                            runCatching { albumTool.onClickListener.onItemClick(grid, item, albumTool.indexInGrid, 0L) }
                            return
                        }
                    }
                }
                // 微信原生相册界面 (AlbumPreviewUI) 兜底
                val act = context as? Activity
                runCatching {
                    val intent = Intent().apply {
                        setClassName(context, "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI")
                        putExtra("max_select_count", 9)
                        putExtra("query_source_type", 3)
                        putExtra("send_raw_img", false)
                        if (act == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onFailure { xlog("launch AlbumPreviewUI fallback fail: ${it.message}") }
            }
            "拍摄" -> {
                val act = context as? Activity
                if (SystemCameraHook.isEnabled() && act != null) {
                    SystemCameraHook.launchSystemCamera(act)
                    return
                }
                var captureTool = tools.firstOrNull { sameToolName(it.name, "拍摄") }
                if (captureTool != null) {
                    var grid = captureTool.gridView.get()
                    if (grid == null) {
                        snapshotTools(appPanel)
                        captureTool = toolsOf(appPanel).tools.firstOrNull { sameToolName(it.name, "拍摄") }
                        grid = captureTool?.gridView?.get()
                    }
                    if (grid != null && captureTool != null) {
                        val item = resolveItemView(grid, captureTool)
                        if (item != null) {
                            runCatching { captureTool.onClickListener.onItemClick(grid, item, captureTool.indexInGrid, 0L) }
                            return
                        }
                    }
                }
                if (firstTool?.onLongClickListener != null) {
                    firstTool.onLongClickListener.onItemLongClick(null, null, 0, 0L)
                    return
                }
                if (act != null) SystemCameraHook.launchSystemCamera(act)
            }
            "语音通话" -> {
                if (invokeDirectVoipCall(appPanel, isVideo = false)) return
                val tool = tools.firstOrNull { sameToolName(it.name, itemKey) } ?: return
                val grid = tool.gridView.get() ?: return
                val item = resolveItemView(grid, tool) ?: return
                runCatching { tool.onClickListener.onItemClick(grid, item, tool.indexInGrid, 0L) }
            }
            "视频通话" -> {
                if (invokeDirectVoipCall(appPanel, isVideo = true)) return
                val tool = tools.firstOrNull { sameToolName(it.name, itemKey) } ?: return
                val grid = tool.gridView.get() ?: return
                val item = resolveItemView(grid, tool) ?: return
                runCatching { tool.onClickListener.onItemClick(grid, item, tool.indexInGrid, 0L) }
            }
            else -> {
                var tool = tools.firstOrNull { sameToolName(it.name, itemKey) }
                if (tool == null) {
                    // 即时触发一次 snapshot
                    snapshotTools(appPanel)
                    tool = toolsOf(appPanel).tools.firstOrNull { sameToolName(it.name, itemKey) }
                }
                if (tool == null) {
                    return
                }
                val grid = tool.gridView.get()
                if (grid == null) {
                    snapshotTools(appPanel)
                    return
                }
                val item = resolveItemView(grid, tool) ?: return
                runCatching {
                    tool.onClickListener.onItemClick(grid, item, tool.indexInGrid, 0L)
                }
            }
        }
    }

    private fun resolveItemView(grid: GridView, tool: MenuItem): View? {
        tool.itemView.get()?.let { return it }
        val firstVisible = grid.firstVisiblePosition
        val childIndex = tool.indexInGrid - firstVisible
        if (childIndex in 0 until grid.childCount) {
            grid.getChildAt(childIndex)?.let { return it }
        }
        if (tool.indexInGrid in 0 until (grid.adapter?.count ?: 0)) {
            return runCatching { grid.adapter?.getView(tool.indexInGrid, null, grid) }.getOrNull()
        }
        return null
    }

    private fun invokeDirectVoipCall(appPanel: ViewGroup, isVideo: Boolean): Boolean {
        return runCatching {
            val uClass = runCatching { XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.chat.u", appPanel.context.classLoader) }.getOrNull() ?: return false
            val listener = findFieldValue(appPanel, uClass) ?: return false

            var i4Obj: Any? = null
            var clazz: Class<*>? = listener.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (f in clazz.declaredFields) {
                    f.isAccessible = true
                    val v = runCatching { f.get(listener) }.getOrNull() ?: continue
                    if (v.javaClass.name.contains("i4") || v.javaClass.name.contains("ChattingFooter")) {
                        i4Obj = v
                        break
                    }
                }
                if (i4Obj != null) break
                clazz = clazz.superclass
            }

            if (i4Obj == null) {
                for (f in listener.javaClass.declaredFields) {
                    f.isAccessible = true
                    val v = runCatching { f.get(listener) }.getOrNull() ?: continue
                    val hasB = v.javaClass.declaredMethods.any { it.name == "B" && it.parameterTypes.isEmpty() }
                    val hasC = v.javaClass.declaredMethods.any { it.name == "C" && it.parameterTypes.isEmpty() }
                    if (hasB && hasC) {
                        i4Obj = v
                        break
                    }
                }
            }

            if (i4Obj != null) {
                val methodName = if (isVideo) "C" else "B"
                val m = i4Obj.javaClass.getDeclaredMethod(methodName)
                m.isAccessible = true
                m.invoke(i4Obj)
                xlog("invoked direct voip call ($methodName - ${if (isVideo) "视频" else "语音"}) on ${i4Obj.javaClass.name}")
                return true
            }
            false
        }.onFailure { xlog("invokeDirectVoipCall fail: ${it.message}") }.getOrDefault(false)
    }

    private fun sameToolName(realName: String, itemKey: String): Boolean {
        if (realName == itemKey || realName.contains(itemKey) || itemKey.contains(realName)) return true
        return when (itemKey) {
            "语音通话" -> realName.contains("语音") || realName.contains("视频") || realName.contains("通话") || realName.contains("音视频")
            "视频通话" -> realName.contains("视频") || realName.contains("语音") || realName.contains("通话") || realName.contains("音视频")
            "个人名片" -> realName.contains("名片") || realName.contains("联系人")
            else -> false
        }
    }

    private fun findChildByPath(view: View, vararg path: Int): View? {
        var current: View? = view
        for (idx in path) {
            current = (current as? ViewGroup)?.getChildAt(idx) ?: return null
        }
        return current
    }

    private fun <T : View> findAllViews(parent: ViewGroup, targetClass: Class<T>, result: MutableList<T>) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (targetClass.isInstance(child)) {
                @Suppress("UNCHECKED_CAST")
                result.add(child as T)
            } else if (child is ViewGroup) {
                findAllViews(child, targetClass, result)
            }
        }
    }

    private fun xlog(msg: String) {
        runCatching { android.util.Log.i(TAG, msg) }
        runCatching { de.robv.android.xposed.XposedBridge.log("[$TAG] $msg") }
        ModuleLog.d("$TAG: $msg")
    }
}
