package org.jetbrains.hexana.mcp.direct

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

        val result = callToolJsonArray("list_exports", jsonPath(fixture.projectRelativePath))

        assertEquals(4, result.size)
        assertEquals("mem", result.obj(0).text("name"))
        assertEquals(WASM_EXPORT_KIND_MEMORY, result.obj(0).int("kind"))
        assertEquals("tab", result.obj(1).text("name"))
        assertEquals(WASM_EXPORT_KIND_TABLE, result.obj(1).int("kind"))
        assertEquals("glob", result.obj(2).text("name"))
        assertEquals(WASM_EXPORT_KIND_GLOBAL, result.obj(2).int("kind"))
        assertEquals("func", result.obj(3).text("name"))
        assertEquals(WASM_EXPORT_KIND_FUNCTION, result.obj(3).int("kind"))
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
