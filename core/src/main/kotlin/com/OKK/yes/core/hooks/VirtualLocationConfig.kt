package com.OKK.yes.core.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 虚拟定位配置 + 地图选点请求/结果（公共文件，微信进程可读）。
 *
 * 路径：`/storage/emulated/0/Android/media/com.tencent.mm/OKK/`
 * - `achat_config.properties` 中 virtual_location_* 键
 * - `map_pick_request` 选点请求
 * - `map_pick_result` 选点结果
 *
 * 默认坐标：优先 [resolveDeviceLocation]（用户当前真实位置），拿不到时才用 [FALLBACK_*]。
 */
object VirtualLocationConfig {
    const val KEY_ENABLED = "virtual_location_enabled"
    const val KEY_LATITUDE = "virtual_location_latitude"
    const val KEY_LONGITUDE = "virtual_location_longitude"
    const val KEY_UPDATED = "virtual_location_updated"

    /** 仅当无法读取系统定位时的兜底（不再使用泰国帕安坐标） */
    const val FALLBACK_LATITUDE = 39.9042
    const val FALLBACK_LONGITUDE = 116.4074

    /** @deprecated 兼容旧测试名，等价于 FALLBACK */
    @Deprecated("use FALLBACK_LATITUDE / resolveDeviceLocation", ReplaceWith("FALLBACK_LATITUDE"))
    const val DEFAULT_LATITUDE = FALLBACK_LATITUDE

    @Deprecated("use FALLBACK_LONGITUDE / resolveDeviceLocation", ReplaceWith("FALLBACK_LONGITUDE"))
    const val DEFAULT_LONGITUDE = FALLBACK_LONGITUDE

    /** 历史误用默认（泰国一带），视为「未设置」 */
    private const val LEGACY_LAT = 16.61953
    private const val LEGACY_LON = 98.56146

    private const val PUBLIC_DIR = "/storage/emulated/0/Android/media/com.tencent.mm/OKK"
    private const val PUBLIC_FILE = "achat_config.properties"
    private const val REQUEST_FILE = "map_pick_request"
    private const val RESULT_FILE = "map_pick_result"

    @Volatile
    private var lastLoad = 0L

    @Volatile
    private var cachedEnabled = false

    @Volatile
    private var cachedLat = FALLBACK_LATITUDE

    @Volatile
    private var cachedLon = FALLBACK_LONGITUDE

    private val writeSeq = AtomicInteger(0)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "achat-vloc-io").apply { isDaemon = true }
    }

    fun load(now: Long = System.currentTimeMillis()): Triple<Boolean, Double, Double> {
        if (now - lastLoad < 3_000L) {
            return Triple(cachedEnabled, cachedLat, cachedLon)
        }
        lastLoad = now

        // 1) 公共文件优先（微信侧 XSP 常读不到）
        loadFromPublic()?.let {
            cachedEnabled = it.first
            cachedLat = it.second
            cachedLon = it.third
            return Triple(cachedEnabled, cachedLat, cachedLon)
        }

        cachedEnabled = PublicConfigStore.getBoolean(KEY_ENABLED, false)
        val latRaw = PublicConfigStore.getString(KEY_LATITUDE, "")
        val lonRaw = PublicConfigStore.getString(KEY_LONGITUDE, "")
        val la = latRaw.toDoubleOrNull()?.coerceIn(-90.0, 90.0)
        val lo = lonRaw.toDoubleOrNull()?.coerceIn(-180.0, 180.0)
        if (la != null && lo != null && !isLegacyUnset(la, lo)) {
            cachedLat = la
            cachedLon = lo
        } else {
            // 未配置或旧默认：用兜底；UI 层会再刷成设备定位
            cachedLat = FALLBACK_LATITUDE
            cachedLon = FALLBACK_LONGITUDE
        }
        return Triple(cachedEnabled, cachedLat, cachedLon)
    }

    fun isEnabled(): Boolean = load().first
    fun latitude(): Double = load().second
    fun longitude(): Double = load().third

    fun invalidate() {
        lastLoad = 0L
    }

    fun isLegacyUnset(lat: Double, lon: Double): Boolean {
        return (kotlin.math.abs(lat - LEGACY_LAT) < 1e-5 && kotlin.math.abs(lon - LEGACY_LON) < 1e-5)
    }

    fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)

    /**
     * 读系统最近一次定位（GPS / 网络 / 被动）。
     * 微信进程通常已有定位权限；模块进程可能无权限则返回 null。
     */
    @SuppressLint("MissingPermission")
    fun resolveDeviceLocation(context: Context?): Pair<Double, Double>? {
        if (context == null) return null
        return runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var best: Location? = null
            for (p in providers) {
                val enabled = runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
                if (!enabled && p != LocationManager.PASSIVE_PROVIDER) continue
                val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            }
            best?.let {
                // 读取私有字段 mLatitude/mLongitude 绕过虚拟定位对 getLatitude/getLongitude 的 Hook，
                // 从而拿到真实系统位置
                val lat = readRawLocationDouble(it, "mLatitude")
                val lon = readRawLocationDouble(it, "mLongitude")
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    lat to lon
                } else null
            }
        }.getOrNull()
    }

    /** 反射读取 Location 私有 double 字段，避开被 Hook 的 getter；失败回退 getter */
    private fun readRawLocationDouble(loc: Location, fieldName: String): Double {
        return runCatching {
            val f = Location::class.java.getDeclaredField(fieldName)
            f.isAccessible = true
            f.getDouble(loc)
        }.getOrElse {
            if (fieldName == "mLatitude") loc.latitude else loc.longitude
        }
    }

    /**
     * UI 默认坐标：设备当前位置 → 已保存有效坐标 → 兜底。
     * [preferDevice] 为 true 时优先设备（打开页 / 重置）。
     */
    fun defaultCoords(context: Context?, preferDevice: Boolean = true): Pair<Double, Double> {
        if (preferDevice) {
            resolveDeviceLocation(context)?.let { return it }
        }
        val loaded = load()
        if (!isLegacyUnset(loaded.second, loaded.third)) {
            return loaded.second to loaded.third
        }
        resolveDeviceLocation(context)?.let { return it }
        return FALLBACK_LATITUDE to FALLBACK_LONGITUDE
    }

    /** 重置为「当前设备位置」（失败则兜底），并关闭虚拟定位 */
    fun resetToDefault(context: Context? = null, async: Boolean = false) {
        val (la, lo) = defaultCoords(context, preferDevice = true)
        writePublic(
            enabled = false,
            lat = la,
            lon = lo,
            context = context,
            async = async
        )
        PublicConfigStore.putBoolean(KEY_ENABLED, false, async = false)
        PublicConfigStore.put(KEY_LATITUDE, fmt(la), async = false)
        PublicConfigStore.put(KEY_LONGITUDE, fmt(lo), async = false)
    }

    /** 把 UI 里未设置/旧泰国默认 填成设备位置并写回（不强制开启虚拟定位） */
    fun ensureInitialCoords(context: Context): Pair<Double, Double> {
        invalidate()
        val en = PublicConfigStore.getBoolean(KEY_ENABLED, false)
        val la = PublicConfigStore.getString(KEY_LATITUDE, "").toDoubleOrNull()
        val lo = PublicConfigStore.getString(KEY_LONGITUDE, "").toDoubleOrNull()
        if (la != null && lo != null && !isLegacyUnset(la, lo) && en) {
            return la to lo
        }
        // 已有用户自定义坐标且开着定位：保留
        if (la != null && lo != null && !isLegacyUnset(la, lo)) {
            return la to lo
        }
        val (dla, dlo) = defaultCoords(context, preferDevice = true)
        // 只在未配置时写入默认，不打开开关
        if (la == null || lo == null || isLegacyUnset(la, lo)) {
            PublicConfigStore.put(KEY_LATITUDE, fmt(dla), async = false)
            PublicConfigStore.put(KEY_LONGITUDE, fmt(dlo), async = false)
            writePublic(en, dla, dlo, context, async = false)
        }
        return dla to dlo
    }

    fun writePublic(
        enabled: Boolean,
        lat: Double,
        lon: Double,
        context: Context? = null,
        async: Boolean = true
    ) {
        val la = lat.coerceIn(-90.0, 90.0)
        val lo = lon.coerceIn(-180.0, 180.0)
        cachedEnabled = enabled
        cachedLat = la
        cachedLon = lo
        lastLoad = System.currentTimeMillis()

        val latText = String.format(Locale.US, "%.6f", la)
        val lonText = String.format(Locale.US, "%.6f", lo)
        val ts = System.currentTimeMillis()
        val body = buildString {
            appendLine("# OKK virtual location")
            appendLine("$KEY_ENABLED=$enabled")
            appendLine("$KEY_LATITUDE=$latText")
            appendLine("$KEY_LONGITUDE=$lonText")
            appendLine("$KEY_UPDATED=$ts")
        }
        val targets = publicFiles(context)
        if (async) {
            val seq = writeSeq.incrementAndGet()
            io.execute {
                if (seq != writeSeq.get()) return@execute
                targets.forEach { f ->
                    runCatching {
                        f.parentFile?.mkdirs()
                        // 合并进已有 achat_config.properties
                        mergeIntoProperties(f, mapOf(
                            KEY_ENABLED to enabled.toString(),
                            KEY_LATITUDE to latText,
                            KEY_LONGITUDE to lonText,
                            KEY_UPDATED to ts.toString()
                        ))
                    }
                }
                // 再写一份独立片段备份
                runCatching {
                    File(PUBLIC_DIR, "virtual_location.properties").apply {
                        parentFile?.mkdirs()
                        writeText(body, Charsets.UTF_8)
                    }
                }
            }
        } else {
            targets.forEach { f ->
                runCatching {
                    f.parentFile?.mkdirs()
                    mergeIntoProperties(
                        f,
                        mapOf(
                            KEY_ENABLED to enabled.toString(),
                            KEY_LATITUDE to latText,
                            KEY_LONGITUDE to lonText,
                            KEY_UPDATED to ts.toString()
                        )
                    )
                }
            }
        }
    }

    // ── 地图选点：模块写 request，微信进程读并拉起 RedirectUI，写 result ──

    fun requestMapPick() {
        val f = File(PUBLIC_DIR, REQUEST_FILE)
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText("ts=${System.currentTimeMillis()}\n", Charsets.UTF_8)
        }
        // 清旧结果，避免读到上一次
        runCatching { File(PUBLIC_DIR, RESULT_FILE).delete() }
    }

    fun hasPendingMapPick(maxAgeMs: Long = 120_000L): Boolean {
        val f = File(PUBLIC_DIR, REQUEST_FILE)
        if (!f.isFile) return false
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        val ts = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("ts=") }
            ?.removePrefix("ts=")
            ?.toLongOrNull()
            ?: f.lastModified()
        return System.currentTimeMillis() - ts <= maxAgeMs
    }

    fun clearMapPickRequest() {
        runCatching { File(PUBLIC_DIR, REQUEST_FILE).delete() }
    }

    fun writeMapPickResult(lat: Double, lon: Double) {
        val f = File(PUBLIC_DIR, RESULT_FILE)
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(
                buildString {
                    appendLine("lat=${String.format(Locale.US, "%.6f", lat)}")
                    appendLine("lon=${String.format(Locale.US, "%.6f", lon)}")
                    appendLine("ts=${System.currentTimeMillis()}")
                },
                Charsets.UTF_8
            )
        }
        clearMapPickRequest()
        // 同时写入配置坐标
        writePublic(enabled = true, lat = lat, lon = lon, async = false)
    }

    /**
     * 读取选点结果；[consume] 为 true 时读完删除。
     */
    fun readMapPickResult(consume: Boolean = true): Pair<Double, Double>? {
        val f = File(PUBLIC_DIR, RESULT_FILE)
        if (!f.isFile) return null
        val map = runCatching {
            f.readText(Charsets.UTF_8).lineSequence()
                .map { it.trim() }
                .filter { it.contains('=') }
                .associate {
                    val i = it.indexOf('=')
                    it.substring(0, i) to it.substring(i + 1)
                }
        }.getOrNull() ?: return null
        val lat = map["lat"]?.toDoubleOrNull() ?: return null
        val lon = map["lon"]?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (consume) runCatching { f.delete() }
        return lat to lon
    }

    private fun loadFromPublic(): Triple<Boolean, Double, Double>? {
        val files = listOf(
            File(PUBLIC_DIR, PUBLIC_FILE),
            File(PUBLIC_DIR, "virtual_location.properties")
        )
        for (file in files) {
            if (!file.isFile) continue
            val map = runCatching { parseProps(file.readText(Charsets.UTF_8)) }.getOrNull()
                ?: continue
            if (!map.containsKey(KEY_ENABLED) && !map.containsKey(KEY_LATITUDE)) continue
            val en = map[KEY_ENABLED].equals("true", ignoreCase = true)
            val la = map[KEY_LATITUDE]?.toDoubleOrNull()?.coerceIn(-90.0, 90.0)
                ?: FALLBACK_LATITUDE
            val lo = map[KEY_LONGITUDE]?.toDoubleOrNull()?.coerceIn(-180.0, 180.0)
                ?: FALLBACK_LONGITUDE
            return Triple(en, la, lo)
        }
        return null
    }

    private fun publicFiles(context: Context?): List<File> {
        val list = ArrayList<File>(2)
        list += File(PUBLIC_DIR, PUBLIC_FILE)
        context?.getExternalFilesDir(null)?.let { list += File(it, PUBLIC_FILE) }
        return list.distinctBy { it.absolutePath }
    }

    private fun mergeIntoProperties(file: File, updates: Map<String, String>) {
        val existing = if (file.isFile) {
            runCatching { parseProps(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val merged = LinkedHashMap(existing)
        merged.putAll(updates)
        val text = buildString {
            appendLine("# OKK config")
            merged.forEach { (k, v) -> appendLine("$k=$v") }
        }
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    private fun parseProps(text: String): Map<String, String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate {
                val i = it.indexOf('=')
                it.substring(0, i).trim() to it.substring(i + 1).trim()
            }
    }
}
