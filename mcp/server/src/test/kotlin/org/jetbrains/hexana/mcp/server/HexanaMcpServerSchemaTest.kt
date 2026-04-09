package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            "Closed zero-based export slice. Both `from` and `to` are required and inclusive.",
            listExportedFunctionsSchema.getValue("description").jsonPrimitive.content
        )
    }
}
