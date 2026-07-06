package org.jetbrains.hexana.mcp.tool.runtime

import kotlinx.serialization.json.JsonObject

interface ToolInputConverter {
    fun convert(request: ToolInputConversionRequest): JsonObject
}

data class ToolInputConversionRequest(
    val context: ToolRuntimeContext,
    val rawArguments: JsonObject,
    val sources: ToolInputSources = ToolInputSources(),
)

class ToolInputSources(
    val stdinText: String? = null,
    stdinBytes: ByteArray? = null,
    pathArguments: Map<String, String> = emptyMap(),
) {
    private val stdinBytes = stdinBytes?.copyOf()
    val pathArguments: Map<String, String> = pathArguments.toMap()

    init {
        require(stdinText == null || stdinBytes == null) {
            "stdinText and stdinBytes are mutually exclusive."
        }
    }

    fun stdinBytesCopy(): ByteArray? = stdinBytes?.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolInputSources) return false

        return stdinText == other.stdinText &&
            stdinBytes.contentEquals(other.stdinBytes) &&
            pathArguments == other.pathArguments
    }

    override fun hashCode(): Int {
        var result = stdinText?.hashCode() ?: 0
        result = 31 * result + (stdinBytes?.contentHashCode() ?: 0)
        result = 31 * result + pathArguments.hashCode()
        return result
    }

    override fun toString(): String =
        "ToolInputSources(stdinTextLength=${stdinText?.length}, stdinBytesSize=${stdinBytes?.size}, pathArguments=$pathArguments)"
}
