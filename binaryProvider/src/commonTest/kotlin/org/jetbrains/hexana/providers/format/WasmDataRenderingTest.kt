package org.jetbrains.hexana.providers.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.io.toCommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider

/**
 * Phase 1 Data-section rendering: per-entry chunks, single header row,
 * foldable body shown as `xxd`-style hex + ASCII rows. Tests lock in the
 * shape of the row list and the header text so the panel can rely on
 * [RowKind] tagging to drive its expand/collapse UI.
 */
class WasmDataRenderingTest {

    /**
     * Single-byte payload — small enough to fit in one body row, useful
     * as a smoke test. Data entry layout:
     *
     * ```
     *   0B 07 01 00 41 00 0B 01 7F
     *   id sz  n  mem  init   len byte
     * ```
     *
     * - active, mem 0, offset i32.const 0
     * - 1 byte payload: 0x7F (DEL — not printable, ASCII column should be `.`).
     */
    private val moduleBytes: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
        0x0B, 0x07, 0x01, 0x00, 0x41, 0x00, 0x0B, 0x01, 0x7F.toByte(),
    )

    /**
     * 20-byte ASCII payload "Hello, WebAssembly!." — straddles the 16-byte
     * row boundary so we get two body rows. Data section bytes:
     *
     * ```
     *   0B 1A 01 00 41 00 0B 14 H e l l o ,   W e b A s s e m b l y ! .
     *   id sz  n  mem  init   len ↑ 20 ASCII bytes ↑
     * ```
     *
     * - active, mem 0, offset i32.const 0
     * - 20 byte payload, mostly printable so the ASCII sidecar is meaningful.
     */
    private val moduleWithLongerData: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
        // 0x1A = section size 26; entries: 01 (count) + entry (00 41 00 0B 14 ...) = 26.
        0x0B, 0x1A, 0x01, 0x00, 0x41, 0x00, 0x0B, 0x14,
        0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x2C, 0x20, 0x57, // "Hello, W"
        0x65, 0x62, 0x41, 0x73, 0x73, 0x65, 0x6D, 0x62, // "ebAssemb"
        0x6C, 0x79, 0x21, 0x2E,                          // "ly!."
    )

    private fun parsed(bytes: ByteArray): WasmParsedBinary {
        val snapshot = binaryDocumentOf(bytes.toCommonByteBuffer()).snapshot()
        val provider = WasmBinaryProvider(snapshot.toCommonByteBuffer())
        return WasmParsedBinary.fromProvider(provider, snapshot)
    }

    private fun dataChunk(parsed: WasmParsedBinary, indexInSection: Int = 0): BinaryChunk.Element {
        val tree = buildWasmChunkTree(parsed)
        return (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .first { it.kind == WasmSectionKinds.DATA && it.indexInSection == indexInSection }
    }

    @Test
    fun data_section_emits_one_chunk_per_entry() {
        val parsed = parsed(moduleBytes)
        val tree = buildWasmChunkTree(parsed)
        val dataElements = (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .filter { it.kind == WasmSectionKinds.DATA }
        assertEquals(1, dataElements.size, "fixture has one data entry")
        assertEquals(0, dataElements.single().indexInSection)
    }

    @Test
    fun small_data_chunk_renders_header_expander_descriptor_and_single_body_row() {
        val parsed = parsed(moduleBytes)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed))

        // Header + expander + 4 descriptor rows (mode, i32.const, end,
        // payload-len) + 1 body row (1 byte fits in one 16-byte row).
        assertEquals(RowKind.DataHeader, rows[0].kind)
        val expander = rows[1].kind
        assertTrue(expander is RowKind.DataExpander, "expander row must carry RowKind.DataExpander")
        assertEquals(1, (expander as RowKind.DataExpander).bodyByteCount)
        val descriptorRows = rows.filter { it.kind == RowKind.DataDescriptor }
        assertEquals(4, descriptorRows.size,
            "expected 4 descriptor rows (mode, i32.const, end, payload-len), got ${descriptorRows.size}")
        val bodyRows = rows.filter { it.kind == RowKind.DataBody }
        assertEquals(1, bodyRows.size, "expected 1 body row, got ${bodyRows.size}")
    }

    @Test
    fun header_text_includes_index_mode_init_expr_and_size() {
        val parsed = parsed(moduleBytes)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed))
        val header = rows.first().tokens.joinToString("") { it.text }
        assertTrue("data[0]" in header, "expected data index in header, got: $header")
        assertTrue("active mem 0" in header, "expected mode + memidx in header, got: $header")
        assertTrue("i32.const 0" in header, "expected init expr in header, got: $header")
        assertTrue("1 byte" in header, "expected byte count in header, got: $header")
    }

    @Test
    fun body_row_renders_hex_and_ascii_sidecar() {
        val parsed = parsed(moduleBytes)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed))
        val bodyText = rows.first { it.kind == RowKind.DataBody }.tokens.joinToString("") { it.text }
        assertTrue("7F" in bodyText, "expected hex `7F` in body row, got: $bodyText")
        assertTrue(" | " in bodyText, "expected hex|ascii separator, got: $bodyText")
        // 0x7F is the non-printable DEL byte; ASCII sidecar shows it as `.`.
        // The sidecar is space-padded to 16 chars so we check the first byte
        // after the separator, not the row's trailing char.
        val ascii = bodyText.substringAfter(" | ")
        assertEquals('.', ascii.first(), "expected ASCII fallback for non-printable 0x7F, got: '$ascii'")
    }

    @Test
    fun multi_row_payload_splits_into_ceil_div_16_body_rows() {
        val parsed = parsed(moduleWithLongerData)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed))

        // 20 bytes / 16 per row = 2 body rows.
        val bodyRows = rows.filter { it.kind == RowKind.DataBody }
        assertEquals(2, bodyRows.size, "expected ceil(20/16) = 2 body rows, got ${bodyRows.size}")

        // The first body row should expose the ASCII "Hello, W…" sidecar
        // (the bytes 0x48..0x57 are all printable).
        val firstBody = bodyRows[0].tokens.joinToString("") { it.text }
        assertTrue("Hello, W" in firstBody, "expected ASCII run in first body row: $firstBody")
        // Second body row carries the tail ("ebAssemb..." plus the trailing
        // "ly!." — the ASCII column is padded to 16 chars so the tail bytes
        // appear in the sidecar substring.
        val secondBody = bodyRows[1].tokens.joinToString("") { it.text }
        assertTrue("ly!." in secondBody, "expected tail ASCII in second body row: $secondBody")
    }

    @Test
    fun u16_mode_packs_bytes_le_with_eight_values_per_row() {
        val parsed = parsed(moduleWithLongerData)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed), DataViewMode.U16_HEX)

        val bodyRows = rows.filter { it.kind == RowKind.DataBody }
        assertEquals(2, bodyRows.size, "u16 mode still 16 bytes / row, so 20 bytes → 2 rows")
        val firstBody = bodyRows[0].tokens.joinToString("") { it.text }
        // Bytes 0..1 = [0x48, 0x65] = "He" → LE u16 = 0x6548. Expect "6548" in hex column.
        assertTrue("6548" in firstBody, "expected LE u16 0x6548 in first body row, got: $firstBody")
        // ASCII sidecar is preserved regardless of mode.
        assertTrue("Hello, W" in firstBody, "ASCII sidecar must still appear in u16 mode: $firstBody")
    }

    @Test
    fun u32_mode_packs_bytes_le_with_four_values_per_row() {
        val parsed = parsed(moduleWithLongerData)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed), DataViewMode.U32_HEX)

        val bodyRows = rows.filter { it.kind == RowKind.DataBody }
        val firstBody = bodyRows[0].tokens.joinToString("") { it.text }
        // Bytes 0..3 = [0x48, 0x65, 0x6C, 0x6C] = "Hell" → LE u32 = 0x6C6C6548.
        assertTrue("6C6C6548" in firstBody, "expected LE u32 0x6C6C6548 in first body row, got: $firstBody")
    }

    @Test
    fun u64_mode_packs_bytes_le_with_two_values_per_row() {
        val parsed = parsed(moduleWithLongerData)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed), DataViewMode.U64_HEX)

        val bodyRows = rows.filter { it.kind == RowKind.DataBody }
        val firstBody = bodyRows[0].tokens.joinToString("") { it.text }
        // Bytes 0..7 = "Hello, W" → 0x57202C6F6C6C6548 in LE u64.
        assertTrue("57202C6F6C6C6548" in firstBody,
            "expected LE u64 0x57202C6F6C6C6548 in first body row, got: $firstBody")
    }

    @Test
    fun ascii_mode_drops_hex_column_and_widens_row() {
        val parsed = parsed(moduleWithLongerData)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed), DataViewMode.ASCII)

        val bodyRows = rows.filter { it.kind == RowKind.DataBody }
        // 20 bytes / 64 per row in ASCII mode → 1 row.
        assertEquals(1, bodyRows.size, "ASCII mode = 64 bytes / row, so 20 bytes → 1 row")
        val text = bodyRows[0].tokens.joinToString("") { it.text }
        // No hex column / pipe separator in ASCII mode.
        assertTrue(" | " !in text, "ASCII mode must drop the `|` hex separator, got: $text")
        assertTrue("Hello, WebAssembly!." in text, "expected full decoded string in row: $text")
    }

    @Test
    fun utf8_mode_decodes_multi_byte_sequences() {
        // Fixture: 4-byte payload "é!" (U+00E9 = 0xC3 0xA9, then '!' = 0x21,
        // then an extra trailing byte 0x2E '.').
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
            0x03, 0x02, 0x01, 0x00,
            0x05, 0x03, 0x01, 0x00, 0x01,
            0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
            // data section: size = 1 + 1+3+1+4 = 10
            0x0B, 0x0A, 0x01, 0x00, 0x41, 0x00, 0x0B, 0x04,
            0xC3.toByte(), 0xA9.toByte(), 0x21, 0x2E,
        )
        val parsed = parsed(bytes)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed), DataViewMode.UTF8)
        val bodyRows = rows.filter { it.kind == RowKind.DataBody }
        val text = bodyRows.single().tokens.joinToString("") { it.text }
        // "é!." — note ASCII mode would have rendered the two-byte UTF-8 sequence as `..`.
        assertTrue("é!." in text, "expected UTF-8-decoded `é!.` in row, got: $text")
    }

    @Test
    fun descriptor_rows_break_out_mode_initexpr_end_and_length() {
        val parsed = parsed(moduleBytes)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed))
        val desc = rows.filter { it.kind == RowKind.DataDescriptor }

        // 1. mode byte
        val modeText = desc[0].tokens.joinToString("") { it.text }
        assertTrue("active, mem 0" in modeText, "mode row should label active mem 0: $modeText")
        assertTrue(" 00 " in modeText, "mode row should show hex byte 00: $modeText")

        // 2. i32.const opcode (offset + hex + mnemonic, like a code row)
        val constText = desc[1].tokens.joinToString("") { it.text }
        assertTrue("i32.const" in constText, "init-expr row missing mnemonic: $constText")
        assertTrue("0" in constText, "init-expr row missing immediate value: $constText")
        assertTrue("41" in constText, "init-expr row missing hex bytes 41: $constText")

        // 3. trailing `end` for the init expr
        val endText = desc[2].tokens.joinToString("") { it.text }
        assertTrue("end" in endText, "expected `end` row after init-expr opcodes: $endText")
        assertTrue("0B" in endText, "end row should show hex byte 0B: $endText")

        // 4. payload-length ULEB
        val lenText = desc[3].tokens.joinToString("") { it.text }
        assertTrue("payload" in lenText, "payload-length row should label the payload size: $lenText")
        assertTrue("1 byte" in lenText, "payload-length row should state byte count: $lenText")
    }

    @Test
    fun init_expr_row_shows_sleb_annotation_for_multibyte_immediate() {
        // Fixture: data offset is `i32.const 0x1000` (SLEB 80 20 = 2 bytes).
        // Expect the `i32.const 4096` row to carry a `(; sleb128 80 20 ;)`
        // annotation — same as code-section rows do.
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
            0x03, 0x02, 0x01, 0x00,
            0x05, 0x03, 0x01, 0x00, 0x01,
            0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
            // data section: id 0x0B, size 8; entry: mode 0, init i32.const 0x1000 (2-byte SLEB), end, len 1, byte 0x42
            0x0B, 0x08, 0x01, 0x00, 0x41, 0x80.toByte(), 0x20, 0x0B, 0x01, 0x42,
        )
        val parsed = parsed(bytes)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed))
        val desc = rows.filter { it.kind == RowKind.DataDescriptor }
        val constRow = desc.first { row -> row.tokens.any { "i32.const" in it.text } }
        val constText = constRow.tokens.joinToString("") { it.text }
        assertTrue("sleb128" in constText, "expected SLEB annotation in i32.const row, got: $constText")
        assertTrue("80 20" in constText, "expected raw SLEB bytes `80 20`, got: $constText")
    }

    @Test
    fun init_expr_row_omits_leb_annotation_for_single_byte_immediate() {
        // `i32.const 0` is a 1-byte SLEB — no annotation noise.
        val parsed = parsed(moduleBytes)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed))
        val constRow = rows.filter { it.kind == RowKind.DataDescriptor }
            .first { row -> row.tokens.any { "i32.const" in it.text } }
        val text = constRow.tokens.joinToString("") { it.text }
        assertTrue("sleb128" !in text, "1-byte SLEB must NOT emit annotation: $text")
        assertTrue("uleb128" !in text, "1-byte ULEB must NOT emit annotation: $text")
    }

    @Test
    fun header_hex_preview_excludes_data_payload_bytes() {
        val parsed = parsed(moduleBytes)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed))
        val headerText = rows.first { it.kind == RowKind.DataHeader }.tokens.joinToString("") { it.text }
        // The fixture's payload byte is 0x7F. Descriptor is `00 41 00 0B 01`.
        // The header's hex column must show descriptor bytes only.
        assertTrue("7F" !in headerText,
            "header hex must not bleed into payload bytes (0x7F was visible): $headerText")
        assertTrue("00" in headerText && "41" in headerText,
            "header hex should still show descriptor bytes: $headerText")
    }

    @Test
    fun header_row_has_data_header_kind_and_no_followtarget() {
        val parsed = parsed(moduleBytes)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, dataChunk(parsed))
        val header = rows.first()
        assertEquals(RowKind.DataHeader, header.kind)
        // No follow target — Data header rows aren't navigation surfaces in
        // Phase 1. (Future: link to the address pointed at by the init expr.)
        assertEquals(null, header.followTarget)
    }
}
