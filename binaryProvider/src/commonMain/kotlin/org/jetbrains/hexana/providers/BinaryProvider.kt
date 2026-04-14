package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.analysis.MonosAnalyzer
import org.jetbrains.hexana.wasm.analysis.MonosResult
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageAnalyzer
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageResult
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
    val bytes: CommonByteBuffer
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

class GenericBinaryProvider(override val bytes: CommonByteBuffer) : BinaryProvider
open class WasmComponentBinaryProvider(override val bytes: CommonByteBuffer): BinaryProvider {
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

    val sizeAnalysis: SizeNode.Module? by lazy {
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
        val component = with(componentSection) { bytes.parseNestedComponent() } ?: return@lazy emptyList()
        bytes.position(componentSection.contentOffset)
        val nestedBytes = ByteArray(componentSection.contentSize)
        bytes.get(nestedBytes, 0, componentSection.contentSize)
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

internal fun buildComponentTypeIndex(sections: List<WasmSection>, buffer: CommonByteBuffer): List<ComponentDefType> {
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

open class WasmBinaryProvider(override val bytes: CommonByteBuffer) : BinaryProvider {
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

    val sizeAnalysis: SizeNode.Module? by lazy {
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
        GarbageAnalyzer(cg, dom, codeOffsets, importFuncCount).analyze()
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
