package com.OKK.yes.core.hooks.plugins

import android.content.Context
import com.OKK.yes.core.compat.DexKitSupport
import com.OKK.yes.core.hooks.PublicConfigStore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

object JavaPluginHook {
    private const val TAG = "OKK-JavaPlugin"
    private var isInstalled = false

    fun install(ctx: Context, classLoader: ClassLoader, modulePath: String?) {
        val enabled = runCatching { PublicConfigStore.getBoolean("java_plugin_enabled", false) }.getOrDefault(false)
        if (!enabled) return

        runCatching {
            DexKitSupport.appContext = ctx
            DexKitSupport.classLoader = classLoader
            DexKitSupport.modulePath = modulePath

            WeMessageSender.init(ctx, classLoader, modulePath)
            JavaScriptEngine.createSampleScriptIfEmpty()
            JavaScriptEngine.reloadAll(classLoader)

            if (!isInstalled) {
                hookMessageEvents(ctx, classLoader, modulePath)
                isInstalled = true
            }
        }.onFailure {
            XposedBridge.log("[$TAG] Failed to init java plugins: ${it.message}")
        }
    }

    private fun hookMessageEvents(ctx: Context, classLoader: ClassLoader, modulePath: String?) {
        runCatching {
            DexKitSupport.withBridge(ctx, classLoader, modulePath) { bridge ->
                val methodsToHook = mutableSetOf<Method>()

                // 0. 监听 ChatFooter.setUserName 提取当前聊天对象
                runCatching {
                    val chatFooterClass = classLoader.loadClass("com.tencent.mm.pluginsdk.ui.chat.ChatFooter")
                    val setUserNameMethod = chatFooterClass.declaredMethods.firstOrNull { it.name == "setUserName" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
                    if (setUserNameMethod != null) {
                        XposedBridge.hookMethod(setUserNameMethod, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val userName = param.args.getOrNull(0) as? String
                                if (!userName.isNullOrEmpty()) {
                                    JavaScriptEngine.setTargetTalker(userName)
                                }
                            }
                        })
                        XposedBridge.log("[$TAG] Successfully hooked ChatFooter.setUserName")
                    } else {
                        XposedBridge.log("[$TAG] ChatFooter.setUserName method not found")
                    }
                }.onFailure {
                    XposedBridge.log("[$TAG] Failed to hook ChatFooter.setUserName: ${it.message}")
                }

                // 1. 高层消息处理入口 ("protect:c2c msg should not here")
                runCatching {
                    bridge.findMethod {
                        matcher {
                            usingStrings("protect:c2c msg should not here")
                        }
                    }.firstOrNull()?.let { mData ->
                        descriptorToMethod(mData.descriptor, classLoader)?.let { methodsToHook.add(it) }
                    }
                }

                // 2. 底层数据库插入方法 ("MsgInfo processAddMsg insert db error")
                runCatching {
                    bridge.findMethod {
                        matcher {
                            usingStrings("MsgInfo processAddMsg insert db error")
                        }
                    }.firstOrNull()?.let { mData ->
                        descriptorToMethod(mData.descriptor, classLoader)?.let { methodsToHook.add(it) }
                    }
                }

                if (methodsToHook.isNotEmpty()) {
                    val hooker = object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val thisObj = param.thisObject
                            if (thisObj != null) {
                                WeMessageSender.provideMsgInfoStorage(thisObj, param.method as Method)
                            }
                            
                            val msgObj = param.args.getOrNull(0) ?: return
                            XposedBridge.log("[$TAG] Message event hooked: method=${param.method.name}, msgObj=${msgObj.javaClass.name}")
                            val wrapper = MsgInfoWrapper(msgObj)
                            JavaScriptEngine.executeAllOnHandleMsg(wrapper)
                        }
                    }
                    methodsToHook.forEach { m ->
                        XposedBridge.hookMethod(m, hooker)
                        XposedBridge.log("[$TAG] Successfully hooked message insert method: ${m.declaringClass.name}.${m.name}")
                    }
                } else {
                    XposedBridge.log("[$TAG] No message insert methods found via DexKit string search")
                }

                // 3. 聊天输入框发送按钮拦截 (onClickSendBtn)
                runCatching {
                    bridge.findMethod {
                        matcher {
                            usingStrings("MicroMsg.ChatFooter", "send msg onClick")
                        }
                    }.firstOrNull()?.let { mData ->
                        descriptorToMethod(mData.descriptor, classLoader)?.let { sendMethod ->
                            XposedBridge.hookMethod(sendMethod, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val thisObj = param.thisObject ?: return
                                    val chatFooter = findChatFooterInObject(thisObj) ?: return
                                    val text = getChatFooterLastText(chatFooter)
                                    if (text.isNotEmpty()) {
                                        val intercepted = JavaScriptEngine.executeAllOnClickSendBtn(text)
                                        if (intercepted) {
                                            XposedBridge.log("[$TAG] Send button intercepted by script: text='$text'")
                                            clearChatFooterLastText(chatFooter)
                                            param.result = null
                                        }
                                    }
                                }
                            })
                            XposedBridge.log("[$TAG] Successfully hooked ChatFooter send message method: ${sendMethod.declaringClass.name}.${sendMethod.name}")
                        }
                    }
                }.onFailure {
                    XposedBridge.log("[$TAG] Failed to hook ChatFooter send method: ${it.message}")
                }
            }
        }.onFailure {
            XposedBridge.log("[$TAG] Failed to hook message events: ${it.message}")
        }
    }

    private fun findChatFooterInObject(obj: Any): Any? {
        if (obj.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter") return obj
        var cur: Class<*>? = obj.javaClass
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (f.type.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter") {
                    f.isAccessible = true
                    val v = runCatching { f.get(obj) }.getOrNull()
                    if (v != null) return v
                }
            }
            cur = cur.superclass
        }
        return null
    }

    private fun getChatFooterLastText(chatFooter: Any): String {
        return runCatching {
            val m = chatFooter.javaClass.methods.firstOrNull { it.name == "getLastText" && it.parameterCount == 0 }
            m?.isAccessible = true
            m?.invoke(chatFooter)?.toString()?.trim().orEmpty()
        }.getOrDefault("")
    }

    private fun clearChatFooterLastText(chatFooter: Any) {
        runCatching {
            val m = chatFooter.javaClass.methods.firstOrNull { it.name == "setLastText" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
            m?.isAccessible = true
            m?.invoke(chatFooter, "")
        }
    }

    private fun descriptorToMethod(descriptor: String, classLoader: ClassLoader): Method {
        val className = descriptor.substringBefore("->").trim().removePrefix("L").removeSuffix(";").replace('/', '.')
        val rest = descriptor.substringAfter("->").trim()
        val methodName = rest.substringBefore("(").trim()
        val paramDesc = rest.substringAfter("(").substringBefore(")")
        val paramTypes = parseParamTypes(paramDesc, classLoader)
        val clazz = classLoader.loadClass(className)
        return clazz.getDeclaredMethod(methodName, *paramTypes.toTypedArray()).apply { isAccessible = true }
    }

    private fun parseParamTypes(desc: String, classLoader: ClassLoader): List<Class<*>> {
        val result = mutableListOf<Class<*>>()
        var i = 0
        while (i < desc.length) {
            when (desc[i]) {
                'Z' -> { result.add(Boolean::class.javaPrimitiveType!!); i++ }
                'B' -> { result.add(Byte::class.javaPrimitiveType!!); i++ }
                'C' -> { result.add(Char::class.javaPrimitiveType!!); i++ }
                'S' -> { result.add(Short::class.javaPrimitiveType!!); i++ }
                'I' -> { result.add(Int::class.javaPrimitiveType!!); i++ }
                'J' -> { result.add(Long::class.javaPrimitiveType!!); i++ }
                'F' -> { result.add(Float::class.javaPrimitiveType!!); i++ }
                'D' -> { result.add(Double::class.javaPrimitiveType!!); i++ }
                'V' -> { result.add(Void.TYPE); i++ }
                'L' -> {
                    val end = desc.indexOf(';', i)
                    val name = desc.substring(i + 1, end).replace('/', '.')
                    result.add(classLoader.loadClass(name))
                    i = end + 1
                }
                '[' -> {
                    var arrayDepth = 0
                    while (i < desc.length && desc[i] == '[') {
                        arrayDepth++
                        i++
                    }
                    val elemDesc = when (desc[i]) {
                        'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> desc.substring(i - arrayDepth, i + 1)
                        'L' -> {
                            val end = desc.indexOf(';', i)
                            val r = desc.substring(i - arrayDepth, end + 1)
                            i = end
                            r
                        }
                        else -> ""
                    }
                    result.add(Class.forName(elemDesc.replace('/', '.'), false, classLoader))
                    i++
                }
                else -> i++
            }
        }
        return result
    }
}
