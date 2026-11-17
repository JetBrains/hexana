package org.jetbrains.hexana.parsing.common

/**
 * A byte range in a native binary: [start] and [length], 64-bit so that a range in a file past
 * 2 GiB (an ELF section, a core-dump segment) can be described at all.
 *
 * [offset], [size] and [endExclusive] are the `Int` views most parsers and table builders use: they
 * work on one `Int`-sized buffer, where every range they produce fits. Reading them on a range that
 * does not fit throws [ArithmeticException] instead of truncating, so code on the large-file path
 * (ELF and what consumes its ranges) reads [start], [length] and [end] instead.
 */
data class NativeByteRange(
    val start: Long,
    val length: Long,
) {
    constructor(offset: Int, size: Int) : this(offset.toLong(), size.toLong())

    init {
        require(start >= 0) { "offset must be non-negative" }
        require(length >= 0) { "size must be non-negative" }
        require(start <= Long.MAX_VALUE - length) { "range end must fit in Long" }
    }

    /** One past the last byte. */
    val end: Long
        get() = start + length

    /** [start] as an `Int`; throws past 2 GiB. */
    val offset: Int
        get() = start.toIntExactly()

    /** [length] as an `Int`; throws past 2 GiB. */
    val size: Int
        get() = length.toIntExactly()

    /** [end] as an `Int`; throws past 2 GiB. */
    val endExclusive: Int
        get() = end.toIntExactly()

    /** Whether the whole range is addressable with `Int` offsets. */
    val fitsInt: Boolean
        get() = end <= Int.MAX_VALUE
}

private fun Long.toIntExactly(): Int {
    if (this > Int.MAX_VALUE || this < Int.MIN_VALUE) throw ArithmeticException("$this does not fit an Int")
    return toInt()
}
