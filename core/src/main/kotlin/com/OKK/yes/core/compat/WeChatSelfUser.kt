package com.OKK.yes.core.compat

import android.content.Context
import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

/**
 * 解析当前登录用户 username（wxid / 微信号）。
 *
 * 8.0.69 混淆为 `iy0.z1.r()`，其它版本类名全变；
 * 全版本共有日志标签 [TAG_CONFIG] → DexKit / 反射扫描。
 */
object WeChatSelfUser {
    private const val TAG = "OKK-SelfUser"
    private const val TAG_CONFIG = "MicroMsg.ConfigStorageLogic"
    private const val LOG_USERINFO_FAIL = "get userinfo fail"
    private const val FALLBACK_69 = "iy0.z1"

    private val cached = AtomicReference("")
    private val cachedNickname = AtomicReference("")
    private val configClass = AtomicReference<Class<*>?>(null)

    fun clearCache() {
        cached.set("")
        cachedNickname.set("")
        configClass.set(null)
    }

    fun resolve(
        classLoader: ClassLoader,
        context: Context? = null,
        modulePath: String? = null
    ): String {
        cached.get().takeIf { it.isNotBlank() }?.let { return it }

        val fromClass = resolveFromConfigClass(classLoader, context, modulePath)
        if (fromClass.isNotBlank()) {
            cached.set(fromClass)
            xlog("resolved=$fromClass")
            return fromClass
        }
        return ""
    }

    fun resolveNickname(
        classLoader: ClassLoader,
        context: Context? = null,
        modulePath: String? = null
    ): String {
        cachedNickname.get().takeIf { it.isNotBlank() }?.let { return it }

        val clazz = findConfigLogicClass(classLoader, context, modulePath) ?: return ""
        val staticStringMethods = clazz.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.isEmpty() &&
                it.returnType == String::class.java
        }.onEach { it.isAccessible = true }

        // 已知 username（wxid 或自定义号），用于排除
        val username = resolveFromConfigClass(classLoader, context, modulePath)

        // 收集所有候选值，排除 username / 空
        val candidates = staticStringMethods.mapNotNull { m ->
            runCatching { m.invoke(null)?.toString()?.trim() }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != "null" && it != username && it != "" }
        }.distinct()
        if (candidates.isEmpty()) {
            xlog("nickname candidates empty")
            return ""
        }

        // 微信号特征：纯 ASCII 字母数字、且非大小写混合（大小写混合如 Youye08/Angus 是昵称）
        fun isWechatId(s: String): Boolean {
            if (s.startsWith("wxid_")) return true
            if (s.any { it.code > 127 }) return false // 含中文/非 ASCII → 昵称
            if (!s.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return false
            val hasUpper = s.any { it.isUpperCase() }
            val hasLower = s.any { it.isLowerCase() }
            val hasDigit = s.any { it.isDigit() }
            // 同时含大写与小写（如 Youye08、Angus）→ 昵称，绝不判为微信号
            if (hasUpper && hasLower) return false
            // 纯数字 → 微信号
            if (hasDigit && !hasUpper && !hasLower) return true
            // 全大写+数字（无小写，DAG1949）→ 微信号
            if (hasDigit && hasUpper && !hasLower) return true
            // 全小写+数字（无大写，john123）→ 微信号
            if (hasDigit && hasLower && !hasUpper) return true
            // 全大写长串无数字（USERNAME）→ 微信号
            if (!hasLower && hasUpper && s.length >= 6) return true
            return false
        }

        // 排序：含非 ASCII > 大小写混合 > 其他；同时排除微信号特征
        val nickCandidates = candidates.filterNot { isWechatId(it) }
        xlog("nickname candidates=" + candidates.joinToString("|") { it.take(20) })
        val best = nickCandidates.firstOrNull { it.any { ch -> ch.code > 127 } }
            ?: nickCandidates.firstOrNull { it.any { it.isUpperCase() } && it.any { it.isLowerCase() } }
            ?: nickCandidates.firstOrNull()
        if (best != null) {
            cachedNickname.set(best)
            xlog("resolved nickname=$best")
            return best
        }
        return ""
    }

    private fun resolveFromConfigClass(
        classLoader: ClassLoader,
        context: Context?,
        modulePath: String?
    ): String {
        val clazz = findConfigLogicClass(classLoader, context, modulePath) ?: return ""
        // 优先无参 static String，常见名 r / s / t / getUsernameFromUserInfo
        val staticStringMethods = clazz.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.isEmpty() &&
                it.returnType == String::class.java
        }.onEach { it.isAccessible = true }

        for (name in listOf("r", "s", "t", "u", "getUsernameFromUserInfo")) {
            val m = staticStringMethods.firstOrNull { it.name == name } ?: continue
            val v = invokeUsername(m) ?: continue
            return v
        }
        for (m in staticStringMethods) {
            val v = invokeUsername(m) ?: continue
            return v
        }
        return ""
    }

    private fun invokeUsername(m: java.lang.reflect.Method): String? {
        val v = runCatching { m.invoke(null)?.toString()?.trim() }.getOrNull().orEmpty()
        if (v.isEmpty() || v == "null") return null
        // wxid_ / 纯数字微信号 / 字母数字号
        if (v.startsWith("wxid_")) return v
        if (v.length in 5..64 && !v.contains(' ') && !v.contains('\n')) return v
        return null
    }

    private fun findConfigLogicClass(
        classLoader: ClassLoader,
        context: Context?,
        modulePath: String?
    ): Class<*>? {
        configClass.get()?.let { return it }

        // 1) 69 兜底
        ReflectCompat.findClass(FALLBACK_69, classLoader)?.let {
            configClass.set(it)
            xlog("config class fallback $FALLBACK_69")
            return it
        }

        // 2) DexKit 特征（全 69–76 命中）
        if (context != null) {
            val found = DexKitSupport.findClassByStrings(
                context,
                classLoader,
                modulePath,
                TAG_CONFIG,
                LOG_USERINFO_FAIL
            ) ?: DexKitSupport.findClassByStrings(
                context,
                classLoader,
                modulePath,
                TAG_CONFIG
            )
            if (found != null) {
                configClass.set(found)
                xlog("config class DexKit ${found.name}")
                return found
            }
        }
        return null
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
