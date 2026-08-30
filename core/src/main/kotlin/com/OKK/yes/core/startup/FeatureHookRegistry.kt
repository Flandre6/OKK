package com.OKK.yes.core.startup

import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 功能级 Hook 安装登记：每个功能独立 try/catch，
 * 新版本某个点失效时只记 FAIL，不影响其它功能继续装。
 */
object FeatureHookRegistry {
    private const val TAG = "OKK-FeatureReg"

    /** PARTIAL：装载未抛异常，但功能自检确认关键点未命中（如入口注入失败、核心方法未解析到）。 */
    enum class Status { OK, FAIL, SKIP, PARTIAL }

    data class Record(
        val name: String,
        val status: Status,
        val detail: String = "",
        val atMs: Long = System.currentTimeMillis()
    )

    private val records = ConcurrentHashMap<String, Record>()
    private val okCount = AtomicInteger(0)
    private val failCount = AtomicInteger(0)

    /**
     * 功能自检结果：由各 Hook 在 install() 内部主动上报，区分“没抛异常”与“真的生效”。
     * 例如设置入口 Hook 即便 install() 全程无异常，只要加号菜单注入 hooked count == 0，
     * 就应上报 ok=false，避免适配报告显示 OK 但用户实际找不到入口。
     * key=功能名，value=(是否真实生效, 说明)
     */
    private val effectiveReports = ConcurrentHashMap<String, Pair<Boolean, String>>()

    /** 供各 Hook 在 install() 内部调用，上报关键功能点是否真的生效。 */
    fun reportEffective(name: String, ok: Boolean, detail: String = "") {
        effectiveReports[name] = ok to detail
        xlog("effective report $name ok=$ok $detail")
    }

    /**
     * 运行时真实状态上报（覆盖 install 状态）。
     * UI 类功能（侧边栏/FAB/悬浮底栏/头像等）是 Activity resume 时才异步注入视图的，
     * install() 时无法知道是否真的加上，必须在注入成功/失败的那一刻调用本方法，
     * 诊断页才能反映“功能现在到底有没有生效”，避免“全部适配”但用户找不到入口。
     */
    private val runtimeStates = ConcurrentHashMap<String, Record>()

    fun reportRuntime(name: String, ok: Boolean, detail: String = "") {
        val st = if (ok) Status.OK else Status.PARTIAL
        runtimeStates[name] = Record(name, st, detail)
        xlog("runtime $name ${if (ok) "OK" else "PARTIAL"} ${detail.take(60)}")
    }

    /** 运行时状态快照（逐功能）。 */
    fun runtimeSnapshot(): List<Record> = runtimeStates.values.sortedBy { it.name }

    /** 是否有运行时失效项（PARTIAL/FAIL）。 */
    fun hasRuntimeIssues(): Boolean = runtimeStates.values.any { it.status != Status.OK }

    fun runtimeIssueCount(): Int = runtimeStates.values.count { it.status != Status.OK }

    fun clear() {
        records.clear()
        effectiveReports.clear()
        runtimeStates.clear()
        okCount.set(0)
        failCount.set(0)
    }

    fun markOk(name: String, detail: String = "") {
        records[name] = Record(name, Status.OK, detail)
        okCount.incrementAndGet()
        xlog("OK  $name ${detail.take(80)}")
        runCatching {
            com.OKK.yes.core.hooks.ModuleLog.i("功能安装成功: $name${if (detail.isBlank()) "" else " · $detail"}")
        }
    }

    fun markFail(name: String, error: Throwable) {
        val detail = "${error.javaClass.simpleName}: ${error.message}"
        records[name] = Record(name, Status.FAIL, detail)
        failCount.incrementAndGet()
        xlog("FAIL $name -> $detail")
        runCatching {
            com.OKK.yes.core.hooks.ModuleLog.e("功能安装失败: $name · $detail")
        }
    }

    fun markSkip(name: String, reason: String) {
        records[name] = Record(name, Status.SKIP, reason)
        xlog("SKIP $name ($reason)")
        runCatching {
            com.OKK.yes.core.hooks.ModuleLog.w("功能跳过: $name · $reason")
        }
    }

    fun markPartial(name: String, detail: String) {
        records[name] = Record(name, Status.PARTIAL, detail)
        xlog("PARTIAL $name $detail")
        runCatching {
            com.OKK.yes.core.hooks.ModuleLog.w("功能部分生效: $name · $detail")
        }
    }

    /**
     * 独立安装一块功能：任何 Throwable 都吞掉，只记状态。
     * install() 结束后检查该功能是否通过 [reportEffective] 上报过“未真实生效”，
     * 若有则登记为 PARTIAL 而不是盲目 OK，避免探针通过但实际无入口/无效果的假阳性。
     */
    fun installIsolated(name: String, block: () -> Unit): Boolean {
        return try {
            effectiveReports.remove(name)
            block()
            val eff = effectiveReports[name]
            if (eff != null && !eff.first) {
                markPartial(name, eff.second)
            } else {
                markOk(name, eff?.second ?: "")
            }
            true
        } catch (t: Throwable) {
            // 登记本身也不得抛出（单测无 Android Log / Xposed）
            runCatching { markFail(name, t) }
            false
        }
    }

    fun snapshot(): List<Record> =
        records.values.sortedBy { it.name }

    fun summaryLine(): String {
        val ok = records.values.count { it.status == Status.OK }
        val fail = records.values.count { it.status == Status.FAIL }
        val skip = records.values.count { it.status == Status.SKIP }
        val partial = records.values.count { it.status == Status.PARTIAL }
        return "ok=$ok partial=$partial fail=$fail skip=$skip total=${records.size}"
    }

    fun failNames(): List<String> =
        records.values.filter { it.status == Status.FAIL }.map { it.name }.sorted()

    fun okNames(): List<String> =
        records.values.filter { it.status == Status.OK }.map { it.name }.sorted()

    /** 写到公共目录，方便 adb pull / 用户反馈 */
    fun persist(dir: File? = null) {
        val targetDir = dir ?: File("/sdcard/Android/media/com.tencent.mm/OKK")
        runCatching {
            targetDir.mkdirs()
            val f = File(targetDir, "hook_features.txt")
            val sb = StringBuilder()
            sb.appendLine("time=${System.currentTimeMillis()}")
            sb.appendLine(summaryLine())
            sb.appendLine("---")
            for (r in snapshot()) {
                sb.appendLine("${r.status}\t${r.name}\t${r.detail}")
            }
            val rt = runtimeSnapshot()
            if (rt.isNotEmpty()) {
                sb.appendLine("--- runtime ---")
                for (r in rt) {
                    sb.appendLine("${r.status}\t${r.name}\t${r.detail}")
                }
            }
            f.writeText(sb.toString())
        }
    }

    private fun xlog(msg: String) {
        runCatching { Log.i(TAG, msg) }
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
