package com.OKK.yes.core.hooks

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ChatTimeFormatter {
    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    /** 默认：月日 时分秒|相对时间（相对时间在此兜底为「刚刚」） */
    fun format(createTime: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val millis = normalizeEpochMillis(createTime)
        val dateTime = Instant.ofEpochMilli(millis).atZone(zoneId)
        return "${dateTime.format(formatter)}|刚刚"
    }

    private fun normalizeEpochMillis(value: Long): Long {
        return if (value in 1..9_999_999_999L) value * 1000L else value
    }
}
