package org.jetbrains.hexana.providers.format

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.document.format.BinaryChunk
import org.jetbrains.hexana.document.io.toCommonByteBuffer
import org.jetbrains.hexana.document.journal.JournalOp
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider

/**
 * End-to-end tests for the editable-WAT mechanics: apply [EditableRowEncoder]
 * output to the original bytes, then re-parse and verify the rebuilt module
 * is still structurally valid and that sections downstream of the edited
 * function (Data, in this fixture) still parse with their payloads intact.
 *
 * The pipeline mirrors the panel's flow:
 *
 * 1. Build [WasmParsedBinary] over the fixture.
 * 2. Ask [WasmHexanaWatRenderer.renderRows] for the editable row info — gives
 *    us the same [EditableRowInfo] (op, opStart, cascade context, …) the panel
 *    feeds to the encoder.
 * 3. Call the appropriate [EditableRowEncoder] entry point.
 * 4. Splice the resulting [JournalOp]s into the original byte array.
 * 5. Re-parse the bytes and assert: top-level [WasmBinaryProvider.wasmModule]
 *    is non-null, [WasmBinaryProvider.sizeAnalysis] builds (this is the
 *    operation that throws `Unknown data record type: …` when sections shift
 *    incorrectly), and the data section's payload byte is preserved.
 */
class EditableDocumentRoundTripTest {

    /**
     * Layout (41 bytes total). The data section's last byte (0x7F) is the
     * payload-preservation marker — if any size cascade is wrong, the data
     * section either fails to parse or carries a different payload byte.
     *
     * ```
     *  00 61 73 6D 01 00 00 00          ; header                            (off 0-7)
     *  01 05 01 60 00 01 7F             ; type[0] = (func () -> i32)        (off 8-14)
     *  03 02 01 00                      ; function[0] uses type 0            (off 15-18)
     *  05 03 01 00 01                   ; memory[0] min=1                    (off 19-23)
     *  0A 06 01 04 00 41 05 0B          ; code[0] body=4 (i32.const 5; end)  (off 24-31)
     *  0B 07 01 00 41 00 0B 01 7F       ; data[0] mem 0 @ i32.const 0 -> 7F  (off 32-40)
     * ```
     *
     * i32.const 5 opcode at byte 29, encoded length 2 (`0x41 0x05`).
     */
    private val moduleBytes: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x05, 0x0B,
        0x0B, 0x07, 0x01, 0x00, 0x41, 0x00, 0x0B, 0x01, 0x7F.toByte(),
    )

    private fun parsed(bytes: ByteArray = moduleBytes): WasmParsedBinary {
        val snapshot = binaryDocumentOf(bytes.toCommonByteBuffer()).snapshot()
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

    /**
     * The first opcode row's [EditableRowInfo] — `i32.const 5` for the
     * default fixture. Carries the cascade context the renderer computed
     * from the live structural metadata, so the encoder can emit the right
     * funcSize / sectionSize Replace ops on length-changing edits.
     */
    private fun firstOpcodeInfo(parsed: WasmParsedBinary): EditableRowInfo {
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, codeChunk(parsed))
        return rows.first { it.editable?.kind == EditableRowInfo.Kind.OPCODE }.editable!!
    }

    /**
     * Splice [ops] into [initial] in declaration order, matching how
     * `commitRowEdit` feeds them into the live [EditableBinaryDocument]. Used
     * to produce the post-edit byte array we can re-parse.
     */
    private fun applyOps(initial: ByteArray, ops: List<JournalOp>): ByteArray {
        val buf = initial.toMutableList()
        for (op in ops) {
            when (op) {
                is JournalOp.Insert -> {
                    val data = Base64.decode(op.data).toList()
                    buf.addAll(op.offset.toInt(), data)
                }
                is JournalOp.Replace -> {
                    val data = Base64.decode(op.data).toList()
                    repeat((op.end - op.start).toInt()) { buf.removeAt(op.start.toInt()) }
                    buf.addAll(op.start.toInt(), data)
                }
                is JournalOp.Delete -> {
                    repeat((op.end - op.start).toInt()) { buf.removeAt(op.start.toInt()) }
                }
            }
        }
        return buf.toByteArray()
    }

    /**
     * Re-parses [bytes] and runs the same downstream pipeline the panel
     * exercises (sizeAnalysis, functions resolution). Throws if anything is
     * structurally broken.
     */
    private fun reparseStrict(bytes: ByteArray): WasmBinaryProvider {
        val provider = WasmBinaryProvider(binaryDocumentOf(bytes.toCommonByteBuffer()).toCommonByteBuffer())
        val module = provider.wasmModule ?: error("re-parse returned null wasmModule")
        assertNotNull(module.codeSection, "rebuilt module must keep its code section")
        assertNotNull(module.dataSection, "rebuilt module must keep its data section")
        assertNotNull(provider.sizeAnalysis, "sizeAnalysis must build without throwing")
        return provider
    }

    /**
     * Decodes the single data payload byte from a parsed module. We check
     * this byte after every edit — if any cascade dropped or duplicated
     * bytes, the data section's `byte-array length` ULEB or the payload
     * byte itself ends up at a wrong offset, and this read returns the
     * wrong byte (or the section fails to parse).
     */
    private fun firstDataPayload(provider: WasmBinaryProvider): Byte {
        val module = provider.wasmModule ?: error("no module")
        val section = module.dataSection ?: error("no data section")
        // Walk: count ULEB, memidx ULEB, init-expr (i32.const N; end), byte-array (len ULEB, bytes).
        val buf = provider.bytes
        buf.position(section.contentOffset)
        val count = buf.getULEB128().toInt()
        check(count == 1) { "fixture has 1 data entry, parsed $count" }
        buf.getULEB128()                  // memidx
        check(buf.get() == 0x41.toByte()) // i32.const
        buf.getULEB128()                  // const value
        check(buf.get() == 0x0B.toByte()) // end
        val byteArrayLen = buf.getULEB128().toInt()
        check(byteArrayLen == 1) { "fixture's data byte array has 1 byte, parsed $byteArrayLen" }
        return buf.get()
    }

    // -- Tests ----------------------------------------------------------

    /**
     * `i32.const 5` → `i32.const 7`. Both encode to a 1-byte SLEB128, so
     * the edit is length-preserving — one [JournalOp.Replace], no cascade.
     * After applying, total length is unchanged, the immediate byte at
     * offset 30 is `0x07`, and the data section's payload byte is still
     * `0x7F` at its original offset.
     */
    @Test
    fun length_preserving_replace_keeps_module_valid_and_data_intact() {
        val parsed = parsed()
        val info = firstOpcodeInfo(parsed)
        val result = EditableRowEncoder.encode(
            op = info.op!!,
            opStartOffset = info.opStart,
            opEncodedLength = info.opEncodedLength,
            text = "i32.const 7",
            localCount = info.localCount,
            cascade = info.cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "encode failed: $result")
        assertEquals(1, ok.ops.size, "length-preserving edit must emit a single Replace op")

        val newBytes = applyOps(moduleBytes, ok.ops)
        assertEquals(moduleBytes.size, newBytes.size, "length-preserving edit must not change byte count")
        assertEquals(0x07.toByte(), newBytes[30], "new immediate must be 0x07")

        val rebuilt = reparseStrict(newBytes)
        assertEquals(0x7F.toByte(), firstDataPayload(rebuilt), "data payload must be preserved")
    }

    /**
     * `i32.const 5` → `i32.const 200`. ULEB widens from 1 byte (`0x05`) to
     * 2 bytes (`0xC8 0x01`), so the encoder emits the row Replace plus
     * funcSize + sectionSize Replaces. Total length grows by 1; the data
     * section's payload byte ends up shifted by 1 and must still parse to
     * the same value.
     */
    @Test
    fun length_widening_replace_cascades_and_preserves_data_payload() {
        val parsed = parsed()
        val info = firstOpcodeInfo(parsed)
        val result = EditableRowEncoder.encode(
            op = info.op!!,
            opStartOffset = info.opStart,
            opEncodedLength = info.opEncodedLength,
            text = "i32.const 200",
            localCount = info.localCount,
            cascade = info.cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "encode failed: $result")
        assertEquals(3, ok.ops.size, "cascade must emit row + funcSize + sectionSize ops")

        val newBytes = applyOps(moduleBytes, ok.ops)
        assertEquals(moduleBytes.size + 1, newBytes.size, "widening edit grew the module by 1 byte")

        val rebuilt = reparseStrict(newBytes)
        assertEquals(0x7F.toByte(), firstDataPayload(rebuilt),
            "data payload must survive the section shift")
        assertEquals(1, rebuilt.codeOffsets.size, "code section still has one entry")
        // body is now `00 41 C8 01 0B` = 5 bytes; funcSize ULEB is still 1 byte
        // (value 5), so the entry as a whole is 6 bytes.
        assertEquals(6, rebuilt.codeOffsets[0].size,
            "code entry size = 1-byte funcSize prefix + 5-byte body")
    }

    /**
     * Inserting a `nop` instruction after the row grows the function body
     * by 1 byte. Like the widening test, the cascade must update funcSize +
     * sectionSize and shift the data section, but here the edit is an
     * [JournalOp.Insert] rather than a Replace.
     */
    @Test
    fun insert_in_function_body_cascades_and_preserves_data_payload() {
        val parsed = parsed()
        val info = firstOpcodeInfo(parsed)
        val result = EditableRowEncoder.encodeInsert(
            insertOffset = info.opStart + info.opEncodedLength.toLong(),
            text = "nop",
            localCount = info.localCount,
            cascade = info.cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "encode failed: $result")

        val newBytes = applyOps(moduleBytes, ok.ops)
        assertEquals(moduleBytes.size + 1, newBytes.size, "inserted nop grew the module by 1 byte")

        val rebuilt = reparseStrict(newBytes)
        assertEquals(0x7F.toByte(), firstDataPayload(rebuilt), "data payload must survive the insert")
        assertEquals(1, rebuilt.codeOffsets.size, "still one code entry")
        // Original body 4 bytes + inserted 1-byte nop = 5 bytes body, 6 bytes entry.
        assertEquals(6, rebuilt.codeOffsets[0].size)
    }

    /**
     * Deleting the `i32.const 5` row (text blank + Enter UX) shrinks the
     * body by 2 bytes and cascades funcSize + sectionSize. Result must
     * re-parse with a 1-entry code section; the data section payload is
     * preserved at its new earlier offset.
     */
    @Test
    fun row_delete_cascades_and_preserves_data_payload() {
        val parsed = parsed()
        val info = firstOpcodeInfo(parsed)
        val result = EditableRowEncoder.encodeDelete(
            rowStart = info.opStart,
            rowLength = info.opEncodedLength,
            cascade = info.cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "encode failed: $result")

        val newBytes = applyOps(moduleBytes, ok.ops)
        assertEquals(moduleBytes.size - 2, newBytes.size, "delete removed 2 bytes (i32.const + imm)")

        val rebuilt = reparseStrict(newBytes)
        assertEquals(0x7F.toByte(), firstDataPayload(rebuilt), "data payload must survive the delete")
        assertEquals(1, rebuilt.codeOffsets.size, "code section still has one entry")
        // Original body 4 - 2 = 2 bytes (just locals 0x00 + end 0x0B); entry = 1 + 2 = 3 bytes.
        assertEquals(3, rebuilt.codeOffsets[0].size)
    }

    /**
     * Same shape as [moduleBytes] but the function declares 1 i32 local, so
     * the renderer emits a LOCALS row whose `EditableRowInfo` we can edit.
     * The 0-locals form in [moduleBytes] collapses the locals declaration
     * into a single byte and the renderer omits the row entirely.
     *
     * ```
     *  0A 0A 01 08 01 01 7F 41 05 0B   ; code[0] body=8: 1 group(1 i32), i32.const 5, end
     * ```
     *
     * Code body grows by 2 bytes vs the no-locals fixture, so section/data
     * offsets shift by 2.
     */
    private val moduleBytesWithLocals: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x0A, 0x08, 0x01, 0x06, 0x01, 0x01, 0x7F, 0x41, 0x05, 0x0B,
        0x0B, 0x07, 0x01, 0x00, 0x41, 0x00, 0x0B, 0x01, 0x7F.toByte(),
    )

    /**
     * Editing the `(local i32)` declaration row to add a second i32 cascades
     * funcSize + sectionSize. Locals decl shape changes from `0x01 0x01 0x7F`
     * (1 group, count=1, i32) to `0x01 0x02 0x7F` (1 group, count=2, i32) —
     * length-preserving in this case, but in general the encoder can grow it.
     * Re-parse must keep the data payload intact and surface the new local
     * count.
     */
    @Test
    fun locals_declaration_edit_keeps_module_valid_and_data_intact() {
        val parsed = parsed(moduleBytesWithLocals)
        val renderer = WasmHexanaWatRenderer(parsed)
        val rows = renderer.renderRows(parsed.snapshot, codeChunk(parsed))
        val localsInfo = rows.firstOrNull { it.editable?.kind == EditableRowInfo.Kind.LOCALS }?.editable
            ?: error("expected a LOCALS row in moduleBytesWithLocals")

        val result = EditableRowEncoder.encodeLocals(
            opStartOffset = localsInfo.opStart,
            opEncodedLength = localsInfo.opEncodedLength,
            text = "(local i32 i32)",
            cascade = localsInfo.cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "encode failed: $result")

        val newBytes = applyOps(moduleBytesWithLocals, ok.ops)
        val rebuilt = reparseStrict(newBytes)
        assertEquals(0x7F.toByte(), firstDataPayload(rebuilt), "data payload survives locals edit")
        val codeRecord = rebuilt.parseCodeRecord(0)
            ?: error("expected code entry 0 to parse after locals edit")
        assertEquals(2, codeRecord.locals.sumOf { it.local }, "two i32 locals after the edit")
    }

    /**
     * Function-section bytes sit BEFORE the code section, so a code edit
     * must never disturb them. Locks in that invariant — after a
     * length-widening edit, the typeidx list reads identically.
     */
    @Test
    fun function_section_typeidxs_unchanged_after_code_edit() {
        val parsed = parsed()
        val info = firstOpcodeInfo(parsed)
        val result = EditableRowEncoder.encode(
            op = info.op!!,
            opStartOffset = info.opStart,
            opEncodedLength = info.opEncodedLength,
            text = "i32.const 200",
            localCount = info.localCount,
            cascade = info.cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result)
        val newBytes = applyOps(moduleBytes, ok.ops)

        val original = parsed.provider
        val rebuilt = reparseStrict(newBytes)
        val originalTypeIdxs = with(original.wasmModule!!.functionSection!!) {
            original.bytes.parseFunctions()
        }
        val rebuiltTypeIdxs = with(rebuilt.wasmModule!!.functionSection!!) {
            rebuilt.bytes.parseFunctions()
        }
        assertEquals(originalTypeIdxs, rebuiltTypeIdxs, "function-section typeidxs are immutable to code edits")
    }

    /**
     * Function-section per-entry ranges (offset + size + typeidx) — same
     * shape after the edit. Verifies the new [WasmSectionFunctions.scanRanges]
     * stays self-consistent and the section's content offset isn't shifted
     * by a downstream code edit.
     */
    @Test
    fun function_section_ranges_unchanged_after_code_edit() {
        val parsed = parsed()
        val info = firstOpcodeInfo(parsed)
        val result = EditableRowEncoder.encode(
            op = info.op!!,
            opStartOffset = info.opStart,
            opEncodedLength = info.opEncodedLength,
            text = "i32.const 200",
            localCount = info.localCount,
            cascade = info.cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result)
        val newBytes = applyOps(moduleBytes, ok.ops)

        val original = parsed.provider
        val rebuilt = reparseStrict(newBytes)
        val origRanges = with(original.wasmModule!!.functionSection!!) {
            original.bytes.scanRanges()
        }
        val rebuiltRanges = with(rebuilt.wasmModule!!.functionSection!!) {
            rebuilt.bytes.scanRanges()
        }
        assertEquals(origRanges, rebuiltRanges, "function-section per-entry ranges must be invariant")
    }
}
