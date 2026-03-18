package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject

class HexanaMcpSummaryToolTest : HexanaMcpToolsetDirectTestBase() {
    fun testSummarizeModuleReturnsProjectRelativeFixtureData() {
        val fixture = createWasmFixture(
            "fixtures/direct/summary/core.wasm",
            """
            (module
              (type (func (param i32) (result i32)))
              (import "env" "print" (func (param i32)))
              (import "env" "seed" (global i32))
              (import "env" "shared" (memory 1))
              (memory 2 4)
              (table 2 funcref)
              (global (export "counter") (mut i64) (i64.const 7))
              (data (i32.const 0) "Hello")
              (func (export "increment") (type 0) (param i32) (result i32)
                (local i32)
                local.get 0
                local.set 1
                local.get 1
                call 0
                local.get 1)
              (func
                ref.func 1
                drop)
              (elem (i32.const 0) func 1 2)
              (export "memory" (memory 1))
              (export "table" (table 0)))
            """.trimIndent()
        )

        val result = callToolJsonObject("summarize_module", jsonPath(fixture.projectRelativePath))

        assertEquals(2, result.int("functions"))
        assertEquals(4, result.int("exports"))
        assertEquals(1, result.int("memories"))
        assertEquals(2, result.int("globals"))
        assertEquals(1, result.int("tables"))
        assertTrue(result.int("types") > 0)
        assertEquals(1, result.obj("imports").int("functions"))
        assertEquals(1, result.obj("imports").int("memories"))
        assertEquals(1, result.obj("imports").int("globals"))
        assertEquals(0, result.obj("imports").int("tables"))
        assertTrue(result.int("moduleSize") > 0)
    }

    fun testSummarizeModuleAcceptsAbsolutePath() {
        val fixture = createWasmFixture(
            "fixtures/direct/summary/absolute.wasm",
            """
            (module
              (memory 1)
              (func (export "main") (result i32)
                i32.const 42))
            """.trimIndent()
        )

        val result = callToolJsonObject("summarize_module", jsonPath(fixture.absolutePath))

        assertEquals(1, result.int("functions"))
        assertEquals(1, result.int("exports"))
        assertEquals(1, result.int("memories"))
        assertEquals(0, result.int("globals"))
        assertEquals(0, result.int("tables"))
        assertTrue(result.int("moduleSize") > 0)
    }

    fun testSummarizeModuleProvidesStructuredContentForTypedResult() {
        val fixture = createWasmFixture(
            "fixtures/direct/summary/structured.wasm",
            """
            (module
              (memory 1)
              (func (export "main") (result i32)
                i32.const 42))
            """.trimIndent()
        )

        val result = callTool("summarize_module", jsonPath(fixture.projectRelativePath))

        assertFalse(result.isError)
        assertEquals(1, result.textPayloads.size)
        val textJson = json.parseToJsonElement(result.textPayloads.single()) as JsonObject
        val structuredContent = result.requireStructuredObject()
        assertEquals(textJson, structuredContent)
        assertEquals(1, structuredContent.int("functions"))
        assertEquals(1, structuredContent.int("exports"))
    }
}
