@file:Suppress("unused")

package com.OKK.yes.loader

import android.app.Application
import android.util.Log
import androidx.annotation.Keep
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

@Keep
class ModernHookEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedBridge.initModern(this)
        runCatching {
            ModulePathHolder.modulePath = moduleApplicationInfo.sourceDir
        }
        Log.e(TAG, "libxposed module loaded path=${ModulePathHolder.modulePath}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != WECHAT_PACKAGE) return

        val processName = Application.getProcessName()?.takeIf { it.isNotBlank() }
            ?: param.applicationInfo.processName?.takeIf { it.isNotBlank() }
            ?: param.packageName
        Log.e(TAG, "libxposed package ready package=${param.packageName} process=$processName first=${param.isFirstPackage}")

        HookInstaller.handlePackage(
            packageName = param.packageName,
            processName = processName,
            isFirstApplication = param.isFirstPackage,
            classLoader = param.classLoader,
            modulePath = moduleApplicationInfo.sourceDir,
            source = "libxposed"
        )
    }

    private companion object {
        const val TAG = "OKK-Modern"
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
