package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class HexanaMcpMemoryToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListMemoryReturnsImportedAndDefinedEntries() {
        val fixture = createMemoryFixture("fixtures/direct/memory/list_memory.wasm")

        val result = callToolJsonObject("list_memory", jsonPath(fixture.projectRelativePath))
        val memories = result.array("memories")

        assertEquals(2, memories.size)
        assertEquals(0, memories.obj(0).int("memoryIdx"))
        assertEquals("import", memories.obj(0).text("definedIn"))
        assertEquals("i32", memories.obj(0).obj("limit").text("addressType"))
        assertEquals(1, memories.obj(0).obj("limit").int("min"))
        assertEquals(1, memories.obj(1).int("memoryIdx"))
        assertEquals("memory_section", memories.obj(1).text("definedIn"))
        assertEquals(2, memories.obj(1).obj("limit").int("min"))
        assertEquals(4, memories.obj(1).obj("limit").int("max"))
    }

    fun testListMemoryProvidesStructuredContentForTypedResult() {
        val fixture = createMemoryFixture("fixtures/direct/memory/structured_list_memory.wasm")

        val result = callTool("list_memory", jsonPath(fixture.projectRelativePath))

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val memories = structuredContent.array("memories")
        assertEquals(2, memories.size)
        assertEquals(0, memories.obj(0).int("memoryIdx"))
        assertEquals(1, memories.obj(1).int("memoryIdx"))
    }

    fun testGetMemoryForIndicesReturnsOnlyRequestedMemories() {
        val fixture = createMemoryFixture("fixtures/direct/memory/get_memory.wasm")

        val result = callToolJsonArray(
            "get_memory_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )

        assertEquals(1, result.size)
        assertEquals(1, result.obj(0).int("memoryIdx"))
        assertEquals(2, result.obj(0).obj("limit").int("min"))
        assertEquals(4, result.obj(0).obj("limit").int("max"))
    }

    private fun createMemoryFixture(pathInProject: String) = createWasmFixture(
        pathInProject,
        """
        (module
          (import "env" "shared" (memory 1))
          (memory 2 4)
          (export "mem" (memory 1)))
        """.trimIndent()
    )
}
