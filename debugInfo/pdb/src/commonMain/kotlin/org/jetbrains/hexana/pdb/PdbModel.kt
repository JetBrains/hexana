package org.jetbrains.hexana.pdb

import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic

/** A decoded Windows (MSF) PDB. Everything below the superblock is best effort. */
class PdbFile internal constructor(
    val superBlock: MsfSuperBlock,
    /** File range of the stream directory's block-number array (the "block map"). */
    val blockMapRange: NativeByteRange?,
    val streams: List<MsfStream>,
    val info: PdbInfoStream?,
    val dbi: PdbDbiStream?,
    val tpi: PdbTypeStreamHeader?,
    val ipi: PdbTypeStreamHeader?,
    val diagnostics: List<NativeParserDiagnostic>,
) {
    fun stream(index: Int): MsfStream? = streams.getOrNull(index)?.takeUnless { it.isNil }

    /** What each stream holds, as far as the info and DBI streams say. */
    val streamRoles: Map<Int, String> by lazy { computeStreamRoles(this) }

    /**
     * The identity a PE image's CodeView `RSDS` record names: the GUID and the DBI age
     * (the PDB info stream's age is bumped on every incremental link, the DBI age is what
     * debuggers match, and they are equal in a freshly linked PDB).
     */
    val identity: PdbIdentity? get() = info?.guid?.let { PdbIdentity(it, dbi?.age ?: info.age) }
}

data class PdbIdentity(val guid: String, val age: UInt)

/** Stream 1: PDB version, signature, age, GUID, named streams and feature codes. */
data class PdbInfoStream(
    val version: UInt,
    val signature: UInt,
    val age: UInt,
    val guid: String?,
    /** Named streams (`/names`, `/LinkInfo`, `/src/headerblock`, …) → stream index. */
    val namedStreams: Map<String, Int>,
    val features: List<PdbFeature>,
)

enum class PdbFeature(val code: UInt, val label: String) {
    Vc110(20091201u, "VC110"),
    Vc140(20140508u, "VC140 (has IPI stream)"),
    NoTypeMerge(0x4D544F4Eu, "NoTypeMerge"),
    MinimalDebugInfo(0x494E494Du, "MinimalDebugInfo (/DEBUG:FASTLINK)"),
    ;

    companion object {
        fun of(code: UInt): PdbFeature? = entries.firstOrNull { it.code == code }
    }
}

/** Stream 3: modules, section contributions, source files and auxiliary stream indices. */
data class PdbDbiStream(
    val versionSignature: Int,
    val version: UInt,
    val age: UInt,
    val globalSymbolStream: Int?,
    val publicSymbolStream: Int?,
    val symbolRecordStream: Int?,
    /** Toolchain that wrote the PDB, `major.minor` from the build-number field. */
    val buildMajor: Int,
    val buildMinor: Int,
    val pdbDllVersion: Int,
    val pdbDllRebuild: Int,
    val flags: Int,
    /** COFF machine type, e.g. `0x8664` for x64. */
    val machine: Int,
    val modules: List<PdbModule>,
    val sectionContributions: List<PdbSectionContribution>,
    val optionalDebugStreams: Map<PdbOptionalDebugStream, Int>,
    /** Every substream the header describes, in file order, for the size breakdown. */
    val substreams: List<PdbDbiSubstream>,
) {
    val isIncrementallyLinked: Boolean get() = flags and 0x1 != 0
    val isStripped: Boolean get() = flags and 0x2 != 0
    val hasConflictingTypes: Boolean get() = flags and 0x4 != 0
}

data class PdbDbiSubstream(val name: String, val streamOffset: Int, val size: Int, val fileRange: NativeByteRange?)

/** One compiland (object file, import library member or `* Linker *`). */
data class PdbModule(
    val index: Int,
    val moduleName: String,
    val objectFileName: String,
    /** Stream holding the module's symbols and C13 line info, or null for none. */
    val symbolStream: Int?,
    val symbolByteSize: UInt,
    val c11ByteSize: UInt,
    val c13ByteSize: UInt,
    val sourceFileCount: Int,
    val flags: Int,
    /** Source files from the DBI file-info substream, in declaration order. */
    val sourceFiles: List<String>,
    val contribution: PdbSectionContribution,
    val streamOffset: Int,
    val recordSize: Int,
    val fileRange: NativeByteRange?,
)

/** A contiguous piece of an image section that one module contributed. */
data class PdbSectionContribution(
    /** 1-based image section number. */
    val section: Int,
    val offset: Int,
    val size: Int,
    val characteristics: UInt,
    val moduleIndex: Int,
    val dataCrc: UInt,
    val relocCrc: UInt,
    val fileRange: NativeByteRange?,
)

/** The DBI optional debug header: stream indices of auxiliary debug data, in declaration order. */
enum class PdbOptionalDebugStream(val label: String) {
    Fpo("FPO data"),
    Exception("Exception data"),
    Fixup("Fixup data"),
    OmapToSource("OMAP to source"),
    OmapFromSource("OMAP from source"),
    SectionHeaders("Section headers"),
    TokenRidMap("Token/RID map"),
    Xdata("Xdata"),
    Pdata("Pdata"),
    NewFpo("New FPO data"),
    OriginalSectionHeaders("Original section headers"),
}

/** Header of the TPI (stream 2) or IPI (stream 4) type-record stream. */
data class PdbTypeStreamHeader(
    val version: UInt,
    val headerSize: UInt,
    val typeIndexBegin: UInt,
    val typeIndexEnd: UInt,
    val typeRecordBytes: UInt,
    val hashStream: Int?,
    val hashAuxStream: Int?,
) {
    val recordCount: Long get() = (typeIndexEnd.toLong() - typeIndexBegin.toLong()).coerceAtLeast(0)
}
