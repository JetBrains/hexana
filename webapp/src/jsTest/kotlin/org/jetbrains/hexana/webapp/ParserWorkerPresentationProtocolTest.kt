package org.jetbrains.hexana.webapp

import org.jetbrains.hexana.wasm.components.ComponentSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserWorkerPresentationProtocolTest {
    @Test
    fun componentFunctionSortNeverCreatesAnInstanceEdgeFromTheSameIndex() {
        assertNull(componentGraphSourceId(ComponentSort.Func, 0))
        assertEquals("instance:0", componentGraphSourceId(ComponentSort.Instance, 0))
    }

    @Test
    fun representativePresentationRoundTripsWithoutTruncation() {
        val wire = encodeBoundedParserWorkerSuccess("request", representativePresentation(55))
        val decoded = assertNotNull(decodeParserWorkerSuccess(wire, "request"))

        assertEquals(55, decoded.presentation.summary.inputBytes)
        assertEquals(false, decoded.partial.partial)
        assertEquals(2, decoded.partial.totalCount)
        assertEquals(1, decoded.presentation.tables.single().records.size)
    }

    @Test
    fun componentGraphRoundTripsAndRejectsDanglingEdges() {
        val source = componentPresentation()
        val wire = encodeBoundedParserWorkerSuccess("request", source)
        val decoded = assertNotNull(decodeParserWorkerSuccess(wire, "request"))

        assertEquals("wasm_component", decoded.presentation.format)
        assertEquals(2, decoded.presentation.componentGraph?.nodes?.size)
        assertEquals(1, decoded.presentation.componentGraph?.edges?.size)

        wire.presentation.componentGraph.edges[0].toId = "missing"
        assertNull(decodeParserWorkerSuccess(wire, "request"))
    }

    @Test
    fun legacyTabsRoundTripAndRejectsMalformedMetadata() {
        val source = representativePresentation().copy(legacyTabs = listOf("Top", "Wat", "WAT (virtualized)"))
        val wire = encodeBoundedParserWorkerSuccess("request", source)
        val decoded = assertNotNull(decodeParserWorkerSuccess(wire, "request"))

        assertEquals(source.legacyTabs, decoded.presentation.legacyTabs)

        wire.presentation.legacyTabs = arrayOf("Components")
        assertNull(decodeParserWorkerSuccess(wire, "request"))

        wire.presentation.legacyTabs = js("undefined")
        assertNull(decodeParserWorkerSuccess(wire, "request"))
    }

    @Test
    fun toolbarAndGraalSummaryRoundTrip() {
        val source = representativePresentation().copy(
            summary = representativePresentation().summary.copy(
                graalWebImage = true,
                graalWebImageMatchedImportModules = listOf("compat", "interop"),
            ),
            toolbar = ParserWorkerToolbar("_start", listOf("BulkMemoryOperations")),
        )

        val decoded = assertNotNull(decodeParserWorkerSuccess(encodeBoundedParserWorkerSuccess("request", source), "request"))

        assertEquals(listOf("compat", "interop"), decoded.presentation.summary.graalWebImageMatchedImportModules)
        assertEquals("_start", decoded.presentation.toolbar.runnableExportName)
        assertEquals(listOf("BulkMemoryOperations"), decoded.presentation.toolbar.proposalIds)
    }

    @Test
    fun nativeImageToolbarAndNumericSortValuesRoundTrip() {
        val source = representativePresentation().copy(
            format = "elf",
            capabilities = listOf("summary", "structural_ranges", "tables"),
            toolbar = ParserWorkerToolbar(
                nativeImage = ParserWorkerNativeImage(
                    version = "GraalVM 25",
                    edition = "community",
                    platform = "LINUX_AMD64",
                    hasSbom = true,
                ),
            ),
            tables = listOf(
                ParserWorkerTable(
                    "Top",
                    listOf(ParserWorkerRecord(".text", "64 B", listOf(".text", "64 B"), numericValues = listOf(null, 64.0))),
                    listOf("Type", "Size"),
                ),
            ),
        )

        val decoded = assertNotNull(decodeParserWorkerSuccess(encodeBoundedParserWorkerSuccess("request", source), "request"))

        assertEquals("GraalVM 25", decoded.presentation.toolbar.nativeImage?.version)
        assertTrue(decoded.presentation.toolbar.nativeImage?.hasSbom == true)
        assertEquals(64.0, decoded.presentation.tables.single().records.single().numericValues[1])
    }

    @Test
    fun recursiveRecordsCountTowardBoundsAndRejectMalformedNestedWireData() {
        val children = (0 until MAX_PARSER_WORKER_RECORDS_PER_COLLECTION).map { index ->
            ParserWorkerRecord("child-$index", "value-$index")
        }
        val source = presentation(listOf(ParserWorkerRecord("parent", "root", children = children)))
        val bounded = boundParserWorkerSuccess("request", source)

        assertEquals(listOf("record_limit"), bounded.partial.reasons)
        assertEquals(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION, bounded.partial.emittedCount)
        assertEquals(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION + 1, bounded.partial.totalCountLowerBound)
        assertEquals(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION - 1, bounded.presentation.tables.single().records.single().children.size)

        val wire = encodeSuccessWire("request", bounded.presentation, bounded.partial)
        assertNotNull(decodeParserWorkerSuccess(wire, "request"))
        wire.presentation.tables[0].records[0].children[0].numericValues = arrayOf(1)
        assertNull(decodeParserWorkerSuccess(wire, "request"))
    }

    @Test
    fun actionMetadataRoundTripsAndRejectsAnUnknownNestedAction() {
        val action = ParserWorkerAction(
            kind = "zip_entry",
            name = "classes.dex",
            dataOffset = 0,
            compressedSize = 1,
            uncompressedSize = 1,
            compressionMethod = 0,
        )
        val source = representativePresentation(10).copy(
            format = "zip",
            capabilities = listOf("summary", "structural_ranges", "tables"),
            diagnostics = emptyList(),
            tables = listOf(
                ParserWorkerTable(
                    "Entries",
                    listOf(ParserWorkerRecord("classes.dex", "1 B", actions = listOf(action))),
                ),
            ),
        )
        val wire = encodeBoundedParserWorkerSuccess("request", source)
        assertEquals("zip_entry", assertNotNull(decodeParserWorkerSuccess(wire, "request")).presentation.tables.single().records.single().actions.single().kind)

        wire.presentation.tables[0].records[0].actions[0].kind = "unknown"
        assertNull(decodeParserWorkerSuccess(wire, "request"))
    }

    @Test
    fun oversizedParserDerivedStringsBecomeAnExplicitPartialInsteadOfAParseFailure() {
        val source = representativePresentation().copy(
            summary = representativePresentation().summary.copy(
                graalWebImage = true,
                graalWebImageMatchedImportModules = listOf("x".repeat(65)),
            ),
            ranges = listOf(ParserWorkerRange(0, 1, "x".repeat(257))),
            tables = listOf(ParserWorkerTable("records", listOf(ParserWorkerRecord("x".repeat(1_025), "value")))),
            diagnostics = listOf(ParserWorkerDiagnostic("code", "x".repeat(1_025))),
            componentGraph = ParserWorkerComponentGraph(
                nodes = listOf(ParserWorkerComponentGraphNode("module:0", "module", "x".repeat(1_025))),
                edges = listOf(ParserWorkerComponentGraphEdge("module:0", "module:0")),
            ),
        )

        val sanitized = sanitizeParserWorkerPresentation(source)
        val bounded = boundParserWorkerSuccess("request", sanitized.presentation, omittedRecordCount = sanitized.omittedRecordCount)
        val decoded = assertNotNull(
            decodeParserWorkerSuccess(
                encodeBoundedParserWorkerSuccess("request", sanitized.presentation, sanitized.omittedRecordCount),
                "request",
            ),
        )

        assertEquals(6, sanitized.omittedRecordCount)
        assertTrue(bounded.partial.partial)
        assertEquals(listOf("content_limit"), bounded.partial.reasons)
        assertEquals(0, bounded.partial.emittedCount)
        assertEquals(6, bounded.partial.totalCount)
        assertTrue(decoded.partial.partial)
    }

    @Test
    fun sanitizerRemapsTableFilterRowsAfterDroppingAnInvalidRootRecord() {
        val source = representativePresentation().copy(
            tables = listOf(
                ParserWorkerTable(
                    id = "records",
                    records = listOf(
                        ParserWorkerRecord("x".repeat(1_025), "invalid"),
                        ParserWorkerRecord("matching", "value"),
                        ParserWorkerRecord("nonmatching", "value"),
                    ),
                    filters = listOf(ParserWorkerTableFilter("Matching", listOf(1))),
                ),
            ),
        )

        val sanitized = sanitizeParserWorkerPresentation(source)

        assertEquals(listOf("matching", "nonmatching"), sanitized.presentation.tables.single().records.map { it.key })
        assertEquals(listOf(0), sanitized.presentation.tables.single().filters.single().matchingRootRows)
    }

    @Test
    fun componentGraphRecordLimitDropsOnlyEdgesWhoseNodesWereTruncated() {
        val nodes = (0..10_000).map { index ->
            ParserWorkerComponentGraphNode("node:$index", "module", "module $index")
        }
        val edges = (0 until 10_000).map { index ->
            ParserWorkerComponentGraphEdge("node:$index", "node:${index + 1}")
        }
        val bounded = boundParserWorkerSuccess(
            "request",
            componentPresentation().copy(componentGraph = ParserWorkerComponentGraph(nodes, edges)),
        )

        assertEquals(listOf("record_limit"), bounded.partial.reasons)
        assertEquals(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION, bounded.presentation.componentGraph?.nodes?.size)
        assertEquals(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION - 1, bounded.presentation.componentGraph?.edges?.size)
        assertNotNull(decodeBounded(bounded))
    }

    @Test
    fun recordLimitPreservesTheExactTenThousandBoundaryAndTruncatesOneOver() {
        val exact = boundParserWorkerSuccess("request", presentation((0 until 10_000).map(::record)))
        assertEquals(false, exact.partial.partial)
        assertEquals(10_000, exact.partial.emittedCount)
        assertEquals(10_000, exact.partial.totalCount)
        assertEquals(null, exact.partial.totalCountLowerBound)

        val oneOver = boundParserWorkerSuccess("request", presentation((0..10_000).map(::record)))
        assertTrue(oneOver.partial.partial)
        assertEquals(listOf("record_limit"), oneOver.partial.reasons)
        assertEquals(10_000, oneOver.partial.emittedCount)
        assertEquals(null, oneOver.partial.totalCount)
        assertEquals(10_001, oneOver.partial.totalCountLowerBound)
        assertEquals("0", oneOver.presentation.tables.single().records.first().key)
        assertEquals("9999", oneOver.presentation.tables.single().records.last().key)
    }

    @Test
    fun byteLimitKeepsPrefixAndNeverEmitsMoreThanTenMiB() {
        val source = presentation((0 until MAX_PARSER_WORKER_RECORDS_PER_COLLECTION).map {
            ParserWorkerRecord(it.toString(), "x".repeat(2_000))
        })
        val bounded = boundParserWorkerSuccess("request", source)
        val wire = encodeBoundedParserWorkerSuccess("request", source)

        assertTrue(bounded.partial.partial)
        assertEquals(listOf("byte_limit"), bounded.partial.reasons)
        assertTrue(bounded.partial.emittedCount < MAX_PARSER_WORKER_RECORDS_PER_COLLECTION)
        assertEquals(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION, bounded.partial.totalCount)
        assertEquals(null, bounded.partial.totalCountLowerBound)
        assertTrue(utf8WireBytes(wire) <= MAX_PARSER_WORKER_WIRE_BYTES)
        assertEquals("0", bounded.presentation.tables.single().records.first().key)
        assertNotNull(decodeParserWorkerSuccess(wire, "request"))
    }

    @Test
    fun byteLimitedTableFiltersOnlyRetainIndicesForEmittedRootRecords() {
        val records = listOf(
            ParserWorkerRecord("0", "first"),
            ParserWorkerRecord("1", "x".repeat(10_000)),
        )
        val source = presentation(records).copy(
            tables = listOf(
                ParserWorkerTable(
                    id = "records",
                    records = records,
                    filters = listOf(ParserWorkerTableFilter("All records", listOf(0, 1))),
                ),
            ),
        )
        val firstRecord = source.copy(tables = source.tables.map { table ->
            table.copy(
                records = table.records.take(1),
                filters = listOf(ParserWorkerTableFilter("All records", listOf(0))),
            )
        })
        val maxWireBytes = boundParserWorkerSuccess("request", firstRecord, Int.MAX_VALUE).wireBytes + 100

        val bounded = boundParserWorkerSuccess("request", source, maxWireBytes)

        assertEquals(1, bounded.presentation.tables.single().records.size)
        assertEquals(listOf(0), bounded.presentation.tables.single().filters.single().matchingRootRows)
        assertNotNull(decodeBounded(bounded))
    }

    @Test
    fun multibyteRecordUsesTheExactAndOneByteOverBoundaries() {
        val source = presentation(listOf(ParserWorkerRecord("0", "😀".repeat(64))))
        val unrestricted = boundParserWorkerSuccess("request", source, Int.MAX_VALUE)
        val atLimit = boundParserWorkerSuccess("request", source, unrestricted.wireBytes)
        val oneByteOver = boundParserWorkerSuccess("request", source, unrestricted.wireBytes - 1)

        assertEquals(false, atLimit.partial.partial)
        assertEquals(unrestricted.wireBytes, atLimit.wireBytes)
        assertEquals(true, oneByteOver.partial.partial)
        assertEquals(listOf("byte_limit"), oneByteOver.partial.reasons)
        assertEquals(0, oneByteOver.partial.emittedCount)
        assertTrue(oneByteOver.wireBytes <= unrestricted.wireBytes - 1)
        assertNotNull(decodeBounded(oneByteOver))
    }

    @Test
    fun byteLimitedSingleRecordCanEmitAValidZeroRecordPartial() {
        val source = presentation(listOf(ParserWorkerRecord("0", "x".repeat(128))))
        val full = boundParserWorkerSuccess("request", source, Int.MAX_VALUE)
        val zero = boundParserWorkerSuccess("request", source, full.wireBytes - 1)

        assertEquals(listOf("byte_limit"), zero.partial.reasons)
        assertEquals(0, zero.partial.emittedCount)
        assertEquals(1, zero.partial.totalCount)
        assertEquals(null, zero.partial.totalCountLowerBound)
        assertNotNull(decodeBounded(zero))
    }

    @Test
    fun recordAndByteLimitsUseTheirSharedPartialMetadataMatrix() {
        val source = presentation((0..10_000).map(::record))
        val recordOnly = boundParserWorkerSuccess("request", source)
        val combined = boundParserWorkerSuccess("request", source, recordOnly.wireBytes - 1)

        assertEquals(listOf("record_limit", "byte_limit"), combined.partial.reasons)
        assertTrue(combined.partial.emittedCount < MAX_PARSER_WORKER_RECORDS_PER_COLLECTION)
        assertEquals(null, combined.partial.totalCount)
        assertEquals(10_001, combined.partial.totalCountLowerBound)
        assertNotNull(decodeBounded(combined))
    }

    @Test
    fun rangesAndDiagnosticsUseTheSameDeterministicRecordLimit() {
        val source = representativePresentation().copy(
            summary = representativePresentation().summary.copy(inputBytes = 10_001),
            ranges = (0..10_000).map { ParserWorkerRange(it, 1, "range-$it") },
            diagnostics = (0..10_000).map { ParserWorkerDiagnostic("D", "diagnostic-$it") },
        )
        val bounded = boundParserWorkerSuccess("request", source)

        assertEquals(listOf("record_limit"), bounded.partial.reasons)
        assertEquals(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION, bounded.presentation.ranges.size)
        assertEquals(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION, bounded.presentation.diagnostics.size)
        assertEquals(20_001, bounded.partial.emittedCount)
        assertEquals(20_003, bounded.partial.totalCountLowerBound)
        assertNotNull(decodeBounded(bounded))
    }

    @Test
    fun producerRejectsSchemaThatTheDecoderWouldReject() {
        val base = representativePresentation()
        val invalidPresentations = listOf(
            base.copy(format = "x".repeat(65)),
            base.copy(capabilities = listOf("summary", "unknown")),
            base.copy(capabilities = listOf("summary", "summary")),
            base.copy(legacyTabs = List(MAX_PARSER_WORKER_LEGACY_TABS + 1) { "Top" }),
            base.copy(legacyTabs = listOf("Unknown")),
            base.copy(legacyTabs = listOf("Top", "Top")),
            base.copy(summary = base.summary.copy(inputBytes = -1)),
            base.copy(summary = base.summary.copy(elapsedMs = Double.NaN)),
            base.copy(ranges = listOf(ParserWorkerRange(8, 1, "outside"))),
            base.copy(ranges = listOf(ParserWorkerRange(0, 0, "empty"))),
            base.copy(ranges = listOf(ParserWorkerRange(0, 1, "x".repeat(257)))),
            base.copy(tables = List(MAX_PARSER_WORKER_TABLES + 1) { ParserWorkerTable("$it", emptyList()) }),
            base.copy(tables = listOf(ParserWorkerTable("x".repeat(65), emptyList()))),
            base.copy(tables = listOf(ParserWorkerTable("", emptyList()))),
            base.copy(tables = listOf(ParserWorkerTable("table", emptyList(), emptyList()))),
            base.copy(
                tables = listOf(
                    ParserWorkerTable("table", listOf(ParserWorkerRecord("key", "value", listOf("one")))),
                ),
            ),
            base.copy(
                tables = listOf(
                    ParserWorkerTable("table", listOf(ParserWorkerRecord("key", "value", numericValues = listOf(1.0)))),
                ),
            ),
            base.copy(
                tables = listOf(
                    ParserWorkerTable("table", listOf(ParserWorkerRecord("key", "value", offset = 0))),
                ),
            ),
            base.copy(tables = listOf(ParserWorkerTable("table", emptyList()), ParserWorkerTable("table", emptyList()))),
            base.copy(tables = listOf(ParserWorkerTable("table", listOf(ParserWorkerRecord("x".repeat(1_025), "value"))))),
            base.copy(
                tables = listOf(
                    ParserWorkerTable("table", listOf(ParserWorkerRecord("key", "x".repeat(16_385)))),
                ),
            ),
            base.copy(diagnostics = listOf(ParserWorkerDiagnostic("x".repeat(65), "message"))),
            base.copy(diagnostics = listOf(ParserWorkerDiagnostic("code", "x".repeat(1_025)))),
        )

        invalidPresentations.forEach { presentation ->
            assertFailsWith<IllegalArgumentException> { encodeBoundedParserWorkerSuccess("request", presentation) }
        }
    }

    @Test
    fun producerRejectsAnOversizedRequestId() {
        assertFailsWith<IllegalArgumentException> {
            encodeBoundedParserWorkerSuccess(
                "x".repeat(MAX_PARSER_WORKER_REQUEST_ID_CHARS + 1),
                representativePresentation(),
            )
        }
    }

    @Test
    fun decoderRejectsCapabilityAndEveryVariableStringOverflow() {
        assertWireRejected { it.presentation.format = "x".repeat(65) }
        assertWireRejected { it.presentation.capabilities = js("Array(33).fill('summary')") }
        assertWireRejected { it.presentation.capabilities = arrayOf("summary", "unknown") }
        assertWireRejected { it.presentation.capabilities = arrayOf("summary", "x".repeat(65)) }
        assertWireRejected { it.presentation.capabilities = arrayOf("summary", "summary") }
        assertWireRejected { it.presentation.legacyTabs = arrayOf("Components") }
        assertWireRejected { it.presentation.legacyTabs = arrayOf("Top", "Top") }
        assertWireRejected { it.presentation.legacyTabs = arrayOf("x".repeat(65)) }
        assertWireRejected { it.presentation.tables = js("Array(33).fill({id: 'table', records: []})") }
        assertWireRejected { it.presentation.tables[0].id = "x".repeat(65) }
        assertWireRejected { it.presentation.tables[0].records[0].key = "x".repeat(1_025) }
        assertWireRejected { it.presentation.tables[0].records[0].value = "x".repeat(16_385) }
        assertWireRejected { it.presentation.tables[0].records[0].numericValues = arrayOf(1) }
        assertWireRejected { it.presentation.ranges[0].label = "x".repeat(257) }
        assertWireRejected(withDiagnostics = true) { it.presentation.diagnostics[0].code = "x".repeat(65) }
        assertWireRejected(withDiagnostics = true) { it.presentation.diagnostics[0].message = "x".repeat(1_025) }
    }

    @Test
    fun decoderRejectsInvalidNumbersRangesVersionsAndPartialMatrices() {
        assertWireRejected { it.protocolVersion = 2 }
        assertWireRejected { it.presentation.summary.sectionCount = -1 }
        assertWireRejected { it.presentation.summary.sectionCount = js("NaN") }
        assertWireRejected { it.presentation.summary.elapsedMs = js("Infinity") }
        assertWireRejected { it.presentation.ranges[0].offset = -1 }
        assertWireRejected { it.presentation.ranges[0].size = 0 }
        assertWireRejected { it.presentation.ranges[0].offset = 8; it.presentation.ranges[0].size = 1 }
        assertWireRejected { it.presentation.tables[0].records = js("Array(10001).fill({key: 'x', value: 'x'})") }
        assertWireRejected { it.partial.emittedCount = -1 }
        assertWireRejected { it.partial.totalCount = null }
        assertWireRejected { it.partial.totalCountLowerBound = 3 }
        assertWireRejected { it.partial.totalCount = -1 }
        assertWireRejected { it.partial.totalCount = js("Infinity") }
        assertWireRejected { it.partial.partial = true }
        assertWireRejected { it.partial.partial = true; it.partial.reasons = arrayOf("byte_limit") }
        assertWireRejected {
            it.partial.partial = true
            it.partial.reasons = arrayOf("byte_limit")
            it.partial.totalCount = 3
            it.partial.totalCountLowerBound = 3
        }
        assertWireRejected {
            it.partial.partial = true
            it.partial.reasons = arrayOf("record_limit")
            it.partial.totalCount = null
            it.partial.totalCountLowerBound = null
        }
        assertWireRejected { it.partial.partial = true; it.partial.reasons = arrayOf("record_limit"); it.partial.totalCount = null; it.partial.totalCountLowerBound = 2 }
        assertWireRejected {
            it.partial.partial = true
            it.partial.reasons = arrayOf("record_limit", "byte_limit")
            it.partial.totalCount = 3
            it.partial.totalCountLowerBound = 3
        }
        assertWireRejected { it.partial.partial = true; it.partial.reasons = arrayOf("byte_limit", "record_limit") }
        assertWireRejected { it.partial.partial = true; it.partial.reasons = arrayOf("byte_limit", "byte_limit") }
        assertWireRejected { it.partial.partial = true; it.partial.reasons = js("Array(3).fill('byte_limit')") }
        assertWireRejected { it.partial.partial = true; it.partial.reasons = arrayOf("unknown") }
    }

    private fun record(index: Int): ParserWorkerRecord = ParserWorkerRecord(index.toString(), "record-$index")

    private fun presentation(records: List<ParserWorkerRecord>): ParserWorkerPresentation = representativePresentation().copy(
        ranges = emptyList(),
        tables = listOf(ParserWorkerTable("records", records)),
        diagnostics = emptyList(),
    )

    private fun componentPresentation(): ParserWorkerPresentation = representativePresentation(10).copy(
        format = "wasm_component",
        capabilities = listOf("summary", "structural_ranges", "tables", "component_dependency_graph"),
        diagnostics = emptyList(),
        componentGraph = ParserWorkerComponentGraph(
            nodes = listOf(
                ParserWorkerComponentGraphNode("module:0", "module", "core module 0"),
                ParserWorkerComponentGraphNode("export:0", "export", "run"),
            ),
            edges = listOf(ParserWorkerComponentGraphEdge("module:0", "export:0")),
        ),
    )

    private fun decodeBounded(bounded: BoundedParserWorkerSuccess): BoundedParserWorkerSuccess? =
        decodeParserWorkerSuccess(encodeSuccessWire("request", bounded.presentation, bounded.partial), "request")

    private fun assertWireRejected(
        withDiagnostics: Boolean = false,
        mutate: (dynamic) -> Unit,
    ) {
        val source = if (withDiagnostics) {
            representativePresentation().copy(diagnostics = listOf(ParserWorkerDiagnostic("code", "message")))
        } else {
            representativePresentation()
        }
        val wire = encodeBoundedParserWorkerSuccess("request", source)
        mutate(wire)
        assertNull(decodeParserWorkerSuccess(wire, "request"))
    }
}
