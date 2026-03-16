package org.jetbrains.hexana.mcp.direct

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

        val result = callToolJsonArray("list_imports", jsonPath(fixture.projectRelativePath))

        assertEquals(1, result.size)
        assertEquals(0, result.obj(0).int("funcidx"))
        assertEquals("env", result.obj(0).text("module"))
        assertEquals("print", result.obj(0).text("name"))
        assertEquals("i32", result.obj(0).obj("signature").array("params")[0].textValue())
    }

    fun testListImportsReturnsEmptyArrayWhenModuleHasNoFunctionImports() {
        val fixture = createWasmFixture(
            "fixtures/direct/imports/without_imports.wasm",
            """
            (module
              (func (export "main") (result i32)
                i32.const 0))
            """.trimIndent()
        )

        val result = callToolJsonArray("list_imports", jsonPath(fixture.projectRelativePath))

        assertTrue(result.isEmpty())
    }
}
