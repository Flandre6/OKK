package de.robv.android.xposed

interface IXposedHookZygoteInit {
    class StartupParam {
        var modulePath: String? = null
    }

    fun initZygote(startupParam: StartupParam)
}
