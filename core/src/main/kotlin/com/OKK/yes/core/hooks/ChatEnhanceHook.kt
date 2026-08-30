package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.NinePatch
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.OKK.yes.core.R
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

object ChatEnhanceHook {
    private const val TAG = "OKK-ChatEnhance"
    private const val ADAPTER_BIND_LOG = "_onBindViewHolder["
    private const val ADAPTER_TAG_LOG = "MicroMsg.ChattingDataAdapterV3"
    private const val COMPONENT_LOG = "clear video generate callback"
    private const val VIEW_ITEMS_CONTAINER_LOG = "x2c.X2CCheckBox"

    private val installed = AtomicBoolean(false)
    private val dexKitNativeLoaded = AtomicBoolean(false)
    private val firstRowEnhancedLogged = AtomicBoolean(false)
    private val firstBindEnteredLogged = AtomicBoolean(false)
    private val firstMemberTitleErrLogged = AtomicBoolean(false)
    private val firstRealNameErrLogged = AtomicBoolean(false)
    private val firstHookErrLogged = AtomicBoolean(false)
    private val firstEnhanceEnteredLogged = AtomicBoolean(false)
    private val firstCreateTimeNullLogged = AtomicBoolean(false)
    private val firstTimePolicyRejectLogged = AtomicBoolean(false)
    private val firstItemViewMissingLogged = AtomicBoolean(false)
    private val firstMessageMissingLogged = AtomicBoolean(false)
    private val firstBubbleMissingLogged = AtomicBoolean(false)
    private val firstNonTextTimeLogged = AtomicBoolean(false)
    private val firstSelfRecalledTimeSkippedLogged = AtomicBoolean(false)
    private val firstSwipeHotZoneLogged = AtomicBoolean(false)
    private val firstSystemNoticeCleanedLogged = AtomicBoolean(false)
    private val firstVideoBindLogged = AtomicBoolean(false)
    private val voiceCallDumpLogged = AtomicBoolean(false)
    private val rowDispatchHooked = AtomicBoolean(false)
    private val neatBackgroundHooked = AtomicBoolean(false)
    private val recallTextHooked = AtomicBoolean(false)
    private val neatRecallTextHooked = AtomicBoolean(false)
    private val x2cRecallTextHooked = AtomicBoolean(false)
    private val hookedMethods = ConcurrentHashMap.newKeySet<String>()
    private val allFieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val allMethodsCache = ConcurrentHashMap<Class<*>, List<Method>>()
    // 按 (Class, name) 缓存零参方法与字段，避免每条消息 bind 重复线性遍历方法/字段列表
    private class Optional<T>(val value: T?)
    private val zeroArgMethodCache = ConcurrentHashMap<String, Optional<Method>>()
    private val fieldByNameCache = ConcurrentHashMap<String, Optional<Field>>()

    @Volatile
    private var chatFooter: Any? = null

    @Volatile
    private var quoteComponent: Any? = null

    @Volatile
    private var getCurrentMsgMethod: Method? = null

    @Volatile
    private var swipeRowContainerClass: Class<*>? = null

    @Volatile
    private var moduleApkPath: String? = null

    @Volatile
    private var bubbleTextId: Int = 0

    const val REQ_PICK_BUBBLE_LEFT = 0x0A0C14
    const val REQ_PICK_BUBBLE_RIGHT = 0x0A0C15

    fun startPickBubble(activity: Activity, isSend: Boolean) {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }
        val req = if (isSend) REQ_PICK_BUBBLE_RIGHT else REQ_PICK_BUBBLE_LEFT
        runCatching {
            activity.startActivityForResult(
                android.content.Intent.createChooser(intent, "选择 .9.png 气泡皮肤"),
                req
            )
        }.onFailure {
            runCatching { activity.startActivityForResult(intent, req) }
        }
    }

    private fun hookBubblePickerResult() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Integer.TYPE,
                Integer.TYPE,
                android.content.Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val req = param.args.getOrNull(0) as? Int ?: return
                        if (req != REQ_PICK_BUBBLE_LEFT && req != REQ_PICK_BUBBLE_RIGHT) return
                        if (param.args.getOrNull(1) as? Int != Activity.RESULT_OK) return
                        val uri = (param.args.getOrNull(2) as? android.content.Intent)?.data ?: return
                        val act = param.thisObject as? Activity ?: return
                        val isSend = req == REQ_PICK_BUBBLE_RIGHT
                        
                        runCatching {
                            val dir = java.io.File("/storage/emulated/0/Android/media/com.tencent.mm/OKK")
                            if (!dir.exists()) dir.mkdirs()
                            val out = java.io.File(dir, if (isSend) "right.9.png" else "left.9.png")
                            act.contentResolver.openInputStream(uri)?.use { input ->
                                out.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            PublicConfigStore.put(
                                if (isSend) "bubble_path_right" else "bubble_path_left",
                                out.absolutePath,
                                false
                            )
                            PublicConfigStore.putBoolean("bubble_enabled", true, false)
                            // 清除旧缓存
                            BubbleAssets.clearCache(out.absolutePath)
                            android.widget.Toast.makeText(act, "气泡皮肤导入成功！", android.widget.Toast.LENGTH_SHORT).show()
                            xlog("bubble imported: ${out.absolutePath}")
                        }.onFailure {
                            xlog("bubble import fail: ${it.message}")
                            android.widget.Toast.makeText(act, "导入失败: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        moduleApkPath = modulePath ?: moduleApkPath
        if (!installed.compareAndSet(false, true)) return

        xlog("install starting from ${context.javaClass.name}")
        hookBubblePickerResult()
        hookMMNeatBackground(classLoader)
        hookChatFooter(classLoader)
        hookMMNeatRecallNoticeTextCleanup(classLoader)
        hookX2CRecallNoticeTextCleanup(classLoader)
        installDexKitHooks(context, classLoader, modulePath)
        hookCurrentWechatFallbacks(classLoader)
        xlog("install done")
    }

    private fun hookRecallNoticeTextCleanup(classLoader: ClassLoader) {
        // 性能保护：绝不能对全局 TextView.setText 挂 Hook
        return
    }

    private fun hookX2CRecallNoticeTextCleanup(classLoader: ClassLoader) {
        if (!x2cRecallTextHooked.compareAndSet(false, true)) return
        runCatching {
            val clazz = XposedHelpers.findClass("com.tencent.mm.view.x2c.X2CTextView", classLoader)
            val methods = clazz.methods
                .filter { method ->
                    method.name == "setText" &&
                        method.parameterTypes.any { CharSequence::class.java.isAssignableFrom(it) || it == String::class.java }
                }
                .distinctBy { "${it.declaringClass.name}#${it.name}${it.parameterTypes.contentToString()}" }

            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val text = param.args.firstNotNullOfOrNull { arg ->
                            when (arg) {
                                is CharSequence -> arg.toString()
                                is String -> arg
                                else -> null
                            }
                        } ?: return
                        if (!MessageTimeLayoutPolicy.isSystemNoticeText(text)) return
                        val view = param.thisObject as? View ?: return
                        cleanSystemNoticeArea(view)
                        logSystemNoticeCleaned("X2CTextView.${method.name}", view.javaClass.name)
                    }
                })
            }
            xlog("hooked X2CTextView recall cleanup methods: ${methods.size}")
        }.onFailure {
            xlog("X2CTextView recall cleanup hook skipped: ${it.message}")
        }
    }

    private fun hookTaggedMessageRowDispatch() {
        // 已废弃：全局 ViewGroup.dispatchTouchEvent 会导致会话列表点击卡顿。
        // 滑动引用走具体消息行容器 dispatchTouchEvent。
        if (!rowDispatchHooked.compareAndSet(false, true)) return
        xlog("skip global ViewGroup.dispatchTouchEvent (perf; swipe via row dispatch)")
    }

    private fun hookChatFooter(classLoader: ClassLoader) {
        runCatching {
            val clazz = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.chat.ChatFooter", classLoader)
            clazz.declaredConstructors.forEach { constructor ->
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        chatFooter = param.thisObject
                    }
                })
            }
            xlog("hooked ChatFooter constructors: ${clazz.declaredConstructors.size}")
        }.onFailure {
            xlog("ChatFooter hook skipped: ${it.message}")
        }
    }

    private fun hookMMNeatRecallNoticeTextCleanup(classLoader: ClassLoader) {
        if (!neatRecallTextHooked.compareAndSet(false, true)) return
        runCatching {
            val clazz = XposedHelpers.findClass("com.tencent.mm.ui.widget.MMNeat7extView", classLoader)
            // 同 hookX2C：用 methods（含继承链）避免 declaredMethods 漏掉父类 setText
            val methods = clazz.methods
                .filter { method ->
                    method.name.startsWith("setText") &&
                        method.parameterTypes.any {
                            CharSequence::class.java.isAssignableFrom(it) || it == String::class.java
                        }
                }
                .distinctBy { "${it.declaringClass.name}#${it.name}${it.parameterTypes.contentToString()}" }

            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val text = param.args.firstNotNullOfOrNull { arg ->
                            when (arg) {
                                is CharSequence -> arg
                                is String -> arg
                                else -> null
                            }
                        } ?: return
                        // 极早过滤：系统提示才可能含「撤回/拍了拍」
                        if (text.length > 80) return
                        if (!MessageTimeLayoutPolicy.isSystemNoticeText(text.toString())) return
                        val view = param.thisObject as? View ?: return
                        cleanSystemNoticeArea(view)
                        logSystemNoticeCleaned("MMNeat.${method.name}", view.javaClass.name)
                    }
                })
            }
            xlog("hooked MMNeat setText recall cleanup methods: ${methods.size}")
        }.onFailure {
            xlog("MMNeat recall text cleanup hook skipped: ${it.message}")
        }
    }

    private fun hookMMNeatBackground(classLoader: ClassLoader) {
        if (!neatBackgroundHooked.compareAndSet(false, true)) return
        runCatching {
            val clazz = XposedHelpers.findClass("com.tencent.mm.ui.widget.MMNeat7extView", classLoader)
            val methods = clazz.methods
                .filter { method ->
                    method.name == "setBackground" &&
                        method.parameterTypes.size == 1 &&
                        Drawable::class.java.isAssignableFrom(method.parameterTypes[0])
                }
                .distinctBy { "${it.declaringClass.name}#${it.name}${it.parameterTypes.contentToString()}" }
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!AppFeatureConfig.load().customBubble) return
                        val view = param.thisObject as? View ?: return
                        val messageType = view.getTag(R.id.abc_tag_bubble_msg_type) as? Int ?: return
                        val supportsCustomBubble = view.getTag(R.id.abc_tag_bubble_supports_custom) as? Boolean
                            ?: BubbleDrawablePolicy.supportsCustomBubble(messageType)
                        if (!BubbleDrawablePolicy.shouldReplaceBackground(view.javaClass.name, messageType, supportsCustomBubble)) {
                            view.setTag(R.id.abc_tag_bubble_source, null)
                            return
                        }
                        val isSend = view.getTag(R.id.abc_tag_bubble_is_send) as? Boolean ?: return
                        val source = BubbleAssets.sourceKey(moduleApkPath, isSend)
                        (param.args.getOrNull(0) as? Drawable)?.let { original ->
                            view.setTag(R.id.abc_tag_bubble_original_background, original)
                        }
                        BubbleAssets.drawableFor(view.context, moduleApkPath, isSend)?.let { asset ->
                            param.args[0] = asset.drawable
                            view.setTag(R.id.abc_tag_bubble_source, source)
                        }
                    }
                })
            }
            xlog("hooked MMNeat7extView setBackground methods: ${methods.size}")
        }.onFailure {
            neatBackgroundHooked.set(false)
            xlog("MMNeat7extView background hook skipped: ${it.message}")
        }
    }

    private fun installDexKitHooks(context: Context, classLoader: ClassLoader, modulePath: String?) {
        runCatching {
            loadDexKitNative(context, modulePath)
            DexKitBridge.create(classLoader, true).use { bridge ->
                findAndHookAdapter(bridge, classLoader)
                findAndHookQuoteComponent(bridge, classLoader)
                findGetCurrentMsgMethod(bridge, classLoader)
                findSwipeRowContainer(bridge, classLoader)
            }
        }.onFailure {
            xlog("DexKit setup failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun findAndHookAdapter(bridge: DexKitBridge, classLoader: ClassLoader) {
        val descriptor = runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.mm.ui.chatting.adapter")
                matcher {
                    usingStrings(ADAPTER_BIND_LOG, ADAPTER_TAG_LOG)
                }
            }.firstOrNull()?.descriptor
        }.getOrNull()

        if (descriptor.isNullOrBlank()) {
            xlog("DexKit did not find adapter bind method")
            return
        }
        val method = runCatching { descriptorToMethod(descriptor, classLoader) }.getOrNull()
        if (method == null) {
            xlog("adapter descriptor could not resolve: $descriptor")
            return
        }
        hookAdapterBind(method, classLoader, "DexKit:$descriptor")
    }

    private fun findAndHookQuoteComponent(bridge: DexKitBridge, classLoader: ClassLoader) {
        val descriptor = runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.mm.ui.chatting.component")
                matcher {
                    usingStrings(COMPONENT_LOG)
                }
            }.firstOrNull()?.descriptor
        }.getOrNull()

        if (descriptor.isNullOrBlank()) {
            xlog("DexKit did not find quote component")
            return
        }
        val method = runCatching { descriptorToMethod(descriptor, classLoader) }.getOrNull()
        if (method == null) {
            xlog("component descriptor could not resolve: $descriptor")
            return
        }
        hookQuoteComponentConstructors(method.declaringClass, "DexKit:$descriptor")
    }

    private fun findGetCurrentMsgMethod(bridge: DexKitBridge, classLoader: ClassLoader) {
        val descriptor = runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.mm.ui.chatting.viewitems")
                matcher { usingEqStrings("ItemDataTag", "getCurrentMsg2 err") }
            }.firstOrNull()?.descriptor
        }.getOrNull()
        if (descriptor.isNullOrBlank()) {
            xlog("DexKit did not find getCurrentMsg2")
            return
        }
        val method = runCatching { descriptorToMethod(descriptor, classLoader) }.getOrNull()
        if (method == null) {
            xlog("getCurrentMsg2 descriptor could not resolve: $descriptor")
            return
        }
        method.isAccessible = true
        getCurrentMsgMethod = method
        xlog("found getCurrentMsg2 via DexKit:$descriptor")
    }

    private fun findSwipeRowContainer(bridge: DexKitBridge, classLoader: ClassLoader) {
        val descriptor = runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.mm.ui.chatting")
                matcher {
                    declaredClass {
                        usingStrings(VIEW_ITEMS_CONTAINER_LOG)
                        methodCount(1..3)
                    }
                }
            }.firstOrNull()?.descriptor
        }.getOrNull()

        if (descriptor.isNullOrBlank()) {
            xlog("DexKit did not find swipe row container")
            return
        }
        val clazz = runCatching {
            descriptorToMethod(descriptor, classLoader).declaringClass
        }.getOrElse {
            runCatching { descriptorToClass(descriptor, classLoader) }.getOrNull()
        }
        if (clazz == null) {
            xlog("swipe row container descriptor could not resolve: $descriptor")
            return
        }
        swipeRowContainerClass = clazz
        xlog("found swipe row container via DexKit:$descriptor class=${clazz.name}")
        hookSwipeRowDispatch(clazz, "DexKit:$descriptor")
    }

    private fun hookCurrentWechatFallbacks(classLoader: ClassLoader) {
        runCatching {
            val adapterClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.adapter.k", classLoader)
            adapterClass.declaredMethods
                .filter { it.name == "F" && it.parameterTypes.size == 2 && it.parameterTypes[1] == Int::class.javaPrimitiveType }
                .forEach { hookAdapterBind(it, classLoader, "current:adapter.k.F") }
        }.onFailure {
            xlog("current adapter fallback skipped: ${it.message}")
        }

        runCatching {
            val componentClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.ma", classLoader)
            hookQuoteComponentConstructors(componentClass, "current:component.ma")
        }.onFailure {
            xlog("current component fallback skipped: ${it.message}")
        }
    }

    private fun hookQuoteComponentConstructors(clazz: Class<*>, label: String) {
        clazz.declaredConstructors.forEach { constructor ->
            val key = "${constructor.declaringClass.name}#<init>${constructor.parameterTypes.contentToString()}"
            if (!hookedMethods.add(key)) return@forEach
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    quoteComponent = param.thisObject
                }
            })
        }
        xlog("hooked quote component constructors via $label")
    }

    private fun hookAdapterBind(method: Method, classLoader: ClassLoader, label: String) {
        val key = "${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()}"
        if (!hookedMethods.add(key)) return
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 进入聊天首帧阶段绝不做消息解析/视图树查找，避免阻塞微信原生 bind。
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val holder = param.args.getOrNull(0) ?: return
                    val position = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                    val itemView = findItemView(holder) ?: return
                    val message = MessageObjectResolver.find(param.thisObject, position) ?: return
                    itemView.setTag(R.id.abc_tag_message_object, message)
                    tagBindingContext(itemView, holder, param.thisObject)
                    handleBoundMessage(param.thisObject, holder, position, classLoader, itemView, message)
                } catch (t: Throwable) {
                    if (firstHookErrLogged.compareAndSet(false, true)) {
                        xlog("bind after light err: ${Log.getStackTraceString(t)}")
                    }
                }
            }
        })
        xlog("hooked adapter bind via $label")
    }

    private fun tagBoundMessageBubble(itemView: View, message: Any, classLoader: ClassLoader): View? {
        val isSend = readIsSend(message)
        val messageType = readIntPreferField(message, "field_type", "getType") ?: 0
        val messageContent = readString(message, "getContent", "field_content").orEmpty()
        val msgId = readLong(message, "getMsgId", "field_msgId") ?: 0L
        itemView.setTag(R.id.abc_tag_message_object, message)
        if (!MessageTimeLayoutPolicy.shouldShowForMessage(messageType, messageContent, cachedRowText(itemView, msgId))) {
            clearMessageInteractionTags(itemView)
            clearBubbleTags(itemView)
            removeTaggedTimeViewsNear(itemView)
            return null
        }
        val bubble = findBubbleView(itemView, classLoader) ?: return null
        val supportsCustomBubble = BubbleDrawablePolicy.supportsCustomBubble(messageType, messageContent)
        bubble.setTag(R.id.abc_tag_message_object, message)
        bubble.setTag(R.id.abc_tag_swipe_target_row, itemView)
        bubble.setTag(R.id.abc_tag_bubble_is_send, isSend)
        bubble.setTag(R.id.abc_tag_bubble_msg_type, messageType)
        bubble.setTag(R.id.abc_tag_bubble_msg_id, msgId)
        bubble.setTag(R.id.abc_tag_bubble_supports_custom, supportsCustomBubble)
        return bubble
    }

    private fun handleBoundMessage(
        adapter: Any?,
        holder: Any,
        position: Int,
        classLoader: ClassLoader,
        cachedItemView: View? = null,
        cachedMessage: Any? = null
    ) {
        if (firstBindEnteredLogged.compareAndSet(false, true)) {
            xlog("adapter bind entered: adapter=${adapter?.javaClass?.name ?: "static"} holder=${holder.javaClass.name}")
        }
        // 优先复用 before hook 已解析的 itemView/message，避免每行重复反射查找
        val itemView = cachedItemView ?: findItemView(holder) ?: run {
            if (firstItemViewMissingLogged.compareAndSet(false, true)) {
                xlog("message row skipped: itemView not found in ${holder.javaClass.name}")
            }
            return
        }
        val message = cachedMessage ?: MessageObjectResolver.find(adapter, position) ?: run {
            if (firstMessageMissingLogged.compareAndSet(false, true)) {
                xlog("message row skipped: message object not found at position=$position adapter=${adapter?.javaClass?.name ?: "static"}")
            }
            return
        }

        itemView.setTag(R.id.abc_tag_message_object, message)
        tagBindingContext(itemView, holder, adapter)
        val messageType = readIntPreferField(message, "field_type", "getType") ?: 0
        val rawType = messageType and 0xFFFF
        val isVideoMessage = rawType == 43 || rawType == 62
        // 诊断：语音/通话首次 bind 打印 type+content+视图树
        val msgContentForDump = readString(message, "getContent", "field_content").orEmpty()
        val isVoiceCallMsg = BubbleDrawablePolicy.isVoiceOrCallType(messageType) ||
            BubbleDrawablePolicy.isVoiceOrCallType(rawType) ||
            BubbleDrawablePolicy.isVoipContent(msgContentForDump)
        if (isVoiceCallMsg && voiceCallDumpLogged.compareAndSet(false, true)) {
            xlog("voice/call bind holder=${holder.javaClass.name} row=${itemView.javaClass.name} type=$messageType raw=$rawType content=${msgContentForDump.take(60)} isSend=${readIsSend(message)}")
            dumpRowTree(itemView, 0)
        }
        if (isVideoMessage) {
            if (firstVideoBindLogged.compareAndSet(false, true)) {
                xlog("video bind holder=${holder.javaClass.name} row=${itemView.javaClass.name} type=$messageType raw=$rawType isSend=${readIsSend(message)} tx=${itemView.translationX} lp=${itemView.layoutParams?.javaClass?.name}")
            }
        } else {
            itemView.translationX = 0f
        }
        enhanceMessageRow(holder, itemView, message, classLoader)
    }

    private fun isMediaViewerContext(context: Context?): Boolean {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) {
                if (isMediaViewerClassName(ctx.javaClass.name)) return true
            }
            if (isMediaViewerClassName(ctx.javaClass.name)) return true
            ctx = ctx.baseContext
        }
        return ctx?.let { isMediaViewerClassName(it.javaClass.name) } == true
    }

    private fun isMediaViewerClassName(name: String): Boolean {
        return name.contains("gallery", ignoreCase = true) ||
            name.contains("ImageGallery", ignoreCase = true) ||
            name.contains("VideoActivity", ignoreCase = true) ||
            name.contains("SnsBrowseUI", ignoreCase = true) ||
            name.contains("SnsOnlineVideo", ignoreCase = true) ||
            name.contains("ImagePreview", ignoreCase = true)
    }

    private fun hookSwipeRowDispatch(clazz: Class<*>, label: String) {
        val key = "${clazz.name}#dispatchTouchEvent(MotionEvent)"
        if (!hookedMethods.add(key)) return
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz,
                "dispatchTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val row = param.thisObject as? View ?: return
                        if (isMediaViewerContext(row.context)) return
                        val event = param.args.getOrNull(0) as? MotionEvent ?: return
                        val message = row.getTag(R.id.abc_tag_message_object) ?: return
                        if (handleSwipe(row, message, event)) {
                            param.result = true
                        }
                    }
                }
            )
            xlog("hooked swipe row dispatch via $label")
        }.onFailure {
            hookedMethods.remove(key)
            xlog("swipe row dispatch hook failed via $label: ${it.message}")
        }
    }

    private fun tagBindingContext(view: View, holder: Any?, adapter: Any?) {
        if (holder != null) view.setTag(R.id.abc_tag_message_holder, holder)
        if (adapter != null) view.setTag(R.id.abc_tag_message_adapter, adapter)
    }

    private fun enhanceMessageRow(holder: Any, itemView: View, message: Any, classLoader: ClassLoader) {
        val createTime = readLong(message, "getCreateTime", "field_createTime")
        if (createTime == null) {
            if (firstCreateTimeNullLogged.compareAndSet(false, true)) {
                xlog("enhance abort: createTime null, msg=${message.javaClass.name}")
            }
            return
        }
        if (createTime <= 0L) return
        val msgId = readLong(message, "getMsgId", "field_msgId") ?: 0L
        val msgSvrId = readLong(message, "getMsgSvrId", "field_msgSvrId") ?: 0L
        val newMsgId = readLong(message, "getNewMsgId", "field_newMsgId") ?: 0L
        val isSend = readIsSend(message)
        val messageType = readIntPreferField(message, "field_type", "getType") ?: 0
        val messageContent = readString(message, "getContent", "field_content").orEmpty()
        // 身份查询与底部时间是两个独立功能，不能被媒体布局或撤回判断提前截断。
        // 默认关闭时外层短路，避免每行绑定都进入 onMessageBound 的 runCatching 开销
        if (MemberTitleHook.isEnabled()) {
            runCatching { MemberTitleHook.onMessageBound(holder, itemView, message) }
                .onFailure { if (firstMemberTitleErrLogged.compareAndSet(false, true)) xlog("MemberTitle err: ${it.javaClass.simpleName}: ${it.message}") }
        }
        if (RealNameTailHook.isEnabled()) {
            runCatching { RealNameTailHook.onMessageBound(holder, itemView, message) }
                .onFailure { if (firstRealNameErrLogged.compareAndSet(false, true)) xlog("RealNameTail err: ${it.javaClass.simpleName}: ${it.message}") }
        }
        val rowText = cachedRowText(itemView, msgId)
        val previousMsgId = itemView.getTag(R.id.abc_tag_enhanced_msg_id) as? Long
        val previousIsSend = itemView.getTag(R.id.abc_tag_enhanced_is_send) as? Boolean
        if (previousMsgId != msgId || previousIsSend != isSend) {
            removeTaggedTimeViews(itemView)
            itemView.setTag(R.id.abc_tag_enhanced_msg_id, msgId)
            itemView.setTag(R.id.abc_tag_enhanced_is_send, isSend)
        }
        if (SelfRevokeMessageRegistry.contains(msgId, msgSvrId, newMsgId)) {
            removeRecallGhostTimeAround(itemView)
            if (firstSelfRecalledTimeSkippedLogged.compareAndSet(false, true)) {
                xlog("self recalled message detail time skipped msgId=$msgId msgSvrId=$msgSvrId newMsgId=$newMsgId")
            }
            return
        }
        if (firstEnhanceEnteredLogged.compareAndSet(false, true)) {
            xlog("enhance entered msg=${message.javaClass.name} type=$messageType isSend=$isSend createTime=$createTime")
        }
        if (!MessageTimeLayoutPolicy.shouldShowForMessage(messageType, messageContent, rowText)) {
            if (firstTimePolicyRejectLogged.compareAndSet(false, true)) {
                xlog("enhance abort: time policy reject type=$messageType content=${messageContent.take(80)}")
            }
            clearMessageInteractionTags(itemView)
            clearBubbleTags(itemView)
            removeRecallGhostTimeAround(itemView)
            return
        }
        val detectedBubble = findBubbleView(itemView, classLoader)
        val bubble = detectedBubble?.takeIf {
            // 应用消息仅在引用文本时套气泡；语音/通话/文本一律放行
            when {
                BubbleDrawablePolicy.isNonTextBubbleType(messageType, messageContent) -> true
                BubbleDrawablePolicy.isAppMessageType(messageType) ->
                    BubbleDrawablePolicy.supportsCustomBubble(messageType, messageContent)
                else -> true
            }
        }
        val emoji = if (bubble == null) findEmojiView(itemView, classLoader) else null
        val contentAnchor = bubble ?: emoji
        if (contentAnchor != null) {
            tagBoundMessageBubble(itemView, message, classLoader)
            // 文字气泡 / 表情：整行 + 内容区都挂滑动，避免偶发引用失效
            ensureSwipeListener(itemView, itemView)
            ensureSwipeListener(contentAnchor, itemView)
            if (bubble != null) {
                ensureSwipeListenersForBubbleCluster(bubble, itemView, message)
                ensureSwipeListenerForRowHotZone(bubble, itemView, message)
                tagSwipeRowContainer(itemView, bubble, message)
                refreshTaggedBubbleBackground(bubble, isSend, messageType, messageContent, msgId)
                alignBubbleCluster(bubble, isSend)
            } else {
                // 表情/无文字气泡：热区挂到表情父链与行容器
                ensureSwipeListenerForRowHotZone(contentAnchor, itemView, message)
                tagSwipeRowContainer(itemView, contentAnchor, message)
                contentAnchor.setTag(R.id.abc_tag_message_object, message)
                contentAnchor.setTag(R.id.abc_tag_swipe_target_row, itemView)
            }
        } else if (firstBubbleMissingLogged.compareAndSet(false, true)) {
            xlog("bubble not found for non-text message type=$messageType")
        }
        // 视频/图片等无气泡消息：修正父容器 gravity，防止 ViewHolder 复用时残留文字气泡的对齐方向
        if (contentAnchor == null && MessageTimeLayoutPolicy.isMediaLikeMessageType(messageType)) {
            // alignMediaCluster(itemView, messageType, isSend)
        }
        
        // 临时跳过视频消息的所有处理，排查是否是时间戳布局导致的左移
        if (messageType == 43 || messageType == 62 || (messageType and 0xFFFF) == 43 || (messageType and 0xFFFF) == 62) {
            return
        }

        val detailOptions = MessageDetailConfig.load()
        val detailText = MessageDetailFormatter.format(
            info = MessageDetailInfo(
                createTime = createTime,
                type = messageType,
                msgId = msgId,
                msgSvrId = msgSvrId
            ),
            options = detailOptions
        )
        if (detailText.isBlank()) {
            // no-op
        } else if (bubble != null) {
            addOrUpdateCustomTimeBelowBubble(bubble, detailText, msgId, isSend, detailOptions)
            ensureDetailClickToggle(bubble, detailOptions)
        } else if (emoji != null) {
            // 表情包：强制插在表情节点下方，避免复用后时间跑到上方
            addOrUpdateCustomTimeBelowMedia(emoji, detailText, msgId, isSend, detailOptions)
            if (detailOptions.clickToShow) {
                ensureDetailClickToggle(emoji, detailOptions)
            }
        } else {
            addTimeToNonBubbleMessage(holder, itemView, detailText, msgId, isSend, messageType, detailOptions)
            if (detailOptions.clickToShow) {
                ensureDetailClickToggle(itemView, detailOptions)
            }
        }
        if (firstRowEnhancedLogged.compareAndSet(false, true)) {
            xlog("first message row enhanced: isSend=$isSend hasBubble=${bubble != null}")
        }
    }

    private fun refreshTaggedBubbleBackground(
        bubble: View,
        isSend: Boolean,
        messageType: Int,
        messageContent: String,
        msgId: Long,
        allowDeferred: Boolean = true
    ) {
        if (!AppFeatureConfig.load().customBubble) {
            bubble.setTag(R.id.abc_tag_bubble_source, null)
            return
        }
        val supportsCustomBubble = BubbleDrawablePolicy.supportsCustomBubble(messageType, messageContent)
        bubble.setTag(R.id.abc_tag_bubble_supports_custom, supportsCustomBubble)
        bubble.setTag(R.id.abc_tag_bubble_msg_id, msgId)
        if (!BubbleDrawablePolicy.shouldReplaceBackground(bubble.javaClass.name, messageType, supportsCustomBubble)) {
            bubble.setTag(R.id.abc_tag_bubble_source, null)
            return
        }
        val source = BubbleAssets.sourceKey(moduleApkPath, isSend)
        val asset = BubbleAssets.drawableFor(bubble.context, moduleApkPath, isSend) ?: return
        val nonText = BubbleDrawablePolicy.isNonTextBubbleType(messageType, messageContent)
        // 语音/通话：去掉 min/intrinsic，且禁止 9-patch padding 写进 View（否则气泡暴涨）
        // 文本气泡：保留 9-patch padding，文字才有正确内边距
        val skin = BubbleDrawablePolicy.withoutMinimumSize(asset.drawable, consumePadding = !nonText)
        // 保存原 padding，setBackground 可能被系统用 drawable padding 覆盖
        val pl = bubble.paddingLeft
        val pt = bubble.paddingTop
        val pr = bubble.paddingRight
        val pb = bubble.paddingBottom
        bubble.background = skin
        if (nonText) {
            bubble.minimumWidth = 0
            bubble.minimumHeight = 0
            // 恢复微信原 padding，完全不被 9.png 影响布局尺寸
            bubble.setPadding(pl, pt, pr, pb)
            // 清内部原生填充色块（时长/波形上的 GradientDrawable）防套壳
            clearInnerBubbleFills(bubble)
            // 锁住 LayoutParams 宽高：若是具体 px 则保持；WRAP 时不改
            (bubble.layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
                // 不强制改宽高，只清掉可能被 minWidth 撑开的请求
                bubble.requestLayout()
            }
        }
        bubble.setTag(R.id.abc_tag_bubble_source, source)
        if (allowDeferred && BubbleDrawablePolicy.needsPostBindRefresh(messageType, messageContent)) {
            postDeferredBubbleBackgroundRefresh(bubble, isSend, messageType, messageContent, msgId)
        }
    }

    private fun postDeferredBubbleBackgroundRefresh(
        bubble: View,
        isSend: Boolean,
        messageType: Int,
        messageContent: String,
        msgId: Long
    ) {
        val refresh = Runnable {
            if ((bubble.getTag(R.id.abc_tag_bubble_msg_id) as? Long) != msgId) return@Runnable
            if ((bubble.getTag(R.id.abc_tag_bubble_msg_type) as? Int) != messageType) return@Runnable
            if ((bubble.getTag(R.id.abc_tag_bubble_is_send) as? Boolean) != isSend) return@Runnable
            refreshTaggedBubbleBackground(
                bubble = bubble,
                isSend = isSend,
                messageType = messageType,
                messageContent = messageContent,
                msgId = msgId,
                allowDeferred = false
            )
            alignBubbleCluster(bubble, isSend)
        }
        bubble.post(refresh)
        bubble.postDelayed(refresh, 96L)
    }

    private fun alignBubbleCluster(bubble: View, isSend: Boolean) {
        val gravity = MessageTimeLayoutPolicy.bubbleClusterGravity(isSend)
        findTimeAnchor(bubble)?.let { anchor ->
            anchor.parent.gravity = gravity
            applyLinearLayoutGravity(anchor.parent, gravity)
            (anchor.child.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                if (params.gravity != gravity) {
                    params.gravity = gravity
                    anchor.child.layoutParams = params
                }
            }
        }
        (bubble.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (params.gravity != gravity) {
                params.gravity = gravity
                bubble.layoutParams = params
            }
        }
    }

    private fun applyLinearLayoutGravity(view: View, gravity: Int) {
        (view.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (params.gravity != gravity) {
                params.gravity = gravity
                view.layoutParams = params
            }
        }
    }

    /**
     * 视频/图片等无气泡媒体消息：找到媒体内容视图的父容器，修正其 gravity。
     * 防止 ViewHolder 从文字消息复用到媒体消息时，残留 alignBubbleCluster 设置的对齐方向。
     */
    private fun alignMediaCluster(itemView: View, messageType: Int, isSend: Boolean) {
        val root = itemView as? ViewGroup ?: return
        val media = findMediaContentView(root, messageType) ?: return
        val gravity = MessageTimeLayoutPolicy.bubbleClusterGravity(isSend)
        findTimeAnchor(media)?.let { anchor ->
            if (anchor.parent.gravity != gravity) {
                anchor.parent.gravity = gravity
            }
            applyLinearLayoutGravity(anchor.parent, gravity)
            return
        }
        var p: Any? = media.parent
        var currentView: View = media
        var depth = 0
        while (p is View && depth < 5) {
            val parentView = p as View
            if (parentView is LinearLayout) {
                if (parentView.gravity != gravity) parentView.gravity = gravity
                applyLinearLayoutGravity(parentView, gravity)
            } else if (parentView is android.widget.RelativeLayout) {
                (currentView.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let { lp ->
                    lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT)
                    lp.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT)
                    if (isSend) {
                        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT)
                    } else {
                        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT)
                    }
                    currentView.layoutParams = lp
                }
            }
            currentView = parentView
            p = parentView.parent
            depth++
        }
    }

    private fun addOrUpdateCustomTimeBelowBubble(
        bubble: View,
        text: String,
        msgId: Long,
        isSend: Boolean,
        options: MessageDetailOptions
    ) {
        if (text.isBlank()) return
        val anchor = findTimeAnchor(bubble) ?: return
        placeTimeBelowInLinear(
            parent = anchor.parent,
            anchorChild = anchor.child,
            text = text,
            msgId = msgId,
            isSend = isSend,
            options = options
        )
    }

    /**
     * 表情 / 图片等：优先竖向 LinearLayout 插在内容下方；
     * RelativeLayout 用 BELOW；FrameLayout 尽量升到竖向父级。
     */
    private fun addOrUpdateCustomTimeBelowMedia(
        mediaView: View,
        text: String,
        msgId: Long,
        isSend: Boolean,
        options: MessageDetailOptions
    ) {
        if (text.isBlank()) return
        val linearAnchor = findTimeAnchor(mediaView)
        if (linearAnchor != null) {
            placeTimeBelowInLinear(
                parent = linearAnchor.parent,
                anchorChild = linearAnchor.child,
                text = text,
                msgId = msgId,
                isSend = isSend,
                options = options
            )
            return
        }
        val target = findEmojiTimeTarget(mediaView) ?: return
        when (target) {
            is LinearLayout -> {
                if (MessageTimeLayoutPolicy.canHostTime(target.orientation)) {
                    // 内容可能是 target 的直接子节点
                    val child = (mediaView.parent as? View)?.takeIf { it.parent == target }
                        ?: mediaView.takeIf { it.parent == target }
                        ?: target.getChildAt((target.childCount - 1).coerceAtLeast(0))
                    if (child != null && child.getTag(R.id.abc_tag_custom_time) != true) {
                        placeTimeBelowInLinear(target, child, text, msgId, isSend, options)
                    } else {
                        addOrUpdateTimeInParent(target, text, msgId, isSend, options)
                    }
                } else {
                    addOrUpdateTimeInParent(target, text, msgId, isSend, options)
                }
            }
            is android.widget.RelativeLayout -> {
                addOrUpdateTimeBelowInRelativeLayout(target, mediaView, text, msgId, isSend, options)
            }
            is FrameLayout -> {
                // 尽量不塞进 Frame 叠层（容易盖住表情）；若无更好父级再退回
                val grand = target.parent as? ViewGroup
                if (grand is LinearLayout && MessageTimeLayoutPolicy.canHostTime(grand.orientation)) {
                    placeTimeBelowInLinear(grand, target, text, msgId, isSend, options)
                } else {
                    addOrUpdateTimeInFrameLayout(target, text, msgId, isSend, options)
                }
            }
            else -> addOrUpdateTimeInParent(target, text, msgId, isSend, options)
        }
    }

    /** 竖向 LinearLayout：保证时间始终在锚点 child 之后（修复表情复用后跑到上方） */
    private fun placeTimeBelowInLinear(
        parent: LinearLayout,
        anchorChild: View,
        text: String,
        msgId: Long,
        isSend: Boolean,
        options: MessageDetailOptions
    ) {
        val context = parent.context
        val anchorIndex = parent.indexOfChild(anchorChild)
        if (anchorIndex < 0) {
            addOrUpdateTimeInParent(parent, text, msgId, isSend, options)
            return
        }
        var oldView = findDirectTaggedTimeView(parent)
        var oldIndex = if (oldView != null) parent.indexOfChild(oldView) else -1
        val moveTo = MessageTimeLayoutPolicy.insertIndexBelowAnchor(
            childCount = parent.childCount,
            anchorIndex = anchorIndex,
            existingTimeIndex = oldIndex
        )
        if (oldView != null && moveTo >= 0) {
            parent.removeView(oldView)
            // remove 后重新计算插入位置
            val newAnchorIndex = parent.indexOfChild(anchorChild)
            val insertAt = if (newAnchorIndex >= 0) {
                min(parent.childCount, newAnchorIndex + 1)
            } else {
                parent.childCount
            }
            val params = (oldView.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(
                    MessageTimeLayoutPolicy.layoutWidth(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            params.width = MessageTimeLayoutPolicy.layoutWidth()
            params.topMargin = dp(context, 4)
            params.gravity = MessageTimeLayoutPolicy.gravity(isSend)
            params.leftMargin = dp(context, options.marginStart(isSend))
            params.rightMargin = dp(context, options.marginEnd(isSend))
            oldView.text = text
            oldView.setTag(R.id.abc_tag_custom_time_msg_id, msgId)
            oldView.gravity = MessageTimeLayoutPolicy.gravity(isSend)
            oldView.textSize = options.textSizeSp
            applyDetailStyle(oldView, options)
            applyClickShowVisibility(oldView, options)
            parent.addView(oldView, insertAt, params)
            return
        }
        if (oldView != null) {
            oldView.text = text
            oldView.setTag(R.id.abc_tag_custom_time_msg_id, msgId)
            oldView.gravity = MessageTimeLayoutPolicy.gravity(isSend)
            oldView.textSize = options.textSizeSp
            applyDetailStyle(oldView, options)
            applyClickShowVisibility(oldView, options)
            (oldView.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = MessageTimeLayoutPolicy.layoutWidth()
                gravity = MessageTimeLayoutPolicy.gravity(isSend)
                leftMargin = dp(context, options.marginStart(isSend))
                rightMargin = dp(context, options.marginEnd(isSend))
            }
            return
        }

        val timeView = createTimeView(context, text, msgId, options)
        val params = LinearLayout.LayoutParams(
            MessageTimeLayoutPolicy.layoutWidth(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(context, 4)
            gravity = MessageTimeLayoutPolicy.gravity(isSend)
            leftMargin = dp(context, options.marginStart(isSend))
            rightMargin = dp(context, options.marginEnd(isSend))
        }
        val insertIndex = min(parent.childCount, anchorIndex + 1)
        parent.addView(timeView, insertIndex, params)
    }

    private fun addOrUpdateTimeInParent(
        parent: ViewGroup,
        text: String,
        msgId: Long,
        isSend: Boolean,
        options: MessageDetailOptions
    ) {
        if (text.isBlank()) return
        val context = parent.context
        val oldView = findDirectTaggedTimeView(parent)
        if (oldView != null) {
            oldView.text = text
            oldView.setTag(R.id.abc_tag_custom_time_msg_id, msgId)
            oldView.gravity = MessageTimeLayoutPolicy.gravity(isSend)
            oldView.textSize = options.textSizeSp
            applyDetailStyle(oldView, options)
            applyClickShowVisibility(oldView, options)
            return
        }
        val timeView = createTimeView(context, text, msgId, options)
        val params: ViewGroup.LayoutParams = when (parent) {
            is android.widget.RelativeLayout -> {
                android.widget.RelativeLayout.LayoutParams(
                    MessageTimeLayoutPolicy.layoutWidth(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (isSend) addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT)
                    else addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT)
                    topMargin = dp(context, 4)
                    leftMargin = dp(context, options.marginStart(isSend))
                    rightMargin = dp(context, options.marginEnd(isSend))
                }
            }
            is FrameLayout -> {
                FrameLayout.LayoutParams(
                    MessageTimeLayoutPolicy.layoutWidth(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.BOTTOM or (if (isSend) android.view.Gravity.END else android.view.Gravity.START)
                    topMargin = dp(context, 4)
                    leftMargin = dp(context, options.marginStart(isSend))
                    rightMargin = dp(context, options.marginEnd(isSend))
                }
            }
            else -> {
                LinearLayout.LayoutParams(
                    MessageTimeLayoutPolicy.layoutWidth(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 4)
                    gravity = MessageTimeLayoutPolicy.gravity(isSend)
                    leftMargin = dp(context, options.marginStart(isSend))
                    rightMargin = dp(context, options.marginEnd(isSend))
                }
            }
        }
        parent.addView(timeView, params)
    }

    private fun addOrUpdateTimeInFrameLayout(
        parent: FrameLayout,
        text: String,
        msgId: Long,
        isSend: Boolean,
        options: MessageDetailOptions
    ) {
        if (text.isBlank()) return
        val context = parent.context
        // Frame 叠层时贴底，避免盖住表情中部/上方
        val g = android.view.Gravity.BOTTOM or
            if (isSend) android.view.Gravity.END else android.view.Gravity.START
        val oldView = findDirectTaggedTimeView(parent)
        if (oldView != null) {
            oldView.text = text
            oldView.setTag(R.id.abc_tag_custom_time_msg_id, msgId)
            oldView.textSize = options.textSizeSp
            applyDetailStyle(oldView, options)
            applyClickShowVisibility(oldView, options)
            (oldView.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.gravity = g
                params.topMargin = 0
                params.bottomMargin = dp(context, 2)
                params.leftMargin = dp(context, options.marginStart(isSend))
                params.rightMargin = dp(context, options.marginEnd(isSend))
                oldView.layoutParams = params
            }
            return
        }
        val timeView = createTimeView(context, text, msgId, options)
        val params = FrameLayout.LayoutParams(
            MessageTimeLayoutPolicy.layoutWidth(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = g
            bottomMargin = dp(context, 2)
            leftMargin = dp(context, options.marginStart(isSend))
            rightMargin = dp(context, options.marginEnd(isSend))
        }
        parent.addView(timeView, params)
    }

    private fun addOrUpdateTimeBelowInRelativeLayout(
        parent: android.widget.RelativeLayout,
        anchorView: View,
        text: String,
        msgId: Long,
        isSend: Boolean,
        options: MessageDetailOptions
    ) {
        if (text.isBlank()) return
        // 表情节点常无 id，需要先分配，否则 BELOW 规则无效 → 时间叠到上方
        var anchorId = anchorView.id
        if (anchorId == View.NO_ID) {
            anchorId = View.generateViewId()
            anchorView.id = anchorId
        }
        val context = parent.context
        fun applyBelowParams(view: TextView) {
            val params = (view.layoutParams as? android.widget.RelativeLayout.LayoutParams)
                ?: android.widget.RelativeLayout.LayoutParams(
                    MessageTimeLayoutPolicy.layoutWidth(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            params.addRule(android.widget.RelativeLayout.BELOW, anchorId)
            params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT, 0)
            params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT, 0)
            if (isSend) params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT)
            else params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT)
            params.topMargin = dp(context, 4)
            params.leftMargin = dp(context, options.marginStart(isSend))
            params.rightMargin = dp(context, options.marginEnd(isSend))
            view.layoutParams = params
        }
        val oldView = findDirectTaggedTimeView(parent)
        if (oldView != null) {
            oldView.text = text
            oldView.setTag(R.id.abc_tag_custom_time_msg_id, msgId)
            oldView.textSize = options.textSizeSp
            applyDetailStyle(oldView, options)
            applyClickShowVisibility(oldView, options)
            applyBelowParams(oldView)
            return
        }
        val timeView = createTimeView(context, text, msgId, options)
        val params = android.widget.RelativeLayout.LayoutParams(
            MessageTimeLayoutPolicy.layoutWidth(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        timeView.layoutParams = params
        applyBelowParams(timeView)
        parent.addView(timeView)
    }

    private fun findEmojiTimeTarget(emojiView: View): ViewGroup? {
        val parent = emojiView.parent as? ViewGroup ?: return null
        if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) return parent
        if (parent is android.widget.RelativeLayout) return parent
        if (parent is FrameLayout) {
            val grand = parent.parent as? ViewGroup
            if (grand is LinearLayout && grand.orientation == LinearLayout.VERTICAL) return grand
            if (grand is android.widget.RelativeLayout) return grand
            val ll = grand?.let { findVerticalLinearLayout(it) }
            if (ll != null) return ll
        }
        val ll = parent.let { findVerticalLinearLayout(it) }
        if (ll != null) return ll
        val grand = parent.parent as? ViewGroup
        if (grand is LinearLayout && grand.orientation == LinearLayout.VERTICAL) return grand
        if (grand is android.widget.RelativeLayout) return grand
        return null
    }

    private fun findVerticalLinearLayout(root: ViewGroup): LinearLayout? {
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child is LinearLayout && child.orientation == LinearLayout.VERTICAL) {
                return child
            }
            if (child is ViewGroup) {
                findVerticalLinearLayout(child)?.let { return it }
            }
        }
        return null
    }

    private fun createTimeView(context: Context, textValue: String, msgId: Long, options: MessageDetailOptions): TextView {
        return TextView(context).apply {
            setTag(R.id.abc_tag_custom_time, true)
            setTag(R.id.abc_tag_custom_time_msg_id, msgId)
            text = textValue
            textSize = options.textSizeSp
            includeFontPadding = false
            gravity = android.view.Gravity.START
            applyDetailStyle(this, options)
            applyClickShowVisibility(this, options)
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                var current: View? = v.parent as? View
                var depth = 0
                while (current != null && depth < 5) {
                    val text = collectVisibleText(current)
                    if (text.contains("撤回") || MessageTimeLayoutPolicy.isSystemNoticeText(text)) {
                        v.visibility = View.GONE
                        (v.parent as? ViewGroup)?.let { p -> p.post { p.removeView(v) } }
                        break
                    }
                    current = current.parent as? View
                    depth++
                }
            }
        }
    }

    private fun applyDetailStyle(view: TextView, options: MessageDetailOptions) {
        val night = (view.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val configured = options.resolveTextColor(night)
        val color = if (!night && Color.alpha(configured) < 0xE6) {
            Color.argb(0xE6, Color.red(configured), Color.green(configured), Color.blue(configured))
        } else {
            configured
        }
        val visibleColor = if (!night &&
            (Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f) < 120f
        ) {
            Color.argb(0xF2, 0xF5, 0xF5, 0xF5)
        } else {
            color
        }
        view.setTextColor(visibleColor)
        view.alpha = 1f
        view.letterSpacing = 0f
        if (night) {
            view.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            view.setShadowLayer(1.2f, 0f, 0.5f, Color.parseColor("#44000000"))
        } else {
            view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            view.setShadowLayer(1.15f, 0f, 0.45f, Color.parseColor("#66000000"))
        }
    }

    /** 点击显示模式：默认隐藏；关闭后恢复可见 */
    private fun applyClickShowVisibility(view: TextView, options: MessageDetailOptions) {
        if (options.clickToShow) {
            // 若用户已点开（hidden=false），复用行时保持可见
            val wasHidden = view.getTag(R.id.abc_tag_custom_time_hidden)
            if (wasHidden != false) {
                view.visibility = View.GONE
                view.setTag(R.id.abc_tag_custom_time_hidden, true)
            }
        } else {
            view.visibility = View.VISIBLE
            view.setTag(R.id.abc_tag_custom_time_hidden, false)
        }
    }

    /** click_show：点气泡切换详情可见性 */
    private fun ensureDetailClickToggle(host: View, options: MessageDetailOptions) {
        if (!options.clickToShow) return
        if (host.getTag(R.id.abc_tag_detail_click_listener) == true) return
        host.setTag(R.id.abc_tag_detail_click_listener, true)
        host.setOnClickListener {
            val root = (host.parent as? ViewGroup) ?: (host.rootView as? ViewGroup) ?: return@setOnClickListener
            toggleTaggedTimeViews(root)
        }
    }

    private fun toggleTaggedTimeViews(root: ViewGroup) {
        fun walk(v: View) {
            if (v.getTag(R.id.abc_tag_custom_time) == true) {
                val show = v.visibility != View.VISIBLE
                v.visibility = if (show) View.VISIBLE else View.GONE
                v.setTag(R.id.abc_tag_custom_time_hidden, !show)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
    }

    private fun findDirectTaggedTimeView(parent: ViewGroup): TextView? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.getTag(R.id.abc_tag_custom_time) == true && child is TextView) {
                return child
            }
        }
        return null
    }

    private fun ensureSwipeListener(view: View, targetRow: View) {
        view.setTag(R.id.abc_tag_swipe_target_row, targetRow)
        if (view.getTag(R.id.abc_tag_swipe_listener) == true) return
        view.setTag(R.id.abc_tag_swipe_listener, true)
        view.setOnTouchListener { touched, event ->
            val row = touched.getTag(R.id.abc_tag_swipe_target_row) as? View ?: targetRow
            val message = touched.getTag(R.id.abc_tag_message_object)
                ?: row.getTag(R.id.abc_tag_message_object)
                ?: return@setOnTouchListener false
            handleSwipe(row, message, event)
        }
    }

    private fun ensureSwipeListenersForBubbleCluster(bubble: View, targetRow: View, message: Any) {
        val anchor = findTimeAnchor(bubble) ?: return
        anchor.parent.setTag(R.id.abc_tag_message_object, message)
        ensureSwipeListener(anchor.parent, targetRow)
        anchor.child.setTag(R.id.abc_tag_message_object, message)
        ensureSwipeListener(anchor.child, targetRow)
        val child = anchor.child
        if (child is ViewGroup) {
            for (i in 0 until child.childCount) {
                val grandChild = child.getChildAt(i)
                grandChild.setTag(R.id.abc_tag_message_object, message)
                ensureSwipeListener(grandChild, targetRow)
            }
        }
    }

    private fun ensureSwipeListenerForRowHotZone(bubble: View, targetRow: View, message: Any) {
        val hotZone = findSwipeHotZone(bubble, targetRow) ?: return
        hotZone.setTag(R.id.abc_tag_message_object, message)
        ensureSwipeListener(hotZone, targetRow)
        if (firstSwipeHotZoneLogged.compareAndSet(false, true)) {
            xlog(
                "swipe hot zone=${hotZone.javaClass.name} width=${viewWidth(hotZone)} " +
                    "target=${targetRow.javaClass.name} targetWidth=${viewWidth(targetRow)} bubbleWidth=${viewWidth(bubble)}"
            )
        }
    }

    private fun findSwipeHotZone(bubble: View, targetRow: View): View? {
        var parent = bubble.parent
        var depth = 0
        var candidate: View? = targetRow
        var candidateWidth = viewWidth(targetRow)
        while (parent is ViewGroup && depth < 6) {
            val className = parent.javaClass.name
            if (isChatListContainer(className)) break
            val parentWidth = viewWidth(parent)
            if (parentWidth >= candidateWidth) {
                candidate = parent
                candidateWidth = parentWidth
            }
            parent = parent.parent
            depth++
        }
        return candidate
    }

    private fun tagSwipeRowContainer(itemView: View, bubble: View, message: Any) {
        val rowClass = swipeRowContainerClass ?: return
        val rowContainer = findAncestorOrSelf(itemView, rowClass) ?: findAncestorOrSelf(bubble, rowClass) ?: return
        rowContainer.setTag(R.id.abc_tag_message_object, message)
        rowContainer.setTag(R.id.abc_tag_message_holder, itemView.getTag(R.id.abc_tag_message_holder))
        rowContainer.setTag(R.id.abc_tag_message_adapter, itemView.getTag(R.id.abc_tag_message_adapter))
        rowContainer.setTag(R.id.abc_tag_swipe_target_row, rowContainer)
    }

    private fun findAncestorOrSelf(view: View, clazz: Class<*>): View? {
        var current: View? = view
        var depth = 0
        while (current != null && depth < 8) {
            if (clazz.isInstance(current)) return current
            val parent = current.parent
            if (parent !is View) return null
            if (isChatListContainer(parent.javaClass.name)) return null
            current = parent
            depth++
        }
        return null
    }

    private fun viewWidth(view: View): Int {
        return max(view.width, view.measuredWidth)
    }

    private fun listWideSwipeTarget(container: ViewGroup, event: MotionEvent): SwipeTarget? {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val target = findTaggedRowAtRawY(container, event.rawY) ?: return null
            container.setTag(R.id.abc_tag_swipe_active_row, target.row)
            container.setTag(R.id.abc_tag_swipe_active_message, target.message)
            return target
        }
        val row = container.getTag(R.id.abc_tag_swipe_active_row) as? View ?: return null
        val message = container.getTag(R.id.abc_tag_swipe_active_message) ?: return null
        return SwipeTarget(row, message)
    }

    private fun clearListSwipeTarget(container: View) {
        container.setTag(R.id.abc_tag_swipe_active_row, null)
        container.setTag(R.id.abc_tag_swipe_active_message, null)
    }

    private fun findTaggedRowAtRawY(root: ViewGroup, rawY: Float): SwipeTarget? {
        val rowClass = swipeRowContainerClass
        val candidates = ArrayList<SwipeTargetCandidate>()
        collectTaggedRows(root, rawY, rowClass, candidates)
        return candidates.minWithOrNull(
            compareBy<SwipeTargetCandidate> { it.distanceToCenter }
                .thenBy { it.height }
        )?.target
    }

    private fun collectTaggedRows(
        view: View,
        rawY: Float,
        rowClass: Class<*>?,
        out: MutableList<SwipeTargetCandidate>
    ) {
        if (!view.isShown || view.height <= 0) return
        val message = view.getTag(R.id.abc_tag_message_object)
        val isRow = rowClass?.isInstance(view) == true || message != null
        if (isRow && message != null) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val top = location[1]
            val bottom = top + view.height
            if (rawY >= top && rawY <= bottom) {
                val targetRow = view.getTag(R.id.abc_tag_swipe_target_row) as? View ?: view
                out.add(
                    SwipeTargetCandidate(
                        target = SwipeTarget(targetRow, message),
                        height = view.height,
                        distanceToCenter = kotlin.math.abs(rawY - ((top + bottom) / 2f))
                    )
                )
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTaggedRows(view.getChildAt(i), rawY, rowClass, out)
            }
        }
    }

    private fun handleSwipe(row: View, message: Any, event: MotionEvent): Boolean {
        if (isMediaViewerContext(row.context)) return false
        val opts = AppFeatureConfig.load()
        if (!opts.swipeQuote && !opts.swipeRepeat) return false
        var state = row.getTag(R.id.abc_tag_swipe_state) as? SwipeState
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                row.animate().cancel()
                row.setTag(
                    R.id.abc_tag_swipe_state,
                    SwipeState(
                        initialRawX = event.rawX,
                        initialRawY = event.rawY,
                        touchSlop = ViewConfiguration.get(row.context).scaledTouchSlop
                    )
                )
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (state == null) return false
                val deltaX = event.rawX - state.initialRawX
                val deltaY = event.rawY - state.initialRawY
                if (!state.dragging && MessageSwipePolicy.shouldStartDrag(
                        deltaX,
                        deltaY,
                        state.touchSlop,
                        allowLeft = opts.swipeQuote,
                        allowRight = opts.swipeRepeat
                    )
                ) {
                    state.dragging = true
                    row.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (state.dragging) {
                    row.translationX = MessageSwipePolicy.clampTranslation(
                        deltaX,
                        dp(row.context, 120),
                        allowLeft = opts.swipeQuote,
                        allowRight = opts.swipeRepeat
                    )
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (state == null) return false
                row.setTag(R.id.abc_tag_swipe_state, null)
                row.parent?.requestDisallowInterceptTouchEvent(false)
                if (!state.dragging) return false

                val finalDeltaX = event.rawX - state.initialRawX
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    // 阈值略降，减少「滑了但不够」导致的偶发失效
                    when (
                        MessageSwipePolicy.resolveAction(
                            finalDeltaX,
                            dp(row.context, 32),
                            allowLeft = opts.swipeQuote,
                            allowRight = opts.swipeRepeat
                        )
                    ) {
                        MessageSwipePolicy.Direction.Left -> {
                            xlog("left swipe quote delta=${finalDeltaX.toInt()}")
                            triggerQuote(row, message)
                        }
                        MessageSwipePolicy.Direction.Right -> {
                            xlog("right swipe repeat delta=${finalDeltaX.toInt()}")
                            triggerRepeat(message)
                        }
                        MessageSwipePolicy.Direction.None -> Unit
                    }
                }
                row.animate().translationX(0f).setDuration(180L).start()
                return true
            }
        }
        return false
    }

    private fun triggerQuote(row: View?, message: Any) {
        val currentMsg = resolveCurrentMsgForQuote(row, message)
        val footerView = chatFooter as? View
        // 先清理一次旧引用态，避免第二次左滑时 Footer 仍停留在上一条引用导致失败。
        if (QuoteDeleteClearHook.clearCurrentQuote("swipe.quote.prepare") && footerView != null) {
            footerView.post {
                if (invokeFooterQuote(currentMsg)) return@post
                if (invokeComponentQuote(currentMsg)) return@post
                footerView.postDelayed({
                    if (invokeFooterQuote(resolveCurrentMsgForQuote(row, currentMsg))) return@postDelayed
                    if (invokeComponentQuote(currentMsg)) return@postDelayed
                    xlog("left swipe quote retry after clear failed for ${currentMsg.javaClass.name}")
                }, 80L)
            }
            return
        }

        // 优先 Footer（输入栏引用态），再组件；失败则短延迟重试一次（Footer 偶发尚未挂上）
        if (invokeFooterQuote(currentMsg)) return
        if (invokeComponentQuote(currentMsg)) return
        if (footerView != null) {
            footerView.post {
                val retryMsg = resolveCurrentMsgForQuote(row, currentMsg)
                if (invokeFooterQuote(retryMsg)) return@post
                if (invokeComponentQuote(retryMsg)) return@post
                xlog("left swipe quote retry failed for ${retryMsg.javaClass.name}")
            }
            return
        }
        xlog("left swipe quote target not ready for ${currentMsg.javaClass.name}")
    }

    private fun resolveCurrentMsgForQuote(row: View?, fallback: Any): Any {
        val method = getCurrentMsgMethod ?: return fallback
        val holders = candidateObjects(row, R.id.abc_tag_message_holder, fallback)
        val adapters = candidateObjects(row, R.id.abc_tag_message_adapter, null)
        for (holder in holders) {
            for (adapter in adapters) {
                val msg = invokeGetCurrentMsg(method, holder, adapter)
                if (msg != null) {
                    xlog("resolved current quote msg via ${method.name}: ${msg.javaClass.name}")
                    return msg
                }
            }
        }
        return fallback
    }

    private fun candidateObjects(row: View?, tagId: Int, fallback: Any?): List<Any?> {
        val out = ArrayList<Any?>()
        fun add(value: Any?) {
            if (out.none { it === value }) out += value
        }
        add(row?.getTag(tagId))
        var v: View? = row
        var depth = 0
        while (v != null && depth < 5) {
            add(v.getTag(tagId))
            v = v.parent as? View
            depth++
        }
        add(fallback)
        add(null)
        return out
    }

    private fun invokeGetCurrentMsg(method: Method, first: Any?, second: Any?): Any? {
        val p = method.parameterTypes
        return runCatching {
            method.isAccessible = true
            when (p.size) {
                1 -> {
                    if (first == null || !wrap(p[0]).isInstance(first)) return@runCatching null
                    method.invoke(null, first)
                }
                2 -> {
                    if (first == null || second == null) return@runCatching null
                    if (!wrap(p[0]).isInstance(first) || !wrap(p[1]).isInstance(second)) return@runCatching null
                    method.invoke(null, first, second)
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun wrap(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }

    /**
     * 右滑复读（swipe_repeat 意图）。
     * 优先走引用组件/Footer 上可接受消息对象的发送类方法；失败则仅打日志（避免误发）。
     */
    private fun triggerRepeat(message: Any) {
        if (invokeRepeatOnTarget(chatFooter, message, "ChatFooter")) return
        if (invokeRepeatOnTarget(quoteComponent, message, "quoteComponent")) return
        // 兜底：再尝试一次引用（部分版本引用后可手动发送；至少给出可操作态）
        if (invokeFooterQuote(message) || invokeComponentQuote(message)) {
            xlog("right swipe fallback to quote for ${message.javaClass.name}")
            return
        }
        xlog("right swipe repeat not ready for ${message.javaClass.name}")
    }

    private fun invokeRepeatOnTarget(target: Any?, message: Any, label: String): Boolean {
        if (target == null) return false
        val methods = target.javaClass.allMethods()
            .asSequence()
            .filter { method ->
                method.parameterTypes.size in 1..2 &&
                    !method.parameterTypes[0].isPrimitive &&
                    method.parameterTypes[0].isAssignableFrom(message.javaClass) &&
                    (
                        method.name.contains("send", ignoreCase = true) ||
                            method.name.contains("resend", ignoreCase = true) ||
                            method.name.contains("forward", ignoreCase = true) ||
                            method.name.contains("repeat", ignoreCase = true) ||
                            method.name.length <= 3
                        )
            }
            .sortedBy { it.name.length }
            .take(8)
            .toList()
        for (method in methods) {
            val ok = runCatching {
                method.isAccessible = true
                val args = if (method.parameterTypes.size == 1) {
                    arrayOf<Any?>(message)
                } else {
                    arrayOf<Any?>(message, null)
                }
                method.invoke(target, *args)
                xlog("triggered repeat via $label.${method.name}")
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun invokeFooterQuote(message: Any): Boolean {
        val footer = chatFooter ?: return false
        for (candidate in quoteMethodCandidates(footer, message)) {
            runCatching {
                candidate.method.isAccessible = true
                val result = candidate.method.invoke(footer, *candidate.args)
                if (result == false) {
                    xlog("ChatFooter quote returned false: ${candidate.method.name}/${candidate.args.size}")
                    false
                } else {
                    xlog("triggered quote via ChatFooter.${candidate.method.name}/${candidate.args.size}")
                    true
                }
            }.getOrElse {
                xlog("ChatFooter quote failed: ${it.message}")
                false
            }.let { success ->
                if (success) return true
            }
        }
        return false
    }

    private fun invokeComponentQuote(message: Any): Boolean {
        val component = quoteComponent ?: return false
        for (candidate in quoteMethodCandidates(component, message)) {
            runCatching {
                candidate.method.isAccessible = true
                val result = candidate.method.invoke(component, *candidate.args)
                if (result == false) {
                    xlog("component quote returned false: ${candidate.method.name}/${candidate.args.size}")
                    false
                } else {
                    xlog("triggered quote via ${component.javaClass.name}.${candidate.method.name}/${candidate.args.size}")
                    true
                }
            }.getOrElse {
                xlog("component quote failed: ${it.message}")
                false
            }.let { success ->
                if (success) return true
            }
        }
        return false
    }

    private fun quoteMethodCandidates(target: Any, message: Any): List<QuoteMethodCandidate> {
        return target.javaClass.allMethods()
            .asSequence()
            .filter { method ->
                method.name != "isLayoutModeOptical" &&
                    method.parameterTypes.isNotEmpty() &&
                    !method.parameterTypes[0].isPrimitive &&
                    method.parameterTypes[0].isAssignableFrom(message.javaClass) &&
                    (
                        method.parameterTypes.size == 1 ||
                            (method.parameterTypes.size == 2 && !method.parameterTypes[1].isPrimitive)
                        ) &&
                    MessageSwipePolicy.quoteMethodScore(
                        method.name,
                        method.parameterTypes.size,
                        isBooleanReturn(method)
                    ) > 0
            }
            .sortedWith(
                compareByDescending<Method> {
                    MessageSwipePolicy.quoteMethodScore(
                        it.name,
                        it.parameterTypes.size,
                        isBooleanReturn(it)
                    )
                }
                    .thenBy { it.parameterTypes.size }
                    .thenBy { it.name.length }
            )
            .map { method ->
                val args = if (method.parameterTypes.size == 1) arrayOf<Any?>(message) else arrayOf<Any?>(message, null)
                QuoteMethodCandidate(method, args)
            }
            .toList()
    }

    private fun isBooleanReturn(method: Method): Boolean {
        return method.returnType == Boolean::class.javaPrimitiveType || method.returnType == Boolean::class.javaObjectType
    }

    private val itemViewFieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Field>()

    private fun findItemView(holder: Any): View? {
        val clazz = holder.javaClass
        val cachedField = itemViewFieldCache[clazz]
        if (cachedField != null) {
            return runCatching { cachedField.get(holder) as? View }.getOrNull()
        }

        val field = clazz.allFields().firstOrNull { View::class.java.isAssignableFrom(it.type) && it.name == "itemView" }
            ?: clazz.allFields().firstOrNull { View::class.java.isAssignableFrom(it.type) }
        
        if (field != null) {
            field.isAccessible = true
            itemViewFieldCache[clazz] = field
            return runCatching { field.get(holder) as? View }.getOrNull()
        }
        return null
    }

    private fun findBubbleView(root: View, classLoader: ClassLoader): View? {
        val bubbleId = cachedHostId(classLoader, "c3g")
        if (bubbleId != 0) {
            root.findViewById<View>(bubbleId)
                ?.takeIf { BubbleDrawablePolicy.isTextBubbleClass(it.javaClass.name) }
                ?.let { return it }
        }
        findFirstView(root) { view ->
            BubbleDrawablePolicy.isTextBubbleClass(view.javaClass.name)
        }?.let { return it }
        // 语音/通话等非文本消息没有 MMNeat 气泡，找它们的内容气泡容器
        return findVoiceCallBubbleView(root)
    }

    /**
     * 语音：波形 AnimImageView 的直接父（内层气泡，绝不往上爬，否则会套到整块内容区导致气泡过大）。
     * 通话：含通话文案/图标的带背景容器；兼容居中通话卡片。
     */
    private fun findVoiceCallBubbleView(root: View): View? {
        // 1) 语音：AnimImageView → 只取直接父 ViewGroup（时长+波形那一层）
        val anim = findFirstView(root) { it.javaClass.name.contains("AnimImageView") }
        if (anim != null) {
            val direct = anim.parent as? ViewGroup
            if (direct != null &&
                direct !== root &&
                !direct.javaClass.name.contains("MaskLayout") &&
                !containsViewMatching(direct) { it.javaClass.name.contains("ChattingAvatarImageView") }
            ) {
                return direct
            }
        }
        // 2) 通话记录：文案匹配（语音通话/视频通话/通话时长/未接等）
        val callLabel = findFirstView(root) { v ->
            if (v !is TextView || v.visibility != View.VISIBLE) return@findFirstView false
            val t = v.text?.toString().orEmpty()
            if (t.isBlank() || t.length > 40) return@findFirstView false
            t.contains("通话") || t.contains("呼叫") || t.contains("未接") ||
                t.contains("已取消") || t.contains("对方无应答") ||
                t.contains("Call", ignoreCase = true) || t.contains("voip", ignoreCase = true)
        }
        if (callLabel != null) {
            // 先找带背景的祖先（真正画气泡的那层）
            var p: android.view.ViewParent? = callLabel.parent
            var hops = 0
            var fallback: ViewGroup? = null
            while (p is ViewGroup && hops < 8) {
                if (p === root) break
                if (p.javaClass.name.contains("MaskLayout")) {
                    p = p.parent; hops++; continue
                }
                if (containsViewMatching(p) { it.javaClass.name.contains("ChattingAvatarImageView") }) {
                    p = p.parent; hops++; continue
                }
                val bg = p.background
                val hasBubbleBg = bg is android.graphics.drawable.NinePatchDrawable ||
                    bg is android.graphics.drawable.GradientDrawable ||
                    bg is android.graphics.drawable.LayerDrawable ||
                    bg is android.graphics.drawable.StateListDrawable
                if (hasBubbleBg) return p
                if (fallback == null && p.childCount in 1..10) fallback = p
                p = p.parent
                hops++
            }
            if (fallback != null) return fallback
        }
        // 3) 通话图标 ImageView（内容描述/类名含 voip）祖先
        val voipIcon = findFirstView(root) { v ->
            val n = v.javaClass.name
            if (n.contains("voip", ignoreCase = true) || n.contains("Voip")) return@findFirstView true
            val cd = v.contentDescription?.toString().orEmpty()
            cd.contains("通话") || cd.contains("voip", ignoreCase = true)
        }
        if (voipIcon != null) {
            var p: android.view.ViewParent? = voipIcon.parent
            var hops = 0
            while (p is ViewGroup && hops < 6) {
                if (p !== root && p.background != null &&
                    !containsViewMatching(p) { it.javaClass.name.contains("ChattingAvatarImageView") }
                ) return p
                p = p.parent
                hops++
            }
        }
        // 4) 兜底：内容容器里排除头像后、带背景的第一个可见子容器
        val container = findChatContentContainer(root) ?: return null
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child !is ViewGroup || child.visibility != View.VISIBLE) continue
            if (child.javaClass.name.contains("MaskLayout")) continue
            if (containsViewMatching(child) { it.javaClass.name.contains("ChattingAvatarImageView") }) continue
            if (child.background != null || child.childCount > 0) return child
        }
        return null
    }

    /** 找聊天行的内容容器：包含头像(ChattingAvatarImageView)的那个 ViewGroup。 */
    private fun findChatContentContainer(root: View): ViewGroup? {
        val avatar = findFirstView(root) { it.javaClass.name.contains("ChattingAvatarImageView") } ?: return null
        var p: android.view.ViewParent? = avatar.parent
        var hops = 0
        while (p is ViewGroup && hops < 4) {
            // 头像的父容器（MaskLayout）再往上一层通常是内容容器
            if (p.childCount >= 2) return p
            p = p.parent
            hops++
        }
        return null
    }

    private fun containsViewMatching(root: View, predicate: (View) -> Boolean): Boolean {
        if (predicate(root)) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (containsViewMatching(root.getChildAt(i), predicate)) return true
            }
        }
        return false
    }

    /**
     * 语音/通话气泡套壳修复：微信把原生气泡填充色画在内部子视图的 GradientDrawable 上，
     * 外层再套自定义 9.png 就会形成“皮肤外壳 + 原生内芯”。清除内部填充色块，只留自定义皮肤。
     * 保留 ImageView 前景（波形帧/通话图标），只清 background。
     */
    private fun clearInnerBubbleFills(root: View) {
        fun visit(v: View, depth: Int) {
            if (depth > 5) return
            // 根容器本身的背景是我们刚设的皮肤，不要清
            if (v !== root) {
                val bg = v.background
                if (bg is android.graphics.drawable.GradientDrawable ||
                    bg is android.graphics.drawable.ColorDrawable ||
                    bg is android.graphics.drawable.ShapeDrawable
                ) {
                    v.background = null
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i), depth + 1)
                }
            }
        }
        visit(root, 0)
    }

    private fun addTimeToNonBubbleMessage(
        holder: Any,
        itemView: View,
        text: String,
        msgId: Long,
        isSend: Boolean,
        messageType: Int,
        options: MessageDetailOptions
    ) {
        if (itemView !is ViewGroup) return
        if (BubbleDrawablePolicy.isAppMessageType(messageType) &&
            addTimeUsingHolderContentAnchor(holder, itemView, text, msgId, isSend, options)
        ) {
            return
        }
        // 优先表情节点（避免走错内容子 View 导致时间在上方）
        findEmojiView(itemView, itemView.context.classLoader)?.let { emoji ->
            addOrUpdateCustomTimeBelowMedia(emoji, text, msgId, isSend, options)
            return
        }
        findMediaContentView(itemView, messageType)?.let { media ->
            addOrUpdateCustomTimeBelowMedia(media, text, msgId, isSend, options)
            return
        }
        var innerLayout: ViewGroup? = null
        for (i in itemView.childCount - 1 downTo 0) {
            val child = itemView.getChildAt(i)
            if (child is ViewGroup && child.childCount >= 2) {
                innerLayout = child
                break
            }
        }
        if (innerLayout == null) return
        if (innerLayout is LinearLayout && innerLayout.orientation == LinearLayout.VERTICAL) {
            val contentView = findContentChild(innerLayout, itemView)
            if (contentView != null) {
                addOrUpdateCustomTimeBelowMedia(contentView, text, msgId, isSend, options)
            } else {
                addOrUpdateTimeInParent(innerLayout, text, msgId, isSend, options)
            }
            return
        }
        for (i in innerLayout.childCount - 1 downTo 0) {
            val child = innerLayout.getChildAt(i)
            if (child is LinearLayout && child.orientation == LinearLayout.VERTICAL) {
                val contentView = findContentChild(child, itemView)
                if (contentView != null) {
                    addOrUpdateCustomTimeBelowMedia(contentView, text, msgId, isSend, options)
                } else {
                    addOrUpdateTimeInParent(child, text, msgId, isSend, options)
                }
                return
            }
        }
    }

    private fun dumpRowTree(view: View, depth: Int) {
        if (depth > 6) return
        val bg = view.background?.javaClass?.name ?: "none"
        val vis = when (view.visibility) {
            View.VISIBLE -> "V"
            View.INVISIBLE -> "I"
            else -> "G"
        }
        val idName = if (view.id != View.NO_ID) view.id.toString() else "-"
        xlog("${"".padEnd(depth * 2, ' ')}v$depth ${view.javaClass.name} vis=$vis bg=$bg id=$idName")
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                try {
                    dumpRowTree(view.getChildAt(i), depth + 1)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun findMediaContentView(root: ViewGroup, messageType: Int): View? {
        val rawType = messageType and 0xFFFF
        if (rawType !in setOf(3, 43, 48, 62, 47)) return null
        val candidates = ArrayList<View>()
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE || view.getTag(R.id.abc_tag_custom_time) == true) return
            if (view is ImageView) candidates += view
            if (view is ViewGroup) {
                // 部分视频/小视频外层是 View / ViewGroup 节点
                val name = view.javaClass.name
                if (name.contains("Video") || name.contains("Media") || name.contains("Thumb")) {
                    candidates += view
                }
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(root)
        val minSide = dp(root.context, 64)
        return candidates
            .filter { max(it.width, it.layoutParams?.width ?: 0) >= minSide && max(it.height, it.layoutParams?.height ?: 0) >= minSide }
            .maxByOrNull {
                max(it.width, it.layoutParams?.width ?: 0).toLong() *
                    max(it.height, it.layoutParams?.height ?: 0).toLong()
            }
    }

    private fun addTimeUsingHolderContentAnchor(
        holder: Any,
        itemView: View,
        text: String,
        msgId: Long,
        isSend: Boolean,
        options: MessageDetailOptions
    ): Boolean {
        val anchor = findHolderContentAnchor(holder, itemView) ?: return false
        val parent = anchor.parent as? ViewGroup ?: return false
        return when (parent) {
            is LinearLayout -> {
                if (!MessageTimeLayoutPolicy.canHostTime(parent.orientation)) return false
                placeTimeBelowInLinear(parent, anchor, text, msgId, isSend, options)
                true
            }
            is android.widget.RelativeLayout -> {
                addOrUpdateTimeBelowInRelativeLayout(parent, anchor, text, msgId, isSend, options)
                true
            }
            else -> false
        }
    }

    private fun findHolderContentAnchor(holder: Any, itemView: View): View? {
        val candidates = ArrayList<View>()
        for (field in holder.javaClass.allFields()) {
            if (!View::class.java.isAssignableFrom(field.type)) continue
            val name = field.name.lowercase()
            if (name == "timetv" ||
                name == "avatariv" ||
                name == "usertv" ||
                name.contains("time") ||
                name.contains("avatar") ||
                name.contains("history") ||
                name.contains("nomore") ||
                name.contains("mask") ||
                name.contains("checkbox") ||
                name.contains("check")
            ) {
                continue
            }
            val view = runCatching {
                field.isAccessible = true
                field.get(holder) as? View
            }.getOrNull() ?: continue
            if (!view.isShown || view.getTag(R.id.abc_tag_custom_time) == true) continue
            if (!isDescendantOf(view, itemView)) continue
            val parent = view.parent
            if (parent !is LinearLayout && parent !is android.widget.RelativeLayout) continue
            if (holderViewArea(view) <= 0L) continue
            candidates += view
        }
        return candidates.maxByOrNull { holderViewArea(it) }
    }

    private fun holderViewArea(view: View): Long {
        val width = max(max(view.width, view.measuredWidth), view.layoutParams?.width ?: 0)
        val height = max(max(view.height, view.measuredHeight), view.layoutParams?.height ?: 0)
        return if (width > 0 && height > 0) width.toLong() * height.toLong() else 0L
    }

    private fun isDescendantOf(view: View, root: View): Boolean {
        var current: Any? = view
        var depth = 0
        while (current is View && depth < 16) {
            if (current === root) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun findContentChild(parent: ViewGroup, itemView: View): View? {
        findEmojiView(itemView, itemView.context.classLoader)?.let { return it }
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (child.getTag(R.id.abc_tag_custom_time) == true) continue
            if (child.getTag(R.id.abc_tag_message_object) != null) continue
            if (child is ViewGroup && child.childCount > 0) continue
            return child
        }
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (child.getTag(R.id.abc_tag_custom_time) == true) continue
            if (child.getTag(R.id.abc_tag_message_object) != null) continue
            return child
        }
        return null
    }

    private fun findEmojiView(root: View, classLoader: ClassLoader): View? {
        val emojiId = cachedHostId(classLoader, "c3h")
        if (emojiId != 0) {
            root.findViewById<View>(emojiId)?.let { return it }
        }
        return findFirstView(root) { view ->
            val name = view.javaClass.name
            name.contains("RTChattingEmojiView") || name.contains("RTChattingEmoji")
        }
    }

    private fun hostId(classLoader: ClassLoader, name: String): Int {
        return runCatching {
            val idClass = XposedHelpers.findClass("com.tencent.mm.R\$id", classLoader)
            idClass.getField(name).getInt(null)
        }.getOrDefault(0)
    }

    private fun cachedHostId(classLoader: ClassLoader, name: String): Int {
        if (name == "c3g") {
            val cached = bubbleTextId
            if (cached != 0) return cached
            val resolved = hostId(classLoader, name)
            bubbleTextId = resolved
            return resolved
        }
        return hostId(classLoader, name)
    }

    private fun findFirstView(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            findFirstView(view.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }

    private fun removeTaggedTimeViews(root: View) {
        if (root !is ViewGroup) return
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (shouldRemoveCustomTimeView(child)) {
                root.removeViewAt(i)
            } else {
                removeTaggedTimeViews(child)
            }
        }
    }

    private fun shouldRemoveCustomTimeView(view: View): Boolean {
        if (view.getTag(R.id.abc_tag_custom_time) == true) return true
        val text = (view as? TextView)?.text?.toString().orEmpty()
        return MessageTimeLayoutPolicy.isCustomDetailTimeText(text)
    }

    private fun cleanSystemNoticeArea(view: View) {
        clearBubbleTagsNear(view)
        removeRecallGhostTimeAround(view)
        // 最多两次：立刻 + 下一帧；禁止 6 次 postDelayed 堆队列拖主线程
        view.post { removeRecallGhostTimeAround(view) }
        view.postDelayed({ removeRecallGhostTimeAround(view) }, 120L)
    }

    private fun clearBubbleTagsNear(view: View) {
        clearMessageInteractionTags(view)
        clearBubbleTags(view)
        var parent = view.parent
        var depth = 0
        while (parent is ViewGroup && depth < 8) {
            clearMessageInteractionTags(parent)
            clearBubbleTags(parent)
            if (isChatListContainer(parent.javaClass.name)) break
            parent = parent.parent
            depth++
        }
    }

    private fun clearMessageInteractionTags(root: View) {
        root.setTag(R.id.abc_tag_message_object, null)
        root.setTag(R.id.abc_tag_swipe_target_row, null)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                clearMessageInteractionTags(root.getChildAt(i))
            }
        }
    }

    private fun clearBubbleTags(root: View) {
        root.setTag(R.id.abc_tag_bubble_source, null)
        root.setTag(R.id.abc_tag_bubble_is_send, null)
        root.setTag(R.id.abc_tag_bubble_msg_type, null)
        root.setTag(R.id.abc_tag_bubble_msg_id, null)
        root.setTag(R.id.abc_tag_bubble_supports_custom, null)
        root.setTag(R.id.abc_tag_bubble_original_background, null)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                clearBubbleTags(root.getChildAt(i))
            }
        }
    }

    private fun removeCustomTimeAroundRecall(view: View) {
        removeRecallGhostTimeAround(view)
    }

    private fun removeRecallGhostTimeAround(view: View) {
        removeTaggedTimeViewsNear(view)
        removeCustomTimeFromSiblingRows(view)
        removeCustomTimeFromNearbyVisibleRows(view)
    }

    private fun removeCustomTimeFromSiblingRows(view: View) {
        val row = findRecallRowContainer(view) ?: return
        val parent = row.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(row)
        for (offset in -2..2) {
            val childIndex = index + offset
            if (childIndex in 0 until parent.childCount) {
                removeTaggedTimeViews(parent.getChildAt(childIndex))
            }
        }
    }

    private fun findRecallRowContainer(view: View): View? {
        var current: View? = view
        var depth = 0
        while (current != null && depth < 8) {
            val className = current.javaClass.name
            if (current.getTag(R.id.abc_tag_message_object) != null || className.contains(".viewitems.")) {
                return current
            }
            val parent = current.parent
            if (parent !is View) return current
            if (isChatListContainer(parent.javaClass.name)) return current
            current = parent
            depth++
        }
        return current
    }

    private fun removeCustomTimeFromNearbyVisibleRows(view: View) {
        val list = findChatListContainer(view) ?: return
        val pivot = IntArray(2)
        view.getLocationOnScreen(pivot)
        val pivotCenterY = pivot[1] + (view.height / 2)
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i) ?: continue
            if (!child.isShown || child.height <= 0) continue
            val location = IntArray(2)
            child.getLocationOnScreen(location)
            val childCenterY = location[1] + (child.height / 2)
            val distance = kotlin.math.abs(childCenterY - pivotCenterY)
            if (distance <= child.height * 3) {
                removeTaggedTimeViews(child)
            }
        }
    }

    private fun findChatListContainer(view: View): ViewGroup? {
        var parent = view.parent
        var depth = 0
        while (parent is ViewGroup && depth < 10) {
            if (isChatListContainer(parent.javaClass.name)) return parent
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun removeTaggedTimeViewsNear(view: View) {
        removeTaggedTimeViews(view)
        var parent = view.parent
        var depth = 0
        var fallback: ViewGroup? = null
        while (parent is ViewGroup && depth < 8) {
            val className = parent.javaClass.name
            if (isChatListContainer(className)) break
            fallback = parent
            if (parent.getTag(R.id.abc_tag_message_object) != null || className.contains(".viewitems.")) {
                removeTaggedTimeViews(parent)
                return
            }
            parent = parent.parent
            depth++
        }
        fallback?.let { removeTaggedTimeViews(it) }
    }

    private fun isChatListContainer(className: String): Boolean {
        // 必须收窄：原先匹配任意 ListView/RecyclerView，首页会话列表也会进滑动逻辑
        if (className.contains("conversation", ignoreCase = true)) return false
        if (className.contains("ConversationListView")) return false
        return className.contains("chatting", ignoreCase = true) ||
            className.contains("Chatting", ignoreCase = false) ||
            className.contains("MxRecyclerView") ||
            className.contains("WxRecyclerView") ||
            (className.contains("RecyclerView") && (className.contains("mm.ui", ignoreCase = true) || className.contains("mm.view", ignoreCase = true)))
    }

    private fun collectVisibleText(root: View): String {
        val builder = StringBuilder()
        fun visit(view: View) {
            if (view is TextView) {
                val text = view.text?.toString().orEmpty()
                if (text.isNotBlank()) builder.append(text).append(' ')
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    visit(view.getChildAt(i))
                }
            }
        }
        visit(root)
        return builder.toString()
    }

    /**
     * 行文本按 msgId 缓存：同一行 View 在一次绑定内（before 的 bubble 标记 + after 的增强）
     * 只 collectVisibleText 一次，ViewHolder 复用时 msgId 变化自动失效。
     */
    private fun cachedRowText(itemView: View, msgId: Long): String {
        val cachedId = itemView.getTag(R.id.abc_tag_row_text_msg_id) as? Long
        val cached = itemView.getTag(R.id.abc_tag_row_text) as? String
        if (cachedId == msgId && cached != null) return cached
        val t = collectVisibleText(itemView)
        itemView.setTag(R.id.abc_tag_row_text_msg_id, msgId)
        itemView.setTag(R.id.abc_tag_row_text, t)
        return t
    }

    private fun findTimeAnchor(view: View): TimeAnchor? {
        var child: View = view
        var current = view.parent
        var depth = 0
        while (current is View && depth < 8) {
            if (current is LinearLayout && MessageTimeLayoutPolicy.canHostTime(current.orientation)) {
                return TimeAnchor(current, child)
            }
            child = current
            current = current.parent
            depth++
        }
        return null
    }

    private fun readLong(target: Any, methodName: String, fieldName: String): Long? {
        val methodValue = runCatching {
            zeroArgMethod(target.javaClass, methodName)?.let {
                (it.invoke(target) as? Number)?.toLong()
            }
        }.getOrNull()
        if (methodValue != null) return methodValue
        return (fieldValue(target, fieldName) as? Number)?.toLong()
    }

    private fun readInt(target: Any, methodName: String, fieldName: String): Int? {
        val methodValue = runCatching {
            zeroArgMethod(target.javaClass, methodName)?.let {
                (it.invoke(target) as? Number)?.toInt()
            }
        }.getOrNull()
        if (methodValue != null) return methodValue
        return (fieldValue(target, fieldName) as? Number)?.toInt()
    }

    private fun readIntPreferField(target: Any, fieldName: String, methodName: String): Int? {
        val field = (fieldValue(target, fieldName) as? Number)?.toInt()
        if (field != null) return field
        return runCatching {
            zeroArgMethod(target.javaClass, methodName)?.let {
                (it.invoke(target) as? Number)?.toInt()
            }
        }.getOrNull()
    }

    private fun readString(target: Any, methodName: String, fieldName: String): String? {
        val methodValue = runCatching {
            zeroArgMethod(target.javaClass, methodName)?.let {
                it.invoke(target)?.toString()
            }
        }.getOrNull()
        if (methodValue != null) return methodValue
        return fieldValue(target, fieldName)?.toString()
    }

    private fun readIsSend(message: Any): Boolean {
        val field = (fieldValue(message, "field_isSend") as? Number)?.toInt()
        if (field != null) return field == 1
        return (readInt(message, "isSend", "field_isSend")
            ?: readInt(message, "E0", "field_isSend")
            ?: 0) == 1
    }

    private fun fieldValue(target: Any, name: String): Any? {
        return fieldByName(target.javaClass, name)?.let { field ->
            runCatching { field.get(target) }.getOrNull()
        }
    }

    private fun fieldByName(clazz: Class<*>, name: String): Field? {
        val key = clazz.name + '#' + name
        return fieldByNameCache.getOrPut(key) {
            Optional(clazz.allFields().firstOrNull { it.name == name }?.also { it.isAccessible = true })
        }.value
    }

    private fun zeroArgMethod(clazz: Class<*>, name: String): Method? {
        val key = clazz.name + '#' + name
        return zeroArgMethodCache.getOrPut(key) {
            Optional(clazz.allMethods().firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?.also { it.isAccessible = true })
        }.value
    }

    private fun Class<*>.allFields(): List<Field> {
        return allFieldsCache.getOrPut(this) {
            val fields = mutableListOf<Field>()
            var current: Class<*>? = this
            while (current != null) {
                fields += current.declaredFields
                current = current.superclass
            }
            fields
        }
    }

    private fun Class<*>.allMethods(): List<Method> {
        return allMethodsCache.getOrPut(this) {
            val methods = mutableListOf<Method>()
            var current: Class<*>? = this
            while (current != null) {
                methods += current.declaredMethods
                current = current.superclass
            }
            methods
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun loadDexKitNative(context: Context, modulePath: String?) {
        if (dexKitNativeLoaded.get()) return

        runCatching {
            System.loadLibrary("dexkit")
        }.onSuccess {
            dexKitNativeLoaded.set(true)
            xlog("DexKit native loaded via library path")
            return
        }

        val apkPath = modulePath ?: throw IllegalStateException("module path unavailable for libdexkit.so")
        val abi = if (Process.is64Bit()) {
            Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a"
        } else {
            Build.SUPPORTED_32_BIT_ABIS.firstOrNull() ?: "armeabi-v7a"
        }
        val out = File(context.cacheDir, "abc_chat_${abi}_libdexkit.so")
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/libdexkit.so")
                ?: throw IllegalStateException("lib/$abi/libdexkit.so not found in module apk")
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        System.load(out.absolutePath)
        dexKitNativeLoaded.set(true)
        xlog("DexKit native loaded from module apk")
    }

    private fun descriptorToMethod(descriptor: String, classLoader: ClassLoader): Method {
        val arrow = descriptor.indexOf("->")
        val argsStart = descriptor.indexOf('(', arrow)
        require(arrow > 1 && argsStart > arrow) { descriptor }

        val className = descriptorClassName(descriptor)
        val methodName = descriptor.substring(arrow + 2, argsStart)
        val signature = descriptor.substring(argsStart)
        var clazz: Class<*>? = classLoader.loadClass(className)
        while (clazz != null) {
            clazz.declaredMethods.firstOrNull { method ->
                method.name == methodName && methodSignature(method) == signature
            }?.let {
                it.isAccessible = true
                return it
            }
            clazz = clazz.superclass
        }
        throw NoSuchMethodException(descriptor)
    }

    private fun descriptorToClass(descriptor: String, classLoader: ClassLoader): Class<*> {
        return classLoader.loadClass(descriptorClassName(descriptor))
    }

    private fun descriptorClassName(descriptor: String): String {
        val arrow = descriptor.indexOf("->")
        require(arrow > 1) { descriptor }
        return descriptor.substring(1, arrow - 1).replace('/', '.')
    }

    private fun methodSignature(method: Method): String {
        return buildString {
            append('(')
            method.parameterTypes.forEach { append(typeSignature(it)) }
            append(')')
            append(typeSignature(method.returnType))
        }
    }

    private fun typeSignature(type: Class<*>): String {
        if (type.isPrimitive) {
            return when (type) {
                java.lang.Integer.TYPE -> "I"
                java.lang.Void.TYPE -> "V"
                java.lang.Boolean.TYPE -> "Z"
                java.lang.Character.TYPE -> "C"
                java.lang.Byte.TYPE -> "B"
                java.lang.Short.TYPE -> "S"
                java.lang.Float.TYPE -> "F"
                java.lang.Long.TYPE -> "J"
                java.lang.Double.TYPE -> "D"
                else -> error("Unknown primitive $type")
            }
        }
        if (type.isArray) return type.name.replace('.', '/')
        return "L${type.name.replace('.', '/')};"
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }

    private fun logSystemNoticeCleaned(source: String, viewClass: String) {
        if (firstSystemNoticeCleanedLogged.compareAndSet(false, true)) {
            xlog("cleaned system notice via $source: $viewClass")
        }
    }

    private data class SwipeState(
        val initialRawX: Float,
        val initialRawY: Float,
        val touchSlop: Int,
        var dragging: Boolean = false
    )

    private data class SwipeTarget(
        val row: View,
        val message: Any
    )

    private data class SwipeTargetCandidate(
        val target: SwipeTarget,
        val height: Int,
        val distanceToCenter: Float
    )

    private data class TimeAnchor(
        val parent: LinearLayout,
        val child: View
    )

    private data class QuoteMethodCandidate(
        val method: Method,
        val args: Array<Any?>
    )

    private object BubbleAssets {
        private data class Range(val start: Int, val end: Int)
        private data class Patch(val bitmap: Bitmap, val chunk: ByteArray, val padding: Rect)
        data class BubbleDrawable(val drawable: Drawable, val padding: Rect)

        private val cache = ConcurrentHashMap<String, Patch?>()

        fun clearCache(path: String) {
            val keysToRemove = cache.keys.filter { it.contains(path) || it.contains(File(path).name) }
            keysToRemove.forEach { cache.remove(it) }
        }

        fun sourceKey(modulePath: String?, isSend: Boolean): String {
            val external = externalBubbleFile(isSend)
            if (external != null) return "file:${external.absolutePath}:${external.lastModified()}"
            val assetName = if (isSend) "right_bubble.9.png" else "left_bubble.9.png"
            return "asset:${modulePath.orEmpty()}:$assetName"
        }

        fun drawableFor(context: Context, modulePath: String?, isSend: Boolean): BubbleDrawable? {
            val external = externalBubbleFile(isSend)
            val patch = if (external != null) {
                cache.computeIfAbsent("file:${external.absolutePath}:${external.lastModified()}") {
                    loadPatch(external)
                }
            } else {
                val apkPath = modulePath ?: return null
                val assetName = if (isSend) "right_bubble.9.png" else "left_bubble.9.png"
                cache.computeIfAbsent("asset:$apkPath:$assetName") {
                    loadPatch(apkPath, assetName)
                }
            } ?: return null
            val padding = BubbleDrawablePolicy.contentPaddingOrDefault(
                patch.padding,
                isSend,
                context.resources.displayMetrics.density
            )
            val drawable = NinePatchDrawable(context.resources, patch.bitmap, patch.chunk, Rect(padding), null)
            return BubbleDrawable(drawable, padding)
        }

        private fun externalBubbleFile(isSend: Boolean): File? {
            // 优先：文件夹模式 - 从用户指定的气泡皮肤文件夹内自动读取左右气泡
            val folderSetting = PublicConfigStore.getString("bubble_folder", "")
            if (folderSetting.isNotBlank()) {
                val dir = File(folderSetting)
                if (dir.isDirectory) {
                    val names = if (isSend) {
                        listOf("right.9.png", "righ.9.png", "right.png", "righ.png", "right", "righ")
                    } else {
                        listOf("left.9.png", "left.png", "left")
                    }
                    names.map { File(dir, it) }.firstOrNull { it.isFile && it.length() > 0L }?.let { return it }
                }
            }

            // 其次：兼容旧的单图片自定义路径
            val customPath = PublicConfigStore.getString(if (isSend) "bubble_path_right" else "bubble_path_left", "")
            if (customPath.isNotBlank()) {
                val f = File(customPath)
                if (f.isFile && f.length() > 0L) return f
            }

            // 兜底降级：检查默认目录
            val dir = File("/storage/emulated/0/Android/media/com.tencent.mm/OKK")
            if (!dir.isDirectory) return null
            val names = if (isSend) {
                listOf("right.9.png", "righ.9.png", "right.png", "righ.png", "right", "righ")
            } else {
                listOf("left.9.png", "left.png", "left")
            }
            return names.map { File(dir, it) }.firstOrNull { it.isFile && it.length() > 0L }
        }

        private fun loadPatch(file: File): Patch? {
            return runCatching {
                val original = BitmapFactory.decodeFile(file.absolutePath) ?: return null
                val compiled = NinePatch.isNinePatchChunk(original.ninePatchChunk)
                val chunk = if (compiled) {
                    original.ninePatchChunk
                } else {
                    buildNinePatchChunk(original)
                } ?: return null
                val bitmap = if (compiled) {
                    original
                } else {
                    Bitmap.createBitmap(original, 1, 1, original.width - 2, original.height - 2)
                }
                val padding = if (compiled) Rect() else contentPadding(original)
                Patch(bitmap, chunk, padding)
            }.getOrNull()
        }

        private fun loadPatch(apkPath: String, assetName: String): Patch? {
            return runCatching {
                ZipFile(apkPath).use { zip ->
                    val entry = zip.getEntry("assets/abc_bubble/$assetName") ?: return null
                    zip.getInputStream(entry).use { input ->
                        val original = BitmapFactory.decodeStream(input) ?: return null
                        val compiled = NinePatch.isNinePatchChunk(original.ninePatchChunk)
                        val chunk = if (compiled) {
                            original.ninePatchChunk
                        } else {
                            buildNinePatchChunk(original)
                        } ?: return null
                        val bitmap = if (compiled) {
                            original
                        } else {
                            Bitmap.createBitmap(original, 1, 1, original.width - 2, original.height - 2)
                        }
                        val padding = if (compiled) Rect() else contentPadding(original)
                        Patch(bitmap, chunk, padding)
                    }
                }
            }.getOrNull()
        }

        private fun buildNinePatchChunk(bitmap: Bitmap): ByteArray? {
            val xRanges = blackRanges(bitmap, horizontal = true)
            val yRanges = blackRanges(bitmap, horizontal = false)
            if (xRanges.isEmpty() || yRanges.isEmpty()) return null
            val padding = contentPadding(bitmap)

            val buffer = ByteBuffer.allocate(((xRanges.size + yRanges.size) * 8) + 68)
            buffer.order(ByteOrder.nativeOrder())
            buffer.put(1.toByte())
            buffer.put((xRanges.size * 2).toByte())
            buffer.put((yRanges.size * 2).toByte())
            buffer.put(9.toByte())
            repeat(2) { buffer.putInt(0) }
            buffer.putInt(padding.left)
            buffer.putInt(padding.right)
            buffer.putInt(padding.top)
            buffer.putInt(padding.bottom)
            buffer.putInt(0)
            xRanges.forEach {
                buffer.putInt(it.start)
                buffer.putInt(it.end)
            }
            yRanges.forEach {
                buffer.putInt(it.start)
                buffer.putInt(it.end)
            }
            repeat(9) { buffer.putInt(1) }
            return buffer.array()
        }

        private fun blackRanges(bitmap: Bitmap, horizontal: Boolean): List<Range> {
            return blackRanges(bitmap, horizontal, contentLine = false)
        }

        private fun contentPadding(bitmap: Bitmap): Rect {
            val xRange = blackRanges(bitmap, horizontal = true, contentLine = true).firstOrNull()
            val yRange = blackRanges(bitmap, horizontal = false, contentLine = true).firstOrNull()
            if (xRange == null || yRange == null) return Rect()
            val contentWidth = bitmap.width - 2
            val contentHeight = bitmap.height - 2
            return Rect(
                xRange.start.coerceAtLeast(0),
                yRange.start.coerceAtLeast(0),
                (contentWidth - xRange.end).coerceAtLeast(0),
                (contentHeight - yRange.end).coerceAtLeast(0)
            )
        }

        private fun blackRanges(bitmap: Bitmap, horizontal: Boolean, contentLine: Boolean): List<Range> {
            val length = if (horizontal) bitmap.width else bitmap.height
            val ranges = mutableListOf<Range>()
            var start = -1
            for (i in 1 until length - 1) {
                val pixel = when {
                    horizontal && contentLine -> bitmap.getPixel(i, bitmap.height - 1)
                    horizontal -> bitmap.getPixel(i, 0)
                    contentLine -> bitmap.getPixel(bitmap.width - 1, i)
                    else -> bitmap.getPixel(0, i)
                }
                val black = Color.alpha(pixel) == 255 &&
                    Color.red(pixel) == 0 &&
                    Color.green(pixel) == 0 &&
                    Color.blue(pixel) == 0
                if (black && start == -1) {
                    start = i - 1
                } else if (!black && start != -1) {
                    ranges += Range(start, i - 1)
                    start = -1
                }
            }
            if (start != -1) ranges += Range(start, length - 2)
            return ranges
        }
    }
}