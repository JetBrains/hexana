package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryFormatDetector
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
import org.jetbrains.hexana.zip.ZipEntry
import org.jetbrains.hexana.zip.ZipParser
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmNamedRange
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
}

class ElfBinaryProvider(
    sharedBytes: CommonByteBuffer,
    override val nativeFormat: NativeBinaryFormat.Elf,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {

    val header: ElfHeader? by lazy { ElfParser.parseHeader(bytes) }

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
        NativeFunctionDiscovery.fromElf(sym, sec, ph)
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
}

class FatMachOBinaryProvider(
    sharedBytes: CommonByteBuffer,
    override val nativeFormat: NativeBinaryFormat.FatMachO,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {

    val fatHeader: MachOFatHeader? by lazy { MachOParser.parseFatHeader(bytes) }

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

    override val nativeImageInfo: NativeImageInfo? by lazy {
        val sec = sections ?: return@lazy null
        NativeImageDetector.fromPe(strings, sec, exports, bytes).takeIf { it !== NativeImageInfo.NONE }
    }

    override val sizeAnalysis: BinarySizeNode? by lazy {
        val secs = sections?.takeIf { it.isNotEmpty() } ?: return@lazy null
        val totalSize = bytes.size
        val children = secs.map { sec ->
            BinarySizeNode(
                name = sec.name.ifBlank { "<unnamed>" },
                size = sec.rawDataSize.toInt(),
                offset = sec.rawDataPointer.toInt(),
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

class CoffBinaryProvider(
    sharedBytes: CommonByteBuffer,
    override val nativeFormat: NativeBinaryFormat.Coff,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {

    val coffHeader: PeCoffHeader? by lazy { PeParser.parseCoffObjectHeader(bytes) }

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
        ClassFileBinaryProvider.isClassFile(sharedBytes) -> ClassFileBinaryProvider(sharedBytes.duplicate().position(0))
        DexBinaryProvider.isDexFile(sharedBytes) -> DexBinaryProvider(sharedBytes.duplicate().position(0))
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

    val wasmModule = when (val parseResult = WasmParser().parse(bytes)) {
        is WasmParseSuccess -> parseResult.wasmModule as? WasmComponent
        else -> null
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
}

class NestedComponentData(val component: WasmComponent, val buffer: CommonByteBuffer)

fun buildComponentTypeIndex(sections: List<WasmSection>, buffer: CommonByteBuffer): List<ComponentDefType> {
    val types = mutableListOf<ComponentDefType>()
    for (section in sections) {
        when (section) {
            is WasmComponentTypesSection -> {
                val sectionTypes = with(section) { buffer.scanTypes() }
                types.addAll(sectionTypes)
            }
            is WasmAliasSection -> {
                val aliases = with(section) { buffer.scanAliases() }
                for (alias in aliases) {
                    if (alias is ComponentAlias && alias.componentSort is ComponentSort.Type) {
                        types.add(ComponentAliasedType(alias))
                    }
                }
            }
            is WasmComponentImportSection -> {
                val imports = with(section) { buffer.scan() }
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

open class WasmBinaryProvider(private val sharedBytes: CommonByteBuffer) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    private val parseResult = WasmParser().parse(bytes)

    val parsedModule = when (parseResult) {
        is WasmParseSuccess -> parseResult.wasmModule
        else -> null
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
        with (importSection) {
            bytes.getImportRecords()
        }
    }

    val exports: List<WasmExportRecordEntry> by lazy {
        val exportsSection = wasmModule?.exportSection ?: return@lazy emptyList()
        with (exportsSection) {
            bytes.scan()
        }
    }

    val data: List<WasmDataRecord> by lazy {
        val dataSection = wasmModule?.dataSection ?: return@lazy emptyList()
        with (dataSection) {
            bytes.scan()
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
        GraalWebImageDetector.detect(module, bytes)
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
        KotlinWasmDetector.detect(module, bytes)
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

        val funs = with(functionSection) {
             bytes.parseFunctions()
        }

        val types = with(typesSection) {
            bytes.parseTypes()
        }

        // TODO: Investigate when this can be a case and how this is better to be approached
        val signatures = funs.mapNotNull { types.getOrNull(it) }

        signatures
    }

    override val sizeAnalysis: SizeNode.Module? by lazy {
        parsedModule?.let { SizeAnalysisBuilder(bytes, it).buildTree() }
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
        wasmModule?.let { CallGraphBuilder(bytes, it).build() }
    }

    val callGraphDominators: DominatorResult? by lazy {
        callGraphResult?.let { DominatorTree.compute(it.graph) }
    }

    /**
     * Statically known `call_indirect` candidates grouped by canonical
     * signature. Lazy and cheap (one pass over type/import/function/element
     * sections, no code decoding) but still built off the EDT by UI callers.
     */
    val dispatchCandidates: DispatchCandidates? by lazy {
        wasmModule?.let { DispatchCandidatesBuilder(bytes, it).build() }
    }

    /**
     * Exception tags resolved to display signatures, for the exception-flow
     * (handler-resolution) popup. Lazy; one pass over the type/import/tag
     * sections, no code decoding.
     */
    val exceptionTags: WasmExceptionTags? by lazy {
        wasmModule?.let { WasmExceptionTagsBuilder.build(bytes, it) }
    }

    val importFuncCount: Int by lazy {
        val importRecords = wasmModule?.importSection?.let { section ->
            with(section) { bytes.getImportRecords() }
        } ?: emptyList()
        importRecords.count { it.type is org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType }
    }

    val monosAnalysis: MonosResult? by lazy {
        val names = nameSection?.functionSubsection?.namemap ?: return@lazy null
        val sizes = mutableMapOf<Int, Int>()
        codeOffsets.forEachIndexed { codeIdx, entry ->
            sizes[importFuncCount + codeIdx] = entry.size
        }
        MonosAnalyzer.analyze(names, sizes)
    }

    val garbageAnalysis: GarbageResult? by lazy {
        val cg = callGraphResult ?: return@lazy null
        val dom = callGraphDominators ?: return@lazy null
        GarbageAnalyzer(cg, dom, codeOffsets, importFuncCount, bytes, wasmModule).analyze()
    }

    val usedProposals: Set<WasmProposal> by lazy {
        val module = wasmModule ?: return@lazy emptySet()
        WasmProposalDetector.detect(module, bytes)
    }

    /**
     * Whether this module's import/export names appear minified (Binaryen
     * `--minify-imports-and-exports`). When true, cross-module name anchoring is
     * unreliable and a diff should treat function matches as structural.
     */
    val minification: MinificationResult by lazy {
        wasmModule ?: return@lazy MinificationResult.NONE
        val funcImports = imports.filter { it.type is WasmTypeIndexExternalType }
        WasmMinificationDetector.detect(
            importModules = funcImports.map { it.prefix },
            importFields = funcImports.map { it.name },
            exportNames = exports.filterIsInstance<WasmFunctionExportRecordEntry>().map { it.name },
        )
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
