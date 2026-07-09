package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class HexanaMcpFunctionsToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListFunctionsReturnsImportedAndDefinedFunctions() {
        val fixture = createFunctionFixture("fixtures/direct/functions/list_functions.wasm")

        val result = callToolJsonObject(
            "list_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("offset", JsonPrimitive(0))
                put("limit", JsonPrimitive(10))
            }
        )
        val functions = result.array("functions")

        assertEquals(3, functions.size)
        assertEquals(0, functions.obj(0).int("funcidx"))
        assertEquals("import", functions.obj(0).text("definedIn"))
        assertEquals(1, functions.obj(1).int("funcidx"))
        assertEquals("function_section", functions.obj(1).text("definedIn"))
        assertEquals("increment", functions.obj(1).array("exportedAs")[0].textValue())
        assertTrue(functions.obj(1).long("codeSize") > 0)
        assertEquals(2, functions.obj(2).int("funcidx"))
    }

    fun testListFunctionsProvidesStructuredContentForTypedResult() {
        val fixture = createFunctionFixture("fixtures/direct/functions/structured_list_functions.wasm")

        val result = callTool(
            "list_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("offset", JsonPrimitive(0))
                put("limit", JsonPrimitive(10))
            }
        )

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val functions = structuredContent.array("functions")
        assertEquals(3, functions.size)
        assertEquals(0, functions.obj(0).int("funcidx"))
        assertEquals(2, functions.obj(2).int("funcidx"))
    }

    fun testListCodeSectionOffsetsReturnsDefinedFunctionBodyRanges() {
        val fixture = createFunctionFixture("fixtures/direct/functions/list_code_section_offsets.wasm")

        val result = callToolJsonObject("list_code_section_offsets", jsonPath(fixture.projectRelativePath))
        val functions = result.array("functions")

        assertEquals(2, functions.size)
        assertEquals(1, functions.obj(0).int("funcidx"))
        assertTrue(functions.obj(0).long("offset") > 0)
        assertTrue(functions.obj(0).long("size") > 0)
        assertEquals(2, functions.obj(1).int("funcidx"))
    }

    fun testFunctionsForIndicesReturnsImportAndDefinedEntries() {
        val fixture = createFunctionFixture("fixtures/direct/functions/functions_for_indices.wasm")

        val result = callToolJsonObject(
            "functions_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                })
            }
        )
        val functions = result.array("functions")

        assertEquals(2, functions.size)
        assertEquals(0, functions.obj(0).int("funcidx"))
        assertEquals("import", functions.obj(0).text("definedIn"))
        assertEquals("env", functions.obj(0).obj("import").text("module"))
        assertEquals("print", functions.obj(0).obj("import").text("name"))
        assertEquals(1, functions.obj(1).int("funcidx"))
        assertEquals("function_section", functions.obj(1).text("definedIn"))
        assertEquals("increment", functions.obj(1).array("exportedAs")[0].textValue())
    }

    fun testFunctionsForIndicesProvidesStructuredContentForTypedResult() {
        val fixture = createFunctionFixture("fixtures/direct/functions/structured_functions_for_indices.wasm")

        val result = callTool(
            "functions_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                })
            }
        )

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val functions = structuredContent.array("functions")
        assertEquals(2, functions.size)
        assertEquals(0, functions.obj(0).int("funcidx"))
        assertEquals(1, functions.obj(1).int("funcidx"))
    }

    fun testFunctionsForIndicesRejectsOversizedIndicesBeforeResolvingFile() {
        val result = callTool(
            "functions_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive("fixtures/direct/functions/missing.wasm"))
                put("indices", buildJsonArray {
                    repeat(10_001) { index ->
                        add(JsonPrimitive(index))
                    }
                })
            }
        )

        assertTrue(result.isError)
        assertEquals("`indices` must contain at most 10000 items", result.textPayloads.single())
    }

    fun testResolveFunctionSignatureReturnsTypeIndexAndSignature() {
        val fixture = createFunctionFixture("fixtures/direct/functions/resolve_function_signature.wasm")

        val result = callToolJsonObject(
            "resolve_function_signature",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("funcidx", JsonPrimitive(1))
            }
        )

        assertEquals(1, result.int("funcidx"))
        assertEquals(0, result.int("typeidx"))
        assertEquals(listOf("i32"), result.array("params").map { it.textValue() })
        assertEquals(listOf("i32"), result.array("results").map { it.textValue() })
        assertEquals("export", result.text("kind"))
        assertEquals("increment", result.array("exportNames")[0].textValue())
    }

    fun testDisassembleFunctionReturnsBodySnapshot() {
        val fixture = createWasmFixture(
            "fixtures/direct/functions/disassemble_function.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "print" (func (param i32)))
              (func (export "increment") (type 0) (param i32) (result i32)
                (local i32 i32)
                local.get 0
                local.set 1
                local.get 1
                call 0
                local.get 1))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "disassemble_function",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("funcidx", JsonPrimitive(1))
            }
        )

        assertEquals(1, result.int("funcidx"))
        assertEquals(0, result.int("typeidx"))
        assertEquals(listOf("i32"), result.obj("signature").array("params").map { it.textValue() })
        assertEquals(listOf("i32"), result.obj("signature").array("results").map { it.textValue() })
        assertFalse(result["isImported"]!!.jsonPrimitive.boolean)
        assertTrue(result["isExported"]!!.jsonPrimitive.boolean)
        assertEquals("increment", result.array("exportNames")[0].textValue())
        assertEquals(listOf("i32"), result.array("locals").map { it.textValue() })
        assertEquals(1, result.array("localGroups").size)
        assertEquals(2, result.array("localGroups").obj(0).int("count"))
        assertEquals("i32", result.array("localGroups").obj(0).text("type"))
        assertTrue(result.long("codeOffset") > 0)
        assertTrue(result.long("codeSize") > 0)
        assertTrue(result.text("hex").isNotBlank())
        assertTrue(result.text("wat").contains("(func (param i32) (result i32)"))
        assertTrue(result.text("wat").contains("(local i32 i32)"))

        val instructions = result.array("instructions")
        assertEquals("local.get", instructions.obj(0).text("op"))
        assertEquals(listOf("0"), instructions.obj(0).array("args").map { it.textValue() })
        assertEquals("local.get 0", instructions.obj(0).text("text"))
        assertTrue(instructions.map { it.toString() }.any { it.contains("call 0") })
    }

    fun testGetFunctionCallGraphReturnsResolvedCallsAndMemoryWrites() {
        val fixture = createWasmFixture(
            "fixtures/direct/functions/get_function_call_graph.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (type (func (param i32)))
              (import "env" "log" (func (type 1)))
              (memory 1)
              (table 1 funcref)
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
                i32.const 0
                call_indirect (type 0)
                drop
                local.get 0
                call 1)
              (elem (i32.const 0) ${'$'}helper))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "get_function_call_graph",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(2))
                })
            }
        )
        val functions = result.array("functions")

        assertEquals(2, functions.size)
        assertEquals(0, functions.obj(0).int("funcidx"))
        assertEquals("import", functions.obj(0).text("kind"))
        assertEquals("env", functions.obj(0).obj("import").text("module"))
        assertEquals("log", functions.obj(0).obj("import").text("name"))

        val main = functions.obj(1)
        assertEquals(2, main.int("funcidx"))
        assertEquals("local", main.text("kind"))
        assertEquals("main", main.array("exportedAs")[0].textValue())
        assertEquals(2, main.array("locals").obj(0).int("count"))
        assertEquals("i32", main.array("locals").obj(0).text("type"))

        val directCalls = main.array("directCalls")
        assertEquals(
            listOf(0, 1),
            listOf(directCalls.obj(0).int("targetFuncidx"), directCalls.obj(1).int("targetFuncidx"))
        )
        assertEquals("call", directCalls.obj(0).text("op"))
        assertEquals("import", directCalls.obj(0).obj("target").text("kind"))
        assertEquals("env", directCalls.obj(0).obj("target").obj("import").text("module"))
        assertEquals("log", directCalls.obj(0).obj("target").obj("import").text("name"))
        assertEquals("local", directCalls.obj(1).obj("target").text("kind"))

        val importedCalls = main.array("directImportedCalls")
        assertEquals(1, importedCalls.size)
        assertEquals(0, importedCalls.obj(0).int("targetFuncidx"))

        val indirectCalls = main.array("indirectCalls")
        assertEquals(1, indirectCalls.size)
        assertEquals(0, indirectCalls.obj(0).int("typeidx"))
        assertEquals(0, indirectCalls.obj(0).int("tableidx"))

        val memoryWrites = main.array("directMemoryWrites")
        assertEquals(1, memoryWrites.size)
        assertEquals("i32.store", memoryWrites.obj(0).text("op"))
        assertEquals(0, memoryWrites.obj(0).int("memoryOffset"))
    }

    fun testSearchFunctionsByNameReturnsImportAndExportMatches() {
        val fixture = createFunctionFixture("fixtures/direct/functions/search_functions_by_name.wasm")

        val importResult = callToolJsonObject(
            "search_functions_by_name",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("query", JsonPrimitive("print"))
                put("limit", JsonPrimitive(10))
            }
        )
        val importMatches = importResult.array("functions")
        assertEquals(1, importMatches.size)
        assertEquals(0, importMatches.obj(0).int("funcidx"))
        assertEquals("print", importMatches.obj(0).text("name"))
        assertEquals("import", importMatches.obj(0).text("kind"))
        assertEquals("env", importMatches.obj(0).text("module"))
        assertEquals("print", importMatches.obj(0).text("importName"))

        val exportResult = callToolJsonObject(
            "search_functions_by_name",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("query", JsonPrimitive("INCRE"))
            }
        )
        val exportMatches = exportResult.array("functions")
        assertEquals(1, exportMatches.size)
        assertEquals(1, exportMatches.obj(0).int("funcidx"))
        assertEquals("increment", exportMatches.obj(0).text("name"))
        assertEquals("export", exportMatches.obj(0).text("kind"))
        assertEquals("increment", exportMatches.obj(0).text("exportName"))
        assertEquals(listOf("i32"), exportMatches.obj(0).obj("signature").array("params").map { it.textValue() })
        assertEquals(listOf("i32"), exportMatches.obj(0).obj("signature").array("results").map { it.textValue() })
        assertEquals(listOf("i32"), exportMatches.obj(0).array("locals").map { it.textValue() })
        assertTrue(exportMatches.obj(0).long("offset") > 0)
    }

    fun testGetLocalsForFunctionsReturnsGroupedLocalDeclarations() {
        val fixture = createFunctionFixture("fixtures/direct/functions/get_locals.wasm")

        val result = callToolJsonObject(
            "get_locals_for_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val functions = result.array("functions")

        assertEquals(1, functions.size)
        assertEquals(1, functions.obj(0).int("funcidx"))
        assertEquals(1, functions.obj(0).array("locals").size)
        assertEquals(1, functions.obj(0).array("locals").obj(0).int("local"))
        assertEquals("i32", functions.obj(0).array("locals").obj(0).text("type"))
    }

    fun testGetLocalsForFunctionsProvidesStructuredContentForTypedResult() {
        val fixture = createFunctionFixture("fixtures/direct/functions/structured_get_locals.wasm")

        val result = callTool(
            "get_locals_for_functions",
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
        val functions = structuredContent.array("functions")
        assertEquals(1, functions.size)
        assertEquals(1, functions.obj(0).int("funcidx"))
    }

    fun testAuditLocalStackBudgetReturnsRecommendedReportWithoutDisassembly() {
        val fixture = createWasmFixture(
            "fixtures/direct/functions/local_budget_audit.wasm",
            """
            (module
              (func (export "health_check"))
              (func (export "hydrate_checkout") (local i32 i32 i32 i32 i64 i64))
              (func (export "render_invoice") (local f64 f64 i32 i32 i32))
              (func (export "route_cart") (local i32 i32 i32 i32)))
            """.trimIndent()
        )
        val policy = createTextFixture(
            "fixtures/direct/functions/local_budget.json",
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
        val log = createTextFixture(
            "fixtures/direct/functions/local_budget.log",
            "2026-04-30T13:05:14Z runtime=edge-runtime-local-budget event=evt-local-7719 checked_exports=health_check,hydrate_checkout,render_invoice,route_cart\n"
        )

        val result = callToolJsonObject(
            "audit_local_stack_budget",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("policyPath", JsonPrimitive(policy.projectRelativePath))
                put("logPath", JsonPrimitive(log.projectRelativePath))
            }
        )

        assertEquals("edge-runtime-local-budget", result.text("runtimeId"))
        assertEquals(4, result.obj("budget").int("maxLocals"))
        val functions = result.array("functions")
        assertEquals(4, functions.size)
        val hydrate = functions.filterIsInstance<JsonObject>().single { it.text("export") == "hydrate_checkout" }
        assertEquals(1, hydrate.int("funcidx"))
        assertEquals(6, hydrate.int("localCount"))
        assertEquals(4, hydrate.obj("localsByType").int("i32"))
        assertEquals(2, hydrate.obj("localsByType").int("i64"))

        val recommended = result.obj("recommendedReport")
        assertEquals("edge-runtime-local-budget", recommended.text("runtime_id"))
        assertEquals(fixture.projectRelativePath, recommended.text("module"))
        assertEquals("evt-local-7719", recommended.obj("evidence").text("log_event_id"))
        assertEquals(listOf("health_check", "route_cart"), recommended.array("clean_exports").map { it.textValue() })
        assertEquals(
            listOf("hydrate_checkout", "render_invoice"),
            recommended.obj("minimal_fix").array("split_or_reduce_exports").map { it.textValue() }
        )
    }

    fun testGetInstructionsForFunctionsReturnsDecodedInstructionText() {
        val fixture = createFunctionFixture("fixtures/direct/functions/get_instructions.wasm")

        val result = callToolJsonObject(
            "get_instructions_for_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )
        val functions = result.array("functions")

        assertEquals(1, functions.size)
        assertEquals(1, functions.obj(0).int("funcidx"))
        assertTrue(functions.obj(0).array("instructions").map { it.textValue() }.contains("local.get 0"))
        assertTrue(functions.obj(0).array("instructions").map { it.textValue() }.contains("call 0"))
    }

    fun testGetInstructionsForFunctionsProvidesStructuredContentForTypedResult() {
        val fixture = createFunctionFixture("fixtures/direct/functions/structured_get_instructions.wasm")

        val result = callTool(
            "get_instructions_for_functions",
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
        val functions = structuredContent.array("functions")
        assertEquals(1, functions.size)
        assertEquals(1, functions.obj(0).int("funcidx"))
    }

    fun testGetInstructionsForFunctionsSupportsMemoryInstructionsWithUnsignedOperands() {
        val fixture = createWasmFixture(
            "fixtures/direct/functions/get_instructions_memory_ops.wasm",
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

        val result = callToolJsonObject(
            "get_instructions_for_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                })
            }
        )
        val functions = result.array("functions")

        assertEquals(2, functions.size)
        assertEquals(0, functions.obj(0).int("funcidx"))
        assertEquals(listOf("memory.size"), functions.obj(0).array("instructions").map { it.textValue() })
        assertEquals(1, functions.obj(1).int("funcidx"))
        assertEquals(
            listOf("i32.const 1", "memory.grow"),
            functions.obj(1).array("instructions").map { it.textValue() }
        )
    }

    private fun createFunctionFixture(pathInProject: String) = createWasmFixture(
        pathInProject,
        """
        (module
          (type (func (param i32) (result i32)))
          (import "env" "print" (func (param i32)))
          (func (export "increment") (type 0) (param i32) (result i32)
            (local i32)
            local.get 0
            local.set 1
            local.get 1
            call 0
            local.get 1)
          (func
            ref.func 1
            drop))
        """.trimIndent()
    )
}
