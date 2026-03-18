package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class HexanaMcpGlobalsToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListGlobalsReturnsImportedAndDefinedGlobals() {
        val fixture = createGlobalsFixture("fixtures/direct/globals/list_globals.wasm")

        val result = callToolJsonObject("list_globals", jsonPath(fixture.projectRelativePath))
        val globals = result.array("globals")

        assertEquals(2, globals.size)
        assertEquals(0, globals.obj(0).int("globalIdx"))
        assertEquals("env", globals.obj(0).text("prefix"))
        assertEquals("seed", globals.obj(0).text("name"))
        assertEquals("i32", globals.obj(0).text("type"))
        assertEquals("import", globals.obj(0).text("definedIn"))
        assertEquals(1, globals.obj(1).int("globalIdx"))
        assertEquals("i64", globals.obj(1).text("type"))
        assertEquals("global_section", globals.obj(1).text("definedIn"))
    }

    fun testListGlobalsProvidesStructuredContentForTypedResult() {
        val fixture = createGlobalsFixture("fixtures/direct/globals/structured_list_globals.wasm")

        val result = callTool("list_globals", jsonPath(fixture.projectRelativePath))

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val globals = structuredContent.array("globals")
        assertEquals(2, globals.size)
        assertEquals(0, globals.obj(0).int("globalIdx"))
        assertEquals(1, globals.obj(1).int("globalIdx"))
    }

    fun testGetGlobalsForIndicesReturnsOnlySelectedGlobals() {
        val fixture = createGlobalsFixture("fixtures/direct/globals/get_globals.wasm")

        val result = callToolJsonArray(
            "get_globals_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )

        assertEquals(1, result.size)
        assertEquals(1, result.obj(0).int("globalIdx"))
        assertEquals("i64", result.obj(0).text("type"))
        assertTrue(result.obj(0).text("expression").contains("i64.const"))
    }

    private fun createGlobalsFixture(pathInProject: String) = createWasmFixture(
        pathInProject,
        """
        (module
          (import "env" "seed" (global i32))
          (global (export "counter") (mut i64) (i64.const 7)))
        """.trimIndent()
    )
}
