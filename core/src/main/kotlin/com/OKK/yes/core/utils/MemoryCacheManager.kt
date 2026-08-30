package com.OKK.yes.core.utils

import android.graphics.Bitmap
import android.util.LruCache

object MemoryCacheManager {
    // 使用 JVM 可用内存的 1/8 作为 Bitmap 缓存
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    fun get(key: String): Bitmap? {
        val bmp = bitmapCache.get(key)
        if (bmp != null && bmp.isRecycled) {
            bitmapCache.remove(key)
            return null
        }
        return bmp
    }

    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            bitmapCache.put(key, bitmap)
        }
    }

    fun remove(key: String) {
        bitmapCache.remove(key)
    }

    fun clear() {
        bitmapCache.evictAll()
    }
}
