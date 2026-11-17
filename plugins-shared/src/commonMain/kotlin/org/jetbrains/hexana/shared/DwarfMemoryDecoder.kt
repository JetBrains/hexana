package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dwarf.DwarfTypeInfo

/**
 * Decodes a raw byte array (wasm little-endian linear-memory slab) into a
 * human-readable [RenderedValue] according to a [DwarfTypeInfo].
 *
 * All multi-byte integers are decoded as little-endian, matching the wasm spec.
 * This object has no I/O dependencies and is fully testable as a pure function.
 */
object DwarfMemoryDecoder {

    /** Maximum array elements rendered without truncation. */
    const val MAX_ARRAY_CHILDREN: Int = 100

    /**
     * A decoded variable value, with optional expandable children (for struct/array/pointer).
     *
     * @property summary one-line display string (shown in the variable tree leaf)
     * @property children decoded children if this is a composite type (struct members,
     *           array elements); empty for scalars
     * @property truncated true when the array had more elements than [MAX_ARRAY_CHILDREN]
     */
    data class RenderedValue(
        val summary: String,
        val children: List<NamedValue> = emptyList(),
        val truncated: Boolean = false,
    )

    /** A named child value (struct member or array element). */
    data class NamedValue(val name: String, val value: RenderedValue)

    // DWARF ATE encoding constants
    private const val ATE_BOOLEAN: Int = 0x02
    private const val ATE_FLOAT: Int = 0x04
    private const val ATE_SIGNED: Int = 0x05
    private const val ATE_SIGNED_CHAR: Int = 0x06
    private const val ATE_UNSIGNED: Int = 0x07
    private const val ATE_UNSIGNED_CHAR: Int = 0x08
    private const val ATE_UTF: Int = 0x10

    /**
     * Decodes [bytes] starting at [offset] using the given [type].
     * Returns `null` when the slab is too short or the type is unknown.
     *
     * @param bytes the raw memory slab (little-endian wasm linear memory)
     * @param offset byte offset within [bytes] where this value starts
     * @param type resolved DWARF type for the value
     */
    fun decode(bytes: ByteArray, offset: Int, type: DwarfTypeInfo): RenderedValue? {
        return when (type) {
            is DwarfTypeInfo.Base -> decodeBase(bytes, offset, type)
            is DwarfTypeInfo.Pointer -> decodePointer(bytes, offset, type)
            is DwarfTypeInfo.Struct -> decodeStruct(bytes, offset, type)
            is DwarfTypeInfo.Array -> decodeArray(bytes, offset, type)
            is DwarfTypeInfo.Enumeration -> decodeEnum(bytes, offset, type)
            is DwarfTypeInfo.Unknown -> RenderedValue("? (unknown type tag=${type.tag})")
        }
    }

    private fun decodeBase(bytes: ByteArray, offset: Int, type: DwarfTypeInfo.Base): RenderedValue? {
        val size = type.byteSize
        if (size <= 0 || offset + size > bytes.size) return null
        return when (type.encoding) {
            ATE_BOOLEAN -> {
                val v = bytes[offset].toInt() and 0xff
                RenderedValue(if (v != 0) "true" else "false")
            }
            ATE_FLOAT -> when (size) {
                4 -> {
                    val bits = readInt32LE(bytes, offset)
                    RenderedValue(Float.fromBits(bits).toString())
                }
                8 -> {
                    val bits = readInt64LE(bytes, offset)
                    RenderedValue(Double.fromBits(bits).toString())
                }
                else -> RenderedValue("0x${readHex(bytes, offset, size)}")
            }
            ATE_SIGNED, ATE_SIGNED_CHAR -> when (size) {
                1 -> RenderedValue(bytes[offset].toString())
                2 -> RenderedValue(readInt16LE(bytes, offset).toShort().toString())
                4 -> RenderedValue(readInt32LE(bytes, offset).toString())
                8 -> RenderedValue(readInt64LE(bytes, offset).toString())
                else -> RenderedValue("0x${readHex(bytes, offset, size)}")
            }
            ATE_UNSIGNED, ATE_UNSIGNED_CHAR, ATE_UTF -> when (size) {
                1 -> RenderedValue((bytes[offset].toInt() and 0xff).toString())
                2 -> RenderedValue((readInt16LE(bytes, offset) and 0xffff).toString())
                4 -> RenderedValue((readInt32LE(bytes, offset).toLong() and 0xffff_ffffL).toString())
                8 -> {
                    val v = readInt64LE(bytes, offset)
                    // Unsigned 64-bit: show as positive if possible, else as hex
                    RenderedValue(if (v >= 0) v.toString() else "0x${v.toString(16).padStart(16, '0')}")
                }
                else -> RenderedValue("0x${readHex(bytes, offset, size)}")
            }
            else -> RenderedValue("0x${readHex(bytes, offset, size)}")
        }
    }

    private fun decodePointer(bytes: ByteArray, offset: Int, type: DwarfTypeInfo.Pointer): RenderedValue? {
        // Wasm32: 4-byte pointer
        if (offset + 4 > bytes.size) return null
        val addr = readInt32LE(bytes, offset).toLong() and 0xffff_ffffL
        return RenderedValue("0x${addr.toString(16)}")
        // Pointee is resolved lazily (one level) in the platform layer on expansion.
        // We don't recurse here to stay bounded and avoid deep chains.
    }

    private fun decodeStruct(bytes: ByteArray, offset: Int, type: DwarfTypeInfo.Struct): RenderedValue? {
        val totalSize = type.byteSize ?: return RenderedValue("{...}")
        if (totalSize <= 0) return RenderedValue("{}")
        if (offset + totalSize > bytes.size) return null

        val children = mutableListOf<NamedValue>()
        for (member in type.members) {
            val memberOffset = offset + member.offset
            val memberType = member.type ?: continue
            val memberValue = decode(bytes, memberOffset, memberType) ?: continue
            val memberName = member.name ?: "<anon>"
            children.add(NamedValue(memberName, memberValue))
        }

        val typeName = type.name ?: "struct"
        return RenderedValue("$typeName {...}", children)
    }

    private fun decodeArray(bytes: ByteArray, offset: Int, type: DwarfTypeInfo.Array): RenderedValue? {
        val elemType = type.elementType ?: return RenderedValue("[...]")
        val count = type.count ?: return RenderedValue("[...]")
        if (count <= 0) return RenderedValue("[]")

        val elemSize = typeByteSize(elemType) ?: return RenderedValue("[...]")
        if (elemSize <= 0) return RenderedValue("[...]")

        val renderCount = minOf(count, MAX_ARRAY_CHILDREN)
        val truncated = count > MAX_ARRAY_CHILDREN

        if (offset + elemSize * renderCount > bytes.size) return null

        val children = mutableListOf<NamedValue>()
        for (i in 0 until renderCount) {
            val elemOffset = offset + i * elemSize
            val elemValue = decode(bytes, elemOffset, elemType) ?: break
            children.add(NamedValue("[$i]", elemValue))
        }

        val summary = if (truncated) "[${count} elements, first $renderCount shown]" else "[${count} elements]"
        return RenderedValue(summary, children, truncated)
    }

    private fun decodeEnum(bytes: ByteArray, offset: Int, type: DwarfTypeInfo.Enumeration): RenderedValue? {
        val size = type.byteSize ?: return null
        if (size <= 0 || offset + size > bytes.size) return null
        val raw = when (size) {
            1 -> (bytes[offset].toInt() and 0xff).toLong()
            2 -> (readInt16LE(bytes, offset) and 0xffff).toLong()
            4 -> readInt32LE(bytes, offset).toLong() and 0xffff_ffffL
            8 -> readInt64LE(bytes, offset)
            else -> return RenderedValue("0x${readHex(bytes, offset, size)}")
        }
        val enumerator = type.enumerators.find { it.value == raw }
        return if (enumerator != null) {
            RenderedValue("${enumerator.name} ($raw)")
        } else {
            RenderedValue("$raw (unknown enumerator)")
        }
    }

    /** Returns the total byte size of a type, or null for dynamic/unknown sizes. */
    fun typeByteSize(type: DwarfTypeInfo): Int? = when (type) {
        is DwarfTypeInfo.Base -> type.byteSize.takeIf { it > 0 }
        is DwarfTypeInfo.Pointer -> 4  // wasm32
        is DwarfTypeInfo.Struct -> type.byteSize?.takeIf { it > 0 }
        is DwarfTypeInfo.Array -> {
            val elemSize = type.elementType?.let { typeByteSize(it) } ?: return null
            val count = type.count ?: return null
            elemSize * count
        }
        is DwarfTypeInfo.Enumeration -> type.byteSize?.takeIf { it > 0 }
        is DwarfTypeInfo.Unknown -> null
    }

    // ---- little-endian readers ----

    private fun readInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun readInt64LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24) or
            ((bytes[offset + 4].toLong() and 0xff) shl 32) or
            ((bytes[offset + 5].toLong() and 0xff) shl 40) or
            ((bytes[offset + 6].toLong() and 0xff) shl 48) or
            ((bytes[offset + 7].toLong() and 0xff) shl 56)

    private fun readHex(bytes: ByteArray, offset: Int, size: Int): String {
        val sb = StringBuilder(size * 2)
        // little-endian: show highest byte first for natural reading
        for (i in size - 1 downTo 0) {
            sb.append((bytes[offset + i].toInt() and 0xff).toString(16).padStart(2, '0'))
        }
        return sb.toString()
    }
}
