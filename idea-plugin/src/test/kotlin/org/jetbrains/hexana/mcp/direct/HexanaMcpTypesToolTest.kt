package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        assertEquals("i32", types.obj(0).array("params")[0].textValue())
        assertEquals("i32", types.obj(0).array("retTypes")[0].textValue())
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
        assertEquals("i64", types.obj(0).array("params")[0].textValue())
    }

    fun testListValueTypesReturnsVocabularyCountsAndSummary() {
        val fixture = createWasmFixture(
            "fixtures/direct/types/list_value_types.wasm",
            """
            (module
              (type (func (param i32 i64 f32 f64 externref funcref) (result i32)))
              (import "env" "g" (global externref))
              (import "env" "table" (table 1 funcref))
              (global (mut i64) (i64.const 0))
              (table 1 externref)
              (func (type 0) (param i32 i64 f32 f64 externref funcref) (result i32)
                (local i32 funcref v128)
                local.get 0))
            """.trimIndent()
        )

        val result = callToolJsonObject("list_value_types", jsonPath(fixture.projectRelativePath))
        val valueTypes = result.obj("valueTypes")

        assertEquals(listOf("i32", "i64", "f32", "f64"), valueTypes.array("scalar").map { it.textValue() })
        assertEquals(listOf("v128"), valueTypes.array("vector").map { it.textValue() })
        assertTrue(valueTypes.array("packed").isEmpty())
        assertTrue(valueTypes.array("reference").any {
            val reference = it as JsonObject
            reference.text("type") == "externref" && reference.text("refKind") == "extern"
        })
        assertTrue(valueTypes.array("reference").any {
            val reference = it as JsonObject
            reference.text("type") == "funcref" && reference.text("refKind") == "func"
        })

        val counts = result.obj("counts").obj("valueTypes")
        assertEquals(3, countFor(counts.array("scalar"), "i32"))
        assertEquals(2, countFor(counts.array("scalar"), "i64"))
        assertEquals(1, countFor(counts.array("vector"), "v128"))
        assertEquals(3, countFor(counts.array("reference"), "externref"))
        assertEquals(3, countFor(counts.array("reference"), "funcref"))

        val summary = result.obj("summary")
        assertFalse(summary["hasGC"]!!.jsonPrimitive.boolean)
        assertFalse(summary["hasPacked"]!!.jsonPrimitive.boolean)
        assertTrue(summary["hasSIMD"]!!.jsonPrimitive.boolean)
        assertTrue(summary["hasReferenceTypes"]!!.jsonPrimitive.boolean)
        assertFalse(summary["pureMVP"]!!.jsonPrimitive.boolean)
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

    private fun countFor(entries: kotlinx.serialization.json.JsonArray, type: String): Int {
        return entries
            .map { it as JsonObject }
            .single { it.text("type") == type }
            .int("count")
    }
}
