package org.jetbrains.hexana.mcp.scripting

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyObject
import java.lang.reflect.InaccessibleObjectException
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.IllegalCallableAccessException
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Read-only reflective view of a host object, used for the `binary.raw` escape
 * hatch and for the fall-through that keeps pre-v1 scripts working.
 *
 * Only public *properties* are exposed, and only as data — no methods, no
 * constructors, no class handles. The GraalJS context still runs with
 * `HostAccess.NONE`, no host class lookup, no IO, and no process or thread
 * creation; a script cannot reach anything from here that it could not already
 * read from the binary it was handed.
 *
 * Property values are resolved on access, which matters because provider
 * properties are lazily computed analyses. Listing the member names is cheap;
 * reading one may not be, and is bounded by the script timeout.
 */
internal object HostObjectProxy {

    /** Wraps [value] for the script, converting primitives and lists in place. */
    fun of(value: Any): ProxyObject = ReflectiveProxy(value)

    /** Member names of a proxy produced by [of]; empty for anything else. */
    fun memberNames(proxy: ProxyObject): List<String> =
        (proxy as? ReflectiveProxy)?.propertyNames ?: emptyList()

    private fun wrap(value: Any?): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Char -> value.toString()
        // Kotlin's unsigned types are not java.lang.Number, so without this they
        // would reflect into an opaque `{ data: … }` wrapper. Provider records use
        // ULong for addresses and sizes throughout, so this matters in practice.
        is UByte -> value.toInt()
        is UShort -> value.toInt()
        is UInt -> value.toLong()
        is ULong -> if (value <= Long.MAX_VALUE.toULong()) value.toLong() else value.toString()
        // An enum is a value, not a record: `{ name, ordinal }` is less useful
        // than the constant name a script wants to compare against.
        is Enum<*> -> value.name
        is List<*> -> LazyListProxy(value)
        is ByteArray -> ByteArrayProxy(value)
        is Array<*> -> LazyListProxy(value.asList())
        is Map<*, *> -> MapProxy(value)
        else -> if (hasProperties(value::class)) ReflectiveProxy(value) else value.toString()
    }

    private fun hasProperties(type: KClass<*>): Boolean = properties(type).isNotEmpty()

    /**
     * Cached public properties per class. `memberProperties` walks the whole
     * hierarchy and is far too slow to redo for every element of a list of
     * thousands of records.
     */
    private val propertyCache = HashMap<KClass<*>, Map<String, KProperty1<Any, *>>>()

    @Suppress("UNCHECKED_CAST")
    private fun properties(type: KClass<*>): Map<String, KProperty1<Any, *>> =
        synchronized(propertyCache) {
            propertyCache.getOrPut(type) {
                try {
                    type.memberProperties
                        .filter { it.visibility == KVisibility.PUBLIC }
                        .onEach { property ->
                            // A public property declared on a non-public class —
                            // an internal record type, a private subclass — has an
                            // accessible getter on an inaccessible owner, and
                            // reading it would otherwise throw.
                            runCatching { property.isAccessible = true }
                        }
                        .associateBy { it.name } as Map<String, KProperty1<Any, *>>
                } catch (_: Throwable) {
                    // Some synthetic and platform classes cannot be reflected on;
                    // an empty view is better than failing the whole script.
                    emptyMap()
                }
            }
        }

    /**
     * Reads one property for the script.
     *
     * A property the JVM refuses to expose reads as `undefined`; one whose own
     * computation fails propagates, because that is a real analysis error the
     * user wants to see. `binary.raw` spans a heterogeneous object graph, and one
     * unreachable corner of it must not take the whole script down.
     */
    private fun read(property: KProperty1<Any, *>, target: Any): Any? {
        val value = try {
            property.get(target)
        } catch (_: IllegalAccessException) {
            return null
        } catch (_: InaccessibleObjectException) {
            return null
        } catch (_: IllegalCallableAccessException) {
            return null
        }
        return wrap(value)
    }

    private class ReflectiveProxy(private val target: Any) : ProxyObject {
        private val properties = properties(target::class)

        val propertyNames: List<String> get() = properties.keys.toList()

        override fun getMember(key: String): Any? {
            val property = properties[key] ?: return null
            return read(property, target)
        }

        override fun getMemberKeys(): Any = ProxyArray.fromList(propertyNames)

        override fun hasMember(key: String): Boolean = key in properties

        override fun putMember(key: String, value: Value) {
            // Read-only.
        }
    }

    private class LazyListProxy(private val items: List<*>) : ProxyArray {
        override fun get(index: Long): Any? {
            if (index < 0 || index >= items.size) {
                throw ArrayIndexOutOfBoundsException("Index $index is out of bounds for length ${items.size}")
            }
            return wrap(items[index.toInt()])
        }

        override fun getSize(): Long = items.size.toLong()

        override fun set(index: Long, value: Value) {
            throw UnsupportedOperationException("Hexana script arrays are read-only")
        }
    }

    /** Byte arrays surface as unsigned numbers, matching `binary.bytes`. */
    private class ByteArrayProxy(private val bytes: ByteArray) : ProxyArray {
        override fun get(index: Long): Any {
            if (index < 0 || index >= bytes.size) {
                throw ArrayIndexOutOfBoundsException("Index $index is out of bounds for length ${bytes.size}")
            }
            return bytes[index.toInt()].toInt() and 0xFF
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun set(index: Long, value: Value) {
            throw UnsupportedOperationException("Hexana script arrays are read-only")
        }
    }

    private class MapProxy(source: Map<*, *>) : ProxyObject {
        private val entries: Map<String, Any?> = source.entries.associate { it.key.toString() to it.value }

        override fun getMember(key: String): Any? = wrap(entries[key])

        override fun getMemberKeys(): Any = ProxyArray.fromList(entries.keys.toList())

        override fun hasMember(key: String): Boolean = key in entries

        override fun putMember(key: String, value: Value) {
            // Read-only.
        }
    }
}
