package com.OKK.yes.core.hooks.plugins

import com.OKK.yes.core.compat.DexKitSupport
import com.OKK.yes.core.compat.WeChatSelfUser

class MsgInfoWrapper(private val raw: Any) {

    // 基础属性 Getters
    fun getMsgId(): Long = getLong("field_msgId")
    fun getId(): Long = getMsgId()
    fun getMsgSvrId(): Long = getLong("field_msgSvrId")
    fun getSvrId(): Long = getMsgSvrId()
    fun getType(): Int = getInt("field_type")
    fun getStatus(): Int = getInt("field_status")
    fun getIsSend(): Int = getInt("field_isSend")
    fun isSendInt(): Int = getIsSend()
    fun isSend(): Boolean = getIsSend() == 1
    fun getCreateTime(): Long = getLong("field_createTime")
    fun getTalker(): String = getString("field_talker") ?: ""
    fun getOriginContent(): String = getString("field_content") ?: ""
    fun getRawContent(): String = getOriginContent()
    fun getImgPath(): String? = getString("field_imgPath")
    fun getLvBuffer(): ByteArray = getByteArray("field_lvbuffer") ?: byteArrayOf()
    fun getTalkerId(): Int = getInt("field_talkerId")
    fun getMsgSeq(): Long = getLong("field_msgSeq")
    fun getOrigin(): Any = raw
    fun getRaw(): Any = raw

    // 常用类型判断
    fun isText(): Boolean = getType() == 1
    fun isImage(): Boolean = getType() in setOf(3, 13, 39)
    fun isVoice(): Boolean = getType() == 34
    fun isVideo(): Boolean = getType() in setOf(43, 62)
    fun isEmoji(): Boolean = getType() in setOf(47, 1048625)
    fun isShareCard(): Boolean = getType() == 42
    fun isPat(): Boolean = getType() == 922746929
    fun isSystem(): Boolean = getType() in setOf(10000, 10002, 9999)
    fun isQuote(): Boolean = getType() == 822083633
    fun isLocation(): Boolean = getType() in setOf(48, 10002)
    fun isApp(): Boolean = getType() == 49
    fun isAppMsg(): Boolean = isApp()
    fun isLink(): Boolean = getType() in setOf(16777265, 974127153, 1040187441)
    fun isTransfer(): Boolean = getType() == 419430449
    fun isRedBag(): Boolean = getType() in setOf(436207665, 469762097)
    fun isVideoNumberVideo(): Boolean = getType() == 486539313
    fun isNote(): Boolean = getType() == 805306417
    fun isFile(): Boolean = getType() == 1090519089
    fun isRecalled(): Boolean = getType() == 268445456
    fun isVoip(): Boolean = getType() in setOf(50, 52, 53)
    fun isVoipVideo(): Boolean = isVoip() && getOriginContent() == "voip_content_video"
    fun isVoipVoice(): Boolean = isVoip() && getOriginContent() == "voip_content_voice"

    // 会话与人称判断
    fun isGroupChat(): Boolean = getTalker().endsWith("@chatroom") || getTalker().endsWith("@im.chatroom")
    fun isChatroom(): Boolean = getTalker().endsWith("@chatroom")
    fun isImChatroom(): Boolean = getTalker().endsWith("@im.chatroom")
    fun isOpenIM(): Boolean = getTalker().endsWith("@openim")
    fun isOfficialAccount(): Boolean = getTalker().startsWith("gh_")
    fun isPrivateChat(): Boolean = !isGroupChat() && !isOfficialAccount() && getTalker() != "filehelper"

    // 判断是否在群聊中 @ 了自己
    fun isAtMe(): Boolean {
        if (isSend()) return false
        val cl = DexKitSupport.classLoader ?: return false
        val ctx = DexKitSupport.appContext ?: return false
        val myWx = WeChatSelfUser.resolve(cl, ctx)
        if (myWx.isEmpty()) return false
        val source = getString("field_msgSource") ?: ""
        return source.contains("<atuserlist>") && source.contains(myWx)
    }

    // 获取实际发送人 (兼容群聊格式解析)
    fun getSendTalker(): String = getSenderWxid()
    fun getSenderWxid(): String {
        val talker = getTalker()
        if (isSend()) {
            val cl = DexKitSupport.classLoader ?: return ""
            val ctx = DexKitSupport.appContext ?: return ""
            return WeChatSelfUser.resolve(cl, ctx)
        }
        if (talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")) {
            val content = getOriginContent()
            val colonIdx = content.indexOf(":\n")
            if (colonIdx != -1) {
                val possibleWxid = content.substring(0, colonIdx)
                if (possibleWxid.isNotEmpty()) return possibleWxid
            }
        }
        return talker
    }

    // 屏蔽群聊前面的 wxid_xxx:\n 方便脚本直接匹配文本内容
    fun getContent(): String {
        val rawContent = getOriginContent()
        val talker = getTalker()
        if ((talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")) && !isSend()) {
            val colonIdx = rawContent.indexOf(":\n")
            if (colonIdx != -1) {
                return rawContent.substring(colonIdx + 2)
            }
        }
        return rawContent
    }

    // 子消息结构解析辅助
    fun getFileMsg(): FileMsg? = if (isFile()) FileMsg(getTitle = "文件消息", getSize = 0, getExt = "dat", getMd5 = "", getUrl = "", getKey = "") else null
    fun getImageMsg(): ImageMsg? = if (isImage()) ImageMsg(getAesKey = "", getCdnUrl = "", getMd5 = "", getBigImgUrl = "", getMidImgUrl = "", getThumbUrl = "") else null
    fun getQuoteMsg(): QuoteMsg? = if (isQuote()) QuoteMsg(getTitle = getRawContent(), getSendTalker = getSendTalker(), getDisplayName = "", getMsgSource = "", getContent = getRawContent(), getSvrId = getSvrId(), getTalker = getTalker(), getType = 1) else null
    fun getTransferMsg(): TransferMsg? = if (isTransfer()) TransferMsg(getTitle = "转账消息", getDes = "", getTransactionId = "", getTransferId = "", getBeginTransferTime = 0, getFeeDesc = "", getInvalidTime = 0, getPayerUsername = getSendTalker(), getReceiverUsername = getTalker()) else null
    fun getPatMsg(): PatMsg? = if (isPat()) PatMsg(getTemplate = getOriginContent(), getFromUser = getSendTalker(), getPattedUser = getTalker()) else null

    // 子消息内部类定义
    class FileMsg(val getTitle: String, val getSize: Long, val getExt: String, val getMd5: String, val getUrl: String, val getKey: String)
    class ImageMsg(val getAesKey: String, val getCdnUrl: String, val getMd5: String, val getBigImgUrl: String, val getMidImgUrl: String, val getThumbUrl: String) { fun getKey(): String = getAesKey }
    class QuoteMsg(val getTitle: String, val getSendTalker: String, val getDisplayName: String, val getMsgSource: String, val getContent: String, val getSvrId: Long, val getTalker: String, val getType: Int) { fun getOriginContent(): String = getContent }
    class TransferMsg(val getTitle: String, val getDes: String, val getTransactionId: String, val getTransferId: String, val getBeginTransferTime: Long, val getFeeDesc: String, val getInvalidTime: Long, val getPayerUsername: String, val getReceiverUsername: String)
    class PatMsg(val getTemplate: String, val getFromUser: String, val getPattedUser: String)

    private fun getField(clazz: Class<*>, fieldName: String): java.lang.reflect.Field {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                val f = current.getDeclaredField(fieldName)
                f.isAccessible = true
                return f
            } catch (e: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(fieldName)
    }

    private fun getInt(fieldName: String): Int {
        return runCatching {
            getField(raw.javaClass, fieldName).getInt(raw)
        }.getOrDefault(0)
    }

    private fun getLong(fieldName: String): Long {
        return runCatching {
            getField(raw.javaClass, fieldName).getLong(raw)
        }.getOrDefault(0L)
    }

    private fun getString(fieldName: String): String? {
        return runCatching {
            getField(raw.javaClass, fieldName).get(raw)?.toString()
        }.getOrNull()
    }

    private fun getByteArray(fieldName: String): ByteArray? {
        return runCatching {
            getField(raw.javaClass, fieldName).get(raw) as? ByteArray
        }.getOrNull()
    }
}
