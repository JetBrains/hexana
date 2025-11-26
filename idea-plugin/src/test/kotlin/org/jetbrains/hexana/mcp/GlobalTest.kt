package org.jetbrains.hexana.mcp

class GlobalTest: WasmTest() {

    fun testGlobal0(){
        doGlobalTest { globals ->
            assertEquals(3, globals.size)
            assertEquals(0, globals[0].globalIdx)
            assertEquals(1, globals[1].globalIdx)
            assertEquals(2, globals[2].globalIdx)
            assertEquals("import", globals[0].definedIn)
            assertEquals("env", globals[0].prefix)
            assertEquals("g0", globals[0].name)
            assertEquals("i32", globals[0].type)
            assertFalse(globals[0].mutability)
            assertEquals("global_section", globals[1].definedIn)
            assertEquals("i32", globals[1].type)
            assertEquals("global_section", globals[2].definedIn)
            assertFalse(globals[1].mutability)
            assertEquals("i64", globals[2].type)
            assertTrue(globals[2].mutability)
        }
    }
}