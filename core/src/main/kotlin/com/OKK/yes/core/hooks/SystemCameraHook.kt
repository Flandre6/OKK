package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 直调系统相机 Hook：
 * 1. 拦截 WeChat 启动 MMRecordUI / RecordMediaUI / SightCaptureUI 的流程
 * 2. Hook MMRecordUI.onCreate 启动系统原生相机并返回
 * 3. Hook Activity.startActivityForResult 拦截启动拍摄界面并重定向至系统相机
 */
object SystemCameraHook {
    private const val TAG = "OKK-SystemCamera"
    private const val REQ_SYSTEM_CAMERA = 0x0A0C22
    private val installed = AtomicBoolean(false)

    fun isEnabled(): Boolean = PublicConfigStore.getBoolean("system_camera_enabled", false)

    /**
     * 判断 Intent 或 Context 是否属于编辑/裁剪/浏览已有图片或视频（而非真正的从零拍照）
     */
    private fun isEditMediaIntent(context: Any?, intent: Intent?): Boolean {
        val callerClassNames = mutableListOf<String>()
        if (context != null) {
            callerClassNames.add(context.javaClass.name)
            if (context is Activity) {
                context.callingActivity?.className?.let { callerClassNames.add(it) }
                context.intent?.component?.className?.let { callerClassNames.add(it) }
            }
        }

        val skipUiKeywords = listOf(
            "Album", "Preview", "Crop", "Gallery", "Sns", "Fav", "Select", "Picker",
            "Image", "Photo", "Media", "Viewer", "Detail", "Grid", "Editor", "Remux", "Draw"
        )

        for (name in callerClassNames) {
            if (skipUiKeywords.any { name.contains(it, ignoreCase = true) }) {
                xlog("isEditMediaIntent: skip by caller class $name")
                return true
            }
        }

        if (intent == null) return false

        val action = intent.action ?: ""
        if (action.contains("EDIT", ignoreCase = true) || action.contains("CROP", ignoreCase = true) || action.contains("CHOOSER", ignoreCase = true)) {
            xlog("isEditMediaIntent: skip by intent action $action")
            return true
        }

        if (intent.data != null || intent.type != null) {
            xlog("isEditMediaIntent: skip by intent data/type ${intent.data} / ${intent.type}")
            return true
        }

        val extras = runCatching { intent.extras }.getOrNull() ?: return false
        if (extras.isEmpty) return false

        val editKeywords = listOf(
            "photo", "image", "media", "picture", "pic", "file", "path",
            "data", "item", "crop", "edit", "preview", "thumb", "raw",
            "source", "uri", "url", "selected", "bitmap", "param"
        )

        for (key in extras.keySet()) {
            val lowerKey = key.lowercase()
            if (editKeywords.any { lowerKey.contains(it) }) {
                val value = extras.get(key) ?: continue
                when (value) {
                    is String -> if (value.isNotBlank()) {
                        xlog("isEditMediaIntent: skip by key $key = String($value)")
                        return true
                    }
                    is Boolean -> if (value) {
                        xlog("isEditMediaIntent: skip by key $key = Boolean(true)")
                        return true
                    }
                    is Number -> if (value.toLong() != 0L) {
                        xlog("isEditMediaIntent: skip by key $key = Number($value)")
                        return true
                    }
                    is Collection<*> -> if (value.isNotEmpty()) {
                        xlog("isEditMediaIntent: skip by key $key = Collection(size=${value.size})")
                        return true
                    }
                    is Bundle -> if (!value.isEmpty) {
                        xlog("isEditMediaIntent: skip by key $key = Bundle")
                        return true
                    }
                    else -> {
                        xlog("isEditMediaIntent: skip by key $key = ${value.javaClass.simpleName}")
                        return true
                    }
                }
            }
        }
        return false
    }

    fun install(context: Context, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        val recordUiNames = listOf(
            "com.tencent.mm.plugin.recordvideo.activity.MMRecordUI",
            "com.tencent.mm.plugin.recordvideo.ui.RecordMediaUI",
            "com.tencent.mm.plugin.mmsight.ui.SightCaptureUI"
        )

        // 1. Hook MMRecordUI 等界面的 onCreate：一进入就直接调起系统相机
        for (name in recordUiNames) {
            runCatching {
                val clazz = XposedHelpers.findClass(name, classLoader)
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "onCreate",
                    Bundle::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            val act = param.thisObject as? Activity ?: return
                            if (isEditMediaIntent(act, act.intent)) {
                                xlog("onCreate skip edit media intent on ${act.javaClass.simpleName}")
                                return
                            }
                            xlog("MMRecordUI onCreate -> redirect to system camera on ${act.javaClass.simpleName}")
                            launchSystemCamera(act)
                            act.finish()
                        }
                    }
                )
                xlog("hooked $name onCreate")
            }.onFailure {
                xlog("find $name fail: ${it.message}")
            }
        }

        // 2. Hook Activity.startActivityForResult 拦截启动 MMRecordUI
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        val intent = param.args[0] as? Intent ?: return
                        val compName = intent.component?.className ?: intent.action ?: ""
                        
                        val isRecordTarget = recordUiNames.any { compName.contains(it) } ||
                                compName.contains("MMRecordUI") ||
                                compName.contains("RecordMediaUI") ||
                                compName.contains("SightCaptureUI")

                        if (isRecordTarget) {
                            if (isEditMediaIntent(param.thisObject, intent)) {
                                xlog("startActivityForResult skip edit media intent target=$compName")
                                return
                            }
                            val act = param.thisObject as? Activity ?: return
                            val reqCode = param.args[1] as? Int ?: 0
                            xlog("startActivityForResult intercepted target=$compName reqCode=$reqCode")
                            
                            // 替换为系统相机 Intent
                            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                            param.args[0] = cameraIntent
                        }
                    }
                }
            )
            xlog("hooked Activity.startActivityForResult for system camera")
        }.onFailure { xlog("hook startActivityForResult fail: ${it.message}") }
    }

    fun launchSystemCamera(activity: Activity) {
        runCatching {
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            activity.startActivityForResult(cameraIntent, REQ_SYSTEM_CAMERA)
        }.onFailure {
            xlog("launchSystemCamera error: ${it.message}")
        }
    }

    private fun xlog(msg: String) {
        ModuleLog.d("$TAG: $msg")
    }
}
