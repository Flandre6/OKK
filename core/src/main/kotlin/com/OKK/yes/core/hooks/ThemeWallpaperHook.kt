package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局背景遮罩：
 * Activity.onResume → decorView 顶层半透明壁纸遮罩（点击穿透）。
 */
object ThemeWallpaperHook {
    private const val TAG = "OKK-ThemeWpHook"
    const val REQ_PICK_IMAGE = 0x0A0C11
    private val installed = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        ThemeWallpaperController.installListener()
        ThemeWallpaperConfig.reloadIfNeeded(force = true)
        // 默认不透明度取可感知区间（0.15~0.45 更明显）
        xlog(
            "install wekit-style en=${ThemeWallpaperConfig.isEnabled()} " +
                "a=${ThemeWallpaperConfig.alpha()} path=${ThemeWallpaperConfig.path()}"
        )

        // Activity.onResume → applyBackground
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        if (!ThemeWallpaperConfig.isEnabled()) return
                        if (!ThemeWallpaperController.isTargetActivity(act)) return
                        runCatching {
                            ThemeWallpaperController.onActivityResume(act)
                        }.onFailure { xlog("resume: ${it.message}") }
                    }
                }
            )
            xlog("hooked Activity.onResume")
        }.onFailure { xlog("onResume fail: ${it.message}") }

        // Launcher 创建后补刷
        runCatching {
            val lu = XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", classLoader)
            XposedHelpers.findAndHookMethod(
                lu,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        ThemeWallpaperController.rememberActivity(act)
                        act.window?.decorView?.postDelayed({
                            if (ThemeWallpaperConfig.isEnabled()) {
                                ThemeWallpaperController.applyBackground(act)
                            }
                        }, 400)
                        act.window?.decorView?.postDelayed({
                            if (ThemeWallpaperConfig.isEnabled()) {
                                ThemeWallpaperController.applyBackground(act)
                            }
                        }, 1200)
                        act.window?.decorView?.postDelayed({
                            if (ThemeWallpaperConfig.isEnabled()) {
                                ThemeWallpaperController.applyBackground(act)
                            }
                        }, 2600)
                        act.window?.decorView?.postDelayed({
                            if (ThemeWallpaperConfig.isEnabled()) {
                                ThemeWallpaperController.applyBackground(act)
                            }
                        }, 5200)
                    }
                }
            )
            xlog("hooked LauncherUI.onCreate")
        }.onFailure { xlog("LauncherUI.onCreate fail: ${it.message}") }

        hookActivityResult()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { ThemeWallpaperController.refreshAll() }
        }, 1800)
    }

    private fun activityFromChattingTab(tab: Any): Activity? {
        return runCatching {
            val field = tab.javaClass.getDeclaredField("f190364a").apply {
                isAccessible = true
            }
            field.get(tab) as? Activity
        }.getOrNull()
    }

    private fun snapHomeReveal(tab: Any) {
        runCatching {
            val act = activityFromChattingTab(tab)
            val chat = tab.javaClass.getDeclaredField("f190366c").apply {
                isAccessible = true
            }.get(tab) as? android.view.View
            chat?.animate()?.cancel()
            chat?.clearAnimation()
            chat?.translationX = 0f
            chat?.translationY = 0f
            chat?.alpha = 1f
            chat?.visibility = android.view.View.GONE
            act?.javaClass?.methods?.firstOrNull {
                it.name == "onSwipe" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Float::class.javaPrimitiveType
            }?.invoke(act, 1.0f)
            xlog("snap home reveal")
        }
    }

    private fun hookActivityResult() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.getOrNull(0) as? Int != REQ_PICK_IMAGE) return
                        if (param.args.getOrNull(1) as? Int != Activity.RESULT_OK) return
                        val uri = (param.args.getOrNull(2) as? Intent)?.data ?: return
                        val act = param.thisObject as? Activity ?: return
                        val ok = ThemeWallpaperConfig.saveFromUri(act, uri)
                        xlog("pick ok=$ok")
                        if (ok) {
                            ThemeWallpaperConfig.setEnabled(true, notify = false)
                            // 重新选图后微信首页可能仍在恢复页面，立即应用时容器尚未就绪。
                            // 强制刷新缓存并在多个布局阶段重试，确保新图替换旧图。
                            ThemeWallpaperConfig.invalidate()
                            ThemeWallpaperConfig.reloadIfNeeded(force = true)
                            xlog("pick saved path=${ThemeWallpaperConfig.path()} key=${ThemeWallpaperConfig.imageKey()}")
                            val delays = longArrayOf(0L, 300L, 900L, 1800L, 3200L)
                            for (delay in delays) {
                                act.window?.decorView?.postDelayed({
                                    if (!act.isFinishing && ThemeWallpaperConfig.isEnabled()) {
                                        val msg = ThemeWallpaperController.forceApplyNow()
                                        xlog("pick apply delay=${delay} $msg")
                                    }
                                }, delay)
                            }
                        }
                    }
                }
            )
        }
    }

    fun startPickImage(activity: Activity) {
        ThemeWallpaperController.rememberActivity(activity)
        val host = ThemeWallpaperController.resolveLauncherUi() ?: activity
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching {
            host.startActivityForResult(
                Intent.createChooser(intent, "选择壁纸"),
                REQ_PICK_IMAGE
            )
        }.onFailure {
            runCatching { activity.startActivityForResult(intent, REQ_PICK_IMAGE) }
        }
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("$TAG: $msg") }
    }
}
