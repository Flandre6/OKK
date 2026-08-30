package com.OKK.yes.core.common

import android.util.Base64

/**
 * 字符串混淆工具：把关键明文字符串（微信类名 / 配置文件路径 / 特征）编译期编码，
 * 运行时解码。使得 `strings` / jadx 直接读不到明文，抬高逆向分析门槛。
 *
 * 编码规则（与生成脚本一致）：
 *   encoded = base64( reverse( byte ^ KEY[i%3] ) )
 *   KEY = [0x5A, 0xB3, 0x2C]
 *
 * 注意：
 *  - LSPosed 入口类名（HookEntry 等写在 assets/xposed_init，meta-data 声明）严禁加密。
 *  - 解码结果按字符串缓存，避免热路径重复计算。
 */
object SecureStrings {
    private val KEY = byteArrayOf(0x5A.toByte(), 0xB3.toByte(), 0x2C.toByte())
    private val cache = HashMap<String, String>()

    /** 解码；缓存同 key 的结果。 */
    fun d(encoded: String): String {
        cache[encoded]?.let { return it }
        val decoded = decode(encoded)
        synchronized(cache) { cache[encoded] = decoded }
        return decoded
    }

    private fun decode(encoded: String): String {
        val b = runCatching {
            Base64.decode(encoded, Base64.NO_WRAP)
        }.getOrNull() ?: return encoded
        // 逆序
        var i = 0
        var j = b.size - 1
        while (i < j) {
            val t = b[i]; b[i] = b[j]; b[j] = t
            i++; j--
        }
        // 异或
        for (k in b.indices) {
            b[k] = (b[k].toInt() xor (KEY[k % KEY.size].toInt() and 0xFF)).toByte()
        }
        return String(b, Charsets.UTF_8)
    }
}
