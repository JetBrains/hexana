package org.jetbrains.hexana.zip

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Parses ZIP central directories. Returns the entry list in directory order.
 *
 * MVP scope:
 *  - Classic ZIP only. Zip64 extensions are *not* parsed; if the End of
 *    Central Directory Record reports any field as `0xFFFFFFFF` (the
 *    classic-ZIP overflow marker) [parse] throws.
 *  - Per-entry name length, extra field length, and offsets are read from
 *    both the central directory and the local file header (the local header
 *    is needed to compute [ZipEntry.dataOffset] because the central
 *    directory only stores the local-header offset, not the variable-length
 *    name+extra widths in front of the data).
 */
object ZipParser {
    /**
     * End of Central Directory Record signature: `PK\x05\x06`. Little-endian.
     */
    const val EOCD_SIGNATURE: Int = 0x06054B50

    /** Central directory file header signature: `PK\x01\x02`. */
    const val CENTRAL_DIR_SIGNATURE: Int = 0x02014B50

    /** Local file header signature: `PK\x03\x04`. */
    const val LOCAL_HEADER_SIGNATURE: Int = 0x04034B50

    private const val EOCD_FIXED_SIZE = 22 // bytes without the variable-length comment
    private const val EOCD_MAX_SEARCH_WINDOW = 22 + 65_535 // EOCD + max comment length

    fun parse(buffer: CommonByteBuffer): List<ZipEntry> {
        val totalSize = buffer.size
        if (totalSize < EOCD_FIXED_SIZE) {
            error("ZIP too small ($totalSize bytes) to contain an End of Central Directory Record")
        }

        val eocdOffset = findEocdOffset(buffer)
            ?: error("End of Central Directory Record not found")

        // Read EOCD fixed fields.
        val cdEntryCount = buffer.readU2LE(eocdOffset + 10) // total entries in CD (this disk)
        val cdSize = buffer.readU4LE(eocdOffset + 12)
        val cdOffset = buffer.readU4LE(eocdOffset + 16)

        if (cdEntryCount == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            error("ZIP64 overflow markers detected — Zip64 parsing is not yet supported")
        }

        // Walk central directory.
        val entries = ArrayList<ZipEntry>(cdEntryCount)
        var p = cdOffset.toInt()
        repeat(cdEntryCount) {
            val signature = buffer.readU4LE(p)
            require(signature.toInt() == CENTRAL_DIR_SIGNATURE) {
                "central directory entry at $p has bad signature 0x${signature.toString(16)}"
            }
            val flags = buffer.readU2LE(p + 8)
            val method = buffer.readU2LE(p + 10)
            val crc = buffer.readU4LE(p + 16).toInt()
            val compressedSize = buffer.readU4LE(p + 20)
            val uncompressedSize = buffer.readU4LE(p + 24)
            val nameLen = buffer.readU2LE(p + 28)
            val extraLen = buffer.readU2LE(p + 30)
            val commentLen = buffer.readU2LE(p + 32)
            val localHeaderOffset = buffer.readU4LE(p + 42)

            if (compressedSize == 0xFFFFFFFFL ||
                uncompressedSize == 0xFFFFFFFFL ||
                localHeaderOffset == 0xFFFFFFFFL
            ) {
                error("ZIP64 size/offset markers in central directory entry $p; not yet supported")
            }

            val name = buffer.readUtf8(p + 46, nameLen)
            val cdEntrySize = 46 + nameLen + extraLen + commentLen
            val dataOffset = computeLocalDataOffset(buffer, localHeaderOffset)

            entries += ZipEntry(
                name = name,
                compressionMethod = method,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                crc32 = crc,
                flags = flags,
                localHeaderOffset = localHeaderOffset,
                dataOffset = dataOffset,
            )

            p += cdEntrySize
        }
        return entries
    }

    private fun findEocdOffset(buffer: CommonByteBuffer): Int? {
        val size = buffer.size
        val searchFrom = (size - EOCD_MAX_SEARCH_WINDOW).coerceAtLeast(0)
        // Walk backwards so the first hit is the EOCD record proper (not a
        // false match inside an archive comment).
        var p = size - EOCD_FIXED_SIZE
        while (p >= searchFrom) {
            if (buffer.readU4LE(p).toInt() == EOCD_SIGNATURE) {
                val commentLen = buffer.readU2LE(p + 20)
                if (p + EOCD_FIXED_SIZE + commentLen == size) {
                    return p
                }
            }
            p--
        }
        return null
    }

    private fun computeLocalDataOffset(buffer: CommonByteBuffer, localHeaderOffset: Long): Long {
        val p = localHeaderOffset.toInt()
        val signature = buffer.readU4LE(p)
        require(signature.toInt() == LOCAL_HEADER_SIGNATURE) {
            "local file header at $p has bad signature 0x${signature.toString(16)}"
        }
        val nameLen = buffer.readU2LE(p + 26)
        val extraLen = buffer.readU2LE(p + 28)
        return localHeaderOffset + 30 + nameLen + extraLen
    }
}

// -----------------------------------------------------------------------------
// Little-endian readers and bounded UTF-8 string reads on CommonByteBuffer
// -----------------------------------------------------------------------------

private fun CommonByteBuffer.readU2LE(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun CommonByteBuffer.readU4LE(at: Int): Long {
    val b0 = this[at].toLong() and 0xFFL
    val b1 = this[at + 1].toLong() and 0xFFL
    val b2 = this[at + 2].toLong() and 0xFFL
    val b3 = this[at + 3].toLong() and 0xFFL
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun CommonByteBuffer.readUtf8(at: Int, length: Int): String {
    if (length <= 0) return ""
    val bytes = ByteArray(length)
    for (i in 0 until length) bytes[i] = this[at + i]
    return bytes.decodeToString()
}
