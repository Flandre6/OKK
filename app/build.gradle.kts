plugins {
    id("com.android.application")
}

val releaseStoreFilePath = System.getenv("OKK_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = System.getenv("OKK_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = System.getenv("OKK_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("OKK_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = releaseStoreFilePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.OKK.yes"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.OKK.yes"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "1.2.6"
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
        // 发版 / 打包：必须压缩 + 混淆 + 资源裁剪；如未配置签名环境变量，则生成 unsigned release APK。
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseSign")
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
