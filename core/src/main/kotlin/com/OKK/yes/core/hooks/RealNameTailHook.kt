package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * 实名尾字：
 * - [DisplayGroupMemberRealNamesLastChar]：CGI 查 + 缓存，不改私聊顶栏
 * - [DisplayGroupMemberRealName]：仅在**群聊对方消息**的 `userTV` 昵称后追加 ` (掩码实名)`
 *
 * 规则：
 * 1. 群聊 + 非自己消息 → 昵称后灰字 ` (尾字/掩码)`
 * 2. 私聊消息列表 / 顶栏 → **不改**
 * 3. 资料页（个人）→ 注入「获取实名尾字」，点击查询 / Toast
 *
 * CGI：`/cgi-bin/mmpay-bin/beforetransfer` type 2783，响应 field4 掩码实名
 * 配置：`real_name_tail` 默认 false；尾字颜色跟随昵称 TextView 当前字体色
 */
object RealNameTailHook {
    private const val TAG = "OKK-RealNameTail"
    private const val KEY = "real_name_tail"
    private const val KEY_COLOR = "real_name_tail_color"
    private const val CGI = "/cgi-bin/mmpay-bin/beforetransfer"
    private const val PREF_KEY = "achat_real_name_tail"
    private const val TAG_SENDER = 0x7E000001
    private const val TAG_BIND_RETRY = 0x7E000002
    private const val CACHE_FILE = "/storage/emulated/0/Android/media/com.tencent.mm/OKK/real_names.json"
    private const val CONTACT_UI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
    private const val NO_REAL_NAME_RETRY_MS = 10 * 60 * 1000L

    private val installed = AtomicBoolean(false)
    private val dexKitNativeLoaded = AtomicBoolean(false)
    private val firstBoundLogged = AtomicBoolean(false)
    private val firstSkipLogged = AtomicBoolean(false)
    private val reentry = ThreadLocal.withInitial { false }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** wxid → 掩码实名（CGI field4 原文，如 *伟） */
    private val realNames = ConcurrentHashMap<String, String>()
    private val pendingOrQueried = ConcurrentHashMap.newKeySet<String>()
    /** sender|room -> 下次允许重查时间。NoRealName 不再永久占坑，避免临时空结果导致某人一直不显示。 */
    private val noRealNameRetryAt = ConcurrentHashMap<String, Long>()
    private val boundNicknames =
        ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<TextView>>>()
    /** 强引用：WeakHashMap 会在 CGI 返回前丢 scene，导致永远无 got/no-real 日志 */
    private val sceneMeta = ConcurrentHashMap<Any, Pair<String, String?>>()
    private val pendingCallbacks = ConcurrentHashMap<Any, (FetchResult) -> Unit>()

    @Volatile
    private var sceneCtor: Constructor<*>? = null

    @Volatile
    private var doSceneMethod: Method? = null

    @Volatile
    private var netSceneQueue: Any? = null

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        hostClassLoader = classLoader
        loadCache()
        xlog("install enabled=${isEnabled()} cache=${realNames.size}")
        resolveBeforeTransferScene(context, classLoader, modulePath)
        hookProfilePage(classLoader)
        // 不 hook 全局 setText（滑动卡顿主因）；bind 时 post 一次 + CGI 回调刷新即可
    }

    fun isEnabled(): Boolean =
        runCatching { PublicConfigStore.getBoolean(KEY, false) }.getOrDefault(false)

    /**
     * 由 [ChatEnhanceHook] 在消息 bind 时调用。
     * 仅群聊对方消息；私聊直接 return（不改顶栏）。
     */
    fun onMessageBound(holder: Any, itemView: View, message: Any) {
        if (!isEnabled()) return
        val identity = MessageBindingIdentity.resolve(message) ?: run {
            if (firstSkipLogged.compareAndSet(false, true)) {
                xlog("skip: identity null msg=${message.javaClass.name}")
            }
            return
        }
        // if (!msgInfo.isInGroupChat) return
        if (!identity.isGroup) return
        // if (msgInfo.isSend != 0) return — 自己消息不画
        // 必须与 ChatEnhance 一致：优先 field_isSend == 1，勿误用其它短方法
        if (isSelfMessage(message)) {
            if (firstSkipLogged.compareAndSet(false, true)) {
                xlog("skip self room=${identity.room} sender=${identity.sender}")
            }
            return
        }

        val nickname = MessageBindingIdentity.nicknameView(holder, itemView)
        if (nickname == null) {
            xlog("nickname missing room=${identity.room} sender=${identity.sender} holder=${holder.javaClass.name}")
            enqueueFetch(identity.sender, identity.room, null)
            // 部分消息行昵称由微信异步填充，bind 当下找不到时最多延迟补两次，避免无昵称行无限重试。
            val retry = (itemView.getTag(TAG_BIND_RETRY) as? Int) ?: 0
            if (retry < 2) {
                itemView.setTag(TAG_BIND_RETRY, retry + 1)
                itemView.postDelayed({ onMessageBound(holder, itemView, message) }, if (retry == 0) 160L else 480L)
            }
            return
        }

        itemView.setTag(TAG_BIND_RETRY, 0)
        nickname.setTag(TAG_SENDER, identity.sender)
        rememberNickname(identity.sender, nickname)

        if (firstBoundLogged.compareAndSet(false, true)) {
            xlog(
                "bound room=${identity.room} sender=${identity.sender} " +
                    "nick=${nickname.text} vis=${nickname.visibility} cache=${realNames[identity.sender]}"
            )
        }

        val cached = realNames[identity.sender]
        if (cached != null) {
            applyAnnotation(nickname, identity.sender, cached)
            // 微信部分行会在 bind 后异步重写 userTV，延迟补写两次提高显示稳定性。
            nickname.post { applyAnnotation(nickname, identity.sender, cached) }
            nickname.postDelayed({ applyAnnotation(nickname, identity.sender, cached) }, 180L)
            nickname.postDelayed({ applyAnnotation(nickname, identity.sender, cached) }, 520L)
        } else {
            enqueueFetch(identity.sender, identity.room) { name ->
                mainHandler.post {
                    refreshSenderViews(identity.sender, name)
                }
            }
        }
    }

    private fun refreshSenderViews(sender: String, name: String) {
        boundNicknames[sender]?.removeAll { ref ->
            val tv = ref.get()
            if (tv == null) {
                true
            } else {
                if (tv.getTag(TAG_SENDER) == sender) {
                    applyAnnotation(tv, sender, name)
                }
                false
            }
        }
    }

    // ── 群聊昵称绘制（对齐 DisplayGroupMemberRealName）────────────────────

    private fun applyAnnotation(textView: TextView, sender: String, maskedName: String) {
        if (reentry.get() == true) return
        if (textView.getTag(TAG_SENDER) != sender) return
        val existing = textView.text ?: return
        if (existing.isEmpty()) return
        val base = existing.toString()

        // 去掉本功能已写过的 " (…)" 尾注；前缀用 Spanned 切片保留群头衔 ReplacementSpan
        val annotStart = base.lastIndexOf(" (")
        val hasExisting = annotStart >= 0 && base.endsWith(")")
        val plainLen = if (hasExisting) annotStart else base.length
        if (plainLen <= 0) return

        // 尾字缓存即 CGI field4 掩码全文（如 *伟 / **伟），直接展示，不再截成单字
        val display = displayMasked(maskedName)
        if (display.isEmpty()) {
            xlog("apply skip empty masked=$maskedName sender=$sender")
            return
        }
        val annotation = " ($display)"

        if (hasExisting && base.substring(annotStart) == annotation) return
        if (!hasExisting && base.endsWith(annotation)) return

        // 跟随昵称当前字体颜色，避免灰字在深色/浅色气泡旁都看不清
        val nickColor = runCatching { textView.currentTextColor }.getOrNull()
            ?: runCatching { textView.textColors?.defaultColor }.getOrNull()
            ?: 0xFF191919.toInt()
        val color = if (Color.alpha(nickColor) == 0) 0xFF191919.toInt() else nickColor

        // SpannableStringBuilder(existing, 0, plainLen) — 保留头衔 span
        val sb = SpannableStringBuilder(existing, 0, plainLen)
        sb.append(annotation)
        sb.setSpan(
            ForegroundColorSpan(color),
            plainLen,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        reentry.set(true)
        try {
            if (textView.visibility != View.VISIBLE) {
                textView.visibility = View.VISIBLE
            }
            textView.setText(sb, TextView.BufferType.SPANNABLE)
        } finally {
            reentry.set(false)
        }
    }

    /**
     * 掩码实名展示（缓存 field4 原文作注解）。
     * - `*伟` / `**伟` → 原样 `*伟` / `**伟`（比只显示尾字信息量更大）
     * - 无掩码纯中文 → 原样
     * 说明：微信 beforetransfer 本身就不给完整明文；首字需另做「爆破」接口（有风控），本功能不做。
     */
    private fun displayMasked(masked: String): String {
        val plain = masked.trim()
            .removePrefix("(").removeSuffix(")")
            .removePrefix("（").removeSuffix("）")
            .filterNot { it.isWhitespace() }
        if (plain.isEmpty() || plain.length > 16) return ""
        return plain
    }

    /** 日志/兼容：可见尾字（从掩码里取最后一个非 * 字） */
    private fun displayTail(masked: String): String {
        val plain = displayMasked(masked)
        if (plain.isEmpty()) return ""
        val visible = plain.filter { it != '*' && it != '＊' && it != 'x' && it != 'X' && it != '?' }
        return if (visible.isEmpty()) plain else visible.last().toString()
    }

    private fun rememberNickname(sender: String, tv: TextView) {
        val list = boundNicknames.computeIfAbsent(sender) { CopyOnWriteArrayList() }
        if (list.none { it.get() === tv }) list += WeakReference(tv)
        if (list.size > 32) list.removeAll { it.get() == null }
    }

    /**
     * 与 ChatEnhanceHook.readIsSend 保持一致，避免误把对方消息当成自己。
     * 微信：field_isSend == 1 为自己。
     */
    private fun isSelfMessage(message: Any): Boolean {
        // 1) 字段 field_isSend（最稳）
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
        // 2) 方法 getIsSend / isSend（不要扫 E0 等短名，易撞其它语义）
        for (name in listOf("getIsSend", "isSend")) {
            val m = runCatching {
                message.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty()
                }
            }.getOrNull() ?: continue
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

    // ── CGI 查询 ───────────────────────────────────────────────────────────

    private fun enqueueFetch(
        sender: String,
        room: String?,
        onFound: ((String) -> Unit)?
    ) {
        if (sender.isBlank() || sender.endsWith("@chatroom")) return
        realNames[sender]?.let {
            onFound?.invoke(it)
            return
        }
        val queryKey = "$sender|${room.orEmpty()}"
        val now = System.currentTimeMillis()
        noRealNameRetryAt[queryKey]?.let { retryAt ->
            if (now < retryAt) return
            noRealNameRetryAt.remove(queryKey)
        }
        // 按 sender+room 去重：同一人不同群可能接口返回不同，不再全局占坑。
        if (!pendingOrQueried.add(queryKey)) {
            // 已在途：若之后有缓存，onFound 由 bind 路径再读
            return
        }
        sendBeforeTransfer(sender, room) { result ->
            when (result) {
                is FetchResult.Found -> {
                    realNames[sender] = result.name
                    saveCache()
                    pendingOrQueried.remove(queryKey)
                    noRealNameRetryAt.remove(queryKey)
                    xlog("got $sender => ${result.name} tail=${displayTail(result.name)}")
                    onFound?.invoke(result.name)
                    mainHandler.post { refreshSenderViews(sender, result.name) }
                }
                FetchResult.NoRealName -> {
                    pendingOrQueried.remove(queryKey)
                    noRealNameRetryAt[queryKey] = System.currentTimeMillis() + NO_REAL_NAME_RETRY_MS
                    xlog("no real name $sender room=$room retry later")
                }
                is FetchResult.Failure -> {
                    pendingOrQueried.remove(queryKey)
                    xlog("fetch fail $sender: ${result.msg}")
                }
            }
        }
    }

    private sealed class FetchResult {
        data class Found(val name: String) : FetchResult()
        data object NoRealName : FetchResult()
        data class Failure(val msg: String) : FetchResult()
    }

    private fun sendBeforeTransfer(
        sender: String,
        room: String?,
        onResult: (FetchResult) -> Unit
    ) {
        val ctor = sceneCtor
        if (ctor == null) {
            onResult(FetchResult.Failure("scene not resolved"))
            return
        }
        runCatching {
            val scene = when (ctor.parameterTypes.size) {
                2 -> ctor.newInstance(sender, room.orEmpty())
                1 -> ctor.newInstance(sender)
                else -> {
                    onResult(FetchResult.Failure("bad ctor"))
                    return
                }
            }
            sceneMeta[scene] = sender to room
            pendingCallbacks[scene] = onResult

            var queue = netSceneQueue
            var method = doSceneMethod
            if (queue == null || method == null) {
                val cl = hostClassLoader
                if (cl != null) resolveNetworkQueue(cl)
                queue = netSceneQueue
                method = doSceneMethod
            }
            if (queue == null || method == null) {
                sceneMeta.remove(scene)
                pendingCallbacks.remove(scene)
                onResult(FetchResult.Failure("no net queue"))
                return
            }
            method.isAccessible = true
            val ret = when (method.parameterTypes.size) {
                1 -> method.invoke(queue, scene)
                2 -> method.invoke(queue, scene, 0)
                else -> {
                    onResult(FetchResult.Failure("bad doScene"))
                    return
                }
            }
            xlog("sent beforetransfer $sender room=$room doSceneRet=$ret queue=${queue.javaClass.simpleName}#${method.name}")
            mainHandler.postDelayed({
                if (pendingCallbacks.remove(scene) != null) {
                    sceneMeta.remove(scene)
                    pendingOrQueried.remove(sender)
                    xlog("timeout $sender")
                    onResult(FetchResult.Failure("timeout"))
                }
            }, 15_000L)
        }.onFailure {
            pendingOrQueried.remove(sender)
            onResult(FetchResult.Failure(it.message ?: "send err"))
        }
    }

    private fun onSceneCallback(scene: Any?) {
        if (scene == null) return
        val pair = sceneMeta.remove(scene)
        val cb = pendingCallbacks.remove(scene)
        // 可能被 hook 多次（I + onGYNetEnd），第二次无 meta 则忽略
        if (pair == null && cb == null) return
        val sender = pair?.first
        val name = extractMaskedRealName(scene)
        val result = when {
            !name.isNullOrBlank() -> FetchResult.Found(name.trim())
            else -> FetchResult.NoRealName
        }
        val respCls = runCatching {
            (readField(scene, "r") ?: readField(scene, "f150798r"))?.javaClass?.name
        }.getOrNull()
        xlog("cgi cb sender=$sender name=$name result=${result.javaClass.simpleName} resp=$respCls")
        if (result is FetchResult.Found && sender != null) {
            realNames[sender] = result.name
            saveCache()
        }
        if (cb != null) {
            runCatching { cb(result) }
        } else if (result is FetchResult.Found && sender != null) {
            mainHandler.post {
                boundNicknames[sender]?.forEach { ref ->
                    val tv = ref.get() ?: return@forEach
                    if (tv.getTag(TAG_SENDER) == sender) {
                        applyAnnotation(tv, sender, result.name)
                    }
                }
            }
        }
    }

    private fun extractMaskedRealName(scene: Any): String? {
        // 当前微信：remittance.model.i.r / f150798r → d15.sw.f231704f (proto field 4)
        val resp = readField(scene, "r")
            ?: readField(scene, "f150798r")
            ?: readField(scene, "f150798R")
        if (resp != null) {
            // 优先 field4 掩码实名，不强制 looksMasked（个别版本无 *）
            readStringField(resp, "f231704f")?.takeIf { it.isNotBlank() && it.length <= 16 }
                ?.let { return it }
            readStringField(resp, "f")?.takeIf { looksMasked(it) }?.let { return it }
            pickBestString(resp)?.let { return it }
        }
        // 从 CommReqResp 再挖一层
        val rr = readField(scene, "f206693n") ?: readField(scene, "n")
        if (rr != null) {
            val body = digResponseBody(rr)
            if (body != null) {
                readStringField(body, "f231704f")?.takeIf { it.isNotBlank() && it.length <= 16 }
                    ?.let { return it }
                pickBestString(body)?.let { return it }
            }
        }
        pickBestString(scene)?.let { return it }
        return null
    }

    private fun digResponseBody(rr: Any): Any? {
        // modelbase.o → f66769b → f66756a
        val respWrapper = readField(rr, "f66769b")
            ?: readField(rr, "b")
            ?: return null
        return readField(respWrapper, "f66756a")
            ?: readField(respWrapper, "a")
    }

    private fun looksMasked(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > 16) return false
        if (t.contains("*") || t.contains("＊")) return true
        if (t.matches(Regex("[\\u4e00-\\u9fff·]{1,4}"))) return true
        return false
    }

    private fun pickBestString(obj: Any): String? {
        val all = mutableListOf<String>()
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type != String::class.java) continue
                f.isAccessible = true
                val s = (f.get(obj) as? String)?.trim().orEmpty()
                if (looksMasked(s)) all += s
            }
            c = c.superclass
        }
        return all.firstOrNull { it.contains("*") || it.contains("＊") } ?: all.firstOrNull()
    }

    private fun readField(obj: Any, name: String): Any? = runCatching {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            runCatching {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    private fun readStringField(obj: Any, name: String): String? =
        (readField(obj, name) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    // ── NetScene 定位 ──────────────────────────────────────────────────────

    private fun resolveBeforeTransferScene(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?
    ) {
        // 优先当前微信稳定类名
        for (name in listOf(
            "com.tencent.mm.plugin.remittance.model.i",
            "com.tencent.mm.plugin.remittance.model.NetSceneBeforeTransfer"
        )) {
            val clazz = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            if (tryBindScene(clazz)) {
                xlog("scene=$name")
                hookSceneCallback(clazz)
                resolveNetworkQueue(classLoader)
                return
            }
        }
        runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                val classes = bridge.findClass {
                    matcher { usingStrings(CGI) }
                }
                for (dc in classes) {
                    val cn = dc.name ?: continue
                    val clazz = runCatching { classLoader.loadClass(cn) }.getOrNull() ?: continue
                    if (!tryBindScene(clazz)) continue
                    xlog("scene dexkit=$cn")
                    hookSceneCallback(clazz)
                    resolveNetworkQueue(classLoader)
                    return
                }
            }
            xlog("beforetransfer scene NOT found")
        }.onFailure { xlog("resolve scene: ${it.message}") }
    }

    private fun tryBindScene(clazz: Class<*>): Boolean {
        for (c in clazz.declaredConstructors) {
            val p = c.parameterTypes
            if (p.size == 2 && p[0] == String::class.java && p[1] == String::class.java) {
                c.isAccessible = true
                sceneCtor = c
                return true
            }
        }
        for (c in clazz.declaredConstructors) {
            val p = c.parameterTypes
            if (p.size == 1 && p[0] == String::class.java) {
                c.isAccessible = true
                sceneCtor = c
                return true
            }
        }
        return false
    }

    private fun hookSceneCallback(clazz: Class<*>) {
        var n = 0
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                onSceneCallback(param.thisObject)
            }
        }
        // 1) remittance.model.i.I — 真正赋值 f150798r 的地方
        for (m in clazz.declaredMethods) {
            if (Modifier.isStatic(m.modifiers) || Modifier.isAbstract(m.modifiers)) continue
            val p = m.parameterTypes
            if (p.size >= 4 && isInt(p[0]) && isInt(p[1]) && isInt(p[2]) &&
                p[3] == String::class.java
            ) {
                m.isAccessible = true
                XposedBridge.hookMethod(m, hook)
                n++
                xlog("hooked scene#${m.name} params=${p.size}")
            }
        }
        // 2) wallet y0.onGYNetEnd — I 之前会先填 ret；双 hook 幂等
        var sup: Class<*>? = clazz.superclass
        while (sup != null && sup != Any::class.java) {
            for (m in sup.declaredMethods) {
                if (Modifier.isStatic(m.modifiers) || Modifier.isAbstract(m.modifiers)) continue
                if (m.name != "onGYNetEnd" && m.name != "I") continue
                val p = m.parameterTypes
                if (p.size >= 4 && isInt(p[0]) && isInt(p[1]) && isInt(p[2])) {
                    runCatching {
                        m.isAccessible = true
                        XposedBridge.hookMethod(m, hook)
                        n++
                        xlog("hooked ${sup.name}#${m.name}")
                    }.onFailure {
                        // abstract 方法 hook 失败是预期（旧日志）
                        xlog("skip hook ${sup?.name}#${m.name}: ${it.message}")
                    }
                }
            }
            sup = sup.superclass
        }
        xlog("scene callback hooks=$n")
    }

    private fun isInt(t: Class<*>) =
        t == Int::class.javaPrimitiveType || t == Int::class.javaObjectType

    private fun resolveNetworkQueue(classLoader: ClassLoader) {
        // 当前微信：rk0.k1.n → 队列，doScene 双参 h(scene, int)
        runCatching {
            val kernel = classLoader.loadClass("rk0.k1")
            kernel.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.name == "i" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.invoke(null)
            val holder = kernel.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.name == "n" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }?.invoke(null) ?: return@runCatching
            val queue = holder.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                f.isAccessible = true
                val v = f.get(holder) ?: return@firstNotNullOfOrNull null
                if (findDoScene(v.javaClass) != null) v else null
            } ?: return@runCatching
            val method = findDoScene(queue.javaClass) ?: return@runCatching
            netSceneQueue = queue
            doSceneMethod = method
            xlog("net queue=${queue.javaClass.name}#${method.name}")
            return
        }.onFailure { xlog("rk0.k1 queue: ${it.message}") }

        for (cn in listOf(
            "com.tencent.mm.modelbase.s1",
            "com.tencent.mm.modelbase.n1",
            "com.tencent.mm.kernel.h",
            "com.tencent.mm.model.bh"
        )) {
            val clazz = runCatching { classLoader.loadClass(cn) }.getOrNull() ?: continue
            for (m in clazz.declaredMethods) {
                if (!Modifier.isStatic(m.modifiers) || m.parameterTypes.isNotEmpty()) continue
                val inst = runCatching {
                    m.isAccessible = true
                    m.invoke(null)
                }.getOrNull() ?: continue
                val ds = findDoScene(inst.javaClass) ?: continue
                netSceneQueue = inst
                doSceneMethod = ds
                xlog("net queue via $cn")
                return
            }
        }
        xlog("net queue unresolved")
    }

    private fun findDoScene(clazz: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                val p = m.parameterTypes
                if (p.size == 2 && isInt(p[1]) && m.name in setOf("h", "g", "a")) {
                    m.isAccessible = true
                    return m
                }
            }
            c = c.superclass
        }
        c = clazz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)) continue
                if (m.parameterTypes.size != 1) continue
                if (m.name in setOf("g", "a", "doscene", "doScene")) {
                    m.isAccessible = true
                    return m
                }
            }
            c = c.superclass
        }
        return null
    }

    // ── 资料页：获取实名尾字（不对私聊顶栏动手）──────────────────────────

    private fun hookProfilePage(classLoader: ClassLoader) {
        val clazz = runCatching { XposedHelpers.findClass(CONTACT_UI, classLoader) }.getOrNull()
            ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz,
                "initView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        val activity = param.thisObject as? Activity ?: return
                        injectProfilePref(activity, classLoader)
                    }
                }
            )
        }
        runCatching {
            val screen = XposedHelpers.findClass(
                "com.tencent.mm.ui.base.preference.r",
                classLoader
            )
            val pref = XposedHelpers.findClass(
                "com.tencent.mm.ui.base.preference.Preference",
                classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz,
                "onPreferenceTreeClick",
                screen,
                pref,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        val preference = param.args.getOrNull(1) ?: return
                        val key = runCatching {
                            XposedHelpers.callMethod(preference, "j") as? String
                        }.getOrNull() ?: return
                        if (key != PREF_KEY) return
                        val activity = param.thisObject as? Activity ?: return
                        onProfileClick(activity)
                        param.result = true
                    }
                }
            )
        }
        xlog("profile page hooked")
    }

    private fun injectProfilePref(activity: Activity, classLoader: ClassLoader) {
        runCatching {
            val wxid = resolveContactUser(activity)
            // 群资料页本身不注入（isGroupChatWxId → empty）
            if (wxid.isNullOrBlank() || wxid.endsWith("@chatroom") ||
                wxid.endsWith("@im.chatroom")
            ) {
                return
            }
            val adapter = runCatching {
                XposedHelpers.callMethod(activity, "getPreferenceScreen")
            }.getOrNull() ?: return

            val summary = realNames[wxid]?.let { "实名: $it" } ?: "点击获取"
            val existing = runCatching {
                XposedHelpers.callMethod(adapter, "i", PREF_KEY)
            }.getOrNull()
            if (existing != null) {
                runCatching {
                    XposedHelpers.callMethod(existing, "K", "获取实名尾字" as CharSequence)
                    XposedHelpers.callMethod(existing, "H", summary as CharSequence)
                    XposedHelpers.callMethod(adapter, "notifyDataSetChanged")
                }
                return
            }
            val prefClass = XposedHelpers.findClass(
                "com.tencent.mm.ui.base.preference.Preference",
                classLoader
            )
            val preference = prefClass.getConstructor(Context::class.java).newInstance(activity)
            XposedHelpers.callMethod(preference, "C", PREF_KEY)
            XposedHelpers.callMethod(preference, "K", "获取实名尾字" as CharSequence)
            XposedHelpers.callMethod(preference, "H", summary as CharSequence)
            XposedHelpers.callMethod(adapter, "d", preference, 1)
            XposedHelpers.callMethod(adapter, "notifyDataSetChanged")
        }.onFailure { xlog("inject profile: ${it.message}") }
    }

    private fun onProfileClick(activity: Activity) {
        val wxid = resolveContactUser(activity)
        if (wxid.isNullOrBlank()) {
            Toast.makeText(activity, "无法获取微信 ID", Toast.LENGTH_SHORT).show()
            return
        }
        realNames[wxid]?.let {
            Toast.makeText(activity, "实名: $it", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, "正在获取...", Toast.LENGTH_SHORT).show()
        val groupHint = activity.intent?.getStringExtra("room_name")
            ?: activity.intent?.getStringExtra("Chat_User")?.takeIf {
                it.endsWith("@chatroom")
            }
        // 资料页允许强制再查
        pendingOrQueried.remove(wxid)
        val done = AtomicBoolean(false)
        enqueueFetch(wxid, groupHint) { name ->
            if (!done.compareAndSet(false, true)) return@enqueueFetch
            mainHandler.post {
                Toast.makeText(activity, "实名: $name", Toast.LENGTH_SHORT).show()
                injectProfilePref(activity, activity.classLoader)
            }
        }
        mainHandler.postDelayed({
            if (done.compareAndSet(false, true) && !realNames.containsKey(wxid)) {
                Toast.makeText(
                    activity,
                    "获取失败: 可能被删除/拉黑/对方账号异常",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, 13_000L)
    }

    private fun resolveContactUser(activity: Activity): String? {
        val intent = activity.intent ?: return null
        for (k in listOf(
            "Contact_User", "Contact_UserName", "User", "userName",
            "Chat_User", "Contact_Alias"
        )) {
            val v = intent.getStringExtra(k)?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    // ── 缓存 ───────────────────────────────────────────────────────────────

    private fun loadCache() {
        for (path in listOf(CACHE_FILE)) {
            runCatching {
                val f = File(path)
                if (!f.isFile) return@runCatching
                val text = f.readText(Charsets.UTF_8).trim()
                if (text.startsWith("{")) {
                    Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(text).forEach { m ->
                        val k = m.groupValues[1]
                        val v = m.groupValues[2]
                        if (k.isNotEmpty() && v.isNotEmpty()) realNames[k] = v
                    }
                } else {
                    text.lineSequence().forEach { line ->
                        val i = line.indexOf('=')
                        if (i > 0) {
                            val k = line.substring(0, i).trim()
                            val v = line.substring(i + 1).trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) realNames[k] = v
                        }
                    }
                }
            }
        }
        if (realNames.isNotEmpty()) xlog("cache loaded size=${realNames.size}")
    }

    private fun saveCache() {
        val body = realNames.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${k.replace("\"", "")}\":\"${v.replace("\"", "")}\""
        }
        for (path in listOf(CACHE_FILE)) {
            runCatching {
                val f = File(path)
                f.parentFile?.mkdirs()
                f.writeText(body, Charsets.UTF_8)
            }
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

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
