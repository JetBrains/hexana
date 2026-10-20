package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.containsRange
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryFormatDetector
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.providers.elf.ArmAttributesParser
import org.jetbrains.hexana.providers.elf.ElfDwarfAccessor
import org.jetbrains.hexana.providers.elf.ElfDynamicEntry
import org.jetbrains.hexana.providers.elf.ElfHeader
import org.jetbrains.hexana.providers.elf.ElfParser
import org.jetbrains.hexana.providers.elf.ElfProgramHeader
import org.jetbrains.hexana.providers.elf.ElfRelocation
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.elf.ElfSymbol
import org.jetbrains.hexana.providers.macho.MachODwarfAccessor
import org.jetbrains.hexana.providers.macho.MachODylibCommand
import org.jetbrains.hexana.providers.macho.MachODysymtabCommand
import org.jetbrains.hexana.providers.macho.MachOFatArch
import org.jetbrains.hexana.providers.macho.MachOFatHeader
import org.jetbrains.hexana.providers.macho.MachOHeader
import org.jetbrains.hexana.providers.macho.MachOLoadCommand
import org.jetbrains.hexana.providers.macho.MachOParser
import org.jetbrains.hexana.providers.macho.MachORelocation
import org.jetbrains.hexana.providers.macho.MachOSection
import org.jetbrains.hexana.providers.macho.MachOSegment
import org.jetbrains.hexana.providers.macho.MachOSymbol
import org.jetbrains.hexana.providers.macho.MachOSymtabCommand
import org.jetbrains.hexana.providers.macho.MachOUuid
import org.jetbrains.hexana.providers.pe.PeArchiveFile
import org.jetbrains.hexana.providers.pe.PeArchiveMember
import org.jetbrains.hexana.providers.pe.PeBaseRelocation
import org.jetbrains.hexana.providers.pe.PeCoffHeader
import org.jetbrains.hexana.providers.pe.PeCoffSymbol
import org.jetbrains.hexana.providers.pe.PeDebugEntry
import org.jetbrains.hexana.providers.pe.PeDosHeader
import org.jetbrains.hexana.providers.pe.PeExportDirectory
import org.jetbrains.hexana.providers.pe.PeImportLibrary
import org.jetbrains.hexana.providers.pe.PeOptionalHeader
import org.jetbrains.hexana.providers.pe.PeParser
import org.jetbrains.hexana.providers.pe.PeSection
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.providers.pe.clr.ClrMetadataParser
import org.jetbrains.hexana.tar.TarEntry
import org.jetbrains.hexana.tar.TarParser
import org.jetbrains.hexana.zip.ZipEntry
import org.jetbrains.hexana.zip.ZipParser
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.SectionScanFailure
import org.jetbrains.hexana.wasm.SectionScanFailureKind
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.customSectionByName
import org.jetbrains.hexana.wasm.analysis.GraalJavaSymbol
import org.jetbrains.hexana.wasm.analysis.GraalSymbolDemangler
import org.jetbrains.hexana.wasm.analysis.GraalWebImageDetector
import org.jetbrains.hexana.wasm.analysis.GraalWebImageInfo
import org.jetbrains.hexana.wasm.analysis.KotlinWasmDetector
import org.jetbrains.hexana.wasm.analysis.KotlinWasmInfo
import org.jetbrains.hexana.wasm.analysis.KotlinWasmNameParser
import org.jetbrains.hexana.wasm.analysis.KotlinWasmSymbol
import org.jetbrains.hexana.wasm.analysis.MinificationResult
import org.jetbrains.hexana.wasm.analysis.MonosAnalyzer
import org.jetbrains.hexana.wasm.analysis.MonosResult
import org.jetbrains.hexana.wasm.analysis.WasmMinificationDetector
import org.jetbrains.hexana.wasm.analysis.WasmProposal
import org.jetbrains.hexana.wasm.analysis.WasmProposalDetector
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageAnalyzer
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageResult
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.SizeAnalysisBuilder
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphBuilder
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphResult
import org.jetbrains.hexana.wasm.analysis.dispatch.DispatchCandidates
import org.jetbrains.hexana.wasm.analysis.dispatch.DispatchCandidatesBuilder
import org.jetbrains.hexana.wasm.analysis.typegraph.TypeGraphIndex
import org.jetbrains.hexana.wasm.analysis.typegraph.TypeGraphIndexBuilder
import org.jetbrains.hexana.wasm.analysis.exceptions.ExceptionPropagation
import org.jetbrains.hexana.wasm.analysis.exceptions.ExceptionPropagationBuilder
import org.jetbrains.hexana.wasm.analysis.exceptions.WasmExceptionTags
import org.jetbrains.hexana.wasm.analysis.exceptions.WasmExceptionTagsBuilder
import org.jetbrains.hexana.wasm.analysis.graph.DominatorResult
import org.jetbrains.hexana.wasm.analysis.graph.DominatorTree
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor
import org.jetbrains.hexana.wasm.sections.*

sealed interface BinaryProvider {
    /**
     * Returns a buffer view with an independent position cursor on every access.
     * The underlying bytes are shared; the cursor is private to the caller, so concurrent
     * readers (e.g. UI panels triggered on `Dispatchers.IO`) cannot clobber each other.
     *
     * Implementations MUST return `sharedBytes.duplicate()`, never the same instance twice.
     * Callers that need cursor continuity across multiple reads MUST cache the result into
     * a local: `val buf = bytes; buf.position(...); buf.get(...)` — `bytes; bytes` would
     * give two different cursors.
     */
    val bytes: CommonByteBuffer

    /**
     * Hierarchical size breakdown of the binary, or `null` when the format
     * does not (yet) support size profiling.
     */
    val sizeAnalysis: BinarySizeNode? get() = null

    val strings: List<BinaryStringRecord> get() = emptyList()

    /**
     * Top-level structural regions of the binary (e.g. sections, segments),
     * each with a byte offset, size, and human-readable label. Used by the
     * hex viewer to apply very subtle background tinting so the user can
     * visually distinguish structural boundaries without distraction.
     *
     * Returns an empty list when the format is unknown or has no meaningful
     * structure to expose.
     */
    val structureRanges: List<StructureRange> get() = emptyList()
}

/**
 * A named byte range representing a top-level structural region of a binary
 * (e.g. a Wasm section, an ELF section, a Mach-O segment).
 */
data class StructureRange(
    val offset: Long,
    val size: Long,
    val label: String,
    /**
     * Optional tab description that this structure range corresponds to in the
     * bottom panel. When non-null, section navigation arrows can switch to the
     * matching tab automatically.
     */
    val tabName: String? = null,
)

/**
 * Builds a [StructureRange] for a region, or `null` when the region is empty or does
 * not lie inside a [fileSize]-byte file.
 *
 * The hex viewers binary-search a sorted list of ranges to find the one covering the
 * caret, so a region a malformed header declares past the end of the file must never
 * make it into the list. Formats whose offsets come straight from unvalidated header
 * fields build their ranges with `mapNotNull { structureRangeOrNull(...) }`.
 *
 * The bound is written as `offset > fileSize - size` rather than
 * `offset + size > fileSize` on purpose: Parquet page offsets and Arrow block offsets
 * are 64-bit values read straight out of the file, and summing two of them overflows
 * into a negative number that would slip past the comparison.
 */
internal fun structureRangeOrNull(
    offset: Long,
    size: Long,
    label: String,
    fileSize: Int,
    tabName: String? = null,
): StructureRange? {
    if (size <= 0L || offset < 0L || offset > fileSize.toLong() - size) return null
    return StructureRange(offset = offset, size = size, label = label, tabName = tabName)
}

object WasmBinaryProviderSelector {
    fun select(bytes: CommonByteBuffer): BinaryProviderType {
        try {
            val byteArray = ByteArray(8)
            bytes.get(byteArray, 0, byteArray.size)
            bytes.position(0)
            if (byteArray[0] != 0x00.toByte() || byteArray[1] != 0x61.toByte() || byteArray[2] != 0x73.toByte() || byteArray[3] != 0x6d.toByte()) {
                return BinaryProviderType.Generic
            }
            when(byteArray[4].toInt().and(0xFF) to byteArray[5].toInt().and(0xFF) ) {
                0x01.toByte().toInt() to 0x00.toByte().toInt() -> return BinaryProviderType.Wasm
                0x0d.toByte().toInt() to 0x00.toByte().toInt() -> return BinaryProviderType.WasmComponent
                else -> return BinaryProviderType.Generic
            }

        } catch (e: Exception) {
            return BinaryProviderType.Generic
        }
    }
}

sealed class BinaryProviderType {
    object Wasm : BinaryProviderType()
    object WasmComponent : BinaryProviderType()
    object Generic : BinaryProviderType()
}

open class GenericBinaryProvider(private val sharedBytes: CommonByteBuffer) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    val nativeFormat: NativeBinaryFormat? by lazy {
        NativeBinaryFormatDetector.detect(bytes)
    }
}

/**
 * Provider for ZIP / JAR archives.
 *
 * Lazily parses the central directory once and caches the entry list so that
 * table-tab providers and enrichers can reuse it without re-parsing.
 */
class ZipBinaryProvider(private val sharedBytes: CommonByteBuffer) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    /** All entries from the ZIP central directory, or empty on parse failure. */
    val zipEntries: List<ZipEntry> by lazy {
        try {
            ZipParser.parse(sharedBytes.duplicate().position(0))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Non-directory entries suitable for display in the entries table. */
    val displayEntries: List<ZipEntry> by lazy {
        zipEntries.filter { !it.isDirectory }
    }

    /**
     * One range per entry, spanning the local file header and the compressed bytes
     * that follow it, so the record shows up in the hex view as a single region.
     */
    override val structureRanges: List<StructureRange> by lazy {
        val total = bytes.size
        zipEntries.mapNotNull { entry ->
            structureRangeOrNull(
                offset = entry.localHeaderOffset,
                size = entry.dataOffset - entry.localHeaderOffset + entry.compressedSize,
                label = entry.name,
                fileSize = total,
                tabName = "Entries",
            )
        }
    }

    companion object {
        /** PK\x03\x04 local file header magic. */
        fun isZip(buffer: CommonByteBuffer): Boolean {
            if (buffer.size < 4) return false
            return buffer[0] == 0x50.toByte() &&
                buffer[1] == 0x4B.toByte() &&
                buffer[2] == 0x03.toByte() &&
                buffer[3] == 0x04.toByte()
        }
    }
}

/**
 * Provider for TAR archives (plain `.tar` and decompressed `.tar.gz` / `.tgz`).
 *
 * Lazily parses the tar header chain once and caches the entry list so that
 * table-tab providers and enrichers can reuse it without re-parsing.
 */
class TarBinaryProvider(private val sharedBytes: CommonByteBuffer) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    /** All entries from the TAR archive, or empty on parse failure. */
    val tarEntries: List<TarEntry> by lazy {
        try {
            TarParser.parse(sharedBytes.duplicate().position(0))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Non-directory entries suitable for display in the entries table. */
    val displayEntries: List<TarEntry> by lazy {
        tarEntries.filter { !it.isDirectory }
    }

    /**
     * One range per entry, spanning its header block and its data. An entry's own
     * header block always sits immediately before its data — GNU long-name entries
     * put their extra blocks before that header, not between it and the data — so
     * `dataOffset - 512` is the start of the record. Trailing block padding is left
     * out; it belongs to no entry and is zero-filled.
     */
    override val structureRanges: List<StructureRange> by lazy {
        val total = bytes.size
        tarEntries.mapNotNull { entry ->
            structureRangeOrNull(
                offset = entry.dataOffset - TAR_BLOCK_SIZE,
                size = TAR_BLOCK_SIZE + entry.size,
                label = entry.name,
                fileSize = total,
                tabName = "Entries",
            )
        }
    }

    companion object {
        /** Delegates to [TarParser.isTar]. */
        fun isTar(buffer: CommonByteBuffer): Boolean = TarParser.isTar(buffer)
    }
}

/** Size of a TAR header block; every entry's data starts one block after its header. */
private const val TAR_BLOCK_SIZE = 512L

sealed class NativeBinaryProvider(
    private val sharedBytes: CommonByteBuffer,
    val sourcePath: String? = null,
) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()
    abstract val nativeFormat: NativeBinaryFormat

    override val strings: List<BinaryStringRecord> by lazy {
        NativeStringScanner.scanAscii(bytes)
    }

    open val nativeFunctions: List<NativeFunctionRecord>? get() = null
    open val nativeSourceLocationProvider: NativeDwarfSourceLocationProvider? get() = null

    /**
     * GraalVM Native Image fingerprint for this binary, or `null` when it is not
     * (or cannot be confirmed to be) a native-image-produced executable. See
     * [NativeImageDetector]. Format-specific subclasses override this with the
     * signals available to them (strings, sections, symbols, exports).
     */
    open val nativeImageInfo: NativeImageInfo? get() = null

    /**
     * Diagnostics from the format's whole-file parser: why a header or table could not be
     * read, which the per-table lazies drop when they answer `null`. `null` for a format
     * without such a parser (archives); for a failed parse, the fatal diagnostic.
     *
     * It re-walks every table the parser knows, so it is lazy in each subclass and meant
     * for the binary inspections rather than for rendering a tab.
     */
    open val parseDiagnostics: List<NativeParserDiagnostic>? get() = null
}

class ElfBinaryProvider(
    sharedBytes: CommonByteBuffer,
    override val nativeFormat: NativeBinaryFormat.Elf,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {

    val header: ElfHeader? by lazy { ElfParser.parseHeader(bytes) }

    override val parseDiagnostics: List<NativeParserDiagnostic> by lazy { ElfParser.parse(bytes).diagnostics }

    val sections: List<ElfSectionHeader>? by lazy {
        header?.let { ElfParser.parseSectionHeaders(bytes, it) }
    }

    val programHeaders: List<ElfProgramHeader>? by lazy {
        header?.let { ElfParser.parseProgramHeaders(bytes, it) }
    }

    val symbols: List<ElfSymbol>? by lazy {
        val h = header ?: return@lazy null
        val s = sections ?: return@lazy null
        ElfParser.parseSymbols(bytes, h, s)
    }

    val relocations: List<ElfRelocation>? by lazy {
        val h = header ?: return@lazy null
        val s = sections ?: return@lazy null
        val sym = symbols ?: return@lazy null
        ElfParser.parseRelocations(bytes, h, s, sym)
    }

    val dynamicEntries: List<ElfDynamicEntry>? by lazy {
        val h = header ?: return@lazy null
        val s = sections ?: return@lazy null
        ElfParser.parseDynamicEntries(bytes, h, s)
    }

    override val nativeFunctions: List<NativeFunctionRecord>? by lazy {
        val sym = symbols ?: return@lazy null
        val sec = sections ?: return@lazy null
        val ph = programHeaders ?: return@lazy null
        NativeFunctionDiscovery.fromElf(sym, sec, ph, machine = nativeFormat.machine)
    }

    override val nativeSourceLocationProvider: NativeDwarfSourceLocationProvider? by lazy {
        val sec = sections ?: return@lazy null
        if (sec.none { it.name == ".debug_line" } || sec.none { it.name == ".debug_info" }) return@lazy null
        NativeDwarfSourceLocationProvider(bytes, ElfDwarfAccessor(sec))
    }

    override val nativeImageInfo: NativeImageInfo? by lazy {
        val sec = sections ?: return@lazy null
        val sym = symbols ?: return@lazy null
        val ph = programHeaders ?: return@lazy null
        NativeImageDetector.fromElf(strings, sec, sym, ph, bytes).takeIf { it !== NativeImageInfo.NONE }
    }

    override val sizeAnalysis: BinarySizeNode? by lazy {
        val secs = sections?.takeIf { it.isNotEmpty() } ?: return@lazy null
        val totalSize = bytes.size
        val children = secs.map { sec ->
            BinarySizeNode(
                name = sec.name.ifEmpty { "<unnamed>" },
                size = sec.size.toInt(),
                offset = sec.fileOffset.toInt(),
            )
        }
        BinarySizeNode(name = "ELF", size = totalSize, offset = 0, children = children)
    }

    /**
     * CPU arch profile from the aeabi `.ARM.attributes` section, or null if the section is absent,
     * the file is not EM_ARM, or the section is malformed. See [ArmAttributesParser].
     * Relevant values: 'M' = Microcontroller (Thumb-2 / Cortex-M), 'A' = Application, 'R' = Real-time.
     */
    val armProfile: Char? by lazy {
        if (nativeFormat.machine != 0x28) return@lazy null
        val sec = sections?.firstOrNull { it.name == ".ARM.attributes" } ?: return@lazy null
        ArmAttributesParser.parseCpuArchProfile(bytes, sec)
    }

    /**
     * NT_GNU_BUILD_ID from the ELF note section, as a lowercase hex string,
     * or null when the section is absent or malformed.
     */
    val buildId: String? by lazy {
        val h = header ?: return@lazy null
        val s = sections ?: return@lazy null
        ElfParser.parseGnuBuildId(bytes, s, h)
    }


    override val structureRanges: List<StructureRange> by lazy {
        val secs = sections?.takeIf { it.isNotEmpty() } ?: return@lazy emptyList()
        secs.filter { it.size > 0uL }.map { sec ->
            StructureRange(
                offset = sec.fileOffset.toLong(),
                size = sec.size.toLong(),
                label = sec.name.ifEmpty { "<unnamed>" },
            )
        }
    }
}

class MachOBinaryProvider(
    sharedBytes: CommonByteBuffer,
    override val nativeFormat: NativeBinaryFormat.MachO,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {

    val header: MachOHeader? by lazy { MachOParser.parseHeader(bytes) }

    override val parseDiagnostics: List<NativeParserDiagnostic> by lazy { MachOParser.parse(bytes).diagnostics }

    val loadCommands: List<MachOLoadCommand>? by lazy {
        header?.let { MachOParser.parseLoadCommands(bytes, it) }
    }

    val segments: List<MachOSegment>? by lazy {
        val h = header ?: return@lazy null
        val c = loadCommands ?: return@lazy null
        MachOParser.parseSegments(bytes, h, c)
    }

    val sections: List<MachOSection>? by lazy { segments?.flatMap { it.sections } }

    val uuid: MachOUuid? by lazy {
        val c = loadCommands ?: return@lazy null
        MachOParser.parseUuid(bytes, c)
    }

    val symtab: MachOSymtabCommand? by lazy {
        val h = header ?: return@lazy null
        val c = loadCommands ?: return@lazy null
        MachOParser.parseSymtab(bytes, h, c)
    }

    val dysymtab: MachODysymtabCommand? by lazy {
        val h = header ?: return@lazy null
        val c = loadCommands ?: return@lazy null
        MachOParser.parseDysymtab(bytes, h, c)
    }

    val symbols: List<MachOSymbol>? by lazy {
        val h = header ?: return@lazy null
        val s = symtab ?: return@lazy emptyList()
        MachOParser.parseSymbols(bytes, h, s)
    }

    val dylibs: List<MachODylibCommand>? by lazy {
        val h = header ?: return@lazy null
        val c = loadCommands ?: return@lazy null
        MachOParser.parseDylibs(bytes, h, c)
    }

    val relocations: List<MachORelocation>? by lazy {
        val h = header ?: return@lazy null
        val sec = sections ?: return@lazy null
        MachOParser.parseRelocations(bytes, h, sec, symbols.orEmpty())
    }

    override val nativeFunctions: List<NativeFunctionRecord>? by lazy {
        val sym = symbols ?: return@lazy null
        val sec = sections ?: return@lazy null
        NativeFunctionDiscovery.fromMachO(sym, sec)
    }

    override val nativeSourceLocationProvider: NativeDwarfSourceLocationProvider? by lazy {
        val sec = sections ?: return@lazy null
        if (sec.none { it.name == "__debug_line" } || sec.none { it.name == "__debug_info" }) return@lazy null
        NativeDwarfSourceLocationProvider(bytes, MachODwarfAccessor(sec))
    }

    // Section-scoped string scan: the VM identity properties live in __DATA,__data (not near
    // the start of the file), so a full-file scan with maxStrings=5_000 silently misses them
    // on large binaries. Scanning only the sections that conventionally hold string data avoids
    // the cap entirely and keeps the scan bounded.
    override val strings: List<BinaryStringRecord> by lazy {
        val sec = sections ?: return@lazy NativeStringScanner.scanAscii(bytes)
        val stringDataSections = sec.filter { it.name in STRING_DATA_SECTIONS && it.contentRange != null }
        if (stringDataSections.isEmpty()) return@lazy NativeStringScanner.scanAscii(bytes)
        NativeStringScanner.scanSections(
            bytes = bytes,
            sections = stringDataSections.map { it.fileOffset.toInt() to it.size.toInt() },
        )
    }

    override val nativeImageInfo: NativeImageInfo? by lazy {
        val sec = sections ?: return@lazy null
        val sym = symbols ?: return@lazy null
        NativeImageDetector.fromMachO(strings, sec, sym, bytes).takeIf { it !== NativeImageInfo.NONE }
    }

    override val sizeAnalysis: BinarySizeNode? by lazy {
        val segs = segments?.takeIf { it.isNotEmpty() } ?: return@lazy null
        val totalSize = bytes.size
        val children = segs.map { seg ->
            val sectionChildren = seg.sections.map { sec ->
                BinarySizeNode(
                    name = sec.name.ifBlank { "<unnamed>" },
                    size = sec.size.toInt(),
                    offset = sec.fileOffset.toInt(),
                )
            }
            BinarySizeNode(
                name = seg.name.ifBlank { "<unnamed>" },
                size = seg.fileSize.toInt(),
                offset = seg.fileOffset.toInt(),
                children = sectionChildren,
            )
        }
        BinarySizeNode(name = "Mach-O", size = totalSize, offset = 0, children = children)
    }

    override val structureRanges: List<StructureRange> by lazy {
        val segs = segments?.takeIf { it.isNotEmpty() } ?: return@lazy emptyList()
        segs.filter { it.fileSize > 0uL }.map { seg ->
            StructureRange(
                offset = seg.fileOffset.toLong(),
                size = seg.fileSize.toLong(),
                label = seg.name.ifBlank { "<unnamed>" },
            )
        }
    }

    companion object {
        // Mach-O section names (short name only, without segment prefix) that conventionally
        // hold string data. These are scanned for NativeImage identity strings instead of the
        // full file to avoid a fixed maxStrings cap silently truncating the scan on large binaries.
        private val STRING_DATA_SECTIONS = setOf("__cstring", "__data", "__const")
    }
}

class FatMachOBinaryProvider(
    sharedBytes: CommonByteBuffer,
    override val nativeFormat: NativeBinaryFormat.FatMachO,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {

    val fatHeader: MachOFatHeader? by lazy { MachOParser.parseFatHeader(bytes) }

    override val parseDiagnostics: List<NativeParserDiagnostic> by lazy { MachOParser.parse(bytes).diagnostics }

    val architectures: List<MachOFatArch>? by lazy {
        fatHeader?.let { MachOParser.parseFatArchitectures(bytes, it) }
    }

    override val sizeAnalysis: BinarySizeNode? by lazy {
        val archs = architectures?.takeIf { it.isNotEmpty() } ?: return@lazy null
        val totalSize = bytes.size
        val children = archs.map { arch ->
            BinarySizeNode(
                name = "Slice ${arch.index}",
                size = arch.size.toInt(),
                offset = arch.offset.toInt(),
            )
        }
        BinarySizeNode(name = "Universal", size = totalSize, offset = 0, children = children)
    }

    override val structureRanges: List<StructureRange> by lazy {
        val archs = architectures?.takeIf { it.isNotEmpty() } ?: return@lazy emptyList()
        archs.map { arch ->
            StructureRange(
                offset = arch.offset.toLong(),
                size = arch.size.toLong(),
                label = "Slice ${arch.index}",
            )
        }
    }
}

class PeBinaryProvider(
    sharedBytes: CommonByteBuffer,
    override val nativeFormat: NativeBinaryFormat.Pe,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {

    val dosHeader: PeDosHeader? by lazy { PeParser.parseDosHeader(bytes) }

    override val parseDiagnostics: List<NativeParserDiagnostic> by lazy { PeParser.parse(bytes).diagnostics }

    val coffHeader: PeCoffHeader? by lazy {
        dosHeader?.let { PeParser.parseCoffHeader(bytes, it) }
    }

    val optionalHeader: PeOptionalHeader? by lazy {
        val d = dosHeader ?: return@lazy null
        val c = coffHeader ?: return@lazy null
        PeParser.parseOptionalHeader(bytes, d, c)
    }

    val sections: List<PeSection>? by lazy {
        val d = dosHeader ?: return@lazy null
        val c = coffHeader ?: return@lazy null
        PeParser.parseSections(bytes, d, c)
    }

    val imports: List<PeImportLibrary>? by lazy {
        val o = optionalHeader ?: return@lazy null
        val s = sections ?: return@lazy null
        PeParser.parseImports(bytes, o, s)
    }

    val exports: PeExportDirectory? by lazy {
        val o = optionalHeader ?: return@lazy null
        val s = sections ?: return@lazy null
        PeParser.parseExports(bytes, o, s)
    }

    val baseRelocations: List<PeBaseRelocation>? by lazy {
        val o = optionalHeader ?: return@lazy null
        val s = sections ?: return@lazy null
        PeParser.parseBaseRelocations(bytes, o, s)
    }

    val debugEntries: List<PeDebugEntry>? by lazy {
        val o = optionalHeader ?: return@lazy null
        val s = sections ?: return@lazy null
        PeParser.parseDebugEntries(bytes, o, s)
    }

    val coffSymbols: List<PeCoffSymbol>? by lazy {
        val c = coffHeader ?: return@lazy null
        PeParser.parseCoffSymbols(bytes, c)
    }

    /** Managed (.NET) metadata; null for native images without a CLR runtime header. */
    val clrMetadata: ClrMetadata? by lazy {
        val o = optionalHeader ?: return@lazy null
        val s = sections ?: return@lazy null
        ClrMetadataParser.parse(bytes, o, s)
    }

    /** Cheap check that does not decode metadata: true when the image declares a CLR runtime header. */
    val isManaged: Boolean by lazy { optionalHeader?.let(ClrMetadataParser::hasClrHeader) == true }

    /**
     * True for a managed image with no native code worth disassembling: IL-only, and not
     * ReadyToRun-compiled. Reads only the CLI header, so it is cheap enough for tab discovery.
     * Mixed-mode (C++/CLI) and ReadyToRun images report false.
     */
    val isPureIl: Boolean by lazy {
        val o = optionalHeader ?: return@lazy false
        val s = sections ?: return@lazy false
        val header = ClrMetadataParser.parseCliHeader(bytes, o, s) ?: return@lazy false
        header.isIlOnly && !header.hasNativeEntryPoint && !ClrMetadataParser.isReadyToRun(bytes, header, o, s)
    }

    override val nativeImageInfo: NativeImageInfo? by lazy {
        val sec = sections ?: return@lazy null
        NativeImageDetector.fromPe(strings, sec, exports, bytes).takeIf { it !== NativeImageInfo.NONE }
    }

    override val sizeAnalysis: BinarySizeNode? by lazy {
        val secs = sections?.takeIf { it.isNotEmpty() } ?: return@lazy null
        val totalSize = bytes.size
        val clrNodes = clrMetadata?.let(::clrSizeNodes).orEmpty()
        val children = secs.map { sec ->
            val start = sec.rawDataPointer.toLong()
            val end = start + sec.rawDataSize.toLong()
            BinarySizeNode(
                name = sec.name.ifBlank { "<unnamed>" },
                size = sec.rawDataSize.toInt(),
                offset = sec.rawDataPointer.toInt(),
                children = clrNodes.filter { it.offset >= start && it.offset + it.size.toLong() <= end },
            )
        }
        BinarySizeNode(name = "PE", size = totalSize, offset = 0, children = children)
    }

    override val structureRanges: List<StructureRange> by lazy {
        val secs = sections?.takeIf { it.isNotEmpty() } ?: return@lazy emptyList()
        secs.filter { it.rawDataSize > 0u }.map { sec ->
            StructureRange(
                offset = sec.rawDataPointer.toLong(),
                size = sec.rawDataSize.toLong(),
                label = sec.name.ifBlank { "<unnamed>" },
            )
        }
    }
}

/**
 * Size-analysis nodes for the managed parts of a PE image, placed under the section that
 * contains them: the CLI metadata (with one child per stream) and embedded managed resources.
 */
private fun clrSizeNodes(metadata: ClrMetadata): List<BinarySizeNode> = buildList {
    val root = metadata.root
    val streams = metadata.streams.mapNotNull { stream ->
        stream.contentRange?.takeIf { it.size > 0 }?.let { BinarySizeNode(name = stream.name, size = it.size, offset = it.offset) }
    }
    if (root != null && streams.isNotEmpty()) {
        val start = minOf(root.range.offset, streams.minOf { it.offset })
        val end = streams.maxOf { it.offset + it.size }
        add(BinarySizeNode(name = "CLR metadata", size = end - start, offset = start, children = streams))
    }
    val resources = metadata.manifestResources.mapNotNull { resource ->
        resource.dataRange?.takeIf { it.size > 0 }?.let { BinarySizeNode(name = resource.name, size = it.size, offset = it.offset) }
    }
    if (resources.isNotEmpty()) {
        val start = resources.minOf { it.offset }
        val end = resources.maxOf { it.offset + it.size }
        add(BinarySizeNode(name = "Managed resources", size = end - start, offset = start, children = resources))
    }
}

class CoffBinaryProvider(
    sharedBytes: CommonByteBuffer,
    override val nativeFormat: NativeBinaryFormat.Coff,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {

    val coffHeader: PeCoffHeader? by lazy { PeParser.parseCoffObjectHeader(bytes) }

    override val parseDiagnostics: List<NativeParserDiagnostic> by lazy { PeParser.parse(bytes).diagnostics }

    val sections: List<PeSection>? by lazy {
        val h = coffHeader ?: return@lazy null
        PeParser.parseCoffObjectSections(bytes, h)
    }

    val coffSymbols: List<PeCoffSymbol>? by lazy {
        val h = coffHeader ?: return@lazy null
        PeParser.parseCoffSymbols(bytes, h)
    }

    override val nativeFunctions: List<NativeFunctionRecord>? by lazy {
        val syms = coffSymbols ?: return@lazy null
        val secs = sections ?: return@lazy null
        NativeFunctionDiscovery.fromCoff(syms, secs)
    }

    override val structureRanges: List<StructureRange> by lazy {
        val secs = sections?.takeIf { it.isNotEmpty() } ?: return@lazy emptyList()
        val total = bytes.size
        secs.mapNotNull { sec ->
            structureRangeOrNull(
                offset = sec.rawDataPointer.toLong(),
                size = sec.rawDataSize.toLong(),
                label = sec.name.ifBlank { "<unnamed>" },
                fileSize = total,
            )
        }
    }
}

class ArchiveBinaryProvider(
    sharedBytes: CommonByteBuffer,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {
    override val nativeFormat: NativeBinaryFormat get() = NativeBinaryFormat.Archive

    /**
     * Lazily enumerated archive (Unix `ar` / Windows COFF import library).
     * Header-only walk — member bodies are never read here; [PeArchiveMember.contentRange]
     * records each member's byte range so a single member can be sliced on demand.
     */
    val archiveFile: PeArchiveFile? by lazy { PeParser.parseArchive(bytes) }

    /** All parsed members, including bookkeeping entries (see [PeArchiveMember.isBookkeeping]). */
    val members: List<PeArchiveMember> get() = archiveFile?.members.orEmpty()

    /**
     * One range per member, spanning its `ar` header and its payload — the two are
     * adjacent, so each member reads as a single region in the hex view. Bookkeeping
     * members are kept: they occupy real bytes the user can see.
     */
    override val structureRanges: List<StructureRange> by lazy {
        val total = bytes.size
        members.mapNotNull { member ->
            structureRangeOrNull(
                offset = member.headerRange.offset.toLong(),
                size = (member.headerRange.size + member.contentRange.size).toLong(),
                label = member.name,
                fileSize = total,
                tabName = "Members",
            )
        }
    }
}

/** Coarse classification of a static-library member, derived from its leading magic bytes. */
enum class ArchiveMemberKind {
    ELF,
    MACH_O,
    FAT_MACH_O,
    PE,
    COFF,
    ARCHIVE,
    SHORT_IMPORT,
    UNKNOWN,
}

data class ArchiveMemberClassification(
    val kind: ArchiveMemberKind,
    val displayName: String,
    /** Whether extracting this member yields a file Hexana can open in a binary view. */
    val openable: Boolean,
)

/**
 * Classifies a single archive member by its own format, reading only the member's leading
 * header fields. [archiveBytes] is the whole-archive buffer; [contentRange] is the member's
 * payload range within it. A zero-copy [CommonByteBuffer.slice] view is used so the COFF
 * detector (which validates section/symbol-table offsets against the buffer size) sees the
 * member's true length without materializing the member into the heap.
 */
fun classifyArchiveMember(
    archiveBytes: CommonByteBuffer,
    contentRange: org.jetbrains.hexana.parsing.common.NativeByteRange,
): ArchiveMemberClassification {
    val unknown = ArchiveMemberClassification(ArchiveMemberKind.UNKNOWN, "unknown", openable = false)
    if (contentRange.offset < 0 || contentRange.size < 4) return unknown
    if (contentRange.offset + contentRange.size > archiveBytes.size) return unknown

    val view = archiveBytes.slice(contentRange.offset, contentRange.size)

    // Windows short-import record: Sig1 = IMAGE_FILE_MACHINE_UNKNOWN (0x0000),
    // Sig2 = 0xFFFF (both little-endian u16). A real member, but it carries no
    // full object file to open.
    val b0 = view[0].toInt() and 0xFF
    val b1 = view[1].toInt() and 0xFF
    val b2 = view[2].toInt() and 0xFF
    val b3 = view[3].toInt() and 0xFF
    if (b0 == 0x00 && b1 == 0x00 && b2 == 0xFF && b3 == 0xFF) {
        return ArchiveMemberClassification(ArchiveMemberKind.SHORT_IMPORT, "Short import", openable = false)
    }

    return when (val fmt = NativeBinaryFormatDetector.detect(view)) {
        is NativeBinaryFormat.Elf -> ArchiveMemberClassification(ArchiveMemberKind.ELF, fmt.displayName, openable = true)
        is NativeBinaryFormat.MachO -> ArchiveMemberClassification(ArchiveMemberKind.MACH_O, fmt.displayName, openable = true)
        is NativeBinaryFormat.FatMachO -> ArchiveMemberClassification(ArchiveMemberKind.FAT_MACH_O, fmt.displayName, openable = true)
        is NativeBinaryFormat.Pe -> ArchiveMemberClassification(ArchiveMemberKind.PE, fmt.displayName, openable = true)
        is NativeBinaryFormat.Coff -> ArchiveMemberClassification(ArchiveMemberKind.COFF, fmt.displayName, openable = true)
        NativeBinaryFormat.Archive -> ArchiveMemberClassification(ArchiveMemberKind.ARCHIVE, fmt.displayName, openable = true)
        null -> unknown
    }
}

fun nativeOrGenericBinaryProvider(
    sharedBytes: CommonByteBuffer,
    sourcePath: String? = null,
): BinaryProvider {
    return when {
        ZipBinaryProvider.isZip(sharedBytes) -> ZipBinaryProvider(sharedBytes)
        TarBinaryProvider.isTar(sharedBytes) -> TarBinaryProvider(sharedBytes)
        ClassFileBinaryProvider.isClassFile(sharedBytes) -> ClassFileBinaryProvider(sharedBytes.duplicate().position(0))
        DexBinaryProvider.isDexFile(sharedBytes) -> DexBinaryProvider(sharedBytes.duplicate().position(0))
        ArscBinaryProvider.isArscFile(sharedBytes) -> ArscBinaryProvider(sharedBytes.duplicate().position(0))
        PcapBinaryProvider.isPcapFile(sharedBytes) ->
            PcapBinaryProvider(sharedBytes.duplicate().position(0), sourcePath)
        ProtobufBinaryProvider.isFileDescriptorSet(sharedBytes) -> ProtobufBinaryProvider(sharedBytes.duplicate().position(0))
        else -> {
            when (val fmt = NativeBinaryFormatDetector.detect(sharedBytes)) {
                is NativeBinaryFormat.Elf -> ElfBinaryProvider(sharedBytes, fmt, sourcePath)
                is NativeBinaryFormat.MachO -> MachOBinaryProvider(sharedBytes, fmt, sourcePath)
                is NativeBinaryFormat.FatMachO -> FatMachOBinaryProvider(sharedBytes, fmt, sourcePath)
                is NativeBinaryFormat.Pe -> PeBinaryProvider(sharedBytes, fmt, sourcePath)
                is NativeBinaryFormat.Coff -> CoffBinaryProvider(sharedBytes, fmt, sourcePath)
                NativeBinaryFormat.Archive -> ArchiveBinaryProvider(sharedBytes, sourcePath)
                null -> GenericBinaryProvider(sharedBytes)
            }
        }
    }
}

open class WasmComponentBinaryProvider(private val sharedBytes: CommonByteBuffer): BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    private val parseResult = WasmParser().parse(bytes)

    val wasmModule = when (parseResult) {
        is WasmParseSuccess -> parseResult.wasmModule as? WasmComponent
        else -> null
    }

    private val sectionScanFailures: List<SectionScanFailure> = when (parseResult) {
        is WasmParseSuccess -> parseResult.sectionScanFailures
        else -> emptyList()
    }

    fun canProvideWasmModule() = wasmModule != null
    open val sourceLocationProvider: HexanaSourceLocationProvider = HexanaSourceLocationProvider.EMPTY

    val imports: List<WasmComponentImport> by lazy {
        val importSections = wasmModule?.importSection ?: return@lazy emptyList()
        importSections.flatMap {
            with(it) {
                bytes.scan()
            }
        }
    }

    val exports: List<WasmComponentExport> by lazy {
        val exportSections = wasmModule?.exportSection ?: return@lazy emptyList()
        exportSections.flatMap {
            with(it) {
                bytes.scan()
            }
        }
    }
    val modules: List<WasmComponentCoreModule> by lazy {
        val moduleSections = wasmModule?.moduleSection ?: return@lazy emptyList()
        moduleSections.map {
            with(it) {
                bytes.scan()
            }
        }
    }

    override val sizeAnalysis: SizeNode.Module? by lazy {
        wasmModule?.let { SizeAnalysisBuilder(bytes, it).buildTree() }
    }

    override val structureRanges: List<StructureRange> by lazy {
        val module = wasmModule ?: return@lazy emptyList()
        module.sections.map { section ->
            StructureRange(
                offset = section.sectionOffset.toLong(),
                size = section.sectionSize.toLong(),
                label = section.name,
            )
        }
    }

    val instances: List<InstanceExpression> by lazy {
        val instanceSections = wasmModule?.instanceSection ?: return@lazy emptyList()
        instanceSections.flatMap {
            with(it) {
                bytes.parseInstances()
            }
        }
    }

    val coreInstances: List<InstanceCoreExpression> by lazy {
        val coreInstanceSections = wasmModule?.coreInstanceSection ?: return@lazy emptyList()
        coreInstanceSections.flatMap {
            with(it) {
                bytes.parseCoreInstances()
            }
        }
    }

    /**
     * Parse problems found in this component, including entries whose decoded
     * prefix is retained and a nested component whose header is not identified.
     * Capped to keep malformed inputs from producing an unbounded diagnostic list.
     */
    val parseProblems: List<ParseProblem> by lazy {
        val module = wasmModule ?: return@lazy emptyList()
        val problems = mutableListOf<ParseProblem>()
        var nextFailure = 0

        fun addFailuresThrough(offset: Int) {
            while (nextFailure < sectionScanFailures.size &&
                sectionScanFailures[nextFailure].byteOffset <= offset &&
                problems.size < MAX_PARSE_PROBLEMS
            ) {
                problems.add(sectionScanFailures[nextFailure++].toParseProblem())
            }
        }

        for (section in module.sections) {
            addFailuresThrough(section.sectionOffset)
            if (problems.size >= MAX_PARSE_PROBLEMS) break
            when (section) {
                is WasmCoreInstanceSection -> {
                    val entries = with(section) { bytes.parseCoreInstanceProblems(MAX_PARSE_PROBLEMS - problems.size) }
                    for (entry in entries) {
                        entry.toComponentParseProblem()?.let(problems::add)
                        if (problems.size >= MAX_PARSE_PROBLEMS) break
                    }
                }
                is WasmInstanceSection -> with(section) { bytes.parseInstancesPartial().second }
                    ?.let { problems.add(section.parseProblemAt(it, "instance entry")) }
                is WasmComponentTypesSection -> with(section) { bytes.scanTypesPartial().second }
                    ?.let { problems.add(section.parseProblemAt(it, "type entry")) }
                is WasmComponentCoreTypeSection -> with(section) { bytes.scanTypesPartial().second }
                    ?.let { problems.add(section.parseProblemAt(it, "core type entry")) }
                is WasmAliasSection -> with(section) { bytes.scanAliasesPartial().second }
                    ?.let { problems.add(section.parseProblemAt(it, "alias entry")) }
                is WasmComponentImportSection -> with(section) { bytes.scanPartial().second }
                    ?.let { problems.add(section.parseProblemAt(it, "import entry")) }
                is WasmComponentExportSection -> with(section) { bytes.scanPartial().second }
                    ?.let { problems.add(section.parseProblemAt(it, "export entry")) }
                is WasmComponentSection -> {
                    if (!sharedBytes.containsRange(section.contentOffset, section.contentSize)) continue
                    val nested = try {
                        with(section) { bytes.parseNestedComponent() }
                    } catch (_: Exception) {
                        null
                    }
                    if (nested == null) problems.add(section.parseProblemAt(section.contentOffset, "nested component"))
                }
            }
            if (problems.size >= MAX_PARSE_PROBLEMS) break
        }
        while (nextFailure < sectionScanFailures.size && problems.size < MAX_PARSE_PROBLEMS) {
            problems.add(sectionScanFailures[nextFailure++].toParseProblem())
        }
        problems.sortedBy { it.byteOffset }
    }

    val nestedComponents: List<NestedComponentData> by lazy {
        val componentSection = wasmModule?.componentSection ?: return@lazy emptyList()
        // Single duplicate for the whole sequence: parseNestedComponent + position + get
        // must share one cursor.
        val buf = bytes
        val component = with(componentSection) { buf.parseNestedComponent() } ?: return@lazy emptyList()
        buf.position(componentSection.contentOffset)
        val nestedBytes = ByteArray(componentSection.contentSize)
        buf.get(nestedBytes, 0, componentSection.contentSize)
        listOf(NestedComponentData(component, nestedBytes.toCommonByteBuffer()))
    }

    val types: List<ComponentDefType> by lazy {
        wasmModule ?: return@lazy emptyList()
        buildComponentTypeIndex(wasmModule.sections, bytes)
    }

    fun nestedComponentExports(componentIndex: Int): List<WasmComponentExport> {
        val data = nestedComponents.getOrNull(componentIndex) ?: return emptyList()
        return data.component.exportSection.flatMap {
            with(it) {
                data.buffer.scan()
            }
        }
    }

    fun nestedComponentTypes(componentIndex: Int): List<ComponentDefType> {
        val data = nestedComponents.getOrNull(componentIndex) ?: return emptyList()
        return buildComponentTypeIndex(data.component.sections, data.buffer)
    }

    private companion object {
        const val MAX_PARSE_PROBLEMS = 1000
    }
}

class NestedComponentData(val component: WasmComponent, val buffer: CommonByteBuffer)

fun buildComponentTypeIndex(sections: List<WasmSection>, buffer: CommonByteBuffer): List<ComponentDefType> {
    return buildComponentTypeIndex(
        sections,
        { section -> with(section) { buffer.scanTypes() } },
        { section -> with(section) { buffer.scanAliases() } },
        { section -> with(section) { buffer.scan() } },
    )
}

fun buildComponentTypeIndexPartial(sections: List<WasmSection>, buffer: CommonByteBuffer): List<ComponentDefType> {
    return buildComponentTypeIndex(
        sections,
        { section -> with(section) { buffer.scanTypesPartial().first } },
        { section -> with(section) { buffer.scanAliasesPartial().first } },
        { section -> with(section) { buffer.scanPartial().first } },
    )
}

private fun buildComponentTypeIndex(
    sections: List<WasmSection>,
    scanTypes: (WasmComponentTypesSection) -> List<ComponentDefType>,
    scanAliases: (WasmAliasSection) -> List<ComponentAliasDef>,
    scanImports: (WasmComponentImportSection) -> List<WasmComponentImport>,
): List<ComponentDefType> {
    val types = mutableListOf<ComponentDefType>()
    for (section in sections) {
        when (section) {
            is WasmComponentTypesSection -> {
                val sectionTypes = scanTypes(section)
                types.addAll(sectionTypes)
            }
            is WasmAliasSection -> {
                val aliases = scanAliases(section)
                for (alias in aliases) {
                    if (alias is ComponentAlias && alias.componentSort is ComponentSort.Type) {
                        types.add(ComponentAliasedType(alias))
                    }
                }
            }
            is WasmComponentImportSection -> {
                val imports = scanImports(section)
                for (import in imports) {
                    if (import.externDescription is ComponentExternTypeBound) {
                        types.add(ComponentImportedType(import))
                    }
                }
            }
            else -> { /* other sections don't contribute to the type index space */ }
        }
    }
    return types
}

private fun WasmSection.parseProblemAt(offset: Int, entryName: String) = ParseProblem(
    kind = ParseProblemKind.SECTION_ENTRY_CORRUPT,
    sectionId = when (this) {
        is WasmComponentCoreTypeSection -> WasmComponentCoreTypeSection.SECTION_ID.toInt()
        is WasmComponentTypesSection -> WasmComponentTypesSection.SECTION_ID.toInt()
        is WasmInstanceSection -> WasmInstanceSection.SECTION_ID.toInt()
        is WasmAliasSection -> WasmAliasSection.SECTION_ID.toInt()
        is WasmComponentImportSection -> WasmComponentImportSection.SECTION_ID.toInt()
        is WasmComponentExportSection -> WasmComponentExportSection.SECTION_ID.toInt()
        is WasmComponentSection -> WasmComponentSection.SECTION_ID.toInt()
        else -> 0
    },
    sectionName = name,
    funcIdx = null,
    typeIdx = null,
    byteOffset = offset,
    byteSize = (sectionOffset + sectionSize - offset).coerceAtLeast(1),
    message = "$entryName could not be decoded at offset $offset",
)

private fun InstanceCoreExpression.toComponentParseProblem(): ParseProblem? = when (this) {
    is UnknownInstanceCoreExpression -> ParseProblem(
        kind = ParseProblemKind.UNKNOWN_CORE_INSTANCE_TAG,
        sectionId = WasmCoreInstanceSection.SECTION_ID.toInt(),
        sectionName = "Instance",
        funcIdx = null,
        typeIdx = null,
        byteOffset = offset,
        byteSize = 1,
        message = "unknown core-instance tag 0x${tag.toHexString()}",
    )
    is CorruptInstanceCoreExpression -> ParseProblem(
        kind = ParseProblemKind.CORE_INSTANCE_ENTRY_CORRUPT,
        sectionId = WasmCoreInstanceSection.SECTION_ID.toInt(),
        sectionName = "Instance",
        funcIdx = null,
        typeIdx = null,
        byteOffset = offset,
        byteSize = size,
        message = message,
    )
    else -> null
}

private fun SectionScanFailure.toParseProblem() = ParseProblem(
    kind = toParseProblemKind(),
    sectionId = 0,
    sectionName = when (kind) {
        SectionScanFailureKind.TRUNCATED -> "Section header"
        SectionScanFailureKind.RESOLVER_FAILURE -> "Custom section"
    },
    funcIdx = null,
    typeIdx = null,
    byteOffset = byteOffset,
    byteSize = 1,
    message = message,
)

private fun SectionScanFailure.toParseProblemKind() = when (kind) {
    SectionScanFailureKind.TRUNCATED -> ParseProblemKind.SECTION_TRUNCATED
    SectionScanFailureKind.RESOLVER_FAILURE -> ParseProblemKind.SECTION_RESOLVE_FAILURE
}

open class WasmBinaryProvider(private val sharedBytes: CommonByteBuffer) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    private val parseResult = WasmParser().parse(bytes)

    val parsedModule = when (parseResult) {
        is WasmParseSuccess -> parseResult.wasmModule
        else -> null
    }

    /** Section-header / custom-section-name failures recorded during parse; cheap (no lazy forcing). */
    val hasSectionScanFailures: Boolean
        get() = sectionScanFailures.isNotEmpty()

    private val sectionScanFailures: List<SectionScanFailure> = when (parseResult) {
        is WasmParseSuccess -> parseResult.sectionScanFailures
        else -> emptyList()
    }

    val wasmModule = parsedModule as? WasmModule

    fun canProvideWasmModule() = wasmModule != null

    /**
     * Payload of the `sourceMappingURL` custom section — a single string
     * holding the source map's URL or path (Kotlin/Wasm and Emscripten emit
     * it) — or null when the section is absent or unreadable.
     */
    val sourceMappingUrl: String? by lazy {
        val section = wasmModule?.customSectionByName("sourceMappingURL") ?: return@lazy null
        runCatching {
            val buf = bytes
            buf.position(section.contentOffset)
            buf.consumeString()
        }.getOrNull()
    }

    // Cached (not a fresh instance per access): the provider builds its merged
    // line-row table lazily on first query, and per-access construction would
    // rebuild it for every lookup. Queries take module-absolute offsets; wasm
    // DWARF addresses are code-section-content-relative, hence the base.
    open val sourceLocationProvider: HexanaSourceLocationProvider by lazy {
        wasmModule?.let {
            DwarfSourceLocationProvider(
                bytes,
                WasmDwarfAccessor(it),
                addressBase = it.codeSection?.contentOffset?.toLong() ?: 0L,
            )
        } ?: HexanaSourceLocationProvider.EMPTY
    }

    val imports: List<WasmImportRecord> by lazy {
        val importSection = wasmModule?.importSection ?: return@lazy emptyList()
        try {
            with (importSection) {
                bytes.getImportRecords()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val exports: List<WasmExportRecordEntry> by lazy {
        val exportsSection = wasmModule?.exportSection ?: return@lazy emptyList()
        try {
            with (exportsSection) {
                bytes.scan()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val data: List<WasmDataRecord> by lazy {
        val dataSection = wasmModule?.dataSection ?: return@lazy emptyList()
        try {
            with (dataSection) {
                bytes.scan()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val nameSection: WasmNameSection? by lazy {
        if (wasmModule == null) return@lazy null
        with (wasmModule) {
            val customSection = getCustomSectionByName("name") ?: return@lazy null
            with (customSection) {
                bytes.parseAsNameSection()
            }
        }
    }

    /**
     * Whether this module was produced by GraalVM Web Image (Java→WASM), detected via the
     * `interop`/`jsbody`/`compat` import-module fingerprint. See [GraalWebImageDetector].
     */
    val graalWebImageInfo: GraalWebImageInfo by lazy {
        val module = wasmModule ?: return@lazy GraalWebImageInfo.NOT_WEB_IMAGE
        try {
            GraalWebImageDetector.detect(module, bytes)
        } catch (_: Exception) {
            GraalWebImageInfo.NOT_WEB_IMAGE
        }
    }

    /**
     * Best-effort Java symbol for a global function index (imports first, then defined functions),
     * available only when this is a Web Image module that retained function names. Returns `null`
     * for non-Web-Image modules, stripped names, or names that are not compiled Java methods.
     */
    fun graalSymbolFor(functionIndex: Int): GraalJavaSymbol? {
        if (!graalWebImageInfo.canResolveSymbols) return null
        val mangled = nameSection?.functionSubsection?.namemap?.get(functionIndex) ?: return null
        return GraalSymbolDemangler.demangle(mangled)
    }

    /**
     * Whether this module was produced by the Kotlin/Wasm (`wasmJs`) backend, detected via the
     * `js_code` import-module fingerprint. See [KotlinWasmDetector].
     */
    val kotlinWasmInfo: KotlinWasmInfo by lazy {
        val module = wasmModule ?: return@lazy KotlinWasmInfo.NOT_KOTLIN_WASM
        try {
            KotlinWasmDetector.detect(module, bytes)
        } catch (_: Exception) {
            KotlinWasmInfo.NOT_KOTLIN_WASM
        }
    }

    /**
     * Structured Kotlin symbol for a global function index (imports first, then defined functions),
     * available only when this is a Kotlin/Wasm module that retained function names. Returns `null`
     * for non-Kotlin/Wasm modules, stripped names, or names that are not Kotlin declarations.
     */
    fun kotlinSymbolFor(functionIndex: Int): KotlinWasmSymbol? {
        if (!kotlinWasmInfo.canResolveSymbols) return null
        val name = nameSection?.functionSubsection?.namemap?.get(functionIndex) ?: return null
        return KotlinWasmNameParser.parse(name)
    }

    val codeRecords: List<WasmCodeRecord> by lazy {
        val functionSection = wasmModule?.codeSection ?: return@lazy emptyList()
        with (functionSection) {
            bytes.scan()
        }
    }

    /** Lightweight offset index — only byte positions, no opcode parsing. */
    val codeOffsets: List<CodeEntryOffset> by lazy {
        val codeSection = wasmModule?.codeSection ?: return@lazy emptyList()
        with(codeSection) { bytes.scanOffsets() }
    }

    /** Parse a single code record by its index in the code section (0-based). */
    fun parseCodeRecord(codeIndex: Int): WasmCodeRecord? {
        val offset = codeOffsets.getOrNull(codeIndex) ?: return null
        return bytes.parseCodeEntry(offset.offset)
    }

    val functions: List<WasmTypeRecord> by lazy {
        wasmModule ?: return@lazy emptyList()
        val functionSection = wasmModule.functionSection ?: return@lazy emptyList()
        val typesSection = wasmModule.typesSection ?: return@lazy emptyList()

        try {
            val funs = with(functionSection) {
                 bytes.parseFunctions()
            }

            val types = with(typesSection) {
                bytes.parseTypes()
            }

            // TODO: Investigate when this can be a case and how this is better to be approached
            funs.mapNotNull { types.getOrNull(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override val sizeAnalysis: SizeNode.Module? by lazy {
        try {
            parsedModule?.let { SizeAnalysisBuilder(bytes, it).buildTree() }
        } catch (_: Exception) {
            null
        }
    }

    override val structureRanges: List<StructureRange> by lazy {
        val module = parsedModule ?: return@lazy emptyList()
        module.sections.map { section ->
            StructureRange(
                offset = section.sectionOffset.toLong(),
                size = section.sectionSize.toLong(),
                label = section.name,
                tabName = wasmSectionTabName(section.name),
            )
        }
    }

    val sizeAnalysisBuilder: SizeAnalysisBuilder? by lazy {
        parsedModule?.let { SizeAnalysisBuilder(bytes, it) }
    }

    val callGraphResult: CallGraphResult? by lazy {
        try {
            wasmModule?.let { CallGraphBuilder(bytes, it).build() }
        } catch (_: Exception) {
            null
        }
    }

    val callGraphDominators: DominatorResult? by lazy {
        try {
            callGraphResult?.let { DominatorTree.compute(it.graph) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Statically known `call_indirect` candidates grouped by canonical
     * signature. Lazy and cheap (one pass over type/import/function/element
     * sections, no code decoding) but still built off the EDT by UI callers.
     */
    val dispatchCandidates: DispatchCandidates? by lazy {
        try {
            wasmModule?.let { DispatchCandidatesBuilder(bytes, it).build() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reverse index of the WasmGC type hierarchy: supertype/subtype edges,
     * field-ref edges, and per-function references. Null for non-GC modules
     * and on parse error. Built lazily off the EDT; the code pass walks the
     * whole code section so callers must not invoke this on the EDT.
     */
    val typeGraphIndex: TypeGraphIndex? by lazy {
        try {
            wasmModule?.let { TypeGraphIndexBuilder(bytes, it).build() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Flattened list of type records in type-index order (rec-group entries
     * expanded). Index i corresponds to typeGraphIndex type index i.
     */
    val leafTypeRecords: List<WasmTypeRecord> by lazy {
        val section = wasmModule?.typesSection ?: return@lazy emptyList()
        val records = try { with(section) { bytes.parseTypes() } } catch (_: Exception) { return@lazy emptyList() }
        buildList {
            for (record in records) {
                when (record) {
                    is WasmRecursiveTypeRecord -> addAll(record.entries)
                    else -> add(record)
                }
            }
        }
    }

    /**
     * Exception tags resolved to display signatures, for the exception-flow
     * (handler-resolution) popup. Lazy; one pass over the type/import/tag
     * sections, no code decoding.
     */
    val exceptionTags: WasmExceptionTags? by lazy {
        try {
            wasmModule?.let { WasmExceptionTagsBuilder.build(bytes, it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Interprocedural exception propagation: per-function effective
     * uncaught-throw sets and the module's panic surface (exported entries
     * whose exceptions escape to the host). One opcode walk per function plus
     * a call-graph fixed point — heavier than [exceptionTags], so lazy and
     * built off the EDT by UI callers, like [callGraphResult].
     */
    val exceptionPropagation: ExceptionPropagation? by lazy {
        val module = wasmModule ?: return@lazy null
        try {
            val names = nameSection?.functionSubsection?.namemap ?: emptyMap()
            ExceptionPropagationBuilder.build(bytes, module, names)
        } catch (_: Exception) {
            null
        }
    }

    val importFuncCount: Int by lazy {
        try {
            val importRecords = wasmModule?.importSection?.let { section ->
                with(section) { bytes.getImportRecords() }
            } ?: emptyList()
            importRecords.count { it.type is org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * All parse problems found in this module: unparsed opcode tails, corrupt code entries,
     * type-section failures, section-header truncations, init-expression failures, etc.
     * Derived without extra parse passes where possible. Capped at 1000 entries.
     * This lazy never throws.
     */
    val parseProblems: List<ParseProblem> by lazy {
        try {
            buildParseProblems()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildParseProblems(): List<ParseProblem> {
        val problems = mutableListOf<ParseProblem>()
        val module = wasmModule

        // Section-header / custom-section-name truncation failures recorded during parse.
        for (failure in sectionScanFailures) {
            if (problems.size >= 1000) break
            problems.add(ParseProblem(
                kind = ParseProblemKind.SECTION_TRUNCATED,
                sectionId = 0,
                sectionName = "Section header",
                funcIdx = null,
                typeIdx = null,
                byteOffset = failure.byteOffset,
                byteSize = 1,
                message = failure.message,
            ))
        }

        // Code section: scan codeRecords for UnparsedOpCode occurrences.
        val codeSection = module?.codeSection
        if (codeSection != null) {
            val base = importFuncCount
            for ((codeIdx, record) in codeRecords.withIndex()) {
                if (problems.size >= 1000) break
                val funcIdx = base + codeIdx
                val unparsed = record.opCodes.filterIsInstance<UnparsedOpCode>()
                if (unparsed.isEmpty()) continue
                val hasPrefix = record.opCodes.size > 1
                for (u in unparsed) {
                    if (problems.size >= 1000) break
                    val kind = when {
                        record.locals.isEmpty() && !hasPrefix -> ParseProblemKind.CODE_ENTRY_CORRUPT
                        !hasPrefix -> ParseProblemKind.LOCALS_PARSE_FAILURE
                        else -> ParseProblemKind.UNKNOWN_OPCODE
                    }
                    problems.add(ParseProblem(
                        kind = kind,
                        sectionId = WasmSectionCode.SECTION_ID.toInt(),
                        sectionName = codeSection.name,
                        funcIdx = funcIdx,
                        typeIdx = null,
                        byteOffset = u.value.offset,
                        byteSize = u.value.size,
                        message = u.e.message ?: "unknown error",
                    ))
                }
            }
        }

        // Type section: check for partial parse failure.
        val typesSection = module?.typesSection
        if (typesSection != null && problems.size < 1000) {
            val (_, failedOffset) = with(typesSection) { bytes.parseTypesPartial() }
            if (failedOffset != null) {
                val failedSize = typesSection.sectionOffset + typesSection.sectionSize - failedOffset
                problems.add(ParseProblem(
                    kind = ParseProblemKind.UNKNOWN_TYPE_BYTE,
                    sectionId = WasmSectionTypes.SECTION_ID.toInt(),
                    sectionName = typesSection.name,
                    funcIdx = null,
                    typeIdx = null,
                    byteOffset = failedOffset,
                    byteSize = failedSize.coerceAtLeast(1),
                    message = "unknown type byte at offset $failedOffset",
                ))
            }
        }

        // Import section: check for partial parse failure.
        val importSection = module?.importSection
        if (importSection != null && problems.size < 1000) {
            val (_, failedOffset) = with(importSection) { bytes.getImportRecordsPartial() }
            if (failedOffset != null) {
                val failedSize = importSection.sectionOffset + importSection.sectionSize - failedOffset
                problems.add(ParseProblem(
                    kind = ParseProblemKind.IMPORT_ENTRY_CORRUPT,
                    sectionId = WasmSectionImport.SECTION_ID.toInt(),
                    sectionName = importSection.name,
                    funcIdx = null,
                    typeIdx = null,
                    byteOffset = failedOffset,
                    byteSize = failedSize.coerceAtLeast(1),
                    message = "import entry could not be decoded at offset $failedOffset",
                ))
            }
        }

        // Export section: check for partial parse failure.
        val exportSection = module?.exportSection
        if (exportSection != null && problems.size < 1000) {
            val (_, failedOffset) = with(exportSection) { bytes.scanPartial() }
            if (failedOffset != null) {
                val failedSize = exportSection.sectionOffset + exportSection.sectionSize - failedOffset
                problems.add(ParseProblem(
                    kind = ParseProblemKind.EXPORT_ENTRY_CORRUPT,
                    sectionId = WasmSectionExport.SECTION_ID.toInt(),
                    sectionName = exportSection.name,
                    funcIdx = null,
                    typeIdx = null,
                    byteOffset = failedOffset,
                    byteSize = failedSize.coerceAtLeast(1),
                    message = "export entry could not be decoded at offset $failedOffset",
                ))
            }
        }

        // Table section: check for partial parse failure.
        val tableSection = module?.tableSection
        if (tableSection != null && problems.size < 1000) {
            val (_, failedOffset) = with(tableSection) { bytes.scanTablePartial() }
            if (failedOffset != null) {
                val failedSize = tableSection.sectionOffset + tableSection.sectionSize - failedOffset
                problems.add(ParseProblem(
                    kind = ParseProblemKind.SECTION_ENTRY_CORRUPT,
                    sectionId = WasmSectionTable.SECTION_ID.toInt(),
                    sectionName = tableSection.name,
                    funcIdx = null,
                    typeIdx = null,
                    byteOffset = failedOffset,
                    byteSize = failedSize.coerceAtLeast(1),
                    message = "table entry could not be decoded at offset $failedOffset",
                ))
            }
        }

        // Memory section: check for partial parse failure.
        val memorySection = module?.memorySection
        if (memorySection != null && problems.size < 1000) {
            val (_, failedOffset) = with(memorySection) { bytes.scanPartial() }
            if (failedOffset != null) {
                val failedSize = memorySection.sectionOffset + memorySection.sectionSize - failedOffset
                problems.add(ParseProblem(
                    kind = ParseProblemKind.SECTION_ENTRY_CORRUPT,
                    sectionId = WasmSectionMemory.SECTION_ID.toInt(),
                    sectionName = memorySection.name,
                    funcIdx = null,
                    typeIdx = null,
                    byteOffset = failedOffset,
                    byteSize = failedSize.coerceAtLeast(1),
                    message = "memory entry could not be decoded at offset $failedOffset",
                ))
            }
        }

        // Function section: check for partial parse failure.
        val functionSection = module?.functionSection
        if (functionSection != null && problems.size < 1000) {
            val (_, failedOffset) = with(functionSection) { bytes.parseFunctionsPartial() }
            if (failedOffset != null) {
                val failedSize = functionSection.sectionOffset + functionSection.sectionSize - failedOffset
                problems.add(ParseProblem(
                    kind = ParseProblemKind.SECTION_ENTRY_CORRUPT,
                    sectionId = WasmSectionFunctions.SECTION_ID.toInt(),
                    sectionName = functionSection.name,
                    funcIdx = null,
                    typeIdx = null,
                    byteOffset = failedOffset,
                    byteSize = failedSize.coerceAtLeast(1),
                    message = "function entry could not be decoded at offset $failedOffset",
                ))
            }
        }

        // Exception (tag) section: check for partial parse failure.
        val exceptionSection = module?.exceptionSection
        if (exceptionSection != null && problems.size < 1000) {
            val (_, failedOffset) = with(exceptionSection) { bytes.scanTagsPartial() }
            if (failedOffset != null) {
                val failedSize = exceptionSection.sectionOffset + exceptionSection.sectionSize - failedOffset
                problems.add(ParseProblem(
                    kind = ParseProblemKind.SECTION_ENTRY_CORRUPT,
                    sectionId = WasmSectionException.SECTION_ID.toInt(),
                    sectionName = exceptionSection.name,
                    funcIdx = null,
                    typeIdx = null,
                    byteOffset = failedOffset,
                    byteSize = failedSize.coerceAtLeast(1),
                    message = "tag entry could not be decoded at offset $failedOffset",
                ))
            }
        }

        // Global section: detect init-expression parse failures.
        val globalSection = module?.globalSection
        if (globalSection != null && problems.size < 1000) {
            val (_, failedOffset) = with(globalSection) { bytes.scanPartial() }
            if (failedOffset != null) {
                val failedSize = globalSection.sectionOffset + globalSection.sectionSize - failedOffset
                problems.add(ParseProblem(
                    kind = ParseProblemKind.INIT_EXPR_PARSE_FAILURE,
                    sectionId = WasmSectionGlobal.SECTION_ID.toInt(),
                    sectionName = globalSection.name,
                    funcIdx = null,
                    typeIdx = null,
                    byteOffset = failedOffset,
                    byteSize = failedSize.coerceAtLeast(1),
                    message = "global section init-expr failure at offset $failedOffset",
                ))
            }
        }

        // Element section: detect init-expression parse failures.
        val elementSection = module?.elementSection
        if (elementSection != null && problems.size < 1000) {
            val (_, failedOffset) = with(elementSection) { bytes.scanElementsPartial() }
            if (failedOffset != null) {
                val failedSize = elementSection.sectionOffset + elementSection.sectionSize - failedOffset
                problems.add(ParseProblem(
                    kind = ParseProblemKind.INIT_EXPR_PARSE_FAILURE,
                    sectionId = WasmSectionElement.SECTION_ID.toInt(),
                    sectionName = elementSection.name,
                    funcIdx = null,
                    typeIdx = null,
                    byteOffset = failedOffset,
                    byteSize = failedSize.coerceAtLeast(1),
                    message = "element section init-expr failure at offset $failedOffset",
                ))
            }
        }

        // Data section: detect init-expression parse failures.
        val dataSection = module?.dataSection
        if (dataSection != null && problems.size < 1000) {
            val (_, failedOffset) = with(dataSection) { bytes.scanPartial() }
            if (failedOffset != null) {
                val failedSize = dataSection.sectionOffset + dataSection.sectionSize - failedOffset
                problems.add(ParseProblem(
                    kind = ParseProblemKind.INIT_EXPR_PARSE_FAILURE,
                    sectionId = WasmSectionData.SECTION_ID.toInt(),
                    sectionName = dataSection.name,
                    funcIdx = null,
                    typeIdx = null,
                    byteOffset = failedOffset,
                    byteSize = failedSize.coerceAtLeast(1),
                    message = "data section init-expr failure at offset $failedOffset",
                ))
            }
        }

        return problems.take(1000)
    }

    val monosAnalysis: MonosResult? by lazy {
        val names = nameSection?.functionSubsection?.namemap ?: return@lazy null
        try {
            val sizes = mutableMapOf<Int, Int>()
            codeOffsets.forEachIndexed { codeIdx, entry ->
                sizes[importFuncCount + codeIdx] = entry.size
            }
            MonosAnalyzer.analyze(names, sizes)
        } catch (_: Exception) {
            null
        }
    }

    val garbageAnalysis: GarbageResult? by lazy {
        val cg = callGraphResult ?: return@lazy null
        val dom = callGraphDominators ?: return@lazy null
        try {
            GarbageAnalyzer(cg, dom, codeOffsets, importFuncCount, bytes, wasmModule).analyze()
        } catch (_: Exception) {
            null
        }
    }

    val usedProposals: Set<WasmProposal> by lazy {
        val module = wasmModule ?: return@lazy emptySet()
        try {
            WasmProposalDetector.detect(module, bytes)
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Whether this module's import/export names appear minified (Binaryen
     * `--minify-imports-and-exports`). When true, cross-module name anchoring is
     * unreliable and a diff should treat function matches as structural.
     */
    val minification: MinificationResult by lazy {
        wasmModule ?: return@lazy MinificationResult.NONE
        try {
            val funcImports = imports.filter { it.type is WasmTypeIndexExternalType }
            WasmMinificationDetector.detect(
                importModules = funcImports.map { it.prefix },
                importFields = funcImports.map { it.name },
                exportNames = exports.filterIsInstance<WasmFunctionExportRecordEntry>().map { it.name },
            )
        } catch (_: Exception) {
            MinificationResult.NONE
        }
    }

    fun getCodeRecordByIndex(index: Int): WasmCodeRecord? {
        return parseCodeRecord(index)
    }

    fun getCodeRecordIndexAtByteOffset(byteOffset: Long): Int {
        return codeOffsets.indexOfFirst { entry ->
            entry.offset <= byteOffset && byteOffset < entry.offset + entry.size.toLong()
        }
    }

    fun getDataRecordIndexAtByteOffset(byteOffset: Long): Int {
        return data.indexOfFirst { record ->
            record.offset <= byteOffset && byteOffset < record.offset + record.recordLength.toLong()
        }
    }

    fun getRecords(section: WasmSection): List<WasmNamedRange> {
        return try {
            section.getRecordRanges(bytes)
        } catch (_: Throwable) {
            // we throw exception on unknown op-codes
            emptyList()
        }
    }

    fun readContent(section: WasmSection, size: Int): ByteArray {
        if (wasmModule == null) {
            return ByteArray(0)
        }
        return with (wasmModule) {
            bytes.readContent(section, size)
        }
    }
}

/**
 * Maps a Wasm section name to the corresponding bottom-panel tab description,
 * or `null` when no direct tab correspondence exists.
 */
private fun wasmSectionTabName(sectionName: String): String? = when (sectionName) {
    "Code", "Functions" -> "Functions"
    "Data" -> "Data"
    "Exports" -> "Exports"
    "Imports" -> "Imports"
    else -> if (sectionName.startsWith(".debug_")) sectionName else null
}


interface HexanaSourceLocationProvider {

    companion object {
        val EMPTY = object : HexanaSourceLocationProvider {
            override fun getSourceLocationForOffset(byteOffset: Int): HexanaSourceLocation? = null
        }
    }

    fun getSourceLocationForOffset(byteOffset: Int): HexanaSourceLocation?

    /**
     * First mapped location in `[startOffset, endOffset)` (module-absolute),
     * for function-level lookups: a function body's first bytes (size field,
     * locals) usually precede its first line-program row, so a point query at
     * the body start misses. Default: no result.
     */
    fun getFirstSourceLocationInRange(startOffset: Int, endOffset: Int): HexanaSourceLocation? = null
}

/**
 * A resolved source position. [line] and [column] are 1-BASED across all
 * backends: DWARF line programs are natively 1-based, and the source-map
 * backend shifts its 0-based VLQ fields on construction. Consumers
 * navigating via editor APIs that count from 0 (OpenFileDescriptor)
 * subtract 1.
 */
data class HexanaSourceLocation(
    val filePath: String,
    val line: Int,
    val column: Int,
)
