package org.jetbrains.hexana.io

/** Describes the precision of a [ByteProvenance] range for a decoded cell value. */
enum class ByteProvenanceKind {
    /** Exact per-value byte range; the value bytes can be read directly from the file. */
    EXACT,

    /** Range covers an encoded run (e.g. RLE/bit-packed); value is inside the run. */
    ENCODED_RUN,

    /** Range covers the compressed or uncompressed page containing this value. */
    PAGE,

    /** Range covers the entire column chunk; per-page position is not tracked. */
    CHUNK,

    /** No provenance available (e.g. null value, unsupported encoding). */
    NONE,
}

/** Byte range in the source file that produced a decoded cell value. */
class ByteProvenance(
    /** Absolute byte offset in the source file. */
    val offset: Long,
    /** Byte length of the range. */
    val size: Int,
    /** How precisely the range locates the value. */
    val kind: ByteProvenanceKind,
)

/** A single decoded cell: the logical value, null flag, and byte provenance. */
class DecodedCell(
    /**
     * The decoded value. Type depends on the column physical type:
     *   INT32 -> Int, INT64 -> Long, FLOAT -> Float, DOUBLE -> Double,
     *   BOOLEAN -> Boolean, BYTE_ARRAY -> String (UTF-8) or ByteArray,
     *   FIXED_LEN_BYTE_ARRAY -> ByteArray.
     */
    val value: Any?,
    /** True when the value is null (definition level < max). */
    val isNull: Boolean,
    /** Where in the source file this value's bytes live. */
    val provenance: ByteProvenance,
)
