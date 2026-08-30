package com.OKK.yes.core.compat

import org.luckypray.dexkit.DexKitBridge

/** 单功能适配探针结果 */
enum class ProbeLevel {
    /** 关键点齐全，可安装 */
    OK,
    /** 主路径可用、次要缺失 */
    PARTIAL,
    /** 不适配，不安装 */
    FAIL
}

data class ProbeResult(
    val id: String,
    val title: String,
    val level: ProbeLevel,
    val detail: String = ""
) {
    val installable: Boolean get() = level != ProbeLevel.FAIL
}

data class ProbeContext(
    val classLoader: ClassLoader,
    val bridge: DexKitBridge?,
    val appContext: android.content.Context,
    val modulePath: String?
) {
    fun classExists(name: String): Boolean =
        ReflectCompat.findClass(name, classLoader) != null

    fun anyClass(vararg names: String): Boolean =
        names.any { classExists(it) }

    fun methodByStrings(vararg strings: String): Boolean {
        val b = bridge ?: return false
        return runCatching {
            b.findMethod {
                matcher {
                    usingStrings(*strings)
                }
            }.isNotEmpty()
        }.getOrDefault(false) || runCatching {
            b.findMethod {
                matcher {
                    usingEqStrings(*strings)
                }
            }.isNotEmpty()
        }.getOrDefault(false)
    }

    fun classByStrings(vararg strings: String): Boolean {
        val b = bridge ?: return false
        return DexKitSupport.findClassByUsingStrings(b, classLoader, *strings) != null
    }
}

fun interface FeatureProbe {
    fun probe(ctx: ProbeContext): ProbeResult
}

data class CompatReport(
    val fingerprint: String,
    val wechatSummary: String,
    val atMs: Long,
    val results: List<ProbeResult>,
    /** 是否因首次/换版本需要弹窗 */
    val shouldShowDialog: Boolean
) {
    val okCount get() = results.count { it.level == ProbeLevel.OK }
    val partialCount get() = results.count { it.level == ProbeLevel.PARTIAL }
    val failCount get() = results.count { it.level == ProbeLevel.FAIL }

    fun byId(id: String): ProbeResult? = results.firstOrNull { it.id == id }

    fun summaryLine(): String =
        "ok=$okCount partial=$partialCount fail=$failCount total=${results.size}"

    fun failTitles(): List<String> =
        results.filter { it.level == ProbeLevel.FAIL }.map { it.title }
}
