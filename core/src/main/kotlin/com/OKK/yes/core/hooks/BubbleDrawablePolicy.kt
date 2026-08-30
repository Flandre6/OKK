package com.OKK.yes.core.hooks

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

internal object BubbleDrawablePolicy {
    private const val TEXT_MESSAGE_TYPE = 1
    private const val APP_MESSAGE_TYPE = 49
    private const val VOICE_MESSAGE_TYPE = 34
    /** 语音通话记录（含旧版 type=50 与新版 1000052） */
    private const val VOICE_CALL_MESSAGE_TYPE = 50
    private const val VOICE_CALL_MESSAGE_TYPE_NEW = 1000052
    /** 视频通话记录 */
    private const val VIDEO_CALL_MESSAGE_TYPE = 1000053
    private const val REFER_APP_MESSAGE_TYPE = "57"
    private val appMsgTypeRegex = Regex("<appmsg\\b[\\s\\S]*?<type>\\s*(\\d+)\\s*</type>", RegexOption.IGNORE_CASE)
    private val escapedAppMsgTypeRegex = Regex("&lt;appmsg\\b[\\s\\S]*?&lt;type&gt;\\s*(\\d+)\\s*&lt;/type&gt;", RegexOption.IGNORE_CASE)

    fun minimumWidth(): Int = 0

    fun minimumHeight(): Int = 0

    fun intrinsicWidth(): Int = -1

    fun intrinsicHeight(): Int = -1

    fun isTextBubbleClass(className: String): Boolean {
        return className.contains("MMNeat7extView")
    }

    fun supportsCustomBubble(messageType: Int, content: String = ""): Boolean {
        val t = normalizedMessageType(messageType)
        return when {
            t == TEXT_MESSAGE_TYPE -> true
            isVoiceOrCallType(t) -> true
            // 部分版本通话 type 异常，但 content 固定为 voip_content_*
            isVoipContent(content) -> true
            t == APP_MESSAGE_TYPE -> isQuotedTextAppMessage(content)
            else -> false
        }
    }

    /** 语音/通话等非文本消息：气泡容器不是 MMNeat7extView，按消息类型/内容放行。 */
    fun isNonTextBubbleType(messageType: Int, content: String = ""): Boolean {
        if (isVoiceOrCallType(normalizedMessageType(messageType))) return true
        return isVoipContent(content)
    }

    fun isVoiceOrCallType(messageType: Int): Boolean {
        return when (messageType) {
            VOICE_MESSAGE_TYPE,
            VOICE_CALL_MESSAGE_TYPE,
            VOICE_CALL_MESSAGE_TYPE_NEW,
            VIDEO_CALL_MESSAGE_TYPE -> true
            else -> false
        }
    }

    /** WeChat 通话记录 content 固定串（WeKit/Hchat 同源）。 */
    fun isVoipContent(content: String): Boolean {
        if (content.isBlank()) return false
        val c = content.trim()
        return c == "voip_content_voice" ||
            c == "voip_content_video" ||
            c.contains("voip_content_voice") ||
            c.contains("voip_content_video")
    }

    /** 语音/通话气泡用更紧的 padding，避免套用文本气泡 17dp 导致“气泡很大”。 */
    fun voiceCallContentPadding(isSend: Boolean, density: Float): Rect {
        val arrow = dp(10f, density)
        val other = dp(8f, density)
        val v = dp(5f, density)
        return if (isSend) {
            rectOf(other, v, arrow, v)
        } else {
            rectOf(arrow, v, other, v)
        }
    }

    fun shouldReplaceBackground(className: String, messageType: Int, content: String = ""): Boolean {
        if (isNonTextBubbleType(messageType, content)) return supportsCustomBubble(messageType, content)
        return isTextBubbleClass(className) && supportsCustomBubble(messageType, content)
    }

    fun shouldReplaceBackground(className: String, messageType: Int, supportsCustomBubble: Boolean): Boolean {
        // 无 content 时仅按 type 判断非文本气泡
        if (isNonTextBubbleType(messageType)) return supportsCustomBubble
        return isTextBubbleClass(className) && supportsCustomBubble
    }

    fun needsPostBindRefresh(messageType: Int, content: String): Boolean {
        if (!supportsCustomBubble(messageType, content)) return false
        // 应用消息 / 语音 / 通话：微信可能在 bind 后才异步设置背景，需延迟重刷确保皮肤生效
        return isAppMessageType(messageType) || isNonTextBubbleType(messageType)
    }

    fun normalizedMessageType(messageType: Int): Int {
        if (messageType == APP_MESSAGE_TYPE) return APP_MESSAGE_TYPE
        val lowBits = messageType and 0xFFFF
        return if (lowBits == APP_MESSAGE_TYPE) APP_MESSAGE_TYPE else messageType
    }

    fun isAppMessageType(messageType: Int): Boolean {
        return normalizedMessageType(messageType) == APP_MESSAGE_TYPE
    }

    fun hasReferMarker(content: String): Boolean {
        val normalized = content.lowercase()
        return normalized.contains("<refermsg") || normalized.contains("&lt;refermsg")
    }

    fun appMessageType(content: String): String? {
        return appMsgTypeRegex.find(content)?.groupValues?.getOrNull(1)
            ?: escapedAppMsgTypeRegex.find(content)?.groupValues?.getOrNull(1)
    }

    private fun isQuotedTextAppMessage(content: String): Boolean {
        if (content.isBlank()) return false
        if (!hasReferMarker(content)) return false
        return appMessageType(content) == REFER_APP_MESSAGE_TYPE
    }

    fun hasPaddingInsets(padding: Rect): Boolean {
        return padding.left != 0 || padding.top != 0 || padding.right != 0 || padding.bottom != 0
    }

    fun contentPaddingOrDefault(padding: Rect, isSend: Boolean, density: Float): Rect {
        if (hasPaddingInsets(padding)) {
            return rectOf(padding.left, padding.top, padding.right, padding.bottom)
        }
        val leading = dp(17.5f, density)
        val trailing = dp(12.5f, density)
        val vertical = dp(8f, density)
        return if (isSend) {
            rectOf(leading, vertical, trailing, vertical)
        } else {
            rectOf(trailing, vertical, leading, vertical)
        }
    }

    private fun dp(value: Float, density: Float): Int {
        return (value * density).roundToInt()
    }

    private fun rectOf(left: Int, top: Int, right: Int, bottom: Int): Rect {
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    /**
     * 包装气泡 Drawable：
     * - 去掉 min/intrinsic 尺寸，避免 9.png（如 172x108）把容器撑大
     * - [consumePadding]=false 时 getPadding 返回 false，避免 View.setBackground
     *   把 9-patch content padding 写进 View（语音/通话会因此暴涨）
     */
    fun withoutMinimumSize(drawable: Drawable, consumePadding: Boolean = true): Drawable {
        return NoMinimumDrawable(drawable, consumePadding)
    }

    private class NoMinimumDrawable(
        private val delegate: Drawable,
        private val consumePadding: Boolean
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            delegate.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            delegate.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            delegate.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android framework")
        override fun getOpacity(): Int {
            return runCatching { delegate.opacity }.getOrDefault(PixelFormat.TRANSLUCENT)
        }

        override fun onBoundsChange(bounds: Rect) {
            delegate.bounds = Rect(bounds)
        }

        override fun getPadding(padding: Rect): Boolean {
            if (!consumePadding) {
                padding.set(0, 0, 0, 0)
                return false
            }
            return delegate.getPadding(padding)
        }

        // 强制 0 / -1，禁止用 9.png 位图尺寸参与 measure
        override fun getMinimumWidth(): Int = 0

        override fun getMinimumHeight(): Int = 0

        override fun getIntrinsicWidth(): Int = -1

        override fun getIntrinsicHeight(): Int = -1

        override fun mutate(): Drawable {
            delegate.mutate()
            return this
        }
    }
}
