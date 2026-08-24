package org.jetbrains.hexana.mcp.scripting

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.jetbrains.hexana.shared.scripting.ScriptValue
import org.jetbrains.hexana.shared.scripting.invoke

/**
 * Binds the shared [ScriptValue] surface into a GraalJS context.
 *
 * This is the JVM half of the "one API, three hosts" split: the surface itself is
 * defined in `plugins-shared` (`scriptBinaryApi`, `ScriptOutput`), and this file
 * only translates it into `Proxy*` objects. Nothing here decides *what* a script
 * can see.
 *
 * Everything stays lazy — a member is computed when the script reads it, and an
 * array element when the script indexes it — so exposing a table with a million
 * rows costs nothing until the script touches one.
 *
 * All proxies are read-only and carry data, never host behavior, so the context
 * keeps `HostAccess.NONE` with no class lookup, IO, process, or thread access.
 */
internal object ScriptValueGraalProxy {

    /** Converts a [ScriptValue] into something GraalJS can hand to the script. */
    fun toGuest(value: ScriptValue): Any? = when (value) {
        is ScriptValue.Null -> null
        is ScriptValue.Str -> value.value
        is ScriptValue.Bool -> value.value
        // Passed through boxed so an integral value keeps printing as `5`.
        is ScriptValue.Num -> value.value
        is ScriptValue.Obj -> ScriptObjectProxy(value)
        is ScriptValue.Arr -> ScriptArrayProxy(value)
        is ScriptValue.Fn -> ScriptFunctionProxy(value)
        is ScriptValue.Host -> HostObjectProxy.of(value.value)
    }

    /**
     * Converts a script-supplied argument back into a [ScriptValue].
     *
     * Objects with no better representation become their string form, which is
     * what `print` did for them before this API existed.
     */
    fun fromGuest(value: Value): ScriptValue = when {
        value.isNull -> ScriptValue.Null
        value.isString -> ScriptValue.Str(value.asString())
        value.isBoolean -> ScriptValue.Bool(value.asBoolean())
        value.isNumber -> when {
            value.fitsInInt() -> ScriptValue.Num(value.asInt())
            value.fitsInLong() -> ScriptValue.Num(value.asLong())
            else -> ScriptValue.Num(value.asDouble())
        }
        value.hasArrayElements() -> {
            val size = value.arraySize
            if (size > Int.MAX_VALUE) {
                ScriptValue.Str(value.toString())
            } else {
                ScriptValue.Arr(size.toInt()) { index -> fromGuest(value.getArrayElement(index.toLong())) }
            }
        }
        else -> ScriptValue.Str(value.toString())
    }

    private class ScriptObjectProxy(private val value: ScriptValue.Obj) : ProxyObject {
        private val fallback: ProxyObject? = value.hostFallback?.let { HostObjectProxy.of(it) }

        override fun getMember(key: String): Any? {
            // Deliberately not `members[key]?.let { … }`: a curated member whose
            // value is null must resolve to null rather than fall through.
            val member = value.members[key]
            if (member != null) return toGuest(member())
            return fallback?.getMember(key)
        }

        override fun getMemberKeys(): Any {
            val keys = LinkedHashSet(value.members.keys)
            fallback?.let { keys += HostObjectProxy.memberNames(it) }
            return ProxyArray.fromList(keys.toList())
        }

        override fun hasMember(key: String): Boolean =
            key in value.members || fallback?.hasMember(key) == true

        override fun putMember(key: String, value: Value) {
            // Read-only, and silently so: assigning to `binary.x` was a no-op
            // before this API existed and scripts may still do it.
        }
    }

    private class ScriptArrayProxy(private val value: ScriptValue.Arr) : ProxyArray {
        override fun get(index: Long): Any? {
            if (index < 0 || index >= value.size) {
                throw ArrayIndexOutOfBoundsException("Index $index is out of bounds for length ${value.size}")
            }
            return toGuest(value.at(index.toInt()))
        }

        override fun getSize(): Long = value.size.toLong()

        override fun set(index: Long, value: Value) {
            throw UnsupportedOperationException("Hexana script arrays are read-only")
        }
    }

    private class ScriptFunctionProxy(private val value: ScriptValue.Fn) : ProxyExecutable {
        override fun execute(vararg arguments: Value): Any? =
            toGuest(value.invoke(arguments.map(::fromGuest)))
    }
}
