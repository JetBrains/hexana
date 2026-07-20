package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal suspend fun assertDefaultStdioSmoke(
    server: HexanaInstalledStdioClient,
    fixtureDirectory: Path,
    logger: Logger,
    label: String,
) {
    logger.info("Connected {} stdio integration client", label)

    logger.info("Requesting tools/list over {} stdio", label)
    val tools = server.client.listTools(ListToolsRequest())
    logger.info("Received tools over {} stdio: {}", label, tools.tools.map { it.name }.sorted())
    assertEquals(EXPECTED_DEFAULT_HEXANA_TOOL_NAMES, tools.tools.map { it.name }.sorted())

    val wasm = createSmokeWasmFile(
        fixtureDirectory = fixtureDirectory,
        name = "$label-summary.wasm",
        wat = """
        (module
          (memory 1)
          (func (export "main")))
        """.trimIndent()
    )
    logger.info("Created {} stdio wasm fixture at {}", label, wasm)

    logger.info("Requesting summarize_module over {} stdio", label)
    val overviewResult = server.client.callTool(
        name = "summarize_module",
        arguments = mapOf("path" to wasm.toString()),
        meta = emptyMap<String, Any?>()
    )
    logger.info(
        "Received summarize_module result over {} stdio isError={}, contentBlocks={}",
        label,
        overviewResult.isError,
        overviewResult.content.size
    )
    assertFalse(overviewResult.isError == true)
    val overviewText = overviewResult.content.single() as TextContent
    logger.info("Summarize module text payload over {} stdio: {}", label, overviewText.text)
    assertJsonTextMatchesStructured(overviewResult)
    assertEquals(
        1,
        overviewResult.structuredContent
            ?.get("functions")
            ?.jsonPrimitive
            ?.int
    )

    logger.info("Requesting list_memory over {} stdio", label)
    val memoryResult = server.client.callTool(
        name = "list_memory",
        arguments = mapOf("path" to wasm.toString()),
        meta = emptyMap<String, Any?>()
    )
    logger.info(
        "Received list_memory result over {} stdio isError={}, contentBlocks={}",
        label,
        memoryResult.isError,
        memoryResult.content.size
    )
    assertFalse(memoryResult.isError == true)
    val memoryText = memoryResult.content.single() as TextContent
    logger.info("List memory text payload over {} stdio: {}", label, memoryText.text)
    assertJsonTextMatchesStructured(memoryResult)
    assertEquals(
        0,
        memoryResult.structuredContent
            ?.get("memories")
            ?.jsonArray
            ?.first()
            ?.jsonObject
            ?.get("memoryIdx")
            ?.jsonPrimitive
            ?.int
    )

    assertTrue(
        server.stderr.contains("Starting stdio MCP transport"),
        "$label stdio server logs should stay on stderr. stderr was: ${server.stderr}"
    )
}

private fun createSmokeWasmFile(fixtureDirectory: Path, name: String, wat: String): Path {
    val wasm = fixtureDirectory.resolve(name)
    Files.write(wasm, wat2wasm(wat))
    return wasm
}

private fun assertJsonTextMatchesStructured(result: CallToolResult) {
    val textPayload = (result.content.single() as TextContent).text
    assertEquals(result.structuredContent, Json.parseToJsonElement(textPayload).jsonObject)
}
