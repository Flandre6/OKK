package de.robv.android.xposed

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method

object XposedHelpers {
    class ClassNotFoundError(cause: Throwable) : Error(cause)
    class InvocationTargetError(cause: Throwable) : Error(cause)

    @JvmStatic
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        return try {
            Class.forName(className, false, classLoader ?: ClassLoader.getSystemClassLoader())
        } catch (t: Throwable) {
            throw ClassNotFoundError(t)
        }
    }

    @JvmStatic
    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg parameterTypesAndCallback: Any?): XC_MethodHook.Unhook {
        val callback = parameterTypesAndCallback.lastOrNull() as? XC_MethodHook
            ?: throw IllegalArgumentException("no XC_MethodHook callback supplied")
        val parameterTypes = parameterTypesAndCallback.dropLast(1).map { resolveType(it, clazz.classLoader) }.toTypedArray()
        return XposedBridge.hookMethod(findMethodBestMatch(clazz, methodName, parameterTypes), callback)
    }

    @JvmStatic
    fun findAndHookMethod(className: String, classLoader: ClassLoader?, methodName: String, vararg parameterTypesAndCallback: Any?): XC_MethodHook.Unhook {
        return findAndHookMethod(findClass(className, classLoader), methodName, *parameterTypesAndCallback)
    }

    @JvmStatic
    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Any?): Method {
        val types = parameterTypes.map { resolveType(it, clazz.classLoader) }.toTypedArray()
        return clazz.getDeclaredMethod(methodName, *types).also { it.isAccessible = true }
    }

    @JvmStatic
    fun findMethodExact(className: String, classLoader: ClassLoader?, methodName: String, vararg parameterTypes: Any?): Method {
        return findMethodExact(findClass(className, classLoader), methodName, *parameterTypes)
    }

    @JvmStatic
    fun callMethod(receiver: Any?, methodName: String, vararg args: Any?): Any? {
        receiver ?: throw NullPointerException("receiver == null")
        val method = findMethodBestMatch(receiver.javaClass, methodName, args.map { it?.javaClass }.toTypedArray())
        return try {
            method.invoke(receiver, *args)
        } catch (e: InvocationTargetException) {
            throw InvocationTargetError(e.targetException)
        }
    }

    @JvmStatic
    fun getObjectField(receiver: Any?, fieldName: String): Any? {
        receiver ?: throw NullPointerException("receiver == null")
        return findField(receiver.javaClass, fieldName).get(receiver)
    }

    @JvmStatic
    fun setObjectField(receiver: Any?, fieldName: String, value: Any?) {
        receiver ?: throw NullPointerException("receiver == null")
        findField(receiver.javaClass, fieldName).set(receiver, value)
    }

    @JvmStatic
    fun findField(clazz: Class<*>, fieldName: String): Field {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredField(fieldName).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldError("${clazz.name}#$fieldName")
    }

    private fun findMethodBestMatch(clazz: Class<*>, methodName: String, parameterTypes: Array<Class<*>?>): Method {
        var c: Class<*>? = clazz
        var best: Method? = null
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != methodName || m.parameterTypes.size != parameterTypes.size) continue
                if (matches(m.parameterTypes, parameterTypes)) {
                    m.isAccessible = true
                    return m
                }
                if (best == null && looselyMatches(m.parameterTypes, parameterTypes)) {
                    best = m
                }
            }
            c = c.superclass
        }
        best?.let {
            it.isAccessible = true
            return it
        }
        throw NoSuchMethodError("${clazz.name}#$methodName/${parameterTypes.size}")
    }

    private fun matches(expected: Array<Class<*>>, actual: Array<Class<*>?>): Boolean {
        return expected.indices.all { i ->
            val a = actual[i] ?: return@all !expected[i].isPrimitive
            wrap(expected[i]).isAssignableFrom(wrap(a))
        }
    }

    private fun looselyMatches(expected: Array<Class<*>>, actual: Array<Class<*>?>): Boolean {
        return expected.indices.all { i -> actual[i] == null || wrap(expected[i]).isAssignableFrom(wrap(actual[i]!!)) }
    }

    private fun resolveType(value: Any?, classLoader: ClassLoader?): Class<*>? {
        return when (value) {
            null -> null
            is Class<*> -> value
            is String -> findClass(value, classLoader)
            else -> throw IllegalArgumentException("Unsupported parameter type spec: $value")
        }
    }

    private fun wrap(type: Class<*>): Class<*> {
        if (!type.isPrimitive) return type
        return when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Void.TYPE -> java.lang.Void::class.java
            else -> type
        }
    }
}
