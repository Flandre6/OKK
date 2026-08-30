package com.OKK.yes.core.hooks

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.time.LocalDate
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

object InputStatsHook {
    private const val TAG = "OKK-InputStats"
    private const val MESSAGE_TABLE = "message"
    private const val STORE_NAME = "abc_input_stats"

    private val installed = AtomicBoolean(false)
    private val dbHookInstalled = AtomicBoolean(false)
    private val applyingHint = AtomicBoolean(false)
    private val recentMessages = RecentInputStatsDeduplicator()
    private val trackedInputs: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var chatFooterView: View? = null

    fun install(context: Context, classLoader: ClassLoader) {
        appContext = context.applicationContext ?: context
        if (!installed.compareAndSet(false, true)) return
        hookChatFooter(classLoader)
        hookFlexEditText(classLoader)
        hookTextViewSetHint()
        installDatabaseHooks(classLoader)
        xlog("installed")
    }

    /** 配置（模板/开关）变更后调用：清除缓存并立即刷新输入框提示 */
    fun refreshNow() {
        InputStatsConfig.invalidateCache()
        postUpdateHint()
    }

    private fun hookChatFooter(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.chat.ChatFooter", classLoader)
            clazz.declaredConstructors.forEach { constructor ->
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        chatFooterView = param.thisObject as? View
                        postUpdateHint()
                    }
                })
            }
            xlog("hooked ChatFooter constructors: ${clazz.declaredConstructors.size}")
        }.onFailure {
            xlog("ChatFooter hook skipped: ${it.message}")
        }
    }

    private fun hookFlexEditText(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass("com.tencent.mm.ui.widget.cedit.api.MMFlexEditText", classLoader)
            clazz.declaredConstructors.forEach { constructor ->
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val input = param.thisObject as? EditText ?: return
                        rememberInput(input)
                        input.post { applyHintToInput(input) }
                    }
                })
            }
            xlog("hooked MMFlexEditText constructors: ${clazz.declaredConstructors.size}")
        }.onFailure {
            xlog("MMFlexEditText hook skipped: ${it.message}")
        }
    }

    private fun hookTextViewSetHint() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setHint",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val input = param.thisObject as? EditText ?: return
                        if (applyingHint.get()) return
                        if (!isChatInput(input)) return
                        input.post { applyHintToInput(input) }
                    }
                }
            )
            xlog("hooked TextView.setHint(CharSequence)")
        }.onFailure {
            xlog("TextView.setHint hook skipped: ${it.message}")
        }
    }

    private fun installDatabaseHooks(classLoader: ClassLoader) {
        if (dbHookInstalled.get()) return
        val dbClass = findDbClass(classLoader) ?: run {
            xlog("database class not ready")
            return
        }
        var count = 0
        count += hookInsertMethod(dbClass, "insert", String::class.java, String::class.java, ContentValues::class.java)
        count += hookInsertMethod(dbClass, "insertOrThrow", String::class.java, String::class.java, ContentValues::class.java)
        count += hookInsertMethod(
            dbClass,
            "insertWithOnConflict",
            String::class.java,
            String::class.java,
            ContentValues::class.java,
            Int::class.javaPrimitiveType!!
        )
        if (count > 0) {
            dbHookInstalled.set(true)
            xlog("hooked $count DB insert methods on ${dbClass.name}")
        }
    }

    private fun hookInsertMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Int {
        return try {
            XposedHelpers.findAndHookMethod(clazz, name, *params, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val result = param.result as? Number
                    if (result != null && result.toLong() <= 0L) return
                    val table = param.args.firstOrNull { it is String } as? String ?: return
                    if (table != MESSAGE_TABLE) return
                    val values = param.args.firstOrNull { it is ContentValues } as? ContentValues ?: return
                    handleInsertedMessage(values)
                }
            })
            1
        } catch (_: Throwable) {
            0
        }
    }

    private fun handleInsertedMessage(values: ContentValues) {
        runCatching {
            val options = InputStatsConfig.load()
            if (!options.enabled || !options.countSend) return
            if ((values.getAsInteger("isSend") ?: values.getAsInteger("field_isSend") ?: 0) != 1) return
            val type = values.getAsInteger("type") ?: values.getAsInteger("field_type") ?: return
            val content = values.getAsString("content") ?: values.getAsString("field_content") ?: ""
            if (!recentMessages.shouldCount(messageIdentity(values, type, content))) return
            val prefs = statsPrefs() ?: return
            val dateKey = todayKey()
            val current = loadSnapshot(prefs, dateKey)
            val next = InputStatsFormatter.addOutgoing(current, type, content)
            saveSnapshot(prefs, next)
            postUpdateHint()
        }.onFailure {
            Log.e(TAG, "handleInsertedMessage error", it)
        }
    }

    private fun messageIdentity(values: ContentValues, type: Int, content: String): String {
        val talker = values.getAsString("talker")
            ?: values.getAsString("field_talker")
            ?: values.getAsString("username")
            ?: values.getAsString("field_username")
            ?: ""
        val createTime = values.getAsLong("createTime")
            ?: values.getAsLong("field_createTime")
            ?: values.getAsLong("msgCreateTime")
            ?: values.getAsLong("field_msgCreateTime")
            ?: 0L
        val msgId = values.getAsLong("msgId")
            ?: values.getAsLong("field_msgId")
            ?: values.getAsLong("rowid")
            ?: values.getAsLong("field_rowid")
            ?: 0L
        val msgSvrId = values.getAsLong("msgSvrId")
            ?: values.getAsLong("field_msgSvrId")
            ?: 0L
        val contentKey = content.hashCode()
        return "$talker|$createTime|$msgId|$msgSvrId|$type|$contentKey"
    }

    private fun postUpdateHint() {
        val footer = chatFooterView ?: return
        footer.post { updateHint(footer) }
    }

    private fun updateHint(footer: View) {
        collectEditTexts(footer)
        val editText = findEditText(footer)
        if (editText != null) {
            applyHintToInput(editText)
        }
        synchronized(trackedInputs) {
            trackedInputs.toList()
        }.forEach { input ->
            applyHintToInput(input)
        }
    }

    private fun applyHintToInput(input: View) {
        runCatching {
            val editText = input as? EditText ?: return
            if (!isChatInput(editText)) return
            rememberInput(editText)
            val options = InputStatsConfig.load()
            val desired = if (options.enabled) {
                val prefs = statsPrefs() ?: return
                val stats = loadSnapshot(prefs, todayKey())
                InputStatsFormatter.format(options.template, stats)
            } else {
                ""
            }
            if (editText.hint?.toString() == desired) return
            if (!applyingHint.compareAndSet(false, true)) return
            try {
                editText.hint = desired
            } finally {
                applyingHint.set(false)
            }
        }.onFailure {
            Log.e(TAG, "applyHintToInput error", it)
        }
    }

    private fun rememberInput(input: EditText) {
        synchronized(trackedInputs) {
            trackedInputs.add(input)
        }
    }

    private fun isChatInput(input: EditText): Boolean {
        if (input.javaClass.name == "com.tencent.mm.ui.widget.cedit.api.MMFlexEditText") return true
        if (input.javaClass.name.contains("MMFlexEditText")) return true
        val footer = chatFooterView
        if (footer is ViewGroup && containsDescendant(footer, input)) return true
        return hasParentClass(input, "com.tencent.mm.pluginsdk.ui.chat.ChatFooter")
    }

    private fun collectEditTexts(view: View) {
        if (view is EditText) rememberInput(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectEditTexts(view.getChildAt(i))
            }
        }
    }

    private fun containsDescendant(root: ViewGroup, target: View): Boolean {
        if (root === target) return true
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child === target) return true
            if (child is ViewGroup && containsDescendant(child, target)) return true
        }
        return false
    }

    private fun hasParentClass(view: View, className: String): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent.javaClass.name == className) return true
            parent = parent.parent
        }
        return false
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findEditText(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun statsPrefs(): SharedPreferences? {
        return appContext?.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
    }

    private fun loadSnapshot(prefs: SharedPreferences, dateKey: String): InputStatsSnapshot {
        val stats = InputStatsSnapshot(
            dateKey = prefs.getString("date", "").orEmpty(),
            totalMsg = prefs.getInt("totalMsg", 0),
            textMsg = prefs.getInt("textMsg", 0),
            textWord = prefs.getInt("textWord", 0),
            emojiMsg = prefs.getInt("emojiMsg", 0),
            transferMsg = prefs.getInt("transferMsg", 0),
            redBagMsg = prefs.getInt("redBagMsg", 0),
            fileMsg = prefs.getInt("fileMsg", 0)
        )
        val normalized = InputStatsFormatter.normalizeForDate(stats, dateKey)
        if (normalized != stats) saveSnapshot(prefs, normalized)
        return normalized
    }

    private fun saveSnapshot(prefs: SharedPreferences, stats: InputStatsSnapshot) {
        prefs.edit()
            .putString("date", stats.dateKey)
            .putInt("totalMsg", stats.totalMsg)
            .putInt("textMsg", stats.textMsg)
            .putInt("textWord", stats.textWord)
            .putInt("emojiMsg", stats.emojiMsg)
            .putInt("transferMsg", stats.transferMsg)
            .putInt("redBagMsg", stats.redBagMsg)
            .putInt("fileMsg", stats.fileMsg)
            .apply()
    }

    private fun todayKey(): String = LocalDate.now().toString()

    private fun findDbClass(classLoader: ClassLoader): Class<*>? {
        return listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "android.database.sqlite.SQLiteDatabase"
        ).firstNotNullOfOrNull { name ->
            runCatching { XposedHelpers.findClass(name, classLoader) }.getOrNull()
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
