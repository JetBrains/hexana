package org.jetbrains.hexana.zip

/**
 * One entry from a ZIP central directory (PKWARE APPNOTE 4.4).
 *
 * MVP — classic ZIP only. Zip64 extensions (entries / offsets >= 4 GiB) are
 * not yet supported; oversized values raise from the parser rather than being
 * silently truncated. Encrypted entries are exposed as-is — the consumer
 * decides whether to surface them as readable.
 */
data class ZipEntry(
    /** Decoded entry name (path). Forward slashes; the parser does not normalize. */
    val name: String,

    /** PKWARE compression method id: 0 = stored, 8 = deflate, others rare. */
    val compressionMethod: Int,

    /** Compressed size in bytes (= bytes on disk between data offset and the next record). */
    val compressedSize: Long,

    /** Uncompressed size in bytes (what an [Inflater]-driven decode would yield). */
    val uncompressedSize: Long,

    /** CRC-32 of the uncompressed data. */
    val crc32: Int,

    /** General purpose bit flags from the central directory entry. */
    val flags: Int,

    /** Offset (from the start of the archive) of the local file header. */
    val localHeaderOffset: Long,

    /**
     * Offset (from the start of the archive) of the entry's data bytes —
     * i.e. immediately after the local file header and its variable-length
     * name + extra fields. This is where compressed bytes actually begin.
     */
    val dataOffset: Long,
) {
    /** Convenience predicate — entry is a directory entry (name ends in `/`, size 0). */
    val isDirectory: Boolean
        get() = name.endsWith('/')

    /** Convenience predicate — entry looks like a JVM `.class` file. */
    val isClass: Boolean
        get() = name.endsWith(".class", ignoreCase = true) && !isDirectory
}
