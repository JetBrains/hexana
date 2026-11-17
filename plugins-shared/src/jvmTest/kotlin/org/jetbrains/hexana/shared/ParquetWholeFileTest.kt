package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.asLargeBytes
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.ArrowBinaryProvider
import org.jetbrains.hexana.providers.ArrowFormat
import org.jetbrains.hexana.providers.ParquetBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A Parquet file past 2 GiB keeps its footer at the end and its column chunks anywhere, beyond the
 * head a `CommonByteBuffer` holds. Simulated here by giving the provider the whole file as its
 * source but only the leading magic as its buffer: everything must still come from the source, and
 * windowed chunks must report provenance at their real file offsets.
 */
class ParquetWholeFileTest {
    private val bytes = ParquetWholeFileTest::class.java.classLoader.getResourceAsStream("alltypes_plain.parquet")!!.readBytes()

    @Test
    fun `footer and column chunks are read from the whole file`() {
        val whole = ParquetBinaryProvider(bytes.toCommonByteBuffer())
        val headOnly = ParquetBinaryProvider(bytes.copyOf(4).toCommonByteBuffer(), source = bytes.toCommonByteBuffer().asLargeBytes())

        assertEquals(whole.metadata?.numRows, assertNotNull(headOnly.metadata).numRows)
        assertEquals(whole.structureRanges.map { it.offset to it.size }, headOnly.structureRanges.map { it.offset to it.size })

        val expected = assertNotNull(whole.decodedTable)
        val actual = assertNotNull(headOnly.decodedTable)
        assertEquals(expected.columnNames, actual.columnNames)
        assertEquals(expected.rowCount, actual.rowCount)
        for (col in 0 until expected.columnCount) {
            assertEquals(expected.columnFullyDecoded(col), actual.columnFullyDecoded(col), "column ${expected.columnNames[col]}")
            for (row in 0 until expected.rowCount) {
                val want = expected.cell(row, col)
                val got = actual.cell(row, col)
                assertEquals(want.value?.let(::render), got.value?.let(::render))
                assertEquals(want.provenance.offset to want.provenance.size, got.provenance.offset to got.provenance.size)
            }
        }
    }

    @Test
    fun `Arrow file footer and first batch are read from the whole file`() {
        val arrow = ParquetWholeFileTest::class.java.classLoader.getResourceAsStream("generated_null_trivial.arrow_file")!!.readBytes()
        val whole = ArrowBinaryProvider(arrow.toCommonByteBuffer(), ArrowFormat.FILE)
        val headOnly = ArrowBinaryProvider(arrow.copyOf(8).toCommonByteBuffer(), ArrowFormat.FILE, source = arrow.toCommonByteBuffer().asLargeBytes())

        assertEquals(whole.fileFooter?.recordBatchBlockCount, assertNotNull(headOnly.fileFooter).recordBatchBlockCount)
        assertEquals(whole.structureRanges.map { it.offset to it.size }, headOnly.structureRanges.map { it.offset to it.size })
        val expected = whole.decodedTable
        val actual = headOnly.decodedTable
        assertEquals(expected?.columnNames, actual?.columnNames)
        assertEquals(expected?.rowCount, actual?.rowCount)
        if (expected != null && actual != null) {
            for (col in 0 until expected.columnCount) for (row in 0 until expected.rowCount) {
                val want = expected.cell(row, col).provenance
                val got = actual.cell(row, col).provenance
                assertEquals(want.offset to want.size, got.offset to got.size)
            }
        }
    }

    private fun render(value: Any): String = if (value is ByteArray) value.toList().toString() else value.toString()
}
