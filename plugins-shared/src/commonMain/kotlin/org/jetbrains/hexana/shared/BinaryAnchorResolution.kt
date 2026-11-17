package org.jetbrains.hexana.shared

/**
 * Stateless helpers for mapping between instruction ordinals and byte offsets.
 *
 * [opOffsets] is an ascending list of absolute instruction byte offsets for
 * one function body. Callers build it from [WasmInstrOffsets.deduceOffsets]
 * output using the recorded offsets of the flattened instruction list.
 */
object BinaryAnchorResolution {

    /**
     * Returns the 0-based instruction ordinal for [byteOffset] in [opOffsets],
     * or null if no instruction starts at exactly that offset.
     *
     * Matching is exact; nearest-neighbour lookup is intentionally not provided.
     */
    fun instrOrdinalForOffset(opOffsets: List<Long>, byteOffset: Long): Int? {
        val idx = opOffsets.indexOf(byteOffset)
        return if (idx == -1) null else idx
    }

    /**
     * Returns the byte offset for instruction at 0-based [ordinal] in [opOffsets],
     * or null if [ordinal] is out of range.
     */
    fun offsetForInstrOrdinal(opOffsets: List<Long>, ordinal: Int): Long? {
        return opOffsets.getOrNull(ordinal)
    }
}
