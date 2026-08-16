package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.vector.i32x4
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.limits
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode
import org.jetbrains.hexana.wasm.sections.WasmFixedI32Limits
import org.jetbrains.hexana.wasm.sections.WasmFixedI64Limits
import org.jetbrains.hexana.wasm.sections.WasmOpenI32Limits
import org.jetbrains.hexana.wasm.sections.WasmOpenI64Limits
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmSectionTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression tests for spec-corpus failures found in the 2026-08 run.
 *
 * Each test pins one fix and includes a note on what assertion fails before the fix.
 *
 * Fix 1 — multi-memory memarg (consumeMemArg): bit 6 of align signals an extra memidx LEB128.
 *   Pre-fix failure: "assertEquals(MemArg(0,0), ...) expected <MemArg(0,0)> but was <MemArg(64,...)>",
 *   followed by stream drift and UnparsedOpCode.
 *
 * Fix 2 — relaxed-SIMD 0x101-0x104 (consumeFDBasedOpCode): four i32x4 relaxed_trunc opcodes
 *   were missing from the dispatch table.
 *   Pre-fix failure: ops.any { it is UnparsedOpCode } was true (single UnparsedOpCode for the body).
 *
 * Fix 3 — S33/SLEB128 type codes (GC proposal): overlong encodings such as `e0 7f` for functype.
 *   Pre-fix failure: "unknown type id: 0x7f" IllegalStateException during type-section parse;
 *   parseTypes() threw, making typeCount == 0 instead of 1.
 *
 * Fix 4 — Extended limits flags (memory64, threads, custom-page-sizes):
 *   Pre-fix failure: "unknown limits type" IllegalStateException for flag values >= 0x04.
 */
class SpecCorpusRegressionTest {

    // ============================================================
    // Fix 1 — multi-memory memarg (0x40 bit in align)
    // ============================================================

    /**
     * Encodes a minimal WASM module with one memory, one function that performs
     * `i32.load8_u offset=0 align=0 memidx=1` where the align field has bit 6 set
     * (0x40) to signal a following memory-index LEB128.
     *
     * Byte stream for the one load instruction: 2d 40 01 00
     *   - 0x2d = i32.load8_u opcode
     *   - 0x40 = raw align value with bit 6 set (= multi-memory flag)
     *   - 0x01 = memidx (consumed, not modeled)
     *   - 0x00 = offset
     * Followed by 0x0b (end).
     *
     * Pre-fix: consumeMemArg reads align=0x40=64, offset=0x01=1; stream drifts; end
     * byte is missed; the body becomes a single UnparsedOpCode.
     *
     * Post-fix: align=0 (bit 6 cleared), offset=0, and no UnparsedOpCode.
     */
    @Test
    fun `multi-memory memarg align-bit-6 produces correct MemArg and no stream drift`() = runTest {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, // magic + version
            // Type section: () -> ()
            0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
            // Function section: 1 function, type 0
            0x03, 0x02, 0x01, 0x00,
            // Memory section: 1 memory, 1 page min
            0x05, 0x03, 0x01, 0x00, 0x01,
            // Code section: 1 entry
            0x0a, 0x09, 0x01,
            // body: size=7, 0 locals, i32.load8_u (0x2d) align=0x40 memidx=0x01 offset=0x00, i32.const 0 (drop), end
            0x07, 0x00, 0x2d.toByte(), 0x40, 0x01, 0x00, // load8_u with multi-mem flag; offset=0
            0x1a, // drop
            0x0b, // end
        )
        val buffer = bytes.toCommonByteBuffer()
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        assertEquals(1, records.size)
        val ops = records[0].opCodes

        // Pre-fix: ops has exactly 1 UnparsedOpCode (the whole body).
        // Post-fix: no UnparsedOpCode.
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "stream drift produces UnparsedOpCode; got: ${unparsed.firstOrNull()?.e?.message}")

        // The load opcode must have align=0 (bit 6 cleared) and offset=0.
        val load = ops.filterIsInstance<org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i32.Load8_U>().firstOrNull()
        assertEquals(MemArg(0, 0), load?.value, "align bit 6 must be cleared and memidx consumed")
    }

    /**
     * Mirrors the address0.0.wasm signature: an i32.load8_s (0x2c) with align byte 0x40 (multi-mem flag),
     * memidx=0, offset=0. Verifies no stream drift.
     *
     * Pre-fix: 0x40 is treated as align=64, offset=0 (memidx byte), the next byte is read as
     * the offset — stream drifts; end is misread; body becomes UnparsedOpCode.
     */
    @Test
    fun `multi-memory memarg i32 load8_s memidx=0 no stream drift`() = runTest {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, // magic + version
            0x01, 0x04, 0x01, 0x60, 0x00, 0x00,             // type: () -> ()
            0x03, 0x02, 0x01, 0x00,                          // function 0: type 0
            0x05, 0x03, 0x01, 0x00, 0x01,                    // memory: 1 page
            0x0a, 0x08, 0x01,                                // code section
            // body: 6 bytes, 0 locals, i32.load8_s memidx variant, drop, end
            0x06, 0x00,
            0x2c.toByte(), 0x40, 0x00, 0x00, // i32.load8_s, align=0x40 (flag), memidx=0, offset=0
            0x1a, 0x0b,                       // drop, end
        )
        val buffer = bytes.toCommonByteBuffer()
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        val ops = records[0].opCodes
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "expected zero UnparsedOpCode after multi-mem memarg fix; got ${unparsed.size}")
    }

    // ============================================================
    // Fix 2 — relaxed-SIMD opcodes 0x101-0x104
    // ============================================================

    /**
     * Minimal WASM with one function using i32x4.relaxed_trunc_f32x4_s (fd 81 02 = prefix fd, code 0x101).
     * wat2wasm --enable-all generates this; the test builds the binary directly to be
     * independent of the wat2wasm tool being available.
     *
     * Code bytes for the function body:
     *   fd 81 02  = 0xFD prefix, subcode 0x101 (ULEB128 of 257)
     *
     * Pre-fix: consumeFDBasedOpCode falls to 'else' and throws UnsupportedOpsException;
     * the whole body is wrapped in a single UnparsedOpCode.
     * Post-fix: exactly one i32x4.RelaxedTruncF32x4S opcode, zero UnparsedOpCode.
     */
    @Test
    fun `relaxed-SIMD 0x101 i32x4 relaxed_trunc_f32x4_s decodes correctly`() = runTest {
        val ops = parseRelaxedTruncBody(
            // fd 81 02 = FD-prefix opcode 0x101
            byteArrayOf(0xfd.toByte(), 0x81.toByte(), 0x02)
        )
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        // Pre-fix: unparsed.size == 1 (whole body wrapped).
        assertTrue(unparsed.isEmpty(), "0x101 must parse; got UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
        assertEquals(1, ops.filterIsInstance<i32x4.RelaxedTruncF32x4S>().size,
            "expected exactly one RelaxedTruncF32x4S opcode")
    }

    @Test
    fun `relaxed-SIMD 0x102 i32x4 relaxed_trunc_f32x4_u decodes correctly`() = runTest {
        val ops = parseRelaxedTruncBody(byteArrayOf(0xfd.toByte(), 0x82.toByte(), 0x02))
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "0x102 must parse; got: ${unparsed.firstOrNull()?.e?.message}")
        assertEquals(1, ops.filterIsInstance<i32x4.RelaxedTruncF32x4U>().size)
    }

    @Test
    fun `relaxed-SIMD 0x103 i32x4 relaxed_trunc_f64x2_s_zero decodes correctly`() = runTest {
        val ops = parseRelaxedTruncBody(byteArrayOf(0xfd.toByte(), 0x83.toByte(), 0x02))
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "0x103 must parse; got: ${unparsed.firstOrNull()?.e?.message}")
        assertEquals(1, ops.filterIsInstance<i32x4.RelaxedTruncF32x4SZero>().size)
    }

    @Test
    fun `relaxed-SIMD 0x104 i32x4 relaxed_trunc_f64x2_u_zero decodes correctly`() = runTest {
        val ops = parseRelaxedTruncBody(byteArrayOf(0xfd.toByte(), 0x84.toByte(), 0x02))
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "0x104 must parse; got: ${unparsed.firstOrNull()?.e?.message}")
        assertEquals(1, ops.filterIsInstance<i32x4.RelaxedTruncF32x4UZero>().size)
    }

    // ============================================================
    // Fix 3 — S33/SLEB128 type codes (overlong encoding)
    // ============================================================

    /**
     * binary-leb128.90.wasm (15 bytes): magic + version + type section with one functype
     * encoded as overlong SLEB128 `e0 7f` instead of the canonical `0x60`.
     *
     * `e0 7f` decodes as SLEB128 = -32 = -0x20 = functype. Canonical `0x60` also decodes to -32.
     *
     * Pre-fix: parseTypes reads `e0` as a raw UByte (224), not 0x4E, falls to else branch,
     * calls position(-1) to un-push one byte (0xe0), then subtype() reads 0xe0 as a raw byte,
     * which is not 0x4f/0x50, calls compType which reads the NEXT byte 0x7f as the type-code
     * byte: "unknown type id: 0x7f".
     * Post-fix: getSLEB128() decodes `e0 7f` to -32, subtypeFromCode(-32, ...) dispatches to
     * compTypeFromCode(-32==-0x20) -> funcType -> 1 WasmSectionFunctionTypeRecord.
     */
    @Test
    fun `overlong SLEB128 functype e0 7f parses as functype`() = runTest {
        // 00 61 73 6d 01 00 00 00  — magic + version
        // 01 05 01                 — type section id=1, size=5, count=1
        // e0 7f                    — overlong SLEB128(-32) = functype
        // 00 00                    — 0 params, 0 results
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x05, 0x01,
            0xe0.toByte(), 0x7f,
            0x00, 0x00,
        )
        val buffer = bytes.toCommonByteBuffer()
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule as WasmModule
        val typesSection = requireNotNull(module.typesSection) { "type section must be present" }
        val types = with(typesSection) { buffer.parseTypes() }

        // Pre-fix: throws "unknown type id: 0x7f" before we get here, or returns empty list.
        assertEquals(1, types.size, "expected exactly 1 type record from the overlong functype encoding")
        assertIs<WasmSectionFunctionTypeRecord>(types[0])
        assertEquals(0, (types[0] as WasmSectionFunctionTypeRecord).params.size)
        assertEquals(0, (types[0] as WasmSectionFunctionTypeRecord).retTypes.size)
    }

    // ============================================================
    // Fix 4 — Extended limits flags
    // ============================================================

    /**
     * Verifies that the limits() parser accepts all flag-byte combinations
     * specified by the memory64 (bit 2), threads (bit 1), and custom-page-sizes (bit 3) proposals.
     *
     * Pre-fix: any flag value >= 0x04 (or 0x02) throws "unknown limits type".
     * Post-fix: all values 0x00-0x0F with valid field layouts parse without exception.
     */
    @Test
    fun `limits flags 0x04 memory64 open parses to WasmOpenI64Limits`() {
        // flags=0x04, min=0 as ULEB128
        val bytes = byteArrayOf(0x04, 0x00)
        val buf = bytes.toCommonByteBuffer()
        // Pre-fix: error("unknown limits type") thrown here.
        val lim = with(buf) { limits() }
        assertIs<WasmOpenI64Limits>(lim)
        assertEquals(0L, lim.min)
    }

    @Test
    fun `limits flags 0x05 memory64 with max parses to WasmFixedI64Limits`() {
        val bytes = byteArrayOf(0x05, 0x01, 0x10) // flags, min=1, max=16
        val buf = bytes.toCommonByteBuffer()
        val lim = with(buf) { limits() }
        assertIs<WasmFixedI64Limits>(lim)
        assertEquals(1L, lim.min)
        assertEquals(16L, lim.max)
    }

    @Test
    fun `limits flags 0x07 memory64 shared with max parses to WasmFixedI64Limits shared`() {
        val bytes = byteArrayOf(0x07, 0x01, 0x10) // flags, min=1, max=16
        val buf = bytes.toCommonByteBuffer()
        val lim = with(buf) { limits() }
        assertIs<WasmFixedI64Limits>(lim)
        assertTrue(lim.shared)
        assertEquals(1L, lim.min)
    }

    @Test
    fun `limits flags 0x08 custom-page-size i32 open consumes page-size-log2`() {
        // flags=0x08, min=1, page-size-log2=16 (meaning 64KiB = standard)
        val bytes = byteArrayOf(0x08, 0x01, 0x10)
        val buf = bytes.toCommonByteBuffer()
        // Pre-fix: error("unknown limits type") thrown here.
        val lim = with(buf) { limits() }
        assertIs<WasmOpenI32Limits>(lim)
        assertEquals(1, lim.min)
        // page-size-log2 is consumed and not modeled — no assertion needed for it
    }

    @Test
    fun `limits flags 0x09 custom-page-size i32 with max consumes page-size-log2`() {
        val bytes = byteArrayOf(0x09, 0x01, 0x10, 0x10) // flags, min=1, max=16, page-size-log2=16
        val buf = bytes.toCommonByteBuffer()
        val lim = with(buf) { limits() }
        assertIs<WasmFixedI32Limits>(lim)
        assertEquals(1, lim.min)
        assertEquals(16, lim.max)
    }

    @Test
    fun `limits flags 0x0c custom-page-size memory64 open consumes page-size-log2`() {
        val bytes = byteArrayOf(0x0c.toByte(), 0x00, 0x10) // flags, min=0, page-size-log2=16
        val buf = bytes.toCommonByteBuffer()
        val lim = with(buf) { limits() }
        assertIs<WasmOpenI64Limits>(lim)
        assertEquals(0L, lim.min)
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Builds a minimal WASM module with one function whose body consists of:
     * (v128.const i32x4 0 0 0 0) + [opcodeBytes] + drop + end.
     * The v128.const provides the v128 operand that relaxed_trunc ops consume.
     */
    private suspend fun parseRelaxedTruncBody(opcodeBytes: ByteArray): List<OpCode> {
        // v128.const immediate: 16 bytes of zeros
        val v128Const = byteArrayOf(
            0xfd.toByte(), 0x0c,         // fd 12 = v128.const
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val drop = byteArrayOf(0x1a)
        val end = byteArrayOf(0x0b)
        val body = v128Const + opcodeBytes + drop + end

        // Build module: type (() -> ()), func 0, code section with one entry
        val typeSection = byteArrayOf(0x01, 0x04, 0x01, 0x60, 0x00, 0x00)
        val funcSection = byteArrayOf(0x03, 0x02, 0x01, 0x00)

        // Code entry: localCount=0, then body
        val codeEntry = byteArrayOf(0x00.toByte()) + body  // 0 local declarations
        val codeEntryWithSize = ulebSize(codeEntry.size) + codeEntry
        val codeSection = byteArrayOf(0x0a.toByte()) + ulebSize(1 + codeEntryWithSize.size) +
            byteArrayOf(0x01) + codeEntryWithSize

        val module = byteArrayOf(
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00
        ) + typeSection + funcSection + codeSection

        val buffer = module.toCommonByteBuffer()
        val result = WasmParser().parse(buffer) as WasmParseSuccess
        val wasmModule = result.wasmModule as WasmModule
        val records = with(wasmModule.codeSection!!) { buffer.scan() }
        return records[0].opCodes
    }

    private fun ulebSize(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        return byteArrayOf((n and 0x7f or 0x80).toByte(), (n shr 7).toByte())
    }
}
