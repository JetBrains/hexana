package org.jetbrains.hexana.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactRecordResult
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancedSemanticCoverageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val service = DefaultHexanaAnalysisService()

    @Test
    fun getInstructionsForFunctionsPreservesUnsignedMemoryOperandRendering() {
        val wasm = createWasmFile(
            "memory-ops.wasm",
            """
            (module
              (memory 1 2)
              (func (export "pages") (result i32)
                memory.size)
              (func (export "grow_one") (result i32)
                i32.const 1
                memory.grow))
            """.trimIndent()
        )

        val result = service.getInstructionsForFunctions(WasmInput.FilePath(wasm.toString()), listOf(0, 1))

        assertEquals(listOf("memory.size"), result.functions[0].instructions)
        assertEquals(listOf("i32.const 1", "memory.grow"), result.functions[1].instructions)
    }

    @Test
    fun listValueTypesPreservesFullVocabularyCountsAndSummary() {
        val wasm = createWasmFile(
            "value-types.wasm",
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

        val result = service.listValueTypes(WasmInput.FilePath(wasm.toString()))

        assertEquals(listOf("i32", "i64", "f32", "f64"), result.valueTypes.scalar)
        assertEquals(listOf("v128"), result.valueTypes.vector)
        assertTrue(result.valueTypes.packed.isEmpty())
        assertTrue(result.valueTypes.reference.any { it.type == "externref" && it.refKind == "extern" })
        assertTrue(result.valueTypes.reference.any { it.type == "funcref" && it.refKind == "func" })
        assertEquals(3, result.counts.valueTypes.scalar.valueTypeCountFor("i32"))
        assertEquals(2, result.counts.valueTypes.scalar.valueTypeCountFor("i64"))
        assertEquals(1, result.counts.valueTypes.vector.valueTypeCountFor("v128"))
        assertEquals(3, result.counts.valueTypes.reference.referenceTypeCountFor("externref"))
        assertEquals(3, result.counts.valueTypes.reference.referenceTypeCountFor("funcref"))
        assertFalse(result.summary.hasGC)
        assertFalse(result.summary.hasPacked)
        assertTrue(result.summary.hasSIMD)
        assertTrue(result.summary.hasReferenceTypes)
        assertFalse(result.summary.pureMVP)
    }

    @Test
    fun listReferenceTypesPreservesGroupingMultiplicityAndSummary() {
        val wasm = createWasmFile(
            "reference-types.wasm",
            """
            (module
              (type (func (param funcref externref anyref eqref i31ref) (result externref)))
              (func (type 0) (param funcref externref anyref eqref i31ref) (result externref)
                local.get 1))
            """.trimIndent()
        )

        val result = service.listReferenceTypes(WasmInput.FilePath(wasm.toString()))

        assertEquals(listOf("funcref"), result.referenceTypes.functionReferences.map { it.type })
        assertEquals(listOf("externref"), result.referenceTypes.externalReferences.map { it.type })
        assertEquals(listOf("anyref", "eqref"), result.referenceTypes.anyReferences.map { it.type })
        assertEquals(listOf("i31ref"), result.referenceTypes.primitiveReferences.map { it.type })
        assertTrue(result.referenceTypes.gcReferences.isEmpty())
        assertTrue(result.referenceTypes.noneReferences.isEmpty())
        assertEquals(1, result.counts.referenceTypeCountFor("funcref"))
        assertEquals(2, result.counts.referenceTypeCountFor("externref"))
        assertEquals(1, result.counts.referenceTypeCountFor("anyref"))
        assertEquals(1, result.counts.referenceTypeCountFor("eqref"))
        assertEquals(1, result.counts.referenceTypeCountFor("i31ref"))
        assertTrue(result.summary.hasGC)
        assertTrue(result.summary.hasI31)
        assertFalse(result.summary.hasStruct)
        assertFalse(result.summary.hasArray)
        assertTrue(result.summary.hasRefEq)
        assertTrue(result.summary.hasAnyRef)
        assertTrue(result.summary.hasExternRef)
    }

    @Test
    fun queryArtifactsOverviewReturnsModuleSummary() {
        val wasm = createWasmFile(
            "overview.wasm",
            """
            (module
              (memory 1)
              (func (export "main") (result i32)
                i32.const 42))
            """.trimIndent()
        )

        val result = queryArtifacts(wasm, operation = "overview")

        assertEquals("overview", result.operation)
        assertEquals(1, result.modules.single().functions)
        assertEquals(1, result.modules.single().exports)
        assertEquals(1, result.modules.single().memories)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun queryArtifactsPublicSurfaceReturnsExportAuditFacts() {
        val wasm = createWasmFile(
            "public-surface.wasm",
            """
            (module
              (import "env" "host_log" (func ${'$'}host_log (param i32)))
              (export "host_log_passthrough" (func ${'$'}host_log))
              (memory (export "runtime_memory") 2 8)
              (global (export "abi_version") i32 (i32.const 7))
              (table (export "handler_table") 1 funcref))
            """.trimIndent()
        )

        val result = queryArtifacts(wasm, operation = "public_surface")

        assertEquals("public_surface", result.operation)
        val summary = result.record("public-surface:summary")
        assertEquals("1", summary.attribute("functionExports"))
        assertEquals("1", summary.attribute("importedFunctionExports"))
        assertEquals("1", summary.attribute("memoryExports"))
        assertEquals("1", summary.attribute("globalExports"))
        assertEquals("1", summary.attribute("tableExports"))
        val global = result.record("export:global:abi_version")
        assertEquals("i32", global.attribute("type"))
        assertEquals("false", global.attribute("mutable"))
        assertEquals("i32.const 7", global.attribute("initExpr"))
    }

    @Test
    fun queryArtifactsAbiSurfaceReturnsFunctionImportsAndExports() {
        val wasm = createWasmFile("abi-surface.wasm", abiSurfaceWat())

        val result = queryArtifacts(wasm, operation = "abi_surface")

        assertEquals("abi_surface", result.operation)
        val summary = result.record("abi-surface:summary")
        assertEquals("2", summary.attribute("functionImports"))
        assertEquals("1", summary.attribute("functionExports"))
        assertEquals("host.log_event,host.read_config", summary.attribute("imports"))
        assertEquals("process_order", summary.attribute("entrypoints"))
        assertEquals(
            listOf("host.log_event", "host.read_config"),
            Json.parseToJsonElement(summary.attribute("functionImportsJson")).jsonArray.map {
                it.jsonObject.getValue("import").jsonPrimitive.content
            }
        )
        assertEquals(
            listOf("process_order"),
            Json.parseToJsonElement(summary.attribute("functionExportsJson")).jsonArray.map {
                it.jsonObject.getValue("export").jsonPrimitive.content
            }
        )
        val imported = result.record("abi-surface:import:host.read_config")
        assertEquals("function_import", imported.kind)
        assertEquals("host.read_config", imported.attribute("import"))
        assertEquals("[i64]", imported.attribute("params"))
        assertEquals("[i32]", imported.attribute("results"))
        val exported = result.record("abi-surface:export:process_order")
        assertEquals("function_export", exported.kind)
        assertEquals("process_order", exported.attribute("export"))
        assertEquals("[i32,i64]", exported.attribute("params"))
        assertEquals("[i32]", exported.attribute("results"))
    }

    @Test
    fun queryArtifactsImportSurfaceReturnsOnlyFunctionImports() {
        val wasm = createWasmFile("import-surface.wasm", abiSurfaceWat())

        val result = queryArtifacts(wasm, operation = "import_surface")

        assertEquals("import_surface", result.operation)
        assertEquals(
            setOf(
                "import-surface:summary",
                "import-surface:import:host.log_event",
                "import-surface:import:host.read_config"
            ),
            result.records.ids()
        )
        val summary = result.record("import-surface:summary")
        assertEquals("2", summary.attribute("functionImports"))
        assertEquals("host.log_event,host.read_config", summary.attribute("imports"))
        assertEquals("2", summary.attribute("functionImportsJsonCount"))
        val importFacts = Json.parseToJsonElement(summary.attribute("functionImportsJson")).jsonArray
        assertEquals(
            listOf("host.log_event", "host.read_config"),
            importFacts.map { it.jsonObject.getValue("import").jsonPrimitive.content }
        )
        val readConfig = importFacts.single {
            it.jsonObject.getValue("import").jsonPrimitive.content == "host.read_config"
        }.jsonObject
        assertEquals(
            listOf("i64"),
            readConfig.getValue("signature").jsonObject.getValue("params").jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("i32"),
            readConfig.getValue("signature").jsonObject.getValue("results").jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("process_order"),
            Json.parseToJsonElement(summary.attribute("exportedEntrypointsJson")).jsonArray.map {
                it.jsonObject.getValue("export").jsonPrimitive.content
            }
        )
        val imported = result.record("import-surface:import:host.read_config")
        assertEquals("function_import", imported.kind)
        assertEquals("host.read_config", imported.attribute("import"))
        assertEquals("[i64]", imported.attribute("params"))
        assertEquals("[i32]", imported.attribute("results"))

        val compactResult = queryArtifacts(wasm, operation = "import_surface", limit = 1)
        val compactSummary = compactResult.record("import-surface:summary")
        assertEquals("2", compactSummary.attribute("functionImportsJsonCount"))
        assertEquals(
            listOf("process_order"),
            Json.parseToJsonElement(compactSummary.attribute("exportedEntrypointsJson")).jsonArray.map {
                it.jsonObject.getValue("export").jsonPrimitive.content
            }
        )
    }

    @Test
    fun queryArtifactsApiDiffReturnsAbiDiffAndReferenceTypeRisks() {
        val baseline = createWasmFile(
            "api-baseline.wasm",
            """
            (module
              (func (export "stable") (param i32) (result i32) local.get 0)
              (func (export "changed") (param i32) (result i32) local.get 0)
              (func (export "removed") (result i64) i64.const 7))
            """.trimIndent()
        )
        val current = createWasmFile(
            "api-current.wasm",
            """
            (module
              (import "host" "bind_external" (func ${'$'}bind_external (param externref)))
              (func (export "added") (result i32) i32.const 9)
              (func (export "stable") (param i32) (result i32) local.get 0)
              (func (export "changed") (param i64) (result i64) local.get 0))
            """.trimIndent()
        )

        val result = queryArtifacts(current, baseline, operation = "api_diff", detail = "evidence")

        assertEquals("api_diff", result.operation)
        assertEquals(
            setOf(
                "api-diff:summary",
                "export:function:added",
                "export:function:changed",
                "export:function:removed",
                "abi-reference-import:host.bind_external"
            ),
            result.records.ids()
        )
        val changed = result.record("export:function:changed")
        assertEquals("changed", changed.status)
        assertEquals("[i32]", changed.attribute("previousParams"))
        assertEquals("[i64]", changed.attribute("currentParams"))
        assertEquals("0", changed.attribute("previousTypeidx"))
        assertEquals("3", changed.attribute("currentTypeidx"))
        val referenceImport = result.record("abi-reference-import:host.bind_external")
        assertEquals("reference_type_import", referenceImport.kind)
        assertEquals("import", referenceImport.locationKind)
        assertEquals("host", referenceImport.module)
        assertEquals("bind_external", referenceImport.name)
        assertEquals(0, referenceImport.typeidx)
        assertEquals("import host.bind_external", referenceImport.locationDisplay)
        assertEquals("reference-types", referenceImport.attribute("feature"))
        assertEquals("host.bind_external", referenceImport.attribute("import"))
        assertEquals("import host.bind_external", referenceImport.attribute("where"))
        assertEquals("import", referenceImport.attribute("locationKind"))
        assertEquals("[externref]", referenceImport.attribute("params"))
        assertEquals("0", referenceImport.attribute("typeidx"))
    }

    @Test
    fun queryArtifactsTriageSummarizesExportSignatureChanges() {
        val baseline = createWasmFile(
            "triage-baseline.wasm",
            "(module (func (export \"transform\") (param i32) (result i32) local.get 0))"
        )
        val current = createWasmFile(
            "triage-current.wasm",
            "(module (func (export \"transform\") (param i64) (result i64) local.get 0))"
        )

        val triage = queryArtifacts(current, baseline, operation = "triage", entityTypes = listOf("export"))

        assertEquals("triage", triage.operation)
        assertEquals("finding", triage.records.first().entityType)
        val changed = triage.record("export:function:transform")
        assertEquals("changed", changed.status)
        assertTrue(changed.summary.contains("signature from (i32) -> (i32) to (i64) -> (i64)"))
        assertEquals("(i32) -> (i32)", changed.attribute("previousSignature"))
        assertEquals("(i64) -> (i64)", changed.attribute("currentSignature"))
    }

    @Test
    fun queryArtifactsTriageIncludesElementSegmentDiff() {
        val elementBaseline = createWasmFile(
            "element-baseline.wasm",
            """
            (module (table 4 funcref) (func) (func) (func) (func)
              (elem (i32.const 0) func 0 1 2 3))
            """.trimIndent()
        )
        val elementCurrent = createWasmFile(
            "element-current.wasm",
            """
            (module (table 4 funcref) (func) (func) (func) (func)
              (elem (i32.const 0) func 0 1 3 2))
            """.trimIndent()
        )
        val elementTriage = queryArtifacts(elementCurrent, elementBaseline, operation = "triage", entityTypes = listOf("element"))
        val changed = elementTriage.record("element:0")
        assertEquals("changed", changed.status)
        assertEquals("[0,1,2,3]", changed.attribute("previousElements"))
        assertEquals("[0,1,3,2]", changed.attribute("currentElements"))
        assertTrue(changed.summary.contains("elements from [0,1,2,3] to [0,1,3,2]"))
    }

    @Test
    fun queryArtifactsSummaryDiffIgnoresFunctionBodyOnlyChanges() {
        val baseline = createWasmFile("body-baseline.wasm", "(module (func (export \"main\") (result i32) i32.const 1))")
        val current = createWasmFile(
            "body-current.wasm",
            "(module (func (export \"main\") (result i32) i32.const 1 i32.const 2 i32.add))"
        )

        val summaryDiff = queryArtifacts(current, baseline, operation = "diff", entityTypes = listOf("function"))

        assertTrue(summaryDiff.records.isEmpty())
    }

    @Test
    fun queryArtifactsEntrypointEffectsScansExportedMemoryWritesAndHostCalls() {
        val wasm = createWasmFile(
            "entrypoint-effects.wasm",
            """
            (module
              (import "env" "log_i32" (func ${'$'}log_i32 (param i32)))
              (import "env" "seed" (func ${'$'}seed (result i64)))
              (memory 1)
              (func (export "both") (param i32) (result i64)
                local.get 0 i32.const 7 i32.store call ${'$'}seed)
              (func (export "notify_host") (param i32) local.get 0 call ${'$'}log_i32)
              (func (export "write_u8") (param i32 i32) local.get 0 local.get 1 i32.store8)
              (func ${'$'}writer_helper (param i32 i32) local.get 0 local.get 1 i32.store)
              (func (export "forward_to_writer") (param i32 i32)
                local.get 0 local.get 1 call ${'$'}writer_helper)
              (func ${'$'}host_helper (param i32) local.get 0 call ${'$'}log_i32)
              (func (export "transitive_host") (param i32) local.get 0 call ${'$'}host_helper))
            """.trimIndent()
        )

        val result = queryArtifacts(wasm, operation = "entrypoint_effects")

        assertEquals("entrypoint_effects", result.operation)
        assertEquals(
            setOf(
                "entrypoint-effects:summary",
                "entrypoint-effects:export:both",
                "entrypoint-effects:export:notify_host",
                "entrypoint-effects:export:write_u8"
            ),
            result.records.ids()
        )
        val summary = result.record("entrypoint-effects:summary")
        assertEquals("5", summary.attribute("localFunctionExports"))
        assertEquals("3", summary.attribute("effectfulExports"))
        assertEquals("2", summary.attribute("memoryWritingExports"))
        assertEquals("2", summary.attribute("hostCallingExports"))
        assertEquals("both,write_u8", summary.attribute("memoryWritingExportNames"))
        assertEquals("both,notify_host", summary.attribute("hostCallingExportNames"))
        assertEquals("transitive_host", summary.attribute("transitiveHostCallingExportNames"))
        assertEquals(
            listOf("both", "notify_host"),
            Json.parseToJsonElement(summary.attribute("directHostEntryPointsJson")).jsonArray.map {
                it.jsonObject.getValue("export").jsonPrimitive.content
            }
        )
        assertEquals(
            listOf("transitive_host"),
            Json.parseToJsonElement(summary.attribute("excludedTransitiveExportsJson")).jsonArray.map {
                it.jsonPrimitive.content
            }
        )
        val write = result.record("entrypoint-effects:export:write_u8")
        assertEquals("[i32,i32]", write.attribute("params"))
        assertEquals("[]", write.attribute("results"))
        assertEquals("1", write.attribute("directMemoryWrites"))
        assertEquals("i32.store8", write.attribute("memoryWriteOps"))
        assertEquals("0", write.attribute("directHostCalls"))
        val notify = result.record("entrypoint-effects:export:notify_host")
        assertEquals("0", notify.attribute("directMemoryWrites"))
        assertEquals("1", notify.attribute("directHostCalls"))
        assertEquals("env.log_i32", notify.attribute("calledImports"))
        assertEquals("env.log_i32:(i32) -> ()", notify.attribute("calledImportSignatures"))
        val notifyPolicy = Json.parseToJsonElement(notify.attribute("directHostPolicyEntryJson")).jsonObject
        assertEquals("notify_host", notifyPolicy.getValue("export").jsonPrimitive.content)
        val notifyImport = notifyPolicy.getValue("called_imports").jsonArray.single().jsonObject
        assertEquals("env", notifyImport.getValue("module").jsonPrimitive.content)
        assertEquals("log_i32", notifyImport.getValue("name").jsonPrimitive.content)
        val both = result.record("entrypoint-effects:export:both")
        assertEquals("1", both.attribute("directMemoryWrites"))
        assertEquals("1", both.attribute("directHostCalls"))
        assertEquals("env.seed", both.attribute("calledImports"))
        assertEquals("[i64]", both.attribute("results"))
    }

    @Test
    fun queryArtifactsDataPayloadAuditReturnsRawSegmentMatches() {
        val wasm = createWasmFile(
            "data-payload-audit.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 128) "public telemetry endpoint")
              (data (i32.const 2048) "https://api.shadow.internal/v2/keys")
              (data "passive release notes")
              (data "FLAG_ENABLE_UNRELEASED_BILLING"))
            """.trimIndent()
        )

        val result = queryArtifacts(
            wasm,
            operation = "data_payload_audit",
            query = listOf("https://api.shadow.internal/v2/keys", "FLAG_ENABLE_UNRELEASED_BILLING"),
            detail = "evidence"
        )

        assertEquals("data_payload_audit", result.operation)
        val summary = result.record("data-payload-audit:summary")
        assertEquals("4", summary.attribute("scannedSegments"))
        assertEquals("1,3", summary.attribute("matchedSegments"))
        assertEquals("0,2", summary.attribute("cleanSegments"))
        val active = result.record("data-segment:1")
        assertEquals("active", active.kind)
        assertEquals("0", active.attribute("memory"))
        assertEquals("i32.const 2048", active.attribute("offsetExpr"))
        assertEquals("https://api.shadow.internal/v2/keys", active.attribute("matchedTerms"))
        assertEquals("true", active.attribute("rawBytesConfirmed"))
        val passive = result.record("data-segment:3")
        assertEquals("passive", passive.kind)
        assertEquals("FLAG_ENABLE_UNRELEASED_BILLING", passive.attribute("matchedTerms"))
        assertEquals("true", passive.attribute("rawBytesConfirmed"))
    }

    @Test
    fun queryArtifactsDataPayloadAuditWithoutTermsReturnsQueryReminderOnly() {
        val wasm = createWasmFile(
            "data-payload-audit-empty-query.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 128) "https://api.shadow.internal/v2/keys")
              (data "FLAG_ENABLE_UNRELEASED_BILLING"))
            """.trimIndent()
        )

        val result = queryArtifacts(wasm, operation = "data_payload_audit", query = listOf(""))

        assertEquals("data_payload_audit", result.operation)
        assertEquals(1, result.records.size)
        val reminder = result.record("data-payload-audit:needs-query")
        assertEquals("true", reminder.attribute("queryRequired"))
        assertEquals("2", reminder.attribute("scannedSegments"))
        assertEquals("1", reminder.attribute("activeSegments"))
        assertEquals("1", reminder.attribute("passiveSegments"))
    }

    @Test
    fun queryArtifactsLocalBudgetReturnsExportedFunctionLocalCounts() {
        val wasm = createWasmFile(
            "local-budget.wasm",
            """
            (module
              (func (export "health_check"))
              (func (export "hydrate_checkout") (local i32 i32 i32 i32 i64 i64))
              (func (export "render_invoice") (local f64 f64 i32 i32 i32))
              (func (export "route_cart") (local i32 i32 i32 i32)))
            """.trimIndent()
        )

        val result = queryArtifacts(
            wasm,
            operation = "local_budget",
            query = listOf("health_check", "hydrate_checkout", "render_invoice", "route_cart")
        )

        assertEquals("local_budget", result.operation)
        val summary = result.record("local-budget:summary")
        assertEquals("4", summary.attribute("functionExports"))
        assertEquals("6", summary.attribute("maxLocalCount"))
        assertEquals("hydrate_checkout", summary.attribute("maxLocalExports"))
        val hydrate = result.record("local-budget:export:hydrate_checkout")
        assertEquals("1", hydrate.attribute("funcidx"))
        assertEquals("6", hydrate.attribute("localCount"))
        assertEquals("i32=4,i64=2", hydrate.attribute("localsByType"))
        val invoice = result.record("local-budget:export:render_invoice")
        assertEquals("5", invoice.attribute("localCount"))
        assertEquals("f64=2,i32=3", invoice.attribute("localsByType"))
        val health = result.record("local-budget:export:health_check")
        assertEquals("0", health.attribute("localCount"))
        assertEquals("[]", health.attribute("localsByType"))
    }

    @Test
    fun queryArtifactsGlobalStatePolicyReturnsPolicyRelevantGlobals() {
        val baseline = createWasmFile(
            "global-policy-baseline.wasm",
            """
            (module
              (import "env" "readonly_epoch" (global i64))
              (global (export "abi_version") i32 (i32.const 7)))
            """.trimIndent()
        )
        val current = createWasmFile(
            "global-policy-current.wasm",
            """
            (module
              (import "env" "clock_offset" (global (mut i64)))
              (import "env" "readonly_epoch" (global i64))
              (global (export "abi_version") i32 (i32.const 7))
              (global (export "request_limit") (mut i32) (i32.const 2048))
              (global (export "feature_flag") i32 (i32.const 1)))
            """.trimIndent()
        )

        val result = queryArtifacts(current, baseline, operation = "global_state_policy")

        assertEquals("global_state_policy", result.operation)
        val mutableImport = result.record("global-policy:mutable-import:0")
        assertEquals("mutable_imported_global", mutableImport.kind)
        assertEquals("env.clock_offset", mutableImport.attribute("import"))
        assertEquals("i64", mutableImport.attribute("type"))
        val newExport = result.record("global-policy:new-export:request_limit")
        assertEquals("new_public_global", newExport.kind)
        assertEquals("request_limit", newExport.attribute("export"))
        assertEquals("true", newExport.attribute("mutable"))
    }

    @Test
    fun queryArtifactsMemoryContractCarriesImportIdentityAndLimitDiff() {
        val baseline = createWasmFile(
            "memory-contract-baseline.wasm",
            """
            (module
              (import "env" "checkout_host_memory" (memory 16 32))
              (export "checkout_memory" (memory 0)))
            """.trimIndent()
        )
        val current = createWasmFile(
            "memory-contract-current.wasm",
            """
            (module
              (import "env" "checkout_host_memory" (memory 24 48))
              (export "checkout_memory" (memory 0)))
            """.trimIndent()
        )

        val result = queryArtifacts(current, baseline, operation = "memory_contract")

        val memory = result.record("memory:0")
        assertEquals("changed", memory.status)
        assertEquals("env.checkout_host_memory", memory.attribute("import"))
        assertEquals("checkout_memory", memory.attribute("exportedAs"))
        assertEquals("16", memory.attribute("previousMin"))
        assertEquals("24", memory.attribute("currentMin"))
        assertEquals("32", memory.attribute("previousMax"))
        assertEquals("48", memory.attribute("currentMax"))
    }

    @Test
    fun queryArtifactsDispatchTableDiffResolvesChangedSlotTargets() {
        val baseline = createWasmFile(
            "dispatch-baseline.wasm",
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
        val current = createWasmFile(
            "dispatch-current.wasm",
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

        val result = queryArtifacts(current, baseline, operation = "dispatch_table_diff")

        val slot = result.record("dispatch-slot:0:2")
        assertEquals("dispatch_slot", slot.entityType)
        assertEquals("changed", slot.status)
        assertEquals("2", slot.attribute("previousFuncidx"))
        assertEquals("3", slot.attribute("currentFuncidx"))
        assertEquals("checkout_review", slot.attribute("previousExportedAs"))
        assertEquals("admin_reconcile", slot.attribute("currentExportedAs"))
    }

    @Test
    fun queryArtifactsHostCallDiffFindsAddedDirectImportCall() {
        val baseline = createWasmFile(
            "host-call-baseline.wasm",
            """
            (module
              (import "env" "log_i32" (func ${'$'}log (param i32)))
              (import "env" "read_preview_state" (func ${'$'}read (param i32)))
              (func (export "preview_sync_session") (param i32)
                local.get 0 call ${'$'}read local.get 0 call ${'$'}log))
            """.trimIndent()
        )
        val current = createWasmFile(
            "host-call-current.wasm",
            """
            (module
              (import "env" "begin_background_sync" (func ${'$'}begin (param i32)))
              (import "env" "log_i32" (func ${'$'}log (param i32)))
              (import "env" "read_preview_state" (func ${'$'}read (param i32)))
              (func (export "preview_sync_session") (param i32)
                local.get 0 call ${'$'}read local.get 0 call ${'$'}begin local.get 0 call ${'$'}log))
            """.trimIndent()
        )

        val result = queryArtifacts(current, baseline, operation = "host_call_diff", startName = "preview_sync_session")

        val added = result.record("host-call:preview_sync_session:env.begin_background_sync")
        assertEquals("added", added.status)
        assertEquals("env.begin_background_sync", added.attribute("hostImport"))
        assertEquals("preview_sync_session", added.attribute("rootName"))
        val summary = result.record("host-call:summary")
        assertEquals("env.log_i32,env.read_preview_state", summary.attribute("oldDirectHostCalls"))
        assertEquals(
            "env.begin_background_sync,env.log_i32,env.read_preview_state",
            summary.attribute("currentDirectHostCalls")
        )
        assertEquals("preview_sync_session", summary.attribute("entrypoints"))
    }

    private fun queryArtifacts(
        input: java.nio.file.Path,
        baseline: java.nio.file.Path? = null,
        operation: String,
        entityTypes: List<String> = emptyList(),
        query: List<String> = emptyList(),
        startName: String? = null,
        detail: String = "summary",
        limit: Int = 25
    ) = service.queryArtifacts(
        input = WasmInput.FilePath(input.toString()),
        baselineInput = baseline?.let { WasmInput.FilePath(it.toString()) },
        bindingInput = null,
        operation = operation,
        entityTypes = entityTypes,
        query = query,
        startName = startName,
        startFuncidx = null,
        relations = emptyList(),
        limit = limit,
        detail = detail,
        maxBytes = 24_000
    )

    private fun createWasmFile(name: String, wat: String): java.nio.file.Path {
        val target = temporaryFolder.newFile(name).toPath()
        Files.write(target, wat2wasm(wat))
        return target
    }

    private fun ArtifactRecordResult.attribute(name: String): String =
        attributes.single { it.name == name }.value

    private fun abiSurfaceWat(): String = """
        (module
          (import "host" "log_event" (func ${'$'}log_event (param i32)))
          (import "host" "read_config" (func ${'$'}read_config (param i64) (result i32)))
          (func (export "process_order") (param i32 i64) (result i32)
            i32.const 1))
    """.trimIndent()

    private fun List<ArtifactRecordResult>.ids(): Set<String> = map { it.id }.toSet()

    private fun List<org.jetbrains.hexana.mcp.model.types.ValueTypeCountResult>.valueTypeCountFor(type: String): Int =
        single { it.type == type }.count

    private fun List<org.jetbrains.hexana.mcp.model.types.ReferenceValueTypeCountResult>.referenceTypeCountFor(type: String): Int =
        single { it.type == type }.count

    private fun org.jetbrains.hexana.mcp.model.artifacts.QueryArtifactsResult.record(id: String): ArtifactRecordResult =
        records.single { it.id == id }
}
