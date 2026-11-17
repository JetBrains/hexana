package org.jetbrains.hexana.javabytecode

import kotlin.jvm.JvmInline

/**
 * Bitmask of JVM access flags. The same `u2` field appears on classes,
 * fields, and methods with different meanings per bit; the helpers here are
 * named after JVMS Tables 4.1-B, 4.5-A, 4.6-A.
 *
 * Callers usually want to check specific bits (`isPublic`, `isStatic`, …);
 * see [JavaClassAccess], [JavaFieldAccess], [JavaMethodAccess] for the
 * flag-name constants per context.
 */
@JvmInline
value class JavaAccessFlags(val raw: Int) {
    fun has(flag: Int): Boolean = (raw and flag) != 0
    override fun toString(): String = "0x${raw.toString(16).padStart(4, '0')}"
}

/** Class-level access flags (JVMS §4.1, Table 4.1-B). */
object JavaClassAccess {
    const val PUBLIC = 0x0001
    const val FINAL = 0x0010
    const val SUPER = 0x0020
    const val INTERFACE = 0x0200
    const val ABSTRACT = 0x0400
    const val SYNTHETIC = 0x1000
    const val ANNOTATION = 0x2000
    const val ENUM = 0x4000
    const val MODULE = 0x8000
}

/** Field-level access flags (JVMS §4.5, Table 4.5-A). */
object JavaFieldAccess {
    const val PUBLIC = 0x0001
    const val PRIVATE = 0x0002
    const val PROTECTED = 0x0004
    const val STATIC = 0x0008
    const val FINAL = 0x0010
    const val VOLATILE = 0x0040
    const val TRANSIENT = 0x0080
    const val SYNTHETIC = 0x1000
    const val ENUM = 0x4000
}

/** Method-level access flags (JVMS §4.6, Table 4.6-A). */
object JavaMethodAccess {
    const val PUBLIC = 0x0001
    const val PRIVATE = 0x0002
    const val PROTECTED = 0x0004
    const val STATIC = 0x0008
    const val FINAL = 0x0010
    const val SYNCHRONIZED = 0x0020
    const val BRIDGE = 0x0040
    const val VARARGS = 0x0080
    const val NATIVE = 0x0100
    const val ABSTRACT = 0x0400
    const val STRICT = 0x0800
    const val SYNTHETIC = 0x1000
}
