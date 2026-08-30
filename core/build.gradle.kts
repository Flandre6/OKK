plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.OKK.yes.core"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
    }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.luckypray:dexkit:2.0.6")

    // ── Compose 基座（WeKit 悬浮底栏，对齐 wcx/loader 版本）──
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    // 注入式 ComposeView 需要 ViewTree Lifecycle/ViewModel/SavedState owners
    implementation("androidx.lifecycle:lifecycle-runtime-android:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-android:2.9.4")

    // MIUIX 毛玻璃（WeKit 底栏 backdrop/blur/lens）
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")

    // Material Symbols 图标（对齐 wcx 2.2.1）
    implementation("com.composables:icons-material-symbols-outlined-cmp:2.2.1")
    implementation("com.composables:icons-material-symbols-outlined-filled-cmp:2.2.1")
    
    // Java Plugin Dependencies
    implementation(project(":bsh"))
    implementation("com.jakewharton.android.repackaged:dalvik-dx:16.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.alibaba.fastjson2:fastjson2:2.0.47")

    testImplementation("junit:junit:4.13.2")
}
