package com.OKK.yes.core.hooks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * 全局主题壁纸配置（公共目录，微信进程可读）。
 *
 * - 不改聊天页背景
 * - 主要作用于 Launcher 主 Tab + 设置相关界面
 */
object ThemeWallpaperConfig {
    const val KEY_ENABLED = "theme_wallpaper_enabled"
    const val KEY_ALPHA = "theme_wallpaper_alpha"
    const val KEY_PATH = "theme_wallpaper_path"
    const val KEY_UPDATED = "theme_wallpaper_updated"

    /**
     * Wallpaper opacity. The overlay path may use the full range; content-layer
     * rendering keeps text above the wallpaper when that path is available.
     */
    const val MIN_ALPHA = 0.01f
    const val MAX_ALPHA = 0.85f
    const val DEFAULT_ALPHA = 0.28f

    private const val PUBLIC_DIR = "/storage/emulated/0/Android/media/com.tencent.mm/OKK"
    private const val WALLPAPER_FILE = "theme_wallpaper.jpg"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var cacheAt = 0L
    @Volatile private var cachedEnabled = false
    @Volatile private var cachedAlpha = DEFAULT_ALPHA
    @Volatile private var cachedPath = ""
    @Volatile private var cachedUpdated = ""
    @Volatile private var cachedBmp: Bitmap? = null
    @Volatile private var bmpPathLoaded = ""
    @Volatile private var bmpUpdatedLoaded = ""

    fun wallpaperFile(): File = File(PUBLIC_DIR, WALLPAPER_FILE)

    fun addListener(l: () -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it.invoke() } }
    }

    fun isEnabled(): Boolean {
        reloadIfNeeded()
        return cachedEnabled
    }

    fun alpha(): Float {
        reloadIfNeeded()
        return cachedAlpha
    }

    fun path(): String {
        reloadIfNeeded()
        return cachedPath
    }

    fun imageKey(): String {
        reloadIfNeeded()
        return "${cachedPath}:${cachedUpdated}"
    }

    fun invalidate() {
        cacheAt = 0L
    }

    fun reloadIfNeeded(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - cacheAt < 800L && cacheAt > 0L) return
        cachedEnabled = PublicConfigStore.getBoolean(KEY_ENABLED, false)
        cachedAlpha = clampAlpha(
            PublicConfigStore.getString(KEY_ALPHA, DEFAULT_ALPHA.toString()).toFloatOrNull()
                ?: DEFAULT_ALPHA
        )
        val p = PublicConfigStore.getString(KEY_PATH, "").trim()
        cachedUpdated = PublicConfigStore.getString(KEY_UPDATED, "").trim()
        val file = wallpaperFile()
        cachedPath = when {
            p.isNotEmpty() && File(p).isFile -> p
            file.isFile -> file.absolutePath
            else -> ""
        }
        cacheAt = now
    }

    fun setEnabled(on: Boolean, notify: Boolean = true) {
        PublicConfigStore.putBoolean(KEY_ENABLED, on, async = true)
        cachedEnabled = on
        cacheAt = SystemClock.uptimeMillis()
        if (notify) notifyChanged()
    }

    /** 实时透明度：写缓存立刻生效，异步落盘 */
    fun setAlphaLive(alpha: Float) {
        val a = clampAlpha(alpha)
        cachedAlpha = a
        cacheAt = SystemClock.uptimeMillis()
        PublicConfigStore.put(KEY_ALPHA, formatAlpha(a), async = true)
        // 实时只改 alpha，不整页 refresh（避免闪）
        ThemeWallpaperController.setAlphaLive(a)
    }

    fun setAlphaPersist(alpha: Float) {
        val a = clampAlpha(alpha)
        PublicConfigStore.put(KEY_ALPHA, formatAlpha(a), async = false)
        cachedAlpha = a
        cacheAt = SystemClock.uptimeMillis()
        ThemeWallpaperController.setAlphaLive(a)
    }

    fun clearWallpaper() {
        runCatching { wallpaperFile().delete() }
        PublicConfigStore.put(KEY_PATH, "", async = true)
        val updated = System.currentTimeMillis().toString()
        PublicConfigStore.put(KEY_UPDATED, updated, async = true)
        cachedPath = ""
        cachedUpdated = updated
        cachedBmp = null
        bmpPathLoaded = ""
        bmpUpdatedLoaded = ""
        cacheAt = SystemClock.uptimeMillis()
        notifyChanged()
    }

    fun saveFromUri(context: Context, uri: Uri): Boolean {
        return runCatching {
            val dir = File(PUBLIC_DIR)
            if (!dir.exists()) dir.mkdirs()
            val out = wallpaperFile()
            context.contentResolver.openInputStream(uri)?.use { input ->
                // 解码并压缩，避免超大图卡顿
                val bytes = input.readBytes()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val sample = sampleSize(opts.outWidth, opts.outHeight, 1440)
                val decode = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
                    ?: return@runCatching false
                FileOutputStream(out).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                }
                if (bmp !== cachedBmp) {
                    // 保留新图作缓存
                    cachedBmp = bmp
                    bmpPathLoaded = out.absolutePath
                }
            } ?: return@runCatching false
            val updated = System.currentTimeMillis().toString()
            PublicConfigStore.put(KEY_PATH, out.absolutePath, async = false)
            PublicConfigStore.putBoolean(KEY_ENABLED, true, async = false)
            PublicConfigStore.put(KEY_UPDATED, updated, async = false)
            cachedEnabled = true
            cachedPath = out.absolutePath
            cachedUpdated = updated
            bmpUpdatedLoaded = updated
            cacheAt = SystemClock.uptimeMillis()
            notifyChanged()
            true
        }.getOrDefault(false)
    }

    fun bitmap(): Bitmap? {
        reloadIfNeeded()
        val p = cachedPath
        if (p.isEmpty()) return null
        if (cachedBmp != null && !cachedBmp!!.isRecycled &&
            bmpPathLoaded == p && bmpUpdatedLoaded == cachedUpdated
        ) {
            return cachedBmp
        }
        val bmp = runCatching {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(p, opts)
            opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, 1600)
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeFile(p, opts)
        }.getOrNull()
        if (bmp != null) {
            cachedBmp = bmp
            bmpPathLoaded = p
            bmpUpdatedLoaded = cachedUpdated
        }
        return bmp
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var sample = 1
        var mw = w
        var mh = h
        while (mw > maxSide || mh > maxSide) {
            sample *= 2
            mw /= 2
            mh /= 2
        }
        return sample.coerceAtLeast(1)
    }

    fun formatAlpha(a: Float): String = String.format(java.util.Locale.US, "%.2f", a)

    fun clampAlpha(a: Float): Float = a.coerceIn(MIN_ALPHA, MAX_ALPHA)

    fun alphaToProgress(a: Float): Int = (clampAlpha(a) * 100f).roundToInt().coerceIn(1, 85)

    fun progressToAlpha(p: Int): Float = clampAlpha(p.coerceIn(1, 85) / 100f)
}
