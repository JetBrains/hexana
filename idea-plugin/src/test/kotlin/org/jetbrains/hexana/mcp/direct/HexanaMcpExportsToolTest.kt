package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

@org.junit.Ignore("Low-level MCP tools are no longer exposed; use query_artifacts direct tests.")
class HexanaMcpExportsToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testListExportsReturnsAllKindsWithIndices() {
        val fixture = createWasmFixture(
            "fixtures/direct/exports/list_exports.wasm",
            """
            (module
              (memory (export "mem") 1)
              (table (export "tab") 2 funcref)
              (global (export "glob") i32 (i32.const 0))
              (func (export "func")))
            """.trimIndent()
        )

        val result = callToolJsonObject("list_exports", jsonPath(fixture.projectRelativePath))
        val exports = result.array("exports")

        assertEquals(4, exports.size)
        assertEquals("mem", exports.obj(0).text("name"))
        assertEquals(WASM_EXPORT_KIND_MEMORY, exports.obj(0).int("kind"))
        assertEquals("tab", exports.obj(1).text("name"))
        assertEquals(WASM_EXPORT_KIND_TABLE, exports.obj(1).int("kind"))
        assertEquals("glob", exports.obj(2).text("name"))
        assertEquals(WASM_EXPORT_KIND_GLOBAL, exports.obj(2).int("kind"))
        assertEquals("func", exports.obj(3).text("name"))
        assertEquals(WASM_EXPORT_KIND_FUNCTION, exports.obj(3).int("kind"))
    }

    fun testListExportsProvidesStructuredContentForTypedResult() {
        val fixture = createWasmFixture(
            "fixtures/direct/exports/structured_list_exports.wasm",
            """
            (module
              (memory (export "mem") 1)
              (func (export "func")))
            """.trimIndent()
        )

        val result = callTool("list_exports", jsonPath(fixture.projectRelativePath))

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val exports = structuredContent.array("exports")
        assertEquals("mem", exports.obj(0).text("name"))
        assertEquals("func", exports.obj(1).text("name"))
    }

    fun testSearchExportsReturnsFunctionSignatureAndMemoryLimits() {
        val fixture = createWasmFixture(
            "fixtures/direct/exports/search_exports.wasm",
            """
            (module
              (memory (export "memory") 1 2)
              (func (export "add") (param i32 i32) (result i32)
                local.get 0))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "search_exports",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("query", buildJsonArray {
                    add(JsonPrimitive("add"))
                    add(JsonPrimitive("mem"))
                })
                put("limit", JsonPrimitive(10))
            }
        )
        val results = result.array("results")

        assertEquals(2, results.size)
        val add = results.obj(0)
        assertEquals("add", add.text("name"))
        assertEquals("func", add.text("kind"))
        assertEquals(0, add.int("funcidx"))
        assertEquals(listOf("i32", "i32"), add.obj("signature").array("params").map { it.textValue() })
        assertEquals(listOf("i32"), add.obj("signature").array("results").map { it.textValue() })
        assertEquals("""(export "add" (func 0))""", add.text("repr"))
        assertEquals(1.0, add["score"]!!.jsonPrimitive.double)

        val memory = results.obj(1)
        assertEquals("memory", memory.text("name"))
        assertEquals("memory", memory.text("kind"))
        assertEquals(0, memory.int("memidx"))
        assertEquals(1, memory.obj("limits").long("min"))
        assertEquals(2, memory.obj("limits").long("max"))
    }

    fun testListExportedFunctionsReturnsMetadataSlice() {
        val fixture = createWasmFixture(
            "fixtures/direct/exports/list_exported_functions.wasm",
            """
            (module
              (func (export "a") (result i32)
                i32.const 1)
              (func (export "b") (result i32)
                i32.const 2))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "list_exported_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(0))
                })
            }
        )

        assertEquals(0, result.int("from"))
        assertEquals(0, result.int("to"))
        assertEquals(2, result.int("total"))
        assertEquals(1, result.array("returned").size)
        assertEquals("a", result.array("returned").obj(0).text("name"))
    }

    fun testListExportedFunctionsPreservesImportedFunctionEntryShape() {
        val fixture = createWasmFixture(
            "fixtures/direct/exports/list_exported_imported_function.wasm",
            """
            (module
              (import "env" "log" (func (param i32)))
              (export "log_alias" (func 0)))
            """.trimIndent()
        )

        val result = callToolJsonObject(
            "list_exported_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(0))
                })
            }
        )

        val returned = result.array("returned")
        assertEquals(1, returned.size)
        assertEquals("log_alias", returned.obj(0).text("name"))
        val entry = returned.obj(0).obj("entry")
        assertEquals(0, entry.int("funcidx"))
        assertEquals("env", entry.text("module"))
        assertEquals("log", entry.text("name"))
        assertEquals("i32", entry.obj("signature").array("params")[0].textValue())
    }

  fun testListExportedFunctionsKeepsUnifiedIndicesAfterImportedPrefix() {
    val fixture = createWasmFixture(
      "fixtures/direct/exports/list_exported_local_after_imports.wasm",
      """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "a" (func))
              (import "env" "b" (func))
              (func (export "local_after_imports") (type 0) (param i32) (result i32)
                local.get 0))
            """.trimIndent()
    )

    val result = callToolJsonObject(
      "list_exported_functions",
      buildJsonObject {
        put("path", JsonPrimitive(fixture.projectRelativePath))
        put("entriesRange", buildJsonObject {
          put("from", JsonPrimitive(0))
          put("to", JsonPrimitive(0))
        })
      }
    )

    val returned = result.array("returned")
    assertEquals(1, returned.size)
    assertEquals("local_after_imports", returned.obj(0).text("name"))
    val entry = returned.obj(0).obj("entry")
    assertEquals(2, entry.int("funcidx"))
    val signature = entry.obj("signature")
    assertEquals("i32", signature.array("params")[0].textValue())
    assertEquals("i32", signature.array("return")[0].textValue())
  }

  fun testListExportedFunctionsUsesNullableReferenceShorthandForAbstractHeapTypes() {
    val fixture = createWasmFixture(
      "fixtures/direct/exports/list_exported_externref_signature.wasm",
      """
            (module
              (func (export "extern_id") (param externref) (result externref)
                local.get 0))
            """.trimIndent()
    )

    val result = callToolJsonObject(
      "list_exported_functions",
      buildJsonObject {
        put("path", JsonPrimitive(fixture.projectRelativePath))
        put("entriesRange", buildJsonObject {
          put("from", JsonPrimitive(0))
          put("to", JsonPrimitive(0))
        })
      }
    )

    val returned = result.array("returned")
    assertEquals(1, returned.size)
    val signature = returned.obj(0).obj("entry").obj("signature")
    assertEquals("externref", signature.array("params")[0].textValue())
    assertEquals("externref", signature.array("return")[0].textValue())
  }

    fun testListExportedFunctionsProvidesStructuredContentForTypedResult() {
        val fixture = createWasmFixture(
            "fixtures/direct/exports/structured_list_exported_functions.wasm",
            """
            (module
              (func (export "a") (result i32)
                i32.const 1)
              (func (export "b") (result i32)
                i32.const 2))
            """.trimIndent()
        )

        val result = callTool(
            "list_exported_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(0))
                })
            }
        )

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        val returned = structuredContent.array("returned")
        assertEquals("a", returned.obj(0).text("name"))
        assertEquals(0, returned.obj(0).obj("entry").int("funcidx"))
    }

    private companion object {
        // TODO should be moved to some kind of enum
        const val WASM_EXPORT_KIND_FUNCTION = 0
        const val WASM_EXPORT_KIND_TABLE = 1
        const val WASM_EXPORT_KIND_MEMORY = 2
        const val WASM_EXPORT_KIND_GLOBAL = 3
    }
}
