package org.jetbrains.hexana.javabytecode

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class JavaClassFileParserTest {
    @Test
    fun `parses a minimal hand-crafted class file`() {
        // Synthesize a class file equivalent to:
        //     public class Hexana extends java.lang.Object {}
        // with zero fields, zero methods, zero class attributes. Constant
        // pool layout:
        //   #1 Utf8 "Hexana"
        //   #2 Class -> #1
        //   #3 Utf8 "java/lang/Object"
        //   #4 Class -> #3
        val bytes = ClassFileBytes().apply {
            u4(JavaClassFileParser.MAGIC)
            u2(0)               // minor_version
            u2(52)              // major_version (Java SE 8)
            u2(5)               // constant_pool_count = 4 entries + sentinel
            // #1 Utf8 "Hexana"
            u1(1); u2("Hexana".length); bytes("Hexana".encodeToByteArray())
            // #2 Class -> #1
            u1(7); u2(1)
            // #3 Utf8 "java/lang/Object"
            u1(1); u2("java/lang/Object".length); bytes("java/lang/Object".encodeToByteArray())
            // #4 Class -> #3
            u1(7); u2(3)
            // access_flags = ACC_PUBLIC | ACC_SUPER
            u2(JavaClassAccess.PUBLIC or JavaClassAccess.SUPER)
            u2(2)               // this_class -> #2 (Hexana)
            u2(4)               // super_class -> #4 (Object)
            u2(0)               // interfaces_count
            u2(0)               // fields_count
            u2(0)               // methods_count
            u2(0)               // attributes_count
        }.toByteArray()

        val classFile = JavaClassFileParser.parse(bytes.toCommonByteBuffer())

        assertEquals(52, classFile.majorVersion)
        assertEquals(0, classFile.minorVersion)
        assertEquals(5, classFile.constantPool.size)
        assertTrue(classFile.accessFlags.has(JavaClassAccess.PUBLIC))
        assertTrue(classFile.accessFlags.has(JavaClassAccess.SUPER))
        assertEquals("Hexana", classFile.thisClassName)
        assertEquals("java/lang/Object", classFile.superClassName)
        assertEquals(emptyList(), classFile.interfaceIndices)
        assertEquals(emptyList(), classFile.fields)
        assertEquals(emptyList(), classFile.methods)
        assertEquals(emptyList(), classFile.attributes)
    }

    @Test
    fun `handles Long and Double taking two constant pool slots`() {
        val bytes = ClassFileBytes().apply {
            u4(JavaClassFileParser.MAGIC); u2(0); u2(52)
            u2(5) // count = 4 -> means 4 real slots, but Long takes 2 -> so we
                  // populate Utf8, Long, Class -> Utf8. That's 3 entries
                  // occupying indices 1, 2 (+phantom 3), 4.
            u1(1); u2("X".length); bytes("X".encodeToByteArray())   // #1 Utf8
            u1(5); u4(0); u4(0x12345678)                            // #2 Long (also fills #3)
            u1(7); u2(1)                                            // #4 Class -> #1
            u2(JavaClassAccess.PUBLIC); u2(4); u2(0)                 // class meta
            u2(0); u2(0); u2(0); u2(0)                              // empty body
        }.toByteArray()

        val classFile = JavaClassFileParser.parse(bytes.toCommonByteBuffer())
        assertEquals(5, classFile.constantPool.size)

        val longEntry = classFile.constantPool[2]
        assertTrue(longEntry is JavaConstantPoolEntry.Long, "expected Long at #2, got $longEntry")
        assertEquals(0x12345678L, longEntry.value)
        assertTrue(classFile.constantPool[3] is JavaConstantPoolEntry.Unusable, "phantom slot")
        assertTrue(classFile.constantPool[4] is JavaConstantPoolEntry.ClassRef)
    }

    @Test
    fun `rejects bytes whose magic is not 0xCAFEBABE`() {
        val bytes = ClassFileBytes().apply { u4(0xDEADBEEF.toInt()) }.toByteArray()
        assertFails { JavaClassFileParser.parse(bytes.toCommonByteBuffer()) }
    }
}

/** Tiny BE byte-builder used only by these tests. */
private class ClassFileBytes {
    private val out = ArrayList<Byte>()
    fun u1(value: Int) { out.add((value and 0xFF).toByte()) }
    fun u2(value: Int) { u1(value ushr 8); u1(value) }
    fun u4(value: Int) { u1(value ushr 24); u1(value ushr 16); u1(value ushr 8); u1(value) }
    fun bytes(b: ByteArray) { for (x in b) out.add(x) }
    fun toByteArray(): ByteArray = ByteArray(out.size) { out[it] }
}
