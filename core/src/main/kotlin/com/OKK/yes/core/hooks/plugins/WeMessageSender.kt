package com.OKK.yes.core.hooks.plugins

import android.content.Context
import com.OKK.yes.core.compat.DexKitSupport
import com.OKK.yes.core.hooks.PublicConfigStore
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object WeMessageSender {
    private const val TAG = "OKK-MessageSender"

    @Volatile
    private var netSceneSendMsgClass: Class<*>? = null

    @Volatile
    private var netSceneSendMsgConstructor: Constructor<*>? = null

    @Volatile
    private var netSceneQueueGetMethod: Method? = null

    @Volatile
    private var netSceneQueueAddMethod: Method? = null

    @Volatile
    private var methodShareFile: Method? = null

    @Volatile
    private var msgInfoStorageInstance: Any? = null

    @Volatile
    private var msgInfoStorageInsertMethod: Method? = null

    fun getMsgInfoStorageInstance(): Any? {
        if (msgInfoStorageInstance != null) return msgInfoStorageInstance
        return runCatching {
            val cl = DexKitSupport.classLoader ?: return null
            // WeServiceApi.msgInfoStorage -> MMKernel.storage().msgInfoStg()
            val kernelClass = cl.loadClass("com.tencent.mm.kernel.MMKernel")
            val storageMethod = kernelClass.methods.firstOrNull { Modifier.isStatic(it.modifiers) && it.name == "storage" && it.parameterCount == 0 }
            val coreStorage = storageMethod?.invoke(null) ?: return null
            val msgInfoStgMethod = coreStorage.javaClass.methods.firstOrNull { it.returnType.name == "com.tencent.mm.storage.MsgInfoStorage" || it.returnType.name == "com.tencent.mm.storage.by" } // by is a common obfuscated name, but we can search by return type if known, or just use DexKit to locate it.
            // A safer approach: Since we hooked MsgInfoStorage.insert, we can capture the instance!
            null
        }.getOrNull()
    }

    fun getMsgInfoStorageInsertMethod(): Method? {
        return msgInfoStorageInsertMethod
    }

    // Called from JavaPluginHook to provide the instance and method
    fun provideMsgInfoStorage(instance: Any, insertMethod: Method) {
        msgInfoStorageInstance = instance
        msgInfoStorageInsertMethod = insertMethod
    }

    private var initialized = false

    fun init(ctx: Context, classLoader: ClassLoader, modulePath: String?) {
        if (initialized) return
        runCatching {
            DexKitSupport.withBridge(ctx, classLoader, modulePath) { bridge ->
                resolve(bridge, classLoader)
            }
        }.onFailure {
            XposedBridge.log("[$TAG] Failed to init DexKit for message sender: ${it.message}")
        }
    }

    private fun resolve(bridge: DexKitBridge, classLoader: ClassLoader) {
        // 1. 查找 NetSceneSendMsg 类: 使用特征字符串 "MicroMsg.NetSceneSendMsg" 和 "markMsgFailed for id:%d"
        val sendMsgClassData = runCatching {
            bridge.findClass {
                matcher {
                    usingStrings("MicroMsg.NetSceneSendMsg", "markMsgFailed for id:%d")
                }
            }.firstOrNull()
        }.getOrNull()

        if (sendMsgClassData != null) {
            netSceneSendMsgClass = runCatching { classLoader.loadClass(sendMsgClassData.name) }.getOrNull()
            netSceneSendMsgClass?.declaredConstructors?.forEach { ctor ->
                val params = ctor.parameterTypes
                // (String toUser, String content, int type, int flags, Object ext)
                if (params.size == 5 &&
                    params[0] == String::class.java &&
                    params[1] == String::class.java &&
                    (params[2] == Int::class.javaPrimitiveType || params[2] == java.lang.Integer::class.java)
                ) {
                    ctor.isAccessible = true
                    netSceneSendMsgConstructor = ctor
                }
            }
        }

        // 2. 查找 MMKernel 获取 NetSceneQueue 实例的方法
        val queueClassData = runCatching {
            bridge.findClass {
                matcher {
                    usingStrings("MicroMsg.NetSceneQueue", "doscene mmcgi Failed type:%d")
                }
            }.firstOrNull()
        }.getOrNull()

        if (queueClassData != null) {
            val queueClass = runCatching { classLoader.loadClass(queueClassData.name) }.getOrNull()
            if (queueClass != null) {
                // 查找 NetSceneQueue.doScene / addScene 方法
                queueClass.declaredMethods.forEach { method ->
                    val params = method.parameterTypes
                    if (Modifier.isPublic(method.modifiers) &&
                        (method.returnType == Boolean::class.javaPrimitiveType || method.returnType == java.lang.Boolean::class.java) &&
                        params.size in 1..2 &&
                        params[0] != String::class.java &&
                        params[0] != Int::class.javaPrimitiveType
                    ) {
                        if (netSceneQueueAddMethod == null || params.size == 2) {
                            method.isAccessible = true
                            netSceneQueueAddMethod = method
                        }
                    }
                }

                // 查找 MMKernel 中返回 queueClass 的静态方法
                // 微信 MMKernel 类含有字符串 "Kernel not null, has initialized."
                val kernelClassData = runCatching {
                    bridge.findClass {
                        matcher {
                            usingStrings("Kernel not null, has initialized.")
                        }
                    }.firstOrNull()
                }.getOrNull() ?: runCatching {
                    bridge.findClass {
                        matcher {
                            usingStrings("MicroMsg.MMKernel")
                        }
                    }.firstOrNull()
                }.getOrNull()

                if (kernelClassData != null) {
                    val kernelClass = runCatching { classLoader.loadClass(kernelClassData.name) }.getOrNull()
                    kernelClass?.declaredMethods?.forEach { m ->
                        if (Modifier.isStatic(m.modifiers) && m.parameterTypes.isEmpty() && m.returnType == queueClass) {
                            m.isAccessible = true
                            netSceneQueueGetMethod = m
                        }
                    }
                }
            }
        }

        // 3. 查找 shareFile / sendMediaMsg 方法 (6 个参数: WXMediaMessage, String, String, String, int, String)
        runCatching {
            bridge.findMethod {
                matcher {
                    paramTypes(
                        "com.tencent.mm.opensdk.modelmsg.WXMediaMessage",
                        "java.lang.String",
                        "java.lang.String",
                        "java.lang.String",
                        "int",
                        "java.lang.String"
                    )
                }
            }.firstOrNull()?.let { mData ->
                val m = classLoader.loadClass(mData.className).declaredMethods.find {
                    it.name == mData.methodName && it.parameterTypes.size == 6
                }
                m?.let {
                    it.isAccessible = true
                    methodShareFile = it
                    XposedBridge.log("[$TAG] Resolved methodShareFile: ${it.declaringClass.name}.${it.name}")
                }
            }
        }

        initialized = (netSceneSendMsgConstructor != null && netSceneQueueAddMethod != null && netSceneQueueGetMethod != null)
        XposedBridge.log("[$TAG] MessageSender resolve status: initialized=$initialized (msgCtor=${netSceneSendMsgConstructor != null}, queueGet=${netSceneQueueGetMethod != null}, queueAdd=${netSceneQueueAddMethod != null}, shareFile=${methodShareFile != null})")
    }

    fun getNetSceneQueue(): Any? {
        val getQueue = netSceneQueueGetMethod ?: return null
        return getQueue.invoke(null)
    }

    fun addNetSceneToQueue(queue: Any, netScene: Any): Boolean {
        val addQueue = netSceneQueueAddMethod ?: return false
        val res = if (addQueue.parameterTypes.size == 1) {
            addQueue.invoke(queue, netScene)
        } else {
            addQueue.invoke(queue, netScene, 0)
        }
        return res as? Boolean ?: true
    }

    fun sendText(toUser: String, content: String): Boolean {
        return runCatching {
            val ctor = netSceneSendMsgConstructor
            if (ctor == null) {
                XposedBridge.log("[$TAG] SendText failed: sender not fully initialized")
                return false
            }
            val queueInstance = getNetSceneQueue() ?: run {
                XposedBridge.log("[$TAG] SendText failed: NetSceneQueue is null")
                return false
            }

            val netScene = ctor.newInstance(toUser, content, 1, 0, null)
            val res = addNetSceneToQueue(queueInstance, netScene)
            XposedBridge.log("[$TAG] Sent text message to $toUser, result: $res")
            res
        }.onFailure {
            XposedBridge.log("[$TAG] SendText exception: ${it.message}")
        }.getOrDefault(false)
    }

    fun sendMediaMsg(talker: String, mediaMessage: Any, appId: String?): Boolean {
        return runCatching {
            val m = methodShareFile
            if (m == null) {
                XposedBridge.log("[$TAG] SendMediaMsg failed: methodShareFile not resolved")
                return false
            }
            m.invoke(null, mediaMessage, appId ?: "", "", talker, 3, null)
            XposedBridge.log("[$TAG] Sent media message to $talker, appId: $appId")
            true
        }.onFailure {
            XposedBridge.log("[$TAG] SendMediaMsg exception: ${it.message}")
        }.getOrDefault(false)
    }

    fun sendImage(talker: String, imgPath: String): Boolean {
        return runCatching {
            val file = File(imgPath)
            if (!file.exists()) {
                XposedBridge.log("[$TAG] sendImage failed: file not exists $imgPath")
                return false
            }
            val bytes = file.readBytes()
            val cl = DexKitSupport.classLoader ?: return false
            val imgObjClass = cl.loadClass("com.tencent.mm.opensdk.modelmsg.WXImageObject")
            val mediaMsgClass = cl.loadClass("com.tencent.mm.opensdk.modelmsg.WXMediaMessage")

            val imgObj = imgObjClass.newInstance()
            imgObjClass.getField("imageData").set(imgObj, bytes)

            val mediaMsg = mediaMsgClass.getConstructor(cl.loadClass("com.tencent.mm.opensdk.modelmsg.WXMediaMessage\$IMediaObject")).newInstance(imgObj)

            // 生成缩略图
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val w = bmp.width
                    val h = bmp.height
                    val tw = if (w > h) 100 else if (w > 0) (100 * w / h).coerceAtLeast(1) else 100
                    val th = if (w > h) if (h > 0) (100 * h / w).coerceAtLeast(1) else 100 else 100
                    val thumb = android.graphics.Bitmap.createScaledBitmap(bmp, tw, th, true)
                    bmp.recycle()
                    val baos = java.io.ByteArrayOutputStream()
                    thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, baos)
                    thumb.recycle()
                    mediaMsgClass.getField("thumbData").set(mediaMsg, baos.toByteArray())
                    baos.close()
                }
            }

            sendMediaMsg(talker, mediaMsg, "wx485a97c844086dc9")
        }.onFailure {
            XposedBridge.log("[$TAG] sendImage exception: ${it.message}")
        }.getOrDefault(false)
    }
}
