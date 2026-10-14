plugins {
    id("com.android.application") version "9.3.0" apply false
    id("com.android.library") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}

// ── 正式签名密钥是否就绪（单一事实来源）──
// 发布者通过以下四个环境变量提供正式签名信息；未提供即视为“开发版构建”。
//  - :app    据此决定 release 签名（无密钥时降级 debug 签名，保证 APK 可安装）
//  - :loader 据此决定是否放行 LegitCheck 反重签自校验（保证开发版功能可用）
extra["okkReleaseSigningReady"] = listOf(
    "OKK_STORE_FILE",
    "OKK_STORE_PASSWORD",
    "OKK_KEY_ALIAS",
    "OKK_KEY_PASSWORD"
).all { !System.getenv(it).isNullOrBlank() }
