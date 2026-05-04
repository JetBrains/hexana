package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

    fun testQueryArtifactsTriageSummarizesExportSignatureChanges() {
        val baseline = createWasmFixture(
            "fixtures/direct/artifacts/triage-signature-baseline.wasm",
            """
            (module
              (func (export "transform") (param i32) (result i32)
                local.get 0))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/artifacts/triage-signature-current.wasm",
            """
            (module
              (func (export "transform") (param i64) (result i64)
                local.get 0))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(current.projectRelativePath))
                put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
                put("operation", JsonPrimitive("triage"))
                put("entityTypes", buildJsonArray {
                    add(JsonPrimitive("export"))
                })
            }
        )

        assertEquals("triage", result.text("operation"))
        val records = result.array("records")
        assertEquals("finding", records.obj(0).text("entityType"))
        val changed = records.recordById("export:function:transform")
        assertEquals("changed", changed.text("status"))
        assertTrue(changed.text("summary").contains("signature from (i32) -> (i32) to (i64) -> (i64)"))
        assertEquals("(i32) -> (i32)", changed.attributeValue("previousSignature"))
        assertEquals("(i64) -> (i64)", changed.attributeValue("currentSignature"))
    }

    fun testQueryArtifactsTriageIncludesElementSegmentDiff() {
        val baseline = createWasmFixture(
            "fixtures/direct/artifacts/triage-element-baseline.wasm",
            """
            (module
              (table 4 funcref)
              (func)
              (func)
              (func)
              (func)
              (elem (i32.const 0) func 0 1 2 3))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/artifacts/triage-element-current.wasm",
            """
            (module
              (table 4 funcref)
              (func)
              (func)
              (func)
              (func)
              (elem (i32.const 0) func 0 1 3 2))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(current.projectRelativePath))
                put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
                put("operation", JsonPrimitive("triage"))
                put("entityTypes", buildJsonArray {
                    add(JsonPrimitive("element"))
                })
            }
        )

        val changed = result.array("records").recordById("element:0")
        assertEquals("changed", changed.text("status"))
        assertEquals("[0,1,2,3]", changed.attributeValue("previousElements"))
        assertEquals("[0,1,3,2]", changed.attributeValue("currentElements"))
        assertTrue(changed.text("summary").contains("elements from [0,1,2,3] to [0,1,3,2]"))
    }

    fun testQueryArtifactsPublicSurfaceReturnsExportAuditFacts() {
        val fixture = createWasmFixture(
            "fixtures/direct/artifacts/public-surface.wasm",
            """
            (module
              (import "env" "host_log" (func ${'$'}host_log (param i32)))
              (export "host_log_passthrough" (func ${'$'}host_log))
              (memory (export "runtime_memory") 2 8)
              (global (export "abi_version") i32 (i32.const 7))
              (table (export "handler_table") 1 funcref))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("operation", JsonPrimitive("public_surface"))
            }
        )

        assertEquals("public_surface", result.text("operation"))
        val summary = result.array("records").recordById("public-surface:summary")
        assertEquals("1", summary.attributeValue("functionExports"))
        assertEquals("1", summary.attributeValue("importedFunctionExports"))
        assertEquals("1", summary.attributeValue("memoryExports"))
        assertEquals("1", summary.attributeValue("globalExports"))
        assertEquals("1", summary.attributeValue("tableExports"))

        val global = result.array("records").recordById("export:global:abi_version")
        assertEquals("i32", global.attributeValue("type"))
        assertEquals("false", global.attributeValue("mutable"))
        assertEquals("i32.const 7", global.attributeValue("initExpr"))
    }

    fun testQueryArtifactsMemoryContractCarriesImportIdentityAndLimitDiff() {
        val baseline = createWasmFixture(
            "fixtures/direct/artifacts/memory-contract-baseline.wasm",
            """
            (module
              (import "env" "checkout_host_memory" (memory 16 32))
              (export "checkout_memory" (memory 0)))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/artifacts/memory-contract-current.wasm",
            """
            (module
              (import "env" "checkout_host_memory" (memory 24 48))
              (export "checkout_memory" (memory 0)))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(current.projectRelativePath))
                put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
                put("operation", JsonPrimitive("memory_contract"))
            }
        )

        val memory = result.array("records").recordById("memory:0")
        assertEquals("changed", memory.text("status"))
        assertEquals("env.checkout_host_memory", memory.attributeValue("import"))
        assertEquals("checkout_memory", memory.attributeValue("exportedAs"))
        assertEquals("16", memory.attributeValue("previousMin"))
        assertEquals("24", memory.attributeValue("currentMin"))
        assertEquals("32", memory.attributeValue("previousMax"))
        assertEquals("48", memory.attributeValue("currentMax"))
    }

    fun testQueryArtifactsDispatchTableDiffResolvesChangedSlotTargets() {
        val baseline = createWasmFixture(
            "fixtures/direct/artifacts/dispatch-baseline.wasm",
            """
            (module
              (table 4 funcref)
              (func (export "warmup"))
              (func (export "checkout_start"))
              (func (export "checkout_review"))
              (func (export "admin_reconcile"))
              (elem (i32.const 0) func 0 1 2 3))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/artifacts/dispatch-current.wasm",
            """
            (module
              (table 4 funcref)
              (func (export "warmup"))
              (func (export "checkout_start"))
              (func (export "checkout_review"))
              (func (export "admin_reconcile"))
              (elem (i32.const 0) func 0 1 3 2))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(current.projectRelativePath))
                put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
                put("operation", JsonPrimitive("dispatch_table_diff"))
            }
        )

        val slot = result.array("records").recordById("dispatch-slot:0:2")
        assertEquals("dispatch_slot", slot.text("entityType"))
        assertEquals("changed", slot.text("status"))
        assertEquals("2", slot.attributeValue("previousFuncidx"))
        assertEquals("3", slot.attributeValue("currentFuncidx"))
        assertEquals("checkout_review", slot.attributeValue("previousExportedAs"))
        assertEquals("admin_reconcile", slot.attributeValue("currentExportedAs"))
    }

    fun testQueryArtifactsHostCallDiffFindsAddedDirectImportCall() {
        val baseline = createWasmFixture(
            "fixtures/direct/artifacts/host-call-baseline.wasm",
            """
            (module
              (import "env" "log_i32" (func ${'$'}log (param i32)))
              (import "env" "read_preview_state" (func ${'$'}read (param i32)))
              (func (export "preview_sync_session") (param i32)
                local.get 0
                call ${'$'}read
                local.get 0
                call ${'$'}log))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/artifacts/host-call-current.wasm",
            """
            (module
              (import "env" "begin_background_sync" (func ${'$'}begin (param i32)))
              (import "env" "log_i32" (func ${'$'}log (param i32)))
              (import "env" "read_preview_state" (func ${'$'}read (param i32)))
              (func (export "preview_sync_session") (param i32)
                local.get 0
                call ${'$'}read
                local.get 0
                call ${'$'}begin
                local.get 0
                call ${'$'}log))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(current.projectRelativePath))
                put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
                put("operation", JsonPrimitive("host_call_diff"))
                put("startName", JsonPrimitive("preview_sync_session"))
            }
        )

        val added = result.array("records").recordById("host-call:preview_sync_session:env.begin_background_sync")
        assertEquals("added", added.text("status"))
        assertEquals("env.begin_background_sync", added.attributeValue("hostImport"))
        assertEquals("preview_sync_session", added.attributeValue("rootName"))
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

    private fun JsonArray.recordById(id: String): JsonObject {
        return indices
            .map { obj(it) }
            .single { it.text("id") == id }
    }

    private fun JsonObject.attributeValue(name: String): String {
        val attributes = array("attributes")
        return attributes.indices
            .map { attributes.obj(it) }
            .single { it.text("name") == name }
            .text("value")
    }
}
