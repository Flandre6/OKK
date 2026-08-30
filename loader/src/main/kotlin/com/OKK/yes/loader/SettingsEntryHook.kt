package com.OKK.yes.loader

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Toast
import com.OKK.yes.core.compat.DexKitSupport
import com.OKK.yes.core.compat.ReflectCompat
import com.OKK.yes.core.compat.WeChatClassNames
import com.OKK.yes.core.compat.WeChatVersion
import com.OKK.yes.core.hooks.PublicConfigStore
import com.OKK.yes.core.settings.SettingsEntryPolicy
import com.OKK.yes.loader.ui.OKKSettingsDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.LinkedList
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信设置列表注入 OKK 入口。
 *
 * 跨版本策略（8.0.69–8.0.76 国服/Play）：
 * 1. 稳定类 [WeChatClassNames.SETTING_GROUP_PERSONAL] / MainSettingsUI
 * 2. DexKit 用 `MicroMsg.SettingDataSource` 找数据源（不绑 hy3.d）
 * 3. 反射扫 List 字段插入行；方法名 A6/C6/U6 在层级上查找
 * 4. 旧版 SettingsUI Preference 作兜底
 */
object SettingsEntryHook {
    private const val TAG = "OKK-SettingsEntry"
    private const val ROW_KEY = "SettingGroup_Main_OKK"
    private const val PLUS_MENU_ID = 0x0A0C2026

    private val installed = AtomicBoolean(false)
    private val modernDataHookInstalled = AtomicBoolean(false)
    private val markedModernItems = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    fun install(classLoader: ClassLoader, context: Context? = null) {
        if (!installed.compareAndSet(false, true)) return
        val ver = context?.let { runCatching { WeChatVersion.resolve(it) }.getOrNull() }
        xlog("install begin ver=${ver?.summary() ?: "n/a"}")

        var n = 0
        n += hookModernSettings(classLoader, context)
        n += hookLegacySettings(classLoader)
        val plusOk = hookHomePlusMenu(classLoader, context)
        n += plusOk
        xlog("settings entry hooks installed count=$n plusMenu=${if (plusOk > 0) "OK" else "FAIL"}")
        if (plusOk == 0) {
            // 加号菜单是当前唯一入口：失败必须显式报错，否则用户只会看到“没有入口”而无从排查。
            xlog("FATAL: plus menu entry unavailable on this build; OKK has no visible entry")
            runCatching {
                com.OKK.yes.core.hooks.ModuleLog.i("严重：加号菜单入口注入失败，模块将没有可见入口")
            }
        }
        // 真实生效上报：入口注入是唯一用户可见的功能入口，plusOk==0 时必须在适配报告中体现为非 OK，
        // 避免探针阶段“类存在就算 OK”与实际无入口的脱节。
        com.OKK.yes.core.startup.FeatureHookRegistry.reportEffective(
            "SettingsEntry",
            plusOk > 0,
            if (plusOk > 0) "加号菜单入口已注入" else "加号菜单入口注入失败，模块无可见入口"
        )
    }

    // ── modern SettingDataSource ───────────────────────────────────────────

    private fun hookModernSettings(classLoader: ClassLoader, context: Context?): Int {
        if (!modernDataHookInstalled.compareAndSet(false, true)) return 0
        // 新版 MainSettingsUI 的 item 渲染链路版本敏感，强插会产生空白占位。
        // 入口统一迁到首页右上角加号菜单；这里只清理旧版本残留的空白占位，不再创建任何设置页 item。
        val dataSource = resolveDataSourceClass(classLoader, context)
        if (dataSource == null) {
            xlog("modern settings list injection disabled; dataSource missing")
            return 0
        }
        var hooked = 0
        runCatching {
            XposedHelpers.findAndHookMethod(dataSource, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    cleanModernPlaceholders(param.thisObject)
                }
            })
            hooked++
        }.onFailure { xlog("modern cleanup onCreate hook fail: ${it.message}") }
        for (m in dataSource.declaredMethods) {
            if (m.parameterCount !in 0..3) continue
            if (m.name.length > 2 && m.name != "a" && m.name != "c" && m.name != "getData") continue
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        cleanModernPlaceholders(param.thisObject)
                    }
                })
                hooked++
            }
        }
        xlog("modern settings list injection disabled; cleanup hooks=$hooked dataSource=${dataSource.name}")
        return if (hooked > 0) 1 else 0
    }

    private fun cleanModernPlaceholders(dataSource: Any) {
        runCatching {
            var removed = 0
            val fields = ReflectCompat.listLikeFields(dataSource)
            for ((_, value) in fields) {
                @Suppress("UNCHECKED_CAST")
                val list = value as? MutableList<Any?> ?: continue
                val before = list.size
                list.removeAll { isOKKPlaceholder(it) }
                removed += before - list.size
            }
            if (removed > 0) xlog("modern placeholders removed=$removed")
        }.onFailure { xlog("modern cleanup fail: ${it.message}") }
    }

    private fun isOKKPlaceholder(rowOrItem: Any?): Boolean {
        if (rowOrItem == null) return false
        if (isMarked(rowOrItem)) return true
        val key = keyOfRow(rowOrItem) ?: keyOfItem(rowOrItem) ?: keyOfItem(unwrapRow(rowOrItem))
        if (key == ROW_KEY || key == SettingsEntryPolicy.entryKey) return true
        return false
    }

    private fun resolveDataSourceClass(classLoader: ClassLoader, context: Context?): Class<*>? {
        // 1) 69 混淆名
        ReflectCompat.findClass(WeChatClassNames.Obfuscated69.SETTING_DATA_SOURCE, classLoader)
            ?.let { return it }

        // 2) DexKit 特征日志（全版本命中；不向 loader 暴露 DexKitBridge 类型）
        if (context != null) {
            val found = DexKitSupport.findClassByStrings(
                context,
                classLoader,
                ModulePathHolder.modulePath,
                WeChatClassNames.SETTING_DATA_SOURCE_TAG,
                WeChatClassNames.SETTING_DATA_SIZE_LOG
            ) ?: DexKitSupport.findClassByStrings(
                context,
                classLoader,
                ModulePathHolder.modulePath,
                WeChatClassNames.SETTING_DATA_SOURCE_TAG
            )
            if (found != null) return found
        }
        return null
    }

    private fun hookPersonalBehaviors(personal: Class<*>, classLoader: ClassLoader) {
        // key
        ReflectCompat.findDeclaredMethod(personal, "A6", paramCount = 0)?.let { m ->
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isMarked(param.thisObject)) param.result = ROW_KEY
                }
            })
        }
        // title: 当前微信 C6 在 fy3.i 基类，不能只找 declared method。
        ReflectCompat.hierarchy(personal)
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "C6" && it.parameterTypes.isEmpty() && it.returnType == String::class.java }
            ?.let { m ->
                m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isMarked(param.thisObject)) {
                        param.result = SettingsEntryPolicy.entryTitle
                    }
                }
            })
        }
        // subtitle resource
        ReflectCompat.findDeclaredMethod(personal, "y6", paramCount = 0)?.let { m ->
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isMarked(param.thisObject)) param.result = null
                }
            })
        }
        // click: U6(Context, View, int) on parent xy3.e
        val click = ReflectCompat.findDeclaredMethod(
            personal,
            "U6",
            paramCount = 3
        ) ?: ReflectCompat.hierarchy(personal)
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { m ->
                m.parameterTypes.size == 3 &&
                    Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    View::class.java.isAssignableFrom(m.parameterTypes[1]) &&
                    (m.parameterTypes[2] == Int::class.javaPrimitiveType ||
                        m.parameterTypes[2] == Int::class.java)
            }?.also { it.isAccessible = true }

        if (click != null) {
            XposedBridge.hookMethod(click, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isMarked(param.thisObject)) return
                    val ctx = param.args.getOrNull(0) as? Context ?: return
                    openSettings(ctx)
                    param.result = null
                }
            })
            xlog("click hooked ${click.declaringClass.name}.${click.name}")
        } else {
            xlog("click method U6 not found on hierarchy")
        }

        // 额外：若 69 基类可加载，再挂一次（双保险）
        runCatching {
            val base = XposedHelpers.findClass(WeChatClassNames.Obfuscated69.SETTING_ITEM_BASE, classLoader)
            ReflectCompat.findDeclaredMethod(base, "C6", paramCount = 0)?.let { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isMarked(param.thisObject)) {
                            param.result = SettingsEntryPolicy.entryTitle
                        }
                    }
                })
            }
        }
    }

    /**
     * 仅主设置页可注入。群「聊天信息」/ 资料页等也会走 SettingDataSource，
     * 不限制 activity 会把 OKK 行插进群聊信息顶部。
     */
    private fun isAllowedSettingsHost(activity: Any?): Boolean {
        if (!PublicConfigStore.getBoolean("settings_entry_enabled", true)) return false
        val name = activity?.javaClass?.name ?: return false
        return SettingsEntryPolicy.shouldInjectInto(name)
    }

    private fun injectIntoSourceList(
        dataSource: Any,
        classLoader: ClassLoader,
        personalClass: Class<*>
    ) {
        runCatching {
            val fields = ReflectCompat.listLikeFields(dataSource)
            for ((field, value) in fields) {
                val list = value as? MutableList<Any?> ?: continue
                if (list.isEmpty()) continue
                val hasPersonal = list.any { el ->
                    el != null && (
                        personalClass.isInstance(el) ||
                            keyOfItem(el) == WeChatClassNames.SETTING_KEY_PERSONAL
                        )
                }
                if (!hasPersonal) continue
                if (list.any { isMarked(it) || keyOfItem(it) == ROW_KEY }) return

                val activity = list.firstNotNullOfOrNull {
                    runCatching { XposedHelpers.callMethod(it, "getActivity") }.getOrNull()
                } ?: continue
                if (!isAllowedSettingsHost(activity)) {
                    xlog("skip inject host=${activity.javaClass.name}")
                    return
                }

                val item = createMarkedPersonalItem(activity, classLoader, personalClass) ?: continue
                list.add(0, item)
                xlog("source list insert via ${field.name} size=${list.size}")
                return
            }
        }.onFailure {
            xlog("source list inject skip: ${it.message}")
        }
    }

    private fun injectModernRow(
        dataSource: Any,
        classLoader: ClassLoader,
        personalClass: Class<*>
    ) {
        runCatching {
            // 若源列表已有我们的项，onCreate 会包一层，无需手塞 wrapper
            injectIntoSourceList(dataSource, classLoader, personalClass)

            // 兜底：已展示的 LinkedList 行
            val fields = ReflectCompat.listLikeFields(dataSource)
            val source = fields.mapNotNull { it.second as? List<*> }
                .firstOrNull { list ->
                    list.any {
                        it != null && (
                            personalClass.isInstance(it) ||
                                keyOfItem(it) == WeChatClassNames.SETTING_KEY_PERSONAL
                            )
                    }
                } ?: return

            if (source.any { isMarked(it) || keyOfItem(it) == ROW_KEY }) return

            val rowsField = fields.firstOrNull { (_, v) ->
                v is LinkedList<*> || (v is MutableList<*> && v !== source)
            } ?: return
            @Suppress("UNCHECKED_CAST")
            val rows = rowsField.second as? MutableList<Any> ?: return
            if (rows.any { keyOfRow(it) == ROW_KEY || isMarked(unwrapRow(it)) }) return

            val activity = source.firstNotNullOfOrNull {
                runCatching { XposedHelpers.callMethod(it, "getActivity") }.getOrNull()
            } ?: return
            if (!isAllowedSettingsHost(activity)) {
                xlog("skip modern row host=${activity.javaClass.name}")
                return
            }
            val item = createMarkedPersonalItem(activity, classLoader, personalClass) ?: return

            val wrapper = createRowWrapper(item, classLoader, personalClass)
            if (wrapper != null) {
                if (rows is LinkedList) rows.addFirst(wrapper) else rows.add(0, wrapper)
                xlog("modern row wrapper inserted size=${rows.size}")
            } else if (rows is MutableList) {
                // 无 wrapper 时直接塞 item（部分版本可渲染）
                rows.add(0, item)
                xlog("modern row raw item inserted")
            }
        }.onFailure {
            xlog("modern row inject fail: ${it.message}")
        }
    }

    private fun createRowWrapper(
        item: Any,
        classLoader: ClassLoader,
        personalClass: Class<*>
    ): Any? {
        // 69: hy3.e(int, fy3.i)
        runCatching {
            val w = XposedHelpers.findClass(WeChatClassNames.Obfuscated69.SETTING_ROW_WRAPPER, classLoader)
            val base = personalClass.superclass ?: personalClass
            for (ctor in w.constructors) {
                if (ctor.parameterTypes.size != 2) continue
                if (ctor.parameterTypes[0] != Int::class.javaPrimitiveType) continue
                if (!ctor.parameterTypes[1].isAssignableFrom(item.javaClass) &&
                    !ctor.parameterTypes[1].isAssignableFrom(base)
                ) continue
                ctor.isAccessible = true
                return ctor.newInstance(0, item)
            }
        }
        // 通用：在 item 的 classloader 里找 (int, itemType) 构造
        return null
    }

    private fun createMarkedPersonalItem(
        activity: Any,
        classLoader: ClassLoader,
        personalClass: Class<*>
    ): Any? {
        return runCatching {
            val item = personalClass.constructors
                .firstOrNull { it.parameterTypes.size == 1 }
                ?.newInstance(activity)
                ?: return null
            markedModernItems.add(item)
            runCatching { XposedHelpers.callMethod(item, "onCreate", null as android.os.Bundle?) }
            item
        }.getOrNull()
    }

    private fun keyOfItem(o: Any?): String? =
        o?.let {
            ReflectCompat.callFirst(it, "A6") as? String
                ?: runCatching { XposedHelpers.callMethod(it, "A6") as? String }.getOrNull()
        }

    private fun keyOfRow(o: Any?): String? =
        o?.let {
            ReflectCompat.callFirst(it, "v", "A6") as? String
        }

    private fun unwrapRow(o: Any?): Any? {
        if (o == null) return null
        return ReflectCompat.callFirst(o, "j") ?: o
    }

    private fun isMarked(o: Any?): Boolean {
        if (o == null) return false
        val inner = unwrapRow(o)
        return synchronized(markedModernItems) {
            markedModernItems.contains(o) || (inner != null && markedModernItems.contains(inner))
        }
    }

    /**
     * 加号菜单入口类名在同一版本号下不同构建批次中会漂移（rg/mg/og/pg 都是短名）。
     * 优先用 DexKit 稳定字符串锚点（xlog tag "MicroMsg.PlusSubMenuHelper"）定位，失败才退回历史硬编码名。
     *
     * 注意：xlog tag 字符串会被多个类引用（如 HomeUI 也会用到该 tag 字符串），
     * 因此必须对 DexKit 候选做结构验证，过滤掉 Activity 等无关类，否则会 hook 到错误的类上。
     */
    private fun resolvePlusHelperClass(classLoader: ClassLoader, context: Context?): Class<*>? {
        if (context != null) {
            val candidates = DexKitSupport.findClassByStringsAll(
                context,
                classLoader,
                ModulePathHolder.modulePath,
                "MicroMsg.PlusSubMenuHelper"
            )
            for (c in candidates) {
                if (isPlausiblePlusHelper(c)) {
                    xlog("plus helper resolved via DexKit: ${c.name}")
                    return c
                }
            }
            if (candidates.isNotEmpty()) {
                xlog("plus helper DexKit candidates rejected: " +
                    candidates.joinToString { it.name })
            }
        }
        return ReflectCompat.findClass("com.tencent.mm.ui.rg", classLoader)
    }

    /**
     * 结构验证：PlusSubMenuHelper 形状的工具类（非 Activity，含 SparseArray + BaseAdapter + LayoutInflater 字段）。
     * 用于排除 DexKit 因 xlog tag 字符串误匹配到的 Activity 类（如 HomeUI/LauncherUI）。
     */
    private fun isPlausiblePlusHelper(c: Class<*>): Boolean {
        if (Activity::class.java.isAssignableFrom(c)) return false
        if (c.isInterface || c.isAnnotation || c.isEnum) return false
        var hasSparseArray = false
        var hasBaseAdapter = false
        var hasInflater = false
        var c2: Class<*>? = c
        while (c2 != null) {
            for (f in c2.declaredFields) {
                val t = f.type
                if (android.util.SparseArray::class.java.isAssignableFrom(t)) hasSparseArray = true
                if (android.widget.BaseAdapter::class.java.isAssignableFrom(t)) hasBaseAdapter = true
                if (android.view.LayoutInflater::class.java.isAssignableFrom(t)) hasInflater = true
            }
            c2 = c2.superclass
        }
        return hasSparseArray && hasBaseAdapter && hasInflater
    }

    /** 在加号帮助类上结构化找到返回 BaseAdapter 子类且无参的工厂方法，不依赖混淆方法名/类名。 */
    private fun resolveAdapterClass(plusClass: Class<*>, classLoader: ClassLoader): Class<*>? {
        ReflectCompat.hierarchy(plusClass)
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { m ->
                m.parameterTypes.isEmpty() &&
                    android.widget.BaseAdapter::class.java.isAssignableFrom(m.returnType) &&
                    m.returnType != android.widget.BaseAdapter::class.java
            }?.returnType?.let { return it }
        return ReflectCompat.findClass("com.tencent.mm.ui.mg", classLoader)
    }

    private fun hookHomePlusMenu(classLoader: ClassLoader, context: Context? = null): Int {
        val plus = resolvePlusHelperClass(classLoader, context) ?: run {
            xlog("plus helper class not resolved (DexKit + fallback both failed)")
            return 0
        }
        xlog("plus helper class resolved=${plus.name}")
        var hooked = 0
        // 菜单构建方法：无参 + 返回 boolean（原 8.0.72 下为 d()）。按签名匹配，不写死方法名。
        ReflectCompat.hierarchy(plus)
            .flatMap { it.declaredMethods.asSequence() }
            .filter { m ->
                m.parameterTypes.isEmpty() &&
                    (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == Void.TYPE)
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!plus.isInstance(param.thisObject)) return
                            injectPlusMenuItem(param.thisObject, classLoader)
                        }
                    })
                    hooked++
                    xlog("plus menu display hooked ${method.declaringClass.name}.${method.name}")
                }.onFailure { xlog("plus display hook fail ${method.declaringClass.name}.${method.name}: ${it.message}") }
            }

        // adapter 工厂方法：无参 + 返回 BaseAdapter 子类（原为 b()）。
        ReflectCompat.hierarchy(plus)
            .flatMap { it.declaredMethods.asSequence() }
            .filter { m ->
                m.parameterTypes.isEmpty() &&
                    android.widget.BaseAdapter::class.java.isAssignableFrom(m.returnType)
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!plus.isInstance(param.thisObject)) return
                            injectPlusMenuItem(param.thisObject, classLoader)
                        }
                    })
                    hooked++
                    xlog("plus adapter factory hooked ${method.declaringClass.name}.${method.name}")
                }.onFailure { xlog("plus adapter factory hook fail: ${it.message}") }
            }

        for (m in plus.declaredMethods) {
            if (m.name != "onItemClick" || m.parameterTypes.size != 4) continue
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isOurPlusMenuClick(param.thisObject, param.args.getOrNull(2))) return
                        val ctx = plusContext(param.thisObject) ?: return
                        openSettings(ctx)
                        param.result = null
                    }
                })
                hooked++
            }.onFailure { xlog("plus click hook fail: ${it.message}") }
        }
        if (hooked > 0) xlog("home plus menu hooked count=$hooked")
        hooked += hookPlusMenuAdapter(classLoader, plus)
        // cn76+ 风格：数据管理器(PlusMenaDataManager)持有真正的渲染菜单 map，
        // 直接 hook 其重建方法作为额外注入点，覆盖 rg.s 未赋值/不同混淆批次的场景。
        hooked += hookPlusDataManager(classLoader, context, plus)
        return if (hooked > 0) 1 else 0
    }

    /**
     * 数据管理器路径（cn76 风格）：rg.v = gg.h(PlusMenaDataManager)，菜单渲染 map 在管理器内部。
     * hook 管理器的重建方法（一参 boolean + void 返回，例如 gg.a(boolean)），重建后把 OKK 行注入渲染 map。
     */
    private fun hookPlusDataManager(classLoader: ClassLoader, context: Context?, plus: Class<*>): Int {
        val mgr = resolveDataManagerClass(classLoader, context, plus) ?: return 0
        xlog("plus data manager resolved=${mgr.name}")
        var hooked = 0
        ReflectCompat.hierarchy(mgr)
            .flatMap { it.declaredMethods.asSequence() }
            .filter { m ->
                m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    m.returnType == Void.TYPE
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            injectIntoMap(param.thisObject, classLoader)
                        }
                    })
                    hooked++
                    xlog("plus data manager rebuild hooked ${method.declaringClass.name}.${method.name}")
                }.onFailure { xlog("plus data manager hook fail ${method.declaringClass.name}.${method.name}: ${it.message}") }
            }
        if (hooked > 0) xlog("plus data manager hooked count=$hooked")
        return hooked
    }

    /** 解析数据管理器：优先 DexKit 锚点 MicroMsg.PlusMenaDataManager，回退到 plus 实例中“含 SparseArray 字段”的字段类型。 */
    private fun resolveDataManagerClass(classLoader: ClassLoader, context: Context?, plus: Class<*>): Class<*>? {
        if (context != null) {
            runCatching {
                DexKitSupport.findClassByStringsAll(
                    context,
                    classLoader,
                    ModulePathHolder.modulePath,
                    "MicroMsg.PlusMenaDataManager"
                ).firstOrNull { c ->
                    !Activity::class.java.isAssignableFrom(c) &&
                        !c.isInterface &&
                        c.declaredFields.any { android.util.SparseArray::class.java.isAssignableFrom(it.type) } &&
                        c.declaredFields.any { it.type == java.util.ArrayList::class.java }
                }?.let { return it }
            }
        }
        // 回退：plus 实例字段中类型含 SparseArray 字段的（即 rg.v -> gg）
        return ReflectCompat.hierarchy(plus)
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { f ->
                !f.type.isPrimitive &&
                    f.type != String::class.java &&
                    f.type.declaredFields.any { android.util.SparseArray::class.java.isAssignableFrom(it.type) }
            }?.type
    }

    private fun hookPlusMenuAdapter(classLoader: ClassLoader, plusClass: Class<*>): Int {
        val adapter = resolveAdapterClass(plusClass, classLoader) ?: run {
            xlog("plus adapter class not resolved")
            return 0
        }
        xlog("plus adapter class resolved=${adapter.name}")
        var hooked = 0
        fun helperOf(adapterObj: Any): Any? = runCatching {
            findField(adapterObj.javaClass) { plusClass.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?.get(adapterObj)
        }.getOrNull()
        // getCount / getView 是 Android SDK 接口方法，不会被混淆；
        // 但它们可能声明在父类，findAndHookMethod 只查 declared 会失败，因此逐层查找。
        ReflectCompat.hierarchy(adapter)
            .flatMap { it.declaredMethods.asSequence() }
            .filter { m -> m.name == "getCount" && m.parameterTypes.isEmpty() }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            helperOf(param.thisObject)?.let { injectPlusMenuItem(it, classLoader) }
                        }
                    })
                    hooked++
                    xlog("plus adapter count hooked ${method.declaringClass.name}")
                }.onFailure { xlog("plus adapter count hook fail: ${it.message}") }
            }
        ReflectCompat.hierarchy(adapter)
            .flatMap { it.declaredMethods.asSequence() }
            .filter { m ->
                m.name == "getView" &&
                    m.parameterTypes.size == 3 &&
                    View::class.java.isAssignableFrom(m.returnType)
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            helperOf(param.thisObject)?.let { injectPlusMenuItem(it, classLoader) }
                        }
                    })
                    hooked++
                    xlog("plus adapter view hooked ${method.declaringClass.name}")
                }.onFailure { xlog("plus adapter view hook fail: ${it.message}") }
            }
        if (hooked > 0) xlog("home plus adapter hooked count=$hooked")
        return hooked
    }

    private fun injectPlusMenuItem(helper: Any, classLoader: ClassLoader) {
        injectIntoMap(helper, classLoader)
    }

    /** 对任意持有渲染菜单 map 的对象执行注入（rg 实例或数据管理器实例均可）。 */
    private val mapNotFoundLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun injectIntoMap(holder: Any, classLoader: ClassLoader) {
        runCatching {
            val map = plusMenuMap(holder) ?: run {
                // 高频路径（getCount/getView 每次渲染都会来），只打一次避免刷屏。
                if (mapNotFoundLogged.compareAndSet(false, true)) {
                    xlog("plus menu map not found on ${holder.javaClass.name} (rg.s 未赋值且无数据管理器渲染 map)")
                }
                return
            }
            for (i in 0 until map.size()) {
                val row = map.valueAt(i) ?: continue
                val pg = plusMenuPayload(row) ?: continue
                val id = plusMenuPayloadId(pg)
                if (id == PLUS_MENU_ID) return
            }
            if (map.size() == 0) {
                // 菜单尚未构建，等下一次重建触发时再试。
                xlog("plus menu map empty, skip inject (retry on next rebuild)")
                return
            }

            // 从已有行对象上反射推导真实 og/pg 类，不再硬编码类名。
            val sampleRow = map.valueAt(0) ?: return
            val samplePayload = plusMenuPayload(sampleRow) ?: return
            val pgClass = samplePayload.javaClass
            val ogClass = sampleRow.javaClass

            val pgCtor = pgClass.constructors
                .filter { it.parameterTypes.size in 4..6 }
                .maxByOrNull { it.parameterTypes.size }
            if (pgCtor == null) {
                xlog("pg ctor not found on ${pgClass.name}")
                return
            }
            pgCtor.isAccessible = true
            val pg = pgCtor.newInstance(*buildPgArgs(pgCtor.parameterTypes))

            val ogCtor = ogClass.constructors.firstOrNull { it.parameterTypes.size == 1 }
            if (ogCtor == null) {
                xlog("og ctor not found on ${ogClass.name}")
                return
            }
            ogCtor.isAccessible = true
            val row = ogCtor.newInstance(pg)

            val key = nextSparseKey(map)
            map.put(key, row)
            notifyPlusAdapter(holder)
            xlog("plus menu item injected key=$key size=${map.size()} pg=${pgClass.name} og=${ogClass.name} holder=${holder.javaClass.name}")
        }.onFailure { xlog("plus item inject fail: ${it.message}") }
    }

    /** pg 构造函数参数顺序基本稳定：(id, title, subtitle, icon, color[, tag])。按位置+类型填充，不假设参数个数固定为 5。 */
    private fun buildPgArgs(paramTypes: Array<Class<*>>): Array<Any?> =
        paramTypes.mapIndexed { idx, t ->
            when {
                idx == 0 -> PLUS_MENU_ID
                t == String::class.java && idx == 1 -> "OKK"
                t == String::class.java -> ""
                t == Int::class.javaPrimitiveType || t == Int::class.java ->
                    if (idx == 3) android.R.drawable.ic_menu_manage else 0
                else -> null
            }
        }.toTypedArray()

    /** 通知菜单 adapter 刷新：先找已持有的 BaseAdapter 字段，再退回无参工厂方法。不写死方法名 b()。 */
    private fun notifyPlusAdapter(helper: Any) {
        val adapter = runCatching {
            findField(helper.javaClass) { android.widget.BaseAdapter::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?.get(helper)
        }.getOrNull() ?: runCatching {
            ReflectCompat.hierarchy(helper.javaClass)
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { m ->
                    m.parameterTypes.isEmpty() &&
                        android.widget.BaseAdapter::class.java.isAssignableFrom(m.returnType)
                }?.apply { isAccessible = true }?.invoke(helper)
        }.getOrNull() ?: return
        runCatching { XposedHelpers.callMethod(adapter, "notifyDataSetChanged") }
    }

    private fun nextSparseKey(map: android.util.SparseArray<*>): Int {
        var key = 0
        while (map.get(key) != null) key++
        return key
    }

    private fun isOurPlusMenuClick(helper: Any, positionArg: Any?): Boolean {
        return runCatching {
            val pos = (positionArg as? Number)?.toInt() ?: return@runCatching false
            val map = plusMenuMap(helper) ?: return@runCatching false
            val row = map.get(pos) ?: return@runCatching false
            plusMenuPayload(row)?.let { plusMenuPayloadId(it) } == PLUS_MENU_ID
        }.getOrDefault(false)
    }

    private fun plusContext(helper: Any): Context? {
        return runCatching {
            findField(helper.javaClass) { Context::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
                ?.get(helper) as? Context
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun plusMenuMap(helper: Any): android.util.SparseArray<Any>? {
        // 1) 直接持有的 SparseArray（play72 风格：rg.s 就是菜单 map）
        val direct = findField(helper.javaClass) { android.util.SparseArray::class.java.isAssignableFrom(it.type) }
            ?.let { f ->
                runCatching {
                    f.isAccessible = true
                    f.get(helper) as? android.util.SparseArray<Any>
                }.getOrNull()
            }
        if (direct != null && direct.size() > 0) return direct

        // 2) 数据管理器字段（cn76 风格：rg.v = gg.h(PlusMenaDataManager)，
        //    真正的渲染菜单 map 在管理器内部的 key 连续 SparseArray 字段，例如 gg.e）
        return runCatching {
            for (c in ReflectCompat.hierarchy(helper.javaClass)) {
                for (f in c.declaredFields) {
                    if (f.type.isPrimitive || f.type == String::class.java) continue
                    if (android.util.SparseArray::class.java.isAssignableFrom(f.type)) continue
                    if (f.type.declaredFields.none { android.util.SparseArray::class.java.isAssignableFrom(it.type) }) continue
                    f.isAccessible = true
                    val mgr = f.get(helper) ?: continue
                    findRenderArray(mgr)?.let { return@runCatching it }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * 在数据管理器对象内找"渲染菜单 map"：key 从 0 连续（0..n-1）且值为非基本类型的 SparseArray。
     * 这样可区分 cn76 gg 类里的配置 map（c，key 为菜单 id 不连续）与渲染 map（e，key 连续）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun findRenderArray(mgr: Any): android.util.SparseArray<Any>? {
        for (c in ReflectCompat.hierarchy(mgr.javaClass)) {
            for (f in c.declaredFields) {
                if (!android.util.SparseArray::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                val arr = runCatching { f.get(mgr) as? android.util.SparseArray<Any> }.getOrNull() ?: continue
                if (arr.size() == 0) continue
                var contiguous = true
                for (i in 0 until arr.size()) {
                    if (arr.keyAt(i) != i) {
                        contiguous = false
                        break
                    }
                }
                if (!contiguous) continue
                val v0 = arr.valueAt(0)
                if (v0 != null && !v0.javaClass.isPrimitive && v0.javaClass != String::class.java) {
                    return arr
                }
            }
        }
        return null
    }

    /** og 结构只有 boolean + pg 引用两个字段；取第一个非基本类型字段即为 payload，不依赖硬编码类名。 */
    private fun plusMenuPayload(row: Any): Any? {
        return findField(row.javaClass) { f ->
            val t = f.type
            !t.isPrimitive && t != String::class.java && t != CharSequence::class.java && !t.isArray
        }?.let { field ->
            runCatching {
                field.isAccessible = true
                field.get(row)
            }.getOrNull()
        }
    }

    /**
     * pg 的 id 字段名与字段顺序都会随混淆漂移，不能假设“第三个 int”。
     * 策略：构造一个带哨兵值的 pg 实例，反推哪个 int 字段承载构造函数第一个参数，结果缓存。
     */
    @Volatile
    private var payloadIdField: java.lang.reflect.Field? = null

    private fun resolvePayloadIdField(pgClass: Class<*>): java.lang.reflect.Field? {
        payloadIdField?.let { if (it.declaringClass.isAssignableFrom(pgClass)) return it }
        val sentinel = 0x5A3C7E11
        val probe = runCatching {
            val ctor = pgClass.constructors
                .filter { it.parameterTypes.size in 4..6 }
                .maxByOrNull { it.parameterTypes.size } ?: return null
            ctor.isAccessible = true
            val args = buildPgArgs(ctor.parameterTypes).also { it[0] = sentinel }
            ctor.newInstance(*args)
        }.getOrNull() ?: return null

        val field = ReflectCompat.hierarchy(pgClass)
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.type == Int::class.javaPrimitiveType || it.type == Int::class.java }
            .firstOrNull { f ->
                runCatching {
                    f.isAccessible = true
                    f.getInt(probe) == sentinel
                }.getOrDefault(false)
            }
        if (field != null) {
            payloadIdField = field
            xlog("pg id field resolved=${field.declaringClass.simpleName}.${field.name}")
        }
        return field
    }

    private fun plusMenuPayloadId(payload: Any): Int {
        resolvePayloadIdField(payload.javaClass)?.let { field ->
            runCatching {
                field.isAccessible = true
                return field.getInt(payload)
            }
        }
        // 兵底：只看是否存在等于我们自己 id 的 int 字段（仅用于去重判重）。
        val hit = ReflectCompat.hierarchy(payload.javaClass)
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.type == Int::class.javaPrimitiveType || it.type == Int::class.java }
            .any { f ->
                runCatching {
                    f.isAccessible = true
                    f.getInt(payload) == PLUS_MENU_ID
                }.getOrDefault(false)
            }
        return if (hit) PLUS_MENU_ID else Int.MIN_VALUE
    }

    private fun findField(clazz: Class<*>, accept: (java.lang.reflect.Field) -> Boolean): java.lang.reflect.Field? {
        for (c in ReflectCompat.hierarchy(clazz)) {
            c.declaredFields.firstOrNull(accept)?.let { return it }
        }
        return null
    }

    // ── legacy Preference ──────────────────────────────────────────────────

    private fun hookLegacySettings(classLoader: ClassLoader): Int {
        // 设置页入口已迁到首页右上角加号菜单。旧版 Preference 注入在当前微信会生成空白占位，
        // 因此这里彻底禁用，不再向任何微信设置页插入 item。
        xlog("legacy settings list injection disabled")
        return 0

        val candidates = listOf(
            WeChatClassNames.LEGACY_SETTINGS_UI,
            SettingsEntryPolicy.legacySettingsClass
        )
        var total = 0
        for (name in candidates.distinct()) {
            val clazz = ReflectCompat.findClass(name, classLoader) ?: continue
            val ok = runCatching {
                val after = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val a = param.thisObject as? Activity ?: return
                        injectLegacy(a, classLoader)
                    }
                }
                runCatching { XposedHelpers.findAndHookMethod(clazz, "initView", after) }
                runCatching { XposedHelpers.findAndHookMethod(clazz, "onResume", after) }
                // Preference 点击：尽量找到 onPreferenceTreeClick
                for (m in clazz.declaredMethods) {
                    if (!m.name.contains("Preference", ignoreCase = true) &&
                        m.name != "onPreferenceTreeClick"
                    ) continue
                    if (m.parameterTypes.size < 2) continue
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val p = param.args.getOrNull(1) ?: return
                            val key = preferenceKey(p)
                            if (key != SettingsEntryPolicy.entryKey) return
                            openSettings(param.thisObject as? Activity ?: return)
                            param.result = true
                        }
                    })
                }
                xlog("legacy/settings UI hooked: $name")
                true
            }.getOrDefault(false)
            if (ok) total++
        }
        return total
    }

    private fun preferenceKey(p: Any): String? {
        ReflectCompat.callFirst(p, "j", "getKey")?.toString()?.let { return it }
        for (f in p.javaClass.declaredFields) {
            if (f.type != String::class.java) continue
            runCatching {
                f.isAccessible = true
                val v = f.get(p) as? String
                if (v == SettingsEntryPolicy.entryKey) return v
            }
        }
        return null
    }

    private fun injectLegacy(activity: Activity, classLoader: ClassLoader) {
        runCatching {
            if (!isAllowedSettingsHost(activity)) {
                xlog("skip legacy host=${activity.javaClass.name}")
                return
            }
            if (activity.javaClass.name.contains("setting_new")) {
                xlog("skip legacy on modern host=${activity.javaClass.name}")
                return
            }
            val adapter = runCatching { XposedHelpers.callMethod(activity, "getPreferenceScreen") }.getOrNull()
                ?: activity.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(activity) ?: return@runCatching null
                        // Preference adapter-ish: has method taking String key
                        if (v.javaClass.methods.any { it.name == "i" || it.name == "findPreference" }) v
                        else null
                    }.getOrNull()
                }
                ?: return

            val exists = runCatching {
                XposedHelpers.callMethod(adapter, "i", SettingsEntryPolicy.entryKey)
            }.getOrNull() != null || runCatching {
                XposedHelpers.callMethod(adapter, "findPreference", SettingsEntryPolicy.entryKey)
            }.getOrNull() != null
            if (exists) return

            val prefClass = ReflectCompat.findClassAny(
                classLoader,
                WeChatClassNames.ICON_PREFERENCE,
                WeChatClassNames.PREFERENCE,
                "com.tencent.weui.base.preference.IconPreference",
                "com.tencent.weui.base.preference.Preference"
            ) ?: return

            val preference = prefClass.getConstructor(Context::class.java).newInstance(activity)
            // setKey / setTitle 方法名多变
            invokeStringSetter(preference, SettingsEntryPolicy.entryKey, "C", "setKey", "D")
            invokeStringSetter(preference, SettingsEntryPolicy.entryTitle, "K", "setTitle", "L", "H")
            runCatching { XposedHelpers.callMethod(adapter, "d", preference, 0) }
            runCatching { XposedHelpers.callMethod(adapter, "addPreference", preference) }
            runCatching { XposedHelpers.callMethod(adapter, "notifyDataSetChanged") }
            xlog("legacy pref inserted on ${activity.javaClass.simpleName}")
        }.onFailure {
            xlog("legacy inject: ${it.message}")
        }
    }

    private fun invokeStringSetter(target: Any, value: String, vararg names: String) {
        for (name in names) {
            val m = ReflectCompat.findDeclaredMethod(target.javaClass, name, paramCount = 1) ?: continue
            if (m.parameterTypes[0] != String::class.java &&
                m.parameterTypes[0] != CharSequence::class.java
            ) continue
            runCatching {
                m.isAccessible = true
                m.invoke(target, value)
                return
            }
        }
        // 扫所有 (String)->void
        for (c in ReflectCompat.hierarchy(target.javaClass)) {
            for (m in c.declaredMethods) {
                if (m.parameterTypes.size != 1) continue
                if (m.parameterTypes[0] != String::class.java) continue
                if (m.returnType != Void.TYPE) continue
                runCatching {
                    m.isAccessible = true
                    m.invoke(target, value)
                    return
                }
            }
        }
    }

    private fun openSettings(context: Context) {
        val activity = context as? Activity
            ?: (context as? android.content.ContextWrapper)?.baseContext as? Activity
        if (activity != null && !activity.isFinishing) {
            val opened = runCatching { OKKSettingsDialog.show(activity) }.isSuccess
            if (opened) {
                xlog("compose settings shown")
            } else {
                // 新 UI 打开失败时回退旧嵌入式 UI
                runCatching { EmbeddedSettingsUi.show(activity) }.onFailure {
                    Toast.makeText(context, "打开失败: ${it.message}", Toast.LENGTH_SHORT).show()
                    xlog("open fail: ${it.message}")
                }
            }
            return
        }
        Toast.makeText(context, "无法打开设置", Toast.LENGTH_SHORT).show()
    }

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
