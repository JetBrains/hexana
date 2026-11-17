package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.codec.inflateComplete
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.providers.pe.PeDebugEntry
import org.jetbrains.hexana.providers.pe.PeDebugType
import org.jetbrains.hexana.providers.pe.toBoundedInt
import org.jetbrains.hexana.providers.pe.u32LeOrNull

/**
 * Portable PDB debug metadata (`dotnet/runtime` `docs/design/specs/PortablePdb-Metadata.md`):
 * an ECMA-335 metadata blob with a `#Pdb` stream and the debug tables 0x30..0x37. It is either a
 * standalone `.pdb` file or deflate-compressed inside the assembly (debug directory type 17).
 */
class ClrPortablePdb internal constructor(
    val header: ClrPdbStreamHeader,
    val documents: List<ClrPdbDocument>,
    /** Rows of MethodDebugInformation, one per MethodDef of the described assembly. */
    val methodDebugInformationCount: Int,
    /** Methods whose row has a sequence point blob; counted from the table, without decoding blobs. */
    val methodsWithSequencePoints: Int,
    private val sequencePointReader: (methodRow: Int) -> List<ClrSequencePoint>,
) {
    /**
     * Sequence points of the method with 1-based MethodDef row [methodRow], in IL offset order;
     * empty for a method without debug information. Decoded on each call, from the blob.
     */
    fun sequencePoints(methodRow: Int): List<ClrSequencePoint> =
        if (methodRow in 1..methodDebugInformationCount) sequencePointReader(methodRow) else emptyList()

    /** Document with 1-based row [row]. */
    fun document(row: Int): ClrPdbDocument? = documents.getOrNull(row - 1)
}

/**
 * One sequence point: IL from [ilOffset] up to the next point belongs to the source span. A
 * hidden point marks compiler-generated IL that maps to no source and has 0 lines and columns.
 */
data class ClrSequencePoint(
    val ilOffset: Int,
    /** 1-based Document row. */
    val document: Int,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
) {
    val isHidden: Boolean get() = startLine == 0
}

/** The `#Pdb` stream: PDB id, entry point and the row counts of the assembly's own tables. */
data class ClrPdbStreamHeader(
    /** First 16 bytes of the PDB id; equals the GUID in the assembly's CodeView record. */
    val guid: String,
    /** Last 4 bytes of the PDB id; equals the CodeView debug directory entry's timestamp. */
    val stamp: UInt,
    /** MethodDef token of the entry point, or 0. */
    val entryPointToken: UInt,
    /** Row counts of the type-system tables the debug tables index into, by table id. */
    val referencedTableRows: Map<Int, Int>,
    val range: NativeByteRange,
)

data class ClrPdbDocument(
    /** 1-based Document row. */
    val row: Int,
    val name: String,
    val hashAlgorithm: String?,
    val hash: String,
    val language: String?,
    val recordRange: NativeByteRange,
)

/** Well-known GUIDs of the Document table, in [guidLeString] spelling. */
object ClrPdbGuids {
    private val hashAlgorithms = mapOf(
        "FF1816EC-AA5E-4D10-87F7-6F4963833460" to "SHA-1",
        "8829D00F-11B8-4213-878B-770E8597AC16" to "SHA-256",
    )
    private val languages = mapOf(
        "3F5162F8-07C6-11D3-9053-00C04FA302A1" to "C#",
        "3A12D0B8-C26C-11D0-B442-00A0244A1DD2" to "Visual Basic",
        "AB4F38C9-B6E6-43BA-BE3B-58080B2CCCE3" to "F#",
    )

    fun hashAlgorithmName(guid: String): String = hashAlgorithms[guid] ?: guid
    fun languageName(guid: String): String = languages[guid] ?: guid
}

/**
 * A Portable PDB embedded in the assembly (`-debug:embedded`): debug directory entry type 17, whose
 * data is `MPDB`, the uncompressed size, then the PDB deflate-compressed.
 */
object ClrEmbeddedPortablePdb {
    /** The embedded-PDB entry of [entries], if any. */
    fun entry(entries: List<PeDebugEntry>): PeDebugEntry? = entries.firstOrNull { it.type == PeDebugType.EMBEDDED_PORTABLE_PDB }

    /** Decompresses and decodes the PDB of [entry], or null when it is malformed or larger than [maxSize]. */
    fun decode(bytes: CommonByteBuffer, entry: PeDebugEntry, maxSize: Int = MAX_EMBEDDED_PDB_SIZE): ClrMetadata? {
        val range = entry.dataRange ?: return null
        if (range.size < 8 || bytes.u32LeOrNull(range.offset) != EMBEDDED_PDB_SIGNATURE) return null
        val size = bytes.u32LeOrNull(range.offset + 4)?.toBoundedInt()?.takeIf { it in 1..maxSize } ?: return null
        val compressed = ByteArray(range.size - 8) { bytes[range.offset + 8 + it] }
        val pdb = inflateComplete(compressed, maxOutput = size)?.takeIf { it.size == size } ?: return null
        val buffer = pdb.toCommonByteBuffer()
        if (!ClrMetadataParser.isPortablePdb(buffer)) return null
        return ClrMetadataParser.parsePortablePdb(buffer)
    }
}

private const val EMBEDDED_PDB_SIGNATURE = 0x4244504Du // "MPDB"
private const val MAX_EMBEDDED_PDB_SIZE = 64 * 1024 * 1024
