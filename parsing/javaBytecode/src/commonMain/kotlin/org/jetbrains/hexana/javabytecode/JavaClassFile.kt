package org.jetbrains.hexana.javabytecode

/**
 * Parsed JVM `.class` file (JVMS §4.1). MVP scope: header + constant pool +
 * class-level metadata + fields / methods as METADATA only — their
 * attributes are kept as opaque [AttributeRecord]s. Specific attribute
 * decoders (`Code`, `LineNumberTable`, …) and bytecode instruction parsing
 * are planned for follow-up commits.
 */
data class JavaClassFile(
    val minorVersion: Int,
    val majorVersion: Int,
    val constantPool: JavaConstantPool,
    val accessFlags: JavaAccessFlags,
    val thisClassIndex: Int,
    val superClassIndex: Int,
    val interfaceIndices: List<Int>,
    val fields: List<FieldInfo>,
    val methods: List<MethodInfo>,
    val attributes: List<AttributeRecord>,
) {
    /** Convenience: this class's binary name via the constant pool. */
    val thisClassName: String
        get() {
            val classRef = constantPool[thisClassIndex] as JavaConstantPoolEntry.ClassRef
            return constantPool.utf8(classRef.nameIndex)
        }

    /** Convenience: this class's super name, or `null` for `java/lang/Object`. */
    val superClassName: String?
        get() {
            if (superClassIndex == 0) return null
            val classRef = constantPool[superClassIndex] as JavaConstantPoolEntry.ClassRef
            return constantPool.utf8(classRef.nameIndex)
        }
}

data class FieldInfo(
    val accessFlags: JavaAccessFlags,
    val nameIndex: Int,
    val descriptorIndex: Int,
    val attributes: List<AttributeRecord>,
)

data class MethodInfo(
    val accessFlags: JavaAccessFlags,
    val nameIndex: Int,
    val descriptorIndex: Int,
    val attributes: List<AttributeRecord>,
)

/**
 * Opaque attribute body. `nameIndex` resolves through the constant pool to
 * the attribute's Utf8 name (`"Code"`, `"LineNumberTable"`, …); `info` is the
 * raw bytes between the length header and the next attribute. Specific
 * attribute decoders consume these in later commits.
 */
data class AttributeRecord(
    val nameIndex: Int,
    val info: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttributeRecord) return false
        return nameIndex == other.nameIndex && info.contentEquals(other.info)
    }

    override fun hashCode(): Int = 31 * nameIndex + info.contentHashCode()
}
