package com.OKK.yes.core.hooks

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ListView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hide divider lines on the LauncherUI conversation page.
 *
 * Keep this hook narrow. Do not hook View/ListView/RecyclerView layout paths: on WeChat 8.0.72
 * Play those hooks can disturb sent video row alignment in ChattingUI.
 */
object HideHomeDividerHook {
    private const val TAG = "OKK-HomeDivider"
    private const val KEY = "hide_home_divider"
    private const val MAIN_UI = "com.tencent.mm.ui.conversation.MainUI"

    /**
     * 会话列表字段名跨版本漂移（jadx 显示的 f200262o 只是别名，真实名在 8.0.72/8.0.76 上是 "o"）。
     * 因此按字段类型查找，不写死字段名。
     */
    private const val CONVERSATION_LIST_CLASS = "com.tencent.mm.ui.conversation.ConversationListView"

    @Volatile
    private var conversationListField: java.lang.reflect.Field? = null

    private val installed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var dividerViewId: Int = 0

    @Volatile
    private var enabled: Boolean = false

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return

        enabled = runCatching {
            PublicConfigStore.getBoolean(KEY, false)
        }.getOrDefault(false)

        // dz8 在部分版本上并不存在，仅作可选加速路径；主路径是通用细线扫描。
        dividerViewId = readStaticInt(classLoader, "com.tencent.mm.R\$id", "dz8")
            .takeIf { it != 0 }
            ?: runCatching {
                context.resources.getIdentifier("dz8", "id", "com.tencent.mm")
            }.getOrDefault(0)

        conversationListField = resolveConversationListField(classLoader)

        xlog(
            "install enabled=$enabled dz8=0x${Integer.toHexString(dividerViewId)} " +
                "listField=${conversationListField?.name ?: "none"}"
        )
        if (!enabled) return

        hookMainUiResume(classLoader)
    }

    private fun hookMainUiResume(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(MAIN_UI, classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        val mainUi = param.thisObject ?: return
                        scheduleHide(mainUi)
                    }
                }
            )
            xlog("hooked $MAIN_UI.onResume only")
        }.onFailure { e -> xlog("MainUI.onResume hook failed: ${e.message}") }
    }

    private fun scheduleHide(mainUi: Any) {
        hideFromMainUi(mainUi)
        mainHandler.postDelayed({ hideFromMainUi(mainUi) }, 120)
        mainHandler.postDelayed({ hideFromMainUi(mainUi) }, 360)
        mainHandler.postDelayed({ hideFromMainUi(mainUi) }, 800)
        mainHandler.postDelayed({ hideFromMainUi(mainUi) }, 1600)
    }

    /** 按类型定位 MainUI 中的 ConversationListView 字段，兼容各版本混淆字段名。 */
    private fun resolveConversationListField(classLoader: ClassLoader): java.lang.reflect.Field? =
        runCatching {
            val mainUi = XposedHelpers.findClass(MAIN_UI, classLoader)
            val listViewClass = runCatching {
                XposedHelpers.findClass(CONVERSATION_LIST_CLASS, classLoader)
            }.getOrNull()
            mainUi.declaredFields.firstOrNull { f ->
                listViewClass?.isAssignableFrom(f.type) == true
            } ?: mainUi.declaredFields.firstOrNull { f ->
                ListView::class.java.isAssignableFrom(f.type) ||
                    AbsListView::class.java.isAssignableFrom(f.type)
            }
        }.getOrNull()?.also { it.isAccessible = true }

    private fun hideFromMainUi(mainUi: Any) {
        val list = runCatching {
            conversationListField?.get(mainUi) as? ListView
        }.getOrNull()
        list?.let {
            stripListDivider(it)
            hideVisibleRows(it)
        }

        val root = runCatching { XposedHelpers.callMethod(mainUi, "getView") as? View }.getOrNull()
            ?: runCatching { XposedHelpers.callMethod(mainUi, "findViewById", android.R.id.content) as? View }.getOrNull()
            ?: list
            ?: return
        hideHomeThinLines(root)
    }

    private fun stripListDivider(list: ListView) {
        runCatching { list.divider = null }
        runCatching { list.dividerHeight = 0 }
    }

    private fun hideVisibleRows(list: ListView) {
        val count = list.childCount
        for (i in 0 until count) {
            val row = list.getChildAt(i) ?: continue
            hideDividerById(row)
            hideHomeThinLines(row)
        }
    }

    private fun hideDividerById(row: View) {
        val id = dividerViewId
        if (id == 0) return
        val line = row.findViewById<View>(id) ?: return
        if (line.visibility != View.GONE) line.visibility = View.GONE
    }

    private fun hideHomeThinLines(view: View, depth: Int = 0) {
        if (depth > 12) return
        if (isThinLine(view)) {
            view.visibility = View.GONE
            return
        }
        val group = view as? ViewGroup ?: return
        val count = group.childCount.coerceAtMost(120)
        for (i in 0 until count) {
            hideHomeThinLines(group.getChildAt(i) ?: continue, depth + 1)
        }
    }

    private fun isThinLine(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view is ViewGroup) return false
        if (view.isClickable || view.isLongClickable) return false
        if (view.alpha <= 0.01f) return false
        val h = view.height.takeIf { it > 0 } ?: view.layoutParams?.height ?: 0
        val w = view.width.takeIf { it > 0 } ?: view.layoutParams?.width ?: 0
        val maxLineHeight = maxOf(4, (view.resources.displayMetrics.density * 1.5f).toInt() + 1)
        if (h !in 1..maxLineHeight) return false
        val parentWidth = (view.parent as? View)?.width ?: 0
        return w == ViewGroup.LayoutParams.MATCH_PARENT || parentWidth <= 0 || w >= parentWidth / 4
    }

    private fun readStaticInt(classLoader: ClassLoader, className: String, fieldName: String): Int =
        runCatching {
            val clazz = XposedHelpers.findClass(className, classLoader)
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.getInt(null)
        }.getOrDefault(0)

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
