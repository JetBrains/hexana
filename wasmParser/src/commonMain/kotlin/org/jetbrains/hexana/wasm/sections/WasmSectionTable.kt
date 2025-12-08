package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes

class WasmSectionTable(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int): WasmSection(sectionSize, sectionOffset, contentSize, contentOffset) {
    companion object {
        const val SECTION_ID: Byte = 4
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    fun CommonByteBuffer.scanTable(wasmModule: WasmModule): List<WasmTableEntry> {
        position(contentOffset)
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
