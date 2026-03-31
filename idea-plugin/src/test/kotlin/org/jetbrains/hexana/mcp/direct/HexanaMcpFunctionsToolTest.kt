package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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
        assertEquals("num:i32", functions.obj(0).array("locals").obj(0).text("type"))
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
