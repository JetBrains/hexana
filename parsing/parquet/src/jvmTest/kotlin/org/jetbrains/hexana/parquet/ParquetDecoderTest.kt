package org.jetbrains.hexana.parquet

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Parquet metadata decoding against real Apache Parquet golden fixtures
 * from https://github.com/apache/parquet-testing.
 *
 * Fixtures:
 *   alltypes_plain.parquet         (1851 B, uncompressed, flat schema, 8 rows, 1 row group)
 *   nested_lists.snappy.parquet    (881 B, nested/Dremel schema)
 *   datapage_v2.snappy.parquet     (1165 B, DataPageHeaderV2)
 *
 * Metadata only; page bodies are not decompressed.
 */
class ParquetDecoderTest {

    private fun loadResource(name: String): ByteArray {
        val stream = ParquetDecoderTest::class.java.classLoader.getResourceAsStream(name)
            ?: error("Test resource not found: $name")
        return stream.readBytes()
    }

    // -----------------------------------------------------------------------
    // alltypes_plain.parquet
    // Flat schema: "schema" root + 11 leaf columns.
    // Expected leaf column names: id, bool_col, tinyint_col, smallint_col, int_col,
    //   bigint_col, float_col, double_col, date_string_col, string_col, timestamp_col.
    // 1 row group, 8 rows.
    // -----------------------------------------------------------------------

    @Test
    fun alltypesPlain_footerDecode() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        // Schema: 12 elements total (1 root + 11 leaf columns)
        assertEquals(12, meta.schema.size, "alltypes_plain should have 12 SchemaElements (root + 11 leaves)")
    }

    @Test
    fun alltypesPlain_schemaRootElement() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        val root = meta.schema[0]
        assertEquals("schema", root.name, "Root schema element name should be 'schema'")
        assertNull(root.type, "Root schema element has no Parquet type (it's a group)")
        assertEquals(11, root.numChildren, "Root should have 11 children")
    }

    @Test
    fun alltypesPlain_leafColumnNames() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        // Elements 1..11 are the leaf columns (no nested groups in this flat schema)
        val leafNames = meta.schema.drop(1).map { it.name }
        assertEquals(
            listOf("id", "bool_col", "tinyint_col", "smallint_col", "int_col",
                "bigint_col", "float_col", "double_col", "date_string_col", "string_col", "timestamp_col"),
            leafNames,
            "Leaf column names mismatch",
        )
    }

    @Test
    fun alltypesPlain_leafColumnTypes() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        val leaves = meta.schema.drop(1)
        assertEquals(ParquetType.INT32, leaves[0].type, "id should be INT32")
        assertEquals(ParquetType.BOOLEAN, leaves[1].type, "bool_col should be BOOLEAN")
        assertEquals(ParquetType.INT32, leaves[2].type, "tinyint_col should be INT32")
        assertEquals(ParquetType.INT32, leaves[3].type, "smallint_col should be INT32")
        assertEquals(ParquetType.INT32, leaves[4].type, "int_col should be INT32")
        assertEquals(ParquetType.INT64, leaves[5].type, "bigint_col should be INT64")
        assertEquals(ParquetType.FLOAT, leaves[6].type, "float_col should be FLOAT")
        assertEquals(ParquetType.DOUBLE, leaves[7].type, "double_col should be DOUBLE")
        assertEquals(ParquetType.BYTE_ARRAY, leaves[8].type, "date_string_col should be BYTE_ARRAY")
        assertEquals(ParquetType.BYTE_ARRAY, leaves[9].type, "string_col should be BYTE_ARRAY")
        assertEquals(ParquetType.INT96, leaves[10].type, "timestamp_col should be INT96")
    }

    @Test
    fun alltypesPlain_rowGroupCount() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        assertEquals(1, meta.rowGroups.size, "alltypes_plain should have 1 row group")
    }

    @Test
    fun alltypesPlain_rowCount() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        assertEquals(8L, meta.rowGroups[0].numRows, "Row group should have 8 rows")
    }

    @Test
    fun alltypesPlain_columnChunkCount() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        assertEquals(11, meta.rowGroups[0].columns.size, "Row group should have 11 column chunks")
    }

    @Test
    fun alltypesPlain_columnMetaData() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        val rg = meta.rowGroups[0]
        // Every column chunk should have metadata
        for ((i, col) in rg.columns.withIndex()) {
            assertNotNull(col.metaData, "Column chunk $i should have ColumnMetaData")
        }

        // id column (INT32): verify key metadata fields
        val idMeta = rg.columns[0].metaData!!
        assertEquals(ParquetType.INT32, idMeta.type, "id column type should be INT32")
        assertEquals(8L, idMeta.numValues, "id column should have 8 values")
        assertTrue(idMeta.dataPageOffset > 0, "id column data page offset should be > 0")
        assertTrue(idMeta.totalCompressedSize > 0, "id column compressed size should be > 0")
    }

    @Test
    fun alltypesPlain_codec() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        // alltypes_plain is uncompressed
        for ((i, col) in meta.rowGroups[0].columns.withIndex()) {
            assertEquals(CompressionCodec.UNCOMPRESSED, col.metaData?.codec,
                "Column $i should be UNCOMPRESSED")
        }
    }

    @Test
    fun alltypesPlain_createdBy() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        assertNotNull(meta.createdBy, "created_by should be set")
        assertTrue(meta.createdBy!!.isNotEmpty(), "created_by should not be empty")
    }

    @Test
    fun alltypesPlain_schemaTree() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        val tree = buildSchemaTree(meta.schema)
        assertEquals("schema", tree.element.name)
        assertEquals(11, tree.children.size, "Schema tree root should have 11 children")
        assertTrue(tree.children.all { it.isLeaf }, "All children of flat schema should be leaves")
        assertEquals("id", tree.children[0].element.name)
        assertEquals("timestamp_col", tree.children[10].element.name)
    }

    // -----------------------------------------------------------------------
    // nested_lists.snappy.parquet — nested schema with Dremel levels
    // -----------------------------------------------------------------------

    @Test
    fun nestedLists_footerDecode() {
        val bytes = loadResource("nested_lists.snappy.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        // Nested schema: should have more than 1 schema element and at least 1 row group
        assertTrue(meta.schema.size > 1, "nested_lists should have multiple schema elements")
        assertTrue(meta.rowGroups.isNotEmpty(), "nested_lists should have at least 1 row group")
    }

    @Test
    fun nestedLists_schemaTree() {
        val bytes = loadResource("nested_lists.snappy.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        val tree = buildSchemaTree(meta.schema)
        // Root should have some children
        assertTrue(tree.children.isNotEmpty(), "nested_lists schema tree should have children")
        // At least one child should be a non-leaf (nested group)
        val hasGroup = tree.children.any { !it.isLeaf }
        assertTrue(hasGroup, "nested_lists schema tree should contain at least one nested group")
    }

    // -----------------------------------------------------------------------
    // datapage_v2.snappy.parquet — DataPageHeaderV2
    // -----------------------------------------------------------------------

    @Test
    fun datapageV2_footerDecode() {
        val bytes = loadResource("datapage_v2.snappy.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        assertTrue(meta.schema.size > 1, "datapage_v2 should have multiple schema elements")
        assertTrue(meta.rowGroups.isNotEmpty(), "datapage_v2 should have at least 1 row group")
        // Snappy compressed — codec should be SNAPPY
        val codec = meta.rowGroups[0].columns[0].metaData?.codec
        assertEquals(CompressionCodec.SNAPPY, codec, "datapage_v2 columns should be SNAPPY compressed")
    }

    @Test
    fun alltypesPlain_dremelLevels() {
        val bytes = loadResource("alltypes_plain.parquet")
        val meta = readParquetFileMetaData(bytes.toCommonByteBuffer())

        val tree = buildSchemaTree(meta.schema)
        // In a flat schema with REQUIRED/OPTIONAL columns, max def levels are 0 or 1
        for (child in tree.children) {
            val defLevel = child.maxDefinitionLevel()
            assertTrue(defLevel in 0..1, "Flat schema def level should be 0 or 1, got $defLevel")
            assertEquals(0, child.maxRepetitionLevel(), "Flat schema has no REPEATED fields")
        }
    }
}