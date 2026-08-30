package com.OKK.yes.core.hooks

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏蔽微信热更新（Tinker）。
 *
 * 逆向说明：
 * `DisableHostHotUpdates.kt`
 * 1) 删除 `/data/data/com.tencent.mm/tinker`
 * 2) Hook `ShareTinkerInternals.isTinkerEnabled*` → 返回 false
 * 3) 禁用 TinkerPatch 相关 Service 组件
 *
 * 当前微信反编译确认：
 * - `com.tencent.tinker.loader.shareutil.ShareTinkerInternals`
 * - `isTinkerEnabled` / `isTinkerEnabledAll` / `isTinkerEnabledForDex` 等
 *
 * 配置：`disable_hot_update` 默认 false
 */
object DisableHotUpdateHook {
    private const val TAG = "OKK-HotUpdate"
    private const val KEY = "disable_hot_update"
    private const val TINKER_INTERNALS =
        "com.tencent.tinker.loader.shareutil.ShareTinkerInternals"

    private val componentNames = listOf(
        "com.tencent.tinker.lib.service.TinkerPatchForeService",
        "com.tencent.tinker.lib.service.TinkerPatchService",
        "com.tencent.tinker.lib.service.TinkerPatchService\$InnerService",
        "com.tencent.tinker.lib.service.DefaultTinkerResultService"
    )

    private val installed = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install enabled=${isEnabled()}")

        // 始终装 Hook；运行时读开关
        hookTinkerFlags(classLoader)

        if (isEnabled()) {
            wipeTinkerDir(context)
            setTinkerComponentsEnabled(context, false)
        }
    }

    fun isEnabled(): Boolean =
        runCatching { PublicConfigStore.getBoolean(KEY, false) }.getOrDefault(false)

    private fun hookTinkerFlags(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(TINKER_INTERNALS, classLoader)
            var n = 0
            for (m in clazz.declaredMethods) {
                if (!m.name.startsWith("isTinkerEnabled")) continue
                if (m.returnType != Boolean::class.javaPrimitiveType &&
                    m.returnType != Boolean::class.javaObjectType
                ) {
                    continue
                }
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        param.result = false
                    }
                })
                n++
                xlog("hooked ${m.name}")
            }
            xlog("ShareTinkerInternals hooks=$n")
        }.onFailure {
            xlog("TinkerInternals fail: ${it.message}")
        }

        // 额外：拦截常见补丁应用入口字符串相关（可选）
        runCatching {
            val loader = XposedHelpers.findClass(
                "com.tencent.tinker.loader.TinkerLoader",
                classLoader
            )
            for (m in loader.declaredMethods) {
                if (m.name.contains("tryLoad", ignoreCase = true) ||
                    m.name.contains("load", ignoreCase = true)
                ) {
                    // 过宽，不默认短路 tryLoad；仅日志探测
                }
            }
        }
    }

    private fun wipeTinkerDir(context: Context) {
        val pkg = "com.tencent.mm"
        val candidates = listOf(
            File("/data/data/$pkg/tinker"),
            File(context.applicationInfo?.dataDir ?: "", "tinker"),
            File("/data/user/0/$pkg/tinker")
        )
        for (dir in candidates) {
            runCatching {
                if (dir.exists()) {
                    dir.deleteRecursively()
                    xlog("wiped ${dir.absolutePath}")
                }
            }
        }
    }

    private fun setTinkerComponentsEnabled(context: Context, enabled: Boolean) {
        val pm = context.packageManager ?: return
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        for (name in componentNames) {
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName("com.tencent.mm", name),
                    state,
                    PackageManager.DONT_KILL_APP
                )
                xlog("component $name enabled=$enabled")
            }.onFailure {
                xlog("component $name fail: ${it.message}")
            }
        }
    }

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
