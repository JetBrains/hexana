package org.jetbrains.hexana.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserWorkerPresentationProtocolTest {
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
            base.copy(summary = base.summary.copy(inputBytes = -1)),
            base.copy(summary = base.summary.copy(elapsedMs = Double.NaN)),
            base.copy(ranges = listOf(ParserWorkerRange(8, 1, "outside"))),
            base.copy(ranges = listOf(ParserWorkerRange(0, 0, "empty"))),
            base.copy(ranges = listOf(ParserWorkerRange(0, 1, "x".repeat(257)))),
            base.copy(tables = List(MAX_PARSER_WORKER_TABLES + 1) { ParserWorkerTable("$it", emptyList()) }),
            base.copy(tables = listOf(ParserWorkerTable("x".repeat(65), emptyList()))),
            base.copy(tables = listOf(ParserWorkerTable("table", listOf(ParserWorkerRecord("x".repeat(1_025), "value"))))),
            base.copy(tables = listOf(ParserWorkerTable("table", listOf(ParserWorkerRecord("key", "x".repeat(16_385)))))),
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
        assertWireRejected { it.presentation.tables = js("Array(33).fill({id: 'table', records: []})") }
        assertWireRejected { it.presentation.tables[0].id = "x".repeat(65) }
        assertWireRejected { it.presentation.tables[0].records[0].key = "x".repeat(1_025) }
        assertWireRejected { it.presentation.tables[0].records[0].value = "x".repeat(16_385) }
        assertWireRejected { it.presentation.ranges[0].label = "x".repeat(257) }
        assertWireRejected(withDiagnostics = true) { it.presentation.diagnostics[0].code = "x".repeat(65) }
        assertWireRejected(withDiagnostics = true) { it.presentation.diagnostics[0].message = "x".repeat(1_025) }
    }

    @Test
    fun decoderRejectsInvalidNumbersRangesVersionsAndPartialMatrices() {
        assertWireRejected { it.protocolVersion = 3 }
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
