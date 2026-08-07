package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode
import org.jetbrains.hexana.wasm.sections.WasmExactHeapType
import org.jetbrains.hexana.wasm.sections.WasmNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRefType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for custom-descriptors proposal degradation in hello-O2.wasm.
 *
 * Ground truth (wasm-tools print + binary inspection):
 *  - 216 types, 234 defined functions, 22 function imports
 *  - Function index 25 (code entry 3, body at offset 8791) has locals:
 *      count=1  ref null (exact 67)
 *      count=1  ref null 67
 *      count=1  i32
 *  - No 0xFB 0x32-0x38 (struct.new_desc etc.) opcodes are present, so every
 *    function body should parse completely (no UnparsedOpCode in any entry).
 */
class CustomDescriptorsDegradationTest {

    // (a) Parsing does not throw.
    @Test
    fun `hello-O2 parses without exception`() = runTest {
        val buffer = resourceAsByteBuffer("kotlin/hello-O2.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
    }

    // (b) Type section parses all 216 types.
    @Test
    fun `hello-O2 type section has 216 types`() = runTest {
        val buffer = resourceAsByteBuffer("kotlin/hello-O2.wasm")
        val result = assertIs<WasmParseSuccess>(WasmParser().parse(buffer))
        val module = result.wasmModule as WasmModule
        val typesSection = assertNotNull(module.typesSection)
        val types = with(typesSection) { buffer.parseTypes() }
        val leafCount = types.sumOf { if (it is WasmRecursiveTypeRecord) it.entries.size else 1 }
        assertEquals(216, leafCount, "expected 216 leaf type records")
    }

    // (c) Code section has 234 entries and all scan without crash.
    @Test
    fun `hello-O2 code section has 234 entries`() = runTest {
        val buffer = resourceAsByteBuffer("kotlin/hello-O2.wasm")
        val result = assertIs<WasmParseSuccess>(WasmParser().parse(buffer))
        val module = result.wasmModule as WasmModule
        val codeSection = assertNotNull(module.codeSection)
        val records = with(codeSection) { buffer.scan() }
        assertEquals(234, records.size, "expected 234 code entries")
    }

    // (d) Locals of function 25 (code entry 3, body at offset 8791) decode correctly.
    @Test
    fun `hello-O2 function 25 locals include exact heap type 67`() = runTest {
        val buffer = resourceAsByteBuffer("kotlin/hello-O2.wasm")
        val result = assertIs<WasmParseSuccess>(WasmParser().parse(buffer))
        val module = result.wasmModule as WasmModule
        val codeSection = assertNotNull(module.codeSection)
        val records = with(codeSection) { buffer.scan() }

        // Code entry 3 = function index 25 (22 imports + 3)
        val entry = records[3]
        assertEquals(3, entry.locals.size, "expected 3 local groups")

        // local 0: count=1 (local groups use field `local`), type = ref null (exact 67)
        val local0 = entry.locals[0]
        assertEquals(1, local0.local)
        val refType0 = assertIs<WasmRefType>(local0.type)
        val ref0 = assertIs<WasmNullableReferenceType>(refType0.code)
        assertIs<WasmExactHeapType>(ref0.heapType)
        assertEquals(67L, ref0.heapType.type)

        // local 1: count=1, type = ref null 67 (plain index)
        val local1 = entry.locals[1]
        assertEquals(1, local1.local)
        val refType1 = assertIs<WasmRefType>(local1.type)
        val ref1 = assertIs<WasmNullableReferenceType>(refType1.code)
        assertFalse(ref1.heapType is WasmExactHeapType, "local 1 should be a plain index ref, not exact")
        assertEquals(67L, ref1.heapType.type)

        // local 2: count=1, type = i32
        val local2 = entry.locals[2]
        assertEquals(1, local2.local)
    }

    // (e) No function has a whole-body UnparsedOpCode (no 0xFB 0x32-0x38 in this binary).
    @Test
    fun `hello-O2 no whole-body UnparsedOpCode entries`() = runTest {
        val buffer = resourceAsByteBuffer("kotlin/hello-O2.wasm")
        val result = assertIs<WasmParseSuccess>(WasmParser().parse(buffer))
        val module = result.wasmModule as WasmModule
        val codeSection = assertNotNull(module.codeSection)
        val records = with(codeSection) { buffer.scan() }

        val corrupt = records.filter { r -> r.opCodes.size == 1 && r.opCodes[0] is UnparsedOpCode }
        assertTrue(corrupt.isEmpty(), "no code entry should be fully unparsed; corrupt entries: $corrupt")
    }

    // Regression: existing Skiko module must have zero UnparsedOpCode entries
    // (it uses only standardised GC opcodes known to the parser).
    @Test
    fun `skiko regression - zero UnparsedOpCode entries`() = runTest {
        val buffer = resourceAsByteBuffer("skiko.wasm")
        val result = assertIs<WasmParseSuccess>(WasmParser().parse(buffer))
        val module = result.wasmModule as WasmModule
        val codeSection = module.codeSection ?: return@runTest
        val records = with(codeSection) { buffer.scan() }

        val withUnparsed = records.filter { r -> r.opCodes.any { it is UnparsedOpCode } }
        assertTrue(withUnparsed.isEmpty(),
            "skiko.wasm should have no UnparsedOpCode entries; found ${withUnparsed.size}")
    }
}