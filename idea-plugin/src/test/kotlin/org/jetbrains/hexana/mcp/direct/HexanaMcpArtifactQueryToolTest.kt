package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class HexanaMcpArtifactQueryToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testQueryArtifactsOverviewReturnsModuleSummary() {
        val fixture = createWasmFixture(
            "fixtures/direct/artifacts/overview.wasm",
            """
            (module
              (memory 1)
              (func (export "main") (result i32)
                i32.const 42))
            """.trimIndent()
        )

        val result = callToolJsonObject("query_artifacts", jsonPath(fixture.projectRelativePath))

        assertEquals("overview", result.text("operation"))
        val module = result.array("modules").obj(0)
        assertEquals(1, module.int("functions"))
        assertEquals(1, module.int("exports"))
        assertEquals(1, module.int("memories"))
        assertEquals(0, result.array("records").size)
    }

    fun testQueryArtifactsFindsExports() {
        val fixture = createWasmFixture(
            "fixtures/direct/artifacts/find.wasm",
            """
            (module
              (func (export "main") (result i32)
                i32.const 42))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("operation", JsonPrimitive("find"))
                put("entityTypes", buildJsonArray {
                    add(JsonPrimitive("export"))
                })
                put("query", buildJsonArray {
                    add(JsonPrimitive("main"))
                })
            }
        )

        val record = result.array("records").obj(0)
        assertEquals("export", record.text("entityType"))
        assertEquals("main", record.text("name"))
        assertEquals("function", record.text("kind"))
    }

    fun testQueryArtifactsDiffsExports() {
        val baseline = createWasmFixture(
            "fixtures/direct/artifacts/baseline.wasm",
            """
            (module
              (func (export "old") (result i32)
                i32.const 1))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/artifacts/current.wasm",
            """
            (module
              (func (export "new") (result i32)
                i32.const 2))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(current.projectRelativePath))
                put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
                put("operation", JsonPrimitive("diff"))
                put("entityTypes", buildJsonArray {
                    add(JsonPrimitive("export"))
                })
            }
        )

        val records = result.array("records")
        assertEquals(setOf("added", "removed"), records.indices.map { records.obj(it).text("status") }.toSet())
        assertEquals(setOf("new", "old"), records.indices.map { records.obj(it).text("name") }.toSet())
    }
}
