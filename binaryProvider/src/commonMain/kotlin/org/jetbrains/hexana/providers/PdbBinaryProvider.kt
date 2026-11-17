package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.LargeBytes
import org.jetbrains.hexana.io.asLargeBytes
import org.jetbrains.hexana.pdb.PdbFile
import org.jetbrains.hexana.pdb.PdbParser
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.providers.pe.clr.ClrMetadataParser

/**
 * A `.pdb` debug symbols file. Two unrelated formats share the extension: the Windows PDB
 * (an MSF container written by MSVC, lld-link and clang-cl) and the .NET Portable PDB (ECMA-335
 * metadata written by Roslyn). Both are matched to their image by the GUID in its CodeView record.
 */
sealed class PdbSymbolsBinaryProvider(sharedBytes: CommonByteBuffer) : GenericBinaryProvider(sharedBytes) {
    /** The GUID the image's CodeView `RSDS` record carries, or null when it cannot be read. */
    abstract val pdbGuid: String?

    companion object {
        /** Content check for both formats; the shared `.pdb` extension is also used by unrelated text formats. */
        fun isPdbSymbolsFile(buffer: CommonByteBuffer): Boolean =
            PdbParser.isPdb(buffer) || ClrMetadataParser.isPortablePdb(buffer)
    }
}

/** Windows PDB (MSF 7.00). */
class PdbBinaryProvider(
    sharedBytes: CommonByteBuffer,
    /** The whole file; larger than [sharedBytes] for a PDB past 2 GiB, whose blocks are read from this. */
    val source: LargeBytes = sharedBytes.asLargeBytes(),
) : PdbSymbolsBinaryProvider(sharedBytes) {

    override val fileSize: Long get() = source.size

    /** The decoded PDB, or null when the MSF superblock is unusable. */
    val parsedPdb: PdbFile? by lazy { PdbParser.parse(source) }

    override val pdbGuid: String? get() = parsedPdb?.identity?.guid

    override val structureRanges: List<StructureRange> by lazy {
        val pdb = parsedPdb ?: return@lazy emptyList()
        val total = source.size
        buildList {
            structureRangeOrNull(0, pdb.superBlock.range.length, "MSF superblock", total, tabName = PDB_SUMMARY_TAB)?.let(::add)
            pdb.blockMapRange?.let { structureRangeOrNull(it.start, it.length, "Stream directory block map", total, tabName = PDB_STREAMS_TAB) }?.let(::add)
            // Streams are scattered over blocks; a large PDB has far too many runs to list them all.
            for (stream in pdb.streams) {
                if (size >= MAX_STRUCTURE_RANGES) break
                val label = pdb.streamRoles[stream.index]?.let { "Stream ${stream.index}: $it" } ?: "Stream ${stream.index}"
                for (range in stream.blockRanges()) {
                    structureRangeOrNull(range.start, range.length, label, total, tabName = PDB_STREAMS_TAB)?.let(::add)
                }
            }
        }
    }

    companion object {
        fun isPdbFile(buffer: CommonByteBuffer): Boolean = PdbParser.isPdb(buffer)
    }
}

/** .NET Portable PDB: standalone debug metadata with a `#Pdb` stream. */
class PortablePdbBinaryProvider(sharedBytes: CommonByteBuffer) : PdbSymbolsBinaryProvider(sharedBytes) {

    /** The debug metadata; [ClrMetadata.portablePdb] carries the PDB-specific part. */
    val metadata: ClrMetadata by lazy { ClrMetadataParser.parsePortablePdb(bytes) }

    override val pdbGuid: String? get() = metadata.portablePdb?.header?.guid

    override val structureRanges: List<StructureRange> by lazy {
        val total = bytes.size
        buildList {
            metadata.root?.range?.let { structureRangeOrNull(it.offset.toLong(), it.size.toLong(), "Metadata root", total, tabName = PORTABLE_PDB_SUMMARY_TAB) }?.let(::add)
            for (stream in metadata.streams) {
                val range = stream.contentRange ?: continue
                structureRangeOrNull(range.offset.toLong(), range.size.toLong(), "Stream ${stream.name}", total, tabName = PORTABLE_PDB_STREAMS_TAB)?.let(::add)
            }
        }
    }

    companion object {
        fun isPortablePdbFile(buffer: CommonByteBuffer): Boolean = ClrMetadataParser.isPortablePdb(buffer)
    }
}

/** Tab names the PDB table tabs use; structure ranges point at them. */
const val PDB_SUMMARY_TAB = "PDB"
const val PDB_STREAMS_TAB = "Streams"
const val PORTABLE_PDB_SUMMARY_TAB = "Portable PDB"
const val PORTABLE_PDB_STREAMS_TAB = "Streams"

private const val MAX_STRUCTURE_RANGES = 4096
