package com.OKK.yes.core.hooks

import android.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class MessageDetailOptions(
    val enabled: Boolean = true,
    val template: String = "\${time} \${relativeTime}",
    val timePattern: String = "MM-dd HH:mm:ss",
    val textSizeSp: Float = 12f,
    /** 兼容旧版单边距；左右分设时优先 left/right */
    val horizontalMarginDp: Int = 0,
    val leftMarginDp: Int = 0,
    val rightMarginDp: Int = 0,
    val textColor: Int = 0xCCFFFFFF.toInt(),
    val textColorLight: Int = 0xE6000000.toInt(),
    val textColorDark: Int = 0xCCFFFFFF.toInt(),
    /** message_details_click_show：点击气泡才显示 */
    val clickToShow: Boolean = false
) {
    fun marginStart(isSend: Boolean): Int {
        return if (leftMarginDp != 0 || rightMarginDp != 0) {
            if (isSend) rightMarginDp else leftMarginDp
        } else {
            horizontalMarginDp
        }
    }

    fun marginEnd(isSend: Boolean): Int {
        return if (leftMarginDp != 0 || rightMarginDp != 0) {
            if (isSend) leftMarginDp else rightMarginDp
        } else {
            horizontalMarginDp
        }
    }

    fun resolveTextColor(nightMode: Boolean): Int {
        return if (nightMode) textColorDark else textColorLight
    }
}

data class MessageDetailInfo(
    val createTime: Long,
    val type: Int,
    val msgId: Long,
    val msgSvrId: Long
)

object MessageDetailFormatter {
    fun format(
        info: MessageDetailInfo,
        options: MessageDetailOptions,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        if (!options.enabled) return ""
        val millis = normalizeEpochMillis(info.createTime)
        val dateTime = Instant.ofEpochMilli(millis).atZone(zoneId)
        val time = formatTime(dateTime, options.timePattern)
        return options.template
            .replace("\${time}", time)
            .replace("\${relativeTime}", relativeTime(millis, nowMillis))
            .replace("\${type}", info.type.toString())
            .replace("\${msgId}", info.msgId.toString())
            .replace("\${msgSvrId}", info.msgSvrId.toString())
            .replace("\${atUserList}", "")
            .trim()
    }

    fun parseColor(value: String?, fallback: Int): Int {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        val normalized = if (raw.startsWith("#")) raw else "#$raw"
        return runCatching { Color.parseColor(normalized) }.getOrDefault(fallback)
    }

    /**
     * 相对时间：始终是「多久前 / 昨天 / 前天 / N天前」，不再退化成月日
     * （旧逻辑 3 天以后返回 MM-dd，和左侧绝对时间撞样式，群里老消息看起来像「相对时间变月日」）。
     */
    private fun relativeTime(msgMillis: Long, nowMillis: Long): String {
        val zone = ZoneId.systemDefault()
        val msgZdt = Instant.ofEpochMilli(msgMillis).atZone(zone)
        val nowZdt = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val diff = nowMillis - msgMillis
        if (diff < 0L) return "刚刚"
        if (diff < 60_000L) return "刚刚"
        val minutes = diff / 60_000L
        if (minutes < 60L) return "${minutes}分钟前"

        // 按日历日差，避免跨午夜把「昨天」算成「N小时前」或反之
        val dayDiff = java.time.temporal.ChronoUnit.DAYS.between(
            msgZdt.toLocalDate(),
            nowZdt.toLocalDate()
        )
        if (dayDiff == 0L) {
            val hours = minutes / 60L
            return if (hours < 1L) "${minutes}分钟前" else "${hours}小时前"
        }
        if (dayDiff == 1L) return "昨天 ${formatHHmm(msgZdt)}"
        if (dayDiff == 2L) return "前天 ${formatHHmm(msgZdt)}"
        if (dayDiff < 30L) return "${dayDiff}天前"
        if (dayDiff < 365L) {
            val months = dayDiff / 30L
            return if (months <= 1L) "1个月前" else "${months}个月前"
        }
        val years = dayDiff / 365L
        return if (years <= 1L) "1年前" else "${years}年前"
    }

    private fun formatHHmm(zdt: ZonedDateTime): String =
        zdt.format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun formatTime(dateTime: ZonedDateTime, pattern: String): String {
        val weekday = when (dateTime.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
        val javaPattern = if (pattern.contains("周")) {
            pattern.replace("周一", "'$weekday'")
        } else {
            pattern
        }
        return dateTime.format(DateTimeFormatter.ofPattern(javaPattern))
    }

    private fun normalizeEpochMillis(value: Long): Long {
        return if (value in 1..9_999_999_999L) value * 1000L else value
    }
}
