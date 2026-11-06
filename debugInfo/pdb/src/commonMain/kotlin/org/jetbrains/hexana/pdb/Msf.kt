package org.jetbrains.hexana.pdb

import org.jetbrains.hexana.io.LargeBytes
import org.jetbrains.hexana.parsing.common.NativeByteRange

/**
 * The MSF 7.00 superblock at the start of a Windows PDB: the file is an array of fixed-size
 * blocks, and every logical stream is a list of (not necessarily contiguous) block numbers.
 */
data class MsfSuperBlock(
    val blockSize: Int,
    val freeBlockMapBlock: UInt,
    val blockCount: UInt,
    val directoryByteCount: UInt,
    val blockMapAddress: UInt,
    val range: NativeByteRange,
)

/**
 * One logical MSF stream. Reads are bounds-checked against [size] and return null past it;
 * a stream whose block list points outside the file reads as null from that block on.
 *
 * Streams are Int-sized (the directory stores u32 sizes), but their blocks can lie anywhere in a
 * PDB past 2 GiB — large-page PDBs reach tens of gigabytes — so file offsets are [Long].
 */
class MsfStream internal constructor(
    val index: Int,
    /** Stream size in bytes; a nil stream (size `0xFFFFFFFF` in the directory) is 0. */
    val size: Int,
    val isNil: Boolean,
    /** Block numbers in stream order. */
    val blocks: IntArray,
    private val blockSize: Int,
    private val bytes: LargeBytes,
) {
    /** True when every block of the stream lies inside the file, so in-range reads never fail. */
    fun isFullyReadable(): Boolean {
        for (index in blocks.indices) {
            val length = if (index == blocks.lastIndex) size - index * blockSize else blockSize
            if (blocks[index].toUInt().toLong() * blockSize + length > bytes.size) return false
        }
        return true
    }

    /** File offset of stream byte [position], or null past the stream or the file. */
    fun fileOffset(position: Int): Long? {
        if (position !in 0 until size) return null
        val block = blocks.getOrNull(position / blockSize) ?: return null
        val offset = block.toUInt().toLong() * blockSize + position % blockSize
        return if (offset in 0 until bytes.size) offset else null
    }

    fun u8(position: Int): Int? = fileOffset(position)?.let { bytes[it].toInt() and 0xFF }

    fun u16(position: Int): Int? {
        val b0 = u8(position) ?: return null
        val b1 = u8(position + 1) ?: return null
        return b0 or (b1 shl 8)
    }

    fun u32(position: Int): UInt? {
        val start = fileOffset(position) ?: return null
        // Fast path: all four bytes inside one block.
        if (position % blockSize <= blockSize - 4 && position + 4 <= size && start + 4 <= bytes.size) {
            return (bytes[start].toUInt() and 0xFFu) or
                ((bytes[start + 1].toUInt() and 0xFFu) shl 8) or
                ((bytes[start + 2].toUInt() and 0xFFu) shl 16) or
                ((bytes[start + 3].toUInt() and 0xFFu) shl 24)
        }
        var value = 0u
        for (index in 0 until 4) {
            value = value or ((u8(position + index) ?: return null).toUInt() shl (8 * index))
        }
        return value
    }

    fun i32(position: Int): Int? = u32(position)?.toInt()

    /** Reads [length] bytes, or null when any of them is outside the stream. */
    fun bytes(position: Int, length: Int): ByteArray? {
        if (length < 0 || position < 0 || length > size - position) return null
        val out = ByteArray(length)
        for (index in 0 until length) out[index] = (u8(position + index) ?: return null).toByte()
        return out
    }

    /**
     * Reads a NUL-terminated UTF-8 string of at most [maxLength] bytes. Returns the string and the
     * number of bytes consumed including the terminator, or null when no terminator is found.
     */
    fun cString(position: Int, maxLength: Int = MAX_STRING_LENGTH): Pair<String, Int>? {
        val limit = minOf(size - position, maxLength + 1)
        if (position < 0 || limit <= 0) return null
        var length = 0
        while (true) {
            if (length >= limit) return null
            val byte = u8(position + length) ?: return null
            if (byte == 0) break
            length++
        }
        val text = bytes(position, length)?.decodeToString() ?: return null
        return text to length + 1
    }

    /** GUID in the registry spelling used by PE CodeView records, e.g. `AEBD2BE3-8B00-6D1E-4C4C-44205044422E`. */
    fun guid(position: Int): String? = bytes(position, 16)?.let(::formatGuid)

    /**
     * File range of the stream bytes `[position, position + length)` as long as they are contiguous
     * in the file, else the contiguous prefix — enough to highlight a record in the hex view.
     */
    fun fileRange(position: Int, length: Int): NativeByteRange? {
        val start = fileOffset(position) ?: return null
        val wanted = length.coerceIn(0, size - position)
        var contiguous = minOf(wanted, blockSize - position % blockSize)
        while (contiguous < wanted) {
            val next = fileOffset(position + contiguous) ?: break
            if (next != start + contiguous) break
            contiguous += minOf(blockSize, wanted - contiguous)
        }
        return NativeByteRange(start, contiguous.toLong().coerceAtMost(bytes.size - start))
    }

    /** File ranges of every block of the stream, adjacent blocks merged. */
    fun blockRanges(): List<NativeByteRange> = buildList {
        var runStart = -1L
        var runEnd = -1L
        var remaining = this@MsfStream.size.toLong()
        for (block in blocks) {
            val start = block.toUInt().toLong() * blockSize
            val length = minOf(blockSize.toLong(), remaining)
            remaining -= length
            if (start + length > bytes.size) continue
            if (start == runEnd) {
                runEnd += length
            } else {
                if (runStart >= 0) add(NativeByteRange(runStart, runEnd - runStart))
                runStart = start
                runEnd = start + length
            }
        }
        if (runStart >= 0) add(NativeByteRange(runStart, runEnd - runStart))
    }
}

internal fun formatGuid(raw: ByteArray): String {
    fun hex(index: Int) = (raw[index].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
    return buildString {
        for (index in 3 downTo 0) append(hex(index))
        append('-')
        append(hex(5)); append(hex(4))
        append('-')
        append(hex(7)); append(hex(6))
        append('-')
        for (index in 8..9) append(hex(index))
        append('-')
        for (index in 10..15) append(hex(index))
    }
}

internal const val MAX_STRING_LENGTH = 4096
