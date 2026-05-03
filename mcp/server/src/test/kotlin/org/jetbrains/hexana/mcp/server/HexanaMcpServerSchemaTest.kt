package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaMcpServerSchemaTest {
    @Test
    fun generatedToolsAreMarkedReadOnly() {
        val annotations = createHexanaMcpServer()
            .tools
            .getValue("query_artifacts")
            .tool
            .annotations
            ?: error("Missing query_artifacts annotations")

        assertTrue(annotations.readOnlyHint == true)
        assertFalse(annotations.destructiveHint == true)
        assertTrue(annotations.idempotentHint == true)
    }

    @Test
    fun registryExposesOnlyQueryArtifacts() {
        assertEquals(listOf("query_artifacts"), createHexanaMcpServer().tools.keys.sorted())
    }

    @Test
    fun wasmInputPropertiesDescribeRuntimeValidation() {
        val schema = queryArtifactsInputSchema()

        assertEquals(
            "Path to a local `.wasm` file readable by the Hexana MCP server. " +
                "Runtime validation requires exactly one of `path`, `bytesBase64`, or `bytes`.",
            schema.getValue("path").jsonObject.getValue("description").jsonPrimitive.content
        )
        assertEquals(
            "Base64-encoded `.wasm` bytes. " +
                "Runtime validation requires exactly one of `path`, `bytesBase64`, or `bytes`.",
            schema.getValue("bytesBase64").jsonObject.getValue("description").jsonPrimitive.content
        )
        assertEquals(
            "Raw `.wasm` bytes as integers in the range 0..255. " +
                "Runtime validation requires exactly one of `path`, `bytesBase64`, or `bytes`.",
            schema.getValue("bytes").jsonObject.getValue("description").jsonPrimitive.content
        )
    }

    @Test
    fun wasmInputPropertiesCarryInlineByteLimits() {
        val schema = queryArtifactsInputSchema()

        val bytesBase64 = schema.getValue("bytesBase64").jsonObject
        assertEquals(
            ((HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES + 2) / 3) * 4,
            bytesBase64.getValue("maxLength").jsonPrimitive.int
        )

        val bytes = schema.getValue("bytes").jsonObject
        assertEquals(HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES, bytes.getValue("maxItems").jsonPrimitive.int)

        val byteItem = bytes.getValue("items").jsonObject
        assertEquals(0, byteItem.getValue("minimum").jsonPrimitive.int)
        assertEquals(255, byteItem.getValue("maximum").jsonPrimitive.int)
    }

    @Test
    fun nullableInputPropertiesAllowNullInGeneratedSchemas() {
        val schema = queryArtifactsInputSchema()

        assertEquals(listOf("string", "null"), schemaTypes(schema.getValue("path").jsonObject))
        assertEquals(listOf("string", "null"), schemaTypes(schema.getValue("bytesBase64").jsonObject))
        assertEquals(listOf("array", "null"), schemaTypes(schema.getValue("bytes").jsonObject))
        assertEquals(listOf("string", "null"), schemaTypes(schema.getValue("baselinePath").jsonObject))
        assertEquals(listOf("array", "null"), schemaTypes(schema.getValue("entityTypes").jsonObject))
        assertEquals(listOf("array", "null"), schemaTypes(schema.getValue("query").jsonObject))
        assertEquals(listOf("string", "null"), schemaTypes(schema.getValue("startName").jsonObject))
        assertEquals(listOf("integer", "null"), schemaTypes(schema.getValue("startFuncidx").jsonObject))
        assertEquals(listOf("array", "null"), schemaTypes(schema.getValue("relations").jsonObject))
    }

    @Test
    fun inputDefaultsArePresentInGeneratedSchemas() {
        val schema = queryArtifactsInputSchema()

        assertEquals("overview", schema.getValue("operation").jsonObject.getValue("default").jsonPrimitive.content)
        assertEquals(50, schema.getValue("limit").jsonObject.getValue("default").jsonPrimitive.int)
        assertEquals("summary", schema.getValue("detail").jsonObject.getValue("default").jsonPrimitive.content)
    }

    @Test
    fun limitBoundsArePresentInGeneratedSchema() {
        val limit = queryArtifactsInputSchema().getValue("limit").jsonObject

        assertEquals(1, limit.getValue("minimum").jsonPrimitive.int)
        assertEquals(200, limit.getValue("maximum").jsonPrimitive.int)
    }

    private fun queryArtifactsInputSchema(): JsonObject {
        return createHexanaMcpServer()
            .tools
            .getValue("query_artifacts")
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?: error("Missing query_artifacts input schema properties")
    }

    private fun schemaTypes(schema: JsonObject): List<String> {
        return schema.getValue("type").jsonArray.map { it.jsonPrimitive.content }
    }
}
