package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class HexanaMcpExportsToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListExportsReturnsAllKindsWithIndices() {
        val fixture = createWasmFixture(
            "fixtures/direct/exports/list_exports.wasm",
            """
            (module
              (memory (export "mem") 1)
              (table (export "tab") 2 funcref)
              (global (export "glob") i32 (i32.const 0))
              (func (export "func")))
            """.trimIndent()
        )

        val result = callToolJsonObject("list_exports", jsonPath(fixture.projectRelativePath))
        val exports = result.array("exports")

        assertEquals(4, exports.size)
        assertEquals("mem", exports.obj(0).text("name"))
        assertEquals(WASM_EXPORT_KIND_MEMORY, exports.obj(0).int("kind"))
        assertEquals("tab", exports.obj(1).text("name"))
        assertEquals(WASM_EXPORT_KIND_TABLE, exports.obj(1).int("kind"))
        assertEquals("glob", exports.obj(2).text("name"))
        assertEquals(WASM_EXPORT_KIND_GLOBAL, exports.obj(2).int("kind"))
        assertEquals("func", exports.obj(3).text("name"))
        assertEquals(WASM_EXPORT_KIND_FUNCTION, exports.obj(3).int("kind"))
    }

    fun testListExportsProvidesStructuredContentForTypedResult() {
        val fixture = createWasmFixture(
            "fixtures/direct/exports/structured_list_exports.wasm",
            """
            (module
              (memory (export "mem") 1)
              (func (export "func")))
            """.trimIndent()
        )

        val result = callTool("list_exports", jsonPath(fixture.projectRelativePath))

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val exports = structuredContent.array("exports")
        assertEquals("mem", exports.obj(0).text("name"))
        assertEquals("func", exports.obj(1).text("name"))
    }

    fun testListExportedFunctionsReturnsMetadataSlice() {
        val fixture = createWasmFixture(
            "fixtures/direct/exports/list_exported_functions.wasm",
            """
            (module
              (func (export "a") (result i32)
                i32.const 1)
              (func (export "b") (result i32)
                i32.const 2))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "list_exported_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(0))
                })
            }
        )

        assertEquals(0, result.int("from"))
        assertEquals(0, result.int("to"))
        assertEquals(2, result.int("total"))
        assertEquals(1, result.array("returned").size)
        assertEquals("a", result.array("returned").obj(0).text("name"))
    }

    private companion object {
        // TODO should be moved to some kind of enum
        const val WASM_EXPORT_KIND_FUNCTION = 0
        const val WASM_EXPORT_KIND_TABLE = 1
        const val WASM_EXPORT_KIND_MEMORY = 2
        const val WASM_EXPORT_KIND_GLOBAL = 3
    }
}
