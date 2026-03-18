package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
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

        val result = callToolJsonObject("list_types", jsonPath(fixture.projectRelativePath))
        val types = result.array("types")

        assertTrue(types.isNotEmpty())
        assertEquals(0, types.obj(0).int("typeidx"))
        assertEquals("func", types.obj(0).text("kind"))
        assertEquals("val", types.obj(0).array("params").obj(0).text("kind"))
        assertEquals("i32", types.obj(0).array("params").obj(0).text("type"))
        assertFalse(types.obj(0).array("params").obj(0).containsKey("typeidx"))
        assertEquals("val", types.obj(0).array("retTypes").obj(0).text("kind"))
    }

    fun testListTypesProvidesStructuredContentForTypedResult() {
        val fixture = createWasmFixture(
            "fixtures/direct/types/structured_list_types.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (func (type 0) (param i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val result = callTool("list_types", jsonPath(fixture.projectRelativePath))

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val types = structuredContent.array("types")
        assertEquals(1, types.size)
        assertEquals(0, types.obj(0).int("typeidx"))
        assertEquals("func", types.obj(0).text("kind"))
    }

    fun testGetTypesForIndicesReturnsRequestedTypeEntries() {
        val fixture = createWasmFixture(
            "fixtures/direct/types/get_types_for_indices.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (type (func (param i64)))
              (func (type 0) (param i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "get_types_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val types = result.array("types")

        assertEquals(1, types.size)
        assertEquals(1, types.obj(0).int("typeidx"))
        assertEquals("func", types.obj(0).text("kind"))
        assertEquals("val", types.obj(0).array("params").obj(0).text("kind"))
        assertEquals("i64", types.obj(0).array("params").obj(0).text("type"))
        assertFalse(types.obj(0).array("params").obj(0).containsKey("typeidx"))
    }

    fun testGetTypesForIndicesProvidesStructuredContentForTypedResult() {
        val fixture = createWasmFixture(
            "fixtures/direct/types/structured_get_types_for_indices.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (type (func (param i64)))
              (func (type 0) (param i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val result = callTool(
            "get_types_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val types = structuredContent.array("types")
        assertEquals(1, types.size)
        assertEquals(1, types.obj(0).int("typeidx"))
        assertEquals("func", types.obj(0).text("kind"))
    }
}
