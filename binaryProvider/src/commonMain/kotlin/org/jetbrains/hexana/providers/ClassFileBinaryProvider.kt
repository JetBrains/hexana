package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.javabytecode.JavaClassFile
import org.jetbrains.hexana.javabytecode.JavaClassFileParser
import org.jetbrains.hexana.javabytecode.JavaConstantPool
import org.jetbrains.hexana.javabytecode.JavaConstantPoolEntry

/**
 * Binary provider for JVM `.class` files. Extends [GenericBinaryProvider] so the
 * hex view works as before, and adds a [parsedClassFile] lazy property that parses
 * the class-file structure once and caches the result for all panels.
 */
class ClassFileBinaryProvider(
    sharedBytes: CommonByteBuffer,
) : GenericBinaryProvider(sharedBytes) {

    /**
     * Lazily parsed class-file structure, or `null` when parsing fails.
     */
    val parsedClassFile: JavaClassFile? by lazy {
        try {
            JavaClassFileParser.parse(bytes.position(0))
        } catch (_: Throwable) {
            null
        }
    }

    override val strings: List<BinaryStringRecord> by lazy {
        val cf = parsedClassFile ?: return@lazy emptyList()
        cf.constantPool.asSequence().map { (index, entry) ->
            BinaryStringRecord(
                value = renderConstantPoolEntry(entry, cf.constantPool),
                index = index,
                tag = constantPoolEntryTag(entry),
            )
        }.toList()
    }

    companion object {
        /**
         * Detects whether [buffer] starts with the JVM class file magic (`0xCAFEBABE`)
         * and is not a Mach-O universal (fat) binary, which shares the same magic.
         *
         * Disambiguation: the 32-bit big-endian integer at offset 4 in a Mach-O fat
         * binary is the architecture count (currently ≤ 18 labelled architectures).
         * In a Java class file the same bytes hold the minor version (u16) and the
         * start of the major version (u16 ≥ 43). Reading them as a single big-endian
         * int yields a value well above any realistic Mach-O arch count. A threshold
         * of 30 safely separates the two formats with room for future Mach-O additions.
         */
        fun isClassFile(buffer: CommonByteBuffer): Boolean {
            if (buffer.size < 8) return false
            val hasMagic = buffer[0] == 0xCA.toByte() &&
                buffer[1] == 0xFE.toByte() &&
                buffer[2] == 0xBA.toByte() &&
                buffer[3] == 0xBE.toByte()
            if (!hasMagic) return false
            // Read bytes 4..7 as a big-endian 32-bit unsigned value.
            val word4 = ((buffer[4].toInt() and 0xFF) shl 24) or
                ((buffer[5].toInt() and 0xFF) shl 16) or
                ((buffer[6].toInt() and 0xFF) shl 8) or
                (buffer[7].toInt() and 0xFF)
            // Mach-O fat binaries have a small arch count here (≤ ~18);
            // Java class files have minor_version (u16) | major_version (u16 ≥ 43),
            // so the combined 32-bit value is always > 30.
            return word4 > 30
        }
    }
}


private fun constantPoolEntryTag(entry: JavaConstantPoolEntry): String = when (entry) {
    is JavaConstantPoolEntry.Utf8 -> "Utf8"
    is JavaConstantPoolEntry.Integer -> "Integer"
    is JavaConstantPoolEntry.Float -> "Float"
    is JavaConstantPoolEntry.Long -> "Long"
    is JavaConstantPoolEntry.Double -> "Double"
    is JavaConstantPoolEntry.ClassRef -> "Class"
    is JavaConstantPoolEntry.StringRef -> "String"
    is JavaConstantPoolEntry.Fieldref -> "Fieldref"
    is JavaConstantPoolEntry.Methodref -> "Methodref"
    is JavaConstantPoolEntry.InterfaceMethodref -> "InterfaceMethodref"
    is JavaConstantPoolEntry.NameAndType -> "NameAndType"
    is JavaConstantPoolEntry.MethodHandle -> "MethodHandle"
    is JavaConstantPoolEntry.MethodType -> "MethodType"
    is JavaConstantPoolEntry.Dynamic -> "Dynamic"
    is JavaConstantPoolEntry.InvokeDynamic -> "InvokeDynamic"
    is JavaConstantPoolEntry.ModuleRef -> "Module"
    is JavaConstantPoolEntry.PackageRef -> "Package"
    JavaConstantPoolEntry.Unusable -> "(unusable)"
}

private fun renderConstantPoolEntry(entry: JavaConstantPoolEntry, cp: JavaConstantPool): String = when (entry) {
    is JavaConstantPoolEntry.Utf8 -> "\"${entry.value}\""
    is JavaConstantPoolEntry.Integer -> "${entry.value}"
    is JavaConstantPoolEntry.Float -> "${entry.value}"
    is JavaConstantPoolEntry.Long -> "${entry.value}"
    is JavaConstantPoolEntry.Double -> "${entry.value}"
    is JavaConstantPoolEntry.ClassRef -> "#${entry.nameIndex}  // ${runCatching { cp.utf8(entry.nameIndex) }.getOrDefault("?")}"
    is JavaConstantPoolEntry.StringRef -> "#${entry.stringIndex}  // \"${runCatching { cp.utf8(entry.stringIndex) }.getOrDefault("?")}\""
    is JavaConstantPoolEntry.Fieldref -> "#${entry.classIndex}.#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.Methodref -> "#${entry.classIndex}.#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.InterfaceMethodref -> "#${entry.classIndex}.#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.NameAndType -> "#${entry.nameIndex}:#${entry.descriptorIndex}"
    is JavaConstantPoolEntry.MethodHandle -> "kind=${entry.referenceKind} ref=#${entry.referenceIndex}"
    is JavaConstantPoolEntry.MethodType -> "#${entry.descriptorIndex}"
    is JavaConstantPoolEntry.Dynamic -> "bsm=${entry.bootstrapMethodAttrIndex} nat=#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.InvokeDynamic -> "bsm=${entry.bootstrapMethodAttrIndex} nat=#${entry.nameAndTypeIndex}"
    is JavaConstantPoolEntry.ModuleRef -> "#${entry.nameIndex}"
    is JavaConstantPoolEntry.PackageRef -> "#${entry.nameIndex}"
    JavaConstantPoolEntry.Unusable -> "(unusable)"
}
