package com.OKK.yes.core.hooks

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
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * 虚拟定位（虚拟定位）。
 *
 * 原理：
 * 1. DexKit 用日志 tag 定位微信侧 `onLocationChanged`：
 *    - MicroMsg.SLocationListener
 *    - MicroMsg.SLocationListenerWgs84
 *    - MicroMsg.DefaultTencentLocationManager
 * 2. 在回调里拿到 Location 参数，Hook 其 `getLatitude` / `getLongitude` 返回设定值
 * 3. 额外 Hook `android.location.Location` 作为系统定位兜底
 *
 * 作用域：主进程（发送位置/附近的人等）。
 */
object VirtualLocationHook {
    private const val TAG = "OKK-VirtualLoc"

    private val installed = AtomicBoolean(false)
    private val dexKitAttempted = AtomicBoolean(false)
    private val dexKitNativeLoaded = AtomicBoolean(false)
    private val hookedClasses: MutableSet<Class<*>> =
        Collections.newSetFromMap(WeakHashMap())
    private val androidLocationHooked = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install enabled=${VirtualLocationConfig.isEnabled()}")
        hookAndroidLocation()
        installDexKitHooks(context, classLoader, modulePath)
    }

    private fun installDexKitHooks(context: Context, classLoader: ClassLoader, modulePath: String?) {
        if (!dexKitAttempted.compareAndSet(false, true)) return
        runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                val tags = listOf(
                    "MicroMsg.SLocationListener",
                    "MicroMsg.SLocationListenerWgs84",
                    "MicroMsg.DefaultTencentLocationManager"
                )
                var count = 0
                for (tag in tags) {
                    count += hookOnLocationChangedByTag(bridge, classLoader, tag)
                }
                // WA DefaultManager：双字符串特征
                count += hookOnLocationChangedByStrings(
                    bridge,
                    classLoader,
                    "DefaultTencentLocationManager",
                    "MicroMsg.DefaultTencentLocationManager",
                    "[mlocationListener]error:%d, reason:%s"
                )
                xlog("DexKit onLocationChanged hooks: $count")
            }
        }.onFailure {
            xlog("DexKit setup failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun hookOnLocationChangedByTag(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        tag: String
    ): Int {
        return hookOnLocationChangedByStrings(bridge, classLoader, tag, tag)
    }

    private fun hookOnLocationChangedByStrings(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        label: String,
        vararg strings: String
    ): Int {
        val descriptors = runCatching {
            bridge.findMethod {
                matcher {
                    name = "onLocationChanged"
                    usingEqStrings(*strings)
                }
            }.map { it.descriptor }
        }.getOrDefault(emptyList())

        var n = 0
        for (desc in descriptors.distinct()) {
            val method = runCatching { descriptorToMethod(desc, classLoader) }.getOrNull()
                ?: continue
            if (hookOnLocationChangedMethod(method, "DexKit:$label")) n++
        }
        if (descriptors.isEmpty()) {
            xlog("DexKit miss onLocationChanged label=$label")
        }
        return n
    }

    private fun hookOnLocationChangedMethod(method: Method, label: String): Boolean {
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!VirtualLocationConfig.isEnabled()) return
                    // 参数 0 一般为 Location / TencentLocation
                    val location = param.args.firstOrNull() ?: return
                    hookLocationGetters(location.javaClass)
                }
            })
            xlog("hooked onLocationChanged via $label -> ${method.declaringClass.name}.${method.name}")
            true
        }.getOrDefault(false)
    }

    /** 系统 Location 兜底 */
    private fun hookAndroidLocation() {
        if (!androidLocationHooked.compareAndSet(false, true)) return
        runCatching {
            val clazz = android.location.Location::class.java
            hookLocationGetters(clazz)
            xlog("hooked android.location.Location getters")
        }.onFailure {
            xlog("android.location.Location hook failed: ${it.message}")
        }
    }

    /**
     * 对 Location 类的 getLatitude / getLongitude 改写返回值。
     * 同一 Class 只 hook 一次。
     */
    private fun hookLocationGetters(locationClass: Class<*>) {
        synchronized(hookedClasses) {
            if (hookedClasses.contains(locationClass)) return
            hookedClasses.add(locationClass)
        }
        var hooked = 0
        for (name in listOf("getLatitude", "getLongitude")) {
            locationClass.declaredMethods
                .filter {
                    it.name == name &&
                        it.parameterTypes.isEmpty() &&
                        (it.returnType == Double::class.javaPrimitiveType ||
                            it.returnType == Double::class.javaObjectType ||
                            it.returnType == Float::class.javaPrimitiveType ||
                            it.returnType == Float::class.javaObjectType)
                }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        val isLat = name == "getLatitude"
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!VirtualLocationConfig.isEnabled()) return
                                val (enabled, lat, lon) = VirtualLocationConfig.load()
                                if (!enabled) return
                                val value = if (isLat) lat else lon
                                param.result = when (method.returnType) {
                                    Float::class.javaPrimitiveType,
                                    Float::class.javaObjectType -> value.toFloat()
                                    else -> value
                                }
                            }
                        })
                        hooked++
                    }
                }
        }
        // 再扫父类（部分实现把 getter 放在父接口实现类）
        locationClass.superclass?.let { superClz ->
            if (superClz != Any::class.java && superClz.name != "java.lang.Object") {
                hookLocationGetters(superClz)
            }
        }
        if (hooked > 0) {
            xlog("hooked $hooked getters on ${locationClass.name}")
        }
    }

    // ── DexKit native / descriptor（与 AntiRevoke 同套路）──────────────────

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
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/libdexkit.so") ?: return
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        System.load(out.absolutePath)
        dexKitNativeLoaded.set(true)
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
                java.lang.Character.TYPE -> "C"
                java.lang.Byte.TYPE -> "B"
                java.lang.Short.TYPE -> "S"
                java.lang.Float.TYPE -> "F"
                java.lang.Long.TYPE -> "J"
                java.lang.Double.TYPE -> "D"
                else -> error("Unknown primitive $type")
            }
        }
        if (type.isArray) return type.name.replace('.', '/')
        return "L${type.name.replace('.', '/')};"
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
