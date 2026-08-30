package com.OKK.yes.core.compat

import android.content.Context
import android.util.Log
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object DexKitCache {
    private const val TAG = "OKK-DexKitCache"
    private var isLoaded = false
    private val cacheMap = ConcurrentHashMap<String, String>()
    private var cacheFile: File? = null

    private fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            val ver = WeChatVersion.resolve(context)
            val dir = File("/storage/emulated/0/Android/media/com.tencent.mm/OKK")
            if (!dir.exists()) dir.mkdirs()
            cacheFile = File(dir, "dexkit_cache_${ver.versionCode}.json")
            if (cacheFile?.exists() == true) {
                runCatching {
                    val json = JSONObject(cacheFile!!.readText())
                    json.keys().forEach { k ->
                        cacheMap[k] = json.getString(k)
                    }
                    xlog("loaded ${cacheMap.size} entries for v${ver.versionCode}")
                }.onFailure { xlog("load err: $it") }
            }
            isLoaded = true
        }
    }

    private fun save() {
        val file = cacheFile ?: return
        runCatching {
            val json = JSONObject()
            cacheMap.forEach { (k, v) -> json.put(k, v) }
            file.writeText(json.toString())
        }
    }

    fun get(context: Context, key: String): String? {
        ensureLoaded(context)
        return cacheMap[key]
    }

    fun put(context: Context, key: String, value: String) {
        ensureLoaded(context)
        if (cacheMap[key] != value) {
            cacheMap[key] = value
            save()
        }
    }

    fun getList(context: Context, key: String): List<String>? {
        val str = get(context, key) ?: return null
        if (str == "EMPTY") return emptyList()
        return str.split(",").filter { it.isNotBlank() }
    }

    fun putList(context: Context, key: String, list: List<String>) {
        val value = if (list.isEmpty()) "EMPTY" else list.joinToString(",")
        put(context, key, value)
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
