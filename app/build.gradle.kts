plugins {
    id("com.android.application")
}

// 正式签名信息来自环境变量；是否就绪由根工程统一判定（:loader 的开发版自校验放行也依赖同一判定）。
val hasReleaseSigning = rootProject.extra["okkReleaseSigningReady"] as Boolean
val releaseStoreFilePath = System.getenv("OKK_STORE_FILE")
val releaseStorePassword = System.getenv("OKK_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("OKK_KEY_ALIAS")
val releaseKeyPassword = System.getenv("OKK_KEY_PASSWORD")

android {
    namespace = "com.OKK.yes"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.OKK.yes"
        minSdk = 26
        targetSdk = 37
        versionCode = 19
        versionName = "1.2.7"
        // 真机主流 arm64；去掉 x86 模拟器 so 减小体积
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    signingConfigs {
        // 正式签名不入库；发布者通过环境变量提供本地签名信息。
        create("releaseSign") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        // 日常开发：使用 Android 默认 debug 签名，不混淆，方便 logcat
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        // 发版 / 打包：必须压缩 + 混淆 + 资源裁剪；
        // 若未配置正式签名环境变量（如 Fork 用户或 Agent 自动化构建），自动降级使用 debug 签名，确保 APK 在真机上可直接安装调试，避免产出 unsigned APK 无法安装。
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseSign")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        jniLibs {
            // 只打进 defaultConfig 过滤后的 ABI
            useLegacyPackaging = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation(project(":loader"))
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
}
