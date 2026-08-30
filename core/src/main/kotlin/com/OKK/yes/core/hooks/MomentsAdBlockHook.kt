package com.OKK.yes.core.hooks

import android.content.Context
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 去除朋友圈广告。
 *
 * 逆向说明：
 * `RemoveMomentsAds.kt` → Hook `com.tencent.mm.plugin.sns.storage.ADInfo`
 * 的 `ADInfo(String)` 构造，before 将 result 置 null，阻止广告对象生成。
 *
 * 当前微信反编译确认：
 * - `ADInfo` 类存在
 * - 构造：`ADInfo()` / `ADInfo(String)`
 *
 * 配置：`remove_moments_ads` 默认 false
 */
object MomentsAdBlockHook {
    private const val TAG = "OKK-MomentsAd"
    private const val KEY = "remove_moments_ads"
    private const val AD_INFO = "com.tencent.mm.plugin.sns.storage.ADInfo"

    private val installed = AtomicBoolean(false)
    private val blocked = AtomicInteger(0)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install enabled=${isEnabled()}")
        runCatching {
            val clazz = XposedHelpers.findClass(AD_INFO, classLoader)
            var n = 0
            // 构造后清空关键广告字段（构造器 before 置 result 在 Xposed 上不可靠）
            for (ctor in clazz.declaredConstructors) {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        val obj = param.thisObject ?: return
                        neutralizeAdInfo(obj)
                        val c = blocked.incrementAndGet()
                        if (c <= 5 || c % 50 == 0) {
                            xlog("neutralized ADInfo #$c")
                        }
                    }
                })
                n++
            }
            // 布尔 isAd* 方法强制 true，让时间线跳过广告节点
            for (m in clazz.declaredMethods) {
                if (m.returnType != Boolean::class.javaPrimitiveType &&
                    m.returnType != Boolean::class.javaObjectType
                ) {
                    continue
                }
                val name = m.name.lowercase()
                if (!name.contains("ad") && !name.contains("advert")) continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        param.result = true
                    }
                })
                n++
            }
            xlog("hooked ADInfo paths count=$n")
        }.onFailure {
            xlog("ADInfo hook fail: ${it.message}")
        }
    }

    private fun neutralizeAdInfo(obj: Any) {
        runCatching {
            for (f in obj.javaClass.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                when {
                    f.type == String::class.java -> {
                        val n = f.name.lowercase()
                        if (n.contains("ad") || n.contains("uxinfo") || n.contains("aid")) {
                            runCatching { f.set(obj, "") }
                        }
                    }
                    f.type == Int::class.javaPrimitiveType ||
                        f.type == Int::class.javaObjectType -> {
                        val n = f.name.lowercase()
                        if (n.contains("ad") || n.contains("type") && n.contains("action")) {
                            runCatching { f.set(obj, 0) }
                        }
                    }
                }
            }
        }
    }

    fun isEnabled(): Boolean =
        runCatching { PublicConfigStore.getBoolean(KEY, false) }.getOrDefault(false)

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
