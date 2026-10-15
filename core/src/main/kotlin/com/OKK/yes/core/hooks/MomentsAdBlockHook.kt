package com.OKK.yes.core.hooks

import android.content.Context
import android.util.Log
import com.OKK.yes.core.compat.DexKitSupport
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 去除朋友圈广告（五层立体防御体系）。
 *
 * 1. ADInfo(String) 构造拦截：置 null 跳过 XML 广告属性解析（WeKit / Hchat 同源核心实现）；
 * 2. ADXml(String) 构造拦截：置 null 阻止富媒体卡片 XML 解析；
 * 3. AdSnsInfo.convertToSnsInfo() 拦截：置 null 阻止广告数据库行转换为朋友圈信息节点；
 * 4. SnsInfo.isAd() 方法门禁：置 false，保证任何视图渲染均不走广告展示分支；
 * 5. SnsSqliteDB rawQuery SQL 拦截：改写含 AdSnsInfo 的查询（插入 1=0 屏蔽条件），从数据库源头抹除已缓存的广告记录。
 *
 * 配置项：`remove_moments_ads`
 */
object MomentsAdBlockHook {
    private const val TAG = "OKK-MomentsAd"
    private const val KEY = "remove_moments_ads"

    private const val CLS_AD_INFO = "com.tencent.mm.plugin.sns.storage.ADInfo"
    private const val CLS_AD_XML = "com.tencent.mm.plugin.sns.storage.ADXml"
    private const val CLS_AD_SNS_INFO = "com.tencent.mm.plugin.sns.storage.AdSnsInfo"
    private const val CLS_SNS_INFO = "com.tencent.mm.plugin.sns.storage.SnsInfo"

    private val installed = AtomicBoolean(false)
    private val blockedCount = AtomicInteger(0)
    private val hookedSqlMethods = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var adInfoHookCount = 0
    @Volatile private var adXmlHookCount = 0
    @Volatile private var isAdHookInstalled = false

    /** 深度自检只读访问器。 */
    val isFullyArmed: Boolean
        get() = adInfoHookCount > 0 && adXmlHookCount > 0 && isAdHookInstalled
    val armedDetail: String
        get() = "ADInfo=$adInfoHookCount ADXml=$adXmlHookCount isAd=$isAdHookInstalled SQL=${hookedSqlMethods.size}"

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install start, enabled=${isEnabled()}")

        // 1. 挂钩 ADInfo(String) 与全参构造
        hookAdInfo(classLoader)

        // 2. 挂钩 ADXml(String) 富媒体卡片构造
        hookAdXml(classLoader)

        // 3. 挂钩 AdSnsInfo 转换与 SnsInfo.isAd() 门禁
        hookSnsInfoAdCheck(classLoader)

        // 4. 挂钩朋友圈 SnsSqliteDB rawQuery，从源头改写 SQL 过滤本地已缓存广告
        hookSnsDatabase(context, classLoader, modulePath)

        xlog("installed completely")
    }

    /** 1. Hook ADInfo 构造函数 */
    private fun hookAdInfo(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(CLS_AD_INFO, classLoader)
            var count = 0
            for (ctor in clazz.declaredConstructors) {
                val params = ctor.parameterTypes
                if (params.size == 1 && params[0] == String::class.java) {
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            // 构造前置 null 阻止 feed(xml) 解析广告数据
                            param.result = null
                            val c = blockedCount.incrementAndGet()
                            runCatching {
                                com.OKK.yes.core.startup.RuntimeHealthAudit.noteActivity(
                                    "MomentsAdBlock",
                                    "已拦截广告对象"
                                )
                            }
                            if (c <= 5 || c % 50 == 0) {
                                xlog("blocked ADInfo(String) ctor #$c")
                            }
                        }
                    })
                    count++
                }
            }
            adInfoHookCount = count
            xlog("hooked ADInfo ctors: $count")
        }.onFailure {
            xlog("hook ADInfo fail: ${it.message}")
        }
    }

    /** 2. Hook ADXml 富媒体构造函数 */
    private fun hookAdXml(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass(CLS_AD_XML, classLoader)
            var count = 0
            for (ctor in clazz.declaredConstructors) {
                val params = ctor.parameterTypes
                if (params.size == 1 && params[0] == String::class.java) {
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled()) return
                            param.result = null
                            blockedCount.incrementAndGet()
                        }
                    })
                    count++
                }
            }
            adXmlHookCount = count
            xlog("hooked ADXml ctors: $count")
        }.onFailure {
            xlog("hook ADXml fail: ${it.message}")
        }
    }

    /** 3. Hook AdSnsInfo.convertToSnsInfo 与 SnsInfo.isAd */
    private fun hookSnsInfoAdCheck(classLoader: ClassLoader) {
        // AdSnsInfo.convertToSnsInfo()
        runCatching {
            val clazz = XposedHelpers.findClass(CLS_AD_SNS_INFO, classLoader)
            val convertMethod = clazz.declaredMethods.firstOrNull {
                it.name == "convertToSnsInfo" && it.parameterCount == 0
            }
            if (convertMethod != null) {
                XposedBridge.hookMethod(convertMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        param.result = null
                    }
                })
                xlog("hooked AdSnsInfo.convertToSnsInfo()")
            }
        }.onFailure {
            xlog("hook AdSnsInfo convert fail: ${it.message}")
        }

        // SnsInfo.isAd()
        runCatching {
            val clazz = XposedHelpers.findClass(CLS_SNS_INFO, classLoader)
            val isAdMethod = clazz.declaredMethods.firstOrNull {
                it.name == "isAd" && it.parameterCount == 0 &&
                    (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == java.lang.Boolean.TYPE)
            }
            if (isAdMethod != null) {
                XposedBridge.hookMethod(isAdMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        // 强制返回 false，切断朋友圈 UI 中一切广告渲染路径
                        param.result = false
                    }
                })
                xlog("hooked SnsInfo.isAd() -> false")
                isAdHookInstalled = true
            }
        }.onFailure {
            xlog("hook SnsInfo.isAd fail: ${it.message}")
        }
    }

    /** 4. Hook 朋友圈 SnsSqliteDB rawQuery */
    private fun hookSnsDatabase(context: Context, classLoader: ClassLoader, modulePath: String?) {
        // 4.1 DexKit 查找 SnsSqliteDB 的 rawQuery 方法
        runCatching {
            val methods = DexKitSupport.findMethodsByStrings(
                context, classLoader, modulePath,
                "com.tencent.mm.plugin.sns.storage.SnsSqliteDB", "rawQuery"
            )
            for (method in methods) {
                hookDatabaseMethod(method, "DexKit")
            }
        }.onFailure { xlog("DexKit SnsSqliteDB search fail: ${it.message}") }

        // 4.2 静态扫描常见 Sns DB 类
        val fallbackClasses = listOf(
            "com.tencent.mm.plugin.sns.storage.n2",
            "com.tencent.mm.plugin.sns.storage.m2",
            "com.tencent.mm.plugin.sns.storage.o2",
            "com.tencent.mm.plugin.sns.storage.p2",
            "com.tencent.mm.plugin.sns.storage.q2",
            "com.tencent.mm.plugin.sns.storage.SnsSqliteDB"
        )
        for (clsName in fallbackClasses) {
            val clazz = runCatching { XposedHelpers.findClass(clsName, classLoader) }.getOrNull() ?: continue
            for (method in clazz.declaredMethods) {
                val params = method.parameterTypes
                if (params.isNotEmpty() && params[0] == String::class.java &&
                    (method.returnType.name.contains("Cursor") || method.returnType == Any::class.java)
                ) {
                    hookDatabaseMethod(method, "fallback")
                }
            }
        }
    }

    private fun hookDatabaseMethod(method: Method, source: String) {
        val strIdx = method.parameterTypes.indexOfFirst { it == String::class.java }
        if (strIdx < 0) return
        val key = "${method.declaringClass.name}#${method.name}_${method.parameterTypes.size}"
        if (!hookedSqlMethods.add(key)) return

        runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val sql = param.args.getOrNull(strIdx) as? String ?: return
                    val rewritten = MomentsAdBlockLogic.rewriteSql(sql) ?: return
                    param.args[strIdx] = rewritten
                    val c = blockedCount.incrementAndGet()
                    runCatching {
                        com.OKK.yes.core.startup.RuntimeHealthAudit.noteActivity(
                            "MomentsAdBlock",
                            "已从本地数据库屏蔽广告行"
                        )
                    }
                    if (c <= 5 || c % 50 == 0) {
                        xlog("rewrote AdSnsInfo SQL #$c via $source ($key)")
                    }
                }
            })
            xlog("hooked Sns DB query method: $key ($source)")
        }.onFailure {
            xlog("hook Sns DB method $key fail: ${it.message}")
        }
    }

    fun isEnabled(): Boolean =
        runCatching { PublicConfigStore.getBoolean(KEY, false) }.getOrDefault(false)

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}
