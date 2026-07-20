package org.jetbrains.hexana.mcp

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
    fun queryArtifactsRoutesOverviewAndSurfaceOperations() {
        val wasm = createWasmFile(
            "surfaces.wasm",
            """
            (module
              (import "host" "log_event" (func ${'$'}log_event (param i32)))
              (import "host" "read_config" (func ${'$'}read_config (param i64) (result i32)))
              (func (export "process_order") (param i32 i64) (result i32)
                i32.const 1)
              (memory (export "runtime_memory") 2 8)
              (global (export "abi_version") i32 (i32.const 7))
              (table (export "handler_table") 1 funcref))
            """.trimIndent()
        )

        val overview = queryArtifacts(wasm, operation = "overview")
        assertEquals("overview", overview.operation)
        assertEquals(1, overview.modules.single().functions)
        assertEquals(4, overview.modules.single().exports)
        assertTrue(overview.records.isEmpty())

        val publicSurface = queryArtifacts(wasm, operation = "public_surface")
        assertEquals("1", publicSurface.record("public-surface:summary").attribute("functionExports"))
        assertEquals("1", publicSurface.record("public-surface:summary").attribute("memoryExports"))
        assertEquals("i32.const 7", publicSurface.record("export:global:abi_version").attribute("initExpr"))

        val abiSurface = queryArtifacts(wasm, operation = "abi_surface")
        assertEquals("2", abiSurface.record("abi-surface:summary").attribute("functionImports"))
        assertEquals("[i64]", abiSurface.record("abi-surface:import:host.read_config").attribute("params"))
        assertEquals("[i32,i64]", abiSurface.record("abi-surface:export:process_order").attribute("params"))

        val importSurface = queryArtifacts(wasm, operation = "import_surface")
        assertEquals("2", importSurface.record("import-surface:summary").attribute("functionImports"))
        assertEquals("host.log_event,host.read_config", importSurface.record("import-surface:summary").attribute("imports"))
        assertEquals("[i32]", importSurface.record("import-surface:import:host.log_event").attribute("params"))
    }

    @Test
    fun queryArtifactsRoutesApiAndTriageDiffsIncludingElements() {
        val apiBaseline = createWasmFile(
            "api-baseline.wasm",
            """
            (module
              (func (export "changed") (param i32) (result i32) local.get 0)
              (func (export "removed") (result i64) i64.const 7))
            """.trimIndent()
        )
        val apiCurrent = createWasmFile(
            "api-current.wasm",
            """
            (module
              (import "host" "bind_external" (func ${'$'}bind_external (param externref)))
              (func (export "added") (result i32) i32.const 9)
              (func (export "changed") (param i64) (result i64) local.get 0))
            """.trimIndent()
        )

        val apiDiff = queryArtifacts(apiCurrent, apiBaseline, operation = "api_diff", detail = "evidence")
        assertEquals("api_diff", apiDiff.operation)
        assertEquals("changed", apiDiff.record("export:function:changed").status)
        assertEquals("[i32]", apiDiff.record("export:function:changed").attribute("previousParams"))
        assertEquals("[i64]", apiDiff.record("export:function:changed").attribute("currentParams"))
        assertEquals("reference_type_import", apiDiff.record("abi-reference-import:host.bind_external").kind)

        val triage = queryArtifacts(apiCurrent, apiBaseline, operation = "triage", entityTypes = listOf("export"))
        assertEquals("triage", triage.operation)
        assertTrue(triage.record("export:function:changed").summary.contains("signature from (i32) -> (i32) to (i64) -> (i64)"))

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
        assertEquals("[0,1,2,3]", elementTriage.record("element:0").attribute("previousElements"))
        assertEquals("[0,1,3,2]", elementTriage.record("element:0").attribute("currentElements"))

        val summaryBaseline = createWasmFile("body-baseline.wasm", "(module (func (export \"main\") (result i32) i32.const 1))")
        val summaryCurrent = createWasmFile(
            "body-current.wasm",
            "(module (func (export \"main\") (result i32) i32.const 1 i32.const 2 i32.add))"
        )
        val summaryDiff = queryArtifacts(summaryCurrent, summaryBaseline, operation = "diff", entityTypes = listOf("function"))
        assertTrue(summaryDiff.records.isEmpty())
    }

    @Test
    fun queryArtifactsRoutesContractAndOperationalAudits() {
        val baseline = createWasmFile(
            "operational-baseline.wasm",
            """
            (module
              (import "env" "checkout_host_memory" (memory 16 32))
              (import "env" "log_i32" (func ${'$'}log (param i32)))
              (table 4 funcref) (func (export "warmup")) (func (export "checkout_review"))
              (func (export "admin_reconcile")) (func (export "preview_sync_session") (param i32)
                local.get 0 call ${'$'}log)
              (elem (i32.const 0) func 0 1 2 3))
            """.trimIndent()
        )
        val current = createWasmFile(
            "operational-current.wasm",
            """
            (module
              (import "env" "checkout_host_memory" (memory 24 48))
              (import "env" "begin_background_sync" (func ${'$'}begin (param i32)))
              (import "env" "log_i32" (func ${'$'}log (param i32)))
              (memory 1)
              (table 4 funcref) (func (export "warmup")) (func (export "checkout_review"))
              (func (export "admin_reconcile"))
              (func (export "preview_sync_session") (param i32) local.get 0 call ${'$'}begin)
              (func (export "write_u8") (param i32 i32) local.get 0 local.get 1 i32.store8)
              (func (export "hydrate_checkout") (local i32 i32 i32 i32 i64 i64))
              (global (export "request_limit") (mut i32) (i32.const 2048))
              (data (i32.const 2048) "https://api.shadow.internal/v2/keys")
              (data "FLAG_ENABLE_UNRELEASED_BILLING")
              (elem (i32.const 0) func 0 1 3 2))
            """.trimIndent()
        )

        val memory = queryArtifacts(current, baseline, operation = "memory_contract")
        assertEquals("24", memory.record("memory:0").attribute("currentMin"))
        assertEquals("48", memory.record("memory:0").attribute("currentMax"))

        val dispatch = queryArtifacts(current, baseline, operation = "dispatch_table_diff")
        assertEquals("changed", dispatch.record("dispatch-slot:0:2").status)
        assertEquals("2", dispatch.record("dispatch-slot:0:2").attribute("previousFuncidx"))

        val hostCalls = queryArtifacts(current, baseline, operation = "host_call_diff", startName = "preview_sync_session")
        assertEquals("added", hostCalls.record("host-call:preview_sync_session:env.begin_background_sync").status)
        assertEquals("env.begin_background_sync", hostCalls.record("host-call:preview_sync_session:env.begin_background_sync").attribute("hostImport"))

        val effects = queryArtifacts(current, operation = "entrypoint_effects")
        assertEquals("1", effects.record("entrypoint-effects:export:write_u8").attribute("directMemoryWrites"))
        assertEquals("env.begin_background_sync", effects.record("entrypoint-effects:export:preview_sync_session").attribute("calledImports"))

        val payloadAudit = queryArtifacts(
            current,
            operation = "data_payload_audit",
            query = listOf("https://api.shadow.internal/v2/keys", "FLAG_ENABLE_UNRELEASED_BILLING"),
            detail = "evidence"
        )
        assertEquals("0,1", payloadAudit.record("data-payload-audit:summary").attribute("matchedSegments"))
        assertEquals("true", payloadAudit.record("data-segment:1").attribute("rawBytesConfirmed"))

        val budget = queryArtifacts(current, operation = "local_budget", query = listOf("hydrate_checkout"))
        assertEquals("6", budget.record("local-budget:export:hydrate_checkout").attribute("localCount"))
        assertEquals("i32=4,i64=2", budget.record("local-budget:export:hydrate_checkout").attribute("localsByType"))

        val globals = queryArtifacts(current, baseline, operation = "global_state_policy")
        assertEquals("true", globals.record("global-policy:new-export:request_limit").attribute("mutable"))
    }

    private fun queryArtifacts(
        input: java.nio.file.Path,
        baseline: java.nio.file.Path? = null,
        operation: String,
        entityTypes: List<String> = emptyList(),
        query: List<String> = emptyList(),
        startName: String? = null,
        detail: String = "summary"
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
        limit = 25,
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

    private fun List<org.jetbrains.hexana.mcp.model.types.ValueTypeCountResult>.valueTypeCountFor(type: String): Int =
        single { it.type == type }.count

    private fun List<org.jetbrains.hexana.mcp.model.types.ReferenceValueTypeCountResult>.referenceTypeCountFor(type: String): Int =
        single { it.type == type }.count

    private fun org.jetbrains.hexana.mcp.model.artifacts.QueryArtifactsResult.record(id: String): ArtifactRecordResult =
        records.single { it.id == id }
}
