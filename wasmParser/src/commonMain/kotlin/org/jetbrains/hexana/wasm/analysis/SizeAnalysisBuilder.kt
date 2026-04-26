package org.jetbrains.hexana.wasm.analysis

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.components.CoreWasmComponent
import org.jetbrains.hexana.wasm.components.WasmComponent
import org.jetbrains.hexana.wasm.components.WasmComponentBase
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
import org.jetbrains.hexana.wasm.sections.WasmDataRecord
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionCode
import org.jetbrains.hexana.wasm.sections.WasmSectionData
import org.jetbrains.hexana.wasm.sections.WasmSectionDataCount
import org.jetbrains.hexana.wasm.sections.WasmSectionElement
import org.jetbrains.hexana.wasm.sections.WasmSectionException
import org.jetbrains.hexana.wasm.sections.WasmSectionExport
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctions
import org.jetbrains.hexana.wasm.sections.WasmSectionGlobal
import org.jetbrains.hexana.wasm.sections.WasmSectionImport
import org.jetbrains.hexana.wasm.sections.WasmSectionMemory
import org.jetbrains.hexana.wasm.sections.WasmSectionStart
import org.jetbrains.hexana.wasm.sections.WasmSectionTable
import org.jetbrains.hexana.wasm.sections.WasmSectionTypes

/**
 * Builds a [SizeNode] tree representing the size breakdown of a WASM module.
 *
 * The tree is built eagerly at the section/record level. Per-function and per-data-segment
 * detail (opcode classification, data content classification) can be computed on demand
 * via [buildFunctionDetail] and [buildDataSegmentDetail].
 */
class SizeAnalysisBuilder(
    private val buffer: CommonByteBuffer,
    private val module: WasmComponentBase<*, *>
) {

    /**
     * Builds the top-level module size tree.
     *
     * Structure: Module -> Sections -> Items (functions, data segments, import/export records).
     */
    fun buildTree(): SizeNode.Module {
        val totalSize = buffer.limit()
        val children = mutableListOf<SizeNode>()

        when (module) {
            is CoreWasmComponent -> buildCoreModuleSections(module, children)
            is WasmComponent -> buildComponentSections(module, children)
        }

        module.customSections.forEach { section ->
            children.add(SizeNode.CustomSection("Custom: ${section.rawName}", section.sectionSize, section.sectionOffset))
        }

        module.unresolvedSections.forEach { section ->
            children.add(SizeNode.Generic("Unresolved (${section.name})", section.sectionSize, section.sectionOffset))
        }

        return SizeNode.Module("module", totalSize, 0, children)
    }

    /**
     * Builds deferred per-function detail: locals vs body split, opcode category breakdown, call targets.
     */
    fun buildFunctionDetail(codeRecords: List<WasmCodeRecord>, funcIndex: Int): List<SizeNode> {
        if (funcIndex !in codeRecords.indices) return emptyList()
        val record = codeRecords[funcIndex]
        return buildFunctionDetailFromRecord(record)
    }

    fun buildFunctionDetail(record: WasmCodeRecord): List<SizeNode> {
        return buildFunctionDetailFromRecord(record)
    }

    /**
     * Builds deferred per-data-segment detail: classifies data bytes as ZeroFilled, StringLiteral, or Binary.
     */
    fun buildDataSegmentDetail(dataRecords: List<WasmDataRecord>, segIndex: Int): List<SizeNode> {
        if (segIndex !in dataRecords.indices) return emptyList()
        val record = dataRecords[segIndex]
        return classifyDataContent(record)
    }

    private fun buildCoreModuleSections(core: CoreWasmComponent, children: MutableList<SizeNode>) {
        core.typesSection?.let { section ->
            children.add(buildSimpleSection(section, WasmSectionTypes.SECTION_ID.toInt()))
        }
        core.importSection?.let { section ->
            children.add(buildImportSection(section))
        }
        core.functionSection?.let { section ->
            children.add(buildSimpleSection(section, WasmSectionFunctions.SECTION_ID.toInt()))
        }
        core.tableSection?.let { section ->
            children.add(buildSimpleSection(section, WasmSectionTable.SECTION_ID.toInt()))
        }
        core.memorySection?.let { section ->
            children.add(buildSimpleSection(section, WasmSectionMemory.SECTION_ID.toInt()))
        }
        core.globalSection?.let { section ->
            children.add(buildSimpleSection(section, WasmSectionGlobal.SECTION_ID.toInt()))
        }
        core.exportSection?.let { section ->
            children.add(buildExportSection(section))
        }
        core.startSection?.let { section ->
            children.add(buildSimpleSection(section, WasmSectionStart.SECTION_ID.toInt()))
        }
        core.elementSection?.let { section ->
            children.add(buildSimpleSection(section, WasmSectionElement.SECTION_ID.toInt()))
        }
        core.codeSection?.let { section ->
            children.add(buildCodeSection(section))
        }
        core.dataSection?.let { section ->
            children.add(buildDataSection(section))
        }
        core.dataCountSection?.let { section ->
            children.add(buildSimpleSection(section, WasmSectionDataCount.SECTION_ID.toInt()))
        }
        core.exceptionSection?.let { section ->
            children.add(buildSimpleSection(section, WasmSectionException.SECTION_ID.toInt()))
        }
    }

    private fun buildComponentSections(component: WasmComponent, children: MutableList<SizeNode>) {
        component.typesSection?.let { section ->
            children.add(SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, null, emptyList()))
        }

        component.importSection.forEach { section ->
            children.add(SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, null, emptyList()))
        }

        component.exportSection.forEach { section ->
            children.add(SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, null, emptyList()))
        }

        component.moduleSection.forEach { section ->
            children.add(
                SizeNode.EmbeddedModule(section.name, section.sectionSize, section.sectionOffset, emptyList())
            )
        }

        component.componentSection?.let { section ->
            children.add(
                SizeNode.EmbeddedComponent(section.name, section.sectionSize, section.sectionOffset, emptyList())
            )
        }

        component.coreInstanceSection.forEach { section ->
            children.add(SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, null, emptyList()))
        }

        component.coreTypesSection.forEach { section ->
            children.add(SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, null, emptyList()))
        }

        component.instanceSection.forEach { section ->
            children.add(SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, null, emptyList()))
        }

        component.aliasSection.forEach { section ->
            children.add(SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, null, emptyList()))
        }

        component.canonSection.forEach { section ->
            children.add(SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, null, emptyList()))
        }

        component.startSection?.let { section ->
            children.add(SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, null, emptyList()))
        }

        component.valueSection.forEach { section ->
            children.add(SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, null, emptyList()))
        }
    }

    private fun buildSimpleSection(section: WasmSection, sectionId: Int): SizeNode.Section {
        return SizeNode.Section(section.name, section.sectionSize, section.sectionOffset, sectionId, emptyList())
    }

    private fun buildCodeSection(section: WasmSectionCode): SizeNode.Section {
        val records = with(section) { buffer.scan() }
        val funcChildren = records.mapIndexed { index, record ->
            SizeNode.Function("func $index", record.size, record.offset, index)
        }
        return SizeNode.Section(
            section.name, section.sectionSize, section.sectionOffset,
            WasmSectionCode.SECTION_ID.toInt(), funcChildren
        )
    }

    private fun buildDataSection(section: WasmSectionData): SizeNode.Section {
        val records = with(section) { buffer.scan() }
        val segChildren = records.mapIndexed { index, record ->
            SizeNode.DataSegment("data $index", record.size, record.offset, index)
        }
        return SizeNode.Section(
            section.name, section.sectionSize, section.sectionOffset,
            WasmSectionData.SECTION_ID.toInt(), segChildren
        )
    }

    private fun buildImportSection(section: WasmSectionImport): SizeNode.Section {
        val records = with(section) { buffer.getImportRecords() }
        val importChildren = records.mapIndexed { index, record ->
            val label = if (record.name.isBlank()) "import $index" else "import $index: ${record.prefix}.${record.name}"
            SizeNode.Generic(label, record.size, record.offset)
        }
        return SizeNode.Section(
            section.name, section.sectionSize, section.sectionOffset,
            WasmSectionImport.SECTION_ID.toInt(), importChildren
        )
    }

    private fun buildExportSection(section: WasmSectionExport): SizeNode.Section {
        val records = with(section) { buffer.scan() }
        val exportChildren = records.mapIndexed { index, record ->
            val label = if (record.name.isBlank()) "export $index" else "export $index: ${record.name}"
            SizeNode.Generic(label, record.size, record.offset)
        }
        return SizeNode.Section(
            section.name, section.sectionSize, section.sectionOffset,
            WasmSectionExport.SECTION_ID.toInt(), exportChildren
        )
    }

    private fun buildFunctionDetailFromRecord(record: WasmCodeRecord): List<SizeNode> {
        val result = mutableListOf<SizeNode>()

        // Split locals vs body.
        // The code record starts with a LEB128 size prefix, then locals declarations, then body opcodes.
        // We approximate locals size vs body size from the opcode offsets.
        val opcodes = record.opCodes
        if (opcodes.isEmpty()) {
            result.add(SizeNode.FunctionPart("body", record.size, record.offset, PartKind.Body))
            return result
        }

        // Classify opcodes by category and accumulate sizes by category.
        val categorySizes = mutableMapOf<OpcodeCategory, Int>()
        val callTargets = mutableListOf<SizeNode.CallTarget>()

        for (opcode in opcodes) {
            val category = classifyOpcode(opcode)
            // Each opcode contributes at least 1 byte; we don't have exact per-opcode sizes,
            // so we count opcodes per category as a proxy.
            categorySizes[category] = (categorySizes[category] ?: 0) + 1

            // Extract call targets
            when (opcode) {
                is OpCodes.Flow.Call -> {
                    callTargets.add(SizeNode.CallTarget("call ${opcode.value}", record.offset, opcode.value.toInt()))
                }
                is OpCodes.Flow.CallIndirect -> {
                    callTargets.add(SizeNode.CallTarget("call_indirect ${opcode.value0}", record.offset, opcode.value0))
                }
                else -> { /* not a direct call */ }
            }
        }

        for ((category, count) in categorySizes) {
            result.add(SizeNode.InstructionCategory(category.name, count, record.offset, category))
        }

        result.addAll(callTargets)

        return result
    }

    private fun classifyDataContent(record: WasmDataRecord): List<SizeNode> {
        val data = record.data
        if (data.isEmpty()) return emptyList()

        val result = mutableListOf<SizeNode>()
        var i = 0

        while (i < data.size) {
            val start = i
            when {
                // Zero-filled region
                data[i] == 0.toByte() && i + 1 < data.size && data[i + 1] == 0.toByte() -> {
                    while (i < data.size && data[i] == 0.toByte()) i++
                    val size = i - start
                    result.add(
                        SizeNode.DataContent("zeros ($size bytes)", size, record.offset + start, ContentKind.ZeroFilled)
                    )
                }
                // String literal region (printable ASCII range)
                isPrintableAscii(data[i]) -> {
                    while (i < data.size && isPrintableAscii(data[i])) i++
                    val size = i - start
                    if (size >= 4) {
                        result.add(
                            SizeNode.DataContent("string ($size bytes)", size, record.offset + start, ContentKind.StringLiteral)
                        )
                    } else {
                        result.add(
                            SizeNode.DataContent("binary ($size bytes)", size, record.offset + start, ContentKind.Binary)
                        )
                    }
                }
                // Binary data
                else -> {
                    while (i < data.size && !isPrintableAscii(data[i]) &&
                        !(data[i] == 0.toByte() && i + 1 < data.size && data[i + 1] == 0.toByte())
                    ) {
                        i++
                    }
                    val size = i - start
                    result.add(
                        SizeNode.DataContent("binary ($size bytes)", size, record.offset + start, ContentKind.Binary)
                    )
                }
            }
        }

        return result
    }

    private fun isPrintableAscii(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v in 0x20..0x7E || v == 0x0A || v == 0x0D || v == 0x09
    }
}