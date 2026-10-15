package com.OKK.yes.core.hooks

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.OKK.yes.core.compat.DexKitSupport
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 下载重定向：通过拦截微信保存到手机的入口方法 (ScopedStorageUtil.c)，
 * 并利用用户通过 SAF (Storage Access Framework) 授权的 Uri 进行复制。
 * 完美绕过 Android 11+ scoped storage 权限限制，实现任意目录保存。
 */
object DownloadRedirectHook {
    private const val TAG = "OKK-DownloadRedirect"
    const val KEY_ENABLED = "download_redirect_enabled"
    const val KEY_DIR = "download_redirect_dir"
    const val KEY_TREE_URI = "download_redirect_tree_uri"
    const val DEFAULT_DIR = "/storage/emulated/0/Android/media/com.tencent.mm/OKK/download"

    private val installed = AtomicBoolean(false)
    /** ScopedStorageUtil 入口是否 hook 成功（install 自检关键点） */
    private val scopedStorageHooked = AtomicBoolean(false)

    /** 深度自检只读访问器：保存入口是否已成功拦截。 */
    val isSaveEntryHooked: Boolean get() = scopedStorageHooked.get()

    /** 用户新选中的目录（点“保存”才落盘），由设置弹窗写入、OKKSettingsDialog 的 onActivityResult hook 缓存 */
    @Volatile
    private var pendingTreeUriValue: String? = null

    fun setPendingTreeUri(uri: String) {
        pendingTreeUriValue = uri
    }

    /** 取出并清空待保存目录；null 表示没有新选择 */
    fun takePendingTreeUri(): String? {
        val v = pendingTreeUriValue
        pendingTreeUriValue = null
        return v
    }

    fun install(context: Context, classLoader: ClassLoader, modulePath: String?) {
        if (!installed.compareAndSet(false, true)) return
        xlog("installing...")
        locateAndHookScopedStorageUtil(context, classLoader, modulePath)
        locateAndHookToastMsg(classLoader)
        // install 自检：关键入口（ScopedStorageUtil）必须 hook 到，否则保存重定向不可能生效
        com.OKK.yes.core.startup.FeatureHookRegistry.reportEffective(
            "DownloadRedirect",
            scopedStorageHooked.get(),
            if (scopedStorageHooked.get()) "保存入口已拦截" else "ScopedStorageUtil 入口未命中"
        )
    }

    // ── 1.1 替换微信保存成功后的 Toast 弹窗文本 ──
    private fun locateAndHookToastMsg(cl: ClassLoader) {
        // 1. 拦截 ExportFileUtil.m (常用的文件保存提示入口)
        runCatching {
            val clazz = Class.forName("com.tencent.mm.platformtools.ExportFileUtil", false, cl)
            val m = clazz.declaredMethods.firstOrNull { cand ->
                cand.name == "m" && cand.parameterCount == 2 &&
                    cand.parameterTypes[0] == Context::class.java &&
                    cand.parameterTypes[1] == String::class.java
            }
            if (m != null) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (!isEnabled()) return
                            val msg = param.args.getOrNull(1) as? String ?: return
                            val newMsg = rewriteToastMsg(msg)
                            if (newMsg != null) {
                                param.args[1] = newMsg
                                xlog("ExportFileUtil toast rewritten: $newMsg")
                            }
                        }
                    }
                })
                xlog("hooked ExportFileUtil.m")
            }
        }.onFailure { xlog("locate ExportFileUtil fail: ${it.message}") }

        // 2. 全局拦截 Android 原生 Toast.makeText，兜底替换所有包含 WeiXin 路径的提示
        runCatching {
            val toastClass = android.widget.Toast::class.java
            val makeTextMethods = toastClass.declaredMethods.filter { 
                it.name == "makeText" && it.parameterTypes.size >= 2 && 
                it.parameterTypes[1] == CharSequence::class.java 
            }
            
            for (m in makeTextMethods) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (!isEnabled()) return
                            val msg = param.args.getOrNull(1)?.toString() ?: return
                            val newMsg = rewriteToastMsg(msg)
                            if (newMsg != null) {
                                param.args[1] = newMsg
                                xlog("Toast.makeText rewritten: $newMsg")
                            }
                        }
                    }
                })
            }
            xlog("hooked Toast.makeText")
        }.onFailure { xlog("locate Toast.makeText fail: ${it.message}") }
    }

    private fun rewriteToastMsg(msg: String): String? {
        if (msg.isBlank()) return null
        // 1. 忽略模块设置、配置、对话框类 Toast
        if (msg.contains("设置") || msg.contains("配置") || msg.contains("快捷") || 
            msg.contains("分组") || msg.contains("模式") || msg.contains("开关") ||
            msg.contains("气泡") || msg.contains("贴图") || msg.contains("修改") ||
            msg.contains("本地")) {
            return null
        }
        // 2. 忽略失败提示
        if (msg.contains("失败") || msg.contains("fail", true)) {
            return null
        }
        // 3. 仅对图片、视频、文件保存或含微信路径的提示生效
        val isFileSaveToast = msg.contains("WeiXin", ignoreCase = true) ||
            msg.contains("WeChat", ignoreCase = true) ||
            msg.contains("MicroMsg", ignoreCase = true) ||
            msg.contains("Pictures", ignoreCase = true) ||
            msg.contains("DCIM", ignoreCase = true) ||
            msg.contains("相册") || msg.contains("图片") || msg.contains("视频") ||
            msg.contains("文件")

        if (isFileSaveToast && (msg.contains("保存") || msg.contains("下载") || msg.contains("Download"))) {
            val dir = saveDir()
            return "已保存到: $dir"
        }
        return null
    }

    // ── 1. ScopedStorageUtil：微信保存文件到公共目录的核心入口 ──
    // 8.0.76: u6.c(Context, src, dest, Uri, y6) -> boolean
    // 8.0.77: v6.c(Context, src, dest, Uri, z6) -> boolean
    private fun locateAndHookScopedStorageUtil(context: Context, cl: ClassLoader, modulePath: String?) {
        runCatching {
            var clazz: Class<*>? = null

            // 1. 优先匹配多版本候选类名 (8.0.77 Play 为 v6，8.0.76 为 u6)
            val candidateNames = listOf(
                "com.tencent.mm.sdk.platformtools.v6",
                "com.tencent.mm.sdk.platformtools.u6",
                "com.tencent.mm.sdk.platformtools.w6",
                "com.tencent.mm.sdk.platformtools.t6"
            )
            for (name in candidateNames) {
                runCatching {
                    val c = Class.forName(name, false, cl)
                    val hasMethod = c.declaredMethods.any { cand ->
                        cand.returnType == Boolean::class.javaPrimitiveType &&
                            cand.parameterCount >= 4 &&
                            cand.parameterTypes.getOrNull(0)?.name == "android.content.Context" &&
                            cand.parameterTypes.getOrNull(1)?.name == "java.lang.String" &&
                            cand.parameterTypes.getOrNull(2)?.name == "java.lang.String"
                    }
                    if (hasMethod) {
                        clazz = c
                    }
                }
                if (clazz != null) break
            }

            // 2. 候选类未命中时，通过 DexKit 按 ScopedStorageUtil 日志串动态搜索
            if (clazz == null) {
                clazz = DexKitSupport.findClassByStrings(
                    context,
                    cl,
                    modulePath,
                    "MicroMsg.ScopedStorageUtil"
                )
            }

            // 3. 动态探测平台工具类 a6 ~ z6
            if (clazz == null) {
                for (ch in 'a'..'z') {
                    val name = "com.tencent.mm.sdk.platformtools.${ch}6"
                    val c = runCatching { Class.forName(name, false, cl) }.getOrNull() ?: continue
                    val hasMethod = c.declaredMethods.any { cand ->
                        cand.returnType == Boolean::class.javaPrimitiveType &&
                            cand.parameterCount >= 4 &&
                            cand.parameterTypes.getOrNull(0)?.name == "android.content.Context" &&
                            cand.parameterTypes.getOrNull(1)?.name == "java.lang.String" &&
                            cand.parameterTypes.getOrNull(2)?.name == "java.lang.String"
                    }
                    if (hasMethod) {
                        clazz = c
                        break
                    }
                }
            }

            if (clazz == null) {
                xlog("ScopedStorageUtil class not found")
                return
            }

            val m = clazz.declaredMethods.firstOrNull { cand ->
                cand.returnType == Boolean::class.javaPrimitiveType &&
                    cand.parameterCount >= 4 &&
                    cand.parameterTypes.getOrNull(0)?.name == "android.content.Context" &&
                    cand.parameterTypes.getOrNull(1)?.name == "java.lang.String" &&
                    cand.parameterTypes.getOrNull(2)?.name == "java.lang.String"
            }
            if (m == null) {
                xlog("ScopedStorageUtil save method not found on ${clazz.name}")
                return
            }
            m.isAccessible = true
            scopedStorageHooked.set(true)

            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!isEnabled()) return

                        val ctx = param.args.getOrNull(0) as? Context ?: return
                        val src = param.args.getOrNull(1) as? String ?: return
                        val dest = param.args.getOrNull(2) as? String ?: return

                        val srcFile = File(src)
                        if (!srcFile.exists()) {
                            xlog("Source file does not exist: $src")
                            return
                        }

                        val fileName = File(dest).name
                        val treeUriStr = saveTreeUri()
                        var copySuccess = false

                        // A. 若用户配置了有效 SAF tree URI，优先走 SAF DocumentsContract
                        if (treeUriStr.isNotBlank() && treeUriStr.startsWith("content://")) {
                            runCatching {
                                val treeUri = Uri.parse(treeUriStr)
                                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                                val newFileUri = DocumentsContract.createDocument(
                                    ctx.contentResolver, 
                                    docUri, 
                                    "*/*", 
                                    fileName
                                )
                                if (newFileUri != null) {
                                    ctx.contentResolver.openOutputStream(newFileUri)?.use { outStream ->
                                        srcFile.inputStream().use { inStream ->
                                            inStream.copyTo(outStream)
                                        }
                                    }
                                    xlog("SAF copy successful! Saved to $newFileUri")
                                    copySuccess = true
                                }
                            }.onFailure { xlog("SAF copy failed: ${it.message}") }
                        }

                        // B. 若未配置 SAF 或 SAF 写入未成功，直接文件复制到指定目录（私有媒体目录直接可写）
                        if (!copySuccess) {
                            val targetDir = File(saveDir())
                            runCatching {
                                if (!targetDir.exists()) {
                                    targetDir.mkdirs()
                                }
                                val destFile = File(targetDir, fileName)
                                srcFile.copyTo(destFile, overwrite = true)
                                xlog("Direct copy successful! Saved to ${destFile.absolutePath}")
                                copySuccess = true
                            }.onFailure { xlog("Direct copy failed: ${it.message}") }
                        }

                        if (copySuccess) {
                            param.result = true // 拦截原方法，告知微信保存成功
                            runCatching {
                                com.OKK.yes.core.startup.RuntimeHealthAudit.noteActivity(
                                    "DownloadRedirect",
                                    "已重定向保存 $fileName"
                                )
                            }
                        }
                    }.onFailure { xlog("copy hook failed: ${it.stackTraceToString()}") }
                }
            })
            xlog("hooked ScopedStorageUtil method: ${clazz.name}.${m.name}")
        }.onFailure { xlog("locate ScopedStorageUtil fail: ${it.message}") }
    }

    // ── 配置 ─────────────────────────────────────────────────────────────────

    fun formatTreeUriToPath(uriStr: String): String {
        if (uriStr.isBlank()) return DEFAULT_DIR
        if (!uriStr.startsWith("content://")) return uriStr
        return runCatching {
            val uri = Uri.parse(uriStr)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val decoded = Uri.decode(docId)
            when {
                decoded.startsWith("primary:", ignoreCase = true) -> {
                    val rel = decoded.substring(8).trimStart('/')
                    if (rel.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$rel"
                }
                decoded.startsWith("raw:", ignoreCase = true) -> {
                    decoded.substring(4)
                }
                decoded.contains(":") -> {
                    val volume = decoded.substringBefore(":")
                    val rel = decoded.substringAfter(":").trimStart('/')
                    if (volume.matches(Regex("(?i)[A-F0-9]{4}-[A-F0-9]{4}"))) {
                        if (rel.isBlank()) "/storage/$volume" else "/storage/$volume/$rel"
                    } else if (rel.startsWith("storage/")) {
                        "/$rel"
                    } else {
                        if (rel.isBlank()) "/$volume" else "/storage/emulated/0/$rel"
                    }
                }
                else -> decoded
            }
        }.getOrElse {
            runCatching { Uri.decode(uriStr) }.getOrDefault(uriStr)
        }
    }

    fun isEnabled(): Boolean = runCatching {
        PublicConfigStore.getBoolean(KEY_ENABLED, false)
    }.getOrDefault(false)

    fun saveDir(): String = runCatching {
        PublicConfigStore.getString(KEY_DIR, DEFAULT_DIR)
    }.getOrDefault(DEFAULT_DIR).ifBlank { DEFAULT_DIR }

    fun saveTreeUri(): String = runCatching {
        PublicConfigStore.getString(KEY_TREE_URI, "")
    }.getOrDefault("")

    fun setEnabled(on: Boolean) {
        PublicConfigStore.putBoolean(KEY_ENABLED, on, false)
    }

    fun setSaveDir(dir: String) {
        val normalized = dir.trim().replace('\\', '/').trimEnd('/').ifBlank { DEFAULT_DIR }
        PublicConfigStore.putPersisted(KEY_DIR, normalized)
    }

    fun setSaveTreeUri(uri: String) {
        PublicConfigStore.putPersisted(KEY_TREE_URI, uri)
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
