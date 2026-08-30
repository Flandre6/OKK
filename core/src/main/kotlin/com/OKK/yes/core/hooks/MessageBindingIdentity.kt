package com.OKK.yes.core.hooks

import android.view.View
import android.widget.TextView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Resolves the group sender and nickname view from a bound WeChat message row. */
internal object MessageBindingIdentity {
    data class Identity(val room: String, val sender: String, val isGroup: Boolean)

    private val fileSizeText = Regex(".*\\d+(?:\\.\\d+)?\\s*(?:B|KB|MB|GB|TB).*")
    private val fileNameText =
        Regex(".*\\.(?:docx?|xlsx?|pptx?|pdf|zip|rar|7z|txt|apk|jpg|jpeg|png|gif|mp4|mp3|m4a|wav)(?:\\s|$).*", RegexOption.IGNORE_CASE)
    private val nicknameFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private val senderMethodCache = ConcurrentHashMap<Class<*>, Method?>()

    fun resolve(message: Any): Identity? {
        val room = readString(message, "getTalker", "field_talker", "talker")?.trim() ?: return null
        if (!isChatroom(room)) {
            return room.takeIf(::isUserId)?.let { Identity(room, room, false) }
        }
        val sender = senderFromMessage(message)
            ?: senderFromContent(readString(message, "getContent", "field_content", "content"))
            ?: return null
        if (isChatroom(sender)) return null
        return Identity(room, sender, true)
    }

    fun nicknameView(holder: Any, itemView: View): TextView? {
        // view.tag.userTV 字段直取（不猜 View 树）
        // ChatEnhance 传入的 holder 可能是 adapter 的 VH 或 tag 对象，都试一遍
        val targets = buildList {
            add(holder)
            itemView.tag?.let { add(it) }
            // holder 上的 itemView 字段有时另有 tag
            runCatching {
                var c: Class<*>? = holder.javaClass
                while (c != null && c != Any::class.java) {
                    for (f in c.declaredFields) {
                        if (View::class.java.isAssignableFrom(f.type)) {
                            f.isAccessible = true
                            val v = f.get(holder) as? View ?: continue
                            v.tag?.let { add(it) }
                        }
                    }
                    c = c.superclass
                }
            }
        }
        for (target in targets) {
            val field = nicknameFieldCache.computeIfAbsent(target.javaClass, ::findNicknameField)
                ?: continue
            val direct = runCatching {
                field.isAccessible = true
                field.get(target) as? TextView
            }.getOrNull()
            if (direct != null) return direct
        }

        val candidates = ArrayList<TextView>()
        collectTextViews(itemView, candidates)
        return candidates
            .asSequence()
            .filter(::isLikelyNickname)
            .filterNot { it.text.toString().matches(Regex(".*\\d{1,2}:\\d{2}.*")) }
            .filterNot { it.text.toString().contains("小时前") || it.text.toString().contains("分钟前") }
            .filter { it.text.length <= 80 }
            .minByOrNull { it.top }
    }

    private fun isLikelyNickname(view: TextView): Boolean {
        // 群聊对方消息的 userTV 可能短暂 GONE/INVISIBLE，仍要识别（字段直取）
        if (view.visibility == View.GONE && view.text.isNullOrBlank()) return false
        if (view.text?.isNotBlank() != true) return false
        val text = view.text.toString().trim()
        if (text.length > 80) return false
        if (text.matches(Regex(".*\\d{1,2}:\\d{2}.*"))) return false
        if (text.matches(fileSizeText) || text.matches(fileNameText)) return false
        if (text.contains("未下载") || text.contains("已下载") || text.contains("下载中") ||
            text.contains("已过期") || text.contains("微信网页版") || text.contains("个人名片") ||
            text.contains("转文字")) return false
        if (text.contains("以下为新消息") || text.contains("撤回了一条消息") ||
            text.contains("小时之前") || text.contains("分钟前") || text.contains("刚刚")) return false
        return true
    }

    private fun senderFromMessage(message: Any): String? {
        val method = senderMethodCache.computeIfAbsent(message.javaClass) { clazz ->
            allMethods(clazz).firstOrNull {
                it.parameterTypes.isEmpty() &&
                    it.returnType == String::class.java &&
                    it.name in setOf("R1", "P1", "o0", "x0", "j0", "getSender", "getSendTalker")
            }?.apply { isAccessible = true }
        }
        val byMethod = runCatching { method?.invoke(message) as? String }.getOrNull()
        if (isUserId(byMethod)) return byMethod!!.trim()
        return readString(message, "getSender", "field_sender", "field_sendTalker", "sender")
            ?.trim()
            ?.takeIf(::isUserId)
    }

    private fun senderFromContent(content: String?): String? {
        val value = content.orEmpty()
        val split = listOf(":\r\n", ":\n")
            .map { value.indexOf(it) }
            .filter { it in 1..80 }
            .minOrNull()
            ?: return null
        return value.substring(0, split).trim().takeIf(::isUserId)
    }

    private fun findNicknameField(clazz: Class<*>): Field? {
        var current: Class<*>? = clazz
        var fallback: Field? = null
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (!TextView::class.java.isAssignableFrom(field.type)) continue
                val lower = field.name.lowercase()
                if (field.name == "userTV" || field.name == "brc") return field
                if (fallback == null && lower.contains("user")) fallback = field
            }
            current = current.superclass
        }
        return fallback
    }

    private fun readString(target: Any, vararg names: String): String? {
        for (name in names) {
            allMethods(target.javaClass).firstOrNull {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }?.let { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(target) as? String
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            var current: Class<*>? = target.javaClass
            while (current != null && current != Any::class.java) {
                runCatching {
                    val field = current.getDeclaredField(name)
                    field.isAccessible = true
                    field.get(target) as? String
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
                current = current.superclass
            }
        }
        return null
    }

    private fun allMethods(clazz: Class<*>): Sequence<Method> = sequence {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            yieldAll(current.declaredMethods.asSequence())
            current = current.superclass
        }
    }

    private fun collectTextViews(view: View, out: MutableList<TextView>) {
        if (view is TextView) out += view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collectTextViews(view.getChildAt(i), out)
        }
    }

    private fun isDescendantOf(view: View, root: View): Boolean {
        var current: Any? = view
        while (current is View) {
            if (current === root) return true
            current = current.parent
        }
        return false
    }

    private fun isChatroom(value: String): Boolean =
        value.endsWith("@chatroom") || value.endsWith("@im.chatroom")

    private fun isUserId(value: String?): Boolean {
        val id = value?.trim().orEmpty()
        if (id.isEmpty() || isChatroom(id)) return false
        return id.startsWith("wxid_") ||
            (id.length in 6..80 && id.matches(Regex("[a-zA-Z][\\w@.\\-]+")))
    }
}
