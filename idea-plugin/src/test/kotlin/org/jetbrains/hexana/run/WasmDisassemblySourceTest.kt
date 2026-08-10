package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import java.io.File

/**
 * Unit tests for [WasmDisassemblySource] — the disassembly engine powering the
 * "Wasm Disassembly" debugger tab.
 *
 * Tests run against real wasm artifacts already present in the repo so no
 * new test binaries are added. All assertions are pure (no IDE/CDP runtime).
 */
class WasmDisassemblySourceTest : UsefulTestCase() {

    private fun wasmParserResource(relativePath: String): File =
        File("../wasmParser/src/commonTest/resources/$relativePath")

    private fun debugInfoFixture(relativePath: String): File =
        File("../debugInfo/dwarf-fixtures/test-data/wasm32/$relativePath")

    // -----------------------------------------------------------------------
    // Availability
    // -----------------------------------------------------------------------

    fun testNullPathIsUnavailable() {
        val src = WasmDisassemblySource(null)
        assertFalse("null path must not be available", src.isAvailable())
    }

    fun testMissingPathIsUnavailable() {
        val src = WasmDisassemblySource("/nonexistent/totally/fake.wasm")
        assertFalse("missing file must not be available", src.isAvailable())
    }

    fun testValidWasmIsAvailable() {
        val file = wasmParserResource("loop.wasm")
        assertTrue("test resource must exist: ${file.absolutePath}", file.exists())
        val src = WasmDisassemblySource(file.absolutePath)
        assertTrue("valid wasm file must be available", src.isAvailable())
    }

    // -----------------------------------------------------------------------
    // Offset-to-function lookup
    // -----------------------------------------------------------------------

    fun testOffsetBeyondCodeSectionReturnsNull() {
        val file = wasmParserResource("loop.wasm")
        assertTrue(file.exists())
        val src = WasmDisassemblySource(file.absolutePath)
        assertTrue(src.isAvailable())
        // offset 0 is in the header/magic — before any code entry
        assertNull("offset 0 should not match any code entry", src.rowsForOffset(0))
    }

    fun testOffsetInsideFunctionReturnsRows() {
        val file = debugInfoFixture("dwarf-types.wasm")
        assertTrue("test resource must exist: ${file.absolutePath}", file.exists())
        val src = WasmDisassemblySource(file.absolutePath)
        assertTrue("dwarf-types.wasm must be available", src.isAvailable())

        // Find the first valid function by scanning at a known code-section offset.
        // We do a breadth-first search over a range of possible offsets.
        val bytes = file.readBytes()
        // Try offsets from 8 (after magic+version) up to the file size in steps;
        // the first offset that returns rows is within a code entry.
        var foundRows: List<WasmDisassemblyTableModel.Row>? = null
        var matchedOffset = -1
        for (off in 8 until minOf(bytes.size, 0x200000) step 16) {
            val result = src.rowsForOffset(off)
            if (result != null && result.first.isNotEmpty()) {
                foundRows = result.first
                matchedOffset = off
                break
            }
        }
        assertNotNull("should find at least one function body in dwarf-types.wasm", foundRows)
        assertTrue("found rows must be non-empty (offset=0x${matchedOffset.toString(16)})", foundRows!!.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Row offset monotonicity
    // -----------------------------------------------------------------------

    fun testRowOffsetsAreMonotonicallyNonDecreasing() {
        val file = debugInfoFixture("dwarf-types.wasm")
        assertTrue(file.exists())
        val src = WasmDisassemblySource(file.absolutePath)
        assertTrue(src.isAvailable())

        val bytes = file.readBytes()
        // Collect rows from the first function we can find
        var rows: List<WasmDisassemblyTableModel.Row>? = null
        for (off in 8 until minOf(bytes.size, 0x200000) step 16) {
            val result = src.rowsForOffset(off)
            if (result != null && result.first.size > 1) {
                rows = result.first
                break
            }
        }
        if (rows == null) return // no multi-instruction function found — skip

        // Singleton zero-operand opcodes carry no offset (-1) and are interleaved;
        // monotonicity applies to the offset-bearing rows only.
        val offsetBearing = rows.filter { it.offset >= 0 }
        for (i in 1 until offsetBearing.size) {
            assertTrue(
                "row offsets must be non-decreasing: ${offsetBearing[i].offset} < ${offsetBearing[i - 1].offset}",
                offsetBearing[i].offset >= offsetBearing[i - 1].offset,
            )
        }
        // Every function body ends with the singleton `end` opcode, so the listing
        // must contain at least one blank-offset row.
        assertTrue(
            "expected at least one singleton (blank-offset) row in the listing",
            rows.any { it.offset < 0 },
        )
    }

    // -----------------------------------------------------------------------
    // Caching: same result on second call, no re-parse
    // -----------------------------------------------------------------------

    fun testCachingReturnsSameRows() {
        val file = debugInfoFixture("dwarf-types.wasm")
        assertTrue(file.exists())
        val src = WasmDisassemblySource(file.absolutePath)
        assertTrue(src.isAvailable())

        val bytes = file.readBytes()
        var firstResult: Pair<List<WasmDisassemblyTableModel.Row>, Int>? = null
        var matchedOffset = -1
        for (off in 8 until minOf(bytes.size, 0x200000) step 16) {
            val r = src.rowsForOffset(off)
            if (r != null && r.first.isNotEmpty()) { firstResult = r; matchedOffset = off; break }
        }
        assertNotNull("must find a function", firstResult)

        // Call again with the same offset — must return identical list instance (cache hit)
        val secondResult = src.rowsForOffset(matchedOffset)
        assertNotNull(secondResult)
        assertSame(
            "second call must return the cached row list",
            firstResult!!.first,
            secondResult!!.first,
        )
    }
}
