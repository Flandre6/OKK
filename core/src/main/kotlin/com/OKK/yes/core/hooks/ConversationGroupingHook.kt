package com.OKK.yes.core.hooks

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ListView
import com.OKK.yes.core.R
import com.OKK.yes.core.compat.DexKitSupport
import com.OKK.yes.core.hooks.ui.StyledDialogs
import com.OKK.yes.core.hooks.ui.wekit.ConversationTabsView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话分组 Hook（WeKit 美化 UI 重构版）。
 *
 * 核心设计：
 * 1. 采用与 WeKit 1:1 对标的 Compose 胶囊分组标签栏 Header 挂载至 ConversationListView 顶部；
 * 2. 完美继承列表 SQL 条件注入逻辑，对微信首页 rconversation 查询进行按分组谓词改写；
 * 3. 支持跑道型 Pill 圆角美化、横向无缝滚动、长按 Dropdown 菜单、以及 iOS 风格抖动拖拽排序；
 * 4. 完美兼容 OKK 原生数据库刷新与 notify 机制。
 */
object ConversationGroupingHook {

    private const val TAG = "OKK-Group"
    const val TAB_BAR_TAG = "abc_conv_group_tab_bar"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var activePredicate: String? = null
    @Volatile private var installed = false
    @Volatile private var lifecycleRegistered = false
    @Volatile private var mainUiRef: Any? = null
    @Volatile private var listViewRef: ListView? = null
    @Volatile private var tabsViewRef: ConversationTabsView? = null

    private val hookedSqlMethods = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile private var storageClazz: Class<*>? = null
    @Volatile var storageRef: Any? = null
    @Volatile private var notifyMethod: Method? = null
    @Volatile private var updateUnreadByTalkerMethod: Method? = null
    private val storageSeen = java.util.Collections.synchronizedSet(mutableSetOf<Any>())
    private var lastGroupClickTime = 0L
    private var lastSelectedGroupId: String? = null

    fun install(ctx: Context, cl: ClassLoader, mp: String?) {
        if (installed) return
        installed = true
        xlog("install start")
        DexKitSupport.classLoader = cl
        DexKitSupport.appContext = ctx.applicationContext ?: ctx
        DexKitSupport.modulePath = mp

        hookMainUi(cl)
        registerLifecycleAttach(ctx)
        hookSqliteMethods(ctx, cl, mp)
        resolveStorage(cl)

        PublicConfigStore.addListener(ConversationGroupConfig.KEY_ENABLED) { enabled ->
            xlog("enabled changed -> $enabled")
            if (enabled.toBoolean()) {
                mainHandler.post { refreshTabs() }
            }
        }
        xlog("install done")
    }

    // ── SQL 注入 ──

    private fun hookSqliteMethods(ctx: Context, cl: ClassLoader, mp: String?) {
        // 1. 尝试使用 DexKit 定位微信底层的 SQLite Wrapper rawQuery 方法：a(String sql, String[] args, int flags) -> Cursor
        val wrapperCandidates = runCatching {
            DexKitSupport.findMethodsByStrings(
                ctx, cl, mp, "sql is null ", "DB IS CLOSED ! {%s}"
            )
        }.getOrDefault(emptyList())

        val wrapperMethod = wrapperCandidates.firstOrNull { m ->
            m.parameterTypes.size == 3 &&
            m.parameterTypes[0] == String::class.java &&
            m.parameterTypes[2] == Int::class.javaPrimitiveType &&
            m.returnType.name.contains("Cursor")
        }

        if (wrapperMethod != null) {
            runCatching {
                wrapperMethod.isAccessible = true
                XposedBridge.hookMethod(wrapperMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        if (activePredicate == null) return
                        val sql = param.args.firstOrNull { it is String } as? String ?: return
                        if (!sql.contains("rconversation", ignoreCase = true)) return
                        val rewritten = rewriteConversationSql(sql) ?: return
                        param.args[0] = rewritten
                        logSqlRewrite("SQLiteWrapper", sql, rewritten)
                    }
                })
                xlog("Hooked SQLite wrapper method: ${wrapperMethod.declaringClass.name}#${wrapperMethod.name}")
            }
        } else {
            xlog("SQLite wrapper method not found via DexKit (candidates count=${wrapperCandidates.size}), fallback to SQLiteDatabase hooks")
        }

        // 2. 通用 SQLiteDatabase 所有包含 SQL 字符串方法 Hook
        val dbClasses = listOf(
            "com.tencent.wcdb.compat.SQLiteDatabase",
            "com.tencent.wcdb.database.SQLiteDatabase",
            "android.database.sqlite.SQLiteDatabase"
        )
        for (cn in dbClasses) {
            val dbClass = runCatching { XposedHelpers.findClass(cn, cl) }.getOrNull() ?: continue
            for (m in dbClass.declaredMethods) {
                val name = m.name
                if (!name.startsWith("rawQuery") && !name.startsWith("query") && name != "execSQL") continue
                val strIdx = m.parameterTypes.indexOf(String::class.java)
                if (strIdx < 0) continue

                val key = "${dbClass.name}#${m.name}#${m.parameterTypes.joinToString(",") { it.name }}"
                if (!hookedSqlMethods.add(key)) continue

                runCatching {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            if (activePredicate == null) return
                            val sql = param.args[strIdx] as? String ?: return
                            if (!sql.contains("rconversation", ignoreCase = true)) return
                            ContactDisplayNames.rememberDb(param.thisObject)
                            val rewritten = rewriteConversationSql(sql) ?: return
                            param.args[strIdx] = rewritten
                            logSqlRewrite("SQLiteDatabase#${m.name}", sql, rewritten)
                        }
                    })
                }
            }
        }
    }

    private fun rewriteConversationSql(sql: String): String? {
        val predicate = activePredicate ?: return null
        val lower = sql.lowercase()
        if (!lower.contains("rconversation")) return null
        if (lower.contains("wekit_folder_") || lower.contains("conversationboxservice")) return null

        // 性能优化：跳过纯 count(*) 等聚合/低价值查询，避免未读角标轮询被反复重写造成卡顿
        val trimmed = sql.trimStart().lowercase()
        if (trimmed.startsWith("select count(") || trimmed.startsWith("select distinct count(")) return null

        // 兼容 SQL 别名 (如 FROM rconversation c 或 FROM rconversation AS c)
        var alias = "rconversation"
        val fromIdx = lower.indexOf("from rconversation")
        if (fromIdx >= 0) {
            val afterFrom = sql.substring(fromIdx + "from rconversation".length).trimStart()
            val tokens = afterFrom.split(Regex("\\s+"))
            if (tokens.isNotEmpty()) {
                val firstToken = tokens[0].lowercase()
                if (firstToken == "as" && tokens.size > 1) {
                    val candidate = tokens[1].replace(",", "").replace(";", "").trim()
                    if (candidate.isNotBlank() && candidate !in listOf("where", "order", "group", "limit", "left", "right", "inner", "join")) {
                        alias = candidate
                    }
                } else if (firstToken !in listOf("where", "order", "group", "limit", "left", "right", "inner", "join", ",", ";")) {
                    val candidate = firstToken.replace(",", "").replace(";", "").trim()
                    if (candidate.isNotBlank()) {
                        alias = candidate
                    }
                }
            }
        }

        val adaptedPredicate = if (alias != "rconversation") {
            predicate.replace("rconversation.", "$alias.")
        } else {
            predicate
        }

        // 1. 尝试替换原 SQL 中的 parentRef 条件限制（避免 parentRef IS NULL 阻碍公众号/服务号筛选）
        val replaced = replaceParentRef(sql, alias, "($adaptedPredicate)")
        if (replaced != null) return replaced

        // 2. 兗底追加到 WHERE 语句
        return injectCondition(sql, "($adaptedPredicate)")
    }

    // 日志限流：避免 SQL 重写日志刷屏（性能关键）
    @Volatile private var lastSqlLogTime = 0L
    private fun logSqlRewrite(kind: String, sql: String, rewritten: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSqlLogTime >= 2000L) {
            lastSqlLogTime = now
            xlog("$kind rewritten: $sql -> $rewritten")
        }
    }

    private fun replaceParentRef(sql: String, alias: String, condition: String): String? {
        val patterns = listOf(
            Regex("(?i)\\(\\s*(?:$alias\\.)?parentref\\s+is\\s+null\\s+or\\s+(?:$alias\\.)?parentref\\s*=\\s*''\\s*\\)"),
            Regex("(?i)\\(\\s*(?:$alias\\.)?parentref\\s*=\\s*''\\s+or\\s+(?:$alias\\.)?parentref\\s+is\\s+null\\s*\\)"),
            Regex("(?i)(?:$alias\\.)?parentref\\s+is\\s+null"),
            Regex("(?i)(?:$alias\\.)?parentref\\s*=\\s*''")
        )
        for (pattern in patterns) {
            if (pattern.containsMatchIn(sql)) {
                return pattern.replace(sql, condition)
            }
        }
        return null
    }

    private fun injectCondition(sql: String, condition: String): String {
        val lower = sql.lowercase()
        val insertion = listOf(" order by ", " group by ", " limit ")
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (insertion == null) {
            return if (lower.contains(" where ")) "$sql AND $condition"
            else "$sql WHERE $condition"
        }
        val head = sql.substring(0, insertion)
        val tail = sql.substring(insertion)
        return if (head.lowercase().contains("where")) "$head AND $condition$tail"
        else "$head WHERE $condition$tail"
    }

    // ── 主 UI 标签栏 ──

    private fun hookMainUi(cl: ClassLoader) {
        fun hook(it: Class<*>?) {
            for (m in sourceMethods(it)) {
                if (m.name != "onTabCreate") continue
                runCatching {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            mainUiRef = param.thisObject
                            Thread {
                                ContactDisplayNames.ensureMainDb(cl)
                            }.start()
                            mainHandler.post {
                                attachTabBar(param.thisObject)
                            }
                        }
                    })
                }
            }
        }
        val viaDex = runCatching {
            DexKitSupport.findMethodByStrings(
                DexKitSupport.appContext!!,
                cl,
                DexKitSupport.modulePath,
                "MicroMsg.MainUI", "onTabCreate, %d"
            )
        }.getOrNull()
        if (viaDex != null) {
            runCatching {
                viaDex.isAccessible = true
                XposedBridge.hookMethod(viaDex, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        mainUiRef = param.thisObject
                        Thread {
                            ContactDisplayNames.ensureMainDb(cl)
                        }.start()
                        mainHandler.post { attachTabBar(param.thisObject) }
                    }
                })
            }
            return
        }
        runCatching {
            val mainUi = XposedHelpers.findClass("com.tencent.mm.ui.conversation.MainUI", cl)
            hook(mainUi)
        }
    }

    private fun sourceMethods(clazz: Class<*>?): Array<out Method> =
        clazz?.declaredMethods ?: emptyArray()

    private fun registerLifecycleAttach(context: Context) {
        if (lifecycleRegistered) return
        val app = (context as? Application) ?: (context.applicationContext as? Application) ?: return
        lifecycleRegistered = true
        runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val cn = activity.javaClass.name
                    if (!cn.contains("LauncherUI") && !cn.contains("MainUI")) return
                    mainUiRef = activity
                    for (delay in longArrayOf(120L, 500L, 1200L)) {
                        mainHandler.postDelayed({ attachTabBar(activity) }, delay)
                    }
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }

    private fun attachTabBar(host: Any) {
        if (!ConversationGroupConfig.isEnabled()) return
        runCatching {
            val listView = findConversationListView(host) ?: return
            if (listView.getTag(R.id.abc_tag_conv_group_header) == TAB_BAR_TAG) {
                refreshTabs()
                return
            }
            val bar = buildTabBar(listView.context)
            val headerAttached = runCatching {
                listView.addHeaderView(bar, null, false)
                true
            }.getOrDefault(false)
            if (!headerAttached) {
                attachTabBarAboveList(listView, bar) ?: return@runCatching
            }
            listView.setTag(R.id.abc_tag_conv_group_header, TAB_BAR_TAG)
            listViewRef = listView
            selectTab(ConversationGroupConfig.selectedGroupId())
            refreshTabs()
        }
    }

    private fun attachTabBarAboveList(listView: ListView, bar: View): Boolean? {
        val parent = listView.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(listView)
        if (index < 0) return null
        if (bar.parent != null) runCatching { (bar.parent as? ViewGroup)?.removeView(bar) }
        parent.addView(
            bar,
            index,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return true
    }

    private fun findConversationListView(host: Any): ListView? {
        listViewRef?.let { if (it.isAttachedToWindow) return it }
        var cursor: Class<*>? = host.javaClass
        var depth = 0
        while (cursor != null && depth < 6) {
            for (f in cursor.declaredFields) {
                runCatching {
                    f.isAccessible = true
                    val v = f.get(host)
                    if (isConversationListView(v)) return v as ListView
                }
            }
            cursor = cursor.superclass
            depth++
        }
        val decor = (host as? Activity)?.window?.decorView as? ViewGroup ?: return null
        fun walk(view: View): ListView? {
            if (isConversationListView(view)) return view as ListView
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val found = walk(view.getChildAt(i))
                    if (found != null) return found
                }
            }
            return null
        }
        return walk(decor)
    }

    private fun isConversationListView(view: Any?): Boolean {
        val list = view as? ListView ?: return false
        if (!list.isShown && !list.isAttachedToWindow) return false
        val name = list.javaClass.name
        if (name.contains("ConversationListView") || name.contains("conversation", ignoreCase = true)) return true
        return list.adapter != null && list.height > 0 && list.childCount > 0
    }

    /** 构建 WeKit 风格的美化 Compose 胶囊标签栏 */
    private fun buildTabBar(ctx: Context): View {
        val tabsView = ConversationTabsView(
            context = ctx,
            onTabSelected = { groupId -> selectTab(groupId) },
            onCreateGroup = {
                ConversationGroupManager.showGroupEditorDialog(ctx, null) {
                    refreshTabs()
                }
            },
            onPresetSettings = {
                ConversationGroupManager.showPresetTagsDialog(ctx) {
                    refreshTabs()
                }
            },
            onEditGroup = { group ->
                ConversationGroupManager.showGroupEditorDialog(ctx, group) {
                    refreshTabs()
                }
            },
            onDeleteGroup = { group ->
                StyledDialogs.confirm(ctx, "删除分组", "确定删除「${group.name}」分组吗？", okLabel = "删除", danger = true) {
                    deleteGroup(group.id)
                    refreshTabs()
                }
            }
        ).apply {
            tag = TAB_BAR_TAG
            setTag(R.id.abc_tag_conv_group_header, TAB_BAR_TAG)
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        tabsViewRef = tabsView
        return tabsView
    }

    fun markUnreadAsReadForActiveGroup() {
        val activePred = activePredicate
        val sql = if (activePred.isNullOrBlank()) {
            "UPDATE rconversation SET unReadCount = 0, unReadMuteCount = 0, atCount = 0 WHERE unReadCount > 0 OR unReadMuteCount > 0 OR atCount > 0"
        } else {
            "UPDATE rconversation SET unReadCount = 0, unReadMuteCount = 0, atCount = 0 WHERE (unReadCount > 0 OR unReadMuteCount > 0 OR atCount > 0) AND ($activePred)"
        }
        val ok = ContactDisplayNames.execSql(sql)
        xlog("markUnreadAsReadForActiveGroup ok=$ok, sql=$sql")
        mainHandler.post {
            reloadConversations()
        }
    }

    private fun selectTab(groupId: String) {
        val now = SystemClock.uptimeMillis()
        if (groupId == lastSelectedGroupId && now - lastGroupClickTime <= 500L) {
            markUnreadAsReadForActiveGroup()
            val ctx = listViewRef?.context ?: DexKitSupport.appContext
            if (ctx != null) {
                mainHandler.post {
                    runCatching {
                        android.widget.Toast.makeText(ctx, "已将消息标记为已读", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        lastGroupClickTime = now
        lastSelectedGroupId = groupId

        ConversationGroupConfig.setSelectedGroupId(groupId)
        val group = ConversationGroupConfig.groupById(groupId)
        activePredicate = group?.let { ConversationGroupConfig.buildPredicate(it) }
        mainHandler.post {
            reloadConversations()
        }
    }

    private fun refreshTabs() {
        val selectedId = ConversationGroupConfig.selectedGroupId()
        val group = ConversationGroupConfig.groupById(selectedId)
        activePredicate = group?.let { ConversationGroupConfig.buildPredicate(it) }
        tabsViewRef?.refreshState()
        reloadConversations()
    }

    private fun deleteGroup(id: String) {
        val groups = ConversationGroupConfig.loadGroups()
        val custom = groups.filter { !it.isPreset() && it.id != id }
        ConversationGroupConfig.saveGroups(custom)
        if (ConversationGroupConfig.selectedGroupId() == id) {
            ConversationGroupConfig.setSelectedGroupId(ConversationGroupConfig.ID_ALL)
            activePredicate = null
        }
    }

    fun selectNextTab() {
        val groups = ConversationGroupConfig.loadGroups()
        if (groups.isEmpty()) return
        val currentId = ConversationGroupConfig.selectedGroupId()
        val index = groups.indexOfFirst { it.id == currentId }
        val nextIndex = if (index < 0 || index >= groups.size - 1) 0 else index + 1
        val nextGroup = groups[nextIndex]
        selectTab(nextGroup.id)
        mainHandler.post { tabsViewRef?.refreshState() }
    }

    fun selectPreviousTab() {
        val groups = ConversationGroupConfig.loadGroups()
        if (groups.isEmpty()) return
        val currentId = ConversationGroupConfig.selectedGroupId()
        val index = groups.indexOfFirst { it.id == currentId }
        val prevIndex = if (index <= 0) groups.size - 1 else index - 1
        val prevGroup = groups[prevIndex]
        selectTab(prevGroup.id)
        mainHandler.post { tabsViewRef?.refreshState() }
    }

    // ── 刷新 ──

    private fun resolveStorage(cl: ClassLoader) {
        val ctx = DexKitSupport.appContext ?: return
        val mp = DexKitSupport.modulePath

        // 1. 通过 DexKit 动态定位 ConversationStorage 类
        val convStorageClazz = runCatching {
            DexKitSupport.findClassByStrings(
                ctx, cl, mp, "MicroMsg.ConversationStorage", "delChatContact username:"
            )
        }.getOrNull()

        if (convStorageClazz != null) {
            storageClazz = convStorageClazz
            val superClass = convStorageClazz.superclass
            if (superClass != null) {
                // MStorage 类的 3 参数通知方法: notify(int eventType, MStorage stg, Object item)
                notifyMethod = superClass.declaredMethods.firstOrNull { m ->
                    m.parameterTypes.size == 3 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[1] == superClass
                }
                xlog("Found notifyMethod via DexKit: ${notifyMethod?.name} in superClass ${superClass.name}")
            }

            // Hook 构造函数以第一时间捕获 storageRef
            for (c in convStorageClazz.declaredConstructors) {
                runCatching {
                    c.isAccessible = true
                    XposedBridge.hookMethod(c, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            storageRef = param.thisObject
                            xlog("Captured storageRef from constructor: $storageRef")
                        }
                    })
                }
            }

        } else {
            xlog("ConversationStorage not found via DexKit, using fallback probe")
            val factoryClazz = runCatching { XposedHelpers.findClass("com.tencent.mm.model.bh", cl) }.getOrNull()
            if (factoryClazz != null) {
                for (m in factoryClazz.declaredMethods) {
                    if (m.returnType.name.contains("ConversationStorage") ||
                        m.returnType.name.endsWith("ConversationStorage") ||
                        m.returnType.name.endsWith(".f") ||
                        m.returnType.name.endsWith(".e")
                    ) {
                        storageClazz = m.returnType
                        break
                    }
                }
            }
            val targetCls = storageClazz
            if (targetCls != null) {
                val superClass = targetCls.superclass
                if (superClass != null) {
                    notifyMethod = superClass.declaredMethods.firstOrNull { m ->
                        m.parameterTypes.size == 3 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        m.parameterTypes[1] == superClass
                    }
                }
                factoryClazz?.declaredMethods?.forEach { m ->
                    if (m.returnType != targetCls) return@forEach
                    runCatching {
                        m.isAccessible = true
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                val instance = param.result ?: return
                                storageSeen.add(instance)
                                if (storageRef == null) storageRef = instance
                            }
                        })
                    }
                }
            }
        }
    }

    fun getStorageInstance(): Any? = storageRef ?: storageSeen.lastOrNull()

    fun forceReloadConversations() = reloadConversations()

    private fun reloadConversations() {
        val s = storageRef ?: storageSeen.lastOrNull()
        val m = notifyMethod
        if (s != null && m != null) {
            runCatching {
                m.isAccessible = true
                if (m.parameterTypes.size == 3) {
                    m.invoke(s, 5, s, "")
                    xlog("reloadConversations: invoked notifyMethod (5, storage, '') successfully")
                } else if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                    m.invoke(s, "")
                    xlog("reloadConversations: invoked 1-param notifyMethod")
                } else if (m.parameterTypes.isEmpty()) {
                    m.invoke(s)
                }
                return
            }.onFailure {
                xlog("reloadConversations failed: ${it.message}")
            }
        } else {
            xlog("reloadConversations failed: storageRef=$s, notifyMethod=$m")
        }

        // 保底：刷新 MainUI ListView Adapter
        val main = mainUiRef ?: return
        var curr: Class<*>? = main.javaClass
        while (curr != null) {
            for (mItem in curr.declaredMethods) {
                if (mItem.parameterTypes.isEmpty() &&
                    (mItem.name.contains("update") || mItem.name.contains("refresh") || mItem.name.contains("reload"))
                ) {
                    runCatching {
                        mItem.isAccessible = true
                        mItem.invoke(main)
                        return
                    }
                }
            }
            curr = curr.superclass
        }
    }

    fun onChatExit() {
        // 保留接口兼容，聊天退出时若需增量刷新会话列表可在此处理
    }

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        try { XposedBridge.log("[$TAG] $msg") } catch (_: Throwable) {}
    }
}
