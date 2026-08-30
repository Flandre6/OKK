// 设置宿主：Dialog + Compose 视图（第一阶段）
// 保留旧 EmbeddedSettingsUi 的入口形态（Dialog），但内容替换为 Compose 骨架。

package com.OKK.yes.loader.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import java.lang.ref.WeakReference
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.core.view.WindowCompat
import com.OKK.yes.loader.EmbeddedSettingsUi
import com.OKK.yes.loader.ui.theme.OKKTheme
import de.robv.android.xposed.XposedBridge

object OKKSettingsDialog {

    @Volatile
    private var openDialog: Dialog? = null

    @Volatile
    private var currentOwner: DialogComposeOwner? = null

    @Volatile
    private var hostActivityHooked = false

    @Volatile
    private var hostResultHooked = false

    @Volatile
    private var lastBackTime = 0L

    // 追踪最近一次 resume 的有效 Activity：host 失效时自愈回退
    @Volatile private var lastValidActivityRef: WeakReference<Activity>? = null
    @Volatile private var lifecycleRegistered = false

    private fun ensureActivityTracker(context: Context) {
        if (lifecycleRegistered) return
        lifecycleRegistered = true
        runCatching {
            val app = context.applicationContext as? android.app.Application ?: return
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    lastValidActivityRef = WeakReference(activity)
                }
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
        }
    }

    private fun resolveValidHost(host: Activity): Activity? {
        val attached = runCatching { host.window?.decorView?.isAttachedToWindow == true }.getOrDefault(false)
        if (attached && !host.isFinishing && !host.isDestroyed) return host
        val last = lastValidActivityRef?.get()
        if (last != null && !last.isFinishing && !last.isDestroyed) {
            val ok = runCatching { last.window?.decorView?.isAttachedToWindow == true }.getOrDefault(false)
            if (ok) return last
        }
        return null
    }

    private fun dispatchBackSafe(owner: DialogComposeOwner) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackTime > 400) {
            lastBackTime = now
            owner.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun ensureHostActivityBackHooked() {
        if (hostActivityHooked) return
        hostActivityHooked = true
        runCatching {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onBackPressed",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activeDialog = openDialog
                        val owner = currentOwner
                        if (activeDialog != null && activeDialog.isShowing && owner != null) {
                            param.result = null
                            dispatchBackSafe(owner)
                        }
                    }
                }
            )
        }
    }

    private fun ensureHostActivityResultHooked() {
        if (hostResultHooked) return
        hostResultHooked = true
        runCatching {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val req = (param.args.getOrNull(0) as? Int) ?: return
                        if (req != 0x0A0D20 && req != 0x0A0D21 && req != 0x0A0D22) return
                        val data = param.args.getOrNull(2) as? Intent ?: return
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                            val uri = data.data ?: return
                            runCatching {
                                val dctx = openDialog?.context
                                dctx?.contentResolver?.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                            }
                            if (req == 0x0A0D20) {
                                com.OKK.yes.core.hooks.DownloadRedirectHook.setPendingTreeUri(uri.toString())
                            } else if (req == 0x0A0D21) {
                                com.OKK.yes.core.hooks.FinderVideoDownloadHook.setPendingTreeUri(uri.toString())
                            } else if (req == 0x0A0D22) {
                                com.OKK.yes.core.hooks.plugins.JavaScriptEngine.setPendingImportUri(uri.toString())
                            }
                        }
                    }
                }
            )
        }
    }

    fun isShowing(): Boolean = openDialog?.isShowing == true

    /**
     * 打开新 Compose 设置界面（Dialog 载体）。
     * @param start 起始视图（兼容旧 EmbeddedSettingsUi.StartTarget 语义）
     */
    fun show(
        host: Activity,
        start: EmbeddedSettingsUi.StartTarget = EmbeddedSettingsUi.StartTarget.Features
    ) {
        ensureActivityTracker(host)
        val host = resolveValidHost(host) ?: return
        if (host.isFinishing || host.isDestroyed) return
        ensureHostActivityBackHooked()
        runCatching {
            openDialog?.dismiss()
            openDialog = null
            com.OKK.yes.core.hooks.PublicConfigStore.ensureDefaults(host)

            val owner = DialogComposeOwner(
                onFallbackBack = { runCatching { openDialog?.dismiss() } }
            )
            currentOwner = owner

            ensureHostActivityResultHooked()

            var hostCallbackCleanup: (() -> Unit)? = null
            var backInvokedCleanup: (() -> Unit)? = null
            val exiting = java.util.concurrent.atomic.AtomicBoolean(false)

            val dialog = object : Dialog(host, android.R.style.Theme_DeviceDefault_Light_NoActionBar) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) {
                            val imm = host.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            if (imm != null && imm.isAcceptingText) {
                                (currentFocus ?: host.currentFocus)?.windowToken?.let {
                                    imm.hideSoftInputFromWindow(it, 0)
                                }
                            } else {
                                dispatchBackSafe(owner)
                            }
                        }
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                override fun onBackPressed() {
                    dispatchBackSafe(owner)
                }

                override fun cancel() {
                    dispatchBackSafe(owner)
                }
            }.apply {
                setCancelable(true)
                setCanceledOnTouchOutside(false)
                val wm = window ?: return@apply
                wm.setBackgroundDrawableResource(android.R.color.transparent)
                wm.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // 全屏沉浸式 Edge-to-Edge：窗口充盈屏幕，透明状态栏与导航栏
                runCatching {
                    WindowCompat.setDecorFitsSystemWindows(wm, false)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    wm.statusBarColor = android.graphics.Color.TRANSPARENT
                    wm.navigationBarColor = android.graphics.Color.TRANSPARENT
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    wm.isNavigationBarContrastEnforced = false
                }
            }

            // 给 host Activity 绑定手势回调
            if (host is androidx.activity.OnBackPressedDispatcherOwner) {
                runCatching {
                    val cb = object : androidx.activity.OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            dispatchBackSafe(owner)
                        }
                    }
                    host.onBackPressedDispatcher.addCallback(cb)
                    hostCallbackCleanup = { runCatching { cb.remove() } }
                }
            }

            val root = ComposeView(host).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

                // ViewTree 五件套
                setViewTreeLifecycleOwnerReflect(this, owner)
                setViewTreeViewModelStoreOwnerReflect(this, owner)
                setViewTreeSavedStateRegistryOwnerReflect(this, owner)
                setViewTreeOnBackPressedDispatcherOwnerReflect(this, owner)

                setContent {
                    CompositionLocalProvider(
                        LocalOnBackPressedDispatcherOwner provides owner
                    ) {
                        // 深色状态提升为响应式：设置页手动切换夜间模式可即时生效
                        var dark by remember {
                            mutableStateOf(EmbeddedSettingsUi.isWeChatDark(host))
                        }
                        OKKTheme(darkTheme = dark) {
                            Box(Modifier.fillMaxSize()) {
                                OKKSettingsRoot(
                                    onExitRoot = {
                                        if (exiting.compareAndSet(false, true)) {
                                            dialog.dismiss()
                                        }
                                    },
                                    onDarkChange = { dark = it },
                                    initialTab = when (start) {
                                        EmbeddedSettingsUi.StartTarget.Home -> MainTab.Home
                                        EmbeddedSettingsUi.StartTarget.Diagnostics -> MainTab.Logs
                                        EmbeddedSettingsUi.StartTarget.Settings -> MainTab.Settings
                                        else -> MainTab.Features
                                    }
                                )
                            }
                        }
                    }
                }
            }

            dialog.setContentView(root)
            dialog.setOnDismissListener {
                hostCallbackCleanup?.invoke()
                hostCallbackCleanup = null
                backInvokedCleanup?.invoke()
                backInvokedCleanup = null
                openDialog = null
                currentOwner = null
                owner.destroy()
            }
            openDialog = dialog
            dialog.show()

            // API 33+：在 Dialog 窗口注册最高优先级系统返回回调，统一走 dispatchBackSafe 防抖。
            // 禁止再用 setOnBackInvokedDispatcher（其内部回调直接调 dispatcher，绕开防抖导致双消费）。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    val callback = object : android.window.OnBackInvokedCallback {
                        override fun onBackInvoked() {
                            val ownerNow = currentOwner
                            if (ownerNow != null) dispatchBackSafe(ownerNow)
                        }
                    }
                    dialog.window?.onBackInvokedDispatcher?.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                        callback
                    )
                    backInvokedCleanup = {
                        runCatching {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                dialog.window?.onBackInvokedDispatcher?.unregisterOnBackInvokedCallback(callback)
                            }
                        }
                    }
                }
            }
        }.onFailure { err ->
            try {
                val msg = err.message ?: ""
                android.util.Log.e("OKK-SettingsEntry", "OKKSettingsDialog FAIL: $err")
                XposedBridge.log("[OKK-SettingsEntry] OKKSettingsDialog FAIL: $err")

                // 若窗口未附加（Activity 正在渲染/从切页恢复），延迟 120ms 重试一次，避免直接弹加载失败
                if (msg.contains("not attached", ignoreCase = true) || msg.contains("attached to window", ignoreCase = true)) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        runCatching {
                            if (!host.isFinishing && !host.isDestroyed) {
                                show(host, start)
                            }
                        }
                    }, 120L)
                } else {
                    android.widget.Toast.makeText(host, "模块设置加载失败: ${err.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Dialog 环境下的完整 Owner 兜底。
     * 微信宿主 Activity 通常不是 androidx LifecycleOwner / OnBackPressedDispatcherOwner。
     */
    private class DialogComposeOwner(
        onFallbackBack: () -> Unit
    ) : LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner,
        OnBackPressedDispatcherOwner {

        private val fallback = onFallbackBack
        private val controller = SavedStateRegistryController.create(this)
        private val registry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val backDispatcher = OnBackPressedDispatcher { fallback() }

        init {
            // attach → restore → 生命周期前进（顺序不能乱）
            controller.performAttach()
            controller.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher

        fun destroy() {
            runCatching {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                store.clear()
            }
        }
    }

    private fun setViewTreeSavedStateRegistryOwnerReflect(view: View, owner: SavedStateRegistryOwner) {
        try {
            val cls = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            cls.getMethod("set", View::class.java, SavedStateRegistryOwner::class.java)
                .invoke(null, view, owner)
            logOk("ViewTreeSavedStateRegistryOwner")
        } catch (t: Throwable) {
            logFail("ViewTreeSavedStateRegistryOwner", t)
        }
    }

    private fun setViewTreeLifecycleOwnerReflect(view: View, owner: LifecycleOwner) {
        try {
            val cls = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            cls.getMethod("set", View::class.java, LifecycleOwner::class.java)
                .invoke(null, view, owner)
            logOk("ViewTreeLifecycleOwner")
        } catch (t: Throwable) {
            logFail("ViewTreeLifecycleOwner", t)
        }
    }

    private fun setViewTreeViewModelStoreOwnerReflect(view: View, owner: ViewModelStoreOwner) {
        try {
            val cls = Class.forName("androidx.lifecycle.ViewTreeViewModelStoreOwner")
            cls.getMethod("set", View::class.java, ViewModelStoreOwner::class.java)
                .invoke(null, view, owner)
            logOk("ViewTreeViewModelStoreOwner")
        } catch (t: Throwable) {
            logFail("ViewTreeViewModelStoreOwner", t)
        }
    }

    private fun setViewTreeOnBackPressedDispatcherOwnerReflect(
        view: View,
        owner: OnBackPressedDispatcherOwner
    ) {
        try {
            val cls = Class.forName("androidx.activity.ViewTreeOnBackPressedDispatcherOwner")
            cls.getMethod("set", View::class.java, OnBackPressedDispatcherOwner::class.java)
                .invoke(null, view, owner)
            logOk("ViewTreeOnBackPressedDispatcherOwner")
        } catch (t: Throwable) {
            logFail("ViewTreeOnBackPressedDispatcherOwner", t)
        }
    }

    private fun logOk(name: String) {
        runCatching {
            com.OKK.yes.core.hooks.ModuleLog.i("[OKKSettings] $name set OK")
            XposedBridge.log("[OKK-SettingsEntry] $name set OK")
        }
    }

    private fun logFail(name: String, t: Throwable) {
        runCatching {
            com.OKK.yes.core.hooks.ModuleLog.i("[OKKSettings] $name reflect fail: $t")
            XposedBridge.log("[OKK-SettingsEntry] $name reflect fail: $t")
        }
    }
}
