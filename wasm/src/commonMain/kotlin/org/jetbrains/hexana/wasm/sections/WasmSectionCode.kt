package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes

class WasmSectionCode(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 10
    }

    fun CommonByteBuffer.scan(): List<WasmCodeRecord> {
        // https://webassembly.github.io/spec/core/binary/modules.html#binary-codesec
        this.position(offset)

        val wasmCodeRecords = mutableListOf<WasmCodeRecord>()

        consumeArray {
            val pos = position()
            val funSize = getULEB128().toUInt()
            val locals = mutableListOf<WasmCodeLocalRecord>()
            consumeArray {
                val local = getULEB128().toInt()
                val type = valueType()
                locals.add(WasmCodeLocalRecord(local, type))
            }

            wasmCodeRecords.add(WasmCodeRecord(pos.toLong(), funSize, locals, parseOpCodes()))
        }

        return wasmCodeRecords
    }
}

data class WasmCodeLocalRecord(val local: Int, val type: WasmValueType<*>)
data class WasmCodeRecord(val offset: Long, val size: UInt, val locals: List<WasmCodeLocalRecord>, val opCodes: List<OpCode<*>>)