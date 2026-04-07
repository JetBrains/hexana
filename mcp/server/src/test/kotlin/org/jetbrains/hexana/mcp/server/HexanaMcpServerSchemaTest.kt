package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HexanaMcpServerSchemaTest {
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
