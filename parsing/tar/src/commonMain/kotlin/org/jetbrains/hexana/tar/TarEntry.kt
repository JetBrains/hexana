package org.jetbrains.hexana.tar

/**
 * One entry from a TAR archive.
 *
 * Supports POSIX ustar and GNU tar extensions for long file names.
 * Each entry describes a file or directory within the archive.
 */
data class TarEntry(
    /** Decoded entry name (path). */
    val name: String,

    /** Size of the entry data in bytes. */
    val size: Long,

    /** Offset from the start of the (uncompressed) tar stream where the entry's data begins. */
    val dataOffset: Long,

    /** Unix file mode (octal), e.g. 0644. */
    val mode: Int,

    /** Last modification time as Unix timestamp (seconds since epoch). */
    val modTime: Long,

    /** Entry type flag: '0' or '\0' = regular file, '5' = directory, '2' = symlink, etc. */
    val typeFlag: Char,

    /** Link target name for symlinks/hardlinks, empty otherwise. */
    val linkName: String,
) {
    /** Convenience predicate — entry is a directory. */
    val isDirectory: Boolean
        get() = typeFlag == '5' || name.endsWith('/')

    /** Convenience predicate — entry is a regular file. */
    val isRegularFile: Boolean
        get() = typeFlag == '0' || typeFlag == '\u0000'
}
