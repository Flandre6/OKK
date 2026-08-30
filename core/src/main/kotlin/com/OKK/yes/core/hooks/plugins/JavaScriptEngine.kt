package com.OKK.yes.core.hooks.plugins

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import bsh.BshMethod
import bsh.Interpreter
import bsh.NameSpace
import com.OKK.yes.core.compat.DexKitSupport
import com.OKK.yes.core.compat.WeChatSelfUser
import com.OKK.yes.core.hooks.PublicConfigStore
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

data class ActiveJavaPlugin(
    val plugin: JavaPlugin,
    val interpreter: Interpreter
)

data class ParsedScript(
    val name: String,
    val author: String,
    val description: String,
    val codeContent: String
)

object JavaScriptEngine {
    private const val TAG = "OKK-JavaPlugin"
    private const val DISABLED_FLAG = "disabled.flag"

    @Volatile
    private var currentTargetTalker: String = ""

    fun setTargetTalker(talker: String) {
        currentTargetTalker = talker
    }

    val activePlugins = ConcurrentHashMap<String, ActiveJavaPlugin>()
    private var cachedClassLoader: ClassLoader? = null

    private fun xlog(msg: String) {
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }

    fun getScriptDir(): File {
        val f = File("/sdcard/Android/media/com.tencent.mm/OKK/scripts")
        if (!f.exists()) f.mkdirs()
        return f
    }

    fun isScriptEnabled(pluginDir: File): Boolean {
        if (File(pluginDir, DISABLED_FLAG).exists()) return false
        if (File(pluginDir.parentFile, "${pluginDir.name}.disabled").exists()) return false
        return true
    }

    fun setScriptEnabled(id: String, enabled: Boolean): Boolean {
        val scriptDir = getScriptDir()
        val dir = File(scriptDir, id)
        if (dir.isDirectory) {
            val flag = File(dir, DISABLED_FLAG)
            return if (enabled) {
                if (flag.exists()) flag.delete() else true
            } else {
                runCatching { flag.createNewFile() }.getOrDefault(false)
            }
        }
        val javaFile = File(scriptDir, if (id.endsWith(".java") || id.endsWith(".bsh")) id else "$id.java")
        val disabledFile = File(scriptDir, "${javaFile.name}.disabled")
        return if (enabled) {
            if (disabledFile.exists()) {
                disabledFile.renameTo(javaFile)
            } else true
        } else {
            if (javaFile.exists()) {
                javaFile.renameTo(disabledFile)
            } else true
        }
    }

    const val KEY_SCRIPTS_ORDER = "java_scripts_order"
    const val REQ_SCRIPT_FOLDER = 0x0A0D22

    @Volatile
    private var pendingImportUriValue: String? = null

    fun setPendingImportUri(uri: String) {
        pendingImportUriValue = uri
    }

    fun takePendingImportUri(): String? {
        val v = pendingImportUriValue
        pendingImportUriValue = null
        return v
    }

    fun getScriptOrder(): List<String> {
        val raw = runCatching { PublicConfigStore.getString(KEY_SCRIPTS_ORDER, "") }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveScriptOrder(order: List<String>) {
        runCatching {
            PublicConfigStore.put(KEY_SCRIPTS_ORDER, order.joinToString(","), true)
        }
    }

    /** 从代码直接安装在线脚本并生效 */
    fun installScriptCode(
        id: String,
        name: String,
        author: String,
        version: String,
        code: String
    ): Boolean {
        return runCatching {
            val scriptDir = getScriptDir()
            val dir = File(scriptDir, id)
            if (!dir.exists()) dir.mkdirs()

            val propFile = File(dir, "info.prop")
            val propContent = "name=$name\nauthor=$author\nversion=$version\nupdateTime=${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}\n"
            propFile.writeText(propContent)

            val mainFile = File(dir, "main.java")
            mainFile.writeText(code)

            val order = getScriptOrder().toMutableList()
            if (!order.contains(id)) {
                order.add(id)
                saveScriptOrder(order)
            }

            reloadAll()
            true
        }.getOrDefault(false)
    }

    fun moveScript(id: String, direction: Int): Boolean {
        val all = listAllScriptEntries().map { it.id }.toMutableList()
        val currentIndex = all.indexOf(id)
        if (currentIndex < 0) return false
        val targetIndex = currentIndex + direction
        if (targetIndex < 0 || targetIndex >= all.size) return false

        all.removeAt(currentIndex)
        all.add(targetIndex, id)
        saveScriptOrder(all)
        return true
    }

    /** 置顶插件 */
    fun pinScript(id: String): Boolean {
        val all = listAllScriptEntries().map { it.id }.toMutableList()
        val currentIndex = all.indexOf(id)
        if (currentIndex < 0) return false
        if (currentIndex == 0) return true // 已经第一位
        all.removeAt(currentIndex)
        all.add(0, id)
        saveScriptOrder(all)
        return true
    }

    /** 重命名插件显示名称 */
    fun renameScript(id: String, newName: String): Boolean {
        return runCatching {
            val scriptDir = getScriptDir()
            val dir = File(scriptDir, id)
            if (!dir.exists() || !dir.isDirectory) return@runCatching false
            val propFile = File(dir, "info.prop")
            val props = Properties()
            if (propFile.exists()) {
                FileInputStream(propFile).use { props.load(it) }
            }
            props.setProperty("name", newName.trim())
            if (!props.containsKey("version")) props.setProperty("version", "1.0")
            if (!props.containsKey("author")) props.setProperty("author", "未知")
            FileOutputStream(propFile).use { props.store(it, "Plugin info") }
            true
        }.getOrDefault(false)
    }

    /** 导出插件为 ZIP 文件到 Download/OKK 目录 */
    fun exportScriptZip(context: Context, id: String): String? {
        return runCatching {
            val scriptDir = getScriptDir()
            val dir = File(scriptDir, id)
            if (!dir.exists() || !dir.isDirectory) return@runCatching null

            val exportDir = File("/storage/emulated/0/Download/OKK/exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val safeId = id.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
            val zipFile = File(exportDir, "${safeId}_export.zip")

            java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                dir.walkTopDown().forEach { file ->
                    val relPath = file.relativeTo(dir).path.replace('\\', '/')
                    if (relPath.isNotBlank()) {
                        if (file.isDirectory) {
                            zos.putNextEntry(java.util.zip.ZipEntry("$relPath/"))
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(java.util.zip.ZipEntry(relPath))
                            FileInputStream(file).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            zipFile.absolutePath
        }.getOrNull()
    }

    fun deleteScript(id: String): Boolean {
        val scriptDir = getScriptDir()
        var deleted = false
        val dir = File(scriptDir, id)
        if (dir.exists() && dir.isDirectory) {
            deleted = dir.deleteRecursively()
        }
        if (!deleted) {
            val possibleFiles = listOf(
                File(scriptDir, id),
                File(scriptDir, "$id.java"),
                File(scriptDir, "$id.bsh"),
                File(scriptDir, "$id.disabled"),
                File(scriptDir, "$id.java.disabled"),
                File(scriptDir, "$id.bsh.disabled")
            )
            for (f in possibleFiles) {
                if (f.exists()) {
                    deleted = f.delete() || deleted
                }
            }
        }
        if (deleted) {
            activePlugins.remove(id)?.let { active ->
                runCatching {
                    active.interpreter.nameSpace.getMethod("onUnload", emptyArray())?.invoke(emptyArray(), active.interpreter)
                }
            }
            val currentOrder = getScriptOrder().toMutableList()
            if (currentOrder.remove(id)) {
                saveScriptOrder(currentOrder)
            }
        }
        return deleted
    }

    fun importScriptFolder(context: Context, treeUri: Uri): Pair<Boolean, String> {
        return runCatching {
            var name = ""
            runCatching {
                context.contentResolver.query(treeUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = cursor.getString(idx) ?: ""
                    }
                }
            }
            if (name.endsWith(".zip", ignoreCase = true) || name.endsWith(".java", ignoreCase = true) || name.endsWith(".bsh", ignoreCase = true)) {
                return importScriptFile(context, treeUri)
            }

            val singleDoc = DocumentFile.fromSingleUri(context, treeUri)
            if (singleDoc != null && singleDoc.isFile) {
                return importScriptFile(context, treeUri)
            }

            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: singleDoc
                ?: return Pair(false, "无法打开所选文件夹")

            if (rootDoc.isFile) {
                return importScriptFile(context, treeUri)
            }

            val folderName = rootDoc.name?.takeIf { it.isNotBlank() } ?: "plugin_${System.currentTimeMillis()}"
            val scriptDir = getScriptDir()
            val targetDir = File(scriptDir, folderName)
            if (!targetDir.exists()) targetDir.mkdirs()

            fun copyTree(doc: DocumentFile, dest: File) {
                for (child in doc.listFiles()) {
                    val name = child.name ?: continue
                    if (child.isDirectory) {
                        val subDest = File(dest, name)
                        if (!subDest.exists()) subDest.mkdirs()
                        copyTree(child, subDest)
                    } else if (child.isFile) {
                        val destFile = File(dest, name)
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }

            copyTree(rootDoc, targetDir)

            // 如果导入的目录下有子文件夹且仅有一个子文件夹包含 main.java，做兼容处理
            val subFiles = targetDir.listFiles()?.toList().orEmpty()
            val singleDir = subFiles.firstOrNull { it.isDirectory }
            if (subFiles.size == 1 && singleDir != null) {
                val hasMain = File(singleDir, "main.java").exists() || File(singleDir, "main.bsh").exists()
                if (hasMain) {
                    singleDir.listFiles()?.forEach { f ->
                        f.renameTo(File(targetDir, f.name))
                    }
                    singleDir.delete()
                }
            }

            // 添加到脚本排序列表末尾
            val order = getScriptOrder().toMutableList()
            if (!order.contains(folderName)) {
                order.add(folderName)
                saveScriptOrder(order)
            }

            reloadAll()
            Pair(true, folderName)
        }.getOrElse { e ->
            xlog("Failed to import script folder: ${e.message}")
            Pair(false, e.message ?: "导入失败")
        }
    }

    /** 从 URI 解析 ZIP 压缩包或目录提取脚本配置与代码 */
    fun parseScriptFromUri(context: Context, uri: Uri): ParsedScript? {
        return runCatching {
            var name = ""
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = cursor.getString(idx) ?: ""
                    }
                }
            }
            if (name.isBlank()) {
                val singleDoc = DocumentFile.fromSingleUri(context, uri)
                val treeDoc = DocumentFile.fromTreeUri(context, uri)
                name = singleDoc?.name ?: treeDoc?.name ?: "script_${System.currentTimeMillis()}"
            }

            var scriptName = name.substringBeforeLast(".")
            var scriptAuthor = "社区开发者"
            var scriptDesc = "从 ZIP / 目录解析提取"
            var scriptCode = ""

            if (name.endsWith(".zip", ignoreCase = true)) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(stream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val entryName = entry.name.lowercase()
                            if (!entry.isDirectory) {
                                if (entryName.endsWith("info.prop")) {
                                    val text = zis.bufferedReader().readText()
                                    val info = JavaPlugin.parseInfoPropContent(text, scriptName)
                                    info.name.takeIf { it.isNotBlank() }?.let { scriptName = it }
                                    info.author?.takeIf { it.isNotBlank() }?.let { scriptAuthor = it }
                                    info.description?.takeIf { it.isNotBlank() }?.let { scriptDesc = it }
                                } else if (entryName.endsWith("main.java") || entryName.endsWith("main.bsh")) {
                                    scriptCode = zis.bufferedReader().readText()
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            } else if (name.endsWith(".java", ignoreCase = true) || name.endsWith(".bsh", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true)) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    scriptCode = input.bufferedReader().readText()
                }
            } else {
                val treeDoc = DocumentFile.fromTreeUri(context, uri)
                if (treeDoc != null && treeDoc.isDirectory) {
                    val infoFile = treeDoc.findFile("info.prop")
                    if (infoFile != null) {
                        context.contentResolver.openInputStream(infoFile.uri)?.use { input ->
                            val info = JavaPlugin.parseInfoPropContent(input.bufferedReader().readText(), scriptName)
                            info.name.takeIf { it.isNotBlank() }?.let { scriptName = it }
                            info.author?.takeIf { it.isNotBlank() }?.let { scriptAuthor = it }
                            info.description?.takeIf { it.isNotBlank() }?.let { scriptDesc = it }
                        }
                    }
                    val mainFile = treeDoc.findFile("main.java") ?: treeDoc.findFile("main.bsh")
                    if (mainFile != null) {
                        context.contentResolver.openInputStream(mainFile.uri)?.use { input ->
                            scriptCode = input.bufferedReader().readText()
                        }
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val text = input.bufferedReader().readText()
                        if (text.isNotBlank()) scriptCode = text
                    }
                }
            }

            if (scriptCode.isNotBlank()) {
                ParsedScript(
                    name = scriptName,
                    author = scriptAuthor,
                    description = scriptDesc,
                    codeContent = scriptCode
                )
            } else null
        }.getOrNull()
    }

    fun importScriptFile(context: Context, fileUri: Uri): Pair<Boolean, String> {
        return runCatching {
            val doc = DocumentFile.fromSingleUri(context, fileUri)
                ?: return Pair(false, "无法读取所选文件")
            val fileName = doc.name ?: "script_${System.currentTimeMillis()}.java"
            val scriptDir = getScriptDir()

            if (fileName.endsWith(".zip", ignoreCase = true)) {
                val baseName = fileName.substringBeforeLast(".")
                val targetDir = File(scriptDir, baseName)
                if (!targetDir.exists()) targetDir.mkdirs()

                context.contentResolver.openInputStream(fileUri)?.use { stream ->
                    ZipInputStream(stream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val entryFile = File(targetDir, entry.name)
                            if (entry.isDirectory) {
                                entryFile.mkdirs()
                            } else {
                                entryFile.parentFile?.mkdirs()
                                FileOutputStream(entryFile).use { out ->
                                    zis.copyTo(out)
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                // 兼容性合并：如果解压后包含多层嵌套文件夹，自动扁平化提取到根目录
                val subFiles = targetDir.listFiles()?.toList().orEmpty()
                val singleDir = subFiles.firstOrNull { it.isDirectory }
                if (subFiles.size == 1 && singleDir != null) {
                    val hasMain = File(singleDir, "main.java").exists() || File(singleDir, "main.bsh").exists()
                    if (hasMain) {
                        singleDir.listFiles()?.forEach { f ->
                            f.renameTo(File(targetDir, f.name))
                        }
                        singleDir.delete()
                    }
                } else if (subFiles.isEmpty()) {
                    // 空目录处理
                } else {
                    // 如果 ZIP 中有嵌套但被外层无关的多级空文件夹包裹，深度扁平化
                    var currentDir = targetDir
                    while (true) {
                        val children = currentDir.listFiles()?.toList().orEmpty()
                        if (children.size == 1 && children[0].isDirectory) {
                            currentDir = children[0]
                        } else {
                            break
                        }
                    }
                    if (currentDir != targetDir) {
                        val hasMain = File(currentDir, "main.java").exists() || File(currentDir, "main.bsh").exists()
                        if (hasMain) {
                            currentDir.listFiles()?.forEach { f ->
                                f.renameTo(File(targetDir, f.name))
                            }
                            // 递归清理空父级
                            var temp = currentDir
                            while (temp != targetDir) {
                                val parent = temp.parentFile
                                temp.delete()
                                if (parent == null || parent == targetDir) break
                                temp = parent
                            }
                        }
                    }
                }
                reloadAll()
                Pair(true, baseName)
            } else {
                val destFile = File(scriptDir, fileName)
                context.contentResolver.openInputStream(fileUri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                reloadAll()
                Pair(true, fileName)
            }
        }.getOrElse { e ->
            xlog("Failed to import script file: ${e.message}")
            Pair(false, e.message ?: "导入失败")
        }
    }

    fun listAllScriptEntries(): List<JavaPlugin> {
        val scriptDir = getScriptDir()
        val result = mutableListOf<JavaPlugin>()
        val files = scriptDir.listFiles() ?: return emptyList()

        for (file in files) {
            if (file.isDirectory) {
                val mainFile = File(file, "main.java").takeIf { it.exists() }
                    ?: File(file, "main.bsh").takeIf { it.exists() }
                if (mainFile != null) {
                    val info = JavaPlugin.parseInfoProp(File(file, "info.prop"))
                    val enabled = isScriptEnabled(file)
                    val content = runCatching { mainFile.readText() }.getOrDefault("")
                    result.add(
                        JavaPlugin(
                            id = file.name,
                            dir = file,
                            info = info,
                            content = content,
                            isEnabled = enabled
                        )
                    )
                }
            } else if (file.name.endsWith(".java") || file.name.endsWith(".bsh") || file.name.endsWith(".java.disabled") || file.name.endsWith(".bsh.disabled")) {
                val isExplicitDisabled = file.name.endsWith(".disabled")
                val cleanName = if (isExplicitDisabled) file.name.removeSuffix(".disabled") else file.name
                val baseName = cleanName.substringBeforeLast(".")
                val content = runCatching { file.readText() }.getOrDefault("")
                val info = JavaPluginInfo(
                    name = baseName,
                    description = "单文件脚本 ($cleanName)"
                )
                result.add(
                    JavaPlugin(
                        id = cleanName,
                        dir = scriptDir,
                        info = info,
                        content = content,
                        isEnabled = !isExplicitDisabled
                    )
                )
            }
        }

        // 根据自定义排序规则排序
        val order = getScriptOrder()
        if (order.isNotEmpty()) {
            val orderMap = order.mapIndexed { idx, id -> id to idx }.toMap()
            result.sortWith(compareBy(
                { orderMap[it.id] ?: Int.MAX_VALUE },
                { it.id }
            ))
        }

        return result
    }

    private fun loadConfig(plugin: JavaPlugin): Properties {
        val props = Properties()
        val cfgFile = File(plugin.dir, "config.properties")
        if (cfgFile.exists()) {
            runCatching {
                FileInputStream(cfgFile).use { props.load(it) }
            }
        }
        return props
    }

    private fun saveConfig(plugin: JavaPlugin, props: Properties) {
        val cfgFile = File(plugin.dir, "config.properties")
        runCatching {
            FileOutputStream(cfgFile).use { props.store(it, "OKK Java Plugin Storage") }
        }
    }

    private fun initNameSpace(bsh: Interpreter, plugin: JavaPlugin) {
        val ctx = DexKitSupport.appContext
        val cl = DexKitSupport.classLoader
        val myWxid = runCatching { cl?.let { WeChatSelfUser.resolve(it, ctx) } }.getOrDefault("") ?: ""
        val nameSpace = bsh.nameSpace

        nameSpace.apply {
            // ===== 环境变量注入 =====
            val cacheDirStr = ctx?.cacheDir?.absolutePath ?: (plugin.dir.absolutePath + "/cache")
            val verName = runCatching { ctx?.packageManager?.getPackageInfo(ctx.packageName, 0)?.versionName ?: "8.0.76" }.getOrDefault("8.0.76")
            val verCode = runCatching { ctx?.packageManager?.getPackageInfo(ctx.packageName, 0)?.longVersionCode?.toInt() ?: 2700 }.getOrDefault(2700)
            
            // Hchat / WA 全局变量对齐
            val scriptRootDir = plugin.dir.parentFile ?: File(cacheDirStr)
            setVariable("context", ctx, false)
            setVariable("hostContext", ctx, false)
            setVariable("classLoader", cl, false)
            setVariable("hostLoader", cl, false)
            setVariable("hostVerName", verName, false)
            setVariable("hostVerCode", verCode, false)
            setVariable("hostVerClient", "0x28004c34", false)
            setVariable("moduleVer", 1418, false)
            setVariable("myWxId", myWxid, false)
            setVariable("myWxid", myWxid, false)
            setVariable("cacheDir", cacheDirStr, false)
            setVariable("cacheDirFile", File(cacheDirStr), false)
            setVariable("okk", OkkScriptApi, false)

            setVariable("pluginPath", plugin.dir.absolutePath, false)
            setVariable("pluginDir", plugin.dir.absolutePath, false)
            setVariable("pluginDirFile", plugin.dir, false)
            setVariable("scriptDir", scriptRootDir.absolutePath, false)
            setVariable("scriptDirFile", scriptRootDir, false)
            setVariable("pluginId", plugin.id, false)
            setVariable("pluginName", plugin.info.name, false)
            setVariable("pluginAuthor", plugin.info.author ?: "", false)
            setVariable("pluginVersion", plugin.info.version ?: "1.0", false)
            setVariable("processName", "com.tencent.mm", false)
            setVariable("pluginProcess", "main", false)
            setVariable("isMainProcess", true, false)
            setVariable("isAppBrandProcess", false, false)
            setVariable("startedAt", System.currentTimeMillis(), false)
            

            
            // Auto-bind HchatCompatBridge methods to namespace
            setVariable("pluginUpdateTime", plugin.info.updateTime ?: "", false)

            setVariable("engineId", "OKK", false)
            setVariable("engineVerCode", 18, false)
            setVariable("engineVerName", "1.2.6", false)

            // Pre-import packages and classes for Hchat / WA compatibility
            nameSpace.importPackage("java.io")
            nameSpace.importPackage("java.util")
            nameSpace.importPackage("java.lang.reflect")
            nameSpace.importPackage("java.util.function")
            nameSpace.importPackage("android.view")
            nameSpace.importPackage("android.content")
            nameSpace.importPackage("android.database")
            nameSpace.importPackage("android.widget")
            nameSpace.importPackage("android.app")
            nameSpace.importPackage("com.OKK.yes.core.hooks.plugins")
            nameSpace.importPackage("de.robv.android.xposed")
            
            // Pre-inject Class objects (align with Hchat)
            setVariable("FieldClass", java.lang.reflect.Field::class.java, false)
            setVariable("MethodClass", java.lang.reflect.Method::class.java, false)
            setVariable("ConstructorClass", java.lang.reflect.Constructor::class.java, false)
            setVariable("XposedBridgeClass", de.robv.android.xposed.XposedBridge::class.java, false)
            setVariable("XposedHelpersClass", de.robv.android.xposed.XposedHelpers::class.java, false)
            setVariable("XC_MethodHookClass", de.robv.android.xposed.XC_MethodHook::class.java, false)

            // ===== BshMethod: 日志与 Toast =====
            setMethod(
                BshMethod("log", arrayOf(Any::class.java)) { args ->
                    OkkScriptApi.log("[${plugin.id}] ${args[0]}")
                }
            )
            setMethod(
                BshMethod("toast", arrayOf(String::class.java)) { args ->
                    Handler(Looper.getMainLooper()).post {
                        val msg = args[0] as? String ?: ""
                        ctx?.let { Toast.makeText(it, "${plugin.info.name}: $msg", Toast.LENGTH_SHORT).show() }
                    }
                }
            )
            setMethod(
                BshMethod("showToast", arrayOf(String::class.java)) { args ->
                    Handler(Looper.getMainLooper()).post {
                        val msg = args[0] as? String ?: ""
                        ctx?.let { Toast.makeText(it, "${plugin.info.name}: $msg", Toast.LENGTH_SHORT).show() }
                    }
                }
            )
            setMethod(
                BshMethod("notify", arrayOf(String::class.java, String::class.java)) { args ->
                    val title = args[0] as? String ?: ""
                    val content = args[1] as? String ?: ""
                    if (ctx != null) {
                        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        if (nm != null) {
                            val channelId = "script_${plugin.id}"
                            val channel = NotificationChannel(channelId, "OKK Script: ${plugin.info.name}", NotificationManager.IMPORTANCE_DEFAULT)
                            nm.createNotificationChannel(channel)
                            val notification = android.app.Notification.Builder(ctx, channelId)
                                .setContentTitle(title)
                                .setContentText(content)
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setAutoCancel(true)
                                .build()
                            nm.notify(channelId.hashCode(), notification)
                        }
                    }
                }
            )

            // ===== BshMethod: 动态脚本与类加载 =====
            setMethod(
                BshMethod("eval", arrayOf(String::class.java)) { args ->
                    val source = args[0] as String
                    bsh.eval(source)
                }
            )
            setMethod(
                BshMethod("loadJava", arrayOf(String::class.java)) { args ->
                    val pathName = args[0] as String
                    val file = File(plugin.dir, pathName)
                    if (file.exists()) {
                        bsh.source(file.absolutePath)
                    }
                }
            )

            // ===== BshMethod: 消息与微信能力 =====
            setMethod(
                BshMethod("sendText", arrayOf(String::class.java, String::class.java)) { args ->
                    val talker = args[0] as String
                    val text = args[1] as String
                    OkkScriptApi.sendText(talker, text)
                }
            )
            setMethod(
                BshMethod("sendImage", arrayOf(String::class.java, String::class.java)) { args ->
                    val talker = args[0] as String
                    val imgPath = args[1] as String
                    WeMessageSender.sendImage(talker, imgPath)
                }
            )
            setMethod(
                BshMethod("sendMediaMsg", arrayOf(String::class.java, Any::class.java, String::class.java)) { args ->
                    val talker = args[0] as String
                    val mediaMsg = args[1]
                    val appId = args[2] as? String
                    WeMessageSender.sendMediaMsg(talker, mediaMsg, appId)
                }
            )
            setMethod(
                BshMethod("getMyWxid", emptyArray()) {
                    myWxid
                }
            )
            setMethod(
                BshMethod("getLoginWxid", emptyArray()) {
                    myWxid
                }
            )
            setMethod(
                BshMethod("getTargetTalker", emptyArray()) {
                    currentTargetTalker
                }
            )
            setMethod(
                BshMethod("setTargetTalker", arrayOf(String::class.java)) { args ->
                    currentTargetTalker = args[0] as String
                }
            )
            setMethod(
                BshMethod("delay", arrayOf(java.lang.Long.TYPE, Runnable::class.java)) { args ->
                    val ms = args[0] as Long
                    val action = args[1] as Runnable
                    kotlin.concurrent.thread {
                        try {
                            Thread.sleep(ms)
                            action.run()
                        } catch (_: Throwable) {}
                    }
                }
            )


            setMethod(BshMethod("firstMethod", arrayOf(Any::class.java, String::class.java)) { args ->
                val target = args[0]
                val name = args[1] as String
                val clazz = (target as? Class<*>) ?: target.javaClass
                clazz.methods.firstOrNull { it.name == name }
            })
            // ===== BshMethod: 网络请求 (OkHttp 强力集成) =====
            // 同步 get(url, headers) -> String
            setMethod(
                BshMethod("get", arrayOf(String::class.java, Map::class.java)) { args ->
                    val url = args[0] as String
                    val headers = args[1] as? Map<*, *>
                    OkkScriptApi.httpGetSync(url, headers)
                }
            )
            // 异步 get(url, headers, callback)
            setMethod(
                BshMethod("get", arrayOf(String::class.java, Map::class.java, Any::class.java)) { args ->
                    val url = args[0] as String
                    val headers = args[1] as? Map<*, *>
                    val cb = args[2]
                    OkkScriptApi.httpGet(url, headers, cb)
                }
            )
            // 异步 download(url, savePath, headers, callback)
            // 异步 download(url, savePath, headers, callback)
            setMethod(
                BshMethod("download", arrayOf(String::class.java, String::class.java, Map::class.java, Any::class.java)) { args ->
                    val url = args[0] as String
                    val savePath = args[1] as String
                    val headers = args[2] as? Map<*, *>
                    val cb = args[3]
                    OkkScriptApi.downloadFile(url, savePath, headers, cb)
                }
            )
            // addToQueue(netScene) -> Boolean
            setMethod(BshMethod("addToQueue", arrayOf(Any::class.java)) { args ->
                val netScene = args[0]
                val queue = WeMessageSender.getNetSceneQueue()
                if (queue != null) {
                    return@BshMethod WeMessageSender.addNetSceneToQueue(queue, netScene)
                }
                return@BshMethod false
            })

            // sendNetScene(netScene) -> Boolean
            setMethod(BshMethod("sendNetScene", arrayOf(Any::class.java)) { args ->
                val netScene = args[0]
                val queue = WeMessageSender.getNetSceneQueue()
                if (queue != null) {
                    return@BshMethod WeMessageSender.addNetSceneToQueue(queue, netScene)
                }
                return@BshMethod false
            })
            // 同步 post(url, headers, body) -> String
            setMethod(
                BshMethod("post", arrayOf(String::class.java, Map::class.java, String::class.java)) { args ->
                    val url = args[0] as String
                    val headers = args[1] as? Map<*, *>
                    val body = args[2] as? String
                    OkkScriptApi.httpPostSync(url, headers, body)
                }
            )
            // 异步 post(url, headers, body, callback)
            setMethod(
                BshMethod("post", arrayOf(String::class.java, Map::class.java, String::class.java, Any::class.java)) { args ->
                    val url = args[0] as String
                    val headers = args[1] as? Map<*, *>
                    val body = args[2] as? String
                    val cb = args[3]
                    OkkScriptApi.httpPost(url, headers, body, cb)
                }
            )

            // insertSystemMsg(talker, content, time) -> insert a system message in chat
            setMethod(
                BshMethod("insertSystemMsg", arrayOf(String::class.java, String::class.java, java.lang.Long.TYPE)) { args ->
                    val talker = args[0] as String
                    val content = args[1] as String
                    val time = args[2] as Long
                    OkkScriptApi.insertSystemMsg(talker, content, time)
                }
            )

            // revokeMsg(msgId)
            setMethod(
                BshMethod("revokeMsg", arrayOf(java.lang.Long.TYPE)) { args ->
                    val msgId = args[0] as Long
                    OkkScriptApi.revokeMsg(msgId)
                }
            )

            // revokeMsgByMsgId(msgId) (WA Compat)
            setMethod(
                BshMethod("revokeMsgByMsgId", arrayOf(java.lang.Long.TYPE)) { args ->
                    val msgId = args[0] as Long
                    OkkScriptApi.revokeMsg(msgId)
                }
            )

            // sendQuoteMsg(talker, msgId, content) -> Boolean
            setMethod(
                BshMethod("sendQuoteMsg", arrayOf(String::class.java, java.lang.Long.TYPE, String::class.java)) { args ->
                    val talker = args[0] as String
                    val msgId = args[1] as Long
                    val content = args[2] as String
                    OkkScriptApi.sendQuoteMsg(talker, content, null, msgId)
                }
            )

            // sendEmoji(talker, md5) -> Boolean
            setMethod(
                BshMethod("sendEmoji", arrayOf(String::class.java, String::class.java)) { args ->
                    val talker = args[0] as String
                    val md5 = args[1] as String
                    OkkScriptApi.sendEmoji(talker, md5)
                }
            )

            // sendPat(chatroom, talker) -> Boolean
            setMethod(
                BshMethod("sendPat", arrayOf(String::class.java, String::class.java)) { args ->
                    val chatroom = args[0] as String
                    val talker = args[1] as String
                    OkkScriptApi.sendPat(talker, chatroom)
                }
            )

            // sendLocation(talker, locationObj) -> Boolean
            setMethod(
                BshMethod("sendLocation", arrayOf(String::class.java, Any::class.java)) { args ->
                    val talker = args[0] as String
                    val loc = args[1]
                    OkkScriptApi.sendLocation(talker, loc)
                }
            )

            // ===== BshMethod: OKK Java 脚本 API =====
            // getFriendList() -> List<OkkFriendInfo>
            setMethod(
                BshMethod("getFriendList", emptyArray()) {
                    OkkScriptApi.getFriendList()
                }
            )

            // ===== BshMethod: 配置存储 =====
            setMethod(
                BshMethod("getString", arrayOf(String::class.java, String::class.java)) { args ->
                    val key = args[0] as String
                    val def = args[1] as String
                    loadConfig(plugin).getProperty(key, def)
                }
            )
            setMethod(
                BshMethod("putString", arrayOf(String::class.java, String::class.java)) { args ->
                    val key = args[0] as String
                    val valStr = args[1] as String
                    val props = loadConfig(plugin)
                    props.setProperty(key, valStr)
                    saveConfig(plugin, props)
                }
            )
            setMethod(
                BshMethod("setString", arrayOf(String::class.java, String::class.java)) { args ->
                    val key = args[0] as String
                    val valStr = args[1] as String
                    val props = loadConfig(plugin)
                    props.setProperty(key, valStr)
                    saveConfig(plugin, props)
                }
            )
            setMethod(
                BshMethod("getBoolean", arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!)) { args ->
                    val key = args[0] as String
                    val def = args[1] as Boolean
                    loadConfig(plugin).getProperty(key)?.toBooleanStrictOrNull() ?: def
                }
            )
            setMethod(
                BshMethod("putBoolean", arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!)) { args ->
                    val key = args[0] as String
                    val valBool = args[1] as Boolean
                    val props = loadConfig(plugin)
                    props.setProperty(key, valBool.toString())
                    saveConfig(plugin, props)
                }
            )
            setMethod(
                BshMethod("setBoolean", arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!)) { args ->
                    val key = args[0] as String
                    val valBool = args[1] as Boolean
                    val props = loadConfig(plugin)
                    props.setProperty(key, valBool.toString())
                    saveConfig(plugin, props)
                }
            )
            setMethod(
                BshMethod("getInt", arrayOf(String::class.java, Int::class.javaPrimitiveType!!)) { args ->
                    val key = args[0] as String
                    val def = args[1] as Int
                    loadConfig(plugin).getProperty(key)?.toIntOrNull() ?: def
                }
            )
            setMethod(
                BshMethod("putInt", arrayOf(String::class.java, Int::class.javaPrimitiveType!!)) { args ->
                    val key = args[0] as String
                    val valInt = args[1] as Int
                    val props = loadConfig(plugin)
                    props.setProperty(key, valInt.toString())
                    saveConfig(plugin, props)
                }
            )
            setMethod(
                BshMethod("setInt", arrayOf(String::class.java, Int::class.javaPrimitiveType!!)) { args ->
                    val key = args[0] as String
                    val valInt = args[1] as Int
                    val props = loadConfig(plugin)
                    props.setProperty(key, valInt.toString())
                    saveConfig(plugin, props)
                }
            )
        }
    }

    fun reloadAll(classLoader: ClassLoader? = null) {
        val hostCl = classLoader ?: cachedClassLoader ?: return
        cachedClassLoader = hostCl

        val moduleCl = JavaScriptEngine::class.java.classLoader ?: ClassLoader.getSystemClassLoader()

        // 动态获取当前环境真实的 Xposed 包名（LSPosed 为了安全可能重命名了包名）
        val xposedRealName = de.robv.android.xposed.XC_MethodHook::class.java.name
        val xposedRealPackage = xposedRealName.substringBeforeLast(".XC_MethodHook")
        xlog("[CombinedCl] Detected real Xposed package name: $xposedRealPackage")

        // HybridClassLoader: Module 优先（Xposed/bsh/okhttp/fastjson 等），兜底走宿主 WeChat
        // 并在运行时动态把脚本里的 de.robv.android.xposed 重映射到当前真实的混淆包名
        val combinedCl = object : ClassLoader(hostCl) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                var targetName = name
                if (name.startsWith("de.robv.android.xposed.")) {
                    targetName = name.replace("de.robv.android.xposed", xposedRealPackage)
                }

                // 强制从 moduleCl/编译依赖包 解析以下包
                val forceModule = targetName.startsWith(xposedRealPackage) ||
                    targetName.startsWith("bsh.") ||
                    targetName.startsWith("com.OKK.yes.") ||
                    targetName.startsWith("okhttp3.") ||
                    targetName.startsWith("okio.") ||
                    targetName.startsWith("com.alibaba.fastjson2.")
                if (forceModule) {
                    try {
                        val c = moduleCl.loadClass(targetName)
                        if (c != null) return c
                    } catch (_: Throwable) {}
                }
                // 先尝试宿主（WeChat），再 fallback 到 moduleCl
                return try {
                    super.loadClass(targetName, resolve)
                } catch (e: ClassNotFoundException) {
                    try {
                        moduleCl.loadClass(targetName)
                    } catch (_: Throwable) {
                        throw e
                    }
                }
            }

            override fun findClass(name: String): Class<*> {
                var targetName = name
                if (name.startsWith("de.robv.android.xposed.")) {
                    targetName = name.replace("de.robv.android.xposed", xposedRealPackage)
                }
                return try {
                    moduleCl.loadClass(targetName)
                } catch (e: ClassNotFoundException) {
                    super.findClass(targetName)
                }
            }
        }

        executeAllOnUnload()
        activePlugins.clear()

        val allEntries = listAllScriptEntries()
        for (plugin in allEntries) {
            if (!plugin.isEnabled) {
                xlog("Skipping disabled script: ${plugin.id}")
                continue
            }
            if (plugin.content.isBlank()) continue

            runCatching {
                val bsh = Interpreter()
                bsh.classManager.setClassLoader(combinedCl)
                bsh.classManager.addClassLoader(combinedCl)
                
                initNameSpace(bsh, plugin)

                bsh.eval(plugin.content)

                bsh.nameSpace.getMethod("onLoad", emptyArray())?.invoke(emptyArray(), bsh)

                activePlugins[plugin.id] = ActiveJavaPlugin(plugin, bsh)
                xlog("Loaded Java script: [${plugin.id}] - ${plugin.info.name}")
            }.onFailure { e ->
                xlog("Failed to load script [${plugin.id}]: ${e.message}\n${e.stackTraceToString()}")
            }
        }
    }

    fun openScriptSettings(scriptId: String): Boolean {
        xlog("[openScriptSettings] requested for scriptId: $scriptId, activePlugins count: ${activePlugins.size}")
        val active = activePlugins[scriptId]
        if (active == null) {
            xlog("[openScriptSettings] script $scriptId not found in activePlugins (keys: ${activePlugins.keys})")
            return false
        }
        return runCatching {
            val ns = active.interpreter.nameSpace
            val m = ns.getMethod("openSettings", emptyArray())
                ?: ns.getMethod("showSettings", emptyArray())
                ?: ns.getMethod("onSettings", emptyArray())
                ?: ns.getMethod("showConsole", emptyArray())
                ?: ns.getMethod("showJayConsole", emptyArray())
            if (m != null) {
                xlog("[openScriptSettings] Invoking settings method: ${m.name} for $scriptId")
                m.invoke(emptyArray(), active.interpreter)
                true
            } else {
                xlog("[openScriptSettings] No settings method found in script $scriptId")
                false
            }
        }.onFailure { e ->
            xlog("[openScriptSettings] Error invoking settings for $scriptId: ${e.message}\n${e.stackTraceToString()}")
        }.getOrDefault(false)
    }

    fun executeAllOnUnload() {
        activePlugins.values.forEach { active ->
            runCatching {
                active.interpreter.nameSpace.getMethod("onUnload", emptyArray())?.invoke(emptyArray(), active.interpreter)
                xlog("onUnload executed for script ${active.plugin.id}")
            }.onFailure { e ->
                xlog("onUnload failed for script ${active.plugin.id}: ${e.message}")
            }
        }
    }

    fun executeAllOnHandleMsg(msgWrapper: MsgInfoWrapper) {
        activePlugins.values.forEach { active ->
            runCatching {
                val ns = active.interpreter.nameSpace
                // 兜底：支持 onHandleMsg / onMessage / onMsg
                var bshMethod = ns.getMethod("onHandleMsg", arrayOf(Any::class.java))
                if (bshMethod == null) bshMethod = ns.getMethod("onHandleMsg", arrayOf(MsgInfoWrapper::class.java))
                if (bshMethod == null) bshMethod = ns.getMethod("onMessage", arrayOf(Any::class.java))
                if (bshMethod == null) bshMethod = ns.getMethod("onMessage", arrayOf(MsgInfoWrapper::class.java))
                if (bshMethod == null) bshMethod = ns.getMethod("onMsg", arrayOf(Any::class.java))
                if (bshMethod == null) bshMethod = ns.getMethod("onMsg", arrayOf(MsgInfoWrapper::class.java))

                if (bshMethod != null) {
                    bshMethod.invoke(arrayOf(msgWrapper), active.interpreter)
                    xlog("onHandleMsg executed successfully for [${active.plugin.id}]")
                } else {
                    xlog("onHandleMsg/onMessage/onMsg NOT found for [${active.plugin.id}]")
                }
            }.onFailure { e ->
                xlog("onHandleMsg error in [${active.plugin.id}]: ${e.message}\n${e.stackTraceToString()}")
            }
        }
    }

    fun executeAllOnClickSendBtn(text: String, talker: String = ""): Boolean {
        var intercepted = false
        activePlugins.values.forEach { active ->
            runCatching {
                val ns = active.interpreter.nameSpace
                var bshMethod = ns.getMethod("onClickSendBtn", arrayOf(String::class.java))
                var res: Any? = null
                if (bshMethod != null) {
                    res = bshMethod.invoke(arrayOf(text), active.interpreter)
                } else {
                    bshMethod = ns.getMethod("onClickSendBtn", arrayOf(String::class.java, String::class.java))
                    if (bshMethod != null) {
                        res = bshMethod.invoke(arrayOf(talker, text), active.interpreter)
                    }
                }
                if (res == true) {
                    intercepted = true
                    xlog("onClickSendBtn intercepted by plugin: [${active.plugin.id}]")
                } else if (res?.toString() == "true") {
                    intercepted = true
                    xlog("onClickSendBtn intercepted by plugin (string match): [${active.plugin.id}]")
                }
            }.onFailure { e ->
                xlog("onClickSendBtn error in [${active.plugin.id}]: ${e.message}")
            }
        }
        return intercepted
    }



    fun createSampleScriptIfEmpty() {
        runCatching {
            val dir = getScriptDir()
            if ((dir.listFiles()?.size ?: 0) == 0) {
                val sampleFile = File(dir, "hello.java")
                val sampleCode = """
                    // OKK Java 插件示例脚本
                    void onLoad() {
                        okk.log("Hello OKK Java Plugin! 自身微信号: " + okk.getMyWxid());
                    }

                    void onHandleMsg(Object msg) {
                        // 收到新消息时触发
                    }

                    void onUnload() {
                        okk.log("Plugin unloaded.");
                    }
                """.trimIndent()
                sampleFile.writeText(sampleCode)
            }
        }
    }
}
