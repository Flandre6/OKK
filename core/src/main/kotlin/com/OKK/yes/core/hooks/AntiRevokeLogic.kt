package com.OKK.yes.core.hooks

data class RevokeUpdate(
    val table: String,
    val msgId: Long,
    val type: Int,
    val content: String
)

/**
 * 缓存原消息（主键用内部 id，不靠备注）。
 */
data class OriginalMessage(
    val type: Int,
    val content: String,
    val talker: String?,
    val createTime: Long,
    val sender: String? = null,
    val msgSvrId: Long = 0L
)

sealed class RevokeAction {
    data object Ignore : RevokeAction()
    data class KeepRevokeNotice(val content: String) : RevokeAction()
}

/**
 * 防撤回提示逻辑（可选模板）。
 *
 * **默认**：直接用微信 `replacemsg`（里面已是备注/昵称），只加 `[已阻止]`。
 * 不要自己猜「对方」——不要错误清洗变量导致名字丢失。
 *
 * **自定义模板** 时再填 `{name}` / `{content}`：
 * - `{name}`：优先从微信系统句抠出的备注/昵称
 * - `{content}`：仅干净文字，路径/图片绝不写入
 */
object AntiRevokeLogic {
    private const val MESSAGE_TABLE = "message"
    private const val RECALL_TYPE = 10000

    const val DEFAULT_NOTICE_TEMPLATE = "{name}撤回了一条消息"

    private val revokeTexts = listOf(
        "撤回了一条消息",
        "撤回一条消息",
        "消息已撤回"
    )

    private val selfRevokeTexts = listOf(
        "你撤回了一条消息",
        "你撤回一条消息"
    )

    fun analyze(
        update: RevokeUpdate,
        original: OriginalMessage?,
        keepSelf: Boolean = false,
        noticeTemplate: String = DEFAULT_NOTICE_TEMPLATE,
        showNotice: Boolean = true,
        sessionTalker: String? = null,
        displayName: String? = null
    ): RevokeAction {
        if (update.table != MESSAGE_TABLE) return RevokeAction.Ignore
        if (update.type != RECALL_TYPE) return RevokeAction.Ignore
        if (!looksLikeRevoke(update.content)) return RevokeAction.Ignore

        val self = isSelfRevoke(update.content)
        if (self && !keepSelf) return RevokeAction.Ignore

        val replacement = extractReplacement(update.content) ?: update.content
        if (!showNotice) {
            return RevokeAction.KeepRevokeNotice("")
        }

        val template = noticeTemplate.ifBlank { DEFAULT_NOTICE_TEMPLATE }
        val useCustomTemplate = isCustomTemplate(template)

        // —— 默认路径：微信系统句已带备注/昵称，原样保留（和改变量前一样稳）——
        if (!useCustomTemplate) {
            val notice = when {
                self -> markBlocked("你撤回了一条消息")
                // 系统句干净且含「撤回」→ 直接用
                isUsableWechatRevokeSentence(replacement) -> markBlocked(replacement)
                // 否则再拼模板（名字从系统句 / 通讯录取）
                else -> {
                    val name = resolveName(self, replacement, update.content, displayName)
                    formatNotice(DEFAULT_NOTICE_TEMPLATE, name, "")
                }
            }
            return RevokeAction.KeepRevokeNotice(notice)
        }

        // —— 自定义模板：只填安全变量 ——
        val name = resolveName(self, replacement, update.content, displayName)
        val body = resolveContentSnippet(original, replacement)
        return RevokeAction.KeepRevokeNotice(formatNotice(template, name, body))
    }

    /** 是否用户自定义了模板（含 {content} 或非默认文案） */
    fun isCustomTemplate(template: String): Boolean {
        val t = template.trim()
        if (t.isEmpty()) return false
        if (t == DEFAULT_NOTICE_TEMPLATE) return false
        // 旧默认也算「非自定义拼装」，走系统句路径
        if (t == "{name}撤回了上一条消息 {content}") return false
        if (t == "{name}撤回了上一条消息") return false
        return true
    }

    /**
     * {name}：微信系统句里的名字（已是备注优先）> 外部 displayName > 对方
     */
    fun resolveName(
        self: Boolean,
        replacement: String,
        fullContent: String,
        displayName: String?
    ): String {
        if (self) return "你"
        extractNameFromRevokeText(replacement)?.let { return it }
        extractNameFromRevokeText(fullContent)?.let { return it }
        cleanHumanName(displayName)?.let { return it }
        return "对方"
    }

    fun extractNameFromRevokeText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val raw = text.trim()

        extractXmlTag(raw, "replacemsg")?.let { inner ->
            extractNameFromRevokeText(inner)?.let { return it }
        }

        // "Name" recalled
        Regex("""["“'「]([^"”'」]{1,40})["”'」]\s*recalled""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)
            ?.let { cleanHumanName(it) }?.let { return it }

        // Name撤回了… / "Name"撤回了…
        Regex("""^["“'「]?([^"”'」\n]{1,40}?)["”'」]?\s*撤回""")
            .find(raw)?.groupValues?.getOrNull(1)
            ?.let { cleanHumanName(it) }
            ?.takeIf { it != "你" }
            ?.let { return it }

        Regex("""["“'「]?([^"”'」\n]{1,40}?)["”'」]?\s*撤回了""")
            .find(raw)?.groupValues?.getOrNull(1)
            ?.let { cleanHumanName(it) }
            ?.takeIf { it != "你" && !it.contains("sysmsg", ignoreCase = true) }
            ?.let { return it }

        return null
    }

    fun cleanHumanName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim().trim('"', '“', '”', '\'', '「', '」')
        if (t.contains(" : ")) t = t.substringBefore(" : ").trim()
        if (t.isEmpty() || t == "对方") return null
        if (t == "你") return "你"
        if (looksLikeLocalPath(t) || looksLikeMediaPayload(t)) return null
        if (t.startsWith("wxid_", ignoreCase = true)) return null
        if (t.contains("@chatroom") || t.contains("@im.chatroom")) return null
        if (t.matches(Regex("(?i)^[a-f0-9]{32,}$"))) return null
        if (t.length > 40) t = t.take(40)
        return t
    }

    /** 微信自带的撤回提示句，可直接展示 */
    fun isUsableWechatRevokeSentence(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("<")) return false
        if (looksLikeLocalPath(t) || looksLikeMediaPayload(t)) return false
        if (t.startsWith("wxid_", ignoreCase = true)) return false
        // 中英文系统句
        if (revokeTexts.any { t.contains(it) }) return true
        if (t.contains("recalled a message", ignoreCase = true)) return true
        return false
    }

    fun resolveContentSnippet(original: OriginalMessage?, replacement: String = ""): String {
        if (original != null) {
            if (looksLikeLocalPath(original.content) || looksLikeMediaPayload(original.content)) {
                return ""
            }
            if (!isTextLike(original.type)) return ""
            return sanitizeTextContent(original.content)
        }
        if (looksLikeLocalPath(replacement) || looksLikeMediaPayload(replacement)) return ""
        return extractPlainContent(replacement)
            ?.takeIf { !looksLikeLocalPath(it) && !looksLikeRevoke(it) }
            .orEmpty()
    }

    fun isTextLike(type: Int): Boolean = type == 1 || type == 11

    fun typeLabel(type: Int): String {
        return when (type) {
            1, 11 -> "一条文本"
            3, 13 -> "一张图片"
            34 -> "一条语音"
            43, 44, 62 -> "一个视频"
            47, 1048625 -> "一个表情"
            else -> ""
        }
    }

    fun looksLikeMediaPayload(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (looksLikeLocalPath(t)) return true
        if (t.startsWith("<msg") || t.startsWith("<?xml") || t.contains("<img ")) return true
        if (t.contains("cdnmidimgurl", ignoreCase = true)) return true
        if (t.contains("aeskey", ignoreCase = true) && t.contains("length")) return true
        if (t.matches(Regex("(?i)^[a-f0-9]{32,}(\\.[a-z0-9]+)?$"))) return true
        return false
    }

    fun looksLikeLocalPath(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.contains("xwechat_files", ignoreCase = true)) return true
        if (t.contains("RWTemp", ignoreCase = true)) return true
        if (t.contains("MicroMsg", ignoreCase = true) && (t.contains('/') || t.contains('\\'))) {
            return true
        }
        if (t.contains("/storage/") || t.contains("/sdcard/") ||
            t.contains("/data/") || t.contains("emulated")
        ) {
            return true
        }
        if (t.matches(Regex("(?i)^[A-Z]:[/\\\\].*"))) return true
        if (t.contains('\\') && (t.contains(':') || t.contains("WeChat", ignoreCase = true))) {
            return true
        }
        val lower = t.lowercase()
        if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".mp4") ||
            lower.endsWith(".amr") || lower.endsWith(".silk") || lower.endsWith(".dat")
        ) {
            if (t.contains('/') || t.contains('\\') || t.length >= 32) return true
        }
        return false
    }

    fun formatNotice(template: String, name: String, content: String): String {
        val raw = template.ifBlank { DEFAULT_NOTICE_TEMPLATE }
        val safeName = when {
            name == "你" -> "你"
            else -> cleanHumanName(name) ?: "对方"
        }
        val safeContent = content.trim().let { c ->
            when {
                c.isEmpty() -> ""
                looksLikeLocalPath(c) || looksLikeMediaPayload(c) -> ""
                looksLikeRevoke(c) && c.length < 48 -> ""
                c.startsWith("wxid_", ignoreCase = true) -> ""
                else -> c.take(80)
            }
        }
        var filled = raw
            .replace("{name}", safeName)
            .replace("{content}", safeContent)
            .replace("\${name}", safeName)
            .replace("\${content}", safeContent)
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
        if (filled.isEmpty()) filled = "${safeName}撤回了一条消息"
        return markBlocked(filled)
    }

    fun looksLikeRevoke(content: String): Boolean {
        if (revokeTexts.any { content.contains(it) }) return true
        if (content.contains("recalled a message", ignoreCase = true)) return true
        if (content.contains("revokemsg", ignoreCase = true)) return true
        if (content.contains("<sysmsg", ignoreCase = true) && content.contains("revoke", ignoreCase = true)) {
            return true
        }
        if (content.contains("MM_DATA_SYSCMD", ignoreCase = true) &&
            content.contains("RECALL", ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    fun isSelfRevoke(content: String): Boolean {
        if (selfRevokeTexts.any { content.contains(it) }) return true
        return content.contains("you recalled a message", ignoreCase = true)
    }

    fun isChatRoom(id: String): Boolean {
        val t = id.trim()
        return t.endsWith("@chatroom") || t.endsWith("@im.chatroom")
    }

    fun isInternalId(id: String): Boolean {
        val t = id.trim()
        if (t.isEmpty() || t.length > 80) return false
        if (looksLikeLocalPath(t)) return false
        if (t.any { it.isWhitespace() }) return false
        if (t.startsWith("wxid_")) return true
        if (t.contains("@")) return true
        if (t in setOf("filehelper", "fmessage", "medianote", "newsapp", "weixin")) return true
        if (t.matches(Regex("^[A-Za-z][A-Za-z0-9_-]{2,}$"))) return true
        return false
    }

    fun extractNewMsgId(xmlOrContent: String): Long {
        extractXmlTag(xmlOrContent, "newmsgid")?.toLongOrNull()?.let { if (it > 0) return it }
        Regex("""newmsgid["\s:=]+(\d+)""", RegexOption.IGNORE_CASE)
            .find(xmlOrContent)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.let { if (it > 0) return it }
        return 0L
    }

    fun extractSession(xmlOrContent: String): String? {
        return extractXmlTag(xmlOrContent, "session")?.takeIf { it.isNotBlank() }
    }

    fun extractGroupSender(content: String?): String? {
        val t = content?.trim().orEmpty()
        if (t.isEmpty()) return null
        val idx = t.indexOf(":\n")
        if (idx in 1..64) {
            val head = t.substring(0, idx).trim()
            if (isInternalId(head)) return head
        }
        val idx2 = t.indexOf(':')
        if (idx2 in 1..64 && !t.substring(0, idx2).contains(' ')) {
            val head = t.substring(0, idx2).trim()
            if (isInternalId(head) && t.length > idx2 + 1) return head
        }
        return null
    }

    fun resolveSenderWxId(
        self: Boolean,
        original: OriginalMessage?,
        sessionTalker: String? = null,
        revokeXml: String = ""
    ): String? {
        if (self) return null
        original?.sender?.takeIf { isInternalId(it) }?.let { return it }
        extractGroupSender(original?.content)?.let { return it }
        original?.talker?.takeIf { isInternalId(it) && !isChatRoom(it) }?.let { return it }
        sessionTalker?.takeIf { isInternalId(it) && !isChatRoom(it) }?.let { return it }
        extractXmlTag(revokeXml, "session")?.takeIf { isInternalId(it) && !isChatRoom(it) }
            ?.let { return it }
        return null
    }

    fun sanitizeTextContent(content: String): String {
        var t = content.trim()
        if (t.isEmpty() || t.startsWith("<")) return ""
        if (looksLikeLocalPath(t) || looksLikeMediaPayload(t)) return ""
        if (looksLikeRevoke(t) && t.length < 48) return ""
        extractGroupSender(t)?.let { sender ->
            t = t.removePrefix("$sender:\n").removePrefix("$sender:").trim()
        }
        if (looksLikeLocalPath(t) || t.startsWith("<")) return ""
        return t.take(80)
    }

    fun sanitizeDisplayName(raw: String?): String = cleanHumanName(raw) ?: "对方"

    private fun extractReplacement(content: String): String? {
        val cdata = Regex(
            "<replacemsg>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</replacemsg>",
            RegexOption.DOT_MATCHES_ALL
        ).find(content)?.groupValues?.getOrNull(1)?.trim()
        if (!cdata.isNullOrBlank()) return cdata

        return Regex("<replacemsg>(.*?)</replacemsg>", RegexOption.DOT_MATCHES_ALL)
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractPlainContent(text: String): String? {
        val t = text.trim()
        if (t.isBlank() || t.startsWith("<")) return null
        if (looksLikeLocalPath(t)) return null
        if (looksLikeRevoke(t) && t.length < 40) return null
        if (t.contains("recalled a message", ignoreCase = true)) return null
        return t.take(80)
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val cdata = Regex(
            "<$tag>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</$tag>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(xml)?.groupValues?.getOrNull(1)?.trim()
        if (!cdata.isNullOrBlank()) return cdata
        return Regex(
            "<$tag>(.*?)</$tag>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun markBlocked(content: String): String {
        val marker = "[已阻止]"
        val base = when {
            content.isBlank() -> "已阻止撤回"
            looksLikeLocalPath(content) || looksLikeMediaPayload(content) -> "已阻止撤回"
            content.startsWith("<") -> "已阻止撤回"
            else -> content.trim()
        }
        return if (base.contains(marker)) base else "$base$marker"
    }
}

object MediaTableProtectionPolicy {
    private val protectedTables = listOf("ImgInfo2", "voiceinfo", "videoinfo2", "WxFileIndex2")

    fun shouldBlock(methodName: String, table: String): Boolean {
        if (!isProtectedMediaTable(table)) return false
        return methodName == "delete"
    }

    private fun isProtectedMediaTable(table: String): Boolean {
        return protectedTables.any { table.contains(it, ignoreCase = true) }
    }
}
