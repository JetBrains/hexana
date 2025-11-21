package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.sections.WasmDataRecord
import org.jetbrains.hexana.wasm.sections.WasmImportRecord

sealed interface BinaryProvider {
    val bytes: ByteArray
}

class GenericBinaryProvider(override val bytes: ByteArray) : BinaryProvider
open class WasmBinaryProvider(override val bytes: ByteArray) : BinaryProvider {
    private val buffer = bytes.toCommonByteBuffer()
    val wasmModule = WasmParser().parse(buffer)

    fun getImports(): List<WasmImportRecord> {
        val importSection = wasmModule?.importSection ?: return emptyList()
        with (importSection) {
            return buffer.getImportRecords()
        }
    }

    fun getData(): List<WasmDataRecord> {
        val dataSection = wasmModule?.dataSection ?: return emptyList()
        with (dataSection) {
            return buffer.scan()
        }
    }
}