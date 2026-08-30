package com.OKK.yes.core.hooks

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Process
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * 朋友圈评论防撤回。
 *
 * 对齐 WeKit / 当前微信实现：
 * 1. [SnsCommentStorage.deleteComment / deleteBySnsId]：拦物理删，写 `[已删除]` + flag bit8
 * 2. [SnsComment.setCommentDelFlag]：拦软删 bit0
 * 3. [SnsComment.convertFrom]：历史软删行读出时恢复并打标
 * 4. [SnsSqliteDB execSQL]：安全网拦 DELETE / commentflag=1|2
 * 5. [SnsInfo.setAttrBuf]：CommentUserList 被删的项合并回来并加 `[已删除]`
 */
object AntiMomentsCommentHook {
    private const val TAG = "OKK-MomentsCmt"
    private const val KEY = "anti_moments_comment_revoke"
    private const val SNS_OBJECT = "com.tencent.mm.protocal.protobuf.SnsObject"

    private val installed = AtomicBoolean(false)
    private val dexKitNativeLoaded = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install enabled=${isEnabled()}")
        var n = 0
        n += installSnsCommentStorageHooks(context, classLoader, modulePath)
        n += installSetCommentDelFlag(context, classLoader, modulePath)
        n += installConvertFrom(context, classLoader, modulePath)
        n += installExecSqlSafety(context, classLoader, modulePath)
        n += installSetAttrBufMerge(context, classLoader, modulePath)
        // 不整段吞 processCommentDelAction：放行以便 setCommentDelFlag/setAttrBuf 钩子完成打标
        xlog("install done hooks=$n")
        ModuleLog.i("朋友圈评论防撤回已安装 hooks=$n")
    }

    fun isEnabled(): Boolean =
        runCatching { PublicConfigStore.getBoolean(KEY, true) }.getOrDefault(true)

    // ── 1. SnsCommentStorage.deleteComment / deleteBySnsId ────────────────

    private fun installSnsCommentStorageHooks(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?
    ): Int {
        return runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                var count = 0
                count += hookByStrings(
                    bridge,
                    classLoader,
                    listOf("deleteComment", "com.tencent.mm.plugin.sns.storage.SnsCommentStorage"),
                    "SnsCommentStorage.deleteComment"
                ) { m ->
                    hookMarkAndBlockDelete(m, single = true, "deleteComment")
                }
                count += hookByStrings(
                    bridge,
                    classLoader,
                    listOf("deleteBySnsId", "com.tencent.mm.plugin.sns.storage.SnsCommentStorage"),
                    "SnsCommentStorage.deleteBySnsId"
                ) { m ->
                    hookMarkAndBlockDelete(m, single = false, "deleteBySnsId")
                }
                count
            }
        }.onFailure {
            xlog("SnsCommentStorage DexKit fail: ${it.message}")
        }.getOrDefault(0)
    }

    /**
     * deleteComment(snsId, commentSvrId, type) / deleteBySnsId(snsId)
     * 查出行 → curActionBuf 加 `[已删除]` → commentflag |= 256 → 取消删除。
     */
    private fun hookMarkAndBlockDelete(method: Method, single: Boolean, label: String): Boolean {
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    runCatching {
                        markAndBlockDelete(param, single)
                    }.onFailure {
                        xlog("markAndBlockDelete fail $label: ${it.message}")
                    }
                    // 无论标记是否成功都取消删除
                    param.result = true
                    xlog("BLOCKED $label + marked")
                    ModuleLog.i("拦截朋友圈评论删除并标记[已删除]($label)")
                }
            })
            true
        }.getOrDefault(false)
    }

    private fun markAndBlockDelete(param: XC_MethodHook.MethodHookParam, single: Boolean) {
        val snsId = (param.args.getOrNull(0) as? Number)?.toLong() ?: return
        val where: String
        val args: Array<String>
        if (single) {
            val commentSvrId = (param.args.getOrNull(1) as? Number)?.toLong() ?: return
            where = "snsID = ? AND commentSvrID = ?"
            args = arrayOf(snsId.toString(), commentSvrId.toString())
        } else {
            where = "snsID = ?"
            args = arrayOf(snsId.toString())
        }

        val db = resolveSnsDb(param.thisObject) ?: run {
            xlog("markAndBlockDelete: no db handle")
            return
        }
        val cursor = rawQuery(
            db,
            "SELECT rowid, curActionBuf, commentflag FROM SnsComment WHERE $where",
            args
        ) ?: return
        cursor.use { c ->
            while (c.moveToNext()) {
                val rowId = c.getLong(0)
                val actionBuf = runCatching { c.getBlob(1) }.getOrNull()
                val currentFlag = runCatching { c.getInt(2) }.getOrDefault(0)
                if (AntiMomentsCommentLogic.isIntercepted(currentFlag)) continue

                val newFlag = AntiMomentsCommentLogic.restoreFlagKeepIntercepted(currentFlag)
                val newBuf = AntiMomentsCommentLogic.injectDeletedMarkerIntoActionBuf(actionBuf)
                val cv = ContentValues().apply {
                    put("curActionBuf", newBuf)
                    put("commentflag", newFlag)
                }
                updateRow(db, "SnsComment", cv, "rowid = ?", arrayOf(rowId.toString()))
                xlog("marked rowid=$rowId flag=$currentFlag->$newFlag")
            }
        }
    }

    // ── 2. setCommentDelFlag ──────────────────────────────────────────────

    private fun installSetCommentDelFlag(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?
    ): Int {
        return runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                hookByStrings(
                    bridge,
                    classLoader,
                    listOf("setCommentDelFlag", "com.tencent.mm.plugin.sns.storage.SnsComment"),
                    "setCommentDelFlag"
                ) { method ->
                    runCatching {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isEnabled()) return
                                // 吞掉：不把 commentflag bit0 置 1
                                param.result = null
                                // 尝试在内存里给 curActionBuf 打标
                                runCatching { markEntityInMemory(param.thisObject) }
                                xlog("BLOCKED setCommentDelFlag")
                                ModuleLog.i("拦截朋友圈评论软删除标志")
                            }
                        })
                        true
                    }.getOrDefault(false)
                }
            }
        }.onFailure {
            xlog("setCommentDelFlag fail: ${it.message}")
        }.getOrDefault(0)
    }

    private fun markEntityInMemory(entity: Any) {
        val flagField = findField(entity.javaClass, "field_commentflag") ?: return
        flagField.isAccessible = true
        val flag = (flagField.get(entity) as? Number)?.toInt() ?: 0
        flagField.set(entity, AntiMomentsCommentLogic.restoreFlagKeepIntercepted(flag))

        val bufField = findField(entity.javaClass, "field_curActionBuf") ?: return
        bufField.isAccessible = true
        val buf = bufField.get(entity) as? ByteArray
        bufField.set(entity, AntiMomentsCommentLogic.injectDeletedMarkerIntoActionBuf(buf))
    }

    // ── 3. convertFrom(Cursor) 救援历史软删 ───────────────────────────────

    private fun installConvertFrom(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?
    ): Int {
        return runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                hookByStrings(
                    bridge,
                    classLoader,
                    listOf("convertFrom", "com.tencent.mm.plugin.sns.storage.SnsComment"),
                    "SnsComment.convertFrom"
                ) { method ->
                    // 只 hook 带 Cursor 的 convertFrom
                    if (method.parameterTypes.isEmpty()) return@hookByStrings false
                    if (!Cursor::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                        method.parameterTypes[0].name != "android.database.Cursor"
                    ) {
                        // 有的签名是 convertFrom(Cursor) 在父类；仍 hook 子类声明
                        if (method.parameterTypes[0] != Cursor::class.java &&
                            !method.parameterTypes[0].name.contains("Cursor")
                        ) {
                            return@hookByStrings false
                        }
                    }
                    runCatching {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!isEnabled()) return
                                val entity = param.thisObject ?: return
                                val flagField = findField(entity.javaClass, "field_commentflag")
                                    ?: return
                                flagField.isAccessible = true
                                val flag = (flagField.get(entity) as? Number)?.toInt() ?: return
                                if (!AntiMomentsCommentLogic.isWechatDeleted(flag)) return
                                // 已是微信删除态：清 bit0、打 bit8、正文加标
                                flagField.set(
                                    entity,
                                    AntiMomentsCommentLogic.restoreFlagKeepIntercepted(flag)
                                )
                                val bufField = findField(entity.javaClass, "field_curActionBuf")
                                if (bufField != null) {
                                    bufField.isAccessible = true
                                    val buf = bufField.get(entity) as? ByteArray
                                    bufField.set(
                                        entity,
                                        AntiMomentsCommentLogic.injectDeletedMarkerIntoActionBuf(buf)
                                    )
                                }
                                xlog("rescued convertFrom flag=$flag")
                            }
                        })
                        true
                    }.getOrDefault(false)
                }
            }
        }.onFailure {
            xlog("convertFrom fail: ${it.message}")
        }.getOrDefault(0)
    }

    // ── 4. execSQL 安全网 ─────────────────────────────────────────────────

    private fun installExecSqlSafety(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?
    ): Int {
        return runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                var count = 0
                count += hookByStrings(
                    bridge,
                    classLoader,
                    listOf("com.tencent.mm.plugin.sns.storage.SnsSqliteDB", "execSQL"),
                    "SnsSqliteDB.execSQL"
                ) { method ->
                    hookExecSql(method, "DexKit:execSQL")
                }
                // setCommentDeleted 路径：update SnsComment set commentflag = 1
                count += hookByStrings(
                    bridge,
                    classLoader,
                    listOf("MicroMsg.SnsCommentStorage", "set sns del"),
                    "setCommentDeleted"
                ) { method ->
                    // 该方法内部会拼 SQL 调 db.i；直接拦方法更稳
                    runCatching {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isEnabled()) return
                                param.result = true
                                xlog("BLOCKED setCommentDeleted")
                                ModuleLog.i("拦截 SnsComment setCommentDeleted")
                            }
                        })
                        true
                    }.getOrDefault(false)
                }
                // ContentValues update：commentflag 删除态 → 改写 + 打标
                count += hookByStrings(
                    bridge,
                    classLoader,
                    listOf("com.tencent.mm.plugin.sns.storage.SnsSqliteDB", "update"),
                    "SnsSqliteDB.update"
                ) { method ->
                    hookContentValuesUpdate(method)
                }
                count
            }
        }.onFailure {
            xlog("execSQL safety fail: ${it.message}")
        }.getOrDefault(0)
    }

    private fun hookContentValuesUpdate(method: Method): Boolean {
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val table = param.args.getOrNull(0)?.toString() ?: return
                    if (!AntiMomentsCommentLogic.isCommentTable(table)) return
                    val values = param.args.getOrNull(1) as? ContentValues ?: return
                    val flag = values.getAsInteger("commentflag")
                        ?: values.getAsInteger("commentFlag")
                    if (!AntiMomentsCommentLogic.isDeleteFlagUpdate(flag)) return
                    val old = flag ?: 0
                    values.put(
                        "commentflag",
                        AntiMomentsCommentLogic.restoreFlagKeepIntercepted(old)
                    )
                    val buf = values.get("curActionBuf") as? ByteArray
                    if (buf != null) {
                        values.put(
                            "curActionBuf",
                            AntiMomentsCommentLogic.injectDeletedMarkerIntoActionBuf(buf)
                        )
                    }
                    xlog("rewrote SnsComment update flag=$old")
                    ModuleLog.i("朋友圈评论 update 改写为[已删除]")
                }
            })
            true
        }.getOrDefault(false)
    }

    private fun hookExecSql(method: Method, label: String): Boolean {
        return runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val a0 = param.args.getOrNull(0)?.toString()
                    val a1 = param.args.getOrNull(1)?.toString()
                    val table: String?
                    val sql: String?
                    when {
                        AntiMomentsCommentLogic.isCommentTable(a0) -> {
                            table = a0
                            sql = a1
                        }
                        a0 != null && a0.contains("SnsComment", ignoreCase = true) -> {
                            table = null
                            sql = a0
                        }
                        else -> {
                            table = a0
                            sql = a1
                        }
                    }
                    if (!AntiMomentsCommentLogic.shouldBlockExecSql(table, sql)) return
                    if (method.returnType == Boolean::class.javaPrimitiveType ||
                        method.returnType == java.lang.Boolean.TYPE
                    ) {
                        param.result = true
                    } else {
                        param.result = null
                    }
                    xlog("BLOCKED execSQL via $label")
                }
            })
            true
        }.getOrDefault(false)
    }

    // ── 5. setAttrBuf：合并被删评论 + [已删除] ────────────────────────────

    private fun installSetAttrBufMerge(
        context: Context,
        classLoader: ClassLoader,
        modulePath: String?
    ): Int {
        return runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                hookByStrings(
                    bridge,
                    classLoader,
                    listOf("setAttrBuf", "com.tencent.mm.plugin.sns.storage.SnsInfo"),
                    "SnsInfo.setAttrBuf"
                ) { method ->
                    if (method.parameterTypes.size != 1) return@hookByStrings false
                    if (method.parameterTypes[0] != ByteArray::class.java) return@hookByStrings false
                    runCatching {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isEnabled()) return
                                val newBuf = param.args.getOrNull(0) as? ByteArray ?: return
                                val oldBuf = runCatching {
                                    XposedHelpers.getObjectField(param.thisObject, "field_attrBuf")
                                }.getOrNull() as? ByteArray
                                if (oldBuf == null || oldBuf.isEmpty()) return
                                val merged = mergeAttrBufComments(oldBuf, newBuf, classLoader)
                                    ?: return
                                if (merged !== newBuf && !merged.contentEquals(newBuf)) {
                                    param.args[0] = merged
                                    xlog("merged CommentUserList into setAttrBuf")
                                    ModuleLog.i("朋友圈评论防删: 合并回 CommentUserList + [已删除]")
                                }
                            }
                        })
                        true
                    }.getOrDefault(false)
                }
            }
        }.onFailure {
            xlog("setAttrBuf merge fail: ${it.message}")
        }.getOrDefault(0)
    }

    /**
     * 旧 attrBuf 里有、新 attrBuf 里没的评论 → 加回并给正文加 `[已删除]`。
     */
    private fun mergeAttrBufComments(
        oldBuf: ByteArray,
        newBuf: ByteArray,
        classLoader: ClassLoader
    ): ByteArray? {
        return runCatching {
            val snsClass = XposedHelpers.findClass(SNS_OBJECT, classLoader)
            val oldObj = parseSnsObject(snsClass, oldBuf) ?: return null
            val newObj = parseSnsObject(snsClass, newBuf) ?: return null
            val oldList = readCommentList(oldObj) ?: return null
            val newList = readCommentList(newObj) ?: return null
            if (oldList.isEmpty()) return null

            val newKeys = newList.mapNotNull { item ->
                (item as? Any)?.let { commentKey(it) }
            }.toHashSet()
            var added = 0
            for (raw in oldList) {
                val c = raw as? Any ?: continue
                val key = commentKey(c) ?: continue
                if (key in newKeys) continue
                markCommentContentDeleted(c)
                // 清删除标志字段 s / f233756s（若存在）
                clearCommentDeleteBit(c)
                @Suppress("UNCHECKED_CAST")
                (newList as MutableList<Any?>).add(c)
                newKeys.add(key)
                added++
            }
            if (added == 0) return null
            val size = newList.size
            runCatching { XposedHelpers.setObjectField(newObj, "CommentCount", size) }
            runCatching { XposedHelpers.setObjectField(newObj, "CommentUserListCount", size) }
            toByteArray(newObj)
        }.onFailure {
            xlog("mergeAttrBufComments: ${it.message}")
        }.getOrNull()
    }

    private fun parseSnsObject(clazz: Class<*>, buf: ByteArray): Any? {
        val inst = clazz.newInstance()
        val parseFrom = findParseFrom(clazz) ?: return null
        parseFrom.isAccessible = true
        return parseFrom.invoke(inst, buf) ?: inst
    }

    private fun readCommentList(snsObject: Any): MutableList<*>? {
        val list = runCatching {
            XposedHelpers.getObjectField(snsObject, "CommentUserList")
        }.getOrNull()
        return list as? MutableList<*>
    }

    private fun commentKey(comment: Any): String? {
        val user = readStringField(
            comment,
            listOf("f233744d", "d", "Username", "username", "UserName")
        ) ?: return null
        val id = readIntLikeField(
            comment,
            listOf("f233750m", "m", "CommentId", "commentId", "i")
        ) ?: 0
        return "$user#$id"
    }

    private fun markCommentContentDeleted(comment: Any) {
        val names = listOf("f233748h", "h", "m", "Content", "content", "Desc")
        for (name in names) {
            val field = findField(comment.javaClass, name) ?: continue
            if (field.type != String::class.java) continue
            field.isAccessible = true
            val cur = field.get(comment) as? String
            // 优先改「看起来像正文」的字段：非空或字段名含 h
            if (cur != null || name.contains("h", ignoreCase = true) || name == "f233748h") {
                field.set(comment, AntiMomentsCommentLogic.markDeletedContent(cur))
                return
            }
        }
        // 兜底：扫所有 String 字段，挑第一个非 username 的
        var cls: Class<*>? = comment.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (f.type != String::class.java) continue
                f.isAccessible = true
                val v = f.get(comment) as? String ?: continue
                if (v.contains("@") || v.startsWith("wxid_")) continue
                if (!AntiMomentsCommentLogic.needsDeletedPrefix(v)) return
                f.set(comment, AntiMomentsCommentLogic.markDeletedContent(v))
                return
            }
            cls = cls.superclass
        }
    }

    private fun clearCommentDeleteBit(comment: Any) {
        for (name in listOf("f233756s", "s", "DeleteFlag", "deleteFlag")) {
            val f = findField(comment.javaClass, name) ?: continue
            if (f.type != Int::class.javaPrimitiveType && f.type != Integer::class.java) continue
            f.isAccessible = true
            runCatching { f.set(comment, 0) }
            return
        }
    }

    // ── DB helpers ────────────────────────────────────────────────────────

    private fun resolveSnsDb(storage: Any): Any? {
        // SnsCommentStorage 持有唯一非基础类型 final 字段 = db
        val cls = storage.javaClass
        val fields = mutableListOf<java.lang.reflect.Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            fields += c.declaredFields
            c = c.superclass
        }
        for (f in fields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            val t = f.type
            if (t.isPrimitive || t == String::class.java || t.name.startsWith("java.")) continue
            f.isAccessible = true
            val v = runCatching { f.get(storage) }.getOrNull() ?: continue
            // 必须有 rawQuery / update 能力
            if (findDbRawQuery(v) != null) return v
        }
        return null
    }

    private fun findDbRawQuery(db: Any): Method? {
        var c: Class<*>? = db.javaClass
        while (c != null) {
            for (m in c.declaredMethods) {
                val p = m.parameterTypes
                if (p.size == 2 &&
                    p[0] == String::class.java &&
                    (p[1] == Array<String>::class.java || p[1].name == "[Ljava.lang.String;") &&
                    Cursor::class.java.isAssignableFrom(m.returnType)
                ) {
                    m.isAccessible = true
                    return m
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun findDbUpdate(db: Any): Method? {
        var c: Class<*>? = db.javaClass
        while (c != null) {
            for (m in c.declaredMethods) {
                val p = m.parameterTypes
                if (p.size == 4 &&
                    p[0] == String::class.java &&
                    p[1] == ContentValues::class.java &&
                    p[2] == String::class.java &&
                    (p[3] == Array<String>::class.java || p[3].name == "[Ljava.lang.String;")
                ) {
                    m.isAccessible = true
                    return m
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun rawQuery(db: Any, sql: String, args: Array<String>): Cursor? {
        val m = findDbRawQuery(db) ?: return null
        return runCatching { m.invoke(db, sql, args) as? Cursor }.getOrNull()
    }

    private fun updateRow(
        db: Any,
        table: String,
        values: ContentValues,
        where: String,
        args: Array<String>
    ): Int {
        val m = findDbUpdate(db) ?: return -1
        return runCatching { (m.invoke(db, table, values, where, args) as? Number)?.toInt() ?: -1 }
            .getOrDefault(-1)
    }

    // ── reflection utils ──────────────────────────────────────────────────

    private fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            runCatching {
                return c.getDeclaredField(name)
            }
            c = c.superclass
        }
        return null
    }

    private fun readStringField(obj: Any, names: List<String>): String? {
        for (n in names) {
            val f = findField(obj.javaClass, n) ?: continue
            if (f.type != String::class.java) continue
            f.isAccessible = true
            val v = f.get(obj) as? String
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    private fun readIntLikeField(obj: Any, names: List<String>): Int? {
        for (n in names) {
            val f = findField(obj.javaClass, n) ?: continue
            f.isAccessible = true
            val v = f.get(obj) ?: continue
            when (v) {
                is Int -> return v
                is Long -> return v.toInt()
                is Number -> return v.toInt()
            }
        }
        return null
    }

    private fun findParseFrom(clazz: Class<*>): Method? =
        clazz.methods.firstOrNull {
            it.name == "parseFrom" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == ByteArray::class.java
        } ?: clazz.declaredMethods.firstOrNull {
            it.name == "parseFrom" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == ByteArray::class.java
        }

    private fun toByteArray(obj: Any): ByteArray? {
        val m = obj.javaClass.methods.firstOrNull {
            it.name == "toByteArray" && it.parameterTypes.isEmpty()
        } ?: return null
        m.isAccessible = true
        return m.invoke(obj) as? ByteArray
    }

    private fun hookByStrings(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        strings: List<String>,
        label: String,
        install: (Method) -> Boolean
    ): Int {
        val methods = runCatching {
            bridge.findMethod {
                matcher {
                    usingStrings(*strings.toTypedArray())
                }
            }
        }.getOrNull().orEmpty()
        if (methods.isEmpty()) {
            xlog("DexKit miss $label")
            return 0
        }
        var count = 0
        methods.forEach { data ->
            val m = runCatching { descriptorToMethod(data.descriptor, classLoader) }.getOrNull()
            if (m != null && install(m)) {
                count++
                xlog("hooked $label -> ${data.descriptor}")
            }
        }
        return count
    }

    private fun loadDexKitNative(context: Context, modulePath: String?) {
        if (dexKitNativeLoaded.get()) return
        runCatching {
            System.loadLibrary("dexkit")
            dexKitNativeLoaded.set(true)
            return
        }
        val apkPath = modulePath ?: return
        val abi = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a"
        } else {
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull() ?: "armeabi-v7a"
        }
        val out = File(context.cacheDir, "abc_cmt_${abi}_libdexkit.so")
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/libdexkit.so") ?: return
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        System.load(out.absolutePath)
        dexKitNativeLoaded.set(true)
    }

    private fun descriptorToMethod(descriptor: String, classLoader: ClassLoader): Method {
        val arrow = descriptor.indexOf("->")
        val argsStart = descriptor.indexOf('(', arrow)
        val className = descriptor.substring(1, arrow - 1).replace('/', '.')
        val methodName = descriptor.substring(arrow + 2, argsStart)
        val signature = descriptor.substring(argsStart)
        var clazz: Class<*>? = classLoader.loadClass(className)
        while (clazz != null) {
            clazz.declaredMethods.firstOrNull { method ->
                method.name == methodName && methodSignature(method) == signature
            }?.let { return it }
            clazz = clazz.superclass
        }
        error("method not found $descriptor")
    }

    private fun methodSignature(method: Method): String {
        val sb = StringBuilder("(")
        method.parameterTypes.forEach { sb.append(typeDesc(it)) }
        sb.append(')').append(typeDesc(method.returnType))
        return sb.toString()
    }

    private fun typeDesc(type: Class<*>): String {
        if (type.isPrimitive) {
            return when (type) {
                Void.TYPE -> "V"
                Boolean::class.javaPrimitiveType -> "Z"
                Byte::class.javaPrimitiveType -> "B"
                Char::class.javaPrimitiveType -> "C"
                Short::class.javaPrimitiveType -> "S"
                Int::class.javaPrimitiveType -> "I"
                Long::class.javaPrimitiveType -> "J"
                Float::class.javaPrimitiveType -> "F"
                Double::class.javaPrimitiveType -> "D"
                else -> "V"
            }
        }
        if (type.isArray) return "[" + typeDesc(type.componentType!!)
        return "L" + type.name.replace('.', '/') + ";"
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
