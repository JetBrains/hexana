package org.jetbrains.hexana.providers.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider

/**
 * Foldable Export-section rows: header + expander + xxd-format symbol /
 * descriptor rows. The descriptor row for a function export carries a
 * [FollowTarget] so clicking / Cmd+B-ing it navigates to the function's
 * declaration row (Function section, or Import section for imported
 * funcs).
 */
class WasmExportRenderingTest {

    /**
     * Module exporting one function (`main`) and one memory (`mem`).
     *
     * ```
     *   ; header
     *   00 61 73 6D 01 00 00 00
     *   ; type[0] = (func()->i32)
     *   01 05 01 60 00 01 7F
     *   ; function[0] = type 0
     *   03 02 01 00
     *   ; memory[0] min=1
     *   05 03 01 00 01
     *   ; export section: size = 1 + (1+4+1+1) + (1+3+1+1) = 13 = 0x0D
     *   07 0D 02
     *     ; export[0]: name "main" (len 4), func, idx 0
     *     04 6D 61 69 6E 00 00
     *     ; export[1]: name "mem" (len 3), memory, idx 0
     *     03 6D 65 6D 02 00
     *   ; code[0]
     *   0A 06 01 04 00 41 05 0B
     * ```
     *
     * The `main` export's record byte layout, starting at chunk offset:
     *   [4-byte name slice][2-byte descriptor (kind=0, idx=0)]
     */
    private val moduleBytes: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x07, 0x0D, 0x02,
        0x04, 0x6D, 0x61, 0x69, 0x6E, 0x00, 0x00,
        0x03, 0x6D, 0x65, 0x6D, 0x02, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    private fun parsed(bytes: ByteArray = moduleBytes): WasmParsedBinary {
        val snapshot = binaryDocumentOf(bytes).snapshot()
        val provider = WasmBinaryProvider(snapshot.toCommonByteBuffer())
        return WasmParsedBinary.fromProvider(provider, snapshot)
    }

    private fun exportChunks(parsed: WasmParsedBinary): List<BinaryChunk.Element> {
        val tree = buildWasmChunkTree(parsed)
        return (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .filter { it.kind == WasmSectionKinds.EXPORT }
    }

    @Test
    fun export_chunk_emits_header_expander_and_two_descriptor_rows() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, exportChunks(parsed).first())

        // Header + expander + 2 descriptor rows.
        assertEquals(4, rows.size, "expected 4 rows total, got ${rows.size}")
        assertTrue(rows[1].kind is RowKind.ExportExpander, "row 1 must be ExportExpander, got ${rows[1].kind}")
        val descriptorRows = rows.filter { it.kind == RowKind.ExportDescriptor }
        assertEquals(2, descriptorRows.size, "expected 2 descriptor rows, got ${descriptorRows.size}")
    }

    @Test
    fun func_export_header_carries_follow_target_descriptor_does_not() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, exportChunks(parsed).first())

        // The header (row 0) is the always-visible preview. Click on it
        // navigates — so the follow target lives there, not on a row
        // that's only visible when the chunk is expanded.
        val header = rows[0]
        val target = header.followTarget
        assertNotNull(target, "header preview must carry the FollowTarget for func exports")
        assertEquals(0, target.funcIdx, "func index in the export header")
        assertEquals(WasmSectionKinds.FUNCTION, target.section,
            "func exports navigate to FUNCTION section like Cmd+B from a call site")

        // Descriptor rows are byte previews only.
        val descRows = rows.filter { it.kind == RowKind.ExportDescriptor }
        for ((i, row) in descRows.withIndex()) {
            assertNull(row.followTarget, "descriptor row $i must not carry a follow target")
        }
    }

    @Test
    fun non_func_export_header_carries_no_follow_target() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, exportChunks(parsed)[1])
        // Phase 1: only function exports are navigable. Memory export's
        // header preview should NOT be clickable.
        assertNull(rows[0].followTarget,
            "memory / table / global export header must not carry a follow target in Phase 1")
        val descRow = rows.filter { it.kind == RowKind.ExportDescriptor }[1]
        val text = descRow.tokens.joinToString("") { it.text }
        assertTrue("memory 0" in text, "memory export descriptor should label `memory 0`: $text")
    }

    @Test
    fun descriptor_rows_use_xxd_format_with_ascii_sidecar() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, exportChunks(parsed).first())
        val descriptorRows = rows.filter { it.kind == RowKind.ExportDescriptor }
        for ((i, row) in descriptorRows.withIndex()) {
            val text = row.tokens.joinToString("") { it.text }
            assertTrue(" | " in text, "row $i must have xxd separator, got: $text")
        }
        // Label content survives the xxd transform.
        val nameText = descriptorRows[0].tokens.joinToString("") { it.text }
        assertTrue("symbol \"main\"" in nameText, "name row should label symbol: $nameText")
        val descText = descriptorRows[1].tokens.joinToString("") { it.text }
        assertTrue("func 0" in descText, "descriptor row should label func 0: $descText")
    }
}
