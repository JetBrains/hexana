package org.jetbrains.hexana.sourcemap

import com.intellij.testFramework.UsefulTestCase
import java.nio.file.Path

class SourceMapParserTest : UsefulTestCase() {

    // Two segments, fields are [byteOffset, fileIdx, sourceLine, sourceCol] deltas:
    //   "QAAA" = [8, 0, 0, 0]  -> offset 8,  0-based line 0
    //   "QAEA" = [8, 0, 2, 0]  -> offset 16, 0-based line 2
    private val mapJson = """
        {"version":3,"sources":["src/lib.rs"],"names":[],"mappings":"QAAA,QAEA"}
    """.trimIndent()

    fun testLinesAreNormalizedToOneBased() {
        val map = parseSourceMap(Path.of("/tmp/app.wasm.map"), mapJson)

        val first = map.getSourceLocationForOffset(10)
        assertNotNull(first)
        assertTrue(first!!.filePath.endsWith("lib.rs"))
        // 0-based VLQ line 0 -> 1-based HexanaSourceLocation line 1.
        assertEquals(1, first.line)

        val second = map.getSourceLocationForOffset(20)
        assertNotNull(second)
        // 0-based VLQ line 2 -> 1-based line 3.
        assertEquals(3, second!!.line)
    }

    fun testOffsetBeforeFirstMappingResolvesToNothing() {
        val map = parseSourceMap(Path.of("/tmp/app.wasm.map"), mapJson)
        assertNull(map.getSourceLocationForOffset(3))
    }
}
