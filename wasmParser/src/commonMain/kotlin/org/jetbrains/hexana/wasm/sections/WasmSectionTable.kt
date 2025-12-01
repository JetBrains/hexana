package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes

class WasmSectionTable(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 4
    }

    fun CommonByteBuffer.scanTable(wasmModule: WasmModule): List<WasmTableEntry> {
        position(offset)
        val entries = mutableListOf<WasmTableEntry>()
        consumeArray {
            if (get() == 0x40.toByte()) {
                if (get() == 0x00.toByte()) {
                    entries.add(WasmTableEntry(tableType(), parseOpCodes()))
                    return@consumeArray
                } else {
                    position(position() - 1)
                }
            }else {
                position(position() - 1)
            }
            entries.add(WasmTableEntry(tableType()))
        }
        return entries
    }

    private fun CommonByteBuffer.tableType(): WasmTableType {
        return WasmTableType(referenceType(), limits())
    }
}

data class WasmTableType(val referenceType: WasmReferenceType, val limits: WasmLimits<*> )

class WasmTableEntry(val tableType: WasmTableType, val expression: List<OpCode>? = null)
