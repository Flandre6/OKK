package com.OKK.yes.core.hooks

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

object AntiRevokeHook {
    private const val TAG = "OKK-AntiRevoke"
    private const val MESSAGE_TABLE = "message"
    private const val RECALL_TYPE = 10000
    private const val DO_REVOKE_LOG = "doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s"
    private const val SEND_TIP_LOG = "check table name from id:%d table:%s getTableNameByLocalId:%s"

    private val dexKitNativeLoaded = AtomicBoolean(false)
    private val dexKitHooksAttempted = AtomicBoolean(false)
    private val sourceHookInstalled = AtomicBoolean(false)
    private val dbHookInstalled = AtomicBoolean(false)
    private val storageScanAttempted = AtomicBoolean(false)

    /** msgId / msgSvrId → 原消息 */
    private val messageCache = ConcurrentHashMap<Long, OriginalMessage>()
    /** talker:msgSvrId → 原消息（） */
    private val messageKeyCache = ConcurrentHashMap<String, OriginalMessage>()
    private val messageObjectCache = ConcurrentHashMap<Long, Any>()

    @Volatile
    private var sendTipMethod: Method? = null

    @Volatile
    private var msgInfoStorage: Any? = null

    var isEnabled = true

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!isEnabled) return
        xlog("install requested from ${context.javaClass.name}")

        installDexKitHooks(context, classLoader, modulePath)
        if (!sourceHookInstalled.get()) {
            hookKnownCurrentDoRevoke(classLoader)
        }

        installDatabaseHooks(classLoader)
        scanAndHookStorageClasses(classLoader)
        // install 自检：撤回源头方法未命中则防撤回不可能生效，标 PARTIAL
        val sourceOk = sourceHookInstalled.get()
        com.OKK.yes.core.startup.FeatureHookRegistry.reportEffective(
            "AntiRevoke",
            sourceOk,
            if (sourceOk) "撤回源头已拦截" else "撤回源头方法未命中，防撤回可能失效"
        )
    }

    fun install(classLoader: ClassLoader) {
        if (!isEnabled) return
        hookKnownCurrentDoRevoke(classLoader)
        installDatabaseHooks(classLoader)
        scanAndHookStorageClasses(classLoader)
    }

    private fun installDexKitHooks(context: Context, classLoader: ClassLoader, modulePath: String?) {
        if (!dexKitHooksAttempted.compareAndSet(false, true)) return

        runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                hookDoRevokeByDexKit(bridge, classLoader)
                hookSendTipByDexKit(bridge, classLoader)
            }
        }.onFailure {
            xlog("DexKit hook setup failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun hookDoRevokeByDexKit(bridge: DexKitBridge, classLoader: ClassLoader) {
        if (sourceHookInstalled.get()) return

        val descriptor = runCatching {
            bridge.findMethod {
                matcher {
                    usingEqStrings(DO_REVOKE_LOG)
                }
            }.firstOrNull()?.descriptor
        }.getOrNull() ?: runCatching {
            // 部分版本格式化串微调时用 contains
            bridge.findMethod {
                matcher {
                    usingStrings("doRevokeMsg xmlSrvMsgId=")
                }
            }.firstOrNull()?.descriptor
        }.getOrNull()

        if (descriptor.isNullOrBlank()) {
            xlog("DexKit did not find doRevokeMsg")
            return
        }

        val method = runCatching { descriptorToMethod(descriptor, classLoader) }.getOrNull()
        if (method == null) {
            xlog("DexKit descriptor could not resolve: $descriptor")
            return
        }

        hookSourceRevokeMethod(method, "DexKit:$descriptor")
    }

    /**
     * 混淆类名随版本变化（仅 8.0.69 常见 iy0.u）。
     * 主路径已是 DexKit + [DO_REVOKE_LOG]；此处多候选兜底。
     */
    private fun hookKnownCurrentDoRevoke(classLoader: ClassLoader) {
        if (sourceHookInstalled.get()) return

        val candidates = listOf(
            "iy0.u", "jy0.u", "hy0.u", "ky0.u", "iy0.t", "iy0.v",
            "iz0.u", "ix0.u", "hz0.u"
        )
        for (name in candidates) {
            val ok = runCatching {
                val clazz = XposedHelpers.findClass(name, classLoader)
                val matches = clazz.declaredMethods.filter { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 6 &&
                        method.parameterTypes[0] == String::class.java &&
                        method.parameterTypes[1] == Long::class.javaPrimitiveType
                }
                matches.forEach { hookSourceRevokeMethod(it, "fallback:$name.${it.name}") }
                matches.isNotEmpty()
            }.getOrDefault(false)
            if (ok || sourceHookInstalled.get()) return
        }
        xlog("static doRevokeMsg fallback not found (DexKit primary for 69-76)")
    }

    private fun hookSourceRevokeMethod(method: Method, label: String) {
        if (!sourceHookInstalled.compareAndSet(false, true)) return

        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 无条件标记“自己撤回”：即使未开防撤回，也让 ChatEnhance 底部时间清理命中
                val talker = param.args.getOrNull(0)?.toString().orEmpty()
                val serverId = (param.args.getOrNull(1) as? Number)?.toLong() ?: 0L
                val replaceMsg = param.args.getOrNull(3)?.toString().orEmpty()
                val self = AntiRevokeLogic.isSelfRevoke(replaceMsg)
                if (self && serverId > 0L) {
                    SelfRevokeMessageRegistry.mark(serverId)
                    xlog("MARKED self revoke source serverId=$serverId (unconditional)")
                }
                val options = AppFeatureConfig.load()
                if (!options.antiRevoke) return
                // doRevokeMsg: talker=会话内部号(wxid/群id), serverId=msgSvrId/newmsgid
                if (self) {
                    if (!options.keepSelfRevoke) return
                }
                if (options.revokeNotice) {
                    insertReminderFromCache(
                        serverId = serverId,
                        fallbackContent = replaceMsg,
                        options = options,
                        sessionTalker = talker
                    )
                }
                param.result = null
                xlog("BLOCKED source revoke serverId=$serverId talker=$talker self=$self")
            }
        })
        xlog("hooked source revoke via $label")
    }

    private fun hookSendTipByDexKit(bridge: DexKitBridge, classLoader: ClassLoader) {
        if (sendTipMethod != null) return

        val descriptor = runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.mm.storage")
                matcher {
                    returnType(Long::class.java)
                    usingStrings(SEND_TIP_LOG)
                }
            }.firstOrNull()?.descriptor
        }.getOrNull()

        if (descriptor.isNullOrBlank()) {
            xlog("DexKit did not find send-tip cache method")
            return
        }

        val method = runCatching { descriptorToMethod(descriptor, classLoader) }.getOrNull()
        if (method == null) {
            xlog("send-tip descriptor could not resolve: $descriptor")
            return
        }

        method.isAccessible = true
        sendTipMethod = method
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val msgInfo = param.args.firstOrNull() ?: return
                msgInfoStorage = param.thisObject
                cacheMessageObject(msgInfo)
            }
        })
        xlog("hooked send-tip cache method: $descriptor")
    }

    private fun hookNativeFileSystemByDexKit(bridge: DexKitBridge, classLoader: ClassLoader) {
        val descriptor = runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.mm")
                matcher {
                    usingStrings("VFS.NativeFileSystem", "Cannot create directory")
                }
            }.firstOrNull()?.descriptor
        }.getOrNull() ?: return

        val anchor = runCatching { descriptorToMethod(descriptor, classLoader) }.getOrNull() ?: return
        var count = 0
        anchor.declaringClass.declaredMethods.forEach { method ->
            if (method.returnType != Boolean::class.javaPrimitiveType) return@forEach
            if (method.parameterTypes.size != 1 || method.parameterTypes[0] != String::class.java) return@forEach
            if (!method.name.all { it in 'a'..'z' }) return@forEach

            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!AppFeatureConfig.load().mediaProtect) return
                    val path = firstStringField(param.thisObject) ?: return
                    if (!shouldProtectMediaPath(path)) return
                    if (Thread.currentThread().stackTrace.any { it.className.contains("modelimage") }) return
                    param.result = true
                    xlog("PROTECTED media path: ${path.takeLast(80)}")
                }
            })
            count++
        }
        xlog("hooked $count native VFS media methods")
    }

    private fun installDatabaseHooks(classLoader: ClassLoader) {
        if (dbHookInstalled.get()) return

        val dbClass = findDbClass(classLoader)
        if (dbClass == null) {
            xlog("database class not ready")
            return
        }

        var count = 0
        count += hookDbMethod(dbClass, "insert", String::class.java, String::class.java, ContentValues::class.java)
        count += hookDbMethod(dbClass, "insertOrThrow", String::class.java, String::class.java, ContentValues::class.java)
        count += hookDbMethod(
            dbClass,
            "insertWithOnConflict",
            String::class.java,
            String::class.java,
            ContentValues::class.java,
            Int::class.javaPrimitiveType!!
        )
        count += hookDbMethod(dbClass, "replace", String::class.java, String::class.java, ContentValues::class.java)
        count += hookDbMethod(dbClass, "replaceOrThrow", String::class.java, String::class.java, ContentValues::class.java)
        count += hookDbMethod(dbClass, "update", String::class.java, ContentValues::class.java, String::class.java, Array<String>::class.java)
        count += hookDbMethod(
            dbClass,
            "updateWithOnConflict",
            String::class.java,
            ContentValues::class.java,
            String::class.java,
            Array<String>::class.java,
            Int::class.javaPrimitiveType!!
        )
        count += hookDbMethod(dbClass, "delete", String::class.java, String::class.java, Array<String>::class.java)
        // rawQuery：抓 DB 实例，保证能查 rcontact
        count += hookRawQueryForDbCapture(dbClass)

        if (count > 0) {
            dbHookInstalled.set(true)
            xlog("hooked $count DB methods on ${dbClass.name}")
        } else {
            xlog("no DB method signatures matched on ${dbClass.name}")
        }
    }

    private fun hookRawQueryForDbCapture(dbClass: Class<*>): Int {
        var n = 0
        dbClass.declaredMethods.filter { it.name == "rawQuery" }.forEach { method ->
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        ContactDisplayNames.rememberDb(param.thisObject)
                    }
                })
                n++
            }
        }
        return if (n > 0) 1 else 0
    }

    private fun hookDbMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Int {
        return try {
            XposedHelpers.findAndHookMethod(clazz, name, *params, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 记下微信 DB，供 rcontact 查备注（conRemark）
                    ContactDisplayNames.rememberDb(param.thisObject)
                    val table = param.args.firstOrNull { it is String } as? String ?: return
                    val options = AppFeatureConfig.load()
                    if (options.mediaProtect && MediaTableProtectionPolicy.shouldBlock(name, table)) {
                        param.result = dbBlockedResult(name)
                        xlog("BLOCKED media table $name on $table")
                        return
                    }

                    // 联系人写入：直接缓存 conRemark（最稳，不依赖 rawQuery）
                    if (table.equals("rcontact", ignoreCase = true)) {
                        val values = param.args.firstOrNull { it is ContentValues } as? ContentValues
                        ContactDisplayNames.rememberFromContactValues(values)
                        return
                    }

                    if (table != MESSAGE_TABLE) return
                    if (!options.antiRevoke) return
                    val values = param.args.firstOrNull { it is ContentValues } as? ContentValues ?: return
                    if (handleMessageValues(name, values, options)) {
                        // 拦截撤回且不写提示：吞掉本次 DB 更新
                        param.result = 1
                    }
                }
            })
            1
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * @return true 表示应拦截本次 DB 写（不落撤回系统消息）
     */
    private fun handleMessageValues(
        methodName: String,
        values: ContentValues,
        options: AppFeatureOptions = AppFeatureConfig.load()
    ): Boolean {
        val type = values.getAsInteger("type") ?: return false
        val msgId = values.longValue("msgId") ?: values.longValue("msgSvrId") ?: 0L
        val content = values.getAsString("content").orEmpty()

        if (type != RECALL_TYPE || !AntiRevokeLogic.looksLikeRevoke(content)) {
            cacheOriginalFromValues(msgId, type, content, values)
            return false
        }

        if (AntiRevokeLogic.isSelfRevoke(content) && !options.keepSelfRevoke) {
            markSelfRevokeFromValues(values, msgId)
            xlog("MARKED self revoke DB msgId=$msgId (pass-through)")
            return false
        }
        if (AntiRevokeLogic.isSelfRevoke(content)) {
            markSelfRevokeFromValues(values, msgId)
            xlog("MARKED self revoke DB msgId=$msgId (keep)")
        }

        val session = values.getAsString("talker")
            ?: AntiRevokeLogic.extractSession(content)
        val newMsgId = AntiRevokeLogic.extractNewMsgId(content)
            .takeIf { it > 0L }
            ?: values.longValue("msgSvrId")
            ?: 0L
        val original = resolveOriginal(
            msgId = msgId,
            msgSvrId = newMsgId,
            talker = session
        )
        val self = AntiRevokeLogic.isSelfRevoke(content)
        val senderWxId = AntiRevokeLogic.resolveSenderWxId(
            self = self,
            original = original,
            sessionTalker = session,
            revokeXml = content
        )
        // 仅作兜底：默认路径会直接用微信 replacemsg（已含备注）
        val displayName = ContactDisplayNames.displayOrId(senderWxId, content)
        if (!senderWxId.isNullOrBlank() && displayName != "对方") {
            ContactDisplayNames.remember(senderWxId, displayName)
        }
        val update = RevokeUpdate(
            table = MESSAGE_TABLE,
            msgId = msgId,
            type = type,
            content = content
        )
        return when (
            val action = AntiRevokeLogic.analyze(
                update = update,
                original = original,
                keepSelf = options.keepSelfRevoke,
                noticeTemplate = options.revokeNoticeTemplate,
                showNotice = options.revokeNotice,
                sessionTalker = session,
                displayName = displayName
            )
        ) {
            RevokeAction.Ignore -> false
            is RevokeAction.KeepRevokeNotice -> {
                if (!options.revokeNotice) {
                    xlog("BLOCKED DB revoke without notice msgId=$msgId")
                    true
                } else {
                    values.put("type", RECALL_TYPE)
                    values.put("content", action.content)
                    xlog(
                        "KEPT DB recall notice via $methodName msgId=$msgId " +
                            "wxid=$senderWxId name=$displayName type=${original?.type} " +
                            "snippet=${action.content.take(48)}"
                    )
                    false
                }
            }
        }
    }

    private fun cacheOriginalFromValues(msgId: Long, type: Int, content: String, values: ContentValues) {
        // 图片等 type 的 content 可能是路径或空，仍要缓存 type 供提示用
        if (msgId <= 0 && (values.longValue("msgSvrId") ?: 0L) <= 0L) return
        val talker = values.getAsString("talker")
        val msgSvrId = values.longValue("msgSvrId") ?: 0L
        val sender = AntiRevokeLogic.extractGroupSender(content)
            ?: talker?.takeIf { AntiRevokeLogic.isInternalId(it) && !AntiRevokeLogic.isChatRoom(it) }
        val original = OriginalMessage(
            type = type,
            content = content,
            talker = talker,
            createTime = values.getAsLong("createTime") ?: 0L,
            sender = sender,
            msgSvrId = msgSvrId
        )
        putOriginal(original, msgId, msgSvrId, talker)
    }

    /** 按 msgId / msgSvrId / talker:msgSvrId 解析原消息（缓存键） */
    private fun resolveOriginal(msgId: Long, msgSvrId: Long, talker: String?): OriginalMessage? {
        if (msgId > 0) messageCache[msgId]?.let { return it }
        if (msgSvrId > 0) {
            messageCache[msgSvrId]?.let { return it }
            if (!talker.isNullOrBlank()) {
                messageKeyCache["$talker:$msgSvrId"]?.let { return it }
            }
        }
        return null
    }

    private fun putOriginal(
        original: OriginalMessage,
        msgId: Long,
        msgSvrId: Long,
        talker: String?
    ) {
        if (msgId > 0) messageCache[msgId] = original
        val svr = if (msgSvrId > 0) msgSvrId else original.msgSvrId
        if (svr > 0) messageCache[svr] = original
        val t = talker ?: original.talker
        if (!t.isNullOrBlank() && svr > 0) {
            messageKeyCache["$t:$svr"] = original
            // 简单上限，避免无限涨
            if (messageKeyCache.size > 1200) {
                val drop = messageKeyCache.size - 1000
                messageKeyCache.keys.take(drop).forEach { messageKeyCache.remove(it) }
            }
        }
        if (messageCache.size > 2400) {
            val drop = messageCache.size - 2000
            messageCache.keys.take(drop).forEach { messageCache.remove(it) }
        }
    }

    private fun markSelfRevokeFromValues(values: ContentValues, fallbackId: Long) {
        SelfRevokeMessageRegistry.mark(
            fallbackId,
            values.longValue("msgId") ?: 0L,
            values.longValue("msgSvrId") ?: 0L,
            values.longValue("newMsgId") ?: 0L
        )
    }

    private fun cacheMessageObject(msgInfo: Any) {
        val type = intField(msgInfo, "field_type") ?: return
        // 图片等可能 content 为空或路径，仍缓存 type
        val content = stringField(msgInfo, "field_content").orEmpty()
        val talker = stringField(msgInfo, "field_talker")
        val msgSvrId = longField(msgInfo, "field_msgSvrId")
            ?: longField(msgInfo, "field_newMsgId")
            ?: 0L
        val sender = AntiRevokeLogic.extractGroupSender(content)
            ?: talker?.takeIf { AntiRevokeLogic.isInternalId(it) && !AntiRevokeLogic.isChatRoom(it) }
        val original = OriginalMessage(
            type = type,
            content = content,
            talker = talker,
            createTime = longField(msgInfo, "field_createTime") ?: 0L,
            sender = sender,
            msgSvrId = msgSvrId
        )

        val msgId = longField(msgInfo, "field_msgId") ?: 0L
        putOriginal(original, msgId, msgSvrId, talker)
        listOfNotNull(msgId, msgSvrId).filter { it > 0L }.forEach { id ->
            messageObjectCache[id] = msgInfo
        }
    }

    private fun insertReminderFromCache(
        serverId: Long,
        fallbackContent: String,
        options: AppFeatureOptions = AppFeatureConfig.load(),
        sessionTalker: String? = null
    ) {
        if (serverId <= 0 || !options.revokeNotice) return

        val storage = msgInfoStorage ?: return
        val method = sendTipMethod ?: return
        val msgInfo = messageObjectCache[serverId] ?: return
        val original = resolveOriginal(serverId, serverId, sessionTalker)
        val content = fallbackContent.ifBlank { "recalled a message" }
        val senderWxId = AntiRevokeLogic.resolveSenderWxId(
            self = AntiRevokeLogic.isSelfRevoke(content),
            original = original,
            sessionTalker = sessionTalker,
            revokeXml = content
        )
        val displayName = ContactDisplayNames.displayOrId(senderWxId, content)

        val action = AntiRevokeLogic.analyze(
            update = RevokeUpdate(
                table = MESSAGE_TABLE,
                msgId = serverId,
                type = RECALL_TYPE,
                content = content
            ),
            original = original,
            keepSelf = options.keepSelfRevoke,
            noticeTemplate = options.revokeNoticeTemplate,
            showNotice = true,
            sessionTalker = sessionTalker,
            displayName = displayName
        )
        val notice = when (action) {
            RevokeAction.Ignore -> return
            is RevokeAction.KeepRevokeNotice -> action.content
        }
        if (notice.isBlank()) return

        runCatching {
            setFieldIfExists(msgInfo, "field_type", RECALL_TYPE)
            setFieldIfExists(msgInfo, "field_content", notice)
            setFieldIfExists(msgInfo, "field_createTime", (original?.createTime ?: 0L) + 1L)
            setFieldIfExists(msgInfo, "x0", notice)
            try {
                method.invoke(storage, msgInfo, false, false)
            } catch (_: Throwable) {
                method.invoke(storage, msgInfo, false)
            }
            xlog("inserted source revoke notice serverId=$serverId notice=${notice.take(48)}")
        }.onFailure {
            xlog("source revoke notice failed serverId=$serverId: ${it.message}")
        }
    }

    private fun scanAndHookStorageClasses(classLoader: ClassLoader) {
        if (!storageScanAttempted.compareAndSet(false, true)) return

        val candidates = mutableListOf<String>()
        for (prefix in listOf("b", "k", "m", "t", "s")) {
            for (c in 'a'..'z') {
                candidates.add("com.tencent.mm.storage.$prefix$c")
                for (c2 in 'a'..'z') {
                    candidates.add("com.tencent.mm.storage.$prefix$c$c2")
                }
            }
        }

        var hookedCount = 0
        candidates.forEach { className ->
            val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull()
                ?: return@forEach
            clazz.declaredMethods.forEach { method ->
                if (method.name.startsWith("access$")) return@forEach
                if (!method.parameterTypes.any { it == ContentValues::class.java }) return@forEach

                runCatching {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val options = AppFeatureConfig.load()
                            if (!options.antiRevoke) return
                            val values = param.args.firstOrNull { it is ContentValues } as? ContentValues ?: return
                            if (handleMessageValues(
                                    "${className.substringAfterLast('.')}.${method.name}",
                                    values,
                                    options
                                )
                            ) {
                                param.result = 1
                            }
                        }
                    })
                    hookedCount++
                }
            }
        }
        xlog("hooked $hookedCount storage ContentValues methods")
    }

    private fun findDbClass(classLoader: ClassLoader): Class<*>? {
        return listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "android.database.sqlite.SQLiteDatabase"
        ).firstNotNullOfOrNull { className ->
            runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull()
        }
    }

    private fun loadDexKitNative(context: Context, modulePath: String?) {
        if (dexKitNativeLoaded.get()) return

        runCatching {
            System.loadLibrary("dexkit")
        }.onSuccess {
            dexKitNativeLoaded.set(true)
            xlog("DexKit native loaded via library path")
            return
        }

        val apkPath = modulePath ?: throw IllegalStateException("module path unavailable for libdexkit.so")
        val abi = currentAbi()
        val out = File(context.cacheDir, "abc_${abi}_libdexkit.so")
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/libdexkit.so")
                ?: throw IllegalStateException("lib/$abi/libdexkit.so not found in module apk")
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        System.load(out.absolutePath)
        dexKitNativeLoaded.set(true)
        xlog("DexKit native loaded from module apk")
    }

    private fun currentAbi(): String {
        return if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a"
        } else {
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull() ?: "armeabi-v7a"
        }
    }

    private fun descriptorToMethod(descriptor: String, classLoader: ClassLoader): Method {
        val arrow = descriptor.indexOf("->")
        val argsStart = descriptor.indexOf('(', arrow)
        require(arrow > 1 && argsStart > arrow) { descriptor }

        val className = descriptor.substring(1, arrow - 1).replace('/', '.')
        val methodName = descriptor.substring(arrow + 2, argsStart)
        val signature = descriptor.substring(argsStart)
        var clazz: Class<*>? = classLoader.loadClass(className)
        while (clazz != null) {
            clazz.declaredMethods.firstOrNull { method ->
                method.name == methodName && methodSignature(method) == signature
            }?.let {
                it.isAccessible = true
                return it
            }
            clazz = clazz.superclass
        }
        throw NoSuchMethodException(descriptor)
    }

    private fun methodSignature(method: Method): String {
        return buildString {
            append('(')
            method.parameterTypes.forEach { append(typeSignature(it)) }
            append(')')
            append(typeSignature(method.returnType))
        }
    }

    private fun typeSignature(type: Class<*>): String {
        if (type.isPrimitive) {
            return when (type) {
                java.lang.Integer.TYPE -> "I"
                java.lang.Void.TYPE -> "V"
                java.lang.Boolean.TYPE -> "Z"
                java.lang.Character.TYPE -> "C"
                java.lang.Byte.TYPE -> "B"
                java.lang.Short.TYPE -> "S"
                java.lang.Float.TYPE -> "F"
                java.lang.Long.TYPE -> "J"
                java.lang.Double.TYPE -> "D"
                else -> error("Unknown primitive $type")
            }
        }
        if (type.isArray) return type.name.replace('.', '/')
        return "L${type.name.replace('.', '/')};"
    }

    private fun ContentValues.longValue(key: String): Long? {
        return runCatching { getAsLong(key) }.getOrNull()
    }

    private fun dbBlockedResult(methodName: String): Any {
        return if (methodName == "delete" || methodName.startsWith("update")) 1 else 1L
    }

    private fun shouldProtectMediaPath(path: String): Boolean {
        return listOf("image2", "emoji", "voice2", "video")
            .any { path.contains(it, ignoreCase = true) }
    }

    private fun firstStringField(target: Any): String? {
        return target.javaClass.declaredFields.firstOrNull { it.type == String::class.java }?.let {
            it.isAccessible = true
            it.get(target) as? String
        }
    }

    private fun stringField(target: Any, name: String): String? = fieldValue(target, name) as? String

    private fun intField(target: Any, name: String): Int? {
        return (fieldValue(target, name) as? Number)?.toInt()
    }

    private fun longField(target: Any, name: String): Long? {
        return (fieldValue(target, name) as? Number)?.toLong()
    }

    private fun fieldValue(target: Any, name: String): Any? {
        return findField(target.javaClass, name)?.let {
            it.isAccessible = true
            it.get(target)
        }
    }

    private fun setFieldIfExists(target: Any, name: String, value: Any) {
        val field = findField(target.javaClass, name) ?: return
        if (Modifier.isFinal(field.modifiers)) return
        field.isAccessible = true
        field.set(target, value)
    }

    private fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
