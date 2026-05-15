package org.jetbrains.hexana.javabytecode

/**
 * A single constant pool entry. JVMS §4.4.
 *
 * The pool is 1-indexed and entries of type [Long] / [Double] take two slots
 * (the second slot is unusable per JVMS §4.4.5); [JavaConstantPool] handles
 * that for callers — see its `get` operator.
 */
sealed interface JavaConstantPoolEntry {
    /** Numeric tag from JVMS Table 4.4-A. */
    val tag: Int

    data class Utf8(val value: String) : JavaConstantPoolEntry {
        override val tag: Int get() = 1
    }

    data class Integer(val value: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 3
    }

    data class Float(val value: kotlin.Float) : JavaConstantPoolEntry {
        override val tag: Int get() = 4
    }

    data class Long(val value: kotlin.Long) : JavaConstantPoolEntry {
        override val tag: Int get() = 5
    }

    data class Double(val value: kotlin.Double) : JavaConstantPoolEntry {
        override val tag: Int get() = 6
    }

    data class ClassRef(val nameIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 7
    }

    data class StringRef(val stringIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 8
    }

    data class Fieldref(val classIndex: Int, val nameAndTypeIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 9
    }

    data class Methodref(val classIndex: Int, val nameAndTypeIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 10
    }

    data class InterfaceMethodref(val classIndex: Int, val nameAndTypeIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 11
    }

    data class NameAndType(val nameIndex: Int, val descriptorIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 12
    }

    data class MethodHandle(val referenceKind: Int, val referenceIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 15
    }

    data class MethodType(val descriptorIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 16
    }

    data class Dynamic(val bootstrapMethodAttrIndex: Int, val nameAndTypeIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 17
    }

    data class InvokeDynamic(val bootstrapMethodAttrIndex: Int, val nameAndTypeIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 18
    }

    data class ModuleRef(val nameIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 19
    }

    data class PackageRef(val nameIndex: Int) : JavaConstantPoolEntry {
        override val tag: Int get() = 20
    }

    /**
     * The phantom second slot occupied by a [Long] or [Double] entry. JVMS
     * §4.4.5 says any direct reference to such a slot is invalid; we still
     * keep one in the pool list so 1-based indexing into the underlying
     * array stays straightforward.
     */
    data object Unusable : JavaConstantPoolEntry {
        override val tag: Int get() = 0
    }
}

/**
 * Convenience view over the 1-indexed JVM constant pool. `entries[0]` is
 * always [JavaConstantPoolEntry.Unusable] (there's no constant pool index 0
 * in the JVMS); valid indices are `1..entries.size - 1`.
 */
class JavaConstantPool internal constructor(
    /** Size includes the unused index-0 sentinel and any Long / Double tails. */
    val size: Int,
    private val entries: List<JavaConstantPoolEntry>,
) {
    operator fun get(index: Int): JavaConstantPoolEntry {
        require(index in 1 until size) { "constant pool index $index out of range (1 until $size)" }
        return entries[index]
    }

    /** Walk only the *real* entries (skip unusable phantom slots and index 0). */
    fun asSequence(): Sequence<Pair<Int, JavaConstantPoolEntry>> = sequence {
        for (i in 1 until size) {
            val e = entries[i]
            if (e !is JavaConstantPoolEntry.Unusable) yield(i to e)
        }
    }

    /** Resolves a Utf8 entry by index; throws if the entry isn't Utf8. */
    fun utf8(index: Int): String {
        val entry = this[index]
        require(entry is JavaConstantPoolEntry.Utf8) {
            "constant pool index $index is ${entry::class.simpleName}, expected Utf8"
        }
        return entry.value
    }
}
