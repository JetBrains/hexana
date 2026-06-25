package org.jetbrains.hexana.mcp

sealed interface BinaryInput {
    data class FilePath(val path: String) : BinaryInput
    data class InlineBytes(val base64: String) : BinaryInput

    class RawBytes(bytes: ByteArray) : BinaryInput {
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
