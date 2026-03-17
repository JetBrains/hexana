package org.jetbrains.hexana.wasm.components

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.FunctionRecord
import org.jetbrains.hexana.wasm.coreResolver
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionCode
import org.jetbrains.hexana.wasm.sections.WasmSectionCustom
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
import org.jetbrains.hexana.wasm.sections.WasmSectionTypes
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved

interface WasmComponentBase<S: WasmSection, T: WasmSection> {
    val layer: UShort
    val sections: List<WasmSection>
    val customSections: List<WasmSectionCustom>
    val typesSection: T?
    val startSection: S?

    val unresolvedSections: List<WasmSection>



    fun CommonByteBuffer.readContent(section: WasmSection, size: Int): ByteArray {
        val sz = size.coerceAtMost(section.contentSize)
        val bytes = ByteArray(sz)
        position(section.contentOffset)
        get(bytes, 0, sz)
        return bytes
    }

    fun getCustomSectionByName(name: String): WasmSectionCustom? {
        return customSections.find { it.name == name }
    }

    fun getCustomSectionsByName(name: String): List<WasmSectionCustom> {
        return customSections.filter { it.name == name }
    }
}

interface WasmComponent: WasmComponentBase<WasmComponentStartSection, WasmComponentTypesSection> {
    val importSection: List<WasmComponentImportSection>
    val exportSection: List<WasmComponentExportSection>
    val moduleSection: List<WasmComponentModuleSection>
    val coreInstanceSection: List<WasmCoreInstanceSection>
    val coreTypesSection: List<WasmComponentCoreTypeSection>
    val componentSection: WasmComponentSection?
    val instanceSection: List<WasmInstanceSection>
    val aliasSection: List<WasmAliasSection>
    val canonSection: List<WasmCanonSection>
    val valueSection: List<WasmValueSection>

}

interface CoreWasmComponent: WasmComponentBase<WasmSectionStart, WasmSectionTypes> {
    override val layer: UShort
        get() = 0U
    val importSection: WasmSectionImport?
    val exportSection: WasmSectionExport?
    val functionSection: WasmSectionFunctions?
    val tableSection: WasmSection?
    val memorySection: WasmSectionMemory?
    val globalSection: WasmSectionGlobal?
    val elementSection: WasmSectionElement?
    val codeSection: WasmSectionCode?
    val dataSection: WasmSectionData?
    val dataCountSection: WasmSectionDataCount?
    val exceptionSection: WasmSectionException?

    fun CommonByteBuffer.scanFunctions(): List<FunctionRecord> {
        requireNotNull(typesSection)
        requireNotNull(functionSection)
        requireNotNull(codeSection)
        val types = with (typesSection!!) {
            parseTypes()
        }

        val functions = with(functionSection!!) {
            parseFunctions()
        }

        val bodies = with(codeSection!!) {
            scan()
        }

        return functions.map { types[it] }.zip(bodies).map { (signature, body) ->
            FunctionRecord(signature, body)
        }
    }
}

fun CommonByteBuffer.componentResolver(unresolved: WasmSectionUnresolved): WasmSection {
    return when (unresolved.id) {
        WasmSectionCustom.SECTION_ID -> { coreResolver(unresolved) }
        WasmComponentModuleSection.SECTION_ID -> WasmComponentModuleSection(unresolved)
        WasmCoreInstanceSection.SECTION_ID -> WasmCoreInstanceSection(unresolved)
        WasmComponentCoreTypeSection.SECTION_ID -> WasmComponentCoreTypeSection(unresolved)
        WasmComponentSection.SECTION_ID -> WasmComponentSection(unresolved)
        WasmInstanceSection.SECTION_ID -> WasmInstanceSection(unresolved)
        WasmAliasSection.SECTION_ID -> WasmAliasSection(unresolved)
        WasmComponentTypesSection.SECTION_ID -> WasmComponentTypesSection(unresolved)
        WasmCanonSection.SECTION_ID -> WasmCanonSection(unresolved)
        WasmComponentStartSection.SECTION_ID -> WasmComponentStartSection(unresolved)
        WasmComponentImportSection.SECTION_ID -> WasmComponentImportSection(unresolved)
        WasmComponentExportSection.SECTION_ID -> WasmComponentExportSection(unresolved)
        WasmValueSection.SECTION_ID -> WasmValueSection(unresolved)
        else -> unresolved
    }
}