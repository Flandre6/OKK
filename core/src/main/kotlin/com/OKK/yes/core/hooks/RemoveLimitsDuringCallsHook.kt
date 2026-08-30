package com.OKK.yes.core.hooks

import android.content.Context
import android.util.Log
import com.OKK.yes.core.compat.DexKitSupport
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 移除通话时聊天限制：
 * 绕过音视频通话时 (VoIP / MultiTalk) 微信系统的设备占用限制 (MicroMsg.DeviceOccupy)。
 * 100% 对齐 WeKit / wcx 逆向实现，通过 DexKit 定位 MicroMsg.DeviceOccupy 混淆类并 Hook 其状态判断方法返回 false。
 */
object RemoveLimitsDuringCallsHook {
    private const val TAG = "OKK-RemoveCallLimits"
    private val installed = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return

        runCatching {
            // 1. 首选通过 DexKit 依据 MultiTalkActionEvent 静态抽象类特征精准定位 DeviceOccupy 混淆类中的 isDuringCall 方法
            val methodIsDuringCall = DexKitSupport.withBridge(context, classLoader, modulePath) { bridge ->
                runCatching {
                    bridge.findMethod {
                        matcher {
                            declaredClass {
                                modifiers(Modifier.ABSTRACT)
                            }
                            modifiers(Modifier.STATIC)
                            paramCount = 0
                            returnType = "boolean"
                            addInvoke {
                                declaredClass = "com.tencent.mm.autogen.events.MultiTalkActionEvent"
                            }
                        }
                    }.firstOrNull()?.getMethodInstance(classLoader)
                }.getOrNull()
            }

            if (methodIsDuringCall != null) {
                xlog("located DeviceOccupy.methodIsDuringCall: ${methodIsDuringCall.declaringClass.name}.${methodIsDuringCall.name}")
                hookTargetMethod(methodIsDuringCall)
                return
            }

            // 2. 备选：定位 DeviceOccupy 类并仅 Hook 与通话占用相关的特定 0 参 static boolean 方法
            var targetClass: Class<*>? = DexKitSupport.findClassByStrings(
                context, classLoader, modulePath, "MicroMsg.DeviceOccupy"
            )
            if (targetClass == null) {
                targetClass = runCatching {
                    XposedHelpers.findClass("com.tencent.mm.sdk.platformtools.DeviceOccupy", classLoader)
                }.getOrNull()
            }

            if (targetClass == null) {
                xlog("DeviceOccupy class not found")
                return
            }

            var count = 0
            for (m in targetClass.declaredMethods) {
                val isBool = m.returnType == Boolean::class.javaPrimitiveType || m.returnType == java.lang.Boolean::class.java
                if (isBool && Modifier.isStatic(m.modifiers) && m.parameterCount == 0) {
                    val n = m.name.lowercase()
                    // 仅 Hook 通话/聊天限制相关的方法，严禁 Hook 涉及音量/静音/扬声器/麦克风状态的方法
                    if (n.contains("call") || n.contains("talk") || n.contains("occupy")) {
                        if (!n.contains("mute") && !n.contains("volume") && !n.contains("speaker") && !n.contains("mic")) {
                            hookTargetMethod(m)
                            count++
                        }
                    }
                }
            }
            xlog("hooked $count methods in ${targetClass.name}")
        }.onFailure { xlog("RemoveLimitsDuringCalls install fail: ${it.message}") }
    }

    private fun hookTargetMethod(method: Method) {
        runCatching {
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!PublicConfigStore.getBoolean("remove_call_limits_enabled", true)) return
                    param.result = false
                }
            })
            xlog("hooked method: ${method.name}")
        }
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
