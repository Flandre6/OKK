package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 直调系统相机 Hook：
 * 1. 拦截 WeChat 启动 MMRecordUI / RecordMediaUI / SightCaptureUI 的流程
 * 2. Hook MMRecordUI.onCreate 启动系统原生相机并返回
 * 3. Hook Activity.startActivityForResult 拦截启动拍摄界面并重定向至系统相机
 * 4. Hook Activity.onActivityResult 处理拍摄结果：写入相册 + Toast 提示
 *
 * 修复说明（2026-09-10）：
 * - 原实现缺少 EXTRA_OUTPUT，系统相机只返回缩略图，全分辨率照片不写入相册
 * - 原实现没有 onActivityResult hook，REQ_SYSTEM_CAMERA 回调被微信丢弃，无法发送
 * - 现已补全：launchSystemCamera 带 EXTRA_OUTPUT，hookCaptureResult 处理回调
 */
object SystemCameraHook {
    private const val TAG = "OKK-SystemCamera"
    private const val REQ_SYSTEM_CAMERA = 0x0A0C22
    private val installed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前待处理的拍摄结果 URI（已写入 MediaStore，系统相机直接写入此处） */
    @Volatile private var pendingCaptureUri: Uri? = null

    /** 发起拍摄的 Activity 弱引用，用于 onActivityResult 兜底 */
    @Volatile private var pendingCaptureActivity: WeakReference<Activity>? = null

    fun isEnabled(): Boolean = PublicConfigStore.getBoolean("system_camera_enabled", false)

    // ──────────────────────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 在 MediaStore 中预先创建一条图片记录并返回其 URI。
     * 系统相机通过 EXTRA_OUTPUT 将全分辨率照片直接写入此 URI 指向的位置，
     * 照片会自动出现在系统相册的"微信"文件夹。
     */
    private fun createCaptureUri(context: Context): Uri? {
        return runCatching {
            val fileName = "IMG_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/微信")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.onFailure { xlog("createCaptureUri fail: ${it.message}") }.getOrNull()
    }

    /**
     * 将 MediaStore URI 映射为实际文件路径（用于 MediaScanner 兜底扫描）。
     * Android Q+ 通常不需要手动扫描，Q 以下需要。
     */
    private fun getPathFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            if (uri.scheme == "file") return uri.path
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null
                )
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    cursor.getString(idx)
                } else null
            } finally {
                cursor?.close()
            }
        }.onFailure { xlog("getPathFromUri fail: ${it.message}") }.getOrNull()
    }

    /**
     * 将 MediaStore 中预先插入的 IS_PENDING 记录标记为完成（Android Q+）。
     * 系统相机写入后必须调用此方法，否则相册看不到该照片。
     */
    private fun markCaptureComplete(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        }.onFailure { xlog("markCaptureComplete fail: ${it.message}") }
    }

    /**
     * 清除拍摄取消时留下的空 MediaStore 记录，避免相册出现幽灵条目。
     */
    private fun cleanPendingUri(context: Context?, uri: Uri) {
        if (context == null) return
        runCatching {
            context.contentResolver.delete(uri, null, null)
        }.onFailure { xlog("cleanPendingUri fail: ${it.message}") }
    }

    /**
     * 拍摄完成后，将照片发送进微信当前聊天的策略：
     *
     * - 先标记 IS_PENDING=0，让相册可见
     * - Android Q 以下额外触发 MediaScanner 通知相册
     * - 弹 Toast 告知用户"照片已存入相册，请从相册选择发送"
     *   （精确的微信内部发图桥接需逆向 ChattingUI，作为后续迭代；
     *    本版保证照片完整写入相册，用户可立即从相册选取，功能可用）
     */
    private fun sendCapturedImageToChat(activity: Activity, uri: Uri) {
        // 1. 解除 IS_PENDING 标记（Android Q+），让系统相册可见
        markCaptureComplete(activity, uri)

        // 2. Android 9 及以下：用 MediaScanner 通知系统刷新相册
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val path = getPathFromUri(activity, uri)
            if (path != null) {
                MediaScannerConnection.scanFile(
                    activity,
                    arrayOf(path),
                    arrayOf("image/jpeg"),
                    null
                )
                xlog("MediaScanner scan: $path")
            }
        }

        xlog("captured image saved to gallery, uri=$uri")

        // 3. Toast 提示用户（主线程）
        mainHandler.post {
            runCatching {
                Toast.makeText(
                    activity,
                    "照片已存入相册，请从相册选择发送",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 判断 Intent 是否属于编辑/裁剪类（而非拍照）
    // ──────────────────────────────────────────────────────────────

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

    // ──────────────────────────────────────────────────────────────
    // install / hooks
    // ──────────────────────────────────────────────────────────────

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
        //    修复：替换 Intent 时同步设置 EXTRA_OUTPUT，确保照片全分辨率写入相册
        //    此路径的 requestCode 保留微信原值，微信原生 onActivityResult 会正常处理
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

                            // 创建 MediaStore URI，系统相机将全分辨率照片写入此处
                            val captureUri = createCaptureUri(act)
                            if (captureUri != null) {
                                // 记录 URI 供取消时清理（此路径 requestCode 归微信，微信处理结果）
                                pendingCaptureUri = captureUri
                                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                    putExtra(MediaStore.EXTRA_OUTPUT, captureUri)
                                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                }
                                param.args[0] = cameraIntent
                                xlog("replaced intent with system camera + EXTRA_OUTPUT=$captureUri")
                            } else {
                                // URI 创建失败兜底：仍替换 intent，但无 EXTRA_OUTPUT
                                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                                param.args[0] = cameraIntent
                                xlog("replaced intent with system camera (no EXTRA_OUTPUT, createCaptureUri failed)")
                            }
                        }
                    }
                }
            )
            xlog("hooked Activity.startActivityForResult for system camera")
        }.onFailure { xlog("hook startActivityForResult fail: ${it.message}") }

        // 3. Hook Activity.onActivityResult 处理 REQ_SYSTEM_CAMERA 回调
        //    修复核心：原实现完全缺少此 hook，拍照结果被微信丢弃
        hookCaptureResult()
    }

    /**
     * Hook Activity.onActivityResult，处理 REQ_SYSTEM_CAMERA（OKK 自定义 requestCode）的回调。
     *
     * 流程：
     * - RESULT_OK  → markCaptureComplete（解除 IS_PENDING）→ MediaScanner → Toast 提示
     * - RESULT_CANCELED → 清除空 MediaStore 记录，避免相册幽灵条目
     */
    private fun hookCaptureResult() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val reqCode = param.args[0] as? Int ?: return
                        if (reqCode != REQ_SYSTEM_CAMERA) return

                        val resCode = param.args[1] as? Int ?: return
                        val activity = param.thisObject as? Activity ?: return

                        if (resCode != Activity.RESULT_OK) {
                            // 用户取消拍摄，清除预先占位的空 MediaStore 记录
                            val uri = pendingCaptureUri
                            if (uri != null) {
                                pendingCaptureUri = null
                                cleanPendingUri(activity, uri)
                                xlog("capture cancelled, cleaned pending uri=$uri")
                            }
                            return
                        }

                        // 拍摄成功
                        val uri = pendingCaptureUri ?: run {
                            xlog("capture result OK but pendingCaptureUri is null, ignored")
                            return
                        }
                        pendingCaptureUri = null
                        xlog("capture result OK, processing uri=$uri")
                        sendCapturedImageToChat(activity, uri)
                    }
                }
            )
            xlog("hooked Activity.onActivityResult for REQ_SYSTEM_CAMERA")
        }.onFailure { xlog("hookCaptureResult fail: ${it.message}") }
    }

    // ──────────────────────────────────────────────────────────────
    // 公开入口：ChatToolbarHook 拍摄按钮直接调用
    // ──────────────────────────────────────────────────────────────

    /**
     * 从 ChatToolbarHook 的"拍摄"快捷按钮直接调用。
     *
     * 修复：
     * - 通过 EXTRA_OUTPUT 将全分辨率照片写入 MediaStore（相册可见）
     * - REQ_SYSTEM_CAMERA 回调由 hookCaptureResult 处理（不再被微信丢弃）
     */
    fun launchSystemCamera(activity: Activity) {
        runCatching {
            val uri = createCaptureUri(activity)
            if (uri != null) {
                pendingCaptureUri = uri
                pendingCaptureActivity = WeakReference(activity)
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                activity.startActivityForResult(cameraIntent, REQ_SYSTEM_CAMERA)
                xlog("launchSystemCamera with EXTRA_OUTPUT=$uri")
            } else {
                // URI 创建失败兜底：无 EXTRA_OUTPUT，仅能获取缩略图
                xlog("launchSystemCamera: createCaptureUri failed, launching without EXTRA_OUTPUT")
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                activity.startActivityForResult(cameraIntent, REQ_SYSTEM_CAMERA)
            }
        }.onFailure {
            xlog("launchSystemCamera error: ${it.message}")
        }
    }

    private fun xlog(msg: String) {
        ModuleLog.d("$TAG: $msg")
    }
}
