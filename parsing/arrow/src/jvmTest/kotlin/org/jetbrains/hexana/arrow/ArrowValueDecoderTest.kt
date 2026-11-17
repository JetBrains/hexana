package org.jetbrains.hexana.arrow

import org.jetbrains.hexana.io.ByteProvenanceKind
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Golden-provenance tests for [decodeRecordBatch] against the Apache Arrow Testing fixture
 * `generated_primitive.stream` (20280 B, 30 fields, 2 record batches of 17 rows).
 *
 * Source: https://github.com/apache/arrow-testing
 *
 * Golden values per field, batch 0 (row 0 is index 0):
 *   int32_nonnullable (schema index 7):  [-2147483648, 2147483647, -523457287, -60798598, 525675187, ...]
 *   int32_nullable    (schema index 6):  [-2147483648, null, -1777158217, -984917788, -1533539476, ...]
 *   float64_nonnullable (index 21):      [471.617, -1174.894, 2613.999, ...]
 *   utf8_nonnullable  (index 25):        ["co矢2p矢m", "wÂ€acrd", "kjd1dlô", ...]
 *   bool_nullable     (index 0):         [null, null, true, null, null, ...]
 */
class ArrowValueDecoderTest {

    private fun loadResource(name: String): ByteArray {
        val stream = ArrowValueDecoderTest::class.java.classLoader.getResourceAsStream(name)
            ?: error("Test resource not found: $name")
        return stream.readBytes()
    }

    private fun loadPrimitiveStream(): Triple<ByteArray, ArrowSchema, ArrowEncapsulatedMessage> {
        val bytes = loadResource("generated_primitive.stream")
        val buf = bytes.toCommonByteBuffer()
        val contents = readArrowStream(buf)
        // First post-schema message is a RecordBatch
        val msg = contents.messages.first { it.message.headerType == MessageHeaderType.RECORD_BATCH }
        return Triple(bytes, contents.schema, msg)
    }

    // -----------------------------------------------------------------------
    // Column count and names
    // -----------------------------------------------------------------------

    @Test
    fun primitiveStream_columnCount() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch, rowLimit = 1)
        // 30 top-level fields => 30 ColumnValues results (no nested fields in this fixture)
        assertEquals(30, cols.size, "Expected 30 columns from generated_primitive.stream")
    }

    @Test
    fun primitiveStream_firstColumnName() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch, rowLimit = 1)
        assertEquals(listOf("bool_nullable"), cols[0].leafPath)
        assertEquals(listOf("bool_nonnullable"), cols[1].leafPath)
        assertEquals(listOf("int32_nonnullable"), cols[7].leafPath)
    }

    // -----------------------------------------------------------------------
    // int32_nonnullable (schema index 7): all valid, Int32 values
    // -----------------------------------------------------------------------

    @Test
    fun int32Nonnullable_goldenValues() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[7]  // int32_nonnullable

        assertTrue(col.fullyDecoded, "int32_nonnullable should be fullyDecoded; reason=${col.undecodedReason}")
        assertEquals(17, col.cells.size, "Batch 0 has 17 rows")

        // Golden values from JSON companion
        assertEquals(-2147483648, col.cells[0].value, "row 0")
        assertEquals(2147483647,  col.cells[1].value, "row 1")
        assertEquals(-523457287,  col.cells[2].value, "row 2")
        assertEquals(-60798598,   col.cells[3].value, "row 3")
        assertEquals(525675187,   col.cells[4].value, "row 4")

        // No nulls
        col.cells.forEach { cell -> assertTrue(!cell.isNull, "int32_nonnullable should have no nulls") }
    }

    @Test
    fun int32Nonnullable_exactProvenance() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[7]  // int32_nonnullable

        // Every cell must have EXACT provenance with size 4
        col.cells.forEach { cell ->
            assertEquals(ByteProvenanceKind.EXACT, cell.provenance.kind,
                "int32_nonnullable provenance must be EXACT")
            assertEquals(4, cell.provenance.size, "int32 cell size must be 4")
        }

        // Provenance offsets must be contiguous (stride 4) within a 4*17=68 byte range
        val base = col.cells[0].provenance.offset
        for (i in col.cells.indices) {
            assertEquals(base + i.toLong() * 4, col.cells[i].provenance.offset,
                "int32_nonnullable provenance offsets must be stride-4 at row $i")
        }

        // Golden read-back: reading 4 bytes at the provenance offset reproduces the value
        val cell0 = col.cells[0]
        val readBack = buf.run {
            val pos = position()
            position(cell0.provenance.offset.toInt())
            val v = getIntLE()
            position(pos)
            v
        }
        assertEquals(-2147483648, readBack, "Read-back of cell0 from file must match decoded value")
    }

    // -----------------------------------------------------------------------
    // int32_nullable (schema index 6): row 1 is null
    // -----------------------------------------------------------------------

    @Test
    fun int32Nullable_nullAtRow1() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[6]  // int32_nullable

        assertTrue(col.fullyDecoded, "int32_nullable should be fullyDecoded")

        // Row 0: not null, value = -2147483648
        assertEquals(-2147483648, col.cells[0].value, "row 0 value")
        assertTrue(!col.cells[0].isNull, "row 0 must not be null")
        assertEquals(ByteProvenanceKind.EXACT, col.cells[0].provenance.kind)

        // Row 1: null (VALIDITY=0 per JSON companion)
        assertTrue(col.cells[1].isNull, "row 1 must be null")
        assertEquals(ByteProvenanceKind.NONE, col.cells[1].provenance.kind)

        // Row 2: not null, value = -1777158217
        assertEquals(-1777158217, col.cells[2].value, "row 2 value")
        assertTrue(!col.cells[2].isNull, "row 2 must not be null")
    }

    // -----------------------------------------------------------------------
    // bool_nullable (schema index 0): rows 0,1,3,4 are null; row 2 = true
    // -----------------------------------------------------------------------

    @Test
    fun boolNullable_goldenValues() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[0]  // bool_nullable

        assertTrue(col.fullyDecoded, "bool_nullable should be fullyDecoded")

        // Rows 0,1,3,4 null per JSON companion (VALIDITY=[0,0,1,0,0,...])
        assertTrue(col.cells[0].isNull, "bool_nullable row 0 must be null")
        assertTrue(col.cells[1].isNull, "bool_nullable row 1 must be null")
        assertTrue(!col.cells[2].isNull, "bool_nullable row 2 must not be null")
        assertEquals(true, col.cells[2].value, "bool_nullable row 2 = true per JSON companion")
        assertTrue(col.cells[3].isNull, "bool_nullable row 3 must be null")
        assertTrue(col.cells[4].isNull, "bool_nullable row 4 must be null")
    }

    @Test
    fun boolNullable_exactProvenance() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[0]  // bool_nullable

        // Non-null cells should have EXACT provenance, size 1
        val nonNull = col.cells.filter { !it.isNull }
        assertTrue(nonNull.isNotEmpty(), "bool_nullable should have some non-null cells")
        nonNull.forEach { cell ->
            assertEquals(ByteProvenanceKind.EXACT, cell.provenance.kind)
            assertEquals(1, cell.provenance.size, "Bool cell provenance size must be 1 (byte)")
        }

        // Null cells have NONE provenance
        col.cells.filter { it.isNull }.forEach { cell ->
            assertEquals(ByteProvenanceKind.NONE, cell.provenance.kind)
        }
    }

    // -----------------------------------------------------------------------
    // float64_nonnullable (schema index 21)
    // -----------------------------------------------------------------------

    @Test
    fun float64Nonnullable_goldenValues() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[21]  // float64_nonnullable

        assertTrue(col.fullyDecoded, "float64_nonnullable should be fullyDecoded")
        assertEquals("Float64", col.typeName)

        // Golden values from JSON companion (3 decimal places)
        assertEquals(471.617, (col.cells[0].value as Double), 1e-3, "float64 row 0")
        assertEquals(-1174.894, (col.cells[1].value as Double), 1e-3, "float64 row 1")
        assertEquals(2613.999, (col.cells[2].value as Double), 1e-3, "float64 row 2")

        // EXACT provenance, size 8
        col.cells.forEach { cell ->
            assertEquals(ByteProvenanceKind.EXACT, cell.provenance.kind)
            assertEquals(8, cell.provenance.size)
        }
    }

    @Test
    fun float64Nonnullable_provenanceReadBack() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[21]  // float64_nonnullable

        val cell0 = col.cells[0]
        val readBack = buf.run {
            val savedPos = position()
            position(cell0.provenance.offset.toInt())
            val v = getDoubleLE()
            position(savedPos)
            v
        }
        assertEquals(cell0.value as Double, readBack, 1e-12, "float64 read-back from provenance offset must match")
    }

    // -----------------------------------------------------------------------
    // utf8_nonnullable (schema index 25)
    // -----------------------------------------------------------------------

    @Test
    fun utf8Nonnullable_goldenValues() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[25]  // utf8_nonnullable

        assertTrue(col.fullyDecoded, "utf8_nonnullable should be fullyDecoded")
        assertEquals("Utf8", col.typeName)

        // Golden values from JSON companion
        assertEquals("co矢2p矢m", col.cells[0].value as String, "utf8 row 0")
        assertEquals("wÂ€acrd",   col.cells[1].value as String, "utf8 row 1")
        assertEquals("kjd1dlô",   col.cells[2].value as String, "utf8 row 2")
    }

    @Test
    fun utf8Nonnullable_exactProvenance() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[25]  // utf8_nonnullable

        col.cells.forEach { cell ->
            assertEquals(ByteProvenanceKind.EXACT, cell.provenance.kind, "utf8 provenance must be EXACT")
        }

        // Read-back: bytes at provenance offset must decode to the same string
        val cell0 = col.cells[0]
        val str0 = col.cells[0].value as String
        val readBack = ByteArray(cell0.provenance.size).also { arr ->
            buf.run {
                val savedPos = position()
                position(cell0.provenance.offset.toInt())
                get(arr, 0, arr.size)
                position(savedPos)
            }
        }.decodeToString()
        assertEquals(str0, readBack, "utf8 read-back from provenance offset must match decoded value")
    }

    // -----------------------------------------------------------------------
    // utf8_nullable (schema index 24): rows 0,4 are null
    // -----------------------------------------------------------------------

    @Test
    fun utf8Nullable_nullRows() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        val cols = decodeRecordBatch(buf, msg.bodyStart, schema, batch)
        val col = cols[24]  // utf8_nullable

        assertTrue(col.fullyDecoded, "utf8_nullable should be fullyDecoded")

        // Rows 0,4 are null per JSON companion (VALIDITY=[0,1,1,1,0,...])
        assertTrue(col.cells[0].isNull, "utf8_nullable row 0 must be null")
        assertTrue(!col.cells[1].isNull, "utf8_nullable row 1 must not be null")
        assertTrue(col.cells[4].isNull, "utf8_nullable row 4 must be null")
    }

    // -----------------------------------------------------------------------
    // No compression: bodyCompression must be null
    // -----------------------------------------------------------------------

    @Test
    fun primitiveStream_noBodyCompression() {
        val (bytes, schema, msg) = loadPrimitiveStream()
        val buf = bytes.toCommonByteBuffer()
        val batch = msg.message.asRecordBatch()!!
        assertEquals(null, batch.bodyCompression, "generated_primitive.stream has no body compression")
    }

    // -----------------------------------------------------------------------
    // Existing ArrowIpcTest coverage still passes (smoke)
    // -----------------------------------------------------------------------

    @Test
    fun primitiveStream_schemaFieldCount() {
        val bytes = loadResource("generated_primitive.stream")
        val buf = bytes.toCommonByteBuffer()
        val contents = readArrowStream(buf)
        assertEquals(30, contents.schema.fieldCount)
    }

    @Test
    fun primitiveStream_batchCount() {
        val bytes = loadResource("generated_primitive.stream")
        val buf = bytes.toCommonByteBuffer()
        val contents = readArrowStream(buf)
        assertEquals(2, contents.messages.size, "generated_primitive.stream should have 2 record batches")
    }
}
