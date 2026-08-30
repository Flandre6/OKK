package com.OKK.yes.core.hooks

object InputStatsFormatter {
    /**
     * 支持变量：${totalMsg} ${textMsg} ${textWord} ${emojiMsg} ${transferMsg} ${redBagMsg} ${fileMsg}
     * 兼容性：变量名不区分大小写，并兼容常见笔误/WA 风格拼写（如 ${tatalmsg} → ${totalMsg}）。
     * 使用安全纯文本替换，绝对避免正则 replace 导致 Matcher.appendReplacement 的 Illegal group reference 崩溃。
     */
    fun format(template: String, stats: InputStatsSnapshot): String {
        if (template.isEmpty()) return ""
        val replacements = mapOf(
            "totalMsg" to stats.totalMsg.toString(),
            "textMsg" to stats.textMsg.toString(),
            "textWord" to stats.textWord.toString(),
            "emojiMsg" to stats.emojiMsg.toString(),
            "transferMsg" to stats.transferMsg.toString(),
            "redBagMsg" to stats.redBagMsg.toString(),
            "fileMsg" to stats.fileMsg.toString(),
            // 兼容别名及大小写变体（例如 WA 的 tatalmsg 拼写）
            "tatalmsg" to stats.totalMsg.toString(),
            "totalmsg" to stats.totalMsg.toString(),
            "totmsg" to stats.totalMsg.toString(),
            "textmsg" to stats.textMsg.toString(),
            "textword" to stats.textWord.toString(),
            "emojimsg" to stats.emojiMsg.toString(),
            "emoji" to stats.emojiMsg.toString(),
            "transfermsg" to stats.transferMsg.toString(),
            "transfer" to stats.transferMsg.toString(),
            "redbagmsg" to stats.redBagMsg.toString(),
            "redbag" to stats.redBagMsg.toString(),
            "filemsg" to stats.fileMsg.toString(),
            "word" to stats.textWord.toString()
        )

        var result = template
        for ((key, value) in replacements) {
            val target = "\${$key}"
            if (result.contains(target, ignoreCase = true)) {
                result = result.replace(target, value, ignoreCase = true)
            }
        }
        return result
    }

    fun normalizeForDate(snapshot: InputStatsSnapshot, targetDateKey: String): InputStatsSnapshot {
        return if (snapshot.dateKey == targetDateKey) {
            snapshot
        } else {
            InputStatsSnapshot(dateKey = targetDateKey)
        }
    }

    fun addOutgoing(snapshot: InputStatsSnapshot, type: Int, content: String): InputStatsSnapshot {
        val rawType = type and 0xFFFF
        val wordDelta = if (rawType == 1 && content.isNotBlank()) content.length else 0
        return snapshot.copy(
            totalMsg = snapshot.totalMsg + 1,
            textMsg = if (rawType == 1) snapshot.textMsg + 1 else snapshot.textMsg,
            textWord = snapshot.textWord + wordDelta,
            emojiMsg = if (rawType == 47) snapshot.emojiMsg + 1 else snapshot.emojiMsg,
            transferMsg = if (rawType == 419430449 || content.contains("微信转账")) snapshot.transferMsg + 1 else snapshot.transferMsg,
            redBagMsg = if (rawType == 436207665 || content.contains("微信红包")) snapshot.redBagMsg + 1 else snapshot.redBagMsg,
            fileMsg = if (rawType == 1090519089 || rawType == 6) snapshot.fileMsg + 1 else snapshot.fileMsg
        )
    }
}

class RecentInputStatsDeduplicator(private val maxSize: Int = 100) {
    private val set = HashSet<String>()
    private val queue = ArrayDeque<String>()

    @Synchronized
    fun shouldCount(id: String): Boolean {
        if (id.isEmpty()) return true
        if (set.contains(id)) return false
        if (queue.size >= maxSize) {
            val oldest = queue.removeFirst()
            set.remove(oldest)
        }
        queue.addLast(id)
        set.add(id)
        return true
    }
}
