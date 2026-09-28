package org.jetbrains.hexana.cli.wasmtools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValidateAndExplainWorkflowTest {
    @Test
    fun `clean core report has exact structural and non-validation contract`() {
        val rendered = report(CORE_HEADER)
        val json = rendered.semanticReport

        assertEquals(0, rendered.exitCode)
        assertEquals(setOf("product", "report", "input", "completeness", "diagnostics", "findings", "truncation",
            "summary", "structural_parse", "semantic_validation", "features_and_proposals", "runtime_compatibility"), json.keys)
        assertEquals("validate-and-explain", json["report"]!!.jsonObject["workflow"]!!.jsonPrimitive.content)
        assertEquals("core_module", json["summary"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("not_performed", json["semantic_validation"]!!.jsonPrimitive.content)
        assertEquals("clean", json["structural_parse"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(setOf("status", "section_scan_failures", "parse_problems"), json["structural_parse"]!!.jsonObject.keys)
        assertEquals("completed", json["features_and_proposals"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
        assertEquals("not_requested", json["runtime_compatibility"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
        assertTrue(json["runtime_compatibility"]!!.jsonObject["targets"]!!.jsonArray.isEmpty())
        assertTrue(json["runtime_compatibility"]!!.jsonObject["results"]!!.jsonArray.isEmpty())
        assertEquals("complete", json["completeness"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `component marks core services inapplicable even for invalid targets`() {
        val rendered = report(COMPONENT_HEADER, listOf("invalid", "invalid"))
        val json = rendered.semanticReport
        val features = json["features_and_proposals"]!!.jsonObject
        val runtime = json["runtime_compatibility"]!!.jsonObject

        assertEquals(0, rendered.exitCode)
        assertEquals("component", json["summary"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("not_performed", json["semantic_validation"]!!.jsonPrimitive.content)
        assertEquals("not_applicable", features["stage"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, features["enabled_features"])
        assertEquals(JsonNull, features["enabled_feature_sources"])
        assertTrue(features["required_proposals"]!!.jsonArray.isEmpty())
        assertEquals("not_applicable", runtime["stage"]!!.jsonPrimitive.content)
        assertTrue(runtime["targets"]!!.jsonArray.isEmpty())
        assertTrue(runtime["results"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `partial parse retains ordered scan and decode evidence with exit one`() {
        val rendered = report(CORE_HEADER + byteArrayOf(0))
        val json = rendered.semanticReport
        val structural = json["structural_parse"]!!.jsonObject

        assertEquals(1, rendered.exitCode)
        assertEquals("partial", structural["status"]!!.jsonPrimitive.content)
        assertEquals(8, structural["section_scan_failures"]!!.jsonArray.single().jsonObject["byte_offset"]!!.jsonPrimitive.content.toInt())
        assertEquals(8, structural["parse_problems"]!!.jsonArray.single().jsonObject["byte_offset"]!!.jsonPrimitive.content.toInt())
        assertEquals("section_scan", json["diagnostics"]!!.jsonArray.first().jsonObject["category"]!!.jsonPrimitive.content)
        assertEquals("partial", json["completeness"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("not_performed", json["semantic_validation"]!!.jsonPrimitive.content)
        assertEquals("completed", json["features_and_proposals"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed core section remains reportable with decode diagnostics`() {
        val rendered = report(CORE_HEADER + byteArrayOf(1, 2, 1, 0xff.toByte()))
        val json = rendered.semanticReport

        assertEquals(1, rendered.exitCode)
        assertEquals("partial", json["structural_parse"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(1, json["structural_parse"]!!.jsonObject["parse_problems"]!!.jsonArray.single()
            .jsonObject["section_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("not_performed", json["semantic_validation"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed code vector renders partial report when feature extraction fails`() {
        val rendered = report(CORE_HEADER + byteArrayOf(0x0a, 0x01, 0x01), listOf("wasmtime"))
        val json = rendered.semanticReport
        val features = json["features_and_proposals"]!!.jsonObject
        val runtime = json["runtime_compatibility"]!!.jsonObject

        assertEquals(1, rendered.exitCode)
        assertEquals("partial", json["structural_parse"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(json["diagnostics"]!!.jsonArray.any { diagnostic ->
            diagnostic.jsonObject["code"]!!.jsonPrimitive.content == "feature_extraction_failure"
        })
        assertEquals("not_applicable", features["stage"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, features["enabled_features"])
        assertEquals(JsonNull, features["enabled_feature_sources"])
        assertTrue(features["required_proposals"]!!.jsonArray.isEmpty())
        assertEquals("not_applicable", runtime["stage"]!!.jsonPrimitive.content)
        assertEquals("wasmtime", runtime["targets"]!!.jsonArray.single().jsonObject["runtime"]!!.jsonPrimitive.content)
        assertTrue(runtime["results"]!!.jsonArray.isEmpty())
        assertTrue(json["completeness"]!!.jsonObject["limitations"]!!.jsonArray.any {
            it.jsonPrimitive.content.contains("extraction was unavailable")
        })
    }

    @Test
    fun `deeply nested blocks do not overflow feature detection`() {
        val rendered = report(coreWithNestedBlocks(20_000))

        assertEquals(0, rendered.exitCode)
        assertEquals("completed", rendered.semanticReport["features_and_proposals"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
    }

    @Test
    fun `proposal-free runtime verdict carries matrix limitation`() {
        val json = report(CORE_HEADER, listOf("wasmtime")).semanticReport
        val result = json["runtime_compatibility"]!!.jsonObject["results"]!!.jsonArray.single().jsonObject

        assertTrue(result["compatible"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["proposal_statuses"]!!.jsonArray.isEmpty())
        assertTrue(json["completeness"]!!.jsonObject["limitations"]!!.jsonArray.any {
            it.jsonPrimitive.content.contains("conservative proposal matrix")
        })
    }

    @Test
    fun `multiple decode problems remain ordered by byte offset`() {
        val bytes = CORE_HEADER + byteArrayOf(
            1, 0,
            0x0a, 8, 1, 6, 0, 0x41, 0, 0xfb.toByte(), 0x32, 0x0b,
        )
        val rendered = report(bytes)
        val problems = rendered.semanticReport["structural_parse"]!!.jsonObject["parse_problems"]!!.jsonArray

        assertEquals(1, rendered.exitCode)
        assertEquals(listOf("10", "17"), problems.map { it.jsonObject["byte_offset"]!!.jsonPrimitive.content })
    }

    @Test
    fun `partial core retains positive feature evidence but withholds runtime verdict`() {
        val rendered = report(MEMORY64_CORE + byteArrayOf(0), listOf("Wasmtime"))
        val json = rendered.semanticReport
        val features = json["features_and_proposals"]!!.jsonObject
        val runtime = json["runtime_compatibility"]!!.jsonObject

        assertEquals(1, rendered.exitCode)
        assertEquals("completed", features["stage"]!!.jsonPrimitive.content)
        assertTrue(features["enabled_features"]!!.jsonObject["memory64"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("memory64", features["required_proposals"]!!.jsonArray.single().jsonObject["proposal"]!!.jsonPrimitive.content)
        assertEquals("not_applicable", runtime["stage"]!!.jsonPrimitive.content)
        assertEquals("wasmtime", runtime["targets"]!!.jsonArray.single().jsonObject["runtime"]!!.jsonPrimitive.content)
        assertTrue(runtime["results"]!!.jsonArray.isEmpty())
        assertTrue(json["completeness"]!!.jsonObject["limitations"]!!.jsonArray.any {
            it.jsonPrimitive.content.contains("evidence may be incomplete")
        })

        val malformedMemoryRuntime = report(CORE_HEADER + byteArrayOf(5, 0), listOf("wasmtime"))
            .semanticReport["runtime_compatibility"]!!.jsonObject
        assertEquals("not_applicable", malformedMemoryRuntime["stage"]!!.jsonPrimitive.content)
        assertTrue(malformedMemoryRuntime["results"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `unresolved sections use original structural schema and diagnostic item limit`() {
        val rendered = report(CORE_HEADER + byteArrayOf(0x40, 0, 0x41, 0), maxItems = 1)
        val json = rendered.semanticReport
        val structural = json["structural_parse"]!!.jsonObject
        val unresolved = json["diagnostics"]!!.jsonArray.single().jsonObject

        assertEquals(1, rendered.exitCode)
        assertEquals(setOf("status", "section_scan_failures", "parse_problems"), structural.keys)
        assertEquals("2", json["summary"]!!.jsonObject["unresolved_section_count"]!!.jsonPrimitive.content)
        assertEquals("unresolved_section", unresolved["code"]!!.jsonPrimitive.content)
        assertEquals("64", unresolved["section_id"]!!.jsonPrimitive.content)
        assertEquals("8", unresolved["byte_offset"]!!.jsonPrimitive.content)
        assertTrue(unresolved["message"]!!.jsonPrimitive.content.contains("2 bytes"))
        assertEquals("1", json["truncation"]!!.jsonObject["omitted"]!!.jsonObject["diagnostics"]!!.jsonPrimitive.content)
        assertEquals("0", json["truncation"]!!.jsonObject["omitted"]!!.jsonObject["workflow_items"]!!.jsonPrimitive.content)
    }

    @Test
    fun `runtime targets are checked in supplied order then sorted in report`() {
        val evidence = report(MEMORY64_CORE, listOf("Wasmtime", "graalwasm"))
        val json = evidence.semanticReport
        val features = json["features_and_proposals"]!!.jsonObject
        val runtime = json["runtime_compatibility"]!!.jsonObject

        assertEquals("completed", runtime["stage"]!!.jsonPrimitive.content)
        assertEquals(listOf("graalwasm", "wasmtime"), runtime["targets"]!!.jsonArray.map {
            it.jsonObject["runtime"]!!.jsonPrimitive.content
        })
        assertEquals(listOf("graalwasm", "wasmtime"), runtime["results"]!!.jsonArray.map {
            it.jsonObject["runtime"]!!.jsonPrimitive.content
        })
        assertTrue(features["enabled_features"]!!.jsonObject["memory64"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("memory64", features["required_proposals"]!!.jsonArray.single().jsonObject["proposal"]!!.jsonPrimitive.content)
        assertEquals(1, evidence.exitCode)
        assertFalse(json["findings"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `invalid target is usage and malformed header is input refusal`() {
        val invalid = validateAndExplain(CORE_HEADER, input(CORE_HEADER), "0.1", listOf("unknown-runtime"))
        val duplicate = validateAndExplain(CORE_HEADER, input(CORE_HEADER), "0.1", listOf("Wasmtime", "wasmtime"))

        assertIs<WasmToolsReportRenderResult.UsageError>(invalid)
        assertEquals(2, invalid.exitCode)
        assertIs<WasmToolsReportRenderResult.UsageError>(duplicate)
        listOf(
            byteArrayOf(1, 2, 3, 4),
            CORE_HEADER.copyOf(7),
            CORE_HEADER.copyOf().also { it[4] = 2 },
        ).forEach { bytes ->
            val refused = validateAndExplain(bytes, input(bytes), "0.1")
            assertIs<WasmToolsReportRenderResult.InputRefused>(refused)
            assertEquals(3, refused.exitCode)
        }
    }

    @Test
    fun `invalid format and report limits are usage errors`() {
        assertEquals(2, validateAndExplain(CORE_HEADER, input(CORE_HEADER), "0.1", format = ToolOutputFormat.RAW).exitCode)
        assertEquals(2, validateAndExplain(CORE_HEADER, input(CORE_HEADER), "0.1", maxItems = 0).exitCode)
        assertEquals(2, validateAndExplain(CORE_HEADER, input(CORE_HEADER), "0.1", maxOutputBytes = 0).exitCode)
    }

    @Test
    fun `max items trims workflow lists and pretty has same semantics`() {
        val json = report(MEMORY64_CORE, listOf("wasmtime", "graalwasm"), maxItems = 1)
        val pretty = report(MEMORY64_CORE, listOf("wasmtime", "graalwasm"), format = ToolOutputFormat.PRETTY, maxItems = 1)

        assertEquals(Json.parseToJsonElement(json.output.text), Json.parseToJsonElement(pretty.output.text))
        assertEquals(1, json.exitCode)
        assertEquals(1, json.semanticReport["runtime_compatibility"]!!.jsonObject["results"]!!.jsonArray.size)
        assertTrue(json.semanticReport["truncation"]!!.jsonObject["truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `output cap below minimum report refuses without JSON`() {
        val result = validateAndExplain(CORE_HEADER, input(CORE_HEADER), "0.1", maxOutputBytes = 1)

        assertIs<WasmToolsReportRenderResult.OutputLimitRefused>(result)
        assertEquals(3, result.exitCode)
    }

    private fun report(
        bytes: ByteArray,
        runtimes: List<String> = emptyList(),
        format: ToolOutputFormat = ToolOutputFormat.JSON,
        maxItems: Int = WASM_TOOLS_DEFAULT_MAX_ITEMS,
    ): WasmToolsReportRenderResult.Rendered = assertIs<WasmToolsReportRenderResult.Rendered>(
        validateAndExplain(bytes, input(bytes), "0.1", runtimes, format, maxItems),
    )

    private fun input(bytes: ByteArray) = WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong())

    private fun coreWithNestedBlocks(depth: Int): ByteArray {
        val body = ByteArray(depth * 3 + 2)
        for (index in 0 until depth) {
            body[1 + index * 2] = 0x02
            body[2 + index * 2] = 0x40
        }
        for (index in 0..depth) body[1 + depth * 2 + index] = 0x0b
        val code = byteArrayOf(1) + unsignedLeb128(body.size) + body
        return CORE_HEADER + byteArrayOf(1, 4, 1, 0x60, 0, 0, 3, 2, 1, 0, 0x0a) +
            unsignedLeb128(code.size) + code
    }

    private fun unsignedLeb128(value: Int): ByteArray {
        var remaining = value
        val bytes = ArrayList<Byte>(5)
        do {
            val low = remaining and 0x7f
            remaining = remaining ushr 7
            bytes.add((low or if (remaining == 0) 0 else 0x80).toByte())
        } while (remaining != 0)
        return bytes.toByteArray()
    }

    private companion object {
        val CORE_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
        val COMPONENT_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6d, 0x0d, 0, 1, 0)
        val MEMORY64_CORE = CORE_HEADER + byteArrayOf(5, 3, 1, 4, 1)
    }
}
