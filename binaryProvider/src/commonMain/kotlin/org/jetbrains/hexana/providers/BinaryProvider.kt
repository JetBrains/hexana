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
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.analysis.GraalJavaSymbol
import org.jetbrains.hexana.wasm.analysis.GraalSymbolDemangler
import org.jetbrains.hexana.wasm.analysis.GraalWebImageDetector
import org.jetbrains.hexana.wasm.analysis.GraalWebImageInfo
import org.jetbrains.hexana.wasm.analysis.MonosAnalyzer
import org.jetbrains.hexana.wasm.analysis.MonosResult
import org.jetbrains.hexana.wasm.analysis.WasmProposal
import org.jetbrains.hexana.wasm.analysis.WasmProposalDetector
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageAnalyzer
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageResult
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.analysis.SizeAnalysisBuilder
import org.jetbrains.hexana.wasm.analysis.SizeNode
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphBuilder
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphResult
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

class GenericBinaryProvider(private val sharedBytes: CommonByteBuffer) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    val nativeFormat: NativeBinaryFormat? by lazy {
        NativeBinaryFormatDetector.detect(bytes)
    }
}

sealed class NativeBinaryProvider(
    private val sharedBytes: CommonByteBuffer,
    val sourcePath: String? = null,
) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()
    abstract val nativeFormat: NativeBinaryFormat

    val strings: List<NativeStringRecord> by lazy {
        NativeStringScanner.scanAscii(bytes)
    }

    open val nativeFunctions: List<NativeFunctionRecord>? get() = null
    open val nativeSourceLocationProvider: NativeDwarfSourceLocationProvider? get() = null
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
}

class CoffBinaryProvider(
    sharedBytes: CommonByteBuffer,
    override val nativeFormat: NativeBinaryFormat.Coff,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {

    val coffHeader: PeCoffHeader? by lazy { PeParser.parseCoffObjectHeader(bytes) }

    val coffSymbols: List<PeCoffSymbol>? by lazy {
        val h = coffHeader ?: return@lazy null
        PeParser.parseCoffSymbols(bytes, h)
    }
}

class ArchiveBinaryProvider(
    sharedBytes: CommonByteBuffer,
    sourcePath: String? = null,
) : NativeBinaryProvider(sharedBytes, sourcePath) {
    override val nativeFormat: NativeBinaryFormat get() = NativeBinaryFormat.Archive
}

fun nativeOrGenericBinaryProvider(
    sharedBytes: CommonByteBuffer,
    sourcePath: String? = null,
): BinaryProvider = when (val fmt = NativeBinaryFormatDetector.detect(sharedBytes)) {
    is NativeBinaryFormat.Elf -> ElfBinaryProvider(sharedBytes, fmt, sourcePath)
    is NativeBinaryFormat.MachO -> MachOBinaryProvider(sharedBytes, fmt, sourcePath)
    is NativeBinaryFormat.FatMachO -> FatMachOBinaryProvider(sharedBytes, fmt, sourcePath)
    is NativeBinaryFormat.Pe -> PeBinaryProvider(sharedBytes, fmt, sourcePath)
    is NativeBinaryFormat.Coff -> CoffBinaryProvider(sharedBytes, fmt, sourcePath)
    NativeBinaryFormat.Archive -> ArchiveBinaryProvider(sharedBytes, sourcePath)
    null -> GenericBinaryProvider(sharedBytes)
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

    open val sourceLocationProvider: HexanaSourceLocationProvider
        get() = wasmModule?.let { DwarfSourceLocationProvider(bytes, WasmDwarfAccessor(it)) }
            ?: HexanaSourceLocationProvider.EMPTY

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

    val sizeAnalysisBuilder: SizeAnalysisBuilder? by lazy {
        parsedModule?.let { SizeAnalysisBuilder(bytes, it) }
    }

    val callGraphResult: CallGraphResult? by lazy {
        wasmModule?.let { CallGraphBuilder(bytes, it).build() }
    }

    val callGraphDominators: DominatorResult? by lazy {
        callGraphResult?.let { DominatorTree.compute(it.graph) }
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


interface HexanaSourceLocationProvider {

    companion object {
        val EMPTY = object : HexanaSourceLocationProvider {
            override fun getSourceLocationForOffset(byteOffset: Int): HexanaSourceLocation? = null
        }
    }

    fun getSourceLocationForOffset(byteOffset: Int): HexanaSourceLocation?
}

data class HexanaSourceLocation(
    val filePath: String,
    val line: Int,
    val column: Int,
)
