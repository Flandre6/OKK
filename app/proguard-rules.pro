# OKK release：R8 压缩 + 混淆
# 仅保留对外入口与反射/JNI 边界，其余可裁剪混淆
# LSPosed / Xposed 入口（assets/xposed_init、meta-data 写死类名
-keep class com.OKK.yes.loader.HookEntry {
    public <init>();
    public *;
}
-keep class com.OKK.yes.loader.ZygoteEntry {
    public <init>();
    public *;
}
-keep class com.OKK.yes.loader.ModernHookEntry {
    public <init>();
    public *;
}

# 诊断日志区运行时写入，勿整类删掉
-keep class com.OKK.yes.core.hooks.ModuleLog {
    public *;
}

# BeanShell 脚本引擎（内部依赖大量反射、AST 动态构造与动态代理，必须完整保留）
-keep class bsh.** { *; }
-dontwarn bsh.**

-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**



# 脚本暴露的 API 及模型类
-keep class com.OKK.yes.core.hooks.plugins.** { *; }

# 脚本依赖的第三方库 (BeanShell 动态调用)
-keep class com.alibaba.fastjson2.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn com.alibaba.fastjson2.**
-dontwarn okhttp3.**
-dontwarn okio.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-renamesourcefileattribute SourceFile
# 混淆时去除局部变量表/源码文件名/行号，断点逆向更吃力
-keepattributes !LocalVariableTable,!LineNumberTable

# 启用聚合优化并对未 keep 的类名/方法名做完整混淆
-optimizationpasses 5
-allowaccessmodification

-keep @androidx.annotation.Keep class * { *; }

# DexKit JNI / 反射
-keep class org.luckypray.dexkit.** { *; }

# Dialog Compose 用反射设置 ViewTree*Owner，R8 不得裁剪
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keep class androidx.lifecycle.ViewTreeViewModelStoreOwner { *; }
-keepclassmembers class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keepclassmembers class androidx.lifecycle.ViewTreeViewModelStoreOwner { *; }
-keep class androidx.savedstate.ViewTreeSavedStateRegistryOwner { *; }
-keepclassmembers class androidx.savedstate.ViewTreeSavedStateRegistryOwner { *; }
-keep class androidx.activity.ViewTreeOnBackPressedDispatcherOwner { *; }
-keepclassmembers class androidx.activity.ViewTreeOnBackPressedDispatcherOwner { *; }
-keep class androidx.navigationevent.ViewTreeNavigationEventDispatcherOwner { *; }
-keepclassmembers class androidx.navigationevent.ViewTreeNavigationEventDispatcherOwner { *; }
-keep class androidx.navigationevent.** { *; }
-keep class androidx.activity.OnBackPressedDispatcherOwner { *; }
-keep class androidx.activity.OnBackPressedDispatcher { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# libxposed（compileOnly，宿主注入）
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker {
    public java.lang.Object intercept(io.github.libxposed.api.XposedInterface$Chain);
}
-dontwarn io.github.libxposed.api.**
-dontwarn de.robv.android.xposed.**

# 微信类名字符串大量反射，禁止改写字符串里的类名
# （不要加 -adaptclassstrings）

# 发布包去除 android.util.Log 调用（ModuleLog 文件落盘仍保留）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
