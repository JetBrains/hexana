package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject

@org.junit.Ignore("Low-level MCP tools are no longer exposed; use query_artifacts direct tests.")
class HexanaMcpImportsToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListImportsReturnsFunctionImportMetadata() {
        val fixture = createWasmFixture(
            "fixtures/direct/imports/with_import.wasm",
            """
            (module
              (import "env" "print" (func (param i32)))
              (func (export "f1") (local i32)
                local.get 0
                call 0))
            """.trimIndent()
        )

        val result = callToolJsonObject("list_imports", jsonPath(fixture.projectRelativePath))

        val imports = result.array("imports")
        assertEquals(1, imports.size)
        assertEquals(0, imports.obj(0).int("funcidx"))
        assertEquals("env", imports.obj(0).text("module"))
        assertEquals("print", imports.obj(0).text("name"))
        assertEquals("i32", imports.obj(0).obj("signature").array("params")[0].textValue())
    }

    fun testListImportsReturnsEmptyImportsArrayWhenModuleHasNoFunctionImports() {
        val fixture = createWasmFixture(
            "fixtures/direct/imports/without_imports.wasm",
            """
            (module
              (func (export "main") (result i32)
                i32.const 0))
            """.trimIndent()
        )

        val result = callToolJsonObject("list_imports", jsonPath(fixture.projectRelativePath))

        assertTrue(result.array("imports").isEmpty())
    }

    fun testListImportsProvidesStructuredContentForTypedResult() {
        val fixture = createWasmFixture(
            "fixtures/direct/imports/structured_import.wasm",
            """
            (module
              (import "env" "print" (func (param i32)))
              (func (export "main")
                i32.const 1
                call 0))
            """.trimIndent()
        )

        val result = callTool("list_imports", jsonPath(fixture.projectRelativePath))

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val imports = structuredContent.array("imports")
        assertEquals("env", imports.obj(0).text("module"))
        assertEquals("print", imports.obj(0).text("name"))
    }
}
