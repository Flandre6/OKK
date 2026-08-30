package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MessageDetailFormatterTest {
    @Test
    fun appliesTemplateWithJustNow() {
        val zone = ZoneId.of("Asia/Shanghai")
        val createTime = LocalDateTime.of(2026, 4, 27, 14, 25, 17)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val now = createTime + 30_000L

        val text = MessageDetailFormatter.format(
            info = MessageDetailInfo(createTime, 1, 1001L, 2002L),
            options = MessageDetailOptions(
                enabled = true,
                template = "\${time} \${relativeTime} \${type} \${msgId} \${msgSvrId}",
                timePattern = "HH:mm:ss"
            ),
            zoneId = zone,
            nowMillis = now
        )

        assertEquals("14:25:17 \u521a\u521a 1 1001 2002", text)
    }

    @Test
    fun appliesTemplateWithMinutesAgo() {
        val zone = ZoneId.of("Asia/Shanghai")
        val createTime = LocalDateTime.of(2026, 4, 27, 14, 25, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val now = createTime + 5 * 60_000L

        val text = MessageDetailFormatter.format(
            info = MessageDetailInfo(createTime, 1, 100L, 200L),
            options = MessageDetailOptions(
                enabled = true,
                template = "\${relativeTime}",
                timePattern = "HH:mm"
            ),
            zoneId = zone,
            nowMillis = now
        )

        assertEquals("5\u5206\u949f\u524d", text)
    }

    @Test
    fun appliesTemplateWithHoursAgo() {
        val zone = ZoneId.of("Asia/Shanghai")
        val createTime = LocalDateTime.of(2026, 4, 27, 14, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val now = createTime + 3 * 3600_000L

        val text = MessageDetailFormatter.format(
            info = MessageDetailInfo(createTime, 1, 100L, 200L),
            options = MessageDetailOptions(
                enabled = true,
                template = "\${relativeTime}",
                timePattern = "HH:mm"
            ),
            zoneId = zone,
            nowMillis = now
        )

        assertEquals("3\u5c0f\u65f6\u524d", text)
    }

    @Test
    fun appliesTemplateWithYesterday() {
        val zone = ZoneId.of("Asia/Shanghai")
        val createTime = LocalDateTime.of(2026, 4, 27, 14, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val now = createTime + 26 * 3600_000L

        val text = MessageDetailFormatter.format(
            info = MessageDetailInfo(createTime, 1, 100L, 200L),
            options = MessageDetailOptions(
                enabled = true,
                template = "\${relativeTime}",
                timePattern = "HH:mm"
            ),
            zoneId = zone,
            nowMillis = now
        )

        assertEquals("\u6628\u5929 14:00", text)
    }

    @Test
    fun appliesTemplateWithDayBeforeYesterday() {
        val zone = ZoneId.of("Asia/Shanghai")
        val createTime = LocalDateTime.of(2026, 4, 27, 14, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val now = createTime + 50 * 3600_000L

        val text = MessageDetailFormatter.format(
            info = MessageDetailInfo(createTime, 1, 100L, 200L),
            options = MessageDetailOptions(
                enabled = true,
                template = "\${relativeTime}",
                timePattern = "HH:mm"
            ),
            zoneId = zone,
            nowMillis = now
        )

        assertEquals("\u524d\u5929 14:00", text)
    }

    @Test
    fun appliesTemplateWithDaysAgoNotMonthDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val createTime = LocalDateTime.of(2026, 3, 15, 10, 30, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val text = MessageDetailFormatter.format(
            info = MessageDetailInfo(createTime, 1, 100L, 200L),
            options = MessageDetailOptions(
                enabled = true,
                template = "\${relativeTime}",
                timePattern = "HH:mm"
            ),
            zoneId = zone,
            nowMillis = createTime + 100 * 3600_000L
        )

        // 约 4 天前 → 「N天前」，不再退化为 MM-dd
        assertEquals("4\u5929\u524d", text)
    }

    @Test
    fun defaultTemplateIsAbsolutePipeRelative() {
        val zone = ZoneId.of("Asia/Shanghai")
        val createTime = LocalDateTime.of(2026, 4, 27, 14, 25, 17)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val now = createTime + 5 * 60_000L

        val text = MessageDetailFormatter.format(
            info = MessageDetailInfo(createTime, 1, 1L, 1L),
            options = MessageDetailOptions(
                enabled = true,
                template = "\${time} \${relativeTime}",
                timePattern = "MM-dd HH:mm:ss"
            ),
            zoneId = zone,
            nowMillis = now
        )

        assertEquals("04-27 14:25:17 5\u5206\u949f\u524d", text)
    }

    @Test
    fun returnsEmptyWhenDisabled() {
        val text = MessageDetailFormatter.format(
            info = MessageDetailInfo(1L, 1, 1L, 1L),
            options = MessageDetailOptions(enabled = false)
        )

        assertEquals("", text)
    }
}
