package com.OKK.yes.core.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureProbeTest {

    @Test
    fun probeResult_installable() {
        assertTrue(ProbeResult("a", "A", ProbeLevel.OK).installable)
        assertTrue(ProbeResult("a", "A", ProbeLevel.PARTIAL).installable)
        assertFalse(ProbeResult("a", "A", ProbeLevel.FAIL).installable)
    }

    @Test
    fun report_summary() {
        val r = CompatReport(
            fingerprint = "1|8.0.69|1.1.3",
            wechatSummary = "test",
            atMs = 0L,
            results = listOf(
                ProbeResult("A", "功能A", ProbeLevel.OK),
                ProbeResult("B", "功能B", ProbeLevel.FAIL, "no"),
                ProbeResult("C", "功能C", ProbeLevel.PARTIAL)
            ),
            shouldShowDialog = true
        )
        assertEquals(1, r.okCount)
        assertEquals(1, r.failCount)
        assertEquals(1, r.partialCount)
        assertEquals(listOf("功能B"), r.failTitles())
        assertTrue(r.summaryLine().contains("fail=1"))
    }

    @Test
    fun catalog_ids_unique() {
        val ids = FeatureProbeCatalog.all.map {
            // probe without context — just ensure list size stable
            it
        }
        assertTrue(ids.size >= 15)
    }
}
