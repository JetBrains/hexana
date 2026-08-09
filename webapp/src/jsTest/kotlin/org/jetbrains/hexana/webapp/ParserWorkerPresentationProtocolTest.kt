package org.jetbrains.hexana.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParserWorkerPresentationProtocolTest {
    @Test
    fun representativePresentationRoundTripsWithoutTruncation() {
        val wire = encodeBoundedParserWorkerSuccess("request", representativePresentation(55))
        val decoded = assertNotNull(decodeParserWorkerSuccess(wire, "request"))

        assertEquals(55, decoded.presentation.summary.inputBytes)
        assertEquals(false, decoded.partial.partial)
        assertEquals(1, decoded.presentation.tables.single().records.size)
    }

    @Test
    fun recordLimitKeepsExactlyTenThousandDeterministicRecordsAndMarksLowerBound() {
        val records = (0..10_000).map { ParserWorkerRecord(it.toString(), "record-$it") }
        val bounded = boundParserWorkerSuccess("request", presentation(records))

        assertTrue(bounded.partial.partial)
        assertEquals(listOf("record_limit"), bounded.partial.reasons)
        assertEquals(10_000, bounded.partial.emittedCount)
        assertEquals(null, bounded.partial.totalCount)
        assertEquals(10_001, bounded.partial.totalCountLowerBound)
        assertEquals("0", bounded.presentation.tables.single().records.first().key)
        assertEquals("9999", bounded.presentation.tables.single().records.last().key)
    }

    @Test
    fun byteLimitKeepsPrefixAndNeverEmitsMoreThanTenMiB() {
        val records = (0 until MAX_PARSER_WORKER_RECORDS_PER_COLLECTION).map {
            ParserWorkerRecord(it.toString(), "x".repeat(2_000))
        }
        val bounded = boundParserWorkerSuccess("request", presentation(records))
        val wire = encodeBoundedParserWorkerSuccess("request", presentation(records))

        assertTrue(bounded.partial.partial)
        assertTrue("byte_limit" in bounded.partial.reasons)
        assertTrue(bounded.partial.emittedCount < MAX_PARSER_WORKER_RECORDS_PER_COLLECTION)
        assertTrue(utf8WireBytes(wire) <= MAX_PARSER_WORKER_WIRE_BYTES)
        assertEquals("0", bounded.presentation.tables.single().records.first().key)
    }

    @Test
    fun byteLimitTruncatesAtTheExactOneByteBoundary() {
        val source = presentation(listOf(ParserWorkerRecord("0", "first"), ParserWorkerRecord("1", "second")))
        val unrestricted = boundParserWorkerSuccess("request", source, Int.MAX_VALUE)
        val atLimit = boundParserWorkerSuccess("request", source, unrestricted.wireBytes)
        val oneByteOver = boundParserWorkerSuccess("request", source, unrestricted.wireBytes - 1)

        assertEquals(false, atLimit.partial.partial)
        assertEquals(unrestricted.wireBytes, atLimit.wireBytes)
        assertTrue(oneByteOver.partial.partial)
        assertEquals(listOf("byte_limit"), oneByteOver.partial.reasons)
        assertTrue(oneByteOver.wireBytes <= unrestricted.wireBytes - 1)
        assertEquals(1, oneByteOver.presentation.tables.single().records.size)
    }

    @Test
    fun rangesAndDiagnosticsAlsoRespectCollectionAndByteLimits() {
        val ranges = (0..10_000).map { ParserWorkerRange(it, 1, "range-$it") }
        val diagnostics = (0..10_000).map { ParserWorkerDiagnostic("D", "diagnostic-$it") }
        val bounded = boundParserWorkerSuccess(
            "request",
            representativePresentation().copy(ranges = ranges, diagnostics = diagnostics),
        )

        assertTrue(bounded.partial.partial)
        assertEquals(listOf("record_limit"), bounded.partial.reasons)
        assertEquals(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION, bounded.presentation.ranges.size)
        assertEquals(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION, bounded.presentation.diagnostics.size)
        assertEquals(20_003, bounded.partial.totalCountLowerBound)
    }

    @Test
    fun utf8AccountingCountsMultibyteCharactersAndDecoderRejectsOverLimit() {
        val ascii = js("({text: 'a'})")
        val multibyte = js("({text: '😀'})")
        assertTrue(utf8WireBytes(multibyte) > utf8WireBytes(ascii))

        val wire = encodeBoundedParserWorkerSuccess("request", representativePresentation())
        wire.padding = "x".repeat(MAX_PARSER_WORKER_WIRE_BYTES)
        assertEquals(null, decodeParserWorkerSuccess(wire, "request"))
    }

    @Test
    fun decoderRejectsInvalidPartialMetadataAndCollectionOverflow() {
        val wire = encodeBoundedParserWorkerSuccess("request", representativePresentation())
        wire.partial.partial = true
        assertEquals(null, decodeParserWorkerSuccess(wire, "request"))

        val overflow = encodeBoundedParserWorkerSuccess("request", representativePresentation())
        overflow.presentation.tables[0].records = js("Array(10001).fill({key: 'x', value: 'x'})")
        assertEquals(null, decodeParserWorkerSuccess(overflow, "request"))
    }

    private fun presentation(records: List<ParserWorkerRecord>): ParserWorkerPresentation = representativePresentation().copy(
        ranges = emptyList(),
        tables = listOf(ParserWorkerTable("records", records)),
    )
}
