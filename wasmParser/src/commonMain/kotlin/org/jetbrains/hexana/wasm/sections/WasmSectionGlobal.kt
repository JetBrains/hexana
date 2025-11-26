package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes

class WasmSectionGlobal(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 6
    }

    fun CommonByteBuffer.scan(): List<WasmGlobalRecord> {
        position(offset)
        val records = mutableListOf<WasmGlobalRecord>()
        consumeArray {
            val globalType = WasmGlobalType(valueType(), get() == 0x01.toByte())
            val expression = parseOpCodes()
            records.add(WasmGlobalRecord(globalType, expression))
        }
        return records
    }
}

data class WasmGlobalRecord(val type: WasmGlobalType, val expression: List<OpCode<*>>)
