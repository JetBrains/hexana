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
        val modules = result.array("modules")
        assertEquals(current.projectRelativePath, modules.obj(0).text("path"))
        assertEquals(baseline.projectRelativePath, modules.obj(1).text("path"))
        assertEquals(setOf("added", "removed"), records.indices.map { records.obj(it).text("status") }.toSet())
        assertEquals(setOf("new", "old"), records.indices.map { records.obj(it).text("name") }.toSet())
    }

    fun testQueryArtifactsApiDiffReturnsAbiDiffAndReferenceTypeRisks() {
        val baseline = createWasmFixture(
            "fixtures/direct/artifacts/api-diff-baseline.wasm",
            """
            (module
              (func (export "stable") (param i32) (result i32)
                local.get 0)
              (func (export "changed") (param i32) (result i32)
                local.get 0)
              (func (export "removed") (result i64)
                i64.const 7))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/artifacts/api-diff-current.wasm",
            """
            (module
              (import "host" "bind_external" (func ${'$'}bind_external (param externref)))
              (func (export "added") (result i32)
                i32.const 9)
              (func (export "stable") (param i32) (result i32)
                local.get 0)
              (func (export "changed") (param i64) (result i64)
                local.get 0))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(current.projectRelativePath))
                put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
                put("operation", JsonPrimitive("api_diff"))
                put("detail", JsonPrimitive("evidence"))
            }
        )

        assertEquals("api_diff", result.text("operation"))
        val records = result.array("records")
        assertEquals(
            setOf(
                "api-diff:summary",
                "export:function:added",
                "export:function:changed",
                "export:function:removed",
                "abi-reference-import:host.bind_external"
            ),
            records.ids()
        )
        assertEquals("changed", records.recordById("export:function:changed").text("status"))
        assertEquals("[i32]", records.recordById("export:function:changed").attributeValue("previousParams"))
        assertEquals("[i64]", records.recordById("export:function:changed").attributeValue("currentParams"))
        assertEquals("0", records.recordById("export:function:changed").attributeValue("previousTypeidx"))
        assertEquals("3", records.recordById("export:function:changed").attributeValue("currentTypeidx"))

        val referenceImport = records.recordById("abi-reference-import:host.bind_external")
        assertEquals("reference_type_import", referenceImport.text("kind"))
        assertEquals("reference-types", referenceImport.attributeValue("feature"))
        assertEquals("host.bind_external", referenceImport.attributeValue("import"))
        assertEquals("[externref]", referenceImport.attributeValue("params"))
        assertEquals("0", referenceImport.attributeValue("typeidx"))
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

    fun testQueryArtifactsAbiSurfaceReturnsFunctionImportsAndExports() {
        val fixture = createWasmFixture(
            "fixtures/direct/artifacts/abi-surface.wasm",
            """
            (module
              (import "host" "log_event" (func ${'$'}log_event (param i32)))
              (import "host" "read_config" (func ${'$'}read_config (param i64) (result i32)))
              (func (export "process_order") (param i32 i64) (result i32)
                i32.const 1))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("operation", JsonPrimitive("abi_surface"))
            }
        )

        assertEquals("abi_surface", result.text("operation"))
        val records = result.array("records")
        val summary = records.recordById("abi-surface:summary")
        assertEquals("2", summary.attributeValue("functionImports"))
        assertEquals("1", summary.attributeValue("functionExports"))
        assertEquals("host.log_event,host.read_config", summary.attributeValue("imports"))
        assertEquals("process_order", summary.attributeValue("entrypoints"))

        val import = records.recordById("abi-surface:import:host.read_config")
        assertEquals("function_import", import.text("kind"))
        assertEquals("host.read_config", import.attributeValue("import"))
        assertEquals("[i64]", import.attributeValue("params"))
        assertEquals("[i32]", import.attributeValue("results"))

        val export = records.recordById("abi-surface:export:process_order")
        assertEquals("function_export", export.text("kind"))
        assertEquals("process_order", export.attributeValue("export"))
        assertEquals("[i32,i64]", export.attributeValue("params"))
        assertEquals("[i32]", export.attributeValue("results"))
    }

    fun testQueryArtifactsImportSurfaceReturnsOnlyFunctionImports() {
        val fixture = createWasmFixture(
            "fixtures/direct/artifacts/import-surface.wasm",
            """
            (module
              (import "host" "log_event" (func ${'$'}log_event (param i32)))
              (import "host" "read_config" (func ${'$'}read_config (param i64) (result i32)))
              (func (export "process_order") (param i32 i64) (result i32)
                i32.const 1))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("operation", JsonPrimitive("import_surface"))
            }
        )

        assertEquals("import_surface", result.text("operation"))
        val records = result.array("records")
        assertEquals(
            setOf(
                "import-surface:summary",
                "import-surface:import:host.log_event",
                "import-surface:import:host.read_config"
            ),
            records.ids()
        )
        val summary = records.recordById("import-surface:summary")
        assertEquals("2", summary.attributeValue("functionImports"))
        assertEquals("host.log_event,host.read_config", summary.attributeValue("imports"))

        val import = records.recordById("import-surface:import:host.read_config")
        assertEquals("function_import", import.text("kind"))
        assertEquals("host.read_config", import.attributeValue("import"))
        assertEquals("[i64]", import.attributeValue("params"))
        assertEquals("[i32]", import.attributeValue("results"))
    }

    fun testQueryArtifactsEntrypointEffectsScansExportedMemoryWritesAndHostCalls() {
        val fixture = createWasmFixture(
            "fixtures/direct/artifacts/entrypoint-effects.wasm",
            """
            (module
              (import "env" "log_i32" (func ${'$'}log_i32 (param i32)))
              (import "env" "seed" (func ${'$'}seed (result i64)))
              (memory 1)
              (func (export "both") (param i32) (result i64)
                local.get 0
                i32.const 7
                i32.store
                call ${'$'}seed)
              (func (export "notify_host") (param i32)
                local.get 0
                call ${'$'}log_i32)
              (func (export "write_u8") (param i32 i32)
                local.get 0
                local.get 1
                i32.store8)
              (func ${'$'}writer_helper (param i32 i32)
                local.get 0
                local.get 1
                i32.store)
              (func (export "forward_to_writer") (param i32 i32)
                local.get 0
                local.get 1
                call ${'$'}writer_helper))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("operation", JsonPrimitive("entrypoint_effects"))
            }
        )

        assertEquals("entrypoint_effects", result.text("operation"))
        val records = result.array("records")
        assertEquals(
            setOf(
                "entrypoint-effects:summary",
                "entrypoint-effects:export:both",
                "entrypoint-effects:export:notify_host",
                "entrypoint-effects:export:write_u8"
            ),
            records.ids()
        )

        val summary = records.recordById("entrypoint-effects:summary")
        assertEquals("4", summary.attributeValue("localFunctionExports"))
        assertEquals("3", summary.attributeValue("effectfulExports"))
        assertEquals("2", summary.attributeValue("memoryWritingExports"))
        assertEquals("2", summary.attributeValue("hostCallingExports"))
        assertEquals("both,write_u8", summary.attributeValue("memoryWritingExportNames"))
        assertEquals("both,notify_host", summary.attributeValue("hostCallingExportNames"))

        val write = records.recordById("entrypoint-effects:export:write_u8")
        assertEquals("[i32,i32]", write.attributeValue("params"))
        assertEquals("[]", write.attributeValue("results"))
        assertEquals("1", write.attributeValue("directMemoryWrites"))
        assertEquals("i32.store8", write.attributeValue("memoryWriteOps"))
        assertEquals("0", write.attributeValue("directHostCalls"))

        val notify = records.recordById("entrypoint-effects:export:notify_host")
        assertEquals("0", notify.attributeValue("directMemoryWrites"))
        assertEquals("1", notify.attributeValue("directHostCalls"))
        assertEquals("env.log_i32", notify.attributeValue("calledImports"))
        assertEquals("env.log_i32:(i32) -> ()", notify.attributeValue("calledImportSignatures"))

        val both = records.recordById("entrypoint-effects:export:both")
        assertEquals("1", both.attributeValue("directMemoryWrites"))
        assertEquals("1", both.attributeValue("directHostCalls"))
        assertEquals("env.seed", both.attributeValue("calledImports"))
        assertEquals("[i64]", both.attributeValue("results"))
    }

    fun testQueryArtifactsDataPayloadAuditReturnsRawSegmentMatches() {
        val fixture = createWasmFixture(
            "fixtures/direct/artifacts/data-payload-audit.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 128) "public telemetry endpoint")
              (data (i32.const 2048) "https://api.shadow.internal/v2/keys")
              (data "passive release notes")
              (data "FLAG_ENABLE_UNRELEASED_BILLING"))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("operation", JsonPrimitive("data_payload_audit"))
                put("query", buildJsonArray {
                    add(JsonPrimitive("https://api.shadow.internal/v2/keys"))
                    add(JsonPrimitive("FLAG_ENABLE_UNRELEASED_BILLING"))
                })
                put("detail", JsonPrimitive("evidence"))
            }
        )

        assertEquals("data_payload_audit", result.text("operation"))
        val records = result.array("records")
        val summary = records.recordById("data-payload-audit:summary")
        assertEquals("4", summary.attributeValue("scannedSegments"))
        assertEquals("1,3", summary.attributeValue("matchedSegments"))
        assertEquals("0,2", summary.attributeValue("cleanSegments"))

        val active = records.recordById("data-segment:1")
        assertEquals("active", active.text("kind"))
        assertEquals("0", active.attributeValue("memory"))
        assertEquals("i32.const 2048", active.attributeValue("offsetExpr"))
        assertEquals("https://api.shadow.internal/v2/keys", active.attributeValue("matchedTerms"))
        assertEquals("true", active.attributeValue("rawBytesConfirmed"))

        val passive = records.recordById("data-segment:3")
        assertEquals("passive", passive.text("kind"))
        assertEquals("FLAG_ENABLE_UNRELEASED_BILLING", passive.attributeValue("matchedTerms"))
        assertEquals("true", passive.attributeValue("rawBytesConfirmed"))
    }

    fun testQueryArtifactsDataPayloadAuditWithoutTermsReturnsQueryReminderOnly() {
        val fixture = createWasmFixture(
            "fixtures/direct/artifacts/data-payload-audit-empty-query.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 128) "https://api.shadow.internal/v2/keys")
              (data "FLAG_ENABLE_UNRELEASED_BILLING"))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("operation", JsonPrimitive("data_payload_audit"))
                put("query", buildJsonArray {
                    add(JsonPrimitive(""))
                })
            }
        )

        assertEquals("data_payload_audit", result.text("operation"))
        val records = result.array("records")
        assertEquals(1, records.size)
        val summary = records.recordById("data-payload-audit:needs-query")
        assertEquals("true", summary.attributeValue("queryRequired"))
        assertEquals("2", summary.attributeValue("scannedSegments"))
        assertEquals("1", summary.attributeValue("activeSegments"))
        assertEquals("1", summary.attributeValue("passiveSegments"))
    }

    fun testQueryArtifactsLocalBudgetReturnsExportedFunctionLocalCounts() {
        val fixture = createWasmFixture(
            "fixtures/direct/artifacts/local-budget.wasm",
            """
            (module
              (func (export "health_check"))
              (func (export "hydrate_checkout") (local i32 i32 i32 i32 i64 i64))
              (func (export "render_invoice") (local f64 f64 i32 i32 i32))
              (func (export "route_cart") (local i32 i32 i32 i32)))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("operation", JsonPrimitive("local_budget"))
                put("query", buildJsonArray {
                    add(JsonPrimitive("health_check"))
                    add(JsonPrimitive("hydrate_checkout"))
                    add(JsonPrimitive("render_invoice"))
                    add(JsonPrimitive("route_cart"))
                })
            }
        )

        assertEquals("local_budget", result.text("operation"))
        val records = result.array("records")
        val summary = records.recordById("local-budget:summary")
        assertEquals("4", summary.attributeValue("functionExports"))
        assertEquals("6", summary.attributeValue("maxLocalCount"))
        assertEquals("hydrate_checkout", summary.attributeValue("maxLocalExports"))

        val hydrate = records.recordById("local-budget:export:hydrate_checkout")
        assertEquals("1", hydrate.attributeValue("funcidx"))
        assertEquals("6", hydrate.attributeValue("localCount"))
        assertEquals("i32=4,i64=2", hydrate.attributeValue("localsByType"))

        val invoice = records.recordById("local-budget:export:render_invoice")
        assertEquals("5", invoice.attributeValue("localCount"))
        assertEquals("f64=2,i32=3", invoice.attributeValue("localsByType"))

        val health = records.recordById("local-budget:export:health_check")
        assertEquals("0", health.attributeValue("localCount"))
        assertEquals("[]", health.attributeValue("localsByType"))
    }

    fun testQueryArtifactsGlobalStatePolicyReturnsPolicyRelevantGlobals() {
        val baseline = createWasmFixture(
            "fixtures/direct/artifacts/global-policy-baseline.wasm",
            """
            (module
              (import "env" "readonly_epoch" (global i64))
              (global (export "abi_version") i32 (i32.const 7)))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/artifacts/global-policy-current.wasm",
            """
            (module
              (import "env" "clock_offset" (global (mut i64)))
              (import "env" "readonly_epoch" (global i64))
              (global (export "abi_version") i32 (i32.const 7))
              (global (export "request_limit") (mut i32) (i32.const 2048))
              (global (export "feature_flag") i32 (i32.const 1)))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(current.projectRelativePath))
                put("baselinePath", JsonPrimitive(baseline.projectRelativePath))
                put("operation", JsonPrimitive("global_state_policy"))
            }
        )

        assertEquals("global_state_policy", result.text("operation"))
        val records = result.array("records")
        val mutableImport = records.recordById("global-policy:mutable-import:0")
        assertEquals("mutable_imported_global", mutableImport.text("kind"))
        assertEquals("env.clock_offset", mutableImport.attributeValue("import"))
        assertEquals("i64", mutableImport.attributeValue("type"))

        val newExport = records.recordById("global-policy:new-export:request_limit")
        assertEquals("new_public_global", newExport.text("kind"))
        assertEquals("request_limit", newExport.attributeValue("export"))
        assertEquals("true", newExport.attributeValue("mutable"))
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

        val summary = result.array("records").recordById("host-call:summary")
        assertEquals("env.log_i32,env.read_preview_state", summary.attributeValue("oldDirectHostCalls"))
        assertEquals(
            "env.begin_background_sync,env.log_i32,env.read_preview_state",
            summary.attributeValue("currentDirectHostCalls")
        )
        assertEquals("preview_sync_session", summary.attributeValue("entrypoints"))
    }

    fun testQueryArtifactsCrashFramesResolvesMultipleFuncidxFrames() {
        val fixture = createWasmFixture(
            "fixtures/direct/artifacts/crash-frames.wasm",
            """
            (module
              (import "env" "log_error" (func ${'$'}log_error))
              (import "env" "read_clock" (func ${'$'}read_clock (result i64)))
              (func ${'$'}handle_checkout (export "handle_checkout") (param i32)
                local.get 0
                call ${'$'}apply_discount
                call ${'$'}log_error)
              (func ${'$'}validate_cart
                call ${'$'}read_clock
                drop)
              (func ${'$'}apply_discount (export "apply_discount") (param i32) (result i32)
                call ${'$'}read_clock
                drop
                local.get 0))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("operation", JsonPrimitive("crash_frames"))
                put("query", buildJsonArray {
                    add(JsonPrimitive("4"))
                    add(JsonPrimitive("2"))
                    add(JsonPrimitive("0"))
                })
            }
        )

        assertEquals("crash_frames", result.text("operation"))
        val records = result.array("records")
        assertEquals("apply_discount", records.recordById("crash-frame:function:4").text("name"))
        assertEquals("handle_checkout", records.recordById("crash-frame:function:2").text("name"))
        assertEquals("env.log_error", records.recordById("crash-frame:function:0").attributeValue("import"))
        assertEquals(
            setOf("env.log_error", "env.read_clock"),
            records.indices
                .map { records.obj(it) }
                .filter { it.text("entityType") == "host_call" }
                .map { it.attributeValue("targetImport") }
                .toSet()
        )
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

    private fun JsonArray.ids(): Set<String> {
        return indices.map { obj(it).text("id") }.toSet()
    }

    private fun JsonObject.attributeValue(name: String): String {
        val attributes = array("attributes")
        return attributes.indices
            .map { attributes.obj(it) }
            .single { it.text("name") == name }
            .text("value")
    }
}
