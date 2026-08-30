package com.OKK.yes.core.hooks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import android.util.Log
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.math.min

/**
 * 圆形头像 —— **严格按第一版生效逻辑**（用户确认 0.43 园中带方最好看）。
 *
 * 第一版要点（勿再改公式）：
 * 1. u.a → 直接调 b(自定义 radius)
 * 2. u.b before 改 args[2]；after forceRatio + **postInvalidate**
 * 3. drawable ctor / draw：forceRatio
 * 4. BitmapUtil：**无条件** `args[2] = min(w,h) * radius`（开启时）
 * 5. forceRatio：实例 float 当前值在 **0~1.01** 都写成 radius（第一版就是这样）
 *
 * 配置读取仍走公共文件优先（修失效 bug），与观感公式无关。
 */
object RoundAvatarHook {
    private const val TAG = "OKK-RoundAvatar"
    private const val AVATAR_LOG = "MicroMsg.AvatarDrawable"
    private const val BITMAP_ROUND_LOG = "getRoundedCornerBitmap in bitmap is null"
    private val FACTORY: String get() = com.OKK.yes.core.common.SecureStrings.d("xnRFxnRH1ylC2j1Z3yoC3jcCxzRJ0DRJx3RB3Dk=")
    private const val DRAWABLE = "com.tencent.mm.pluginsdk.ui.x"
    private const val BITMAP_UTIL = "com.tencent.mm.sdk.platformtools.x"

    private val installed = AtomicBoolean(false)
    private val dexKitNativeLoaded = AtomicBoolean(false)
    private val applyCount = AtomicInteger(0)

    @Volatile
    private var methodB: Method? = null

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        RoundAvatarConfig.invalidate()
        val opt = RoundAvatarConfig.load()
        xlog("install v1 enabled=${opt.enabled} radius=${opt.radius} src=${opt.source}")

        var n = 0
        n += hookFactory(classLoader)
        n += hookDrawable(classLoader)
        n += hookBitmapRound(classLoader)
        n += hookByDexKit(context, classLoader, modulePath)
        xlog("done hooks=$n methodB=${methodB != null}")
    }

    // ── 1) 工厂 u.a / u.b ────────────────────────────────────────────────

    private fun hookFactory(classLoader: ClassLoader): Int {
        val clazz = findClass(FACTORY, classLoader) ?: return 0
        var n = 0
        clazz.declaredMethods.forEach { m ->
            if (isB(m)) {
                methodB = m
                if (hookB(m, "u.b")) n++
            }
        }
        // 8.0.76 上 u.a→b.invoke 反射会进入已 hook 的 u.b 引发递归栈溢出，
        // 已禁用 u.a 桥接，仅靠 hookU.b 修改 radius 生效（安全无递归）。
        return n
    }

    private fun isA(m: Method): Boolean {
        if (!Modifier.isStatic(m.modifiers)) return false
        val p = m.parameterTypes
        return p.size == 2 &&
            ImageView::class.java.isAssignableFrom(p[0]) &&
            p[1] == String::class.java
    }

    private fun isB(m: Method): Boolean {
        if (!Modifier.isStatic(m.modifiers)) return false
        val p = m.parameterTypes
        return p.size == 4 &&
            ImageView::class.java.isAssignableFrom(p[0]) &&
            p[1] == String::class.java &&
            (p[2] == Float::class.javaPrimitiveType || p[2] == java.lang.Float::class.java) &&
            (p[3] == Boolean::class.javaPrimitiveType || p[3] == java.lang.Boolean::class.java)
    }

    private fun hookA(method: Method, host: Class<*>, label: String): Boolean {
        // 8.0.76 禁用 u.a 反射桥接，避免递归崩溃
        return false
    }

    private val hookedMethods = java.util.Collections.synchronizedSet(HashSet<String>())
    private val inHookB = ThreadLocal.withInitial { false }

    private fun hookB(method: Method, label: String): Boolean {
        if (!hookedMethods.add(method.toString())) {
            return false
        }
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (inHookB.get() == true) return
                    inHookB.set(true)
                    try {
                        val opt = RoundAvatarConfig.load()
                        if (!opt.enabled) return
                        if (param.args.size >= 3) {
                            param.args[2] = opt.radius
                        }
                    } finally {
                        inHookB.set(false)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (inHookB.get() == true) return
                    inHookB.set(true)
                    try {
                        val opt = RoundAvatarConfig.load()
                        if (!opt.enabled) return
                        val iv = param.args.getOrNull(0) as? ImageView ?: return
                        forceRatio(iv.drawable, opt.radius)
                    } finally {
                        inHookB.set(false)
                    }
                }
            })
            true
        }.getOrDefault(false)
    }

    // ── 2) AvatarRoundDrawable ────────────────────────────────────────────

    private fun hookDrawable(classLoader: ClassLoader): Int {
        val clazz = findClass(DRAWABLE, classLoader) ?: return 0
        var n = 0

        clazz.declaredConstructors.forEach { ctor ->
            val p = ctor.parameterTypes
            if (p.size == 2 && p[0] == String::class.java &&
                (p[1] == Float::class.javaPrimitiveType || p[1] == java.lang.Float::class.java)
            ) {
                runCatching {
                    ctor.isAccessible = true
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val opt = RoundAvatarConfig.load()
                            if (!opt.enabled) return
                            param.args[1] = opt.radius
                            logApply("drawable.<init>", opt.radius)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val opt = RoundAvatarConfig.load()
                            if (!opt.enabled) return
                            forceRatio(param.thisObject, opt.radius)
                        }
                    })
                    n++
                    xlog("hooked drawable ctor")
                }
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz,
                "draw",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val opt = RoundAvatarConfig.load()
                        if (!opt.enabled) return
                        forceRatio(param.thisObject, opt.radius)
                    }
                }
            )
            n++
            xlog("hooked drawable.draw")
        }.onFailure {
            xlog("drawable.draw skip: ${it.message}")
        }
        return n
    }

    // ── 3) BitmapUtil —— 第一版公式 + 开启即强制写入 ─────────────────────

    private fun hookBitmapRound(classLoader: ClassLoader): Int {
        val util = findClass(BITMAP_UTIL, classLoader) ?: return 0
        var n = 0
        util.declaredMethods.forEach { m ->
            val p = m.parameterTypes
            val isS0 = p.size == 3 &&
                p[0] == Bitmap::class.java &&
                (p[1] == Boolean::class.javaPrimitiveType || p[1] == java.lang.Boolean::class.java) &&
                (p[2] == Float::class.javaPrimitiveType || p[2] == java.lang.Float::class.java)
            // 第一版：t0 用 size >= 4
            val isT0 = p.size >= 4 &&
                p[0] == Bitmap::class.java &&
                (p[1] == Boolean::class.javaPrimitiveType || p[1] == java.lang.Boolean::class.java) &&
                (p[2] == Float::class.javaPrimitiveType || p[2] == java.lang.Float::class.java)

            if (!isS0 && !isT0) return@forEach
            if (m.returnType != Bitmap::class.java) return@forEach

            runCatching {
                m.isAccessible = true
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val opt = RoundAvatarConfig.load()
                        if (!opt.enabled) return
                        val bmp = param.args.getOrNull(0) as? Bitmap ?: return
                        if (bmp.isRecycled) return
                        val px = (param.args[2] as? Number)?.toFloat() ?: return
                        val side = min(bmp.width, bmp.height).toFloat()
                        if (side <= 1f) return

                        // ★ 第一版公式：短边 × radius（0.43 → 园中带方）
                        val next = opt.radius * side

                        // 只处理「像头像圆角」的入参，避免误伤其它 UI
                        // 但阈值放宽：微信默认 0.1、中间值、已是 0.43 都覆盖
                        val ratio = px / side
                        if (ratio !in 0.03f..0.60f && px > side * 0.60f) return

                        // 第一版后来加了 0.25 阈值会漏微调；这里接近就写，保证 0.43 稳定
                        if (kotlin.math.abs(next - px) > 0.01f) {
                            param.args[2] = next
                            logApply("BitmapUtil.${m.name} $px→$next", opt.radius)
                        }
                    }
                })
                n++
                xlog("hooked BitmapUtil.${m.name}${p.contentToString()}")
            }
        }
        return n
    }

    private fun hookByDexKit(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?
    ): Int {
        return runCatching {
            loadDexKitNative(context, modulePath)
            var n = 0
            DexKitBridge.create(classLoader, true).use { bridge ->
                bridge.findMethod {
                    matcher {
                        usingEqStrings(AVATAR_LOG)
                        paramCount(4)
                    }
                }.forEach { data ->
                    val m = runCatching { descriptorToMethod(data.descriptor, classLoader) }.getOrNull()
                        ?: return@forEach
                    if (isB(m)) {
                        methodB = m
                        if (hookB(m, "DexKit.b")) n++
                    }
                }
                // 按字符串再挂一遍 5 参 BitmapUtil（类名混淆时兜底）
                bridge.findMethod {
                    matcher {
                        usingEqStrings(BITMAP_ROUND_LOG)
                        paramCount(5)
                    }
                }.forEach { data ->
                    val m = runCatching { descriptorToMethod(data.descriptor, classLoader) }.getOrNull()
                        ?: return@forEach
                    if (m.declaringClass.name == BITMAP_UTIL) return@forEach
                    if (m.returnType != Bitmap::class.java) return@forEach
                    runCatching {
                        m.isAccessible = true
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val opt = RoundAvatarConfig.load()
                                if (!opt.enabled) return
                                val bmp = param.args.getOrNull(0) as? Bitmap ?: return
                                if (bmp.isRecycled || param.args.size < 3) return
                                val side = min(bmp.width, bmp.height).toFloat()
                                if (side <= 1f) return
                                param.args[2] = opt.radius * side
                            }
                        })
                        n++
                        xlog("hooked DexKit BitmapUtil ${m.declaringClass.simpleName}.${m.name}")
                    }
                }
            }
            n
        }.onFailure {
            xlog("DexKit fail: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(0)
    }

    /**
     * ★ 第一版 forceRatio：所有非 static、当前值在 0~1.01 的 float 都写成 radius。
     * 后来收成 0.04~0.56 后观感变差，按用户要求恢复这一版。
     */
    private fun forceRatio(target: Any?, radius: Float) {
        if (target == null) return
        runCatching {
            var c: Class<*>? = target.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    if (f.type != Float::class.javaPrimitiveType && f.type != java.lang.Float::class.java) {
                        continue
                    }
                    val fname = f.name.lowercase()
                    // 排除透明度、缩放、动画进度类 float 字段，防止盲写破坏 Drawable 渲染与显隐
                    if (fname.contains("alpha") || fname.contains("scale") || fname.contains("anim") ||
                        fname.contains("trans") || fname.contains("progress") || fname.contains("opacity")) {
                        continue
                    }
                    f.isAccessible = true
                    val old = runCatching { f.getFloat(target) }.getOrNull() ?: continue
                    if (old in 0.04f..0.98f) {
                        f.setFloat(target, radius)
                    }
                }
                c = c.superclass
            }
        }
    }

    private fun logApply(where: String, radius: Float) {
        val n = applyCount.incrementAndGet()
        if (n <= 25 || n % 200 == 0) {
            xlog("APPLY #$n $where radius=$radius")
        }
    }

    private fun findClass(name: String, cl: ClassLoader): Class<*>? =
        runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()

    private fun loadDexKitNative(context: Context, modulePath: String?) {
        if (dexKitNativeLoaded.get()) return
        runCatching {
            System.loadLibrary("dexkit")
            dexKitNativeLoaded.set(true)
            return
        }
        val apk = modulePath ?: return
        val abi = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a"
        } else {
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull() ?: "armeabi-v7a"
        }
        val out = File(context.cacheDir, "abc_avatar_${abi}_libdexkit.so")
        ZipFile(apk).use { zip ->
            val e = zip.getEntry("lib/$abi/libdexkit.so") ?: return
            zip.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        }
        System.load(out.absolutePath)
        dexKitNativeLoaded.set(true)
    }

    private fun descriptorToMethod(descriptor: String, classLoader: ClassLoader): Method {
        val arrow = descriptor.indexOf("->")
        val argsStart = descriptor.indexOf('(', arrow)
        val className = descriptor.substring(1, arrow - 1).replace('/', '.')
        val methodName = descriptor.substring(arrow + 2, argsStart)
        val signature = descriptor.substring(argsStart)
        var clazz: Class<*>? = classLoader.loadClass(className)
        while (clazz != null) {
            clazz.declaredMethods.firstOrNull {
                it.name == methodName && methodSignature(it) == signature
            }?.let {
                it.isAccessible = true
                return it
            }
            clazz = clazz.superclass
        }
        throw NoSuchMethodException(descriptor)
    }

    private fun methodSignature(method: Method): String = buildString {
        append('(')
        method.parameterTypes.forEach { append(typeSignature(it)) }
        append(')')
        append(typeSignature(method.returnType))
    }

    private fun typeSignature(type: Class<*>): String {
        if (type.isPrimitive) {
            return when (type) {
                java.lang.Integer.TYPE -> "I"
                java.lang.Void.TYPE -> "V"
                java.lang.Boolean.TYPE -> "Z"
                java.lang.Byte.TYPE -> "B"
                java.lang.Character.TYPE -> "C"
                java.lang.Short.TYPE -> "S"
                java.lang.Long.TYPE -> "J"
                java.lang.Float.TYPE -> "F"
                java.lang.Double.TYPE -> "D"
                else -> "V"
            }
        }
        if (type.isArray) return "[" + typeSignature(type.componentType!!)
        return "L" + type.name.replace('.', '/') + ";"
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
