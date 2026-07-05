package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.ArrowBinaryProvider
import org.jetbrains.hexana.providers.ArrowFormat
import org.jetbrains.hexana.providers.ParquetBinaryProvider
import org.jetbrains.hexana.providers.isArrowFileMagic
import org.jetbrains.hexana.providers.isArrowStreamMagic
import org.jetbrains.hexana.providers.isParquetMagic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Integration tests for ParquetTableTabsProvider and ArrowTableTabsProvider.
 *
 * Fixtures:
 *   alltypes_plain.parquet          — 12 schema elements (1 root + 11 leaves), 1 row group, 8 rows
 *   generated_null_trivial.arrow_file — file format, 1 field "f0", 2 record batch blocks
 *   generated_null_trivial.stream   — stream format, 1 field "f0", 2 record batches
 */
class ParquetArrowTabsTest {

    private fun loadResource(name: String): ByteArray {
        val stream = ParquetArrowTabsTest::class.java.classLoader.getResourceAsStream(name)
            ?: error("Test resource not found: $name")
        return stream.readBytes()
    }

    // ------------------------------------------------------------------
    // Magic detection
    // ------------------------------------------------------------------

    @Test fun `isParquetMagic detects parquet file`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        assertTrue(isParquetMagic(buf))
    }

    @Test fun `isArrowFileMagic detects arrow file`() {
        val buf = loadResource("generated_null_trivial.arrow_file").toCommonByteBuffer()
        assertTrue(isArrowFileMagic(buf))
        assertFalse(isArrowStreamMagic(buf))
    }

    @Test fun `isArrowStreamMagic detects arrow stream`() {
        val buf = loadResource("generated_null_trivial.stream").toCommonByteBuffer()
        assertTrue(isArrowStreamMagic(buf))
    }

    // ------------------------------------------------------------------
    // ParquetBinaryProvider
    // ------------------------------------------------------------------

    @Test fun `ParquetBinaryProvider parses alltypes_plain`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val meta = assertNotNull(provider.metadata)
        assertEquals(1, meta.rowGroups.size)
        assertEquals(8L, meta.numRows)
        assertEquals(11, provider.leafElements.size)
    }

    @Test fun `ParquetTableTabsProvider produces Schema, Row Groups, Metadata, Data tabs for alltypes_plain`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val tabs = ParquetTableTabsProvider().tableTabs(provider)
        assertEquals(4, tabs.size)
        assertEquals("Schema", tabs[0].name)
        assertEquals("Row Groups", tabs[1].name)
        assertEquals("Metadata", tabs[2].name)
        assertEquals("Data", tabs[3].name)
    }

    @Test fun `Schema tab has 11 leaf rows`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val tabs = ParquetTableTabsProvider().tableTabs(provider)
        val schemaTab = tabs[0]
        assertEquals(11, schemaTab.rowCount)
        assertEquals(3, schemaTab.columns.size)
    }

    @Test fun `Row Groups tab has children provider`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val tabs = ParquetTableTabsProvider().tableTabs(provider)
        val rgTab = tabs[1]
        assertEquals(1, rgTab.rowCount)
        assertNotNull(rgTab.childrenProvider)
        val children = assertNotNull(rgTab.childrenProvider!!(0))
        assertEquals(11, children.rowCount)
    }

    @Test fun `Metadata tab has 5 rows`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val tabs = ParquetTableTabsProvider().tableTabs(provider)
        assertEquals(5, tabs[2].rowCount)
    }

    // ------------------------------------------------------------------
    // ArrowBinaryProvider — file format
    // ------------------------------------------------------------------

    @Test fun `ArrowBinaryProvider file format reads footer`() {
        val buf = loadResource("generated_null_trivial.arrow_file").toCommonByteBuffer()
        val provider = ArrowBinaryProvider(buf, ArrowFormat.FILE)
        val schema = assertNotNull(provider.schema)
        assertEquals(1, schema.fieldCount)
        assertEquals("f0", schema.field(0).name)
    }

    @Test fun `ArrowTableTabsProvider file format produces tabs`() {
        val buf = loadResource("generated_null_trivial.arrow_file").toCommonByteBuffer()
        val provider = ArrowBinaryProvider(buf, ArrowFormat.FILE)
        val tabs = ArrowTableTabsProvider().tableTabs(provider)
        assertTrue(tabs.size >= 2)
        assertEquals("Schema", tabs[0].name)
        assertEquals(1, tabs[0].rowCount)
    }

    @Test fun `Record Batches tab present for arrow file with batches`() {
        val buf = loadResource("generated_null_trivial.arrow_file").toCommonByteBuffer()
        val provider = ArrowBinaryProvider(buf, ArrowFormat.FILE)
        val tabs = ArrowTableTabsProvider().tableTabs(provider)
        val batchesTab = tabs.firstOrNull { it.name == "Record Batches" }
        assertNotNull(batchesTab)
        assertEquals(2, batchesTab.rowCount)
    }

    // ------------------------------------------------------------------
    // ArrowBinaryProvider — stream format
    // ------------------------------------------------------------------

    @Test fun `ArrowBinaryProvider stream format reads schema`() {
        val buf = loadResource("generated_null_trivial.stream").toCommonByteBuffer()
        val provider = ArrowBinaryProvider(buf, ArrowFormat.STREAM)
        val schema = assertNotNull(provider.schema)
        assertEquals(1, schema.fieldCount)
        assertEquals("f0", schema.field(0).name)
    }

    @Test fun `ArrowTableTabsProvider stream format produces tabs with messages`() {
        val buf = loadResource("generated_null_trivial.stream").toCommonByteBuffer()
        val provider = ArrowBinaryProvider(buf, ArrowFormat.STREAM)
        val tabs = ArrowTableTabsProvider().tableTabs(provider)
        assertTrue(tabs.size >= 2)
        assertEquals("Schema", tabs[0].name)
        val msgTab = tabs.firstOrNull { it.name == "Messages" }
        assertNotNull(msgTab)
        assertEquals(2, msgTab.rowCount)
    }

    // ------------------------------------------------------------------
    // Parquet Data tab
    // ------------------------------------------------------------------

    @Test fun `ParquetTableTabsProvider adds Data tab for alltypes_plain`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val tabs = ParquetTableTabsProvider().tableTabs(provider)
        val dataTab = tabs.firstOrNull { it.name == "Data" }
        assertNotNull(dataTab, "Data tab should be present")
        assertEquals(8, dataTab.rowCount, "alltypes_plain has 8 rows")
    }

    @Test fun `Parquet Data tab columns match schema leaf names`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val tabs = ParquetTableTabsProvider().tableTabs(provider)
        val dataTab = assertNotNull(tabs.firstOrNull { it.name == "Data" })
        // 11 leaf columns (timestamp_col is INT96, decoded as a signed decimal string)
        assertEquals(11, dataTab.columns.size, "Data tab should have 11 columns")
        assertEquals("id", dataTab.columns[0].name, "First column should be 'id'")
    }

    @Test fun `Parquet Data tab id column first cell value is 4`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val table = assertNotNull(provider.decodedTable)
        // "id" is column 0; first row value = 4 (per golden decode in ParquetValueDecoderTest)
        val idColIdx = table.columnNames.indexOf("id")
        assertTrue(idColIdx >= 0, "id column must exist")
        val cell = table.cell(0, idColIdx)
        assertFalse(cell.isNull, "id row 0 must not be null")
        assertEquals(4, cell.value, "id row 0 must be 4")
    }

    @Test fun `Parquet Data tab rowHighlightRangesProvider returns non-empty ranges for row 0`() {
        val rawBytes = loadResource("alltypes_plain.parquet")
        val buf = rawBytes.toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val tabs = ParquetTableTabsProvider().tableTabs(provider)
        val dataTab = assertNotNull(tabs.firstOrNull { it.name == "Data" })
        val rangesProvider = assertNotNull(dataTab.rowHighlightRangesProvider, "rowHighlightRangesProvider must be set")
        val ranges = rangesProvider(0)
        assertTrue(ranges.isNotEmpty(), "rowHighlightRangesProvider(0) must return at least one range")
        // Every range must be within file bounds.
        for (r in ranges) {
            assertTrue(r.offset >= 0, "range offset must be non-negative")
            assertTrue(r.size > 0, "range size must be positive")
            assertTrue(r.offset + r.size <= rawBytes.size, "range must be within file")
        }
    }

    @Test fun `Parquet Data tab rowSelectionProvider returns first range for row 0`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val tabs = ParquetTableTabsProvider().tableTabs(provider)
        val dataTab = assertNotNull(tabs.firstOrNull { it.name == "Data" })
        val sel = dataTab.rowSelectionProvider(0)
        assertNotNull(sel, "rowSelectionProvider(0) must return non-null for decoded rows")
        assertTrue(sel.offset >= 0)
        assertTrue(sel.size > 0)
    }

    @Test fun `Parquet Data tab truncated flag is false for alltypes_plain`() {
        val buf = loadResource("alltypes_plain.parquet").toCommonByteBuffer()
        val provider = ParquetBinaryProvider(buf)
        val table = assertNotNull(provider.decodedTable)
        assertFalse(table.truncated, "alltypes_plain has 8 rows — not truncated")
        assertNull(ParquetTableTabsProvider().tableTabs(provider)
            .firstOrNull { it.name == "Data" }?.prologueText,
            "prologueText must be null when not truncated")
    }

    // ------------------------------------------------------------------
    // Arrow Data tab (generated_primitive.stream: 30 columns, 17 rows per batch)
    // ------------------------------------------------------------------

    @Test fun `ArrowTableTabsProvider adds Data tab for generated_primitive stream`() {
        val buf = loadResource("generated_primitive.stream").toCommonByteBuffer()
        val provider = ArrowBinaryProvider(buf, ArrowFormat.STREAM)
        val tabs = ArrowTableTabsProvider().tableTabs(provider)
        val dataTab = tabs.firstOrNull { it.name == "Data" }
        assertNotNull(dataTab, "Data tab should be present for generated_primitive.stream")
        assertEquals(17, dataTab.rowCount, "First batch of generated_primitive.stream has 17 rows")
    }

    @Test fun `Arrow Data tab columns match schema field names`() {
        val buf = loadResource("generated_primitive.stream").toCommonByteBuffer()
        val provider = ArrowBinaryProvider(buf, ArrowFormat.STREAM)
        val tabs = ArrowTableTabsProvider().tableTabs(provider)
        val dataTab = assertNotNull(tabs.firstOrNull { it.name == "Data" })
        assertEquals(30, dataTab.columns.size, "generated_primitive.stream has 30 columns")
        // Column 0 = "bool_nullable", column 7 = "int32_nonnullable"
        assertEquals("bool_nullable", dataTab.columns[0].name)
        assertEquals("int32_nonnullable", dataTab.columns[7].name)
    }

    @Test fun `Arrow Data tab int32_nonnullable row 0 value is -2147483648`() {
        val buf = loadResource("generated_primitive.stream").toCommonByteBuffer()
        val provider = ArrowBinaryProvider(buf, ArrowFormat.STREAM)
        val table = assertNotNull(provider.decodedTable)
        val colIdx = table.columnNames.indexOf("int32_nonnullable")
        assertTrue(colIdx >= 0, "int32_nonnullable column must exist")
        val cell = table.cell(0, colIdx)
        assertFalse(cell.isNull)
        assertEquals(-2147483648, cell.value)
    }

    @Test fun `Arrow Data tab rowHighlightRangesProvider returns non-empty ranges for row 0`() {
        val rawBytes = loadResource("generated_primitive.stream")
        val buf = rawBytes.toCommonByteBuffer()
        val provider = ArrowBinaryProvider(buf, ArrowFormat.STREAM)
        val tabs = ArrowTableTabsProvider().tableTabs(provider)
        val dataTab = assertNotNull(tabs.firstOrNull { it.name == "Data" })
        val rangesProvider = assertNotNull(dataTab.rowHighlightRangesProvider)
        val ranges = rangesProvider(0)
        assertTrue(ranges.isNotEmpty(), "rowHighlightRangesProvider(0) must return at least one range")
        for (r in ranges) {
            assertTrue(r.offset >= 0)
            assertTrue(r.size > 0)
            assertTrue(r.offset + r.size <= rawBytes.size, "range must be within file")
        }
    }

    @Test fun `Arrow Data tab shows empty state for malformed file (bad block offset)`() {
        // clusterfuzz case: valid ARROW1 magic at both ends, but the footer points its
        // first record batch at a garbage offset the bounds-checked reader rejects, so
        // nothing decodes. The Data tab must remain visible as an empty state, not vanish.
        val buf = loadResource("fuzz_arrow_bad_block_offset.arrow_file").toCommonByteBuffer()
        val provider = ArrowBinaryProvider(buf, ArrowFormat.FILE)
        assertNull(provider.decodedTable)
        val data = assertNotNull(
            ArrowTableTabsProvider().tableTabs(provider).firstOrNull { it.name == "Data" },
            "Data tab must remain visible as an empty state, not be silently hidden",
        )
        assertEquals(0, data.rowCount)
        assertNotNull(data.emptyMessage)
    }
}