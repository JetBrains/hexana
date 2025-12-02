package org.jetbrains.hexana.mcp

import org.junit.Ignore

class McpDataSegmentsReportTest: WasmTest() {
    // TODO: why?
    @Ignore
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
}