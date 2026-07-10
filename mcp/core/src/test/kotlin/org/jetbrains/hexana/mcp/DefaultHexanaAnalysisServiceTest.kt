package org.jetbrains.hexana.mcp

import kotlinx.serialization.json.JsonNull
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultHexanaAnalysisServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val service = DefaultHexanaAnalysisService()

    @Test
    fun summarizeModuleReadsFilePathInput() {
        val wasm = createWasmFile(
            "summary.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "print" (func (param i32)))
              (memory 1)
              (func (export "main") (type 0) (param i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val result = service.summarizeModule(WasmInput.FilePath(wasm.toString()))

        assertEquals(1, result.functions)
        assertEquals(1, result.exports)
        assertEquals(1, result.memories)
        assertEquals(1, result.imports.functions)
        assertTrue(result.moduleSize > 0)
    }

    @Test
    fun summarizeModuleReadsInlineBytesInput() {
        val wasm = createWasmFile(
            "summary-inline.wasm",
            """
            (module
              (memory 1)
              (func (export "main")))
            """.trimIndent()
        )

        val result = service.summarizeModule(
            WasmInput.InlineBytes(Base64.getEncoder().encodeToString(Files.readAllBytes(wasm)))
        )

        assertEquals(1, result.functions)
        assertEquals(1, result.exports)
        assertEquals(1, result.memories)
        assertTrue(result.moduleSize > 0)
    }

    @Test
    fun summarizeModuleReadsRawBytesInput() {
        val wasm = createWasmFile(
            "summary-raw-bytes.wasm",
            """
              (module
              (memory 1)
              (func (export "main")))
            """.trimIndent()
        )

        val result = service.summarizeModule(WasmInput.RawBytes(Files.readAllBytes(wasm)))

        assertEquals(1, result.functions)
        assertEquals(1, result.exports)
        assertEquals(1, result.memories)
        assertTrue(result.moduleSize > 0)
    }

    @Test
    fun queryBinaryFindHonorsEntityTypesAndKeepsSummaryCompact() {
        val wasm = createWasmFile(
            "query-binary-find.wasm",
            """
            (module
              (import "env" "log" (func))
              (func (export "main")
                call 0))
            """.trimIndent()
        )

        val result = service.queryBinary(
            input = BinaryInput.FilePath(wasm.toString()),
            operation = "find",
            entityTypes = listOf("export"),
            query = listOf("main"),
            offset = 0,
            limit = 10,
            detail = "summary",
            maxBytes = 2_000,
        )

        assertEquals("find", result.operation)
        assertEquals(1, result.total)
        assertEquals(1, result.returned)
        val record = result.records.single()
        assertEquals("export", record.kind)
        assertEquals("main", record.name)
        assertEquals(null, record.location)
        assertTrue(record.evidence.isEmpty())
    }

    @Test
    fun queryBinaryEvidenceAddsLocationsOnlyForReturnedRecords() {
        val wasm = createWasmFile(
            "query-binary-evidence.wasm",
            """
            (module
              (func (export "first"))
              (func (export "second")))
            """.trimIndent()
        )

        val result = service.queryBinary(
            input = BinaryInput.FilePath(wasm.toString()),
            operation = "exports",
            entityTypes = emptyList(),
            query = emptyList(),
            offset = 1,
            limit = 1,
            detail = "evidence",
            maxBytes = 2_000,
        )

        assertEquals(2, result.total)
        assertEquals(1, result.returned)
        assertEquals("second", result.records.single().name)
        assertTrue(result.records.single().location != null)
        assertTrue(result.records.single().evidence.isNotEmpty())
        assertEquals(1, result.omitted.single { it.reason.contains("before requested") }.count)
        assertEquals(null, result.nextOffset)
    }

    @Test
    fun queryBinaryUnsupportedOperationReturnsBoundedOverviewWarning() {
        val result = service.queryBinary(
            input = BinaryInput.RawBytes(byteArrayOf(1, 2, 3)),
            operation = "hexdump",
            entityTypes = listOf("export"),
            query = emptyList(),
            offset = 0,
            limit = 10,
            detail = "summary",
            maxBytes = 2_000,
        )

        assertEquals("overview", result.operation)
        assertEquals(1, result.returned)
        assertTrue(result.warnings.any { it.code == "unsupported_operation" })
    }

    @Test
    fun queryBinaryUnknownEntityTypesDoNotReturnUnfilteredRecords() {
        val wasm = createWasmFile(
            "query-binary-unknown-entity.wasm",
            """
            (module
              (func (export "main")))
            """.trimIndent()
        )

        val result = service.queryBinary(
            input = BinaryInput.FilePath(wasm.toString()),
            operation = "find",
            entityTypes = listOf("not_a_kind"),
            query = emptyList(),
            offset = 0,
            limit = 10,
            detail = "summary",
            maxBytes = 2_000,
        )

        assertEquals(0, result.returned)
        assertTrue(result.warnings.any { it.code == "unsupported_entity_types" })
        assertTrue(result.warnings.any { it.code == "no_matching_entity_types" })
    }

    @Test
    fun referenceTypesGlobalAndTargetFeaturesDoNotRequireGc() {
        val wasm = createBinaryFile(
            "reference-types-with-target-features.wasm",
            wat2wasm(
                """
                (module
                  (type (func (param externref) (result i32 i64)))
                  (global (export "host_ref") externref (ref.null extern))
                  (func (export "check") (type 0) (param externref) (result i32 i64)
                    local.get 0
                    ref.is_null
                    i32.extend8_s
                    i64.const 0))
                """.trimIndent()
            ) + targetFeaturesSection("reference-types", "multivalue", "sign-ext")
        )

        val features = service.listEnabledFeatures(WasmInput.FilePath(wasm.toString())).features
        val proposals = service.listRequiredProposals(WasmInput.FilePath(wasm.toString())).summary.proposals
        val compatibility = service.checkRuntimeCompatibility(
            WasmInput.FilePath(wasm.toString()),
            runtime = "graalwasm",
            version = "23.1.2"
        )
        val baseline = createWasmFile(
            "reference-types-baseline.wasm",
            """
            (module
              (type (func (result i32 i64)))
              (func (export "base") (type 0)
                i32.const 1
                i64.const 2))
            """.trimIndent()
        )
        val diff = service.diffRequiredProposals(
            WasmInput.FilePath(wasm.toString()),
            WasmInput.FilePath(baseline.toString())
        )

        assertTrue(features.referenceTypes)
        assertFalse(features.gcTypes)
        assertTrue("reference-types" in proposals)
        assertTrue("multi-value" in proposals)
        assertTrue("sign-extension" in proposals)
        assertFalse("gc" in proposals)
        assertFalse("gc" in compatibility.requiredProposals)
        assertFalse("gc" in compatibility.unsupportedProposals)
        assertFalse("gc" in diff.newlyRequiredProposals)
    }

    @Test
    fun actualGcTypeRequiresGcProposal() {
        val wasm = createBinaryFile(
            "struct-type.wasm",
            wasmModule(
                wasmSection(
                    1,
                    encodeU32(1) + byteArrayOf(
                        0x5f.toByte(),
                        0x01,
                        0x7f.toByte(),
                        0x00
                    )
                )
            )
        )

        val features = service.listEnabledFeatures(WasmInput.FilePath(wasm.toString())).features
        val proposals = service.listRequiredProposals(WasmInput.FilePath(wasm.toString())).summary.proposals

        assertTrue(features.gcTypes)
        assertTrue("gc" in proposals)
    }

    @Test
    fun listImportsReturnsImportedFunctions() {
        val wasm = createWasmFile(
            "imports.wasm",
            """
            (module
              (import "env" "print" (func (param i32)))
              (func (export "main")
                i32.const 1
                call 0))
            """.trimIndent()
        )

        val result = service.listImports(WasmInput.FilePath(wasm.toString()))

        assertEquals(1, result.imports.size)
        assertEquals("env", result.imports.single().module)
        assertEquals("print", result.imports.single().name)
    }

    @Test
    fun granularToolsExposeStableReferenceTypeImportLocation() {
        val wasm = createWasmFile(
            "reference_type_import_location.wasm",
            """
            (module
              (type ${'$'}bind_type (func (param externref)))
              (import "host" "bind_external" (func (type ${'$'}bind_type))))
            """.trimIndent()
        )

        val imports = service.listImports(WasmInput.FilePath(wasm.toString()))
        val functions = service.listFunctions(WasmInput.FilePath(wasm.toString()), offset = 0, limit = 10)
        val types = service.listTypes(WasmInput.FilePath(wasm.toString()))

        val importedFunction = imports.imports.single()
        assertEquals(0, importedFunction.funcidx)
        assertEquals(0, importedFunction.typeidx)
        assertEquals("import", importedFunction.locationKind)
        assertEquals("host", importedFunction.module)
        assertEquals("bind_external", importedFunction.name)
        assertEquals("import host.bind_external", importedFunction.locationDisplay)
        assertEquals(listOf("externref"), importedFunction.signature!!.params)

        val function = functions.functions.single()
        assertEquals(0, function.funcidx)
        assertEquals(0, function.typeidx)
        assertEquals("import", function.definedIn)
        assertEquals("import", function.locationKind)
        assertEquals("host", function.module)
        assertEquals("bind_external", function.name)
        assertEquals("import host.bind_external", function.locationDisplay)

        val type = types.types.single()
        assertEquals(0, type.typeidx)
        assertEquals(listOf("ref extern"), type.params)
        val location = type.importLocations.single()
        assertEquals("import", location.locationKind)
        assertEquals("host", location.module)
        assertEquals("bind_external", location.name)
        assertEquals("import host.bind_external", location.locationDisplay)
        assertEquals(0, location.funcidx)
        assertEquals(0, location.typeidx)
    }

    @Test
    fun getEntrypointPolicyReturnsDirectHostCallContract() {
        val wasm = createWasmFile(
            "entrypoints.wasm",
            """
            (module
              (import "env" "log_i32" (func ${'$'}log_i32 (param i32)))
              (import "env" "seed" (func ${'$'}seed (result i64)))
              (func (export "notify_host") (param i32)
                local.get 0
                call ${'$'}log_i32)
              (func (export "read_seed") (result i64)
                call ${'$'}seed)
              (func ${'$'}host_helper (param i32)
                local.get 0
                call ${'$'}log_i32)
              (func (export "transitive_log") (param i32)
                local.get 0
                call ${'$'}host_helper))
            """.trimIndent()
        )

        val result = service.getEntrypointPolicy(WasmInput.FilePath(wasm.toString()))

        assertEquals("entrypoint-host-call-policy-v2", result.policyId)
        assertEquals(wasm.toString(), result.module)
        assertEquals(listOf("notify_host", "read_seed"), result.directHostEntrypoints.map { it.export })
        assertEquals(listOf("transitive_log"), result.excludedTransitiveExports)

        val notify = result.directHostEntrypoints.single { it.export == "notify_host" }
        assertEquals(2, notify.funcidx)
        assertEquals(listOf("i32"), notify.signature.params)
        assertEquals(emptyList(), notify.signature.results)
        assertEquals("env", notify.calledImports.single().module)
        assertEquals("log_i32", notify.calledImports.single().name)
        assertEquals(listOf("i32"), notify.calledImports.single().signature.params)
        assertEquals(emptyList(), notify.calledImports.single().signature.result)

        val readSeed = result.directHostEntrypoints.single { it.export == "read_seed" }
        assertEquals(listOf("i64"), readSeed.signature.results)
        assertEquals("seed", readSeed.calledImports.single().name)
    }

    @Test
    fun listExportsAndFunctionsReuseCurrentQueryShapes() {
        val wasm = createWasmFile(
            "functions.wasm",
            """
            (module
              (import "env" "log" (func (param i32)))
              (func (export "increment") (param i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val exports = service.listExports(WasmInput.FilePath(wasm.toString()))
        val functions = service.listFunctions(WasmInput.FilePath(wasm.toString()), offset = 0, limit = 10)

        assertEquals(listOf("increment"), exports.exports.map { it.name })
        assertEquals(2, functions.functions.size)
        assertEquals("import", functions.functions.first().definedIn)
        assertEquals("function_section", functions.functions.last().definedIn)
    }

    @Test
    fun listExportedFunctionsReturnsPaginatedEntries() {
        val wasm = createWasmFile(
            "exported_functions.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "log" (func (param i32)))
              (func ${'$'}main (type 0) (param i32) (result i32)
                local.get 0)
              (export "log_alias" (func 0))
              (export "main" (func 1)))
            """.trimIndent()
        )

        val result = service.listExportedFunctions(WasmInput.FilePath(wasm.toString()), entriesRange = 0..0)

        assertEquals(0, result.from)
        assertEquals(0, result.to)
        assertEquals(2, result.total)
        assertEquals(1, result.returned.size)
        assertEquals("log_alias", result.returned.single().name)
        assertEquals("env", result.returned.single().entry.module)
        assertEquals("log", result.returned.single().entry.name)
    }

    @Test
    fun listTypesAndMemoryReuseCurrentQueryShapes() {
        val wasm = createWasmFile(
            "types_memory.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "shared" (memory 1))
              (memory 2 4))
            """.trimIndent()
        )

        val types = service.listTypes(WasmInput.FilePath(wasm.toString()))
        val memory = service.listMemory(WasmInput.FilePath(wasm.toString()))

        assertEquals(1, types.types.size)
        assertEquals("func", types.types.single().kind)
        assertEquals("i32", types.types.single().params!!.single())
        assertEquals("i32", types.types.single().retTypes!!.single())
        assertEquals(2, memory.memories.size)
        assertEquals("import", memory.memories.first().definedIn)
        assertEquals("env", memory.memories.first().importInfo!!.module)
        assertEquals("shared", memory.memories.first().importInfo!!.name)
        assertEquals("memory_section", memory.memories.last().definedIn)
        assertEquals(null, memory.memories.last().importInfo)
        assertEquals(1L, memory.memories.first().limit.min)
        assertEquals(4L, memory.memories.last().limit.max)
    }

    @Test
    fun remainingQueriesReuseCurrentQueryShapes() {
        val wasm = createWasmFile(
            "rich_fixture.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "print" (func (param i32)))
              (import "env" "seed" (global i32))
              (memory (export "memory") 1)
              (table 1 funcref)
              (func ${'$'}increment (type 0) (param i32) (result i32)
                (local i32)
                local.get 0
                local.set 1
                local.get 1
                call 0
                local.get 1)
              (global (export "counter") (mut i64) (i64.const 7))
              (elem (i32.const 0) func 1)
              (data (i32.const 0) "Hello"))
            """.trimIndent()
        )

        val functions = service.functionsForIndices(WasmInput.FilePath(wasm.toString()), indices = listOf(0, 1))
        val globals = service.listGlobals(WasmInput.FilePath(wasm.toString()))
        val selectedGlobals = service.getGlobalsForIndices(WasmInput.FilePath(wasm.toString()), indices = listOf(1))
        val data = service.listData(WasmInput.FilePath(wasm.toString()), entriesRange = 0..0)
        val dataSegments = service.listDataSegments(
            input = WasmInput.FilePath(wasm.toString()),
            segmentStartInclusive = 0,
            segmentEndExclusive = 1,
            includeExports = false,
            includeGlobals = true,
            minByteLength = null
        )
        val selectedMemory = service.getMemoryForIndices(WasmInput.FilePath(wasm.toString()), indices = listOf(0))
        val selectedTypes = service.getTypesForIndices(WasmInput.FilePath(wasm.toString()), indices = listOf(0))
        val locals = service.getLocalsForFunctions(WasmInput.FilePath(wasm.toString()), indices = listOf(1))
        val instructions = service.getInstructionsForFunctions(WasmInput.FilePath(wasm.toString()), indices = listOf(1))
        val elements = service.listElementSegments(WasmInput.FilePath(wasm.toString()))

        assertEquals(2, functions.functions.size)
        assertEquals("env", functions.functions.first().importInfo!!.module)
        assertEquals(2, globals.globals.size)
        assertEquals("env", globals.globals.first().prefix)
        assertEquals(1, selectedGlobals.globals.single().globalIdx)
        assertEquals("SGVsbG8=", java.util.Base64.getEncoder().encodeToString(data.returned.single().data))
        assertEquals("Hello", dataSegments.segments.single().utf8Preview)
        assertEquals(1, dataSegments.summary.totalSegments)
        assertEquals(0, selectedMemory.memories.single().memoryIdx)
        assertEquals("func", selectedTypes.types.single().kind)
        assertEquals("i32", locals.functions.single().locals.single().type)
        assertTrue(instructions.functions.single().instructions.contains("call 0"))
        assertEquals("1", elements.segments.single().elements!!.single())
    }

    @Test
    fun auditLocalStackBudgetReturnsReportReadyExportVerdicts() {
        val wasm = createWasmFile(
            "local_budget_handlers.wasm",
            """
            (module
              (func (export "health_check"))
              (func (export "hydrate_checkout") (local i32 i32 i32 i32 i64 i64))
              (func (export "render_invoice") (local f64 f64 i32 i32 i32))
              (func (export "route_cart") (local i32 i32 i32 i32)))
            """.trimIndent()
        )
        val policy = temporaryFolder.newFile("local_budget.json").toPath()
        Files.writeString(
            policy,
            """
            {
              "runtime_id": "edge-runtime-local-budget",
              "max_locals": 4,
              "count_params": false,
              "checked_exports": [
                "health_check",
                "hydrate_checkout",
                "render_invoice",
                "route_cart"
              ]
            }
            """.trimIndent()
        )
        val log = temporaryFolder.newFile("verifier.log").toPath()
        Files.writeString(
            log,
            "2026-04-30T13:05:14Z runtime=edge-runtime-local-budget event=evt-local-7719 checked_exports=health_check,hydrate_checkout,render_invoice,route_cart\n"
        )

        val result = service.auditLocalStackBudget(
            input = WasmInput.FilePath(wasm.toString()),
            policyInput = TextInput.FilePath(policy.toString()),
            logInput = TextInput.FilePath(log.toString()),
            checkedExports = emptyList(),
            maxLocals = null,
            countParams = false
        )

        assertEquals("edge-runtime-local-budget", result.runtimeId)
        assertEquals(4, result.budget.maxLocals)
        assertEquals(listOf("health_check", "hydrate_checkout", "render_invoice", "route_cart"), result.checkedExports)
        assertEquals(listOf("hydrate_checkout", "render_invoice"), result.violations.map { it.export })
        assertEquals(mapOf("i32" to 4, "i64" to 2), result.violations.first().localsByType)
        assertEquals(listOf("health_check", "route_cart"), result.cleanExports)
        assertEquals("evt-local-7719", result.evidence.logEventId)
        assertEquals(result.violations.map { it.export }, result.minimalFix.splitOrReduceExports)
        assertEquals(result.violations.map { it.export }, result.recommendedReport.minimalFix.splitOrReduceExports)
        assertEquals("hydrate_checkout", result.recommendedReport.violations.first().export)
        assertEquals(6, result.recommendedReport.violations.first().localCount)
    }

    @Test
    fun auditLocalStackBudgetUsesProjectRelativeReportModuleUnderGitRoot() {
        Files.createDirectories(temporaryFolder.root.toPath().resolve("project/.git"))
        val wasm = createWasmFile(
            "project/dist/runtime/handlers.wasm",
            """
            (module
              (func (export "health_check"))
              (func (export "hydrate_checkout") (local i32 i32 i32 i32 i64 i64)))
            """.trimIndent()
        )
        val policy = createTextFile(
            "project/runtime/local_budget.json",
            """
            {
              "runtime_id": "edge-runtime-local-budget",
              "max_locals": 4,
              "count_params": false,
              "checked_exports": [
                "health_check",
                "hydrate_checkout"
              ]
            }
            """.trimIndent()
        )

        val result = service.auditLocalStackBudget(
            input = WasmInput.FilePath(wasm.toString()),
            policyInput = TextInput.FilePath(policy.toString()),
            logInput = null,
            checkedExports = emptyList(),
            maxLocals = null,
            countParams = false
        )

        assertEquals("dist/runtime/handlers.wasm", result.module.path)
        assertEquals("dist/runtime/handlers.wasm", result.recommendedReport.module)
    }

    @Test
    fun auditDataPayloadPolicyReturnsReportReadyRawByteMatches() {
        val wasm = createWasmFile(
            "payloads.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 0) "public payload")
              (data (i32.const 2048) "leaked https://api.shadow.internal/v2/keys endpoint")
              (data (i32.const 4096) "safe payload")
              (data "FLAG_ENABLE_UNRELEASED_BILLING is passive"))
            """.trimIndent()
        )
        val policy = temporaryFolder.newFile("payload_policy.json").toPath()
        Files.writeString(
            policy,
            """
            {
              "policy_id": "payload-policy-2026-04",
              "forbidden_payloads": [
                {
                  "id": "shadow-admin-endpoint",
                  "match": "https://api.shadow.internal/v2/keys",
                  "severity": "critical"
                },
                {
                  "id": "unreleased-billing-flag",
                  "match": "FLAG_ENABLE_UNRELEASED_BILLING",
                  "severity": "high"
                },
                {
                  "id": "legacy-debug-token",
                  "match": "DEBUG_TOKEN_DO_NOT_SHIP",
                  "severity": "medium"
                }
              ]
            }
            """.trimIndent()
        )

        val result = service.auditDataPayloadPolicy(
            WasmInput.FilePath(wasm.toString()),
            TextInput.FilePath(policy.toString())
        )

        assertEquals("payload-policy-2026-04", result.policyId)
        assertEquals(wasm.toString(), result.module)
        assertEquals(4, result.scannedSegments)
        assertEquals(listOf(0, 2), result.cleanSegments)
        assertEquals(listOf(1, 3), result.minimalFix.removeOrReplaceSegments)
        assertEquals(listOf(1, 3), result.matches.map { it.segmentIndex })
        assertTrue(result.matches.all { it.rawBytesConfirmed })
        assertEquals(0, result.matches.first().memory)
        assertEquals("i32.const 2048", result.matches.first().offsetExpr)
        assertEquals(null, result.matches.last().memory)
        assertEquals(null, result.matches.last().offsetExpr)
        assertEquals(result.policyId, result.recommendedReport.policyId)
        assertEquals(result.scannedSegments, result.recommendedReport.scannedSegments)
        assertEquals(result.cleanSegments, result.recommendedReport.cleanSegments)
        assertEquals(result.minimalFix.removeOrReplaceSegments, result.recommendedReport.minimalFix.removeOrReplaceSegments)
        assertEquals("shadow-admin-endpoint", result.recommendedReport.matches.first().policyEntryId)
        assertEquals("high", result.recommendedReport.matches.last().severity)
        assertEquals(JsonNull, result.recommendedReport.matches.last().memory)
        assertEquals(JsonNull, result.recommendedReport.matches.last().offsetExpr)
    }

    @Test
    fun auditDataPayloadPolicyUsesProjectRelativeReportModuleUnderGitRoot() {
        Files.createDirectories(temporaryFolder.root.toPath().resolve("project/.git"))
        val wasm = createWasmFile(
            "project/dist/runtime/payloads.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 0) "DEBUG_TOKEN_DO_NOT_SHIP"))
            """.trimIndent()
        )
        val policy = createTextFile(
            "project/config/payload_policy.json",
            """
            {
              "policy_id": "payload-policy-test",
              "forbidden_payloads": [
                {
                  "id": "legacy-debug-token",
                  "match": "DEBUG_TOKEN_DO_NOT_SHIP",
                  "severity": "medium"
                }
              ]
            }
            """.trimIndent()
        )

        val result = service.auditDataPayloadPolicy(
            WasmInput.FilePath(wasm.toString()),
            TextInput.FilePath(policy.toString())
        )

        assertEquals("dist/runtime/payloads.wasm", result.module)
        assertEquals("dist/runtime/payloads.wasm", result.recommendedReport.module)
    }

    @Test
    fun auditDataPayloadPolicyKeepsAbsoluteReportModuleOutsideKnownProjectRoot() {
        val wasm = createWasmFile(
            "outside-payloads.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 0) "DEBUG_TOKEN_DO_NOT_SHIP"))
            """.trimIndent()
        )
        val policy = createTextFile(
            "outside-payload-policy.json",
            """
            {
              "policy_id": "payload-policy-test",
              "forbidden_payloads": [
                {
                  "id": "legacy-debug-token",
                  "match": "DEBUG_TOKEN_DO_NOT_SHIP",
                  "severity": "medium"
                }
              ]
            }
            """.trimIndent()
        )

        val result = service.auditDataPayloadPolicy(
            WasmInput.FilePath(wasm.toString()),
            TextInput.FilePath(policy.toString())
        )

        assertEquals(wasm.toString(), result.module)
        assertEquals(wasm.toString(), result.recommendedReport.module)
    }

    @Test
    fun globalStatePolicyReturnsTargetedGlobalsAmongManyStableGlobals() {
        val baseline = createWasmFile(
            "global-policy-baseline.wasm",
            globalStatePolicyWat(
                includeClockOffset = false,
                includeRequestLimit = false,
                includeFeatureFlag = false
            )
        )
        val current = createWasmFile(
            "global-policy-current.wasm",
            globalStatePolicyWat(
                includeClockOffset = true,
                includeRequestLimit = true,
                includeFeatureFlag = true
            )
        )

        val globals = service.listGlobals(WasmInput.FilePath(current.toString())).globals
        val policy = service.queryArtifacts(
            input = WasmInput.FilePath(current.toString()),
            baselineInput = WasmInput.FilePath(baseline.toString()),
            bindingInput = null,
            operation = "global_state_policy",
            entityTypes = emptyList(),
            query = emptyList(),
            startName = null,
            startFuncidx = null,
            relations = emptyList(),
            limit = 25,
            detail = "summary",
            maxBytes = 24_000
        )

        assertEquals(listOf("request_limit"), globals.single { it.globalIdx == 34 }.exportedAs)
        assertEquals(listOf("feature_flag"), globals.single { it.globalIdx == 50 }.exportedAs)

        val summary = policy.records.single { it.id == "global-policy:summary" }
        assertEquals("2,34,50", summary.attributeValue("targetedGlobalIndices"))
        assertEquals("abi_version,stable_limit_0", summary.attributeValue("allowedPublicGlobals")!!.substringBefore(",stable_limit_1"))
        assertTrue(summary.attributeValue("newPublicGlobalsJson")!!.contains("\"export\":\"request_limit\""))
        assertTrue(summary.attributeValue("newPublicGlobalsJson")!!.contains("\"globalidx\":34"))
        assertTrue(summary.attributeValue("newPublicGlobalsJson")!!.contains("\"initExpr\":\"i32.const 2048\""))
        assertTrue(summary.attributeValue("newPublicGlobalsJson")!!.contains("\"export\":\"feature_flag\""))
        assertTrue(summary.attributeValue("newPublicGlobalsJson")!!.contains("\"globalidx\":50"))
        assertTrue(summary.attributeValue("mutableImportedGlobalsJson")!!.contains("\"import\":\"env.clock_offset\""))

        val mutableImport = policy.records.single { it.id == "global-policy:mutable-import:2" }
        assertEquals("mutable_imported_global", mutableImport.kind)
        assertEquals("env.clock_offset", mutableImport.attributeValue("import"))
        assertEquals("2", mutableImport.attributeValue("globalidx"))

        val requestLimit = policy.records.single { it.id == "global-policy:new-export:request_limit" }
        assertEquals(34, requestLimit.index)
        assertEquals("i32.const 2048", requestLimit.attributeValue("initExpr"))

        val featureFlag = policy.records.single { it.id == "global-policy:new-export:feature_flag" }
        assertEquals(50, featureFlag.index)
        assertEquals("i32.const 1", featureFlag.attributeValue("initExpr"))
    }

    @Test
    fun queryArtifactsPreservesCanonicalArtifactPathForNestedBasenameCollisions() {
        val baseline = createWasmFile(
            "previous/runtime/sync.wasm",
            """
            (module
              (func (export "sync") (result i32)
                i32.const 1))
            """.trimIndent()
        )
        val current = createWasmFile(
            "dist/runtime/sync.wasm",
            """
            (module
              (func (export "sync") (result i64)
                i64.const 1))
            """.trimIndent()
        )

        val result = service.queryArtifacts(
            input = WasmInput.FilePath(current.toString()),
            baselineInput = WasmInput.FilePath(baseline.toString()),
            bindingInput = null,
            operation = "triage",
            entityTypes = listOf("export"),
            query = emptyList(),
            startName = null,
            startFuncidx = null,
            relations = emptyList(),
            limit = 25,
            detail = "summary",
            maxBytes = 24_000
        )

        assertEquals(current.toString(), result.modules.single { it.source == "current" }.artifactPath)
        assertEquals(baseline.toString(), result.modules.single { it.source == "baseline" }.artifactPath)
        assertEquals("sync.wasm", result.modules.single { it.source == "current" }.artifactName)
        assertEquals("sync.wasm", result.modules.single { it.source == "baseline" }.artifactName)

        val changedExport = result.records.single { it.id == "export:function:sync" }
        assertEquals(current.toString(), changedExport.artifactPath)
        assertEquals("sync.wasm", changedExport.artifactName)
        assertTrue(changedExport.artifactPath!!.endsWith("dist/runtime/sync.wasm"))
    }

    @Test
    fun queryArtifactsCrashFramesReturnsStableOffsetAndIndexEvidence() {
        val wasm = createWasmFile(
            "dist/runtime/crash.wasm",
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

        val result = service.queryArtifacts(
            input = WasmInput.FilePath(wasm.toString()),
            baselineInput = null,
            bindingInput = null,
            operation = "crash_frames",
            entityTypes = emptyList(),
            query = listOf("4", "2", "0"),
            startName = null,
            startFuncidx = null,
            relations = emptyList(),
            limit = 25,
            detail = "summary",
            maxBytes = 24_000
        )

        val summary = result.records.single { it.id == "crash-frames:summary" }
        assertEquals("0,2,4", summary.attributeValue("funcidx"))
        assertEquals("2,4", summary.attributeValue("callerFuncidx"))
        assertEquals("0,1", summary.attributeValue("targetFuncidx"))
        assertEquals("env.log_error,env.read_clock", summary.attributeValue("directImportedCalls"))
        assertEquals(
            summary.attributeValue("instructionOffsets")!!.split(",").map { it.toLong() }.sorted(),
            summary.attributeValue("instructionOffsets")!!.split(",").map { it.toLong() }
        )

        val hostCalls = result.records.filter { it.entityType == "host_call" }
        assertEquals(
            hostCalls.mapNotNull { it.attributeValue("offset")?.toLong() }.sorted(),
            hostCalls.mapNotNull { it.attributeValue("offset")?.toLong() }
        )
        assertTrue(hostCalls.all { it.artifactPath == wasm.toString() })
    }

    @Test
    fun auditGlobalStatePolicyReportsAllowedForbiddenAndMutableGlobals() {
        val baseline = createWasmFile(
            "global-audit-baseline.wasm",
            globalStatePolicyWat(
                includeClockOffset = false,
                includeRequestLimit = false,
                includeFeatureFlag = false
            )
        )
        val current = createWasmFile(
            "global-audit-current.wasm",
            globalStatePolicyWat(
                includeClockOffset = true,
                includeRequestLimit = true,
                includeFeatureFlag = true
            )
        )
        val policy = createTextFile(
            "global-audit-policy.json",
            """
            {
              "policyId": "global-state-policy-v1",
              "allowedPublicGlobals": ["request_limit"],
              "requestLimits": {
                "exports": ["request_limit"],
                "maxValue": 2048
              }
            }
            """.trimIndent()
        )

        val result = service.auditGlobalStatePolicy(
            input = WasmInput.FilePath(current.toString()),
            baselineInput = WasmInput.FilePath(baseline.toString()),
            policyInput = TextInput.FilePath(policy.toString()),
            logInput = null
        )

        assertEquals(current.toString(), result.module.path)
        assertEquals(baseline.toString(), result.module.baselinePath)
        assertEquals(policy.toString(), result.module.policyPath)

        assertEquals("global-state-policy-v1", result.policyId)
        assertEquals("compact", result.detail.mode)
        assertEquals(12_000, result.detail.compactPayloadBudgetEstimatedTokens)
        assertEquals(listOf(2, 34, 50), result.targetedGlobalIndices)

        val requestLimit = result.newPublicGlobals.single { it.export == "request_limit" }
        assertEquals(34, requestLimit.globalidx)
        assertTrue(requestLimit.allowed)
        assertEquals("request_limits", requestLimit.allowedBy)
        assertEquals("i32.const 2048", requestLimit.initExpr)

        val featureFlag = result.newPublicGlobals.single { it.export == "feature_flag" }
        assertEquals(50, featureFlag.globalidx)
        assertFalse(featureFlag.allowed)

        assertFalse(result.allowedPublicGlobals.any { it.allowedBy == "baseline" })
        assertTrue(result.allowedPublicGlobals.any { it.export == "request_limit" && it.allowedBy == "request_limits" })
        assertTrue(result.allowedPublicGlobalSummary.names.contains("abi_version"))
        assertTrue(result.allowedPublicGlobalSummary.names.contains("request_limit"))
        assertEquals(result.allowedPublicGlobalSummary.count - result.allowedPublicGlobals.size, result.allowedPublicGlobalSummary.omittedObjectCount)
        assertTrue(result.unchangedGlobalSummary.unchangedPublicGlobalNames.contains("abi_version"))

        val requestLimitFact = result.requestLimits.single()
        assertEquals("request_limit", requestLimitFact.export)
        assertEquals(2048, requestLimitFact.value)
        assertEquals(2048, requestLimitFact.maxValue)
        assertTrue(requestLimitFact.allowed)

        assertEquals(
            listOf("mutable_imported_global", "forbidden_public_global"),
            result.violations.map { it.kind }
        )
        val mutableImport = result.violations.single { it.kind == "mutable_imported_global" }
        assertEquals(2, mutableImport.globalidx)
        assertEquals("env.clock_offset", mutableImport.importNameQualified)

        val forbiddenGlobal = result.violations.single { it.kind == "forbidden_public_global" }
        assertEquals(50, forbiddenGlobal.globalidx)
        assertEquals("feature_flag", forbiddenGlobal.export)

        assertTrue(result.evidence.any { it.globalidx == 34 && it.export == "request_limit" })
        assertTrue(result.evidence.any { it.globalidx == 2 && it.importNameQualified == "env.clock_offset" })
        assertFalse(result.evidence.any { it.kind == "baseline_public" })

        val fullResult = service.auditGlobalStatePolicy(
            input = WasmInput.FilePath(current.toString()),
            baselineInput = WasmInput.FilePath(baseline.toString()),
            policyInput = TextInput.FilePath(policy.toString()),
            logInput = null,
            detail = "full"
        )
        assertEquals("full", fullResult.detail.mode)
        assertTrue(fullResult.allowedPublicGlobals.any { it.export == "abi_version" && it.allowedBy == "baseline" })
        assertEquals(fullResult.allowedPublicGlobalSummary.count, fullResult.allowedPublicGlobals.size)
    }

    @Test
    fun auditGlobalStatePolicyReturnsCompactRecommendedReportForReportOnlyPolicy() {
        val baseline = createWasmFile(
            "global-report-only-baseline.wasm",
            globalStatePolicyWat(
                includeClockOffset = false,
                includeRequestLimit = false,
                includeFeatureFlag = false
            )
        )
        val current = createWasmFile(
            "global-report-only-current.wasm",
            globalStatePolicyWat(
                includeClockOffset = true,
                includeRequestLimit = true,
                includeFeatureFlag = true
            )
        )
        val policy = createTextFile(
            "global-report-only-policy.json",
            """
            {
              "policy_id": "host-global-state-policy-2026-04",
              "allow_mutable_imported_globals": false,
              "report_new_exported_globals": true
            }
            """.trimIndent()
        )
        val log = createTextFile(
            "global-report-only.log",
            """
            2026-04-30T15:20:33Z policy=host-global-state-policy-2026-04 event=evt-global-5501 status=blocked module=dist/runtime/global_state.wasm reason=global-state-release-gate
            2026-04-30T15:20:34Z policy=host-global-state-policy-2026-04 event=evt-global-5501 status=needs-report module=dist/runtime/global_state.wasm compared_to=previous_build/dist/runtime/global_state.wasm
            """.trimIndent()
        )

        val result = service.auditGlobalStatePolicy(
            input = WasmInput.FilePath(current.toString()),
            baselineInput = WasmInput.FilePath(baseline.toString()),
            policyInput = TextInput.FilePath(policy.toString()),
            logInput = TextInput.FilePath(log.toString())
        )

        assertEquals(listOf("mutable_imported_global"), result.violations.map { it.kind })
        assertEquals("host-global-state-policy-2026-04", result.report.policyId)
        assertEquals("dist/runtime/global_state.wasm", result.report.module)
        assertEquals(listOf("mutable_imported_global"), result.report.violations.map { it.kind })
        assertEquals(listOf("feature_flag", "request_limit"), result.report.newPublicGlobals.map { it.export })
        assertEquals(listOf(2, 34, 50), result.report.evidence.targetedGlobalIndices)
        assertEquals("evt-global-5501", result.report.evidence.logEventId)
        assertEquals("previous_build/dist/runtime/global_state.wasm", result.report.evidence.comparedTo)
        assertTrue(result.report.allowedPublicGlobals.first() == "abi_version")
        assertEquals(listOf("feature_flag", "request_limit"), result.recommendedReport.newPublicGlobals.map { it.export })
        assertEquals(listOf(2, 34, 50), result.recommendedReport.targetedGlobalIndices)
        assertEquals("evt-global-5501", result.recommendedReport.logEventId)
        assertEquals("previous_build/dist/runtime/global_state.wasm", result.recommendedReport.comparedTo)
        assertTrue(result.recommendedReport.allowedPublicGlobals.first() == "abi_version")
        assertTrue(result.detail.allowedPublicGlobalsReturned < result.detail.allowedPublicGlobalsTotal)
    }

    @Test
    fun auditGlobalStatePolicyReportsRequestLimitViolation() {
        val baseline = createWasmFile(
            "global-request-limit-baseline.wasm",
            """
            (module
              (global (export "abi_version") i32 (i32.const 7)))
            """.trimIndent()
        )
        val current = createWasmFile(
            "global-request-limit-current.wasm",
            """
            (module
              (global (export "abi_version") i32 (i32.const 7))
              (global (export "request_limit") i32 (i32.const 4096)))
            """.trimIndent()
        )
        val policy = createTextFile(
            "global-request-limit-policy.json",
            """{"requestLimits":{"exports":["request_limit"],"maxValue":2048}}"""
        )

        val result = service.auditGlobalStatePolicy(
            input = WasmInput.FilePath(current.toString()),
            baselineInput = WasmInput.FilePath(baseline.toString()),
            policyInput = TextInput.FilePath(policy.toString()),
            logInput = null
        )

        assertEquals(listOf("request_limit_exceeded"), result.violations.map { it.kind })
        assertEquals(4096, result.requestLimits.single().value)
        assertEquals(2048, result.requestLimits.single().maxValue)
    }

    @Test
    fun abiBindingRegenerationComparesCurrentWasmToStalePythonBindings() {
        val wasm = createWasmFile(
            "pricing_plugin.wasm",
            """
            (module
              (import "env" "emit_metric" (func (param i32 i64)))
              (import "env" "fetch_discount" (func (param i32) (result i32)))
              (func (export "calculate_total") (param i32 i32) (result i64)
                i64.const 0)
              (func (export "adapter_version") (result i32)
                i32.const 2))
            """.trimIndent()
        )
        val binding = temporaryFolder.newFile("pricing_bindings.py").toPath().also {
            Files.writeString(
                it,
                """
                MODULE = 'dist/plugin/pricing_plugin.wasm'
                IMPORTS = {
                    'env.emit_metric': {'params': ['i32'], 'results': []},
                    'env.fetch_discount': {'params': ['i64'], 'results': ['i32']},
                }
                EXPORTS = {
                    'calculate_total': {'params': ['i32', 'i32'], 'results': ['i32']},
                    'legacy_price': {'params': ['i32'], 'results': ['i32']},
                }
                """.trimIndent()
            )
        }

        val result = service.queryArtifacts(
            input = WasmInput.FilePath(wasm.toString()),
            baselineInput = null,
            bindingInput = TextInput.FilePath(binding.toString()),
            operation = "abi_binding_regeneration",
            entityTypes = emptyList(),
            query = emptyList(),
            startName = null,
            startFuncidx = null,
            relations = emptyList(),
            limit = 25,
            detail = "summary",
            maxBytes = 24_000
        )

        val summary = result.records.single { it.id == "abi-binding-regeneration:summary" }
        assertEquals("5", summary.attributeValue("mismatches"))
        assertTrue(summary.attributeValue("replacementBindingsJson")!!.contains("\"MODULE\":\"dist/plugin/pricing_plugin.wasm\""))
        assertTrue(summary.attributeValue("replacementBindingsJson")!!.contains("\"adapter_version\""))
        assertTrue(summary.attributeValue("currentImportsJson")!!.contains("\"env.emit_metric\""))
        assertTrue(summary.attributeValue("bindingMismatchesJson")!!.contains("\"symbol\":\"EXPORTS.legacy_price\""))

        fun mismatch(symbol: String) = result.records.single { it.attributeValue("symbol") == symbol }

        val missingExport = mismatch("EXPORTS.adapter_version")
        assertEquals("added", missingExport.status)
        assertEquals("""{"params":[],"results":["i32"]}""", missingExport.attributeValue("expectedSignatureJson"))
        assertEquals("null", missingExport.attributeValue("actualSignatureJson"))

        val changedExport = mismatch("EXPORTS.calculate_total")
        assertEquals("""{"params":["i32","i32"],"results":["i64"]}""", changedExport.attributeValue("expectedSignatureJson"))
        assertEquals("""{"params":["i32","i32"],"results":["i32"]}""", changedExport.attributeValue("actualSignatureJson"))

        val staleExport = mismatch("EXPORTS.legacy_price")
        assertEquals("removed", staleExport.status)
        assertEquals("null", staleExport.attributeValue("expectedSignatureJson"))
        assertEquals("""{"params":["i32"],"results":["i32"]}""", staleExport.attributeValue("actualSignatureJson"))

        val changedImport = mismatch("IMPORTS.env.emit_metric")
        assertEquals("""{"params":["i32","i64"],"results":[]}""", changedImport.attributeValue("expectedSignatureJson"))
        assertEquals("""{"params":["i32"],"results":[]}""", changedImport.attributeValue("actualSignatureJson"))
    }

    @Test
    fun planAbiBindingRegenerationReportsImportChanges() {
        val wasm = createWasmFile(
            "binding_imports.wasm",
            """
            (module
              (import "env" "emit_metric" (func (param i32 i64)))
              (import "env" "fetch_discount" (func (result i32))))
            """.trimIndent()
        )
        val binding = createBindingFile(
            "binding_imports.py",
            """
            MODULE = 'dist/plugin/pricing_plugin.wasm'
            IMPORTS = {
                'env.emit_metric': {'params': ['i32'], 'results': []},
                'env.legacy': {'params': [], 'results': []},
            }
            EXPORTS = {}
            """.trimIndent()
        )

        val result = service.planAbiBindingRegeneration(
            input = WasmInput.FilePath(wasm.toString()),
            bindingInput = TextInput.FilePath(binding.toString())
        )

        assertEquals(listOf("env.emit_metric", "env.fetch_discount"), result.imports.keys.toList())
        assertEquals(
            setOf("IMPORTS.env.emit_metric", "IMPORTS.env.fetch_discount", "IMPORTS.env.legacy"),
            result.bindingMismatches.map { it.symbol }.toSet()
        )
        assertEquals(listOf("i32", "i64"), result.replacementBindings.imports.getValue("env.emit_metric").params)
        assertEquals(listOf("i32"), result.replacementBindings.imports.getValue("env.fetch_discount").results)
    }

    @Test
    fun planAbiBindingRegenerationReportsExportChanges() {
        val wasm = createWasmFile(
            "binding_exports.wasm",
            """
            (module
              (func (export "calculate_total") (param i32 i32) (result i64)
                i64.const 0)
              (func (export "adapter_version") (result i32)
                i32.const 2))
            """.trimIndent()
        )
        val binding = createBindingFile(
            "binding_exports.py",
            """
            MODULE = 'dist/plugin/pricing_plugin.wasm'
            IMPORTS = {}
            EXPORTS = {
                'calculate_total': {'params': ['i32', 'i32'], 'results': ['i64']},
                'legacy_price': {'params': ['i32'], 'results': ['i32']},
            }
            """.trimIndent()
        )

        val result = service.planAbiBindingRegeneration(
            input = WasmInput.FilePath(wasm.toString()),
            bindingInput = TextInput.FilePath(binding.toString())
        )

        assertEquals(listOf("adapter_version", "calculate_total"), result.exports.keys.toList())
        assertEquals(
            setOf("EXPORTS.adapter_version", "EXPORTS.legacy_price"),
            result.bindingMismatches.map { it.symbol }.toSet()
        )
        assertEquals(listOf("i32"), result.replacementBindings.exports.getValue("adapter_version").results)
    }

    @Test
    fun planAbiBindingRegenerationReportsFunctionSignatureDriftWithResultsField() {
        val wasm = createWasmFile(
            "binding_signature_drift.wasm",
            """
            (module
              (import "env" "fetch_discount" (func (param i32) (result i32)))
              (func (export "calculate_total") (param i32 i32) (result i64)
                i64.const 0))
            """.trimIndent()
        )
        val binding = createBindingFile(
            "binding_signature_drift.py",
            """
            MODULE = 'dist/plugin/pricing_plugin.wasm'
            IMPORTS = {
                'env.fetch_discount': {'params': ['i64'], 'results': ['i32']},
            }
            EXPORTS = {
                'calculate_total': {'params': ['i32', 'i32'], 'results': ['i32']},
            }
            """.trimIndent()
        )

        val result = service.planAbiBindingRegeneration(
            input = WasmInput.FilePath(wasm.toString()),
            bindingInput = TextInput.FilePath(binding.toString())
        )

        val importMismatch = result.bindingMismatches.single { it.symbol == "IMPORTS.env.fetch_discount" }
        assertEquals(listOf("i32"), importMismatch.expected!!.params)
        assertEquals(listOf("i64"), importMismatch.actual!!.params)
        val exportMismatch = result.bindingMismatches.single { it.symbol == "EXPORTS.calculate_total" }
        assertEquals(listOf("i64"), exportMismatch.expected!!.results)
        assertEquals(listOf("i32"), exportMismatch.actual!!.results)
    }

    @Test
    fun planAbiBindingRegenerationReturnsNoMismatchesForCompatibleBindings() {
        val wasm = createWasmFile(
            "binding_compatible.wasm",
            """
            (module
              (import "env" "emit_metric" (func (param i32 i64)))
              (func (export "adapter_version") (result i32)
                i32.const 2))
            """.trimIndent()
        )
        val binding = createBindingFile(
            "binding_compatible.py",
            """
            MODULE = 'dist/plugin/pricing_plugin.wasm'
            IMPORTS = {
                'env.emit_metric': {'params': ['i32', 'i64'], 'results': []},
            }
            EXPORTS = {
                'adapter_version': {'params': [], 'results': ['i32']},
            }
            """.trimIndent()
        )

        val result = service.planAbiBindingRegeneration(
            input = WasmInput.FilePath(wasm.toString()),
            bindingInput = TextInput.FilePath(binding.toString())
        )

        assertTrue(result.bindingMismatches.isEmpty())
        assertEquals(result.imports, result.replacementBindings.imports)
        assertEquals(result.exports, result.replacementBindings.exports)
        assertEquals("dist/plugin/pricing_plugin.wasm", result.module.replacementModule)
    }

    @Test
    fun listDataPaginatesWithoutRepeatingCurrentEntry() {
        val wasm = createWasmFile(
            "data_pagination.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 0) "A")
              (data (i32.const 1) "B"))
            """.trimIndent()
        )

        val first = service.listData(WasmInput.FilePath(wasm.toString()), entriesRange = 0..0)
        val second = service.listData(WasmInput.FilePath(wasm.toString()), entriesRange = 1..1)

        assertEquals(0, first.from)
        assertEquals(0, first.to)
        assertEquals(listOf(0), first.returned.map { it.index })
        assertEquals(false, first.truncate)
        assertEquals(1, first.nextOffset)
        assertEquals(listOf(1), second.returned.map { it.index })
        assertEquals(false, second.truncate)
        assertEquals(null, second.nextOffset)
    }

    @Test
    fun listDataReportsRequestedRangeForOutOfRangeSlice() {
        val wasm = createWasmFile(
            "data_out_of_range.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 0) "A"))
            """.trimIndent()
        )

        val result = service.listData(WasmInput.FilePath(wasm.toString()), entriesRange = 5..5)

        assertEquals(5, result.from)
        assertEquals(5, result.to)
        assertTrue(result.returned.isEmpty())
        assertEquals(false, result.truncate)
        assertEquals(null, result.nextOffset)
    }

    @Test
    fun listDataMarksRangeClippedOnlyWhenReturnedDataWasClippedAtEnd() {
        val wasm = createWasmFile(
            "data_clipped_range.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 0) "A")
              (data (i32.const 1) "B"))
            """.trimIndent()
        )

        val result = service.listData(WasmInput.FilePath(wasm.toString()), entriesRange = 0..5)

        assertEquals(0, result.from)
        assertEquals(5, result.to)
        assertEquals(listOf(0, 1), result.returned.map { it.index })
        assertEquals(true, result.truncate)
        assertEquals(null, result.nextOffset)
    }

    @Test
    fun listDataReturnsStableMetadataForEmptyDataSection() {
        val wasm = createWasmFile(
            "data_empty_section.wasm",
            """
            (module)
            """.trimIndent()
        )

        val result = service.listData(WasmInput.FilePath(wasm.toString()), entriesRange = 0..0)

        assertEquals(0, result.from)
        assertEquals(0, result.to)
        assertTrue(result.returned.isEmpty())
        assertEquals(0, result.totalSegments)
        assertEquals(false, result.truncate)
        assertEquals(null, result.nextOffset)
    }

    @Test
    fun rejectsMissingFile() {
        val error = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath("/definitely/missing/module.wasm"))
        }

        assertEquals(HexanaAnalysisErrorCode.FILE_NOT_FOUND, error.code)
        assertEquals("File not found", error.message)
    }

    @Test
    fun rejectsUnsupportedUriInputKind() {
        val uriError = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.Uri("file:///tmp/module.wasm"))
        }

        assertEquals(HexanaAnalysisErrorCode.UNSUPPORTED_INPUT_KIND, uriError.code)
    }

    @Test
    fun rejectsInvalidInlineBytes() {
        val invalidBase64 = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.InlineBytes("%%%"))
        }
        val blankInlineBytes = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.InlineBytes("   "))
        }
        val malformedBase64 = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.InlineBytes("A"))
        }
        val oversizedInlineBytes = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(maxInlineInputBytes = 3)
                .summarizeModule(WasmInput.InlineBytes(Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, 3))))
        }
        val oversizedRawBytes = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(maxInlineInputBytes = 3)
                .summarizeModule(WasmInput.RawBytes(byteArrayOf(0, 1, 2, 3)))
        }
        val nonWasmInlineBytes = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(
                WasmInput.InlineBytes(Base64.getEncoder().encodeToString("not wasm".encodeToByteArray()))
            )
        }

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, invalidBase64.code)
        assertEquals("Inline bytes must be valid base64", invalidBase64.message)
        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, blankInlineBytes.code)
        assertEquals("Inline bytes must not be blank", blankInlineBytes.message)
        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, malformedBase64.code)
        assertEquals("Inline bytes must be valid base64", malformedBase64.message)
        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, oversizedInlineBytes.code)
        assertEquals(
            "Inline bytes exceed maximum size of 3 bytes. Send a smaller inline payload or increase the configured MCP inline-size limit.",
            oversizedInlineBytes.message
        )
        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, oversizedRawBytes.code)
        assertEquals(
            "Inline bytes exceed maximum size of 3 bytes. Send a smaller inline payload or increase the configured MCP inline-size limit.",
            oversizedRawBytes.message
        )
        assertEquals(HexanaAnalysisErrorCode.WASM_PARSE_FAILED, nonWasmInlineBytes.code)
    }

    @Test
    fun acceptsInlineBytesAtExactConfiguredLimitWhenBase64IsPadded() {
        val service = DefaultHexanaAnalysisService(maxInlineInputBytes = 8)
        val minimalWasm = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

        val result = service.summarizeModule(
            WasmInput.InlineBytes(Base64.getEncoder().encodeToString(minimalWasm))
        )

        assertEquals(8, result.moduleSize)
    }

    @Test
    fun rejectsOversizedFileInputBeforeReadingBytes() {
        val oversizedFile = temporaryFolder.newFile("oversized.wasm").toPath()
        Files.write(oversizedFile, byteArrayOf(0, 1, 2, 3))

        val error = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(maxFileInputBytes = 3)
                .summarizeModule(WasmInput.FilePath(oversizedFile.toString()))
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertEquals(
            "File exceeds maximum size of 3 bytes. Use a smaller file or increase the configured MCP file-size limit.",
            error.message
        )
    }

    @Test
    fun rejectsTextFileThatExceedsLimitAfterRead() {
        val binding = temporaryFolder.newFile("growing-binding.json").toPath()
        Files.writeString(binding, "abc")
        val minimalWasm = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val service = DefaultHexanaAnalysisService(
            limits = HexanaResourceLimits(maxTextFileBytes = 3),
            fileSizeReader = { 3L },
            fileBytesReader = { "abcd".encodeToByteArray() },
        )

        val error = assertFailsWith<HexanaAnalysisException> {
            service.planAbiBindingRegeneration(
                input = WasmInput.RawBytes(minimalWasm),
                bindingInput = TextInput.FilePath(binding.toString()),
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertEquals(
            "Text file exceeds maximum size of 3 bytes. Use a smaller text file or increase the configured MCP text-file limit.",
            error.message
        )
    }

    @Test
    fun rejectsMalformedUtf8TextFileAsReadFailure() {
        val binding = temporaryFolder.newFile("malformed-binding.json").toPath()
        Files.write(binding, byteArrayOf(0x7b, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x7d))
        val minimalWasm = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

        val error = assertFailsWith<HexanaAnalysisException> {
            service.planAbiBindingRegeneration(
                input = WasmInput.RawBytes(minimalWasm),
                bindingInput = TextInput.FilePath(binding.toString()),
            )
        }

        assertEquals(HexanaAnalysisErrorCode.INTERNAL_ERROR, error.code)
        assertEquals("Failed to read text file", error.message)
    }

    @Test
    fun rejectsNonWasmAndMalformedFiles() {
        val textFile = temporaryFolder.newFile("not_wasm.txt").toPath()
        Files.writeString(textFile, "not wasm")

        val malformedFile = temporaryFolder.newFile("malformed.wasm").toPath()
        Files.writeString(malformedFile, "not actually wasm")

        val nonWasmError = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath(textFile.toString()))
        }
        val malformedError = assertFailsWith<HexanaAnalysisException> {
            service.summarizeModule(WasmInput.FilePath(malformedFile.toString()))
        }

        assertEquals(HexanaAnalysisErrorCode.NOT_WASM, nonWasmError.code)
        assertEquals(HexanaAnalysisErrorCode.WASM_PARSE_FAILED, malformedError.code)
    }

    @Test
    fun queryArtifactsClampsMaxBytesToConfiguredOutputLimit() {
        val wasm = createWasmFile(
            "configured_output_limit.wasm",
            """
            (module
              (func (export "main")))
            """.trimIndent()
        )

        val result = DefaultHexanaAnalysisService(
            limits = HexanaResourceLimits(maxOutputBytes = 1_500)
        ).queryArtifacts(
            input = WasmInput.FilePath(wasm.toString()),
            baselineInput = null,
            bindingInput = null,
            operation = "find",
            entityTypes = listOf("export"),
            query = listOf("main"),
            startName = null,
            startFuncidx = null,
            relations = emptyList(),
            limit = 10,
            detail = "summary",
            maxBytes = 60_000
        )

        assertTrue(result.warnings.any { it == "`maxBytes` was clamped to 1500." })
    }

    @Test
    fun rejectsLimitAboveConfiguredCandidateCount() {
        val wasm = createWasmFile(
            "candidate_limit.wasm",
            """
            (module
              (func (export "main")))
            """.trimIndent()
        )

        val error = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(
                limits = HexanaResourceLimits(maxCandidateCount = 1)
            ).listFunctions(WasmInput.FilePath(wasm.toString()), offset = 0, limit = 2)
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertEquals(
            "Limit 2 exceeds configured maximum candidate count of 1. Reduce the request scope or increase the configured MCP resource limit.",
            error.message
        )
    }

    @Test
    fun rejectsQueryLimitsAboveConfiguredCandidateCount() {
        val wasm = createWasmFile(
            "query_candidate_limit.wasm",
            """
            (module
              (func (export "main")))
            """.trimIndent()
        )
        val service = DefaultHexanaAnalysisService(
            limits = HexanaResourceLimits(maxCandidateCount = 1)
        )

        val binaryError = assertFailsWith<HexanaAnalysisException> {
            service.queryBinary(
                input = BinaryInput.FilePath(wasm.toString()),
                operation = "find",
                entityTypes = listOf("export"),
                query = listOf("main"),
                offset = 0,
                limit = 2,
                detail = "summary",
                maxBytes = 24_000,
            )
        }
        val artifactError = assertFailsWith<HexanaAnalysisException> {
            service.queryArtifacts(
                input = WasmInput.FilePath(wasm.toString()),
                baselineInput = null,
                bindingInput = null,
                operation = "find",
                entityTypes = listOf("export"),
                query = listOf("main"),
                startName = null,
                startFuncidx = null,
                relations = emptyList(),
                limit = 2,
                detail = "summary",
                maxBytes = 24_000,
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, binaryError.code)
        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, artifactError.code)
    }

    @Test
    fun rejectsOpenEndedDataSegmentRangeAboveConfiguredCandidateCount() {
        val wasm = createWasmFile(
            "open_ended_data_range.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 0) "first")
              (data (i32.const 16) "second"))
            """.trimIndent()
        )

        val error = assertFailsWith<HexanaAnalysisException> {
            DefaultHexanaAnalysisService(
                limits = HexanaResourceLimits(maxCandidateCount = 1)
            ).listDataSegments(
                input = WasmInput.FilePath(wasm.toString()),
                segmentStartInclusive = 0,
                segmentEndExclusive = Int.MAX_VALUE,
                includeExports = false,
                includeGlobals = true,
                minByteLength = null
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertEquals(
            "Range requests 2 entries, exceeding configured maximum candidate count of 1. Reduce the request scope or increase the configured MCP resource limit.",
            error.message
        )
    }

    @Test
    fun rejectsInvalidFunctionPagination() {
        val wasm = createWasmFile(
            "range.wasm",
            """
            (module
              (func (export "main")))
            """.trimIndent()
        )

        val negativeOffset = assertFailsWith<HexanaAnalysisException> {
            service.listFunctions(WasmInput.FilePath(wasm.toString()), offset = -1, limit = 1)
        }
        val nonPositiveLimit = assertFailsWith<HexanaAnalysisException> {
            service.listFunctions(WasmInput.FilePath(wasm.toString()), offset = 0, limit = 0)
        }

        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, negativeOffset.code)
        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, nonPositiveLimit.code)
    }

    @Test
    fun rejectsInvalidExportedFunctionRange() {
        val wasm = createWasmFile(
            "export_range.wasm",
            """
            (module
              (func (export "main")))
            """.trimIndent()
        )

        val negativeFrom = assertFailsWith<HexanaAnalysisException> {
            service.listExportedFunctions(WasmInput.FilePath(wasm.toString()), entriesRange = -1..0)
        }
        val invertedRange = assertFailsWith<HexanaAnalysisException> {
            service.listExportedFunctions(WasmInput.FilePath(wasm.toString()), entriesRange = 1..0)
        }

        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, negativeFrom.code)
        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, invertedRange.code)
    }

    @Test
    fun rejectsInvalidIndicesAndSegmentParameters() {
        val wasm = createWasmFile(
            "invalid_inputs.wasm",
            """
            (module
              (memory 1)
              (func (export "main")))
            """.trimIndent()
        )

        val negativeIndex = assertFailsWith<HexanaAnalysisException> {
            service.getTypesForIndices(WasmInput.FilePath(wasm.toString()), indices = listOf(-1))
        }
        val invertedSegmentRange = assertFailsWith<HexanaAnalysisException> {
            service.listDataSegments(
                input = WasmInput.FilePath(wasm.toString()),
                segmentStartInclusive = 5,
                segmentEndExclusive = 1,
                includeExports = false,
                includeGlobals = true,
                minByteLength = null
            )
        }
        val negativeMinByteLength = assertFailsWith<HexanaAnalysisException> {
            service.listDataSegments(
                input = WasmInput.FilePath(wasm.toString()),
                segmentStartInclusive = 0,
                segmentEndExclusive = 1,
                includeExports = false,
                includeGlobals = true,
                minByteLength = -1
            )
        }

        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, negativeIndex.code)
        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, invertedSegmentRange.code)
        assertEquals(HexanaAnalysisErrorCode.OUT_OF_RANGE, negativeMinByteLength.code)
    }

    private fun createWasmFile(name: String, wat: String) = temporaryFile(name).also {
        Files.write(it, wat2wasm(wat))
    }

    private fun createBindingFile(name: String, text: String) = temporaryFile(name).also {
        Files.writeString(it, text)
    }

    private fun createTextFile(name: String, text: String) = temporaryFile(name).also {
        Files.writeString(it, text)
    }

    private fun temporaryFile(name: String) = temporaryFolder.root.toPath().resolve(name).also {
        Files.createDirectories(it.parent)
    }

    private fun globalStatePolicyWat(
        includeClockOffset: Boolean,
        includeRequestLimit: Boolean,
        includeFeatureFlag: Boolean
    ): String {
        val imports = buildList {
            add("""(import "env" "readonly_epoch" (global i64))""")
            add("""(import "env" "readonly_region" (global i32))""")
            if (includeClockOffset) add("""(import "env" "clock_offset" (global (mut i64)))""")
        }
        val globals = buildList {
            add("""(global (export "abi_version") i32 (i32.const 7))""")
            for (index in 0..29) {
                add("""(global (export "stable_limit_$index") i32 (i32.const ${100 + index}))""")
            }
            if (includeRequestLimit) add("""(global (export "request_limit") i32 (i32.const 2048))""")
            for (index in 30..44) {
                add("""(global (export "stable_limit_$index") i32 (i32.const ${100 + index}))""")
            }
            if (includeFeatureFlag) add("""(global (export "feature_flag") i32 (i32.const 1))""")
            for (index in 45..70) {
                add("""(global (export "stable_limit_$index") i32 (i32.const ${100 + index}))""")
            }
        }
        return (imports + globals).joinToString(prefix = "(module\n  ", separator = "\n  ", postfix = "\n)")
    }

    private fun org.jetbrains.hexana.mcp.model.artifacts.ArtifactRecordResult.attributeValue(name: String): String? =
        attributes.singleOrNull { it.name == name }?.value

    private fun createBinaryFile(name: String, bytes: ByteArray) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, bytes)
    }

    private fun targetFeaturesSection(vararg features: String): ByteArray {
        val entries = features.map { feature -> byteArrayOf('+'.code.toByte()) + encodeName(feature) }
        return wasmSection(0, encodeName("target_features") + encodeVector(entries))
    }

    private fun wasmModule(vararg sections: ByteArray): ByteArray {
        return byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00) + sections.fold(byteArrayOf()) { acc, section ->
            acc + section
        }
    }

    private fun wasmSection(id: Int, payload: ByteArray): ByteArray {
        return byteArrayOf(id.toByte()) + encodeU32(payload.size) + payload
    }

    private fun encodeName(name: String): ByteArray {
        val bytes = name.encodeToByteArray()
        return encodeU32(bytes.size) + bytes
    }

    private fun encodeVector(items: List<ByteArray>): ByteArray {
        return encodeU32(items.size) + items.fold(byteArrayOf()) { acc, item -> acc + item }
    }

    private fun encodeU32(value: Int): ByteArray {
        require(value >= 0)
        val result = mutableListOf<Byte>()
        var remaining = value
        do {
            var byte = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) {
                byte = byte or 0x80
            }
            result += byte.toByte()
        } while (remaining != 0)
        return result.toByteArray()
    }
}
