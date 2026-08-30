package com.OKK.yes.core.hooks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Process
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.Log
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * 群员头衔：
 * - 仅群聊 + 对方消息
 * - holder.userTV / brc 上画徽章
 * - 角色：field_roomowner=群主；getChatroomData flags&2048=管理员；否则成员
 * - 微信 bind 后 setText 会冲掉 span → 对打了 TAG 的 TextView 回写
 *
 * 配置：`member_title`（默认 false）；`member_title_show_member`（默认 true）
 */
object MemberTitleHook {
    private const val TAG = "OKK-MemberTitle"
    private const val KEY = "member_title"
    private const val KEY_SHOW_MEMBER = "member_title_show_member"
    /** 与 RealName 的 0x7E000001 错开 */
    private const val TAG_ROLE = 0x7E0A0002
    private const val TAG_SENDER = 0x7E0A0003

    private val installed = AtomicBoolean(false)
    private val dexKitNativeLoaded = AtomicBoolean(false)
    private val firstBoundLogged = AtomicBoolean(false)
    private val reentry = ThreadLocal.withInitial { false }
    private val roleCache = ConcurrentHashMap<String, Int>()

    @Volatile
    private var getChatroomData: Method? = null

    /** ChatroomStorage.get(groupId) → ChatRoomMember (t2) */
    @Volatile
    private var getGroupMethod: Method? = null

    @Volatile
    private var chatroomStorage: Any? = null

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        hostClassLoader = classLoader
        xlog("install enabled=${isEnabled()}")
        resolveChatroomApis(context, classLoader, modulePath)
        // 不再 hook 全局 TextView.setText：滑动时微信大量 setText，全局 afterHook 极卡
    }

    fun isEnabled(): Boolean =
        runCatching { PublicConfigStore.getBoolean(KEY, false) }.getOrDefault(false)

    fun onMessageBound(holder: Any, itemView: View, message: Any) {
        if (!isEnabled()) return
        val identity = MessageBindingIdentity.resolve(message) ?: return
        if (!identity.isGroup) return
        if (isSelfMessage(message)) return

        val nickname = MessageBindingIdentity.nicknameView(holder, itemView) ?: run {
            if (firstBoundLogged.compareAndSet(false, true)) {
                xlog("nickname missing holder=${holder.javaClass.name}")
            }
            return
        }

        val role = resolveRole(identity.room, identity.sender)
        if (role == 3 && !showMemberBadge()) return

        nickname.setTag(TAG_ROLE, role)
        nickname.setTag(TAG_SENDER, identity.sender)
        applyRoleBadge(nickname, role)
        // 仅一次 post：等微信本帧 setText 完再画，避免 50/150ms 双延迟造成滑动卡顿
        nickname.post { applyRoleBadge(nickname, role) }

        if (firstBoundLogged.compareAndSet(false, true)) {
            xlog(
                "bound room=${identity.room} sender=${identity.sender} role=$role " +
                    "nick=${nickname.text} vis=${nickname.visibility}"
            )
        }
    }

    private fun showMemberBadge(): Boolean =
        runCatching { PublicConfigStore.getBoolean(KEY_SHOW_MEMBER, true) }.getOrDefault(true)

    private fun resolveChatroomApis(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?
    ) {
        runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                // getChatroomData(sender) on ChatRoomMember
                val list = bridge.findMethod {
                    matcher {
                        usingEqStrings(
                            "MicroMsg.ChatRoomMember",
                            "getChatroomData hashMap is null!"
                        )
                    }
                }
                for (dm in list) {
                    val m = runCatching { descriptorToMethod(dm.descriptor, classLoader) }.getOrNull()
                        ?: continue
                    if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                        getChatroomData = m.apply { isAccessible = true }
                        xlog("getChatroomData=${m.declaringClass.name}#${m.name}")
                        break
                    }
                }

                // ChatroomStorage.get(groupId)
                val storageMethods = bridge.findMethod {
                    matcher {
                        usingEqStrings(
                            "MicroMsg.ChatroomStorage",
                            "[getMemberCount] cost:%sms"
                        )
                    }
                }
                val storageClass = storageMethods.firstOrNull()?.let { dm ->
                    runCatching {
                        val arrow = dm.descriptor.indexOf("->")
                        val cn = dm.descriptor.substring(1, arrow - 1).replace('/', '.')
                        classLoader.loadClass(cn)
                    }.getOrNull()
                }
                val memberClass = getChatroomData?.declaringClass
                if (storageClass != null && memberClass != null) {
                    for (m in storageClass.declaredMethods) {
                        if (m.parameterTypes.size != 1) continue
                        if (m.parameterTypes[0] != String::class.java) continue
                        if (!memberClass.isAssignableFrom(m.returnType)) continue
                        m.isAccessible = true
                        getGroupMethod = m
                        xlog("getGroup=${storageClass.name}#${m.name} -> ${m.returnType.simpleName}")
                        break
                    }
                    // 尝试拿到 storage 单例：在 kernel 服务上找返回 storageClass 的方法
                    resolveChatroomStorageInstance(classLoader, storageClass)
                }
            }
            if (getChatroomData == null) xlog("getChatroomData not found")
            if (getGroupMethod == null) xlog("getGroup not found")
        }.onFailure { xlog("DexKit fail: ${it.message}") }
    }

    private fun resolveChatroomStorageInstance(classLoader: ClassLoader, storageClass: Class<*>) {
        // 当前微信稳定路径（jadx 多处）：
        // ((ft1.a) ((gt1.f) rk0.k1.s(gt1.f.class))).a() → storage.u2
        runCatching {
            val kernel = classLoader.loadClass("rk0.k1")
            val svcIface = classLoader.loadClass("gt1.f")
            val sMethod = kernel.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Class::class.java
            } ?: return@runCatching
            sMethod.isAccessible = true
            val service = sMethod.invoke(null, svcIface) ?: return@runCatching
            // ft1.a.a() → u2
            val getter = service.javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() &&
                    !Modifier.isStatic(it.modifiers) &&
                    storageClass.isAssignableFrom(it.returnType)
            } ?: service.javaClass.methods.firstOrNull {
                it.name == "a" && it.parameterTypes.isEmpty()
            }
            if (getter != null) {
                getter.isAccessible = true
                val inst = getter.invoke(service)
                if (inst != null && storageClass.isInstance(inst)) {
                    chatroomStorage = inst
                    xlog("chatroomStorage via rk0.k1.s(gt1.f).${getter.name}()")
                    return
                }
            }
            // 字段 f263326d 等
            var c: Class<*>? = service.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (!storageClass.isAssignableFrom(f.type)) continue
                    f.isAccessible = true
                    val v = f.get(service) ?: continue
                    chatroomStorage = v
                    xlog("chatroomStorage field ${c.name}#${f.name}")
                    return
                }
                c = c.superclass
            }
        }.onFailure { xlog("rk0.k1.s path: ${it.message}") }

        // 备选：iy0.c9.b().m()
        runCatching {
            val c9 = classLoader.loadClass("iy0.c9")
            val b = c9.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.name == "b" && it.parameterTypes.isEmpty()
            } ?: return@runCatching
            b.isAccessible = true
            val holder = b.invoke(null) ?: return@runCatching
            val m = holder.javaClass.methods.firstOrNull {
                it.name == "m" && it.parameterTypes.isEmpty() &&
                    storageClass.isAssignableFrom(it.returnType)
            } ?: return@runCatching
            m.isAccessible = true
            val inst = m.invoke(holder)
            if (inst != null) {
                chatroomStorage = inst
                xlog("chatroomStorage via iy0.c9.b().m()")
                return
            }
        }.onFailure { xlog("iy0.c9 path: ${it.message}") }

        // 静态字段兜底
        for (cn in listOf("com.tencent.mm.model.z", "com.tencent.mm.model.s", "rk0.k1")) {
            val clazz = runCatching { classLoader.loadClass(cn) }.getOrNull() ?: continue
            for (m in clazz.declaredMethods) {
                if (!Modifier.isStatic(m.modifiers) || m.parameterTypes.isNotEmpty()) continue
                if (!storageClass.isAssignableFrom(m.returnType)) continue
                val inst = runCatching {
                    m.isAccessible = true
                    m.invoke(null)
                }.getOrNull() ?: continue
                chatroomStorage = inst
                xlog("chatroomStorage via $cn#${m.name}")
                return
            }
        }
        xlog("chatroomStorage unresolved — will use DB / class static")
    }

    /** 延迟解析：install 时 kernel 可能未就绪 */
    private fun ensureChatroomStorage() {
        if (chatroomStorage != null) return
        val cl = hostClassLoader ?: return
        val storageClass = getGroupMethod?.declaringClass ?: runCatching {
            cl.loadClass("com.tencent.mm.storage.u2")
        }.getOrNull() ?: return
        resolveChatroomStorageInstance(cl, storageClass)
    }

    /** 1=群主 2=管理 3=成员 */
    private fun resolveRole(room: String, sender: String): Int {
        val key = "$room|$sender"
        roleCache[key]?.let { return it }

        // 1) getGroup + field_roomowner + getChatroomData (in-memory, FAST)
        val group = findGroupObject(room)
        if (group != null) {
            val owner = runCatching {
                XposedHelpers.getObjectField(group, "field_roomowner") as? String
            }.getOrNull()?.trim()
            if (!owner.isNullOrEmpty() && owner == sender) {
                roleCache[key] = 1
                return 1
            }
            val method = getChatroomData
            if (method != null) {
                runCatching {
                    val data = method.invoke(group, sender)
                    if (data != null) {
                        val flags = firstIntField(data)
                        if (flags != null && flags and 2048 != 0) {
                            roleCache[key] = 2
                            return 2
                        }
                    }
                }.onFailure { xlog("getChatroomData invoke: ${it.message}") }
            }
            roleCache[key] = 3
            return 3
        }

        // 2) ͨѶ¼ DB (Fallback, SLOW)
        ContactDisplayNames.chatroomRole(room, sender)?.let {
            roleCache[key] = it
            return it
        }

        roleCache[key] = 3
        return 3
    }

    private fun findGroupObject(room: String): Any? {
        ensureChatroomStorage()
        val method = getGroupMethod
        val storage = chatroomStorage
        if (method != null && storage != null) {
            runCatching {
                method.isAccessible = true
                val obj = method.invoke(storage, room)
                // Q1 可能返回空 shell（field 未填），j1 返回 null 更准
                if (obj != null && hasFieldRoomOwner(obj.javaClass)) {
                    val owner = runCatching {
                        XposedHelpers.getObjectField(obj, "field_roomowner") as? String
                    }.getOrNull()
                    // 空 owner 也返回，后续当成员处理
                    return obj
                }
            }.onFailure { xlog("getGroup invoke: ${it.message}") }
        }
        // 静态 get(room) 兜底
        val cl = hostClassLoader ?: return null
        val memberClass = getChatroomData?.declaringClass
        val candidates = listOf(
            "com.tencent.mm.storage.u2",
            "com.tencent.mm.storage.t2",
            "com.tencent.mm.model.z",
            "com.tencent.mm.model.s"
        )
        for (cn in candidates) {
            val clazz = runCatching { cl.loadClass(cn) }.getOrNull() ?: continue
            for (m in clazz.declaredMethods) {
                if (m.parameterTypes.size != 1 || m.parameterTypes[0] != String::class.java) continue
                if (m.returnType == Void.TYPE || m.returnType == String::class.java) continue
                if (memberClass != null && !memberClass.isAssignableFrom(m.returnType) &&
                    m.returnType != memberClass
                ) {
                    // 允许返回 t2 子类
                    if (!hasFieldRoomOwner(m.returnType)) continue
                }
                val obj = runCatching {
                    m.isAccessible = true
                    if (Modifier.isStatic(m.modifiers)) {
                        m.invoke(null, room)
                    } else if (storage != null && clazz.isInstance(storage)) {
                        m.invoke(storage, room)
                    } else {
                        null
                    }
                }.getOrNull() ?: continue
                if (hasFieldRoomOwner(obj.javaClass)) return obj
            }
        }
        return null
    }

    private fun hasFieldRoomOwner(clazz: Class<*>): Boolean {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            if (c.declaredFields.any { it.name == "field_roomowner" }) return true
            c = c.superclass
        }
        return false
    }

    private fun applyRoleBadge(tv: TextView, role: Int) {
        if (reentry.get() == true) return
        // 更贴近微信风格的胶囊色：群主金、管理蓝、成员浅灰描边
        val (label, bg, fg, stroke) = when (role) {
            1 -> Quad(
                PublicConfigStore.getString("member_title_owner", "群主"),
                "#FA9D3B",
                "#FFFFFF",
                0
            )
            2 -> Quad(
                PublicConfigStore.getString("member_title_admin", "管理员"),
                "#5B8FF9",
                "#FFFFFF",
                0
            )
            else -> Quad(
                PublicConfigStore.getString("member_title_member", "成员"),
                "#F2F3F5",
                "#646A73",
                0x1A000000
            )
        }
        val existing = tv.text ?: return
        if (existing.isEmpty() || existing.length > 120) return

        // 已有本功能的 ReplacementSpan 徽章则跳过（保留后面实名尾字）
        if (existing is Spanned) {
            val spans = existing.getSpans(0, existing.length, RoundedBackgroundSpan::class.java)
            if (spans.isNotEmpty()) return
        }
        val current = existing.toString()
        // 纯文本重复前缀
        if (current.startsWith(label + " ") || current.startsWith(label)) {
            // 若已有标签文字但没 span（被 setText 成 plain），仍要重画
            if (existing is Spanned &&
                existing.getSpans(0, minOf(label.length + 1, existing.length), ReplacementSpan::class.java)
                    .isNotEmpty()
            ) {
                return
            }
        }

        // 去掉旧的「群主/管理/成员 」纯文本前缀，保留实名 " (x)" 后缀
        var body: CharSequence = existing
        for (prefix in listOf(
            PublicConfigStore.getString("member_title_owner", "群主"),
            PublicConfigStore.getString("member_title_admin", "管理员"),
            PublicConfigStore.getString("member_title_member", "成员")
        )) {
            val pfx = "$prefix "
            if (body.toString().startsWith(pfx)) {
                body = if (body is Spanned) {
                    SpannableStringBuilder(body, pfx.length, body.length)
                } else {
                    body.toString().substring(pfx.length)
                }
                break
            }
        }

        val density = tv.resources.displayMetrics.density
        val hPad = 5.5f * density
        val vPad = 1.6f * density
        val radius = 4.5f * density
        val gap = 4f * density

        val sb = SpannableStringBuilder()
        sb.append(label)
        sb.append("\u2009") // 窄空格，徽章与昵称间距更紧凑
        sb.append(body)
        sb.setSpan(
            RoundedBackgroundSpan(
                backgroundColor = parseColor(bg),
                textColor = parseColor(fg),
                cornerRadius = radius,
                hPadding = hPad,
                vPadding = vPad,
                trailingGap = gap,
                strokeColor = stroke,
                textSizeScale = 0.78f
            ),
            0,
            label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        reentry.set(true)
        try {
            if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
            tv.setTag(TAG_ROLE, role)
            tv.setText(sb, TextView.BufferType.SPANNABLE)
        } finally {
            reentry.set(false)
        }
    }

    private data class Quad(
        val label: String,
        val bg: String,
        val fg: String,
        val stroke: Int
    )

    private fun isSelfMessage(message: Any): Boolean {
        runCatching {
            var c: Class<*>? = message.javaClass
            while (c != null && c != Any::class.java) {
                runCatching {
                    val f = c.getDeclaredField("field_isSend")
                    f.isAccessible = true
                    val v = f.get(message)
                    return when (v) {
                        is Number -> v.toInt() == 1
                        is Boolean -> v
                        else -> false
                    }
                }
                c = c.superclass
            }
        }
        for (name in listOf("getIsSend", "isSend")) {
            val m = message.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            } ?: continue
            val v = runCatching {
                m.isAccessible = true
                m.invoke(message)
            }.getOrNull()
            when (v) {
                is Number -> return v.toInt() == 1
                is Boolean -> return v
            }
        }
        return false
    }

    private fun firstIntField(obj: Any): Int? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type == Int::class.javaPrimitiveType || f.type == Int::class.javaObjectType) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    return f.get(obj) as? Int
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun parseColor(hex: String): Int =
        runCatching { android.graphics.Color.parseColor(hex) }
            .getOrDefault(android.graphics.Color.GRAY)

    /** RoundedBackgroundSpan — 必须 public 以便 Spanned.getSpans 识别 */
    class RoundedBackgroundSpan(
        private val backgroundColor: Int,
        private val textColor: Int,
        private val cornerRadius: Float = 12f,
        private val hPadding: Float = 10f,
        private val vPadding: Float = 2f,
        private val trailingGap: Float = 6f,
        private val strokeColor: Int = 0,
        private val textSizeScale: Float = 0.8f
    ) : ReplacementSpan() {
        // 兼容旧构造
        constructor(
            backgroundColor: Int,
            textColor: Int,
            cornerRadius: Float,
            padding: Float
        ) : this(backgroundColor, textColor, cornerRadius, padding, padding * 0.25f, padding * 0.6f, 0, 0.8f)

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val t = text ?: return 0
            val oldSize = paint.textSize
            paint.textSize = oldSize * textSizeScale
            val w = paint.measureText(t, start, end) + hPadding * 2f + trailingGap
            paint.textSize = oldSize
            return w.roundToInt().coerceAtLeast(1)
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val t = text ?: return
            val oldColor = paint.color
            val oldSize = paint.textSize
            val oldStyle = paint.style
            val oldStroke = paint.strokeWidth
            val oldFakeBold = paint.isFakeBoldText

            paint.textSize = oldSize * textSizeScale
            paint.isFakeBoldText = true
            val textW = paint.measureText(t, start, end)
            val badgeH = (paint.descent() - paint.ascent()) + vPadding * 2f
            // 相对基线垂直居中，避免顶/底被裁切或偏上
            val centerY = (y + paint.descent() + y + paint.ascent()) / 2f
            val rectTop = centerY - badgeH / 2f
            val rectBottom = centerY + badgeH / 2f
            val rect = RectF(x, rectTop, x + textW + hPadding * 2f, rectBottom)

            paint.style = Paint.Style.FILL
            paint.color = backgroundColor
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            if (strokeColor != 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = strokeColor
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
                paint.style = Paint.Style.FILL
            }

            paint.color = textColor
            val textY = centerY - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(t, start, end, x + hPadding, textY, paint)

            paint.color = oldColor
            paint.textSize = oldSize
            paint.style = oldStyle
            paint.strokeWidth = oldStroke
            paint.isFakeBoldText = oldFakeBold
        }
    }

    private fun loadDexKitNative(context: Context, modulePath: String?) {
        if (dexKitNativeLoaded.get()) return
        runCatching {
            System.loadLibrary("dexkit")
            dexKitNativeLoaded.set(true)
            return
        }
        val apk = modulePath ?: return
        val abi = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a"
        } else {
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull() ?: "armeabi-v7a"
        }
        val out = File(context.cacheDir, "abc_${abi}_libdexkit.so")
        runCatching {
            ZipFile(apk).use { zip ->
                val e = zip.getEntry("lib/$abi/libdexkit.so") ?: return
                zip.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
            System.load(out.absolutePath)
            dexKitNativeLoaded.set(true)
        }
    }

    private fun descriptorToMethod(descriptor: String, classLoader: ClassLoader): Method {
        val arrow = descriptor.indexOf("->")
        val argsStart = descriptor.indexOf('(', arrow)
        val className = descriptor.substring(1, arrow - 1).replace('/', '.')
        val methodName = descriptor.substring(arrow + 2, argsStart)
        val signature = descriptor.substring(argsStart)
        var clazz: Class<*>? = classLoader.loadClass(className)
        while (clazz != null) {
            clazz.declaredMethods.firstOrNull {
                it.name == methodName && methodSignature(it) == signature
            }?.let {
                it.isAccessible = true
                return it
            }
            clazz = clazz.superclass
        }
        throw NoSuchMethodException(descriptor)
    }

    private fun methodSignature(method: Method): String {
        val sb = StringBuilder("(")
        for (p in method.parameterTypes) sb.append(typeDesc(p))
        sb.append(')').append(typeDesc(method.returnType))
        return sb.toString()
    }

    private fun typeDesc(type: Class<*>): String {
        if (type.isPrimitive) {
            return when (type) {
                Boolean::class.javaPrimitiveType -> "Z"
                Byte::class.javaPrimitiveType -> "B"
                Char::class.javaPrimitiveType -> "C"
                Short::class.javaPrimitiveType -> "S"
                Int::class.javaPrimitiveType -> "I"
                Long::class.javaPrimitiveType -> "J"
                Float::class.javaPrimitiveType -> "F"
                Double::class.javaPrimitiveType -> "D"
                Void.TYPE -> "V"
                else -> "V"
            }
        }
        if (type.isArray) return "[" + typeDesc(type.componentType!!)
        return "L" + type.name.replace('.', '/') + ";"
    }

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
