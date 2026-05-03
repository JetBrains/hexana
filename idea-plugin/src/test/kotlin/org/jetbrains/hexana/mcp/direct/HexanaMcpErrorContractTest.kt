package org.jetbrains.hexana.mcp.direct

class HexanaMcpErrorContractTest : HexanaMcpToolsetDirectTestBase() {
    fun testQueryArtifactsReturnsErrorPayloadForMissingFile() {
        val result = callTool("query_artifacts", jsonPath("fixtures/direct/missing/nope.wasm"))

        assertTrue(result.isError)
        assertEquals("File not found", result.textPayloads.single())
        assertNull(result.structuredContent)
    }

    fun testQueryArtifactsReturnsErrorPayloadForNonWasmFile() {
        val fixture = createTextFixture("fixtures/direct/errors/not_wasm.txt", "not wasm")

        val result = callTool("query_artifacts", jsonPath(fixture.projectRelativePath))

        assertTrue(result.isError)
        assertEquals("Not a .wasm file", result.textPayloads.single())
        assertNull(result.structuredContent)
    }

    fun testQueryArtifactsReturnsErrorPayloadForMalformedWasmFile() {
        val fixture = createTextFixture("fixtures/direct/errors/malformed.wasm", "not actually wasm")

        val result = callTool("query_artifacts", jsonPath(fixture.projectRelativePath))

        assertTrue(result.isError)
        assertEquals("Failed to parse .wasm file", result.textPayloads.single())
        assertNull(result.structuredContent)
    }

}
