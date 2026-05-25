package org.jetbrains.hexana.mcp.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

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

    fun testListEnabledFeaturesReturnsFlagsAndSources() {
        val fixture = createWasmFixture(
            "fixtures/direct/summary/enabled_features.wasm",
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

        val result = callToolJsonObject("list_enabled_features", jsonPath(fixture.projectRelativePath))
        val features = result.obj("features")

        assertTrue(features["referenceTypes"]!!.jsonPrimitive.boolean)
        assertTrue(features["bulkMemory"]!!.jsonPrimitive.boolean)
        assertTrue(features["multiValue"]!!.jsonPrimitive.boolean)
        assertTrue(features["signExtension"]!!.jsonPrimitive.boolean)
        assertFalse(features["simd"]!!.jsonPrimitive.boolean)
        assertFalse(features["threads"]!!.jsonPrimitive.boolean)
        assertFalse(features["memory64"]!!.jsonPrimitive.boolean)
        assertFalse(features["functionReferences"]!!.jsonPrimitive.boolean)

        val sources = result.obj("sources")
        assertTrue(sources.array("sections").map { it.textValue() }.contains("Type"))
        assertTrue(sources.array("sections").map { it.textValue() }.contains("DataCount"))
        assertTrue(sources.array("instructions").map { it.textValue() }.contains("memory.init"))
        assertTrue(sources.array("instructions").map { it.textValue() }.contains("data.drop"))
        assertTrue(sources.array("instructions").map { it.textValue() }.contains("i32.extend8_s"))
    }

    fun testCheckRuntimeCompatibilityReturnsProposalEvidence() {
        val fixture = createWasmFixture(
            "fixtures/direct/summary/runtime_compatibility.wasm",
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

        val result = callToolJsonObject(
            "check_runtime_compatibility",
            JsonObject(
                jsonPath(fixture.projectRelativePath) + mapOf(
                    "runtime" to kotlinx.serialization.json.JsonPrimitive("graalwasm")
                )
            )
        )

        assertFalse(result["compatible"]!!.jsonPrimitive.boolean)
        assertTrue(result.array("requiredProposals").map { it.textValue() }.contains("bulk-memory"))
        assertTrue(result.array("unknownProposals").map { it.textValue() }.contains("bulk-memory"))
        val bulkMemory = result.array("proposalStatuses")
            .map { it as JsonObject }
            .single { it.text("proposal") == "bulk-memory" }
        assertEquals("unknown", bulkMemory.text("status"))
        assertTrue(bulkMemory.obj("evidence").array("instructions").map { it.textValue() }.contains("memory.init"))
    }

    fun testDiffRequiredProposalsReturnsCompactDelta() {
        val baseline = createWasmFixture(
            "fixtures/direct/summary/runtime_compatibility_baseline.wasm",
            """
            (module
              (type (func (result i32 i32)))
              (func (export "base") (type 0)
                i32.const 1
                i32.const 2))
            """.trimIndent()
        )
        val current = createWasmFixture(
            "fixtures/direct/summary/runtime_compatibility_current.wasm",
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

        val result = callToolJsonObject(
            "diff_required_proposals",
            JsonObject(
                jsonPath(current.projectRelativePath) + mapOf(
                    "baselinePath" to kotlinx.serialization.json.JsonPrimitive(baseline.projectRelativePath)
                )
            )
        )

        assertEquals(current.projectRelativePath, result.text("path"))
        assertEquals(baseline.projectRelativePath, result.text("baselinePath"))
        assertEquals(
            listOf("bulk-memory", "reference-types", "sign-extension"),
            result.array("newlyRequiredProposals").map { it.textValue() }
        )
        assertTrue(result.array("newlyRequiredEvidence").any {
            (it as JsonObject).text("proposal") == "bulk-memory"
        })
        assertEquals(3, result.obj("summary").int("newlyRequiredCount"))
    }

    fun testListCustomSectionsReturnsOffsetsAndPreviews() {
        val fixture = createBinaryFixture(
            "fixtures/direct/summary/custom_sections.wasm",
            wasmWithCustomSections()
        )

        val result = callToolJsonObject("list_custom_sections", jsonPath(fixture.projectRelativePath))
        val sections = result.array("customSections")

        assertEquals(2, sections.size)
        assertEquals("answer", sections.obj(0).text("name"))
        assertEquals(8, sections.obj(0).long("offset"))
        assertTrue(sections.obj(0).long("size") > 0)
        assertEquals("utf8", sections.obj(0).text("encoding"))
        assertEquals("flag=42", sections.obj(0).text("preview"))

        assertEquals(".debug_info", sections.obj(1).text("name"))
        assertEquals("binary", sections.obj(1).text("encoding"))
        assertEquals("0x04 0x00 0x00 0x00 0x11 0xff", sections.obj(1).text("preview"))
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
}
