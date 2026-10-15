package com.OKK.yes.core.startup

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 深度生效审计（Runtime Health Audit）。
 *
 * 静态探针只能证明「锚点在 dex 里找得到」，无法证明「功能真的在跑」。
 * 本模块在既有「安装登记 + 生效上报 + 运行时上报」之上补两路信号：
 *
 * 1. **运行时触发计数**（[noteActivity]）：Hook 真正命中时由各功能主动上报；
 * 2. **主动干跑自检**（[registerVerifier]）：只读反射状态，验证关键方法/入口是否可用。
 *
 * 合并后产出逐功能判定 [Verdict]：`READY / DEGRADED / UNVERIFIED / BROKEN`。
 *
 * 设计约束：
 * - [noteActivity] 走 `ConcurrentHashMap` 计数，无 IO、无锁竞争，可高频调用；
 * - Verifier 只读，禁止 invoke 业务方法、禁止写 DB；
 * - `hits == 0` 只作提示（本次会话未触发），**不自动修改安装状态**。
 */
object RuntimeHealthAudit {
    private const val TAG = "OKK-RuntimeAudit"

    enum class Verdict {
        /** 安装成功 + 已实际触发（或主动自检通过）。 */
        READY,

        /** 装了但部分失效：探针 PARTIAL / 生效上报为否 / 运行时上报为否。 */
        DEGRADED,

        /** 安装成功但既未触发也无主动自检结论（探针通过 ≠ 真的在跑）。 */
        UNVERIFIED,

        /** 未安装或主动自检明确失败。 */
        BROKEN
    }

    /** 主动自检结论。`pass == null` 表示无法在无用户交互下判定。 */
    data class VerifyOutcome(val pass: Boolean?, val detail: String)

    fun interface FeatureVerifier {
        fun verify(): VerifyOutcome
    }

    data class AuditRow(
        val id: String,
        val title: String,
        val install: String,
        val effective: String,
        val runtime: String,
        val hits: Int,
        val lastHitMs: Long,
        val verdict: Verdict,
        val detail: String
    )

    private class Activity {
        val count = AtomicInteger(0)
        @Volatile var lastMs: Long = 0L
        @Volatile var lastDetail: String = ""
    }

    private val activities = ConcurrentHashMap<String, Activity>()
    private val verifiers = ConcurrentHashMap<String, FeatureVerifier>()

    /** 功能 ID → 中文展示名（与 FeatureInstallGateway / FeatureHookRegistry 的 ID 对齐）。 */
    private val titles = linkedMapOf(
        "SettingsEntry" to "设置入口",
        "SettingsEntryPlus" to "加号菜单入口",
        "HomeAvatar" to "首页头像入口",
        "HomeSideDrawer" to "侧边栏",
        "HideHomeDivider" to "隐藏首页分隔线",
        "FoldBannerPin" to "折叠顶栏",
        "BottomTabIcon" to "底栏图标",
        "BottomTabFloating" to "悬浮底栏",
        "FloatingQuickEntry" to "悬浮快捷入口",
        "RoundAvatar" to "圆角头像",
        "ThemeWallpaper" to "主题壁纸",
        "AntiRevoke" to "防撤回",
        "ChatEnhance" to "聊天增强",
        "BlockTypingReport" to "禁止输入状态",
        "QuoteDeleteClear" to "删除清引用",
        "EditMessage" to "修改消息",
        "InputStats" to "输入统计",
        "ChatToolbar" to "聊天工具栏",
        "SystemCamera" to "系统相机",
        "RemoveLimitsDuringCalls" to "通话无限制",
        "AntiMomentsDelete" to "朋友圈防删",
        "AntiMomentsComment" to "朋友圈评论防撤回",
        "MomentsAdBlock" to "朋友圈广告",
        "ProfileId" to "资料页 ID",
        "RealNameTail" to "实名尾字",
        "MemberTitle" to "群员头衔",
        "DisableHotUpdate" to "禁用热更新",
        "VirtualLocation" to "虚拟定位",
        "AutoLoginWin" to "PC 自动登录",
        "MapPickBridge" to "地图选点",
        "DownloadRedirect" to "下载重定向",
        "ConversationGrouping" to "会话分组",
        "ConversationListStyle" to "会话列表卡片",
        "SwipeDeleteKeep" to "左滑删除存记录",
        "CloseFriend" to "密友功能",
        "FinderVideoDownload" to "视频号下载",
        "JavaPlugin" to "Java脚本"
    )

    // ── 1. 运行时触发计数 ────────────────────────────────────────────────

    /**
     * 关键 Hook 命中时上报。高频安全（无 IO / 无锁竞争）。
     *
     * @param id 功能 ID（[titles] 的 key）
     * @param detail 命中说明（仅保留最后一次，用于诊断展示）
     */
    fun noteActivity(id: String, detail: String = "") {
        val a = activities.getOrPut(id) { Activity() }
        a.count.incrementAndGet()
        a.lastMs = System.currentTimeMillis()
        if (detail.isNotBlank()) a.lastDetail = detail
    }

    /** 返回 (触发次数, 最后触发时间戳)。 */
    fun activityOf(id: String): Pair<Int, Long> {
        val a = activities[id] ?: return 0 to 0L
        return a.count.get() to a.lastMs
    }

    fun clearActivity() {
        activities.clear()
    }

    // ── 2. 主动干跑自检 ─────────────────────────────────────────────────

    fun registerVerifier(id: String, verifier: FeatureVerifier) {
        verifiers[id] = verifier
    }

    fun unregisterVerifier(id: String) {
        verifiers.remove(id)
    }

    /** 运行单个功能的自检；未注册或无异常返回 null 表示无法判定。 */
    private fun runVerifier(id: String): VerifyOutcome {
        val v = verifiers[id] ?: return VerifyOutcome(null, "")
        return runCatching { v.verify() }.getOrElse { VerifyOutcome(false, "自检异常: ${it.message}") }
    }

    // ── 3. 判定 ────────────────────────────────────────────────────────

    /**
     * 判定单个功能。纯函数，便于单测。
     *
     * @param installOk 安装登记状态是否 OK
     * @param installBad 安装登记状态是否 FAIL/SKIP
     * @param installPartial 安装登记状态是否 PARTIAL
     * @param effectiveOk `reportEffective` 上报值（null = 未上报）
     * @param runtimeOk `reportRuntime` 上报值（null = 未上报）
     * @param verify 主动自检结论
     * @param hits 运行时触发次数
     */
    fun decide(
        installOk: Boolean,
        installBad: Boolean,
        installPartial: Boolean,
        effectiveOk: Boolean?,
        runtimeOk: Boolean?,
        verify: VerifyOutcome,
        hits: Int
    ): Verdict {
        if (installBad) return Verdict.BROKEN
        if (verify.pass == false) return Verdict.BROKEN
        if (installPartial) return Verdict.DEGRADED
        if (effectiveOk == false) return Verdict.DEGRADED
        if (runtimeOk == false) return Verdict.DEGRADED
        if (verify.pass == true) return Verdict.READY
        if (hits > 0) return Verdict.READY
        if (installOk) return Verdict.UNVERIFIED
        return Verdict.UNVERIFIED
    }

    /** 汇总四路信号，产出逐功能审计表（按功能清单顺序）。 */
    fun audit(): List<AuditRow> {
        val installRecords = runCatching { FeatureHookRegistry.snapshot() }.getOrDefault(emptyList())
        val runtimeRecords = runCatching { FeatureHookRegistry.runtimeSnapshot() }.getOrDefault(emptyList())

        val out = ArrayList<AuditRow>(titles.size)
        for ((id, title) in titles) {
            val installRec = installRecords.firstOrNull { it.name == id }
            val installLabel = when (installRec?.status) {
                FeatureHookRegistry.Status.OK -> "OK"
                FeatureHookRegistry.Status.PARTIAL -> "PARTIAL"
                FeatureHookRegistry.Status.FAIL -> "FAIL"
                FeatureHookRegistry.Status.SKIP -> "SKIP"
                null -> "—"
            }
            val installOk = installRec?.status == FeatureHookRegistry.Status.OK
            val installBad = installRec?.status == FeatureHookRegistry.Status.FAIL ||
                installRec?.status == FeatureHookRegistry.Status.SKIP
            val installPartial = installRec?.status == FeatureHookRegistry.Status.PARTIAL

            val eff = FeatureHookRegistry.effectiveOf(id)
            val effectiveLabel = when (eff?.first) {
                true -> "ok"
                false -> "未生效"
                null -> "—"
            }

            val rt = runtimeRecords.firstOrNull { it.name == id || it.name == title }
            val runtimeLabel = when (rt?.status) {
                FeatureHookRegistry.Status.OK -> "OK"
                FeatureHookRegistry.Status.PARTIAL -> "PARTIAL"
                else -> "—"
            }
            val runtimeOk = when (rt?.status) {
                FeatureHookRegistry.Status.OK -> true
                FeatureHookRegistry.Status.PARTIAL -> false
                else -> null
            }

            val (hits, lastMs) = activityOf(id)
            val verify = runVerifier(id)

            val verdict = decide(
                installOk = installOk,
                installBad = installBad,
                installPartial = installPartial,
                effectiveOk = eff?.first,
                runtimeOk = runtimeOk,
                verify = verify,
                hits = hits
            )

            val detail = buildString {
                installRec?.detail?.takeIf { it.isNotBlank() }?.let { append(it) }
                eff?.second?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
                verify.detail.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" · ")
                    append("自检: ").append(it)
                }
                val a = activities[id]
                if (hits == 0) {
                    if (isNotEmpty()) append(" · ")
                    append("未触发（建议实际使用一次后再检测）")
                } else {
                    if (isNotEmpty()) append(" · ")
                    append("已触发 ").append(hits).append(" 次")
                    a?.lastDetail?.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
                }
            }

            out += AuditRow(
                id = id,
                title = title,
                install = installLabel,
                effective = effectiveLabel,
                runtime = runtimeLabel,
                hits = hits,
                lastHitMs = lastMs,
                verdict = verdict,
                detail = detail
            )
        }
        return out
    }

    fun summaryLine(rows: List<AuditRow> = audit()): String {
        val ready = rows.count { it.verdict == Verdict.READY }
        val degraded = rows.count { it.verdict == Verdict.DEGRADED }
        val unverified = rows.count { it.verdict == Verdict.UNVERIFIED }
        val broken = rows.count { it.verdict == Verdict.BROKEN }
        return "ready=$ready degraded=$degraded unverified=$unverified broken=$broken total=${rows.size}"
    }

    fun issueRows(rows: List<AuditRow> = audit()): List<AuditRow> =
        rows.filter { it.verdict == Verdict.BROKEN || it.verdict == Verdict.DEGRADED }

    // ── 4. 落盘 ────────────────────────────────────────────────────────

    fun persist(rows: List<AuditRow> = audit()): File? {
        val dir = File("/sdcard/Android/media/com.tencent.mm/OKK")
        return runCatching {
            dir.mkdirs()
            val f = File(dir, "runtime_audit.txt")
            val sb = StringBuilder()
            sb.appendLine("time=${System.currentTimeMillis()}")
            sb.appendLine(summaryLine(rows))
            sb.appendLine("---")
            sb.appendLine("verdict\tid\ttitle\tinstall\teffective\truntime\thits\tdetail")
            for (r in rows) {
                sb.appendLine(
                    listOf(
                        r.verdict.name, r.id, r.title, r.install, r.effective, r.runtime,
                        r.hits.toString(), r.detail
                    ).joinToString("\t")
                )
            }
            f.writeText(sb.toString())
            f
        }.getOrNull()
    }

    /** 供诊断页一键复制。 */
    fun reportText(rows: List<AuditRow> = audit()): String = buildString {
        appendLine("=== OKK 深度生效自检 ===")
        appendLine(summaryLine(rows))
        appendLine()
        for (r in rows) {
            appendLine("[${r.verdict}] ${r.title} · 安装=${r.install} · 触发=${r.hits} 次")
            if (r.detail.isNotBlank()) appendLine("    ${r.detail}")
        }
    }

    private fun xlog(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }
}
