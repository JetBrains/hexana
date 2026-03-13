package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type

class FunctionLocalsIndexMappingTest : WasmTest() {
    fun testLocalsForFunctionsReturnsAllRequestedEntriesAcrossMixedIndexSpace() = doTest("functionIndexSpace") { wasmModule ->
        val results = localsForFunctions(listOf(0, 1, 2, 3, 4, 5), wasmModule)

        assertEquals(listOf(0, 1, 2, 3, 4, 5), results.map { it.funcidx })
        assertTrue(results[0].locals.isEmpty())
        assertTrue(results[1].locals.isEmpty())

        assertEquals(1, results[2].locals.size)
        assertLocalNum(results[2].locals[0], count = 1, type = WasmI32Type.code)

        assertEquals(1, results[3].locals.size)
        assertLocalNum(results[3].locals[0], count = 2, type = WasmI64Type.code)

        assertEquals(1, results[4].locals.size)
        assertLocalNum(results[4].locals[0], count = 1, type = WasmF32Type.code)

        assertEquals(2, results[5].locals.size)
        assertLocalNum(results[5].locals[0], count = 1, type = WasmI32Type.code)
        assertLocalNum(results[5].locals[1], count = 1, type = WasmI64Type.code)
    }

    fun testLocalsForFunctionsReturnsOnlyRequestedSparseIndices() = doTest("functionIndexSpace") { wasmModule ->
        val results = localsForFunctions(listOf(0, 2, 5), wasmModule)

        assertEquals(listOf(0, 2, 5), results.map { it.funcidx })
        assertTrue(results[0].locals.isEmpty())

        assertEquals(1, results[1].locals.size)
        assertLocalNum(results[1].locals[0], count = 1, type = WasmI32Type.code)

        assertEquals(2, results[2].locals.size)
        assertLocalNum(results[2].locals[0], count = 1, type = WasmI32Type.code)
        assertLocalNum(results[2].locals[1], count = 1, type = WasmI64Type.code)
    }

    private fun assertLocalNum(local: Local, count: Int, type: Byte) {
        assertTrue(local is LocalNum)
        local as LocalNum
        assertEquals(count, local.count)
        assertEquals(type, local.type)
    }
}
