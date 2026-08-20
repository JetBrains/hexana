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

    /** The disassembly of a stop whose module id the caller does not know. */
    private fun WasmDisassemblySource.rowsForOffset(offset: Int): List<WasmDisassemblyTableModel.Row>? =
        analyse(moduleId = null, offset = offset)?.rows

    // -----------------------------------------------------------------------
    // Availability
    // -----------------------------------------------------------------------

    fun testNullPathIsUnavailable() {
        val src = WasmDisassemblySource(mapWasmFileToBuffer(null))
        assertFalse("null path must not be available", src.isAvailable())
    }

    fun testMissingPathIsUnavailable() {
        val src = WasmDisassemblySource(mapWasmFileToBuffer("/nonexistent/totally/fake.wasm"))
        assertFalse("missing file must not be available", src.isAvailable())
    }

    fun testValidWasmIsAvailable() {
        val file = wasmParserResource("loop.wasm")
        assertTrue("test resource must exist: ${file.absolutePath}", file.exists())
        val src = WasmDisassemblySource(mapWasmFileToBuffer(file.absolutePath))
        assertTrue("valid wasm file must be available", src.isAvailable())
    }

    // -----------------------------------------------------------------------
    // Offset-to-function lookup
    // -----------------------------------------------------------------------

    fun testOffsetBeyondCodeSectionReturnsNull() {
        val file = wasmParserResource("loop.wasm")
        assertTrue(file.exists())
        val src = WasmDisassemblySource(mapWasmFileToBuffer(file.absolutePath))
        assertTrue(src.isAvailable())
        // offset 0 is in the header/magic — before any code entry
        assertNull("offset 0 should not match any code entry", src.rowsForOffset(0))
    }

    fun testOffsetInsideFunctionReturnsRows() {
        val file = debugInfoFixture("dwarf-types.wasm")
        assertTrue("test resource must exist: ${file.absolutePath}", file.exists())
        val src = WasmDisassemblySource(mapWasmFileToBuffer(file.absolutePath))
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
            if (result != null && result.isNotEmpty()) {
                foundRows = result
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
        val src = WasmDisassemblySource(mapWasmFileToBuffer(file.absolutePath))
        assertTrue(src.isAvailable())

        val bytes = file.readBytes()
        // Collect rows from the first multi-instruction function we can find.
        var rows: List<WasmDisassemblyTableModel.Row>? = null
        for (off in 8 until minOf(bytes.size, 0x200000) step 16) {
            val result = src.rowsForOffset(off)
            if (result != null && result.size > 1) {
                rows = result
                break
            }
        }
        if (rows == null) return // no multi-instruction function found -- skip

        // Offset deduction fills in singleton offsets, so all rows should bear a
        // non-negative offset (deduction succeeded for this well-formed fixture).
        assertTrue(
            "all rows should have a deduced offset after singleton deduction",
            rows.all { it.offset >= 0 },
        )

        // Offsets across ALL rows must be strictly increasing (singletons are interleaved
        // and now have real offsets, so the full sequence is ordered).
        for (i in 1 until rows.size) {
            assertTrue(
                "row offsets must be strictly increasing: rows[$i].offset=${rows[i].offset} <= rows[${i - 1}].offset=${rows[i - 1].offset}",
                rows[i].offset > rows[i - 1].offset,
            )
        }

        // Singleton-offset assertion: the ground-truth function at offsets 0x72..0x7d
        // has two i32.add singletons. Query that function and verify their offsets are deduced.
        val computeRows = src.rowsForOffset(0x72)
        assertNotNull("must find function at 0x72 in dwarf-types.wasm", computeRows)
        // The two i32.add singletons should appear at 0x79 and 0x7c.
        val singletonOffsets = computeRows!!.map { it.offset }.filter { it == 0x79 || it == 0x7c }
        assertTrue(
            "expected deduced singleton offsets 0x79 and 0x7c in compute function; got ${computeRows.map { "0x${it.offset.toString(16)}" }}",
            singletonOffsets.containsAll(listOf(0x79, 0x7c)),
        )
    }

    // -----------------------------------------------------------------------
    // Caching: same result on second call, no re-parse
    // -----------------------------------------------------------------------

    fun testCachingReturnsSameRows() {
        val file = debugInfoFixture("dwarf-types.wasm")
        assertTrue(file.exists())
        val src = WasmDisassemblySource(mapWasmFileToBuffer(file.absolutePath))
        assertTrue(src.isAvailable())

        val bytes = file.readBytes()
        var firstResult: List<WasmDisassemblyTableModel.Row>? = null
        var matchedOffset = -1
        for (off in 8 until minOf(bytes.size, 0x200000) step 16) {
            val r = src.rowsForOffset(off)
            if (r != null && r.isNotEmpty()) { firstResult = r; matchedOffset = off; break }
        }
        assertNotNull("must find a function", firstResult)

        // Call again with the same offset — must return identical list instance (cache hit)
        val secondResult = src.rowsForOffset(matchedOffset)
        assertNotNull(secondResult)
        assertSame(
            "second call must return the cached row list",
            firstResult!!,
            secondResult!!,
        )
    }

    // -----------------------------------------------------------------------
    // Component-model binaries
    // -----------------------------------------------------------------------

    /**
     * A component embeds its core modules, and the gdbstub reports offsets relative
     * to the embedded module rather than to the component file — so the tab must
     * index the embedded modules and read the stub's `(module id, offset)` pair.
     *
     * Ground truth for `add.component.wasm` (magic at component offset 0xb):
     * `0x38 local.get 0; 0x3a local.get 1; 0x3c i32.add; 0x3d end`, module-relative.
     */
    fun testComponentResolvesEmbeddedCoreModule() {
        val file = wasmParserResource("component/add.component.wasm")
        assertTrue("test resource must exist: ${file.absolutePath}", file.exists())
        val src = WasmDisassemblySource(mapWasmFileToBuffer(file.absolutePath))

        assertTrue("component must be available", src.isAvailable())
        assertTrue("component must be recognised as one", src.isComponent())

        val analysis = src.analyse(moduleId = 0, offset = 0x3c)
        assertNotNull("module-relative offset must resolve in the embedded module", analysis)
        assertEquals(0, analysis!!.moduleId)
        val rendered: List<String> = analysis.rows.map { "0x${it.offset.toString(16)} ${it.instruction}" }
        assertEquals(
            listOf("0x38 local.get 0", "0x3a local.get 1", "0x3c i32.add", "0x3d end"),
            rendered,
        )
        assertNotNull("the embedded module must carry its own stack analyzer", analysis.stackAnalyzer)
    }

    /** The old behaviour: treating the stub's offset as component-absolute finds nothing. */
    fun testComponentAbsoluteOffsetDoesNotResolve() {
        val file = wasmParserResource("component/add.component.wasm")
        assertTrue(file.exists())
        val src = WasmDisassemblySource(mapWasmFileToBuffer(file.absolutePath))
        // 0x47 is the same i32.add counted from the component's magic instead.
        assertNull("component-absolute offsets must not resolve", src.analyse(moduleId = 0, offset = 0x47))
    }
}
