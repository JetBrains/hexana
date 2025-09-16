package org.jetbrains.hexana.wasm

sealed interface WasmSection {
    val size: Int
}

class WasmSectionCustom(override val size: Int): WasmSection {
    companion object {
        const val SECTION_ID: Byte = 0
    }
}

class WasmSectionTypes(override val size: Int): WasmSection {
    companion object {
        const val SECTION_ID: Byte = 1
    }
}

class WasmSectionImport(override val size: Int): WasmSection {
    companion object {
        const val SECTION_ID: Byte = 2
    }
}

class WasmSectionFunctions(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 3
    }
}

class WasmSectionTable(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 4
    }
}

class WasmSectionMemory(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 5
    }
}

class WasmSectionGlobal(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 6
    }
}

class WasmSectionExport(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 7
    }
}

class WasmSectionStart(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 8
    }
}

class WasmSectionElement(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 9
    }
}

class WasmSectionCode(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 10
    }
}

class WasmSectionData(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 11
    }
}

class WasmSectionDataCount(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 12
    }
}

class WasmSectionException(override val size: Int): WasmSection  {
    companion object {
        const val SECTION_ID: Byte = 13
    }
}

class WasmSectionUnresolved(val id: Byte, override val size: Int): WasmSection