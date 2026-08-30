package com.OKK.yes.core.hooks

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object MessageObjectResolver {
    private const val MAX_FALLBACK_DEPTH = 5
    private val allFieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val allMethodsCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private class Optional<T>(val value: T?)
    private val zeroArgMethodCache = ConcurrentHashMap<String, Optional<Method>>()
    private val fieldByNameCache = ConcurrentHashMap<String, Optional<Field>>()


    fun find(adapter: Any?, position: Int): Any? {
        if (adapter == null || position < 0) return null
        
        val clazz = adapter.javaClass
        
        // Fast path 1: WeChat 8.0.x hardcoded field (might be obsolete)
        val currentPathResult = findCurrentWechatPath(adapter, position)
        if (currentPathResult != null) return currentPathResult
        
        // Fast path 2: Heuristic search for List fields up to depth 2
        return findMessageObjectViaHeuristic(adapter, position)
    }

    private fun findMessageObjectViaHeuristic(adapter: Any, position: Int): Any? {
        val adapterFields = adapter.javaClass.allFields()
        // Depth 1: Direct List field on the adapter
        for (field in adapterFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            val value = field.safeGet(adapter)
            if (value is List<*>) {
                val item = value.getOrNull(position) ?: continue
                if (isMessageLike(item)) return item
                val inner = findMessageObjectInBoundItem(item)
                if (inner != null) return inner
            }
        }
        // Depth 2: A wrapper field on the adapter that contains a List
        for (field in adapterFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            val wrapper = field.safeGet(adapter) ?: continue
            if (shouldSkip(wrapper.javaClass)) continue
            if (wrapper is List<*>) continue // Already checked in depth 1
            
            for (innerField in wrapper.javaClass.allFields()) {
                if (Modifier.isStatic(innerField.modifiers)) continue
                val innerValue = innerField.safeGet(wrapper)
                if (innerValue is List<*>) {
                    val item = innerValue.getOrNull(position) ?: continue
                    if (isMessageLike(item)) return item
                    val innerInner = findMessageObjectInBoundItem(item)
                    if (innerInner != null) return innerInner
                }
            }
        }
        return null
    }

    fun clearCachesForTest() {
        allFieldsCache.clear()
        allMethodsCache.clear()
        zeroArgMethodCache.clear()
        fieldByNameCache.clear()
    }

    private fun findCurrentWechatPath(adapter: Any, position: Int): Any? {
        return runCatching {
            val holder = fieldValue(adapter, "H") ?: return null
            val list = fieldValue(holder, "f146203o") as? List<*> ?: return null
            val item = list.getOrNull(position) ?: return null
            findMessageObjectInBoundItem(item)
        }.getOrNull()
    }

    private fun findMessageObjectInBoundItem(item: Any): Any? {
        if (isMessageLike(item)) return item
        val first = item.javaClass.allFields().asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field -> field.safeGet(item) }
            .firstOrNull { isMessageLike(it) }
        if (first != null) return first

        return item.javaClass.allFields().asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                val value = field.safeGet(item) ?: return@mapNotNull null
                value.javaClass.allFields().asSequence()
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .mapNotNull { nested -> nested.safeGet(value) }
                    .firstOrNull { isMessageLike(it) }
            }
            .firstOrNull()
    }



    private fun isMessageLike(target: Any): Boolean {
        return readLong(target, "getCreateTime", "field_createTime") != null &&
            readLong(target, "getMsgId", "field_msgId") != null
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

    private fun zeroArgMethod(clazz: Class<*>, name: String): Method? {
        val key = clazz.name + '#' + name
        return zeroArgMethodCache.getOrPut(key) {
            Optional(clazz.allMethods().firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.also { it.isAccessible = true })
        }.value
    }



    private fun fieldValue(target: Any, name: String): Any? {
        return fieldByName(target.javaClass, name)?.safeGet(target)
    }

    private fun fieldByName(clazz: Class<*>, name: String): Field? {
        val key = clazz.name + '#' + name
        return fieldByNameCache.getOrPut(key) {
            Optional(clazz.allFields().firstOrNull { it.name == name }?.also { it.isAccessible = true })
        }.value
    }



    private fun Field.safeGet(target: Any): Any? {
        return runCatching {
            isAccessible = true
            get(target)
        }.getOrNull()
    }

    private fun shouldSkip(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isArray || type == String::class.java || type == Class::class.java) return true
        val name = type.name
        return name.startsWith("android.") ||
            name.startsWith("java.lang.") ||
            name.startsWith("java.io.") ||
            name.startsWith("kotlin.")
    }

    private fun shouldSkipFieldType(type: Class<*>): Boolean {
        if (List::class.java.isAssignableFrom(type) || Iterable::class.java.isAssignableFrom(type)) return false
        return shouldSkip(type)
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


}
