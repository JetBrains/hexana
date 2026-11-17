package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.ByteProvenance
import org.jetbrains.hexana.io.ByteProvenanceKind
import org.jetbrains.hexana.io.DecodedCell

/**
 * A materialized tabular view of decoded record data for a single batch or row group.
 *
 * Columns are stored column-major ([columns] indexed by column, then row). The
 * [cell] / [rowRanges] accessors provide row-major access without transposing in memory.
 *
 * [rowRanges] returns the coalesced byte provenance for one row: each column's cell
 * provenance is collected, NONE-kind entries are dropped, the remaining spans are
 * sorted by offset, and adjacent/overlapping spans are merged into disjoint ranges.
 * When two spans overlap, the coarser [ByteProvenanceKind] is kept (EXACT < ENCODED_RUN
 * < PAGE < CHUNK).
 *
 * @param columnNames    Display names for each column, length == [columnCount].
 * @param columnTypes    Human-readable type labels, length == [columnCount].
 * @param rowCount       Number of rows (after row-limit capping).
 * @param truncated      True when the source data was capped (row limit or only first
 *                       row group / record batch decoded).
 * @param columns        Per-column cell lists, each of length [rowCount].
 * @param columnDecoded  Whether each column was fully decoded.
 * @param undecodedReason  Reason text when a column was not fully decoded; null if decoded.
 */
class DecodedTable(
    val columnNames: List<String>,
    val columnTypes: List<String>,
    val rowCount: Int,
    val truncated: Boolean,
    private val columns: List<List<DecodedCell>>,
    private val columnDecoded: List<Boolean>,
    private val undecodedReason: List<String?>,
) {
    val columnCount: Int get() = columnNames.size

    /** Returns the decoded cell at [row], [col]. */
    fun cell(row: Int, col: Int): DecodedCell = columns[col][row]

    /** Returns true when column [col] was fully decoded. */
    fun columnFullyDecoded(col: Int): Boolean = columnDecoded[col]

    /** Returns the human-readable reason column [col] was not decoded, or null if decoded. */
    fun columnUndecodedReason(col: Int): String? = undecodedReason[col]

    /**
     * Returns the coalesced byte-provenance ranges for all cells in [row].
     *
     * Algorithm: collect each column's provenance for [row], drop NONE-kind entries,
     * sort by offset, then merge overlapping or adjacent spans (keeping the coarser kind
     * when merging). Returns an empty list when no non-NONE provenance is available.
     */
    fun rowRanges(row: Int): List<ByteProvenance> {
        val spans = mutableListOf<ByteProvenance>()
        for (col in 0 until columnCount) {
            val prov = columns[col][row].provenance
            if (prov.kind != ByteProvenanceKind.NONE && prov.size > 0) {
                spans += prov
            }
        }
        if (spans.isEmpty()) return emptyList()
        spans.sortWith(compareBy { it.offset })
        val merged = mutableListOf<ByteProvenance>()
        var cur = spans[0]
        for (i in 1 until spans.size) {
            val next = spans[i]
            val curEnd = cur.offset + cur.size
            if (next.offset <= curEnd) {
                // Overlapping or adjacent — extend and coarsen kind.
                val newEnd = maxOf(curEnd, next.offset + next.size)
                val newKind = coarser(cur.kind, next.kind)
                cur = ByteProvenance(cur.offset, (newEnd - cur.offset).toInt(), newKind)
            } else {
                merged += cur
                cur = next
            }
        }
        merged += cur
        return merged
    }
}

private fun coarser(a: ByteProvenanceKind, b: ByteProvenanceKind): ByteProvenanceKind {
    // Ordinal order: EXACT(0) < ENCODED_RUN(1) < PAGE(2) < CHUNK(3) < NONE(4)
    return if (a.ordinal >= b.ordinal) a else b
}

/** Default maximum number of rows decoded per batch/row-group. */
const val DEFAULT_ROW_LIMIT: Int = 10_000
