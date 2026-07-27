package org.jetbrains.hexana.providers.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.io.toCommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider

/**
 * `call_indirect` rows are outside the inline-editable subset
 * ([EditableRows.isEditable] returns false), so `RenderedRow.editable` is
 * always null for them. The dispatch-candidates popup therefore keys off
 * [RenderedRow.indirectCallTypeIdx], which the renderer must set from the
 * instruction's type immediate. This pins that plumbing — the popup is
 * unreachable without it.
 */
class WasmIndirectCallRowTest {

    /**
     * Minimal module with one function whose body is
     * `i32.const 0; call_indirect (type 0)`, a funcref table, and an active
     * element segment placing func 0 in the table.
     *
     * ```
     *   00 61 73 6D 01 00 00 00          ; header
     *   01 04 01 60 00 00                ; type[0] = (func)
     *   03 02 01 00                      ; function[0] = type 0
     *   04 04 01 70 00 01                ; table[0] = funcref min=1
     *   09 07 01 00 41 00 0B 01 00       ; elem: active, offset 0, [func 0]
     *   0A 09 01 07 00 41 00 11 00 00 0B ; code[0]: i32.const 0; call_indirect (type 0) 0
     * ```
     */
    private val moduleBytes: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x04, 0x04, 0x01, 0x70, 0x00, 0x01,
        0x09, 0x07, 0x01, 0x00, 0x41, 0x00, 0x0B, 0x01, 0x00,
        0x0A, 0x09, 0x01, 0x07, 0x00, 0x41, 0x00, 0x11, 0x00, 0x00, 0x0B,
    )

    private fun codeRows(inlineCandidates: Boolean): List<RenderedRow> {
        val snapshot = binaryDocumentOf(moduleBytes.toCommonByteBuffer()).snapshot()
        val provider = WasmBinaryProvider(snapshot.toCommonByteBuffer())
        val parsed = WasmParsedBinary.fromProvider(provider, snapshot)
        val tree = buildWasmChunkTree(parsed)
        val codeChunk = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .first { it.kind == WasmSectionKinds.CODE }
        val renderer = WasmHexanaWatRenderer(parsed).apply { inlineIndirectCandidates = inlineCandidates }
        return renderer.renderRows(snapshot, codeChunk)
    }

    @Test
    fun call_indirect_row_carries_type_immediate_and_stays_read_only() {
        val rows = codeRows(inlineCandidates = false)
        val indirectRows = rows.filter { it.indirectCallTypeIdx != null }

        assertEquals(1, indirectRows.size, "expected exactly one call_indirect row, got ${indirectRows.size}")
        assertEquals(0, indirectRows.single().indirectCallTypeIdx)
        assertNull(indirectRows.single().editable, "call_indirect rows must stay read-only")
    }

    @Test
    fun candidate_rows_are_off_by_default_for_non_panel_consumers() {
        // The VS Code WAT tab and the WAT-diff path call renderRows with a
        // default-configured renderer and have no expander handling — they
        // must never see candidate rows.
        val rows = codeRows(inlineCandidates = false)
        assertEquals(
            0,
            rows.count { it.kind is RowKind.IndirectCandidate || it.kind is RowKind.IndirectCandidatesExpander },
        )
    }

    @Test
    fun inline_candidates_emit_expander_and_clickable_rows_after_the_site() {
        val rows = codeRows(inlineCandidates = true)
        val siteIndex = rows.indexOfFirst { it.indirectCallTypeIdx != null }

        val expander = rows[siteIndex + 1].kind
        assertIs<RowKind.IndirectCandidatesExpander>(expander, "expander row must follow the call_indirect row")
        assertEquals(1, expander.count)

        val candidate = rows[siteIndex + 2]
        val candidateKind = candidate.kind
        assertIs<RowKind.IndirectCandidate>(candidateKind)
        assertEquals(expander.siteKey, candidateKind.siteKey, "candidate rows must share the site key")
        assertEquals(0, candidate.followTarget?.funcIdx, "candidate row must navigate to the table entry")
        assertNull(candidate.editable)
    }
}
