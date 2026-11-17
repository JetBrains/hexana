package org.jetbrains.hexana.providers

import org.jetbrains.hexana.dex.DexFile
import org.jetbrains.hexana.dex.DexFileParser
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Binary provider for Android `.dex` files. Extends [GenericBinaryProvider] so the
 * hex view works as before, and adds a [parsedDexFile] lazy property that parses
 * the DEX structure once and caches the result for all panels.
 */
class DexBinaryProvider(
    sharedBytes: CommonByteBuffer,
) : GenericBinaryProvider(sharedBytes) {

    /**
     * Lazily parsed DEX file structure, or `null` when parsing fails.
     */
    val parsedDexFile: DexFile? by lazy {
        try {
            DexFileParser.parse(bytes.position(0))
        } catch (_: Throwable) {
            null
        }
    }

    override val strings: List<BinaryStringRecord> by lazy {
        val dex = parsedDexFile ?: return@lazy emptyList()
        dex.stringIds.mapIndexed { index, entry ->
            BinaryStringRecord(value = entry.value, index = index)
        }
    }

    /**
     * The regions the DEX header declares, in file order. Every id table has a fixed
     * per-item stride, so its extent follows from the count the header carries; the
     * `data` and `link` sections declare their sizes directly. `map_list` is omitted:
     * it lives inside `data` and its size is only known by reading it.
     */
    override val structureRanges: List<StructureRange> by lazy {
        val header = parsedDexFile?.header ?: return@lazy emptyList()
        val total = bytes.size
        listOfNotNull(
            structureRangeOrNull(0L, header.headerSize, "header", total, tabName = "Header"),
            structureRangeOrNull(
                header.stringIdsOff, header.stringIdsSize * STRING_ID_ITEM_SIZE,
                "string_ids", total, tabName = "Strings",
            ),
            structureRangeOrNull(
                header.typeIdsOff, header.typeIdsSize * TYPE_ID_ITEM_SIZE, "type_ids", total,
            ),
            structureRangeOrNull(
                header.protoIdsOff, header.protoIdsSize * PROTO_ID_ITEM_SIZE, "proto_ids", total,
            ),
            structureRangeOrNull(
                header.fieldIdsOff, header.fieldIdsSize * FIELD_ID_ITEM_SIZE, "field_ids", total,
            ),
            structureRangeOrNull(
                header.methodIdsOff, header.methodIdsSize * METHOD_ID_ITEM_SIZE, "method_ids", total,
            ),
            structureRangeOrNull(
                header.classDefsOff, header.classDefsSize * CLASS_DEF_ITEM_SIZE,
                "class_defs", total, tabName = "Classes",
            ),
            structureRangeOrNull(header.dataOff, header.dataSize, "data", total),
            structureRangeOrNull(header.linkOff, header.linkSize, "link", total),
        ).sortedBy { it.offset }
    }

    companion object {
        /** Detects whether [buffer] starts with the DEX magic (`dex\n`). */
        fun isDexFile(buffer: CommonByteBuffer): Boolean {
            if (buffer.size < 4) return false
            return buffer[0] == 0x64.toByte() && // 'd'
                buffer[1] == 0x65.toByte() &&     // 'e'
                buffer[2] == 0x78.toByte() &&     // 'x'
                buffer[3] == 0x0A.toByte()        // '\n'
        }
    }
}

// Fixed strides of the DEX id tables, per the format reference. They match the fields
// DexFileParser reads for each item, and let a table's extent be derived from the count
// in the header without walking it.

/** `string_id_item`: one `uint` offset. */
private const val STRING_ID_ITEM_SIZE = 4L

/** `type_id_item`: one `uint` descriptor index. */
private const val TYPE_ID_ITEM_SIZE = 4L

/** `proto_id_item`: three `uint`s. */
private const val PROTO_ID_ITEM_SIZE = 12L

/** `field_id_item`: two `ushort`s and a `uint`. */
private const val FIELD_ID_ITEM_SIZE = 8L

/** `method_id_item`: two `ushort`s and a `uint`. */
private const val METHOD_ID_ITEM_SIZE = 8L

/** `class_def_item`: eight `uint`s. */
private const val CLASS_DEF_ITEM_SIZE = 32L
