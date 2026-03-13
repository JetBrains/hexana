package org.jetbrains.hexana.mcp

class FunctionCodeIndexMappingTest : WasmTest() {
    fun testCodeForFunctionsUsesUnifiedFuncidxAcrossImportsAndDefinitions() = doTest("functionIndexSpace") { wasmModule ->
        val imported = codeForFunctions(listOf(0), wasmModule)
        assertEquals(1, imported.size)
        assertEquals(0, imported.single().funcidx)
        assertTrue(imported.single().instructions.isEmpty())

        val firstDefined = codeForFunctions(listOf(1), wasmModule)
        assertEquals(1, firstDefined.size)
        assertEquals(1, firstDefined.single().funcidx)
        assertTrue(firstDefined.single().instructions.contains("i32.const 11"))
        assertTrue(firstDefined.single().instructions.contains("drop"))

        val secondDefined = codeForFunctions(listOf(2), wasmModule)
        assertEquals(1, secondDefined.size)
        assertEquals(2, secondDefined.single().funcidx)
        assertTrue(secondDefined.single().instructions.contains("i32.const 22"))
        assertFalse(secondDefined.any { it.funcidx == 1 })
    }

    fun testCodeForFunctionsReturnsAllRequestedEntriesAcrossMixedIndexSpace() = doTest("functionIndexSpace") { wasmModule ->
        val results = codeForFunctions(listOf(0, 1, 2, 3, 4), wasmModule)

        assertEquals(listOf(0, 1, 2, 3, 4), results.map { it.funcidx })
        assertTrue(results[0].instructions.isEmpty())
        assertTrue(results[1].instructions.isEmpty())

        assertTrue(results[2].instructions.contains("i32.const 11"))
        assertFalse(results[2].instructions.contains("i32.const 22"))
        assertFalse(results[2].instructions.contains("i32.const 33"))

        assertTrue(results[3].instructions.contains("i32.const 22"))
        assertFalse(results[3].instructions.contains("i32.const 11"))
        assertFalse(results[3].instructions.contains("i32.const 33"))

        assertTrue(results[4].instructions.contains("i32.const 33"))
        assertFalse(results[4].instructions.contains("i32.const 11"))
        assertFalse(results[4].instructions.contains("i32.const 22"))
    }

    fun testCodeForFunctionsReturnsOnlyRequestedSparseIndices() = doTest("functionIndexSpace") { wasmModule ->
        val results = codeForFunctions(listOf(0, 2, 5), wasmModule)

        assertEquals(listOf(0, 2, 5), results.map { it.funcidx })
        assertTrue(results[0].instructions.isEmpty())

        assertTrue(results[1].instructions.contains("i32.const 11"))
        assertFalse(results[1].instructions.contains("i32.const 22"))
        assertFalse(results[1].instructions.contains("i32.const 33"))
        assertFalse(results[1].instructions.contains("i32.const 44"))

        assertTrue(results[2].instructions.contains("i32.const 44"))
        assertFalse(results[2].instructions.contains("i32.const 11"))
        assertFalse(results[2].instructions.contains("i32.const 22"))
        assertFalse(results[2].instructions.contains("i32.const 33"))
    }
}
