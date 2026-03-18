package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class HexanaMcpDataToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListDataReturnsRangeMetadataAndEntries() {
        val fixture = createDataFixture("fixtures/direct/data/list_data.wasm")

        val result = callToolJsonObject(
            "list_data",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(0))
                })
            }
        )

        assertEquals(0, result.int("from"))
        assertEquals(0, result.int("to"))
        assertEquals(1, result.array("returned").size)
        assertEquals(1, result.int("total_segments"))
        assertEquals("SGVsbG8=", result.array("returned").obj(0).text("data"))
        assertTrue(result.array("returned").obj(0).text("expression").contains("i32.const"))
    }

    fun testListDataSegmentsReturnsSegmentPreviewAndSummary() {
        val fixture = createDataFixture("fixtures/direct/data/list_data_segments.wasm")

        val result = callToolJsonObject(
            "list_data_segments",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("segmentRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(1))
                })
                put("includeExports", JsonPrimitive(false))
                put("includeGlobals", JsonPrimitive(true))
            }
        )

        assertEquals(1, result.array("segments").size)
        assertEquals("active", result.array("segments").obj(0).text("mode"))
        assertEquals("Hello", result.array("segments").obj(0).text("utf8Preview"))
        assertTrue(result.array("memory").isNotEmpty())
        assertEquals(1, result.obj("summary").int("totalSegments"))
        assertEquals(1, result.obj("summary").int("activeSegments"))
        assertEquals(0, result.obj("summary").int("passiveSegments"))
    }

    private fun createDataFixture(pathInProject: String) = createWasmFixture(
        pathInProject,
        """
        (module
          (memory (export "memory") 1)
          (data (i32.const 0) "Hello"))
        """.trimIndent()
    )
}
