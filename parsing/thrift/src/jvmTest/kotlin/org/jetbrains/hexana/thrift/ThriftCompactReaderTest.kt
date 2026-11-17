package org.jetbrains.hexana.thrift

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Thrift Compact Protocol fixture tests.
 *
 * Byte layouts are hand-built and documented inline.
 *
 * Wire-format rules verified against apache/thrift doc/specs/thrift-compact-protocol.md:
 *   - Field header: one byte `ddddtttt`; delta 1-15 → short form; delta 0 → long form
 *     followed by zigzag-varint i16 field id.
 *   - STOP = 0x00.
 *   - Type nibbles: BOOLEAN_TRUE=1, BOOLEAN_FALSE=2, BYTE=3, I16=4, I32=5, I64=6,
 *     DOUBLE=7, BINARY=8, LIST=9, SET=10, MAP=11, STRUCT=12.
 *   - i16/i32/i64: zigzag ULEB128. zigzag(n) = (n<<1) ^ (n>>(bits-1)).
 *   - double: 8 bytes LE.
 *   - binary/string: ULEB128 byte count + raw bytes.
 *   - list/set: byte `sssstttt`; size>=15 → `1111tttt` + ULEB128 size.
 *   - map: ULEB128 size; 0 → single 0x00; non-zero → byte `kkkkvvvv` + pairs.
 */
class ThriftCompactReaderTest {

    /**
     * Struct with bool fields (value in the type nibble).
     *
     * Struct fields:
     *   field 1, BOOLEAN_TRUE  → true   (header byte: delta=1, type=1 → 0x11)
     *   field 2, BOOLEAN_FALSE → false  (header byte: delta=1, type=2 → 0x12)
     *   STOP                             (0x00)
     *
     * No value bytes follow bool fields — the value is carried in the type nibble.
     */
    @Test
    fun `bool fields with value in nibble`() {
        val bytes = byteArrayOf(
            0x11.toByte(),  // field 1, BOOLEAN_TRUE
            0x12.toByte(),  // field 2, BOOLEAN_FALSE
            0x00            // STOP
        ).toCommonByteBuffer()

        val reader = ThriftCompactReader(bytes)

        val f1 = reader.readFieldBegin()
        assertEquals(1, f1.id)
        assertEquals(ThriftType.BOOLEAN_TRUE, f1.type)
        assertTrue(reader.readBool(f1))

        val f2 = reader.readFieldBegin()
        assertEquals(2, f2.id)
        assertEquals(ThriftType.BOOLEAN_FALSE, f2.type)
        assertFalse(reader.readBool(f2))

        val stop = reader.readFieldBegin()
        assertEquals(ThriftType.STOP, stop.type)
    }

    /**
     * Struct with i32 fields (positive and negative) to exercise zigzag decode.
     *
     * zigzag encode: (n << 1) ^ (n >> 31)
     *   zigzag(0)    = 0
     *   zigzag(1)    = 2
     *   zigzag(-1)   = 1
     *   zigzag(100)  = 200 (0xC8, single byte ULEB128 since 200 > 127: 0xC8 0x01)
     *   zigzag(-100) = 199 = 0xC7, needs 2 bytes: 0xC7 0x01
     *
     * Fields:
     *   field 1, I32 = 0    (header: 0x15; zigzag=0 → ULEB128: 0x00)
     *   field 2, I32 = 1    (header: 0x15; delta=1 → 0x15; zigzag=2 → 0x02)
     *   field 3, I32 = -1   (header: 0x15; delta=1 → 0x15; zigzag=1 → 0x01)
     *   field 4, I32 = 100  (header: 0x15; delta=1 → 0x15; zigzag=200 → 0xC8 0x01)
     *   field 5, I32 = -100 (header: 0x15; delta=1 → 0x15; zigzag=199 → 0xC7 0x01)
     *   STOP
     */
    @Test
    fun `i32 fields positive and negative zigzag`() {
        val bytes = byteArrayOf(
            0x15.toByte(),  // field 1, I32 (delta=1, type=5)
            0x00,           // zigzag(0) = 0
            0x15.toByte(),  // field 2, I32 (delta=1)
            0x02,           // zigzag(1) = 2
            0x15.toByte(),  // field 3, I32 (delta=1)
            0x01,           // zigzag(-1) = 1
            0x15.toByte(),  // field 4, I32 (delta=1)
            0xC8.toByte(), 0x01,  // zigzag(100) = 200 = ULEB128 two bytes
            0x15.toByte(),  // field 5, I32 (delta=1)
            0xC7.toByte(), 0x01,  // zigzag(-100) = 199 = ULEB128 two bytes
            0x00            // STOP
        ).toCommonByteBuffer()

        val reader = ThriftCompactReader(bytes)

        val f1 = reader.readFieldBegin(); assertEquals(1, f1.id)
        assertEquals(0, reader.readI32())

        val f2 = reader.readFieldBegin(); assertEquals(2, f2.id)
        assertEquals(1, reader.readI32())

        val f3 = reader.readFieldBegin(); assertEquals(3, f3.id)
        assertEquals(-1, reader.readI32())

        val f4 = reader.readFieldBegin(); assertEquals(4, f4.id)
        assertEquals(100, reader.readI32())

        val f5 = reader.readFieldBegin(); assertEquals(5, f5.id)
        assertEquals(-100, reader.readI32())

        assertEquals(ThriftType.STOP, reader.readFieldBegin().type)
    }

    /**
     * Struct with an i64 field and a string field.
     *
     * zigzag64(-9876543210L):
     *   (-9876543210L << 1) ^ (-9876543210L >> 63)
     *   = (-19753086420L) ^ (-1L)
     *   = (-19753086420L) xor 0xFFFFFFFFFFFFFFFFL
     *   = -19753086420L xor -1 = ... let's compute:
     *   -9876543210L in two's complement: 0xFFFFFFDB6CB3D756
     *   zigzag = (n << 1) ^ (n >> 63)
     *           = (0xFFFFFFB6D967AEAc) ^ 0xFFFFFFFFFFFFFFFF
     *           = 0x000000491698514 ... let me be precise:
     *   n = -9876543210L
     *   n << 1 = -19753086420L
     *   n >> 63 = -1L (arithmetic shift)
     *   zigzag = (-19753086420L) xor (-1L)
     *           = 19753086419L = 0x49_8E7B_EAB3
     *   ULEB128(0x498E7BEAB3):
     *     0xB3 | 0x80 = 0xB3, next
     *     0xF5 | 0x80: 0xEA>>0=... let me encode properly:
     *     Value: 19753086419 = 0x49_8E_7B_EA_B3
     *     Byte 0: 0xB3 & 0x7F | 0x80 = 0xB3 (bits 0-6 = 0x33, with MSB set)
     *     Byte 1: (0x49_8E_7B_EA_B3 >> 7) = 0x931_0F7_D5 shifted... easier to just compute:
     *     19753086419 >> 7 = 154320987 remainder?
     *     Actually let me just use a simple test value.
     *
     * Use i64 = 1000000000000L (1 trillion) instead:
     *   zigzag(1000000000000L) = 2000000000000L = 0x1_D1_A9_4A_20_00
     *   ULEB128:
     *     byte 0: 0x00 | 0x80 = 0x80 (bits 0-6 = 0)
     *     byte 1: 0x84 | 0x80 (bits 7-13 of 2000000000000)
     *     ...
     *   2000000000000 = 0x1D1A94A2000
     *   In binary: 0001_1101_0001_1010_1001_0100_1010_0010_0000_0000_0000
     *   ULEB128 (7 bits per byte, LSB first):
     *     bits 0-6:   000_0000  → 0x00 | 0x80 = 0x80
     *     bits 7-13:  100_0100  → 0x44 | 0x80 = 0xC4
     *     bits 14-20: 010_1000  → 0x28 | 0x80 = 0xA8 (wait: 0x1D1A94A2000 >> 14 = ?)
     *     2000000000000 / 128 = 15625000000 r 0
     *     15625000000 / 128 = 122070312 r 64 (0x40)
     *     122070312 / 128 = 953674 r 40 (0x28)
     *     953674 / 128 = 7450 r 74 (0x4A)
     *     7450 / 128 = 58 r 26 (0x1A)
     *     58 / 128 = 0 r 58 (0x3A)
     *   ULEB128 bytes (with continuation bits):
     *     0x80 (0 | 0x80), 0xC0 (64 | 0x80), 0xA8 (40 | 0x80), 0xCA (74 | 0x80),
     *     0x9A (26 | 0x80), 0x3A (58, no continuation)
     *
     * i64 field: field 1, I64 (header 0x16), zigzag 6 bytes above.
     * string field: field 2, BINARY (header 0x12 delta=1, type=8), ULEB128 length + bytes.
     *   string "hello": length=5, bytes 0x68 0x65 0x6C 0x6C 0x6F.
     *
     * Total: 0x16, 0x80,0xC0,0xA8,0xCA,0x9A,0x3A, 0x18, 0x05, 'h','e','l','l','o', 0x00
     */
    @Test
    fun `i64 and string fields`() {
        val bytes = byteArrayOf(
            // field 1, I64 (delta=1, type=6 → 0x16)
            0x16,
            // zigzag(1000000000000L) = 2000000000000L, ULEB128:
            0x80.toByte(), 0xC0.toByte(), 0xA8.toByte(), 0xCA.toByte(), 0x9A.toByte(), 0x3A,
            // field 2, BINARY (delta=1, type=8 → 0x18)
            0x18,
            // string "hello": length varint=5
            0x05,
            'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(),
            'l'.code.toByte(), 'o'.code.toByte(),
            // STOP
            0x00
        ).toCommonByteBuffer()

        val reader = ThriftCompactReader(bytes)

        val f1 = reader.readFieldBegin(); assertEquals(1, f1.id); assertEquals(ThriftType.I64, f1.type)
        assertEquals(1000000000000L, reader.readI64())

        val f2 = reader.readFieldBegin(); assertEquals(2, f2.id); assertEquals(ThriftType.BINARY, f2.type)
        assertEquals("hello", reader.readString())

        assertEquals(ThriftType.STOP, reader.readFieldBegin().type)
    }

    /**
     * Struct with a list field.
     *
     * List of i32: [1, 2, 3].
     * List header: size=3 (fits in nibble), elemType=I32(5) → byte = (3<<4)|5 = 0x35.
     * Each element is a zigzag ULEB128: 1→2=0x02, 2→4=0x04, 3→6=0x06.
     *
     * Struct:
     *   field 1, LIST (header 0x19 delta=1, type=9)
     *   list header 0x35 (size=3, I32)
     *   0x02, 0x04, 0x06
     *   STOP
     */
    @Test
    fun `list of i32`() {
        val bytes = byteArrayOf(
            0x19.toByte(),  // field 1, LIST (delta=1, type=9)
            0x35,           // list: size=3, elemType=I32(5)
            0x02,           // zigzag(1) = 2
            0x04,           // zigzag(2) = 4
            0x06,           // zigzag(3) = 6
            0x00            // STOP
        ).toCommonByteBuffer()

        val reader = ThriftCompactReader(bytes)
        val f1 = reader.readFieldBegin(); assertEquals(1, f1.id)
        val list = reader.readListBegin()
        assertEquals(3, list.size)
        assertEquals(ThriftType.I32, list.elemType)
        assertEquals(1, reader.readI32())
        assertEquals(2, reader.readI32())
        assertEquals(3, reader.readI32())
        assertEquals(ThriftType.STOP, reader.readFieldBegin().type)
    }

    /**
     * Skip over an unread field.
     *
     * Struct:
     *   field 1, I32 = 7  (we read this)
     *   field 2, BINARY = "skip me"  (we skip this)
     *   field 3, I32 = 99 (we read this)
     *   STOP
     *
     * Headers (all delta=1, type nibbles):
     *   field 1: 0x15 (delta=1, I32=5); value zigzag(7)=14=0x0E
     *   field 2: 0x18 (delta=1, BINARY=8); ULEB128(7)=0x07; 's','k','i','p',' ','m','e'
     *   field 3: 0x15 (delta=1, I32=5); zigzag(99)=198=0xC6 0x01
     *   STOP: 0x00
     */
    @Test
    fun `skip over unread field`() {
        val bytes = byteArrayOf(
            0x15.toByte(),  // field 1, I32
            0x0E,           // zigzag(7) = 14
            0x18.toByte(),  // field 2, BINARY
            0x07,           // length = 7
            's'.code.toByte(), 'k'.code.toByte(), 'i'.code.toByte(), 'p'.code.toByte(),
            ' '.code.toByte(), 'm'.code.toByte(), 'e'.code.toByte(),
            0x15.toByte(),  // field 3, I32
            0xC6.toByte(), 0x01,  // zigzag(99) = 198, ULEB128: 0xC6 0x01
            0x00            // STOP
        ).toCommonByteBuffer()

        val reader = ThriftCompactReader(bytes)

        val f1 = reader.readFieldBegin(); assertEquals(1, f1.id)
        assertEquals(7, reader.readI32())

        val f2 = reader.readFieldBegin(); assertEquals(2, f2.id)
        reader.skip(f2.type)  // skip the string

        val f3 = reader.readFieldBegin(); assertEquals(3, f3.id)
        assertEquals(99, reader.readI32())

        assertEquals(ThriftType.STOP, reader.readFieldBegin().type)
    }

    /**
     * Nested struct (push/pop readStructBegin/readStructEnd).
     *
     * Outer struct:
     *   field 1, I32 = 10
     *   field 2, STRUCT → inner struct:
     *     inner field 1, I32 = 20
     *     inner STOP
     *   outer STOP
     *
     * Outer field 1: header 0x15, zigzag(10)=20=0x14
     * Outer field 2: header 0x2C (delta=1, STRUCT=12 → (1<<4)|12=0x1C)
     *   Inner field 1: header 0x15 (delta=1, I32), zigzag(20)=40=0x28
     *   Inner STOP: 0x00
     * Outer STOP: 0x00
     */
    @Test
    fun `nested struct`() {
        val bytes = byteArrayOf(
            0x15.toByte(),  // outer field 1, I32
            0x14,           // zigzag(10) = 20
            0x1C.toByte(),  // outer field 2, STRUCT (delta=1, type=12 → (1<<4)|12 = 0x1C)
            // inner struct:
            0x15.toByte(),  // inner field 1, I32
            0x28,           // zigzag(20) = 40
            0x00,           // inner STOP
            // back to outer:
            0x00            // outer STOP
        ).toCommonByteBuffer()

        val reader = ThriftCompactReader(bytes)

        val f1 = reader.readFieldBegin(); assertEquals(1, f1.id)
        assertEquals(10, reader.readI32())

        val f2 = reader.readFieldBegin()
        assertEquals(2, f2.id)
        assertEquals(ThriftType.STRUCT, f2.type)

        reader.readStructBegin()
        val inner1 = reader.readFieldBegin(); assertEquals(1, inner1.id)
        assertEquals(20, reader.readI32())
        assertEquals(ThriftType.STOP, reader.readFieldBegin().type)
        reader.readStructEnd()

        assertEquals(ThriftType.STOP, reader.readFieldBegin().type)
    }

    /**
     * Long-form field id (delta == 0 → zigzag i16 follows).
     *
     * Field id 16 with I32 value 5.
     * Previous field id = 15 → delta = 16 - 15 = 1 (fits short form).
     * But delta > 15 always forces long form. Let's use field id 17 after field id 1:
     * delta = 16 → doesn't fit in 4 bits → long form.
     *
     * Field 1, I32 = 1 (short form, delta=1): 0x15, 0x02
     * Field 17, I32 = 2 (long form, delta=0): 0x05 (type I32 with delta=0), then zigzag(17)
     *   zigzag(17) = 34 = 0x22
     *
     * Header for long form: 0x05 (delta=0<<4 | type=5)
     * zigzag varint for id=17: (17<<1)^0 = 34 = 0x22, single byte.
     */
    @Test
    fun `long-form field id`() {
        val bytes = byteArrayOf(
            0x15.toByte(),  // field 1, I32 (delta=1)
            0x02,           // zigzag(1) = 2
            0x05.toByte(),  // field long-form, I32 (delta=0, type=5)
            0x22,           // zigzag(17) = 34
            0x02,           // zigzag(1) = 2 → value=1
            0x00            // STOP
        ).toCommonByteBuffer()

        val reader = ThriftCompactReader(bytes)
        val f1 = reader.readFieldBegin(); assertEquals(1, f1.id)
        assertEquals(1, reader.readI32())
        val f17 = reader.readFieldBegin(); assertEquals(17, f17.id)
        assertEquals(1, reader.readI32())
        assertEquals(ThriftType.STOP, reader.readFieldBegin().type)
    }

    /**
     * Map field test.
     *
     * Map of {I32 → BINARY}: { 1 → "a", 2 → "b" }
     * Map header: ULEB128(2) = 0x02; type byte: (I32=5 << 4) | BINARY=8 = 0x58
     * Entry 1: key = zigzag(1)=2=0x02; value = ULEB128(1)=0x01, 'a'
     * Entry 2: key = zigzag(2)=4=0x04; value = ULEB128(1)=0x01, 'b'
     *
     * Struct field 1, MAP (delta=1, type=11 → 0x1B):
     */
    @Test
    fun `map of i32 to string`() {
        val bytes = byteArrayOf(
            0x1B.toByte(),  // field 1, MAP (delta=1, type=11)
            0x02,           // map size = 2 (ULEB128)
            0x58,           // key type = I32(5), val type = BINARY(8): (5<<4)|8 = 0x58
            0x02,           // key 1 zigzag(1) = 2
            0x01, 'a'.code.toByte(),  // value "a": length=1, byte 'a'
            0x04,           // key 2 zigzag(2) = 4
            0x01, 'b'.code.toByte(),  // value "b": length=1, byte 'b'
            0x00            // STOP
        ).toCommonByteBuffer()

        val reader = ThriftCompactReader(bytes)
        val f1 = reader.readFieldBegin(); assertEquals(1, f1.id)
        val map = reader.readMapBegin()
        assertEquals(2, map.size)
        assertEquals(ThriftType.I32, map.keyType)
        assertEquals(ThriftType.BINARY, map.valType)

        assertEquals(1, reader.readI32())
        assertEquals("a", reader.readString())
        assertEquals(2, reader.readI32())
        assertEquals("b", reader.readString())

        assertEquals(ThriftType.STOP, reader.readFieldBegin().type)
    }
}