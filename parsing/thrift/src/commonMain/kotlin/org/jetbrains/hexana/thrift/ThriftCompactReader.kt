package org.jetbrains.hexana.thrift

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Thrift Compact Protocol type nibble values.
 *
 * Verified against apache/thrift doc/specs/thrift-compact-protocol.md:
 *   STOP=0, BOOLEAN_TRUE=1, BOOLEAN_FALSE=2, BYTE=3, I16=4, I32=5,
 *   I64=6, DOUBLE=7, BINARY=8, LIST=9, SET=10, MAP=11, STRUCT=12.
 */
enum class ThriftType(val nibble: Int) {
    STOP(0),
    BOOLEAN_TRUE(1),
    BOOLEAN_FALSE(2),
    BYTE(3),
    I16(4),
    I32(5),
    I64(6),
    DOUBLE(7),
    BINARY(8),
    LIST(9),
    SET(10),
    MAP(11),
    STRUCT(12);

    companion object {
        private val BY_NIBBLE = Array(14) { n -> entries.firstOrNull { it.nibble == n } }

        fun fromNibble(nibble: Int): ThriftType =
            BY_NIBBLE.getOrNull(nibble)
                ?: error("Unknown Thrift Compact type nibble: $nibble")
    }
}

/** One decoded field header — id resolved from the running per-struct delta. */
data class ThriftField(val id: Int, val type: ThriftType)

/** Result of [ThriftCompactReader.readListBegin] / [ThriftCompactReader.readSetBegin]. */
data class ThriftContainer(val size: Int, val elemType: ThriftType)

/** Result of [ThriftCompactReader.readMapBegin]. */
data class ThriftMap(val size: Int, val keyType: ThriftType, val valType: ThriftType)

/**
 * Streaming pull-cursor reader for the Thrift Compact Protocol.
 *
 * The Compact protocol encodes struct fields as sequential headers; each header carries a
 * 4-bit delta from the previous field id and a 4-bit type nibble. Because random access
 * to field N requires traversing 0..N-1, this reader is a forward-only pull cursor, not
 * a random-access view (contrast: FlatBufferTable, which is vtable-indexed).
 *
 * Wire format verified against apache/thrift doc/specs/thrift-compact-protocol.md:
 *   - Field header: one byte `ddddtttt`; delta 1-15 = short form, delta 0 = long form
 *     (followed by zigzag-varint i16 field id).
 *   - STOP = full 0x00 byte.
 *   - i16/i32/i64: zigzag varint (built on buf.getULEB128()).
 *   - double: 8 bytes little-endian.
 *   - binary/string: varint (ULEB128) byte count + raw bytes.
 *   - list/set: one byte `sssstttt` (size 0-14 + elemType); size 15+ → `1111tttt` + varint size.
 *   - map: empty = 0x00; non-empty = varint size + one byte `kkkkvvvv` + pairs.
 *   - Nested struct: push a fresh field-id context; pop on readStructEnd.
 *
 * Thread-safety: not thread-safe. Each concurrent reader must hold its own [buf] duplicate.
 *
 * @param buf  A [CommonByteBuffer.duplicate] positioned at the start of the first struct's fields.
 */
class ThriftCompactReader(val buf: CommonByteBuffer) {

    /** Per-struct running field-id stack. Top is the active struct's last-seen field id. */
    private val fieldIdStack = ArrayDeque<Int>()

    /** Last field id in the currently active struct frame. */
    private var lastFieldId: Int = 0

    /** Maximum recursion depth for [skip] to guard against hostile/malformed input. */
    private val maxSkipDepth = 64

    // --- Struct iteration ---

    /**
     * Read the next field header in the current struct.
     *
     * Returns [ThriftField] with [ThriftType.STOP] when the struct ends.
     * The bool value is carried in the type nibble (BOOLEAN_TRUE/BOOLEAN_FALSE),
     * so no additional read is needed for bool fields — use [readBool] to extract it.
     */
    fun readFieldBegin(): ThriftField {
        val byte = buf.get().toInt() and 0xFF
        if (byte == 0) {
            return ThriftField(0, ThriftType.STOP)
        }
        val delta = (byte ushr 4) and 0xF
        val typeNibble = byte and 0xF
        val type = ThriftType.fromNibble(typeNibble)

        val fieldId = if (delta != 0) {
            lastFieldId + delta
        } else {
            // Long form: field id is a zigzag-encoded i16 varint following the header byte.
            buf.zigzag32()
        }
        lastFieldId = fieldId
        return ThriftField(fieldId, type)
    }

    // --- Scalar reads (call after readFieldBegin for the matching type) ---

    /**
     * Extract the boolean value for a bool field. The value is encoded in the type nibble
     * itself (BOOLEAN_TRUE or BOOLEAN_FALSE), so no bytes are consumed from [buf].
     */
    fun readBool(field: ThriftField): Boolean = when (field.type) {
        ThriftType.BOOLEAN_TRUE -> true
        ThriftType.BOOLEAN_FALSE -> false
        else -> error("readBool called on non-bool field type: ${field.type}")
    }

    /** Read one byte (i8). */
    fun readByte(): Byte = buf.get()

    /** Read an i16: zigzag varint (same encoding as i32, truncated to Short). */
    fun readI16(): Short = buf.zigzag32().toShort()

    /** Read an i32: zigzag varint via [CommonByteBuffer.zigzag32]. */
    fun readI32(): Int = buf.zigzag32()

    /** Read an i64: zigzag varint via [CommonByteBuffer.zigzag64]. */
    fun readI64(): Long = buf.zigzag64()

    /** Read a double: 8 bytes little-endian via [CommonByteBuffer.getDoubleLE]. */
    fun readDouble(): Double = buf.getDoubleLE()

    /**
     * Read a binary blob: ULEB128 byte count + raw bytes.
     * Returns a copy of the bytes. For zero-copy access to large blobs, use [binarySlice].
     */
    fun readBinary(): ByteArray {
        val length = buf.getULEB128()
        require(length in 0..Int.MAX_VALUE) { "Thrift binary length too large: $length" }
        val bytes = ByteArray(length.toInt())
        buf.get(bytes, 0, length.toInt())
        return bytes
    }

    /**
     * Read a UTF-8 string: [readBinary] decoded via [ByteArray.decodeToString].
     * Uses the common-safe stdlib path (works on jvm/js/wasmJs).
     */
    fun readString(): String = readBinary().decodeToString()

    // --- Container framing ---

    /**
     * Read a list or set header. Wire format: one byte `sssstttt`.
     * If size nibble == 15 (0xF), size follows as a ULEB128 varint.
     */
    fun readListBegin(): ThriftContainer {
        val byte = buf.get().toInt() and 0xFF
        val sizeNibble = (byte ushr 4) and 0xF
        val elemType = ThriftType.fromNibble(byte and 0xF)
        val size = if (sizeNibble == 0xF) {
            val v = buf.getULEB128()
            require(v in 0..Int.MAX_VALUE) { "Thrift list/set size too large: $v" }
            v.toInt()
        } else {
            sizeNibble
        }
        return ThriftContainer(size, elemType)
    }

    /** Read a set header. Identical encoding to list. */
    fun readSetBegin(): ThriftContainer = readListBegin()

    /**
     * Read a map header. Empty map = single 0x00 byte. Non-empty:
     * ULEB128 size + one byte `kkkkvvvv` + size key/value pairs.
     */
    fun readMapBegin(): ThriftMap {
        val rawSize = buf.getULEB128()
        require(rawSize in 0..Int.MAX_VALUE) { "Thrift map size too large: $rawSize" }
        val size = rawSize.toInt()
        if (size == 0) return ThriftMap(0, ThriftType.STOP, ThriftType.STOP)
        val typeByte = buf.get().toInt() and 0xFF
        val keyType = ThriftType.fromNibble((typeByte ushr 4) and 0xF)
        val valType = ThriftType.fromNibble(typeByte and 0xF)
        return ThriftMap(size, keyType, valType)
    }

    // --- Nested struct ---

    /**
     * Enter a nested struct: push the current running field-id onto the stack and reset
     * the active id to 0 for the new struct's delta sequence.
     */
    fun readStructBegin() {
        fieldIdStack.addLast(lastFieldId)
        lastFieldId = 0
    }

    /**
     * Exit a nested struct: pop the enclosing struct's field-id from the stack.
     * The caller must have consumed the STOP sentinel via [readFieldBegin] before calling this.
     */
    fun readStructEnd() {
        lastFieldId = fieldIdStack.removeLastOrNull()
            ?: error("readStructEnd called without a matching readStructBegin")
    }

    // --- Skip ---

    /**
     * Consume and discard a value of the given [type] without decoding it.
     * Essential for forward-compatibility (unknown fields in e.g. FileMetaData).
     * Recursive with a depth cap to guard against adversarial or malformed input.
     */
    fun skip(type: ThriftType, depth: Int = 0) {
        require(depth < maxSkipDepth) { "Thrift skip exceeded max depth $maxSkipDepth — possible circular structure" }
        when (type) {
            ThriftType.STOP -> { /* nothing to consume */ }
            ThriftType.BOOLEAN_TRUE, ThriftType.BOOLEAN_FALSE -> { /* value is in the nibble, no bytes */ }
            ThriftType.BYTE -> buf.get()
            ThriftType.I16 -> readI16()
            ThriftType.I32 -> readI32()
            ThriftType.I64 -> readI64()
            ThriftType.DOUBLE -> readDouble()
            ThriftType.BINARY -> readBinary()
            ThriftType.LIST, ThriftType.SET -> {
                val container = readListBegin()
                repeat(container.size) { skip(container.elemType, depth + 1) }
            }
            ThriftType.MAP -> {
                val map = readMapBegin()
                repeat(map.size) {
                    skip(map.keyType, depth + 1)
                    skip(map.valType, depth + 1)
                }
            }
            ThriftType.STRUCT -> {
                readStructBegin()
                while (true) {
                    val field = readFieldBegin()
                    if (field.type == ThriftType.STOP) break
                    skip(field.type, depth + 1)
                }
                readStructEnd()
            }
        }
    }

    // --- Zero-copy escape hatch ---

    /**
     * Zero-copy alternative to [readBinary] for large blobs (e.g. embedded statistics or
     * page headers). Reads the ULEB128 length and returns a [CommonByteBuffer.slice] over
     * those bytes without copying. Advances the cursor past the blob.
     */
    fun binarySlice(): CommonByteBuffer {
        val length = buf.getULEB128()
        require(length in 0..Int.MAX_VALUE) { "Thrift binarySlice length too large: $length" }
        val start = buf.position()
        val len = length.toInt()
        // Advance cursor past the slice region.
        buf.position(start + len)
        return buf.slice(start, len)
    }
}