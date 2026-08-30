package com.OKK.yes.core.compat

import java.lang.reflect.Field
import java.lang.reflect.Method

/** 跨版本反射辅助：按签名 / 层级找方法，不绑死混淆类名。 */
object ReflectCompat {

    fun findClass(name: String, cl: ClassLoader): Class<*>? =
        runCatching { Class.forName(name, false, cl) }.getOrNull()

    fun findClassAny(cl: ClassLoader, vararg names: String): Class<*>? {
        for (n in names) {
            findClass(n, cl)?.let { return it }
        }
        return null
    }

    fun hierarchy(start: Class<*>): Sequence<Class<*>> = sequence {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            yield(c)
            c = c.superclass
        }
    }

    fun findDeclaredMethod(
        start: Class<*>,
        name: String,
        paramCount: Int? = null,
        returnType: Class<*>? = null,
        paramTypes: Array<out Class<*>>? = null
    ): Method? {
        for (c in hierarchy(start)) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                if (paramCount != null && m.parameterTypes.size != paramCount) continue
                if (returnType != null && m.returnType != returnType) continue
                if (paramTypes != null) {
                    if (m.parameterTypes.size != paramTypes.size) continue
                    if (!m.parameterTypes.indices.all {
                            paramTypes[it].isAssignableFrom(m.parameterTypes[it]) ||
                                m.parameterTypes[it].isAssignableFrom(paramTypes[it])
                        }
                    ) continue
                }
                m.isAccessible = true
                return m
            }
        }
        return null
    }

    fun findDeclaredMethodsNamed(start: Class<*>, name: String): List<Method> {
        val out = ArrayList<Method>()
        for (c in hierarchy(start)) {
            for (m in c.declaredMethods) {
                if (m.name == name) {
                    m.isAccessible = true
                    out += m
                }
            }
        }
        return out
    }

    fun listLikeFields(instance: Any): List<Pair<Field, Any?>> {
        val out = ArrayList<Pair<Field, Any?>>()
        for (c in hierarchy(instance.javaClass)) {
            for (f in c.declaredFields) {
                runCatching {
                    f.isAccessible = true
                    val v = f.get(instance) ?: return@runCatching
                    if (v is List<*> || v is MutableList<*>) {
                        out += f to v
                    }
                }
            }
        }
        return out
    }

    fun callFirst(receiver: Any, vararg methodNames: String, args: Array<Any?> = emptyArray()): Any? {
        for (name in methodNames) {
            val m = findDeclaredMethod(
                receiver.javaClass,
                name,
                paramCount = args.size
            ) ?: continue
            return runCatching {
                m.isAccessible = true
                m.invoke(receiver, *args)
            }.getOrNull()
        }
        return null
    }
}
