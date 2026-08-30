package com.OKK.yes.core.compat

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 适配报告落盘。
 *
 * 自动弹窗指纹包含：
 * - 微信 versionName / versionCode
 * - **微信 firstInstallTime + lastUpdateTime**（重装微信会变）
 * - 模块 versionName + **lastUpdateTime**（重装模块会变）
 *
 * 因此：模块重装 / 微信重装 都会重新弹适配窗；同一安装周期内只弹一次。
 */
object CompatReportStore {
    private const val TAG = "OKK-CompatStore"
    private const val MODULE_PKG = "com.OKK.yes"
    private const val WECHAT_PKG = "com.tencent.mm"
    private const val MODULE_VER = "1.2.6"
    private const val FILE_NAME = "compat_report.json"
    private const val SHOWN_FILE = "compat_dialog_shown.txt"

    @Volatile
    var lastReport: CompatReport? = null
        private set

    @Volatile
    var pendingDialog: Boolean = false

    /**
     * true：需在主界面弹出进度并现场扫描（首次 / 重装）。
     * false：可用缓存静默安装，不弹进度。
     */
    @Volatile
    var pendingInteractiveScan: Boolean = false

    /** 兼容旧调用：无 Context 时只有版本号（不推荐） */
    fun fingerprint(ver: WeChatVersion): String =
        "${ver.versionCode}|${ver.versionName}|$MODULE_VER"

    /** 完整指纹：含安装时间戳，重装必变 */
    fun fingerprint(context: Context, ver: WeChatVersion, modulePath: String? = null): String {
        val wx = packageStamp(context, WECHAT_PKG)
        val mod = moduleStamp(context, modulePath)
        return "${ver.versionCode}|${ver.versionName}|$MODULE_VER|wx=$wx|mod=$mod"
    }

    private fun moduleStamp(context: Context, modulePath: String?): String {
        val fileStamp = runCatching {
            val apk = modulePath?.takeIf { it.isNotBlank() }?.let(::File)
            if (apk != null && apk.isFile) {
                "apk_${apk.length()}_${apk.lastModified()}"
            } else null
        }.getOrNull()
        if (!fileStamp.isNullOrBlank()) return fileStamp
        return packageStamp(context, MODULE_PKG)
    }

    private fun packageStamp(context: Context, packageName: String): String {
        return runCatching {
            val pm = context.packageManager
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val first = pi.firstInstallTime
            val last = pi.lastUpdateTime
            "${first}_${last}"
        }.getOrElse { "na" }
    }

    fun dir(): File = File("/sdcard/Android/media/com.tencent.mm/OKK")

    fun reportFile(): File = File(dir(), FILE_NAME)

    private fun shownFile(): File = File(dir(), SHOWN_FILE)

    fun loadFingerprint(): String? = runCatching {
        val f = reportFile()
        if (!f.isFile) return null
        JSONObject(f.readText()).optString("fingerprint", "").ifBlank { null }
    }.getOrNull()

    fun loadShownFingerprint(): String? = runCatching {
        val f = shownFile()
        if (!f.isFile) return null
        f.readText().trim().ifBlank { null }
    }.getOrNull()

    /** 该指纹是否还需要自动弹窗 */
    fun needsAutoDialog(fp: String): Boolean {
        val shown = loadShownFingerprint()
        val need = shown == null || shown != fp
        xlog("needsAutoDialog=$need fp=$fp shown=$shown")
        return need
    }

    fun markDialogShown(fp: String) {
        pendingDialog = false
        pendingInteractiveScan = false
        runCatching {
            dir().mkdirs()
            shownFile().writeText(fp)
        }
        xlog("dialog marked shown for $fp")
    }

    fun loadReport(): CompatReport? = runCatching {
        val f = reportFile()
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("results")
        val list = ArrayList<ProbeResult>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            list += ProbeResult(
                id = it.getString("id"),
                title = it.getString("title"),
                level = ProbeLevel.valueOf(it.getString("level")),
                detail = it.optString("detail", "")
            )
        }
        CompatReport(
            fingerprint = o.getString("fingerprint"),
            wechatSummary = o.optString("wechat", ""),
            atMs = o.optLong("atMs", 0L),
            results = list,
            shouldShowDialog = false
        ).also { lastReport = it }
    }.getOrNull()

    fun save(report: CompatReport) {
        lastReport = report
        pendingDialog = report.shouldShowDialog || needsAutoDialog(report.fingerprint)
        runCatching {
            dir().mkdirs()
            val arr = JSONArray()
            report.results.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("title", r.title)
                        .put("level", r.level.name)
                        .put("detail", r.detail)
                )
            }
            val o = JSONObject()
                .put("fingerprint", report.fingerprint)
                .put("wechat", report.wechatSummary)
                .put("atMs", report.atMs)
                .put("summary", report.summaryLine())
                .put("results", arr)
                .put("pendingDialog", pendingDialog)
            reportFile().writeText(o.toString(2))
            xlog("saved ${report.summaryLine()} pendingDialog=$pendingDialog")
        }.onFailure {
            xlog("save fail: ${it.message}")
        }
    }

    fun isInstallable(featureId: String): Boolean {
        val r = lastReport?.byId(featureId) ?: return true
        return r.installable
    }

    fun clearPendingDialog() {
        pendingDialog = false
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
    }
}
