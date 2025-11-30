package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.UnsupportedOpsException
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes

class WasmSectionCode(size: Int, offset: Int): WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 10
    }

    fun CommonByteBuffer.scan(): List<WasmCodeRecord> {
        // https://webassembly.github.io/spec/core/binary/modules.html#binary-codesec
        this.position(offset)

        val wasmCodeRecords = mutableListOf<WasmCodeRecord>()

        consumeArray { funcIndx ->
            wasmCodeRecords.add(consumeCodeEntry())
        }

        return wasmCodeRecords
    }
}

fun CommonByteBuffer.consumeCodeEntry(): WasmCodeRecord {
    val pos = position()
    val funSize = getULEB128().toUInt()

    val bodyStart = position()
    val locals = mutableListOf<WasmCodeLocalRecord>()
    try {
        consumeLocals(locals)
        return WasmCodeRecord(pos.toLong(), funSize, locals, parseOpCodes())
    } catch (e: UnsupportedOpsException) {

        position(bodyStart)

        val raw = ByteArray(funSize.toInt())
        get(raw, 0, raw.size)

        return WasmCodeRecord(
                pos.toLong(),
                funSize,
                locals,
            listOf(UnparsedOpCode(e, raw))).also {
            position(bodyStart + funSize.toInt())
        }
    }
}

fun CommonByteBuffer.consumeLocals(locals: MutableList<WasmCodeLocalRecord>) {
    consumeArray { localIndex ->
        val local = getULEB128().toInt()
        val type = valueType()
        locals.add(WasmCodeLocalRecord(local, type))
    }
}

class UnparsedOpCode(val e: Throwable, rawCodes: ByteArray): OpCode<ByteArray>(rawCodes)

data class WasmCodeLocalRecord(val local: Int, val type: WasmValueType<*>)
data class WasmCodeRecord(val offset: Long, val size: UInt, val locals: List<WasmCodeLocalRecord>, val opCodes: List<OpCode<*>>)