package org.jetbrains.hexana.mcp.direct

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class HexanaMcpStructuredOutputContractTest : HexanaMcpToolsetDirectTestBase() {
    private val sdkJson = Json { ignoreUnknownKeys = true }

    fun testStructuredOutputUsesSdkCompatibleJsonObjectAndSerializes() {
        runBlocking {
            val fixture = createWasmFixture(
                pathInProject = "module.wasm",
                wat = """
                    (module
                      (memory 1)
                      (func (export "main") (result i32)
                        i32.const 42))
                """.trimIndent()
            )

            val rawResult = findRawTool("summarize_module").call(jsonPath(fixture.projectRelativePath))
            assertFalse("summarize_module returned an MCP error", rawResult.isError)

            val structuredContent = (rawResult.structuredContent
                ?: fail("Expected structuredContent to be present for summarize_module")) as kotlinx.serialization.json.JsonObject

            val sdkJsonObjectClass = Class.forName(
                "kotlinx.serialization.json.JsonObject",
                true,
                McpToolCallResult::class.java.classLoader
            )

            assertTrue(
                "structuredContent class ${structuredContent.javaClass.name} from ${structuredContent.javaClass.classLoader} is not compatible with MCP SDK JsonObject from ${sdkJsonObjectClass.classLoader}",
                sdkJsonObjectClass.isInstance(structuredContent)
            )

            val sdkResult = CallToolResult(content = emptyList(), structuredContent = structuredContent)
            val encoded = sdkJson.encodeToString(CallToolResult.serializer(), sdkResult)

            assertTrue(encoded, encoded.contains("\"structuredContent\""))
            assertTrue(encoded, encoded.contains("\"types\""))
            assertTrue(encoded, encoded.contains("\"moduleSize\""))
        }
    }

    private fun findRawTool(toolName: String): McpTool {
        return com.intellij.mcpserver.McpToolsProvider.EP.extensionList
            .flatMap { it.getTools() }
            .singleOrNull { it.descriptor.name == toolName }
            ?: error("MCP tool `$toolName` is not registered")
    }
}
