package org.jetbrains.hexana.javabytecode

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Parses JVM `.class` files (JVMS §4.1) on top of [CommonByteBuffer].
 *
 * The buffer is read big-endian (the JVM class file convention); only the
 * little-endian helpers exist on `CommonByteBuffer`, so this parser does the
 * byte-swapping locally.
 *
 * MVP scope: header + constant pool + class metadata + field / method
 * metadata. Attributes are kept opaque as [AttributeRecord]s; specific
 * attribute parsers (`Code`, `LineNumberTable`, …) follow in later commits.
 */
object JavaClassFileParser {
    /** ClassFile.magic per JVMS §4.1: `0xCAFEBABE` in big-endian. */
    const val MAGIC: Int = 0xCAFEBABE.toInt()

    fun parse(buffer: CommonByteBuffer): JavaClassFile {
        val magic = buffer.readU4()
        require(magic == MAGIC) {
            "not a JVM class file: magic = 0x${magic.toUInt().toString(16).padStart(8, '0')}"
        }
        val minor = buffer.readU2()
        val major = buffer.readU2()
        val constantPool = parseConstantPool(buffer)
        val constantPoolEnd = buffer.position()
        val accessFlags = JavaAccessFlags(buffer.readU2())
        val thisClass = buffer.readU2()
        val superClass = buffer.readU2()

        val interfacesCount = buffer.readU2()
        val interfaces = List(interfacesCount) { buffer.readU2() }

        val fieldsCount = buffer.readU2()
        val fields = List(fieldsCount) { parseFieldOrMethod(buffer, ::FieldInfo) }

        val methodsCount = buffer.readU2()
        val methods = List(methodsCount) { parseFieldOrMethod(buffer, ::MethodInfo) }

        val attributes = parseAttributes(buffer)

        return JavaClassFile(
            minorVersion = minor,
            majorVersion = major,
            constantPool = constantPool,
            accessFlags = accessFlags,
            thisClassIndex = thisClass,
            superClassIndex = superClass,
            interfaceIndices = interfaces,
            fields = fields,
            methods = methods,
            attributes = attributes,
            constantPoolEndOffset = constantPoolEnd,
        )
    }

    private fun parseConstantPool(buffer: CommonByteBuffer): JavaConstantPool {
        val count = buffer.readU2()
        // The pool is 1-indexed; entries[0] is a sentinel and the size is
        // `count` (matching JVMS terminology — `constant_pool_count` is one
        // more than the actual number of usable indices).
        val entries = ArrayList<JavaConstantPoolEntry>(count)
        entries.add(JavaConstantPoolEntry.Unusable) // index 0
        var i = 1
        while (i < count) {
            val entry = parseConstantPoolEntry(buffer)
            entries.add(entry)
            i++
            // JVMS §4.4.5: Long / Double take two slots; the second is
            // unusable but still consumes an index.
            if (entry is JavaConstantPoolEntry.Long || entry is JavaConstantPoolEntry.Double) {
                entries.add(JavaConstantPoolEntry.Unusable)
                i++
            }
        }
        return JavaConstantPool(size = count, entries = entries)
    }

    private fun parseConstantPoolEntry(buffer: CommonByteBuffer): JavaConstantPoolEntry {
        val tag = buffer.readU1()
        return when (tag) {
            1 -> {
                val length = buffer.readU2()
                val bytes = buffer.readBytes(length)
                // JVMS uses "modified UTF-8" (null encoded as 0xC0 0x80, no
                // 4-byte sequences); standard decodeToString() handles the
                // common ASCII / BMP case correctly. Specialised modified
                // UTF-8 decoding can replace this when we hit a real fixture.
                JavaConstantPoolEntry.Utf8(bytes.decodeToString())
            }
            3 -> JavaConstantPoolEntry.Integer(buffer.readU4())
            4 -> JavaConstantPoolEntry.Float(Float.fromBits(buffer.readU4()))
            5 -> JavaConstantPoolEntry.Long(buffer.readI8())
            6 -> JavaConstantPoolEntry.Double(Double.fromBits(buffer.readI8()))
            7 -> JavaConstantPoolEntry.ClassRef(buffer.readU2())
            8 -> JavaConstantPoolEntry.StringRef(buffer.readU2())
            9 -> JavaConstantPoolEntry.Fieldref(buffer.readU2(), buffer.readU2())
            10 -> JavaConstantPoolEntry.Methodref(buffer.readU2(), buffer.readU2())
            11 -> JavaConstantPoolEntry.InterfaceMethodref(buffer.readU2(), buffer.readU2())
            12 -> JavaConstantPoolEntry.NameAndType(buffer.readU2(), buffer.readU2())
            15 -> JavaConstantPoolEntry.MethodHandle(buffer.readU1(), buffer.readU2())
            16 -> JavaConstantPoolEntry.MethodType(buffer.readU2())
            17 -> JavaConstantPoolEntry.Dynamic(buffer.readU2(), buffer.readU2())
            18 -> JavaConstantPoolEntry.InvokeDynamic(buffer.readU2(), buffer.readU2())
            19 -> JavaConstantPoolEntry.ModuleRef(buffer.readU2())
            20 -> JavaConstantPoolEntry.PackageRef(buffer.readU2())
            else -> error("unknown constant pool tag $tag")
        }
    }

    private fun <T> parseFieldOrMethod(
        buffer: CommonByteBuffer,
        ctor: (JavaAccessFlags, Int, Int, List<AttributeRecord>, Int) -> T,
    ): T {
        val byteOffset = buffer.position()
        val accessFlags = JavaAccessFlags(buffer.readU2())
        val nameIndex = buffer.readU2()
        val descriptorIndex = buffer.readU2()
        val attributes = parseAttributes(buffer)
        return ctor(accessFlags, nameIndex, descriptorIndex, attributes, byteOffset)
    }

    private fun parseAttributes(buffer: CommonByteBuffer): List<AttributeRecord> {
        val count = buffer.readU2()
        return List(count) {
            val byteOffset = buffer.position()
            val nameIndex = buffer.readU2()
            val length = buffer.readU4()
            val info = buffer.readBytes(length)
            AttributeRecord(nameIndex, info, byteOffset)
        }
    }
}

// -----------------------------------------------------------------------------
// Big-endian readers over CommonByteBuffer
//
// CommonByteBuffer ships only little-endian helpers; JVM class files are
// strictly big-endian (JVMS §4.1, third paragraph). These local helpers keep
// the patch self-contained — no changes to :encdec.
// -----------------------------------------------------------------------------

private fun CommonByteBuffer.readU1(): Int = get().toInt() and 0xFF

private fun CommonByteBuffer.readU2(): Int = (readU1() shl 8) or readU1()

private fun CommonByteBuffer.readU4(): Int =
    (readU1() shl 24) or (readU1() shl 16) or (readU1() shl 8) or readU1()

private fun CommonByteBuffer.readI8(): Long {
    val hi = readU4().toLong() and 0xFFFF_FFFFL
    val lo = readU4().toLong() and 0xFFFF_FFFFL
    return (hi shl 32) or lo
}

private fun CommonByteBuffer.readBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    if (n > 0) get(out, 0, n)
    return out
}
