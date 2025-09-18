package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.CommonByteBuffer
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
import org.jetbrains.hexana.wasm.sections.WasmSectionTypesRecord

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
    val exceptionSection: WasmSectionException? = null
) {
    companion object {
        fun fromSections(sections: List<WasmSection>): WasmModule {
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
            var exceptionSection: WasmSectionException? = null

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
                    is WasmSectionException -> exceptionSection = section
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
                exceptionSection
            )
        }
    }

    fun CommonByteBuffer.scanFunctions(): List<FunctionRecord> {
        requireNotNull(typesSection)
        requireNotNull(functionSection)
        requireNotNull(codeSection)
        val types = with (typesSection) {
            parseTypes()
        }

        val functions = with(functionSection) {
            parseFunctions()
        }

        val bodies = with(codeSection) {
            scan()
        }

        return functions.map { types[it] }.zip(bodies).map { (signature, body) ->
            FunctionRecord(signature, body)
        }
    }
}

data class FunctionRecord(val signature: WasmSectionTypesRecord, val body: WasmCodeRecord)