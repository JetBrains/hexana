package org.jetbrains.hexana.zip

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.LargeBytes
import org.jetbrains.hexana.io.asLargeBytes
import org.jetbrains.hexana.io.readBytes

/**
 * Parses ZIP central directories. Returns the entry list in directory order.
 *
 * Supports both classic ZIP and Zip64 extensions:
 *  - When the End of Central Directory Record contains overflow markers
 *    (`0xFFFF` / `0xFFFFFFFF`), the parser locates the Zip64 End of Central
 *    Directory Locator and Record to obtain the true entry count, central
 *    directory size, and offset.
 *  - Per-entry Zip64 Extended Information Extra Fields (header id `0x0001`)
 *    are parsed to recover oversized compressed/uncompressed sizes and
 *    local-header offsets.
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

    /** Zip64 End of Central Directory Record signature: `PK\x06\x06`. */
    private const val ZIP64_EOCD_SIGNATURE: Int = 0x06064B50

    /** Zip64 End of Central Directory Locator signature: `PK\x06\x07`. */
    private const val ZIP64_EOCD_LOCATOR_SIGNATURE: Int = 0x07064B50

    /** Zip64 Extended Information Extra Field header id. */
    private const val ZIP64_EXTRA_FIELD_ID: Int = 0x0001

    private const val EOCD_FIXED_SIZE = 22 // bytes without the variable-length comment
    private const val EOCD_MAX_SEARCH_WINDOW = 22 + 65_535 // EOCD + max comment length
    private const val ZIP64_EOCD_LOCATOR_SIZE = 20
    private const val ZIP64_EOCD_FIXED_SIZE = 56 // minimum fixed part

    fun parse(buffer: CommonByteBuffer): List<ZipEntry> = parse(buffer.asLargeBytes())

    /**
     * Parses the central directory of [buffer], which may be larger than 2 GiB: the directory of
     * a ZIP64 archive sits at its end, and entry offsets are 64-bit.
     */
    fun parse(buffer: LargeBytes): List<ZipEntry> {
        val totalSize = buffer.size
        if (totalSize < EOCD_FIXED_SIZE) {
            error("ZIP too small ($totalSize bytes) to contain an End of Central Directory Record")
        }

        val eocdOffset = findEocdOffset(buffer)
            ?: error("End of Central Directory Record not found")

        // Read EOCD fixed fields.
        var cdEntryCount = buffer.readU2LE(eocdOffset + 10).toLong()
        var cdSize = buffer.readU4LE(eocdOffset + 12)
        var cdOffset = buffer.readU4LE(eocdOffset + 16)

        // If any EOCD field is at its overflow sentinel, look for Zip64 records.
        if (cdEntryCount == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            val zip64Eocd = findZip64Eocd(buffer, eocdOffset)
                ?: error("Zip64 overflow markers in EOCD but Zip64 End of Central Directory Record not found")
            cdEntryCount = zip64Eocd.entryCount
            cdSize = zip64Eocd.cdSize
            cdOffset = zip64Eocd.cdOffset
        }

        // Walk central directory.
        require(cdEntryCount <= Int.MAX_VALUE) {
            "ZIP entry count $cdEntryCount exceeds supported maximum of ${Int.MAX_VALUE}"
        }
        val entryCount = cdEntryCount.toInt()
        val entries = ArrayList<ZipEntry>(entryCount.coerceAtMost(65536))
        var p = cdOffset
        repeat(entryCount) {
            val signature = buffer.readU4LE(p)
            require(signature.toInt() == CENTRAL_DIR_SIGNATURE) {
                "central directory entry at $p has bad signature 0x${signature.toString(16)}"
            }
            val flags = buffer.readU2LE(p + 8)
            val method = buffer.readU2LE(p + 10)
            val crc = buffer.readU4LE(p + 16).toInt()
            var compressedSize = buffer.readU4LE(p + 20)
            var uncompressedSize = buffer.readU4LE(p + 24)
            val nameLen = buffer.readU2LE(p + 28)
            val extraLen = buffer.readU2LE(p + 30)
            val commentLen = buffer.readU2LE(p + 32)
            var localHeaderOffset = buffer.readU4LE(p + 42)

            // Parse Zip64 Extended Information Extra Field when overflow markers are present.
            if (uncompressedSize == 0xFFFFFFFFL ||
                compressedSize == 0xFFFFFFFFL ||
                localHeaderOffset == 0xFFFFFFFFL
            ) {
                val zip64Extra = parseZip64ExtraField(
                    buffer, p + 46 + nameLen, extraLen,
                    uncompressedSize, compressedSize, localHeaderOffset,
                )
                if (zip64Extra != null) {
                    uncompressedSize = zip64Extra.uncompressedSize
                    compressedSize = zip64Extra.compressedSize
                    localHeaderOffset = zip64Extra.localHeaderOffset
                } else {
                    error("Zip64 overflow markers in central directory entry at $p but no Zip64 extra field found")
                }
            }

            val name = buffer.readUtf8(p, 46, nameLen)
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

    private fun findEocdOffset(buffer: LargeBytes): Long? {
        val size = buffer.size
        val searchFrom = (size - EOCD_MAX_SEARCH_WINDOW).coerceAtLeast(0)
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

    private class Zip64EocdInfo(
        val entryCount: Long,
        val cdSize: Long,
        val cdOffset: Long,
    )

    private fun findZip64Eocd(buffer: LargeBytes, eocdOffset: Long): Zip64EocdInfo? {
        // The Zip64 EOCD Locator sits immediately before the classic EOCD.
        val locatorOffset = eocdOffset - ZIP64_EOCD_LOCATOR_SIZE
        if (locatorOffset < 0) return null
        if (buffer.readU4LE(locatorOffset).toInt() != ZIP64_EOCD_LOCATOR_SIGNATURE) return null

        val zip64EocdOffset = buffer.readU8LE(locatorOffset + 8)
        if (zip64EocdOffset < 0 || zip64EocdOffset + ZIP64_EOCD_FIXED_SIZE > buffer.size) return null
        if (buffer.readU4LE(zip64EocdOffset).toInt() != ZIP64_EOCD_SIGNATURE) return null

        val entryCount = buffer.readU8LE(zip64EocdOffset + 32)
        val cdSize = buffer.readU8LE(zip64EocdOffset + 40)
        val cdOffset = buffer.readU8LE(zip64EocdOffset + 48)
        return Zip64EocdInfo(entryCount, cdSize, cdOffset)
    }

    private class Zip64ExtraInfo(
        val uncompressedSize: Long,
        val compressedSize: Long,
        val localHeaderOffset: Long,
    )

    /**
     * Parses the Zip64 Extended Information Extra Field (header id 0x0001)
     * from the extra field area of a central directory entry.
     *
     * Per APPNOTE 4.5.3, the extra field contains 8-byte values only for
     * fields that are set to 0xFFFFFFFF in the corresponding fixed-size
     * record, in the order: uncompressed size, compressed size, local header
     * offset, disk start number.
     */
    private fun parseZip64ExtraField(
        buffer: LargeBytes,
        extraStart: Long,
        extraLen: Int,
        origUncompressed: Long,
        origCompressed: Long,
        origLocalOffset: Long,
    ): Zip64ExtraInfo? {
        var pos = extraStart
        val extraEnd = extraStart + extraLen
        while (pos + 4 <= extraEnd) {
            val headerId = buffer.readU2LE(pos)
            val dataSize = buffer.readU2LE(pos + 2)
            if (headerId == ZIP64_EXTRA_FIELD_ID) {
                var off = pos + 4
                var uncompressed = origUncompressed
                var compressed = origCompressed
                var localOffset = origLocalOffset
                if (origUncompressed == 0xFFFFFFFFL) {
                    uncompressed = buffer.readU8LE(off); off += 8
                }
                if (origCompressed == 0xFFFFFFFFL) {
                    compressed = buffer.readU8LE(off); off += 8
                }
                if (origLocalOffset == 0xFFFFFFFFL) {
                    localOffset = buffer.readU8LE(off)
                }
                return Zip64ExtraInfo(uncompressed, compressed, localOffset)
            }
            pos += 4 + dataSize
        }
        return null
    }

    private fun computeLocalDataOffset(buffer: LargeBytes, localHeaderOffset: Long): Long {
        val signature = buffer.readU4LE(localHeaderOffset)
        require(signature.toInt() == LOCAL_HEADER_SIGNATURE) {
            "local file header at $localHeaderOffset has bad signature 0x${signature.toString(16)}"
        }
        val nameLen = buffer.readU2LE(localHeaderOffset + 26)
        val extraLen = buffer.readU2LE(localHeaderOffset + 28)
        return localHeaderOffset + 30 + nameLen + extraLen
    }
}

// -----------------------------------------------------------------------------
// Little-endian readers and bounded UTF-8 string reads, at 64-bit offsets
// -----------------------------------------------------------------------------

private fun LargeBytes.readU2LE(at: Long): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun LargeBytes.readU4LE(at: Long): Long {
    var value = 0L
    for (i in 0 until 4) value = value or ((this[at + i].toLong() and 0xFFL) shl (8 * i))
    return value
}

private fun LargeBytes.readU8LE(at: Long): Long {
    var value = 0L
    for (i in 0 until 8) value = value or ((this[at + i].toLong() and 0xFFL) shl (8 * i))
    return value
}

private fun LargeBytes.readUtf8(at: Long, offset: Int, length: Int): String {
    if (length <= 0) return ""
    return readBytes(at + offset, length).decodeToString()
}
