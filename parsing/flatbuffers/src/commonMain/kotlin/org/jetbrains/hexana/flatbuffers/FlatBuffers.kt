package org.jetbrains.hexana.flatbuffers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation

/**
 * Lazy, zero-copy, position-independent FlatBuffers table reader.
 *
 * Wire layout (all LE, per flatbuffers.dev/internals):
 *   - Table at [pos]: int32 soffset → vtable at (pos - soffset).
 *   - Vtable: [u16 vtableSize][u16 tableSize][u16 fieldOffset × N].
 *     Field slot i is at vtablePos + 4 + i*2; value 0 means field absent.
 *   - Present field value is at pos + fieldOffset.
 *   - Sub-object offsets (table/string/vector) are u32 values relative to their own location:
 *     target = offsetLoc + u32.
 *
 * This class is schema-agnostic: slot numbers carry no names. Schema knowledge lives in callers.
 *
 * @param buf  A [CommonByteBuffer.duplicate] with its own independent cursor.
 * @param pos  Absolute position of the table's soffset field within [buf].
 */
class FlatBufferTable(val buf: CommonByteBuffer, val pos: Int) {

    /**
     * Resolve the byte offset of a field slot within the table, or 0 if absent.
     * Value 0 means the field is not present (use default). Result is relative to [pos].
     */
    fun fieldOffset(vtableSlot: Int): Int = buf.crossSectionOperation {
        // soffset_t: signed 32-bit; vtablePos = pos - soffset
        buf.position(pos)
        val soffset = buf.getIntLE()
        val vtablePos = pos - soffset

        buf.position(vtablePos)
        val vtableSize = buf.getShortLE().toInt() and 0xFFFF  // u16
        // Slot i lives at vtablePos + 4 + i*2 (past the two u16 header words).
        val slotByteOffset = 4 + vtableSlot * 2
        if (slotByteOffset + 2 > vtableSize) return@crossSectionOperation 0
        buf.position(vtablePos + slotByteOffset)
        buf.getShortLE().toInt() and 0xFFFF
    }

    /** True when the given vtable slot is present (offset != 0). */
    fun hasField(vtableSlot: Int): Boolean = fieldOffset(vtableSlot) != 0

    // --- Scalar reads ---

    fun getByte(slot: Int, default: Byte = 0): Byte {
        val fo = fieldOffset(slot)
        if (fo == 0) return default
        return buf[pos + fo]
    }

    fun getBool(slot: Int, default: Boolean = false): Boolean {
        val fo = fieldOffset(slot)
        if (fo == 0) return default
        return buf[pos + fo] != 0.toByte()
    }

    fun getShort(slot: Int, default: Short = 0): Short {
        val fo = fieldOffset(slot)
        if (fo == 0) return default
        return buf.crossSectionOperation {
            buf.position(pos + fo)
            buf.getShortLE()
        }
    }

    fun getInt(slot: Int, default: Int = 0): Int {
        val fo = fieldOffset(slot)
        if (fo == 0) return default
        return buf.crossSectionOperation {
            buf.position(pos + fo)
            buf.getIntLE()
        }
    }

    fun getLong(slot: Int, default: Long = 0L): Long {
        val fo = fieldOffset(slot)
        if (fo == 0) return default
        return buf.crossSectionOperation {
            buf.position(pos + fo)
            buf.getLongLE()
        }
    }

    fun getFloat(slot: Int, default: Float = 0f): Float {
        val fo = fieldOffset(slot)
        if (fo == 0) return default
        return buf.crossSectionOperation {
            buf.position(pos + fo)
            buf.getFloatLE()
        }
    }

    fun getDouble(slot: Int, default: Double = 0.0): Double {
        val fo = fieldOffset(slot)
        if (fo == 0) return default
        return buf.crossSectionOperation {
            buf.position(pos + fo)
            buf.getDoubleLE()
        }
    }

    // --- Sub-object reads ---

    /**
     * Resolve a table field. The field stores a u32 offset relative to the field's own location;
     * target = offsetLoc + u32.
     */
    fun getTable(slot: Int): FlatBufferTable? {
        val fo = fieldOffset(slot)
        if (fo == 0) return null
        val offsetLoc = pos + fo
        val relOffset = buf.crossSectionOperation {
            buf.position(offsetLoc)
            buf.getIntLE().toLong() and 0xFFFFFFFFL
        }
        require(relOffset in 0..Int.MAX_VALUE) { "FlatBuffers table offset out of range: $relOffset" }
        val tablePos = offsetLoc + relOffset.toInt()
        require(tablePos in 0 until buf.size) { "FlatBuffers table pos out of bounds: $tablePos" }
        return FlatBufferTable(buf, tablePos)
    }

    /**
     * Resolve a string field. Strings: [u32 length][bytes]['\0'].
     * Returns null when the field is absent.
     */
    fun getString(slot: Int): String? {
        val fo = fieldOffset(slot)
        if (fo == 0) return null
        val offsetLoc = pos + fo
        val relOffset = buf.crossSectionOperation {
            buf.position(offsetLoc)
            buf.getIntLE().toLong() and 0xFFFFFFFFL
        }
        require(relOffset in 0..Int.MAX_VALUE) { "FlatBuffers string offset out of range: $relOffset" }
        val strPos = offsetLoc + relOffset.toInt()
        require(strPos in 0 until buf.size) { "FlatBuffers string pos out of bounds: $strPos" }
        return buf.crossSectionOperation {
            buf.position(strPos)
            val length = buf.getIntLE().toLong() and 0xFFFFFFFFL
            // A string cannot extend past the buffer; bound the length before allocating so a
            // garbage length field throws instead of attempting a multi-GB allocation.
            require(length in 0..(buf.size.toLong() - strPos - 4)) {
                "FlatBuffers string length out of bounds: $length (bufSize=${buf.size}, strPos=$strPos)"
            }
            val bytes = ByteArray(length.toInt())
            buf.get(bytes, 0, length.toInt())
            bytes.decodeToString()
        }
    }

    /**
     * Resolve a vector field. Returns null when the field is absent.
     * The [FlatBufferVector] pos points to the u32 element-count prefix.
     */
    fun getVector(slot: Int): FlatBufferVector? {
        val fo = fieldOffset(slot)
        if (fo == 0) return null
        val offsetLoc = pos + fo
        val relOffset = buf.crossSectionOperation {
            buf.position(offsetLoc)
            buf.getIntLE().toLong() and 0xFFFFFFFFL
        }
        require(relOffset in 0..Int.MAX_VALUE) { "FlatBuffers vector offset out of range: $relOffset" }
        val vecPos = offsetLoc + relOffset.toInt()
        require(vecPos in 0 until buf.size) { "FlatBuffers vector pos out of bounds: $vecPos" }
        return FlatBufferVector(buf, vecPos)
    }

    /**
     * Returns the absolute position of an inline struct field, or null if the field is absent.
     * Inline structs carry no vtable; the caller reads fields directly via absolute offsets.
     */
    fun getStruct(slot: Int): Int? {
        val fo = fieldOffset(slot)
        if (fo == 0) return null
        return pos + fo
    }
}

/**
 * Lazy, zero-copy FlatBuffers vector reader.
 *
 * Wire layout: [u32 elementCount][element...].
 * For scalar/struct vectors, elements are inline with per-type stride.
 * For table/string vectors, elements are u32 relative offsets (target = offsetLoc + u32).
 *
 * @param buf  Shared buffer (independent cursor via [CommonByteBuffer.duplicate] at call site).
 * @param pos  Absolute position of the u32 element-count prefix.
 */
class FlatBufferVector(val buf: CommonByteBuffer, val pos: Int) {

    /** Number of elements (reads the u32 count at [pos]). */
    val length: Int
        get() = buf.crossSectionOperation {
            buf.position(pos)
            buf.getIntLE().toLong().and(0xFFFFFFFFL).toInt()
        }

    /**
     * Absolute position of element [i] for a scalar/struct vector with the given [stride] (bytes
     * per element). For stride-4 int vectors, stride=4; for stride-1 byte vectors, stride=1.
     */
    fun elementPos(i: Int, stride: Int = 4): Int {
        require(i in 0 until length) { "FlatBufferVector index $i out of range [0, ${length})" }
        return pos + 4 + i * stride
    }

    /** Read element [i] of a vector<table>. */
    fun table(i: Int): FlatBufferTable {
        val offsetLoc = elementPos(i, stride = 4)
        val relOffset = buf.crossSectionOperation {
            buf.position(offsetLoc)
            buf.getIntLE().toLong() and 0xFFFFFFFFL
        }
        require(relOffset in 0..Int.MAX_VALUE) { "FlatBuffers vector table offset out of range: $relOffset" }
        val tablePos = offsetLoc + relOffset.toInt()
        require(tablePos in 0 until buf.size) { "FlatBuffers vector table pos out of bounds: $tablePos" }
        return FlatBufferTable(buf, tablePos)
    }

    /** Read element [i] of a vector<string>. */
    fun string(i: Int): String {
        val offsetLoc = elementPos(i, stride = 4)
        val relOffset = buf.crossSectionOperation {
            buf.position(offsetLoc)
            buf.getIntLE().toLong() and 0xFFFFFFFFL
        }
        require(relOffset in 0..Int.MAX_VALUE) { "FlatBuffers vector string offset out of range: $relOffset" }
        val strPos = offsetLoc + relOffset.toInt()
        require(strPos in 0 until buf.size) { "FlatBuffers vector string pos out of bounds: $strPos" }
        return buf.crossSectionOperation {
            buf.position(strPos)
            val length = buf.getIntLE().toLong() and 0xFFFFFFFFL
            require(length in 0..(buf.size.toLong() - strPos - 4)) {
                "FlatBuffers vector string length out of bounds: $length (bufSize=${buf.size}, strPos=$strPos)"
            }
            val bytes = ByteArray(length.toInt())
            buf.get(bytes, 0, length.toInt())
            bytes.decodeToString()
        }
    }

    /** Read element [i] of a vector<int32>. */
    fun int(i: Int): Int = buf.crossSectionOperation {
        buf.position(elementPos(i, stride = 4))
        buf.getIntLE()
    }

    /** Read element [i] of a vector<int64>. */
    fun long(i: Int): Long = buf.crossSectionOperation {
        buf.position(elementPos(i, stride = 8))
        buf.getLongLE()
    }

    /** Read element [i] of a vector<int8/byte>. */
    fun byte(i: Int): Byte = buf[elementPos(i, stride = 1)]

    /**
     * Absolute position of element [i] in a vector<struct> with the given [stride].
     * The caller reads struct fields from the returned absolute position.
     */
    fun structPos(i: Int, stride: Int): Int {
        require(stride > 0) { "structPos stride must be positive" }
        require(i in 0 until length) { "FlatBufferVector structPos index $i out of range [0, ${length})" }
        return pos + 4 + i * stride
    }
}

/**
 * Entry point for FlatBuffers buffer parsing.
 *
 * FlatBuffers root: buffer[base] is a u32 offset to the root table.
 * Optional 4-byte file identifier lives at base+4..base+7 (before the root offset? no — the root
 * offset is at base+0, the file identifier is at base+4, *after* the root offset). The root table
 * target = base + rootOffset.
 */
object FlatBuffers {

    /**
     * Resolve the root table from [buf] starting at [base].
     * The u32 at [base] is the root-table offset (relative to [base]).
     */
    fun root(buf: CommonByteBuffer, base: Int = 0): FlatBufferTable {
        val rootOffset = buf.crossSectionOperation {
            buf.position(base)
            buf.getIntLE().toLong() and 0xFFFFFFFFL
        }
        require(rootOffset in 0..Int.MAX_VALUE) { "FlatBuffers root offset out of range: $rootOffset" }
        val tablePos = base + rootOffset.toInt()
        require(tablePos in 0 until buf.size) { "FlatBuffers root table pos out of bounds: $tablePos" }
        return FlatBufferTable(buf, tablePos)
    }

    /**
     * Read the optional 4-byte file identifier at [base]+4.
     * Returns null if the identifier bytes are all zero (not present).
     * The identifier is 4 ASCII bytes; an all-zero identifier signals absent.
     */
    fun readFileIdentifier(buf: CommonByteBuffer, base: Int = 0): String? {
        require(base + 8 <= buf.size) { "Buffer too small to contain FlatBuffers file identifier" }
        val bytes = ByteArray(4) { buf[base + 4 + it] }
        if (bytes.all { it == 0.toByte() }) return null
        return bytes.decodeToString()
    }
}