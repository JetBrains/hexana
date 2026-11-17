package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.wasm.components.CoreWasmComponent
import org.jetbrains.hexana.wasm.components.WasmAliasSection
import org.jetbrains.hexana.wasm.components.WasmCanonSection
import org.jetbrains.hexana.wasm.components.WasmComponent
import org.jetbrains.hexana.wasm.components.WasmComponentBase
import org.jetbrains.hexana.wasm.components.WasmComponentCoreTypeSection
import org.jetbrains.hexana.wasm.components.WasmComponentExportSection
import org.jetbrains.hexana.wasm.components.WasmComponentImportSection
import org.jetbrains.hexana.wasm.components.WasmComponentModuleSection
import org.jetbrains.hexana.wasm.components.WasmComponentSection
import org.jetbrains.hexana.wasm.components.WasmComponentStartSection
import org.jetbrains.hexana.wasm.components.WasmComponentTypesSection
import org.jetbrains.hexana.wasm.components.WasmCoreInstanceSection
import org.jetbrains.hexana.wasm.components.WasmInstanceSection
import org.jetbrains.hexana.wasm.components.WasmValueSection
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
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
import org.jetbrains.hexana.wasm.sections.WasmSectionTable
import org.jetbrains.hexana.wasm.sections.WasmSectionTypes
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord

open class BaseModule(
    val sections: List<WasmSection>,
    val customSections: List<WasmSectionCustom> = emptyList(),
    val unresolvedSections: List<WasmSection>
)

class WasmModule(
    sections: List<WasmSection>,
    customSections: List<WasmSectionCustom> = emptyList(),
    override val typesSection: WasmSectionTypes? = null,
    override val importSection: WasmSectionImport? = null,
    override val functionSection: WasmSectionFunctions? = null,
    override val tableSection: WasmSectionTable? = null,
    override val memorySection: WasmSectionMemory? = null,
    override val globalSection: WasmSectionGlobal? = null,
    override val exportSection: WasmSectionExport? = null,
    override val startSection: WasmSectionStart? = null,
    override val elementSection: WasmSectionElement? = null,
    override val codeSection: WasmSectionCode? = null,
    override val dataSection: WasmSectionData? = null,
    override val dataCountSection: WasmSectionDataCount? = null,
    override val exceptionSection: WasmSectionException? = null,
    unresolvedSections: List<WasmSection>
) : BaseModule(sections, customSections, unresolvedSections), CoreWasmComponent

class ComponentModule(
    override val layer: UShort,
    sections: List<WasmSection>,
    customSections: List<WasmSectionCustom> = emptyList(),
    override val importSection: List<WasmComponentImportSection>,
    override val exportSection: List<WasmComponentExportSection>,
    override val coreTypesSection: List<WasmComponentCoreTypeSection>,
    override val startSection: WasmComponentStartSection?,
    override val typesSection: WasmComponentTypesSection?,
    override val moduleSection: List<WasmComponentModuleSection>,
    override val coreInstanceSection: List<WasmCoreInstanceSection>,
    override val componentSection: WasmComponentSection?,
    override val instanceSection: List<WasmInstanceSection>,
    override val aliasSection: List<WasmAliasSection>,
    override val canonSection: List<WasmCanonSection>,
    override val valueSection: List<WasmValueSection>,
    unresolvedSections: List<WasmSection>,
) : BaseModule(sections, customSections, unresolvedSections), WasmComponent {

}


fun fromSections(version: UShort, layer: UShort, sections: List<WasmSection>): WasmComponentBase<*, *>? {
    return when {
        isCoreVersion(version, layer) -> coreWasmModuleFromSections(sections)
        isComponentsVersion(version, layer) -> componentModuleFromSections(version, layer, sections)
        else -> null
    }
}

private fun componentModuleFromSections(version: UShort, layer: UShort, sections: List<WasmSection>): ComponentModule {
    val customSections = mutableListOf<WasmSectionCustom>()
    val importSection = mutableListOf<WasmComponentImportSection>()
    val exportSection = mutableListOf<WasmComponentExportSection>()
    var startSection: WasmComponentStartSection? = null
    val moduleSection = mutableListOf<WasmComponentModuleSection>()
    val coreInstanceSection = mutableListOf<WasmCoreInstanceSection>()
    var componentTypesSection: WasmComponentTypesSection? = null
    val coreTypesSection = mutableListOf<WasmComponentCoreTypeSection>()
    var componentSection: WasmComponentSection? = null
    val instanceSection = mutableListOf<WasmInstanceSection>()
    val aliasSection = mutableListOf<WasmAliasSection>()
    val cannonSection = mutableListOf<WasmCanonSection>()
    val valueSection = mutableListOf<WasmValueSection>()
    val unresolvedSections = mutableListOf<WasmSection>()

    sections.forEach { section ->
        when (section) {
            is WasmSectionCustom -> customSections.add(section)
            is WasmComponentImportSection -> importSection.add(section)
            is WasmComponentExportSection -> exportSection.add(section)
            is WasmComponentStartSection -> startSection = section
            is WasmComponentModuleSection -> moduleSection.add(section)
            is WasmCoreInstanceSection -> coreInstanceSection.add(section)
            is WasmComponentTypesSection -> componentTypesSection = section
            is WasmComponentCoreTypeSection-> coreTypesSection.add(section)
            is WasmComponentSection -> componentSection = section
            is WasmInstanceSection -> instanceSection.add(section)
            is WasmAliasSection -> aliasSection.add(section)
            is WasmCanonSection -> cannonSection.add(section)
            else -> {
                unresolvedSections.add(section)
            }
        }
    }
    return ComponentModule(
        layer,
        sections,
        customSections,
        importSection,
        exportSection,
        coreTypesSection,
        startSection,
        componentTypesSection,
        moduleSection,
        coreInstanceSection,
        componentSection,
        instanceSection,
        aliasSection,
        cannonSection,
        valueSection,
        unresolvedSections
    )
}


private fun coreWasmModuleFromSections(sections: List<WasmSection>): WasmModule {
    val customSections = mutableListOf<WasmSectionCustom>()
    var typesSection: WasmSectionTypes? = null
    var importSection: WasmSectionImport? = null
    var functionSection: WasmSectionFunctions? = null
    var tableSection: WasmSectionTable? = null
    var memorySection: WasmSectionMemory? = null
    var globalSection: WasmSectionGlobal? = null
    var exportSection: WasmSectionExport? = null
    var startSection: WasmSectionStart? = null
    var elementSection: WasmSectionElement? = null
    var codeSection: WasmSectionCode? = null
    var dataSection: WasmSectionData? = null
    var dataCountSection: WasmSectionDataCount? = null
    var exceptionSection: WasmSectionException? = null
    val unresolvedSections = mutableListOf<WasmSection>()

    sections.forEach { section ->
        when (section) {
            is WasmSectionCustom -> customSections.add(section)
            is WasmSectionTypes -> typesSection = section
            is WasmSectionImport -> importSection = section
            is WasmSectionFunctions -> functionSection = section
            is WasmSectionTable -> tableSection = section
            is WasmSectionMemory -> memorySection = section
            is WasmSectionGlobal -> globalSection = section
            is WasmSectionExport -> exportSection = section
            is WasmSectionStart -> startSection = section
            is WasmSectionElement -> elementSection = section
            is WasmSectionCode -> codeSection = section
            is WasmSectionData -> dataSection = section
            is WasmSectionDataCount -> dataCountSection = section
            is WasmSectionException -> exceptionSection = section
            else -> {
                unresolvedSections.add(section)
            }
        }
    }

    return WasmModule(
        sections,
        customSections,
        typesSection,
        importSection,
        functionSection,
        tableSection,
        memorySection,
        globalSection,
        exportSection,
        startSection,
        elementSection,
        codeSection,
        dataSection,
        dataCountSection,
        exceptionSection,
        unresolvedSections
    )
}

data class FunctionRecord(val signature: WasmTypeRecord, val body: WasmCodeRecord)

fun BaseModule.customSectionByName(name: String): WasmSectionCustom? = customSections.find { it.name == name }