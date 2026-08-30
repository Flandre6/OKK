package com.OKK.yes.core.hooks

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 撤回提示里的 {name}：
 * 1. 微信系统撤回句里的展示名（已是 **备注 > 昵称**）
 * 2. rcontact：**conRemark（备注）> nickname（昵称）> alias**
 * 3. 实在没有 →「对方」
 *
 * 绝不展示 wxid / 本地路径。
 */
object ContactDisplayNames {
    fun execRawQuery(sql: String, args: Array<String> = emptyArray()): List<Map<String, Any?>> {
        val dbs = capturedDbs()
        for (db in dbs) {
            val raw = invokeRawQuery(db, sql, args) ?: continue
            val cursor = raw as? android.database.Cursor ?: continue
            val out = ArrayList<Map<String, Any?>>()
            val cols = cursor.columnNames ?: emptyArray()
            while (cursor.moveToNext()) {
                val row = HashMap<String, Any?>()
                for (i in cols.indices) {
                    row[cols[i]] = cursorStringByIndex(cursor, i)
                }
                out.add(row)
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    fun hasCapturedDb(): Boolean = capturedDbs().isNotEmpty()

    private val dbList = CopyOnWriteArrayList<Any>()
    private val primaryDb = AtomicReference<Any?>(null)
    private val cache = ConcurrentHashMap<String, String>()

    private val inDbCheck = ThreadLocal.withInitial { false }

    private fun isMainDb(db: Any): Boolean {
        if (inDbCheck.get() == true) return false
        inDbCheck.set(true)
        return try {
            // 只有主数据库 EnMicroMsg.db 才拥有 rconversation 表
            val raw = invokeRawQuery(db, "SELECT count(*) FROM rconversation", emptyArray()) ?: return false
            val cursor = raw as? android.database.Cursor ?: return false
            try {
                if (cursor.moveToNext()) {
                    cursor.getInt(0) >= 0
                } else false
            } catch (_: Throwable) {
                false
            } finally {
                runCatching { cursor.close() }
            }
        } catch (_: Throwable) {
            false
        } finally {
            inDbCheck.set(false)
        }
    }

    fun rememberDb(db: Any?) {
        if (db == null) return
        if (!hasRawQuery(db)) return
        val main = isMainDb(db)
        if (main) {
            primaryDb.set(db)
            wrapperCaptured.set(true)
        }
        if (dbList.none { it === db }) {
            dbList.add(db)
            while (dbList.size > 8) dbList.removeAt(0)
        }
    }

    /** 包装器（如 ka5.b0）反射缓存：提取内部 SQLiteDatabase 的零参方法 */
    @Volatile private var wrapperDbMethod: Method? = null
    private val wrapperCaptured = AtomicBoolean(false)

    /**
     * 从微信自有 SQLite 包装器实例提取内部 DB（会话分组 hook 必走的路径，比标准 rawQuery 捕获更及时）。
     * 包装器有 P()Lcom/tencent/wcdb/database/SQLiteDatabase; 这类零参访问器，方法名混淆，按返回类型匹配。
     */
    /**
     * 1:1 WeKit 做法：通过 MMKernel 显式提取主数据库，防止由于 rawQuery 未被触发导致捕获不到主库。
     */
    @Volatile
    private var mGetStorageMethod: java.lang.reflect.Method? = null

    /** WeKit classSqliteDbWrapper：特征串 "MicroMsg.SqliteDB" + "sql is null " */
    @Volatile private var sqliteDbWrapperClazz: Class<*>? = null
    @Volatile private var wrapperClazzSearched: Boolean = false

    private fun findSqliteDbWrapperClass(cl: ClassLoader): Class<*>? {
        if (wrapperClazzSearched) return sqliteDbWrapperClazz
        wrapperClazzSearched = true
        sqliteDbWrapperClazz = runCatching {
            org.luckypray.dexkit.DexKitBridge.create(com.OKK.yes.core.compat.DexKitSupport.modulePath ?: return null)?.use { bridge ->
                bridge.findClass {
                    matcher { usingEqStrings("MicroMsg.SqliteDB", "sql is null ") }
                }.firstOrNull()?.getInstance(cl)
            }
        }.getOrNull()
        return sqliteDbWrapperClazz
    }

    /**
     * 实时获取当前账号的主数据库，解决切换账号时由于缓存导致读取旧账号数据的 bug
     */
    fun getCurrentMainDb(): Any? {
        val cl = com.OKK.yes.core.compat.DexKitSupport.classLoader ?: return primaryDb.get()
        runCatching {
            var mGetStorage = mGetStorageMethod
            if (mGetStorage == null) {
                mGetStorage = org.luckypray.dexkit.DexKitBridge.create(com.OKK.yes.core.compat.DexKitSupport.modulePath ?: return null)?.use { bridge ->
                    bridge.findMethod {
                        matcher {
                            usingStrings("mCoreStorage not initialized!")
                            paramCount(0)
                            modifiers(java.lang.reflect.Modifier.PUBLIC or java.lang.reflect.Modifier.STATIC)
                        }
                    }.firstOrNull()?.getMethodInstance(cl)
                }
                if (mGetStorage != null) {
                    mGetStorage.isAccessible = true
                    mGetStorageMethod = mGetStorage
                }
            }
            if (mGetStorage == null) return null

            val coreStorage = mGetStorage.invoke(null) ?: return null
            val fields = coreStorage.javaClass.declaredFields
            // WeKit 1:1：优先按声明类型精确匹配 MicroMsg.SqliteDB wrapper 字段（确保抓到主库 EnMicroMsg.db）
            val wrapperClazz = findSqliteDbWrapperClass(cl)
            if (wrapperClazz != null) {
                for (f in fields) {
                    val ft = f.type
                    if (ft != wrapperClazz && !ft.interfaces.contains(wrapperClazz) && !wrapperClazz.isAssignableFrom(ft)) continue
                    f.isAccessible = true
                    val obj = f.get(coreStorage) ?: continue
                    val wm = obj.javaClass.declaredMethods.firstOrNull {
                        it.parameterTypes.isEmpty() && it.returnType.name == "com.tencent.wcdb.database.SQLiteDatabase"
                    } ?: continue
                    val wdb = runCatching { wm.isAccessible = true; wm.invoke(obj) }.getOrNull()
                    if (wdb != null && hasRawQuery(wdb)) {
                        de.robv.android.xposed.XposedBridge.log("[OKK-CG] getCurrentMainDb via SqliteDB-wrapper field '${f.name}' cls=${wdb.javaClass.name}")
                        return wdb
                    }
                }
            }
            for (f in fields) {
                f.isAccessible = true
                val obj = f.get(coreStorage) ?: continue
                val cls = obj.javaClass
                if (cls.name == "com.tencent.wcdb.database.SQLiteDatabase" ||
                    cls.name == "android.database.sqlite.SQLiteDatabase" ||
                    cls.name == "com.tencent.wcdb.compat.SQLiteDatabase"
                ) {
                    return obj
                }
                // Try wrapper
                val m = wrapperDbMethod ?: cls.declaredMethods.firstOrNull {
                    it.parameterTypes.isEmpty() &&
                        (it.returnType.name == "com.tencent.wcdb.database.SQLiteDatabase" ||
                            it.returnType.name == "android.database.sqlite.SQLiteDatabase")
                }?.also { wrapperDbMethod = it }
                
                if (m != null) {
                    val db = runCatching {
                        m.isAccessible = true
                        m.invoke(obj)
                    }.getOrNull()
                    if (db != null && hasRawQuery(db)) {
                        return db
                    }
                }
            }
        }
        return primaryDb.get()
    }

    fun ensureMainDb(cl: ClassLoader) {
        getCurrentMainDb()
    }

    fun rememberWrapper(wrapper: Any?) {
        if (wrapper == null || wrapperCaptured.get() || primaryDb.get() != null) return
        val cls = wrapper.javaClass
        // 自身就是 DB 的情况直接收
        if (cls.name == "com.tencent.wcdb.database.SQLiteDatabase" ||
            cls.name == "android.database.sqlite.SQLiteDatabase" ||
            cls.name == "com.tencent.wcdb.compat.SQLiteDatabase"
        ) {
            rememberDb(wrapper)
            return
        }
        val m = wrapperDbMethod ?: cls.declaredMethods.firstOrNull {
            it.parameterTypes.isEmpty() &&
                (it.returnType.name == "com.tencent.wcdb.database.SQLiteDatabase" ||
                    it.returnType.name == "android.database.sqlite.SQLiteDatabase")
        }?.also { wrapperDbMethod = it } ?: return
        val db = runCatching {
            m.isAccessible = true
            m.invoke(wrapper)
        }.getOrNull() ?: return
        if (db != null && hasRawQuery(db)) {
            rememberDb(db)
        }
    }

    /**
     * 查询全部联系人（自定义会话分组的成员选择数据源）。
     * 返回 (username, conRemark, nickname) 三元组；db 未就绪时返回空列表。
     * WCDB 的 Cursor 实现兼容 android.database.Cursor 接口，可直接强转。
     */
    fun queryAllContacts(limit: Int = 1500): List<Triple<String, String, String>> {
        return queryCleanContactsForGrouping()
    }

    /** 查询当前会话库里符合条件的未读 talker，用于会话分组双击标已读。 */
    fun queryUnreadConversationUsernames(where: String? = null, limit: Int = 1000): List<String> {
        val unread = "(unReadCount>0 OR unReadMuteCount>0 OR atCount>0)"
        val filter = where?.trim()?.takeIf { it.isNotEmpty() }
        val sql = buildString {
            append("SELECT username FROM rconversation WHERE ")
            append(unread)
            if (filter != null) append(" AND (").append(filter).append(')')
            append(" LIMIT ").append(limit.coerceIn(1, 5000))
        }
        for (db in capturedDbs()) {
            if (!hasRawQuery(db)) continue
            val raw = invokeRawQuery(db, sql, emptyArray()) ?: continue
            val cursor = raw as? android.database.Cursor ?: continue
            val out = ArrayList<String>()
            try {
                while (cursor.moveToNext()) {
                    val u = runCatching { cursor.getString(0) }.getOrNull()?.trim().orEmpty()
                    if (u.isNotEmpty()) out.add(u)
                }
            } finally {
                runCatching { cursor.close() }
            }
            if (out.isNotEmpty()) return out.distinct()
        }
        return emptyList()
    }

    /** 兜底执行 SQL；只供模块内部维护 rconversation 状态使用。 */
    fun execSql(sql: String, args: Array<Any?> = emptyArray()): Boolean {
        for (db in capturedDbs()) {
            val ok = invokeExecSql(db, sql, args)
            if (ok) return true
        }
        return false
    }

    private fun capturedDbs(): List<Any> = buildList {
        getCurrentMainDb()?.let { add(it) }
        primaryDb.get()?.let { add(it) }
        addAll(dbList)
    }.distinct()

    /** 诊断：dump rcontact 表结构（列名），并 dump gh_ 账号的 verifyFlag/brand 字段 */
    fun diagnoseContactSchema(): Boolean {
        val dbs = buildList {
            primaryDb.get()?.let { add(it) }
            addAll(dbList)
        }.distinct()
        for (db in dbs) {
            if (!hasRawQuery(db)) continue
            // SELECT * LIMIT 10，用 getColumnNames 拿列名（比 PRAGMA 可靠）
            val raw = invokeRawQuery(db, "SELECT * FROM rcontact WHERE username LIKE 'gh_%' LIMIT 10", emptyArray()) ?: continue
            val cursor = raw as? android.database.Cursor ?: continue
            val sb = StringBuilder()
            try {
                val colNames = runCatching { cursor.columnNames?.toList() }.getOrNull().orEmpty()
                sb.append("rcontact columns: ").append(colNames.joinToString(",")).append("\n\n")
                // dump gh_ 行的非空字段
                var rows = 0
                while (cursor.moveToNext() && rows < 6) {
                    sb.append("--- gh_ row ").append(rows).append(" ---\n")
                    for (i in 0 until cursor.columnCount) {
                        val v = runCatching { cursor.getString(i) }.getOrNull()
                        if (!v.isNullOrEmpty() && v != "0") {
                            val cname = runCatching { cursor.getColumnName(i) }.getOrNull() ?: "c$i"
                            sb.append("  ").append(cname).append("=").append(v.take(80)).append("\n")
                        }
                    }
                    rows++
                }
            } finally {
                runCatching { cursor.close() }
            }
            val f = java.io.File("/storage/emulated/0/Android/media/com.tencent.mm/OKK/contact_schema.txt")
            f.parentFile?.mkdirs()
            f.writeText(sb.toString())
            return true
        }
        return false
    }


    /** 诊断：dump rconversation 的 parentRef 分布 + 官方盒子子会话 + bizinfo.type */
    fun diagnoseConversationParents(): Boolean {
        val dbs = buildList {
            primaryDb.get()?.let { add(it) }
            addAll(dbList)
        }.distinct()
        for (db in dbs) {
            if (!hasRawQuery(db)) continue
            val sb = StringBuilder()
            // 1) parentRef 分布
            runCatching {
                val raw = invokeRawQuery(
                    db,
                    "SELECT IFNULL(parentRef,'') AS p, COUNT(*) AS c FROM rconversation GROUP BY IFNULL(parentRef,'') ORDER BY c DESC LIMIT 50",
                    emptyArray()
                ) ?: return@runCatching
                val cursor = raw as? android.database.Cursor ?: return@runCatching
                try {
                    sb.append("=== parentRef distribution ===\n")
                    while (cursor.moveToNext()) {
                        val p = runCatching { cursor.getString(0) }.getOrNull().orEmpty()
                        val c = runCatching { cursor.getInt(1) }.getOrNull() ?: 0
                        sb.append("parentRef=[").append(p).append("] count=").append(c).append('\n')
                    }
                } finally {
                    runCatching { cursor.close() }
                }
            }
            // 2) 官方盒子相关行
            runCatching {
                val raw = invokeRawQuery(
                    db,
                    "SELECT username, IFNULL(parentRef,''), unReadCount, conversationTime, substr(digest,1,40) FROM rconversation WHERE username IN ('officialaccounts','service_officialaccounts') OR parentRef IN ('officialaccounts','service_officialaccounts') OR username LIKE 'gh_%' ORDER BY conversationTime DESC LIMIT 80",
                    emptyArray()
                ) ?: return@runCatching
                val cursor = raw as? android.database.Cursor ?: return@runCatching
                try {
                    sb.append("\n=== official/service rows ===\n")
                    while (cursor.moveToNext()) {
                        val u = runCatching { cursor.getString(0) }.getOrNull().orEmpty()
                        val p = runCatching { cursor.getString(1) }.getOrNull().orEmpty()
                        val ur = runCatching { cursor.getInt(2) }.getOrNull() ?: 0
                        val t = runCatching { cursor.getLong(3) }.getOrNull() ?: 0L
                        val d = runCatching { cursor.getString(4) }.getOrNull().orEmpty()
                        sb.append("u=").append(u).append(" p=[").append(p).append("] unread=").append(ur)
                            .append(" time=").append(t).append(" dig=").append(d).append('\n')
                    }
                } finally {
                    runCatching { cursor.close() }
                }
            }
            // 3) bizinfo.type 分布（服务号=1 / 公众号=0）
            runCatching {
                val raw = invokeRawQuery(
                    db,
                    "SELECT type, COUNT(*) FROM bizinfo GROUP BY type ORDER BY COUNT(*) DESC LIMIT 20",
                    emptyArray()
                ) ?: return@runCatching
                val cursor = raw as? android.database.Cursor ?: return@runCatching
                try {
                    sb.append("\n=== bizinfo.type distribution ===\n")
                    while (cursor.moveToNext()) {
                        val t = runCatching { cursor.getLong(0) }.getOrNull() ?: 0L
                        val c = runCatching { cursor.getInt(1) }.getOrNull() ?: 0
                        sb.append("bizinfo.type=").append(t).append(" count=").append(c).append('\n')
                    }
                } finally {
                    runCatching { cursor.close() }
                }
            }
            // 4) bizinfo join 样本
            runCatching {
                val raw = invokeRawQuery(
                    db,
                    "SELECT b.username, b.type, r.nickname, IFNULL(c.parentRef,''), c.conversationTime FROM bizinfo b LEFT JOIN rcontact r ON b.username=r.username LEFT JOIN rconversation c ON b.username=c.username WHERE b.username LIKE 'gh_%' ORDER BY c.conversationTime DESC LIMIT 40",
                    emptyArray()
                ) ?: return@runCatching
                val cursor = raw as? android.database.Cursor ?: return@runCatching
                try {
                    sb.append("\n=== bizinfo sample with conversation ===\n")
                    while (cursor.moveToNext()) {
                        val u = runCatching { cursor.getString(0) }.getOrNull().orEmpty()
                        val t = runCatching { cursor.getLong(1) }.getOrNull() ?: 0L
                        val n = runCatching { cursor.getString(2) }.getOrNull().orEmpty()
                        val p = runCatching { cursor.getString(3) }.getOrNull().orEmpty()
                        val tm = runCatching { cursor.getLong(4) }.getOrNull() ?: 0L
                        sb.append("u=").append(u).append(" bizType=").append(t)
                            .append(" nick=").append(n.take(20)).append(" parent=[").append(p)
                            .append("] time=").append(tm).append('\n')
                    }
                } finally {
                    runCatching { cursor.close() }
                }
            }
            if (sb.isNotEmpty()) {
                val f = java.io.File("/storage/emulated/0/Android/media/com.tencent.mm/OKK/conv_parents.txt")
                f.parentFile?.mkdirs()
                f.writeText(sb.toString())
                return true
            }
        }
        return false
    }

    /** 诊断：dump rcontact 里 gh_ 与 @openim 账号的 type/ type 分布，用于确认服务号/企业微信分类依据 */
    fun diagnoseContactTypes(): Boolean {
        val dbs = buildList {
            primaryDb.get()?.let { add(it) }
            addAll(dbList)
        }.distinct()
        for (db in dbs) {
            if (!hasRawQuery(db)) continue
            val raw = invokeRawQuery(
                db,
                "SELECT username, type, verifyFlag FROM rcontact LIMIT 3000",
                emptyArray()
            ) ?: continue
            val cursor = raw as? android.database.Cursor ?: continue
            val ghTypes = HashMap<Long, Int>()
            val openimTypes = HashMap<Long, Int>()
            val chatroomTypes = HashMap<Long, Int>()
            val friendTypes = HashMap<Long, Int>()
            try {
                val iUser = cursor.getColumnIndex("username")
                val iType = cursor.getColumnIndex("type")
                val iVerify = cursor.getColumnIndex("verifyFlag")
                if (iUser < 0) continue
                while (cursor.moveToNext()) {
                    val u = runCatching { cursor.getString(iUser) }.getOrNull().orEmpty()
                    val t = if (iType >= 0) runCatching { cursor.getLong(iType) }.getOrNull() ?: 0L else 0L
                    val m = when {
                        u.startsWith("gh_") -> ghTypes
                        u.contains("@openim") -> openimTypes
                        u.endsWith("@chatroom") || u.endsWith("@im.chatroom") -> chatroomTypes
                        else -> friendTypes
                    }
                    m[t] = (m[t] ?: 0) + 1
                }
            } finally {
                runCatching { cursor.close() }
            }
            android.util.Log.i("OKK-ContactType", "gh_ types: ${ghTypes}")
            android.util.Log.i("OKK-ContactType", "openim types: ${openimTypes}")
            android.util.Log.i("OKK-ContactType", "chatroom types: ${chatroomTypes}")
            android.util.Log.i("OKK-ContactType", "friend types: ${friendTypes.entries.take(20)}")
            // 同时写入文件，避免被微信日志系统吞掉
            runCatching {
                val sb = StringBuilder()
                sb.append("gh_ types: ${ghTypes}\n")
                sb.append("openim types: ${openimTypes}\n")
                sb.append("chatroom types: ${chatroomTypes}\n")
                sb.append("friend types: ${friendTypes.entries.take(20)}\n")
                val f = java.io.File("/storage/emulated/0/Android/media/com.tencent.mm/OKK/contact_types.txt")
                f.parentFile?.mkdirs()
                f.writeText(sb.toString())
            }
            return true
        }
        return false
    }

    /**
     * 为会话分组提取干净、无乱码、可读性极高的高质量联系人与群聊数据源。
     */
    /** 详细联系人模型 */
    data class ContactItem(
        val username: String,
        val displayName: String,
        val alias: String = "",
        val type: Int = 0,
        val verifyFlag: Int = 0,
        val encryptUsername: String = "",
        val conversationTime: Long = 0L,
        val avatarUrl: String = ""
    )

    data class ContactClassification(
        val friendWxIds: Set<String>,
        val groupWxIds: Set<String>,
        val officialWxIds: Set<String>
    )

    /**
     * 照搬 WeKit WeDatabaseApi 的 4 查询分类架构：
     * - friendWxIds   : 严格好友 SQL（encryptUsername != '' OR 自己, verifyFlag=0, type&1!=0, type&8=0, type&32=0, 非 chatroom）
     * - groupWxIds    : username LIKE '%@chatroom'
     * - officialWxIds : username LIKE 'gh_%'
     * 遍历所有已捕获数据库并合并结果（保证主库覆盖副库）。
     */
    fun queryClassification(): ContactClassification {
        val friendIds = HashSet<String>()
        val groupIds = HashSet<String>()
        val officialIds = HashSet<String>()
        
        val dbs = mutableListOf<Any>()
        val currentDb = getCurrentMainDb()
        if (currentDb != null && hasRawQuery(currentDb)) {
            dbs.add(currentDb)
        } else {
            dbs.addAll(capturedDbs())
        }
        
        for (db in dbs) {
            if (!hasRawQuery(db)) continue
            // 好友（照搬 WeKit FRIENDS SQL）
            runCatching {
                queryStringSet(db, """
                    SELECT r.username FROM rcontact r
                    WHERE (r.encryptUsername != '' OR r.username = (SELECT value FROM userinfo WHERE id = 2))
                      AND r.verifyFlag = 0
                      AND (r.type & 1) != 0
                      AND (r.type & 8) = 0
                      AND (r.type & 32) = 0
                      AND r.username NOT LIKE '%chatroom'
                """.trimIndent())
            }.getOrDefault(emptySet()).let { friendIds += it }
            // 群聊
            runCatching {
                queryStringSet(db, "SELECT r.username FROM rcontact r WHERE r.username LIKE '%@chatroom'")
            }.getOrDefault(emptySet()).let { groupIds += it }
            // 公众号
            runCatching {
                queryStringSet(db, "SELECT r.username FROM rcontact r WHERE r.username LIKE 'gh_%'")
            }.getOrDefault(emptySet()).let { officialIds += it }
        }
        de.robv.android.xposed.XposedBridge.log("[OKK-CG] classify friends=${friendIds.size} groups=${groupIds.size} officials=${officialIds.size} (dbs=${dbs.size})")
        return ContactClassification(friendIds, groupIds, officialIds)
    }

    private fun queryStringSet(db: Any, sql: String): Set<String> {
        val raw = invokeRawQuery(db, sql, emptyArray()) ?: return emptySet()
        val cursor = raw as? android.database.Cursor ?: return emptySet()
        val out = HashSet<String>()
        try {
            while (cursor.moveToNext()) {
                cursorStringByIndex(cursor, 0)?.trim()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        } finally {
            cursorClose(cursor)
        }
        return out
    }

    /**
     * 判断是否为真实好友（过滤群聊、公众号、系统服务号等）。
     */
    fun isRealFriendUsername(username: String?): Boolean {
        if (username.isNullOrBlank()) return false
        val u = username.trim()
        if (u.endsWith("@chatroom") || u.endsWith("@im.chatroom") || u.contains("@chatroom")) return false
        if (u.startsWith("gh_")) return false
        if (u.endsWith("@app") || u.endsWith("@openim") || u.endsWith("@fakeuser") || u.endsWith("@t.qq.com") || u.endsWith("@qqim")) return false
        val systemAccounts = setOf(
            "officialaccounts", "notifymessage", "notification_messages",
            "gamecenter", "wxgame", "brandservice", "brandecstemplatemsg",
            "weixinreminder", "opencustomerservicemsg", "appbrand_notify_message",
            "appbrandcustomerservicemsg", "filehelper", "fmessage", "medianote",
            "qqmail", "floatbottle", "lbsapp", "shakeapp", "newsapp", "weibo",
            "qqfriend", "masssendapp", "voip", "voicevoipapp", "cardpackage",
            "voiceinputapp", "googlecontact", "helper_entry", "blogapp", "qmessage"
        )
        if (u.lowercase() in systemAccounts) return false
        return true
    }

    @Volatile private var cachedFriends: List<ContactItem>? = null
    @Volatile private var cachedFriendsTime: Long = 0L
    private const val FRIENDS_CACHE_TTL_MS = 60_000L // 1分钟好友缓存

    fun getCachedFriendsIfPresent(): List<ContactItem>? {
        val c = cachedFriends
        return if (c != null && (System.currentTimeMillis() - cachedFriendsTime < FRIENDS_CACHE_TTL_MS)) c else null
    }

    fun invalidateFriendsCache() {
        cachedFriends = null
        cachedFriendsTime = 0L
    }

    /**
     * 极速仅好友查询（专供密友功能使用）：
     * 1. 优先使用 1 分钟内的内存缓存，实现 0ms 瞬间打开；
     * 2. 单表极速扫描 rcontact（不 JOIN 慢表 img_flag），毫秒级完成；
     * 3. 严格排除群聊、公众号、系统号。
     */
    fun queryFriendsOnlyFast(forceRefresh: Boolean = false): List<ContactItem> {
        if (!forceRefresh) {
            val c = cachedFriends
            if (c != null && (System.currentTimeMillis() - cachedFriendsTime < FRIENDS_CACHE_TTL_MS)) {
                return c
            }
        }

        val currentDb = getCurrentMainDb()
        val dbs = mutableListOf<Any>()
        if (currentDb != null && hasRawQuery(currentDb)) {
            dbs.add(currentDb)
        } else {
            dbs.addAll(capturedDbs())
        }

        val out = ArrayList<ContactItem>(512)
        val seen = HashSet<String>()

        for (db in dbs) {
            if (!hasRawQuery(db)) continue
            val sql = """
                SELECT r.username, r.alias, r.conRemark, r.nickname, r.type, r.verifyFlag, r.encryptUsername
                FROM rcontact r
                WHERE (r.type & 1) != 0
                  AND (r.type & 8) = 0
                  AND (r.type & 32) = 0
                  AND r.verifyFlag = 0
                  AND r.username NOT LIKE '%chatroom'
                  AND r.username NOT LIKE 'gh_%'
                  AND r.username != ''
                  AND r.username != 'notchatroom'
                  AND r.username NOT LIKE '%@app'
                  AND r.username NOT LIKE '%@openim'
                  AND r.username NOT LIKE '%@fakeuser'
            """.trimIndent()
            runCatching {
                val raw = invokeRawQuery(db, sql, emptyArray())
                val cursor = raw as? android.database.Cursor
                if (cursor != null) {
                    try {
                        val iUser = findColumnIndexIgnoreCase(cursor, "username")
                        val iAlias = findColumnIndexIgnoreCase(cursor, "alias")
                        val iRemark = findColumnIndexIgnoreCase(cursor, "conRemark")
                        val iNick = findColumnIndexIgnoreCase(cursor, "nickname")
                        val iType = findColumnIndexIgnoreCase(cursor, "type")
                        val iVerify = findColumnIndexIgnoreCase(cursor, "verifyFlag")
                        val iEncrypt = findColumnIndexIgnoreCase(cursor, "encryptUsername")
                        if (iUser >= 0) {
                            while (cursor.moveToNext()) {
                                val u = cursorStringByIndex(cursor, iUser)?.trim().orEmpty()
                                if (!isRealFriendUsername(u) || !seen.add(u)) continue
                                val conRemark = cleanHumanName(cursorStringByIndex(cursor, iRemark)).orEmpty()
                                val nickname = cleanHumanName(cursorStringByIndex(cursor, iNick)).orEmpty()
                                val alias = cleanHumanName(cursorStringByIndex(cursor, iAlias)).orEmpty()

                                val display = when {
                                    conRemark.isNotBlank() -> conRemark
                                    nickname.isNotBlank() -> nickname
                                    alias.isNotBlank() -> alias
                                    else -> u
                                }

                                val type = if (iType >= 0) cursorIntByIndex(cursor, iType) ?: 0 else 0
                                val verifyFlag = if (iVerify >= 0) cursorIntByIndex(cursor, iVerify) ?: 0 else 0
                                val encryptUsername = if (iEncrypt >= 0) cursorStringByIndex(cursor, iEncrypt).orEmpty() else ""

                                out.add(ContactItem(u, display, alias, type, verifyFlag, encryptUsername, 0L, ""))
                            }
                        }
                    } finally {
                        runCatching { cursor.close() }
                    }
                }
            }
            if (out.isNotEmpty()) break
        }

        val res = if (out.isNotEmpty()) out else queryCleanFriendsFallback()
        cachedFriends = res
        cachedFriendsTime = System.currentTimeMillis()
        de.robv.android.xposed.XposedBridge.log("[OKK-CF] queryFriendsOnlyFast -> ${res.size} friends loaded")
        return res
    }

    private fun queryCleanFriendsFallback(): List<ContactItem> {
        val raw = queryCleanContactsForGrouping()
        return raw.filter { isRealFriendUsername(it.first) }.map {
            ContactItem(it.first, it.second, "", 1, 0, "", 0L, "")
        }
    }

    fun queryAllContactsDetailed(): List<ContactItem> {
        // 使用动态获取的当前主库，防止由于切换账号时保留旧数据库引用导致读取旧账号数据
        val currentDb = getCurrentMainDb()
        if (currentDb != null && hasRawQuery(currentDb)) {
            val res = queryContactsFromDb(currentDb)
            de.robv.android.xposed.XposedBridge.log("[OKK-CG] queryAllContactsDetailed currentDb -> ${res.size} contacts")
            if (res.isNotEmpty()) {
                return res
            }
        }
        
        // 兜底：如果上面的动态获取失败，则尝试历史捕获的 DB 列表
        val dbs = capturedDbs()
        var bestResult = emptyList<ContactItem>()
        for (db in dbs) {
            if (!hasRawQuery(db)) continue
            val res = queryContactsFromDb(db)
            if (res.size > bestResult.size) {
                bestResult = res
            }
        }
        return bestResult
    }

    private fun queryContactsFromDb(db: Any): List<ContactItem> {
        val out = ArrayList<ContactItem>(1024)
        val seen = HashSet<String>()
        val groupNames = HashMap<String, String>()

        // 1. Fetch chatrooms from `chatroom` table to get proper display names and catch unsaved groups
        runCatching {
            val sqlChatroom = "SELECT chatroomname, displayname FROM chatroom"
            val rawChatroom = invokeRawQuery(db, sqlChatroom, emptyArray())
            val cursorChatroom = rawChatroom as? android.database.Cursor
            if (cursorChatroom != null) {
                try {
                    val iRoom = findColumnIndexIgnoreCase(cursorChatroom, "chatroomname")
                    val iDisp = findColumnIndexIgnoreCase(cursorChatroom, "displayname")
                    if (iRoom >= 0) {
                        while (cursorChatroom.moveToNext()) {
                            val room = cursorStringByIndex(cursorChatroom, iRoom)?.trim().orEmpty()
                            if (room.isNotBlank()) {
                                val disp = if (iDisp >= 0) cleanHumanName(cursorStringByIndex(cursorChatroom, iDisp)).orEmpty() else ""
                                // 无条件收录该群聊。如果 disp 为空，后续会自动兜底生成 "群聊 (...)"
                                groupNames[room] = disp
                            }
                        }
                    }
                } finally {
                    runCatching { cursorChatroom.close() }
                }
            }
        }

        // 2. Fetch all from rcontact
        val sql = """
            SELECT r.username, r.alias, r.conRemark, r.nickname, r.type, r.verifyFlag, r.encryptUsername, i.reserved2 AS avatarUrl
            FROM rcontact r LEFT JOIN img_flag i ON r.username = i.username
            WHERE r.username != '' AND r.username != 'notchatroom'
              AND r.username NOT LIKE '%@fakeuser'
              AND r.username NOT IN ('officialaccounts','notifymessage','notification_messages',
                  'gamecenter','wxgame','brandservice','brandecstemplatemsg','weixinreminder',
                  'opencustomerservicemsg','appbrand_notify_message','appbrandcustomerservicemsg')
        """.trimIndent()
        val raw = invokeRawQuery(db, sql, emptyArray()) ?: return out
        val cursor = raw as? android.database.Cursor ?: return out
        try {
            val iUser = findColumnIndexIgnoreCase(cursor, "username")
            val iAlias = findColumnIndexIgnoreCase(cursor, "alias")
            val iRemark = findColumnIndexIgnoreCase(cursor, "conRemark")
            val iNick = findColumnIndexIgnoreCase(cursor, "nickname")
            val iType = findColumnIndexIgnoreCase(cursor, "type")
            val iVerify = findColumnIndexIgnoreCase(cursor, "verifyFlag")
            val iEncrypt = findColumnIndexIgnoreCase(cursor, "encryptUsername")
            val iAvatarUrl = findColumnIndexIgnoreCase(cursor, "avatarUrl")
            if (iUser < 0) return out
            while (cursor.moveToNext()) {
                val u = cursorStringByIndex(cursor, iUser)?.trim().orEmpty()
                if (u.isEmpty() || !seen.add(u)) continue
                val alias = cleanHumanName(cursorStringByIndex(cursor, iAlias)).orEmpty()
                val conRemark = cleanHumanName(cursorStringByIndex(cursor, iRemark)).orEmpty()
                val nickname = cleanHumanName(cursorStringByIndex(cursor, iNick)).orEmpty()

                var display = when {
                    conRemark.isNotBlank() -> conRemark
                    nickname.isNotBlank() -> nickname
                    else -> u
                }

                if (u.endsWith("@chatroom") || u.endsWith("@im.chatroom")) {
                    val roomName = groupNames[u]
                    if (!roomName.isNullOrBlank()) {
                        display = roomName
                    } else if (display == u) {
                        display = "群聊 (${u.take(8)})"
                    }
                }

                val type = if (iType >= 0) cursorIntByIndex(cursor, iType) ?: 0 else 0
                val verifyFlag = if (iVerify >= 0) cursorIntByIndex(cursor, iVerify) ?: 0 else 0
                val encryptUsername = if (iEncrypt >= 0) cursorStringByIndex(cursor, iEncrypt).orEmpty() else ""
                val avatarUrl = if (iAvatarUrl >= 0) cursorStringByIndex(cursor, iAvatarUrl).orEmpty() else ""

                out.add(ContactItem(u, display, alias, type, verifyFlag, encryptUsername, 0L, avatarUrl))
            }
        } finally {
            runCatching { cursor.close() }
        }

        // 3. Append missing chatrooms not in rcontact
        for ((room, disp) in groupNames) {
            if (seen.add(room)) {
                out.add(ContactItem(room, disp, "", 0, 0, "", 0L, ""))
            }
        }

        return out
    }

    fun queryCleanContactsForGrouping(): List<Triple<String, String, String>> {
        return queryAllContactsDetailed().map { Triple(it.username, it.displayName, "") }
    }

    private fun queryCleanContactsOn(db: Any): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>(1024)
        val seen = HashSet<String>()

        // 1. 好友 (参考 WeKit getContacts：宽松过滤，抓取全部个人联系)
        val sqlFriends = """
            SELECT username, alias, conRemark, nickname FROM rcontact
            WHERE username NOT LIKE '%chatroom' AND username NOT LIKE 'gh_%'
              AND username != '' AND username != 'notchatroom'
        """.trimIndent()
        runCatching {
            val raw = invokeRawQuery(db, sqlFriends, emptyArray())
            val cursor = raw as? android.database.Cursor
            if (cursor != null) {
                val iUser = findColumnIndexIgnoreCase(cursor, "username")
                val iRemark = findColumnIndexIgnoreCase(cursor, "conRemark")
                val iNick = findColumnIndexIgnoreCase(cursor, "nickname")
                val iAlias = findColumnIndexIgnoreCase(cursor, "alias")
                if (iUser >= 0) {
                    while (cursor.moveToNext()) {
                        val u = cursorStringByIndex(cursor, iUser)?.trim().orEmpty()
                        if (u.isEmpty() || !seen.add(u)) continue
                        val remark = cleanHumanName(cursorStringByIndex(cursor, iRemark)).orEmpty()
                        val nick = cleanHumanName(cursorStringByIndex(cursor, iNick)).orEmpty()
                        val alias = cleanHumanName(cursorStringByIndex(cursor, iAlias)).orEmpty()

                        val display = if (remark.isNotBlank()) {
                            if (nick.isNotBlank()) "$remark ($nick)" else remark
                        } else if (nick.isNotBlank()) nick else if (alias.isNotBlank()) alias else u

                        out.add(Triple(u, display, if (remark.isNotBlank() && nick.isNotBlank()) "昵称: $nick" else ""))
                    }
                }
                runCatching { cursor.close() }
            }
        }

        // 2. 群聊 (参考 WeKit / wcx-src 架构 SQL)
        val sqlGroups = """
            SELECT username, nickname FROM rcontact
            WHERE username LIKE '%@chatroom' OR username LIKE '%@im.chatroom'
        """.trimIndent()
        runCatching {
            val raw = invokeRawQuery(db, sqlGroups, emptyArray())
            val cursor = raw as? android.database.Cursor
            if (cursor != null) {
                val iUser = findColumnIndexIgnoreCase(cursor, "username")
                val iNick = findColumnIndexIgnoreCase(cursor, "nickname")
                if (iUser >= 0) {
                    while (cursor.moveToNext()) {
                        val u = cursorStringByIndex(cursor, iUser)?.trim().orEmpty()
                        if (u.isEmpty() || !seen.add(u)) continue
                        var nick = cleanHumanName(cursorStringByIndex(cursor, iNick)).orEmpty()
                        if (nick.isBlank()) {
                            nick = queryChatroomDisplayName(db, u).orEmpty()
                        }
                        if (nick.isBlank()) {
                            nick = "群聊 (${u.take(8)})"
                        }
                        out.add(Triple(u, nick, ""))
                    }
                }
                runCatching { cursor.close() }
            }
        }

        // 3. 公众号 (参考 WeKit / wcx-src 架构 SQL)
        val sqlOfficial = """
            SELECT username, alias, nickname FROM rcontact
            WHERE username LIKE 'gh_%'
        """.trimIndent()
        runCatching {
            val raw = invokeRawQuery(db, sqlOfficial, emptyArray())
            val cursor = raw as? android.database.Cursor
            if (cursor != null) {
                val iUser = findColumnIndexIgnoreCase(cursor, "username")
                val iNick = findColumnIndexIgnoreCase(cursor, "nickname")
                val iAlias = findColumnIndexIgnoreCase(cursor, "alias")
                if (iUser >= 0) {
                    while (cursor.moveToNext()) {
                        val u = cursorStringByIndex(cursor, iUser)?.trim().orEmpty()
                        if (u.isEmpty() || !seen.add(u)) continue
                        val nick = cleanHumanName(cursorStringByIndex(cursor, iNick)).orEmpty()
                        val alias = cleanHumanName(cursorStringByIndex(cursor, iAlias)).orEmpty()
                        val display = nick.ifBlank { alias }.ifBlank { u }
                        out.add(Triple(u, display, ""))
                    }
                }
                runCatching { cursor.close() }
            }
        }

        return out
    }

    private fun queryChatroomDisplayName(db: Any, roomUsername: String): String? {
        val raw = invokeRawQuery(db, "SELECT displayname FROM chatroom WHERE chatroomname=? LIMIT 1", arrayOf(roomUsername)) ?: return null
        return (raw as? android.database.Cursor)?.use { cursor ->
            if (cursorMoveToFirst(cursor)) {
                val dn = cursorString(cursor, "displayname") ?: cursorStringByIndex(cursor, 0)
                cleanHumanName(dn)
            } else null
        }
    }

    fun invalidate(username: String? = null) {
        if (username.isNullOrBlank()) cache.clear() else cache.remove(username)
    }

    fun rememberFromContactValues(values: android.content.ContentValues?) {
        if (values == null) return
        val user = values.getAsString("username")?.trim().orEmpty()
        if (user.isEmpty() || AntiRevokeLogic.isChatRoom(user)) return
        val name = pickName(
            values.getAsString("conRemark"),
            values.getAsString("nickname"),
            values.getAsString("alias")
        ) ?: return
        cache[user] = name
    }

    /** 手动写入缓存（备注或昵称） */
    fun remember(username: String?, displayName: String?) {
        val id = username?.trim().orEmpty()
        val name = cleanHumanName(displayName) ?: return
        if (id.isEmpty() || AntiRevokeLogic.isChatRoom(id)) return
        cache[id] = name
    }

    /**
     * 用户可见名：备注 > 昵称（微信句 / 通讯录），不要 wxid。
     */
    fun displayOrId(username: String?, revokeHint: String? = null): String {
        val id = username?.trim().orEmpty()

        // ① 微信系统句（最准：已按备注/昵称渲染）
        extractNameFromRevokeText(revokeHint)?.let { fromHint ->
            if (id.isNotEmpty()) cache[id] = fromHint
            return fromHint
        }

        // ② 内存缓存
        if (id.isNotEmpty()) {
            cache[id]?.let { return it }
        }

        // ③ rcontact：备注 > 昵称
        if (id.isNotEmpty() && !AntiRevokeLogic.isChatRoom(id)) {
            queryRcontactAllDbs(id)?.let {
                cache[id] = it
                trimCache()
                return it
            }
        }

        return "对方"
    }

    fun resolve(username: String?, revokeHint: String? = null): String? {
        val name = displayOrId(username, revokeHint)
        return name.takeIf { it != "对方" }
    }

    /** 1=群主，2=管理员，3=普通成员。群成员角色字段。 */
    fun chatroomRole(room: String, sender: String): Int? {
        if (!AntiRevokeLogic.isChatRoom(room) || sender.isBlank()) return null
        val dbs = buildList {
            primaryDb.get()?.let { add(it) }
            addAll(dbList)
        }.distinct()
        for (db in dbs) {
            queryChatroomRole(db, room, sender)?.let { return it }
        }
        return null
    }

    /**
     * 从系统撤回文案提取展示名（备注或昵称）。
     */
    fun extractNameFromRevokeText(text: String?): String? {
        // 与 AntiRevokeLogic 共用一套解析，避免两套正则不一致
        return AntiRevokeLogic.extractNameFromRevokeText(text)
    }

    private fun extractXmlInner(xml: String, tag: String): String? {
        Regex(
            """<$tag>\s*<!\[CDATA\[(.*?)]]>\s*</$tag>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        Regex(
            """<$tag>(.*?)</$tag>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    fun cleanHumanName(raw: String?): String? = AntiRevokeLogic.cleanHumanName(raw)

    private fun pickName(remark: String?, nick: String?, alias: String?): String? {
        return cleanHumanName(remark)
            ?: cleanHumanName(nick)
            ?: cleanHumanName(alias)
    }

    private fun queryRcontactAllDbs(username: String): String? {
        val dbs = buildList {
            primaryDb.get()?.let { add(it) }
            addAll(dbList)
        }.distinct()
        for (db in dbs) {
            queryRcontact(db, username)?.let { return it }
        }
        return null
    }

    private fun queryChatroomRole(db: Any, room: String, sender: String): Int? {
        val cursor = invokeRawQuery(
            db,
            "SELECT roomowner, roomdata FROM chatroom WHERE chatroomname=? LIMIT 1",
            arrayOf(room)
        ) ?: return null
        return try {
            if (!cursorMoveToFirst(cursor)) return null
            val owner = cursorString(cursor, "roomowner") ?: cursorStringByIndex(cursor, 0)
            if (owner?.trim() == sender) return 1
            val roomData = cursorBlob(cursor, "roomdata") ?: cursorBlobByIndex(cursor, 1)
            val flags = roomData?.let { findChatroomMemberFlags(it, sender) }
            if (flags != null && flags and 2048 != 0) 2 else 3
        } finally {
            cursorClose(cursor)
        }
    }

    private fun queryRcontact(db: Any, username: String): String? {
        val safe = username.replace("'", "''")
        val attempts = listOf(
            "SELECT conRemark, nickname, alias FROM rcontact WHERE username=? LIMIT 1" to arrayOf(username),
            "SELECT conRemark, nickname, alias FROM rcontact WHERE username=? OR encryptUsername=? LIMIT 1" to
                arrayOf(username, username),
            "SELECT conRemark, nickname, alias FROM rcontact WHERE username='$safe' LIMIT 1" to emptyArray()
        )
        for ((sql, args) in attempts) {
            runCatching { queryOne(db, sql, args) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun queryOne(db: Any, sql: String, args: Array<String>): String? {
        val cursor = invokeRawQuery(db, sql, args) ?: return null
        return try {
            if (!cursorMoveToFirst(cursor)) return null
            // 先按列名，再按 0/1/2 顺序（WCDB 有时 getColumnIndex 异常）
            val remark = cursorString(cursor, "conRemark") ?: cursorStringByIndex(cursor, 0)
            val nick = cursorString(cursor, "nickname") ?: cursorStringByIndex(cursor, 1)
            val alias = cursorString(cursor, "alias") ?: cursorStringByIndex(cursor, 2)
            pickName(remark, nick, alias)
        } finally {
            cursorClose(cursor)
        }
    }

    private fun hasRawQuery(db: Any): Boolean {
        return db.javaClass.methods.any { it.name == "rawQuery" && it.parameterTypes.isNotEmpty() }
    }

    private fun invokeRawQuery(db: Any, sql: String, args: Array<String>): Any? {
        val methods = db.javaClass.methods
            .filter { it.name == "rawQuery" }
            .sortedBy { it.parameterTypes.size }
        for (m in methods) {
            tryInvokeRawQuery(m, db, sql, args)?.let { return it }
        }
        return null
    }

    private fun invokeExecSql(db: Any, sql: String, args: Array<Any?>): Boolean {
        val methods = db.javaClass.methods
            .filter { it.name == "execSQL" || it.name == "execute" }
            .sortedBy { it.parameterTypes.size }
        for (m in methods) {
            val ok = runCatching {
                m.isAccessible = true
                val pts = m.parameterTypes
                when {
                    pts.size == 1 && pts[0] == String::class.java -> {
                        m.invoke(db, sql)
                        true
                    }
                    pts.size >= 2 && pts[0] == String::class.java -> {
                        val second = when {
                            pts[1].name == "[Ljava.lang.Object;" -> args
                            pts[1].name == "[Ljava.lang.String;" -> args.map { it?.toString().orEmpty() }.toTypedArray()
                            else -> args
                        }
                        if (pts.size == 2) m.invoke(db, sql, second)
                        else m.invoke(db, sql, second, *Array(pts.size - 2) { null as Any? })
                        true
                    }
                    else -> false
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun tryInvokeRawQuery(m: Method, db: Any, sql: String, args: Array<String>): Any? {
        return runCatching {
            m.isAccessible = true
            val pts = m.parameterTypes
            when {
                pts.size == 1 && pts[0] == String::class.java ->
                    m.invoke(db, sql)
                pts.size >= 2 && pts[0] == String::class.java -> {
                    val secondArg: Any? = when {
                        pts[1] == Array<String>::class.java ||
                            pts[1].name == "[Ljava.lang.String;" -> args
                        else -> args
                    }
                    if (pts.size == 2) {
                        m.invoke(db, sql, secondArg)
                    } else {
                        val extra = Array(pts.size - 2) { null as Any? }
                        m.invoke(db, sql, secondArg, *extra)
                    }
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun cursorMoveToFirst(cursor: Any): Boolean {
        return runCatching {
            val m = cursor.javaClass.methods.firstOrNull {
                it.name == "moveToFirst" && it.parameterTypes.isEmpty()
            } ?: return false
            m.isAccessible = true
            m.invoke(cursor) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun cursorString(cursor: Any, column: String): String? {
        return runCatching {
            val idxMethod = cursor.javaClass.methods.firstOrNull {
                it.name == "getColumnIndex" && it.parameterTypes.size == 1
            } ?: return null
            idxMethod.isAccessible = true
            var idx = (idxMethod.invoke(cursor, column) as? Number)?.toInt() ?: -1
            if (idx < 0) {
                // 再试忽略大小写：遍历列名
                idx = findColumnIndexIgnoreCase(cursor, column)
            }
            if (idx < 0) return null
            cursorStringByIndex(cursor, idx)
        }.getOrNull()
    }

    private fun findColumnIndexIgnoreCase(cursor: Any, column: String): Int {
        return runCatching {
            val countM = cursor.javaClass.methods.firstOrNull {
                it.name == "getColumnCount" && it.parameterTypes.isEmpty()
            } ?: return -1
            countM.isAccessible = true
            val count = (countM.invoke(cursor) as? Number)?.toInt() ?: return -1
            val nameM = cursor.javaClass.methods.firstOrNull {
                it.name == "getColumnName" && it.parameterTypes.size == 1
            } ?: return -1
            nameM.isAccessible = true
            for (i in 0 until count) {
                val n = nameM.invoke(cursor, i) as? String ?: continue
                if (n.equals(column, ignoreCase = true)) return i
            }
            -1
        }.getOrDefault(-1)
    }

    private fun cursorGetType(cursor: Any, index: Int): Int {
        if (index < 0) return 0
        return runCatching {
            val getTypeM = cursor.javaClass.methods.firstOrNull {
                it.name == "getType" && it.parameterTypes.size == 1
            } ?: return 0
            getTypeM.isAccessible = true
            (getTypeM.invoke(cursor, index) as? Number)?.toInt() ?: 0
        }.getOrDefault(0)
    }

    
    private fun cursorLongByIndex(cursor: Any, index: Int): Long {
        if (index < 0) return 0L
        return runCatching {
            if (cursor is android.database.Cursor) {
                cursor.getLong(index)
            } else {
                val m = cursor.javaClass.methods.firstOrNull { it.name == "getLong" && it.parameterTypes.size == 1 }
                (m?.invoke(cursor, index) as? Number)?.toLong() ?: 0L
            }
        }.getOrDefault(0L)
    }

private fun cursorIntByIndex(cursor: Any, index: Int): Int {
        if (index < 0) return 0
        return runCatching {
            if (cursor is android.database.Cursor) {
                cursor.getInt(index)
            } else {
                val m = cursor.javaClass.methods.firstOrNull { it.name == "getInt" && it.parameterTypes.size == 1 }
                (m?.invoke(cursor, index) as? Number)?.toInt() ?: 0
            }
        }.getOrDefault(0)
    }

    private fun cursorStringByIndex(cursor: Any, index: Int): String? {
        if (index < 0) return null
        val type = cursorGetType(cursor, index)
        // FIELD_TYPE_NULL=0, FIELD_TYPE_BLOB=4: 拒绝直接读取二进制 Blob，防止读出乱码 garbage
        if (type == 0 || type == 4) return null
        return runCatching {
            val getString = cursor.javaClass.methods.firstOrNull {
                it.name == "getString" && it.parameterTypes.size == 1
            } ?: return null
            getString.isAccessible = true
            getString.invoke(cursor, index) as? String
        }.getOrNull()
    }

    private fun cursorBlob(cursor: Any, column: String): ByteArray? {
        val index = runCatching {
            val method = cursor.javaClass.methods.firstOrNull {
                it.name == "getColumnIndex" && it.parameterTypes.size == 1
            } ?: return null
            (method.invoke(cursor, column) as? Number)?.toInt() ?: -1
        }.getOrDefault(-1)
        return cursorBlobByIndex(cursor, index)
    }

    private fun cursorBlobByIndex(cursor: Any, index: Int): ByteArray? {
        if (index < 0) return null
        val bytes = runCatching {
            val method = cursor.javaClass.methods.firstOrNull {
                it.name == "getBlob" && it.parameterTypes.size == 1
            } ?: return@runCatching null
            method.isAccessible = true
            method.invoke(cursor, index) as? ByteArray
        }.getOrNull()
        if (bytes != null) return bytes
        val encoded = cursorStringByIndex(cursor, index)?.trim().orEmpty().removePrefix("hex->")
        val hex = encoded.replace(" ", "").replace("\n", "").replace("\r", "")
        if (hex.length < 2 || hex.length % 2 != 0 || !hex.matches(Regex("[0-9a-fA-F]+"))) return null
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun findChatroomMemberFlags(roomData: ByteArray, sender: String): Int? {
        var index = 0
        while (index < roomData.size) {
            val tag = readVarint(roomData, index) ?: return null
            index = tag.second
            val field = (tag.first ushr 3).toInt()
            val wire = (tag.first and 7).toInt()
            if (field == 1 && wire == 2) {
                val length = readVarint(roomData, index) ?: return null
                index = length.second
                val end = (index + length.first.toInt()).coerceAtMost(roomData.size)
                if (end < index) return null
                parseChatroomMember(roomData, index, end)?.let { (username, flags) ->
                    if (username == sender) return flags
                }
                index = end
            } else {
                index = skipProtoField(roomData, index, wire) ?: return null
            }
        }
        return null
    }

    private fun parseChatroomMember(data: ByteArray, start: Int, end: Int): Pair<String, Int>? {
        var index = start
        var username: String? = null
        var flags = 0
        while (index < end) {
            val tag = readVarint(data, index) ?: return null
            index = tag.second
            val field = (tag.first ushr 3).toInt()
            val wire = (tag.first and 7).toInt()
            when {
                field == 1 && wire == 2 -> {
                    val length = readVarint(data, index) ?: return null
                    index = length.second
                    val next = (index + length.first.toInt()).coerceAtMost(end)
                    username = data.copyOfRange(index, next).toString(Charsets.UTF_8).trim()
                    index = next
                }
                field == 3 && wire == 0 -> {
                    val value = readVarint(data, index) ?: return null
                    flags = value.first.toInt()
                    index = value.second
                }
                else -> index = skipProtoField(data, index, wire, end) ?: return null
            }
        }
        return username?.let { it to flags }
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var index = start
        while (index < data.size && shift < 64) {
            val byte = data[index++].toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }

    private fun skipProtoField(data: ByteArray, start: Int, wire: Int, limit: Int = data.size): Int? {
        return when (wire) {
            0 -> readVarint(data, start)?.second
            1 -> (start + 8).takeIf { it <= limit }
            2 -> {
                val length = readVarint(data, start) ?: return null
                (length.second + length.first.toInt()).takeIf { it <= limit }
            }
            5 -> (start + 4).takeIf { it <= limit }
            else -> null
        }
    }

    private fun cursorClose(cursor: Any) {
        runCatching {
            val m = cursor.javaClass.methods.firstOrNull {
                it.name == "close" && it.parameterTypes.isEmpty()
            } ?: return
            m.isAccessible = true
            m.invoke(cursor)
        }
    }

    private fun trimCache() {
        if (cache.size > 800) {
            cache.keys.take(200).forEach { cache.remove(it) }
        }
    }
}
