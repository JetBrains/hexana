package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

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

    fun testQueryArtifactsDiffHonorsMaxBytesBudget() {
        val baseline = createWasmFixture(
            "fixtures/direct/artifacts/budget-baseline.wasm",
            buildExportModule("old", 80)
        )
        val current = createWasmFixture(
            "fixtures/direct/artifacts/budget-current.wasm",
            buildExportModule("new", 80)
        )

        val payload = buildJsonObject {
            put("path", JsonPrimitive(current.projectRelativePath))
            put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
            put("operation", JsonPrimitive("diff"))
            put("entityTypes", buildJsonArray {
                add(JsonPrimitive("export"))
            })
            put("limit", JsonPrimitive(100))
            put("maxBytes", JsonPrimitive(2_000))
        }
        val text = callToolText("query_artifacts", payload)
        val result = json.parseToJsonElement(text).jsonObject

        assertTrue("Expected response to stay within the requested byte budget", text.encodeToByteArray().size <= 2_000)
        assertTrue("Expected byte-budgeted response to omit records", result.obj("omitted").int("count") > 0)
        assertTrue(
            "Expected a maxBytes warning",
            result.array("warnings").any { it.textValue().contains("maxBytes=2000") }
        )
    }

    fun testQueryArtifactsSummaryDiffIgnoresFunctionBodyOnlyChanges() {
        val baseline = createWasmFixture(
            "fixtures/direct/artifacts/body-baseline.wasm",
            """
            (module
              (func (export "main") (result i32)
                i32.const 1))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/artifacts/body-current.wasm",
            """
            (module
              (func (export "main") (result i32)
                i32.const 1
                i32.const 2
                i32.add))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(current.projectRelativePath))
                put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
                put("operation", JsonPrimitive("diff"))
                put("entityTypes", buildJsonArray {
                    add(JsonPrimitive("function"))
                })
            }
        )

        assertEquals(0, result.array("records").size)
    }

    private fun buildExportModule(prefix: String, count: Int): String {
        return buildString {
            appendLine("(module")
            repeat(count) { index ->
                appendLine("""  (func (export "${prefix}_$index") (result i32)""")
                appendLine("    i32.const $index)")
            }
            appendLine(")")
        }
    }
}
