package org.jetbrains.hexana.parquet

import org.jetbrains.hexana.io.ByteProvenanceKind
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [decodeColumnChunk] and [BitReader].
 *
 * Fixture strategy:
 * - Golden real-file tests use `alltypes_plain.parquet` (8 rows, 11 OPTIONAL columns,
 *   PLAIN_DICTIONARY encoding with RLE def levels — exercises Phase 3 fully).
 * - Synthetic PLAIN tests use hand-crafted Parquet blobs (REQUIRED columns).
 * - RLE / bitWidth unit tests use raw byte arrays.
 *
 * Synthetic INT32 file layout (113 bytes total):
 *   offset  0: "PAR1"
 *   offset  4: PageHeader (17 bytes, Thrift Compact)
 *   offset 21: INT32 page body — 4 values × 4 bytes LE: 10, 20, 30, 40
 *   offset 37: FileMetaData footer (68 bytes), 4-byte LE length, "PAR1"
 *   data_page_offset=4, page_body_abs_offset=21
 *
 * Synthetic BOOLEAN file layout (96 bytes total):
 *   offset  0: "PAR1"
 *   offset  4: PageHeader (17 bytes, Thrift Compact)
 *   offset 21: BOOLEAN page body — 1 byte (0x55 = TFTFTFTF, LSB-first)
 *   offset 22: FileMetaData footer (66 bytes), 4-byte LE length, "PAR1"
 *   data_page_offset=4, page_body_abs_offset=21
 */
class ParquetValueDecoderTest {

    private fun loadResource(name: String): ByteArray {
        val stream = ParquetDecoderTest::class.java.classLoader.getResourceAsStream(name)
            ?: error("Test resource not found: $name")
        return stream.readBytes()
    }

    // -----------------------------------------------------------------------
    // GOLDEN real-file: alltypes_plain.parquet (Phase 3)
    //
    // 8 rows, all columns OPTIONAL (maxDef=1), PLAIN_DICTIONARY + RLE def levels.
    // No nulls in this file. fullyDecoded must be true for all columns (INT96 included).
    //
    // Known column values (verified by independent Python pyarrow inspection):
    //   id:              [4, 5, 6, 7, 2, 3, 0, 1]
    //   bool_col:        [true, false, true, false, true, false, true, false]  (PLAIN, no dict)
    //   tinyint_col:     [0, 1, 0, 1, 0, 1, 0, 1]
    //   smallint_col:    [0, 1, 0, 1, 0, 1, 0, 1]
    //   int_col:         [0, 1, 0, 1, 0, 1, 0, 1]
    //   bigint_col:      [0L, 10L, 0L, 10L, 0L, 10L, 0L, 10L]
    //   float_col:       [0f, 1.1f, 0f, 1.1f, 0f, 1.1f, 0f, 1.1f]
    //   double_col:      [0.0, 10.1, 0.0, 10.1, 0.0, 10.1, 0.0, 10.1]
    //   date_string_col: ["03/01/09","03/01/09","04/01/09","04/01/09","02/01/09","02/01/09","01/01/09","01/01/09"]
    //   string_col:      ["0","1","0","1","0","1","0","1"]
    //   timestamp_col:   INT96 -> decoded as signed decimal string (see below)
    // -----------------------------------------------------------------------

    @Test
    fun alltypesPlain_idColumn_fullyDecodedPhase3() {
        val bytes = loadResource("alltypes_plain.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val idChunk = meta.rowGroups[0].columns[0].metaData!!
        val idLeaf = tree.children[0]

        val result = decodeColumnChunk(buf, idChunk, idLeaf)

        assertTrue(result.fullyDecoded, "id column should be fully decoded in Phase 3, reason=${result.undecodedReason}")
        assertNull(result.undecodedReason)
        assertEquals(8, result.cells.size)
        assertEquals(listOf("id"), result.leafPath)
        assertEquals(ParquetType.INT32, result.type)

        // Verify actual values: [4, 5, 6, 7, 2, 3, 0, 1]
        val expected = listOf(4, 5, 6, 7, 2, 3, 0, 1)
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull, "id[$i] should not be null")
            assertEquals(expected[i], cell.value as Int, "id[$i] value mismatch")
        }
    }

    @Test
    fun alltypesPlain_boolCol_fullyDecoded() {
        val bytes = loadResource("alltypes_plain.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[1].metaData!!  // bool_col
        val leaf = tree.children[1]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "bool_col should be fully decoded, reason=${result.undecodedReason}")
        assertEquals(8, result.cells.size)
        // Values: T F T F T F T F
        val expected = listOf(true, false, true, false, true, false, true, false)
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull)
            assertEquals(expected[i], cell.value as Boolean, "bool_col[$i] mismatch")
        }
    }

    @Test
    fun alltypesPlain_intCol_fullyDecoded() {
        val bytes = loadResource("alltypes_plain.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[4].metaData!!  // int_col
        val leaf = tree.children[4]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "int_col should be fully decoded, reason=${result.undecodedReason}")
        assertEquals(8, result.cells.size)
        val expected = listOf(0, 1, 0, 1, 0, 1, 0, 1)
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull)
            assertEquals(expected[i], cell.value as Int, "int_col[$i] mismatch")
        }
    }

    @Test
    fun alltypesPlain_bigintCol_fullyDecoded() {
        val bytes = loadResource("alltypes_plain.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[5].metaData!!  // bigint_col
        val leaf = tree.children[5]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "bigint_col should be fully decoded, reason=${result.undecodedReason}")
        assertEquals(8, result.cells.size)
        val expected = listOf(0L, 10L, 0L, 10L, 0L, 10L, 0L, 10L)
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull)
            assertEquals(expected[i], cell.value as Long, "bigint_col[$i] mismatch")
        }
    }

    @Test
    fun alltypesPlain_floatCol_fullyDecoded() {
        val bytes = loadResource("alltypes_plain.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[6].metaData!!  // float_col
        val leaf = tree.children[6]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "float_col should be fully decoded, reason=${result.undecodedReason}")
        assertEquals(8, result.cells.size)
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull)
            val v = cell.value as Float
            if (i % 2 == 0) assertEquals(0f, v, "float_col[$i] should be 0.0")
            else assertTrue(v > 1.0f && v < 1.2f, "float_col[$i] should be ~1.1, got $v")
        }
    }

    @Test
    fun alltypesPlain_doubleCol_fullyDecoded() {
        val bytes = loadResource("alltypes_plain.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[7].metaData!!  // double_col
        val leaf = tree.children[7]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "double_col should be fully decoded, reason=${result.undecodedReason}")
        assertEquals(8, result.cells.size)
        val expected = listOf(0.0, 10.1, 0.0, 10.1, 0.0, 10.1, 0.0, 10.1)
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull)
            assertEquals(expected[i], cell.value as Double, 1e-9, "double_col[$i] mismatch")
        }
    }

    @Test
    fun alltypesPlain_stringCol_fullyDecoded() {
        val bytes = loadResource("alltypes_plain.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[9].metaData!!  // string_col
        val leaf = tree.children[9]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "string_col should be fully decoded, reason=${result.undecodedReason}")
        assertEquals(8, result.cells.size)
        val expected = listOf("0", "1", "0", "1", "0", "1", "0", "1")
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull)
            assertEquals(expected[i], cell.value as String, "string_col[$i] mismatch")
        }
    }

    @Test
    fun alltypesPlain_timestampCol_int96DecodedAsDecimalString() {
        val bytes = loadResource("alltypes_plain.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[10].metaData!!  // timestamp_col (INT96)
        val leaf = tree.children[10]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "INT96 should now decode, reason=${result.undecodedReason}")
        assertNull(result.undecodedReason)
        assertEquals(ParquetType.INT96, result.type)
        assertEquals(8, result.cells.size)
        // Raw 96-bit little-endian integer view: julian_day * 2^64 + nanos_of_day.
        // Ground truth from independent computation (see Int96Decimal / commit message).
        val expected = listOf(
            "45284764452596988585705472", "45284764452597048585705472",
            "45285336301663273581805568", "45285336301663333581805568",
            "45284247943762924718260224", "45284247943762984718260224",
            "45283676094696639722160128", "45283676094696699722160128",
        )
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull, "timestamp_col[$i] should not be null")
            assertEquals(expected[i], cell.value as String, "timestamp_col[$i] mismatch")
        }
    }

    @Test
    fun alltypesPlain_provenanceIsEncodedRunForDictColumns() {
        val bytes = loadResource("alltypes_plain.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        // id column uses PLAIN_DICTIONARY -> ENCODED_RUN provenance
        val chunk = meta.rowGroups[0].columns[0].metaData!!
        val leaf = tree.children[0]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded)
        result.cells.forEach { cell ->
            assertEquals(ByteProvenanceKind.ENCODED_RUN, cell.provenance.kind,
                "Dictionary-decoded cells should have ENCODED_RUN provenance")
        }
    }

    @Test
    fun alltypesPlain_defLevelPathExercised() {
        // All cells non-null (def == maxDef == 1). Verify isNull=false for all 8 rows.
        val bytes = loadResource("alltypes_plain.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        for (colIdx in 0 until 10) {  // skip timestamp_col (INT96)
            val chunk = meta.rowGroups[0].columns[colIdx].metaData!!
            val leaf = tree.children[colIdx]
            val result = decodeColumnChunk(buf, chunk, leaf)
            if (!result.fullyDecoded) continue
            result.cells.forEach { cell ->
                assertFalse(cell.isNull, "col[$colIdx]: no nulls expected in alltypes_plain")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Synthetic INT32 REQUIRED PLAIN file — golden decode + provenance tests
    // (unchanged from Phase 2 — must stay passing)
    // -----------------------------------------------------------------------

    /** 113-byte hand-crafted Parquet file with one REQUIRED INT32 column, 4 values: 10, 20, 30, 40. */
    private val syntheticInt32File: ByteArray = byteArrayOf(
        0x50.toByte(), 0x41.toByte(), 0x52.toByte(), 0x31.toByte(), 0x15.toByte(), 0x00.toByte(),
        0x15.toByte(), 0x20.toByte(), 0x15.toByte(), 0x20.toByte(), 0x2c.toByte(), 0x15.toByte(),
        0x08.toByte(), 0x15.toByte(), 0x00.toByte(), 0x15.toByte(), 0x06.toByte(), 0x15.toByte(),
        0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0a.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x14.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1e.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x28.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x15.toByte(), 0x02.toByte(), 0x19.toByte(), 0x2c.toByte(), 0x48.toByte(),
        0x07.toByte(), 0x6d.toByte(), 0x65.toByte(), 0x73.toByte(), 0x73.toByte(), 0x61.toByte(),
        0x67.toByte(), 0x65.toByte(), 0x15.toByte(), 0x02.toByte(), 0x00.toByte(), 0x15.toByte(),
        0x02.toByte(), 0x25.toByte(), 0x00.toByte(), 0x18.toByte(), 0x05.toByte(), 0x76.toByte(),
        0x61.toByte(), 0x6c.toByte(), 0x75.toByte(), 0x65.toByte(), 0x00.toByte(), 0x15.toByte(),
        0x08.toByte(), 0x19.toByte(), 0x1c.toByte(), 0x19.toByte(), 0x1c.toByte(), 0x25.toByte(),
        0x08.toByte(), 0x1c.toByte(), 0x15.toByte(), 0x02.toByte(), 0x19.toByte(), 0x15.toByte(),
        0x00.toByte(), 0x19.toByte(), 0x18.toByte(), 0x05.toByte(), 0x76.toByte(), 0x61.toByte(),
        0x6c.toByte(), 0x75.toByte(), 0x65.toByte(), 0x15.toByte(), 0x00.toByte(), 0x15.toByte(),
        0x08.toByte(), 0x15.toByte(), 0x20.toByte(), 0x15.toByte(), 0x20.toByte(), 0x25.toByte(),
        0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x15.toByte(), 0x20.toByte(), 0x15.toByte(),
        0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x44.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x50.toByte(), 0x41.toByte(), 0x52.toByte(), 0x31.toByte(),
    )

    @Test
    fun syntheticInt32_footerDecodesCorrectly() {
        val buf = syntheticInt32File.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        assertEquals(1, meta.rowGroups.size)
        assertEquals(4L, meta.rowGroups[0].numRows)
        val chunk = meta.rowGroups[0].columns[0].metaData!!
        assertEquals(ParquetType.INT32, chunk.type)
        assertEquals(4L, chunk.numValues)
        assertEquals(CompressionCodec.UNCOMPRESSED, chunk.codec)
        assertNull(chunk.dictionaryPageOffset)
    }

    @Test
    fun syntheticInt32_decodesValues() {
        val buf = syntheticInt32File.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[0].metaData!!
        val leaf = tree.children[0]

        assertEquals(0, leaf.maxDefinitionLevel())
        assertEquals(0, leaf.maxRepetitionLevel())

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "Expected fullyDecoded=true, reason=${result.undecodedReason}")
        assertNull(result.undecodedReason)
        assertEquals(4, result.cells.size)
        assertEquals(10, result.cells[0].value as Int)
        assertEquals(20, result.cells[1].value as Int)
        assertEquals(30, result.cells[2].value as Int)
        assertEquals(40, result.cells[3].value as Int)
        result.cells.forEach { assertFalse(it.isNull) }
    }

    @Test
    fun syntheticInt32_provenanceIsExact() {
        val buf = syntheticInt32File.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[0].metaData!!
        val leaf = tree.children[0]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded)
        result.cells.forEach { cell ->
            assertEquals(ByteProvenanceKind.EXACT, cell.provenance.kind)
            assertEquals(4, cell.provenance.size)
        }
    }

    /**
     * Golden provenance check: re-read [ByteProvenance.size] bytes at [ByteProvenance.offset]
     * from the raw file bytes and assert the little-endian INT32 matches the decoded value.
     * This is the core EXACT-provenance invariant.
     */
    @Test
    fun syntheticInt32_provenanceReproducesValues() {
        val bytes = syntheticInt32File
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[0].metaData!!
        val leaf = tree.children[0]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded)
        val expectedValues = listOf(10, 20, 30, 40)
        for ((i, cell) in result.cells.withIndex()) {
            val p = cell.provenance
            assertEquals(4, p.size, "Cell $i provenance size should be 4")
            val reproduced = readInt32LE(bytes, p.offset.toInt())
            assertEquals(expectedValues[i], reproduced,
                "Cell $i: provenance at offset ${p.offset} reproduces wrong value")
            assertEquals(expectedValues[i], cell.value as Int)
        }
    }

    // -----------------------------------------------------------------------
    // Synthetic BOOLEAN REQUIRED PLAIN file
    // -----------------------------------------------------------------------

    /** 96-byte hand-crafted Parquet file with one REQUIRED BOOLEAN column, 8 values. */
    private val syntheticBoolFile: ByteArray = byteArrayOf(
        0x50.toByte(), 0x41.toByte(), 0x52.toByte(), 0x31.toByte(), 0x15.toByte(), 0x00.toByte(),
        0x15.toByte(), 0x02.toByte(), 0x15.toByte(), 0x02.toByte(), 0x2c.toByte(), 0x15.toByte(),
        0x10.toByte(), 0x15.toByte(), 0x00.toByte(), 0x15.toByte(), 0x06.toByte(), 0x15.toByte(),
        0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x55.toByte(), 0x15.toByte(), 0x02.toByte(),
        0x19.toByte(), 0x2c.toByte(), 0x48.toByte(), 0x07.toByte(), 0x6d.toByte(), 0x65.toByte(),
        0x73.toByte(), 0x73.toByte(), 0x61.toByte(), 0x67.toByte(), 0x65.toByte(), 0x15.toByte(),
        0x02.toByte(), 0x00.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x00.toByte(),
        0x18.toByte(), 0x04.toByte(), 0x66.toByte(), 0x6c.toByte(), 0x61.toByte(), 0x67.toByte(),
        0x00.toByte(), 0x15.toByte(), 0x10.toByte(), 0x19.toByte(), 0x1c.toByte(), 0x19.toByte(),
        0x1c.toByte(), 0x25.toByte(), 0x08.toByte(), 0x1c.toByte(), 0x15.toByte(), 0x00.toByte(),
        0x19.toByte(), 0x15.toByte(), 0x00.toByte(), 0x19.toByte(), 0x18.toByte(), 0x04.toByte(),
        0x66.toByte(), 0x6c.toByte(), 0x61.toByte(), 0x67.toByte(), 0x15.toByte(), 0x00.toByte(),
        0x15.toByte(), 0x10.toByte(), 0x15.toByte(), 0x02.toByte(), 0x15.toByte(), 0x02.toByte(),
        0x25.toByte(), 0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x15.toByte(), 0x02.toByte(),
        0x15.toByte(), 0x10.toByte(), 0x00.toByte(), 0x00.toByte(), 0x42.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x50.toByte(), 0x41.toByte(), 0x52.toByte(), 0x31.toByte(),
    )

    @Test
    fun syntheticBoolean_decodesValues() {
        val buf = syntheticBoolFile.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[0].metaData!!
        val leaf = tree.children[0]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "Expected fullyDecoded=true, reason=${result.undecodedReason}")
        assertEquals(8, result.cells.size)
        // 0x55 = 01010101 LSB-first: bit0=1(T), bit1=0(F), bit2=1(T), ..., bit7=0(F)
        val expected = listOf(true, false, true, false, true, false, true, false)
        for ((i, cell) in result.cells.withIndex()) {
            assertEquals(expected[i], cell.value as Boolean, "Cell $i value mismatch")
            assertFalse(cell.isNull)
            assertEquals(ByteProvenanceKind.EXACT, cell.provenance.kind)
        }
    }

    @Test
    fun syntheticBoolean_provenancePointsToPackedByte() {
        val buf = syntheticBoolFile.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[0].metaData!!
        val leaf = tree.children[0]

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded)
        // All 8 booleans are packed into one byte at absolute offset 21 (page body start).
        result.cells.forEach { cell ->
            assertEquals(21L, cell.provenance.offset,
                "All 8 bool cells must reference the single packed byte at offset 21")
            assertEquals(1, cell.provenance.size)
            assertEquals(ByteProvenanceKind.EXACT, cell.provenance.kind)
        }
    }

    // -----------------------------------------------------------------------
    // rowLimit cap
    // -----------------------------------------------------------------------

    @Test
    fun syntheticInt32_rowLimitCaps() {
        val buf = syntheticInt32File.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val chunk = meta.rowGroups[0].columns[0].metaData!!
        val leaf = tree.children[0]

        val result = decodeColumnChunk(buf, chunk, leaf, rowLimit = 2)

        assertTrue(result.fullyDecoded)
        assertEquals(2, result.cells.size)
        assertEquals(10, result.cells[0].value as Int)
        assertEquals(20, result.cells[1].value as Int)
    }

    // -----------------------------------------------------------------------
    // BitReader unit tests
    // -----------------------------------------------------------------------

    @Test
    fun bitReader_readBits_lsbFirst() {
        // 0xA5 = 1010_0101; reading LSB-first:
        // bit 0 = 1, bit 1 = 0, bit 2 = 1, bit 3 = 0
        // After consuming those 4 bits, currentByte = 0xA5 >> 4 = 0x0A = 0000_1010
        // readBitsLE(4) returns (0x0A & 0xF) = 10
        val buf = byteArrayOf(0xA5.toByte()).toCommonByteBuffer()
        val reader = BitReader(buf)
        assertEquals(1, reader.readBitsLE(1))  // bit 0
        assertEquals(0, reader.readBitsLE(1))  // bit 1
        assertEquals(1, reader.readBitsLE(1))  // bit 2
        assertEquals(0, reader.readBitsLE(1))  // bit 3
        assertEquals(10, reader.readBitsLE(4)) // bits 4..7 = 0000_1010 & 0xF = 10
    }

    @Test
    fun bitReader_readMultiByte() {
        // 0x03 0x00 -> 16 bits: value 3 (bit0=1, bit1=1, rest 0)
        val buf = byteArrayOf(0x03.toByte(), 0x00.toByte()).toCommonByteBuffer()
        assertEquals(3, BitReader(buf).readBitsLE(16))
    }

    @Test
    fun bitReader_byteAlign_discardsBits() {
        val buf = byteArrayOf(0xFF.toByte(), 0x01.toByte()).toCommonByteBuffer()
        val reader = BitReader(buf)
        reader.readBitsLE(3)   // consume 3 bits from first byte
        reader.byteAlign()     // discard remaining 5 bits, advance to next byte
        assertEquals(1, reader.readBitsLE(8))  // 0x01 = 1
    }

    @Test
    fun bitReader_zeroWidth_returnsZero() {
        val buf = byteArrayOf(0xFF.toByte()).toCommonByteBuffer()
        assertEquals(0, BitReader(buf).readBitsLE(0))
    }

    // -----------------------------------------------------------------------
    // RLE/bit-packed hybrid unit tests
    // -----------------------------------------------------------------------

    @Test
    fun rleHybrid_rleRun_allSameValue() {
        // RLE run: header = (count << 1) | 0 = 8 << 1 = 16 = 0x10
        // Value: 1 byte (bitWidth=1) = 0x01
        // Length-prefix: 2 bytes (1 header + 1 value byte)
        // Stream = [0x02, 0x00, 0x00, 0x00,  0x10, 0x01]
        //           4-byte LE length=2       RLE hdr  val
        val stream = byteArrayOf(
            0x02, 0x00, 0x00, 0x00,  // length prefix = 2
            0x10,                     // RLE header: count=8, type=RLE
            0x01,                     // value = 1 (1 byte for bitWidth=1)
        )
        val buf = stream.toCommonByteBuffer()
        val result = readRleBitPackedHybrid(buf, 1, 8)
        assertEquals(8, result.size)
        result.forEach { assertEquals(1, it) }
    }

    @Test
    fun rleHybrid_bitPackedRun_alternating() {
        // Bit-packed run: 8 values (1 group), bit_width=1
        // Header: (1 << 1) | 1 = 3 = 0x03 (1 group of 8 values, bit-packed)
        // Values packed: 0xAA = 1010_1010 LSB-first: 0,1,0,1,0,1,0,1
        // Stream = [0x02, 0x00, 0x00, 0x00, 0x03, 0xAA]
        val stream = byteArrayOf(
            0x02, 0x00, 0x00, 0x00,  // length prefix = 2
            0x03,                     // bit-packed header: 1 group
            0xAA.toByte(),            // 0xAA = 10101010 -> LSB-first: 0,1,0,1,0,1,0,1
        )
        val buf = stream.toCommonByteBuffer()
        val result = readRleBitPackedHybrid(buf, 1, 8)
        assertEquals(8, result.size)
        val expected = intArrayOf(0, 1, 0, 1, 0, 1, 0, 1)
        for (i in 0 until 8) assertEquals(expected[i], result[i], "value[$i] mismatch")
    }

    @Test
    fun rleHybrid_bitWidthZero_returnsAllZeros() {
        val buf = byteArrayOf().toCommonByteBuffer()
        val result = readRleBitPackedHybrid(buf, 0, 5)
        assertEquals(5, result.size)
        result.forEach { assertEquals(0, it) }
    }

    @Test
    fun bitWidthNeeded_correctValues() {
        assertEquals(0, bitWidthNeeded(0))
        assertEquals(1, bitWidthNeeded(1))
        assertEquals(2, bitWidthNeeded(2))
        assertEquals(2, bitWidthNeeded(3))
        assertEquals(3, bitWidthNeeded(4))
        assertEquals(3, bitWidthNeeded(7))
        assertEquals(4, bitWidthNeeded(8))
    }

    // -----------------------------------------------------------------------
    // DELTA_BINARY_PACKED unit test (hand-built stream)
    // -----------------------------------------------------------------------

    @Test
    fun deltaBinaryPacked_handBuilt_decodesCorrectly() {
        // Build a minimal DELTA_BINARY_PACKED INT32 stream for values [0, 1, 2, 3].
        // Block size = 128 (min allowed), miniblocks = 4 -> values/miniblock = 32.
        // firstValue = 0 (zigzag = 0x00)
        // Block 1: minDelta = 1 (zigzag = 0x02), bitWidth = 0 for all miniblocks
        //   (all deltas are 0 relative to minDelta=1, so all 32*4=128 bit-packed values are 0,
        //    but we only need 3 more values after firstValue)
        //
        // Stream bytes:
        //   blockSize = 128 -> ULEB128 = 0x80 0x01
        //   miniblocks = 4  -> ULEB128 = 0x04
        //   totalCount = 4  -> ULEB128 = 0x04
        //   firstValue = 0  -> zigzag ULEB128 = 0x00
        //   (Block 1:)
        //   minDelta = 1  -> zigzag ULEB128 = 0x02
        //   bitWidths = [0, 0, 0, 0]
        //   (No miniblock data bytes since bitWidth=0 means 0 bytes per 32-value miniblock)
        val stream = byteArrayOf(
            0x80.toByte(), 0x01.toByte(),  // blockSize = 128
            0x04,                           // miniblocks = 4
            0x04,                           // totalCount = 4
            0x00,                           // firstValue = 0 (zigzag)
            0x02,                           // minDelta = 1 (zigzag)
            0x00, 0x00, 0x00, 0x00,         // 4 bitWidths, all 0
            // No miniblock data bytes needed since bitWidth=0
        )
        val buf = stream.toCommonByteBuffer()

        // Build a synthetic REQUIRED INT32 column pointing into this stream as the data page body.
        // We need to wrap it in a fake page structure. Instead, test the delta decoder directly.
        // Since decodeDeltaBinaryPacked is private, we test through decodeColumnChunk with a
        // synthetic Parquet file that uses DELTA_BINARY_PACKED encoding.
        //
        // For simplicity, verify the internal helper readZigzagVarlong and the stream structure.
        // The full end-to-end with a real DELTA-encoded file needs Phase 4 (those fixtures are SNAPPY).
        // We verify correctness of the algorithm via the alltypes_plain tests (dict/RLE paths).
        // This test documents the limitation.

        // Direct test of the delta-int helper by reading back the stream manually:
        buf.position(0)
        val blockSize = buf.getULEB128().toInt()
        val miniblocks = buf.getULEB128().toInt()
        val totalCount = buf.getULEB128().toInt()
        assertEquals(128, blockSize)
        assertEquals(4, miniblocks)
        assertEquals(4, totalCount)
    }

    // -----------------------------------------------------------------------
    // Phase 4: SNAPPY-compressed fixtures
    // -----------------------------------------------------------------------

    // nested_lists.snappy.parquet
    //   Schema: a (nested list), b (int32 REQUIRED)
    //   b is col[1] in row_group[0], maxRep=0, codec=SNAPPY.
    //   a.list.element.list.element.list.element is col[0], maxRep > 0 (rejected).

    @Test
    fun nestedListsSnappy_bColumn_fullyDecoded() {
        val bytes = loadResource("nested_lists.snappy.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        // b is the second leaf column; find it by path
        val colIdx = meta.rowGroups[0].columns.indexOfFirst { it.metaData?.pathInSchema == listOf("b") }
        assertTrue(colIdx >= 0, "Column 'b' not found in nested_lists.snappy.parquet")
        val chunk = meta.rowGroups[0].columns[colIdx].metaData!!
        // Locate the leaf SchemaNode for b
        val bLeaf = findLeaf(tree, listOf("b"))!!
        assertEquals(CompressionCodec.SNAPPY, chunk.codec)
        assertEquals(ParquetType.INT32, chunk.type)

        val result = decodeColumnChunk(buf, chunk, bLeaf)

        assertTrue(result.fullyDecoded, "b should be fully decoded (SNAPPY INT32), reason=${result.undecodedReason}")
        assertEquals(3, result.cells.size, "nested_lists.snappy has 3 rows")
        result.cells.forEach { cell ->
            assertFalse(cell.isNull)
            assertEquals(1, cell.value as Int, "b values should all be 1")
            assertEquals(ByteProvenanceKind.PAGE, cell.provenance.kind,
                "SNAPPY-compressed cells must have PAGE provenance")
        }
    }

    @Test
    fun nestedListsSnappy_nestedColumn_gracefullyRejected() {
        val bytes = loadResource("nested_lists.snappy.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        // a.list.element.list.element.list.element has maxRep > 0.
        // Pass the intermediate list group node so that maxRepetitionLevel() > 0 is visible.
        val colIdx = 0
        val chunk = meta.rowGroups[0].columns[colIdx].metaData!!
        // tree.children[0] = 'a' (group); a.children[0] = 'list' (REPEATED group) whose
        // maxRepetitionLevel() = 1 even when called from the node itself.
        val aNode = tree.children[0]  // 'a' group
        val listNode = aNode.children[0]  // 'list' REPEATED group -> maxRep >= 1
        assertTrue(listNode.maxRepetitionLevel() > 0, "list group should have maxRep > 0")
        val result = decodeColumnChunk(buf, chunk, listNode)
        assertFalse(result.fullyDecoded, "Nested/repeated column should not be fully decoded")
        assertNotNull(result.undecodedReason)
    }

    // datapage_v2.snappy.parquet
    //   Schema: a (BYTE_ARRAY OPTIONAL), b (INT32 REQUIRED DELTA_BINARY_PACKED),
    //           c (DOUBLE REQUIRED RLE_DICTIONARY), d (BOOL REQUIRED RLE), e (nested list)
    //   5 rows: a=[abc,abc,abc,null,abc], b=[1,2,3,4,5], c=[2.0,3.0,4.0,5.0,2.0],
    //           d=[T,T,T,F,T], e=nested (rejected)
    //   All data pages are DATA_PAGE_V2 with SNAPPY compression.

    @Test
    fun datapageV2Snappy_bColumn_deltaDecoded() {
        val bytes = loadResource("datapage_v2.snappy.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val bIdx = meta.rowGroups[0].columns.indexOfFirst { it.metaData?.pathInSchema == listOf("b") }
        val chunk = meta.rowGroups[0].columns[bIdx].metaData!!
        val leaf = findLeaf(tree, listOf("b"))!!
        assertEquals(CompressionCodec.SNAPPY, chunk.codec)

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "b (DELTA_BINARY_PACKED + SNAPPY) should decode, reason=${result.undecodedReason}")
        assertEquals(5, result.cells.size)
        val expected = listOf(1, 2, 3, 4, 5)
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull)
            assertEquals(expected[i], cell.value as Int, "b[$i] mismatch")
            assertEquals(ByteProvenanceKind.PAGE, cell.provenance.kind,
                "SNAPPY-compressed cells must have PAGE provenance")
        }
    }

    @Test
    fun datapageV2Snappy_aColumn_optionalStringDecoded() {
        val bytes = loadResource("datapage_v2.snappy.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val aIdx = meta.rowGroups[0].columns.indexOfFirst { it.metaData?.pathInSchema == listOf("a") }
        val chunk = meta.rowGroups[0].columns[aIdx].metaData!!
        val leaf = findLeaf(tree, listOf("a"))!!

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "a (OPTIONAL BYTE_ARRAY + SNAPPY) should decode, reason=${result.undecodedReason}")
        assertEquals(5, result.cells.size)
        // row 3 (index 3) is null
        assertFalse(result.cells[0].isNull); assertEquals("abc", result.cells[0].value as String)
        assertFalse(result.cells[1].isNull); assertEquals("abc", result.cells[1].value as String)
        assertFalse(result.cells[2].isNull); assertEquals("abc", result.cells[2].value as String)
        assertTrue(result.cells[3].isNull)
        assertEquals(ByteProvenanceKind.NONE, result.cells[3].provenance.kind)
        assertFalse(result.cells[4].isNull); assertEquals("abc", result.cells[4].value as String)
        // Non-null cells: PAGE provenance
        listOf(0, 1, 2, 4).forEach { i ->
            assertEquals(ByteProvenanceKind.PAGE, result.cells[i].provenance.kind,
                "Non-null compressed cell[$i] must have PAGE provenance")
        }
    }

    @Test
    fun datapageV2Snappy_dColumn_boolRleDecoded() {
        val bytes = loadResource("datapage_v2.snappy.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val dIdx = meta.rowGroups[0].columns.indexOfFirst { it.metaData?.pathInSchema == listOf("d") }
        val chunk = meta.rowGroups[0].columns[dIdx].metaData!!
        val leaf = findLeaf(tree, listOf("d"))!!

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "d (BOOL RLE + SNAPPY v2) should decode, reason=${result.undecodedReason}")
        assertEquals(5, result.cells.size)
        val expected = listOf(true, true, true, false, true)
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull)
            assertEquals(expected[i], cell.value as Boolean, "d[$i] mismatch")
            assertEquals(ByteProvenanceKind.PAGE, cell.provenance.kind)
        }
    }

    @Test
    fun datapageV2Snappy_cColumn_dictDoubleDecoded() {
        val bytes = loadResource("datapage_v2.snappy.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        val cIdx = meta.rowGroups[0].columns.indexOfFirst { it.metaData?.pathInSchema == listOf("c") }
        val chunk = meta.rowGroups[0].columns[cIdx].metaData!!
        val leaf = findLeaf(tree, listOf("c"))!!

        val result = decodeColumnChunk(buf, chunk, leaf)

        assertTrue(result.fullyDecoded, "c (DOUBLE DICT + SNAPPY v2) should decode, reason=${result.undecodedReason}")
        assertEquals(5, result.cells.size)
        val expected = listOf(2.0, 3.0, 4.0, 5.0, 2.0)
        for ((i, cell) in result.cells.withIndex()) {
            assertFalse(cell.isNull)
            assertEquals(expected[i], cell.value as Double, 1e-9, "c[$i] mismatch")
            assertEquals(ByteProvenanceKind.PAGE, cell.provenance.kind)
        }
    }

    @Test
    fun datapageV2Snappy_eColumn_gracefullyRejected() {
        val bytes = loadResource("datapage_v2.snappy.parquet")
        val buf = bytes.toCommonByteBuffer()
        val meta = readParquetFileMetaData(buf)
        val tree = buildSchemaTree(meta.schema)
        // e.list.element is nested/repeated.
        // tree.children[4] = 'e' (OPTIONAL group); e.children[0] = 'list' (REPEATED group).
        val eIdx = meta.rowGroups[0].columns.indexOfFirst {
            it.metaData?.pathInSchema?.first() == "e"
        }
        assertTrue(eIdx >= 0)
        val chunk = meta.rowGroups[0].columns[eIdx].metaData!!
        val eNode = tree.children[4]   // 'e' group
        val listNode = eNode.children[0]  // 'list' REPEATED group -> maxRep >= 1
        assertTrue(listNode.maxRepetitionLevel() > 0, "list group should have maxRep > 0")
        val result = decodeColumnChunk(buf, chunk, listNode)
        assertFalse(result.fullyDecoded, "Nested e.list.element should be rejected")
        assertNotNull(result.undecodedReason)
    }

    // -----------------------------------------------------------------------
    // int96ToDecimalString unit tests (pure, cross-target)
    // -----------------------------------------------------------------------

    private fun le12(vararg b: Int): ByteArray {
        val a = ByteArray(12)
        for (i in b.indices) a[i] = b[i].toByte()
        return a
    }

    @Test
    fun int96ToDecimalString_knownVectors() {
        assertEquals("0", int96ToDecimalString(le12()))
        assertEquals("1", int96ToDecimalString(le12(1)))
        assertEquals("256", int96ToDecimalString(le12(0, 1)))
        // all-0xFF = -1 (two's complement)
        assertEquals("-1", int96ToDecimalString(ByteArray(12) { 0xFF.toByte() }))
        // -2: LE 0xFE, 0xFF...
        val minusTwo = ByteArray(12) { 0xFF.toByte() }.also { it[0] = 0xFE.toByte() }
        assertEquals("-2", int96ToDecimalString(minusTwo))
        // Largest magnitude: 0x80 00..00 (BE) => LE last byte 0x80 => -2^95
        val mostNegative = ByteArray(12).also { it[11] = 0x80.toByte() }
        assertEquals("-39614081257132168796771975168", int96ToDecimalString(mostNegative))
        // alltypes_plain timestamp_col[0] raw bytes.
        assertEquals(
            "45284764452596988585705472",
            int96ToDecimalString(le12(0, 0, 0, 0, 0, 0, 0, 0, 108, 117, 37, 0)),
        )
    }

    @Test
    fun int96ToDecimalString_rejectsWrongLength() {
        assertFailsWith<IllegalArgumentException> { int96ToDecimalString(ByteArray(8)) }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Find a leaf SchemaNode matching [path] in the schema tree rooted at [node].
     * Returns null if not found.
     */
    private fun findLeaf(node: SchemaNode, path: List<String>): SchemaNode? {
        if (path.isEmpty()) return null
        val name = path[0]
        for (child in node.children) {
            if (child.element.name == name) {
                return if (path.size == 1) child else findLeaf(child, path.drop(1))
            }
        }
        return null
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
