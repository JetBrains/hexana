package org.jetbrains.hexana.mcp

class FunctionTest: WasmTest() {
    fun testFunc0() = doFunctionTest { funcs ->
        assertEquals(6, funcs.size)
        assertNull(funcs[0].codeOffset)
        assertEquals(0, funcs[0].funcidx)
        assertNull(funcs[0].codeOffset)
        assertEquals(ParameterSignature(emptyList(), emptyList()), funcs[0].signature)
        assertEquals("import", funcs[0].definedIn)
        assertEquals("env", funcs[0].import?.module)
        assertEquals("f0", funcs[0].import?.name)
        assertNull(funcs[1].codeOffset)
        assertNull(funcs[1].codeSize)
        assertNull(funcs[1].instructionCount)
        assertEquals("env", funcs[1].import?.module)
        assertEquals("f1", funcs[1].import?.name)
        assertEquals(1, funcs[1].funcidx)
        assertEquals(2, funcs[2].funcidx)
        assertEquals(3, funcs[3].funcidx)
        assertEquals(62L, funcs[3].codeOffset)
        assertEquals(4, funcs[4].funcidx)
        assertEquals(0L, funcs[4].instructionCount)
        assertEquals(2L, funcs[4].codeSize)
        assertEquals(5, funcs[5].funcidx)
        assertEquals(listOf("call_all"), funcs[5].exportedAs)
        assertEquals("function_section", funcs[5].definedIn)
        assertEquals(5L, funcs[5].instructionCount)
        assertEquals(12L, funcs[5].codeSize)
        assertNull(funcs[5].import)
    }

    fun testFunc1() = doFunctionTest { funcs ->
        assertEquals(2, funcs.size)
        assertEquals(0, funcs[0].funcidx)
    }
}