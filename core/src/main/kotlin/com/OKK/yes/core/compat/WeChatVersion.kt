package com.OKK.yes.core.compat

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 运行时微信版本信息。
 *
 * 分析包清单（`wxapk/`）：
 * - play69  8.0.69 / 3022
 * - cn70    8.0.70 / 3060
 * - cn72    8.0.72 / 3100
 * - play72  8.0.72 / 3083
 * - cn74    8.0.74 / 3120
 * - cn76    8.0.76 / 3140
 */
data class WeChatVersion(
    val versionName: String,
    val versionCode: Long,
    val channel: Channel,
    val packageName: String = "com.tencent.mm"
) {
    enum class Channel { CN, PLAY, UNKNOWN }

    val major: Int
    val minor: Int
    val patch: Int

    init {
        val p = parseName(versionName)
        major = p.first
        minor = p.second
        patch = p.third
    }

    /** 主适配区间：8.0.69 ～ 8.0.76（含国服 / Play） */
    val inPrimaryRange: Boolean
        get() = major == 8 && minor == 0 && patch in MIN_PATCH..MAX_PATCH

    val supportLabel: String
        get() = when {
            inPrimaryRange -> "主适配"
            patch in (MIN_PATCH - 2) until MIN_PATCH -> "可能可用（偏低）"
            patch in (MAX_PATCH + 1)..(MAX_PATCH + 2) -> "可能可用（偏高）"
            else -> "未验证"
        }

    fun summary(): String =
        "$versionName ($versionCode) ${channel.name} · $supportLabel"

    companion object {
        const val MIN_PATCH = 69
        const val MAX_PATCH = 76
        const val PRIMARY_RANGE_LABEL = "微信 8.0.69–8.0.76（国服 / Play）"

        private const val TAG = "OKK-WxVer"

        @Volatile
        private var cached: WeChatVersion? = null

        fun parseName(versionName: String): Triple<Int, Int, Int> {
            val parts = versionName.trim()
                .removePrefix("v")
                .split('.', '-', '_', ' ')
                .mapNotNull { it.toIntOrNull() }
            return Triple(
                parts.getOrNull(0) ?: 0,
                parts.getOrNull(1) ?: 0,
                parts.getOrNull(2) ?: 0
            )
        }

        fun resolve(context: Context): WeChatVersion {
            cached?.let { return it }
            val pm = context.packageManager
            val pkg = context.packageName.takeIf { it == "com.tencent.mm" } ?: "com.tencent.mm"
            val info = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
            }.getOrNull()

            val name = info?.versionName?.trim().orEmpty().ifBlank { "unknown" }
            val code = if (info != null) {
                if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            } else 0L

            val channel = detectChannel(code, name, context)
            val v = WeChatVersion(name, code, channel, pkg)
            cached = v
            xlog("resolved ${v.summary()}")
            return v
        }

        fun current(): WeChatVersion? = cached

        fun clearCache() {
            cached = null
        }

        /**
         * 渠道识别：优先级=安装来源 → 精确映射表 → Play 特征类扫描（classes.dex 含 billingclient）。
         *
         * 注意：不能靠 versionCode 尾数判断！国服 8.0.76 存在 3140 与 3141 两个 build（3141 尾数非 0），
         * 旧尾数规则会把国服误判成 Play。不同渠道同版本号时 versionCode 分布：
         *   cn70=3060 / cn72=3100 / cn74=3120 / cn76=3140·3141
         *   play69=3022 / play72=3083·3084
         */
        private fun detectChannel(code: Long, name: String, context: Context): Channel {
            // 1) 安装来源：Google Play 安装的来源必为 vending / google
            val installer = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    context.packageManager.getInstallSourceInfo("com.tencent.mm").installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getInstallerPackageName("com.tencent.mm")
                }
            }.getOrNull().orEmpty()
            if (installer.contains("vending", true) ||
                installer.contains("google", true)
            ) {
                return Channel.PLAY
            }

            // 2) 精确映射表：已知国服 / Play 的 versionCode（尾数不规律，必须精确匹配）
            when (code) {
                // 国服
                3060L, 3100L, 3120L, 3140L, 3141L -> return Channel.CN
                // Google Play
                3022L, 3083L, 3084L -> return Channel.PLAY
            }

            // 3) 兜底：运行时扫描微信 APK 的 classes.dex 是否含 Google Play 计费特征
            //    （com.android.billingclient / com.android.vending.billing 仅在 Play 版存在）
            //    在后台线程扫描，首次调用先返回 UNKNOWN，避免阻塞模块加载。
            return detectPlayByDexAsync(context)
        }

        /**
         * 扫描微信 APK 的 dex 是否含 Play 计费特征。
         * Play 版内置 InApp Billing（com.android.billingclient / com.android.vending.billing），
         * 国服没有；com.google.android.play.core 两者都有，不能作为判据。
         * 只读前 4 个 dex（billingclient 均在前部，国服 8.0.76 共 17 个 dex 全读太重），命中即 Play。
         * 在后台线程执行，避免阻塞模块加载；结果缓存到 playDexCache。
         */
        @Volatile
        private var playDexCache: Channel? = null

        private fun detectPlayByDexAsync(context: Context): Channel {
            playDexCache?.let { return it }
            // 后台线程扫描，首次返回 UNKNOWN
            Thread {
                try {
                    var result: Channel = Channel.UNKNOWN
                    val apkPath = runCatching {
                        context.packageManager.getApplicationInfo("com.tencent.mm", 0).sourceDir
                    }.getOrNull()
                    if (!apkPath.isNullOrBlank()) {
                        result = runCatching {
                            val f = java.io.File(apkPath)
                            if (!f.exists()) return@runCatching Channel.UNKNOWN
                            java.util.zip.ZipFile(f).use { zf ->
                                var found = false
                                val headers = listOf("classes.dex", "classes2.dex", "classes3.dex", "classes4.dex")
                                for (n in headers) {
                                    val entry = zf.getEntry(n) ?: continue
                                    val size = entry.size
                                    if (size > 40L * 1024 * 1024) continue // 跳过超大 dex
                                    val buf = ByteArray(size.toInt())
                                    zf.getInputStream(entry).read(buf)
                                    val s = String(buf, Charsets.ISO_8859_1)
                                    if (s.contains("com/android/billingclient") ||
                                        s.contains("com/android/vending/billing")
                                    ) { found = true; break }
                                }
                                if (found) Channel.PLAY else Channel.CN
                            }
                        }.getOrDefault(Channel.UNKNOWN)
                    }
                    playDexCache = result
                    xlog("dex probe result=$result")
                } catch (t: Throwable) {
                    xlog("dex probe fail: ${t.message}")
                }
            }.start()
            return Channel.UNKNOWN
        }

        private fun xlog(msg: String) {
            Log.i(TAG, msg)
            runCatching { XposedBridge.log("[$TAG] $msg") }
        }
    }
}
