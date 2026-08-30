package com.OKK.yes.core.hooks

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 圆形头像 / 圆形弧度配置。
 *
 * 热路径优化（流畅度）：
 * - [load] 默认 **5s 内存缓存**，draw 不再每帧读盘
 * - 优先读 [ModulePrefs]（XSP 快照），失败再扫公共 properties
 * - [writePublic] 可异步写盘，且只写 1~2 个关键路径，不再 6 路同步 IO
 */
data class RoundAvatarOptions(
    val enabled: Boolean = false,
    /** 圆角比例：0.05 正方 → 0.36 方圆 → 0.50 圆形 */
    val radius: Float = 0.36f,
    val source: String = "default",
    val updated: Long = 0L
)

object RoundAvatarConfig {
    private val PUBLIC_FILE_NAME: String get() = com.OKK.yes.core.common.SecureStrings.d("wD9FxyhJwzVew3RL2jxC3DlzxztE0Ds=")
    private const val MEDIA_DIR = "/storage/emulated/0/Android/media/com.tencent.mm/OKK"

    const val KEY_ENABLED = "round_avatar_enabled"
    const val KEY_RADIUS = "round_avatar_radius"
    const val KEY_UPDATED = "round_avatar_updated"

    /** 用户确认最好看的「方圆」 */
    const val DEFAULT_RADIUS = 0.36f
    /** 范围 0.05~0.50 */
    const val MIN_RADIUS = 0.05f
    const val MAX_RADIUS = 0.50f
    /** 预设：正方 / 方圆 / 圆形 */
    const val PRESET_SQUARE = 0.05f
    const val PRESET_SOFT_CIRCLE = 0.36f
    const val PRESET_FULL_CIRCLE = 0.50f

    /** 配置缓存（仅读盘降频，不改公式） */
    private const val CACHE_TTL_MS = 1_500L

    @Volatile
    private var lastLoadTime = 0L

    @Volatile
    private var cached = RoundAvatarOptions()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "achat-config-io").apply { isDaemon = true }
    }

    private val writeSeq = AtomicInteger(0)

    fun load(now: Long = System.currentTimeMillis()): RoundAvatarOptions {
        if (now - lastLoadTime < CACHE_TTL_MS) return cached
        lastLoadTime = now
        cached = RoundAvatarOptions(
            enabled = PublicConfigStore.getBoolean(KEY_ENABLED, false),
            radius = clampRadius(
                PublicConfigStore.getString(KEY_RADIUS, DEFAULT_RADIUS.toString()).toFloatOrNull()
                    ?: DEFAULT_RADIUS
            ),
            source = "public",
            updated = now
        )
        return cached

        // 微信进程里 XSP 经常不可读：必须优先公共文件。
        // 性能优化时曾优先 ModulePrefs，其默认 enabled=false 会把整功能关掉。
        val pub = loadFromPublicFileFast()
        if (pub != null) {
            cached = pub
            return cached
        }

        val fromPrefs = loadFromModulePrefs()
        if (fromPrefs != null) {
            cached = fromPrefs
            return cached
        }

        return cached
    }

    /** 强制下次 load 重新读盘（安装/调试用） */
    fun invalidate() {
        lastLoadTime = 0L
        ModulePrefs.invalidate()
    }

    fun clampRadius(value: Float): Float = value.coerceIn(MIN_RADIUS, MAX_RADIUS)

    /**
     * 微信进程可读的公共路径（少而精）。
     * 写盘只打这些，避免设置页卡顿。
     */
    fun publicWriteTargets(context: Context? = null): List<File> {
        val list = ArrayList<File>(2)
        list += File(MEDIA_DIR, PUBLIC_FILE_NAME)
        // 应用专属备份（无存储权限也能成功）
        context?.getExternalFilesDir(null)?.let {
            list += File(it, PUBLIC_FILE_NAME)
        }
        return list.distinctBy { it.absolutePath }
    }

    fun publicConfigCandidates(context: Context? = null): List<File> = publicWriteTargets(context)

    fun publicConfigFile(): File = File(MEDIA_DIR, PUBLIC_FILE_NAME)

    /**
     * 设置页写入。
     * @param async true：后台线程写盘，立即返回（默认，避免 UI/微信卡顿）
     * @return 同步模式返回成功路径数；异步模式返回 1（表示已排队）或 0
     */
    fun writePublic(
        enabled: Boolean,
        radius: Float,
        context: Context? = null,
        async: Boolean = true
    ): Int {
        val r = clampRadius(radius)
        val ts = System.currentTimeMillis()
        val radiusText = String.format(java.util.Locale.US, "%.2f", r)
        val content = buildString {
            appendLine("# OKK config")
            appendLine("$KEY_ENABLED=$enabled")
            appendLine("$KEY_RADIUS=$radiusText")
            appendLine("$KEY_UPDATED=$ts")
        }

        // 立刻更新内存，热路径马上用新值
        cached = RoundAvatarOptions(
            enabled = enabled,
            radius = r,
            source = "write",
            updated = ts
        )
        lastLoadTime = System.currentTimeMillis()

        val targets = publicWriteTargets(context)
        if (targets.isEmpty()) return 0

        if (async) {
            val seq = writeSeq.incrementAndGet()
            // 尽量持有 context 的 applicationContext，避免泄漏 Activity
            val appCtx = context?.applicationContext
            ioExecutor.execute {
                // 只执行最新一次写入，中间快速连点可合并
                if (seq != writeSeq.get()) return@execute
                writeToTargets(targetsFor(appCtx), content, radiusText)
            }
            return 1
        }

        return writeToTargets(targets, content, radiusText)
    }

    private fun targetsFor(context: Context?): List<File> = publicWriteTargets(context)

    private fun writeToTargets(targets: List<File>, content: String, radiusText: String): Int {
        var ok = 0
        for (file in targets) {
            val written = runCatching {
                file.parentFile?.mkdirs()
                val merged = LinkedHashMap<String, String>()
                if (file.isFile) merged.putAll(parseKeyValues(file.readText(Charsets.UTF_8)))
                merged.putAll(parseKeyValues(content))
                val mergedText = buildString {
                    appendLine("# OKK config")
                    merged.forEach { (key, value) -> appendLine("$key=$value") }
                }
                file.writeText(mergedText, Charsets.UTF_8)
                // 不强制 chmod 全路径（慢且常失败）；系统侧 sdcard 已 world-readable
                val back = file.readText(Charsets.UTF_8)
                back.contains(KEY_RADIUS) && back.contains(radiusText)
            }.getOrDefault(false)
            if (written) ok++
        }
        return ok
    }

    fun debugSources(): String {
        val opt = load()
        return "cached=${opt.enabled}/${opt.radius}@${opt.source}"
    }

    private fun loadFromModulePrefs(): RoundAvatarOptions? {
        // XSP 读不到时 contains/get 都会走默认值，不能当成用户配置
        if (!ModulePrefs.isReadable()) return null
        if (!ModulePrefs.contains(KEY_ENABLED) && !ModulePrefs.contains(KEY_RADIUS)) {
            return null
        }
        return RoundAvatarOptions(
            enabled = ModulePrefs.getBoolean(KEY_ENABLED, false),
            radius = clampRadius(
                ModulePrefs.getString(KEY_RADIUS, DEFAULT_RADIUS.toString()).toFloatOrNull()
                    ?: DEFAULT_RADIUS
            ),
            source = "prefs",
            updated = System.currentTimeMillis()
        )
    }

    /** 只扫微信可读的媒体目录，避免无效 IO 和权限拒绝日志 */
    private fun loadFromPublicFileFast(): RoundAvatarOptions? {
        val files = listOf(
            File(MEDIA_DIR, PUBLIC_FILE_NAME)
        )
        var best: RoundAvatarOptions? = null
        var bestUpdated = -1L
        for (file in files) {
            if (!file.isFile) continue
            val opt = runCatching {
                val map = parseKeyValues(file.readText(Charsets.UTF_8))
                if (!map.containsKey(KEY_ENABLED) && !map.containsKey(KEY_RADIUS)) {
                    return@runCatching null
                }
                val updated = map[KEY_UPDATED]?.toLongOrNull() ?: file.lastModified()
                RoundAvatarOptions(
                    enabled = map[KEY_ENABLED].equals("true", ignoreCase = true),
                    radius = clampRadius(
                        map[KEY_RADIUS]?.toFloatOrNull() ?: DEFAULT_RADIUS
                    ),
                    source = "public:${file.parentFile?.name ?: "?"}",
                    updated = updated
                )
            }.getOrNull() ?: continue
            if (opt.updated >= bestUpdated) {
                bestUpdated = opt.updated
                best = opt
            }
        }
        return best
    }

    private fun parseKeyValues(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) return@forEach
            val i = t.indexOf('=')
            if (i <= 0) return@forEach
            map[t.substring(0, i).trim()] = t.substring(i + 1).trim()
        }
        return map
    }
}
