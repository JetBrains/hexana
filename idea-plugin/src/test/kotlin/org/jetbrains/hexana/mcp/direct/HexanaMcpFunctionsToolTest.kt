package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class HexanaMcpFunctionsToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListFunctionsReturnsImportedAndDefinedFunctions() {
        val fixture = createFunctionFixture("fixtures/direct/functions/list_functions.wasm")

        val result = callToolJsonArray(
            "list_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("offset", JsonPrimitive(0))
                put("limit", JsonPrimitive(10))
            }
        )

        assertEquals(3, result.size)
        assertEquals(0, result.obj(0).int("funcidx"))
        assertEquals("import", result.obj(0).text("definedIn"))
        assertEquals(1, result.obj(1).int("funcidx"))
        assertEquals("function_section", result.obj(1).text("definedIn"))
        assertEquals("increment", result.obj(1).array("exportedAs")[0].textValue())
        assertTrue(result.obj(1).long("codeSize") > 0)
        assertEquals(2, result.obj(2).int("funcidx"))
    }

    fun testFunctionsForIndicesReturnsImportAndDefinedEntries() {
        val fixture = createFunctionFixture("fixtures/direct/functions/functions_for_indices.wasm")

        val result = callToolJsonArray(
            "functions_for_indices",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                })
            }
        )

        assertEquals(2, result.size)
        assertEquals(0, result.obj(0).int("funcidx"))
        assertEquals("import", result.obj(0).text("definedIn"))
        assertEquals("env", result.obj(0).obj("import").text("module"))
        assertEquals("print", result.obj(0).obj("import").text("name"))
        assertEquals(1, result.obj(1).int("funcidx"))
        assertEquals("function_section", result.obj(1).text("definedIn"))
        assertEquals("increment", result.obj(1).array("exportedAs")[0].textValue())
    }

    fun testGetLocalsForFunctionsReturnsGroupedLocalDeclarations() {
        val fixture = createFunctionFixture("fixtures/direct/functions/get_locals.wasm")

        val result = callToolJsonArray(
            "get_locals_for_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )

        assertEquals(1, result.size)
        assertEquals(1, result.obj(0).int("funcidx"))
        assertEquals(1, result.obj(0).array("locals").size)
        assertEquals(1, result.obj(0).array("locals").obj(0).int("local"))
        assertEquals("num:i32", result.obj(0).array("locals").obj(0).text("type"))
    }

    fun testGetInstructionsForFunctionsReturnsDecodedInstructionText() {
        val fixture = createFunctionFixture("fixtures/direct/functions/get_instructions.wasm")

        val result = callToolJsonArray(
            "get_instructions_for_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("indices", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(1))
                })
            }
        )

        assertEquals(1, result.size)
        assertEquals(1, result.obj(0).int("funcidx"))
        assertTrue(result.obj(0).array("instructions").map { it.textValue() }.contains("local.get 0"))
        assertTrue(result.obj(0).array("instructions").map { it.textValue() }.contains("call 0"))
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
