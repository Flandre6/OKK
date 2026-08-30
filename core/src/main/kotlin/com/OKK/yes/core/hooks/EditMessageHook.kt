package com.OKK.yes.core.hooks

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Process
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/** Local-only message editing. The WeChat message database is never changed. */
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
    private var activeTarget: WeakReference<TextView>? = null

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
        val target: WeakReference<TextView>
    )

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install enabled=${isEnabled()}")
        hookDatabaseCapture(classLoader)
        hookTextRebind()
        hookLongPress()
        hookWechatMenu(classLoader)
        hookMenuItemClick(classLoader)
        hookMenuSelection(context, classLoader, modulePath)
    }

    fun isEnabled(): Boolean =
        runCatching { PublicConfigStore.getBoolean(KEY, false) }.getOrDefault(false)

    private fun hookDatabaseCapture(classLoader: ClassLoader) {
        if (!dbHookInstalled.compareAndSet(false, true)) return
        val dbClass = findDbClass(classLoader)
        if (dbClass == null) {
            xlog("database class not ready")
            return
        }
        var count = 0
        dbClass.declaredMethods
            .filter { it.name in setOf("rawQuery", "query") }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            rememberDb(param.thisObject)
                        }
                    })
                    count++
                }
            }
        xlog("database capture hooks=$count on ${dbClass.name}")
    }

    private fun findDbClass(classLoader: ClassLoader): Class<*>? {
        return listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "android.database.sqlite.SQLiteDatabase"
        ).firstNotNullOfOrNull { className ->
            runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull()
        }
    }

    private fun rememberDb(db: Any?) {
        if (db == null) return
        if (dbCaptureFull) return
        synchronized(dbIdentitySeen) {
            if (dbCaptureFull) return
            if (dbIdentitySeen.contains(db)) return
            if (dbList.any { it === db }) {
                dbIdentitySeen.add(db)
                return
            }
            if (dbClassUpdateCache.computeIfAbsent(db.javaClass) { hasDbUpdateClass(it) } != true) return
            dbIdentitySeen.add(db)
            if (dbList.size >= 4) {
                dbCaptureFull = true
                return
            }
        }
        dbList.add(db)
        if (dbList.size >= 4) dbCaptureFull = true
        if (dbList.size <= 2) xlog("remember db=${db.javaClass.name} total=${dbList.size}")
    }

    private fun hasDbUpdateClass(clazz: Class<*>): Boolean {
        return clazz.methods.any {
            it.name == "update" &&
                it.parameterTypes.size >= 4 &&
                it.parameterTypes[0] == String::class.java &&
                ContentValues::class.java.isAssignableFrom(it.parameterTypes[1])
        }
    }

    private fun hasDbUpdate(db: Any): Boolean {
        return dbClassUpdateCache.computeIfAbsent(db.javaClass) { hasDbUpdateClass(it) }
    }

    private fun hookLongPress() {
        runCatching {
            val method = View::class.java.getDeclaredMethod("performLongClick")
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val view = param.thisObject as? View ?: return
                    if (!inChatting(view)) return
                    val target = editableText(view) ?: return
                    activeMessage = messageRefFromView(view, target)
                    activeTarget = WeakReference(target)
                    activeOriginalText = activeMessage?.content ?: resolveOriginalText(target)
                    activeAt = System.currentTimeMillis()
                    menuShown = false
                    xlog("long press target=${target.javaClass.name} text=${shortLog(target.text?.toString().orEmpty())}")
                }
            })
            xlog("long-press observer installed")
        }.onFailure {
            xlog("long-press observer fail: ${it.message}")
        }
    }

    private fun hookWechatMenu(classLoader: ClassLoader) {
        // l75.g4 仅 8.0.69 存在；其它版本找 ContextMenu 实现类
        val menuClasses = linkedSetOf<Class<*>>()
        runCatching {
            menuClasses += XposedHelpers.findClass("l75.g4", classLoader)
        }
        // 常见邻近混淆 + 反射扫接口（轻量：仅已知候选）
        for (name in listOf(
            "l75.g4", "m75.g4", "k75.g4", "n75.g4", "l65.g4", "l85.g4",
            "l75.f4", "l75.h4"
        )) {
            runCatching {
                val c = XposedHelpers.findClass(name, classLoader)
                if (Menu::class.java.isAssignableFrom(c)) menuClasses += c
            }
        }
        var hooked = 0
        for (menuClass in menuClasses) {
            if (!Menu::class.java.isAssignableFrom(menuClass) &&
                !menuClass.interfaces.any { it.name.contains("Menu") }
            ) {
                // 仍尝试：微信菜单类 implements ContextMenu
                if (!menuClass.interfaces.any { it == android.view.ContextMenu::class.java }) {
                    continue
                }
            }
            val methods = menuClass.declaredMethods
                .filter {
                    it.name == "add" ||
                        (it.name.length <= 2 && it.parameterTypes.size in 1..4)
                }
                .distinctBy { "${it.name}${it.parameterTypes.contentToString()}" }
            methods.forEach { method ->
                runCatching {
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
        // 兜底：系统 MenuBuilder（部分机型弹窗）
        runCatching {
            val mb = XposedHelpers.findClass("com.android.internal.view.menu.MenuBuilder", classLoader)
            for (m in mb.declaredMethods.filter { it.name == "add" }) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        ensureEditItem(param.thisObject as? Menu ?: return)
                    }
                })
                hooked++
            }
        }
        xlog("wechat context menu hooks=$hooked classes=${menuClasses.map { it.name }}")
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

    private fun hookTextRebind() {
        runCatching {
            val method = TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (applyingText || !isEnabled()) return
                    if (editedByMsgId.isEmpty() && editedTexts.isEmpty()) return
                    val view = param.thisObject as? TextView ?: return
                    if (view.getTag(com.OKK.yes.core.R.id.abc_tag_custom_time) == true) return

                    // Fast-path 1: Instant tag lookup if view was scanned before
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

                    // Fast-path 2: Check if view is inside chat UI
                    if (!inChatting(view)) {
                        view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, -1L)
                        return
                    }

                    val incoming = param.args.getOrNull(0)?.toString() ?: return
                    if (incoming.length < 1 || incoming.length > 3000) {
                        view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, -1L)
                        return
                    }

                    // Fast-path 3: O(1) exact content text map
                    val editedByText = editedTexts[incoming]
                    if (editedByText != null) {
                        if (editedByText != incoming) param.args[0] = editedByText
                        return
                    }

                    // Fast-path 4: Filter out non-message view candidates
                    if (!isMessageTextCandidate(view)) {
                        view.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, -1L)
                        return
                    }

                    // Slow-path: scan view tree ONCE and store tag permanently
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
            xlog("text rebind hook installed with fast-path tag caching")
        }.onFailure {
            xlog("text rebind hook fail: ${it.message}")
        }
    }

    private fun hookMenuItemClick(classLoader: ClassLoader) {
        // l75.h4 仅 69；优先 hook MenuItem 点击接口，再试混淆候选
        var ok = false
        runCatching {
            // 无法直接 hook OnMenuItemClickListener 接口；试混淆 MenuItem 实现 + 系统 MenuItemImpl
            for (name in listOf(
                "l75.h4", "m75.h4", "k75.h4", "n75.h4", "l65.h4", "l85.h4", "l75.i4"
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
        // 系统 MenuItemImpl.invoke
        runCatching {
            val impl = XposedHelpers.findClass(
                "com.android.internal.view.menu.MenuItemImpl",
                classLoader
            )
            val invoke = impl.declaredMethods.firstOrNull {
                it.name == "invoke" && it.parameterTypes.isEmpty()
            } ?: return@runCatching
            XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val item = param.thisObject as? MenuItem ?: return
                    if (item.itemId != EDIT_ID) return
                    val target = activeTarget?.get() ?: return
                    if (!isActive(target)) return
                    menuShown = true
                    showActiveEditDialog(target)
                    param.result = true
                }
            })
            ok = true
        }
        xlog(if (ok) "wechat menu item click hook installed" else "menu item click hook skipped (long-press path still works)")
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
                    val target = editableText(row) ?: return
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
        val packageName = context.packageName
        return listOf("raw", "drawable")
            .asSequence()
            .map { type -> resources.getIdentifier("icons_filled_edit_photo_pencil", type, packageName) }
            .firstOrNull { it != 0 }
    }

    private fun menuContext(menu: Menu, target: TextView): Context? {
        return runCatching { target.context }.getOrNull()
            ?: runCatching { menu.javaClass.getDeclaredField("mContext").apply { isAccessible = true }.get(menu) as? Context }.getOrNull()
    }

    private fun loadDexKitNative(context: Context, modulePath: String?) {
        if (dexKitLoaded.get()) return
        runCatching { System.loadLibrary("dexkit") }.onSuccess {
            dexKitLoaded.set(true)
            return
        }
        val apkPath = modulePath ?: error("module path unavailable for libdexkit.so")
        val abi = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a"
        else Build.SUPPORTED_32_BIT_ABIS.firstOrNull() ?: "armeabi-v7a"
        val out = File(context.cacheDir, "abc_${abi}_libdexkit.so")
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/libdexkit.so") ?: error("lib/$abi/libdexkit.so not found")
            zip.getInputStream(entry).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        }
        System.load(out.absolutePath)
        dexKitLoaded.set(true)
    }

    private fun descriptorToMethod(descriptor: String, classLoader: ClassLoader): Method {
        val arrow = descriptor.indexOf("->")
        val argsStart = descriptor.indexOf('(', arrow)
        require(arrow > 1 && argsStart > arrow) { descriptor }
        val className = descriptor.substring(1, arrow - 1).replace('/', '.')
        val methodName = descriptor.substring(arrow + 2, argsStart)
        val signature = descriptor.substring(argsStart)
        var clazz: Class<*>? = classLoader.loadClass(className)
        while (clazz != null) {
            clazz.declaredMethods.firstOrNull { method -> method.name == methodName && methodSignature(method) == signature }?.let {
                it.isAccessible = true
                return it
            }
            clazz = clazz.superclass
        }
        error("method not found: $descriptor")
    }

    private fun methodSignature(method: Method): String = buildString {
        append('(')
        method.parameterTypes.forEach { append(typeSignature(it)) }
        append(')')
        append(typeSignature(method.returnType))
    }

    private fun typeSignature(type: Class<*>): String {
        if (type.isPrimitive) return when (type) {
            java.lang.Integer.TYPE -> "I"
            java.lang.Void.TYPE -> "V"
            java.lang.Boolean.TYPE -> "Z"
            java.lang.Character.TYPE -> "C"
            java.lang.Byte.TYPE -> "B"
            java.lang.Short.TYPE -> "S"
            java.lang.Float.TYPE -> "F"
            java.lang.Long.TYPE -> "J"
            java.lang.Double.TYPE -> "D"
            else -> "V"
        }
        if (type.isArray) return type.name.replace('.', '/')
        return "L${type.name.replace('.', '/')};"
    }

    private fun messageRefFromView(view: View, target: TextView): MessageRef? {
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
        val name = root.javaClass.name
        if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.")) return null
        allFields(root.javaClass).forEach { field ->
            if (field.type.isPrimitive || field.type.isArray || field.type == String::class.java) return@forEach
            val value = runCatching {
                field.isAccessible = true
                field.get(root)
            }.getOrNull()
            findMessageObject(value, depth + 1, seen)?.let { return it }
        }
        return null
    }

    private fun msgIdFromViewTree(view: View): Long {
        var current: Any? = view
        repeat(10) {
            val v = current as? View ?: return 0L
            messageRefFromView(v, view as? TextView ?: activeTarget?.get() ?: return 0L)?.let { return it.msgId }
            current = v.parent
        }
        return 0L
    }

    private fun readMsgId(message: Any): Long =
        readNumber(message, "getMsgId", "getMsgID", "field_msgId", "msgId", "msgID", "id")?.toLong() ?: 0L

    private fun readMessageContent(message: Any): String? =
        readString(message, "getContent", "field_content", "content")

    private fun writeMessageContent(message: Any, content: String): Boolean {
        allMethods(message.javaClass).firstOrNull {
            it.name in setOf("setContent", "setMsgContent") &&
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
        if (content.isBlank() || content.length > 4000) return false
        if (content.trimStart().startsWith("<")) return false
        if (looksLikeMetaText(content)) return false
        return true
    }

    private fun isMessageObject(value: Any): Boolean = isMessageClass(value.javaClass)

    private fun isMessageClass(clazz: Class<*>): Boolean =
        clazz.name.startsWith("com.tencent.mm.storage.")

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
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }

    private fun allMethods(clazz: Class<*>): Sequence<Method> = sequence {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            yieldAll(current.declaredMethods.asSequence())
            current = current.superclass
        }
    }

    private fun allFields(clazz: Class<*>): Sequence<Field> = sequence {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            yieldAll(current.declaredFields.asSequence())
            current = current.superclass
        }
    }

    private fun identitySet(): MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    private fun stripSenderPrefix(content: String): String {
        val end = senderPrefixEnd(content) ?: return content
        val split = if (content.startsWith(":\r\n", end - 3)) end - 3 else end - 2
        val prefix = content.substring(0, split)
        return if (prefix.startsWith("wxid_") || prefix.matches(Regex("[a-zA-Z][\\w@.\\-]{4,80}"))) {
            content.substring(end)
        } else {
            content
        }
    }

    private fun rawContentWithEditedBody(rawContent: String, body: String): String {
        val end = senderPrefixEnd(rawContent) ?: return body
        val split = if (rawContent.startsWith(":\r\n", end - 3)) end - 3 else end - 2
        val prefix = rawContent.substring(0, split)
        return if (prefix.startsWith("wxid_") || prefix.matches(Regex("[a-zA-Z][\\w@.\\-]{4,80}"))) {
            rawContent.substring(0, end) + body
        } else {
            body
        }
    }

    private fun senderPrefixEnd(content: String): Int? {
        val rn = content.indexOf(":\r\n").takeIf { it in 1..80 }?.let { it + 3 }
        val n = content.indexOf(":\n").takeIf { it in 1..80 }?.let { it + 2 }
        return listOfNotNull(rn, n).minOrNull()
    }

    private fun editableText(view: View): TextView? {
        if (view is TextView && isMessageTextCandidate(view)) return view
        var current: View? = view
        var best: TextView? = null
        var bestScore = Int.MIN_VALUE
        repeat(8) {
            val parent = current?.parent as? View ?: return@repeat
            if (parent is android.view.ViewGroup) {
                val candidates = ArrayList<TextView>()
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

    private fun collectTextViews(view: View, out: MutableList<TextView>) {
        if (view is TextView) out += view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collectTextViews(view.getChildAt(i), out)
        }
    }

    private fun isMessageTextCandidate(tv: TextView): Boolean {
        if (tv.getTag(com.OKK.yes.core.R.id.abc_tag_custom_time) == true) return false
        if (tv.visibility != View.VISIBLE || tv.alpha <= 0f) return false
        val text = tv.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || text.length > 4000) return false
        if (text == MENU_TITLE || text.startsWith("\u270E")) return false
        if (looksLikeMetaText(text)) return false
        return true
    }

    private fun messageTextScore(tv: TextView, origin: View): Int {
        val text = tv.text?.toString()?.trim().orEmpty()
        val sp = tv.textSize / tv.resources.displayMetrics.scaledDensity
        var score = text.length.coerceAtMost(120)
        if (tv === origin) score += 90
        if (sp >= 15f) score += 45 else score -= 35
        if (text.length <= 2 && sp < 16f) score -= 30
        if (tv.width > 0 && tv.height > 0) score += ((tv.width * tv.height) / 1200).coerceAtMost(80)
        if (text.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }) score += 15
        return score
    }

    private fun looksLikeMetaText(text: String): Boolean {
        val compact = text.trim()
        if (compact.matches(Regex("""\d{1,2}:\d{2}(:\d{2})?"""))) return true
        if (compact.matches(Regex("""\d{1,2}月\d{1,2}日\s+\d{1,2}:\d{2}"""))) return true
        if (compact.matches(Regex("""\d{1,2}[-/]\d{1,2}\s+周.\s+.*"""))) return true
        if (compact.contains("分钟前") || compact.contains("小时前") || compact.contains("昨天") || compact.contains("前天")) {
            if (compact.any { it.isDigit() } && compact.length <= 40) return true
        }
        if (compact.matches(Regex(""".*\b(KB|MB|GB)\b.*""", RegexOption.IGNORE_CASE))) return true
        if (compact in setOf("\u672a\u4e0b\u8f7d", "\u8f6c\u6587\u5b57", "\u5fae\u4fe1\u7f51\u9875\u7248", "\u4e2a\u4eba\u540d\u7247")) return true
        return false
    }

    private fun isActive(target: TextView): Boolean =
        activeTarget?.get() === target && System.currentTimeMillis() - activeAt < 8_000L

    private fun isActiveMessage(ref: MessageRef?): Boolean =
        ref != null && activeMessage === ref && System.currentTimeMillis() - activeAt < 8_000L

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

    private fun showActiveEditDialog(tv: TextView) {
        val ref = activeMessage
        if (isActiveMessage(ref)) {
            showEditDialog(ref!!, tv)
            return
        }
        Toast.makeText(tv.context, "\u5f53\u524d\u6d88\u606f\u4e0d\u53ef\u4fee\u6539", Toast.LENGTH_SHORT).show()
        xlog("edit blocked: no active message ref")
    }

    private fun showEditDialog(tv: TextView) {
        if (!isEnabled()) return
        if (dialogOpen) return
        menuShown = true
        val ctx = tv.context ?: return
        val originalText = resolveOriginalText(tv)
        showStyledEditDialog(ctx, originalText, editedTexts[originalText] ?: originalText, isEdited = editedTexts.containsKey(originalText)) { newText, isReset ->
            if (isReset) {
                editedTexts.remove(originalText)
                applyEditedText(tv, originalText)
                Toast.makeText(ctx, "已还原原始消息", Toast.LENGTH_SHORT).show()
            } else {
                if (originalText.isNotBlank()) editedTexts[originalText] = newText
                applyEditedText(tv, newText)
                Toast.makeText(ctx, "已保存本地修改", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEditDialog(ref: MessageRef, tv: TextView) {
        if (!isEnabled()) return
        if (dialogOpen) return
        menuShown = true
        val ctx = tv.context ?: return
        val originalText = ref.content
        val currentText = editedByMsgId[ref.msgId] ?: originalText
        val isEdited = editedByMsgId.containsKey(ref.msgId) || editedTexts.containsKey(originalText)

        showStyledEditDialog(ctx, originalText, currentText, isEdited) { newText, isReset ->
            if (isReset) {
                editedByMsgId.remove(ref.msgId)
                if (originalText.isNotBlank()) editedTexts.remove(originalText)
                tv.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, null)
                val rawEdited = rawContentWithEditedBody(ref.rawContent, originalText)
                ref.message.get()?.let { writeMessageContent(it, rawEdited) }
                updateMessageContentInDb(ref.msgId, rawEdited)
                applyEditedVisual(ref, tv, originalText, originalText)
                tv.post { applyEditedVisual(ref, tv, originalText, originalText) }
                Toast.makeText(ctx, "已还原原始消息", Toast.LENGTH_SHORT).show()
            } else {
                editedByMsgId[ref.msgId] = newText
                if (originalText.isNotBlank()) editedTexts[originalText] = newText
                tv.setTag(com.OKK.yes.core.R.id.abc_tag_edit_msg_id, ref.msgId)
                val rawEdited = rawContentWithEditedBody(ref.rawContent, newText)
                val objectApplied = ref.message.get()?.let { writeMessageContent(it, rawEdited) } == true
                val dbRows = updateMessageContentInDb(ref.msgId, rawEdited)
                applyEditedVisual(ref, tv, originalText, newText)
                tv.post { applyEditedVisual(ref, tv, originalText, newText) }
                Toast.makeText(ctx, "已保存本地修改", Toast.LENGTH_SHORT).show()
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
            text = "修改本地消息"
            textSize = 17f
            setTextColor(primaryTxt)
            typeface = Typeface.DEFAULT_BOLD
        })

        // Subtitle / Tip
        root.addView(TextView(ctx).apply {
            text = "修改仅在当前设备生效，重新载入消息列表时将自动刷新"
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
            // Restore default button
            val restoreBtn = TextView(ctx).apply {
                text = "还原"
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
            text = "取消"
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
            text = "保存修改"
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

    private fun resolveOriginalText(tv: TextView): String {
        val active = activeOriginalText
        val current = tv.text?.toString().orEmpty()
        return if (!active.isNullOrBlank() && editedTexts[active] == current) active else current
    }

    private fun applyEditedText(tv: TextView, text: String) {
        applyingText = true
        try {
            runCatching { tv.text = text }.onFailure { tv.setText(text) }
            tv.invalidate()
            tv.requestLayout()
        } finally {
            applyingText = false
        }
    }

    private fun applyEditedVisual(ref: MessageRef, tv: TextView, originalText: String, newText: String): Int {
        val targets = java.util.LinkedHashSet<TextView>()
        targets += tv
        ref.target.get()?.let { targets += it }
        ref.view.get()?.let { row ->
            val candidates = ArrayList<TextView>()
            collectTextViews(row, candidates)
            candidates.forEach { candidate ->
                val text = candidate.text?.toString().orEmpty()
                if (candidate === tv || candidate === ref.target.get() || text == originalText || editedTexts[text] == newText) {
                    if (isMessageTextCandidate(candidate) || candidate === tv || candidate === ref.target.get()) {
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
