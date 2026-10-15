package com.OKK.yes.core.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHealthAuditTest {

    private fun unknown() = RuntimeHealthAudit.VerifyOutcome(null, "")

    @Test
    fun brokenWhenInstallFailed() {
        assertEquals(
            RuntimeHealthAudit.Verdict.BROKEN,
            RuntimeHealthAudit.decide(
                installOk = false, installBad = true, installPartial = false,
                effectiveOk = null, runtimeOk = null, verify = unknown(), hits = 99
            )
        )
    }

    @Test
    fun brokenWhenVerifierFails() {
        assertEquals(
            RuntimeHealthAudit.Verdict.BROKEN,
            RuntimeHealthAudit.decide(
                installOk = true, installBad = false, installPartial = false,
                effectiveOk = null, runtimeOk = null,
                verify = RuntimeHealthAudit.VerifyOutcome(false, "入口未命中"), hits = 5
            )
        )
    }

    @Test
    fun degradedWhenPartialOrNotEffective() {
        // 安装 PARTIAL
        assertEquals(
            RuntimeHealthAudit.Verdict.DEGRADED,
            RuntimeHealthAudit.decide(
                installOk = false, installBad = false, installPartial = true,
                effectiveOk = null, runtimeOk = null, verify = unknown(), hits = 0
            )
        )
        // 生效上报为否
        assertEquals(
            RuntimeHealthAudit.Verdict.DEGRADED,
            RuntimeHealthAudit.decide(
                installOk = true, installBad = false, installPartial = false,
                effectiveOk = false, runtimeOk = null, verify = unknown(), hits = 0
            )
        )
        // 运行时上报为否
        assertEquals(
            RuntimeHealthAudit.Verdict.DEGRADED,
            RuntimeHealthAudit.decide(
                installOk = true, installBad = false, installPartial = false,
                effectiveOk = true, runtimeOk = false, verify = unknown(), hits = 3
            )
        )
    }

    @Test
    fun readyWhenVerifiedOrTriggered() {
        // 主动自检通过
        assertEquals(
            RuntimeHealthAudit.Verdict.READY,
            RuntimeHealthAudit.decide(
                installOk = true, installBad = false, installPartial = false,
                effectiveOk = null, runtimeOk = null,
                verify = RuntimeHealthAudit.VerifyOutcome(true, "已就绪"), hits = 0
            )
        )
        // 已实际触发
        assertEquals(
            RuntimeHealthAudit.Verdict.READY,
            RuntimeHealthAudit.decide(
                installOk = true, installBad = false, installPartial = false,
                effectiveOk = null, runtimeOk = null, verify = unknown(), hits = 1
            )
        )
    }

    @Test
    fun unverifiedWhenInstalledButNeverTriggeredAndNoVerdict() {
        assertEquals(
            RuntimeHealthAudit.Verdict.UNVERIFIED,
            RuntimeHealthAudit.decide(
                installOk = true, installBad = false, installPartial = false,
                effectiveOk = null, runtimeOk = null, verify = unknown(), hits = 0
            )
        )
    }

    @Test
    fun activityCounterAndTimestampIncrease() {
        RuntimeHealthAudit.clearActivity()
        assertEquals(0 to 0L, RuntimeHealthAudit.activityOf("AntiRevoke"))

        RuntimeHealthAudit.noteActivity("AntiRevoke", "第一次")
        val (c1, t1) = RuntimeHealthAudit.activityOf("AntiRevoke")
        assertEquals(1, c1)
        assertTrue(t1 > 0L)

        RuntimeHealthAudit.noteActivity("AntiRevoke", "第二次")
        val (c2, t2) = RuntimeHealthAudit.activityOf("AntiRevoke")
        assertEquals(2, c2)
        assertTrue(t2 >= t1)

        RuntimeHealthAudit.clearActivity()
        assertEquals(0 to 0L, RuntimeHealthAudit.activityOf("AntiRevoke"))
    }

    @Test
    fun auditRowTitleMappingAndSummaryCounts() {
        val rows = RuntimeHealthAudit.audit()
        assertTrue("审计表应包含已登记功能", rows.isNotEmpty())

        // 标题映射存在
        val swipe = rows.firstOrNull { it.id == "SwipeDeleteKeep" }
        assertNotNull("SwipeDeleteKeep 应出现在审计表中", swipe)
        assertEquals("左滑删除存记录", swipe!!.title)

        // summaryLine 计数与逐行判定自洽
        val summary = RuntimeHealthAudit.summaryLine(rows)
        val ready = rows.count { it.verdict == RuntimeHealthAudit.Verdict.READY }
        val broken = rows.count { it.verdict == RuntimeHealthAudit.Verdict.BROKEN }
        assertTrue(summary.contains("ready=$ready"))
        assertTrue(summary.contains("broken=$broken"))
        assertTrue(summary.contains("total=${rows.size}"))
    }

    @Test
    fun reportTextContainsSummaryAndRows() {
        val rows = RuntimeHealthAudit.audit()
        val text = RuntimeHealthAudit.reportText(rows)
        assertTrue(text.contains("OKK 深度生效自检"))
        assertTrue(text.contains("左滑删除存记录"))
    }
}
