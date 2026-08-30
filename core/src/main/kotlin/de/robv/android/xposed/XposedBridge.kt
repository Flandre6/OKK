package de.robv.android.xposed

import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.Collections

object XposedBridge {
    @JvmField
    val BOOTCLASSLOADER: ClassLoader? = ClassLoader.getSystemClassLoader()

    @Volatile
    private var module: XposedModule? = null

    @JvmStatic
    fun initModern(module: XposedModule) {
        this.module = module
    }

    @JvmStatic
    fun getXposedVersion(): Int = module?.apiVersion ?: 102

    @JvmStatic
    fun log(text: String) {
        Log.e("OKK-Xposed", text)
        runCatching { module?.log(Log.INFO, "OKK-Xposed", text, null) }
    }

    @JvmStatic
    fun log(t: Throwable) {
        Log.e("OKK-Xposed", t.message ?: t.javaClass.name, t)
        runCatching { module?.log(Log.ERROR, "OKK-Xposed", t.message ?: t.javaClass.name, t) }
    }

    @JvmStatic
    fun hookMethod(member: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val executable = member as? Executable
            ?: throw IllegalArgumentException("Only methods and constructors can be hooked: $member")
        val activeModule = module ?: error("libxposed module is not initialized")
        val handle = activeModule.hook(executable)
            .setPriority(callback.priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept(LegacyHooker(executable, callback))
        return XC_MethodHook.Unhook(member, callback) { handle.unhook() }
    }

    @Keep
    private class LegacyHooker(
        private val executable: Executable,
        private val callback: XC_MethodHook
    ) : XposedInterface.Hooker {
        @Keep
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val param = XC_MethodHook.MethodHookParam().also {
                it.method = executable
                it.thisObject = chain.thisObject
                it.args = chain.args.toTypedArray()
            }

            try {
                callback.callBefore(param)
            } catch (t: Throwable) {
                log(t)
            }

            if (!param.returnEarly) {
                try {
                    param.setResultFromOriginal(chain.proceed(param.args))
                } catch (t: Throwable) {
                    param.setThrowableFromOriginal(t)
                }
            }

            try {
                callback.callAfter(param)
            } catch (t: Throwable) {
                log(t)
            }

            param.throwable?.let { throw it }
            return param.result
        }
    }
    @JvmStatic
    fun unhookMethod(member: Member, callback: XC_MethodHook) {
        // Individual handles are returned from hookMethod; legacy callers in OKK do not use this path.
    }

    @JvmStatic
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook): Set<XC_MethodHook.Unhook> {
        return clazz.declaredMethods
            .filter { it.name == methodName }
            .map { hookMethod(it, callback) }
            .toSet()
    }

    @JvmStatic
    fun hookAllConstructors(clazz: Class<*>, callback: XC_MethodHook): Set<XC_MethodHook.Unhook> {
        return clazz.declaredConstructors.map { hookMethod(it, callback) }.toSet()
    }

    @JvmStatic
    @Throws(Throwable::class)
    fun invokeOriginalMethod(member: Member, thisObject: Any?, args: Array<Any?>?): Any? {
        val activeModule = module ?: return when (member) {
            is Method -> {
                member.isAccessible = true
                member.invoke(thisObject, *(args ?: emptyArray()))
            }
            is Constructor<*> -> {
                member.isAccessible = true
                member.newInstance(*(args ?: emptyArray()))
            }
            else -> null
        }
        return when (member) {
            is Method -> {
                val invoker = activeModule.getInvoker(member)
                invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
                invoker.invoke(thisObject, *(args ?: emptyArray()))
            }
            is Constructor<*> -> {
                val invoker = activeModule.getInvoker(member)
                invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
                invoker.newInstance(*(args ?: emptyArray()))
            }
            else -> null
        }
    }

    @JvmStatic
    fun emptyUnhookSet(): Set<XC_MethodHook.Unhook> = Collections.emptySet()
}
