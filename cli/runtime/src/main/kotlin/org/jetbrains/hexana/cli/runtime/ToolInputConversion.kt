package org.jetbrains.hexana.cli.runtime

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
    sourceSelections: List<ToolInputSourceSelection> = emptyList(),
) {
    private val stdinBytes = stdinBytes?.copyOf()
    val pathArguments: Map<String, String> = pathArguments.toMap()
    val sourceSelections: List<ToolInputSourceSelection> = sourceSelections.toList()

    init {
        require(stdinText == null || stdinBytes == null) {
            "stdinText and stdinBytes are mutually exclusive."
        }
        this.pathArguments.keys.forEach { requireValidToolInputSourceSlotName(it) }
        val duplicateSourceNames = (this.pathArguments.keys.toList() + this.sourceSelections.mapNotNull { it.slotName })
            .groupBy { it }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateSourceNames.isEmpty()) {
            "Duplicate input source names: $duplicateSourceNames"
        }
    }

    fun stdinBytesCopy(): ByteArray? = stdinBytes?.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolInputSources) return false

        return stdinText == other.stdinText &&
            stdinBytes.contentEquals(other.stdinBytes) &&
            pathArguments == other.pathArguments &&
            sourceSelections == other.sourceSelections
    }

    override fun hashCode(): Int {
        var result = stdinText?.hashCode() ?: 0
        result = 31 * result + (stdinBytes?.contentHashCode() ?: 0)
        result = 31 * result + pathArguments.hashCode()
        result = 31 * result + sourceSelections.hashCode()
        return result
    }

    override fun toString(): String =
        "ToolInputSources(" +
            "stdinTextLength=${stdinText?.length}, " +
            "stdinBytesSize=${stdinBytes?.size}, " +
            "pathArguments=$pathArguments, " +
            "sourceSelections=$sourceSelections" +
            ")"
}
