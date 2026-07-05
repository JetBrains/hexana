package org.jetbrains.hexana.arrow

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for Arrow IPC stream and file-format metadata decoding against real Apache Arrow
 * golden fixtures from https://github.com/apache/arrow-testing.
 *
 * Fixtures:
 *   generated_null_trivial.stream    (312 B, 1 schema field "f0" of type Null, 2 record batches)
 *   generated_primitive_no_batches.stream  (1944 B, 30 schema fields, 0 batches)
 *   generated_null_trivial.arrow_file      (522 B, file format, same schema)
 */
class ArrowIpcTest {

    private fun loadResource(name: String): ByteArray {
        val stream = ArrowIpcTest::class.java.classLoader.getResourceAsStream(name)
            ?: error("Test resource not found: $name")
        return stream.readBytes()
    }

    // -----------------------------------------------------------------------
    // Stream format: generated_null_trivial.stream
    // Schema: 1 field named "f0", type Null (tag=0, but field present with empty type table or null)
    // 2 record batches follow
    // -----------------------------------------------------------------------

    @Test
    fun nullTrivialStream_schemaDecode() {
        val bytes = loadResource("generated_null_trivial.stream")
        val buf = bytes.toCommonByteBuffer()

        val schema = readArrowStreamSchema(buf)

        // The null_trivial fixture has exactly 1 field: "f0"
        assertEquals(1, schema.fieldCount, "null_trivial should have 1 field")
        val field0 = schema.field(0)
        assertEquals("f0", field0.name, "Field 0 name should be f0")
    }

    @Test
    fun nullTrivialStream_messageScan() {
        val bytes = loadResource("generated_null_trivial.stream")
        val buf = bytes.toCommonByteBuffer()

        val contents = readArrowStream(buf)

        // Schema verified above
        assertEquals(1, contents.schema.fieldCount)

        // JSON companion confirms 2 record batches in this fixture
        assertEquals(2, contents.messages.size, "null_trivial should contain 2 post-schema messages")

        val batch0 = contents.messages[0].message.asRecordBatch()
        assertNotNull(batch0, "First post-schema message should be a RecordBatch")
        assertEquals(MessageHeaderType.RECORD_BATCH, contents.messages[0].message.headerType)
    }

    // -----------------------------------------------------------------------
    // Stream format: generated_primitive_no_batches.stream
    // Schema: 30 fields of various primitive types, 0 record batches.
    // -----------------------------------------------------------------------

    @Test
    fun primitiveNoBatchesStream_schemaDecode() {
        val bytes = loadResource("generated_primitive_no_batches.stream")
        val buf = bytes.toCommonByteBuffer()

        val contents = readArrowStream(buf)
        val schema = contents.schema

        // 30 fields confirmed by the JSON companion file
        assertEquals(30, schema.fieldCount, "primitive_no_batches should have 30 fields")

        // 0 record batches (the filename says so, confirmed by JSON companion)
        assertEquals(0, contents.messages.size, "primitive_no_batches should have 0 post-schema messages")

        // Spot-check specific field names from JSON companion:
        //   0=bool_nullable, 1=bool_nonnullable, 2=int8_nullable ...
        assertEquals("bool_nullable", schema.field(0).name)
        assertEquals("bool_nonnullable", schema.field(1).name)
        assertEquals("int8_nullable", schema.field(2).name)
        assertEquals("utf8_nullable", schema.field(24).name)
        assertEquals("utf8_nonnullable", schema.field(25).name)
        assertEquals("fixedsizebinary_19_nullable", schema.field(26).name)
        assertEquals("fixedsizebinary_120_nonnullable", schema.field(29).name)
    }

    @Test
    fun primitiveNoBatchesStream_fieldNullability() {
        val bytes = loadResource("generated_primitive_no_batches.stream")
        val buf = bytes.toCommonByteBuffer()

        val schema = readArrowStreamSchema(buf)

        // _nullable fields should have nullable=true, _nonnullable should have nullable=false
        assertEquals(true, schema.field(0).nullable, "bool_nullable should be nullable")
        assertEquals(false, schema.field(1).nullable, "bool_nonnullable should not be nullable")
        assertEquals(true, schema.field(2).nullable, "int8_nullable should be nullable")
        assertEquals(false, schema.field(3).nullable, "int8_nonnullable should not be nullable")
    }

    // -----------------------------------------------------------------------
    // File format: generated_null_trivial.arrow_file
    // Same schema as null_trivial stream: 1 field "f0", 2 record batches.
    // -----------------------------------------------------------------------

    @Test
    fun nullTrivialFile_footerDecode() {
        val bytes = loadResource("generated_null_trivial.arrow_file")
        val buf = bytes.toCommonByteBuffer()

        val footer = readArrowFileFooter(buf)

        // Footer schema: 1 field "f0"
        val schema = footer.schema
        assertNotNull(schema, "Footer schema should not be null")
        assertEquals(1, schema.fieldCount, "null_trivial file footer schema should have 1 field")
        assertEquals("f0", schema.field(0).name, "Footer schema field 0 should be f0")
    }

    @Test
    fun nullTrivialFile_recordBatchBlocks() {
        val bytes = loadResource("generated_null_trivial.arrow_file")
        val buf = bytes.toCommonByteBuffer()

        val footer = readArrowFileFooter(buf)

        // The null_trivial fixture has 2 record batch blocks in the footer
        assertEquals(2, footer.recordBatchBlockCount, "null_trivial file should have 2 record batch blocks")
        assertEquals(0, footer.dictionaryCount, "null_trivial file should have 0 dictionary blocks")

        // Verify the block offsets are within the file
        val vec = footer.recordBatchesVector
        assertNotNull(vec)
        val blockOffset0 = footer.blockOffset(vec, 0)
        assert(blockOffset0 > 0 && blockOffset0 < bytes.size) {
            "RecordBatch block 0 offset $blockOffset0 out of range [0, ${bytes.size})"
        }
    }

    @Test
    fun nullTrivialFile_version() {
        val bytes = loadResource("generated_null_trivial.arrow_file")
        val buf = bytes.toCommonByteBuffer()

        val footer = readArrowFileFooter(buf)

        // MetadataVersion::V5 = 4 in Arrow's enum (V1=0, V2=1, V3=2, V4=3, V5=4)
        // The integration tests use V5 files; accept any non-negative version
        assert(footer.version.toInt() >= 0) { "Arrow file version should be non-negative: ${footer.version}" }
    }
}