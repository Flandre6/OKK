package com.OKK.yes.core.hooks

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout

internal object MessageTimeLayoutPolicy {
    private const val SYSTEM_NOTICE_TYPE = 10000
    private const val SYSTEM_NOTICE_TYPE_2 = 10002
    private const val PAT_NOTICE_TYPE = 922746929

    fun canHostTime(orientation: Int): Boolean {
        return orientation == LinearLayout.VERTICAL
    }

    fun layoutWidth(): Int {
        return ViewGroup.LayoutParams.WRAP_CONTENT
    }

    fun gravity(isSend: Boolean): Int {
        return if (isSend) Gravity.END else Gravity.START
    }

    fun bubbleClusterGravity(isSend: Boolean): Int {
        return if (isSend) Gravity.END else Gravity.START
    }

    fun insertIndexBelowAnchor(childCount: Int, anchorIndex: Int, existingTimeIndex: Int): Int {
        if (anchorIndex < 0) return childCount
        return if (existingTimeIndex in 0..anchorIndex) {
            anchorIndex
        } else {
            (anchorIndex + 1).coerceAtMost(childCount)
        }
    }

    fun shouldShowForMessageType(messageType: Int): Boolean {
        if (messageType == 285222674 || messageType == SYSTEM_NOTICE_TYPE ||
            messageType == SYSTEM_NOTICE_TYPE_2 || messageType == PAT_NOTICE_TYPE ||
            messageType == 318767153) {
            return false
        }
        return true
    }

    fun shouldShowForMessage(messageType: Int, messageContent: String, rowText: String): Boolean {
        if (!shouldShowForMessageType(messageType)) return false
        if (isSystemNoticeXml(messageContent)) return false
        if (messageContent.contains("撤回") || rowText.contains("撤回")) return false
        return !isSystemNoticeText(messageContent) && !isSystemNoticeText(rowText)
    }

    private fun isSystemNoticeXml(content: String): Boolean {
        if (content.isBlank()) return false
        return content.contains("<sysmsg") && (
            content.contains("revokemsg") || 
            content.contains("patmsg") || 
            content.contains("sysmsgtemplate")
        )
    }

    fun isSystemNoticeText(text: String): Boolean {
        if (!mightBeSystemNotice(text)) return false
        return isSelfRecallNoticeText(text) || isPatPatNoticeText(text)
    }

    private fun mightBeSystemNotice(text: String): Boolean {
        if (text.isBlank()) return false
        return text.contains("撤回") ||
            text.contains("拍了拍") ||
            text.contains("recalled", ignoreCase = true) ||
            text.contains("patmsg", ignoreCase = true)
    }

    fun isSelfRecallNoticeText(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.replace("\\s+".toRegex(), "")
        if (normalized.contains("你撤回了一条消息")) return true
        if (normalized.contains("你撤回一条消息")) return true
        if (normalized.contains("Yourecalledamessage", ignoreCase = true)) return true
        return normalized.contains("撤回") && normalized.contains("重新编辑")
    }

    fun isCustomDetailTimeText(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.trim()
        if (normalized.contains("刚刚")) return true
        if (Regex("\\d{1,3}分钟前").containsMatchIn(normalized)) return true
        if (Regex("\\d{1,3}小时前").containsMatchIn(normalized)) return true
        if (normalized.contains("昨天") || normalized.contains("前天")) return true
        return Regex("\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2}").containsMatchIn(normalized)
    }

    fun isPatPatNoticeText(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.replace("\\s+".toRegex(), "")
        if (normalized.contains("<patmsg", ignoreCase = true)) return true
        if (normalized.contains("patmsg", ignoreCase = true) && normalized.contains("template", ignoreCase = true)) return true
        if (!normalized.contains("拍了拍")) return false
        return normalized.startsWith("\"") ||
            normalized.startsWith("“") ||
            normalized.contains("拍了拍我") ||
            normalized.contains("拍了拍自己")
    }

    fun isMediaLikeMessageType(messageType: Int): Boolean {
        return messageType == 3 || messageType == 43 || messageType == 62 || messageType == 47
    }
}
