package com.OKK.yes.core.hooks

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.BaseAdapter
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 资料页显示微信 ID。
 *
 * 逆向说明（对标 WeKit WeContactPrefsScreenApi，当前微信版本）：
 * - Host：`ContactInfoUI` / `ChatroomInfoUI`
 * - `initView` after 注入 Preference
 * - `onPreferenceTreeClick` before 复制
 *
 * 关键：Preference 的操作方法（setKey / setTitle / setSummary / 读取 key / 注入 add）
 * 全部是**混淆后的短名**且随版本变化。因此这里不写死任何方法名，而是
 * 通过对标 WeKit 的 WeContactPrefsScreenApi 用**按签名反射**解析：
 *   - setKey：Preference 上「参数为 String、返回 void」的方法
 *   - setTitle/setSummary：Preference 上「参数为 CharSequence」的方法
 *   - readKey：Preference 上「无参返回 String」的方法
 *   - adapter：Activity/MMPreference 上「BaseAdapter 子类」的非静态字段
 *   - add：adapter 上「(Preference, Int)」的方法
 *
 * 配置：`profile_id` 默认 false
 */
object ProfileIdHook {
    private const val TAG = "OKK-ProfileId"
    private const val KEY_ENABLE = "profile_id"
    private const val PREF_KEY = "achat_profile_id"
    private const val CONTACT_UI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
    private const val CHATROOM_UI = "com.tencent.mm.chatroom.ui.ChatroomInfoUI"
    private const val PREF_CLAZZ = "com.tencent.mm.ui.base.preference.Preference"

    private val installed = AtomicBoolean(false)

    fun install(context: Context, classLoader: ClassLoader, modulePath: String? = null) {
        if (!installed.compareAndSet(false, true)) return
        xlog("install enabled=${isEnabled()}")
        hookUi(classLoader, CONTACT_UI)
        hookUi(classLoader, CHATROOM_UI)
    }

    fun isEnabled(): Boolean =
        runCatching { PublicConfigStore.getBoolean(KEY_ENABLE, false) }.getOrDefault(false)

    private fun hookUi(classLoader: ClassLoader, className: String) {
        val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull()
        if (clazz == null) {
            xlog("class miss: $className")
            return
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                clazz,
                "initView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        val activity = param.thisObject as? Activity ?: return
                        injectPreference(activity, classLoader)
                    }
                }
            )
            xlog("hooked $className.initView")
        }.onFailure { xlog("$className.initView fail: ${it.message}") }

        runCatching {
            var hookedClick = false
            for (m in clazz.declaredMethods) {
                if (m.name != "onPreferenceTreeClick" || m.parameterCount != 2) continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        val preference = param.args.getOrNull(1) ?: return
                        val key = readPrefKey(preference) ?: return
                        if (key != PREF_KEY) return
                        val activity = param.thisObject as? Activity ?: return
                        val id = resolveWxId(activity)
                        if (id.isNotBlank()) {
                            copy(activity, id)
                            Toast.makeText(activity, "已复制 $id", Toast.LENGTH_SHORT).show()
                        }
                        param.result = true
                    }
                })
                hookedClick = true
            }
            if (hookedClick) xlog("hooked $className.onPreferenceTreeClick")
            else xlog("$className.onPreferenceTreeClick not found")
        }.onFailure { xlog("$className.click fail: ${it.message}") }
    }

    private fun injectPreference(activity: Activity, classLoader: ClassLoader) {
        runCatching {
            val adapter = resolvePreferenceAdapter(activity)
            if (adapter == null) {
                xlog("inject fail: resolvePreferenceAdapter returned null")
                return
            }
            val id = resolveWxId(activity).ifBlank { "获取失败" }
            val title = "微信 ID: $id"

            val prefClass = runCatching {
                XposedHelpers.findClass(PREF_CLAZZ, classLoader)
            }.getOrNull()
            if (prefClass == null) {
                xlog("inject fail: Preference class not found")
                return
            }
            val ctor = prefClass.constructors.firstOrNull { c ->
                c.parameterCount == 1 && c.parameterTypes[0] == Context::class.java
            }
            if (ctor == null) {
                xlog("inject fail: no Preference(Context) ctor")
                return
            }
            val preference = ctor.newInstance(activity)

            // 打印 Preference 类的所有成员方法，彻底排查
            xlog("=== DUMPING PREFERENCE METHODS (${preference.javaClass.name}) ===")
            allMethods(preference.javaClass).forEachIndexed { index, method ->
                val params = method.parameterTypes.joinToString { it.simpleName }
                xlog("Method[$index]: ${method.name}($params) : ${method.returnType.simpleName}")
            }

            // 已存在则仅更新标题
            val existing = findPreferenceByKey(adapter, PREF_KEY)
            if (existing != null) {
                setProfileIdText(existing, title)
                notifyAdapterChanged(adapter)
                xlog("updated existing profile row: $id")
                return
            }

            setPreferenceKey(preference, PREF_KEY)
            setProfileIdText(preference, title)
            addPreference(adapter, preference, 1)
            notifyAdapterChanged(adapter)
            xlog("injected profile id row: $id")
        }.onFailure {
            xlog("inject fail: ${it.message}")
        }
    }

    // ── 按签名反射解析（对标 WeKit WeContactPrefsScreenApi）──────────────────

    private fun resolvePreferenceAdapter(activity: Activity): Any? {
        // 1) 尝试 getPreferenceScreen()（若版本保留）
        runCatching {
            val m = activity.javaClass.methods.firstOrNull {
                it.name.startsWith("getPreference") && it.parameterCount == 0
            }
            if (m != null) {
                m.isAccessible = true
                val r = m.invoke(activity)
                if (r != null) return r
            }
        }
        // 2) 在 Activity/其父类字段中找 BaseAdapter 子类字段（MMPreference 的 adapter）
        var cls: Class<*>? = activity.javaClass
        while (cls != null && cls != Any::class.java && cls != Activity::class.java) {
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (BaseAdapter::class.java.isAssignableFrom(f.type)) {
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(activity)
                        if (v != null) return v
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findPreferenceByKey(adapter: Any, key: String): Any? {
        // BaseAdapter 的 getCount/getItem，遍历找 key 匹配的 Preference
        runCatching {
            val ba = adapter as? BaseAdapter ?: return null
            for (i in 0 until ba.count) {
                val item = ba.getItem(i) ?: continue
                if (readPrefKey(item) == key) return item
            }
        }
        return null
    }

    private fun setPreferenceKey(pref: Any, key: String) {
        // 对标 WeKit：Preference 上「参数 String、返回 void」的方法（setKey）
        val m = allMethods(pref.javaClass).firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java && it.returnType == Void.TYPE
        } ?: return
        m.isAccessible = true
        m.invoke(pref, key)
    }

    private fun setProfileIdText(pref: Any, text: String) {
        // 当前微信 Preference 的右侧摘要位会吞掉 CharSequence setter 的结果；
        // 这里直接对齐 Hchat 做法：写左侧标题字段，并清空右侧摘要字段。
        writeFirstMatchingTextField(pref, text, listOf("h", "title", "mTitle"))
        writeFirstMatchingTextField(pref, "", listOf("m", "summary", "mSummary"))

        val charSeqMethods = allMethods(pref.javaClass).filter {
            it.parameterCount == 1 &&
                CharSequence::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.returnType == Void.TYPE
        }
        charSeqMethods.forEach { m ->
            runCatching {
                m.isAccessible = true
                m.invoke(pref, text)
            }
        }
        writeFirstMatchingTextField(pref, text, listOf("h", "title", "mTitle"))
        writeFirstMatchingTextField(pref, "", listOf("m", "summary", "mSummary"))
    }

    private fun writeFirstMatchingTextField(pref: Any, value: String, names: List<String>): Boolean {
        for (name in names) {
            var cls: Class<*>? = pref.javaClass
            while (cls != null && cls != Any::class.java) {
                val field = cls.declaredFields.firstOrNull {
                    it.name == name && (it.type == String::class.java || CharSequence::class.java.isAssignableFrom(it.type))
                }
                if (field != null) {
                    runCatching {
                        field.isAccessible = true
                        field.set(pref, value)
                        return true
                    }
                }
                cls = cls.superclass
            }
        }
        return false
    }

    private fun readPrefKey(pref: Any): String? {
        // 1) 无参返回 String 的方法（j()）
        runCatching {
            val m = findMethod(pref.javaClass) { it.parameterCount == 0 && it.returnType == String::class.java }
            if (m != null) {
                m.isAccessible = true
                return m.invoke(pref) as? String
            }
        }
        // 2) 非 final 的 String 字段
        var cls: Class<*>? = pref.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isFinal(f.modifiers)) continue
                if (f.type == String::class.java) {
                    runCatching {
                        f.isAccessible = true
                        return f.get(pref) as? String
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun addPreference(adapter: Any, pref: Any, position: Int) {
        val m = allMethods(adapter.javaClass).firstOrNull {
            it.parameterCount == 2 && it.parameterTypes[0] == pref.javaClass && it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return
        m.isAccessible = true
        m.invoke(adapter, pref, position)
    }

    private fun notifyAdapterChanged(adapter: Any) {
        runCatching {
            val m = allMethods(adapter.javaClass).firstOrNull { it.name == "notifyDataSetChanged" && it.parameterCount == 0 }
                ?: return
            m.isAccessible = true
            m.invoke(adapter)
        }
    }

    private fun allMethods(clazz: Class<*>): List<Method> {
        val list = ArrayList<Method>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (list.none { it.name == m.name && it.parameterTypes.contentEquals(m.parameterTypes) }) {
                    list.add(m)
                }
            }
            c = c.superclass
        }
        return list
    }

    private fun findMethod(clazz: Class<*>, pred: (Method) -> Boolean): Method? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (pred(m)) return m
            }
            c = c.superclass
        }
        return null
    }

    private fun resolveWxId(activity: Activity): String {
        val intent = activity.intent ?: return ""
        val keys = listOf(
            "Contact_User",
            "Contact_UserName",
            "User",
            "userName",
            "Chat_User",
            "RoomInfo_Id",
            "room_name",
            "Contact_ChatRoomId",
            "Contact_Alias"
        )
        for (k in keys) {
            val v = intent.getStringExtra(k)?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        val extras = intent.extras
        if (extras != null) {
            for (k in extras.keySet()) {
                val v = extras.get(k)
                if (v is String && (v.startsWith("wxid_") || v.endsWith("@chatroom") ||
                        v.endsWith("@im.chatroom") || v.matches(Regex("[a-zA-Z][\\w\\-]{5,}")))
                ) {
                    if (k.contains("User", ignoreCase = true) ||
                        k.contains("talker", ignoreCase = true) ||
                        k.contains("username", ignoreCase = true) ||
                        k.contains("room", ignoreCase = true)
                    ) {
                        return v
                    }
                }
            }
        }
        return ""
    }

    private fun copy(ctx: Context, text: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("wxid", text))
        }
    }

    private fun xlog(msg: String) {
        Log.e(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (_: Throwable) {
        }
    }
}