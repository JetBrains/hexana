package org.jetbrains.hexana.document

/**
 * Where a logical-offset byte came from in the original (unmodified) file.
 *
 * Phase 1: the document is a single immutable buffer, so [originalOffset] is
 * always equal to the logical offset.
 *
 * Phase 2 introduces a piece table over (original mmap + append-only added
 * buffer). Bytes sourced from the original buffer report their original-file
 * offset; bytes sourced from the added buffer (i.e. inserted by the user) have
 * no original-file location and [BinaryDocument.originate] returns `null` for
 * them. The hex view uses this to dim or badge "modified" rows.
 */
public data class OriginalLocation(
    public val originalOffset: Long,
    public val length: Long,
)
