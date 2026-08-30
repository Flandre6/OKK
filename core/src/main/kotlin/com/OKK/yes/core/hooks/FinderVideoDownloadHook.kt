package com.OKK.yes.core.hooks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import com.OKK.yes.core.compat.DexKitSupport
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.math.BigInteger
import java.net.URL
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频号下载：向微信「视频号分享菜单」注入「复制链接」与「下载」菜单项。
 *
 * 逆向参考：WeKit `WeShortVideosShareMenuApi` + `DownloadMedia`（GitHub Ujhhgtg/WeKit）。
 * 原理：
 *  1. DexKit 定位 `com.tencent.mm.plugin.finder.feed` 包下三条链路：
 *     - `onCreateMMMenu(ContextMenu)`（特征串 "pos is error " / "getCreateSecondMoreMenuListener: username="）
 *       → 向分享菜单追加自绘图标菜单项
 *     - `onMMMenuItemSelected(...)`（特征串 "[getMoreMenuItemSelectedListener] feed " / "button_speedplay"+"ref_eid"）
 *       → 点击分发，从 thisObject 取 BaseFinderFeed.feedObject，再 getMediaType()/getMediaList()
 *  2. mediaType==2 图片 / ==4 视频；mediaList 为 JSONArray（每一项 toJSON()）
 *  3. 视频：media_cdn_info.pcdn_url 存在则直接下载；否则 `url`+`url_token`+`decodeKey`，
 *     下载后用 BigInteger(decodeKey) 跑 Salsa20 风格流解密（前 128KB、8 字节异或块），落盘 mp4。
 *  4. 保存目录：/sdcard/Download/OKK（可配置 KEY_DIR）。
 */
object FinderVideoDownloadHook {
    private const val TAG = "OKK-FinderVideo"
    const val KEY_ENABLED = "finder_video_download_enabled"
    const val KEY_DIR = "finder_video_download_dir"
    const val DEFAULT_DIR = "/storage/emulated/0/Download/OKK"

    private const val MENU_COPY = 777004
    private const val MENU_DOWNLOAD = 777007
    const val REQ_PICK_DIR = 0x0A0D21

    private const val ENCRYPTED_PREFIX_SIZE = 128 * 1024
    private const val VIDEO_FORMAT_H265 = 2
    private const val VIDEO_FORMAT_H266 = 3

    private val installed = AtomicBoolean(false)
    /** install 自检：是否至少 hook 到一组菜单创建/选中方法 */
    private val menuCreateHooked = AtomicBoolean(false)
    private val menuSelectHooked = AtomicBoolean(false)

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

    private val io: ExecutorService = Executors.newCachedThreadPool()

    // ── 配置 ─────────────────────────────────────────────────────────────────

    fun isEnabled(): Boolean = runCatching {
        PublicConfigStore.getBoolean(KEY_ENABLED, false)
    }.getOrDefault(false)

    fun saveDir(): String = runCatching {
        PublicConfigStore.getString(KEY_DIR, DEFAULT_DIR)
    }.getOrDefault(DEFAULT_DIR).ifBlank { DEFAULT_DIR }

    fun setEnabled(on: Boolean) {
        PublicConfigStore.putBoolean(KEY_ENABLED, on, false)
    }

    fun setSaveDir(dir: String) {
        val normalized = dir.trim().replace('\\', '/').trimEnd('/').ifBlank { DEFAULT_DIR }
        PublicConfigStore.putPersisted(KEY_DIR, normalized)
    }

    // ── install ─────────────────────────────────────────────────────────────

    fun install(context: Context, classLoader: ClassLoader, modulePath: String?) {
        if (!installed.compareAndSet(false, true)) return
        cachedApp = context.applicationContext ?: context
        xlog("installing...")
        locateAndHookMenu(context, classLoader, modulePath)
        com.OKK.yes.core.startup.FeatureHookRegistry.reportEffective(
            "FinderVideoDownload",
            menuCreateHooked.get() && menuSelectHooked.get(),
            if (menuCreateHooked.get() && menuSelectHooked.get()) "视频号分享菜单已注入"
            else "菜单入口未命中（微信版本可能变化）"
        )
    }

    /**
     * 定位微信视频号分享菜单的创建/选中方法并注入。
     * 参考 WeKit 三组锚点，全部用 DexKitSupport.findMethodsByStrings 按特征串定位。
     */
    private fun locateAndHookMenu(context: Context, cl: ClassLoader, mp: String?) {
        val createCandidates = mutableListOf<Method>()
        val selectCandidates = mutableListOf<Method>()

        val stringGroups = listOf(
            listOf("pos is error "),
            listOf("getCreateSecondMoreMenuListener: username="),
            listOf("feed", "menu", "sheet", "holder", "KEY_FINDER_SELF_FLAG"),
            listOf("[getMoreMenuItemSelectedListener] feed "),
            listOf("getMoreMenuItemSelectedListener feed "),
            listOf("button_speedplay")
        )

        DexKitSupport.withBridge(context, cl, mp) { bridge ->
            for (strs in stringGroups) {
                runCatching {
                    val mths = bridge.findMethod {
                        matcher {
                            usingStrings(*strs.toTypedArray())
                        }
                    }
                    for (mData in mths) {
                        runCatching {
                            val m = mData.getMethodInstance(cl) ?: return@runCatching
                            val pTypes = m.parameterTypes
                            if (pTypes.any { ContextMenu::class.java.isAssignableFrom(it) }) {
                                createCandidates.add(m)
                            }
                            if (pTypes.any { MenuItem::class.java.isAssignableFrom(it) }) {
                                selectCandidates.add(m)
                            }
                        }
                    }
                }
            }
        }

        val distinctCreate = createCandidates.distinctBy { "${it.declaringClass.name}.${it.name}(${it.parameterTypes.joinToString { p -> p.name }})" }
        val distinctSelect = selectCandidates.distinctBy { "${it.declaringClass.name}.${it.name}(${it.parameterTypes.joinToString { p -> p.name }})" }

        var createHooked = 0
        for (m in distinctCreate) {
            if (hookCreateMenu(m)) createHooked++
        }

        var selectHooked = 0
        for (m in distinctSelect) {
            if (hookSelectMenu(m)) selectHooked++
        }

        menuCreateHooked.set(createHooked > 0)
        menuSelectHooked.set(selectHooked > 0)
        xlog("locateAndHookMenu done: create=$createHooked select=$selectHooked (from ${distinctCreate.size} / ${distinctSelect.size} candidates)")
    }

    private fun hookCreateMenu(m: Method): Boolean {
        return runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!isEnabled()) return
                        val menu = param.args.getOrNull(0) as? ContextMenu ?: return
                        addMenuItems(menu)
                    }
                }
            })
            xlog("hooked create menu ${m.declaringClass.name}.${m.name}")
            true
        }.onFailure { xlog("create menu hook fail: ${it.message}") }.getOrDefault(false)
    }

    private fun hookSelectMenu(m: Method): Boolean {
        return runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!isEnabled()) return
                        dispatchSelection(param)
                    }
                }
            })
            xlog("hooked select menu ${m.declaringClass.name}.${m.name}")
            true
        }.onFailure { xlog("select menu hook fail: ${it.message}") }.getOrDefault(false)
    }

    // ── 菜单 UI ─────────────────────────────────────────────────────────────

    private fun addMenuItems(menu: ContextMenu) {
        runCatching {
            val addWithIcon = menu.javaClass.methods.firstOrNull { cand ->
                cand.name == "add" && cand.parameterTypes.size == 3 &&
                    cand.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    cand.parameterTypes[1] == CharSequence::class.java &&
                    Drawable::class.java.isAssignableFrom(cand.parameterTypes[2])
            }
            if (addWithIcon != null) {
                addWithIcon.isAccessible = true
                addWithIcon.invoke(menu, MENU_COPY, "复制链接", iconLink())
                addWithIcon.invoke(menu, MENU_DOWNLOAD, "下载", iconDownload())
            } else {
                menu.add(0, MENU_COPY, 0, "复制链接")
                menu.add(0, MENU_DOWNLOAD, 0, "下载")
            }
        }.onFailure {
            runCatching {
                menu.add(0, MENU_COPY, 0, "复制链接")
                menu.add(0, MENU_DOWNLOAD, 0, "下载")
            }
        }
    }

    private fun dispatchSelection(param: XC_MethodHook.MethodHookParam) {
        val menuItem = (param.args.firstOrNull { it is MenuItem } as? MenuItem) ?: return
        val id = menuItem.itemId
        if (id != MENU_COPY && id != MENU_DOWNLOAD) return

        val feed = resolveFeed(param) ?: run {
            toast("未找到视频号数据")
            return
        }
        val feedObject = readFeedObject(feed) ?: run {
            toast("未找到视频号内容")
            return
        }
        val mediaType = readIntMethod(feedObject, "getMediaType") ?: return
        val mediaList = readMediaList(feedObject) ?: emptyList()

        if (mediaList.isEmpty()) {
            toast("未找到媒体数据")
            return
        }

        if (id == MENU_COPY) {
            copyMedia(mediaType, mediaList)
        } else {
            downloadMedia(mediaType, mediaList)
        }
        param.result = null
    }

    private fun resolveFeed(param: XC_MethodHook.MethodHookParam): Any? {
        param.args.firstOrNull { isBaseFinderFeed(it) }?.let { return it }
        return findBaseFinderFeed(param.thisObject)
    }

    private fun isBaseFinderFeed(obj: Any?): Boolean {
        if (obj == null) return false
        val name = obj.javaClass.name
        return name.contains("finder.model.BaseFinderFeed") ||
            name.contains("BaseFinderFeed") ||
            name.contains("finder.feed.model") ||
            name.contains("FinderItem")
    }

    // ── 数据读取（反射，弱定位）──────────────────────────────────────────────

    /** 从菜单宿主对象取 BaseFinderFeed 字段（含父类，类型匹配 com.tencent.mm.plugin.finder.model.BaseFinderFeed） */
    private fun findBaseFinderFeed(host: Any?): Any? {
        if (host == null) return null
        var cls: Class<*>? = host.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                val t = f.type.name
                if (t.contains("finder.model.BaseFinderFeed") ||
                    t.contains("BaseFinderFeed") ||
                    t.contains("finder.feed.model")
                ) {
                    runCatching {
                        f.isAccessible = true
                        return f.get(host)
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun readFeedObject(feed: Any): Any? {
        var cls: Class<*>? = feed.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (f.name != "feedObject") continue
                runCatching {
                    f.isAccessible = true
                    return f.get(feed)
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun readIntMethod(target: Any, name: String): Int? {
        return runCatching {
            var cls: Class<*>? = target.javaClass
            while (cls != null && cls != Any::class.java) {
                val m = cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
                if (m != null) {
                    m.isAccessible = true
                    return@runCatching (m.invoke(target) as? Number)?.toInt()
                }
                cls = cls.superclass
            }
            null
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun readMediaList(target: Any): List<JSONObject>? {
        return runCatching {
            var cls: Class<*>? = target.javaClass
            var listObj: Any? = null
            while (cls != null && cls != Any::class.java) {
                val m = cls.declaredMethods.firstOrNull {
                    it.name == "getMediaList" && it.parameterCount == 0
                }
                if (m != null) {
                    m.isAccessible = true
                    listObj = m.invoke(target)
                    break
                }
                cls = cls.superclass
            }
            listObj ?: return@runCatching null

            // List<FinderMedia>，每项 toJSON() -> JSONObject
            val rawItems = (listObj as? java.util.Collection<*>) ?: return@runCatching null
            rawItems.mapNotNull { item ->
                runCatching {
                    var itemCls: Class<*>? = item?.javaClass
                    var json: Any? = null
                    while (itemCls != null && itemCls != Any::class.java) {
                        val toJson = itemCls.declaredMethods.firstOrNull {
                            it.name == "toJSON" && it.parameterCount == 0
                        }
                        if (toJson != null) {
                            toJson.isAccessible = true
                            json = toJson.invoke(item)
                            break
                        }
                        itemCls = itemCls.superclass
                    }
                    (json as? JSONObject) ?: (json as? String)?.let { JSONObject(it) }
                }.getOrNull()
            }.filterNotNull()
        }.getOrNull()
    }

    // ── 复制链接 ────────────────────────────────────────────────────────────

    private fun copyMedia(mediaType: Int, mediaList: List<JSONObject>) {
        runCatching {
            val ctx = appContext()
            val clip = ctx?.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return
            when (mediaType) {
                2 -> {
                    val urls = mediaList.map { it.getString("url") + it.getString("url_token") }
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("finder", urls.joinToString("\n")))
                    toast("已复制链接")
                }
                4 -> {
                    val json = mediaList[0]
                    val clipItems = mutableListOf<Pair<String, String>>()
                    val duration = json.optInt("videoDuration")
                    val size = json.optLong("fileSize")
                    clipItems += "时长" to "%02d:%02d:%02d".format(duration / 3600, (duration % 3600) / 60, duration % 60)
                    clipItems += "大小" to formatBytesSize(size)
                    val cdn = json.optJSONObject("media_cdn_info")
                    if (cdn == null || !cdn.has("pcdn_url")) {
                        clipItems += "密链" to (json.getString("url") + json.getString("url_token"))
                        clipItems += "密钥" to json.getString("decodeKey")
                    } else {
                        clipItems += "链接" to cdn.getString("pcdn_url")
                    }
                    clip.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "finder",
                            clipItems.joinToString("\n") { "${it.first}: ${it.second}" }
                        )
                    )
                    toast("已复制链接")
                }
                else -> toast("未知的媒体类型，无法复制链接")
            }
        }
    }

    // ── 下载 ────────────────────────────────────────────────────────────────

    private fun downloadMedia(mediaType: Int, mediaList: List<JSONObject>) {
        io.execute {
            runCatching {
                when (mediaType) {
                    2 -> downloadImages(mediaList)
                    4 -> downloadVideo(mediaList[0])
                    else -> toast("未知的媒体类型，无法下载")
                }
            }
        }
    }

    private fun downloadImages(mediaList: List<JSONObject>) {
        val urls = mediaList.map { it.getString("url") + it.getString("url_token") }
        urls.forEachIndexed { index, fullUrl ->
            toast("开始下载第 ${index + 1} 张图片")
            runCatching {
                val fileName = "image_${System.currentTimeMillis()}.png"
                downloadFile(fullUrl, File(saveDir(), fileName))
                toast("已将图片下载到 ${saveDir()}/$fileName")
            }.onFailure {
                xlog("image download fail: ${it.message}")
                toast("第 ${index + 1} 张图片下载失败")
            }
        }
    }

    private fun downloadVideo(json: JSONObject) {
        val cdn = json.optJSONObject("media_cdn_info")
        if (cdn == null || !cdn.has("pcdn_url")) {
            val url = json.getString("url")
            val urlToken = json.getString("url_token")
            val decodeKey = json.getString("decodeKey")
            val fullUrl = preferCompatibleVideoFormat(url + urlToken)
            toast("开始下载并解密视频")
            runCatching {
                val dir = saveDir()
                val fileName = "video_${System.currentTimeMillis()}.mp4"
                val tmp = File(dir, "$fileName.tmp")
                downloadFile(fullUrl, tmp)
                toast("开始解密视频")
                decryptFile(tmp, File(dir, fileName), BigInteger(decodeKey))
                tmp.delete()
                toast("已将视频下载到 $dir/$fileName")
            }.onFailure {
                xlog("video download/decrypt fail: ${it.message}")
                toast("视频下载失败")
            }
        } else {
            val pcdnUrl = cdn.getString("pcdn_url")
            toast("开始下载视频")
            runCatching {
                val fileName = "video_${System.currentTimeMillis()}.mp4"
                downloadFile(pcdnUrl, File(saveDir(), fileName))
                toast("已将视频下载到 ${saveDir()}/$fileName")
            }.onFailure {
                xlog("pcdn video download fail: ${it.message}")
                toast("视频下载失败")
            }
        }
    }

    private fun preferCompatibleVideoFormat(url: String): String {
        return runCatching {
            val uri = android.net.Uri.parse(url)
            val baseData = uri.getQueryParameter("basedata") ?: return url
            val decoded = android.util.Base64.decode(
                baseData,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )

            if (decoded.size < 2 || decoded[0] != 0x08.toByte() || decoded[1].toInt() != VIDEO_FORMAT_H266) {
                return url
            }

            decoded[1] = VIDEO_FORMAT_H265.toByte()
            val replacement = android.util.Base64.encodeToString(
                decoded,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            val encodedQuery = uri.encodedQuery ?: return url
            val oldParameter = "basedata=${android.net.Uri.encode(baseData)}"
            val newParameter = "basedata=${android.net.Uri.encode(replacement)}"
            val newQuery = encodedQuery.replaceFirst(oldParameter, newParameter)

            if (newQuery == encodedQuery) url else uri.buildUpon()
                .encodedQuery(newQuery)
                .build()
                .toString()
        }.getOrElse {
            url
        }
    }

    private fun downloadFile(url: String, target: File) {
        runCatching { target.parentFile?.mkdirs() }
        URL(url).openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    // ── 解密（Salsa20 风格，仅解密前 128KB 头部，后续直接拷贝）───────────────────

    private fun decryptFile(original: File, newFile: File, key: BigInteger) {
        original.inputStream().use { fin ->
            newFile.outputStream().use { fout ->
                val encryptedPrefix = ByteArray(ENCRYPTED_PREFIX_SIZE)
                var prefixSize = 0
                while (prefixSize < encryptedPrefix.size) {
                    val read = fin.read(
                        encryptedPrefix,
                        prefixSize,
                        encryptedPrefix.size - prefixSize
                    )
                    if (read == -1) break
                    prefixSize += read
                }
                decryptBuffer(encryptedPrefix, key)
                fout.write(encryptedPrefix, 0, prefixSize)
                fin.copyTo(fout)
            }
        }
    }

    private fun decryptBuffer(buffer: ByteArray, key: BigInteger) {
        if (buffer.isEmpty() || buffer.size < 128 * 1024) return
        val crypto = CryptoState(key)
        val limit = 128 * 1024
        for (i in 0 until limit step 8) {
            val f = crypto.f
            val keyBlock = crypto.c[f]
            if (f == 0) {
                crypto.updateState()
                crypto.f = 255
            } else {
                crypto.f = f - 1
            }
            val keyBytes = ByteArray(8)
            for (j in 0 until 8) {
                val shifted = keyBlock.shiftRight(j * 8)
                val masked = shifted.and(BigInteger.valueOf(255))
                keyBytes[7 - j] = masked.toByteArray().lastOrNull() ?: 0
            }
            for (j in 0 until 8) {
                val idx = i + j
                if (idx >= limit) return
                buffer[idx] = (buffer[idx].toInt() xor keyBytes[j].toInt()).toByte()
            }
        }
    }

    private class CryptoState(bigInteger: BigInteger) {
        private val mask = BigInteger("ffffffffffffffff", 16)
        val b: Array<BigInteger> = Array(8) { BigInteger("9e3779b97f4a7c13", 16) }
        val c: Array<BigInteger> = Array(256) { BigInteger.ZERO }
        val d: Array<BigInteger> = Array(256) { BigInteger.ZERO }
        val e: Array<BigInteger> = Array(256) { BigInteger.ZERO }
        var f: Int = 255

        init {
            c[0] = bigInteger
            repeat(4) { mix(b) }
            var i6 = 0
            while (i6 < 256) {
                for (j in 0 until 8) b[j] = b[j].add(c[i6 + j]).and(mask)
                mix(b)
                for (j in 0 until 8) d[i6 + j] = b[j]
                i6 += 8
            }
            var i11 = 0
            while (i11 < 256) {
                for (j in 0 until 8) b[j] = b[j].add(d[i11 + j]).and(mask)
                mix(b)
                for (j in 0 until 8) d[i11 + j] = b[j]
                i11 += 8
            }
            updateState()
        }

        private fun mix(state: Array<BigInteger>) {
            state[0] = state[0].subtract(state[4]).and(mask)
            state[5] = state[5].xor(state[7].shiftRight(9)).and(mask)
            state[7] = state[7].add(state[0]).and(mask)
            state[1] = state[1].subtract(state[5]).and(mask)
            state[6] = state[6].xor(state[0].shiftLeft(9)).and(mask)
            state[0] = state[0].add(state[1]).and(mask)
            state[2] = state[2].subtract(state[6]).and(mask)
            state[7] = state[7].xor(state[1].shiftRight(23)).and(mask)
            state[1] = state[1].add(state[2]).and(mask)
            state[3] = state[3].subtract(state[7]).and(mask)
            state[0] = state[0].xor(state[2].shiftLeft(15)).and(mask)
            state[2] = state[2].add(state[3]).and(mask)
            state[4] = state[4].subtract(state[0]).and(mask)
            state[1] = state[1].xor(state[3].shiftRight(14)).and(mask)
            state[3] = state[3].add(state[4]).and(mask)
            state[5] = state[5].subtract(state[1]).and(mask)
            state[2] = state[2].xor(state[4].shiftLeft(20)).and(mask)
            state[4] = state[4].add(state[5]).and(mask)
            state[6] = state[6].subtract(state[2]).and(mask)
            state[3] = state[3].xor(state[5].shiftRight(17)).and(mask)
            state[5] = state[5].add(state[6]).and(mask)
            state[7] = state[7].subtract(state[3]).and(mask)
            state[4] = state[4].xor(state[6].shiftLeft(14)).and(mask)
            state[6] = state[6].add(state[7]).and(mask)
        }

        fun updateState() {
            e[2] = e[2].add(BigInteger.ONE).and(mask)
            e[1] = e[1].add(e[2]).and(mask)
            for (i in 0 until 256) {
                when (i % 4) {
                    0 -> e[0] = e[0].xor(e[0].shiftLeft(21)).not().and(mask)
                    1 -> e[0] = e[0].xor(e[0].shiftRight(5))
                    2 -> e[0] = e[0].xor(e[0].shiftLeft(12))
                    3 -> e[0] = e[0].xor(e[0].shiftRight(33))
                }
                e[0] = e[0].add(d[(i + 128) % 256]).and(mask)
                val di = d[i]
                val index1 = di.shiftRight(3).mod(BigInteger.valueOf(256)).toInt()
                val sum1 = d[index1].add(e[0])
                val s = sum1.add(e[1]).and(mask)
                d[i] = s
                val index2 = s.shiftRight(11).mod(BigInteger.valueOf(256)).toInt()
                e[1] = d[index2].add(di).and(mask)
                c[i] = e[1]
            }
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    @Volatile private var cachedApp: Context? = null
    private fun appContext(): Context? = cachedApp

    private fun toast(msg: String) {
        runCatching {
            val ctx = appContext() ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            } else {
                android.os.Handler(Looper.getMainLooper()).post {
                    runCatching { Toast.makeText(appContext(), msg, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun iconLink(): Drawable = simpleIcon(Color.parseColor("#07C160"), linkGlyph)
    private fun iconDownload(): Drawable = simpleIcon(Color.parseColor("#07C160"), downGlyph)

    private fun simpleIcon(color: Int, draw: (Paint, Canvas) -> Unit): Drawable {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }
        draw(paint, canvas)
        return BitmapDrawable(appContext()?.resources, bmp)
    }

    private val linkGlyph: (Paint, Canvas) -> Unit = { p, c ->
        val r = RectF(20f, 10f, 60f, 50f); c.drawRoundRect(r, 8f, 8f, p)
        val r2 = RectF(12f, 30f, 52f, 70f); c.drawRoundRect(r2, 8f, 8f, p)
    }

    private val downGlyph: (Paint, Canvas) -> Unit = { p, c ->
        c.drawLine(36f, 12f, 36f, 52f, p)
        c.drawLine(20f, 38f, 36f, 58f, p)
        c.drawLine(52f, 38f, 36f, 58f, p)
        c.drawLine(20f, 64f, 52f, 64f, p)
    }

    private fun formatBytesSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
        val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt()
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return "%.2f %s".format(value, units[digitGroups])
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}