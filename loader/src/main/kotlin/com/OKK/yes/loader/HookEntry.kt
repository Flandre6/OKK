package com.OKK.yes.loader

import android.app.Application
import android.content.Context
import android.util.Log
import com.OKK.yes.core.compat.CompatReportStore
import com.OKK.yes.core.compat.WeChatVersion
import com.OKK.yes.core.hooks.ModuleLog
import com.OKK.yes.core.startup.FeatureHookRegistry
import com.OKK.yes.core.startup.WeChatProcessPolicy
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookInstaller.handlePackage(
            packageName = lpparam.packageName,
            processName = lpparam.processName,
            isFirstApplication = lpparam.isFirstApplication,
            classLoader = lpparam.classLoader,
            modulePath = null,
            source = "legacy"
        )
    }
}

object HookInstaller {
    private const val TAG = "OKK-Loader"
    private val coreInstalled = AtomicBoolean(false)

    fun handlePackage(
        packageName: String,
        processName: String,
        isFirstApplication: Boolean,
        classLoader: ClassLoader,
        modulePath: String?,
        source: String
    ) {
        if (!modulePath.isNullOrBlank()) {
            ModulePathHolder.modulePath = modulePath
        }

        if (packageName == "com.tencent.mm") {
            xlog(
                "seen wechat source=$source process=$processName firstApp=$isFirstApplication"
            )
        }
        if (!WeChatProcessPolicy.shouldHandle(
                packageName,
                processName,
                isFirstApplication
            )
        ) {
            return
        }

        xlog("handlePackage accepted: $processName source=$source")
        hookTinkerBaseContext(classLoader)
        hookApplicationOnCreate(classLoader)
    }

    private fun hookTinkerBaseContext(classLoader: ClassLoader) {
        val tinkerClass = runCatching {
            XposedHelpers.findClass(
                "com.tencent.tinker.loader.app.TinkerApplication",
                classLoader
            )
        }.getOrNull()

        if (tinkerClass == null) {
            xlog("TinkerApplication not found; waiting for Application.onCreate")
            return
        }

        var hooked = 0
        hooked += hookBaseContextSignature(tinkerClass, classLoader, "Context") {
            XposedHelpers.findAndHookMethod(
                tinkerClass,
                "onBaseContextAttached",
                Context::class.java,
                it
            )
        }
        hooked += hookBaseContextSignature(tinkerClass, classLoader, "Context,long,long") {
            XposedHelpers.findAndHookMethod(
                tinkerClass,
                "onBaseContextAttached",
                Context::class.java,
                Long::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!,
                it
            )
        }
        xlog("Tinker startup hooks installed: $hooked")
    }

    private fun hookBaseContextSignature(
        tinkerClass: Class<*>,
        classLoader: ClassLoader,
        signature: String,
        install: (XC_MethodHook) -> Unit
    ): Int {
        return try {
            install(object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.args.firstOrNull() as? Context ?: return
                    installCoreHooks(
                        context,
                        classLoader,
                        "Tinker.onBaseContextAttached($signature)"
                    )
                }
            })
            xlog("hooked ${tinkerClass.name}.onBaseContextAttached($signature)")
            1
        } catch (e: Throwable) {
            xlog("skip Tinker.onBaseContextAttached($signature): ${e.message}")
            0
        }
    }

    private fun hookApplicationOnCreate(classLoader: ClassLoader) {
        var hooked = 0
        for (className in listOf("com.tencent.mm.app.Application", "android.app.Application")) {
            try {
                XposedHelpers.findAndHookMethod(
                    className,
                    classLoader,
                    "onCreate",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val app = param.thisObject as? Application ?: return
                            if (app.packageName != "com.tencent.mm") return
                            installCoreHooks(
                                app,
                                app.classLoader ?: classLoader,
                                "$className.onCreate"
                            )
                        }
                    }
                )
                xlog("hooked $className.onCreate")
                hooked++
            } catch (e: Throwable) {
                xlog("skip $className.onCreate: ${e.message}")
            }
        }
        if (hooked == 0) xlog("WARNING: failed to hook Application.onCreate")
    }

    private fun installCoreHooks(context: Context, classLoader: ClassLoader, reason: String) {
        if (!coreInstalled.compareAndSet(false, true)) {
            xlog("core hooks already installed, skip ($reason)")
            return
        }

        // 反重签：非本机正式签名则不注入任何核心 Hook（模块静默失效）
        if (!LegitCheck.isLegit(context.applicationContext ?: context, ModulePathHolder.modulePath)) {
            xlog("LEGIT CHECK FAILED: module not signed by OKK release cert, abort injection")
            return
        }

        val appContext = context.applicationContext ?: context
        xlog("install core hooks via $reason")
        writeAlive(reason)
        FeatureHookRegistry.clear()
        runCatching { ModuleLog.bootstrap() }
        ModuleLog.i("开始加载核心 Hook · $reason")
        val mp = ModulePathHolder.modulePath
        FeatureInstallGateway.prepare(appContext, classLoader, mp)

        // 1) 版本
        FeatureHookRegistry.installIsolated("WeChatVersion") {
            val ver = WeChatVersion.resolve(appContext)
            xlog("wechat ${ver.summary()} range=${WeChatVersion.PRIMARY_RANGE_LABEL}")
            ModuleLog.i("当前微信: ${ver.summary()}")
        }

        // 2) 设置入口始终先装（扫适配时也能进关于页）
        FeatureHookRegistry.installIsolated("SettingsEntry") {
            SettingsEntryHook.install(classLoader, appContext)
        }

        // 3) 弹窗 / 进度 UI 钩子
        FeatureHookRegistry.installIsolated("CompatCheckUi") {
            CompatCheckUi.install(appContext, classLoader)
        }

        val ver = WeChatVersion.current() ?: WeChatVersion.resolve(appContext)
        // 指纹含安装时间：模块重装 / 微信重装 → 必弹适配窗
        val fp = CompatReportStore.fingerprint(appContext, ver, mp)
        val cached = CompatReportStore.loadReport()
        val needInteractive = CompatReportStore.needsAutoDialog(fp)
        ModuleLog.i(
            "适配指纹 needPopup=$needInteractive shown=${CompatReportStore.loadShownFingerprint()} fp=$fp"
        )

        // 全量直接静默安装，无需阻塞式弹窗
        if (needInteractive) {
            // 重装 / 换微信版本：保留交互式适配检测弹窗
            CompatReportStore.pendingDialog = true
            ModuleLog.i("版本指纹变更，将弹出适配检测")
        } else {
            CompatReportStore.pendingInteractiveScan = false
            CompatReportStore.pendingDialog = false
        }
        FeatureInstallGateway.installFeaturesOnce("direct-fast")
        ModuleLog.i("已完成全部功能极速静默安装")

        FeatureHookRegistry.persist()
        xlog("bootstrap finished ${FeatureHookRegistry.summaryLine()}")
    }

    private fun writeAlive(reason: String) {
        val text =
            "alive=${System.currentTimeMillis()}\nreason=$reason\npid=${android.os.Process.myPid()}\n"
        for (path in listOf(
            "/sdcard/Android/media/com.tencent.mm/OKK/hook_alive.txt"
        )) {
            runCatching {
                val f = java.io.File(path)
                f.parentFile?.mkdirs()
                f.writeText(text)
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
