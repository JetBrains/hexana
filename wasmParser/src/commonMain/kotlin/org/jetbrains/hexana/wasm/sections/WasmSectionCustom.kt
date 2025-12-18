package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer

class WasmSectionCustom(
    /**
     * [rawName] is the name written in the binary
     */
    val rawName: String,
    sectionSize: Int,
    sectionOffset: Int,
    contentSize: Int,
    contentOffset: Int,
): WasmSection(getSectionDisplayName(rawName), sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 0
    }

    fun CommonByteBuffer.parseAsNameSection(): WasmNameSection {
        // https://webassembly.github.io/spec/core/appendix/custom.html
        position(contentOffset)

        var moduleSubsection: WasmModuleNameSection? = null
        var functionSubsection: WasmFunctionsNameSection? = null
        var localsSection: WasmLocalsNameSection? = null
        var labelsSection: WasmLabelsNameSection? = null
        var typesSection: WasmTypesNameSection? = null
        var tablesNameSection: WasmTablesNameSection? = null
        var memoriesNameSection: WasmMemoriesNameSection? = null
        var globalsNameSection: WasmGlobalsNameSection? = null
        var elemsNameSection: WasmElemsNameSection? = null
        var dataSegmentsNameSection: WasmDataSegmentsNameSection? = null
        var fieldsNameSection: WasmFieldsNameSection? = null
        var tagsNameSection: WasmTagsNameSection? = null

        while (position() < (contentOffset + contentSize)) {
            when (val subsection = parseAsNameSubSection()) {
                is WasmModuleNameSection -> moduleSubsection = subsection
                is WasmFunctionsNameSection -> functionSubsection = subsection
                is WasmLocalsNameSection -> localsSection = subsection
                is WasmLabelsNameSection -> labelsSection = subsection
                is WasmTypesNameSection -> typesSection = subsection
                is WasmTablesNameSection -> tablesNameSection = subsection
                is WasmMemoriesNameSection -> memoriesNameSection = subsection
                is WasmGlobalsNameSection -> globalsNameSection = subsection
                is WasmElemsNameSection -> elemsNameSection = subsection
                is WasmDataSegmentsNameSection -> dataSegmentsNameSection = subsection
                is WasmFieldsNameSection -> fieldsNameSection = subsection
                is WasmTagsNameSection -> tagsNameSection = subsection
                else -> break
            }
        }

        return WasmNameSection(
            moduleSubsection = moduleSubsection,
            functionSubsection = functionSubsection,
            localsSection = localsSection,
            labelsSection = labelsSection,
            typesSection = typesSection,
            tablesNameSection = tablesNameSection,
            memoriesNameSection = memoriesNameSection,
            globalsNameSection = globalsNameSection,
            elemsNameSection = elemsNameSection,
            dataSegmentsNameSection = dataSegmentsNameSection,
            fieldsNameSection = fieldsNameSection,
            tagsNameSection = tagsNameSection,
        )
    }

    fun CommonByteBuffer.parseAsNameSubSection(): WasmNameSubsection? {
        // https://webassembly.github.io/spec/core/appendix/custom.html
        val subSectionId = get().toInt()
        // sub section size
        getULEB128()

        return when (subSectionId) {
            0 -> WasmModuleNameSection(consumeString())
            1 -> WasmFunctionsNameSection(consumeNamespace())
            2 -> WasmLocalsNameSection(consumeIndirectNamespace())
            // TODO: generate binary with labels, though it's standard, it's pretty rare
            3 -> WasmLabelsNameSection(consumeIndirectNamespace())
            4 -> WasmTypesNameSection(consumeNamespace())
            5 -> WasmTablesNameSection(consumeNamespace())
            6 -> WasmMemoriesNameSection(consumeNamespace())
            7 -> WasmGlobalsNameSection(consumeNamespace())
            8 -> WasmElemsNameSection(consumeNamespace())
            9 -> WasmDataSegmentsNameSection(consumeNamespace())
            10 -> WasmFieldsNameSection(consumeIndirectNamespace())
            11 -> WasmTagsNameSection(consumeNamespace())
            else -> null
        }
    }

    private fun CommonByteBuffer.consumeNamespace(): List<WasmNameMapItem> {
        val items = mutableListOf<WasmNameMapItem>()
        consumeArray {
            val id = getULEB128().toInt()
            val name = consumeString()
            items.add(WasmNameMapItem(id, name))
        }
        return items
    }

    private fun CommonByteBuffer.consumeIndirectNamespace(): List<WasmIndirectNameMapItem> {
        val items = mutableListOf<WasmIndirectNameMapItem>()

        consumeArray {
            val id = getULEB128().toInt()
            items.add(WasmIndirectNameMapItem(id, consumeNamespace()))
        }

        return items
    }
}



sealed interface WasmNameSubsection
class WasmModuleNameSection(val moduleName: String) : WasmNameSubsection

class WasmFunctionsNameSection(val namemap: List<WasmNameMapItem>) : WasmNameSubsection
class WasmLocalsNameSection(val namemap: List<WasmIndirectNameMapItem>) : WasmNameSubsection
class WasmLabelsNameSection(val namemap: List<WasmIndirectNameMapItem>) : WasmNameSubsection
class WasmTypesNameSection(val namemap: List<WasmNameMapItem>) : WasmNameSubsection
class WasmTablesNameSection(val namemap: List<WasmNameMapItem>) : WasmNameSubsection
class WasmMemoriesNameSection(val namemap: List<WasmNameMapItem>): WasmNameSubsection
class WasmGlobalsNameSection(val namemap: List<WasmNameMapItem>) : WasmNameSubsection
class WasmElemsNameSection(val namemap: List<WasmNameMapItem>) : WasmNameSubsection
class WasmDataSegmentsNameSection(val namemap: List<WasmNameMapItem>) : WasmNameSubsection
class WasmFieldsNameSection(val namemap: List<WasmIndirectNameMapItem>) : WasmNameSubsection
class WasmTagsNameSection(val namemap: List<WasmNameMapItem>) : WasmNameSubsection

class WasmNameSection(
    val moduleSubsection: WasmModuleNameSection? = null,
    val functionSubsection: WasmFunctionsNameSection? = null,
    val localsSection: WasmLocalsNameSection? = null,
    val labelsSection: WasmLabelsNameSection? = null,
    val typesSection: WasmTypesNameSection? = null,
    val tablesNameSection: WasmTablesNameSection? = null,
    val memoriesNameSection: WasmMemoriesNameSection? = null,
    val globalsNameSection: WasmGlobalsNameSection? = null,
    val elemsNameSection: WasmElemsNameSection? = null,
    val dataSegmentsNameSection: WasmDataSegmentsNameSection? = null,
    val fieldsNameSection: WasmFieldsNameSection? = null,
    val tagsNameSection: WasmTagsNameSection? = null,
)

data class WasmNameMapItem(val id: Int, val name: String)
data class WasmIndirectNameMapItem(val id: Int, val value: List<WasmNameMapItem>)

private fun getSectionDisplayName(rawName: String): String {
    return rawName.ifBlank {
        "Custom"
    }
}


