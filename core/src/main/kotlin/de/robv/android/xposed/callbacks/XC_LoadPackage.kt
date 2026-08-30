package de.robv.android.xposed.callbacks

class XC_LoadPackage {
    class LoadPackageParam {
        lateinit var packageName: String
        lateinit var processName: String
        lateinit var classLoader: ClassLoader
        var appInfo: android.content.pm.ApplicationInfo? = null
        var isFirstApplication: Boolean = false
    }
}
