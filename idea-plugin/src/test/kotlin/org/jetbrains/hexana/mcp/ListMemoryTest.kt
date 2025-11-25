package org.jetbrains.hexana.mcp


class ListMemoryTest : WasmTest() {
    fun testMem0() = doTest { wasmModule ->
        listMemory(wasmModule)
    }
}