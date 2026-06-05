package org.jetbrains.hexana.mcp

sealed interface WasmInput {
    data class FilePath(val path: String) : WasmInput
    data class Uri(val uri: String) : WasmInput
    data class InlineBytes(val base64: String) : WasmInput

    class RawBytes(bytes: ByteArray) : WasmInput {
        private val stableBytes = bytes.copyOf()

        val bytes: ByteArray
            get() = stableBytes.copyOf()

        override fun equals(other: Any?): Boolean {
            return other is RawBytes && stableBytes.contentEquals(other.stableBytes)
        }

        override fun hashCode(): Int {
            return stableBytes.contentHashCode()
        }

        override fun toString(): String {
            return "RawBytes(size=${stableBytes.size})"
        }
    }
}

sealed interface TextInput {
    data class FilePath(val path: String) : TextInput
}
