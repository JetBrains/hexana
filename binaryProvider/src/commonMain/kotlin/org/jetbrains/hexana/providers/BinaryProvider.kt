package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
import org.jetbrains.hexana.wasm.sections.WasmDataRecord
import org.jetbrains.hexana.wasm.sections.WasmExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import org.jetbrains.hexana.wasm.sections.WasmSection

sealed interface BinaryProvider {
    val bytes: ByteArray
}

class GenericBinaryProvider(override val bytes: ByteArray) : BinaryProvider
open class WasmBinaryProvider(override val bytes: ByteArray) : BinaryProvider {
    private val buffer = bytes.toCommonByteBuffer()
    val wasmModule = WasmParser().parse(buffer)

    val imports: List<WasmImportRecord> by lazy {
        val importSection = wasmModule?.importSection ?: return@lazy emptyList()
        with (importSection) {
            buffer.getImportRecords()
        }
    }

    val exports: List<WasmExportRecordEntry> by lazy {
        val exportsSection = wasmModule?.exportSection ?: return@lazy emptyList()
        with (exportsSection) {
            buffer.scan()
        }
    }

    val data: List<WasmDataRecord> by lazy {
        val dataSection = wasmModule?.dataSection ?: return@lazy emptyList()
        with (dataSection) {
            buffer.scan()
        }
    }

    val codeRecords: List<WasmCodeRecord> by lazy {
        val functionSection = wasmModule?.codeSection ?: return@lazy emptyList()
        with (functionSection) {
            buffer.scan()
        }
    }

    fun getCodeRecordByIndex(index: Int): WasmCodeRecord? {
        return codeRecords.getOrNull(index)
    }

    fun getCodeRecordIndexAtByteOffset(byteOffset: Long): Int {
        return codeRecords.indexOfFirst { record ->
            record.offset <= byteOffset && byteOffset < record.offset + record.size.toLong()
        }
    }

    fun getDataRecordIndexAtByteOffset(byteOffset: Long): Int {
        return data.indexOfFirst { record ->
            record.offset <= byteOffset && byteOffset < record.offset + record.recordLength.toLong()
        }
    }

    fun getRecords(section: WasmSection): List<WasmNamedRange> {
        return try {
            section.getRecordRanges(buffer)
        } catch (_: Exception) {
            // we throw exception on unknown op-codes
            emptyList()
        }
    }
}