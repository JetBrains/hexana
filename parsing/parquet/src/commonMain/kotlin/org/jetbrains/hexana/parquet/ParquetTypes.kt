/**
 * Parquet metadata types decoded from Thrift Compact Protocol.
 *
 * Field IDs verified against:
 *   apache/parquet-format master branch parquet.thrift, fetched 2026-07-03.
 *   https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift
 *
 * Scope: FileMetaData, SchemaElement, RowGroup, ColumnChunk, ColumnMetaData,
 * PageHeader, DataPageHeader, DataPageHeaderV2, DictionaryPageHeader.
 * Page-body decompression and column-value encoding are deferred.
 */
package org.jetbrains.hexana.parquet

// ---------------------------------------------------------------------------
// Enums (Thrift i32 values; decoded from wire and stored as Kotlin enum)
// ---------------------------------------------------------------------------

/** parquet.thrift: enum Type */
enum class ParquetType(val id: Int) {
    BOOLEAN(0), INT32(1), INT64(2), INT96(3), FLOAT(4), DOUBLE(5), BYTE_ARRAY(6), FIXED_LEN_BYTE_ARRAY(7);
    companion object {
        fun fromId(id: Int): ParquetType = entries.firstOrNull { it.id == id }
            ?: error("Unknown Parquet Type id=$id")
    }
}

/** parquet.thrift: enum FieldRepetitionType */
enum class FieldRepetitionType(val id: Int) {
    REQUIRED(0), OPTIONAL(1), REPEATED(2);
    companion object {
        fun fromId(id: Int): FieldRepetitionType = entries.firstOrNull { it.id == id }
            ?: error("Unknown FieldRepetitionType id=$id")
    }
}

/** parquet.thrift: enum Encoding */
enum class ParquetEncoding(val id: Int) {
    PLAIN(0), GROUP_VAR_INT(1), PLAIN_DICTIONARY(2), RLE(3),
    BIT_PACKED(4), DELTA_BINARY_PACKED(5), DELTA_LENGTH_BYTE_ARRAY(6),
    DELTA_BYTE_ARRAY(7), RLE_DICTIONARY(8), BYTE_STREAM_SPLIT(9);
    companion object {
        fun fromId(id: Int): ParquetEncoding = entries.firstOrNull { it.id == id }
            ?: error("Unknown Parquet Encoding id=$id")
    }
}

/** parquet.thrift: enum CompressionCodec */
enum class CompressionCodec(val id: Int) {
    UNCOMPRESSED(0), SNAPPY(1), GZIP(2), LZO(3), BROTLI(4), LZ4(5), ZSTD(6), LZ4_RAW(7);
    companion object {
        fun fromId(id: Int): CompressionCodec = entries.firstOrNull { it.id == id }
            ?: error("Unknown CompressionCodec id=$id")
    }
}

/** parquet.thrift: enum PageType */
enum class PageType(val id: Int) {
    DATA_PAGE(0), INDEX_PAGE(1), DICTIONARY_PAGE(2), DATA_PAGE_V2(3);
    companion object {
        fun fromId(id: Int): PageType = entries.firstOrNull { it.id == id }
            ?: error("Unknown PageType id=$id")
    }
}

// ---------------------------------------------------------------------------
// Metadata model types (populated by hand-written Thrift decoders)
// ---------------------------------------------------------------------------

/**
 * parquet.thrift: struct SchemaElement
 * Field IDs: type=1, type_length=2, repetition_type=3, name=4, num_children=5, ...
 * A null `type` indicates a group (non-leaf) node.
 */
data class SchemaElement(
    val type: ParquetType?,
    val typeLength: Int?,
    val repetitionType: FieldRepetitionType?,
    val name: String,
    val numChildren: Int?,
)

/**
 * parquet.thrift: struct ColumnMetaData
 * Field IDs: type=1, encodings=2, path_in_schema=3, codec=4, num_values=5,
 *   total_uncompressed_size=6, total_compressed_size=7, data_page_offset=9,
 *   index_page_offset=10, dictionary_page_offset=11.
 */
data class ColumnMetaData(
    val type: ParquetType,
    val encodings: List<ParquetEncoding>,
    val pathInSchema: List<String>,
    val codec: CompressionCodec,
    val numValues: Long,
    val totalUncompressedSize: Long,
    val totalCompressedSize: Long,
    val dataPageOffset: Long,
    val indexPageOffset: Long?,
    val dictionaryPageOffset: Long?,
)

/**
 * parquet.thrift: struct ColumnChunk
 * Field IDs: file_path=1, file_offset=2, meta_data=3.
 */
data class ColumnChunk(
    val filePath: String?,
    val fileOffset: Long,
    val metaData: ColumnMetaData?,
)

/**
 * parquet.thrift: struct RowGroup
 * Field IDs: columns=1, total_byte_size=2, num_rows=3.
 */
data class RowGroup(
    val columns: List<ColumnChunk>,
    val totalByteSize: Long,
    val numRows: Long,
)

/**
 * parquet.thrift: struct DataPageHeader (v1)
 * Field IDs: num_values=1, encoding=2, definition_level_encoding=3, repetition_level_encoding=4.
 */
data class DataPageHeader(
    val numValues: Int,
    val encoding: ParquetEncoding,
    val definitionLevelEncoding: ParquetEncoding,
    val repetitionLevelEncoding: ParquetEncoding,
)

/**
 * parquet.thrift: struct DictionaryPageHeader
 * Field IDs: num_values=1, encoding=2, is_sorted=3.
 */
data class DictionaryPageHeader(
    val numValues: Int,
    val encoding: ParquetEncoding,
    val isSorted: Boolean?,
)

/**
 * parquet.thrift: struct DataPageHeaderV2
 * Field IDs: num_values=1, num_nulls=2, num_rows=3, encoding=4,
 *   definition_levels_byte_length=5, repetition_levels_byte_length=6, is_compressed=7.
 */
data class DataPageHeaderV2(
    val numValues: Int,
    val numNulls: Int,
    val numRows: Int,
    val encoding: ParquetEncoding,
    val definitionLevelsByteLength: Int,
    val repetitionLevelsByteLength: Int,
    val isCompressed: Boolean,
)

/**
 * parquet.thrift: struct PageHeader
 * Field IDs: type=1, uncompressed_page_size=2, compressed_page_size=3, crc=4,
 *   data_page_header=5, index_page_header=6, dictionary_page_header=7, data_page_header_v2=8.
 */
data class PageHeader(
    val type: PageType,
    val uncompressedPageSize: Int,
    val compressedPageSize: Int,
    val crc: Int?,
    val dataPageHeader: DataPageHeader?,
    val dictionaryPageHeader: DictionaryPageHeader?,
    val dataPageHeaderV2: DataPageHeaderV2?,
)

/**
 * parquet.thrift: struct FileMetaData
 * Field IDs: version=1, schema=2, num_rows=3, row_groups=4, key_value_metadata=5, created_by=6.
 */
data class FileMetaData(
    val version: Int,
    val schema: List<SchemaElement>,
    val numRows: Long,
    val rowGroups: List<RowGroup>,
    val createdBy: String?,
)

// ---------------------------------------------------------------------------
// Schema tree helpers
// ---------------------------------------------------------------------------

/** A node in the Parquet schema tree derived from a flat SchemaElement list. */
data class SchemaNode(
    val element: SchemaElement,
    val children: List<SchemaNode>,
) {
    val isLeaf: Boolean get() = children.isEmpty()
    val isRequired: Boolean get() = element.repetitionType == FieldRepetitionType.REQUIRED

    /** Max definition level: count of OPTIONAL/REPEATED ancestors (including self if leaf). */
    fun maxDefinitionLevel(parentLevel: Int = 0): Int {
        val self = if (element.repetitionType != FieldRepetitionType.REQUIRED) parentLevel + 1 else parentLevel
        return if (isLeaf) self else children.maxOf { it.maxDefinitionLevel(self) }
    }

    /** Max repetition level: count of REPEATED ancestors (including self). */
    fun maxRepetitionLevel(parentLevel: Int = 0): Int {
        val self = if (element.repetitionType == FieldRepetitionType.REPEATED) parentLevel + 1 else parentLevel
        return if (isLeaf) self else children.maxOf { it.maxRepetitionLevel(self) }
    }
}

/**
 * Build the schema tree from the flat SchemaElement list.
 *
 * The first element is the root message group (its num_children covers all top-level fields).
 * Subsequent elements are parsed depth-first using num_children to determine group extents.
 */
fun buildSchemaTree(elements: List<SchemaElement>): SchemaNode {
    var index = 0

    fun parseNode(): SchemaNode {
        val elem = elements[index++]
        val childCount = elem.numChildren ?: 0
        val children = (0 until childCount).map { parseNode() }
        return SchemaNode(elem, children)
    }

    return parseNode()
}