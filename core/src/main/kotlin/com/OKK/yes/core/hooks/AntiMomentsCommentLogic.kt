package com.OKK.yes.core.hooks

/**
 * 朋友圈评论防撤回逻辑（可单测）。
 *
 * 微信侧主要两路：
 * 1. 表 `SnsComment`：`commentflag |= 1` 软删，或 `DELETE FROM SnsComment`
 * 2. `SnsInfo.attrBuf` 内 `CommentUserList` 被 remove
 *
 * 策略（对齐 WeKit / Hchat）：
 * - 拦删除并保留行
 * - 用 bit8(256) 记「已拦截」
 * - 正文前缀 `[已删除]`
 */
object AntiMomentsCommentLogic {
    const val COMMENT_TABLE = "SnsComment"
    const val SNS_TABLE = "SnsInfo"
    const val DELETED_PREFIX = "[已删除]"
    private const val LEGACY_DELETED_PREFIX = "(已删除)"

    /** 自用标记：bit8，微信未占用 */
    const val INTERCEPTED_FLAG = 256

    /** 微信删除位：bit0 */
    const val WECHAT_DELETED_BIT = 1

    /** curActionBuf (b26) 评论正文 protobuf field number */
    const val ACTION_CONTENT_FIELD = 8

    /** CommentUserList 项 (v26) 评论正文 protobuf field number */
    const val COMMENT_CONTENT_FIELD = 5

    fun isCommentTable(table: String?): Boolean =
        table.equals(COMMENT_TABLE, ignoreCase = true)

    fun isSnsTable(table: String?): Boolean =
        table.equals(SNS_TABLE, ignoreCase = true)

    /** 物理删除评论行 */
    fun shouldBlockDelete(table: String?): Boolean = isCommentTable(table)

    /**
     * 是否为「标记评论已删」的 SQL。
     * 例：`update SnsComment set commentflag = 1 where snsID = ...`
     */
    fun isMarkDeletedSql(sql: String?): Boolean {
        val s = sql.orEmpty()
        if (!s.contains("SnsComment", ignoreCase = true)) return false
        if (!s.contains("commentflag", ignoreCase = true)) return false
        return Regex(
            """commentflag\s*=\s*[12]\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(s)
    }

    fun shouldBlockExecSql(table: String?, sql: String?): Boolean {
        if (isCommentTable(table) && isMarkDeletedSql(sql)) return true
        if (table.isNullOrBlank() && isMarkDeletedSql(sql)) return true
        val s = sql.orEmpty()
        if (s.contains("delete from SnsComment", ignoreCase = true)) return true
        return false
    }

    /** ContentValues 把 commentflag 写成删除态 */
    fun isDeleteFlagUpdate(commentFlag: Int?): Boolean {
        return commentFlag != null && (
            commentFlag == 1 ||
                commentFlag == 2 ||
                (commentFlag and WECHAT_DELETED_BIT) != 0
            )
    }

    fun isWechatDeleted(flag: Int): Boolean = (flag and WECHAT_DELETED_BIT) != 0

    fun isIntercepted(flag: Int): Boolean = (flag and INTERCEPTED_FLAG) != 0

    /**
     * 清微信删除位，打上已拦截位。
     * 这样 UI 不再走「系统已删除」样式，仍能区分我们处理过的行。
     */
    fun restoreFlagKeepIntercepted(flag: Int): Int =
        (flag and WECHAT_DELETED_BIT.inv()) or INTERCEPTED_FLAG

    /** 拦截删除标记时改回 0（兼容旧路径） */
    fun restoreCommentFlag(): Int = 0

    fun markDeletedContent(original: String?): String {
        val text = original.orEmpty()
        if (text.startsWith(DELETED_PREFIX)) return text
        if (text.startsWith(LEGACY_DELETED_PREFIX)) {
            return DELETED_PREFIX + text.removePrefix(LEGACY_DELETED_PREFIX)
        }
        return if (text.isEmpty()) DELETED_PREFIX else "$DELETED_PREFIX $text"
    }

    fun needsDeletedPrefix(text: String?): Boolean {
        if (text == null) return true
        return !text.startsWith(DELETED_PREFIX) &&
            !text.startsWith(LEGACY_DELETED_PREFIX)
    }

    /**
     * 在 protobuf 二进制上给某个 length-delimited string 字段加前缀。
     * 字段号按微信协议固定（正文 field 5/8），不依赖混淆字段名。
     */
    fun prependProtoStringField(buf: ByteArray?, fieldNumber: Int, prefix: String): ByteArray? {
        if (buf == null || buf.isEmpty() || fieldNumber <= 0) return buf
        if (prefix.isEmpty()) return buf
        return runCatching {
            ProtoStringField.prepend(buf, fieldNumber, prefix)
        }.getOrDefault(buf)
    }

    fun injectDeletedMarkerIntoActionBuf(buf: ByteArray?): ByteArray {
        if (buf == null || buf.isEmpty()) return buf ?: ByteArray(0)
        // 已有标记则不动
        val asTextProbe = runCatching { String(buf, Charsets.UTF_8) }.getOrNull()
        if (asTextProbe != null && asTextProbe.contains(DELETED_PREFIX)) return buf
        return prependProtoStringField(buf, ACTION_CONTENT_FIELD, "$DELETED_PREFIX ") ?: buf
    }
}

/**
 * 极简 protobuf wire 工具：只处理 varint / length-delimited，给指定 string 字段加前缀。
 */
internal object ProtoStringField {
    fun prepend(buf: ByteArray, fieldNumber: Int, prefix: String): ByteArray {
        if (prefix.isEmpty()) return buf
        val targetTag = (fieldNumber shl 3) or 2 // wire type 2 = length-delimited
        val out = java.io.ByteArrayOutputStream(buf.size + prefix.toByteArray().size + 8)
        var i = 0
        var replaced = false
        while (i < buf.size) {
            val tagStart = i
            val (tag, tagEnd) = readVarint(buf, i) ?: break
            i = tagEnd
            val wireType = (tag and 7).toInt()
            val field = (tag ushr 3).toInt()
            when (wireType) {
                0 -> { // varint
                    val (_, end) = readVarint(buf, i) ?: break
                    out.write(buf, tagStart, end - tagStart)
                    i = end
                }
                1 -> { // 64-bit
                    if (i + 8 > buf.size) break
                    out.write(buf, tagStart, 8 + (i - tagStart))
                    i += 8
                }
                2 -> { // length-delimited
                    val (lenLong, lenEnd) = readVarint(buf, i) ?: break
                    val len = lenLong.toInt()
                    i = lenEnd
                    if (i + len > buf.size) break
                    if (!replaced && field == fieldNumber && tag.toInt() == targetTag) {
                        val old = buf.copyOfRange(i, i + len)
                        val oldStr = runCatching { String(old, Charsets.UTF_8) }.getOrNull()
                        if (oldStr != null &&
                            (oldStr.startsWith(AntiMomentsCommentLogic.DELETED_PREFIX) ||
                                oldStr.contains(AntiMomentsCommentLogic.DELETED_PREFIX))
                        ) {
                            out.write(buf, tagStart, (i + len) - tagStart)
                        } else {
                            val newBytes = if (oldStr != null) {
                                (prefix + oldStr).toByteArray(Charsets.UTF_8)
                            } else {
                                prefix.toByteArray(Charsets.UTF_8) + old
                            }
                            writeVarint(out, targetTag.toLong())
                            writeVarint(out, newBytes.size.toLong())
                            out.write(newBytes)
                        }
                        replaced = true
                    } else {
                        out.write(buf, tagStart, (i + len) - tagStart)
                    }
                    i += len
                }
                5 -> { // 32-bit
                    if (i + 4 > buf.size) break
                    out.write(buf, tagStart, 4 + (i - tagStart))
                    i += 4
                }
                else -> {
                    // 未知类型，整段原样返回避免损坏
                    return buf
                }
            }
        }
        if (!replaced) {
            // 字段不存在：追加一个带前缀的空内容字段（仅标记）
            writeVarint(out, targetTag.toLong())
            val bytes = prefix.trim().toByteArray(Charsets.UTF_8)
            writeVarint(out, bytes.size.toLong())
            out.write(bytes)
        }
        // 若循环提前 break 且未写完，回退原 buf
        if (i < buf.size && !replaced) return buf
        if (i < buf.size) {
            out.write(buf, i, buf.size - i)
        }
        return out.toByteArray()
    }

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < buf.size && shift < 64) {
            val b = buf[i].toInt() and 0xff
            i++
            result = result or ((b and 0x7f).toLong() shl shift)
            if ((b and 0x80) == 0) return result to i
            shift += 7
        }
        return null
    }

    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7fL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
    }
}
