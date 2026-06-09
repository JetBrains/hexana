package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

        val result = callToolJsonObject(
            "get_globals_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val globals = result.array("globals")

        assertEquals(1, globals.size)
        assertEquals(1, globals.obj(0).int("globalIdx"))
        assertEquals("i64", globals.obj(0).text("type"))
        assertTrue(globals.obj(0).text("expression").contains("i64.const"))
    }

    fun testGetGlobalsForIndicesProvidesStructuredContentForTypedResult() {
        val fixture = createGlobalsFixture("fixtures/direct/globals/structured_get_globals.wasm")

        val result = callTool(
            "get_globals_for_indices",
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
        val globals = structuredContent.array("globals")
        assertEquals(1, globals.size)
        assertEquals(1, globals.obj(0).int("globalIdx"))
    }

    fun testAuditGlobalStatePolicyReturnsReportReadyFacts() {
        val baseline = createWasmFixture(
            "fixtures/direct/globals/audit-baseline.wasm",
            """
            (module
              (import "env" "readonly_epoch" (global i64))
              (global (export "abi_version") i32 (i32.const 7)))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/globals/audit-current.wasm",
            """
            (module
              (import "env" "clock_offset" (global (mut i64)))
              (import "env" "readonly_epoch" (global i64))
              (global (export "abi_version") i32 (i32.const 7))
              (global (export "request_limit") i32 (i32.const 2048))
              (global (export "feature_flag") i32 (i32.const 1)))
            """.trimIndent()
        )
        val policy = createTextFixture(
            "fixtures/direct/globals/audit-policy.json",
            """{"requestLimits":{"exports":["request_limit"],"maxValue":2048}}"""
        )

        val result = callTool(
            "audit_global_state_policy",
            buildJsonObject {
                put("path", JsonPrimitive(current.projectRelativePath))
                put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
                put("policyPath", JsonPrimitive(policy.projectRelativePath))
            }
        )

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)

        val newPublicGlobals = structuredContent.array("newPublicGlobals")
        assertEquals(listOf("request_limit", "feature_flag"), newPublicGlobals.map { it.jsonObject.text("export") })
        assertTrue(newPublicGlobals.obj(0).getValue("allowed").jsonPrimitive.boolean)
        assertFalse(newPublicGlobals.obj(1).getValue("allowed").jsonPrimitive.boolean)

        val requestLimit = structuredContent.array("requestLimits").obj(0)
        assertEquals("request_limit", requestLimit.text("export"))
        assertEquals(2048, requestLimit.int("value"))
        assertEquals(2048, requestLimit.int("maxValue"))

        assertEquals(
            listOf("mutable_imported_global", "forbidden_public_global"),
            structuredContent.array("violations").map { it.jsonObject.text("kind") }
        )
        assertEquals(listOf(0, 2, 3, 4), structuredContent.array("targetedGlobalIndices").map { it.jsonPrimitive.int })
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
