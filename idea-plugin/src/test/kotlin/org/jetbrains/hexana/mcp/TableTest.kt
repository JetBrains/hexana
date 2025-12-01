package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.wasm.sections.WasmFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmReferenceHeapType
import org.jetbrains.hexana.wasm.sections.WasmTableEntry

class TableTest : WasmTest() {
    fun testTable0() = doTableTest { table ->
        assertEquals(1, table.size)
        assertEquals(WasmReferenceHeapType(WasmFuncHeapType), table[0].tableType.referenceType)
        assertNull(table[0].expression)
    }
}