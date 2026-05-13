package org.jetbrains.hexana.providers.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider

/**
 * Foldable Import-section rows: header (always) + expander + module /
 * symbol / type descriptor rows when expanded. Mirrors the Data-section
 * pattern so users get a consistent expand interaction across sections.
 */
class WasmImportRenderingTest {

    /**
     * Module with a single function import: `env.log : (func (type 0))`.
     *
     * ```
     *   00 61 73 6D 01 00 00 00                                ; header
     *   01 05 01 60 00 01 7F                                   ; type[0] = (func()->i32)
     *   02 0B 01 03 65 6E 76 03 6C 6F 67 00 00                 ; import "env" "log" func type 0
     *   03 02 01 00                                            ; function[0] = type 0
     *   05 03 01 00 01                                         ; memory[0]
     *   0A 06 01 04 00 41 05 0B                                ; code[0]
     * ```
     *
     * Import record starts at byte 0x11 (after section id + size + count
     * ULEBs). Layout within the record:
     *   - module string slice: `03 'env'` (4 bytes, off 0x11..0x14)
     *   - symbol string slice: `03 'log'` (4 bytes, off 0x15..0x18)
     *   - descriptor: `00 00` (2 bytes — kind=func, typeidx=0, off 0x19..0x1A)
     */
    private val moduleBytes: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x02, 0x0B, 0x01, 0x03, 0x65, 0x6E, 0x76, 0x03, 0x6C, 0x6F, 0x67, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    private fun parsed(bytes: ByteArray = moduleBytes): WasmParsedBinary {
        val snapshot = binaryDocumentOf(bytes).snapshot()
        val provider = WasmBinaryProvider(snapshot.toCommonByteBuffer())
        return WasmParsedBinary.fromProvider(provider, snapshot)
    }

    private fun importChunk(parsed: WasmParsedBinary): BinaryChunk.Element {
        val tree = buildWasmChunkTree(parsed)
        return (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .first { it.kind == WasmSectionKinds.IMPORT }
    }

    @Test
    fun import_chunk_emits_header_expander_and_three_descriptor_rows() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, importChunk(parsed))

        // Header + expander + 3 descriptor rows (module / symbol / type).
        assertEquals(5, rows.size, "expected 5 rows total, got ${rows.size}")
        assertTrue(rows[1].kind is RowKind.ImportExpander, "row 1 must be ImportExpander, got ${rows[1].kind}")
        val descriptorRows = rows.filter { it.kind == RowKind.ImportDescriptor }
        assertEquals(3, descriptorRows.size, "expected 3 descriptor rows, got ${descriptorRows.size}")
    }

    @Test
    fun descriptor_rows_carry_module_symbol_and_type_comments() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, importChunk(parsed))
        val descriptorRows = rows.filter { it.kind == RowKind.ImportDescriptor }

        val moduleText = descriptorRows[0].tokens.joinToString("") { it.text }
        assertTrue("module \"env\"" in moduleText, "module row should label `env`, got: $moduleText")

        val symbolText = descriptorRows[1].tokens.joinToString("") { it.text }
        assertTrue("symbol \"log\"" in symbolText, "symbol row should label `log`, got: $symbolText")

        val typeText = descriptorRows[2].tokens.joinToString("") { it.text }
        assertTrue("func (type 0)" in typeText, "type row should describe `func (type 0)`, got: $typeText")
    }

    /**
     * Long module name "wasi_snapshot_preview1" (22 chars + 1 length ULEB =
     * 23 bytes). At 16 bytes per xxd row, that's 2 rows; symbol "fd_write"
     * stays at 1 row, type at 1 row. Total descriptor rows = 4.
     */
    private val moduleBytesLongName: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        // Import section: id 0x02, size = 1(count) + 1+22(module) + 1+8(symbol) + 2(desc) = 35 = 0x23
        0x02, 0x23, 0x01,
        0x16, // module len = 22
        0x77, 0x61, 0x73, 0x69, 0x5F, 0x73, 0x6E, 0x61, 0x70, 0x73, 0x68, 0x6F, 0x74, 0x5F, 0x70, 0x72,
        0x65, 0x76, 0x69, 0x65, 0x77, 0x31,
        0x08, // symbol len = 8
        0x66, 0x64, 0x5F, 0x77, 0x72, 0x69, 0x74, 0x65,
        0x00, 0x00, // func, type 0
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    @Test
    fun long_module_name_spans_multiple_xxd_rows_with_label_on_first() {
        val parsed = parsed(moduleBytesLongName)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, importChunk(parsed))
        val descriptorRows = rows.filter { it.kind == RowKind.ImportDescriptor }

        // 23-byte module slice = 2 rows (16 + 7). Symbol = 1 row, type = 1 row. Total 4.
        assertEquals(4, descriptorRows.size,
            "23-byte module slice expected to span 2 rows, total descriptor rows = 4, got ${descriptorRows.size}")

        // First row carries the label; second row is a bare continuation.
        val firstRowText = descriptorRows[0].tokens.joinToString("") { it.text }
        assertTrue("module \"wasi_snapshot_preview1\"" in firstRowText,
            "first row should carry module label: $firstRowText")
        val secondRowText = descriptorRows[1].tokens.joinToString("") { it.text }
        assertTrue("module" !in secondRowText,
            "continuation row must not repeat the label: $secondRowText")
        // ASCII sidecar in continuation row shows the rest of the name.
        assertTrue("review1" in secondRowText,
            "continuation row should show ASCII tail `review1`: $secondRowText")
    }

    @Test
    fun descriptor_rows_use_xxd_format_with_ascii_sidecar() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, importChunk(parsed))
        val descriptorRows = rows.filter { it.kind == RowKind.ImportDescriptor }

        // Each row must contain the xxd-style ` | ` separator and the
        // ASCII sidecar — the format we'll later hang inline editing on.
        for ((i, row) in descriptorRows.withIndex()) {
            val text = row.tokens.joinToString("") { it.text }
            assertTrue(" | " in text, "row $i must have xxd separator, got: $text")
        }
    }

    @Test
    fun descriptor_rows_anchor_offsets_to_record_bytes() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val chunk = importChunk(parsed)
        val rows = renderer.renderRows(parsed.snapshot, chunk)
        val descriptorRows = rows.filter { it.kind == RowKind.ImportDescriptor }

        // First descriptor row anchors at chunk start.
        val moduleOffset = descriptorRows[0].tokens
            .firstOrNull { it.kind.name == "OFFSET_ANNOTATION" }
            ?.sourceOffset
        assertEquals(chunk.byteRange.first, moduleOffset,
            "module row offset should equal chunk start (= record offset)")

        // Type row anchors at offset of kind byte: chunk.byteRange.first + 4 + 4 (two 4-byte string slices).
        val typeOffset = descriptorRows[2].tokens
            .firstOrNull { it.kind.name == "OFFSET_ANNOTATION" }
            ?.sourceOffset
        assertEquals(chunk.byteRange.first + 8L, typeOffset,
            "type row offset should sit after the two name slices")
    }
}
