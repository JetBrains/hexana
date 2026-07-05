package org.jetbrains.hexana.arrow

import org.jetbrains.hexana.io.ByteProvenanceKind
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-provenance tests for temporal, interval, and decimal types in [decodeRecordBatch].
 *
 * Fixtures from apache/arrow-testing (Apache License 2.0):
 *   generated_datetime.stream  — Date32/64, Time32/64, Timestamp (7 rows, 15 fields)
 *   generated_interval.stream  — Duration[s/ms/us/ns], Interval[YEAR_MONTH], Interval[DAY_TIME] (7 rows)
 *   generated_decimal.stream   — Decimal128 columns precision 3..38 scale 2 (7 rows, 36 fields)
 *   generated_decimal256.stream — Decimal256 columns precision 37..69 scale 5 (7 rows, 33 fields)
 *
 * Golden values derived from the JSON companions in apache/arrow-testing.
 */
class ArrowTemporalDecoderTest {

    private fun loadResource(name: String): ByteArray {
        val stream = ArrowTemporalDecoderTest::class.java.classLoader.getResourceAsStream(name)
            ?: error("Test resource not found: $name")
        return stream.readBytes()
    }

    private fun loadStream(name: String): Triple<ByteArray, ArrowSchema, ArrowEncapsulatedMessage> {
        val bytes = loadResource(name)
        val buf = bytes.toCommonByteBuffer()
        val contents = readArrowStream(buf)
        val msg = contents.messages.first { it.message.headerType == MessageHeaderType.RECORD_BATCH }
        return Triple(bytes, contents.schema, msg)
    }

    // -----------------------------------------------------------------------
    // scaledDecimalString unit tests (no file I/O)
    // -----------------------------------------------------------------------

    @Test
    fun scaledDecimalString_positive() {
        assertEquals("128.10", scaledDecimalString(12810L, 2))
        assertEquals("326.97", scaledDecimalString(32697L, 2))
        assertEquals("3.14159", scaledDecimalString(314159L, 5))
        assertEquals("1.00", scaledDecimalString(100L, 2))
    }

    @Test
    fun scaledDecimalString_negative() {
        assertEquals("-14.84", scaledDecimalString(-1484L, 2))
        assertEquals("-596.50", scaledDecimalString(-59650L, 2))
        assertEquals("-0.01", scaledDecimalString(-1L, 2))
    }

    @Test
    fun scaledDecimalString_zeroScale() {
        assertEquals("12345", scaledDecimalString(12345L, 0))
        assertEquals("-12345", scaledDecimalString(-12345L, 0))
    }

    @Test
    fun scaledDecimalString_zero() {
        assertEquals("0.00", scaledDecimalString(0L, 2))
    }

    @Test
    fun scaledDecimalString_leadingFracZeros() {
        // 102 / 1000 = 0.102
        assertEquals("0.102", scaledDecimalString(102L, 3))
        // 5 / 100 = 0.05
        assertEquals("0.05", scaledDecimalString(5L, 2))
    }

    // -----------------------------------------------------------------------
    // generated_datetime.stream — Date32 (f0)
    // -----------------------------------------------------------------------

    @Test
    fun datetime_date32_fullyDecoded() {
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[0]  // f0: date32[day]
        assertTrue(col.fullyDecoded, "Date32 should be fullyDecoded; reason=${col.undecodedReason}")
        assertEquals("Date32", col.typeName)
        assertEquals(7, col.cells.size)
    }

    @Test
    fun datetime_date32_goldenValues() {
        // JSON companion: validity=[1,1,1,1,1,1,0] data=[-719162, 2932896, -165637, 638140, 1050718, 1780621]
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[0]

        assertEquals(-719162, col.cells[0].value, "date32 row 0")
        assertEquals(2932896, col.cells[1].value, "date32 row 1")
        assertEquals(-165637, col.cells[2].value, "date32 row 2")
        assertTrue(col.cells[6].isNull, "date32 row 6 must be null")
    }

    @Test
    fun datetime_date32_exactProvenance() {
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[0]

        val cell0 = col.cells[0]
        assertEquals(ByteProvenanceKind.EXACT, cell0.provenance.kind)
        assertEquals(4, cell0.provenance.size, "Date32 cell must have 4-byte provenance")

        // Read-back: 4 bytes at provenance offset must equal the decoded int32 value
        val readBack = buf.run {
            val savedPos = position()
            position(cell0.provenance.offset.toInt())
            val v = getIntLE()
            position(savedPos)
            v
        }
        assertEquals(-719162, readBack, "Date32 read-back from provenance offset must match")
    }

    // -----------------------------------------------------------------------
    // generated_datetime.stream — Date64 (f1)
    // -----------------------------------------------------------------------

    @Test
    fun datetime_date64_goldenValues() {
        // JSON companion: validity=[0,1,1,1,1,1,1]
        // data[1]='253402214400000', data[2]='213620221665533'
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[1]  // f1: date64[ms]
        assertTrue(col.fullyDecoded, "Date64 should be fullyDecoded")
        assertEquals("Date64", col.typeName)

        assertTrue(col.cells[0].isNull, "date64 row 0 must be null")
        assertEquals(253402214400000L, col.cells[1].value, "date64 row 1")
        assertEquals(213620221665533L, col.cells[2].value, "date64 row 2")
    }

    @Test
    fun datetime_date64_exactProvenance() {
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[1]

        val cell1 = col.cells[1]  // non-null
        assertEquals(ByteProvenanceKind.EXACT, cell1.provenance.kind)
        assertEquals(8, cell1.provenance.size, "Date64 cell must have 8-byte provenance")

        val readBack = buf.run {
            val savedPos = position()
            position(cell1.provenance.offset.toInt())
            val v = getLongLE()
            position(savedPos)
            v
        }
        assertEquals(253402214400000L, readBack, "Date64 read-back from provenance offset must match")
    }

    // -----------------------------------------------------------------------
    // generated_datetime.stream — Time32[s] (f2) and Time32[ms] (f3)
    // -----------------------------------------------------------------------

    @Test
    fun datetime_time32s_goldenValues() {
        // JSON: validity=[0,0,1,1,1,0,1] data=[0,86400,22815,42191,45032,_,65008]
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[2]  // f2: time32[s]
        assertTrue(col.fullyDecoded, "Time32[s] should be fullyDecoded")
        assertTrue(col.typeName.startsWith("Time32"), "Time32[s] typeName must start with Time32")

        assertTrue(col.cells[0].isNull, "time32[s] row 0 must be null")
        assertTrue(col.cells[1].isNull, "time32[s] row 1 must be null")
        assertEquals(22815, col.cells[2].value, "time32[s] row 2")
        assertEquals(42191, col.cells[3].value, "time32[s] row 3")
        assertEquals(45032, col.cells[4].value, "time32[s] row 4")
        assertEquals(65008, col.cells[6].value, "time32[s] row 6")
    }

    @Test
    fun datetime_time32s_exactProvenance() {
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[2]

        val cell2 = col.cells[2]  // non-null row
        assertEquals(ByteProvenanceKind.EXACT, cell2.provenance.kind)
        assertEquals(4, cell2.provenance.size)

        val readBack = buf.run {
            val savedPos = position()
            position(cell2.provenance.offset.toInt())
            val v = getIntLE()
            position(savedPos)
            v
        }
        assertEquals(22815, readBack)
    }

    @Test
    fun datetime_time32ms_goldenValues() {
        // JSON: validity=[1,0,1,0,0,1,1] data=[0,86400000,58562592,...,176541,14295686]
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[3]  // f3: time32[ms]
        assertTrue(col.fullyDecoded, "Time32[ms] should be fullyDecoded")

        assertEquals(0, col.cells[0].value, "time32[ms] row 0")
        assertTrue(col.cells[1].isNull, "time32[ms] row 1 must be null")
        assertEquals(58562592, col.cells[2].value, "time32[ms] row 2")
    }

    // -----------------------------------------------------------------------
    // generated_datetime.stream — Time64[us] (f4) and Time64[ns] (f5)
    // -----------------------------------------------------------------------

    @Test
    fun datetime_time64us_goldenValues() {
        // JSON: validity=[1,0,0,1,0,1,0] data=['0','86400000000','48616086360','27317666184',...]
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[4]  // f4: time64[us]
        assertTrue(col.fullyDecoded, "Time64[us] should be fullyDecoded")
        assertTrue(col.typeName.startsWith("Time64"), "Time64[us] typeName must start with Time64")

        assertEquals(0L, col.cells[0].value, "time64[us] row 0")
        assertTrue(col.cells[1].isNull, "time64[us] row 1 must be null")
        assertEquals(27317666184L, col.cells[3].value, "time64[us] row 3")
    }

    @Test
    fun datetime_time64us_exactProvenance() {
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[4]

        val cell0 = col.cells[0]  // non-null
        assertEquals(ByteProvenanceKind.EXACT, cell0.provenance.kind)
        assertEquals(8, cell0.provenance.size, "Time64 cell must have 8-byte provenance")

        val readBack = buf.run {
            val savedPos = position()
            position(cell0.provenance.offset.toInt())
            val v = getLongLE()
            position(savedPos)
            v
        }
        assertEquals(0L, readBack)
    }

    @Test
    fun datetime_time64ns_goldenValues() {
        // JSON: validity=[1,0,0,1,1,1,1]
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[5]  // f5: time64[ns]
        assertTrue(col.fullyDecoded, "Time64[ns] should be fullyDecoded")

        assertEquals(0L, col.cells[0].value, "time64[ns] row 0")
        assertTrue(col.cells[1].isNull, "time64[ns] row 1 must be null")
        assertEquals(30734393435189L, col.cells[3].value, "time64[ns] row 3")
    }

    // -----------------------------------------------------------------------
    // generated_datetime.stream — Timestamp (f6..f14)
    // -----------------------------------------------------------------------

    @Test
    fun datetime_timestamp_s_goldenValues() {
        // f6: timestamp[s] JSON validity=[0,1,1,1,0,1,0] data=['',253402214400,-53002797342,208532018591,...]
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[6]  // f6: timestamp[s]
        assertTrue(col.fullyDecoded, "Timestamp[s] should be fullyDecoded")
        assertTrue(col.typeName.startsWith("Timestamp["), "Timestamp typeName must start with Timestamp[")

        assertTrue(col.cells[0].isNull, "timestamp[s] row 0 must be null")
        assertEquals(253402214400L, col.cells[1].value, "timestamp[s] row 1")
        assertEquals(-53002797342L, col.cells[2].value, "timestamp[s] row 2")
    }

    @Test
    fun datetime_timestamp_exactProvenance() {
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[6]

        val cell1 = col.cells[1]  // non-null
        assertEquals(ByteProvenanceKind.EXACT, cell1.provenance.kind)
        assertEquals(8, cell1.provenance.size, "Timestamp cell must have 8-byte provenance")

        val readBack = buf.run {
            val savedPos = position()
            position(cell1.provenance.offset.toInt())
            val v = getLongLE()
            position(savedPos)
            v
        }
        assertEquals(253402214400L, readBack)
    }

    @Test
    fun datetime_timestamp_tz_typeName() {
        // f11: timestamp[s, tz=UTC], f12: timestamp[ms, tz=US/Eastern]
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col11 = cols[11]  // f11
        val col12 = cols[12]  // f12
        assertTrue(col11.typeName.contains("UTC"), "Timestamp[s,UTC] must include UTC in typeName: ${col11.typeName}")
        assertTrue(col12.typeName.contains("US/Eastern"), "Timestamp[ms,US/Eastern] typeName: ${col12.typeName}")
        assertTrue(col11.fullyDecoded, "Timestamp with tz should be fullyDecoded")
    }

    // -----------------------------------------------------------------------
    // generated_interval.stream — Duration (f1..f4)
    // -----------------------------------------------------------------------

    @Test
    fun interval_duration_s_fullyDecoded() {
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        assertEquals(6, cols.size, "generated_interval.stream should have 6 columns")
        val col = cols[0]  // f1: duration[s]
        assertTrue(col.fullyDecoded, "Duration[s] should be fullyDecoded; reason=${col.undecodedReason}")
        assertTrue(col.typeName.startsWith("Duration["), "Duration typeName: ${col.typeName}")
    }

    @Test
    fun interval_duration_s_goldenValues() {
        // JSON: validity=[1,1,1,1,1,1,0] data=[MIN_LONG, MAX_LONG, 2199428923299600048, ...]
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[0]

        assertEquals(Long.MIN_VALUE, col.cells[0].value, "duration[s] row 0 = MIN_LONG")
        assertEquals(Long.MAX_VALUE, col.cells[1].value, "duration[s] row 1 = MAX_LONG")
        assertEquals(2199428923299600048L, col.cells[2].value, "duration[s] row 2")
        assertTrue(col.cells[6].isNull, "duration[s] row 6 must be null")
    }

    @Test
    fun interval_duration_s_exactProvenance() {
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[0]

        val cell0 = col.cells[0]
        assertEquals(ByteProvenanceKind.EXACT, cell0.provenance.kind)
        assertEquals(8, cell0.provenance.size, "Duration cell must have 8-byte provenance")

        val readBack = buf.run {
            val savedPos = position()
            position(cell0.provenance.offset.toInt())
            val v = getLongLE()
            position(savedPos)
            v
        }
        assertEquals(Long.MIN_VALUE, readBack)
    }

    @Test
    fun interval_duration_ns_goldenValues() {
        // f4: duration[ns] validity=[1,0,1,0,0,1,1]
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[3]  // f4: duration[ns]
        assertTrue(col.fullyDecoded, "Duration[ns] should be fullyDecoded")

        assertEquals(Long.MIN_VALUE, col.cells[0].value, "duration[ns] row 0")
        assertTrue(col.cells[1].isNull, "duration[ns] row 1 must be null")
        assertEquals(-8077459638238925307L, col.cells[2].value, "duration[ns] row 2")
    }

    // -----------------------------------------------------------------------
    // generated_interval.stream — Interval[YEAR_MONTH] (f5)
    // -----------------------------------------------------------------------

    @Test
    fun interval_yearMonth_fullyDecoded() {
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[4]  // f5: month_interval
        assertTrue(col.fullyDecoded, "Interval[YEAR_MONTH] should be fullyDecoded; reason=${col.undecodedReason}")
        assertEquals("Interval[YEAR_MONTH]", col.typeName)
    }

    @Test
    fun interval_yearMonth_goldenValues() {
        // JSON: validity=[0,0,1,1,0,1,1] data=[-120000,120000,35900,-3323,-112340,-103022,81846]
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[4]

        assertTrue(col.cells[0].isNull, "year_month row 0 must be null")
        assertTrue(col.cells[1].isNull, "year_month row 1 must be null")
        assertEquals(35900, col.cells[2].value, "year_month row 2")
        assertEquals(-3323, col.cells[3].value, "year_month row 3")
        assertTrue(col.cells[4].isNull, "year_month row 4 must be null")
        assertEquals(-103022, col.cells[5].value, "year_month row 5")
        assertEquals(81846, col.cells[6].value, "year_month row 6")
    }

    @Test
    fun interval_yearMonth_exactProvenance() {
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[4]

        val cell2 = col.cells[2]  // non-null
        assertEquals(ByteProvenanceKind.EXACT, cell2.provenance.kind)
        assertEquals(4, cell2.provenance.size, "YEAR_MONTH interval must have 4-byte provenance")

        val readBack = buf.run {
            val savedPos = position()
            position(cell2.provenance.offset.toInt())
            val v = getIntLE()
            position(savedPos)
            v
        }
        assertEquals(35900, readBack)
    }

    // -----------------------------------------------------------------------
    // generated_interval.stream — Interval[DAY_TIME] (f6)
    // -----------------------------------------------------------------------

    @Test
    fun interval_dayTime_fullyDecoded() {
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[5]  // f6: day_time_interval
        assertTrue(col.fullyDecoded, "Interval[DAY_TIME] should be fullyDecoded")
        assertEquals("Interval[DAY_TIME]", col.typeName)
    }

    @Test
    fun interval_dayTime_goldenValues() {
        // JSON: validity=[1,1,0,0,1,0,0]
        // row 0: days=-2622376, ms=-67227994
        // row 1: days=-3168416, ms=-2598138
        // row 4: days=589643, ms=23343218
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[5]

        val repr0 = col.cells[0].value as String
        assertTrue(repr0.contains("days=-2622376"), "day_time row 0 days: $repr0")
        assertTrue(repr0.contains("ms=-67227994"), "day_time row 0 ms: $repr0")

        val repr1 = col.cells[1].value as String
        assertTrue(repr1.contains("days=-3168416"), "day_time row 1 days: $repr1")
        assertTrue(repr1.contains("ms=-2598138"), "day_time row 1 ms: $repr1")

        assertTrue(col.cells[2].isNull, "day_time row 2 must be null")
        assertTrue(col.cells[3].isNull, "day_time row 3 must be null")

        val repr4 = col.cells[4].value as String
        assertTrue(repr4.contains("days=589643"), "day_time row 4 days: $repr4")
        assertTrue(repr4.contains("ms=23343218"), "day_time row 4 ms: $repr4")
    }

    @Test
    fun interval_dayTime_exactProvenance() {
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[5]

        val cell0 = col.cells[0]  // non-null
        assertEquals(ByteProvenanceKind.EXACT, cell0.provenance.kind)
        assertEquals(8, cell0.provenance.size, "DAY_TIME interval must have 8-byte provenance")

        // Read raw bytes and verify days and ms components
        val raw = ByteArray(8)
        buf.run {
            val savedPos = position()
            position(cell0.provenance.offset.toInt())
            get(raw, 0, 8)
            position(savedPos)
        }
        val days = raw[0].toInt() and 0xFF or ((raw[1].toInt() and 0xFF) shl 8) or
            ((raw[2].toInt() and 0xFF) shl 16) or (raw[3].toInt() shl 24)
        val ms = raw[4].toInt() and 0xFF or ((raw[5].toInt() and 0xFF) shl 8) or
            ((raw[6].toInt() and 0xFF) shl 16) or (raw[7].toInt() shl 24)
        assertEquals(-2622376, days, "DAY_TIME provenance days read-back")
        assertEquals(-67227994, ms, "DAY_TIME provenance ms read-back")
    }

    // -----------------------------------------------------------------------
    // generated_decimal.stream — Decimal128
    // -----------------------------------------------------------------------

    @Test
    fun decimal128_fullyDecoded() {
        val (bytes, schema, msg) = loadStream("generated_decimal.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        assertEquals(36, cols.size, "generated_decimal.stream should have 36 columns")
        val col = cols[0]  // f0: decimal128(3,2)
        assertTrue(col.fullyDecoded, "Decimal128 should be fullyDecoded; reason=${col.undecodedReason}")
        assertTrue(col.typeName.startsWith("Decimal128"), "typeName: ${col.typeName}")
    }

    @Test
    fun decimal128_f0_goldenValues() {
        // f0 precision=3 scale=2: validity=[1,0,0,0,0,1,0]
        // data=[12810,...,-32139,...] => "128.10" at row 0, null at rows 1-4, "-321.39" at row 5
        val (bytes, schema, msg) = loadStream("generated_decimal.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[0]

        assertTrue(!col.cells[0].isNull, "f0 row 0 must not be null")
        assertEquals("128.10", col.cells[0].value as String, "f0 row 0 = 128.10")
        assertTrue(col.cells[1].isNull, "f0 row 1 must be null")
        assertTrue(col.cells[2].isNull, "f0 row 2 must be null")
        assertTrue(!col.cells[5].isNull, "f0 row 5 must not be null")
        assertEquals("-321.39", col.cells[5].value as String, "f0 row 5 = -321.39")
    }

    @Test
    fun decimal128_f1_goldenValues() {
        // f1 precision=4 scale=2: all 7 rows valid
        // data=[32697,15178,27487,14861,-1484,6468,6302]
        // => "326.97","151.78","274.87","148.61","-14.84","64.68","63.02"
        val (bytes, schema, msg) = loadStream("generated_decimal.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[1]

        assertEquals("326.97", col.cells[0].value as String, "f1 row 0")
        assertEquals("151.78", col.cells[1].value as String, "f1 row 1")
        assertEquals("274.87", col.cells[2].value as String, "f1 row 2")
        assertEquals("148.61", col.cells[3].value as String, "f1 row 3")
        assertEquals("-14.84", col.cells[4].value as String, "f1 row 4")
        assertEquals("64.68",  col.cells[5].value as String, "f1 row 5")
        assertEquals("63.02",  col.cells[6].value as String, "f1 row 6")
    }

    @Test
    fun decimal128_exactProvenance() {
        val (bytes, schema, msg) = loadStream("generated_decimal.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[1]  // f1: all valid

        val cell0 = col.cells[0]
        assertEquals(ByteProvenanceKind.EXACT, cell0.provenance.kind)
        assertEquals(16, cell0.provenance.size, "Decimal128 cell must have 16-byte provenance")

        // Read-back: 16 bytes LE; low 8 as int64 = 32697; high 8 = 0x00..00
        val raw = ByteArray(16)
        buf.run {
            val savedPos = position()
            position(cell0.provenance.offset.toInt())
            get(raw, 0, 16)
            position(savedPos)
        }
        var low8 = 0L
        for (i in 0 until 8) low8 = low8 or (raw[i].toLong() and 0xFF shl (i * 8))
        assertEquals(32697L, low8, "Decimal128 f1 row0 low8 must be 32697")
        for (i in 8 until 16) assertEquals(0, raw[i].toInt() and 0xFF, "Decimal128 f1 row0 high bytes must be 0")
    }

    @Test
    fun decimal128_provenanceStride() {
        // Consecutive non-null cells in f1 must have provenance offsets striding by 16
        val (bytes, schema, msg) = loadStream("generated_decimal.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[1]  // all 7 non-null

        val base = col.cells[0].provenance.offset
        for (i in 0 until 7) {
            assertEquals(base + i.toLong() * 16, col.cells[i].provenance.offset,
                "Decimal128 provenance stride 16 at row $i")
        }
    }

    // -----------------------------------------------------------------------
    // generated_decimal256.stream — Decimal256 (big values, hex fallback expected)
    // -----------------------------------------------------------------------

    @Test
    fun decimal256_fullyDecoded() {
        val (bytes, schema, msg) = loadStream("generated_decimal256.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        // All columns in decimal256 fixture have precision >> 18, so values won't fit in int64.
        // We only test that the columns decode without error (fullyDecoded=true).
        assertTrue(cols.isNotEmpty(), "decimal256 should produce columns")
        val col = cols[0]
        assertTrue(col.fullyDecoded, "Decimal256 should be fullyDecoded; reason=${col.undecodedReason}")
        assertTrue(col.typeName.startsWith("Decimal256"), "typeName: ${col.typeName}")
    }

    @Test
    fun decimal256_exactProvenance() {
        val (bytes, schema, msg) = loadStream("generated_decimal256.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        // Find a non-null cell in column 1 (f1: decimal256(38,5))
        val col = cols[1]
        val nonNull = col.cells.firstOrNull { !it.isNull }
        if (nonNull != null) {
            assertEquals(ByteProvenanceKind.EXACT, nonNull.provenance.kind)
            assertEquals(32, nonNull.provenance.size, "Decimal256 cell must have 32-byte provenance")
        }
    }

    @Test
    fun decimal256_hexFallbackFormat() {
        // Decimal256 values with precision > 18 digits won't fit in int64.
        // They should be rendered as "0x..." hex strings.
        val (bytes, schema, msg) = loadStream("generated_decimal256.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[0]
        val nonNull = col.cells.firstOrNull { !it.isNull }
        if (nonNull != null) {
            val repr = nonNull.value as String
            // Either starts with "0x" (hex fallback) or is a valid scaled decimal
            assertTrue(repr.startsWith("0x") || repr.contains("."),
                "Decimal256 non-null repr must be hex or scaled decimal: $repr")
        }
    }

    // -----------------------------------------------------------------------
    // Column count sanity for all fixtures
    // -----------------------------------------------------------------------

    @Test
    fun datetime_columnCount() {
        val (bytes, schema, msg) = loadStream("generated_datetime.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        assertEquals(15, cols.size, "generated_datetime.stream should have 15 columns")
        // All columns must be fullyDecoded (all fixed-width leaf types)
        cols.forEach { col ->
            assertTrue(col.fullyDecoded, "All datetime columns must be fullyDecoded; ${col.leafPath}: ${col.undecodedReason}")
        }
    }

    @Test
    fun interval_columnCount() {
        val (bytes, schema, msg) = loadStream("generated_interval.stream")
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        assertEquals(6, cols.size, "generated_interval.stream should have 6 columns")
        cols.forEach { col ->
            assertTrue(col.fullyDecoded, "All interval columns must be fullyDecoded; ${col.leafPath}: ${col.undecodedReason}")
        }
    }
}
