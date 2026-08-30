package com.OKK.yes.core.compat

import android.content.Context
import android.util.Log
import com.OKK.yes.core.hooks.ModuleLog
import de.robv.android.xposed.XposedBridge

/**
 * 运行全部功能探针，生成 [CompatReport]。
 * [onProgress] 在**调用线程**回调：index 从 1 开始，total 为探针总数。
 *
 * 首次扫描使用 DexKit bridge 进行完整字符串锚点检测。
 * 后续重复扫描（如「重新检查适配」）复用首次 bridge 结果写入的 [CompatReportStore]，
 * 不再重复创建 DexKit bridge（Android 上 DexKit bridge 只能在进程生命周期内创建一次）。
 */
object FeatureCompatProbe {
    private const val TAG = "OKK-CompatProbe"

    /**
     * 进程内是否已完成首次 bridge 扫描。
     * 非首次调用时，直接从 [CompatReportStore] 加载缓存的报告结果，
     * 只做 normalize 和 mergeRuntimeResult 更新，不依赖 DexKit bridge。
     */
    @Volatile
    private var initialScanDone = false

    fun run(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?,
        forceDialog: Boolean = false,
        onProgress: ((index: Int, total: Int, title: String) -> Unit)? = null,
        onResult: ((index: Int, result: ProbeResult) -> Unit)? = null
    ): CompatReport {
        val ver = WeChatVersion.resolve(context)
        val fp = CompatReportStore.fingerprint(context, ver, modulePath)
        val needDialog = forceDialog || CompatReportStore.needsAutoDialog(fp)
        val probes = FeatureProbeCatalog.all
        val total = probes.size

        xlog("probe start fp=$fp needDialog=$needDialog total=$total")
        ModuleLog.i("适配检查开始 · 共 $total 项 · fp=$fp")

        // ── 首次扫描：用 DexKit bridge 做完整检测 ──────────────────────────
        if (!initialScanDone) {
            val results = DexKitSupport.withBridge(context, classLoader, modulePath) { bridge ->
                runAllProbes(ProbeContext(classLoader, bridge, context, modulePath), onProgress, onResult)
            } ?: run {
                xlog("DexKit unavailable, class-only probes")
                runAllProbes(ProbeContext(classLoader, null, context, modulePath), onProgress, onResult)
            }

            val normalized = normalizeResults(ver, results)
            val report = CompatReport(
                fingerprint = fp,
                wechatSummary = ver.summary(),
                atMs = System.currentTimeMillis(),
                results = normalized,
                shouldShowDialog = needDialog
            )
            CompatReportStore.save(report)
            initialScanDone = true
            xlog("probe done ${report.summaryLine()}")
            ModuleLog.i("适配检查完成 · ${report.summaryLine()}")
            return report
        }

        // ── 非首次扫描：复用首次缓存报告，更新指纹/时间戳 ─────────────────
        xlog("re-scan: using cached probe results (bridge unavailable after first use)")
        ModuleLog.i("适配复检 · 跳过 DexKit bridge，复用首次扫描缓存")

        val cached = CompatReportStore.loadReport()
        if (cached != null) {
            // 模拟进度走一遍（让 UI 进度条有显示）
            probes.forEachIndexed { i, probe ->
                val titleHint = FeatureProbeCatalog.titleOf(probe, i)
                onProgress?.invoke(i + 1, total, titleHint)
                try { Thread.sleep(120) } catch (_: InterruptedException) {}
            }

            val report = CompatReport(
                fingerprint = fp,
                wechatSummary = ver.summary(),
                atMs = System.currentTimeMillis(),
                results = cached.results,
                shouldShowDialog = needDialog
            )
            CompatReportStore.save(report)
            xlog("re-scan done (cached) ${report.summaryLine()}")
            ModuleLog.i("适配复检完成（复用缓存） · ${report.summaryLine()}")
            return report
        }

        // 缓存也不存在（理论上不会发生，兜底）
        xlog("re-scan but no cached report, doing full scan without bridge")
        val results = runCatching {
            runAllProbes(ProbeContext(classLoader, null, context, modulePath), onProgress)
        }.getOrDefault(emptyList())
        val normalized = normalizeResults(ver, results)
        val fallback = CompatReport(
            fingerprint = fp,
            wechatSummary = ver.summary(),
            atMs = System.currentTimeMillis(),
            results = normalized,
            shouldShowDialog = needDialog
        )
        CompatReportStore.save(fallback)
        return fallback
    }

    private fun runAllProbes(
        ctx: ProbeContext,
        onProgress: ((index: Int, total: Int, title: String) -> Unit)?,
        onResult: ((index: Int, result: ProbeResult) -> Unit)? = null
    ): List<ProbeResult> {
        val probes = FeatureProbeCatalog.all
        val total = probes.size
        val out = ArrayList<ProbeResult>(total)
        probes.forEachIndexed { i, probe ->
            val titleHint = FeatureProbeCatalog.titleOf(probe, i)
            onProgress?.invoke(i + 1, total, titleHint)
            val r = runCatching { probe.probe(ctx) }.getOrElse { e ->
                ProbeResult("?", titleHint, ProbeLevel.FAIL, e.message ?: "error")
            }
            val fixed = if (r.title.isBlank() || r.title == "?") {
                r.copy(title = titleHint)
            } else r
            xlog("\${fixed.level} \${fixed.id} \${fixed.detail}")
            ModuleLog.i(
                "适配 \${i + 1}/\$total \${fixed.title}: \${fixed.level.name} · \${fixed.detail}"
            )
            out += fixed
            onResult?.invoke(i + 1, fixed)
            if (onProgress != null) {
                // 逐个检测节奏：每个功能检测完成停顿片刻，转圈→打勾的视觉清晰可见
                try { Thread.sleep(120) } catch (_: InterruptedException) {}
            }
        }
        return out
    }

    /** 供需要强制重新首次扫描（清缓存后首次启动）时复位 */
    fun resetInitialScan() {
        initialScanDone = false
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }

    private fun normalizeResults(
        ver: WeChatVersion,
        results: List<ProbeResult>
    ): List<ProbeResult> {
        return results.map { result ->
            when {
                result.level == ProbeLevel.FAIL -> result
                !ver.inPrimaryRange -> result.copy(
                    level = ProbeLevel.PARTIAL,
                    detail = withPrefix(result.detail, "超出主适配范围，按静态特征估计")
                )
                ver.channel == WeChatVersion.Channel.UNKNOWN && result.level == ProbeLevel.OK -> result.copy(
                    level = ProbeLevel.PARTIAL,
                    detail = withPrefix(result.detail, "渠道未识别，需运行时验证")
                )
                else -> result
            }
        }
    }

    private fun withPrefix(detail: String, prefix: String): String {
        return if (detail.isBlank()) prefix else "$prefix · $detail"
    }
}
