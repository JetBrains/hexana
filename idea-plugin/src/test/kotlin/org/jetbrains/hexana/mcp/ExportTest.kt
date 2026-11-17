package org.jetbrains.hexana.mcp

class ExportTest : WasmTest() {
    fun testExports0() = doExportTest { exports ->
        assertEquals(1, exports.size)
        assertEquals("f0", exports[0].name)
        assertEquals(0, exports[0].kind)
        assertEquals(0, exports[0].externIndex)
    }

    fun testExports1() = doExportTest { exports ->
        assertEquals(2, exports.size)
        assertEquals("go", exports[0].name)
        assertEquals(0, exports[0].kind)
        assertEquals(0, exports[0].externIndex)
        assertEquals("start", exports[1].name)
        assertEquals(0, exports[1].kind)
        assertEquals(0, exports[1].externIndex)
    }

    fun testExports2() = doExportTest { exports ->
        assertEquals(1, exports.size)
        assertEquals("external", exports[0].name)
        assertEquals(0, exports[0].kind)
        assertEquals(0, exports[0].externIndex)
    }

    fun testExports3() = doExportTest { exports ->
        assertEquals(1, exports.size)
        assertEquals("table", exports[0].name)
        assertEquals(1, exports[0].kind)
        assertEquals(0, exports[0].externIndex)
    }

    fun testExports4() = doExportTest { exports ->
        assertEquals(1, exports.size)
        assertEquals("counter", exports[0].name)
        assertEquals(3, exports[0].kind)
        assertEquals(0, exports[0].externIndex)
    }

    fun testExports5() = doExportTest { exports ->
        assertEquals(1, exports.size)
        assertEquals("memory32", exports[0].name)
        assertEquals(2, exports[0].kind)
        assertEquals(0, exports[0].externIndex)
    }

    fun testExports6() = doExportTest { exports ->
        assertEquals(1, exports.size)
        assertEquals("memory64", exports[0].name)
        assertEquals(2, exports[0].kind)
        assertEquals(0, exports[0].externIndex)
    }

    fun testExports7() = doExportTest { exports ->
        assertEquals(4, exports.size)
        assertEquals("f", exports[0].name)
        assertEquals(0, exports[0].externIndex)
        assertEquals(0, exports[0].kind)

        assertEquals("mem", exports[1].name)
        assertEquals(0, exports[1].externIndex)
        assertEquals(2, exports[1].kind)

        assertEquals("tab", exports[2].name)
        assertEquals(0, exports[2].externIndex)
        assertEquals(1, exports[2].kind)

        assertEquals("g", exports[3].name)
        assertEquals(0, exports[3].externIndex)
        assertEquals(3, exports[3].kind)
    }

    fun testExports8() = doExportTest { exports ->
        assertEquals(4, exports.size)
        assertEquals("A", exports[0].name)
        assertEquals(0, exports[0].externIndex)
        assertEquals(0, exports[0].kind)

        assertEquals("M", exports[1].name)
        assertEquals(0, exports[1].externIndex)
        assertEquals(2, exports[1].kind)

        assertEquals("T", exports[2].name)
        assertEquals(0, exports[2].externIndex)
        assertEquals(1, exports[2].kind)

        assertEquals("G", exports[3].name)
        assertEquals(0, exports[3].externIndex)
        assertEquals(3, exports[3].kind)
    }

    fun testExports9() = doExportTest { exports ->
        assertEquals(4, exports.size)

        assertEquals("A", exports[0].name)
        assertEquals(0, exports[0].externIndex)
        assertEquals(0, exports[0].kind)

        assertEquals("Mem", exports[1].name)
        assertEquals(0, exports[1].externIndex)
        assertEquals(2, exports[1].kind)

        assertEquals("func2", exports[2].name)
        assertEquals(0, exports[2].externIndex)
        assertEquals(0, exports[2].kind)

        assertEquals("glob2", exports[3].name)
        assertEquals(0, exports[3].externIndex)
        assertEquals(3, exports[3].kind)
    }
}