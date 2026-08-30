package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.OKK.yes.core.R
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动登录 PC / 其它设备（PC/平板登录页自动勾选）。
 *
 * 原理：
 * 1. Hook `com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI`
 * 2. `onCreate` before：注入 `intent.key.function.control` 位掩码勾选项
 * 3. `initView` after（或 onCreate post）：找到登录 [Button] 并 `performClick()`
 *
 * 类名相对稳定；initView 若混淆则降级为遍历字段 / 视图树找 Button。
 */
object AutoLoginWinHook {
    private const val TAG = "OKK-AutoLogin"
    private const val TARGET = "com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI"

    private val installed = AtomicBoolean(false)
    private val hookedMethods = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        val opt = AutoLoginWinConfig.load()
        xlog("install enabled=${opt.enabled} sync=${opt.syncMsg} showDev=${opt.showDevice} autoDev=${opt.autoLoginDevice} click=${opt.autoClick}")

        val clazz = runCatching {
            XposedHelpers.findClass(TARGET, classLoader)
        }.getOrElse {
            xlog("class not found: $TARGET (${it.message})")
            return
        }

        hookOnCreate(clazz)
        hookInitView(clazz)
        xlog("hooks ready for $TARGET")
    }

    private fun hookOnCreate(clazz: Class<*>) {
        val methods = clazz.declaredMethods.filter { m ->
            m.name == "onCreate" &&
                m.parameterTypes.size == 1 &&
                Bundle::class.java.isAssignableFrom(m.parameterTypes[0])
        }.ifEmpty {
            // 兼容混淆：Activity 子类单 Bundle 参数
            clazz.methods.filter { m ->
                m.parameterTypes.size == 1 &&
                    Bundle::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    (m.name == "onCreate" || m.declaringClass == clazz)
            }
        }

        methods.forEach { method ->
            if (!hookedMethods.add(keyOf(method))) return@forEach
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val opt = AutoLoginWinConfig.load()
                    if (!opt.enabled) return
                    val control = opt.functionControl()
                    activity.intent.putExtra(AutoLoginWinConfig.EXTRA_FUNCTION_CONTROL, control)
                    activity.intent.putExtra(AutoLoginWinConfig.EXTRA_PRIVACY, false)
                    xlog("onCreate inject functionControl=$control (${method.name})")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val opt = AutoLoginWinConfig.load()
                    if (!opt.enabled || !opt.autoClick) return
                    // initView 可能尚未执行，延迟再点一次作兜底
                    activity.window?.decorView?.post {
                        tryClickLoginButton(activity, "onCreate.post")
                    }
                    activity.window?.decorView?.postDelayed({
                        tryClickLoginButton(activity, "onCreate.postDelayed")
                    }, 400L)
                }
            })
            xlog("hooked ${method.declaringClass.simpleName}.${method.name}")
        }
        if (methods.isEmpty()) xlog("no onCreate method found on $TARGET")
    }

    private fun hookInitView(clazz: Class<*>) {
        val candidates = clazz.declaredMethods.filter { m ->
            m.parameterTypes.isEmpty() &&
                (m.returnType == Void.TYPE || m.returnType == Void::class.java) &&
                (
                    m.name == "initView" ||
                        m.name == "init" ||
                        m.name.equals("a", ignoreCase = false) ||
                        m.name.length <= 2
                    )
        }.sortedWith(
            compareByDescending<Method> { it.name == "initView" }
                .thenByDescending { it.name == "init" }
                .thenBy { it.name.length }
        )

        // 优先明确的 initView；否则只 hook 名称像 init 的，避免乱 hook 短名
        val toHook = candidates.filter { it.name == "initView" || it.name == "init" }
            .ifEmpty { emptyList() }

        toHook.forEach { method ->
            if (!hookedMethods.add(keyOf(method))) return@forEach
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val opt = AutoLoginWinConfig.load()
                    if (!opt.enabled || !opt.autoClick) return
                    tryClickLoginButton(activity, "initView.${method.name}")
                }
            })
            xlog("hooked ${method.declaringClass.simpleName}.${method.name}()")
        }

        if (toHook.isEmpty()) {
            xlog("initView not found; rely on onCreate post click")
        }
    }

    private fun tryClickLoginButton(activity: Activity, reason: String) {
        if (activity.isFinishing) return
        val decor = activity.window?.decorView ?: return
        // 已点过则跳过（防 onCreate + initView 双触发）
        if (decor.getTag(R.id.abc_tag_auto_login_clicked) == true) return

        val button = findLoginButton(activity) ?: run {
            xlog("login button not found ($reason)")
            return
        }
        if (!button.isEnabled || button.visibility != View.VISIBLE) {
            xlog("login button not clickable enabled=${button.isEnabled} vis=${button.visibility} ($reason)")
            return
        }
        runCatching {
            button.performClick()
            decor.setTag(R.id.abc_tag_auto_login_clicked, true)
            xlog("performClick ok via $reason text=${button.text}")
        }.onFailure {
            xlog("performClick failed: ${it.message}")
        }
    }

    private fun findLoginButton(activity: Activity): Button? {
        // 1) 字段类型 Button（WA 写法）
        findButtonField(activity)?.let { return it }

        // 2) 视图树：优先文案含「登录」的 Button
        val root = activity.window?.decorView as? ViewGroup ?: return null
        val all = ArrayList<Button>()
        collectButtons(root, all)
        if (all.isEmpty()) return null

        val byText = all.firstOrNull { btn ->
            val t = btn.text?.toString().orEmpty()
            t.contains("登录") || t.contains("登入") ||
                t.contains("Login", ignoreCase = true) ||
                t.contains("確認") || t.contains("确认")
        }
        if (byText != null) return byText

        // 3) 取最后一个可见且可点的 Button（登录页通常主按钮在底部）
        return all.lastOrNull { it.isEnabled && it.visibility == View.VISIBLE } ?: all.lastOrNull()
    }

    private fun findButtonField(activity: Activity): Button? {
        var clazz: Class<*>? = activity.javaClass
        var depth = 0
        while (clazz != null && depth < 6) {
            for (field in clazz.declaredFields) {
                if (!Button::class.java.isAssignableFrom(field.type)) continue
                val btn = runCatching {
                    field.isAccessible = true
                    field.get(activity) as? Button
                }.getOrNull()
                if (btn != null && btn.visibility == View.VISIBLE) return btn
            }
            clazz = clazz.superclass
            depth++
        }
        return null
    }

    private fun collectButtons(view: View, out: MutableList<Button>) {
        if (view is Button) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectButtons(view.getChildAt(i), out)
            }
        }
    }

    private fun keyOf(method: Method): String {
        return "${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()}"
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
