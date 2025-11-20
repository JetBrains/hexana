package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.JvmByteBuffer
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import java.nio.ByteBuffer

internal sealed interface BinaryProvider {
    val bytes: ByteArray
}

class GenericBinaryProvider(override val bytes: ByteArray) : BinaryProvider
class WasmBinaryProvider(override val bytes: ByteArray) : BinaryProvider {
    private val buffer = JvmByteBuffer(ByteBuffer.wrap(bytes))
    val wasmModule = WasmParser().parse(buffer)

    fun getImports(): List<WasmImportRecord> {
        val importSection = wasmModule?.importSection ?: return emptyList()
        with (importSection) {
            return buffer.getImportRecords()
        }
    }
}