package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject

@org.junit.Ignore("Low-level MCP tools are no longer exposed; use query_artifacts direct tests.")
class HexanaMcpElementSegmentsToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListElementSegmentsReturnsActiveSegmentDetails() {
        val fixture = createWasmFixture(
            "fixtures/direct/elements/list_element_segments.wasm",
            """
            (module
              (table 2 funcref)
              (func)
              (func)
              (elem (i32.const 0) func 0 1))
            """.trimIndent()
        )

        val result = callToolJsonObject("list_element_segments", jsonPath(fixture.projectRelativePath))
        val segments = result.array("segments")

        assertEquals(1, segments.size)
        assertEquals(0, segments.obj(0).int("elementidx"))
        assertEquals(0, segments.obj(0).int("kind"))
        assertEquals("active", segments.obj(0).text("mode"))
        assertEquals("funcref", segments.obj(0).text("elementType"))
        assertEquals(listOf("0", "1"), segments.obj(0).array("elements").map { it.textValue() })
    }

    fun testListElementSegmentsProvidesStructuredContentForTypedResult() {
        val fixture = createWasmFixture(
            "fixtures/direct/elements/structured_list_element_segments.wasm",
            """
            (module
              (table 2 funcref)
              (func)
              (func)
              (elem (i32.const 0) func 0 1))
            """.trimIndent()
        )

        val result = callTool("list_element_segments", jsonPath(fixture.projectRelativePath))

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val segments = structuredContent.array("segments")
        assertEquals(0, segments.obj(0).int("elementidx"))
        assertEquals(listOf("0", "1"), segments.obj(0).array("elements").map { it.textValue() })
    }
}
