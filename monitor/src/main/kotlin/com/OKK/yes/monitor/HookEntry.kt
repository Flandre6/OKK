package com.OKK.yes.monitor

import android.app.Application
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return
        if (lpparam.processName != "com.tencent.mm") return
        if (!lpparam.isFirstApplication) return

        log("HookMonitor loaded in WeChat")

        // Hook Application.onCreate to trace AfterHooked callback invocations
        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.app.Application", lpparam.classLoader, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        log("=== WeChat Application.onCreate ===")
                        dumpAllDatabaseMethods(lpparam.classLoader)
                        dumpStorageMethods(lpparam.classLoader)
                    }
                }
            )
        } catch (_: Throwable) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.Application", lpparam.classLoader, "onCreate",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            val app = param.thisObject as? Application ?: return
                            if (app.packageName != "com.tencent.mm") return
                            log("=== WeChat Application.onCreate (standard) ===")
                            dumpAllDatabaseMethods(lpparam.classLoader)
                            dumpStorageMethods(lpparam.classLoader)
                        }
                    }
                )
            } catch (_: Throwable) {}
        }
    }

    private fun dumpAllDatabaseMethods(cl: ClassLoader) {
        for (className in listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "android.database.sqlite.SQLiteDatabase"
        )) {
            try {
                val dbClass = XposedHelpers.findClass(className, cl)
                log("=== Database class FOUND: ${dbClass.name} ===")
                for (m in dbClass.declaredMethods) {
                    if (m.name.contains("insert", true) ||
                        m.name.contains("update", true) ||
                        m.name.contains("replace", true) ||
                        m.name.contains("execSQL", true) ||
                        m.name == "delete"
                    ) {
                        log("  DB: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
                    }
                }
                return
            } catch (_: Throwable) {
                log("Database class NOT found: $className")
            }
        }
    }

    private fun dumpStorageMethods(cl: ClassLoader) {
        for (pkg in listOf("com.tencent.mm.storage")) {
            for (name in listOf("bh", "bi", "bj", "bb", "t", "k9", "m9")) {
                try {
                    val cls = XposedHelpers.findClass("$pkg.$name", cl)
                    log("=== Storage class FOUND: $pkg.$name ===")
                    for (m in cls.declaredMethods) {
                        if (m.name.contains("insert", true) ||
                            m.name.contains("update", true) ||
                            m.name.contains("replace", true) ||
                            m.name == "a" || m.name == "b" || m.name == "c"
                        ) {
                            if (m.parameterTypes.any { it.name.contains("ContentValues") }) {
                                log("  STORE: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
                            }
                        }
                    }
                    return
                } catch (_: Throwable) {}
            }
        }
        log("No storage class found")
    }

    private fun log(msg: String) {
        Log.i("HM-Hook", msg)
        try { XposedBridge.log("[HM-Hook] $msg") } catch (_: Throwable) {}
    }
}
