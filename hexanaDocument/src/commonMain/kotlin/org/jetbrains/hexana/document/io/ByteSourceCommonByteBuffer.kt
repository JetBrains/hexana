package org.jetbrains.hexana.document.io

import org.jetbrains.hexana.document.BinaryDocument
import org.jetbrains.hexana.document.ByteSource
import org.jetbrains.hexana.io.CommonByteBuffer

private const val READ_ONLY_MSG: String =
    "ByteSource-backed CommonByteBuffer is read-only; mutations belong to BinaryDocument.edit()."

private fun Long.toIntChecked(): Int {
    require(this <= Int.MAX_VALUE) {
        "ByteSource length $this exceeds CommonByteBuffer Int.MAX_VALUE limit ($Int.MAX_VALUE)"
    }
    return toInt()
}

/**
 * Adapter exposing a [ByteSource] as a [CommonByteBuffer] so existing parsers
 * (`wasmParser`, DWARF section readers, etc.) consume a [BinaryDocument]
 * without source changes.
 *
 * [baseOffset] lets [slice] share the same underlying [ByteSource] while
 * presenting a translated coordinate frame; [sliceLimit] caps reads to the
 * slice's view. The cursor [pos] is local to this view (always starts at 0
 * after [slice]).
 *
 * **Read-only**: `put*` methods throw. Edits go through [BinaryDocument.edit].
 */
internal class ByteSourceCommonByteBuffer private constructor(
    private val source: ByteSource,
    private val baseOffset: Long,
    private val sliceLimit: Int,
    private var pos: Int,
) : CommonByteBuffer {

    constructor(source: ByteSource) : this(
        source = source,
        baseOffset = 0L,
        sliceLimit = source.length.toIntChecked(),
        pos = 0,
    )

    override fun get(): Byte {
        val b = source.get(baseOffset + pos.toLong())
        pos++
        return b
    }

    override fun get(dst: ByteArray, offset: Int, size: Int) {
        source.read(baseOffset + pos.toLong(), dst, offset, size)
        pos += size
    }

    override fun get(index: Int): Byte = source.get(baseOffset + index.toLong())

    override fun put(value: Byte): Unit = throw UnsupportedOperationException(READ_ONLY_MSG)
    override fun put(src: ByteArray, offset: Int, size: Int): Unit = throw UnsupportedOperationException(READ_ONLY_MSG)
    override fun putIntLE(value: Int): Unit = throw UnsupportedOperationException(READ_ONLY_MSG)

    override fun getIntLE(): Int {
        val b0 = get().toInt() and 0xFF
        val b1 = get().toInt() and 0xFF
        val b2 = get().toInt() and 0xFF
        val b3 = get().toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    override fun getFloatLE(): Float = Float.fromBits(getIntLE())

    override fun getDoubleLE(): Double {
        val lo = getIntLE().toLong() and 0xFFFFFFFFL
        val hi = getIntLE().toLong() and 0xFFFFFFFFL
        return Double.fromBits((hi shl 32) or lo)
    }

    override fun position(): Int = pos

    override fun position(index: Int): CommonByteBuffer {
        require(index in 0..sliceLimit) { "position $index out of bounds [0, $sliceLimit]" }
        pos = index
        return this
    }

    override fun remaining(): Int = sliceLimit - pos

    override fun hasRemaining(): Boolean = pos < sliceLimit

    override fun limit(): Int = sliceLimit

    override fun slice(offset: Int, length: Int): CommonByteBuffer {
        require(offset >= 0 && length >= 0 && offset + length <= sliceLimit) {
            "slice [$offset, ${offset + length}) out of bounds [0, $sliceLimit]"
        }
        return ByteSourceCommonByteBuffer(
            source = source,
            baseOffset = baseOffset + offset.toLong(),
            sliceLimit = length,
            pos = 0,
        )
    }

    override fun duplicate(): CommonByteBuffer = ByteSourceCommonByteBuffer(
        source = source,
        baseOffset = baseOffset,
        sliceLimit = sliceLimit,
        pos = pos,
    )
}

/** Adapts [this] [ByteSource] for consumption by parsers that expect [CommonByteBuffer]. */
public fun ByteSource.toCommonByteBuffer(): CommonByteBuffer = ByteSourceCommonByteBuffer(this)

/** Convenience: same as `byteSource().toCommonByteBuffer()`. */
public fun BinaryDocument.toCommonByteBuffer(): CommonByteBuffer = byteSource().toCommonByteBuffer()
