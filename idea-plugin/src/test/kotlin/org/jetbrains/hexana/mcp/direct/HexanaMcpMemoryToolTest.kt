package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class HexanaMcpMemoryToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListMemoryReturnsImportedAndDefinedEntries() {
        val fixture = createMemoryFixture("fixtures/direct/memory/list_memory.wasm")

        val result = callToolJsonArray("list_memory", jsonPath(fixture.projectRelativePath))

        assertEquals(2, result.size)
        assertEquals(0, result.obj(0).int("memoryIdx"))
        assertEquals("import", result.obj(0).text("definedIn"))
        assertEquals("i32", result.obj(0).obj("limit").text("addressType"))
        assertEquals(1, result.obj(0).obj("limit").int("min"))
        assertEquals(1, result.obj(1).int("memoryIdx"))
        assertEquals("memory_section", result.obj(1).text("definedIn"))
        assertEquals(2, result.obj(1).obj("limit").int("min"))
        assertEquals(4, result.obj(1).obj("limit").int("max"))
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
