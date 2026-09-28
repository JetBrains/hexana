package org.jetbrains.hexana.cli.wasmtools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.model.metadata.MetadataStatus
import org.jetbrains.hexana.mcp.query.MetadataAuditQueryResult
import org.jetbrains.hexana.mcp.query.readMetadataAudit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MetadataAuditWorkflowTest {
    @Test
    fun `absent metadata and core feature evidence are complete`() {
        val result = render(coreWasm())
        val report = result.semanticReport

        assertEquals(0, result.exitCode)
        assertEquals("metadata-audit", report.obj("report").string("workflow"))
        assertEquals("complete", report.obj("completeness").string("status"))
        assertTrue(report.array("custom_sections").isEmpty())
        assertEquals("absent", report.obj("typed_metadata").obj("producers").string("status"))
        assertEquals("absent", report.obj("typed_metadata").obj("target_features").string("status"))
        assertEquals("completed", report.obj("enabled_features").string("stage"))
        assertEquals(false, report.obj("enabled_features").obj("features")["simd"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `inventory preview and typed occurrences preserve encoded order and duplicates`() {
        val version = "v".repeat(140)
        val bytes = coreWasm(
            customSection("zeta", "short".encodeToByteArray()),
            customSection("producers", producersPayload(
                "processed-by" to listOf("tool" to version, "tool" to "2"),
                "processed-by" to listOf("tool" to "3"),
            )),
            customSection("target_features", targetFeaturesPayload("+simd", "-threads", "+simd")),
            customSection("producers", producersPayload("language" to listOf("Kotlin" to "2.4"))),
            customSection("target_features", targetFeaturesPayload("+gc")),
        )
        val report = render(bytes).semanticReport
        val inventory = report.array("custom_sections").map { it.jsonObject }
        val typed = report.obj("typed_metadata")
        val producerSections = typed.obj("producers").array("sections").map { it.jsonObject }
        val featureSections = typed.obj("target_features").array("sections").map { it.jsonObject }

        assertEquals(listOf("zeta", "producers", "target_features", "producers", "target_features"),
            inventory.map { it.string("name") })
        assertEquals(inventory.map { it["offset_bytes"] }, inventory.map { it["offset_bytes"] }.sortedBy { it!!.jsonPrimitive.int })
        assertEquals("decoded", typed.obj("producers").string("status"))
        assertEquals("decoded", typed.obj("target_features").string("status"))
        assertEquals(listOf(0, 1), producerSections.map { it["occurrence"]!!.jsonPrimitive.int })
        assertEquals(listOf(0, 1), featureSections.map { it["occurrence"]!!.jsonPrimitive.int })
        assertEquals(listOf("processed-by", "processed-by"), producerSections.first().array("fields").map { it.jsonObject.string("field") })
        assertEquals(listOf(0, 1), producerSections.first().array("fields").map { it.jsonObject["field_index"]!!.jsonPrimitive.int })
        assertEquals(listOf(version, "2"), producerSections.first().array("fields").first().jsonObject.array("values")
            .map { it.jsonObject.string("version") })
        assertEquals(listOf("simd", "threads", "simd"), featureSections.first().array("features")
            .map { it.jsonObject.string("name") })
        assertEquals(listOf("enabled", "disabled", "enabled"), featureSections.first().array("features")
            .map { it.jsonObject.string("state") })
        val producerPreview = inventory[1].obj("preview")
        assertEquals(96, producerPreview["captured_bytes"]!!.jsonPrimitive.int)
        assertTrue(producerPreview["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("binary", producerPreview.string("encoding"))
        assertEquals(version, producerSections.first().array("fields").first().jsonObject.array("values").first().jsonObject.string("version"))
        assertEquals("utf8", inventory.first().obj("preview").string("encoding"))
        assertEquals("short", inventory.first().obj("preview").string("value"))
        assertEquals(customSection("zeta", "short".encodeToByteArray()).size,
            inventory.first()["size_bytes"]!!.jsonPrimitive.int)
        assertEquals(5, inventory.first().obj("preview")["captured_bytes"]!!.jsonPrimitive.int)
        assertEquals(false, inventory.first().obj("preview")["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `preview distinguishes invalid and incomplete utf8 suffixes`() {
        val invalid = render(coreWasm(customSection("note", byteArrayOf('a'.code.toByte(), 0xc2.toByte()))))
            .semanticReport.array("custom_sections").single().jsonObject.obj("preview")
        val incompleteAtCap = render(coreWasm(customSection("note", ByteArray(95) { 'a'.code.toByte() } +
            byteArrayOf(0xc2.toByte(), 0xa2.toByte()))))
            .semanticReport.array("custom_sections").single().jsonObject.obj("preview")
        val invalidAtCap = render(coreWasm(customSection("note", ByteArray(95) { 'a'.code.toByte() } +
            byteArrayOf(0xff.toByte(), 'b'.code.toByte()))))
            .semanticReport.array("custom_sections").single().jsonObject.obj("preview")
        val invalidExactCap = render(coreWasm(customSection("note", ByteArray(95) { 'a'.code.toByte() } +
            byteArrayOf(0xc2.toByte()))))
            .semanticReport.array("custom_sections").single().jsonObject.obj("preview")
        val fourBytePrefixes = listOf(
            byteArrayOf(0xf0.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x80.toByte(), 0x90.toByte(), 0x80.toByte()),
        ).map { suffix ->
            render(coreWasm(customSection("note", ByteArray(93) { 'a'.code.toByte() } + suffix)))
                .semanticReport.array("custom_sections").single().jsonObject.obj("preview")
        }
        val invalidFourBytePrefixes = listOf(
            byteArrayOf(0xf0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
        ).map { suffix ->
            render(coreWasm(customSection("note", ByteArray(93) { 'a'.code.toByte() } + suffix)))
                .semanticReport.array("custom_sections").single().jsonObject.obj("preview")
        }

        assertEquals("binary", invalid.string("encoding"))
        assertEquals("0x61 0xc2", invalid.string("value"))
        assertEquals(2, invalid["captured_bytes"]!!.jsonPrimitive.int)
        assertEquals(false, invalid["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("utf8", incompleteAtCap.string("encoding"))
        assertEquals("a".repeat(95), incompleteAtCap.string("value"))
        assertEquals(96, incompleteAtCap["captured_bytes"]!!.jsonPrimitive.int)
        assertEquals(true, incompleteAtCap["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("binary", invalidAtCap.string("encoding"))
        assertTrue(invalidAtCap.string("value").endsWith("0xff"))
        assertEquals(true, invalidAtCap["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("binary", invalidExactCap.string("encoding"))
        assertTrue(invalidExactCap.string("value").endsWith("0xc2"))
        assertEquals(96, invalidExactCap["captured_bytes"]!!.jsonPrimitive.int)
        assertEquals(false, invalidExactCap["truncated"]!!.jsonPrimitive.boolean)
        fourBytePrefixes.forEach { preview ->
            assertEquals("utf8", preview.string("encoding"))
            assertEquals("a".repeat(93), preview.string("value"))
            assertEquals(true, preview["truncated"]!!.jsonPrimitive.boolean)
        }
        invalidFourBytePrefixes.forEach { preview ->
            assertEquals("binary", preview.string("encoding"))
            assertEquals(true, preview["truncated"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `malformed occurrence keeps decodable metadata and inventory`() {
        val bytes = coreWasm(
            customSection("producers", producersPayload("language" to listOf("Rust" to "1"))),
            customSection("producers", byteArrayOf(1, 10, 'x'.code.toByte())),
            customSection("target_features", targetFeaturesPayload("+simd")),
        )
        val result = render(bytes)
        val report = result.semanticReport
        val producers = report.obj("typed_metadata").obj("producers")

        assertEquals(1, result.exitCode)
        assertEquals("partial", report.obj("completeness").string("status"))
        assertEquals("partial", producers.string("status"))
        assertEquals(listOf("decoded", "malformed"), producers.array("sections").map { it.jsonObject.string("status") })
        assertEquals("Rust", producers.array("sections").first().jsonObject.array("fields").first().jsonObject
            .array("values").first().jsonObject.string("name"))
        assertEquals(3, report.array("custom_sections").size)
        assertTrue(report.array("diagnostics").any { it.jsonObject.string("category") == "metadata_decode" })
        assertTrue(report.array("findings").any { it.jsonObject.string("code") == "malformed_producers" })
    }

    @Test
    fun `all malformed typed occurrences report malformed and exit one`() {
        val result = render(coreWasm(customSection("target_features", byteArrayOf(1, '?'.code.toByte(), 3, 'x'.code.toByte()))))
        val report = result.semanticReport

        assertEquals(1, result.exitCode)
        assertEquals("malformed", report.obj("typed_metadata").obj("target_features").string("status"))
        assertEquals("absent", report.obj("typed_metadata").obj("producers").string("status"))
        assertTrue(report.array("diagnostics").any {
            it.jsonObject.string("category") == "metadata_decode" &&
                it.jsonObject.string("code") == "malformed_target_features"
        })
        assertTrue(report.array("findings").any { it.jsonObject.string("code") == "malformed_target_features" })
    }

    @Test
    fun `section scan failure retains preceding inventory and exits one`() {
        val bytes = coreWasm(customSection("note", "kept".encodeToByteArray()), byteArrayOf(0))
        val result = render(bytes)

        assertEquals(1, result.exitCode)
        assertEquals(listOf("note"), result.semanticReport.array("custom_sections").map { it.jsonObject.string("name") })
        assertEquals("partial", result.semanticReport.obj("completeness").string("status"))
        assertTrue(result.semanticReport.array("diagnostics").any { it.jsonObject.string("category") == "section_scan" })
        assertEquals(1, result.semanticReport.array("diagnostics").size)
    }

    @Test
    fun `section scan failures are bounded before report rendering`() {
        val malformedName = byteArrayOf(0, 5, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10)
        val bytes = coreWasm(*Array(502) { malformedName })
        val evidence = assertIs<MetadataAuditQueryResult.Identified>(readMetadataAudit(bytes))
        val result = assertIs<WasmToolsReportRenderResult.Rendered>(MetadataAuditWorkflow.run(
            bytes, WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong()), "test",
            maxOutputBytes = null,
        ))
        val report = result.semanticReport

        assertEquals(500, evidence.sectionScanFailures.size)
        assertEquals(2, evidence.omittedSectionScanFailures)
        assertEquals(500, evidence.unresolvedSections.size)
        assertEquals(2, evidence.omittedUnresolvedSections)
        assertTrue(evidence.parseProblems.isEmpty())
        assertEquals(8L, evidence.sectionScanFailures.first().byteOffset)
        assertEquals(8L + 499 * malformedName.size, evidence.sectionScanFailures.last().byteOffset)
        assertEquals(1, result.exitCode)
        assertTrue(report.array("diagnostics").size <= 500)
        assertEquals(504, report.obj("truncation").obj("omitted")["diagnostics"]!!.jsonPrimitive.int)
        assertTrue(report.obj("truncation").array("causes").any { it.jsonPrimitive.content == "max_items" })
    }

    @Test
    fun `unknown core and component sections make the audit partial`() {
        listOf(CORE_HEADER, COMPONENT_HEADER).forEach { header ->
            val result = render(header + byteArrayOf(0x40, 0x00))
            val report = result.semanticReport

            assertEquals(1, result.exitCode)
            assertEquals("partial", report.obj("completeness").string("status"))
            assertTrue(report.array("diagnostics").any {
                it.jsonObject.string("code") == "unresolved_section" &&
                    it.jsonObject["section_id"]?.jsonPrimitive?.int == 0x40
            })
        }
    }

    @Test
    fun `unresolved diagnostic omissions are counted before rendering`() {
        val bytes = coreWasm(*Array(502) { byteArrayOf(0x40, 0x00) })
        val evidence = assertIs<MetadataAuditQueryResult.Identified>(readMetadataAudit(bytes))
        val result = assertIs<WasmToolsReportRenderResult.Rendered>(MetadataAuditWorkflow.run(
            bytes, WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong()), "test",
            maxOutputBytes = null,
        ))
        val report = result.semanticReport

        assertEquals(500, evidence.unresolvedSections.size)
        assertEquals(2, evidence.omittedUnresolvedSections)
        assertEquals(2, report.obj("truncation").obj("omitted")["diagnostics"]!!.jsonPrimitive.int)
        assertEquals("partial", report.obj("completeness").string("status"))
        assertEquals(listOf("max_items"), report.obj("truncation").array("causes").map { it.jsonPrimitive.content })
    }

    @Test
    fun `saturated provider problems disclose unknown additional omissions`() {
        val unknownEntries = ByteArray(1_001) { 0xff.toByte() }
        val bytes = COMPONENT_HEADER + byteArrayOf(0x02, 0xeb.toByte(), 0x07, 0xe9.toByte(), 0x07) + unknownEntries
        val result = assertIs<WasmToolsReportRenderResult.Rendered>(MetadataAuditWorkflow.run(
            bytes, WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong()), "test",
            maxOutputBytes = null,
        ))
        val report = result.semanticReport

        assertEquals(1, result.exitCode)
        assertTrue(report.obj("completeness").array("limitations")
            .any { it.jsonPrimitive.content == "parse_problem_limit_reached" })
        assertTrue(report.array("diagnostics").any { it.jsonObject.string("code") == "parse_problem_limit_reached" })
        assertEquals(501, report.obj("truncation").obj("omitted")["diagnostics"]!!.jsonPrimitive.int)
    }

    @Test
    fun `malformed core section makes feature evidence partial`() {
        val malformedTypeSection = byteArrayOf(1, 5, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x0f)
        val result = render(coreWasm(customSection("note", "kept".encodeToByteArray()), malformedTypeSection))
        val report = result.semanticReport

        assertEquals(1, result.exitCode)
        assertEquals("partial", report.obj("completeness").string("status"))
        assertEquals("completed", report.obj("enabled_features").string("stage"))
        assertEquals(listOf("note"), report.array("custom_sections").map { it.jsonObject.string("name") })
        assertTrue(report.array("diagnostics").any {
            it.jsonObject.string("category") == "section_decode" &&
                it.jsonObject.string("code") == "unknown_type_byte"
        })
        assertEquals(1, report.array("diagnostics").size)
    }

    @Test
    fun `truncated core code vector retains a partial report`() {
        val result = render(coreWasm(byteArrayOf(0x0a, 0x01, 0x80.toByte())))
        val report = result.semanticReport

        assertEquals(1, result.exitCode)
        assertEquals("partial", report.obj("completeness").string("status"))
        assertEquals("partial", report.obj("enabled_features").string("stage"))
        assertEquals(JsonNull, report.obj("enabled_features")["features"])
        assertTrue(report.array("diagnostics").any {
            it.jsonObject.string("code") == "enabled_features_incomplete"
        })
    }

    @Test
    fun `component inventory and typed metadata have no core feature claim`() {
        val bytes = COMPONENT_HEADER + customSection("target_features", targetFeaturesPayload("+gc"))
        val result = render(bytes)
        val report = result.semanticReport

        assertEquals(0, result.exitCode)
        assertEquals("decoded", report.obj("typed_metadata").obj("target_features").string("status"))
        assertEquals("gc", report.obj("typed_metadata").obj("target_features").array("sections").first().jsonObject
            .array("features").first().jsonObject.string("name"))
        assertEquals("not_applicable", report.obj("enabled_features").string("stage"))
        assertEquals(JsonNull, report.obj("enabled_features")["features"])
        assertEquals(JsonNull, report.obj("enabled_features")["sources"])
        assertEquals(1, report.array("custom_sections").size)
    }

    @Test
    fun `malformed component section reports decode problem and exits one`() {
        val bytes = COMPONENT_HEADER + byteArrayOf(0x02, 0x02, 0x01, 0xff.toByte())
        val result = render(bytes)
        val report = result.semanticReport

        assertEquals(1, result.exitCode)
        assertEquals("partial", report.obj("completeness").string("status"))
        assertEquals("not_applicable", report.obj("enabled_features").string("stage"))
        assertTrue(report.array("diagnostics").any {
            it.jsonObject.string("category") == "section_decode" &&
                it.jsonObject.string("code") == "unknown_core_instance_tag" &&
                it.jsonObject["byte_offset"]?.jsonPrimitive?.int == 11
        })
    }

    @Test
    fun `default byte limit trims metadata occurrences atomically`() {
        val occurrence = customSection("producers", producersPayload(
            "language" to listOf("tool" to "v".repeat(700)),
        ))
        val result = render(coreWasm(*Array(120) { occurrence }))
        val report = result.semanticReport
        val retained = report.array("custom_sections").size

        assertEquals(1, result.exitCode)
        assertTrue(result.output.text.encodeToByteArray().size <= HexanaAnalysisLimits.MAX_OUTPUT_BYTES)
        assertTrue(retained in 1 until 120)
        assertEquals(retained, report.obj("typed_metadata").obj("producers").array("sections").size)
        assertEquals(120 - retained, report.obj("truncation").obj("omitted")["workflow_items"]!!.jsonPrimitive.int)
    }

    @Test
    fun `byte trimming makes both typed metadata statuses partial`() {
        val producers = customSection("producers", producersPayload("language" to listOf("Rust" to "1")))
        val features = customSection("target_features", targetFeaturesPayload("+simd"))
        val sections = Array(500) { index -> if (index % 2 == 0) producers else features }
        val bytes = coreWasm(*sections)
        val evidence = assertIs<MetadataAuditQueryResult.Identified>(readMetadataAudit(bytes))
        val result = render(bytes)
        val report = result.semanticReport
        val typed = report.obj("typed_metadata")

        assertEquals(MetadataStatus.DECODED, evidence.typedMetadata.producers.status)
        assertEquals(MetadataStatus.DECODED, evidence.typedMetadata.targetFeatures.status)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.text.encodeToByteArray().size <= HexanaAnalysisLimits.MAX_OUTPUT_BYTES)
        assertTrue(typed.obj("producers").array("sections").size < 250)
        assertTrue(typed.obj("target_features").array("sections").size < 250)
        assertEquals("partial", typed.obj("producers").string("status"))
        assertEquals("partial", typed.obj("target_features").string("status"))
        assertEquals(listOf("max_output_bytes"), report.obj("truncation").array("causes")
            .map { it.jsonPrimitive.content })
    }

    @Test
    fun `oversized typed metadata is not decoded before report limits`() {
        val featureCount = 40_000
        val entries = ByteArray(featureCount * 3) { index ->
            when (index % 3) {
                0 -> '+'.code.toByte()
                1 -> 1
                else -> 'x'.code.toByte()
            }
        }
        val prefix = customSection("target_features", targetFeaturesPayload("+simd"))
        val oversized = customSection("target_features", unsigned(featureCount) + entries)
        val bytes = coreWasm(
            prefix,
            oversized,
            customSection("target_features", targetFeaturesPayload("+gc")),
            customSection("producers", producersPayload("language" to listOf("Rust" to "1"))),
        )
        val evidence = assertIs<MetadataAuditQueryResult.Identified>(readMetadataAudit(bytes))
        val result = render(bytes)
        val report = result.semanticReport

        assertEquals(2, evidence.typedMetadataBudget?.omittedSections)
        assertEquals((CORE_HEADER.size + prefix.size).toLong(), evidence.typedMetadataBudget?.firstOmittedOffset)
        assertEquals(1, evidence.typedMetadata.targetFeatures.sections.size)
        assertEquals(1, result.exitCode)
        assertEquals("partial", report.obj("completeness").string("status"))
        assertEquals(4, report.array("custom_sections").size)
        assertEquals("partial", report.obj("typed_metadata").obj("target_features").string("status"))
        assertEquals(1, report.obj("typed_metadata").obj("target_features").array("sections").size)
        assertEquals("simd", report.obj("typed_metadata").obj("target_features").array("sections")
            .first().jsonObject.array("features").first().jsonObject.string("name"))
        assertEquals("decoded", report.obj("typed_metadata").obj("producers").string("status"))
        assertEquals(1, report.obj("typed_metadata").obj("producers").array("sections").size)
        assertTrue(report.array("diagnostics").any {
            it.jsonObject.string("category") == "resource_limit" &&
                it.jsonObject.string("code") == "typed_metadata_budget_exceeded"
        })
        assertTrue(report.array("findings").any { it.jsonObject.string("code") == "typed_metadata_budget_exceeded" })
    }

    @Test
    fun `hard inventory limit retains first five hundred typed occurrences`() {
        val occurrence = customSection("producers", producersPayload("language" to listOf("Rust" to "1")))
        val bytes = coreWasm(*Array(501) { occurrence })
        val evidence = assertIs<MetadataAuditQueryResult.Identified>(readMetadataAudit(bytes, Int.MAX_VALUE))
        val result = assertIs<WasmToolsReportRenderResult.Rendered>(MetadataAuditWorkflow.run(
            bytes, WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong()), "test",
            maxItems = Int.MAX_VALUE, maxOutputBytes = null,
        ))
        val report = result.semanticReport
        val omittedOffset = (CORE_HEADER.size + 500 * occurrence.size).toLong()

        assertEquals(500, evidence.customSections.size)
        assertEquals(1, evidence.omittedCustomSections)
        assertEquals(500, evidence.typedMetadata.producers.sections.size)
        assertEquals(499, evidence.typedMetadata.producers.sections.last().occurrence)
        assertEquals(MetadataStatus.PARTIAL, evidence.typedMetadata.producers.status)
        assertEquals(1, evidence.typedMetadataBudget?.omittedSections)
        assertEquals(omittedOffset, evidence.typedMetadataBudget?.firstOmittedOffset)
        assertEquals(1, result.exitCode)
        assertEquals("partial", report.obj("typed_metadata").obj("producers").string("status"))
        assertEquals(1, report.obj("truncation").obj("omitted")["workflow_items"]!!.jsonPrimitive.int)
        assertTrue(report.obj("truncation").array("causes").any { it.jsonPrimitive.content == "max_items" })
        assertTrue(report.array("diagnostics").any {
            it.jsonObject.string("code") == "typed_metadata_budget_exceeded" &&
                it.jsonObject["byte_offset"]?.jsonPrimitive?.long == omittedOffset
        })
        assertTrue(report.array("findings").any { it.jsonObject.string("code") == "typed_metadata_budget_exceeded" })
    }

    @Test
    fun `high cardinality inventory is limited before preview rendering`() {
        val occurrence = customSection("note", byteArrayOf(1))
        val bytes = coreWasm(*Array(2_000) { occurrence })
        val evidence = assertIs<MetadataAuditQueryResult.Identified>(readMetadataAudit(bytes))
        val result = render(bytes)
        val report = result.semanticReport
        val retained = report.array("custom_sections").size

        assertEquals(500, evidence.customSections.size)
        assertEquals(1_500, evidence.omittedCustomSections)
        assertEquals((CORE_HEADER.size + 499 * occurrence.size).toLong(), evidence.customSections.last().offsetBytes)
        assertEquals(1, result.exitCode)
        assertTrue(retained <= 500)
        assertEquals(2_000 - retained, report.obj("truncation").obj("omitted")["workflow_items"]!!.jsonPrimitive.int)
        assertTrue(report.obj("truncation")["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `item limit removes custom section and its typed occurrence together`() {
        val bytes = coreWasm(
            customSection("producers", producersPayload("language" to listOf("A" to "1"))),
            customSection("target_features", targetFeaturesPayload("+gc")),
        )
        val result = render(bytes, maxItems = 1)
        val report = result.semanticReport

        assertEquals(1, result.exitCode)
        assertEquals(listOf("producers"), report.array("custom_sections").map { it.jsonObject.string("name") })
        assertEquals(1, report.obj("typed_metadata").obj("producers").array("sections").size)
        assertTrue(report.obj("typed_metadata").obj("target_features").array("sections").isEmpty())
        assertEquals("partial", report.obj("typed_metadata").obj("target_features").string("status"))
        assertTrue(report.array("diagnostics").any {
            it.jsonObject.string("code") == "typed_metadata_budget_exceeded"
        })
        assertEquals(1, report.obj("truncation").obj("omitted")["workflow_items"]!!.jsonPrimitive.int)
        assertEquals("output_truncated", report.array("findings").last().jsonObject.string("code"))
    }

    @Test
    fun `nonpositive item limit remains a caller error`() {
        val bytes = coreWasm()
        val input = WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong())

        listOf(0, -1).forEach { maxItems ->
            assertFailsWith<IllegalArgumentException> {
                MetadataAuditWorkflow.run(bytes, input, "test", maxItems = maxItems)
            }
        }
    }

    @Test
    fun `input byte count must match analyzed bytes`() {
        val bytes = coreWasm()

        assertFailsWith<IllegalArgumentException> {
            MetadataAuditWorkflow.run(
                bytes, WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong() + 1), "test",
            )
        }
    }

    @Test
    fun `unidentified input exits three without a report`() {
        listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(1, 0x61, 0x73, 0x6d, 1, 0, 0, 0),
            byteArrayOf(0, 0x61, 0x73, 0x6d, 2, 0, 0, 0),
        ).forEach { bytes ->
            val result = MetadataAuditWorkflow.run(
                bytes, WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong()), "test",
            )
            assertIs<WasmToolsReportRenderResult.InputRefused>(result)
            assertEquals(3, result.exitCode)
        }
    }

    private fun render(bytes: ByteArray, maxItems: Int = 500): WasmToolsReportRenderResult.Rendered =
        assertIs<WasmToolsReportRenderResult.Rendered>(MetadataAuditWorkflow.run(
            bytes, WasmToolsReportInput(ReportInputKind.STDIN, null, bytes.size.toLong()), "test",
            ToolOutputFormat.JSON, maxItems,
        ))

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun coreWasm(vararg sections: ByteArray): ByteArray = CORE_HEADER + sections.fold(ByteArray(0), ByteArray::plus)
    private fun customSection(name: String, content: ByteArray): ByteArray =
        byteArrayOf(0) + unsigned(encodeString(name).size + content.size) + encodeString(name) + content

    private fun producersPayload(vararg fields: Pair<String, List<Pair<String, String>>>): ByteArray =
        vector(fields.map { (field, values) ->
            encodeString(field) + vector(values.map { (name, version) -> encodeString(name) + encodeString(version) })
        })

    private fun targetFeaturesPayload(vararg features: String): ByteArray = vector(features.map { feature ->
        byteArrayOf(feature.first().code.toByte()) + encodeString(feature.drop(1))
    })

    private fun encodeString(value: String): ByteArray = value.encodeToByteArray().let { unsigned(it.size) + it }
    private fun vector(items: List<ByteArray>): ByteArray = unsigned(items.size) + items.fold(ByteArray(0), ByteArray::plus)

    private fun unsigned(value: Int): ByteArray {
        var remaining = value
        val result = mutableListOf<Byte>()
        do {
            var next = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            result += next.toByte()
        } while (remaining != 0)
        return result.toByteArray()
    }

    private companion object {
        val CORE_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
        val COMPONENT_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6d, 0x0d, 0, 1, 0)
    }
}
