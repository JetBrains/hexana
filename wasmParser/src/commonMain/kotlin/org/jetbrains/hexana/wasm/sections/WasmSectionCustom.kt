package org.jetbrains.hexana.wasm.sections

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
}


private fun getSectionDisplayName(rawName: String): String {
    if (rawName.isBlank()) {
        return "Custom"
    }
    return rawName
}