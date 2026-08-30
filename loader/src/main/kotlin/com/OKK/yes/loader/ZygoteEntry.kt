package com.OKK.yes.loader

import de.robv.android.xposed.IXposedHookZygoteInit

class ZygoteEntry : IXposedHookZygoteInit {
    companion object {
        var modulePath: String?
            get() = ModulePathHolder.modulePath
            set(value) {
                ModulePathHolder.modulePath = value
            }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ModulePathHolder.modulePath = startupParam.modulePath
    }
}
