package com.OKK.yes.loader

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File

/**
 * 运行时签名自校验：确认当前加载的模块 APK 由正式证书签名，
 * 防止别人对 APK 重签名后“换个域名/作者名”倒卖伪装成正版。
 *
 * 策略（防脆弱性）：
 *  - 直接对模块 APK 文件做 PackageManager.getPackageArchiveInfo（不依赖包可见性，跨进程可靠）。
 *  - 读不到 / 无法解析（极端厂商环境）→ 默认放行，避免误伤正规用户，仅打日志。
 *  - 读到但哈希不匹配（被重签）→ 拒绝注入核心 Hook（模块静默失效，攻击者需先破解此校验）。
 *
 * 目标哈希 = module APK 内 SigningCert.DER 的 SHA256 十六进制，与正式发布证书一致。
 */
object LegitCheck {
    private const val TAG = "OKK-Legit"

    // 正式签名的 SigningCert.SHA256（证书指纹，非私钥，可安全硬编码）
    private const val EXPECTED_SHA256 =
        "58952704A74D496BF4C008DA7974725D049B04A827CE208C84885A4F260324B6"

    @Volatile
    private var cachedResult: Boolean? = null

    /** 是否为本机正式签名。moduleApkPath 来自 LSPosed 注入的模块 sourceDir。 */
    fun isLegit(context: Context, moduleApkPath: String?): Boolean {
        cachedResult?.let { return it }

        // 拿不到路径：无法判定，默认放行（避免误伤）
        val apk = moduleApkPath?.takeIf { it.isNotBlank() && File(it).isFile } ?: run {
            xlog("module path unavailable -> allow")
            return true
        }

        val pm = runCatching { context.packageManager }.getOrNull()
        if (pm == null) {
            xlog("packageManager unavailable -> allow")
            return true
        }

        // 能否读取签名（真正判定依据）
        val readResult = readSignatureSha256(pm, apk)
        val decided = when {
            readResult == null -> {
                // 读不到签名（非标准解析）-> 放行避免误伤
                xlog("cannot read APK signature -> allow")
                true
            }
            readResult.equals(EXPECTED_SHA256, ignoreCase = true) -> {
                xlog("signature MATCH sha256=$readResult")
                true
            }
            else -> {
                // 能读到签名但不匹配 -> 判定为重签，拦截
                xlog("signature MISMATCH got=$readResult -> block")
                false
            }
        }
        cachedResult = decided
        return decided
    }

    private fun xlog(msg: String) {
        runCatching {
            de.robv.android.xposed.XposedBridge.log("[$TAG] $msg")
        }
    }

    /** 读取模块 APK 内首个签名证书(DER)的 SHA256 十六进制；读不到返回 null。 */
    private fun readSignatureSha256(pm: PackageManager, apk: String): String? = runCatching {
        val info: PackageInfo = pm.getPackageArchiveInfo(apk, PackageManager.GET_SIGNATURES)
            ?: return@runCatching null
        val sigs: Array<Signature> = info.signatures ?: return@runCatching null
        val bytes = sigs.firstOrNull()?.toByteArray() ?: return@runCatching null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val d = md.digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02X".format(b))
        sb.toString()
    }.getOrNull()
}
