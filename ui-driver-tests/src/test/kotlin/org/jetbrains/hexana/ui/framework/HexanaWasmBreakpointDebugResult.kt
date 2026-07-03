package org.jetbrains.hexana.ui.framework

private const val PAUSED_MARKER = "PAUSED"
private const val RESUMED_MARKER = "RESUMED"
private val RUNTIME_INJECTED_VARIABLES = setOf("__vmctx", "memory")

internal sealed class HexanaWasmBreakpointDebugResult(open val rawOutput: String) {
    data class Success(
        override val rawOutput: String,
        val variables: List<HexanaWasmBreakpointVariable>,
    ) : HexanaWasmBreakpointDebugResult(rawOutput) {
        val userVariables: Map<String, String> =
            variables.asSequence()
                .filterNot { it.name in RUNTIME_INJECTED_VARIABLES }
                .associate { it.name to it.value }
    }

    data class Timeout(override val rawOutput: String) : HexanaWasmBreakpointDebugResult(rawOutput)

    data class Malformed(
        override val rawOutput: String,
        val reason: String,
    ) : HexanaWasmBreakpointDebugResult(rawOutput)

    fun assertStrict(expectedA: String = "3", expectedB: String = "7"): Success =
        when (this) {
            is Success -> {
                val userVariables = userVariables
                requireResolvedValue(userVariables, "a", expectedA)
                requireResolvedValue(userVariables, "b", expectedB)
                this
            }
            is Timeout -> fail("Expected a paused breakpoint session, but the helper timed out")
            is Malformed -> fail("Expected a parsed breakpoint session, but output was malformed: $reason")
        }

    private fun requireResolvedValue(
        userVariables: Map<String, String>,
        name: String,
        expectedValue: String,
    ) {
        val value = userVariables[name]
            ?: fail("Expected variable '$name' to be present in the breakpoint output")
        if (value.isBlank() || !value.contains(expectedValue)) {
            fail("Expected variable '$name' to contain '$expectedValue', but it was '$value'")
        }
    }

    private fun fail(message: String): Nothing =
        throw AssertionError("$message; rawOutput=$rawOutput")

    companion object {
        fun parse(rawOutput: String): HexanaWasmBreakpointDebugResult {
            val trimmed = rawOutput.trim()
            if (trimmed.lineSequence().firstOrNull() == "TIMEOUT") {
                return Timeout(rawOutput)
            }
            if (trimmed.isEmpty()) {
                return Malformed(rawOutput, "empty output")
            }

            val lines = trimmed.lines()
            if (lines.first() != PAUSED_MARKER) {
                return Malformed(rawOutput, "missing $PAUSED_MARKER marker at the first line")
            }
            if (lines.last() != RESUMED_MARKER) {
                return Malformed(rawOutput, "missing $RESUMED_MARKER marker at the last line")
            }
            if (lines.size < 3) {
                return Malformed(rawOutput, "missing variable lines between $PAUSED_MARKER and $RESUMED_MARKER")
            }

            val variables = mutableListOf<HexanaWasmBreakpointVariable>()
            for (line in lines.subList(1, lines.size - 1)) {
                if (line.isBlank()) {
                    return Malformed(rawOutput, "blank variable line")
                }
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    return Malformed(rawOutput, "malformed variable line '$line'; expected name=value")
                }
                val name = line.substring(0, separator)
                val value = line.substring(separator + 1)
                variables += HexanaWasmBreakpointVariable(name, value)
            }

            return Success(rawOutput, variables)
        }
    }
}

internal data class HexanaWasmBreakpointVariable(
    val name: String,
    val value: String,
)
