package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `renderRows` covers Phase C.3a — the renderer-side row API. The panel
 * uses this to give each on-screen line its own composable; editable rows
 * become a `BasicTextField`, others stay as static Text. Tests here lock
 * in the row split + editability tagging so the UI layer can be added in
 * C.3b without revisiting the contract.
 */
class WasmRenderRowsTest {

    /**
     * Smallest valid module with an editable instruction in the function body:
     *
     * ```
     *  00 61 73 6D 01 00 00 00          ; magic + version 1
     *  01 05 01 60 00 01 7F             ; type[0] = (func (result i32))
     *  03 02 01 00                      ; function[0] uses type 0
     *  0A 06 01 04 00 41 05 0B          ; code[0]: 0 locals, i32.const 5, end
     * ```
     *
     * The `i32.const 5` opcode lives at byte offset 24 (encoded length 2:
     * `0x41 0x05`).
     */
    private val moduleBytes: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
    )

    private fun parsed(): WasmParsedBinary {
        val snapshot = binaryDocumentOf(moduleBytes).snapshot()
        val provider = WasmBinaryProvider(snapshot.toCommonByteBuffer())
        return WasmParsedBinary.fromProvider(provider, snapshot)
    }

    private fun codeChunk(parsed: WasmParsedBinary): BinaryChunk.Element {
        val tree = buildWasmChunkTree(parsed)
        return (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .first { it.kind == WasmSectionKinds.CODE }
    }

    private fun typeChunk(parsed: WasmParsedBinary): BinaryChunk.Element {
        val tree = buildWasmChunkTree(parsed)
        return (0 until tree.totalChunks)
            .map { tree.chunkAt(it) }
            .filterIsInstance<BinaryChunk.Element>()
            .first { it.kind == WasmSectionKinds.TYPE }
    }

    @Test
    fun non_code_chunk_returns_single_non_editable_row() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, typeChunk(parsed))

        assertEquals(1, rows.size, "type chunk renders as one row")
        assertNull(rows[0].editable, "non-code rows must not carry editability")
        assertTrue(rows[0].tokens.isNotEmpty(), "row should have content")
    }

    @Test
    fun code_chunk_splits_into_multiple_rows() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, codeChunk(parsed))

        // Expected layout (no locals because the function has none):
        //   1. (func $func0 ...) header
        //   2. i32.const 5         ← editable
        //   3. (synthetic end row for the closing 0x0B byte)
        //   4. ) close row
        assertTrue(rows.size >= 3, "expected at least 3 rows (header + body + close), got ${rows.size}")
    }

    @Test
    fun i32_const_row_is_tagged_editable_with_correct_metadata() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, codeChunk(parsed))

        val editableRow = rows.firstOrNull { it.editable != null }
        assertNotNull(editableRow, "expected at least one editable row in the function body")
        val info = editableRow.editable!!

        assertTrue(info.op is I32.Const, "expected I32.Const, got ${info.op?.let { it::class.simpleName }}")
        assertEquals(24L, info.opStart, "i32.const opcode lives at byte offset 24")
        assertEquals(2, info.opEncodedLength, "i32.const + 1-byte SLEB(5) = 2 bytes")
        assertEquals("i32.const 5", info.watText, "watText is the editable mnemonic + immediate")
    }

    @Test
    fun header_locals_close_rows_are_not_editable() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, codeChunk(parsed))

        // The first row is the function header `(func $func0 …)` — not an
        // OpCode row, must not be tagged editable.
        assertNull(rows.first().editable, "function header row is not editable")

        // The last row is the closing `)` — also not editable.
        assertNull(rows.last().editable, "function close row is not editable")
    }

    /**
     * Same as [moduleBytes] but the function declares 2 i32 locals so the
     * editable `i32.const 5` row has a non-zero `params + locals` count to
     * assert against.
     *
     * Code entry layout:
     *   `04 02 ; entry size 4 → 6, count=1`
     *   `01 02 7F          ; 1 local group: count=2, type=i32`
     *   `41 05 0B          ; i32.const 5, end`
     */
    private val moduleWithLocalsBytes: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x0A, 0x08, 0x01, 0x06, 0x01, 0x02, 0x7F, 0x41, 0x05, 0x0B,
    )

    private fun parsedWithLocals(): WasmParsedBinary {
        val snapshot = binaryDocumentOf(moduleWithLocalsBytes).snapshot()
        val provider = WasmBinaryProvider(snapshot.toCommonByteBuffer())
        return WasmParsedBinary.fromProvider(provider, snapshot)
    }

    /**
     * Regression — every editable opcode row in a code chunk must carry the
     * function's `params + locals` count, not just rows whose original op is
     * already a `local.{get,set,tee}`. Without this, the user can edit an
     * `i32.const`-style row into `local.get 10` (out-of-range) and the
     * encoder lets it through because `info.localCount == null` disables
     * semantic validation.
     */
    @Test
    fun editable_opcode_row_always_carries_localCount() {
        val parsed = parsedWithLocals()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, codeChunk(parsed))

        val info = rows.first { it.editable?.kind == EditableRowInfo.Kind.OPCODE }.editable!!
        assertTrue(info.op is I32.Const, "fixture row is i32.const, not a local-access op")
        assertEquals(2, info.localCount, "i32.const row should still carry params+locals=2")
    }

    /**
     * End-to-end regression for the user-reported scenario: a function with
     * `(local i32 i32)` had its `i32.const 5` row edited to `local.get 10`
     * and the editor accepted it. With the renderer always passing
     * `localCount`, the encoder now rejects the out-of-range index.
     */
    @Test
    fun editing_non_local_op_to_out_of_range_local_get_is_rejected() {
        val parsed = parsedWithLocals()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, codeChunk(parsed))
        val info = rows.first { it.editable?.kind == EditableRowInfo.Kind.OPCODE }.editable!!

        val result = EditableRowEncoder.encode(
            op = info.op!!,
            opStartOffset = info.opStart,
            opEncodedLength = info.opEncodedLength,
            text = "local.get 10",
            localCount = info.localCount,
            cascade = info.cascade,
        )
        check(result is RowEncodeResult.Error) {
            "expected out-of-range error for local.get 10 when localCount=2, got $result"
        }
        assertTrue(
            "out of range" in result.message,
            "expected 'out of range' in error message, got '${result.message}'",
        )
    }

    @Test
    fun rendered_row_text_strips_offset_gutter_and_hex_column() {
        val parsed = parsed()
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, codeChunk(parsed))

        val info = rows.first { it.editable != null }.editable!!
        // The row's tokens still include the offset gutter ("0x...") and hex
        // bytes column, but watText must not contain either — those are
        // structural decoration, not part of the editable WAT.
        assertTrue(!info.watText.startsWith("0x"), "watText should not start with offset")
        assertTrue("41" !in info.watText && "05" !in info.watText.split(' ').first(),
            "watText should not contain raw hex bytes from the gutter")
        assertEquals("i32.const 5", info.watText)
    }
}
