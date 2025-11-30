package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmI32Type

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

    fun test978() = doFunctionTest { funcs ->
        assertEquals(2, funcs.size)
        assertEquals(0, funcs[0].funcidx)
    }

    fun testSimpleLocals() {
        doOpcodeInFunctionTest(0) { ops ->
            assertNotNull(ops)
            assertEquals(8, ops!!.size)
            assertEquals(OpCodes.Local.Get(0), ops[0])
            assertEquals(OpCodes.Flow.Drop, ops[6])
        }
        doLocalsInFunctionTest(0) { locals ->
            assertNotNull(locals)
            assertEquals(4, locals!!.size)
            assertEquals(1, locals[0].local)
            assertEquals(WasmI32Type, locals[0].type)
            assertEquals(1, locals[3].local)
            assertEquals(WasmF64Type, locals[3].type)
        }
    }

    fun testBaseTestWithImports() {
        doOpcodeInFunctionTest(0) { ops ->
            assertNotNull(ops)
            assertEquals(3, ops!!.size)
            assertEquals(OpCodes.Local.Get(0), ops[0])
            assertEquals(OpCodes.Flow.Call(0), ops[2])
        }
        doLocalsInFunctionTest(0) { locals ->
            assertNotNull(locals)
            assertEquals(1, locals!!.size)
            assertEquals(1, locals[0].local)
            assertEquals(WasmI32Type, locals[0].type)
        }

    }
}