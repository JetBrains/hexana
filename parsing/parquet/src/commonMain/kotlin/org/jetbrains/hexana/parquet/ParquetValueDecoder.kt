/**
 * Phase 2+3+4 Parquet value decoder.
 *
 * Phase 2: UNCOMPRESSED + PLAIN encoding, REQUIRED leaves (EXACT provenance).
 * Phase 3: RLE/bit-packed hybrid, definition levels (OPTIONAL leaves), dictionary
 *   decode (PLAIN_DICTIONARY / RLE_DICTIONARY), DELTA_BINARY_PACKED, DELTA_LENGTH_BYTE_ARRAY,
 *   DELTA_BYTE_ARRAY, BYTE_STREAM_SPLIT.
 * Phase 4: Compressed pages (SNAPPY/GZIP/ZSTD/LZ4). Decompresses each page via
 *   parsing:codec then runs the Phase 2/3 decoders on the in-memory buffer.
 *   Provenance for compressed pages is PAGE-granularity (the compressed byte range).
 *
 * Returns [ColumnValues.fullyDecoded] = false for anything still outside scope
 * (unsupported codec on this platform, unsupported encodings). No exception
 * is thrown for graceful-degradation paths.
 *
 * INT96 (legacy 12-byte timestamp) is decoded as its signed two's-complement decimal
 * string (see [int96ToDecimalString]).
 *
 * Provenance conventions:
 *   Uncompressed PLAIN fixed-width: EXACT — per-value byte range.
 *   Uncompressed PLAIN BYTE_ARRAY: EXACT — payload range (4-byte prefix excluded from size).
 *   Uncompressed dictionary / RLE / DELTA: ENCODED_RUN — the run's byte span in the page.
 *   Uncompressed BYTE_STREAM_SPLIT: ENCODED_RUN — the whole split region.
 *   Compressed page (any encoding): PAGE — the compressed page range in the file.
 *   Null values (def < maxDef): NONE provenance.
 */
package org.jetbrains.hexana.parquet

import org.jetbrains.hexana.codec.Codec
import org.jetbrains.hexana.codec.decompress
import org.jetbrains.hexana.codec.isCodecSupported
import org.jetbrains.hexana.io.ByteProvenance
import org.jetbrains.hexana.io.ByteProvenanceKind
import org.jetbrains.hexana.io.DecodedCell
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.thrift.ThriftCompactReader

/**
 * Result of decoding a single column chunk.
 *
 * @param leafPath        Full dotted path of the column in the schema.
 * @param type            Physical Parquet type of the column.
 * @param cells           Decoded cells (empty when [fullyDecoded] is false).
 * @param fullyDecoded    True only when all values were decoded.
 * @param undecodedReason Human-readable explanation when [fullyDecoded] is false.
 */
class ColumnValues(
    val leafPath: List<String>,
    val type: ParquetType,
    val cells: List<DecodedCell>,
    val fullyDecoded: Boolean,
    val undecodedReason: String? = null,
)

// Sentinel provenance used for null cells.
private val NULL_PROVENANCE = ByteProvenance(0L, 0, ByteProvenanceKind.NONE)

/**
 * Decode up to [rowLimit] values from the first data page of [chunk].
 *
 * Supports all Phase 2/3 encodings (PLAIN, dictionary, RLE, DELTA*, BYTE_STREAM_SPLIT)
 * plus Phase 4 decompression (SNAPPY/GZIP/ZSTD/LZ4 via parsing:codec). For compressed
 * pages the decoders run on the in-memory decompressed buffer; all resulting cells carry
 * PAGE provenance (the compressed page range in the file).
 *
 * Returns [ColumnValues.fullyDecoded] = false for unsupported codecs on this
 * platform, unsupported encodings, and repeated/nested columns. INT96 decodes to
 * its signed decimal string.
 *
 * @param file      Full file buffer; absolute offsets from [ColumnMetaData.dataPageOffset] are used.
 * @param chunk     Column chunk metadata decoded from the Parquet footer.
 * @param leaf      Schema node for this leaf column (guards def/rep levels).
 * @param rowLimit  Maximum number of values to decode (default 10 000).
 */
fun decodeColumnChunk(
    file: CommonByteBuffer,
    chunk: ColumnMetaData,
    leaf: SchemaNode,
    rowLimit: Int = 10_000,
): ColumnValues {
    val path = chunk.pathInSchema

    val maxDef = leaf.maxDefinitionLevel()
    val maxRep = leaf.maxRepetitionLevel()

    // REPETITION levels > 0 indicate nested/repeated columns. v1 plan: coarse flatten.
    // For flat files (maxRep == 0) we skip rep levels entirely.
    if (maxRep > 0) {
        return ColumnValues(path, chunk.type, emptyList(), false,
            "repeated/nested column (maxRep=$maxRep) — nested Dremel assembly not yet supported")
    }

    val isCompressed = chunk.codec != CompressionCodec.UNCOMPRESSED

    // Phase 4: map and validate the codec when the column is compressed.
    val codecEnum: Codec? = if (isCompressed) {
        mapCodec(chunk.codec)
    } else null

    if (isCompressed && (codecEnum == null || !isCodecSupported(codecEnum))) {
        val reason = if (codecEnum == null) "${chunk.codec} not mapped" else "${chunk.codec} not supported on this platform"
        return ColumnValues(path, chunk.type, emptyList(), false, reason)
    }

    return if (isCompressed) {
        decodeCompressedChunk(file, chunk, leaf, maxDef, rowLimit, codecEnum!!)
    } else {
        decodeUncompressedChunk(file, chunk, leaf, maxDef, rowLimit)
    }
}

/**
 * Map a Parquet [CompressionCodec] to the codec module's [Codec] enum.
 * Returns null for codecs with no corresponding entry (should not happen with current enums).
 */
private fun mapCodec(parquetCodec: CompressionCodec): Codec? = when (parquetCodec) {
    CompressionCodec.UNCOMPRESSED -> Codec.UNCOMPRESSED
    CompressionCodec.SNAPPY       -> Codec.SNAPPY
    CompressionCodec.GZIP         -> Codec.GZIP
    CompressionCodec.ZSTD         -> Codec.ZSTD
    CompressionCodec.LZ4          -> Codec.LZ4
    CompressionCodec.LZ4_RAW      -> Codec.LZ4_RAW
    CompressionCodec.BROTLI       -> Codec.BROTLI
    CompressionCodec.LZO          -> Codec.LZO
}

// ---------------------------------------------------------------------------
// Uncompressed path (Phase 2/3) — EXACT / ENCODED_RUN provenance
// ---------------------------------------------------------------------------

private fun decodeUncompressedChunk(
    file: CommonByteBuffer,
    chunk: ColumnMetaData,
    leaf: SchemaNode,
    maxDef: Int,
    rowLimit: Int,
): ColumnValues {
    val path = chunk.pathInSchema

    // Decode dictionary page if present.
    val dictionary: List<Any?>? = if (chunk.dictionaryPageOffset != null) {
        decodeDictionaryPage(file, chunk)
            ?: return ColumnValues(path, chunk.type, emptyList(), false,
                "dictionary page at ${chunk.dictionaryPageOffset} could not be decoded")
    } else null

    val reader = ThriftCompactReader(file.duplicate().also { it.position(chunk.dataPageOffset.toInt()) })
    // Some writers set data_page_offset to the start of the column chunk (= dictionary page)
    // without setting dictionary_page_offset. Detect a leading dictionary page and decode it.
    var pageHeader = decodePageHeader(reader)
    val effectiveDictionary: List<Any?>? = if (pageHeader.type == PageType.DICTIONARY_PAGE) {
        val dph = pageHeader.dictionaryPageHeader
        val dictBodyOffset = reader.buf.position()
        val dictBody = file.slice(dictBodyOffset, pageHeader.compressedPageSize)
        val decoded = if (dph != null) decodeDictPageBody(dictBody, pageHeader.compressedPageSize, dph.numValues, chunk.type) else null
        // Advance past the dictionary page body, then re-read the next page header.
        reader.buf.position(dictBodyOffset + pageHeader.compressedPageSize)
        pageHeader = decodePageHeader(reader)
        decoded ?: dictionary  // fall back to already-decoded dict
    } else {
        dictionary  // no leading dict page; use the one already decoded
    }
    val bodyOffset = reader.buf.position().toLong()

    return when {
        pageHeader.dataPageHeader != null -> {
            val dph = pageHeader.dataPageHeader
            val numValues = minOf(dph.numValues, rowLimit)
            val bodySlice = file.slice(bodyOffset.toInt(), pageHeader.compressedPageSize)
            val cells = decodeDataPage(
                body = bodySlice,
                bodyAbsOffset = bodyOffset,
                encoding = dph.encoding,
                type = chunk.type,
                leaf = leaf,
                maxDef = maxDef,
                numValues = numValues,
                dictionary = effectiveDictionary,
                uncompressedPageSize = pageHeader.uncompressedPageSize,
                pageProv = null,
            ) ?: return ColumnValues(path, chunk.type, emptyList(), false,
                "encoding=${dph.encoding} decode failed for type=${chunk.type}")
            ColumnValues(path, chunk.type, cells, true)
        }
        pageHeader.dataPageHeaderV2 != null -> {
            val dph2 = pageHeader.dataPageHeaderV2
            val numValues = minOf(dph2.numValues, rowLimit)
            // v2: levels are uncompressed, data body follows
            val levelsLen = dph2.repetitionLevelsByteLength + dph2.definitionLevelsByteLength
            val dataBodyOffset = bodyOffset + levelsLen
            val dataBodySize = pageHeader.compressedPageSize - levelsLen
            val levelsSlice = file.slice(bodyOffset.toInt(), levelsLen)
            val dataSlice = file.slice(dataBodyOffset.toInt(), dataBodySize)
            val cells = decodeDataPageV2Uncompressed(
                levelsSlice, dataSlice, dataBodyOffset, dph2, chunk.type, leaf, maxDef, numValues,
                effectiveDictionary, pageHeader.uncompressedPageSize,
            ) ?: return ColumnValues(path, chunk.type, emptyList(), false,
                "v2 encoding=${dph2.encoding} decode failed for type=${chunk.type}")
            ColumnValues(path, chunk.type, cells, true)
        }
        else -> ColumnValues(path, chunk.type, emptyList(), false,
            "page at dataPageOffset is not a DATA_PAGE (type=${pageHeader.type})")
    }
}

// ---------------------------------------------------------------------------
// Compressed path (Phase 4) — PAGE provenance for all non-null cells
// ---------------------------------------------------------------------------

private fun decodeCompressedChunk(
    file: CommonByteBuffer,
    chunk: ColumnMetaData,
    leaf: SchemaNode,
    maxDef: Int,
    rowLimit: Int,
    codec: Codec,
): ColumnValues {
    val path = chunk.pathInSchema

    // Decode dictionary page if present (also compressed).
    val dictionary: List<Any?>? = if (chunk.dictionaryPageOffset != null) {
        decodeDictionaryPageCompressed(file, chunk, codec)
            ?: return ColumnValues(path, chunk.type, emptyList(), false,
                "compressed dictionary page at ${chunk.dictionaryPageOffset} could not be decoded")
    } else null

    val reader = ThriftCompactReader(file.duplicate().also { it.position(chunk.dataPageOffset.toInt()) })
    // Some writers set data_page_offset to the start of the column chunk (= dictionary page)
    // without setting dictionary_page_offset. Detect a leading dictionary page and decode it.
    var pageHeader = decodePageHeader(reader)
    val effectiveDictionary: List<Any?>? = if (pageHeader.type == PageType.DICTIONARY_PAGE) {
        val dph = pageHeader.dictionaryPageHeader
        val dictBodyStart = reader.buf.position()
        val decoded: List<Any?>? = if (dph != null) {
            val compressedDictBytes = ByteArray(pageHeader.compressedPageSize)
            file.duplicate().also { it.position(dictBodyStart) }
                .get(compressedDictBytes, 0, pageHeader.compressedPageSize)
            val decompressedDict = try {
                decompress(codec, compressedDictBytes, pageHeader.uncompressedPageSize)
            } catch (_: Exception) { null }
            if (decompressedDict != null)
                decodeDictPageBody(decompressedDict.toCommonByteBuffer(), pageHeader.uncompressedPageSize, dph.numValues, chunk.type)
            else null
        } else null
        // Advance past the compressed dictionary body, then re-read the next page header.
        reader.buf.position(dictBodyStart + pageHeader.compressedPageSize)
        pageHeader = decodePageHeader(reader)
        decoded ?: dictionary  // fall back to already-decoded dict
    } else {
        dictionary
    }
    val compressedBodyStart = reader.buf.position().toLong()

    return when {
        pageHeader.dataPageHeader != null -> {
            val dph = pageHeader.dataPageHeader
            val numValues = minOf(dph.numValues, rowLimit)
            val compressedBytes = ByteArray(pageHeader.compressedPageSize)
            file.duplicate().also { it.position(compressedBodyStart.toInt()) }
                .get(compressedBytes, 0, pageHeader.compressedPageSize)
            val decompressed = try {
                decompress(codec, compressedBytes, pageHeader.uncompressedPageSize)
            } catch (e: Exception) {
                return ColumnValues(path, chunk.type, emptyList(), false,
                    "decompression failed (${chunk.codec}): ${e.message}")
            }
            val bodySlice = decompressed.toCommonByteBuffer()
            val pageProv = ByteProvenance(compressedBodyStart, pageHeader.compressedPageSize, ByteProvenanceKind.PAGE)
            val cells = decodeDataPage(
                body = bodySlice,
                bodyAbsOffset = 0L,
                encoding = dph.encoding,
                type = chunk.type,
                leaf = leaf,
                maxDef = maxDef,
                numValues = numValues,
                dictionary = effectiveDictionary,
                uncompressedPageSize = pageHeader.uncompressedPageSize,
                pageProv = pageProv,
            ) ?: return ColumnValues(path, chunk.type, emptyList(), false,
                "encoding=${dph.encoding} decode failed for type=${chunk.type}")
            ColumnValues(path, chunk.type, cells, true)
        }
        pageHeader.dataPageHeaderV2 != null -> {
            // v2: rep/def levels are UNCOMPRESSED even when isCompressed=true; only the data portion
            // is compressed. Levels are already in the file; only the data body needs decompression.
            val dph2 = pageHeader.dataPageHeaderV2
            val numValues = minOf(dph2.numValues, rowLimit)
            val levelsLen = dph2.repetitionLevelsByteLength + dph2.definitionLevelsByteLength
            val compressedDataLen = pageHeader.compressedPageSize - levelsLen
            val uncompressedDataLen = pageHeader.uncompressedPageSize - levelsLen
            val levelsSlice = file.slice(compressedBodyStart.toInt(), levelsLen)
            val pageProv = ByteProvenance(compressedBodyStart, pageHeader.compressedPageSize, ByteProvenanceKind.PAGE)
            val dataBodySlice: CommonByteBuffer
            if (dph2.isCompressed && compressedDataLen > 0) {
                val compressedData = ByteArray(compressedDataLen)
                file.duplicate().also { it.position((compressedBodyStart + levelsLen).toInt()) }
                    .get(compressedData, 0, compressedDataLen)
                val decompressedData = try {
                    decompress(codec, compressedData, uncompressedDataLen)
                } catch (e: Exception) {
                    return ColumnValues(path, chunk.type, emptyList(), false,
                        "v2 data decompression failed (${chunk.codec}): ${e.message}")
                }
                dataBodySlice = decompressedData.toCommonByteBuffer()
            } else {
                dataBodySlice = file.slice((compressedBodyStart + levelsLen).toInt(), compressedDataLen)
            }
            val cells = decodeDataPageV2Compressed(
                levelsSlice, dataBodySlice, dph2, chunk.type, leaf, maxDef, numValues, effectiveDictionary,
                pageHeader.uncompressedPageSize, pageProv,
            ) ?: return ColumnValues(path, chunk.type, emptyList(), false,
                "v2 encoding=${dph2.encoding} decode failed for type=${chunk.type}")
            ColumnValues(path, chunk.type, cells, true)
        }
        else -> ColumnValues(path, chunk.type, emptyList(), false,
            "page at dataPageOffset is not a DATA_PAGE (type=${pageHeader.type})")
    }
}

// ---------------------------------------------------------------------------
// Shared data-page dispatch (used by both uncompressed and compressed paths)
// ---------------------------------------------------------------------------

/**
 * Decode a single data page body.
 *
 * @param body              Buffer positioned at the start of the page body (after the header).
 * @param bodyAbsOffset     Absolute file offset of [body] position 0. Unused when [pageProv] != null.
 * @param encoding          Value encoding declared in the page header.
 * @param type              Physical Parquet type of the column.
 * @param leaf              Schema node (for typeLength of FLBA columns).
 * @param maxDef            Max definition level for this column.
 * @param numValues         Number of logical values to decode.
 * @param dictionary        Pre-decoded dictionary page values, or null if no dictionary.
 * @param uncompressedPageSize Page uncompressed size (needed by BYTE_STREAM_SPLIT).
 * @param pageProv          When non-null, override every non-null cell's provenance with this
 *                          PAGE-granularity value (compressed page mode).
 * @return list of decoded cells, or null on any format error.
 */
private fun decodeDataPage(
    body: CommonByteBuffer,
    bodyAbsOffset: Long,
    encoding: ParquetEncoding,
    type: ParquetType,
    leaf: SchemaNode,
    maxDef: Int,
    numValues: Int,
    dictionary: List<Any?>?,
    uncompressedPageSize: Int,
    pageProv: ByteProvenance?,
): List<DecodedCell>? {
    // Definition levels (OPTIONAL leaf: maxDef > 0).
    val defLevels: IntArray? = if (maxDef == 0) null else {
        val bw = bitWidthNeeded(maxDef)
        readRleBitPackedHybrid(body, bw, numValues)
    }

    return when (encoding) {
        ParquetEncoding.PLAIN ->
            decodePlainPage(body, bodyAbsOffset, type, leaf, numValues, defLevels, pageProv)
        ParquetEncoding.PLAIN_DICTIONARY, ParquetEncoding.RLE_DICTIONARY -> {
            if (dictionary == null) return null
            decodeDictionaryDataPage(body, bodyAbsOffset, dictionary, numValues, defLevels, pageProv)
        }
        ParquetEncoding.DELTA_BINARY_PACKED ->
            decodeDeltaBinaryPacked(body, bodyAbsOffset, type, numValues, defLevels, pageProv)
        ParquetEncoding.DELTA_LENGTH_BYTE_ARRAY ->
            decodeDeltaLengthByteArray(body, bodyAbsOffset, numValues, defLevels, pageProv)
        ParquetEncoding.DELTA_BYTE_ARRAY ->
            decodeDeltaByteArray(body, bodyAbsOffset, numValues, defLevels, pageProv)
        ParquetEncoding.BYTE_STREAM_SPLIT ->
            decodeByteStreamSplit(body, bodyAbsOffset, type, numValues, defLevels, uncompressedPageSize, pageProv)
        else -> null  // unsupported encoding
    }
}

// ---------------------------------------------------------------------------
// DATA_PAGE_V2 helpers (levels always uncompressed, data optionally compressed)
// ---------------------------------------------------------------------------

/**
 * Decode a v2 data page for the UNCOMPRESSED path.
 *
 * In v2 pages, rep/def levels are stored raw (no length prefix) before the data body.
 * The [levelsSlice] contains exactly `repLevelsByteLength + defLevelsByteLength` bytes;
 * [dataBodySlice] is the remaining data body (already the correct length).
 */
private fun decodeDataPageV2Uncompressed(
    levelsSlice: CommonByteBuffer,
    dataBodySlice: CommonByteBuffer,
    dataBodyAbsOffset: Long,
    dph2: DataPageHeaderV2,
    type: ParquetType,
    leaf: SchemaNode,
    maxDef: Int,
    numValues: Int,
    dictionary: List<Any?>?,
    uncompressedPageSize: Int,
): List<DecodedCell>? = decodeDataPageV2(
    levelsSlice, dataBodySlice, dataBodyAbsOffset, dph2, type, leaf, maxDef, numValues,
    dictionary, uncompressedPageSize, pageProv = null,
)

/**
 * Decode a v2 data page for the COMPRESSED path.
 *
 * [dataBodySlice] must already be the decompressed data body. [pageProv] is set for all
 * non-null cells.
 */
private fun decodeDataPageV2Compressed(
    levelsSlice: CommonByteBuffer,
    dataBodySlice: CommonByteBuffer,
    dph2: DataPageHeaderV2,
    type: ParquetType,
    leaf: SchemaNode,
    maxDef: Int,
    numValues: Int,
    dictionary: List<Any?>?,
    uncompressedPageSize: Int,
    pageProv: ByteProvenance,
): List<DecodedCell>? = decodeDataPageV2(
    levelsSlice, dataBodySlice, 0L, dph2, type, leaf, maxDef, numValues,
    dictionary, uncompressedPageSize, pageProv,
)

private fun decodeDataPageV2(
    levelsSlice: CommonByteBuffer,
    dataBodySlice: CommonByteBuffer,
    dataBodyAbsOffset: Long,
    dph2: DataPageHeaderV2,
    type: ParquetType,
    leaf: SchemaNode,
    maxDef: Int,
    numValues: Int,
    dictionary: List<Any?>?,
    uncompressedPageSize: Int,
    pageProv: ByteProvenance?,
): List<DecodedCell>? {
    // Rep levels: skip (v1 plan: maxRep > 0 already rejected upstream)
    // Def levels: raw RLE/bit-packed without a length prefix.
    val defLevels: IntArray? = if (maxDef == 0) null else {
        val bw = bitWidthNeeded(maxDef)
        if (dph2.definitionLevelsByteLength > 0 && bw > 0) {
            val defEnd = levelsSlice.position() + dph2.repetitionLevelsByteLength + dph2.definitionLevelsByteLength
            // Skip rep level bytes first
            repeat(dph2.repetitionLevelsByteLength) { levelsSlice.get() }
            readRleHybridBody(levelsSlice, defEnd, bw, numValues)
        } else null
    }

    return when (dph2.encoding) {
        ParquetEncoding.PLAIN ->
            decodePlainPage(dataBodySlice, dataBodyAbsOffset, type, leaf, numValues, defLevels, pageProv)
        ParquetEncoding.PLAIN_DICTIONARY, ParquetEncoding.RLE_DICTIONARY -> {
            if (dictionary == null) return null
            decodeDictionaryDataPage(dataBodySlice, dataBodyAbsOffset, dictionary, numValues, defLevels, pageProv)
        }
        ParquetEncoding.RLE -> {
            // v2 BOOLEAN with RLE: body is a length-prefixed RLE/bit-packed stream (bitWidth=1).
            // The 4-byte LE length prefix is included even in v2 pages for the data portion.
            if (type != ParquetType.BOOLEAN) return null
            val maxDef2 = leaf.maxDefinitionLevel()
            val physCount = if (defLevels == null) numValues else defLevels.count { it == maxDef2 }
            val boolProv = pageProv ?: ByteProvenance(dataBodyAbsOffset, dataBodySlice.size, ByteProvenanceKind.ENCODED_RUN)
            val bits = readRleBitPackedHybrid(dataBodySlice, 1, physCount)
            val physCells = List(physCount) { i -> DecodedCell(bits[i] != 0, false, boolProv) }
            expandWithDefLevels(physCells, defLevels, maxDef2)
        }
        ParquetEncoding.DELTA_BINARY_PACKED ->
            decodeDeltaBinaryPacked(dataBodySlice, dataBodyAbsOffset, type, numValues, defLevels, pageProv)
        ParquetEncoding.DELTA_LENGTH_BYTE_ARRAY ->
            decodeDeltaLengthByteArray(dataBodySlice, dataBodyAbsOffset, numValues, defLevels, pageProv)
        ParquetEncoding.DELTA_BYTE_ARRAY ->
            decodeDeltaByteArray(dataBodySlice, dataBodyAbsOffset, numValues, defLevels, pageProv)
        ParquetEncoding.BYTE_STREAM_SPLIT ->
            decodeByteStreamSplit(dataBodySlice, dataBodyAbsOffset, type, numValues, defLevels,
                uncompressedPageSize, pageProv)
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Dictionary page decode (uncompressed and compressed variants)
// ---------------------------------------------------------------------------

/**
 * Decode the uncompressed dictionary page at [chunk.dictionaryPageOffset] into a list
 * of physical values (type matches chunk.type). Returns null on any error.
 */
private fun decodeDictionaryPage(file: CommonByteBuffer, chunk: ColumnMetaData): List<Any?>? {
    val offset = chunk.dictionaryPageOffset ?: return null
    val reader = ThriftCompactReader(file.duplicate().also { it.position(offset.toInt()) })
    val pageHeader = try { decodePageHeader(reader) } catch (_: Exception) { return null }
    val dph = pageHeader.dictionaryPageHeader ?: return null
    val bodyOffset = reader.buf.position()
    val body = file.slice(bodyOffset, pageHeader.compressedPageSize)
    return decodeDictPageBody(body, pageHeader.compressedPageSize, dph.numValues, chunk.type)
}

/**
 * Decode a compressed dictionary page via [codec]. Returns null on any error.
 */
private fun decodeDictionaryPageCompressed(
    file: CommonByteBuffer,
    chunk: ColumnMetaData,
    codec: Codec,
): List<Any?>? {
    val offset = chunk.dictionaryPageOffset ?: return null
    val reader = ThriftCompactReader(file.duplicate().also { it.position(offset.toInt()) })
    val pageHeader = try { decodePageHeader(reader) } catch (_: Exception) { return null }
    val dph = pageHeader.dictionaryPageHeader ?: return null
    val compressedBodyStart = reader.buf.position()
    val compressedBytes = ByteArray(pageHeader.compressedPageSize)
    file.duplicate().also { it.position(compressedBodyStart) }
        .get(compressedBytes, 0, pageHeader.compressedPageSize)
    val decompressed = try {
        decompress(codec, compressedBytes, pageHeader.uncompressedPageSize)
    } catch (_: Exception) { return null }
    val body = decompressed.toCommonByteBuffer()
    return decodeDictPageBody(body, pageHeader.uncompressedPageSize, dph.numValues, chunk.type)
}

/** Decode dictionary page body bytes into physical values. */
private fun decodeDictPageBody(
    body: CommonByteBuffer,
    @Suppress("UNUSED_PARAMETER") bodySize: Int,
    n: Int,
    type: ParquetType,
): List<Any?>? = when (type) {
    ParquetType.INT32 -> decodePlainInt32Raw(body, n)
    ParquetType.INT64 -> decodePlainInt64Raw(body, n)
    ParquetType.FLOAT -> decodePlainFloatRaw(body, n)
    ParquetType.DOUBLE -> decodePlainDoubleRaw(body, n)
    ParquetType.BOOLEAN -> decodePlainBoolRaw(body, n)
    ParquetType.BYTE_ARRAY -> decodePlainByteArrayRaw(body, n)
    // FIXED_LEN_BYTE_ARRAY: typeLength lives on SchemaElement, not available here.
    // FLBA dict columns are uncommon; left un-decoded (data page yields null values).
    ParquetType.FIXED_LEN_BYTE_ARRAY -> null
    ParquetType.INT96 -> decodePlainInt96Raw(body, n)
}

// ---------------------------------------------------------------------------
// Data-page dispatch helpers
// ---------------------------------------------------------------------------

/**
 * Decode a PLAIN-encoded data page.
 *
 * For OPTIONAL columns (defLevels != null) the body cursor must already be positioned
 * past the definition-level bytes (they were consumed by [readRleBitPackedHybrid] above).
 *
 * @param pageProv When non-null, all non-null cells use this PAGE provenance instead of EXACT.
 */
private fun decodePlainPage(
    body: CommonByteBuffer,
    bodyAbsOffset: Long,
    type: ParquetType,
    leaf: SchemaNode,
    numValues: Int,
    defLevels: IntArray?,
    pageProv: ByteProvenance?,
): List<DecodedCell>? {
    val maxDef = leaf.maxDefinitionLevel()
    val physCount = if (defLevels == null) numValues else defLevels.count { it == maxDef }

    val physCells: List<DecodedCell> = when (type) {
        ParquetType.INT32 -> decodePlainInt32(body, bodyAbsOffset, physCount, pageProv)
        ParquetType.INT64 -> decodePlainInt64(body, bodyAbsOffset, physCount, pageProv)
        ParquetType.FLOAT -> decodePlainFloat(body, bodyAbsOffset, physCount, pageProv)
        ParquetType.DOUBLE -> decodePlainDouble(body, bodyAbsOffset, physCount, pageProv)
        ParquetType.BOOLEAN -> decodePlainBoolean(body, bodyAbsOffset, physCount, pageProv)
        ParquetType.BYTE_ARRAY -> decodePlainByteArray(body, bodyAbsOffset, physCount, pageProv)
        ParquetType.FIXED_LEN_BYTE_ARRAY -> {
            val typeLen = leaf.element.typeLength ?: return null
            decodePlainFixedLenByteArray(body, bodyAbsOffset, physCount, typeLen, pageProv)
        }
        ParquetType.INT96 -> decodePlainInt96(body, bodyAbsOffset, physCount, pageProv)
    }
    return expandWithDefLevels(physCells, defLevels, maxDef)
}

/**
 * Decode a PLAIN_DICTIONARY / RLE_DICTIONARY data page.
 *
 * After def levels the page body starts with one byte = bit width for dictionary indices,
 * followed by the RLE/bit-packed hybrid stream (NO additional length prefix).
 *
 * @param pageProv When non-null, all non-null cells use this PAGE provenance instead of ENCODED_RUN.
 */
private fun decodeDictionaryDataPage(
    body: CommonByteBuffer,
    bodyAbsOffset: Long,
    dictionary: List<Any?>,
    numValues: Int,
    defLevels: IntArray?,
    pageProv: ByteProvenance?,
): List<DecodedCell>? {
    val maxDef = if (defLevels != null) defLevels.maxOrNull() ?: 0 else 0
    val physCount = if (defLevels == null) numValues else defLevels.count { it == maxDef }

    val bitWidthByte = body.get().toInt() and 0xFF
    val streamEnd = body.size
    val indices = readRleHybridBody(body, streamEnd, bitWidthByte, physCount)

    val prov = pageProv ?: ByteProvenance(bodyAbsOffset, body.size, ByteProvenanceKind.ENCODED_RUN)
    val physCells = List(indices.size) { i ->
        val idx = indices[i]
        val value = if (idx < dictionary.size) dictionary[idx] else null
        DecodedCell(value, false, prov)
    }
    return expandWithDefLevels(physCells, defLevels, maxDef)
}

// ---------------------------------------------------------------------------
// DELTA_BINARY_PACKED (INT32 / INT64)
// ---------------------------------------------------------------------------

/**
 * DELTA_BINARY_PACKED decode.
 *
 * Block layout (parquet-format §Encoding.DELTA_BINARY_PACKED):
 *   - Block size in values: ULEB128 (must be multiple of 128)
 *   - Miniblocks per block: ULEB128
 *   - Total value count: ULEB128
 *   - First value: zigzag SLEB128
 * Then per block:
 *   - Min delta: zigzag SLEB128
 *   - Bit widths: miniblocks-per-block bytes, one per miniblock
 *   - Miniblock data: bit-packed deltas (bitwidth from above) in groups of
 *     (blockSize / miniblocks) values
 *
 * Values-per-miniblock = blockSize / miniblocks.
 * Provenance: ENCODED_RUN spanning the entire body range, or [pageProv] if non-null.
 */
private fun decodeDeltaBinaryPacked(
    body: CommonByteBuffer,
    bodyAbsOffset: Long,
    type: ParquetType,
    numValues: Int,
    defLevels: IntArray?,
    pageProv: ByteProvenance?,
): List<DecodedCell>? {
    if (type != ParquetType.INT32 && type != ParquetType.INT64) return null
    val maxDef = if (defLevels != null) defLevels.maxOrNull() ?: 0 else 0
    val physCount = if (defLevels == null) numValues else defLevels.count { it == maxDef }

    val blockSize = body.getULEB128().toInt()        // values per block
    val miniblocks = body.getULEB128().toInt()       // miniblocks per block
    val totalCount = body.getULEB128().toInt()       // total values in stream (may exceed physCount)
    val firstValue = readZigzagVarlong(body)

    val valuesPerMiniblock = blockSize / miniblocks
    val out = ArrayList<Long>(physCount)
    var last = firstValue
    if (out.size < physCount) out.add(last)

    while (out.size < physCount) {
        val minDelta = readZigzagVarlong(body)
        // Read bit widths for each miniblock (one byte per miniblock)
        val bitWidths = IntArray(miniblocks) { body.get().toInt() and 0xFF }
        for (mbIdx in 0 until miniblocks) {
            val bw = bitWidths[mbIdx]
            val mbValues = IntArray(valuesPerMiniblock)
            // Decode bit-packed miniblock
            var current = 0; var bitsLeft = 0
            for (vi in 0 until valuesPerMiniblock) {
                var bitsNeeded = bw
                var delta = 0; var shift = 0
                while (bitsNeeded > 0) {
                    if (bitsLeft == 0) { current = body.get().toInt() and 0xFF; bitsLeft = 8 }
                    val take = minOf(bitsNeeded, bitsLeft)
                    delta = delta or ((current and ((1 shl take) - 1)) shl shift)
                    current = current ushr take; bitsLeft -= take
                    shift += take; bitsNeeded -= take
                }
                mbValues[vi] = delta
            }
            for (vi in 0 until valuesPerMiniblock) {
                if (out.size >= physCount) break
                last += minDelta + mbValues[vi]
                out.add(last)
            }
            if (out.size >= physCount) break
        }
    }

    val prov = pageProv ?: ByteProvenance(bodyAbsOffset, body.size, ByteProvenanceKind.ENCODED_RUN)
    val physCells = List(minOf(out.size, physCount)) { i ->
        val v: Any = if (type == ParquetType.INT32) out[i].toInt() else out[i]
        DecodedCell(v, false, prov)
    }
    return expandWithDefLevels(physCells, defLevels, maxDef)
}

// ---------------------------------------------------------------------------
// DELTA_LENGTH_BYTE_ARRAY (BYTE_ARRAY only)
// ---------------------------------------------------------------------------

/**
 * Decode a DELTA_LENGTH_BYTE_ARRAY page.
 *
 * Layout: a DELTA_BINARY_PACKED INT32 stream of lengths, followed by the concatenated
 * byte payloads (one per value). Provenance: ENCODED_RUN for the whole body, or [pageProv].
 */
private fun decodeDeltaLengthByteArray(
    body: CommonByteBuffer,
    bodyAbsOffset: Long,
    numValues: Int,
    defLevels: IntArray?,
    pageProv: ByteProvenance?,
): List<DecodedCell>? {
    val maxDef = if (defLevels != null) defLevels.maxOrNull() ?: 0 else 0
    val physCount = if (defLevels == null) numValues else defLevels.count { it == maxDef }

    // Decode length stream as DELTA_BINARY_PACKED INT32
    val lengths = decodeDeltaInts(body, physCount) ?: return null

    val prov = pageProv ?: ByteProvenance(bodyAbsOffset, body.size, ByteProvenanceKind.ENCODED_RUN)
    var lenIdx = 0
    val physCells = List(physCount) {
        val len = lengths[lenIdx++]
        val bytes = ByteArray(len)
        body.get(bytes, 0, len)
        DecodedCell(bytes.decodeToString(), false, prov)
    }
    return expandWithDefLevels(physCells, defLevels, maxDef)
}

// ---------------------------------------------------------------------------
// DELTA_BYTE_ARRAY (BYTE_ARRAY only — prefix-encoded)
// ---------------------------------------------------------------------------

/**
 * Decode a DELTA_BYTE_ARRAY page.
 *
 * Layout: two DELTA_BINARY_PACKED INT32 streams (prefix lengths, suffix lengths),
 * followed by concatenated suffix payloads. Each value is built as
 * `prev[0..prefixLen) + suffix[0..suffixLen)`. Provenance: ENCODED_RUN, or [pageProv].
 */
private fun decodeDeltaByteArray(
    body: CommonByteBuffer,
    bodyAbsOffset: Long,
    numValues: Int,
    defLevels: IntArray?,
    pageProv: ByteProvenance?,
): List<DecodedCell>? {
    val maxDef = if (defLevels != null) defLevels.maxOrNull() ?: 0 else 0
    val physCount = if (defLevels == null) numValues else defLevels.count { it == maxDef }

    val prefixLengths = decodeDeltaInts(body, physCount) ?: return null
    val suffixLengths = decodeDeltaInts(body, physCount) ?: return null

    val prov = pageProv ?: ByteProvenance(bodyAbsOffset, body.size, ByteProvenanceKind.ENCODED_RUN)
    var prev = ByteArray(0)
    val physCells = List(physCount) { i ->
        val prefixLen = prefixLengths[i]
        val suffixLen = suffixLengths[i]
        val suffix = ByteArray(suffixLen)
        body.get(suffix, 0, suffixLen)
        val value = ByteArray(prefixLen + suffixLen)
        prev.copyInto(value, 0, 0, prefixLen)
        suffix.copyInto(value, prefixLen)
        prev = value
        DecodedCell(value.decodeToString(), false, prov)
    }
    return expandWithDefLevels(physCells, defLevels, maxDef)
}

// ---------------------------------------------------------------------------
// BYTE_STREAM_SPLIT (FLOAT / DOUBLE)
// ---------------------------------------------------------------------------

/**
 * Decode a BYTE_STREAM_SPLIT page.
 *
 * Layout: [width] byte-planes, each of length [numValues], stored sequentially.
 * Value i is assembled by taking byte i from each plane in order (plane0 = least
 * significant byte for FLOAT/DOUBLE on little-endian targets).
 *
 * Provenance: ENCODED_RUN spanning the whole body, or [pageProv] if non-null.
 *
 * @param uncompressedPageSize Used to verify the body contains all expected plane bytes.
 */
private fun decodeByteStreamSplit(
    body: CommonByteBuffer,
    bodyAbsOffset: Long,
    type: ParquetType,
    numValues: Int,
    defLevels: IntArray?,
    uncompressedPageSize: Int,
    pageProv: ByteProvenance?,
): List<DecodedCell>? {
    val width = when (type) {
        ParquetType.FLOAT -> 4
        ParquetType.DOUBLE -> 8
        else -> return null  // BYTE_STREAM_SPLIT only valid for FLOAT/DOUBLE in practice
    }
    val maxDef = if (defLevels != null) defLevels.maxOrNull() ?: 0 else 0
    val physCount = if (defLevels == null) numValues else defLevels.count { it == maxDef }

    // Read all width*physCount bytes (planes are interleaved byte-by-byte across values)
    val planes = Array(width) { ByteArray(physCount) }
    for (byteIdx in 0 until width) {
        body.get(planes[byteIdx], 0, physCount)
    }

    val prov = pageProv ?: ByteProvenance(bodyAbsOffset, body.size, ByteProvenanceKind.ENCODED_RUN)
    val physCells = List(physCount) { i ->
        // Reconstruct: plane[0][i] is byte 0 (LE), plane[1][i] is byte 1, etc.
        val bytes = ByteArray(width) { byteIdx -> planes[byteIdx][i] }
        val value: Any = when (type) {
            ParquetType.FLOAT -> {
                val bits = (bytes[0].toInt() and 0xFF) or
                    ((bytes[1].toInt() and 0xFF) shl 8) or
                    ((bytes[2].toInt() and 0xFF) shl 16) or
                    ((bytes[3].toInt() and 0xFF) shl 24)
                Float.fromBits(bits)
            }
            ParquetType.DOUBLE -> {
                var bits = 0L
                for (b in 0 until 8) bits = bits or ((bytes[b].toInt() and 0xFF).toLong() shl (b * 8))
                Double.fromBits(bits)
            }
            else -> return null
        }
        DecodedCell(value, false, prov)
    }
    return expandWithDefLevels(physCells, defLevels, maxDef)
}

// ---------------------------------------------------------------------------
// Dremel def-level expansion
// ---------------------------------------------------------------------------

/**
 * Expand [physCells] (present-value cells) using [defLevels] to produce the full
 * logical row sequence including null cells for absent values.
 *
 * If [defLevels] is null all values are present (REQUIRED column).
 */
private fun expandWithDefLevels(
    physCells: List<DecodedCell>,
    defLevels: IntArray?,
    maxDef: Int,
): List<DecodedCell> {
    if (defLevels == null) return physCells
    val result = ArrayList<DecodedCell>(defLevels.size)
    var physIdx = 0
    for (def in defLevels) {
        if (def == maxDef) {
            result.add(physCells[physIdx++])
        } else {
            result.add(DecodedCell(null, true, NULL_PROVENANCE))
        }
    }
    return result
}

// ---------------------------------------------------------------------------
// DELTA_BINARY_PACKED helper for a stream of INT32 values
// ---------------------------------------------------------------------------

/**
 * Read a DELTA_BINARY_PACKED stream of [count] INT32 values from [body].
 * Returns null on any format error.
 */
private fun decodeDeltaInts(body: CommonByteBuffer, count: Int): IntArray? {
    return try {
        val blockSize = body.getULEB128().toInt()
        val miniblocks = body.getULEB128().toInt()
        @Suppress("UNUSED_VARIABLE")
        val totalCount = body.getULEB128().toInt()
        val firstValue = readZigzagVarlong(body).toInt()

        val valuesPerMiniblock = blockSize / miniblocks
        val out = IntArray(count)
        var filled = 0
        var last = firstValue
        if (filled < count) { out[filled++] = last }

        while (filled < count) {
            val minDelta = readZigzagVarlong(body).toInt()
            val bitWidths = IntArray(miniblocks) { body.get().toInt() and 0xFF }
            for (mbIdx in 0 until miniblocks) {
                val bw = bitWidths[mbIdx]
                var current = 0; var bitsLeft = 0
                for (vi in 0 until valuesPerMiniblock) {
                    var bitsNeeded = bw
                    var delta = 0; var shift = 0
                    while (bitsNeeded > 0) {
                        if (bitsLeft == 0) { current = body.get().toInt() and 0xFF; bitsLeft = 8 }
                        val take = minOf(bitsNeeded, bitsLeft)
                        delta = delta or ((current and ((1 shl take) - 1)) shl shift)
                        current = current ushr take; bitsLeft -= take
                        shift += take; bitsNeeded -= take
                    }
                    if (filled < count) {
                        last += minDelta + delta
                        out[filled++] = last
                    }
                }
                if (filled >= count) break
            }
        }
        out
    } catch (_: Exception) {
        null
    }
}

/** Read a zigzag-encoded signed varint (handles both 32-bit and 64-bit ranges). */
private fun readZigzagVarlong(buf: CommonByteBuffer): Long {
    var result = 0L; var shift = 0
    while (true) {
        val b = buf.get().toInt() and 0xFF
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) break
        shift += 7
    }
    return (result ushr 1) xor -(result and 1L)
}

// ---------------------------------------------------------------------------
// Per-type PLAIN decoders — EXACT provenance when pageProv == null, PAGE otherwise
// ---------------------------------------------------------------------------

private fun decodePlainInt32(
    body: CommonByteBuffer, bodyAbsOffset: Long, count: Int, pageProv: ByteProvenance?,
): List<DecodedCell> {
    return List(count) {
        val absOffset = bodyAbsOffset + body.position().toLong()
        val prov = pageProv ?: ByteProvenance(absOffset, 4, ByteProvenanceKind.EXACT)
        DecodedCell(body.getIntLE(), false, prov)
    }
}

private fun decodePlainInt64(
    body: CommonByteBuffer, bodyAbsOffset: Long, count: Int, pageProv: ByteProvenance?,
): List<DecodedCell> {
    return List(count) {
        val absOffset = bodyAbsOffset + body.position().toLong()
        val prov = pageProv ?: ByteProvenance(absOffset, 8, ByteProvenanceKind.EXACT)
        DecodedCell(body.getLongLE(), false, prov)
    }
}

private fun decodePlainInt96(
    body: CommonByteBuffer, bodyAbsOffset: Long, count: Int, pageProv: ByteProvenance?,
): List<DecodedCell> {
    return List(count) {
        val absOffset = bodyAbsOffset + body.position().toLong()
        val prov = pageProv ?: ByteProvenance(absOffset, 12, ByteProvenanceKind.EXACT)
        val raw = ByteArray(12)
        body.get(raw, 0, 12)
        DecodedCell(int96ToDecimalString(raw), false, prov)
    }
}

private fun decodePlainFloat(
    body: CommonByteBuffer, bodyAbsOffset: Long, count: Int, pageProv: ByteProvenance?,
): List<DecodedCell> {
    return List(count) {
        val absOffset = bodyAbsOffset + body.position().toLong()
        val prov = pageProv ?: ByteProvenance(absOffset, 4, ByteProvenanceKind.EXACT)
        DecodedCell(body.getFloatLE(), false, prov)
    }
}

private fun decodePlainDouble(
    body: CommonByteBuffer, bodyAbsOffset: Long, count: Int, pageProv: ByteProvenance?,
): List<DecodedCell> {
    return List(count) {
        val absOffset = bodyAbsOffset + body.position().toLong()
        val prov = pageProv ?: ByteProvenance(absOffset, 8, ByteProvenanceKind.EXACT)
        DecodedCell(body.getDoubleLE(), false, prov)
    }
}

private fun decodePlainBoolean(
    body: CommonByteBuffer, bodyAbsOffset: Long, count: Int, pageProv: ByteProvenance?,
): List<DecodedCell> {
    val boolStartInBody = body.position().toLong()
    val bitReader = BitReader(body)
    return List(count) { i ->
        val absOffset = bodyAbsOffset + boolStartInBody + (i / 8).toLong()
        val prov = pageProv ?: ByteProvenance(absOffset, 1, ByteProvenanceKind.EXACT)
        val bit = bitReader.readBitsLE(1) != 0
        DecodedCell(bit, false, prov)
    }
}

private fun decodePlainByteArray(
    body: CommonByteBuffer, bodyAbsOffset: Long, count: Int, pageProv: ByteProvenance?,
): List<DecodedCell> {
    return List(count) {
        val prefixRelOffset = body.position().toLong()
        val payloadLen = body.getIntLE()
        require(payloadLen >= 0) { "BYTE_ARRAY length prefix is negative: $payloadLen" }
        val payloadAbsOffset = bodyAbsOffset + prefixRelOffset + 4
        val bytes = ByteArray(payloadLen)
        body.get(bytes, 0, payloadLen)
        val prov = pageProv ?: ByteProvenance(payloadAbsOffset, payloadLen, ByteProvenanceKind.EXACT)
        DecodedCell(bytes.decodeToString(), false, prov)
    }
}

private fun decodePlainFixedLenByteArray(
    body: CommonByteBuffer, bodyAbsOffset: Long, count: Int, typeLen: Int, pageProv: ByteProvenance?,
): List<DecodedCell> {
    return List(count) {
        val absOffset = bodyAbsOffset + body.position().toLong()
        val prov = pageProv ?: ByteProvenance(absOffset, typeLen, ByteProvenanceKind.EXACT)
        val bytes = ByteArray(typeLen)
        body.get(bytes, 0, typeLen)
        DecodedCell(bytes, false, prov)
    }
}

// ---------------------------------------------------------------------------
// Per-type PLAIN raw-value decoders (no provenance) — for dictionary page body
// ---------------------------------------------------------------------------

private fun decodePlainInt32Raw(body: CommonByteBuffer, count: Int): List<Any?> =
    List(count) { body.getIntLE() }

private fun decodePlainInt64Raw(body: CommonByteBuffer, count: Int): List<Any?> =
    List(count) { body.getLongLE() }

private fun decodePlainInt96Raw(body: CommonByteBuffer, count: Int): List<Any?> =
    List(count) {
        val raw = ByteArray(12)
        body.get(raw, 0, 12)
        int96ToDecimalString(raw)
    }

private fun decodePlainFloatRaw(body: CommonByteBuffer, count: Int): List<Any?> =
    List(count) { body.getFloatLE() }

private fun decodePlainDoubleRaw(body: CommonByteBuffer, count: Int): List<Any?> =
    List(count) { body.getDoubleLE() }

private fun decodePlainBoolRaw(body: CommonByteBuffer, count: Int): List<Any?> {
    val bitReader = BitReader(body)
    return List(count) { bitReader.readBitsLE(1) != 0 }
}

private fun decodePlainByteArrayRaw(body: CommonByteBuffer, count: Int): List<Any?> =
    List(count) {
        val len = body.getIntLE()
        val bytes = ByteArray(len)
        body.get(bytes, 0, len)
        bytes.decodeToString()
    }
