package org.jetbrains.hexana.cli.wasmtools

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.junit.Test
import java.nio.charset.StandardCharsets

class WasmToolsReportRendererTest {
    @Test
    fun `common envelope serializes every workflow and input kind`() {
        val expectedCommon = setOf("product", "report", "input", "completeness", "diagnostics", "findings", "truncation", "summary")
        val expectedContentType = "application/json; charset=utf-8"
        val workflows = listOf(
            WasmToolsWorkflow.VALIDATE_AND_EXPLAIN to "validate-and-explain",
            WasmToolsWorkflow.METADATA_AUDIT to "metadata-audit",
            WasmToolsWorkflow.COMPONENT_WIT_REPORT to "component-wit-report",
        )
        val inputs = listOf(
            Triple(WasmToolsReportInput(ReportInputKind.PATH, "sample.wasm", 4), "path", "sample.wasm"),
            Triple(WasmToolsReportInput(ReportInputKind.STDIN, null, 9), "stdin", null),
        )
        workflows.forEach { (workflow, expectedWorkflow) ->
            inputs.forEach { (input, expectedKind, expectedPath) ->
                val request = request(workflow = workflow, input = input)
                val json = rendered(WasmToolsReportRenderer.render(request))
                val pretty = rendered(WasmToolsReportRenderer.render(request.copy(format = ToolOutputFormat.PRETTY)))
                val report = json.semanticReport

                assertEquals(expectedCommon, report.keys)
                assertFalse("payload" in report)
                assertEquals("hexana-wasm-tools", report["product"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                assertEquals("0.1.0", report["product"]!!.jsonObject["version"]!!.jsonPrimitive.content)
                assertEquals(expectedWorkflow, report["report"]!!.jsonObject["workflow"]!!.jsonPrimitive.content)
                assertEquals(1, report["report"]!!.jsonObject["schema_version"]!!.jsonPrimitive.int)
                assertEquals(expectedKind, report["input"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
                assertEquals(expectedPath, report["input"]!!.jsonObject["path"]!!.jsonPrimitive.contentOrNull)
                assertEquals(input.byteCount, report["input"]!!.jsonObject["byte_count"]!!.jsonPrimitive.long)
                assertEquals("complete", report["completeness"]!!.jsonObject["status"]!!.jsonPrimitive.content)
                assertTrue(report["completeness"]!!.jsonObject["limitations"]!!.jsonArray.isEmpty())
                assertTrue(report["diagnostics"]!!.jsonArray.isEmpty())
                assertTrue(report["findings"]!!.jsonArray.isEmpty())
                assertFalse(report["truncation"]!!.jsonObject["truncated"]!!.jsonPrimitive.boolean)
                assertTrue(report["truncation"]!!.jsonObject["causes"]!!.jsonArray.isEmpty())
                assertOmitted(report, workflowItems = 0, findings = 0, diagnostics = 0)
                assertEquals(expectedContentType, WasmToolsReportRenderer.JSON_CONTENT_TYPE)
                assertEquals(expectedContentType, json.output.contentType)
                assertEquals(expectedContentType, pretty.output.contentType)
                assertEquals(Json.parseToJsonElement(json.output.text), Json.parseToJsonElement(pretty.output.text))
                assertOneNewline(json.output.text)
                assertOneNewline(pretty.output.text)
            }
        }
        assertFailsWith<IllegalArgumentException> { WasmToolsReportRenderer.render(request().copy(format = ToolOutputFormat.RAW)) }
        assertFailsWith<IllegalArgumentException> { WasmToolsReportRenderer.render(request().copy(format = ToolOutputFormat.TABLE)) }
    }

    @Test
    fun `diagnostic and finding wire shape preserves optional values`() {
        val report = rendered(WasmToolsReportRenderer.render(request(
            diagnostics = listOf(
                ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.SECTION_DECODE, "decode", "bad section", 12, 3),
                ReportDiagnostic(DiagnosticSeverity.WARNING, DiagnosticCategory.INPUT, "input", "no offset"),
            ),
            findings = listOf(ReportFinding(FindingSeverity.WARNING, FindingCategory.METADATA, "metadata", "bad metadata")),
        ))).semanticReport

        val diagnostic = report["diagnostics"]!!.jsonArray.first().jsonObject
        assertEquals(setOf("severity", "category", "code", "message", "byte_offset", "section_id"), diagnostic.keys)
        assertEquals(listOf("error", "section_decode", "decode", "bad section"), listOf(
            diagnostic["severity"]!!.jsonPrimitive.content, diagnostic["category"]!!.jsonPrimitive.content,
            diagnostic["code"]!!.jsonPrimitive.content, diagnostic["message"]!!.jsonPrimitive.content,
        ))
        assertEquals(12L, diagnostic["byte_offset"]!!.jsonPrimitive.long)
        assertEquals(3, diagnostic["section_id"]!!.jsonPrimitive.int)
        val nullDiagnostic = report["diagnostics"]!!.jsonArray.last().jsonObject
        assertTrue(nullDiagnostic["byte_offset"] is kotlinx.serialization.json.JsonNull)
        assertTrue(nullDiagnostic["section_id"] is kotlinx.serialization.json.JsonNull)
        val finding = report["findings"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("severity", "category", "code", "message", "byte_offset"), finding.keys)
        assertEquals(listOf("warning", "metadata", "metadata", "bad metadata"), listOf(
            finding["severity"]!!.jsonPrimitive.content, finding["category"]!!.jsonPrimitive.content,
            finding["code"]!!.jsonPrimitive.content, finding["message"]!!.jsonPrimitive.content,
        ))
        assertTrue(finding["byte_offset"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `public constructors reject invalid report contracts`() {
        val failures = listOf<() -> Unit>(
            { WasmToolsReportInput(ReportInputKind.PATH, null, 0) },
            { WasmToolsReportInput(ReportInputKind.STDIN, "stdin", 0) },
            { WasmToolsReportInput(ReportInputKind.STDIN, null, -1) },
            { ReportDiagnostic(DiagnosticSeverity.WARNING, DiagnosticCategory.INPUT, " ", "message") },
            { ReportDiagnostic(DiagnosticSeverity.WARNING, DiagnosticCategory.INPUT, "code", "\t") },
            { ReportDiagnostic(DiagnosticSeverity.WARNING, DiagnosticCategory.INPUT, "code", "message", -1) },
            { ReportFinding(FindingSeverity.INFO, FindingCategory.METADATA, " ", "message") },
            { ReportFinding(FindingSeverity.INFO, FindingCategory.METADATA, "code", "\n") },
            { ReportFinding(FindingSeverity.INFO, FindingCategory.METADATA, "code", "message", -1) },
            { WorkflowReportCollection("", emptyList()) { JsonObject(emptyMap()) } },
            { WorkflowReportBody(collections = listOf(arrayCollection("same", emptyList()), arrayCollection("same", emptyList()))) },
            { request().copy(productVersion = " ") },
            { request(limitations = listOf("\t")) },
            { request(maxItems = 0) },
            { request(maxOutputBytes = -1) },
            { request(findings = listOf(ReportFinding(FindingSeverity.WARNING, FindingCategory.STRUCTURAL_PARSE, "output_truncated", "caller supplied"))) },
        )

        failures.forEach { failure -> assertFailsWith<IllegalArgumentException>(block = failure) }
    }

    @Test
    fun `limitations only remain complete and exit zero`() {
        val limitation = "WIT reconstruction is not available."
        val result = rendered(WasmToolsReportRenderer.render(request(limitations = listOf(limitation))))
        val report = result.semanticReport

        assertEquals("complete", report["completeness"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(listOf(limitation), report["completeness"]!!.jsonObject["limitations"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(report["findings"]!!.jsonArray.isEmpty())
        assertFalse(report["truncation"]!!.jsonObject["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(report["truncation"]!!.jsonObject["causes"]!!.jsonArray.isEmpty())
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `default item limit bounds every workflow collection`() {
        val contractDefaultMaxItems = 500
        assertEquals(contractDefaultMaxItems, WASM_TOOLS_DEFAULT_MAX_ITEMS)
        val items = List(contractDefaultMaxItems + 1) { index ->
            item(index.toString().padStart(3, '0'), index.toString())
        }
        val result = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(arrayCollection("items", items))),
        )))

        assertEquals(contractDefaultMaxItems, values(result.semanticReport, "items").size)
        assertOmitted(result.semanticReport, workflowItems = 1, findings = 0, diagnostics = 0)
        assertEquals(
            listOf("max_items"),
            result.semanticReport["truncation"]!!.jsonObject["causes"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `info stays complete while warnings and errors are partial`() {
        assertEquals(0, rendered(WasmToolsReportRenderer.render(request(findings = listOf(finding(FindingSeverity.INFO))))).exitCode)
        assertEquals(1, rendered(WasmToolsReportRenderer.render(request(findings = listOf(finding(FindingSeverity.WARNING))))).exitCode)
        assertEquals(1, rendered(WasmToolsReportRenderer.render(request(findings = listOf(finding(FindingSeverity.ERROR))))).exitCode)
        assertEquals(1, rendered(WasmToolsReportRenderer.render(request(diagnostics = listOf(diagnostic(DiagnosticSeverity.WARNING))))).exitCode)
        assertEquals(1, rendered(WasmToolsReportRenderer.render(request(diagnostics = listOf(diagnostic(DiagnosticSeverity.ERROR))))).exitCode)
    }

    @Test
    fun `max items applies independently and reserves the final truncation finding`() {
        val first = listOf(item("a", "first"), item("sentinel", "sentinel"), item("z", "dropped"))
        val second = listOf(item("a", "second"), item("sentinel", "retained"), item("z", "dropped"))
        val result = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(arrayCollection("first_items", first), arrayCollection("second_items", second))),
            diagnostics = listOf(diagnostic(DiagnosticSeverity.WARNING, "a"), diagnostic(DiagnosticSeverity.WARNING, "sentinel"), diagnostic(DiagnosticSeverity.WARNING, "z")),
            findings = listOf(finding(FindingSeverity.INFO, "a"), finding(FindingSeverity.INFO, "sentinel"), finding(FindingSeverity.INFO, "z")),
            maxItems = 2,
        )))
        val report = result.semanticReport

        assertEquals(listOf("first", "sentinel"), values(report, "first_items"))
        assertEquals(listOf("second", "retained"), values(report, "second_items"))
        assertEquals(listOf("a", "sentinel"), report["diagnostics"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content })
        assertEquals(listOf("a", "output_truncated"), report["findings"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content })
        assertEquals(1, report["truncation"]!!.jsonObject["omitted"]!!.jsonObject["diagnostics"]!!.jsonPrimitive.int)
        assertEquals(2, report["truncation"]!!.jsonObject["omitted"]!!.jsonObject["findings"]!!.jsonPrimitive.int)
        assertEquals(2, report["truncation"]!!.jsonObject["omitted"]!!.jsonObject["workflow_items"]!!.jsonPrimitive.int)
        assertEquals(listOf("max_items"), report["truncation"]!!.jsonObject["causes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `byte trimming removes atomic workflow units before findings or diagnostics`() {
        val units = listOf(atomicType("a", "x".repeat(1_000)), atomicType("b", "y".repeat(1_000)))
        val full = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(componentTypes(units))),
            findings = listOf(finding(FindingSeverity.INFO, "ordinary")),
            diagnostics = listOf(diagnostic(DiagnosticSeverity.WARNING, "diagnostic")),
        )))
        val capped = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(componentTypes(units))),
            findings = listOf(finding(FindingSeverity.INFO, "ordinary")),
            diagnostics = listOf(diagnostic(DiagnosticSeverity.WARNING, "diagnostic")),
            maxOutputBytes = full.output.text.toByteArray(StandardCharsets.UTF_8).size.toLong() - 1,
        )))
        val report = capped.semanticReport
        val types = report["types"]!!.jsonObject

        assertEquals(listOf("type/a"), types["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("type/a"), types["nodes"]!!.jsonArray.map { it.jsonObject["parent"]!!.jsonPrimitive.content })
        assertEquals(listOf("ordinary", "output_truncated"), report["findings"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content })
        assertEquals(listOf("diagnostic"), report["diagnostics"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content })
        assertEquals(1, report["truncation"]!!.jsonObject["omitted"]!!.jsonObject["workflow_items"]!!.jsonPrimitive.int)
        assertEquals(listOf("max_output_bytes"), report["truncation"]!!.jsonObject["causes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, capped.exitCode)
        Json.parseToJsonElement(capped.output.text)
        assertOneNewline(capped.output.text)
    }

    @Test
    fun `byte limit removes a suffix from the first declared collection before later collections`() {
        val first = listOf(item("a", "a".repeat(1_000)), item("b", "b".repeat(1_000)))
        val second = listOf(item("a", "second-a"), item("b", "second-b"))
        val body = WorkflowReportBody(collections = listOf(arrayCollection("first", first), arrayCollection("second", second)))
        val full = rendered(WasmToolsReportRenderer.render(request(body = body)))
        val result = rendered(WasmToolsReportRenderer.render(request(
            body = body,
            maxOutputBytes = full.output.text.toByteArray(StandardCharsets.UTF_8).size.toLong() - 1,
        )))

        assertEquals(listOf("a".repeat(1_000)), values(result.semanticReport, "first"))
        assertEquals(listOf("second-a", "second-b"), values(result.semanticReport, "second"))
        assertOmitted(result.semanticReport, workflowItems = 1, findings = 0, diagnostics = 0)
        assertEquals(listOf("max_output_bytes"), result.semanticReport["truncation"]!!.jsonObject["causes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `successful byte trimming respects compact and pretty utf8 caps`() {
        listOf(ToolOutputFormat.JSON, ToolOutputFormat.PRETTY).forEach { format ->
            val body = WorkflowReportBody(collections = listOf(arrayCollection("items", listOf(
                item("a", "a".repeat(1_000)),
                item("b", "b".repeat(1_000)),
            ))))
            val full = rendered(WasmToolsReportRenderer.render(request(body = body).copy(format = format)))
            val cap = full.output.text.toByteArray(StandardCharsets.UTF_8).size.toLong() - 1
            val trimmed = rendered(WasmToolsReportRenderer.render(request(
                body = body,
                maxOutputBytes = cap,
            ).copy(format = format)))

            assertEquals(listOf("a".repeat(1_000)), values(trimmed.semanticReport, "items"))
            assertOmitted(trimmed.semanticReport, workflowItems = 1, findings = 0, diagnostics = 0)
            assertEquals(
                listOf("max_output_bytes"),
                trimmed.semanticReport["truncation"]!!.jsonObject["causes"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(1, findingCodes(trimmed.semanticReport).count { it == "output_truncated" })
            assertEquals(1, trimmed.exitCode)
            assertTrue(trimmed.output.text.toByteArray(StandardCharsets.UTF_8).size <= cap)
        }
    }

    @Test
    fun `workflow field collisions map to internal failure`() {
        fun assertInternalFailure(body: WorkflowReportBody) {
            val result = WasmToolsReportRenderer.render(request(body = body))
            assertEquals(WasmToolsReportRenderResult.InternalFailure, result)
            assertEquals(70, result.exitCode)
        }
        fun projection(fields: JsonObject) = WorkflowReportCollection("projection", emptyList()) { fields }

        assertInternalFailure(WorkflowReportBody(fields = buildJsonObject { put("payload", "forbidden") }))
        assertInternalFailure(WorkflowReportBody(fields = buildJsonObject { put("product", "forbidden") }))
        assertInternalFailure(WorkflowReportBody(collections = listOf(projection(buildJsonObject { put("payload", "forbidden") }))))
        assertInternalFailure(WorkflowReportBody(collections = listOf(projection(buildJsonObject { put("findings", "forbidden") }))))
        assertInternalFailure(WorkflowReportBody(
            fields = buildJsonObject { put("summary", "body") },
            collections = listOf(projection(buildJsonObject { put("summary", "projection") })),
        ))
        assertInternalFailure(WorkflowReportBody(collections = listOf(
            projection(buildJsonObject { put("summary", "first") }),
            WorkflowReportCollection("second", emptyList()) { buildJsonObject { put("summary", "second") } },
        )))
    }

    @Test
    fun `request snapshots mutable model inputs before rendering`() {
        val fieldBacking = linkedMapOf<String, JsonElement>("summary" to JsonPrimitive("original"))
        val nestedBacking = linkedMapOf<String, JsonElement>("value" to JsonPrimitive("original nested"))
        val arrayBacking = mutableListOf<JsonElement>(JsonPrimitive("original array"))
        val itemBacking = linkedMapOf<String, JsonElement>(
            "nested" to JsonObject(nestedBacking),
            "array" to JsonArray(arrayBacking),
        )
        val sourceItems = mutableListOf(WorkflowReportItem("original", JsonObject(itemBacking)))
        val sourceCollections = mutableListOf(arrayCollection("items", sourceItems))
        val sourceFindings = mutableListOf(finding(FindingSeverity.INFO, "original_finding"))
        val sourceLimitations = mutableListOf("original limitation")
        val request = request(
            body = WorkflowReportBody(JsonObject(fieldBacking), sourceCollections),
            findings = sourceFindings,
            limitations = sourceLimitations,
        )

        fieldBacking["summary"] = JsonPrimitive("mutated")
        fieldBacking["payload"] = JsonPrimitive("reserved mutation")
        nestedBacking["value"] = JsonPrimitive("mutated nested")
        arrayBacking[0] = JsonPrimitive("mutated array")
        itemBacking["new"] = JsonPrimitive("mutated item")
        sourceItems += item("mutated", "mutated")
        sourceCollections += arrayCollection("items", listOf(item("mutated", "mutated")))
        sourceFindings += finding(FindingSeverity.WARNING, "output_truncated", "reserved mutation")
        sourceLimitations += " "

        val result = rendered(WasmToolsReportRenderer.render(request))
        assertEquals("original", result.semanticReport["summary"]!!.jsonPrimitive.content)
        assertEquals(
            "original nested",
            result.semanticReport["items"]!!.jsonArray.single().jsonObject["nested"]!!.jsonObject["value"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "original array",
            result.semanticReport["items"]!!.jsonArray.single().jsonObject["array"]!!.jsonArray.single().jsonPrimitive.content,
        )
        assertEquals(listOf("original_finding"), findingCodes(result.semanticReport))
        assertEquals(listOf("original limitation"), result.semanticReport["completeness"]!!.jsonObject["limitations"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `projections receive immutable frozen retained prefixes`() {
        var attemptedMutation = false
        val collection = WorkflowReportCollection("items", listOf(item("a", "original"))) { retained ->
            attemptedMutation = true
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (retained as MutableList<WorkflowReportItem>).clear()
            }
            buildJsonObject { put("items", JsonArray(retained.map { it.value })) }
        }

        val result = rendered(WasmToolsReportRenderer.render(request(body = WorkflowReportBody(collections = listOf(collection)))))
        assertTrue(attemptedMutation)
        assertEquals(listOf("original"), values(result.semanticReport, "items"))
    }

    @Test
    fun `successful render paths project once`() {
        var projections = 0
        val collection = WorkflowReportCollection("items", listOf(item("a", "original"))) { retained ->
            projections++
            buildJsonObject { put("items", JsonArray(retained.map { it.value })) }
        }
        val base = request(body = WorkflowReportBody(collections = listOf(collection)))

        rendered(WasmToolsReportRenderer.render(base))
        rendered(WasmToolsReportRenderer.render(base.copy(maxOutputBytes = Long.MAX_VALUE)))

        assertEquals(2, projections)
    }

    @Test
    fun `item and byte limits accumulate workflow omissions`() {
        val items = listOf(item("a", "a".repeat(1_000)), item("b", "b".repeat(1_000)), item("c", "c".repeat(1_000)))
        val limitedByItems = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(arrayCollection("items", items))),
            maxItems = 2,
        )))
        val result = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(arrayCollection("items", items))),
            maxItems = 2,
            maxOutputBytes = limitedByItems.output.text.toByteArray(StandardCharsets.UTF_8).size.toLong() - 1,
        )))
        val truncation = result.semanticReport["truncation"]!!.jsonObject

        assertEquals(listOf("a".repeat(1_000)), values(result.semanticReport, "items"))
        assertEquals(2, truncation["omitted"]!!.jsonObject["workflow_items"]!!.jsonPrimitive.int)
        assertEquals(
            listOf("max_items", "max_output_bytes"),
            truncation["causes"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `byte limiting reserves the sentinel inside a bounded findings collection`() {
        val workflow = listOf(item("a", "a".repeat(1_000)), item("b", "b".repeat(1_000)))
        val full = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(arrayCollection("items", workflow))),
            findings = listOf(finding(FindingSeverity.INFO, "first"), finding(FindingSeverity.INFO, "second")),
            maxItems = 2,
        )))
        val result = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(arrayCollection("items", workflow))),
            findings = listOf(finding(FindingSeverity.INFO, "first"), finding(FindingSeverity.INFO, "second")),
            maxItems = 2,
            maxOutputBytes = full.output.text.toByteArray(StandardCharsets.UTF_8).size.toLong() - 1,
        )))
        val report = result.semanticReport

        assertEquals(listOf("first", "output_truncated"), findingCodes(report))
        assertEquals(1, report["truncation"]!!.jsonObject["omitted"]!!.jsonObject["findings"]!!.jsonPrimitive.int)
        assertEquals(1, report["truncation"]!!.jsonObject["omitted"]!!.jsonObject["workflow_items"]!!.jsonPrimitive.int)
        assertEquals(1, findingCodes(report).count { it == "output_truncated" })
        val sentinel = report["findings"]!!.jsonArray.last().jsonObject
        assertEquals("resource_limit", sentinel["category"]!!.jsonPrimitive.content)
        assertEquals("output_truncated", sentinel["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sentinel reservation is rechecked before byte trimming workflow items`() {
        val workflow = listOf(item("workflow", "small"))
        val findings = listOf(
            finding(FindingSeverity.INFO, "retained"),
            finding(FindingSeverity.INFO, "displaced", "x".repeat(4_096)),
        )
        val full = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(arrayCollection("items", workflow))),
            findings = findings,
            maxItems = 2,
        )))
        val result = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(arrayCollection("items", workflow))),
            findings = findings,
            maxItems = 2,
            maxOutputBytes = full.output.text.toByteArray(StandardCharsets.UTF_8).size.toLong() - 1,
        )))

        assertEquals(listOf("small"), values(result.semanticReport, "items"))
        assertEquals(listOf("retained", "output_truncated"), findingCodes(result.semanticReport))
        assertOmitted(result.semanticReport, workflowItems = 0, findings = 1, diagnostics = 0)
        assertEquals(listOf("max_output_bytes"), result.semanticReport["truncation"]!!.jsonObject["causes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `diagnostics sort by offset while findings retain caller order`() {
        val result = rendered(WasmToolsReportRenderer.render(request(
            diagnostics = listOf(
                ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.COMPONENT, "high", "high", byteOffset = 8),
                ReportDiagnostic(DiagnosticSeverity.WARNING, DiagnosticCategory.SECTION_SCAN, "low-first", "low", byteOffset = 3),
                ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.INPUT, "low-second", "low", byteOffset = 3),
                ReportDiagnostic(DiagnosticSeverity.WARNING, DiagnosticCategory.METADATA_DECODE, "null-first", "null"),
                ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.RESOURCE_LIMIT, "null-second", "null"),
            ),
            findings = listOf(
                finding(FindingSeverity.ERROR, "error-first"),
                finding(FindingSeverity.INFO, "info-second"),
                finding(FindingSeverity.WARNING, "warning-third"),
            ),
        )))

        assertEquals(
            listOf("low-first", "low-second", "high", "null-first", "null-second"),
            result.semanticReport["diagnostics"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content },
        )
        assertEquals(listOf("error-first", "info-second", "warning-third"), findingCodes(result.semanticReport))
    }

    @Test
    fun `code point order controls workflow keys fields and limitations`() {
        val privateUse = "\uE000"
        val supplementary = String(Character.toChars(0x10000))
        val result = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(
                fields = buildJsonObject { put(supplementary, "supplementary"); put(privateUse, "private") },
                collections = listOf(arrayCollection("items", listOf(item(supplementary, "supplementary"), item(privateUse, "private")))),
            ),
            limitations = listOf(supplementary, privateUse),
            maxItems = 1,
        )))

        assertEquals(listOf("private"), values(result.semanticReport, "items"))
        assertEquals(listOf(privateUse, supplementary), result.semanticReport.keys.toList().takeLast(2))
        assertEquals(listOf(privateUse, supplementary), result.semanticReport["completeness"]!!.jsonObject["limitations"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `equal workflow order keys preserve encounter order`() {
        val result = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(arrayCollection("items", listOf(item("same", "first"), item("same", "second"))))),
            maxItems = 1,
        )))

        assertEquals(listOf("first"), values(result.semanticReport, "items"))
    }

    @Test
    fun `byte trimming progresses through workflow findings and diagnostics`() {
        val workflow = listOf(item("a", "a".repeat(4_096)), item("b", "b".repeat(4_096)))
        val findings = listOf(finding(FindingSeverity.INFO, "first", "f".repeat(4_096)), finding(FindingSeverity.INFO, "second", "g".repeat(4_096)))
        val diagnostics = listOf(diagnostic(DiagnosticSeverity.WARNING, "first", "d".repeat(4_096)), diagnostic(DiagnosticSeverity.ERROR, "second", "e".repeat(4_096)))
        fun renderAt(maxOutputBytes: Long) = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(arrayCollection("items", workflow))),
            findings = findings,
            diagnostics = diagnostics,
            maxOutputBytes = maxOutputBytes,
        )))

        val afterWorkflow = renderAt(18_000)
        val afterFindings = renderAt(10_000)
        val afterDiagnostics = renderAt(1_000)

        assertOmitted(afterWorkflow.semanticReport, workflowItems = 2, findings = 0, diagnostics = 0)
        assertOmitted(afterFindings.semanticReport, workflowItems = 2, findings = 2, diagnostics = 0)
        assertOmitted(afterDiagnostics.semanticReport, workflowItems = 2, findings = 2, diagnostics = 2)
        listOf(afterWorkflow, afterFindings, afterDiagnostics).forEach { Json.parseToJsonElement(it.output.text) }
    }

    @Test
    fun `byte trimming probes a bounded number of retained workflow prefixes`() {
        var projectionCalls = 0
        val collection = WorkflowReportCollection(
            id = "items",
            items = List(500) { index -> item(index.toString().padStart(3, '0'), "x".repeat(10_000)) },
        ) { retained ->
            projectionCalls++
            buildJsonObject { put("items", JsonArray(retained.map { it.value })) }
        }

        val result = rendered(WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(collection)),
            maxOutputBytes = 1_500,
        )))

        assertTrue(result.output.text.toByteArray(StandardCharsets.UTF_8).size <= 1_500)
        assertTrue(projectionCalls <= 16, "expected bounded retained-prefix probes, got $projectionCalls")
    }

    @Test
    fun `output limit counts utf8 bytes rather than characters`() {
        val request = request(body = WorkflowReportBody(fields = buildJsonObject { put("summary", "é") }))
        val rendered = rendered(WasmToolsReportRenderer.render(request))
        val byteCount = rendered.output.text.toByteArray(StandardCharsets.UTF_8).size.toLong()

        assertIs<WasmToolsReportRenderResult.Rendered>(WasmToolsReportRenderer.render(request.copy(maxOutputBytes = byteCount)))
        assertEquals(
            WasmToolsReportRenderResult.OutputLimitRefused,
            WasmToolsReportRenderer.render(request.copy(maxOutputBytes = rendered.output.text.length.toLong())),
        )
    }

    @Test
    fun `too small limit emits no report and uses resource refusal exit`() {
        val result = WasmToolsReportRenderer.render(request(maxOutputBytes = 1))

        assertEquals(WasmToolsReportRenderResult.OutputLimitRefused, result)
        assertEquals(3, result.exitCode)
    }

    @Test
    fun `projection failure maps to internal failure exit`() {
        val throwingCollection = WorkflowReportCollection("throwing", emptyList()) {
            error("projection failed")
        }

        val result = WasmToolsReportRenderer.render(request(
            body = WorkflowReportBody(collections = listOf(throwingCollection)),
        ))

        assertEquals(WasmToolsReportRenderResult.InternalFailure, result)
        assertEquals(70, result.exitCode)
    }

    private fun request(
        body: WorkflowReportBody = WorkflowReportBody(fields = buildJsonObject { put("summary", "ok") }),
        workflow: WasmToolsWorkflow = WasmToolsWorkflow.VALIDATE_AND_EXPLAIN,
        input: WasmToolsReportInput = WasmToolsReportInput(ReportInputKind.PATH, "sample.wasm", 4),
        diagnostics: List<ReportDiagnostic> = emptyList(),
        findings: List<ReportFinding> = emptyList(),
        limitations: List<String> = emptyList(),
        maxItems: Int = WASM_TOOLS_DEFAULT_MAX_ITEMS,
        maxOutputBytes: Long? = null,
    ) = WasmToolsReportRequest(
        productVersion = "0.1.0",
        workflow = workflow,
        input = input,
        body = body,
        diagnostics = diagnostics,
        findings = findings,
        limitations = limitations,
        maxItems = maxItems,
        maxOutputBytes = maxOutputBytes,
    )

    private fun finding(severity: FindingSeverity, code: String = "finding", message: String = "message $code") =
        ReportFinding(severity, FindingCategory.STRUCTURAL_PARSE, code, message)

    private fun diagnostic(severity: DiagnosticSeverity, code: String = "diagnostic", message: String = "message $code") =
        ReportDiagnostic(severity, DiagnosticCategory.SECTION_SCAN, code, message)

    private fun item(key: String, value: String) = WorkflowReportItem(key, buildJsonObject { put("value", value) })

    private fun arrayCollection(name: String, items: List<WorkflowReportItem>) = WorkflowReportCollection(name, items) { retained ->
        buildJsonObject { put(name, JsonArray(retained.map { it.value })) }
    }

    private fun atomicType(key: String, padding: String) = WorkflowReportItem(key, buildJsonObject {
        put("type", buildJsonObject { put("id", "type/$key"); put("padding", padding) })
        put("node", buildJsonObject { put("parent", "type/$key"); put("padding", padding) })
    })

    private fun componentTypes(items: List<WorkflowReportItem>) = WorkflowReportCollection("types", items) { retained ->
        buildJsonObject {
            put("types", buildJsonObject {
                put("items", JsonArray(retained.map { it.value["type"]!! }))
                put("nodes", JsonArray(retained.map { it.value["node"]!! }))
            })
        }
    }

    private fun values(report: JsonObject, name: String): List<String> =
        report[name]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content }

    private fun findingCodes(report: JsonObject): List<String> =
        report["findings"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }

    private fun assertOmitted(report: JsonObject, workflowItems: Int, findings: Int, diagnostics: Int) {
        val omitted = report["truncation"]!!.jsonObject["omitted"]!!.jsonObject
        assertEquals(workflowItems, omitted["workflow_items"]!!.jsonPrimitive.int)
        assertEquals(findings, omitted["findings"]!!.jsonPrimitive.int)
        assertEquals(diagnostics, omitted["diagnostics"]!!.jsonPrimitive.int)
    }

    private fun rendered(result: WasmToolsReportRenderResult): WasmToolsReportRenderResult.Rendered =
        assertIs<WasmToolsReportRenderResult.Rendered>(result)

    private fun assertOneNewline(text: String) {
        assertTrue(text.endsWith("\n"))
        assertFalse(text.endsWith("\n\n"))
    }
}
