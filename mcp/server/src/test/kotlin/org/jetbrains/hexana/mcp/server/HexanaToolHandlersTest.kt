package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.*
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.data.ListDataSectionsResult
import org.jetbrains.hexana.mcp.model.data.ListDataSegmentsResult
import org.jetbrains.hexana.mcp.model.elements.ListElementSegmentsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportedFunctionsResult
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.mcp.model.exports.SearchExportsResult
import org.jetbrains.hexana.mcp.model.features.ListEnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.functions.DisassembleFunctionResult
import org.jetbrains.hexana.mcp.model.functions.GetFunctionCallGraphResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetInstructionsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.GetLocalsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.ListCodeSectionOffsetsResult
import org.jetbrains.hexana.mcp.model.functions.ResolveFunctionSignatureResult
import org.jetbrains.hexana.mcp.model.functions.SearchFunctionsByNameResult
import org.jetbrains.hexana.mcp.model.globals.ListGlobalsResult
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.mcp.model.memory.ListMemoryResult
import org.jetbrains.hexana.mcp.model.sections.ListCustomSectionsResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.model.types.ListReferenceTypesResult
import org.jetbrains.hexana.mcp.model.types.ListValueTypesResult
import org.jetbrains.hexana.mcp.model.types.TypesResult
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.Logger
import java.nio.file.Files
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaToolHandlersTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val handlers = HexanaToolHandlers()

    @Test
    fun queryArtifactsFindsBoundedExportRecords() {
        val wasm = createWasmFile(
            "query-artifacts-find.wasm",
            """
            (module
              (func (export "main") (result i32)
                i32.const 42))
            """.trimIndent()
        )

        val result = handlers.queryArtifacts(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("operation", JsonPrimitive("find"))
                put("entityTypes", buildJsonArray {
                    add(JsonPrimitive("export"))
                })
                put("query", buildJsonArray {
                    add(JsonPrimitive("main"))
                })
                put("limit", JsonPrimitive(5))
            }
        )

        assertTrue(result.isError != true)
        val structured = result.structuredContent ?: error("Expected structured content")
        assertEquals("find", structured["operation"]!!.jsonPrimitive.content)
        val record = structured["records"]!!.jsonArray.single().jsonObject
        assertEquals("export", record["entityType"]!!.jsonPrimitive.content)
        assertEquals("main", record["name"]!!.jsonPrimitive.content)
        assertEquals("function", record["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun queryArtifactsDiffsCurrentAndBaselineExports() {
        val baseline = createWasmFile(
            "query-artifacts-baseline.wasm",
            """
            (module
              (func (export "old") (result i32)
                i32.const 1))
            """.trimIndent()
        )
        val current = createWasmFile(
            "query-artifacts-current.wasm",
            """
            (module
              (func (export "new") (result i32)
                i32.const 2))
            """.trimIndent()
        )

        val result = handlers.queryArtifacts(
            buildJsonObject {
                put("path", JsonPrimitive(current.toString()))
                put("baselinePath", JsonPrimitive(baseline.toString()))
                put("operation", JsonPrimitive("diff"))
                put("entityTypes", buildJsonArray {
                    add(JsonPrimitive("export"))
                })
                put("limit", JsonPrimitive(10))
            }
        )

        assertTrue(result.isError != true)
        val records = result.structuredContent!!["records"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("added", "removed"), records.map { it["status"]!!.jsonPrimitive.content }.sorted())
        assertEquals(setOf("new", "old"), records.map { it["name"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun queryArtifactsHostAdapterRepairUsesConfigPathAndOmitsStaleFields() {
        val wasm = createWasmFile(
            "query-artifacts-host-adapter-repair.wasm",
            """
            (module
              (import "host" "log_event" (func ${'$'}log_event (param i32)))
              (import "host" "read_config" (func ${'$'}read_config (param i32) (result i64)))
              (func (export "process_order") (param i32 i64) (result i32)
                i32.const 1))
            """.trimIndent()
        )
        val config = temporaryFolder.newFile("adapter_config.json").toPath().also {
            Files.writeString(
                it,
                """
                {
                  "module": "dist/plugin/order_adapter.wasm",
                  "profile": "orders-v2-prod",
                  "imports": {
                    "host.log_event": {"params": ["i64"], "results": []},
                    "host.read_config": {"params": [], "results": ["i32"]}
                  },
                  "entrypoint": {"export": "run_order", "params": ["i32"], "results": ["i32"]},
                  "rollback_entrypoint": {"export": "rollback_order", "params": ["i64"], "results": ["i32"]}
                }
                """.trimIndent()
            )
        }

        val result = handlers.queryArtifacts(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("configPath", JsonPrimitive(config.toString()))
                put("operation", JsonPrimitive("host_adapter_repair"))
                put("limit", JsonPrimitive(1))
            }
        )

        assertTrue(result.isError != true)
        val summary = result.structuredContent!!["records"]!!.jsonArray.single().jsonObject
        val attributes = summary["attributes"]!!.jsonArray
            .map { it.jsonObject }
            .associate { it["name"]!!.jsonPrimitive.content to it["value"]!!.jsonPrimitive.content }
        assertEquals("profile,rollback_entrypoint", attributes["ignoredConfigFields"])
        val repairConfig = Json.parseToJsonElement(attributes["repairConfigJson"]!!).jsonObject
        assertFalse("profile" in repairConfig)
        assertFalse("rollback_entrypoint" in repairConfig)
        assertEquals("process_order", repairConfig["entrypoint"]!!.jsonObject["export"]!!.jsonPrimitive.content)
        val mismatches = Json.parseToJsonElement(attributes["configMismatchesJson"]!!).jsonArray
        assertTrue(mismatches.any { it.jsonObject["field"]!!.jsonPrimitive.content == "entrypoint.export" })
        assertTrue(mismatches.any { it.jsonObject["field"]!!.jsonPrimitive.content == "imports.host.read_config.results" })
    }

    @Test
    fun summarizeModuleReturnsStructuredSuccessPayload() {
        val wasm = createWasmFile(
            "summary.wasm",
            """
            (module
              (memory 1)
              (func (export "main")))
            """.trimIndent()
        )

        val result = handlers.summarizeModule(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
            }
        )

        assertTrue(result.isError != true)
        val textPayload = result.content.single() as TextContent
        val structured = result.structuredContent ?: error("Expected structured content")
        assertEquals(structured, Json.parseToJsonElement(textPayload.text).jsonObject)
        assertEquals(1, structured["functions"]!!.jsonPrimitive.int)
    }

    @Test
    fun summarizeModuleAcceptsInlineByteArray() {
        val wasm = createWasmFile(
            "summary-inline-array.wasm",
            """
            (module
              (memory 1)
              (func (export "main")))
            """.trimIndent()
        )

        val result = handlers.summarizeModule(
            buildJsonObject {
                put("bytes", buildJsonArray {
                    Files.readAllBytes(wasm).forEach { add(JsonPrimitive(it.toInt() and 0xFF)) }
                })
            }
        )

        assertTrue(result.isError != true)
        assertEquals(1, result.structuredContent!!["functions"]!!.jsonPrimitive.int)
    }

    @Test
    fun listImportsAcceptsInlineBase64Bytes() {
        val wasm = createWasmFile(
            "imports-inline-base64.wasm",
            """
            (module
              (import "env" "print" (func (param i32)))
              (func (export "main")
                i32.const 1
                call 0))
            """.trimIndent()
        )

        val result = handlers.listImports(
            buildJsonObject {
                put("bytesBase64", JsonPrimitive(Base64.getEncoder().encodeToString(Files.readAllBytes(wasm))))
            }
        )

        assertFalse(result.isError == true)
        assertEquals(
            "env",
            result.structuredContent!!["imports"]!!.jsonArray[0].jsonObject["module"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun resolveFunctionSignatureReturnsTypeIndexAndSignature() {
        val wasm = createWasmFile(
            "resolve_function_signature.wasm",
            """
            (module
              (type (func (param i32 f64) (result i32)))
              (import "env" "log" (func (param i64)))
              (func ${'$'}main (type 0) (param i32 f64) (result i32)
                local.get 0)
              (export "main" (func 1)))
            """.trimIndent()
        )

        val result = handlers.resolveFunctionSignature(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("funcidx", JsonPrimitive(1))
            }
        )

        assertFalse(result.isError == true)
        val structured = result.structuredContent ?: error("Expected structured content")
        assertEquals(1, structured["funcidx"]!!.jsonPrimitive.int)
        assertEquals(0, structured["typeidx"]!!.jsonPrimitive.int)
        assertEquals("i32", structured["params"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("f64", structured["params"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("i32", structured["results"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("export", structured["kind"]!!.jsonPrimitive.content)
        assertEquals("main", structured["exportNames"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun disassembleFunctionReturnsBodySnapshot() {
        val wasm = createWasmFile(
            "disassemble_function.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "log" (func (param i32)))
              (func ${'$'}main (type 0) (param i32) (result i32)
                (local i32)
                local.get 0
                local.set 1
                local.get 1
                call 0
                local.get 1)
              (export "main" (func 1)))
            """.trimIndent()
        )

        val result = handlers.disassembleFunction(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("funcidx", JsonPrimitive(1))
            }
        )

        assertFalse(result.isError == true)
        val structured = result.structuredContent ?: error("Expected structured content")
        assertEquals(1, structured["funcidx"]!!.jsonPrimitive.int)
        assertEquals(0, structured["typeidx"]!!.jsonPrimitive.int)
        assertEquals("i32", structured["signature"]!!.jsonObject["params"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("i32", structured["signature"]!!.jsonObject["results"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(false, structured["isImported"]!!.jsonPrimitive.boolean)
        assertEquals(true, structured["isExported"]!!.jsonPrimitive.boolean)
        assertEquals("main", structured["exportNames"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("i32", structured["locals"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("local.get", structured["instructions"]!!.jsonArray[0].jsonObject["op"]!!.jsonPrimitive.content)
        assertEquals("0", structured["instructions"]!!.jsonArray[0].jsonObject["args"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(structured["wat"]!!.jsonPrimitive.content.contains("(func (param i32) (result i32)"))
        assertTrue(structured["hex"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun getFunctionCallGraphReturnsResolvedCallSummary() {
        val wasm = createWasmFile(
            "get_function_call_graph.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (type (func (param i32)))
              (import "env" "log" (func (type 1)))
              (memory 1)
              (func ${'$'}helper (type 0) (param i32) (result i32)
                local.get 0)
              (func ${'$'}main (export "main") (type 0) (param i32) (result i32)
                (local i32 i32)
                local.get 0
                call 0
                i32.const 0
                i32.const 7
                i32.store
                local.get 0
                call 1))
            """.trimIndent()
        )

        val result = handlers.getFunctionCallGraph(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(2))
                })
            }
        )

        assertFalse(result.isError == true)
        val structured = result.structuredContent ?: error("Expected structured content")
        val function = structured["functions"]!!.jsonArray[0].jsonObject
        assertEquals(2, function["funcidx"]!!.jsonPrimitive.int)
        assertEquals("main", function["exportedAs"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(2, function["locals"]!!.jsonArray[0].jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(2, function["directCalls"]!!.jsonArray.size)
        val importedCall = function["directImportedCalls"]!!.jsonArray[0].jsonObject
        assertEquals(0, importedCall["targetFuncidx"]!!.jsonPrimitive.int)
        assertEquals("env", importedCall["target"]!!.jsonObject["import"]!!.jsonObject["module"]!!.jsonPrimitive.content)
        assertEquals("i32.store", function["directMemoryWrites"]!!.jsonArray[0].jsonObject["op"]!!.jsonPrimitive.content)
    }

    @Test
    fun listCodeSectionOffsetsReturnsDefinedFunctionBodyRanges() {
        val wasm = createWasmFile(
            "list_code_section_offsets.wasm",
            """
            (module
              (import "env" "log" (func))
              (func ${'$'}a)
              (func ${'$'}b))
            """.trimIndent()
        )

        val result = handlers.listCodeSectionOffsets(jsonPath(wasm.toString()))

        assertFalse(result.isError == true)
        val functions = result.structuredContent!!["functions"]!!.jsonArray
        assertEquals(2, functions.size)
        assertEquals(1, functions[0].jsonObject["funcidx"]!!.jsonPrimitive.int)
        assertTrue(functions[0].jsonObject["offset"]!!.jsonPrimitive.long > 0)
        assertTrue(functions[0].jsonObject["size"]!!.jsonPrimitive.long > 0)
        assertEquals(2, functions[1].jsonObject["funcidx"]!!.jsonPrimitive.int)
    }

    @Test
    fun listCustomSectionsReturnsOffsetsAndPreviews() {
        val wasm = temporaryFolder.newFile("custom_sections.wasm").toPath().also {
            Files.write(it, wasmWithCustomSections())
        }

        val result = handlers.listCustomSections(jsonPath(wasm.toString()))

        assertFalse(result.isError == true)
        val sections = result.structuredContent!!["customSections"]!!.jsonArray
        assertEquals(2, sections.size)
        val answer = sections[0].jsonObject
        assertEquals("answer", answer["name"]!!.jsonPrimitive.content)
        assertEquals(8, answer["offset"]!!.jsonPrimitive.long)
        assertTrue(answer["size"]!!.jsonPrimitive.long > 0)
        assertEquals("utf8", answer["encoding"]!!.jsonPrimitive.content)
        assertEquals("flag=42", answer["preview"]!!.jsonPrimitive.content)

        val debugInfo = sections[1].jsonObject
        assertEquals(".debug_info", debugInfo["name"]!!.jsonPrimitive.content)
        assertEquals("binary", debugInfo["encoding"]!!.jsonPrimitive.content)
        assertEquals("0x04 0x00 0x00 0x00 0x11 0xff", debugInfo["preview"]!!.jsonPrimitive.content)
    }

    @Test
    fun searchFunctionsByNameReturnsMatchingImportAndExportNames() {
        val wasm = createWasmFile(
            "search_functions_by_name.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "print" (func (param i32)))
              (func ${'$'}main (type 0) (param i32) (result i32)
                (local i32)
                local.get 0)
              (export "main" (func 1)))
            """.trimIndent()
        )

        val importResult = handlers.searchFunctionsByName(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("query", JsonPrimitive("print"))
                put("limit", JsonPrimitive(10))
            }
        )
        val exportResult = handlers.searchFunctionsByName(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("query", JsonPrimitive("MAIN"))
            }
        )

        assertFalse(importResult.isError == true)
        val importMatch = importResult.structuredContent!!["functions"]!!.jsonArray[0].jsonObject
        assertEquals(0, importMatch["funcidx"]!!.jsonPrimitive.int)
        assertEquals("print", importMatch["name"]!!.jsonPrimitive.content)
        assertEquals("import", importMatch["kind"]!!.jsonPrimitive.content)
        assertEquals("env", importMatch["module"]!!.jsonPrimitive.content)
        assertEquals("print", importMatch["importName"]!!.jsonPrimitive.content)

        assertFalse(exportResult.isError == true)
        val exportMatch = exportResult.structuredContent!!["functions"]!!.jsonArray[0].jsonObject
        assertEquals(1, exportMatch["funcidx"]!!.jsonPrimitive.int)
        assertEquals("main", exportMatch["name"]!!.jsonPrimitive.content)
        assertEquals("export", exportMatch["kind"]!!.jsonPrimitive.content)
        assertEquals("main", exportMatch["exportName"]!!.jsonPrimitive.content)
        assertEquals("i32", exportMatch["signature"]!!.jsonObject["params"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("i32", exportMatch["signature"]!!.jsonObject["results"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("i32", exportMatch["locals"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun nullableInputFieldsAcceptExplicitJsonNull() {
        val wasm = createWasmFile(
            "nullable-inputs.wasm",
            """
            (module
              (memory 1)
              (data (i32.const 0) "Hello"))
            """.trimIndent()
        )

        val inlineSummary = handlers.summarizeModule(
            buildJsonObject {
                put("path", JsonNull)
                put("bytes", buildJsonArray {
                    Files.readAllBytes(wasm).forEach { add(JsonPrimitive(it.toInt() and 0xFF)) }
                })
            }
        )
        val dataSegments = handlers.listDataSegments(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("bytesBase64", JsonNull)
                put("bytes", JsonNull)
                put("segmentRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonNull)
                })
                put("minByteLength", JsonNull)
            }
        )

        assertFalse(inlineSummary.isError == true)
        assertFalse(dataSegments.isError == true)
        assertEquals(1, dataSegments.structuredContent!!["segments"]!!.jsonArray.size)
    }

    @Test
    fun listFunctionsReturnsToolErrorForMissingFile() {
        val result = handlers.listFunctions(
            buildJsonObject {
                put("path", JsonPrimitive("/missing/module.wasm"))
                put("offset", JsonPrimitive(0))
                put("limit", JsonPrimitive(10))
            }
        )

        assertEquals(true, result.isError)
        val textPayload = result.content.single() as TextContent
        assertEquals("File not found", textPayload.text)
        assertEquals("FILE_NOT_FOUND", result.structuredContent?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun listImportsAndExportsReuseCurrentQueryShapes() {
        val wasm = createWasmFile(
            "imports_exports.wasm",
            """
            (module
              (import "env" "print" (func (param i32)))
              (func (export "main")
                i32.const 1
                call 0))
            """.trimIndent()
        )

        val imports = handlers.listImports(jsonPath(wasm.toString()))
        val exports = handlers.listExports(jsonPath(wasm.toString()))

        assertFalse(imports.isError == true)
        assertFalse(exports.isError == true)
        assertEquals(
            imports.structuredContent,
            Json.parseToJsonElement((imports.content.single() as TextContent).text).jsonObject
        )
        assertEquals(
            exports.structuredContent,
            Json.parseToJsonElement((exports.content.single() as TextContent).text).jsonObject
        )
        assertEquals(
            "env",
            imports.structuredContent!!["imports"]!!.jsonArray[0].jsonObject["module"]!!.jsonPrimitive.content
        )
        assertEquals(
            "main",
            exports.structuredContent!!["exports"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun searchExportsReturnsResolvedFunctionAndMemoryMetadata() {
        val wasm = createWasmFile(
            "search_exports.wasm",
            """
            (module
              (memory (export "memory") 1 2)
              (func (export "add") (param i32 i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val result = handlers.searchExports(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("query", buildJsonArray {
                    add(JsonPrimitive("add"))
                    add(JsonPrimitive("mem"))
                })
                put("limit", JsonPrimitive(10))
            }
        )

        assertFalse(result.isError == true)
        val results = result.structuredContent!!["results"]!!.jsonArray
        assertEquals(2, results.size)
        val add = results[0].jsonObject
        assertEquals("add", add["name"]!!.jsonPrimitive.content)
        assertEquals("func", add["kind"]!!.jsonPrimitive.content)
        assertEquals(0, add["funcidx"]!!.jsonPrimitive.int)
        assertEquals("i32", add["signature"]!!.jsonObject["params"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("i32", add["signature"]!!.jsonObject["results"]!!.jsonArray[0].jsonPrimitive.content)
        val memory = results[1].jsonObject
        assertEquals("memory", memory["name"]!!.jsonPrimitive.content)
        assertEquals("memory", memory["kind"]!!.jsonPrimitive.content)
        assertEquals(0, memory["memidx"]!!.jsonPrimitive.int)
        assertEquals(1, memory["limits"]!!.jsonObject["min"]!!.jsonPrimitive.long)
        assertEquals(2, memory["limits"]!!.jsonObject["max"]!!.jsonPrimitive.long)
    }

    @Test
    fun listExportedFunctionsReturnsStructuredPaginatedPayload() {
        val wasm = createWasmFile(
            "exported_functions.wasm",
            """
            (module
              (import "env" "log" (func (param i32)))
              (func ${'$'}main (param i32) (result i32)
                local.get 0)
              (export "main" (func 1))
              (export "log_alias" (func 0)))
            """.trimIndent()
        )

        val result = handlers.listExportedFunctions(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(1))
                    put("to", JsonPrimitive(1))
                })
            }
        )

        assertFalse(result.isError == true)
        val structured = result.structuredContent ?: error("Expected structured content")
        assertEquals(1, structured["from"]!!.jsonPrimitive.int)
        assertEquals(1, structured["to"]!!.jsonPrimitive.int)
        assertEquals(1, structured["returned"]!!.jsonArray.size)
        assertEquals(
            "log_alias",
            structured["returned"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
        )
        assertEquals(
            "env",
            structured["returned"]!!.jsonArray[0].jsonObject["entry"]!!.jsonObject["module"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun listTypesAndMemoryReturnStructuredPayloads() {
        val wasm = createWasmFile(
            "types_memory.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "shared" (memory 1))
              (memory 2 4))
            """.trimIndent()
        )

        val types = handlers.listTypes(jsonPath(wasm.toString()))
        val memory = handlers.listMemory(jsonPath(wasm.toString()))

        assertFalse(types.isError == true)
        assertFalse(memory.isError == true)
        assertEquals(
            "func",
            types.structuredContent!!["types"]!!.jsonArray[0].jsonObject["kind"]!!.jsonPrimitive.content
        )
        assertEquals(2, memory.structuredContent!!["memories"]!!.jsonArray.size)
        assertEquals(
            "import",
            memory.structuredContent!!["memories"]!!.jsonArray[0].jsonObject["definedIn"]!!.jsonPrimitive.content
        )
        assertEquals(
            "env",
            memory.structuredContent!!["memories"]!!.jsonArray[0].jsonObject["import"]!!.jsonObject["module"]!!.jsonPrimitive.content
        )
        assertEquals(
            "shared",
            memory.structuredContent!!["memories"]!!.jsonArray[0].jsonObject["import"]!!.jsonObject["name"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun listExportedFunctionsValidatesEntriesRangeInput() {
        val result = handlers.listExportedFunctions(
            buildJsonObject {
                put("path", JsonPrimitive("/tmp/module.wasm"))
            }
        )

        assertEquals(true, result.isError)
        assertEquals("INVALID_INPUT", result.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`entriesRange` is required", (result.content.single() as TextContent).text)
    }

    @Test
    fun listExportedFunctionsRejectsMalformedEntriesRangeInput() {
        val stringRange = handlers.listExportedFunctions(
            buildJsonObject {
                put("path", JsonPrimitive("/tmp/module.wasm"))
                put("entriesRange", JsonPrimitive("""{"from": 0, "10}"""))
            }
        )
        val nonIntegerBound = handlers.listExportedFunctions(
            buildJsonObject {
                put("path", JsonPrimitive("/tmp/module.wasm"))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive("0"))
                    put("to", JsonPrimitive(1))
                })
            }
        )
        val reversedRange = handlers.listExportedFunctions(
            buildJsonObject {
                put("path", JsonPrimitive("/tmp/module.wasm"))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(2))
                    put("to", JsonPrimitive(1))
                })
            }
        )

        assertEquals(true, stringRange.isError)
        assertEquals("`entriesRange` must be an object", (stringRange.content.single() as TextContent).text)
        assertEquals(true, nonIntegerBound.isError)
        assertEquals("`entriesRange.from` must be an integer", (nonIntegerBound.content.single() as TextContent).text)
        assertEquals(true, reversedRange.isError)
        assertEquals("`entriesRange.to` must be >= `entriesRange.from`", (reversedRange.content.single() as TextContent).text)
    }

    @Test
    fun inputValidationRejectsMissingOrConflictingWasmInputs() {
        val missingInput = handlers.listImports(buildJsonObject {})
        val conflictingInput = handlers.listImports(
            buildJsonObject {
                put("path", JsonPrimitive("/tmp/module.wasm"))
                put("bytesBase64", JsonPrimitive("AGFzbQ=="))
            }
        )

        assertEquals(true, missingInput.isError)
        assertEquals("INVALID_INPUT", missingInput.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals(
            "Exactly one of `path`, `bytesBase64`, or `bytes` is required",
            (missingInput.content.single() as TextContent).text
        )

        assertEquals(true, conflictingInput.isError)
        assertEquals("INVALID_INPUT", conflictingInput.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals(
            "Provide exactly one of `path`, `bytesBase64`, or `bytes`",
            (conflictingInput.content.single() as TextContent).text
        )
    }

    @Test
    fun inputValidationRejectsOutOfRangeByteValues() {
        val outOfRange = handlers.listImports(
            buildJsonObject {
                put("bytes", buildJsonArray {
                    add(JsonPrimitive(256))
                })
            }
        )
        val negative = handlers.listImports(
            buildJsonObject {
                put("bytes", buildJsonArray {
                    add(JsonPrimitive(-1))
                })
            }
        )
        val nonInteger = handlers.listImports(
            buildJsonObject {
                put("bytes", buildJsonArray {
                    add(JsonPrimitive("bad"))
                })
            }
        )
        val nullElement = handlers.listImports(
            buildJsonObject {
                put("bytes", buildJsonArray {
                    add(JsonNull)
                })
            }
        )
        val oversized = HexanaToolHandlers(maxInlineInputBytes = 2).listImports(
            buildJsonObject {
                put("bytes", buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                    add(JsonPrimitive(2))
                })
            }
        )

        assertEquals(true, outOfRange.isError)
        assertEquals("INVALID_INPUT", outOfRange.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`bytes[0]` must be between 0 and 255", (outOfRange.content.single() as TextContent).text)

        assertEquals(true, negative.isError)
        assertEquals("INVALID_INPUT", negative.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`bytes[0]` must be between 0 and 255", (negative.content.single() as TextContent).text)

        assertEquals(true, nonInteger.isError)
        assertEquals("INVALID_INPUT", nonInteger.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`bytes[0]` must be an integer", (nonInteger.content.single() as TextContent).text)

        assertEquals(true, nullElement.isError)
        assertEquals("INVALID_INPUT", nullElement.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`bytes[0]` must be an integer", (nullElement.content.single() as TextContent).text)

        assertEquals(true, oversized.isError)
        assertEquals("INVALID_INPUT", oversized.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`bytes` exceeds maximum size of 2 bytes", (oversized.content.single() as TextContent).text)
    }

    @Test
    fun indexAndSegmentToolsReturnStructuredPayloads() {
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

        val functions = handlers.functionsForIndices(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                })
            }
        )
        val globals = handlers.listGlobals(jsonPath(wasm.toString()))
        val selectedGlobals = handlers.getGlobalsForIndices(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val data = handlers.listData(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(0))
                })
            }
        )
        val dataSegments = handlers.listDataSegments(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("segmentRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(1))
                })
                put("includeExports", JsonPrimitive(false))
                put("includeGlobals", JsonPrimitive(true))
            }
        )
        val selectedMemory = handlers.getMemoryForIndices(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(0))
                })
            }
        )
        val selectedTypes = handlers.getTypesForIndices(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(0))
                })
            }
        )
        val valueTypes = handlers.listValueTypes(jsonPath(wasm.toString()))
        val referenceTypes = handlers.listReferenceTypes(jsonPath(wasm.toString()))
        val locals = handlers.getLocalsForFunctions(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val instructions = handlers.getInstructionsForFunctions(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("indices", buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val elements = handlers.listElementSegments(jsonPath(wasm.toString()))
        val features = handlers.listEnabledFeatures(jsonPath(wasm.toString()))

        assertFalse(functions.isError == true)
        assertEquals(2, functions.structuredContent!!["functions"]!!.jsonArray.size)
        assertEquals(
            "env",
            functions.structuredContent!!["functions"]!!.jsonArray[0].jsonObject["import"]!!.jsonObject["module"]!!.jsonPrimitive.content
        )

        assertFalse(globals.isError == true)
        assertEquals(2, globals.structuredContent!!["globals"]!!.jsonArray.size)
        assertEquals(
            "env",
            globals.structuredContent!!["globals"]!!.jsonArray[0].jsonObject["prefix"]!!.jsonPrimitive.content
        )

        assertFalse(selectedGlobals.isError == true)
        assertEquals(1, selectedGlobals.structuredContent!!["globals"]!!.jsonArray.size)
        assertEquals(
            1,
            selectedGlobals.structuredContent!!["globals"]!!.jsonArray[0].jsonObject["globalIdx"]!!.jsonPrimitive.int
        )

        assertFalse(data.isError == true)
        assertEquals(
            "SGVsbG8=",
            data.structuredContent!!["returned"]!!.jsonArray[0].jsonObject["data"]!!.jsonPrimitive.content
        )

        assertFalse(dataSegments.isError == true)
        assertEquals(
            "Hello",
            dataSegments.structuredContent!!["segments"]!!.jsonArray[0].jsonObject["utf8Preview"]!!.jsonPrimitive.content
        )
        assertEquals(1, dataSegments.structuredContent!!["summary"]!!.jsonObject["totalSegments"]!!.jsonPrimitive.int)

        assertFalse(selectedMemory.isError == true)
        assertEquals(1, selectedMemory.structuredContent!!["memories"]!!.jsonArray.size)
        assertEquals(
            0,
            selectedMemory.structuredContent!!["memories"]!!.jsonArray[0].jsonObject["memoryIdx"]!!.jsonPrimitive.int
        )

        assertFalse(selectedTypes.isError == true)
        assertEquals(
            "func",
            selectedTypes.structuredContent!!["types"]!!.jsonArray[0].jsonObject["kind"]!!.jsonPrimitive.content
        )

        assertFalse(valueTypes.isError == true)
        assertTrue(valueTypes.structuredContent!!["valueTypes"]!!.jsonObject["scalar"]!!.jsonArray.any {
            it.jsonPrimitive.content == "i32"
        })
        assertTrue(valueTypes.structuredContent!!["counts"]!!.jsonObject["valueTypes"]!!.jsonObject["reference"]!!.jsonArray.any {
            it.jsonObject["type"]!!.jsonPrimitive.content == "funcref"
        })

        assertFalse(referenceTypes.isError == true)
        assertEquals(
            "funcref",
            referenceTypes.structuredContent!!["referenceTypes"]!!.jsonObject["functionReferences"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content
        )

        assertFalse(locals.isError == true)
        assertEquals(
            "i32",
            locals.structuredContent!!["functions"]!!.jsonArray[0].jsonObject["locals"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content
        )

        assertFalse(instructions.isError == true)
        assertTrue(instructions.structuredContent!!["functions"]!!.jsonArray[0].jsonObject["instructions"]!!.jsonArray.any { it.jsonPrimitive.content == "call 0" })

        assertFalse(elements.isError == true)
        assertEquals(1, elements.structuredContent!!["segments"]!!.jsonArray.size)
        assertEquals(
            "1",
            elements.structuredContent!!["segments"]!!.jsonArray[0].jsonObject["elements"]!!.jsonArray[0].jsonPrimitive.content
        )

        assertFalse(features.isError == true)
        assertEquals(
            true,
            features.structuredContent!!["features"]!!.jsonObject["referenceTypes"]!!.jsonPrimitive.boolean
        )
    }

    @Test
    fun listRequiredProposalsReturnsCompactEvidence() {
        val wasm = createWasmFile(
            "required-proposals.wasm",
            """
            (module
              (type (func (param funcref) (result i32 i32)))
              (memory 1)
              (data ${'$'}d "abc")
              (func (export "features") (type 0) (param funcref) (result i32 i32)
                local.get 0
                ref.is_null
                i32.extend8_s
                i32.const 1)
              (func (export "bulk")
                i32.const 0
                i32.const 0
                i32.const 3
                memory.init ${'$'}d
                data.drop ${'$'}d))
            """.trimIndent()
        )

        val result = handlers.listRequiredProposals(jsonPath(wasm.toString()))

        assertFalse(result.isError == true)
        val structured = result.structuredContent ?: error("Expected structured content")
        val proposalNames = structured["proposals"]!!.jsonArray.map {
            it.jsonObject["proposal"]!!.jsonPrimitive.content
        }
        assertTrue("reference-types" in proposalNames)
        assertTrue("bulk-memory" in proposalNames)
        assertTrue("multi-value" in proposalNames)
        assertTrue("sign-extension" in proposalNames)
        assertEquals(proposalNames.size, structured["summary"]!!.jsonObject["requiredCount"]!!.jsonPrimitive.int)

        val bulkMemory = structured["proposals"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["proposal"]!!.jsonPrimitive.content == "bulk-memory" }
        assertEquals(true, bulkMemory["required"]!!.jsonPrimitive.boolean)
        assertEquals("bulkMemory", bulkMemory["evidence"]!!.jsonObject["featureFlag"]!!.jsonPrimitive.content)
        assertTrue(bulkMemory["evidence"]!!.jsonObject["instructions"]!!.jsonArray.any {
            it.jsonPrimitive.content == "memory.init"
        })
    }

    @Test
    fun diffRequiredProposalsReturnsCurrentAndBaselineDelta() {
        val baseline = createWasmFile(
            "required-proposals-baseline.wasm",
            """
            (module
              (type (func (result i32 i32)))
              (func (export "base") (type 0)
                i32.const 1
                i32.const 2))
            """.trimIndent()
        )
        val current = createWasmFile(
            "required-proposals-current.wasm",
            """
            (module
              (type (func (param funcref) (result i32 i32)))
              (memory 1)
              (data ${'$'}d "abc")
              (func (export "features") (type 0) (param funcref) (result i32 i32)
                local.get 0
                ref.is_null
                i32.extend8_s
                i32.const 1)
              (func (export "bulk")
                i32.const 0
                i32.const 0
                i32.const 3
                memory.init ${'$'}d))
            """.trimIndent()
        )

        val result = handlers.diffRequiredProposals(
            buildJsonObject {
                put("path", JsonPrimitive(current.toString()))
                put("baselinePath", JsonPrimitive(baseline.toString()))
            }
        )

        assertFalse(result.isError == true)
        val structured = result.structuredContent ?: error("Expected structured content")
        assertEquals(current.toString(), structured["path"]!!.jsonPrimitive.content)
        assertEquals(baseline.toString(), structured["baselinePath"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("multi-value"),
            structured["baselineRequiredProposals"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("bulk-memory", "reference-types", "sign-extension"),
            structured["newlyRequiredProposals"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertTrue(structured["newlyRequiredEvidence"]!!.jsonArray.any {
            it.jsonObject["proposal"]!!.jsonPrimitive.content == "bulk-memory"
        })
        assertEquals(3, structured["summary"]!!.jsonObject["newlyRequiredCount"]!!.jsonPrimitive.int)
    }

    @Test
    fun checkRuntimeCompatibilityReportsUnknownGraalWasmProposalSupport() {
        val wasm = createWasmFile(
            "runtime-compatibility.wasm",
            """
            (module
              (type (func (param funcref) (result i32 i32)))
              (memory 1)
              (data ${'$'}d "abc")
              (func (export "features") (type 0) (param funcref) (result i32 i32)
                local.get 0
                ref.is_null
                i32.extend8_s)
              (func (export "bulk")
                i32.const 0
                i32.const 0
                i32.const 3
                memory.init ${'$'}d))
            """.trimIndent()
        )

        val result = handlers.checkRuntimeCompatibility(
            buildJsonObject {
                put("path", JsonPrimitive(wasm.toString()))
                put("runtime", JsonPrimitive("graalwasm"))
                put("version", JsonPrimitive("test-profile"))
            }
        )

        assertFalse(result.isError == true)
        val structured = result.structuredContent ?: error("Expected structured content")
        assertEquals("graalwasm", structured["runtime"]!!.jsonPrimitive.content)
        assertEquals("test-profile", structured["version"]!!.jsonPrimitive.content)
        assertEquals(false, structured["compatible"]!!.jsonPrimitive.boolean)
        val required = structured["requiredProposals"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("reference-types" in required)
        assertTrue("bulk-memory" in required)
        assertTrue("sign-extension" in required)
        val unknown = structured["unknownProposals"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("reference-types" in unknown)
        assertTrue("bulk-memory" in unknown)
        assertTrue("sign-extension" in unknown)

        val bulkMemoryStatus = structured["proposalStatuses"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["proposal"]!!.jsonPrimitive.content == "bulk-memory" }
        assertEquals("unknown", bulkMemoryStatus["status"]!!.jsonPrimitive.content)
        assertTrue(bulkMemoryStatus["evidence"]!!.jsonObject["instructions"]!!.jsonArray.any {
            it.jsonPrimitive.content == "memory.init"
        })
    }

    @Test
    fun indexToolsValidateArrayInput() {
        val result = handlers.getTypesForIndices(
            buildJsonObject {
                put("path", JsonPrimitive("/tmp/module.wasm"))
                put("indices", JsonPrimitive("nope"))
            }
        )

        assertEquals(true, result.isError)
        assertEquals("INVALID_INPUT", result.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`indices` must be an array", (result.content.single() as TextContent).text)
    }

    @Test
    fun listDataSegmentsValidatesSegmentRangeInput() {
        val result = handlers.listDataSegments(
            buildJsonObject {
                put("path", JsonPrimitive("/tmp/module.wasm"))
                put("segmentRange", buildJsonObject {
                    put("from", JsonPrimitive("bad"))
                })
            }
        )

        assertEquals(true, result.isError)
        assertEquals("INVALID_INPUT", result.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("`from` must be an integer", (result.content.single() as TextContent).text)
    }

    @Test
    fun unexpectedExceptionReturnsClearInternalErrorMessage() {
        val logger = RecordingLogger()
        val handlers = HexanaToolHandlers(
            analysisService = object : HexanaAnalysisService {
                override fun summarizeModule(input: WasmInput): SummarizeModuleResult = error("boom")
                override fun listEnabledFeatures(input: WasmInput): ListEnabledFeaturesResult = error("boom")
                override fun listCustomSections(input: WasmInput): ListCustomSectionsResult = error("boom")
                override fun listImports(input: WasmInput): ListImportsResult = error("boom")
                override fun listExports(input: WasmInput): ListExportsResult = error("boom")
                override fun searchExports(input: WasmInput, query: List<String>, limit: Int): SearchExportsResult =
                    error("boom")

                override fun listFunctions(input: WasmInput, offset: Int, limit: Int): FunctionsResult = error("boom")
                override fun listCodeSectionOffsets(input: WasmInput): ListCodeSectionOffsetsResult = error("boom")
                override fun resolveFunctionSignature(
                    input: WasmInput,
                    funcidx: Int
                ): ResolveFunctionSignatureResult = error("boom")

                override fun disassembleFunction(input: WasmInput, funcidx: Int): DisassembleFunctionResult =
                    error("boom")

                override fun getFunctionCallGraph(input: WasmInput, indices: List<Int>): GetFunctionCallGraphResult =
                    error("boom")

                override fun searchFunctionsByName(
                    input: WasmInput,
                    query: String,
                    limit: Int
                ): SearchFunctionsByNameResult = error("boom")

                override fun listExportedFunctions(
                    input: WasmInput,
                    entriesRange: IntRange
                ): ListExportedFunctionsResult = error("boom")

                override fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult = error("boom")
                override fun listGlobals(input: WasmInput): ListGlobalsResult = error("boom")
                override fun listTypes(input: WasmInput): TypesResult = error("boom")
                override fun listValueTypes(input: WasmInput): ListValueTypesResult = error("boom")
                override fun listReferenceTypes(input: WasmInput): ListReferenceTypesResult = error("boom")
                override fun listMemory(input: WasmInput): ListMemoryResult = error("boom")
                override fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult =
                    error("boom")

                override fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult = error("boom")
                override fun listDataSegments(
                    input: WasmInput,
                    segmentStartInclusive: Int,
                    segmentEndExclusive: Int,
                    includeExports: Boolean,
                    includeGlobals: Boolean,
                    minByteLength: Int?
                ): ListDataSegmentsResult = error("boom")

                override fun getMemoryForIndices(input: WasmInput, indices: List<Int>): ListMemoryResult = error("boom")
                override fun getTypesForIndices(input: WasmInput, indices: List<Int>): TypesResult = error("boom")
                override fun getLocalsForFunctions(input: WasmInput, indices: List<Int>): GetLocalsForFunctionsResult =
                    error("boom")

                override fun getInstructionsForFunctions(
                    input: WasmInput,
                    indices: List<Int>
                ): GetInstructionsForFunctionsResult = error("boom")

                override fun listElementSegments(input: WasmInput): ListElementSegmentsResult = error("boom")
            },
            logger = logger
        )

        val result = handlers.listImports(jsonPath("/tmp/module.wasm"))

        assertEquals(true, result.isError)
        assertEquals("INTERNAL_ERROR", result.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals(
            "Internal error while executing `list_imports`. See server logs for details.",
            (result.content.single() as TextContent).text
        )
        assertEquals(1, logger.errorEvents.size)
        assertTrue(logger.errorEvents.single().message.contains("list_imports"))
        assertEquals("boom", logger.errorEvents.single().throwable?.message)
    }

    @Test
    fun internalAnalysisExceptionKeepsUserMessageAndLogsCause() {
        val logger = RecordingLogger()
        val handlers = HexanaToolHandlers(
            analysisService = object : HexanaAnalysisService {
                override fun summarizeModule(input: WasmInput): SummarizeModuleResult {
                    throw HexanaAnalysisException(
                        HexanaAnalysisErrorCode.INTERNAL_ERROR,
                        "Failed to read .wasm file",
                        IllegalStateException("disk unavailable")
                    )
                }

                override fun listEnabledFeatures(input: WasmInput): ListEnabledFeaturesResult = error("unused")
                override fun listCustomSections(input: WasmInput): ListCustomSectionsResult = error("unused")
                override fun listImports(input: WasmInput): ListImportsResult = error("unused")
                override fun listExports(input: WasmInput): ListExportsResult = error("unused")
                override fun searchExports(input: WasmInput, query: List<String>, limit: Int): SearchExportsResult =
                    error("unused")

                override fun listFunctions(input: WasmInput, offset: Int, limit: Int): FunctionsResult = error("unused")
                override fun listCodeSectionOffsets(input: WasmInput): ListCodeSectionOffsetsResult = error("unused")
                override fun resolveFunctionSignature(
                    input: WasmInput,
                    funcidx: Int
                ): ResolveFunctionSignatureResult = error("unused")

                override fun disassembleFunction(input: WasmInput, funcidx: Int): DisassembleFunctionResult =
                    error("unused")

                override fun getFunctionCallGraph(input: WasmInput, indices: List<Int>): GetFunctionCallGraphResult =
                    error("unused")

                override fun searchFunctionsByName(
                    input: WasmInput,
                    query: String,
                    limit: Int
                ): SearchFunctionsByNameResult = error("unused")

                override fun listExportedFunctions(
                    input: WasmInput,
                    entriesRange: IntRange
                ): ListExportedFunctionsResult = error("unused")

                override fun functionsForIndices(input: WasmInput, indices: List<Int>): FunctionsResult =
                    error("unused")

                override fun listGlobals(input: WasmInput): ListGlobalsResult = error("unused")
                override fun listTypes(input: WasmInput): TypesResult = error("unused")
                override fun listValueTypes(input: WasmInput): ListValueTypesResult = error("unused")
                override fun listReferenceTypes(input: WasmInput): ListReferenceTypesResult = error("unused")
                override fun listMemory(input: WasmInput): ListMemoryResult = error("unused")
                override fun getGlobalsForIndices(input: WasmInput, indices: List<Int>): ListGlobalsResult =
                    error("unused")

                override fun listData(input: WasmInput, entriesRange: IntRange): ListDataSectionsResult =
                    error("unused")

                override fun listDataSegments(
                    input: WasmInput,
                    segmentStartInclusive: Int,
                    segmentEndExclusive: Int,
                    includeExports: Boolean,
                    includeGlobals: Boolean,
                    minByteLength: Int?
                ): ListDataSegmentsResult = error("unused")

                override fun getMemoryForIndices(input: WasmInput, indices: List<Int>): ListMemoryResult =
                    error("unused")

                override fun getTypesForIndices(input: WasmInput, indices: List<Int>): TypesResult = error("unused")
                override fun getLocalsForFunctions(input: WasmInput, indices: List<Int>): GetLocalsForFunctionsResult =
                    error("unused")

                override fun getInstructionsForFunctions(
                    input: WasmInput,
                    indices: List<Int>
                ): GetInstructionsForFunctionsResult = error("unused")

                override fun listElementSegments(input: WasmInput): ListElementSegmentsResult = error("unused")
            },
            logger = logger
        )

        val result = handlers.summarizeModule(jsonPath("/tmp/module.wasm"))

        assertEquals(true, result.isError)
        assertEquals("INTERNAL_ERROR", result.structuredContent?.get("code")?.jsonPrimitive?.content)
        assertEquals("Failed to read .wasm file", (result.content.single() as TextContent).text)
        assertEquals(1, logger.errorEvents.size)
        assertEquals(
            "disk unavailable",
            logger.errorEvents.single().throwable?.cause?.message ?: logger.errorEvents.single().throwable?.message
        )
    }

    private fun jsonPath(path: String): JsonObject = buildJsonObject {
        put("path", JsonPrimitive(path))
    }

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
    }

    private fun wasmWithCustomSections(): ByteArray {
        return intArrayOf(
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x0e, 0x06,
            0x61, 0x6e, 0x73, 0x77, 0x65, 0x72,
            0x66, 0x6c, 0x61, 0x67, 0x3d, 0x34, 0x32,
            0x00, 0x12, 0x0b,
            0x2e, 0x64, 0x65, 0x62, 0x75, 0x67, 0x5f, 0x69, 0x6e, 0x66, 0x6f,
            0x04, 0x00, 0x00, 0x00, 0x11, 0xff,
        ).map { it.toByte() }.toByteArray()
    }

    private class RecordingLogger : Logger by LoggerStub() {
        val errorEvents = mutableListOf<ErrorEvent>()

        override fun error(format: String?, arg: Any?, arg2: Any?) {
            errorEvents += ErrorEvent(
                message = listOf(format, arg?.toString(), arg2?.toString()).joinToString(" "),
                throwable = when {
                    arg is Throwable -> arg
                    arg2 is Throwable -> arg2
                    else -> null
                }
            )
        }

        override fun error(format: String?, vararg arguments: Any?) {
            errorEvents += ErrorEvent(
                message = buildList {
                    add(format)
                    arguments.forEach { add(it?.toString()) }
                }.joinToString(" "),
                throwable = when {
                    arguments.lastOrNull() is Throwable -> arguments.last() as Throwable
                    else -> null
                }
            )
        }
    }

    private data class ErrorEvent(
        val message: String,
        val throwable: Throwable?
    )
}
