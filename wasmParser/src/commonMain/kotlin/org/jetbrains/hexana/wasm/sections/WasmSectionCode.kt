package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue
import org.jetbrains.hexana.wasm.opcodes.UnsupportedOpsException
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes

class WasmSectionCode(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Code", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 10
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return with (buffer) {
            scan().mapIndexed { index, record ->
                WasmNamedRange(record, "func $index")
            }
        }
    }

    fun CommonByteBuffer.scan(): List<WasmCodeRecord> {
        // https://webassembly.github.io/spec/core/binary/modules.html#binary-codesec
        this.position(contentOffset)

        val wasmCodeRecords = mutableListOf<WasmCodeRecord>()

        consumeArray { funcIndx ->
            wasmCodeRecords.add(consumeCodeEntry())
        }

        return wasmCodeRecords
    }

    fun CommonByteBuffer.scanCodeEntries(declaredFuncIdxs: List<Int>): Map<Int, WasmCodeRecord> {
        val wasmCodeRecords = mutableMapOf<Int, WasmCodeRecord>()
        position(contentOffset)
        consumeArray { idx ->
            val current = position()
            val funSize = getULEB128().toUInt()
            if (idx in declaredFuncIdxs) {
                position(current) // code entry reread funcSize.
                wasmCodeRecords[idx] = consumeCodeEntry()
            } else {
                position(position() + funSize.toInt())
            }
        }
        return wasmCodeRecords
    }

    fun CommonByteBuffer.scanOpcodesForIndices(declaredFuncIdxs: List<Int>): Map<Int, List<OpCode>> {
        return scanCodeEntries(declaredFuncIdxs).map { it.key to it.value.opCodes }.toMap()
    }

    fun CommonByteBuffer.scanLocalsEntries(declaredFuncIdxs: List<Int>): Map<Int, List<WasmCodeLocalRecord>> {
        return scanCodeEntries(declaredFuncIdxs).map { it.key to it.value.locals }.toMap()
    }
}

fun CommonByteBuffer.consumeCodeEntry(): WasmCodeRecord {
    val codeEntryStart = position()
    val locals = mutableListOf<WasmCodeLocalRecord>()
    try {
        val funSize = getULEB128().toUInt()
        val bodyStart = position()
        try {
            consumeLocals(locals)
            val opCodes = parseOpCodes()
            return WasmCodeRecord(codeEntryStart, sizeFromOffset(codeEntryStart), locals, opCodes)
        } catch (e: Exception) {
            position(bodyStart)
            val bytesToRead = minOf(funSize.toInt(), remaining())
            val raw = ByteArray(bytesToRead)
            get(raw, 0, raw.size)
            position(bodyStart + bytesToRead)
            return WasmCodeRecord(
                codeEntryStart,
                sizeFromOffset(codeEntryStart),
                locals,
                listOf(UnparsedOpCode(e, raw))
            )
        }
    } catch (e: Exception) {
        val raw = ByteArray(remaining())
        if (raw.isNotEmpty()) get(raw, 0, raw.size)
        return WasmCodeRecord(
            codeEntryStart,
            sizeFromOffset(codeEntryStart),
            locals,
            listOf(UnparsedOpCode(e, raw))
        )
    }
}

fun CommonByteBuffer.consumeLocals(locals: MutableList<WasmCodeLocalRecord>) {
    consumeArray { localIndex ->
        val local = getULEB128().toInt()
        val type = valueType()
        locals.add(WasmCodeLocalRecord(local, type))
    }
}

class UnparsedOpCode(val e: Throwable, rawCodes: ByteArray): OpCodeWithValue<ByteArray>(rawCodes)

data class WasmCodeLocalRecord(val local: Int, val type: WasmValueType<*>)
data class WasmCodeRecord(
    override val offset: Int,
    override val size: Int,
    val locals: List<WasmCodeLocalRecord>,
    val opCodes: List<OpCode>,
) : WasmRange