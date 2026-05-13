package org.jetbrains.hexana.providers.format

import kotlin.io.encoding.Base64
import org.jetbrains.hexana.document.journal.JournalOp
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Per-family encoder tests. Each test fixes the input op + edit text +
 * expected byte sequence, asserting that the encoder emits the expected
 * `JournalOp.Replace`. Boundary cases (LEB128 widths, mnemonic mismatch,
 * length cascade detection, MemArg parsing) get their own tests.
 */
class EditableRowEncoderTest {

    private fun bytes(vararg vs: Int): ByteArray = ByteArray(vs.size) { vs[it].toByte() }

    /** Asserts encode succeeds with one Replace op containing exactly [expected] bytes. */
    private fun assertEncodes(
        op: org.jetbrains.hexana.wasm.opcodes.OpCode,
        opStart: Long,
        opLen: Int,
        text: String,
        expected: ByteArray,
    ) {
        val result = EditableRowEncoder.encode(
            op = op,
            opStartOffset = opStart,
            opEncodedLength = opLen,
            text = text,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "encode failed: $result")
        assertEquals(1, ok.ops.size)
        val rep = assertIs<JournalOp.Replace>(ok.ops[0])
        assertEquals(opStart, rep.start)
        assertEquals(opStart + opLen.toLong(), rep.end)
        assertContentEquals(expected, Base64.decode(rep.data))
    }

    private fun assertError(
        op: org.jetbrains.hexana.wasm.opcodes.OpCode,
        opLen: Int,
        text: String,
        cascade: Boolean = false,
        messageContains: String? = null,
        localCount: Int? = null,
    ) {
        val result = EditableRowEncoder.encode(
            op = op,
            opStartOffset = 100L,
            opEncodedLength = opLen,
            text = text,
            localCount = localCount,
        )
        val err = assertIs<RowEncodeResult.Error>(result, message = "expected Error, got: $result")
        assertEquals(cascade, err.requiresCascade, "requiresCascade flag mismatch")
        if (messageContains != null) {
            assertTrue(messageContains in err.message, "message='${err.message}' missing '$messageContains'")
        }
    }

    // -- Numeric constants -------------------------------------------------

    @Test
    fun i32_const_zero_encodes_to_one_sleb_byte() {
        assertEncodes(
            op = I32.Const(value = 0),
            opStart = 100L,
            opLen = 2, // 0x41 + SLEB128(0)
            text = "i32.const 0",
            expected = bytes(0x41, 0x00),
        )
    }

    @Test
    fun i32_const_negative_encodes_with_sign_extension() {
        // SLEB128(-1) = 0x7F (one byte, sign bit set).
        assertEncodes(
            op = I32.Const(value = -1),
            opStart = 0L,
            opLen = 2,
            text = "i32.const -1",
            expected = bytes(0x41, 0x7F),
        )
    }

    @Test
    fun i32_const_64_fits_in_one_byte_sleb() {
        // SLEB128(64) = 0xC0 0x00 — two bytes (sign bit forces continuation).
        // SLEB128(63) = 0x3F — one byte.
        assertEncodes(
            op = I32.Const(value = 63),
            opStart = 0L,
            opLen = 2,
            text = "i32.const 63",
            expected = bytes(0x41, 0x3F),
        )
    }

    @Test
    fun i64_const_value_encoded_as_signed_leb() {
        assertEncodes(
            op = I64.Const(value = 0L),
            opStart = 0L,
            opLen = 2,
            text = "i64.const 0",
            expected = bytes(0x42, 0x00),
        )
    }

    @Test
    fun f32_const_pi_encodes_as_4_bytes_le() {
        val pi = 3.14f
        val bits = pi.toRawBits()
        assertEncodes(
            op = F32.Const(initValue = pi),
            opStart = 0L,
            opLen = 5, // 1 opcode + 4 IEEE bytes
            text = "f32.const 3.14",
            expected = bytes(0x43) + ByteArray(4) { i -> ((bits ushr (i * 8)) and 0xFF).toByte() },
        )
    }

    @Test
    fun f64_const_zero_encodes_as_8_bytes_le() {
        assertEncodes(
            op = F64.Const(initValue = 0.0),
            opStart = 0L,
            opLen = 9,
            text = "f64.const 0.0",
            expected = bytes(0x44, 0, 0, 0, 0, 0, 0, 0, 0),
        )
    }

    @Test
    fun f32_inf_and_negative_inf_parse() {
        assertEncodes(
            op = F32.Const(initValue = 0f),
            opStart = 0L,
            opLen = 5,
            text = "f32.const inf",
            expected = bytes(0x43, 0x00, 0x00, 0x80, 0x7F), // +inf
        )
        assertEncodes(
            op = F32.Const(initValue = 0f),
            opStart = 0L,
            opLen = 5,
            text = "f32.const -inf",
            expected = bytes(0x43, 0x00, 0x00, 0x80, 0xFF), // -inf
        )
    }

    // -- Variable / global access ------------------------------------------

    @Test
    fun local_get_encodes_uleb_index() {
        assertEncodes(
            op = OpCodes.Local.Get(value = 0u),
            opStart = 50L,
            opLen = 2,
            text = "local.get 0",
            expected = bytes(0x20, 0x00),
        )
        assertEncodes(
            op = OpCodes.Local.Get(value = 5u),
            opStart = 0L,
            opLen = 2,
            text = "local.get 5",
            expected = bytes(0x20, 0x05),
        )
    }

    @Test
    fun local_set_and_tee_use_distinct_opcodes() {
        assertEncodes(
            op = OpCodes.Local.Set(value = 0u),
            opStart = 0L, opLen = 2,
            text = "local.set 7",
            expected = bytes(0x21, 0x07),
        )
        assertEncodes(
            op = OpCodes.Local.Tee(value = 0u),
            opStart = 0L, opLen = 2,
            text = "local.tee 7",
            expected = bytes(0x22, 0x07),
        )
    }

    @Test
    fun global_get_set_use_distinct_opcodes() {
        assertEncodes(
            op = OpCodes.Global.Get(value = 0u),
            opStart = 0L, opLen = 2,
            text = "global.get 1",
            expected = bytes(0x23, 0x01),
        )
        assertEncodes(
            op = OpCodes.Global.Set(value = 0u),
            opStart = 0L, opLen = 2,
            text = "global.set 1",
            expected = bytes(0x24, 0x01),
        )
    }

    @Test
    fun call_and_branches_encode() {
        assertEncodes(
            op = OpCodes.Flow.Call(value = 0u),
            opStart = 0L, opLen = 2,
            text = "call 42",
            expected = bytes(0x10, 0x2A),
        )
        assertEncodes(
            op = OpCodes.Flow.Br(value = 0u),
            opStart = 0L, opLen = 2,
            text = "br 3",
            expected = bytes(0x0c, 0x03),
        )
        assertEncodes(
            op = OpCodes.Flow.BrIf(value = 0u),
            opStart = 0L, opLen = 2,
            text = "br_if 0",
            expected = bytes(0x0d, 0x00),
        )
    }

    // -- Memory load/store with MemArg -------------------------------------

    @Test
    fun memory_load_with_natural_alignment_omitted() {
        // i32.load natural align is 2 (4-byte access). Omitting the align
        // and offset terms means natural=2, offset=0 → encoded ULEBs are 02 00.
        assertEncodes(
            op = OpCodes.Memory.i32.Load(value = MemArg(align = 2, offset = 0)),
            opStart = 0L, opLen = 3,
            text = "i32.load",
            expected = bytes(0x28, 0x02, 0x00),
        )
    }

    @Test
    fun memory_load_with_offset_only() {
        assertEncodes(
            op = OpCodes.Memory.i32.Load(value = MemArg(align = 2, offset = 4)),
            opStart = 0L, opLen = 3,
            text = "i32.load offset=4",
            expected = bytes(0x28, 0x02, 0x04),
        )
    }

    @Test
    fun memory_load_with_align_override() {
        // align=2 (i.e. exponent 1) overrides natural=2 (exponent 2).
        assertEncodes(
            op = OpCodes.Memory.i32.Load(value = MemArg(align = 2, offset = 0)),
            opStart = 0L, opLen = 3,
            text = "i32.load align=2",
            expected = bytes(0x28, 0x01, 0x00),
        )
    }

    @Test
    fun memory_store_uses_distinct_opcode() {
        assertEncodes(
            op = OpCodes.Memory.i64.Store(value = MemArg(align = 3, offset = 0)),
            opStart = 0L, opLen = 3,
            text = "i64.store",
            expected = bytes(0x37, 0x03, 0x00),
        )
    }

    @Test
    fun memory_invalid_align_rejected() {
        assertError(
            op = OpCodes.Memory.i32.Load(value = MemArg(align = 2, offset = 0)),
            opLen = 3,
            text = "i32.load align=3",
            messageContains = "power of two",
        )
    }

    @Test
    fun memory_unknown_memarg_key_rejected() {
        assertError(
            op = OpCodes.Memory.i32.Load(value = MemArg(align = 2, offset = 0)),
            opLen = 3,
            text = "i32.load foo=1",
            messageContains = "unknown memarg key",
        )
    }

    // -- Local-index semantic validation -----------------------------------

    @Test
    fun local_get_index_within_count_accepted() {
        val result = EditableRowEncoder.encode(
            op = OpCodes.Local.Get(value = 0u),
            opStartOffset = 0L,
            opEncodedLength = 2,
            text = "local.get 4",
            localCount = 5,
        )
        assertIs<RowEncodeResult.Success>(result, message = "encode failed: $result")
    }

    @Test
    fun local_get_index_past_count_rejected() {
        assertError(
            op = OpCodes.Local.Get(value = 0u),
            opLen = 2,
            text = "local.get 10",
            localCount = 5,
            messageContains = "out of range",
        )
    }

    @Test
    fun local_get_at_count_boundary_rejected() {
        // Index 5 is OUT of range when localCount is 5 (valid indices 0..4).
        assertError(
            op = OpCodes.Local.Get(value = 0u),
            opLen = 2,
            text = "local.get 5",
            localCount = 5,
            messageContains = "out of range",
        )
    }

    @Test
    fun local_set_and_tee_share_validation() {
        assertError(
            op = OpCodes.Local.Set(value = 0u),
            opLen = 2,
            text = "local.set 99",
            localCount = 3,
            messageContains = "out of range",
        )
        assertError(
            op = OpCodes.Local.Tee(value = 0u),
            opLen = 2,
            text = "local.tee 99",
            localCount = 3,
            messageContains = "out of range",
        )
    }

    @Test
    fun local_get_unbounded_when_count_unknown() {
        // localCount = null → encoder skips semantic validation. Useful when
        // the renderer couldn't compute params + locals for some reason.
        val result = EditableRowEncoder.encode(
            op = OpCodes.Local.Get(value = 0u),
            opStartOffset = 0L,
            opEncodedLength = 2,
            text = "local.get 99",
            localCount = null,
        )
        assertIs<RowEncodeResult.Success>(result, message = "encode failed: $result")
    }

    // -- Length cascade detection ------------------------------------------

    @Test
    fun length_widening_without_cascade_context_returns_error() {
        // local.get 200 needs a 2-byte ULEB; original was 1-byte (local.get 5).
        // Without a CascadeContext the encoder still returns the cascade
        // error so the panel can surface it.
        assertError(
            op = OpCodes.Local.Get(value = 5u),
            opLen = 2,
            text = "local.get 200",
            cascade = true,
            messageContains = "no cascade context",
        )
    }

    @Test
    fun i32_const_widening_without_cascade_context_returns_error() {
        assertError(
            op = I32.Const(value = 5),
            opLen = 2,
            text = "i32.const 1024",
            cascade = true,
        )
    }

    // -- Length cascade success path ---------------------------------------

    private fun cascadeFor(
        funcSizePrefixStart: Long,
        funcSizePrefixLength: Int,
        currentBodyLength: Long,
        sectionSizePrefixStart: Long,
        sectionSizePrefixLength: Int,
        currentSectionLength: Long,
    ) = CascadeContext(
        funcSizePrefixStart = funcSizePrefixStart,
        funcSizePrefixLength = funcSizePrefixLength,
        currentBodyLength = currentBodyLength,
        sectionSizePrefixStart = sectionSizePrefixStart,
        sectionSizePrefixLength = sectionSizePrefixLength,
        currentSectionLength = currentSectionLength,
    )

    @Test
    fun cascade_emits_three_ops_when_row_widens_one_byte() {
        // Setup: local.get 5 (2-byte row at offset 24). Edit to local.get 200
        // (3-byte row). Body grows by 1, funcSize ULEB stays 1 byte (body
        // size 4 → 5, both fit in 1 byte), section size ULEB stays 1 byte.
        val cascade = cascadeFor(
            funcSizePrefixStart = 22L,
            funcSizePrefixLength = 1,
            currentBodyLength = 4L,
            sectionSizePrefixStart = 20L,
            sectionSizePrefixLength = 1,
            currentSectionLength = 6L,
        )
        val result = EditableRowEncoder.encode(
            op = OpCodes.Local.Get(value = 5u),
            opStartOffset = 24L,
            opEncodedLength = 2,
            text = "local.get 200",
            cascade = cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        assertEquals(3, ok.ops.size)

        val rowOp = assertIs<JournalOp.Replace>(ok.ops[0])
        assertEquals(24L, rowOp.start)
        assertEquals(26L, rowOp.end)
        // Decoded payload: 0x20 + ULEB128(200) = 0x20 0xC8 0x01 = 3 bytes.
        assertContentEquals(bytes(0x20, 0xC8, 0x01), Base64.decode(rowOp.data))

        val funcSizeOp = assertIs<JournalOp.Replace>(ok.ops[1])
        assertEquals(22L, funcSizeOp.start)
        assertEquals(23L, funcSizeOp.end)
        // New body length = 4 + 1 = 5. ULEB128(5) = 0x05.
        assertContentEquals(bytes(0x05), Base64.decode(funcSizeOp.data))

        val sectionSizeOp = assertIs<JournalOp.Replace>(ok.ops[2])
        assertEquals(20L, sectionSizeOp.start)
        assertEquals(21L, sectionSizeOp.end)
        // New section length = 6 + 1 (row delta) + 0 (funcSize unchanged) = 7.
        assertContentEquals(bytes(0x07), Base64.decode(sectionSizeOp.data))
    }

    @Test
    fun cascade_handles_funcSize_widening() {
        // Body grows from 127 → 130. funcSize ULEB widens 1 → 2 bytes.
        // Section size grows by row-delta + funcSize-delta = 3 + 1 = 4.
        val cascade = cascadeFor(
            funcSizePrefixStart = 100L,
            funcSizePrefixLength = 1,
            currentBodyLength = 127L,
            sectionSizePrefixStart = 90L,
            sectionSizePrefixLength = 1,
            currentSectionLength = 129L, // 1 (count) + 1 (funcSize) + 127 (body)
        )
        val result = EditableRowEncoder.encode(
            op = OpCodes.Local.Get(value = 5u),
            opStartOffset = 200L,
            opEncodedLength = 2,
            text = "local.get 16384", // ULEB128(16384) = 3 bytes (0x80 0x80 0x01)
            cascade = cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        assertEquals(3, ok.ops.size)

        // Row replace: 0x20 + ULEB128(16384) = 4 bytes.
        val rowOp = assertIs<JournalOp.Replace>(ok.ops[0])
        assertContentEquals(bytes(0x20, 0x80, 0x80, 0x01), Base64.decode(rowOp.data))

        // funcSize: new body = 127 + (4 - 2) = 129. ULEB128(129) = 2 bytes (0x81 0x01).
        val funcSizeOp = assertIs<JournalOp.Replace>(ok.ops[1])
        assertEquals(100L, funcSizeOp.start)
        assertEquals(101L, funcSizeOp.end)
        assertContentEquals(bytes(0x81, 0x01), Base64.decode(funcSizeOp.data))

        // sectionSize: new section = 129 + 2 (row delta) + 1 (funcSize delta) = 132.
        // ULEB128(132) = 2 bytes (0x84 0x01).
        val sectionSizeOp = assertIs<JournalOp.Replace>(ok.ops[2])
        assertContentEquals(bytes(0x84, 0x01), Base64.decode(sectionSizeOp.data))
    }

    @Test
    fun cascade_i32_const_5_to_200_byte_exact() {
        // Exact repro of a length-widening edit a user reported. Original
        // row is `i32.const 5` (2 bytes: 0x41 0x05). User types
        // `i32.const 200` (3 bytes: 0x41 0xC8 0x01). Body grows by 1;
        // funcSize stays 1 byte (4 → 5); section size stays 1 byte (6 → 7).
        // Expected: 3 ops with byte-exact payloads.
        val cascade = cascadeFor(
            funcSizePrefixStart = 22L,
            funcSizePrefixLength = 1,
            currentBodyLength = 4L,
            sectionSizePrefixStart = 20L,
            sectionSizePrefixLength = 1,
            currentSectionLength = 6L,
        )
        val result = EditableRowEncoder.encode(
            op = I32.Const(value = 5),
            opStartOffset = 24L,
            opEncodedLength = 2,
            text = "i32.const 200",
            cascade = cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        assertEquals(3, ok.ops.size)

        val rowOp = assertIs<JournalOp.Replace>(ok.ops[0])
        assertEquals(24L, rowOp.start)
        assertEquals(26L, rowOp.end)
        assertContentEquals(bytes(0x41, 0xC8, 0x01), Base64.decode(rowOp.data))

        val funcOp = assertIs<JournalOp.Replace>(ok.ops[1])
        assertEquals(22L, funcOp.start)
        assertEquals(23L, funcOp.end)
        assertContentEquals(bytes(0x05), Base64.decode(funcOp.data))

        val secOp = assertIs<JournalOp.Replace>(ok.ops[2])
        assertEquals(20L, secOp.start)
        assertEquals(21L, secOp.end)
        assertContentEquals(bytes(0x07), Base64.decode(secOp.data))
    }

    @Test
    fun cascade_handles_row_shrinking() {
        // Edit a 3-byte row down to 2 bytes (i32.const 1024 → i32.const 5).
        // Body shrinks by 1; funcSize and sectionSize update accordingly.
        val cascade = cascadeFor(
            funcSizePrefixStart = 10L,
            funcSizePrefixLength = 1,
            currentBodyLength = 5L,
            sectionSizePrefixStart = 5L,
            sectionSizePrefixLength = 1,
            currentSectionLength = 7L,
        )
        val result = EditableRowEncoder.encode(
            op = I32.Const(value = 1024),
            opStartOffset = 12L,
            opEncodedLength = 3, // 0x41 + 2-byte SLEB(1024)
            text = "i32.const 5",
            cascade = cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        assertEquals(3, ok.ops.size)

        // Row: 0x41 + SLEB(5) = 2 bytes.
        val rowOp = assertIs<JournalOp.Replace>(ok.ops[0])
        assertContentEquals(bytes(0x41, 0x05), Base64.decode(rowOp.data))

        // funcSize: 5 + (-1) = 4 → ULEB(4) = 0x04.
        val funcSizeOp = assertIs<JournalOp.Replace>(ok.ops[1])
        assertContentEquals(bytes(0x04), Base64.decode(funcSizeOp.data))

        // sectionSize: 7 + (-1) + 0 = 6 → ULEB(6) = 0x06.
        val sectionSizeOp = assertIs<JournalOp.Replace>(ok.ops[2])
        assertContentEquals(bytes(0x06), Base64.decode(sectionSizeOp.data))
    }

    // -- Mnemonic mismatch -------------------------------------------------

    // -- Insert new instruction row (D.3) ----------------------------------

    @Test
    fun encode_insert_emits_three_ops() {
        // Body grows by 2 (i32.const 5 = 0x41 0x05); funcSize stays 1 byte
        // (0x05 → 0x07), section size grows from 7 to 9.
        val cascade = CascadeContext(
            funcSizePrefixStart = 22L,
            funcSizePrefixLength = 1,
            currentBodyLength = 5L,
            sectionSizePrefixStart = 20L,
            sectionSizePrefixLength = 1,
            currentSectionLength = 7L,
        )
        val result = EditableRowEncoder.encodeInsert(
            insertOffset = 27L,
            text = "i32.const 5",
            cascade = cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        assertEquals(3, ok.ops.size)

        // Op 1 must be Insert (not Replace) at the requested offset.
        val insertOp = assertIs<JournalOp.Insert>(ok.ops[0])
        assertEquals(27L, insertOp.offset)
        assertContentEquals(bytes(0x41, 0x05), Base64.decode(insertOp.data))

        // funcSize: 5 + 2 = 7 → ULEB(7) = 0x07.
        val funcOp = assertIs<JournalOp.Replace>(ok.ops[1])
        assertContentEquals(bytes(0x07), Base64.decode(funcOp.data))

        // sectionSize: 7 + 2 + 0 = 9 → ULEB(9) = 0x09.
        val secOp = assertIs<JournalOp.Replace>(ok.ops[2])
        assertContentEquals(bytes(0x09), Base64.decode(secOp.data))
    }

    @Test
    fun encode_insert_handles_funcSize_widening() {
        // Body crosses the 127→128 boundary, funcSize widens 1 → 2 bytes.
        val cascade = CascadeContext(
            funcSizePrefixStart = 50L,
            funcSizePrefixLength = 1,
            currentBodyLength = 127L,
            sectionSizePrefixStart = 40L,
            sectionSizePrefixLength = 1,
            currentSectionLength = 129L,
        )
        val result = EditableRowEncoder.encodeInsert(
            insertOffset = 100L,
            text = "i32.add", // 1 byte: 0x6A
            cascade = cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        // funcSize: 127 + 1 = 128 → ULEB(128) = 0x80 0x01 (2 bytes).
        val funcOp = assertIs<JournalOp.Replace>(ok.ops[1])
        assertContentEquals(bytes(0x80, 0x01), Base64.decode(funcOp.data))
        // sectionSize: 129 + 1 + 1 = 131 → ULEB(131) = 0x83 0x01.
        val secOp = assertIs<JournalOp.Replace>(ok.ops[2])
        assertContentEquals(bytes(0x83, 0x01), Base64.decode(secOp.data))
    }

    @Test
    fun encode_insert_without_cascade_returns_error() {
        val result = EditableRowEncoder.encodeInsert(
            insertOffset = 0L,
            text = "i32.add",
            cascade = null,
        )
        val err = assertIs<RowEncodeResult.Error>(result)
        assertEquals(true, err.requiresCascade)
    }

    @Test
    fun encode_insert_unknown_mnemonic_rejected() {
        val cascade = CascadeContext(0L, 1, 1L, 0L, 1, 1L)
        val result = EditableRowEncoder.encodeInsert(
            insertOffset = 0L,
            text = "totally.bogus",
            cascade = cascade,
        )
        val err = assertIs<RowEncodeResult.Error>(result)
        assertEquals(false, err.requiresCascade)
    }

    @Test
    fun encode_insert_empty_text_rejected() {
        val cascade = CascadeContext(0L, 1, 1L, 0L, 1, 1L)
        val result = EditableRowEncoder.encodeInsert(
            insertOffset = 0L,
            text = "   ",
            cascade = cascade,
        )
        assertIs<RowEncodeResult.Error>(result)
    }

    // -- Locals declaration (D.2) ------------------------------------------

    @Test
    fun locals_empty_encodes_to_zero_groups() {
        val result = EditableRowEncoder.encodeLocals(
            opStartOffset = 0L,
            opEncodedLength = 1, // current `(local)` has 0 groups → 1-byte ULEB(0)
            text = "(local)",
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        val rep = assertIs<JournalOp.Replace>(ok.ops.single())
        assertContentEquals(bytes(0x00), Base64.decode(rep.data))
    }

    @Test
    fun locals_single_type_encodes_to_one_group() {
        // (local i32) = 1 group of count 1 of type 0x7F
        // bytes: 01 (group count) 01 (run count) 7F (i32) = 3 bytes total
        val result = EditableRowEncoder.encodeLocals(
            opStartOffset = 0L,
            opEncodedLength = 3,
            text = "(local i32)",
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        val rep = assertIs<JournalOp.Replace>(ok.ops.single())
        assertContentEquals(bytes(0x01, 0x01, 0x7F), Base64.decode(rep.data))
    }

    @Test
    fun locals_RLE_compresses_consecutive_same_types() {
        // (local i32 i32 i32 i64) → 2 groups: (3, i32), (1, i64)
        // bytes: 02  03 7F  01 7E  = 5 bytes
        val result = EditableRowEncoder.encodeLocals(
            opStartOffset = 0L,
            opEncodedLength = 5,
            text = "(local i32 i32 i32 i64)",
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        val rep = assertIs<JournalOp.Replace>(ok.ops.single())
        assertContentEquals(bytes(0x02, 0x03, 0x7F, 0x01, 0x7E), Base64.decode(rep.data))
    }

    @Test
    fun locals_with_all_four_numeric_types() {
        // (local i32 i64 f32 f64) → 4 groups, each count 1
        // bytes: 04  01 7F  01 7E  01 7D  01 7C  = 9 bytes
        val result = EditableRowEncoder.encodeLocals(
            opStartOffset = 0L,
            opEncodedLength = 9,
            text = "(local i32 i64 f32 f64)",
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        val rep = assertIs<JournalOp.Replace>(ok.ops.single())
        assertContentEquals(
            bytes(0x04, 0x01, 0x7F, 0x01, 0x7E, 0x01, 0x7D, 0x01, 0x7C),
            Base64.decode(rep.data),
        )
    }

    @Test
    fun locals_invalid_syntax_rejected() {
        val noOpenParen = EditableRowEncoder.encodeLocals(0L, 3, "local i32)")
        assertIs<RowEncodeResult.Error>(noOpenParen)

        val unknownType = EditableRowEncoder.encodeLocals(0L, 3, "(local unknown)")
        assertIs<RowEncodeResult.Error>(unknownType)

        val notLocal = EditableRowEncoder.encodeLocals(0L, 3, "(memory i32)")
        assertIs<RowEncodeResult.Error>(notLocal)
    }

    @Test
    fun locals_length_change_without_cascade_returns_error() {
        // Original locals row is 3 bytes (e.g. (local i32)); user adds a
        // type — new encoding is 4 bytes. Without cascade context, error.
        val result = EditableRowEncoder.encodeLocals(
            opStartOffset = 50L,
            opEncodedLength = 3,
            text = "(local i32 i64)", // becomes 5 bytes: 02 01 7F 01 7E
            cascade = null,
        )
        val err = assertIs<RowEncodeResult.Error>(result)
        assertEquals(true, err.requiresCascade)
    }

    @Test
    fun locals_length_change_with_cascade_emits_three_ops() {
        // (local i32) [3 bytes] → (local i32 i64 i64) [5 bytes: 02 01 7F 02 7E]
        // Body grows by 2.
        val cascade = CascadeContext(
            funcSizePrefixStart = 100L,
            funcSizePrefixLength = 1,
            currentBodyLength = 5L,
            sectionSizePrefixStart = 90L,
            sectionSizePrefixLength = 1,
            currentSectionLength = 7L,
        )
        val result = EditableRowEncoder.encodeLocals(
            opStartOffset = 101L,
            opEncodedLength = 3,
            text = "(local i32 i64 i64)",
            cascade = cascade,
        )
        val ok = assertIs<RowEncodeResult.Success>(result, message = "got: $result")
        assertEquals(3, ok.ops.size)
        val rowOp = assertIs<JournalOp.Replace>(ok.ops[0])
        assertContentEquals(bytes(0x02, 0x01, 0x7F, 0x02, 0x7E), Base64.decode(rowOp.data))
        val funcOp = assertIs<JournalOp.Replace>(ok.ops[1])
        assertContentEquals(bytes(0x07), Base64.decode(funcOp.data)) // 5 + 2 = 7
        val secOp = assertIs<JournalOp.Replace>(ok.ops[2])
        assertContentEquals(bytes(0x09), Base64.decode(secOp.data))  // 7 + 2 + 0 = 9
    }

    // -- Mnemonic-changing edits (D.1) -------------------------------------

    @Test
    fun local_get_can_swap_to_local_set() {
        // D.1: encoder dispatches on the typed mnemonic, so swapping op
        // kinds within the editable subset works — `local.get 5` →
        // `local.set 5` produces the local.set opcode (0x21).
        assertEncodes(
            op = OpCodes.Local.Get(value = 5u),
            opStart = 0L,
            opLen = 2,
            text = "local.set 5",
            expected = bytes(0x21, 0x05),
        )
    }

    @Test
    fun local_get_can_swap_to_call_within_subset() {
        // Cross-family swap (variable access → direct call) — both encode
        // as opcode + ULEB128(idx), but the opcode byte differs.
        assertEncodes(
            op = OpCodes.Local.Get(value = 5u),
            opStart = 0L,
            opLen = 2,
            text = "call 7",
            expected = bytes(0x10, 0x07),
        )
    }

    @Test
    fun typo_rejected() {
        assertError(
            op = OpCodes.Local.Get(value = 5u),
            opLen = 2,
            text = "lokal.get 5",
            messageContains = "unknown or unsupported mnemonic",
        )
    }

    // -- Parse errors / range -----------------------------------------------

    @Test
    fun missing_immediate_rejected() {
        assertError(
            op = I32.Const(value = 0),
            opLen = 2,
            text = "i32.const",
            messageContains = "expects one immediate",
        )
    }

    @Test
    fun extra_tokens_rejected() {
        assertError(
            op = I32.Const(value = 0),
            opLen = 2,
            text = "i32.const 5 6",
            messageContains = "expects one immediate",
        )
    }

    @Test
    fun out_of_range_i32_rejected() {
        assertError(
            op = I32.Const(value = 0),
            opLen = 2,
            text = "i32.const 99999999999",
            messageContains = "invalid i32 value",
        )
    }

    @Test
    fun unparseable_index_rejected() {
        assertError(
            op = OpCodes.Local.Get(value = 0u),
            opLen = 2,
            text = "local.get foo",
            messageContains = "invalid u32 index",
        )
    }

    @Test
    fun empty_text_rejected() {
        assertError(
            op = I32.Const(value = 0),
            opLen = 2,
            text = "   ",
            messageContains = "empty",
        )
    }

    // -- D.1: parametric / no-immediate ops --------------------------------

    @Test
    fun drop_encodes_to_single_byte() {
        assertEncodes(
            op = OpCodes.ParametricInstructions.Drop,
            opStart = 0L, opLen = 1,
            text = "drop",
            expected = bytes(0x1A),
        )
    }

    @Test
    fun unreachable_nop_select_return_encode() {
        assertEncodes(
            op = OpCodes.ParametricInstructions.Unreachable,
            opStart = 0L, opLen = 1,
            text = "unreachable",
            expected = bytes(0x00),
        )
        assertEncodes(
            op = OpCodes.ParametricInstructions.Nop,
            opStart = 0L, opLen = 1,
            text = "nop",
            expected = bytes(0x01),
        )
        assertEncodes(
            op = OpCodes.ParametricInstructions.Select,
            opStart = 0L, opLen = 1,
            text = "select",
            expected = bytes(0x1B),
        )
        assertEncodes(
            op = OpCodes.Flow.Return,
            opStart = 0L, opLen = 1,
            text = "return",
            expected = bytes(0x0F),
        )
    }

    @Test
    fun no_immediate_op_rejects_extra_tokens() {
        assertError(
            op = OpCodes.ParametricInstructions.Drop,
            opLen = 1,
            text = "drop 5",
            messageContains = "no immediates",
        )
    }

    // -- D.1: i32 numeric ops ----------------------------------------------

    @Test
    fun i32_add_encodes_to_single_byte() {
        assertEncodes(
            op = I32.Add,
            opStart = 0L, opLen = 1,
            text = "i32.add",
            expected = bytes(0x6A),
        )
    }

    @Test
    fun i32_sub_and_mul_have_distinct_opcodes() {
        assertEncodes(
            op = I32.Sub, opStart = 0L, opLen = 1,
            text = "i32.sub", expected = bytes(0x6B),
        )
        assertEncodes(
            op = I32.Mul, opStart = 0L, opLen = 1,
            text = "i32.mul", expected = bytes(0x6C),
        )
    }

    @Test
    fun i32_compare_ops_encode() {
        assertEncodes(op = I32.Eqz, opStart = 0L, opLen = 1, text = "i32.eqz", expected = bytes(0x45))
        assertEncodes(op = I32.Eq, opStart = 0L, opLen = 1, text = "i32.eq", expected = bytes(0x46))
        assertEncodes(op = I32.LTS, opStart = 0L, opLen = 1, text = "i32.lt_s", expected = bytes(0x48))
        assertEncodes(op = I32.GEU, opStart = 0L, opLen = 1, text = "i32.ge_u", expected = bytes(0x4F))
    }

    @Test
    fun i32_convert_ops_encode() {
        assertEncodes(op = I32.WrapI64, opStart = 0L, opLen = 1, text = "i32.wrap_i64", expected = bytes(0xA7))
        assertEncodes(op = I32.ReinterpretF32, opStart = 0L, opLen = 1, text = "i32.reinterpret_f32", expected = bytes(0xBC))
        assertEncodes(op = I32.Extend8S, opStart = 0L, opLen = 1, text = "i32.extend8_s", expected = bytes(0xC0))
        assertEncodes(op = I32.Extend16S, opStart = 0L, opLen = 1, text = "i32.extend16_s", expected = bytes(0xC1))
    }

    // -- D.1: i32 trunc_sat (0xFC prefix) ----------------------------------

    @Test
    fun i32_trunc_sat_f32_s_encodes_as_two_bytes() {
        assertEncodes(
            op = I32.TruncSatF32S,
            opStart = 0L, opLen = 2,
            text = "i32.trunc_sat_f32_s",
            expected = bytes(0xFC, 0x00),
        )
    }

    @Test
    fun i32_trunc_sat_f64_u_sub_opcode_is_0x03() {
        assertEncodes(
            op = I32.TruncSatF64U,
            opStart = 0L, opLen = 2,
            text = "i32.trunc_sat_f64_u",
            expected = bytes(0xFC, 0x03),
        )
    }

    // -- D.1: i64 numeric ops ----------------------------------------------

    @Test
    fun i64_shr_u_encodes_to_0x88() {
        assertEncodes(
            op = I64.ShrU,
            opStart = 0L, opLen = 1,
            text = "i64.shr_u",
            expected = bytes(0x88),
        )
    }

    @Test
    fun i64_high_byte_ops_encode_correctly() {
        // DivU=0x80, RemS=0x81, RemU=0x82 are >=0x80 — single opcode bytes,
        // not LEB128. Verify the bytes round-trip without sign extension issues.
        assertEncodes(op = I64.DivU, opStart = 0L, opLen = 1, text = "i64.div_u", expected = bytes(0x80))
        assertEncodes(op = I64.RemS, opStart = 0L, opLen = 1, text = "i64.rem_s", expected = bytes(0x81))
        assertEncodes(op = I64.RemU, opStart = 0L, opLen = 1, text = "i64.rem_u", expected = bytes(0x82))
    }

    @Test
    fun i64_trunc_sat_sub_opcodes() {
        assertEncodes(
            op = I64.TruncSatF32S, opStart = 0L, opLen = 2,
            text = "i64.trunc_sat_f32_s", expected = bytes(0xFC, 0x04),
        )
        assertEncodes(
            op = I64.TruncSatF64U, opStart = 0L, opLen = 2,
            text = "i64.trunc_sat_f64_u", expected = bytes(0xFC, 0x07),
        )
    }

    @Test
    fun i64_convert_ops_encode() {
        assertEncodes(op = I64.Extend_I32_S, opStart = 0L, opLen = 1, text = "i64.extend_i32_s", expected = bytes(0xAC))
        assertEncodes(op = I64.Extend_I32_U, opStart = 0L, opLen = 1, text = "i64.extend_i32_u", expected = bytes(0xAD))
        assertEncodes(op = I64.ReinterpretF64, opStart = 0L, opLen = 1, text = "i64.reinterpret_f64", expected = bytes(0xBD))
        assertEncodes(op = I64.Extend8S, opStart = 0L, opLen = 1, text = "i64.extend8_s", expected = bytes(0xC2))
        assertEncodes(op = I64.Extend32S, opStart = 0L, opLen = 1, text = "i64.extend32_s", expected = bytes(0xC4))
    }

    // -- D.1: f32 numeric ops ----------------------------------------------

    @Test
    fun f32_add_encodes_to_0x92() {
        assertEncodes(
            op = F32.Add,
            opStart = 0L, opLen = 1,
            text = "f32.add",
            expected = bytes(0x92),
        )
    }

    @Test
    fun f32_compare_and_convert_ops_encode() {
        assertEncodes(op = F32.EQ, opStart = 0L, opLen = 1, text = "f32.eq", expected = bytes(0x5B))
        assertEncodes(op = F32.LE, opStart = 0L, opLen = 1, text = "f32.le", expected = bytes(0x5F))
        assertEncodes(op = F32.ConvertI32S, opStart = 0L, opLen = 1, text = "f32.convert_i32_s", expected = bytes(0xB2))
        assertEncodes(op = F32.DemoteF64, opStart = 0L, opLen = 1, text = "f32.demote_f64", expected = bytes(0xB6))
        assertEncodes(op = F32.ReinterpretI32, opStart = 0L, opLen = 1, text = "f32.reinterpret_i32", expected = bytes(0xBE))
    }

    // -- D.1: f64 numeric ops ----------------------------------------------

    @Test
    fun f64_add_encodes_to_0xa0() {
        assertEncodes(
            op = F64.Add,
            opStart = 0L, opLen = 1,
            text = "f64.add",
            expected = bytes(0xA0),
        )
    }

    @Test
    fun f64_compare_and_convert_ops_encode() {
        assertEncodes(op = F64.EQ, opStart = 0L, opLen = 1, text = "f64.eq", expected = bytes(0x61))
        assertEncodes(op = F64.GE, opStart = 0L, opLen = 1, text = "f64.ge", expected = bytes(0x66))
        assertEncodes(op = F64.ConvertI64U, opStart = 0L, opLen = 1, text = "f64.convert_i64_u", expected = bytes(0xBA))
        assertEncodes(op = F64.PromoteF32, opStart = 0L, opLen = 1, text = "f64.promote_f32", expected = bytes(0xBB))
        assertEncodes(op = F64.ReinterpretI64, opStart = 0L, opLen = 1, text = "f64.reinterpret_i64", expected = bytes(0xBF))
    }

    // -- D.1: mnemonic swap from numeric arithmetic to numeric arithmetic ---

    @Test
    fun i32_add_can_swap_to_i32_sub_within_no_immediate_subset() {
        // Both are 1-byte, length-preserving. Encoder dispatches on the new
        // mnemonic text regardless of what op was there originally.
        assertEncodes(
            op = I32.Add,
            opStart = 0L, opLen = 1,
            text = "i32.sub",
            expected = bytes(0x6B),
        )
    }

    @Test
    fun numeric_op_can_swap_to_drop() {
        // Cross-family: arithmetic → parametric. Both are 1-byte.
        assertEncodes(
            op = I64.Mul,
            opStart = 0L, opLen = 1,
            text = "drop",
            expected = bytes(0x1A),
        )
    }

    // -- Whitespace and comment tolerance ----------------------------------

    @Test
    fun leading_whitespace_tolerated() {
        assertEncodes(
            op = I32.Const(value = 0),
            opStart = 0L, opLen = 2,
            text = "    i32.const 5",
            expected = bytes(0x41, 0x05),
        )
    }

    @Test
    fun trailing_leb_annotation_tolerated() {
        // The renderer may emit `(; sleb128 05 ;)` for multi-byte LEB
        // encodings; the encoder strips it before parsing.
        assertEncodes(
            op = I32.Const(value = 0),
            opStart = 0L, opLen = 2,
            text = "i32.const 5  (; sleb128 05 ;)",
            expected = bytes(0x41, 0x05),
        )
    }
}
