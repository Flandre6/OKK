package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.OKK.yes.core.compat.ReflectCompat
import com.OKK.yes.core.compat.WeChatClassNames
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * 打开微信内置页面。
 *
 * 跨版本：稳定 Activity 类名全版本存在，优先 [openClass]；
 * 插件路由 hub（如 8.0.69 的 v05.l）仅作加速路径，失败不影响。
 */
object WeChatPageLauncher {
    private const val TAG = "OKK-WxLaunch"

    private val pluginHub = AtomicReference<Method?>(null)
    private val classLoaderTag = AtomicReference<Int>(0)

    private fun ensureHub(cl: ClassLoader): Method? {
        val tag = System.identityHashCode(cl)
        if (classLoaderTag.get() != tag) {
            pluginHub.set(null)
            classLoaderTag.set(tag)
        }
        pluginHub.get()?.let { return it }

        val candidates = listOf(
            WeChatClassNames.Obfuscated69.PLUGIN_HUB,
            // 邻近版本可能出现的包名（尝试失败即跳过）
            "u05.l", "w05.l", "t05.l", "x05.l", "y05.l", "z05.l",
            "v15.l", "v04.l", "v06.l"
        )
        for (name in candidates) {
            val m = runCatching {
                val hub = Class.forName(name, false, cl)
                hub.declaredMethods.firstOrNull { method ->
                    method.parameterTypes.size >= 4 &&
                        Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                        method.parameterTypes[1] == String::class.java &&
                        method.parameterTypes[2] == String::class.java
                }?.also { it.isAccessible = true }
            }.getOrNull()
            if (m != null) {
                pluginHub.set(m)
                xlog("plugin hub ${m.declaringClass.name}.${m.name}")
                return m
            }
        }
        return null
    }

    fun openPlugin(
        activity: Activity,
        plugin: String,
        path: String,
        extras: Map<String, Any?> = emptyMap()
    ): Boolean {
        val intent = Intent().also { putExtras(it, extras) }
        val ok = runCatching {
            val m = ensureHub(activity.classLoader) ?: return@runCatching false
            val args = arrayOfNulls<Any?>(m.parameterTypes.size)
            args[0] = activity
            args[1] = plugin
            args[2] = path
            args[3] = intent
            m.invoke(null, *args)
            true
        }.getOrDefault(false)
        if (ok) return true

        val className = when {
            path.startsWith(".") -> "com.tencent.mm.plugin.$plugin$path"
            else -> path
        }.replace("..", ".")
        return openClass(activity, className, extras)
    }

    fun openClass(
        activity: Activity,
        className: String,
        extras: Map<String, Any?> = emptyMap()
    ): Boolean {
        // 类不存在时直接失败，避免 startActivity 抛错卡顿
        if (ReflectCompat.findClass(className, activity.classLoader) == null) {
            return false
        }
        return runCatching {
            val intent = Intent()
            intent.setClassName(activity, className)
            putExtras(intent, extras)
            activity.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun openQrCode(activity: Activity): Boolean {
        // 稳定类名优先（cn74/76 已无 Colorful 时仍有 SelfQRCodeUI）
        if (openClass(activity, WeChatClassNames.SELF_QR)) return true
        if (openClass(activity, WeChatClassNames.SELF_QR_COLORFUL)) return true
        if (openPlugin(activity, "setting", ".ui.setting.SelfQRCodeUI")) return true
        return false
    }

    fun openOfflinePay(activity: Activity): Boolean {
        if (openClass(activity, WeChatClassNames.OFFLINE_PAY)) return true
        return openPlugin(activity, "offline", ".ui.WalletOfflineEntranceUI")
    }

    fun openService(activity: Activity): Boolean {
        if (openClass(activity, WeChatClassNames.MALL_INDEX)) return true
        return openPlugin(activity, "mall", ".ui.MallIndexUIv2")
    }

    fun openFavorite(activity: Activity): Boolean {
        if (openClass(activity, WeChatClassNames.FAVORITE)) return true
        return openPlugin(activity, "fav", ".ui.FavoriteIndexUI")
    }

    fun openShortcut(activity: Activity, s: HomeDrawerConfig.Shortcut): Boolean {
        return when (s) {
            HomeDrawerConfig.Shortcut.QRCODE -> openQrCode(activity)
            HomeDrawerConfig.Shortcut.PAY -> openOfflinePay(activity)
            HomeDrawerConfig.Shortcut.SERVICE -> openService(activity)
            HomeDrawerConfig.Shortcut.FAVORITE -> openFavorite(activity)
        }
    }

    private fun putExtras(intent: Intent, extras: Map<String, Any?>) {
        extras.forEach { (k, v) ->
            when (v) {
                null -> Unit
                is String -> intent.putExtra(k, v)
                is Boolean -> intent.putExtra(k, v)
                is Int -> intent.putExtra(k, v)
                is Long -> intent.putExtra(k, v)
                else -> intent.putExtra(k, v.toString())
            }
        }
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
