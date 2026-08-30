plugins {
    id("com.android.application")
}

android {
    namespace = "com.OKK.yes.monitor"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.OKK.yes.monitor"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        debug { }
        release { isMinifyEnabled = false }
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
    compileOnly(files("../loader/libs/xposed-api-82_compileonly.jar"))
    compileOnly(files("../loader/libs/lsposed-api-100_compileonly.jar"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
