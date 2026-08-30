package com.OKK.yes.core.hooks

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object MessageSwipePolicy {
    enum class Direction { None, Left, Right }

    fun shouldStartDrag(deltaX: Float, deltaY: Float, touchSlop: Int, allowLeft: Boolean, allowRight: Boolean): Boolean {
        val horizontalSlop = max(4f, touchSlop * 0.75f)
        if (abs(deltaX) <= horizontalSlop || abs(deltaX) <= abs(deltaY)) return false
        if (deltaX < 0f && allowLeft) return true
        if (deltaX > 0f && allowRight) return true
        return false
    }

    fun clampTranslation(deltaX: Float, maxDistance: Int, allowLeft: Boolean, allowRight: Boolean): Float {
        val max = maxDistance.toFloat()
        var x = deltaX
        if (!allowLeft && x < 0f) x = 0f
        if (!allowRight && x > 0f) x = 0f
        return max(-max, min(max, x))
    }

    fun resolveAction(finalDeltaX: Float, threshold: Int, allowLeft: Boolean, allowRight: Boolean): Direction {
        if (finalDeltaX < -threshold && allowLeft) return Direction.Left
        if (finalDeltaX > threshold && allowRight) return Direction.Right
        return Direction.None
    }

    /** 兼容旧单测 / 旧调用：仅左滑 */
    fun shouldStartDrag(deltaX: Float, deltaY: Float, touchSlop: Int): Boolean {
        return shouldStartDrag(deltaX, deltaY, touchSlop, allowLeft = true, allowRight = false)
    }

    fun clampTranslation(deltaX: Float, maxDistance: Int): Float {
        return clampTranslation(deltaX, maxDistance, allowLeft = true, allowRight = false)
    }

    fun shouldTriggerQuote(finalDeltaX: Float, threshold: Int): Boolean {
        return resolveAction(finalDeltaX, threshold, allowLeft = true, allowRight = false) == Direction.Left
    }

    /**
     * 引用方法候选打分：越高越优先。
     * 过滤明显无关的 is/get/set 只读方法，优先 boolean 返回 + 短名。
     */
    fun quoteMethodScore(methodName: String, paramCount: Int, booleanReturn: Boolean): Int {
        val n = methodName.lowercase()
        if (n.startsWith("get") || n.startsWith("is") || n.startsWith("has") ||
            n.startsWith("can") || n.startsWith("should") || n.startsWith("equals") ||
            n == "hashcode" || n == "tostring" || n == "compareto"
        ) {
            return -1000
        }
        var score = 0
        if (booleanReturn) score += 50
        if (paramCount == 1) score += 20 else if (paramCount == 2) score += 10
        if (n.contains("quote") || n.contains("refer") || n.contains("reply")) score += 80
        if (n == "v0" || n == "d" || n == "a" || n == "b" || n == "c") score += 30
        if (n.length <= 3) score += 15
        if (n.length <= 2) score += 10
        return score
    }
}
