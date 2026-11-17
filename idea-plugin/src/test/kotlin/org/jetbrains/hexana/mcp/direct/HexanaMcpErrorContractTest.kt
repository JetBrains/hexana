package org.jetbrains.hexana.mcp.direct

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
}
