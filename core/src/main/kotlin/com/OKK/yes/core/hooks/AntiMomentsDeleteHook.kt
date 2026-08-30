package com.OKK.yes.core.hooks

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * 朋友圈防删除。
 *
 * 挂在 `SnsSqliteDB` 包装层：
 * - update：sourceType=0 时改回 2，并给 TimeLineObject.ContentDesc 加「[已删除]」
 * - rawQuery：去掉 sourceType 可见性过滤
 * - execSQL：拦截 `sourceType = sourceType & -3`
 * - delete：拦截 SnsInfo 物理删除
 */
object AntiMomentsDeleteHook {
    private const val TAG = "OKK-AntiMoments"
    private const val SNS_DB_TAG = "com.tencent.mm.plugin.sns.storage.SnsSqliteDB"
    private const val TIMELINE_OBJECT = "com.tencent.mm.protocal.protobuf.TimeLineObject"

    private val installed = AtomicBoolean(false)
    private val dexKitNativeLoaded = AtomicBoolean(false)
    private val dexKitAttempted = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return

        xlog("install requested from ${context.javaClass.name}")
        val hooked = installByDexKit(context, classLoader, modulePath)
        if (!hooked) {
            installByStaticFallback(classLoader)
        }
    }

    private fun installByDexKit(context: Context, classLoader: ClassLoader, modulePath: String?): Boolean {
        if (!dexKitAttempted.compareAndSet(false, true)) return false

        return runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                var count = 0
                count += hookMethodsByStrings(
                    bridge,
                    classLoader,
                    listOf(SNS_DB_TAG, "update"),
                    "update"
                ) { method -> hookUpdateMethod(method, "DexKit:update") }
                count += hookMethodsByStrings(
                    bridge,
                    classLoader,
                    listOf(SNS_DB_TAG, "rawQuery"),
                    "rawQuery"
                ) { method -> hookRawQueryMethod(method, "DexKit:rawQuery") }
                count += hookMethodsByStrings(
                    bridge,
                    classLoader,
                    listOf(SNS_DB_TAG, "execSQL"),
                    "execSQL"
                ) { method -> hookExecSqlMethod(method, "DexKit:execSQL") }
                count += hookMethodsByStrings(
                    bridge,
                    classLoader,
                    listOf(SNS_DB_TAG, "delete"),
                    "delete"
                ) { method -> hookDeleteMethod(method, "DexKit:delete") }
                xlog("DexKit installed $count sns db hooks")
                count > 0
            }
        }.onFailure {
            xlog("DexKit setup failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    private fun hookMethodsByStrings(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        strings: List<String>,
        label: String,
        install: (Method) -> Boolean
    ): Int {
        val methods = runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.mm.plugin.sns.storage")
                matcher {
                    usingStrings(*strings.toTypedArray())
                }
            }
        }.getOrNull().orEmpty()

        if (methods.isEmpty()) {
            xlog("DexKit missed $label")
            return 0
        }

        var count = 0
        methods.forEach { data ->
            val method = runCatching { descriptorToMethod(data.descriptor, classLoader) }.getOrNull()
            if (method != null && install(method)) count++
        }
        return count
    }

    /**
     * 当前版本 jadx：`com.tencent.mm.plugin.sns.storage.n2` 即 SnsSqliteDB。
     * 方法签名：
     * - update e(String, ContentValues, String, String[])
     * - rawQuery a(String, String[], int) / j(String, String[])
     * - execSQL i(String, String)
     * - delete delete(String, String, String[])
     */
    private fun installByStaticFallback(classLoader: ClassLoader) {
        val clazz = runCatching {
            XposedHelpers.findClass("com.tencent.mm.plugin.sns.storage.n2", classLoader)
        }.getOrNull()
        if (clazz == null) {
            xlog("static fallback class n2 not found")
            return
        }

        var count = 0
        clazz.declaredMethods.forEach { method ->
            val params = method.parameterTypes
            when {
                // update(table, values, where, whereArgs)
                params.size == 4 &&
                    params[0] == String::class.java &&
                    params[1] == ContentValues::class.java &&
                    params[2] == String::class.java &&
                    params[3] == Array<String>::class.java -> {
                    if (hookUpdateMethod(method, "static:${method.name}")) count++
                }
                // rawQuery(sql, args) or rawQuery(sql, args, flags)
                params.isNotEmpty() &&
                    params[0] == String::class.java &&
                    method.returnType.name.contains("Cursor") -> {
                    if (hookRawQueryMethod(method, "static:${method.name}")) count++
                }
                // execSQL(table, sql)
                params.size == 2 &&
                    params[0] == String::class.java &&
                    params[1] == String::class.java &&
                    (method.returnType == Boolean::class.javaPrimitiveType ||
                        method.returnType == java.lang.Boolean.TYPE) -> {
                    if (hookExecSqlMethod(method, "static:${method.name}")) count++
                }
                // delete(table, where, whereArgs)
                method.name == "delete" &&
                    params.size == 3 &&
                    params[0] == String::class.java -> {
                    if (hookDeleteMethod(method, "static:delete")) count++
                }
            }
        }
        xlog("static fallback installed $count hooks on ${clazz.name}")
    }

    private fun hookUpdateMethod(method: Method, label: String): Boolean {
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!AppFeatureConfig.load().antiMomentsDelete) return
                    val table = param.args.getOrNull(0)?.toString() ?: return
                    if (!AntiMomentsDeleteLogic.isSnsTable(table)) return
                    val values = param.args.getOrNull(1) as? ContentValues ?: return
                    if (!AntiMomentsDeleteLogic.isDeleteUpdate(values.getAsInteger("sourceType"))) return

                    values.put("sourceType", AntiMomentsDeleteLogic.restoreSourceTypeOnDelete())
                    markContentDeleted(values, param.thisObject?.javaClass?.classLoader)
                    xlog("BLOCKED sns delete-update via $label")
                }
            })
            xlog("hooked update via $label")
            true
        }.onFailure {
            xlog("hook update failed $label: ${it.message}")
        }.getOrDefault(false)
    }

    private fun hookRawQueryMethod(method: Method, label: String): Boolean {
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!AppFeatureConfig.load().antiMomentsDelete) return
                    val sqlIndex = param.args.indexOfFirst { it is String }
                    if (sqlIndex < 0) return
                    val sql = param.args[sqlIndex] as? String ?: return
                    val rewritten = AntiMomentsDeleteLogic.rewriteQuerySql(sql) ?: return
                    param.args[sqlIndex] = rewritten
                }
            })
            xlog("hooked rawQuery via $label")
            true
        }.onFailure {
            xlog("hook rawQuery failed $label: ${it.message}")
        }.getOrDefault(false)
    }

    private fun hookExecSqlMethod(method: Method, label: String): Boolean {
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!AppFeatureConfig.load().antiMomentsDelete) return
                    val table = param.args.getOrNull(0)?.toString()
                    val sql = param.args.getOrNull(1)?.toString()
                    if (!AntiMomentsDeleteLogic.shouldBlockClearVisibleBit(table, sql)) return
                    // execSQL 返回 boolean
                    param.result = true
                    xlog("BLOCKED sns clear-visible-bit execSQL via $label")
                }
            })
            xlog("hooked execSQL via $label")
            true
        }.onFailure {
            xlog("hook execSQL failed $label: ${it.message}")
        }.getOrDefault(false)
    }

    private fun hookDeleteMethod(method: Method, label: String): Boolean {
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!AppFeatureConfig.load().antiMomentsDelete) return
                    val table = param.args.getOrNull(0)?.toString()
                    if (!AntiMomentsDeleteLogic.shouldBlockDelete(table)) return
                    param.result = 0
                    xlog("BLOCKED sns physical delete via $label")
                }
            })
            xlog("hooked delete via $label")
            true
        }.onFailure {
            xlog("hook delete failed $label: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * 解析 content blob 为 TimeLineObject，给 ContentDesc 加「[已删除]」前缀。
     * 失败时仅保留 sourceType 改写，不影响主路径。
     */
    private fun markContentDeleted(values: ContentValues, classLoader: ClassLoader?) {
        val content = values.get("content") as? ByteArray ?: return
        if (content.isEmpty() || classLoader == null) return

        runCatching {
            val timelineClass = XposedHelpers.findClass(TIMELINE_OBJECT, classLoader)
            val timeline = timelineClass.newInstance()
            val parseFrom = findParseFrom(timelineClass) ?: return@runCatching
            parseFrom.isAccessible = true
            val parsed = parseFrom.invoke(timeline, content) ?: timeline

            val desc = XposedHelpers.getObjectField(parsed, "ContentDesc") as? String
            if (!AntiMomentsDeleteLogic.needsDeletedPrefix(desc)) return@runCatching
            XposedHelpers.setObjectField(
                parsed,
                "ContentDesc",
                AntiMomentsDeleteLogic.markDeletedDescription(desc)
            )

            val toBytes = findToByteArray(parsed.javaClass) ?: return@runCatching
            toBytes.isAccessible = true
            val bytes = toBytes.invoke(parsed) as? ByteArray ?: return@runCatching
            values.put("content", bytes)
            xlog("marked ContentDesc deleted prefix")
        }.onFailure {
            xlog("ContentDesc mark skipped: ${it.message}")
        }
    }

    private fun findParseFrom(clazz: Class<*>): Method? {
        return clazz.methods.firstOrNull { method ->
            method.name == "parseFrom" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == ByteArray::class.java
        } ?: clazz.declaredMethods.firstOrNull { method ->
            method.name == "parseFrom" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == ByteArray::class.java
        }
    }

    private fun findToByteArray(clazz: Class<*>): Method? {
        return clazz.methods.firstOrNull {
            it.name == "toByteArray" && it.parameterTypes.isEmpty() && it.returnType == ByteArray::class.java
        } ?: clazz.declaredMethods.firstOrNull {
            it.name == "toByteArray" && it.parameterTypes.isEmpty() && it.returnType == ByteArray::class.java
        }
    }

    private fun loadDexKitNative(context: Context, modulePath: String?) {
        if (dexKitNativeLoaded.get()) return

        runCatching {
            System.loadLibrary("dexkit")
        }.onSuccess {
            dexKitNativeLoaded.set(true)
            xlog("DexKit native loaded via library path")
            return
        }

        val apkPath = modulePath ?: throw IllegalStateException("module path unavailable for libdexkit.so")
        val abi = currentAbi()
        val out = File(context.cacheDir, "abc_moments_${abi}_libdexkit.so")
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/libdexkit.so")
                ?: throw IllegalStateException("lib/$abi/libdexkit.so not found in module apk")
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        System.load(out.absolutePath)
        dexKitNativeLoaded.set(true)
        xlog("DexKit native loaded from module apk")
    }

    private fun currentAbi(): String {
        return if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a"
        } else {
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull() ?: "armeabi-v7a"
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
        return buildString {
            append('(')
            method.parameterTypes.forEach { append(typeSignature(it)) }
            append(')')
            append(typeSignature(method.returnType))
        }
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
