package org.jetbrains.hexana.wasm.analysis.dispatch

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.sections.parseCodeEntry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DispatchCandidatesTest {

    private suspend fun buildFor(resource: String): DispatchCandidates {
        val buffer = resourceAsByteBuffer(resource)
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)
        return DispatchCandidatesBuilder(buffer, module).build()
    }

    // Fixture layout (see dispatch-candidates.wat): type 0 and type 1 are both
    // (i32) -> i32; funcidx 0=$ext(import) 1=$add1 2=$mul2 3=$sum 4=$orphan
    // 5=$passive 6=$caller; active elem {0,1,2,3}, passive elem {5},
    // declare elem {4}.

    @Test
    fun `candidates merge across structurally identical type entries`() = runTest {
        val candidates = buildFor("snippets/dispatch-candidates.wasm")
        // $mul2 is declared under type 1, $add1 under type 0 — both must be
        // candidates for either type index. Passive-segment $passive included,
        // declare-segment $orphan and out-of-table $caller excluded.
        assertContentEquals(intArrayOf(0, 1, 2, 5), candidates.candidatesForType(0))
        assertContentEquals(intArrayOf(0, 1, 2, 5), candidates.candidatesForType(1))
    }

    @Test
    fun `distinct signature has its own candidate set`() = runTest {
        val candidates = buildFor("snippets/dispatch-candidates.wasm")
        assertContentEquals(intArrayOf(3), candidates.candidatesForType(2))
    }

    @Test
    fun `signature with no table entries yields empty candidates`() = runTest {
        val candidates = buildFor("snippets/dispatch-candidates.wasm")
        assertContentEquals(intArrayOf(), candidates.candidatesForType(3))
        // Out-of-range type index must not throw.
        assertContentEquals(intArrayOf(), candidates.candidatesForType(42))
    }

    @Test
    fun `signature text renders WAT-flavored types`() = runTest {
        val candidates = buildFor("snippets/dispatch-candidates.wasm")
        assertEquals("(i32) -> i32", candidates.signatureOfType(0)?.text())
        assertEquals("(i32, i32) -> i32", candidates.signatureOfType(2)?.text())
        assertEquals("(f64) -> f64", candidates.signatureOfType(3)?.text())
    }

    @Test
    fun `slot image maps active-segment slots and nothing else`() = runTest {
        val candidates = buildFor("snippets/dispatch-candidates.wasm")
        // Active segment: (elem (i32.const 0) $ext $add1 $mul2 $sum).
        assertEquals(0, candidates.functionAtSlot(0))
        assertEquals(1, candidates.functionAtSlot(1))
        assertEquals(3, candidates.functionAtSlot(3))
        // Slot 4 is only reachable via table.init of the passive segment —
        // not statically initialized.
        assertEquals(null, candidates.functionAtSlot(4))
    }

    @Test
    fun `constant index at the call site resolves to its table slot`() = runTest {
        val buffer = resourceAsByteBuffer("snippets/dispatch-candidates.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)
        val codeSection = module.codeSection!!
        val offsets = with(codeSection) { buffer.scanOffsets() }

        // $caller is defined function 5 (funcidx 6): local.get 0; i32.const 0;
        // call_indirect — the previous-sibling const resolves to slot 0.
        val caller = buffer.parseCodeEntry(offsets[5].offset)
        val site = caller.opCodes.first {
            it is OpCodes.Flow.CallIndirect || it is OpCodes.Flow.ReturnCallIndirect
        } as OpCodeWithOffset
        assertEquals(0, resolveConstantIndirectIndex(caller.opCodes, site.offset))

        // An offset that is not a call_indirect site resolves to nothing.
        assertEquals(null, resolveConstantIndirectIndex(caller.opCodes, site.offset - 1))
    }

    @Test
    fun `non-constant index answers null`() {
        // local.get feeds the index: not statically evident.
        val site = OpCodes.Flow.CallIndirect(0, 0, offset = 10)
        val ops = listOf(OpCodes.Local.Get(0u, 5), site)
        assertEquals(null, resolveConstantIndirectIndex(ops, 10))

        // The const sits inside a preceding block — its result is not modeled.
        val site2 = OpCodes.Flow.CallIndirect(0, 0, offset = 20)
        val nested = OpCodes.Flow.Block(null, listOf(I32.Const(3, 12)), 11)
        assertEquals(null, resolveConstantIndirectIndex(listOf(nested, site2), 20))

        // Canonical shape still resolves in a synthetic list.
        val site3 = OpCodes.Flow.CallIndirect(0, 0, offset = 30)
        assertEquals(7, resolveConstantIndirectIndex(listOf(I32.Const(7, 25), site3), 30))
    }

    @Test
    fun `emscripten-scale module builds a populated index`() = runTest {
        val candidates = buildFor("skiko.wasm")
        // Skiko dispatches via call_indirect heavily; at least one signature
        // must have a non-trivial candidate set, and lookups stay cheap.
        var populated = 0
        for (typeIdx in 0 until 200) {
            if (candidates.candidatesForType(typeIdx).isNotEmpty()) populated++
        }
        assertTrue(populated > 0, "expected populated candidate sets in skiko.wasm")
    }
}
