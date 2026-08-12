package org.jetbrains.hexana.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [WasmStackShapeAnalyzer] against real fixtures with ground truth
 * from `wasm-objdump -d`.
 *
 * wasmtime-tiny.wasm (source: wasmtime-tiny.wat in the same directory):
 * ```
 * func[0] add:        0x4e local.get 0; 0x50 local.get 1; 0x52 i32.add; 0x53 end
 * func[1] sum_to:     0x58 block; 0x5a loop; 0x5c local.get 1; 0x5e local.get 0;
 *                     0x60 i32.ge_u; 0x61 br_if 1; 0x63 local.get 2; 0x65 local.get 1;
 *                     0x67 i32.add; 0x68 local.set 2; 0x6a local.get 1; 0x6c i32.const 1;
 *                     0x6e i32.add; 0x6f local.set 1; 0x71 br 0; ends; 0x75 local.get 2; 0x77 end
 * func[2] store_load: 0x7a local.get 0; 0x7c local.get 1; 0x7e i32.store;
 *                     0x81 local.get 0; 0x83 i32.load; 0x86 end
 * ```
 * The i32.add / i32.ge_u targets exercise the backward offset deduction for
 * offset-less singleton opcodes.
 */
class WasmStackShapeAnalyzerTest {

    private fun tinyAnalyzer(): WasmStackShapeAnalyzer {
        val stream = WasmStackShapeAnalyzerTest::class.java.classLoader.getResourceAsStream("wasmtime-tiny.wasm")
            ?: error("Test resource not found: wasmtime-tiny.wasm")
        return assertNotNull(WasmStackShapeAnalyzer.create(stream.readBytes()), "tiny module must index")
    }

    private fun okSlots(result: WasmStackShapeResult): List<WasmSymbolicStackSlot> =
        assertIs<WasmStackShapeResult.Ok>(result, "expected Ok, got ${(result as? WasmStackShapeResult.Unavailable)?.reason}").slots

    private fun provenances(result: WasmStackShapeResult): List<String> = okSlots(result).map { it.provenance }

    // ── add: straight-line, singleton target ───────────────────────────

    @Test fun `singleton i32 add sees both locals`() {
        val slots = okSlots(tinyAnalyzer().stackAt(0x52))
        assertEquals(listOf("i32", "i32"), slots.map { it.type })
        assertEquals(listOf("local 0", "local 1"), slots.map { it.provenance })
    }

    @Test fun `mid function single slot`() {
        assertEquals(listOf("local 0"), provenances(tinyAnalyzer().stackAt(0x50)))
    }

    @Test fun `function entry has empty stack`() {
        assertEquals(emptyList(), okSlots(tinyAnalyzer().stackAt(0x4e)))
    }

    @Test fun `local names decorate provenance`() {
        val result = tinyAnalyzer().stackAt(0x50) { idx -> if (idx == 0) "a" else null }
        assertEquals(listOf("local 0 (a)"), provenances(result))
    }

    // ── sum_to: block + loop + branches ─────────────────────────────────

    @Test fun `inside loop before comparison`() {
        assertEquals(listOf("local 1", "local 0"), provenances(tinyAnalyzer().stackAt(0x60)))
    }

    @Test fun `const operand before add`() {
        val slots = okSlots(tinyAnalyzer().stackAt(0x6e))
        assertEquals(listOf("local 1", "const 1"), slots.map { it.provenance })
        assertEquals(listOf("i32", "i32"), slots.map { it.type })
    }

    @Test fun `stack empty after block and loop unwind`() {
        assertEquals(emptyList(), okSlots(tinyAnalyzer().stackAt(0x75)))
    }

    // ── store_load: memory ops ──────────────────────────────────────────

    @Test fun `store operands`() {
        assertEquals(listOf("local 0", "local 1"), provenances(tinyAnalyzer().stackAt(0x7e)))
    }

    @Test fun `after store only address remains`() {
        assertEquals(listOf("local 0"), provenances(tinyAnalyzer().stackAt(0x83)))
    }

    @Test fun `closing end carries the return value`() {
        val slots = okSlots(tinyAnalyzer().stackAt(0x86))
        assertEquals(1, slots.size)
        assertEquals("i32", slots[0].type)
        assertEquals("i32.load @0x83", slots[0].provenance)
    }

    // ── Unavailable paths ───────────────────────────────────────────────

    @Test fun `mid instruction offset is unavailable`() {
        assertIs<WasmStackShapeResult.Unavailable>(tinyAnalyzer().stackAt(0x4f))
    }

    @Test fun `offset outside code section is unavailable`() {
        assertIs<WasmStackShapeResult.Unavailable>(tinyAnalyzer().stackAt(0x2000))
    }

    @Test fun `garbage bytes do not index`() {
        assertNull(WasmStackShapeAnalyzer.create(ByteArray(32) { 7 }))
    }

    // ── dwarf-memvars: real rustc -O0 body (globals, stores, load) ─────
    //
    // wasm-objdump ground truth for func[0] make:
    //   0x116 global.get 0; 0x11c i32.const 16; 0x11e i32.sub; 0x11f local.set 1;
    //   0x121 local.get 1; 0x123 global.set 0; 0x129 local.get 1; 0x12b local.get 0;
    //   0x12d i32.store; ... 0x14c local.get 2; 0x14e i32.const 1; 0x150 i32.add; 0x151 local.set 3

    private fun memvarsAnalyzer(): WasmStackShapeAnalyzer {
        val file = File("../wasmParser/src/commonTest/resources/rust/dwarf-memvars.wasm")
        assertTrue(file.exists(), "fixture must exist: ${file.absolutePath}")
        return assertNotNull(WasmStackShapeAnalyzer.create(file.readBytes()), "dwarf-memvars must index")
    }

    @Test fun `rust shadow stack prologue`() {
        val slots = okSlots(memvarsAnalyzer().stackAt(0x11e))
        assertEquals(listOf("global 0", "const 16"), slots.map { it.provenance })
        assertEquals(listOf("i32", "i32"), slots.map { it.type })
    }

    @Test fun `rust store operands`() {
        assertEquals(listOf("local 1", "local 0"), provenances(memvarsAnalyzer().stackAt(0x12d)))
    }

    @Test fun `rust increment before add`() {
        assertEquals(listOf("local 2", "const 1"), provenances(memvarsAnalyzer().stackAt(0x150)))
    }
}
