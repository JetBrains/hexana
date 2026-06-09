package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HexanaMcpMemoryToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListMemoryReturnsImportedAndDefinedEntries() {
        val fixture = createMemoryFixture("fixtures/direct/memory/list_memory.wasm")

        val result = callToolJsonObject("list_memory", jsonPath(fixture.projectRelativePath))
        val memories = result.array("memories")

        assertEquals(2, memories.size)
        assertEquals(0, memories.obj(0).int("memoryIdx"))
        assertEquals("import", memories.obj(0).text("definedIn"))
        assertEquals("env", memories.obj(0).obj("import").text("module"))
        assertEquals("shared", memories.obj(0).obj("import").text("name"))
        assertEquals("i32", memories.obj(0).obj("limit").text("addressType"))
        assertEquals(1, memories.obj(0).obj("limit").int("min"))
        assertEquals(1, memories.obj(1).int("memoryIdx"))
        assertEquals("memory_section", memories.obj(1).text("definedIn"))
        assertFalse("import" in memories.obj(1))
        assertEquals(2, memories.obj(1).obj("limit").int("min"))
        assertEquals(4, memories.obj(1).obj("limit").int("max"))
        assertEquals(listOf("mem"), memories.obj(1).stringArray("exportedAs"))
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

        val result = callToolJsonObject(
            "get_memory_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val memories = result.array("memories")

        assertEquals(1, memories.size)
        assertEquals(1, memories.obj(0).int("memoryIdx"))
        assertEquals(2, memories.obj(0).obj("limit").int("min"))
        assertEquals(4, memories.obj(0).obj("limit").int("max"))
        assertEquals(listOf("mem"), memories.obj(0).stringArray("exportedAs"))
    }

    fun testGetMemoryForIndicesProvidesStructuredContentForTypedResult() {
        val fixture = createMemoryFixture("fixtures/direct/memory/structured_get_memory.wasm")

        val result = callTool(
            "get_memory_for_indices",
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
        val memories = structuredContent.array("memories")
        assertEquals(1, memories.size)
        assertEquals(1, memories.obj(0).int("memoryIdx"))
        assertFalse("import" in memories.obj(0))
    }

    fun testCheckMemoryContractReportsExportedMemoryLimitIncreases() {
        val baseline = createWasmFixture(
            "fixtures/direct/memory/contract-increase-baseline.wasm",
            """
            (module
              (memory 16 32)
              (export "checkout_memory" (memory 0)))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/memory/contract-increase-current.wasm",
            """
            (module
              (memory 24 48)
              (export "checkout_memory" (memory 0)))
            """.trimIndent()
        )
        val policy = createTextFixture(
            "fixtures/direct/memory/contract-increase-policy.json",
            """{"maxMin":64,"maxMax":64,"requireMax":true}"""
        )

        val result = callToolJsonObject(
            "check_memory_contract",
            memoryContractPayload(current.projectRelativePath, baseline.projectRelativePath, policy.projectRelativePath)
        )

        val memory = result.array("memories").obj(0)
        assertEquals(0, memory.int("memoryIdx"))
        assertEquals(listOf("checkout_memory"), memory.stringArray("exportedAs"))
        assertEquals(24, memory.obj("declaredLimits").int("min"))
        assertEquals(16, memory.obj("previousLimits").int("min"))
        assertEquals(24, memory.obj("minimalCompatibleLimits").int("min"))
        assertEquals(48, memory.obj("minimalCompatibleLimits").int("max"))
        assertEquals(listOf("min_increased", "max_increased"), memory.violationKinds())
        assertEquals(memory.violationKinds(), result.array("violations").map { it.jsonObject.text("kind") })
    }

    fun testCheckMemoryContractReportsNonExportedLimitDecreases() {
        val baseline = createWasmFixture(
            "fixtures/direct/memory/contract-decrease-baseline.wasm",
            """
            (module
              (memory 8 16))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/memory/contract-decrease-current.wasm",
            """
            (module
              (memory 4 12))
            """.trimIndent()
        )
        val policy = createTextFixture(
            "fixtures/direct/memory/contract-decrease-policy.json",
            """{"maxMin":16,"maxMax":32,"requireMax":true}"""
        )

        val result = callToolJsonObject(
            "check_memory_contract",
            memoryContractPayload(current.projectRelativePath, baseline.projectRelativePath, policy.projectRelativePath)
        )

        val memory = result.array("memories").obj(0)
        assertEquals(emptyList<String>(), memory.stringArray("exportedAs"))
        assertEquals(4, memory.obj("declaredLimits").int("min"))
        assertEquals(8, memory.obj("previousLimits").int("min"))
        assertEquals(listOf("min_decreased", "max_decreased"), memory.violationKinds())
    }

    fun testCheckMemoryContractReportsPolicyViolationsByExportName() {
        val baseline = createWasmFixture(
            "fixtures/direct/memory/contract-policy-baseline.wasm",
            """
            (module
              (memory 1 2)
              (export "runtime_memory" (memory 0)))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/memory/contract-policy-current.wasm",
            """
            (module
              (memory 4)
              (export "runtime_memory" (memory 0)))
            """.trimIndent()
        )
        val policy = createTextFixture(
            "fixtures/direct/memory/contract-policy.json",
            """
            {
              "memories": [
                {"exportedAs": "runtime_memory", "maxMin": 2, "maxMax": 8, "requireMax": true}
              ]
            }
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "check_memory_contract",
            memoryContractPayload(current.projectRelativePath, baseline.projectRelativePath, policy.projectRelativePath)
        )

        val memory = result.array("memories").obj(0)
        assertEquals(listOf("runtime_memory"), memory.stringArray("exportedAs"))
        assertEquals(2, memory.obj("policyLimits").int("maxMin"))
        assertEquals(8, memory.obj("policyLimits").int("maxMax"))
        assertEquals(
            listOf("min_increased", "max_removed", "policy_min_exceeded", "policy_max_required", "policy_max_exceeded"),
            memory.violationKinds()
        )
        assertTrue(result.array("violations").all { it.jsonObject.stringArray("exportedAs") == listOf("runtime_memory") })
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

    private fun memoryContractPayload(path: String, baselinePath: String, policyPath: String): JsonObject {
        return buildJsonObject {
            put("path", JsonPrimitive(path))
            put("baselinePath", JsonPrimitive(baselinePath))
            put("policyPath", JsonPrimitive(policyPath))
        }
    }

    private fun JsonObject.stringArray(name: String): List<String> {
        return array(name).map { element ->
            element.jsonPrimitive.contentOrNull ?: error("Expected `$name` to contain only strings: $this")
        }
    }

    private fun JsonObject.violationKinds(): List<String> {
        return array("violations").map { it.jsonObject.text("kind") }
    }
}
