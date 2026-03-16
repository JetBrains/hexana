package org.jetbrains.hexana.mcp.direct

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

        val result = callToolJsonArray("list_element_segments", jsonPath(fixture.projectRelativePath))

        assertEquals(1, result.size)
        assertEquals(0, result.obj(0).int("elementidx"))
        assertEquals(0, result.obj(0).int("kind"))
        assertEquals("active", result.obj(0).text("mode"))
        assertEquals("funcref", result.obj(0).text("elementType"))
        assertEquals(listOf("0", "1"), result.obj(0).array("elements").map { it.textValue() })
    }
}
