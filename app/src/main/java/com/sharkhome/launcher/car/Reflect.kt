package com.sharkhome.launcher.car

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Small, fail-soft reflection helpers for calling the head unit's private car APIs. */
object Reflect {

    private val cache = ConcurrentHashMap<Class<*>, List<Method>>()

    /**
     * Public methods of [target]. Methods declared on its public interfaces come first: an AIDL
     * proxy's own class is a private nested class, and reflection refuses to call methods on it
     * from another package, but calling the same method through the public interface works.
     */
    fun methodsOf(target: Any): List<Method> = cache.getOrPut(target.javaClass) {
        val out = LinkedHashSet<Method>()
        fun addInterfaces(c: Class<*>) {
            for (i in c.interfaces) {
                out.addAll(i.methods)
                addInterfaces(i)
            }
            c.superclass?.let { addInterfaces(it) }
        }
        addInterfaces(target.javaClass)
        out.addAll(target.javaClass.methods)
        out.toList()
    }

    fun method(target: Any, name: String, argc: Int): Method? =
        methodsOf(target).firstOrNull { it.name == name && it.parameterTypes.size == argc }

    /** Calls [name] on [target]. Numbers are converted to whatever primitive type the method expects. */
    fun invoke(target: Any, name: String, args: List<Any>): Any? {
        val m = method(target, name, args.size) ?: throw NoSuchMethodException(name)
        val converted = Array(args.size) { i -> coerce(args[i], m.parameterTypes[i]) }
        runCatching { m.isAccessible = true }
        try {
            return m.invoke(target, *converted)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun coerce(v: Any, t: Class<*>): Any {
        if (v is List<*> && t == IntArray::class.java) return IntArray(v.size) { (v[it] as Number).toDouble().roundToInt() }
        if (v !is Number) return v
        val d = v.toDouble()
        return when (t) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> d.roundToInt()
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> d.roundToLong()
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> d.toFloat()
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> d
            Short::class.javaPrimitiveType, Short::class.javaObjectType -> d.roundToInt().toShort()
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> d.roundToInt().toByte()
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> d.roundToInt() != 0
            else -> v
        }
    }

    fun toNumber(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is IntArray -> v.firstOrNull()?.toDouble()
        is FloatArray -> v.firstOrNull()?.toDouble()
        else -> null
    }

    /** Every public method on [target] (minus Object/Binder plumbing), for the probe report. */
    fun inventory(target: Any): List<String> = methodsOf(target)
        .filter { it.declaringClass != Any::class.java && !it.declaringClass.name.startsWith("android.os.") }
        .map { m -> "${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }}): ${m.returnType.simpleName}" }
        .distinct()
        .sorted()
}
