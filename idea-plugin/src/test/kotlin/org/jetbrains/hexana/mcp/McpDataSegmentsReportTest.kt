package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.mcp.query.readDataSegments

class McpDataSegmentsReportTest: WasmTest() {
    fun testSegment0() = doListDataSegments { report ->
        assertEquals(1, report.segments.size)
        assertEquals(1, report.memory.size)
        assertEquals("i32", report.memory[0].limits.addressType)
        assertEquals("memory_section", report.memory[0].definedIn)
        assertEquals(0, report.globals.size)
        assertEquals(1, report.segments.size)
        assertEquals("active", report.segments[0].mode)
        assertEquals("48 65 6c 6c 6f", report.segments[0].hexPreview)
        assertEquals("Hello", report.segments[0].utf8Preview)
        assertTrue( report.segments[0].rawAvailable)
        assertEquals(1, report.summary.totalSegments)
        assertEquals(1, report.summary.activeSegments)
        assertEquals(0, report.summary.passiveSegments)
        assertEquals(1, report.summary.memories)
    }

    fun testSegmentRangeUsesHalfOpenBounds() = doTest("listDataSegments") { wasmModule ->
        val firstOnly = readDataSegments(
            segmentRange = 0 until 1,
            wasmModule = wasmModule!!,
            includeExports = false,
            includeGlobals = false,
            minByteLength = 0,
        )
        assertEquals(1, firstOnly.segments.size)
        assertEquals("First", firstOnly.segments.single().utf8Preview)
        assertEquals(2, firstOnly.summary.totalSegments)

        val secondOnly = readDataSegments(
            segmentRange = 1 until 2,
            wasmModule = wasmModule!!,
            includeExports = false,
            includeGlobals = false,
            minByteLength = 0,
        )
        assertEquals(1, secondOnly.segments.size)
        assertEquals("Second", secondOnly.segments.single().utf8Preview)
        assertEquals(2, secondOnly.summary.totalSegments)
    }

    fun testSegmentRangeExplicitBoundsMatchHalfOpenContract() = doTest("listDataSegments") { wasmModule ->
        val report = readDataSegments(
            segmentStartInclusive = 0,
            segmentEndExclusive = 1,
            wasmModule = wasmModule!!,
            includeExports = false,
            includeGlobals = false,
            minByteLength = 0,
        )

        assertEquals(1, report.segments.size)
        assertEquals("First", report.segments.single().utf8Preview)
        assertEquals(2, report.summary.totalSegments)
    }
}
