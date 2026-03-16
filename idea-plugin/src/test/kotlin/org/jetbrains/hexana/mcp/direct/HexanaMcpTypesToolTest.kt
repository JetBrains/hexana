package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class HexanaMcpTypesToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListTypesReturnsFunctionTypeEntriesForSimpleModule() {
        val fixture = createWasmFixture(
            "fixtures/direct/types/list_types.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (func (type 0) (param i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val result = callToolJsonArray("list_types", jsonPath(fixture.projectRelativePath))

        assertTrue(result.isNotEmpty())
        assertEquals(0, result.obj(0).int("typeidx"))
        assertEquals("func", result.obj(0).text("kind"))
        assertEquals("val", result.obj(0).array("params").obj(0).text("kind"))
        assertEquals("i32", result.obj(0).array("params").obj(0).text("type"))
        assertEquals("val", result.obj(0).array("retTypes").obj(0).text("kind"))
    }

    fun testGetTypesForIndicesReturnsRequestedTypeEntries() {
        val fixture = createWasmFixture(
            "fixtures/direct/types/get_types_for_indices.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (func (type 0) (param i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val result = callToolJsonArray(
            "get_types_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(0))
                })
            }
        )

        assertEquals(1, result.size)
        assertTrue(result.obj(0).toString().isNotBlank())
    }
}
