package org.jetbrains.hexana.tar

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.LargeBytes
import org.jetbrains.hexana.io.asLargeBytes

/**
 * Parses TAR archives. Returns the entry list in archive order.
 *
 * Supports classic V7, POSIX ustar, and GNU tar extensions for long
 * file names (typeflag 'L') and long link names (typeflag 'K').
 *
 * The parser reads [LargeBytes] with 64-bit offsets, so an archive past 2 GiB is listed whole; it
 * should contain the uncompressed tar stream (for `.tar.gz` files, decompress with gzip first).
 */
object TarParser {
    private const val BLOCK_SIZE = 512
    private const val NAME_OFFSET = 0
    private const val NAME_LENGTH = 100
    private const val MODE_OFFSET = 100
    private const val MODE_LENGTH = 8
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val MTIME_OFFSET = 136
    private const val MTIME_LENGTH = 12
    private const val CHECKSUM_OFFSET = 148
    private const val CHECKSUM_LENGTH = 8
    private const val TYPEFLAG_OFFSET = 156
    private const val LINKNAME_OFFSET = 157
    private const val LINKNAME_LENGTH = 100
    private const val MAGIC_OFFSET = 257
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155

    /** Longest GNU long-name record read; real ones are a few hundred bytes. */
    private const val MAX_LONG_NAME = 1L shl 20

    fun parse(buffer: CommonByteBuffer): List<TarEntry> = parse(buffer.asLargeBytes())

    fun parse(buffer: LargeBytes): List<TarEntry> {
        val totalSize = buffer.size
        if (totalSize < BLOCK_SIZE) return emptyList()

        val entries = ArrayList<TarEntry>()
        var pos = 0L
        var pendingLongName: String? = null
        var pendingLongLink: String? = null

        while (pos + BLOCK_SIZE <= totalSize) {
            // Check for end-of-archive (two consecutive zero blocks).
            if (isZeroBlock(buffer, pos)) break

            // Validate checksum.
            if (!verifyChecksum(buffer, pos)) break

            val typeFlag = (buffer[pos + TYPEFLAG_OFFSET].toInt() and 0xFF).toChar()
            val rawSize = readOctal(buffer, pos + SIZE_OFFSET, SIZE_LENGTH)
            val dataOffset = pos + BLOCK_SIZE
            val dataBlocks = (rawSize + BLOCK_SIZE - 1) / BLOCK_SIZE

            when (typeFlag) {
                'L' -> {
                    // GNU long name: data contains the real file name.
                    if (rawSize !in 0..MAX_LONG_NAME || dataOffset + rawSize > totalSize) break
                    pendingLongName = readString(buffer, dataOffset, rawSize.toInt()).trimEnd('\u0000')
                    pos = dataOffset + dataBlocks * BLOCK_SIZE
                    continue
                }
                'K' -> {
                    // GNU long link name: data contains the real link target.
                    if (rawSize !in 0..MAX_LONG_NAME || dataOffset + rawSize > totalSize) break
                    pendingLongLink = readString(buffer, dataOffset, rawSize.toInt()).trimEnd('\u0000')
                    pos = dataOffset + dataBlocks * BLOCK_SIZE
                    continue
                }
            }

            val name = pendingLongName ?: readEntryName(buffer, pos)
            val linkName = pendingLongLink ?: readNullTerminated(buffer, pos + LINKNAME_OFFSET, LINKNAME_LENGTH)
            pendingLongName = null
            pendingLongLink = null

            val mode = readOctal(buffer, pos + MODE_OFFSET, MODE_LENGTH).toInt()
            val mtime = readOctal(buffer, pos + MTIME_OFFSET, MTIME_LENGTH)

            entries += TarEntry(
                name = name,
                size = rawSize,
                dataOffset = dataOffset,
                mode = mode,
                modTime = mtime,
                typeFlag = typeFlag,
                linkName = linkName,
            )

            pos = dataOffset + dataBlocks * BLOCK_SIZE
        }
        return entries
    }

    /**
     * Quick magic check: returns true if the buffer looks like a TAR archive.
     * Checks for "ustar" magic at offset 257 in the first header block.
     * Also accepts V7 tar files by verifying the checksum of the first block.
     */
    fun isTar(buffer: CommonByteBuffer): Boolean = isTar(buffer.asLargeBytes())

    fun isTar(buffer: LargeBytes): Boolean {
        if (buffer.size < BLOCK_SIZE) return false
        // Check ustar magic.
        if (hasUstarMagic(buffer, 0)) return true
        // Fallback: verify checksum of the first header block (V7 tar).
        return verifyChecksum(buffer, 0)
    }

    private fun hasUstarMagic(buffer: LargeBytes, blockOffset: Long): Boolean {
        val m0 = buffer[blockOffset + MAGIC_OFFSET].toInt() and 0xFF
        val m1 = buffer[blockOffset + MAGIC_OFFSET + 1].toInt() and 0xFF
        val m2 = buffer[blockOffset + MAGIC_OFFSET + 2].toInt() and 0xFF
        val m3 = buffer[blockOffset + MAGIC_OFFSET + 3].toInt() and 0xFF
        val m4 = buffer[blockOffset + MAGIC_OFFSET + 4].toInt() and 0xFF
        return m0 == 'u'.code && m1 == 's'.code && m2 == 't'.code && m3 == 'a'.code && m4 == 'r'.code
    }

    private fun isZeroBlock(buffer: LargeBytes, offset: Long): Boolean {
        val end = (offset + BLOCK_SIZE).coerceAtMost(buffer.size)
        for (i in offset until end) {
            if (buffer[i] != 0.toByte()) return false
        }
        return true
    }

    private fun verifyChecksum(buffer: LargeBytes, blockOffset: Long): Boolean {
        val stored = readOctal(buffer, blockOffset + CHECKSUM_OFFSET, CHECKSUM_LENGTH)
        var unsigned = 0L
        for (i in 0 until BLOCK_SIZE) {
            val b = if (i in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH) {
                ' '.code.toLong()
            } else {
                buffer[blockOffset + i].toLong() and 0xFFL
            }
            unsigned += b
        }
        // Some implementations use signed byte arithmetic.
        var signed = 0L
        for (i in 0 until BLOCK_SIZE) {
            val b = if (i in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH) {
                ' '.code.toLong()
            } else {
                buffer[blockOffset + i].toLong() // signed
            }
            signed += b
        }
        return stored == unsigned || stored == signed
    }

    /**
     * Reads the entry name, combining the ustar prefix field with the name field
     * when the prefix is non-empty.
     */
    private fun readEntryName(buffer: LargeBytes, blockOffset: Long): String {
        val name = readNullTerminated(buffer, blockOffset + NAME_OFFSET, NAME_LENGTH)
        if (!hasUstarMagic(buffer, blockOffset)) return name
        val prefix = readNullTerminated(buffer, blockOffset + PREFIX_OFFSET, PREFIX_LENGTH)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun readNullTerminated(buffer: LargeBytes, offset: Long, maxLen: Int): String {
        val bytes = ByteArray(maxLen)
        var len = 0
        for (i in 0 until maxLen) {
            val b = buffer[offset + i]
            if (b == 0.toByte()) break
            bytes[len++] = b
        }
        return bytes.decodeToString(0, len)
    }

    private fun readString(buffer: LargeBytes, offset: Long, length: Int): String {
        val bytes = ByteArray(length)
        for (i in 0 until length) {
            bytes[i] = buffer[offset + i]
        }
        return bytes.decodeToString()
    }

    /**
     * Reads an octal ASCII number from the buffer. Handles both null-terminated
     * and space-terminated fields. Also handles GNU binary size encoding
     * (high bit set in the first byte).
     */
    private fun readOctal(buffer: LargeBytes, offset: Long, length: Int): Long {
        val firstByte = buffer[offset].toInt() and 0xFF
        // GNU binary encoding: high bit set.
        if (firstByte and 0x80 != 0) {
            var value = 0L
            for (i in 1 until length) {
                value = (value shl 8) or (buffer[offset + i].toLong() and 0xFFL)
            }
            return value
        }
        var result = 0L
        for (i in 0 until length) {
            val b = buffer[offset + i].toInt() and 0xFF
            if (b == 0 || b == ' '.code) {
                if (result > 0 || i > 0) break // stop at first null/space after digits
                continue // skip leading spaces
            }
            if (b < '0'.code || b > '7'.code) break
            result = result * 8 + (b - '0'.code)
        }
        return result
    }
}
