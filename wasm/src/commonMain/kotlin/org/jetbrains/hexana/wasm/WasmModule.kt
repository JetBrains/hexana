package org.jetbrains.hexana.wasm

class WasmModule(
    val customSection: WasmSectionCustom? = null,
    val typesSection: WasmSectionTypes? = null,
    val importSection: WasmSectionImport? = null,
    val functionSection: WasmSectionFunctions? = null,
    val tableSection: WasmSectionTable? = null,
    val memorySection: WasmSectionMemory? = null,
    val globalSection: WasmSectionGlobal? = null,
    val exportSection: WasmSectionExport? = null,
    val startSection: WasmSectionStart? = null,
    val elementSection: WasmSectionElement? = null,
    val codeSection: WasmSectionCode? = null,
    val dataSection: WasmSectionData? = null,
    val dataCountSection: WasmSectionDataCount? = null,
    val dataExceptionSection: WasmSectionException? = null
) {
    companion object {
        fun fromSections(sections: List<WasmSection>): WasmModule? {
            var customSection: WasmSectionCustom? = null
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
            var dataExceptionSection: WasmSectionException? = null

            sections.forEach { section ->
                when (section) {
                    is WasmSectionCustom -> customSection = section
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
                    is WasmSectionException -> dataExceptionSection = section
                    else -> {}
                }
            }

            return WasmModule(
                customSection,
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
                dataExceptionSection
            )
        }
    }
}