package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.consumeOpCode

class WasmSectionCode(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 10
    }

    fun CommonByteBuffer.scan(): List<WasmCodeRecord> {
        // https://webassembly.github.io/spec/core/binary/modules.html#binary-codesec
        this.position(offset)

        val wasmCodeRecords = mutableListOf<WasmCodeRecord>()

        consumeArray {
            val funSize = getULEB128().toInt()
            val locals = mutableListOf<WasmCodeLocalRecord>()
            consumeArray {
                val local = getULEB128().toInt()
                val type = get()
                locals.add(WasmCodeLocalRecord(local, type))
            }

            val opCodes = mutableListOf<OpCode<*>>()
            while (true) {
                val opCode = get().toUByte()
                if (opCode == OpCodes.Flow.Terminal.code) {
                    break
                }

                val op = consumeOpCode(opCode) ?: error("Unknown opcode $opCode")
                opCodes.add(op)
            }

            wasmCodeRecords.add(WasmCodeRecord(funSize, locals, opCodes))
        }

        return wasmCodeRecords
    }
}

data class WasmCodeLocalRecord(val local: Int, val type: Byte)
data class WasmCodeRecord(val size: Int, val locals: List<WasmCodeLocalRecord>, val opCodes: List<OpCode<*>>)