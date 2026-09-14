package org.jetbrains.hexana.providers

import org.jetbrains.hexana.arsc.ArscChunkKind
import org.jetbrains.hexana.arsc.ArscFile
import org.jetbrains.hexana.arsc.ArscFileParser
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Binary provider for Android binary resource tables (`resources.arsc`).
 * Extends [GenericBinaryProvider] so the hex view keeps working, and adds a
 * [parsedArscFile] lazy property that parses the table once and caches the
 * result for all panels.
 */
class ArscBinaryProvider(
    sharedBytes: CommonByteBuffer,
) : GenericBinaryProvider(sharedBytes) {

    /**
     * Lazily parsed resource table, or `null` when parsing fails.
     */
    val parsedArscFile: ArscFile? by lazy {
        try {
            ArscFileParser.parse(bytes.position(0))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The global value string pool — the strings resource values point at.
     * Type and entry names live in per-package pools and are shown by the
     * resource tables instead.
     */
    override val strings: List<BinaryStringRecord> by lazy {
        val arsc = parsedArscFile ?: return@lazy emptyList()
        arsc.valueStrings.strings.mapIndexed { index, value ->
            BinaryStringRecord(value = value, index = index)
        }
    }

    /**
     * The chunks directly under the table root: the global value pool and one per
     * package. [ArscFileParser.chunkRanges] walks the chunk tree separately from
     * [parsedArscFile], because the parsed model keeps a table's contents but not the
     * byte offsets they came from.
     */
    override val structureRanges: List<StructureRange> by lazy {
        val total = bytes.size
        ArscFileParser.chunkRanges(bytes.position(0)).mapNotNull { chunk ->
            structureRangeOrNull(
                offset = chunk.offset.toLong(),
                size = chunk.size.toLong(),
                label = chunk.label,
                fileSize = total,
                tabName = when (chunk.kind) {
                    ArscChunkKind.VALUE_STRINGS -> "Strings"
                    ArscChunkKind.PACKAGE -> "Resources"
                    ArscChunkKind.OTHER -> null
                },
            )
        }
    }

    companion object {
        /**
         * Detects a resource table. Unlike DEX or class files, `resources.arsc`
         * has no magic number, so detection is structural — see
         * [ArscFileParser.isArsc].
         */
        fun isArscFile(buffer: CommonByteBuffer): Boolean = ArscFileParser.isArsc(buffer)
    }
}
