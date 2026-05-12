package org.jetbrains.hexana.parsing.common

data class NativeByteRange(
    val offset: Int,
    val size: Int,
) {
    init {
        require(offset >= 0) { "offset must be non-negative" }
        require(size >= 0) { "size must be non-negative" }
        require(offset <= Int.MAX_VALUE - size) { "range end must fit in Int" }
    }

    val endExclusive: Int
        get() = offset + size
}
