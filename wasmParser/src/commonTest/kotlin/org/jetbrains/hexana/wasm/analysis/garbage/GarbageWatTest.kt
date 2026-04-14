package org.jetbrains.hexana.wasm.analysis.garbage

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.tests.watResourceToBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphBuilder
import org.jetbrains.hexana.wasm.analysis.graph.DominatorTree
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GarbageWatTest {

    private suspend fun analyzeInline(wat: String): GarbageResult {
        val buffer = watStringToBuffer(wat)
        return analyzeBuffer(buffer)
    }

    private suspend fun analyzeFile(resourcePath: String): GarbageResult {
        val buffer = watResourceToBuffer(resourcePath)
        return analyzeBuffer(buffer)
    }

    private fun analyzeBuffer(buffer: org.jetbrains.hexana.io.CommonByteBuffer): GarbageResult {
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule

        val cgr = CallGraphBuilder(buffer, module).build()
        val dom = DominatorTree.compute(cgr.graph)

        val codeSection = module.codeSection!!
        val codeOffsets = with(codeSection) { buffer.scanOffsets() }

        val importRecords = module.importSection?.let { section ->
            with(section) { buffer.getImportRecords() }
        } ?: emptyList()
        val importFuncCount = importRecords.count { it.type is WasmTypeIndexExternalType }

        return GarbageAnalyzer(cgr, dom, codeOffsets, importFuncCount, buffer, module).analyze()
    }

    @Test
    fun `all exported — no garbage`() = runTest {
        val result = analyzeInline("""
            (module
              (func ${'$'}a (export "a") (nop))
              (func ${'$'}b (export "b") (nop))
            )
        """.trimIndent())

        assertTrue(result.entries.isEmpty())
        assertEquals(0L, result.totalWastedBytes)
    }

    @Test
    fun `single dead function`() = runTest {
        val result = analyzeInline("""
            (module
              (func ${'$'}used (export "used") (nop))
              (func ${'$'}dead (nop))
            )
        """.trimIndent())

        assertEquals(1, result.entries.size)
        assertEquals(GarbageReason.NO_CALLERS, result.entries[0].reason)
    }

    @Test
    fun `dead function called by another dead function`() = runTest {
        val result = analyzeInline("""
            (module
              (func ${'$'}live (export "live") (nop))
              (func ${'$'}dead_a (call ${'$'}dead_b))
              (func ${'$'}dead_b (nop))
            )
        """.trimIndent())

        assertEquals(2, result.entries.size)
        val byName = result.entries.associateBy { it.name }
        // dead_a has no callers from reachable code
        assertEquals(GarbageReason.NO_CALLERS, byName["func_1"]?.reason)
        // dead_b is called only by dead_a which is also unreachable
        assertEquals(GarbageReason.CALLERS_UNREACHABLE, byName["func_2"]?.reason)
    }

    @Test
    fun `indirect call keeps function alive`() = runTest {
        val result = analyzeInline("""
            (module
              (type ${'$'}t (func))
              (table 1 funcref)
              (elem (i32.const 0) ${'$'}target)
              (func ${'$'}caller (export "caller")
                (call_indirect (type ${'$'}t) (i32.const 0))
              )
              (func ${'$'}target (nop))
            )
        """.trimIndent())

        // target is in element section so it's connected to root
        assertTrue(result.entries.isEmpty(), "indirect call target should be reachable")
    }

    @Test
    fun `file-based — call chain with dead and live functions`() = runTest {
        val result = analyzeFile("wat/garbage/call_chain.wat")

        // 3 dead: x, y, isolated (func indices 3, 4, 5 — after entry=0, a=1, b=2)
        assertEquals(3, result.entries.size)
        val byName = result.entries.associateBy { it.name }
        assertEquals(GarbageReason.NO_CALLERS, byName["func_3"]?.reason)      // x — no callers
        assertEquals(GarbageReason.CALLERS_UNREACHABLE, byName["func_4"]?.reason) // y — called by x (dead)
        assertEquals(GarbageReason.NO_CALLERS, byName["func_5"]?.reason)      // isolated — no callers
    }
}