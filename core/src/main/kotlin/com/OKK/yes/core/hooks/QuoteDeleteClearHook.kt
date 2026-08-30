package com.OKK.yes.core.hooks

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * 删除键清引用（输入为空时删除键取消引用）。
 *
 * 输入框为空时按删除 → 取消引用条，不是删消息。
 *
 * 关键因修复：
 * - 清引用方法必须是 ChatFooter 上处理引用 UI 的 (boolean,boolean)->void
 *   （当前微信反编译为 n1；错误选到 a0/u0 会导致按删除无效果）
 * - IME 包装始终安装，运行时读开关
 * - 空内容优先 getLastText()
 */
object QuoteDeleteClearHook {
    private const val TAG = "OKK-QuoteDelClear"
    private const val FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
    private const val KEY_ENABLE = "quote_delete_clear"

    private val installed = AtomicBoolean(false)
    private val imeHooked = AtomicBoolean(false)
    private val onKeyHooked = AtomicBoolean(false)
    private val dexKitNativeLoaded = AtomicBoolean(false)
    private val dexKitTried = AtomicBoolean(false)

    @Volatile
    private var chatFooterRef: WeakReference<Any>? = null

    @Volatile
    private var clearQuoteMethod: Method? = null

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install enabled=${isEnabled()}")
        // 先 DexKit（usingStrings handleQuoteMsgFillingFrom 精确定位真正的清引用方法），
        // 再反射回退：避免旧逻辑里反射评分先选错、导致 DexKit 因 clearQuoteMethod!=null 短路。
        resolveClearMethodWithDexKit(context, classLoader, modulePath)
        hookChatFooter(classLoader)
        // 始终装 IME；开关在回调里判断（避免启动时关、后来开却没钩）
        hookImeDelete()
        hookOnKeyWithDexKit(context, classLoader, modulePath)
    }

    fun isEnabled(): Boolean {
        return runCatching {
            PublicConfigStore.getBoolean(KEY_ENABLE, false)
        }.getOrDefault(false)
    }

    fun clearCurrentQuote(reason: String = "external"): Boolean {
        return tryClearQuote(reason, currentFooter())
    }

    private fun hookChatFooter(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(FOOTER, classLoader)
            resolveClearMethod(clazz)

            runCatching {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "onAttachedToWindow",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            chatFooterRef = WeakReference(param.thisObject)
                            if (clearQuoteMethod == null) {
                                resolveClearMethod(param.thisObject.javaClass)
                            }
                        }
                    }
                )
            }
            runCatching {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "onDetachedFromWindow",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val cur = chatFooterRef?.get()
                            if (cur === param.thisObject) {
                                chatFooterRef = WeakReference(null)
                            }
                        }
                    }
                )
            }

            clazz.declaredConstructors.forEach { ctor ->
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        chatFooterRef = WeakReference(param.thisObject)
                        if (clearQuoteMethod == null) {
                            resolveClearMethod(param.thisObject.javaClass)
                        }
                    }
                })
            }
            xlog("ChatFooter hooked clearMethod=${clearQuoteMethod?.name}")
        }.onFailure {
            xlog("ChatFooter hook failed: ${it.message}")
        }
    }

    /**
     * DexKit：void (boolean,boolean) + handleQuoteMsgFillingFrom
     * （特征串定位，对应 n1 而非 a0/u0）
     */
    private fun resolveClearMethodWithDexKit(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?
    ) {
        if (clearQuoteMethod != null) return
        if (!dexKitTried.compareAndSet(false, true)) return
        runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                val primary = bridge.findMethod {
                    matcher {
                        declaredClass = FOOTER
                        returnType = "void"
                        paramTypes("boolean", "boolean")
                        usingStrings("handleQuoteMsgFillingFrom")
                    }
                }
                for (dm in primary) {
                    val m = runCatching { descriptorToMethod(dm.descriptor, classLoader) }.getOrNull()
                        ?: continue
                    if (isClearQuoteCandidate(m)) {
                        m.isAccessible = true
                        clearQuoteMethod = m
                        xlog("DexKit clearQuote: ${m.declaringClass.name}#${m.name}")
                        return
                    }
                }
                val alt = bridge.findMethod {
                    matcher {
                        declaredClass = FOOTER
                        returnType = "void"
                        paramTypes("boolean", "boolean")
                        usingStrings("openim_card_type_name", "err_not_started")
                    }
                }
                for (dm in alt) {
                    val m = runCatching { descriptorToMethod(dm.descriptor, classLoader) }.getOrNull()
                        ?: continue
                    if (isClearQuoteCandidate(m)) {
                        m.isAccessible = true
                        clearQuoteMethod = m
                        xlog("DexKit clearQuote alt: ${m.name}")
                        return
                    }
                }
            }
            xlog("DexKit clearQuote not found, keep reflection=${clearQuoteMethod?.name}")
        }.onFailure {
            xlog("DexKit clearQuote fail: ${it.message}")
        }
    }

    private fun resolveClearMethod(clazz: Class<*>) {
        if (clearQuoteMethod != null) return
        val candidates = mutableListOf<Method>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (!isClearQuoteCandidate(m)) continue
                candidates.add(m)
            }
            c = c.superclass
        }
        if (candidates.isEmpty()) return

        val best = candidates.maxWithOrNull(
            compareBy<Method> { scoreClearMethod(it) }
                .thenByDescending { it.name.length }
                .thenBy { it.name }
        )
        if (best != null) {
            best.isAccessible = true
            clearQuoteMethod = best
            xlog(
                "resolved clearQuote by score: ${best.name} " +
                    "from=${candidates.joinToString { it.name }}"
            )
        }
    }

    private fun isClearQuoteCandidate(m: Method): Boolean {
        if (Modifier.isStatic(m.modifiers)) return false
        if (m.returnType != Void.TYPE && m.returnType != Void::class.java) return false
        val p = m.parameterTypes
        if (p.size != 2) return false
        if (!isBool(p[0]) || !isBool(p[1])) return false
        val decl = m.declaringClass.name
        if (decl != FOOTER && !decl.startsWith("com.tencent.mm.pluginsdk.ui.chat.")) return false
        val n = m.name
        if (n.startsWith("set") && n.length > 6) return false
        return true
    }

    /**
     * 当前微信：n1 是清引用；a0 / u0 是其它 bool 开关，误选会导致删除无效果。
     */
    private fun scoreClearMethod(m: Method): Int {
        var s = 0
        if (m.declaringClass.name == FOOTER) s += 50
        when (m.name.length) {
            1 -> s -= 30
            2 -> s += 5
            else -> s += 10
        }
        when (m.name) {
            "a0", "u0" -> s -= 40
            "n1" -> s += 80
        }
        return s
    }

    private fun isBool(t: Class<*>): Boolean {
        return t == Boolean::class.javaPrimitiveType || t == Boolean::class.javaObjectType
    }

    private fun hookImeDelete() {
        if (!imeHooked.compareAndSet(false, true)) return
        runCatching {
            val method = TextView::class.java.getDeclaredMethod(
                "onCreateInputConnection",
                EditorInfo::class.java
            )
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val view = param.thisObject as? View ?: return
                    if (!isInputLike(view)) return
                    if (!isUnderChatFooter(view)) return
                    val ic = param.result as? InputConnection ?: return
                    val footerWeak = WeakReference(findFooterNear(view))
                    param.result = object : InputConnectionWrapper(ic, true) {
                        override fun deleteSurroundingText(
                            beforeLength: Int,
                            afterLength: Int
                        ): Boolean {
                            if (beforeLength > 0 && afterLength == 0 &&
                                shouldClear(view, footerWeak.get())
                            ) {
                                if (tryClearQuote("ime.delete", footerWeak.get())) return true
                            }
                            return super.deleteSurroundingText(beforeLength, afterLength)
                        }

                        override fun deleteSurroundingTextInCodePoints(
                            beforeLength: Int,
                            afterLength: Int
                        ): Boolean {
                            if (beforeLength > 0 && afterLength == 0 &&
                                shouldClear(view, footerWeak.get())
                            ) {
                                if (tryClearQuote("ime.deleteCp", footerWeak.get())) return true
                            }
                            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                        }

                        override fun sendKeyEvent(event: KeyEvent): Boolean {
                            if (event.action == KeyEvent.ACTION_DOWN &&
                                event.keyCode == KeyEvent.KEYCODE_DEL &&
                                shouldClear(view, footerWeak.get())
                            ) {
                                if (tryClearQuote("ime.key", footerWeak.get())) return true
                            }
                            return super.sendKeyEvent(event)
                        }
                    }
                }
            })
            xlog("IME delete hook installed (always; gate at runtime)")
        }.onFailure {
            xlog("IME hook failed: ${it.message}")
        }
    }

    private fun hookOnKeyWithDexKit(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?
    ) {
        if (!onKeyHooked.compareAndSet(false, true)) return
        runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                val methods = bridge.findMethod {
                    matcher {
                        name = "onKey"
                        returnType = "boolean"
                        paramCount = 3
                        usingStrings("ChatFooterKtHelper", "supportAutoComplete err")
                    }
                }
                var hooked = 0
                for (dm in methods) {
                    val m = runCatching { descriptorToMethod(dm.descriptor, classLoader) }.getOrNull()
                        ?: continue
                    val p = m.parameterTypes
                    if (p.size != 3) continue
                    if (!View::class.java.isAssignableFrom(p[0])) continue
                    if (p[2] != KeyEvent::class.java) continue
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            val event = param.args.getOrNull(2) as? KeyEvent ?: return
                            if (event.action != KeyEvent.ACTION_DOWN) return
                            if (event.keyCode != KeyEvent.KEYCODE_DEL) return
                            val view = param.args.getOrNull(0) as? View
                            val footer = findFooterNear(view) ?: currentFooter() ?: return
                            if (!isInputEmpty(view, footer)) return
                            if (tryClearQuote("onKey", footer)) {
                                param.result = true
                            }
                        }
                    })
                    hooked++
                    xlog("onKey hooked: ${m.declaringClass.name}#${m.name}")
                }
                if (hooked == 0) xlog("onKey DexKit: no method")
            }
        }.onFailure {
            xlog("onKey DexKit fail: ${it.message}")
        }
    }

    private fun isInputLike(view: View): Boolean {
        if (view is EditText) return true
        val name = view.javaClass.name
        return name.contains("EditText", ignoreCase = true) ||
            name.contains("MMEdit", ignoreCase = true)
    }

    private fun isUnderChatFooter(view: View): Boolean {
        var v: Any? = view
        var depth = 0
        while (v is View && depth < 20) {
            if (isChatFooterInstance(v)) return true
            v = v.parent
            depth++
        }
        return false
    }

    private fun isChatFooterInstance(obj: Any?): Boolean {
        if (obj == null) return false
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            if (c.name == FOOTER) return true
            c = c.superclass
        }
        return false
    }

    private fun findFooterNear(view: View?): Any? {
        if (view == null) return currentFooter()
        var v: Any? = view
        var depth = 0
        while (v is View && depth < 20) {
            if (isChatFooterInstance(v)) return v
            v = v.parent
            depth++
        }
        return currentFooter()
    }

    private fun currentFooter(): Any? {
        val f = chatFooterRef?.get()
        if (f is View && f.isAttachedToWindow && isChatFooterInstance(f)) return f
        if (f != null && isChatFooterInstance(f)) return f
        return null
    }

    private fun shouldClear(view: View?, footer: Any?): Boolean {
        if (!isEnabled()) return false
        return isInputEmpty(view, footer)
    }

    private fun isInputEmpty(view: View?, footer: Any?): Boolean {
        if (view is TextView) {
            val t = view.text?.toString()
            if (!t.isNullOrEmpty()) return false
        }
        val f = footer ?: currentFooter()
        if (f != null) {
            val last = runCatching {
                XposedHelpers.callMethod(f, "getLastText") as? CharSequence
            }.getOrNull()?.toString()
            if (!last.isNullOrEmpty()) return false
        }
        if (view is TextView) return view.text.isNullOrEmpty()
        return f != null
    }

    private fun tryClearQuote(reason: String, footerHint: Any?): Boolean {
        val footer = footerHint ?: currentFooter() ?: return false
        var method = clearQuoteMethod
        if (method == null) {
            resolveClearMethod(footer.javaClass)
            method = clearQuoteMethod
        }
        if (method == null) {
            xlog("clear quote: no method ($reason)")
            return false
        }
        return runCatching {
            method.isAccessible = true
            val argSets = arrayOf(
                booleanArrayOf(false, true),
                booleanArrayOf(true, true),
                booleanArrayOf(false, false)
            )
            var lastError: Throwable? = null
            for (args in argSets) {
                val ok = runCatching {
                    method.invoke(footer, args[0], args[1])
                    true
                }.getOrElse { e ->
                    lastError = e
                    false
                }
                if (ok) {
                    refreshFooterTree(footer)
                    xlog("clear quote ok via $reason method=${method.name} args=${args[0]},${args[1]}")
                    return true
                }
            }
            throw lastError ?: IllegalStateException("clear quote invoke failed")
        }.getOrElse {
            xlog("clear quote fail: ${it.message}")
            false
        }
    }

    private fun refreshFooterTree(footer: Any) {
        if (footer !is View) return
        var v: View? = footer
        var i = 0
        while (v != null && i < 5) {
            v.requestLayout()
            v.invalidate()
            v = v.parent as? View
            i++
        }
    }

    private fun loadDexKitNative(context: Context, modulePath: String?) {
        if (dexKitNativeLoaded.get()) return
        runCatching {
            System.loadLibrary("dexkit")
        }.onSuccess {
            dexKitNativeLoaded.set(true)
            return
        }
        val apkPath = modulePath ?: return
        val abi = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a"
        } else {
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull() ?: "armeabi-v7a"
        }
        val out = File(context.cacheDir, "abc_${abi}_libdexkit.so")
        runCatching {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("lib/$abi/libdexkit.so") ?: return
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            System.load(out.absolutePath)
            dexKitNativeLoaded.set(true)
        }.onFailure {
            xlog("load dexkit native fail: ${it.message}")
        }
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
            clazz.declaredMethods.firstOrNull { method ->
                method.name == methodName && methodSignature(method) == signature
            }?.let {
                it.isAccessible = true
                return it
            }
            clazz = clazz.superclass
        }
        throw NoSuchMethodException(descriptor)
    }

    private fun methodSignature(method: Method): String {
        val sb = StringBuilder("(")
        for (p in method.parameterTypes) {
            sb.append(typeDescriptor(p))
        }
        sb.append(')').append(typeDescriptor(method.returnType))
        return sb.toString()
    }

    private fun typeDescriptor(type: Class<*>): String {
        if (type.isPrimitive) {
            return when (type) {
                Boolean::class.javaPrimitiveType -> "Z"
                Byte::class.javaPrimitiveType -> "B"
                Char::class.javaPrimitiveType -> "C"
                Short::class.javaPrimitiveType -> "S"
                Int::class.javaPrimitiveType -> "I"
                Long::class.javaPrimitiveType -> "J"
                Float::class.javaPrimitiveType -> "F"
                Double::class.javaPrimitiveType -> "D"
                Void.TYPE -> "V"
                else -> "V"
            }
        }
        if (type.isArray) return "[" + typeDescriptor(type.componentType!!)
        return "L" + type.name.replace('.', '/') + ";"
    }

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
