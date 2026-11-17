package org.jetbrains.hexana.cli.runtime

import java.nio.file.Path
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Runtime context equality and hash codes intentionally include the immutable environment snapshot because it is part
 * of the execution context identity. String rendering intentionally omits environment keys and values so logs and
 * exception messages cannot expose caller-provided secrets.
 */
class ToolRuntimeContext(
    val toolName: String,
    val workingDirectory: Path? = null,
    environment: Map<String, String> = emptyMap(),
) {
    val environment: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(environment))

    init {
        require(toolName.isNotBlank()) { "toolName must not be blank." }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolRuntimeContext) return false

        return toolName == other.toolName &&
            workingDirectory == other.workingDirectory &&
            environment == other.environment
    }

    override fun hashCode(): Int {
        var result = toolName.hashCode()
        result = 31 * result + (workingDirectory?.hashCode() ?: 0)
        result = 31 * result + environment.hashCode()
        return result
    }

    override fun toString(): String =
        "ToolRuntimeContext(" +
            "toolName=$toolName, " +
            "workingDirectory=$workingDirectory, " +
            "environment=<redacted>" +
            ")"
}
