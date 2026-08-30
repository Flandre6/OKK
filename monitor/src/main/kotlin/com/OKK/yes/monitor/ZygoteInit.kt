package com.OKK.yes.monitor

import android.util.Log
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member

/**
 * Hooks XposedBridge.hookMethod DURING Zygote init.
 * This fires BEFORE any app (including WeChat) loads,
 * so it will catch ALL module hook installations.
 */
class ZygoteInit : IXposedHookZygoteInit {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        Log.i("HM-Zygote", "ZygoteInit: installing hookMethod tracer")

        try {
            // Hook XposedBridge.hookMethod(Member, XC_MethodHook) - the universal hook api
            XposedBridge.hookMethod(
                XposedBridge::class.java.getDeclaredMethod("hookMethod",
                    Member::class.java, XC_MethodHook::class.java),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val member = param.args[0] as? Member ?: return
                        val callback = param.args[1] as? XC_MethodHook ?: return
                        val caller = getCallerInfo()

                        val methodInfo = "${member.declaringClass.name}.${member.name}"
                        val hookType = when {
                            callback::class.java.name.contains("Before", true) -> "BEFORE"
                            callback::class.java.name.contains("After", true) -> "AFTER"
                            callback::class.java.name.contains("Replace", true) -> "REPLACE"
                            else -> callback::class.java.simpleName.take(20)
                        }

                        log("HOOK $hookType $methodInfo | caller=$caller")
                    }
                }
            )

            // Hook XposedHelpers.findAndHookMethod(String, ClassLoader, String, Object...)
            try {
                for (m in de.robv.android.xposed.XposedHelpers::class.java.declaredMethods) {
                    if (m.name != "findAndHookMethod") continue
                    if (m.parameterTypes.size < 3) continue
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val className = param.args[0]?.toString() ?: "?"
                            val methodName = param.args[2]?.toString() ?: "?"
                            log("findAndHook $className.$methodName")
                        }
                    })
                }
            } catch (_: Throwable) {}

            // Hook XposedHelpers.findAndHookMethod(Class, String, Object...)
            try {
                for (m in de.robv.android.xposed.XposedHelpers::class.java.declaredMethods) {
                    if (m.name != "findAndHookMethod") continue
                    if (m.parameterTypes[0] == Class::class.java && m.parameterTypes[1] == String::class.java) {
                        m.isAccessible = true
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val clazz = param.args[0]?.toString() ?: "?"
                                val methodName = param.args[1]?.toString() ?: "?"
                                log("findAndHookClass $clazz.$methodName")
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}

            Log.i("HM-Zygote", "hookMethod tracer installed")
        } catch (e: Throwable) {
            Log.e("HM-Zygote", "initZygote failed: ${e.message}")
            try { XposedBridge.log("[HM-Zygote] FAILED: ${e.message}") } catch (_: Throwable) {}
        }
    }

    private fun getCallerInfo(): String {
        val stack = Throwable().stackTrace
        // Find the first frame NOT in XposedHelpers, XC_MethodHook, or our own code
        for (frame in stack) {
            val cn = frame.className
            if (cn.startsWith("de.robv.android.xposed.")) continue
            if (cn.startsWith("com.OKK.yes.monitor")) continue
            if (cn.startsWith("android.")) continue
            if (cn.startsWith("java.")) continue
            if (cn.startsWith("dalvik.")) continue
            if (cn.startsWith("org.luckypray.")) continue
            return "${cn.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
        }
        return "?"
    }

    private fun log(msg: String) {
        Log.i("HM-Hook", msg)
        try { XposedBridge.log("[HM-Hook] $msg") } catch (_: Throwable) {}
    }
}
