package com.OKK.yes.core.hooks

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Process
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.OKK.yes.core.hooks.ui.StyledDialogs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * 微信消息本地修改 Hook。
 * 支持 TextView 与 MMNeat7extView / NeatTextView 聊天控件。
 */
object EditMessageHook {
    private const val TAG = "OKK-EditMsg"
    private const val KEY = "edit_message"
    private const val EDIT_ID = 1212368196
    private const val MENU_TITLE = "\u4fee\u6539"
    private const val MESSAGE_TABLE = "message"

    private val installed = AtomicBoolean(false)
    private val dbHookInstalled = AtomicBoolean(false)
    private val dexKitLoaded = AtomicBoolean(false)
    private val hookedSelectionMethods = AtomicInteger(0)
    private val editedTexts = ConcurrentHashMap<String, String>()
    private val editedByMsgId = ConcurrentHashMap<Long, String>()
    private val originalByMsgId = ConcurrentHashMap<Long, String>()
    private val originalRawByMsgId = ConcurrentHashMap<Long, String>()
    private val msgGetterCache = ConcurrentHashMap<Class<*>, Method?>()
    private val msgFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private val dbMethodCache = ConcurrentHashMap<Class<*>, Method?>()
    private val dbList = CopyOnWriteArrayList<Any>()
    private val dbClassUpdateCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val dbIdentitySeen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    @Volatile
    private var dbCaptureFull = false

    @Volatile
    private var applyingText = false

    @Volatile
    private var activeMessage: MessageRef? = null

    @Volatile
    private var activeTarget: WeakReference<View>? = null

    @Volatile
    private var activeOriginalText: String? = null

    @Volatile
    private var activeAt: Long = 0L

    @Volatile
    private var menuShown = false

    @Volatile
    private var addingMenu = false

    @Volatile
    private var dialogOpen = false

    private data class MessageRef(
        val msgId: Long,
        val content: String,
        val rawContent: String,
        val message: WeakReference<Any>,
        val view: WeakReference<View>,
        val target: WeakReference<View>
    )

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install enabled=${isEnabled()}")
        hookDatabaseCapture(classLoader)
        hookLongPress()
        hookChattingLongClick(classLoader)
        hookChattingMenuDirect(classLoader)
        hookWechatMenu(classLoader)
        hookTextRebind(classLoader)
        hookMenuItemClick(classLoader)
        hookMenuSelection(context, classLoader, modulePath)
    }

    fun isEnabled(): Boolean =
        PublicConfigStore.getBoolean(KEY, false)

    private fun hookDatabaseCapture(classLoader: ClassLoader) {
        if (!dbHookInstalled.compareAndSet(false, true)) return
        val dbClass = findDbClass(classLoader) ?: return
        runCatching {
            val queryMethods = dbClass.declaredMethods
                .filter { it.name in setOf("rawQuery", "rawQueryWithFactory", "query", "queryWithFactory") }
                .distinctBy { "${it.name}${it.parameterTypes.size}" }
            queryMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        rememberDb(param.thisObject)
                    }
                })
            }
            xlog("database observer installed on ${dbClass.name} (methods=${queryMethods.size})")
        }.onFailure {
            xlog("database observer fail: ${it.message}")
        }
    }

    private fun findDbClass(classLoader: ClassLoader): Class<*>? {
        val candidates = listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "com.tencent.wcdb.database.SQLiteDirectCursor",
            "com.tencent.wcdb.database.SQLiteAsyncCursor",
            "android.database.sqlite.SQLiteDatabase"
        )
        return candidates.firstNotNullOfOrNull { name ->
            runCatching { XposedHelpers.findClass(name, classLoader) }.getOrNull()
        }
    }

    private fun rememberDb(db: Any?) {
        db ?: return
        if (dbCaptureFull) return
        if (!hasDbUpdate(db)) return
        synchronized(dbIdentitySeen) {
            if (dbCaptureFull || !dbIdentitySeen.add(db)) return
            dbList += db
            if (dbList.size >= 4) {
                dbCaptureFull = true
                dbIdentitySeen.clear()
            }
            xlog("captured db instance count=${dbList.size} class=${db.javaClass.name}")
        }
    }

    private fun hasDbUpdateClass(clazz: Class<*>): Boolean =
        dbClassUpdateCache.computeIfAbsent(clazz) { c ->
            c.methods.any {
                it.name in setOf("update", "updateWithOnConflict") &&
                    it.parameterTypes.size >= 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    ContentValues::class.java.isAssignableFrom(it.parameterTypes[1])
            }
        }

    private fun hasDbUpdate(db: Any): Boolean =
        hasDbUpdateClass(db.javaClass)

    private fun hookLongPress() {
        runCatching {
            val method = View::class.java.getDeclaredMethod("performLongClick")
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val view = param.thisObject as? View ?: return
                    if (!inChatting(view)) return
                    val target = editableText(view) ?: return
                    val ref = messageRefFromView(view, target)
                    activeMessage = ref
                    activeTarget = WeakReference(target)
                    activeOriginalText = ref?.content ?: resolveOriginalText(target)
                    activeAt = System.currentTimeMillis()
                    menuShown = false
                    xlog("long press target=${target.javaClass.name} text=${shortLog(getViewText(target))}")
                }
            })
            xlog("long-press observer installed")
        }.onFailure {
            xlog("long-press observer fail: ${it.message}")
        }
    }

    private fun hookChattingLongClick(classLoader: ClassLoader) {
        runCatching {
            val q0Class = runCatching {
                XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.q0", classLoader)
            }.getOrNull() ?: return
            val onLongClickMethod = q0Class.declaredMethods.firstOrNull {
                it.name == "onLongClick" && it.parameterTypes.size == 1 && View::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: return
            onLongClickMethod.isAccessible = true
            XposedBridge.hookMethod(onLongClickMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val view = param.args.firstOrNull() as? View ?: return
                    val target = editableText(view) ?: view
                    val ref = messageRefFromView(view, target)
                    activeMessage = ref
                    activeTarget = WeakReference(target)
                    activeOriginalText = ref?.content ?: resolveOriginalText(target)
                    activeAt = System.currentTimeMillis()
                    menuShown = false
                    xlog("chatting onLongClick view=${view.javaClass.name} msgId=${ref?.msgId}")
                }
            })
            xlog("chatting long-click hook installed on q0")
        }.onFailure {
            xlog("chatting long-click hook fail: ${it.message}")
        }
    }

    private fun hookChattingMenuDirect(classLoader: ClassLoader) {
        // 1. Direct hook m0.a(ContextMenu, View, ContextMenuInfo)
        runCatching {
            val m0Class = runCatching {
                XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.m0", classLoader)
            }.getOrNull()
            if (m0Class != null) {
                val createMethod = m0Class.declaredMethods.firstOrNull { m ->
                    m.parameterTypes.size == 3 &&
                        View::class.java.isAssignableFrom(m.parameterTypes[1])
                }
                if (createMethod != null) {
                    createMethod.isAccessible = true
                    XposedBridge.hookMethod(createMethod, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            val menu = param.args.getOrNull(0) as? Menu ?: return
                            val row = param.args.getOrNull(1) as? View ?: return
                            val target = editableText(row) ?: row
                            val ref = messageRefFromView(row, target) ?: return
                            activeMessage = ref
                            activeTarget = WeakReference(target)
                            activeOriginalText = ref.content
                            activeAt = System.currentTimeMillis()
                            menuShown = true
                            ensureEditItem(menu)
                            xlog("m0.a direct bind msgId=${ref.msgId} text=${shortLog(ref.content)}")
                        }
                    })
                    xlog("direct hook m0.a installed")
                }
            }
        }.onFailure {
            xlog("direct hook m0.a fail: ${it.message}")
        }

        // 2. Direct hook p0.onMMMenuItemSelected(MenuItem, int)
        runCatching {
            val p0Class = runCatching {
                XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.p0", classLoader)
            }.getOrNull()
            if (p0Class != null) {
                val selectMethod = p0Class.declaredMethods.firstOrNull { m ->
                    m.name == "onMMMenuItemSelected" &&
                        m.parameterTypes.size == 2 &&
                        MenuItem::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                        m.parameterTypes[1] == Int::class.javaPrimitiveType
                }
                if (selectMethod != null) {
                    selectMethod.isAccessible = true
                    XposedBridge.hookMethod(selectMethod, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            val item = param.args.getOrNull(0) as? MenuItem ?: return
                            if (item.itemId != EDIT_ID) return
                            param.result = null // 阻止微信后续处理

                            var target = activeTarget?.get()
                            var ref = activeMessage
                            if (ref == null || target == null) {
                                // 尝试从 p0 自身字段提取 ct (字段 d)
                                val tag = runCatching {
                                    val f = p0Class.declaredFields.firstOrNull { it.type.name.endsWith("ct") }
                                    f?.isAccessible = true
                                    f?.get(param.thisObject)
                                }.getOrNull()
                                if (tag != null) {
                                    val msg = messageFromTag(tag)
                                    if (msg != null && isEditableTextMessage(msg)) {
                                        val raw = readMessageContent(msg).orEmpty()
                                        val c = stripSenderPrefix(raw)
                                        val id = readMsgId(msg)
                                        if (id > 0L) {
                                            ref = MessageRef(id, c, raw, WeakReference(msg), WeakReference(target ?: View(XposedBridge::class.java.classLoader.let { null })), WeakReference(target ?: View(XposedBridge::class.java.classLoader.let { null })))
                                            activeMessage = ref
                                        }
                                    }
                                }
                            }

                            if (target == null) {
                                target = ref?.target?.get() ?: ref?.view?.get()
                            }
                            if (target != null) {
                                menuShown = true
                                activeTarget = WeakReference(target)
                                activeOriginalText = ref?.content ?: resolveOriginalText(target)
                                activeAt = System.currentTimeMillis()
                                showActiveEditDialog(target)
                                xlog("direct hook p0 selected success msgId=${ref?.msgId}")
                            } else {
                                xlog("direct hook p0 selected fail: target view null")
                            }
                        }
                    })
                    xlog("direct hook p0.onMMMenuItemSelected installed")
                }
            }
        }.onFailure {
            xlog("direct hook p0 fail: ${it.message}")
        }
    }

    private fun hookWechatMenu(classLoader: ClassLoader) {
        val menuClasses = linkedSetOf<Class<*>>()
        for (pkg in listOf("ra5", "o95", "l75", "m75", "k75", "n75", "l65", "l85", "p95", "n95")) {
            for (name in listOf("g4", "f4", "h4", "d4", "e4")) {
                runCatching {
                    val c = XposedHelpers.findClass("$pkg.$name", classLoader)
                    if (android.view.ContextMenu::class.java.isAssignableFrom(c) ||
                        Menu::class.java.isAssignableFrom(c)
                    ) {
                        menuClasses += c
                    }
                }
            }
        }

        var hooked = 0
        for (menuClass in menuClasses) {
            val methods = menuClass.declaredMethods
                .filter {
                    it.name == "add" ||
                        (it.name.length <= 2 && it.parameterTypes.size in 1..5)
                }
                .distinctBy { "${it.name}${it.parameterTypes.contentToString()}" }
            methods.forEach { method ->
                runCatching {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            val menu = param.thisObject as? Menu ?: return
                            ensureEditItem(menu)
                        }
                    })
                    hooked++
                }
            }
        }
        xlog("wechat menu add hook installed count=$hooked classes=${menuClasses.size}")
    }

    private fun ensureEditItem(menu: Menu) {
        val ref = activeMessage
        val target = ref?.target?.get() ?: activeTarget?.get() ?: return
        if (!isActive(target) || !isActiveMessage(ref) || addingMenu) return
        addingMenu = true
        try {
            val existing = menu.findItem(EDIT_ID)
            val item = existing ?: menu.add(0, EDIT_ID, 0, MENU_TITLE)
            item.title = MENU_TITLE
            findEditIcon(menuContext(menu, target))?.let { iconId ->
                runCatching { item.setIcon(iconId) }.onSuccess {
                    xlog("edit menu icon=native:$iconId")
                }
            }
            runCatching { item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER) }
            item.setOnMenuItemClickListener {
                menuShown = true
                activeTarget = WeakReference(target)
                activeOriginalText = ref?.content ?: resolveOriginalText(target)
                activeAt = System.currentTimeMillis()
                showActiveEditDialog(target)
                true
            }
            xlog(if (existing == null) "edit menu added" else "edit menu rebound")
        } catch (t: Throwable) {
            xlog("edit menu add fail: ${t.message}")
        } finally {
            addingMenu = false
        }
    }

    private fun hookTextRebind(classLoader: ClassLoader) {
        // 1. Hook TextView.setText(CharSequence)
        runCatching {
            val method = TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (applyingText || !isEnabled()) return
                    if (editedByMsgId.isEmpty() && editedTexts.isEmpty()) return
                    val view = param.thisObject as? TextView ?: return
                    if (view.getTag(com.OKK.yes.core.R.id.abc_tag_custom_time) == true) return

                    val tagVal = view.getTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id)
                    if (tagVal != null) {
                        val cachedMsgId = tagVal as? Long ?: -1L
                        if (cachedMsgId <= 0L) return
                        val edited = editedByMsgId[cachedMsgId]
                        if (edited != null) {
                            param.args[0] = edited
                        }
                        return
                    }

                    if (!inChatting(view)) {
                        view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, -1L)
                        return
                    }

                    val incoming = param.args.getOrNull(0)?.toString() ?: return
                    if (incoming.length < 1 || incoming.length > 3000) {
                        view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, -1L)
                        return
                    }

                    val editedByText = editedTexts[incoming]
                    if (editedByText != null) {
                        if (editedByText != incoming) param.args[0] = editedByText
                        return
                    }

                    if (!isMessageTextCandidate(view)) {
                        view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, -1L)
                        return
                    }

                    val msgId = msgIdFromViewTree(view)
                    view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, if (msgId > 0L) msgId else -1L)
                    if (msgId > 0L) {
                        val edited = editedByMsgId[msgId]
                        if (edited != null && edited != incoming) {
                            param.args[0] = edited
                        }
                    }
                }
            })
            xlog("TextView.setText rebind hook installed")
        }.onFailure {
            xlog("TextView.setText rebind hook fail: ${it.message}")
        }

        // 2. Hook NeatTextView.b(CharSequence) & NeatTextView.c(CharSequence, ...)
        runCatching {
            val neatClass = runCatching {
                XposedHelpers.findClass("com.tencent.neattextview.textview.view.NeatTextView", classLoader)
            }.getOrNull() ?: return
            val bMethod = neatClass.declaredMethods.firstOrNull {
                it.name == "b" && it.parameterTypes.size == 1 && it.parameterTypes[0] == CharSequence::class.java
            }
            if (bMethod != null) {
                bMethod.isAccessible = true
                XposedBridge.hookMethod(bMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (applyingText || !isEnabled()) return
                        if (editedByMsgId.isEmpty() && editedTexts.isEmpty()) return
                        val view = param.thisObject as? View ?: return

                        val tagVal = view.getTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id)
                        if (tagVal != null) {
                            val cachedMsgId = tagVal as? Long ?: -1L
                            if (cachedMsgId <= 0L) return
                            val edited = editedByMsgId[cachedMsgId]
                            if (edited != null) {
                                param.args[0] = edited
                            }
                            return
                        }

                        if (!inChatting(view)) {
                            view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, -1L)
                            return
                        }

                        val incoming = param.args.getOrNull(0)?.toString() ?: return
                        if (incoming.length < 1 || incoming.length > 3000) {
                            view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, -1L)
                            return
                        }

                        val editedByText = editedTexts[incoming]
                        if (editedByText != null) {
                            if (editedByText != incoming) param.args[0] = editedByText
                            return
                        }

                        if (!isMessageTextCandidate(view)) {
                            view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, -1L)
                            return
                        }

                        val msgId = msgIdFromViewTree(view)
                        view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, if (msgId > 0L) msgId else -1L)
                        if (msgId > 0L) {
                            val edited = editedByMsgId[msgId]
                            if (edited != null && edited != incoming) {
                                param.args[0] = edited
                            }
                        }
                    }
                })
                xlog("NeatTextView.b rebind hook installed")
            }
        }.onFailure {
            xlog("NeatTextView rebind hook fail: ${it.message}")
        }
    }

    private fun hookMenuItemClick(classLoader: ClassLoader) {
        var ok = false
        runCatching {
            for (name in listOf(
                "o95.h4", "l75.h4", "m75.h4", "k75.h4", "n75.h4", "l65.h4", "l85.h4", "p95.h4", "n95.h4"
            )) {
                val clazz = runCatching { XposedHelpers.findClass(name, classLoader) }.getOrNull()
                    ?: continue
                if (!MenuItem::class.java.isAssignableFrom(clazz)) continue
                val methods = clazz.declaredMethods.filter {
                    it.parameterTypes.isEmpty() &&
                        (it.returnType == Boolean::class.javaPrimitiveType ||
                            it.returnType == Boolean::class.java)
                }
                for (method in methods) {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            val item = param.thisObject as? MenuItem ?: return
                            if (item.itemId != EDIT_ID) return
                            val target = activeTarget?.get() ?: return
                            if (!isActive(target)) return
                            menuShown = true
                            showActiveEditDialog(target)
                            param.result = true
                            xlog("edit menu item dispatched via ${clazz.name}.${method.name}")
                        }
                    })
                    ok = true
                }
            }
        }
        runCatching {
            val impl = XposedHelpers.findClass(
                "com.android.internal.view.menu.MenuItemImpl",
                classLoader
            )
            val method = impl.getDeclaredMethod("invoke")
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val item = param.thisObject as? MenuItem ?: return
                    if (item.itemId != EDIT_ID) return
                    val target = activeTarget?.get() ?: return
                    if (!isActive(target)) return
                    menuShown = true
                    showActiveEditDialog(target)
                    param.result = true
                    xlog("edit menu item dispatched via MenuItemImpl.invoke")
                }
            })
            ok = true
        }
        xlog(if (ok) "wechat menu item click hook installed" else "menu item click hook skipped")
    }

    private fun hookMenuSelection(context: Context, classLoader: ClassLoader, modulePath: String?) {
        runCatching {
            loadDexKitNative(context, modulePath)
            var selectionCount = 0
            var createCount = 0
            DexKitBridge.create(classLoader, true).use { bridge ->
                val createMethods = bridge.findMethod {
                    searchPackages("com.tencent.mm.ui.chatting.viewitems")
                    matcher {
                        usingEqStrings("MicroMsg.ChattingItem", "msg is null!")
                    }
                }
                createMethods.forEach { result ->
                    val method = runCatching { result.descriptor.let { descriptorToMethod(it, classLoader) } }.getOrNull()
                        ?: return@forEach
                    if (hookCreateMenuMethod(method)) createCount++
                }

                val methods = bridge.findMethod {
                    searchPackages("com.tencent.mm.ui.chatting")
                    matcher {
                        name("onMMMenuItemSelected")
                        returnType(Void.TYPE)
                        paramTypes(MenuItem::class.java, Int::class.javaPrimitiveType!!)
                    }
                }
                methods.forEach { result ->
                    val method = runCatching { result.descriptor.let { descriptorToMethod(it, classLoader) } }.getOrNull()
                        ?: return@forEach
                    if (hookSelectionMethod(method)) selectionCount++
                }
            }
            xlog("wechat concrete menu hooks create=$createCount selection=$selectionCount")
        }.onFailure {
            xlog("wechat concrete menu selection hook fail: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun hookCreateMenuMethod(method: Method): Boolean {
        val hasMenu = method.parameterTypes.any { Menu::class.java.isAssignableFrom(it) }
        val hasView = method.parameterTypes.any { View::class.java.isAssignableFrom(it) }
        if (!hasMenu || !hasView) return false
        val key = method.toGenericString()
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val menu = param.args.firstOrNull { it is Menu } as? Menu ?: return
                    val row = param.args.firstOrNull { it is View } as? View ?: return
                    val target = editableText(row) ?: row
                    val ref = messageRefFromView(row, target) ?: return
                    activeMessage = ref
                    activeTarget = WeakReference(target)
                    activeOriginalText = ref.content
                    activeAt = System.currentTimeMillis()
                    menuShown = true
                    ensureEditItem(menu)
                    xlog("create menu bind msgId=${ref.msgId} text=${shortLog(ref.content)} method=$key")
                }
            })
            true
        }.getOrElse {
            xlog("create menu hook fail ${method.declaringClass.name}: ${it.message}")
            false
        }
    }

    private fun hookSelectionMethod(method: Method): Boolean {
        if (method.parameterTypes.size != 2 || method.parameterTypes[0] != MenuItem::class.java ||
            method.parameterTypes[1] != Int::class.javaPrimitiveType || method.returnType != Void.TYPE) return false
        val key = method.toGenericString()
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val item = param.args.firstOrNull { it is MenuItem } as? MenuItem ?: return
                    if (item.itemId != EDIT_ID) return
                    val target = activeTarget?.get() ?: return
                    if (!isActive(target)) return
                    val ref = activeMessage
                    if (!isActiveMessage(ref)) return
                    menuShown = true
                    activeTarget = WeakReference(target)
                    activeOriginalText = ref?.content ?: resolveOriginalText(target)
                    activeAt = System.currentTimeMillis()
                    showActiveEditDialog(target)
                    xlog("edit menu selected class=${method.declaringClass.name} method=$key")
                    param.setResult(null)
                }
            })
            hookedSelectionMethods.incrementAndGet()
            true
        }.getOrElse {
            xlog("selection hook fail ${method.declaringClass.name}: ${it.message}")
            false
        }
    }

    private fun findEditIcon(context: Context?): Int? {
        context ?: return null
        val resources = context.resources
        val names = listOf("icons_filled_pencil", "icons_filled_edit", "icons_outlined_pencil", "icons_outlined_edit")
        return names.firstNotNullOfOrNull { name ->
            val id = resources.getIdentifier(name, "raw", context.packageName)
            if (id != 0) id else null
        }
    }

    private fun menuContext(menu: Menu, target: View): Context? =
        (menu as? android.view.ContextMenu)?.let { target.context } ?: target.context

    private fun loadDexKitNative(context: Context, modulePath: String?) {
        if (!dexKitLoaded.compareAndSet(false, true)) return
        runCatching {
            System.loadLibrary("dexkit")
            xlog("loaded native dexkit library")
            return
        }
        val path = modulePath ?: return
        val zip = runCatching { ZipFile(File(path)) }.getOrNull() ?: return
        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            listOf(Build.CPU_ABI, Build.CPU_ABI2).filter { !it.isNullOrBlank() }
        }
        val entry = abis.firstNotNullOfOrNull { abi -> zip.getEntry("lib/$abi/libdexkit.so") } ?: return
        val out = File(context.cacheDir, "abc_${Process.myPid()}_libdexkit.so")
        zip.getInputStream(entry).use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        System.load(out.absolutePath)
        xlog("loaded native dexkit library from cache path=${out.absolutePath}")
    }

    private fun descriptorToMethod(descriptor: String, classLoader: ClassLoader): Method {
        val (declaringRaw, signature) = descriptor.split("->", limit = 2)
        val declaring = declaringRaw.removePrefix("L").removeSuffix(";").replace('/', '.')
        val clazz = XposedHelpers.findClass(declaring, classLoader)
        return clazz.declaredMethods.firstOrNull { methodSignature(it) == signature }
            ?: clazz.methods.first { methodSignature(it) == signature }
    }

    private fun methodSignature(method: Method): String = buildString {
        append(method.name)
        append('(')
        method.parameterTypes.forEach { append(typeSignature(it)) }
        append(')')
        append(typeSignature(method.returnType))
    }

    private fun typeSignature(type: Class<*>): String = when {
        type == Void.TYPE -> "V"
        type == Boolean::class.javaPrimitiveType -> "Z"
        type == Byte::class.javaPrimitiveType -> "B"
        type == Char::class.javaPrimitiveType -> "C"
        type == Short::class.javaPrimitiveType -> "S"
        type == Int::class.javaPrimitiveType -> "I"
        type == Long::class.javaPrimitiveType -> "J"
        type == Float::class.javaPrimitiveType -> "F"
        type == Double::class.javaPrimitiveType -> "D"
        type.isArray -> "[" + typeSignature(type.componentType)
        else -> "L" + type.name.replace('.', '/') + ";"
    }

    private fun messageRefFromView(view: View, target: View): MessageRef? {
        val message = messageFromTag(view.tag) ?: findMessageObject(view, 0, identitySet())
        val msgId = message?.let(::readMsgId) ?: 0L
        if (message == null || msgId <= 0L) return null
        if (!isEditableTextMessage(message)) return null
        val rawContent = readMessageContent(message)?.takeIf { it.isNotBlank() } ?: return null
        val content = stripSenderPrefix(rawContent).takeIf { it.isNotBlank() } ?: return null
        return MessageRef(msgId, content, rawContent, WeakReference(message), WeakReference(view), WeakReference(target))
    }

    private fun messageFromTag(tag: Any?): Any? {
        tag ?: return null
        if (isMessageObject(tag) && readMsgId(tag) > 0L) return tag
        val getter = msgGetterCache.computeIfAbsent(tag.javaClass) { clazz ->
            allMethods(clazz).firstOrNull {
                it.parameterTypes.isEmpty() && isMessageClass(it.returnType)
            }?.apply { isAccessible = true }
        }
        runCatching { getter?.invoke(tag) }.getOrNull()?.let { message ->
            if (readMsgId(message) > 0L) return message
        }
        val field = msgFieldCache.computeIfAbsent(tag.javaClass) { clazz ->
            allFields(clazz).firstOrNull { isMessageClass(it.type) }?.apply { isAccessible = true }
        }
        return runCatching { field?.get(tag) }.getOrNull()?.takeIf { readMsgId(it) > 0L }
    }

    private fun findMessageObject(root: Any?, depth: Int, seen: MutableSet<Any>): Any? {
        if (root == null || depth > 5 || !seen.add(root)) return null
        messageFromTag(root)?.let { return it }
        if (root is View) {
            messageFromTag(root.tag)?.let { return it }
        }
        if (root is Array<*>) {
            root.forEach { findMessageObject(it, depth + 1, seen)?.let { found -> return found } }
            return null
        }
        if (root is Iterable<*>) {
            root.forEach { findMessageObject(it, depth + 1, seen)?.let { found -> return found } }
            return null
        }
        val clazz = root.javaClass
        if (clazz.name.startsWith("java.") || clazz.name.startsWith("android.")) return null
        for (field in allFields(clazz)) {
            if (isMessageClass(field.type)) {
                runCatching {
                    field.isAccessible = true
                    field.get(root)
                }.getOrNull()?.takeIf { readMsgId(it) > 0L }?.let { return it }
            }
        }
        for (field in allFields(clazz)) {
            val value = runCatching {
                field.isAccessible = true
                field.get(root)
            }.getOrNull() ?: continue
            findMessageObject(value, depth + 1, seen)?.let { return it }
        }
        return null
    }

    private fun msgIdFromViewTree(view: View): Long {
        var current: View? = view
        repeat(8) {
            val v = current ?: return 0L
            val tag = v.tag
            messageFromTag(tag)?.let { return readMsgId(it) }
            messageRefFromView(v, view)?.let { return it.msgId }
            current = v.parent as? View
        }
        return 0L
    }

    private fun readMsgId(message: Any): Long =
        readNumber(message, "getMsgId", "getMsgID", "field_msgId", "msgId", "msgID", "id")?.toLong() ?: 0L

    private fun readMessageContent(message: Any): String? =
        readString(message, "S1", "getContent", "field_content", "content", "j")

    private fun writeMessageContent(message: Any, content: String): Boolean {
        allMethods(message.javaClass).firstOrNull {
            it.name in setOf("c1", "setContent", "setMsgContent") &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        }?.let { method ->
            runCatching {
                method.isAccessible = true
                method.invoke(message, content)
            }.onSuccess {
                return true
            }.onFailure {
                xlog("content setter fail ${message.javaClass.name}.${method.name}: ${it.message}")
            }
        }
        allFields(message.javaClass).firstOrNull {
            it.name in setOf("field_content", "content") && it.type == String::class.java
        }?.let { field ->
            runCatching {
                field.isAccessible = true
                field.set(message, content)
            }.onSuccess {
                return true
            }.onFailure {
                xlog("content field fail ${message.javaClass.name}.${field.name}: ${it.message}")
            }
        }
        return false
    }

    private fun isEditableTextMessage(message: Any): Boolean {
        val type = readNumber(message, "getType", "field_type", "type")?.toInt()
        val content = readMessageContent(message).orEmpty()
        if (type != null && type != 1) return false
        if (content.length > 4000) return false
        if (content.startsWith("<msg>") || content.startsWith("~SEMI_XML~")) return false
        return true
    }

    private fun isMessageObject(value: Any): Boolean = isMessageClass(value.javaClass)

    private fun isMessageClass(clazz: Class<*>): Boolean =
        clazz.name.startsWith("com.tencent.mm.storage.") ||
        clazz.name == "ms0.m1" ||
        clazz.name == "tl.b8" ||
        clazz.name == "nt0.m1" ||
        clazz.name == "sm.b8"

    private fun readString(target: Any, vararg names: String): String? {
        names.forEach { name ->
            allMethods(target.javaClass).firstOrNull {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }?.let { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(target) as? String
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            allFields(target.javaClass).firstOrNull { it.name == name && it.type == String::class.java }?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(target) as? String
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private fun readNumber(target: Any, vararg names: String): Number? {
        names.forEach { name ->
            allMethods(target.javaClass).firstOrNull {
                it.name == name && it.parameterTypes.isEmpty() && Number::class.java.isAssignableFrom(boxType(it.returnType))
            }?.let { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(target) as? Number
                }.getOrNull()?.let { return it }
            }
            allFields(target.javaClass).firstOrNull { it.name == name && Number::class.java.isAssignableFrom(boxType(it.type)) }?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(target) as? Number
                }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun boxType(type: Class<*>): Class<*> = when (type) {
        Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
        Byte::class.javaPrimitiveType -> java.lang.Byte::class.java
        Char::class.javaPrimitiveType -> java.lang.Character::class.java
        Short::class.javaPrimitiveType -> java.lang.Short::class.java
        Int::class.javaPrimitiveType -> java.lang.Integer::class.java
        Long::class.javaPrimitiveType -> java.lang.Long::class.java
        Float::class.javaPrimitiveType -> java.lang.Float::class.java
        Double::class.javaPrimitiveType -> java.lang.Double::class.java
        else -> type
    }

    private fun allMethods(clazz: Class<*>): Sequence<Method> = sequence {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredMethods.forEach { yield(it) }
            current = current.superclass
        }
    }

    private fun allFields(clazz: Class<*>): Sequence<Field> = sequence {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { yield(it) }
            current = current.superclass
        }
    }

    private fun identitySet(): MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap())

    private fun stripSenderPrefix(content: String): String {
        val index = senderPrefixEnd(content) ?: return content
        return content.substring(index).trimStart()
    }

    private fun rawContentWithEditedBody(rawContent: String, body: String): String {
        val index = senderPrefixEnd(rawContent) ?: return body
        val prefix = rawContent.substring(0, index)
        return prefix + body
    }

    private fun senderPrefixEnd(content: String): Int? {
        val rn = content.indexOf(":\r\n").takeIf { it in 1..80 }?.let { it + 3 }
        val n = content.indexOf(":\n").takeIf { it in 1..80 }?.let { it + 2 }
        return listOfNotNull(rn, n).minOrNull()
    }

    private fun getViewText(view: View): String {
        if (view is TextView) return view.text?.toString().orEmpty()
        return runCatching {
            val aMethod = view.javaClass.getMethod("a")
            aMethod.invoke(view)?.toString().orEmpty()
        }.getOrElse {
            runCatching {
                val getText = view.javaClass.getMethod("getText")
                getText.invoke(view)?.toString().orEmpty()
            }.getOrDefault("")
        }
    }

    private fun setViewText(view: View, text: String) {
        applyingText = true
        try {
            if (view is TextView) {
                runCatching { view.text = text }.onFailure { view.setText(text) }
            } else {
                val bMethod = runCatching {
                    view.javaClass.getMethod("b", CharSequence::class.java)
                }.getOrNull()
                if (bMethod != null) {
                    bMethod.invoke(view, text)
                } else {
                    val setText = runCatching {
                        view.javaClass.getMethod("setText", CharSequence::class.java)
                    }.getOrNull()
                    setText?.invoke(view, text)
                }
            }
            view.invalidate()
            view.requestLayout()
        } finally {
            applyingText = false
        }
    }

    private fun isNeatOrTextView(view: View): Boolean {
        if (view is TextView) return true
        val name = view.javaClass.name
        return name.contains("NeatTextView") || name.contains("MMNeat7extView")
    }

    private fun editableText(view: View): View? {
        if (isNeatOrTextView(view) && isMessageTextCandidate(view)) return view
        var current: View? = view
        var best: View? = null
        var bestScore = Int.MIN_VALUE
        repeat(8) {
            val parent = current?.parent as? View ?: return@repeat
            if (parent is ViewGroup) {
                val candidates = ArrayList<View>()
                collectTextViews(parent, candidates)
                candidates.forEach { candidate ->
                    if (!isMessageTextCandidate(candidate)) return@forEach
                    val score = messageTextScore(candidate, view)
                    if (score > bestScore) {
                        best = candidate
                        bestScore = score
                    }
                }
                if (bestScore >= 60) return best
            }
            current = parent
        }
        return best
    }

    private fun collectTextViews(view: View, out: MutableList<View>) {
        if (isNeatOrTextView(view)) out += view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTextViews(view.getChildAt(i), out)
        }
    }

    private fun isMessageTextCandidate(v: View): Boolean {
        if (v.getTag(com.OKK.yes.core.R.id.abc_tag_custom_time) == true) return false
        if (v.visibility != View.VISIBLE || v.alpha <= 0f) return false
        val text = getViewText(v).trim()
        if (text.isBlank() || text.length > 4000) return false
        if (text == MENU_TITLE || text.startsWith("\u270E")) return false
        if (looksLikeMetaText(text)) return false
        return true
    }

    private fun messageTextScore(v: View, origin: View): Int {
        val text = getViewText(v).trim()
        val sp = if (v is TextView) {
            v.textSize / v.resources.displayMetrics.scaledDensity
        } else {
            runCatching {
                val getTextSize = v.javaClass.getMethod("getTextSize")
                (getTextSize.invoke(v) as Number).toFloat() / v.resources.displayMetrics.scaledDensity
            }.getOrDefault(16f)
        }
        var score = text.length.coerceAtMost(120)
        if (v === origin) score += 90
        if (sp >= 15f) score += 45 else score -= 35
        if (text.length <= 2 && sp < 16f) score -= 30
        if (v.width > 0 && v.height > 0) score += ((v.width * v.height) / 1200).coerceAtMost(80)
        if (text.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }) score += 15
        return score
    }

    private fun looksLikeMetaText(text: String): Boolean {
        val compact = text.trim()
        if (compact.matches(Regex("""\d{1,2}:\d{2}(:\d{2})?"""))) return true
        if (compact.matches(Regex("""\d{1,2}\u6708\d{1,2}\u65e5\s+\d{1,2}:\d{2}"""))) return true
        if (compact.matches(Regex("""\d{1,2}[-/]\d{1,2}\s+.*"""))) return true
        if (compact.contains("\u5206\u949f\u524d") || compact.contains("\u5c0f\u65f6\u524d") || compact.contains("\u6628\u5929") || compact.contains("\u524d\u5929")) {
            if (compact.any { it.isDigit() } && compact.length <= 40) return true
        }
        if (compact.matches(Regex(""".*\b(KB|MB|GB)\b.*""", RegexOption.IGNORE_CASE))) return true
        if (compact in setOf("\u672a\u4e0b\u8f7d", "\u8f6c\u6587\u5b57", "\u5fae\u4fe1\u7f51\u9875\u7248", "\u4e2a\u4eba\u540d\u7247")) return true
        return false
    }

    private fun isActive(target: View): Boolean =
        activeTarget?.get() === target && System.currentTimeMillis() - activeAt < 8_000L

    private fun isActiveMessage(ref: MessageRef?): Boolean =
        ref != null && ref.msgId > 0L

    private fun inChatting(view: View): Boolean {
        var current: Any? = view
        repeat(16) {
            val name = (current as? View)?.javaClass?.name ?: return@repeat
            if (name.contains("ChatFooter")) return false
            if (name.contains("chatting", ignoreCase = true)) return true
            current = (current as? View)?.parent
        }
        return false
    }

    private fun showActiveEditDialog(target: View) {
        val ref = activeMessage
        if (isActiveMessage(ref)) {
            showEditDialog(ref!!, target)
            return
        }
        Toast.makeText(target.context, "\u5f53\u524d\u6d88\u606f\u4e0d\u53ef\u4fee\u6539", Toast.LENGTH_SHORT).show()
        xlog("edit blocked: no active message ref")
    }

    private fun showEditDialog(target: View) {
        if (!isEnabled()) return
        if (dialogOpen) return
        menuShown = true
        val ctx = target.context ?: return
        val originalText = resolveOriginalText(target)
        val currentText = editedTexts[originalText] ?: originalText
        showStyledEditDialog(ctx, originalText, currentText, isEdited = editedTexts.containsKey(originalText)) { newText, isReset ->
            if (isReset) {
                editedTexts.remove(originalText)
                if (currentText.isNotBlank()) editedTexts.remove(currentText)
                applyEditedText(target, originalText)
                Toast.makeText(ctx, "\u5df2\u8fd8\u539f\u539f\u59cb\u6d88\u606f", Toast.LENGTH_SHORT).show()
            } else {
                if (originalText.isNotBlank()) editedTexts[originalText] = newText
                applyEditedText(target, newText)
                Toast.makeText(ctx, "\u5df2\u4fdd\u5b58\u672c\u5730\u4fee\u6539", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEditDialog(ref: MessageRef, target: View) {
        if (!isEnabled()) return
        if (dialogOpen) return
        menuShown = true
        val ctx = target.context ?: return
        val originalText = originalByMsgId[ref.msgId] ?: ref.content
        val currentText = editedByMsgId[ref.msgId] ?: ref.content
        val isEdited = editedByMsgId.containsKey(ref.msgId) || editedTexts.containsKey(originalText)

        showStyledEditDialog(ctx, originalText, currentText, isEdited) { newText, isReset ->
            if (isReset) {
                val origText = originalByMsgId.remove(ref.msgId) ?: originalText
                val origRaw = originalRawByMsgId.remove(ref.msgId) ?: ref.rawContent
                editedByMsgId.remove(ref.msgId)
                if (origText.isNotBlank()) editedTexts.remove(origText)
                if (currentText.isNotBlank()) editedTexts.remove(currentText)
                target.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, -1L)
                val rawRestored = rawContentWithEditedBody(origRaw, origText)
                ref.message.get()?.let { writeMessageContent(it, rawRestored) }
                updateMessageContentInDb(ref.msgId, rawRestored)
                applyEditedVisual(ref, target, origText, origText)
                target.post { applyEditedVisual(ref, target, origText, origText) }
                Toast.makeText(ctx, "\u5df2\u8fd8\u539f\u539f\u59cb\u6d88\u606f", Toast.LENGTH_SHORT).show()
                xlog("restore msgId=${ref.msgId} origText=${shortLog(origText)}")
            } else {
                if (!originalByMsgId.containsKey(ref.msgId)) {
                    originalByMsgId[ref.msgId] = originalText
                    originalRawByMsgId[ref.msgId] = ref.rawContent
                }
                editedByMsgId[ref.msgId] = newText
                val origText = originalByMsgId[ref.msgId] ?: originalText
                if (origText.isNotBlank()) editedTexts[origText] = newText
                target.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, ref.msgId)
                val baseRaw = originalRawByMsgId[ref.msgId] ?: ref.rawContent
                val rawEdited = rawContentWithEditedBody(baseRaw, newText)
                val objectApplied = ref.message.get()?.let { writeMessageContent(it, rawEdited) } == true
                val dbRows = updateMessageContentInDb(ref.msgId, rawEdited)
                applyEditedVisual(ref, target, origText, newText)
                target.post { applyEditedVisual(ref, target, origText, newText) }
                Toast.makeText(ctx, "\u5df2\u4fdd\u5b58\u672c\u5730\u4fee\u6539", Toast.LENGTH_SHORT).show()
                xlog("edited msgId=${ref.msgId} objectApplied=$objectApplied dbRows=$dbRows")
            }
        }
    }

    private fun showStyledEditDialog(
        ctx: Context,
        originalText: String,
        currentText: String,
        isEdited: Boolean,
        onResult: (newText: String, isReset: Boolean) -> Unit
    ) {
        val night = StyledDialogs.isNight(ctx)
        val cardBg = if (night) 0xFF1E1F24.toInt() else Color.WHITE
        val primaryTxt = if (night) 0xFFEAEAEA.toInt() else 0xFF1A1A1A.toInt()
        val subTxt = if (night) 0xFF9A9A9A.toInt() else 0xFF7A7A7A.toInt()
        val faintBg = if (night) 0x14FFFFFF else 0x0F000000
        val divider = if (night) 0x1FFFFFFF else 0x12000000
        val accentColor = 0xFF07C160.toInt()

        var dialog: AlertDialog? = null
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 22).toFloat()
                setColor(cardBg)
            }
            setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 16))
        }

        // Title
        root.addView(TextView(ctx).apply {
            text = "\u4fee\u6539\u672c\u5730\u6d88\u606f"
            textSize = 17f
            setTextColor(primaryTxt)
            typeface = Typeface.DEFAULT_BOLD
        })

        // Subtitle / Tip
        root.addView(TextView(ctx).apply {
            text = "\u4fee\u6539\u4ec5\u5728\u5f53\u524d\u8bbe\u5907\u751f\u6548\uff0c\u6ed1\u52a8\u6d88\u606f\u5217\u8868\u65f6\u4f1a\u81ea\u52a8\u5237\u65b0"
            textSize = 12.5f
            setTextColor(subTxt)
            setPadding(0, dp(ctx, 4), 0, 0)
        })

        // EditText container
        val input = EditText(ctx).apply {
            setText(currentText)
            textSize = 14.5f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(primaryTxt)
            setHintTextColor(subTxt)
            minLines = 3
            maxLines = 7
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(faintBg)
                setStroke(dp(ctx, 1), divider)
            }
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            if (currentText.isNotEmpty()) setSelection(currentText.length)
        }
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 14) })

        // Buttons row
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 16), 0, 0)
        }

        if (isEdited) {
            val restoreBtn = TextView(ctx).apply {
                text = "\u8fd8\u539f"
                textSize = 13.5f
                setTextColor(0xFFE64545.toInt())
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 16).toFloat()
                    setColor(faintBg)
                }
                setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                setOnClickListener {
                    dialog?.dismiss()
                    onResult(originalText, true)
                }
            }
            btnRow.addView(restoreBtn)
        }

        val spacer = View(ctx)
        btnRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        // Cancel button
        val cancelBtn = TextView(ctx).apply {
            text = "\u53d6\u6d88"
            textSize = 14f
            setTextColor(subTxt)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                setColor(faintBg)
            }
            setPadding(dp(ctx, 18), dp(ctx, 8), dp(ctx, 18), dp(ctx, 8))
            setOnClickListener { dialog?.dismiss() }
        }
        btnRow.addView(cancelBtn)

        // Save button
        val saveBtn = TextView(ctx).apply {
            text = "\u4fdd\u5b58\u4fee\u6539"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                setColor(accentColor)
            }
            setPadding(dp(ctx, 22), dp(ctx, 8), dp(ctx, 22), dp(ctx, 8))
            setOnClickListener {
                val newText = input.text?.toString().orEmpty()
                dialog?.dismiss()
                onResult(newText, false)
            }
        }
        btnRow.addView(saveBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(ctx, 8) })

        root.addView(btnRow)

        val d = AlertDialog.Builder(ctx).setView(root).create()
        dialog = d
        d.setOnDismissListener { dialogOpen = false }
        dialogOpen = true
        d.show()
        d.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            val dm = ctx.resources.displayMetrics
            val lp = w.attributes
            lp.width = (dm.widthPixels * 0.86f).toInt()
            w.attributes = lp
        }
    }

    private fun resolveOriginalText(target: View): String {
        val active = activeOriginalText
        val current = getViewText(target)
        return if (!active.isNullOrBlank() && editedTexts[active] == current) active else current
    }

    private fun applyEditedText(target: View, text: String) {
        setViewText(target, text)
    }

    private fun applyEditedVisual(ref: MessageRef, target: View, originalText: String, newText: String): Int {
        val targets = java.util.LinkedHashSet<View>()
        targets += target
        ref.target.get()?.let { targets += it }
        ref.view.get()?.let { row ->
            val candidates = ArrayList<View>()
            collectTextViews(row, candidates)
            candidates.forEach { candidate ->
                val text = getViewText(candidate)
                if (candidate === target || candidate === ref.target.get() || text == originalText || editedTexts[text] == newText) {
                    if (isMessageTextCandidate(candidate) || candidate === target || candidate === ref.target.get()) {
                        targets += candidate
                    }
                }
            }
        }
        targets.forEach { applyEditedText(it, newText) }
        ref.view.get()?.let {
            it.invalidate()
            it.requestLayout()
        }
        return targets.size
    }

    private fun updateMessageContentInDb(msgId: Long, content: String): Int {
        if (msgId <= 0L) return 0
        var rows = 0
        val dbs = dbList.distinct()
        for (db in dbs) {
            val updated = invokeDbUpdate(db, MESSAGE_TABLE, content, "msgId=?", arrayOf(msgId.toString()))
            if (updated > 0) {
                rows += updated
                continue
            }
            rows += invokeDbUpdate(db, MESSAGE_TABLE, content, "msgId=$msgId", emptyArray())
        }
        if (rows <= 0) xlog("db update missed msgId=$msgId dbs=${dbs.size}")
        return rows
    }

    private fun invokeDbUpdate(
        db: Any,
        table: String,
        content: String,
        where: String,
        args: Array<String>
    ): Int {
        val values = ContentValues().apply { put("content", content) }
        val method = dbMethodCache.computeIfAbsent(db.javaClass) { clazz ->
            clazz.methods
                .filter {
                    it.name in setOf("update", "updateWithOnConflict") &&
                        it.parameterTypes.size >= 4 &&
                        it.parameterTypes[0] == String::class.java &&
                        ContentValues::class.java.isAssignableFrom(it.parameterTypes[1])
                }
                .minByOrNull { it.parameterTypes.size }
                ?.apply { isAccessible = true }
        } ?: return 0

        val result = runCatching {
            val pts = method.parameterTypes
            when {
                pts.size == 4 -> method.invoke(db, table, values, where, args)
                pts.size == 5 -> method.invoke(db, table, values, where, args, 0)
                else -> {
                    val extras = Array(pts.size - 4) { null as Any? }
                    method.invoke(db, table, values, where, args, *extras)
                }
            }
        }.onFailure {
            xlog("db update fail ${db.javaClass.name}.${method.name}: ${it.message}")
        }.getOrNull()
        return (result as? Number)?.toInt() ?: 0
    }

    private fun shortLog(text: String): String =
        text.replace('\n', ' ').take(32)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
