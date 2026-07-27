package org.jetbrains.hexana.wasm.analysis.dispatch

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
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
