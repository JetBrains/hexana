package org.jetbrains.hexana.mcp

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
        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, oversizedInlineBytes.code)
        assertEquals("Inline bytes exceed maximum size of 3 bytes", oversizedInlineBytes.message)
        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, oversizedRawBytes.code)
        assertEquals("Inline bytes exceed maximum size of 3 bytes", oversizedRawBytes.message)
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

        assertEquals(HexanaAnalysisErrorCode.INVALID_INPUT, error.code)
        assertEquals("File exceeds maximum size of 3 bytes", error.message)
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

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
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
