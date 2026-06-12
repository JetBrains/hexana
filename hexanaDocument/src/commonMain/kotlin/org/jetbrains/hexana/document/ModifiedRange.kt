package org.jetbrains.hexana.document

/**
 * A contiguous range of bytes in the current document state that differs from
 * the original (unmodified) content. Returned by
 * [EditableBinaryDocument.modifiedRanges].
 *
 * @property offset logical offset in the current document where the modified
 *   range starts.
 * @property length number of modified bytes starting at [offset].
 */
public data class ModifiedRange(
    public val offset: Long,
    public val length: Long,
) {
    init {
        require(offset >= 0L) { "offset must be non-negative: $offset" }
        require(length > 0L) { "length must be positive: $length" }
    }
}
