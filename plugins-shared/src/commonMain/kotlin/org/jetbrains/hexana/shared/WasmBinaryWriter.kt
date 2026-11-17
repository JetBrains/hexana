package org.jetbrains.hexana.shared

/**
 * Minimal binary writer for constructing WebAssembly module bytes.
 *
 * This is a platform-independent utility used by [WasmImportStubGenerator]
 * and potentially other components that need to produce valid `.wasm` binaries
 * at runtime.
 */
class WasmBinaryWriter {
    private val bytes = mutableListOf<Byte>()

    fun writeByte(b: Int) {
        bytes.add(b.toByte())
    }

    fun writeBytes(data: ByteArray) {
        for (b in data) bytes.add(b)
    }

    fun writeULEB128(value: Int) {
        var v = value
        do {
            var byte = v and 0x7F
            v = v ushr 7
            if (v != 0) byte = byte or 0x80
            bytes.add(byte.toByte())
        } while (v != 0)
    }

    fun writeName(name: String) {
        val encoded = name.encodeToByteArray()
        writeULEB128(encoded.size)
        writeBytes(encoded)
    }

    fun writeSection(sectionId: Int, content: ByteArray) {
        writeByte(sectionId)
        writeULEB128(content.size)
        writeBytes(content)
    }

    fun size(): Int = bytes.size

    fun toByteArray(): ByteArray = bytes.toByteArray()
}
