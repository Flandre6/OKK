package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * 在**微信进程内**拉起内置地图选点（RedirectUI），写回公共文件。
 *
 * 模块 APK 无法跨进程 startActivityForResult 微信内部页，
 * 因此选点流程改为：
 * 1. 模块写 `map_pick_request` 并打开微信
 * 2. 本桥在微信 Activity.onResume 发现请求 → startActivityForResult(RedirectUI)
 * 3. onActivityResult 解析 KLocationIntent → 写 `map_pick_result` + 坐标配置
 *
 * 地图选点桥接。
 */
object WeChatMapPickBridge {
    private const val TAG = "OKK-MapPick"
    private const val REDIRECT_UI = "com.tencent.mm.plugin.location.ui.RedirectUI"
    private const val EXTRA_MAP_VIEW_TYPE = "map_view_type"
    private const val MAP_VIEW_TYPE = 8
    private const val EXTRA_K_LOCATION = "KLocationIntent"
    private const val REQUEST_CODE = 0xAC07

    private val installed = AtomicBoolean(false)
    private val picking = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val LAT_LNG = Pattern.compile(
        """lat\s*([-+]?\d*\.?\d+)\s*;\s*lng\s*([-+]?\d*\.?\d+)\s*;""",
        Pattern.CASE_INSENSITIVE
    )

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        hookActivityResume()
        hookActivityResult()
        xlog("map pick bridge installed")
    }

    private fun hookActivityResume() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName != "com.tencent.mm") return
                        // 避免在 RedirectUI 自身上重复拉起
                        if (activity.javaClass.name.contains("RedirectUI")) return
                        maybeLaunchPicker(activity)
                    }
                }
            )
        }.onFailure {
            xlog("hook onResume failed: ${it.message}")
        }
    }

    private fun hookActivityResult() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName != "com.tencent.mm") return
                        val requestCode = param.args[0] as? Int ?: return
                        val resultCode = param.args[1] as? Int ?: return
                        val data = param.args[2] as? Intent
                        if (requestCode != REQUEST_CODE) return
                        picking.set(false)
                        if (resultCode != Activity.RESULT_OK) {
                            VirtualLocationConfig.clearMapPickRequest()
                            xlog("map pick canceled")
                            return
                        }
                        handleResult(activity, data)
                    }
                }
            )
        }.onFailure {
            xlog("hook onActivityResult failed: ${it.message}")
        }
    }

    private fun maybeLaunchPicker(activity: Activity) {
        if (!VirtualLocationConfig.hasPendingMapPick()) return
        if (!picking.compareAndSet(false, true)) return

        mainHandler.post {
            runCatching {
                val cl = activity.classLoader
                val clazz = XposedHelpers.findClass(REDIRECT_UI, cl)
                // 默认地图中心使用真实系统位置；取不到时回退当前虚拟坐标
                val real = VirtualLocationConfig.resolveDeviceLocation(activity)
                val centerLat = real?.first ?: VirtualLocationConfig.latitude()
                val centerLon = real?.second ?: VirtualLocationConfig.longitude()
                @Suppress("DEPRECATION")
                activity.startActivityForResult(
                    Intent(activity, clazz).apply {
                        putExtra(EXTRA_MAP_VIEW_TYPE, MAP_VIEW_TYPE)
                        // 传入初始中心点，避免地图依赖 GPS 定位失败导致“无法定位当前位置”
                        putExtra("kwebmap_slat", centerLat)
                        putExtra("kwebmap_lng", centerLon)
                    },
                    REQUEST_CODE
                )
                xlog("launched RedirectUI for map pick")
                Toast.makeText(activity, "OKK：请在地图上选择位置", Toast.LENGTH_SHORT).show()
            }.onFailure {
                picking.set(false)
                VirtualLocationConfig.clearMapPickRequest()
                xlog("launch RedirectUI failed: ${it.message}")
                Toast.makeText(
                    activity,
                    "OKK：打开微信地图失败 ${it.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleResult(activity: Activity, data: Intent?) {
        val pair = parseResult(data)
        if (pair == null) {
            VirtualLocationConfig.clearMapPickRequest()
            Toast.makeText(activity, "OKK：解析地图结果失败", Toast.LENGTH_SHORT).show()
            xlog("parse result failed data=$data")
            return
        }
        val (lat, lon) = pair
        VirtualLocationConfig.writeMapPickResult(lat, lon)
        Toast.makeText(
            activity,
            String.format("OKK：已选点 %.5f, %.5f", lat, lon),
            Toast.LENGTH_LONG
        ).show()
        xlog("map pick ok lat=$lat lon=$lon")
    }

    fun parseResult(data: Intent?): Pair<Double, Double>? {
        if (data == null) return null
        @Suppress("DEPRECATION")
        val kLoc = data.getParcelableExtra<Parcelable>(EXTRA_K_LOCATION)
        if (kLoc != null) {
            parseFromObject(kLoc)?.let { return it }
        }
        data.extras?.keySet()?.forEach { key ->
            val v = runCatching { data.extras?.get(key) }.getOrNull() ?: return@forEach
            parseFromObject(v)?.let { return it }
        }
        data.dataString?.let { parseLatLngString(it)?.let { p -> return p } }
        return null
    }

    private fun parseFromObject(obj: Any): Pair<Double, Double>? {
        val texts = mutableListOf(obj.toString())
        obj.javaClass.methods
            .filter {
                it.parameterTypes.isEmpty() &&
                    it.returnType == String::class.java &&
                    it.name != "toString"
            }
            .take(16)
            .forEach { m ->
                runCatching {
                    m.isAccessible = true
                    (m.invoke(obj) as? String)?.takeIf { it.length in 5..500 }?.let { texts += it }
                }
            }
        for (t in texts) {
            parseLatLngString(t)?.let { return it }
        }
        return null
    }

    fun parseLatLngString(text: String): Pair<Double, Double>? {
        val m = LAT_LNG.matcher(text)
        if (m.find()) {
            val lat = m.group(1)?.toDoubleOrNull()
            val lon = m.group(2)?.toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                return lat to lon
            }
        }
        val latM = Pattern.compile(
            """lat(?:itude)?[=:\s]+([-+]?\d+\.?\d*)""",
            Pattern.CASE_INSENSITIVE
        ).matcher(text)
        val lonM = Pattern.compile(
            """(?:lng|lon|longitude)[=:\s]+([-+]?\d+\.?\d*)""",
            Pattern.CASE_INSENSITIVE
        ).matcher(text)
        if (latM.find() && lonM.find()) {
            val lat = latM.group(1)?.toDoubleOrNull()
            val lon = lonM.group(1)?.toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                return lat to lon
            }
        }
        return null
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
