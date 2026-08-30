package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListAdapter
import android.widget.ListView
import com.OKK.yes.core.compat.DexKitSupport
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 密友 Hook（对标 InkHide / MaskWechat 跨版本架构，OKK 原生实现）。
 *
 * 核心设计原理（适配 8.0.69 ~ 8.0.76 全系微信）：
 *  1. 朋友圈全端隐藏（SnsSqliteDB SQL 源头截断 + 组件流过滤 + View 重映射三重保险）：
 *     - SQL 层：拦截对 `SnsInfo` / `SnsComment` 表的查询（覆盖 `SnsSqliteDB` 与 SQLite rawQuery），
 *       追加 `AND (SnsInfo.userName NOT IN ('wxid_xx', ...))`，从 WCDB 朋友圈数据库源头剔除密友动态与互动；
 *     - 数据流层：针对 `ImproveMainUIC` / `SnsTimeLineUI` 等组件的回调执行数据项安全剥离；
 *     - 视图层：支持传统 `SnsTimeLineBaseAdapter` 与改进版 `ImproveTimelineAdapter` 的位置映射；
 *  2. 通讯录全端隐藏（SQL 数据源截断 + Adapter 视图重映射双保险）：
 *     - SQL 层：拦截对 `rcontact` 表的查询，对非单人查询追加 `AND (rcontact.username NOT IN ('wxid_xx', ...))`；
 *     - 视图层：支持传统 ListView / 现代 MvvmAddressUIFragment 的 RecyclerView 映射；
 *  3. 主页会话隐藏：复用 SQLite 注入点，对含 rconversation 的查询追加 `AND username NOT IN (...)`；
 *  4. 顶部搜索隐藏：对 FTS5 搜索查询（联系人/聊天记录）追加 `AND aux_index NOT IN (...)`；
 *
 * 名单/开关变更通过 PublicConfigStore 监听即时生效（热刷新会话 + 列表 Adapter）。
 */
object CloseFriendHook {

    private const val TAG = "OKK-CloseFriend"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var installed = false

    /** 适配器类型：通讯录 or 朋友圈 */
    enum class AdapterKind { ADDRESS, SNS }

    /** 已 hook 的 SQL 方法去重键 */
    private val hookedSqlMethods = Collections.synchronizedSet(mutableSetOf<String>())

    /** 已 hook 的 Adapter 类名去重集合 */
    private val hookedListClasses = Collections.synchronizedSet(mutableSetOf<String>())
    private val hookedRecyclerClasses = Collections.synchronizedSet(mutableSetOf<String>())

    /** 活跃的 ListAdapter -> AdapterKind 映射 */
    private val listAdapters = Collections.synchronizedMap(WeakHashMap<ListAdapter, AdapterKind>())

    /** 活跃的 RecyclerView.Adapter -> AdapterKind 映射 */
    private val recyclerAdapters = Collections.synchronizedMap(WeakHashMap<Any, AdapterKind>())

    /** Adapter -> 位置映射缓存（realCount + virtual->real 表） */
    private class Mapping(
        val realCount: Int,
        val virtualToReal: IntArray,
        val hiddenCount: Int,
        val idsSnapshot: Set<String>
    )

    private val listMappingCache = WeakHashMap<ListAdapter, Mapping>()
    private val recyclerMappingCache = WeakHashMap<Any, Mapping>()

    /** 构建映射期间防止递归 */
    private val buildingMapping = ThreadLocal<Boolean>()

    fun install(ctx: Context, cl: ClassLoader, mp: String?) {
        if (installed) return
        installed = true
        xlog("install start")
        DexKitSupport.classLoader = cl
        DexKitSupport.appContext = ctx.applicationContext ?: ctx
        DexKitSupport.modulePath = mp

        hookSqliteMethods(ctx, cl, mp)
        hookSnsSqliteDb(ctx, cl, mp)
        hookListView(cl)
        hookRecyclerView(cl)
        hookSnsTimelineComponents(cl)
        hookTitleLongPress(cl)
        registerConfigListeners()
        xlog("install done")
    }

    // ── 1. SQL 层拦截（主页会话 + 朋友圈 SnsInfo + 顶部搜索 + 通讯录 rcontact） ──

    private fun hookSqliteMethods(ctx: Context, cl: ClassLoader, mp: String?) {
        // 1) DexKit 定位微信主库 SQLite Wrapper 的 rawQuery：a(String sql, String[] args, int flags) -> Cursor
        val wrapperCandidates = runCatching {
            DexKitSupport.findMethodsByStrings(ctx, cl, mp, "sql is null ", "DB IS CLOSED ! {%s}")
        }.getOrDefault(emptyList())

        val wrapperMethod = wrapperCandidates.firstOrNull { m ->
            m.parameterTypes.size == 3 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == Array<String>::class.java &&
                m.parameterTypes[2] == Int::class.javaPrimitiveType
        }

        if (wrapperMethod != null) {
            val key = "${wrapperMethod.declaringClass.name}#${wrapperMethod.name}"
            if (hookedSqlMethods.add(key)) {
                runCatching {
                    XposedBridge.hookMethod(
                        wrapperMethod,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                rewriteSql(param, 0)
                            }
                        }
                    )
                    xlog("hooked SQLite wrapper: $key")
                }.onFailure { xlog("hook SQLite wrapper failed: ${it.message}") }
            }
        }

        // 2) 兜底：Hook WCDB / SQLite 常见 rawQuery 方法
        val dbClasses = listOf(
            "com.tencent.wcdb.compat.SQLiteDatabase",
            "com.tencent.wcdb.database.SQLiteDatabase",
            "android.database.sqlite.SQLiteDatabase"
        )
        for (className in dbClasses) {
            val dbClass = runCatching { XposedHelpers.findClass(className, cl) }.getOrNull() ?: continue
            for (method in dbClass.declaredMethods) {
                if (method.name.startsWith("rawQuery") || method.name.startsWith("query") || method.name == "execSQL") {
                    val strIdx = method.parameterTypes.indexOfFirst { it == String::class.java }
                    if (strIdx < 0) continue
                    val key = "$className#${method.name}_${method.parameterTypes.size}"
                    if (!hookedSqlMethods.add(key)) continue
                    runCatching {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                    rewriteSql(param, strIdx)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * 针对朋友圈专属数据库 `SnsSqliteDB` 进行挂钩拦截。
     * 微信朋友圈数据（SnsInfo / SnsComment）存放在独立的 SnsMicroMsg.db，由 SnsSqliteDB 管理。
     */
    private fun hookSnsSqliteDb(ctx: Context, cl: ClassLoader, mp: String?) {
        // 1) DexKit 动态定位 SnsSqliteDB rawQuery 方法
        runCatching {
            val methods = DexKitSupport.findMethodsByStrings(
                ctx, cl, mp,
                "com.tencent.mm.plugin.sns.storage.SnsSqliteDB", "rawQuery"
            )
            for (method in methods) {
                val strIdx = method.parameterTypes.indexOfFirst { it == String::class.java }
                if (strIdx >= 0 && (method.returnType.name.contains("Cursor") || method.returnType == Any::class.java)) {
                    val key = "${method.declaringClass.name}#${method.name}_${method.parameterTypes.size}"
                    if (hookedSqlMethods.add(key)) {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    rewriteSql(param, strIdx)
                                }
                            }
                        )
                        xlog("hooked SnsSqliteDB rawQuery via DexKit: $key")
                    }
                }
            }
        }.onFailure { xlog("hook SnsSqliteDB via DexKit failed: ${it.message}") }

        // 2) 静态兜底：扫描 com.tencent.mm.plugin.sns.storage 下的类
        val fallbackClasses = listOf(
            "com.tencent.mm.plugin.sns.storage.n2",
            "com.tencent.mm.plugin.sns.storage.m2",
            "com.tencent.mm.plugin.sns.storage.o2",
            "com.tencent.mm.plugin.sns.storage.p2",
            "com.tencent.mm.plugin.sns.storage.q2",
            "com.tencent.mm.plugin.sns.storage.SnsSqliteDB"
        )
        for (clsName in fallbackClasses) {
            val clazz = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull() ?: continue
            for (method in clazz.declaredMethods) {
                val params = method.parameterTypes
                if (params.isNotEmpty() && params[0] == String::class.java && method.returnType.name.contains("Cursor")) {
                    val key = "$clsName#${method.name}_${params.size}"
                    if (hookedSqlMethods.add(key)) {
                        runCatching {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        rewriteSql(param, 0)
                                    }
                                }
                            )
                            xlog("hooked SnsSqliteDB rawQuery via fallback: $key")
                        }
                    }
                }
            }
        }
    }

    /** SQL 统一拦截分发：主页会话 / 朋友圈 / 全局搜索 / 通讯录 */
    private fun rewriteSql(param: XC_MethodHook.MethodHookParam, strIdx: Int) {
        val sql = param.args.getOrNull(strIdx) as? String ?: return
        val lower = sql.lowercase()

        when {
            lower.contains("rconversation") -> {
                rewriteConversation(sql, param, strIdx, lower)
            }
            lower.contains("snsinfo") -> {
                rewriteSnsSql(sql, param, strIdx, lower)
            }
            lower.contains("snscomment") -> {
                rewriteSnsCommentSql(sql, param, strIdx, lower)
            }
            lower.contains(" from rcontact") || lower.contains(" from rcontact ") || lower.contains(",rcontact") || lower.contains(" rcontact ") -> {
                rewriteContactSql(sql, param, strIdx, lower)
            }
            isFtsSearchSql(lower) -> {
                rewriteFtsSearch(sql, param, strIdx, lower)
            }
        }
    }

    /** 朋友圈 SnsInfo SQL 源头过滤（彻底剔除密友动态） */
    private fun rewriteSnsSql(sql: String, param: XC_MethodHook.MethodHookParam, strIdx: Int, lower: String) {
        if (!CloseFriendStore.hideSns()) return
        val ids = CloseFriendStore.ids()
        if (ids.isEmpty()) return
        if (sql.contains("userName NOT IN") || sql.contains("username NOT IN")) return

        val idListStr = ids.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        val colName = if (lower.contains("snsinfo.")) "SnsInfo.userName" else "userName"
        val cond = "$colName NOT IN ($idListStr)"

        val rewritten = injectCondition(sql, "($cond)") ?: return
        param.args[strIdx] = rewritten
        xlogThrottled("sns SnsInfo SQL rewritten")
    }

    /** 朋友圈 SnsComment SQL 过滤（过滤密友在朋友圈的点赞与评论） */
    private fun rewriteSnsCommentSql(sql: String, param: XC_MethodHook.MethodHookParam, strIdx: Int, lower: String) {
        if (!CloseFriendStore.hideSns()) return
        val ids = CloseFriendStore.ids()
        if (ids.isEmpty()) return
        if (sql.contains("userName NOT IN") || sql.contains("talker NOT IN")) return

        val idListStr = ids.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        val cond = "userName NOT IN ($idListStr) AND talker NOT IN ($idListStr)"

        val rewritten = injectCondition(sql, "($cond)") ?: return
        param.args[strIdx] = rewritten
        xlogThrottled("sns SnsComment SQL rewritten")
    }

    /** 主页会话列表 SQL 过滤 */
    private fun rewriteConversation(sql: String, param: XC_MethodHook.MethodHookParam, strIdx: Int, lower: String) {
        if (!CloseFriendStore.hideConversation()) return
        val predicate = CloseFriendStore.buildConversationExcludePredicate() ?: return
        if (lower.contains("wekit_folder_") || lower.contains("conversationboxservice")) return
        val trimmed = lower.trimStart()
        if (trimmed.startsWith("select count(") || trimmed.startsWith("select distinct count(")) return
        if (sql.contains(predicate)) return
        val rewritten = injectCondition(sql, "($predicate)") ?: return
        param.args[strIdx] = rewritten
        xlogThrottled("conversation SQL rewritten")
    }

    /** 通讯录 rcontact SQL 源头拦截 */
    private fun rewriteContactSql(sql: String, param: XC_MethodHook.MethodHookParam, strIdx: Int, lower: String) {
        if (!CloseFriendStore.hideContact()) return
        val ids = CloseFriendStore.ids()
        if (ids.isEmpty()) return

        // 排除单人查询（按用户名/别名等单个查找联系人信息时不改写）
        if (lower.contains(" where username=") || lower.contains(" where username =") ||
            lower.contains(" where encryptusername=") || lower.contains(" where encryptusername =")
        ) {
            return
        }

        // 检查调用栈：如果是模块自身的管理页面或分类查询（如 ContactDisplayNames、CloseFriendManagerUi），不要过滤，确保管理成员列表可见密友并方便移除
        val stack = Thread.currentThread().stackTrace
        val isModuleInternalQuery = stack.any { elem ->
            val cls = elem.className
            cls.contains("com.OKK.yes.core.hooks.ContactDisplayNames") ||
            cls.contains("com.OKK.yes.core.hooks.CloseFriendManagerUi") ||
            cls.contains("com.OKK.yes.core.hooks.CloseFriendStore") ||
            cls.contains("com.OKK.yes.core.hooks.ConversationGroupManager")
        }
        if (isModuleInternalQuery) {
            return
        }

        if (sql.contains("rcontact.username NOT IN") || sql.contains("username NOT IN")) return

        val idListStr = ids.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        val usernameCol = if (lower.contains("rcontact.")) "rcontact.username" else "username"
        val cond = "$usernameCol NOT IN ($idListStr)"

        val rewritten = injectCondition(sql, "($cond)") ?: return
        param.args[strIdx] = rewritten
        xlogThrottled("contact rcontact SQL rewritten")
    }

    /** 是否为 FTS 搜索查询 */
    private fun isFtsSearchSql(lower: String): Boolean =
        (lower.contains("fts5meta") || lower.contains("fts5index")) && lower.contains(" match ")

    /** 顶部搜索 SQL 过滤 */
    private fun rewriteFtsSearch(sql: String, param: XC_MethodHook.MethodHookParam, strIdx: Int, lower: String) {
        if (!CloseFriendStore.hideSearch()) return
        val ids = CloseFriendStore.ids()
        if (ids.isEmpty()) return
        if (sql.contains("aux_index NOT IN")) return
        val cond = "aux_index NOT IN (" + ids.joinToString(",") { "'" + it.replace("'", "''") + "'" } + ")"
        val rewritten = injectCondition(sql, "($cond)") ?: return
        param.args[strIdx] = rewritten
        xlogThrottled("search SQL rewritten")
    }

    /** 在 ORDER BY / GROUP BY / LIMIT 之前把条件 AND 进 WHERE */
    private fun injectCondition(sql: String, condition: String): String? {
        val lower = sql.lowercase()
        val insertion = listOf(" order by ", " group by ", " limit ")
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        return if (insertion == null) {
            if (lower.contains(" where ")) "$sql AND $condition"
            else "$sql WHERE $condition"
        } else {
            val head = sql.substring(0, insertion)
            val tail = sql.substring(insertion)
            if (head.lowercase().contains("where")) "$head AND $condition$tail"
            else "$head WHERE $condition$tail"
        }
    }

    // ── 2. ListView 拦截（兼容传统通讯录与老版朋友圈） ──

    private fun hookListView(cl: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                ListView::class.java,
                "setAdapter",
                ListAdapter::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val listView = param.thisObject as? ListView ?: return
                        val adapter = param.args.firstOrNull() as? ListAdapter ?: return
                        val kind = detectListKind(listView, adapter) ?: return
                        listAdapters[adapter] = kind
                        hookListAdapterClass(adapter.javaClass)
                        xlog("ListView adapter captured: ${adapter.javaClass.name} kind=$kind")
                    }
                }
            )
        }.onFailure { xlog("hook ListView.setAdapter failed: ${it.message}") }
    }

    private fun detectListKind(view: View, adapter: ListAdapter): AdapterKind? {
        val act = activityName(view) ?: ""
        val cls = adapter.javaClass.name
        if (act.contains("AddressUI") || act.contains("contact.Address") || cls.contains("AddressAdapter") || cls.contains("ContactAdapter")) {
            return AdapterKind.ADDRESS
        }
        if (act.contains("SnsTimeLineUI") || act.contains("ImproveSnsTimelineUI") || cls.contains("SnsTimeLine", ignoreCase = true)) {
            return AdapterKind.SNS
        }
        return null
    }

    private fun hookListAdapterClass(cls: Class<*>) {
        if (!hookedListClasses.add(cls.name)) return

        runCatching {
            XposedBridge.hookMethod(
                cls.getMethod("getCount"),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val self = param.thisObject as? ListAdapter ?: return
                        val kind = listAdapters[self] ?: return
                        if (!isKindEnabled(kind)) return
                        val realCount = (param.result as? Int) ?: return
                        val mapping = ensureListMapping(self, realCount, kind) ?: return
                        if (mapping.hiddenCount > 0) {
                            param.result = realCount - mapping.hiddenCount
                        }
                    }
                }
            )
        }.onFailure { xlog("hook ListView getCount failed for ${cls.name}: ${it.message}") }

        runCatching {
            XposedBridge.hookMethod(
                cls.getMethod("getItem", Int::class.javaPrimitiveType ?: java.lang.Integer.TYPE),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        if (buildingMapping.get() == true) return
                        val self = param.thisObject as? ListAdapter ?: return
                        val kind = listAdapters[self] ?: return
                        if (!isKindEnabled(kind)) return
                        val pos = param.args[0] as? Int ?: return
                        val mapping = listMappingCache[self] ?: return
                        if (pos >= 0 && pos < mapping.virtualToReal.size) {
                            param.args[0] = mapping.virtualToReal[pos]
                        }
                    }
                }
            )
        }.onFailure { xlog("hook ListView getItem failed for ${cls.name}: ${it.message}") }

        runCatching {
            XposedBridge.hookMethod(
                cls.getMethod(
                    "getView",
                    Int::class.javaPrimitiveType ?: java.lang.Integer.TYPE,
                    View::class.java,
                    ViewGroup::class.java
                ),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val self = param.thisObject as? ListAdapter ?: return
                        val kind = listAdapters[self] ?: return
                        if (!isKindEnabled(kind)) return
                        val pos = param.args[0] as? Int ?: return
                        val mapping = listMappingCache[self] ?: return
                        if (pos >= 0 && pos < mapping.virtualToReal.size) {
                            param.args[0] = mapping.virtualToReal[pos]
                        }
                    }
                }
            )
        }.onFailure { xlog("hook ListView getView failed for ${cls.name}: ${it.message}") }
    }

    private fun ensureListMapping(adapter: ListAdapter, realCount: Int, kind: AdapterKind): Mapping? {
        val ids = CloseFriendStore.ids()
        val cached = listMappingCache[adapter]
        if (cached != null && cached.realCount == realCount && cached.idsSnapshot == ids) return cached

        buildingMapping.set(true)
        val mapping = try {
            val hidden = sortedSetOf<Int>()
            for (i in 0 until realCount) {
                val item = runCatching { adapter.getItem(i) }.getOrNull() ?: continue
                val username = extractUsername(item) ?: continue
                if (username in ids) hidden.add(i)
            }
            val table = IntArray(realCount - hidden.size)
            var j = 0
            for (i in 0 until realCount) {
                if (i !in hidden) table[j++] = i
            }
            Mapping(realCount, table, hidden.size, ids).also {
                if (hidden.isNotEmpty()) {
                    xlog("ListView mapping rebuilt ($kind): real=$realCount hidden=${hidden.size}")
                }
            }
        } catch (t: Throwable) {
            xlog("build ListView mapping failed: ${t.message}")
            null
        } finally {
            buildingMapping.remove()
        }
        if (mapping != null) listMappingCache[adapter] = mapping
        return mapping
    }

    // ── 3. RecyclerView 拦截（现代微信 8.0.76 MVVM 通讯录 + 改进版朋友圈） ──

    private fun hookRecyclerView(cl: ClassLoader) {
        val rvClass = runCatching {
            XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", cl)
        }.getOrNull() ?: return
        val adapterClass = runCatching {
            XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView\$Adapter", cl)
        }.getOrNull() ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                rvClass,
                "setAdapter",
                adapterClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val rv = param.thisObject as? View ?: return
                        val adapter = param.args.firstOrNull() ?: return
                        val kind = detectRecyclerKind(rv, adapter) ?: return
                        recyclerAdapters[adapter] = kind
                        hookRecyclerAdapterClass(adapter.javaClass)
                        xlog("RecyclerView adapter captured: ${adapter.javaClass.name} kind=$kind")
                    }
                }
            )
        }.onFailure { xlog("hook RecyclerView.setAdapter failed: ${it.message}") }
    }

    private fun detectRecyclerKind(view: View, adapter: Any): AdapterKind? {
        val act = activityName(view) ?: ""
        val cls = adapter.javaClass.name

        // 通讯录特征
        if (cls.contains("Address", ignoreCase = true) ||
            cls.contains("Contact", ignoreCase = true) ||
            cls.contains("MvvmAddress", ignoreCase = true) ||
            act.contains("AddressUI", ignoreCase = true)
        ) {
            return AdapterKind.ADDRESS
        }

        // 朋友圈特征
        if (cls.contains("ImproveTimeline", ignoreCase = true) ||
            cls.contains("SnsTimeline", ignoreCase = true) ||
            cls.contains("SnsAdapter", ignoreCase = true) ||
            act.contains("ImproveSnsTimelineUI", ignoreCase = true) ||
            act.contains("SnsTimeLineUI", ignoreCase = true) ||
            act.contains("ImproveMainUIC", ignoreCase = true)
        ) {
            return AdapterKind.SNS
        }

        val viewCls = view.javaClass.name
        if (viewCls.contains("Address", ignoreCase = true)) return AdapterKind.ADDRESS
        if (viewCls.contains("Sns", ignoreCase = true) || viewCls.contains("Timeline", ignoreCase = true)) return AdapterKind.SNS

        return null
    }

    private fun hookRecyclerAdapterClass(cls: Class<*>) {
        if (!hookedRecyclerClasses.add(cls.name)) return

        // 1) getItemCount
        findMethodInHierarchy(cls, "getItemCount")?.let { m ->
            runCatching {
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            val self = param.thisObject ?: return
                            val kind = recyclerAdapters[self] ?: return
                            if (!isKindEnabled(kind)) return
                            val realCount = (param.result as? Int) ?: return
                            val mapping = ensureRecyclerMapping(self, realCount, kind) ?: return
                            if (mapping.hiddenCount > 0) {
                                param.result = realCount - mapping.hiddenCount
                            }
                        }
                    }
                )
            }.onFailure { xlog("hook RecyclerView getItemCount failed: ${it.message}") }
        }

        // 2) getItemViewType(int position)
        findMethodInHierarchy(cls, "getItemViewType", java.lang.Integer.TYPE)?.let { m ->
            runCatching {
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            remapRecyclerArgPosition(param, 0)
                        }
                    }
                )
            }
        }

        // 3) getItemId(int position)
        findMethodInHierarchy(cls, "getItemId", java.lang.Integer.TYPE)?.let { m ->
            runCatching {
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            remapRecyclerArgPosition(param, 0)
                        }
                    }
                )
            }
        }

        // 4) onBindViewHolder(VH holder, int position) & 3-param overload
        for (m in cls.methods) {
            if (m.name == "onBindViewHolder") {
                val params = m.parameterTypes
                if (params.size >= 2 && params[1] == Int::class.javaPrimitiveType) {
                    runCatching {
                        XposedBridge.hookMethod(
                            m,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                    remapRecyclerArgPosition(param, 1)
                                }
                            }
                        )
                    }
                }
            }
        }

        // 5) 常见 getter: getItem(int) / getItemOrNull(int)
        for (name in listOf("getItem", "getItemOrNull", "getData", "get")) {
            findMethodInHierarchy(cls, name, java.lang.Integer.TYPE)?.let { m ->
                runCatching {
                    XposedBridge.hookMethod(
                        m,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                remapRecyclerArgPosition(param, 0)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun remapRecyclerArgPosition(param: XC_MethodHook.MethodHookParam, argIndex: Int) {
        if (buildingMapping.get() == true) return
        val self = param.thisObject ?: return
        val kind = recyclerAdapters[self] ?: return
        if (!isKindEnabled(kind)) return
        val pos = param.args.getOrNull(argIndex) as? Int ?: return
        val mapping = recyclerMappingCache[self] ?: return
        if (pos >= 0 && pos < mapping.virtualToReal.size) {
            param.args[argIndex] = mapping.virtualToReal[pos]
        }
    }

    /** 从 RecyclerView.Adapter 提取真实数据列表并构建位置映射 */
    private fun ensureRecyclerMapping(adapter: Any, realCount: Int, kind: AdapterKind): Mapping? {
        val ids = CloseFriendStore.ids()
        val cached = recyclerMappingCache[adapter]
        if (cached != null && cached.realCount == realCount && cached.idsSnapshot == ids) return cached

        buildingMapping.set(true)
        val mapping = try {
            val items = extractItemListFromRecyclerAdapter(adapter, realCount)
            val hidden = sortedSetOf<Int>()
            for (i in 0 until realCount) {
                val item = items?.getOrNull(i) ?: runCatching {
                    XposedHelpers.callMethod(adapter, "getItem", i)
                }.getOrNull()
                val username = item?.let { extractUsername(it) } ?: continue
                if (username in ids) hidden.add(i)
            }
            val table = IntArray(realCount - hidden.size)
            var j = 0
            for (i in 0 until realCount) {
                if (i !in hidden) table[j++] = i
            }
            Mapping(realCount, table, hidden.size, ids).also {
                if (hidden.isNotEmpty()) {
                    xlog("RecyclerView mapping rebuilt ($kind): real=$realCount hidden=${hidden.size}")
                }
            }
        } catch (t: Throwable) {
            xlog("build RecyclerView mapping failed: ${t.message}")
            null
        } finally {
            buildingMapping.remove()
        }
        if (mapping != null) recyclerMappingCache[adapter] = mapping
        return mapping
    }

    /** 反射探测 Adapter 内的数据集 List<*> 字段（AsyncListDiffer 或直接 List 字段） */
    private fun extractItemListFromRecyclerAdapter(adapter: Any, expectedCount: Int): List<*>? {
        var cls: Class<*>? = adapter.javaClass
        var depth = 0
        var fallbackList: List<*>? = null

        while (cls != null && cls != Any::class.java && depth < 8) {
            for (f in cls.declaredFields) {
                val v = runCatching {
                    f.isAccessible = true
                    f.get(adapter)
                }.getOrNull() ?: continue

                if (v is List<*>) {
                    if (v.size == expectedCount) return v
                    if (fallbackList == null && v.isNotEmpty()) fallbackList = v
                } else if (v.javaClass.name.contains("AsyncListDiffer")) {
                    val currentList = runCatching {
                        XposedHelpers.callMethod(v, "getCurrentList") as? List<*>
                    }.getOrNull()
                    if (currentList != null) {
                        if (currentList.size == expectedCount) return currentList
                        if (fallbackList == null && currentList.isNotEmpty()) fallbackList = currentList
                    }
                }
            }
            cls = cls.superclass
            depth++
        }
        return fallbackList
    }

    // ── 4. 朋友圈深度组件拦截（对标 InkHide h1.1 改进版朋友圈数据源拦截） ──

    private fun hookSnsTimelineComponents(cl: ClassLoader) {
        val snsDataClasses = listOf(
            "com.tencent.mm.plugin.sns.ui.improve.component.ImproveMainUIC",
            "com.tencent.mm.plugin.sns.ui.improve.component.g2",
            "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI"
        )
        for (name in snsDataClasses) {
            val clazz = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: continue
            for (method in clazz.declaredMethods) {
                val hasListParam = method.parameterTypes.any { List::class.java.isAssignableFrom(it) }
                if (hasListParam) {
                    runCatching {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                    if (!CloseFriendStore.hideSns()) return
                                    val ids = CloseFriendStore.ids()
                                    if (ids.isEmpty()) return
                                    for (i in param.args.indices) {
                                        val list = param.args[i] as? MutableList<*> ?: continue
                                        val iterator = list.iterator()
                                        var removed = 0
                                        while (iterator.hasNext()) {
                                            val item = iterator.next() ?: continue
                                            val username = extractUsername(item)
                                            if (username != null && username in ids) {
                                                iterator.remove()
                                                removed++
                                            }
                                        }
                                        if (removed > 0) {
                                            xlog("Sns timeline list filtered $removed posts in ${clazz.name}#${method.name}")
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ── 5. 超强泛化 Username 提取器 ──

    /**
     * 从任意数据项（Contact、MvvmAddressItem、SnsInfo、SnsObject、Wrapper 包装对象）提取 wxid/talker。
     */
    fun extractUsername(item: Any?): String? {
        if (item == null) return null
        if (item is String) {
            return if (isWxidPattern(item)) item else null
        }
        // 1) 优先调用 getter 方法
        extractUsernameViaGetter(item)?.let { return it }
        // 2) 常见关键字段名直接反射
        extractUsernameViaFieldNames(item)?.let { return it }
        // 3) 扫描 String 字段
        extractUsernameViaScanningFields(item)?.let { return it }
        // 4) 递归检查内部包含的嵌套数据实体（如 wrapper.contact, wrapper.snsInfo, wrapper.data）
        extractUsernameViaNestedWrapper(item, 0)?.let { return it }
        return null
    }

    private fun isWxidPattern(str: String): Boolean {
        if (str.isBlank()) return false
        return str.startsWith("wxid_") ||
            str.endsWith("@chatroom") ||
            str.endsWith("@openim") ||
            str.startsWith("gh_") ||
            str.endsWith("@stranger") ||
            str.contains("@im.chatroom") ||
            str.endsWith("@app")
    }

    private fun extractUsernameViaGetter(item: Any): String? {
        var cls: Class<*>? = item.javaClass
        var depth = 0
        while (cls != null && cls != Any::class.java && depth < 6) {
            for (m in cls.declaredMethods) {
                if (m.parameterTypes.isEmpty() && m.returnType == String::class.java) {
                    val name = m.name.lowercase()
                    if (name.contains("username") || name.contains("talker") || name.contains("wxid") || name.contains("user")) {
                        val v = runCatching {
                            m.isAccessible = true
                            m.invoke(item) as? String
                        }.getOrNull() ?: continue
                        if (isWxidPattern(v)) return v
                    }
                }
            }
            cls = cls.superclass
            depth++
        }
        return null
    }

    private fun extractUsernameViaFieldNames(item: Any): String? {
        var cls: Class<*>? = item.javaClass
        var depth = 0
        val targetNames = listOf(
            "username", "field_username", "userName", "field_userName",
            "talker", "field_talker", "wxid", "field_wxid"
        )
        while (cls != null && cls != Any::class.java && depth < 6) {
            for (name in targetNames) {
                val f = runCatching { cls.getDeclaredField(name) }.getOrNull() ?: continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(item) as? String
                }.getOrNull()
                if (v != null && isWxidPattern(v)) return v
            }
            cls = cls.superclass
            depth++
        }
        return null
    }

    private fun extractUsernameViaScanningFields(item: Any): String? {
        var cls: Class<*>? = item.javaClass
        var depth = 0
        while (cls != null && cls != Any::class.java && depth < 6) {
            for (f in cls.declaredFields) {
                if (f.type != String::class.java) continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(item) as? String
                }.getOrNull() ?: continue
                if (isWxidPattern(v)) return v
            }
            cls = cls.superclass
            depth++
        }
        return null
    }

    /** 递归解包包装类（最多递归 2 层） */
    private fun extractUsernameViaNestedWrapper(item: Any, depth: Int): String? {
        if (depth >= 2) return null
        var cls: Class<*>? = item.javaClass
        var classDepth = 0
        while (cls != null && cls != Any::class.java && classDepth < 4) {
            for (f in cls.declaredFields) {
                val typeName = f.type.name
                if (f.type.isPrimitive || typeName.startsWith("java.lang.") || typeName.startsWith("android.")) continue
                val child = runCatching {
                    f.isAccessible = true
                    f.get(item)
                }.getOrNull() ?: continue
                if (child === item) continue
                val username = extractUsernameViaGetter(child)
                    ?: extractUsernameViaFieldNames(child)
                    ?: extractUsernameViaScanningFields(child)
                if (username != null) return username
            }
            cls = cls.superclass
            classDepth++
        }
        return null
    }

    // ── 6. 辅助与生命周期 ──

    private fun isKindEnabled(kind: AdapterKind): Boolean = when (kind) {
        AdapterKind.ADDRESS -> CloseFriendStore.hideContact()
        AdapterKind.SNS -> CloseFriendStore.hideSns()
    }

    private fun findMethodInHierarchy(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            runCatching { return c.getDeclaredMethod(name, *paramTypes) }
            c = c.superclass
        }
        return null
    }

    private fun activityName(view: View): String? {
        var ctx: Context? = view.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx.javaClass.name
            ctx = ctx.baseContext
        }
        return null
    }

    private fun registerConfigListeners() {
        val refresh: (String) -> Unit = {
            mainHandler.post {
                // 会话列表：走分组 Hook 的 MStorage notify 通道
                runCatching { ConversationGroupingHook.forceReloadConversations() }

                // 通讯录 & 朋友圈：清除映射缓存并刷新 UI
                synchronized(listMappingCache) { listMappingCache.clear() }
                synchronized(recyclerMappingCache) { recyclerMappingCache.clear() }

                listAdapters.keys.forEach { a ->
                    runCatching { (a as? BaseAdapter)?.notifyDataSetChanged() }
                }
                recyclerAdapters.keys.forEach { a ->
                    runCatching { XposedHelpers.callMethod(a, "notifyDataSetChanged") }
                }
                xlog("config changed: all mappings cleared and adapters refreshed")
            }
        }
        PublicConfigStore.addListener(CloseFriendStore.KEY_LIST, refresh)
        PublicConfigStore.addListener(CloseFriendStore.KEY_ENABLED, refresh)
        PublicConfigStore.addListener(CloseFriendStore.KEY_HIDE_CONVERSATION, refresh)
        PublicConfigStore.addListener(CloseFriendStore.KEY_HIDE_CONTACT, refresh)
        PublicConfigStore.addListener(CloseFriendStore.KEY_HIDE_SNS, refresh)
        PublicConfigStore.addListener(CloseFriendStore.KEY_HIDE_SEARCH, refresh)
    }

    // ── 7. 首页顶部标题长按唤起密友私密中枢 ──

    private fun hookTitleLongPress(cl: ClassLoader) {
        val tabClasses = listOf(
            "com.tencent.mm.ui.conversation.MainUI",
            "com.tencent.mm.ui.LauncherUI"
        )
        for (clsName in tabClasses) {
            val clazz = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull() ?: continue
            runCatching {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "onResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val act = param.thisObject as? Activity ?: return
                            mainHandler.postDelayed({ attachTitleLongPress(act) }, 200L)
                            mainHandler.postDelayed({ attachTitleLongPress(act) }, 800L)
                        }
                    }
                )
            }
        }

        // 监听 TextView.setText，动态捕获主页标题并绑定长按
        runCatching {
            XposedBridge.hookAllMethods(
                android.widget.TextView::class.java,
                "setText",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? android.widget.TextView ?: return
                        val act = tv.context as? Activity ?: return
                        val name = act.javaClass.name
                        if (!name.contains("LauncherUI") && !name.contains("MainTabUI")) return
                        if (isTitleTextView(tv)) {
                            bindTitleLongClick(tv, act)
                        }
                    }
                }
            )
        }
    }

    private fun attachTitleLongPress(activity: Activity) {
        if (activity.isFinishing) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val tv = findTitleTextView(decor) ?: return
        bindTitleLongClick(tv, activity)
    }

    private fun bindTitleLongClick(tv: android.widget.TextView, activity: Activity) {
        if (tv.getTag(com.OKK.yes.core.R.id.abc_tag_close_friend_title_long) == true) return
        tv.setTag(com.OKK.yes.core.R.id.abc_tag_close_friend_title_long, true)
        tv.isLongClickable = true
        tv.setOnLongClickListener { v ->
            // 仅在非聊天窗口前台时唤起
            if (ThemeWallpaperController.isChattingForeground(activity)) return@setOnLongClickListener false

            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            com.OKK.yes.core.hooks.ui.wekit.CloseFriendHubDialog.show(activity)
            xlog("CloseFriendHubDialog triggered via title long-press")
            true
        }
    }

    private fun isTitleTextView(tv: android.widget.TextView): Boolean {
        val text = tv.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return false
        val defaultLabels = listOf("微信", "通讯录", "发现", "我")
        val floatingLabels = BottomTabConfig.floatingTabLabels()
        return defaultLabels.any { text.startsWith(it) } || floatingLabels.any { text.startsWith(it) }
    }

    private fun findTitleTextView(root: View): android.widget.TextView? {
        if (root is android.widget.TextView && isTitleTextView(root)) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findTitleTextView(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    // ── 日志（限流防刷屏） ──

    @Volatile private var lastLogTime = 0L
    private fun xlogThrottled(msg: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLogTime >= 2000L) {
            lastLogTime = now
            xlog(msg)
        }
    }

    private fun xlog(msg: String) {
        android.util.Log.i(TAG, msg)
    }
}
