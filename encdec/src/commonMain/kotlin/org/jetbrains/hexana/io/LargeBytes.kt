package org.jetbrains.hexana.io

/**
 * Random-access bytes addressed with [Long] offsets: the whole of a file of any size.
 *
 * [CommonByteBuffer] is `Int`-addressed, which caps it at 2 GiB, and most parsers are happy with
 * that. Code that must see past that limit — the hex viewer, and the parsers of formats whose
 * structures can sit anywhere in a large file (ELF, ZIP64, tar, DWARF, …) — reads through this
 * instead, and hands the `Int`-sized decoders a [window] onto just the region they decode.
 *
 * Implementations are immutable views and safe to share between threads.
 */
interface LargeBytes {
    /** Number of bytes. */
    val size: Long

    /** The byte at [offset]; throws [IndexOutOfBoundsException] outside `0 until size`. */
    operator fun get(offset: Long): Byte

    /**
     * Copies `length` bytes starting at [offset] into [dst] at [dstOffset]. Throws
     * [IndexOutOfBoundsException] when the range is not inside these bytes or does not fit [dst].
     */
    fun read(offset: Long, dst: ByteArray, dstOffset: Int = 0, length: Int = dst.size - dstOffset)

    /**
     * A [CommonByteBuffer] over `[offset, offset + length)`, whose index 0 is [offset]. Shares the
     * bytes where it can (no copy). For decoders that work on one region — a section, a footer, a
     * header table — with `Int` offsets relative to its start.
     */
    fun window(offset: Long, length: Int): CommonByteBuffer

    /**
     * These bytes as one [CommonByteBuffer] in the same coordinates, or `null` when they are too
     * large for one. Lets callers keep the fast path for files that fit.
     */
    fun asCommonByteBufferOrNull(): CommonByteBuffer?
}

/** Bytes as a [ByteArray] copy of `[offset, offset + length)`. */
fun LargeBytes.readBytes(offset: Long, length: Int): ByteArray = ByteArray(length).also { read(offset, it, 0, length) }

/** Whether `[offset, offset + length)` lies inside these bytes, overflow-safe. */
fun LargeBytes.containsRange(offset: Long, length: Long): Boolean =
    offset >= 0 && length >= 0 && offset <= size - length

/** Little-endian unsigned 16/32-bit and 64-bit reads at an absolute offset, `null` when out of range. */
fun LargeBytes.u16LeOrNull(offset: Long): Int? =
    if (containsRange(offset, 2)) (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) else null

fun LargeBytes.u32LeOrNull(offset: Long): Long? {
    if (!containsRange(offset, 4)) return null
    var value = 0L
    for (i in 0 until 4) value = value or ((this[offset + i].toLong() and 0xFF) shl (8 * i))
    return value
}

fun LargeBytes.u64LeOrNull(offset: Long): Long? {
    if (!containsRange(offset, 8)) return null
    var value = 0L
    for (i in 0 until 8) value = value or ((this[offset + i].toLong() and 0xFF) shl (8 * i))
    return value
}

/** An in-memory or already-mapped [CommonByteBuffer] as [LargeBytes]; no copy. */
fun CommonByteBuffer.asLargeBytes(): LargeBytes = BufferLargeBytes(duplicate().position(0))

private class BufferLargeBytes(private val buffer: CommonByteBuffer) : LargeBytes {
    override val size: Long get() = buffer.size.toLong()

    override fun get(offset: Long): Byte {
        if (offset < 0 || offset >= size) throw IndexOutOfBoundsException("offset $offset outside 0 until $size")
        return buffer[offset.toInt()]
    }

    override fun read(offset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
        checkRead(offset, length, size, dst, dstOffset)
        buffer.duplicate().position(offset.toInt()).get(dst, dstOffset, length)
    }

    override fun window(offset: Long, length: Int): CommonByteBuffer {
        checkWindow(offset, length, size)
        return buffer.slice(offset.toInt(), length)
    }

    override fun asCommonByteBufferOrNull(): CommonByteBuffer = buffer.duplicate().position(0)
}

internal fun checkRead(offset: Long, length: Int, size: Long, dst: ByteArray, dstOffset: Int) {
    if (length < 0 || dstOffset < 0 || dstOffset > dst.size - length) {
        throw IndexOutOfBoundsException("$length bytes do not fit at $dstOffset of a ${dst.size}-byte array")
    }
    checkWindow(offset, length, size)
}

internal fun checkWindow(offset: Long, length: Int, size: Long) {
    if (length < 0 || offset < 0 || offset > size - length) {
        throw IndexOutOfBoundsException("range $offset+$length outside 0 until $size")
    }
}
