package org.jetbrains.hexana.mcp


class MemoryTest : WasmTest() {
    fun testMem0() = doMemTest { memories ->
        assertEquals(1, memories.size)
        assertEquals(0, memories[0].memoryIdx)
        assertEquals(1, memories[0].limit.min)
        assertNull(memories[0].limit.max)
    }

    fun testMem1() = doMemTest { memories ->
        assertEquals(2, memories.size)
        assertEquals(0, memories[0].memoryIdx)
        assertEquals(1, memories[0].limit.min)
        assertEquals(1, memories[1].memoryIdx)
        assertNull(memories[0].limit.max)
    }

    fun testMem2() = doMemTest { memories ->
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

    fun testMem3() = doMemTest { memories ->
        assertEquals(3, memories.size)
        assertEquals(0, memories[0].memoryIdx)
        assertEquals(1, memories[1].memoryIdx)
        assertEquals(2, memories[2].memoryIdx)
    }

    fun testMem4() = doMemTest { memories ->
        assertEquals(2, memories.size)
        assertEquals(0, memories[0].memoryIdx)
        assertEquals(1, memories[1].memoryIdx)
    }

    fun testMem5() = doMemTest { memories ->
        assertEquals(3, memories.size)
        assertEquals(0, memories[0].memoryIdx)
        assertEquals("i32", memories[0].limit.addressType)
        assertEquals("i64", memories[1].limit.addressType)
        assertEquals("i32", memories[2].limit.addressType)
    }

    fun testMem6() = doMemTest { memories ->
        assertEquals(5, memories.size)
        assertEquals(0, memories[0].memoryIdx)
        assertEquals("i32", memories[0].limit.addressType)
        assertEquals(5, memories[3].limit.min)
    }

    fun testMem7() = doMemTest { memories ->
        assertEquals(5, memories.size)
        assertEquals(0, memories[0].memoryIdx)
        assertEquals("i32", memories[0].limit.addressType)
        assertEquals("i64", memories[3].limit.addressType)
        assertEquals("3..3", memories[4].limit.range)
    }

}