package org.jetbrains.hexana.mcp.direct

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import java.nio.file.Files
import java.nio.file.Path

abstract class HexanaMcpToolsetDirectTestBase : BasePlatformTestCase() {
    protected val json = Json { ignoreUnknownKeys = true }

    protected data class ProjectFileFixture(
        val file: VirtualFile,
        val projectRelativePath: String
    ) {
        val absolutePath: String
            get() = file.path
    }

    protected data class DirectToolCallResult(
        val isError: Boolean,
        val textPayloads: List<String>,
        val structuredContent: JsonObject?
    )

    protected fun createWasmFixture(pathInProject: String, wat: String): ProjectFileFixture {
        return createBinaryFixture(pathInProject, wat2wasm(wat))
    }

    protected fun createTextFixture(pathInProject: String, text: String): ProjectFileFixture {
        val projectBasePath = project.basePath ?: error("Project base path is not available")
        val fullPath = Path.of(projectBasePath).resolve(pathInProject)
        Files.createDirectories(fullPath.parent)
        Files.writeString(fullPath, text)

        val file = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fullPath)
                ?: error("Failed to refresh $fullPath")
        }
        return ProjectFileFixture(file, pathInProject)
    }

    protected fun callTool(toolName: String, input: JsonObject): DirectToolCallResult = runBlocking {
        val result = findTool(toolName).call(input)
        DirectToolCallResult(
            isError = result.isError,
            textPayloads = result.content.filterIsInstance<McpToolCallResultContent.Text>().map { it.text },
            structuredContent = extractStructuredContent(result)
        )
    }

    protected fun callToolText(toolName: String, input: JsonObject): String {
        val result = callTool(toolName, input)
        assertFalse("Tool call failed: $result", result.isError)
        assertEquals("Expected a single text payload for `$toolName`", 1, result.textPayloads.size)
        return result.textPayloads.single()
    }

    protected fun callToolJsonObject(toolName: String, input: JsonObject): JsonObject {
        val payload = callToolText(toolName, input)
        val parsed = parseToolJson(toolName, payload)
        return parsed as? JsonObject
            ?: assertionFailure("Expected `$toolName` to return a JSON object, but got ${jsonType(parsed)}. Payload: $payload")
    }

    protected fun callToolJsonArray(toolName: String, input: JsonObject): JsonArray {
        val payload = callToolText(toolName, input)
        val parsed = parseToolJson(toolName, payload)
        return parsed as? JsonArray
            ?: assertionFailure("Expected `$toolName` to return a JSON array, but got ${jsonType(parsed)}. Payload: $payload")
    }

    protected fun toolNames(): Set<String> {
        return com.intellij.mcpserver.McpToolsProvider.EP.extensionList
            .flatMap { it.getTools() }
            .map { it.descriptor.name }
            .toSet()
    }

    protected fun JsonObject.obj(name: String): JsonObject {
        val element = requireField(name)
        return element as? JsonObject
            ?: assertionFailure("Expected field `$name` to be a JSON object, but got ${jsonType(element)}. JSON: $this")
    }

    protected fun JsonObject.array(name: String): JsonArray {
        val element = requireField(name)
        return element as? JsonArray
            ?: assertionFailure("Expected field `$name` to be a JSON array, but got ${jsonType(element)}. JSON: $this")
    }

    protected fun JsonObject.int(name: String): Int {
        val primitive = requirePrimitive(name)
        return runCatching { primitive.int }
            .getOrElse { throw AssertionError("Expected field `$name` to be an integer, but got `$primitive`. JSON: $this", it) }
    }

    protected fun JsonObject.long(name: String): Long {
        val primitive = requirePrimitive(name)
        return runCatching { primitive.long }
            .getOrElse { throw AssertionError("Expected field `$name` to be a long, but got `$primitive`. JSON: $this", it) }
    }

    protected fun JsonObject.text(name: String): String = requirePrimitive(name).content

    protected fun JsonArray.obj(index: Int): JsonObject {
        if (index !in indices) {
            assertionFailure("Expected array index $index to exist, but size is $size. JSON: $this")
        }
        val element = get(index)
        return element as? JsonObject
            ?: assertionFailure("Expected array element at index $index to be a JSON object, but got ${jsonType(element)}. JSON: $this")
    }

    protected fun JsonElement.textValue(): String {
        val primitive = this as? JsonPrimitive
            ?: assertionFailure("Expected JSON primitive value, but got ${jsonType(this)}. JSON: $this")
        return primitive.content
    }

    protected fun jsonPath(path: String): JsonObject = JsonObject(mapOf("path" to JsonPrimitive(path)))

    private fun JsonObject.requireField(name: String): JsonElement {
        return get(name)
            ?: assertionFailure("Expected field `$name` in object with keys ${keys.sorted()}. JSON: $this")
    }

    private fun JsonObject.requirePrimitive(name: String): JsonPrimitive {
        val element = requireField(name)
        return element as? JsonPrimitive
            ?: assertionFailure("Expected field `$name` to be a JSON primitive, but got ${jsonType(element)}. JSON: $this")
    }

    private fun parseToolJson(toolName: String, payload: String): JsonElement {
        return runCatching { json.parseToJsonElement(payload) }
            .getOrElse { throw AssertionError("Expected `$toolName` to return valid JSON, but parsing failed. Payload: $payload", it) }
    }

    private fun jsonType(element: JsonElement): String = when (element) {
        is JsonObject -> "JsonObject"
        is JsonArray -> "JsonArray"
        is JsonPrimitive -> "JsonPrimitive"
    }

    private fun assertionFailure(message: String): Nothing = throw AssertionError(message)

    private fun createBinaryFixture(pathInProject: String, bytes: ByteArray): ProjectFileFixture {
        val projectBasePath = project.basePath ?: error("Project base path is not available")
        val fullPath = Path.of(projectBasePath).resolve(pathInProject)
        Files.createDirectories(fullPath.parent)
        Files.write(fullPath, bytes)

        val file = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fullPath)
                ?: error("Failed to refresh $fullPath")
        }
        return ProjectFileFixture(file, pathInProject)
    }

    private fun findTool(toolName: String): McpTool {
        return com.intellij.mcpserver.McpToolsProvider.EP.extensionList
            .flatMap { it.getTools() }
            .singleOrNull { it.descriptor.name == toolName }
            ?: error("MCP tool `$toolName` is not registered")
    }

    private fun extractStructuredContent(result: Any): JsonObject? {
        return runCatching {
            result.javaClass.methods
                .singleOrNull { it.name == "getStructuredContent" && it.parameterCount == 0 }
                ?.invoke(result) as? JsonObject
        }.getOrNull()
    }
}
