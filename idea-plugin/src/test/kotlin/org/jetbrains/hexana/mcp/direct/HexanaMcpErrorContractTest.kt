package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class HexanaMcpErrorContractTest : HexanaMcpToolsetDirectTestBase() {
    fun testSummarizeModuleReturnsErrorPayloadForMissingFile() {
        val result = callTool("summarize_module", jsonPath("fixtures/direct/missing/nope.wasm"))

        assertTrue(result.isError)
        assertEquals("File not found", result.textPayloads.single())
        assertNull(result.structuredContent)
    }

    fun testSummarizeModuleReturnsErrorPayloadForNonWasmFile() {
        val fixture = createTextFixture("fixtures/direct/errors/not_wasm.txt", "not wasm")

        val result = callTool("summarize_module", jsonPath(fixture.projectRelativePath))

        assertTrue(result.isError)
        assertEquals("Not a .wasm file", result.textPayloads.single())
        assertNull(result.structuredContent)
    }

    fun testSummarizeModuleReturnsErrorPayloadForMalformedWasmFile() {
        val fixture = createTextFixture("fixtures/direct/errors/malformed.wasm", "not actually wasm")

        val result = callTool("summarize_module", jsonPath(fixture.projectRelativePath))

        assertTrue(result.isError)
        assertEquals("Failed to parse .wasm file", result.textPayloads.single())
        assertNull(result.structuredContent)
    }

    fun testListDataReturnsErrorPayloadForInvalidRange() {
        val fixture = createWasmFixture(
            "fixtures/direct/errors/invalid_range.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 0) "Hi"))
            """.trimIndent()
        )

        val result = callTool(
            "list_data",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(5))
                    put("to", JsonPrimitive(1))
                })
            }
        )

        assertTrue(result.isError)
        assertEquals("Invalid segmentRange `to` must be >= `from`", result.textPayloads.single())
        assertNull(result.structuredContent)
    }
}
