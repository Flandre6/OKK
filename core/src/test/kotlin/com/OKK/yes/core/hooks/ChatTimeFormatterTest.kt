package com.OKK.yes.core.hooks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChatTimeFormatterTest {
    @Test
    fun formatsWechatStyleCustomTimestamp() {
        val zone = ZoneId.of("Asia/Shanghai")
        val createTime = LocalDateTime.of(2026, 4, 27, 14, 25, 17)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        assertEquals("04-27 14:25:17|刚刚", ChatTimeFormatter.format(createTime, zone))
    }

    @Test
    fun acceptsSecondPrecisionEpochs() {
        val zone = ZoneId.of("Asia/Shanghai")
        val createTimeSeconds = LocalDateTime.of(2026, 7, 5, 8, 9, 10)
            .atZone(zone)
            .toEpochSecond()

        assertEquals("07-05 08:09:10|刚刚", ChatTimeFormatter.format(createTimeSeconds, zone))
    }
}
