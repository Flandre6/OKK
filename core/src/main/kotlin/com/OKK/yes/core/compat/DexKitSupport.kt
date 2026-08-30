package com.OKK.yes.core.compat

import android.content.Context
import android.os.Build
import android.util.Log
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * 共享 DexKit native 加载，避免各 Hook 重复解压 so。
 */
object DexKitSupport {
    @Volatile var classLoader: ClassLoader? = null
    @Volatile var appContext: Context? = null
    @Volatile var modulePath: String? = null

    private const val TAG = "OKK-DexKit"
    private val nativeLoaded = AtomicBoolean(false)

    fun ensureNative(context: Context, modulePath: String?): Boolean {
        if (nativeLoaded.get()) return true
        return runCatching {
            loadNative(context, modulePath)
            nativeLoaded.set(true)
            true
        }.getOrElse {
            xlog("native load fail: ${it.message}")
            false
        }
    }

    fun <T> withBridge(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?,
        block: (DexKitBridge) -> T
    ): T? {
        if (!ensureNative(context, modulePath)) return null
        return runCatching {
            DexKitBridge.create(classLoader, true).use(block)
        }.onFailure {
            xlog("bridge fail: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /**
     * 供 loader 等未直接依赖 DexKit API 的模块调用（不暴露 Bridge 类型）。
     */
    fun findClassByStrings(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?,
        vararg strings: String
    ): Class<*>? {
        val cacheKey = "cls:" + strings.joinToString("|")
        DexKitCache.get(context, cacheKey)?.let { cachedName ->
            if (cachedName == "NULL") return null
            resolveClassName(cachedName, classLoader)?.let { return it }
        }
        val result = withBridge(context, classLoader, modulePath) { bridge ->
            findClassByUsingStrings(bridge, classLoader, *strings)
        }
        DexKitCache.put(context, cacheKey, result?.name ?: "NULL")
        return result
    }

    /**
     * 返回所有匹配字符串的类（不去重、不取 first）。
     * 用于锚点字符串被多个无关类引用时的候选过滤（例如 xlog tag 字符串可能同时出现在真正目标类与 Activity 中）。
     */
    fun findClassByStringsAll(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?,
        vararg strings: String
    ): List<Class<*>> {
        val cacheKey = "clsAll:" + strings.joinToString("|")
        DexKitCache.getList(context, cacheKey)?.let { cachedNames ->
            return cachedNames.mapNotNull { resolveClassName(it, classLoader) }
        }
        val result = withBridge(context, classLoader, modulePath) { bridge ->
            runCatching {
                bridge.findClass {
                    matcher {
                        usingStrings(*strings)
                    }
                }.mapNotNull { it.name }.mapNotNull { resolveClassName(it, classLoader) }
            }.getOrDefault(emptyList())
        }.orEmpty()
        DexKitCache.putList(context, cacheKey, result.map { it.name })
        return result
    }

    fun findClassByUsingStrings(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        vararg strings: String
    ): Class<*>? {
        // 1) findClass
        val byClass = runCatching {
            bridge.findClass {
                matcher {
                    usingStrings(*strings)
                }
            }.firstOrNull()?.name
        }.getOrNull()
        resolveClassName(byClass, classLoader)?.let { return it }

        // 2) findMethod → 声明类（部分版本 findClass 对日志串不敏感）
        val byMethod = runCatching {
            bridge.findMethod {
                matcher {
                    usingStrings(*strings)
                }
            }.firstOrNull()?.className
        }.getOrNull()
        return resolveClassName(byMethod, classLoader)
    }

    /**
     * 类级字符串锚点定位方法：先 findClass(usingStrings) 找到声明类，再按
     * 参数/返回类型签名过滤反射方法。对应 WeKit dexMethod DSL 的
     * declaredClass { usingStrings(...) } + paramTypes/returnType 语义。
     * 用于 vfs downloadFile 等“类内使用特征串、方法本身不含特征串”的场景。
     * paramTypes: java 风格类名列表（如 "long", "java.lang.String"），null 表示不约束。
     */
    fun findMethodInClassByStrings(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?,
        strings: Array<String>,
        paramTypes: Array<String>? = null,
        returnType: String? = null
    ): java.lang.reflect.Method? {
        val clazz = findClassByStrings(context, classLoader, modulePath, *strings) ?: return null
        return findMethodBySignature(clazz, paramTypes, returnType)
    }

    /**
     * 在已定位的类中按签名找方法（public/private 均可）。
     * paramTypes 为空数组/null 时不约束参数；returnType 为 null 时不约束返回。
     */
    fun findMethodBySignature(
        clazz: Class<*>,
        paramTypes: Array<String>? = null,
        returnType: String? = null
    ): java.lang.reflect.Method? {
        return runCatching {
            clazz.declaredMethods.firstOrNull { m ->
                val paramsOk = paramTypes == null ||
                    (m.parameterTypes.size == paramTypes.size &&
                        m.parameterTypes.indices.all { i ->
                            typeNameMatches(m.parameterTypes[i], paramTypes[i])
                        })
                val retOk = returnType == null || typeNameMatches(m.returnType, returnType)
                paramsOk && retOk
            }
        }.getOrNull()
    }

    private fun typeNameMatches(actual: Class<*>, expected: String): Boolean {
        val actualName = when {
            actual == Int::class.javaPrimitiveType -> "int"
            actual == Long::class.javaPrimitiveType -> "long"
            actual == Boolean::class.javaPrimitiveType -> "boolean"
            actual == Float::class.javaPrimitiveType -> "float"
            actual == Double::class.javaPrimitiveType -> "double"
            actual == Byte::class.javaPrimitiveType -> "byte"
            actual == Short::class.javaPrimitiveType -> "short"
            actual == Char::class.javaPrimitiveType -> "char"
            else -> actual.name
        }
        return actualName == expected ||
            actualName == expected.replace("java.lang.String", "kotlin.String") ||
            actualName == "kotlin.String" && expected == "java.lang.String"
    }

    /**
     * 按日志/特征字符串定位具体方法（不是类）。用于方法名在不同构建批次中混淆为不同字母的场景（如 k/l 互换）。
     * 返回第一个匹配结果；调用方需自行校验声明类/参数符合预期。
     */
    fun findMethodByStrings(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?,
        vararg strings: String
    ): java.lang.reflect.Method? {
        val cacheKey = "mth:" + strings.joinToString("|")
        DexKitCache.get(context, cacheKey)?.let { cached ->
            if (cached == "NULL") return null
            resolveMethod(cached, classLoader)?.let { return it }
        }
        val result = withBridge(context, classLoader, modulePath) { bridge ->
            runCatching {
                bridge.findMethod {
                    matcher {
                        usingStrings(*strings)
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)
            }.getOrNull()
        }
        DexKitCache.put(context, cacheKey, encodeMethod(result) ?: "NULL")
        return result
    }

    /**
     * 按日志/特征字符串定位具体方法并返回多个候选（用于锚点字符串被多个类引用时）。
     * 调用方需自行过滤声明类/参数符合预期的候选。
     */
    fun findMethodsByStrings(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?,
        vararg strings: String
    ): List<java.lang.reflect.Method> {
        val cacheKey = "mths:" + strings.joinToString("|")
        DexKitCache.getList(context, cacheKey)?.let { cached ->
            return cached.mapNotNull { resolveMethod(it, classLoader) }
        }
        val result = withBridge(context, classLoader, modulePath) { bridge ->
            runCatching {
                bridge.findMethod {
                    matcher {
                        usingStrings(*strings)
                    }
                }.mapNotNull { it.getMethodInstance(classLoader) }
            }.getOrDefault(emptyList())
        }.orEmpty()
        DexKitCache.putList(context, cacheKey, result.mapNotNull { encodeMethod(it) })
        return result
    }

    /**
     * 查找所有实现指定接口的类（用于定位同一接口的代理单例实现，如 fe4.f0 之于 fe4.b）。
     * interfaceClassName 为 java 风格全限定名（如 com.xxx.Yyy），运行时可从实现类 getInterfaces() 取到混淆名。
     */
    fun findClassImplementing(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?,
        interfaceClassName: String
    ): List<Class<*>> {
        val cacheKey = "impl:$interfaceClassName"
        DexKitCache.getList(context, cacheKey)?.let { cachedNames ->
            return cachedNames.mapNotNull { resolveClassName(it, classLoader) }
        }
        val desc = "L${interfaceClassName.replace('.', '/')};"
        val result = withBridge(context, classLoader, modulePath) { bridge ->
            runCatching {
                bridge.findClass {
                    matcher {
                        addInterface(desc)
                    }
                }.mapNotNull { resolveClassName(it.name, classLoader) }
            }.getOrDefault(emptyList())
        }.orEmpty()
        DexKitCache.putList(context, cacheKey, result.map { it.name })
        return result
    }

    private fun encodeMethod(m: java.lang.reflect.Method?): String? {
        if (m == null) return null
        val params = m.parameterTypes.joinToString(",") { it.name }
        return "${m.declaringClass.name}#${m.name}($params)"
    }

    private fun resolveMethod(encoded: String, classLoader: ClassLoader): java.lang.reflect.Method? {
        return runCatching {
            val parts = encoded.split("#", limit = 2)
            if (parts.size != 2) return null
            val className = parts[0]
            val methodParts = parts[1].split("(", ")")
            val methodName = methodParts[0]
            val paramStr = methodParts[1]
            val paramTypeNames = if (paramStr.isEmpty()) emptyList() else paramStr.split(",")
            val clazz = Class.forName(className, false, classLoader)
            val paramTypes = paramTypeNames.map { name ->
                when (name) {
                    "int" -> Int::class.javaPrimitiveType
                    "long" -> Long::class.javaPrimitiveType
                    "boolean" -> Boolean::class.javaPrimitiveType
                    "float" -> Float::class.javaPrimitiveType
                    "double" -> Double::class.javaPrimitiveType
                    "byte" -> Byte::class.javaPrimitiveType
                    "short" -> Short::class.javaPrimitiveType
                    "char" -> Char::class.javaPrimitiveType
                    else -> Class.forName(name, false, classLoader)
                }
            }.toTypedArray()
            val m = clazz.getDeclaredMethod(methodName, *paramTypes)
            m.isAccessible = true
            m
        }.getOrNull()
    }

    private fun resolveClassName(raw: String?, classLoader: ClassLoader): Class<*>? {
        if (raw.isNullOrBlank()) return null
        val name = raw.removePrefix("L").removeSuffix(";").replace('/', '.')
        return runCatching { Class.forName(name, false, classLoader) }.getOrNull()
    }

    private fun loadNative(context: Context, modulePath: String?) {
        runCatching {
            System.loadLibrary("dexkit")
            xlog("loaded via library path")
            return
        }
        val apk = modulePath?.takeIf { it.isNotBlank() && File(it).isFile }
            ?: context.applicationInfo.sourceDir
        val abi = preferredAbi()
        val dir = File(context.cacheDir, "achat_dexkit").apply { mkdirs() }
        val so = File(dir, "libdexkit.so")
        if (!so.isFile || so.length() == 0L) {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/$abi/libdexkit.so")
                    ?: zip.entries().asSequence().firstOrNull {
                        it.name.endsWith("libdexkit.so")
                    }
                    ?: error("libdexkit.so not in module apk")
                zip.getInputStream(entry).use { input ->
                    so.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        System.load(so.absolutePath)
        xlog("loaded from module apk abi=$abi")
    }

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        return when {
            abis.any { it.contains("arm64") } -> "arm64-v8a"
            abis.any { it.contains("armeabi") } -> "armeabi-v7a"
            abis.any { it.contains("x86_64") } -> "x86_64"
            abis.any { it.contains("x86") } -> "x86"
            else -> "arm64-v8a"
        }
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
