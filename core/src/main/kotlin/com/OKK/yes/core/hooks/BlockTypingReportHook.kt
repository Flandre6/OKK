package com.OKK.yes.core.hooks

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拦截“正在输入”状态对外上报（BlockTypingReport）。
 *
 * 逆向说明（参考 WeKit DisableTypingStatusUploading）：
 * - 微信最终通过 `com.tencent.mm.modelsimple` 下的 MMTypingSend 请求上传正在输入状态。
 * - 核心特征字符串："null cannot be cast to non-null type com.tencent.mm.protocal.MMTypingSend.Req" + "autoAuth"。
 * - Hook 该请求类的 `doScene`，在启用时直接返回 -1，阻断网络层上传。
 * - 旧 SignallingComponent doDirectSend 仅作为兜底路径。
 *
 * 配置键：`block_typing_report`，默认开启 true。
 */
object BlockTypingReportHook {
    private const val TAG = "OKK-BlockTyping"
    const val KEY = "block_typing_report"

    private val installed = AtomicBoolean(false)
    private val dexKitAttempted = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install requested enabled=${isEnabled()}")

        val hooked = installTypingUploadByDexKit(context, classLoader, modulePath)
        if (!hooked) {
            xlog("MMTypingSend hook not found, try SignallingComponent fallback")
            installBySignallingFallback(context, classLoader, modulePath)
        }
    }

    fun isEnabled(): Boolean =
        runCatching { PublicConfigStore.getBoolean(KEY, true) }.getOrDefault(true)

    private fun installTypingUploadByDexKit(context: Context, classLoader: ClassLoader, modulePath: String?): Boolean {
        if (!dexKitAttempted.compareAndSet(false, true)) return false

        return runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                val classes = bridge.findClass {
                    searchPackages("com.tencent.mm.modelsimple")
                    matcher {
                        usingEqStrings(
                            "null cannot be cast to non-null type com.tencent.mm.protocal.MMTypingSend.Req",
                            "autoAuth"
                        )
                    }
                }
                var count = 0
                classes.forEach { data ->
                    val clazz = runCatching { descriptorToClass(data.descriptor, classLoader) }.getOrNull() ?: return@forEach
                    val doScene = clazz.declaredMethods.firstOrNull { it.name == "doScene" }
                        ?: clazz.methods.firstOrNull { it.name == "doScene" }
                    if (doScene != null) {
                        hookTypingUploadDoScene(doScene, "DexKit:${clazz.name}")
                        count++
                    }
                }
                xlog("DexKit found $count MMTypingSend doScene methods")
                count > 0
            }
        }.onFailure {
            xlog("DexKit MMTypingSend find failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun installBySignallingFallback(context: Context, classLoader: ClassLoader, modulePath: String?) {
        runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                val methodsData = bridge.findMethod {
                    matcher {
                        usingStrings("[doDirectSend] mChattingContext is null!")
                    }
                }
                var count = 0
                methodsData.forEach { data ->
                    runCatching {
                        val method = descriptorToMethod(data.descriptor, classLoader)
                        hookSignallingMethod(method, "DexKitFallback")
                        count++
                    }
                }
                xlog("DexKit fallback found $count SignallingComponent methods")
            }
        }.onFailure {
            xlog("DexKit fallback failed: ${it.message}")
            installByStaticFallback(classLoader)
        }
    }

    private fun installByStaticFallback(classLoader: ClassLoader) {
        val candidateClasses = listOf(
            "com.tencent.mm.ui.chatting.component.SignallingComponent",
            "com.tencent.mm.ui.chatting.component.c0",
            "com.tencent.mm.ui.chatting.component.b0"
        )
        for (clsName in candidateClasses) {
            runCatching {
                val clazz = XposedHelpers.findClass(clsName, classLoader)
                for (method in clazz.declaredMethods) {
                    if (method.returnType == Void.TYPE || method.returnType == Boolean::class.javaPrimitiveType) {
                        hookSignallingMethod(method, "StaticFallback:$clsName")
                    }
                }
            }.onSuccess {
                xlog("Static fallback hooked $clsName")
            }
        }
    }

    private fun hookTypingUploadDoScene(method: Method, source: String) {
        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    xlog("blocked typing upload via $source.${method.name}")
                    param.result = -1
                }
            })
            xlog("Successfully hooked MMTypingSend doScene: ${method.declaringClass.name}.${method.name} via $source")
        }.onFailure {
            xlog("Failed to hook MMTypingSend doScene ${method.name}: ${it.message}")
        }
    }

    private fun hookSignallingMethod(method: Method, source: String) {
        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    xlog("intercepted send_typing fallback via $source: ${method.name}")
                    if (method.returnType == Boolean::class.javaPrimitiveType || method.returnType == Boolean::class.javaObjectType) {
                        param.result = false
                    } else {
                        param.result = null
                    }
                }
            })
            xlog("Successfully hooked fallback method: ${method.declaringClass.name}.${method.name} via $source")
        }.onFailure {
            xlog("Failed to hook fallback method ${method.name}: ${it.message}")
        }
    }

    private fun descriptorToMethod(descriptor: String, classLoader: ClassLoader): Method {
        val arrow = descriptor.indexOf("->")
        val argsStart = descriptor.indexOf('(', arrow)
        require(arrow > 1 && argsStart > arrow) { descriptor }
        val clazz = descriptorToClass(descriptor.substring(0, arrow), classLoader)
        val methodName = descriptor.substring(arrow + 2, argsStart)
        val signature = descriptor.substring(argsStart)
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == methodName && methodSignature(method) == signature
            }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        throw NoSuchMethodException(descriptor)
    }

    private fun descriptorToClass(descriptor: String, classLoader: ClassLoader): Class<*> {
        val normalized = descriptor.substringBefore("->")
        require(normalized.startsWith("L") && normalized.endsWith(";")) { descriptor }
        return classLoader.loadClass(normalized.substring(1, normalized.length - 1).replace('/', '.'))
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
                Void.TYPE -> "V"
                Boolean::class.javaPrimitiveType -> "Z"
                Byte::class.javaPrimitiveType -> "B"
                Char::class.javaPrimitiveType -> "C"
                Short::class.javaPrimitiveType -> "S"
                Int::class.javaPrimitiveType -> "I"
                Long::class.javaPrimitiveType -> "J"
                Float::class.javaPrimitiveType -> "F"
                Double::class.javaPrimitiveType -> "D"
                else -> error("Unsupported primitive: $type")
            }
        }
        if (type.isArray) {
            return "[" + typeSignature(type.componentType)
        }
        return "L" + type.name.replace('.', '/') + ";"
    }

    private fun loadDexKitNative(context: Context, modulePath: String?) {
        runCatching {
            System.loadLibrary("dexkit")
        }.onFailure {
            if (!modulePath.isNullOrBlank()) {
                val apkFile = java.io.File(modulePath)
                if (apkFile.exists()) {
                    val abi = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                    val libName = "libdexkit.so"
                    val outSo = java.io.File(context.cacheDir, libName)
                    java.util.zip.ZipFile(apkFile).use { zip ->
                        val entry = zip.getEntry("lib/$abi/$libName") ?: zip.getEntry("lib/arm64-v8a/$libName")
                        if (entry != null) {
                            zip.getInputStream(entry).use { input ->
                                outSo.outputStream().use { output -> input.copyTo(output) }
                            }
                            System.load(outSo.absolutePath)
                        }
                    }
                }
            }
        }
    }

    private fun xlog(msg: String) {
        XposedBridge.log("[$TAG] $msg")
    }
}
