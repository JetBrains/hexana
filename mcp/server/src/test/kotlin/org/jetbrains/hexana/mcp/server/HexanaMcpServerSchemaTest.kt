package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import kotlin.test.Test
import kotlin.test.assertEquals

class HexanaMcpServerSchemaTest {
    @Test
    fun wasmInputPropertiesDescribeRuntimeValidation() {
        val summarizeModuleSchema = createHexanaMcpServer()
            .tools
            .getValue("summarize_module")
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?: error("Missing summarize_module input schema properties")

        assertEquals(
            "Path to a local `.wasm` file readable by the Hexana MCP server. " +
                "Runtime validation requires exactly one of `path`, `bytesBase64`, or `bytes`.",
            summarizeModuleSchema.getValue("path").jsonObject.getValue("description").jsonPrimitive.content
        )
        assertEquals(
            "Base64-encoded `.wasm` bytes. " +
                "Runtime validation requires exactly one of `path`, `bytesBase64`, or `bytes`.",
            summarizeModuleSchema.getValue("bytesBase64").jsonObject.getValue("description").jsonPrimitive.content
        )
        assertEquals(
            "Raw `.wasm` bytes as integers in the range 0..255. " +
                "Runtime validation requires exactly one of `path`, `bytesBase64`, or `bytes`.",
            summarizeModuleSchema.getValue("bytes").jsonObject.getValue("description").jsonPrimitive.content
        )
    }

    @Test
    fun wasmInputPropertiesCarryInlineByteLimits() {
        val summarizeModuleSchema = createHexanaMcpServer()
            .tools
            .getValue("summarize_module")
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?: error("Missing summarize_module input schema properties")

        val bytesBase64 = summarizeModuleSchema.getValue("bytesBase64").jsonObject
        assertEquals(
            ((HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES + 2) / 3) * 4,
            bytesBase64.getValue("maxLength").jsonPrimitive.int
        )

        val bytes = summarizeModuleSchema.getValue("bytes").jsonObject
        assertEquals(HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES, bytes.getValue("maxItems").jsonPrimitive.int)

        val byteItem = bytes.getValue("items").jsonObject
        assertEquals(0, byteItem.getValue("minimum").jsonPrimitive.int)
        assertEquals(255, byteItem.getValue("maximum").jsonPrimitive.int)
    }

    @Test
    fun nullableInputPropertiesAllowNullInGeneratedSchemas() {
        val tools = createHexanaMcpServer().tools

        val summarizeModuleSchema = tools
            .getValue("summarize_module")
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?: error("Missing summarize_module input schema properties")
        assertEquals(listOf("string", "null"), schemaTypes(summarizeModuleSchema.getValue("path").jsonObject))
        assertEquals(listOf("string", "null"), schemaTypes(summarizeModuleSchema.getValue("bytesBase64").jsonObject))
        assertEquals(listOf("array", "null"), schemaTypes(summarizeModuleSchema.getValue("bytes").jsonObject))

        val listDataSegmentsSchema = tools
            .getValue("list_data_segments")
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?: error("Missing list_data_segments input schema properties")
        val segmentRangeProperties = listDataSegmentsSchema
            .getValue("segmentRange")
            .jsonObject
            .getValue("properties")
            .jsonObject
        assertEquals(listOf("integer", "null"), schemaTypes(segmentRangeProperties.getValue("to").jsonObject))
        assertEquals(listOf("integer", "null"), schemaTypes(listDataSegmentsSchema.getValue("minByteLength").jsonObject))
    }

    @Test
    fun listExportedFunctionsSchemaMakesInclusiveBoundsExplicit() {
        val listExportedFunctionsSchema = createHexanaMcpServer()
            .tools
            .getValue("list_exported_functions")
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?.getValue("entriesRange")
            ?.jsonObject
            ?: error("Missing list_exported_functions entriesRange schema")

        assertEquals(
            "Closed zero-based export slice. Pass `entriesRange = { from, to }`; both bounds are inclusive.",
            listExportedFunctionsSchema.getValue("description").jsonPrimitive.content
        )
    }

    @Test
    fun closedRangeBoundsAreNonNegative() {
        val entriesRange = createHexanaMcpServer()
            .tools
            .getValue("list_exported_functions")
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?.getValue("entriesRange")
            ?.jsonObject
            ?: error("Missing list_exported_functions entriesRange schema")

        val properties = entriesRange.getValue("properties").jsonObject
        assertEquals(0, properties.getValue("from").jsonObject.getValue("minimum").jsonPrimitive.int)
        assertEquals(0, properties.getValue("to").jsonObject.getValue("minimum").jsonPrimitive.int)
    }

    @Test
    fun inputDefaultsArePresentInGeneratedSchemas() {
        val tools = createHexanaMcpServer().tools

        val listFunctionsProperties = tools
            .getValue("list_functions")
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?: error("Missing list_functions input schema properties")
        assertEquals(0, listFunctionsProperties.getValue("offset").jsonObject.getValue("default").jsonPrimitive.int)
        assertEquals(200, listFunctionsProperties.getValue("limit").jsonObject.getValue("default").jsonPrimitive.int)

        val listDataSegmentsProperties = tools
            .getValue("list_data_segments")
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?: error("Missing list_data_segments input schema properties")
        assertEquals(
            false,
            listDataSegmentsProperties.getValue("includeExports").jsonObject.getValue("default").jsonPrimitive.boolean,
        )
        assertEquals(
            true,
            listDataSegmentsProperties.getValue("includeGlobals").jsonObject.getValue("default").jsonPrimitive.boolean,
        )
    }

    private fun schemaTypes(schema: JsonObject): List<String> {
        return schema.getValue("type").jsonArray.map { it.jsonPrimitive.content }
    }
}
