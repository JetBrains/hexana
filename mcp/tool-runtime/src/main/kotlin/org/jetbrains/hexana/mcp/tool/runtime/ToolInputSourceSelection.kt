package org.jetbrains.hexana.mcp.tool.runtime

sealed interface ToolInputSourceSelection {
    val slotName: String?
    val mcpPayloadField: ToolInputSourceMcpPayloadField

    val mcpPayloadFieldName: String
        get() = mcpPayloadField.jsonName

    data class Path(
        val path: String,
        override val slotName: String? = null,
    ) : ToolInputSourceSelection {
        override val mcpPayloadField: ToolInputSourceMcpPayloadField
            get() = ToolInputSourceMcpPayloadField.Path

        init {
            requireValidToolInputSourceSlotName(slotName)
            require(path.isNotBlank()) { "Path source path must not be blank." }
        }
    }

    data class Base64(
        val bytesBase64: String,
        override val slotName: String? = null,
    ) : ToolInputSourceSelection {
        override val mcpPayloadField: ToolInputSourceMcpPayloadField
            get() = ToolInputSourceMcpPayloadField.BytesBase64

        init {
            requireValidToolInputSourceSlotName(slotName)
            require(bytesBase64.isNotBlank()) { "Base64 source payload must not be blank." }
        }

        override fun toString(): String =
            "Base64(slotName=$slotName, bytesBase64Length=${bytesBase64.length})"
    }

    class StdinBytes(
        bytes: ByteArray,
        override val slotName: String? = null,
    ) : ToolInputSourceSelection {
        private val stableBytes = bytes.copyOf()

        override val mcpPayloadField: ToolInputSourceMcpPayloadField
            get() = ToolInputSourceMcpPayloadField.Bytes

        init {
            requireValidToolInputSourceSlotName(slotName)
        }

        fun bytes(): ByteArray = stableBytes.copyOf()

        override fun equals(other: Any?): Boolean {
            return other is StdinBytes &&
                slotName == other.slotName &&
                stableBytes.contentEquals(other.stableBytes)
        }

        override fun hashCode(): Int {
            var result = stableBytes.contentHashCode()
            result = 31 * result + (slotName?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "StdinBytes(slotName=$slotName, byteSize=${stableBytes.size})"
    }

    class ByteList(
        bytes: List<Int>,
        override val slotName: String? = null,
    ) : ToolInputSourceSelection {
        val bytes: List<Int> = bytes.toList()

        override val mcpPayloadField: ToolInputSourceMcpPayloadField
            get() = ToolInputSourceMcpPayloadField.Bytes

        init {
            requireValidToolInputSourceSlotName(slotName)
            require(this.bytes.all { it in 0..255 }) {
                "Byte-list source values must be in the 0..255 range."
            }
        }

        override fun equals(other: Any?): Boolean {
            return other is ByteList &&
                slotName == other.slotName &&
                bytes == other.bytes
        }

        override fun hashCode(): Int {
            var result = bytes.hashCode()
            result = 31 * result + (slotName?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "ByteList(slotName=$slotName, byteCount=${bytes.size})"
    }

    companion object {
        fun fromPayload(
            slotName: String? = null,
            path: String? = null,
            stdinBytes: ByteArray? = null,
            bytesBase64: String? = null,
            bytes: List<Int>? = null,
        ): ToolInputSourceSelection {
            val payloadCount = listOfNotNull(path, stdinBytes, bytesBase64, bytes).size
            require(payloadCount == 1) {
                "Exactly one source payload must be provided."
            }

            return when {
                path != null -> Path(path = path, slotName = slotName)
                stdinBytes != null -> StdinBytes(bytes = stdinBytes, slotName = slotName)
                bytesBase64 != null -> Base64(bytesBase64 = bytesBase64, slotName = slotName)
                bytes != null -> ByteList(bytes = bytes, slotName = slotName)
                else -> error("Unreachable payload validation branch.")
            }
        }
    }
}

enum class ToolInputSourceMcpPayloadField(val jsonName: String) {
    Path("path"),
    BytesBase64("bytesBase64"),
    Bytes("bytes"),
}

internal fun requireValidToolInputSourceSlotName(slotName: String?) {
    require(slotName == null || slotName.isNotBlank()) {
        "Source slot name must not be blank."
    }
}
