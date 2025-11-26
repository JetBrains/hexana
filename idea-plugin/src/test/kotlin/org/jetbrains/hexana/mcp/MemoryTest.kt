package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule


class MemoryTest : WasmTest() {
    fun testMem0() = doTest { wasmModule ->
        listMemory(wasmModule).let { memories ->
            assertEquals(1, memories.size)
            assertEquals(0, memories[0].memoryIdx)
            assertEquals(1, memories[0].limit.min)
            assertNull(memories[0].limit.max)
        }
    }

    fun testMem1() = doTest { wasmModule ->
        listMemory(wasmModule).let { memories ->
            assertEquals(2, memories.size)
            assertEquals(0, memories[0].memoryIdx)
            assertEquals(1, memories[0].limit.min)
            assertEquals(1, memories[1].memoryIdx)
            assertNull(memories[0].limit.max)
        }
    }

    fun testMem2() = doTest { wasmModule ->
        listMemory(wasmModule).let { memories ->
            assertEquals(3, memories.size)
            assertEquals(0, memories[0].memoryIdx)
            assertEquals(1, memories[0].limit.min)
            assertFalse(memories[0].limit.hasMax)
            assertEquals("0x00", memories[0].limit.flag)
            assertEquals("i32", memories[0].limit.addressType)
            assertEquals(1, memories[1].memoryIdx)
            assertEquals(1, memories[1].memoryIdx)
            assertEquals(1, memories[1].limit.min)
            assertFalse(memories[1].limit.hasMax)
            assertEquals("0x00", memories[1].limit.flag)
            assertEquals("i32", memories[1].limit.addressType)
            assertEquals(2, memories[2].memoryIdx)
        }
    }

    fun testMem3() = doTest { wasmModule ->
        listMemory(wasmModule).let { memories ->
            assertEquals(3, memories.size)
            assertEquals(0, memories[0].memoryIdx)
            assertEquals(1, memories[1].memoryIdx)
            assertEquals(2, memories[2].memoryIdx)
        }
    }

    fun testMem4() = doTest { wasmModule ->
        listMemory(wasmModule).let { memories ->
            assertEquals(2, memories.size)
            assertEquals(0, memories[0].memoryIdx)
            assertEquals(1, memories[1].memoryIdx)
        }
    }

    fun testMem5() = doTest { wasmModule ->
        listMemory(wasmModule).let { memories ->
            assertEquals(3, memories.size)
            assertEquals(0, memories[0].memoryIdx)
            assertEquals("i32", memories[0].limit.addressType)
            assertEquals("i64", memories[1].limit.addressType)
            assertEquals("i32", memories[2].limit.addressType)
        }
    }

    private fun doTest(body: CommonByteBuffer.(WasmModule?) -> Unit) = doTest("memory", body)
}