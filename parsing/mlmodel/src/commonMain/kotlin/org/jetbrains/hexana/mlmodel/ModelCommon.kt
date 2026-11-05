package org.jetbrains.hexana.mlmodel

/**
 * A byte range in a model file. Offsets are [Long] even though Hexana buffers are
 * currently `Int`-sized: tensor data in a model routinely sits past 2 GiB, and a parser
 * handed only the head of such a file must still be able to say where each tensor lives.
 */
data class ModelByteRange(val offset: Long, val size: Long) {
    init {
        require(offset >= 0) { "offset must be non-negative" }
        require(size >= 0) { "size must be non-negative" }
    }

    val endExclusive: Long get() = offset + size
}

/** A problem found while parsing a model file. The parse still returns what it could read. */
data class ModelDiagnostic(
    /** Stable, machine-readable code, e.g. `gguf.truncated`. */
    val code: String,
    val message: String,
    val range: ModelByteRange? = null,
)

/** Product of [dims], or `null` when a dimension is negative or the product overflows a [Long]. */
internal fun elementCountOf(dims: List<Long>): Long? {
    var count = 1L
    for (dim in dims) {
        if (dim < 0) return null
        if (dim != 0L && count > Long.MAX_VALUE / dim) return null
        count *= dim
    }
    return count
}
