package org.jetbrains.hexana.mcp.tools

import kotlin.reflect.KFunction

sealed class InputParameter {
    abstract val name: String
    abstract val describe: String?

    data class IntParam(
        override val name: String,
        val default: Int?,
        val min: Int?,
        val max: Int?,
        override val describe: String?,
    ) : InputParameter()

    data class IntListParam(
        override val name: String,
        val minSize: Int?,
        val maxSize: Int?,
        override val describe: String?,
    ) : InputParameter()

    data class ClosedRangeParam(
        override val name: String,
        override val describe: String?,
    ) : InputParameter()

    data class FlagParam(
        override val name: String,
        val default: Boolean,
        override val describe: String?,
    ) : InputParameter()
}

data class InputSchema(
    val parameters: List<InputParameter>,
)

data class ToolSpec(
    val name: String,
    val summary: String,
    val guidance: String?,
    val input: InputSchema,
    val handler: KFunction<*>,
)

@DslMarker
annotation class HexanaToolsDsl

@HexanaToolsDsl
class ToolsBuilder internal constructor() {
    private val specs = mutableListOf<ToolSpec>()

    fun tool(name: String, block: ToolBuilder.() -> Unit) {
        val builder = ToolBuilder(name).apply(block)
        specs += builder.build()
    }

    internal fun build(): List<ToolSpec> {
        val duplicates = specs.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate tool names: $duplicates" }
        return specs.toList()
    }
}

@HexanaToolsDsl
class ToolBuilder internal constructor(private val name: String) {
    var summary: String? = null
    var guidance: String? = null
    private var inputSchema: InputSchema? = null
    var handler: KFunction<*>? = null

    fun input(block: InputBuilder.() -> Unit) {
        val builder = InputBuilder().apply(block)
        inputSchema = builder.build()
    }

    internal fun build(): ToolSpec {
        val resolvedSummary = requireNotNull(summary) { "Tool '$name' is missing `summary`." }
        val resolvedInput = requireNotNull(inputSchema) { "Tool '$name' is missing `input { }`." }
        val resolvedHandler = requireNotNull(handler) { "Tool '$name' is missing `handler`." }
        return ToolSpec(
            name = name,
            summary = resolvedSummary.trim(),
            guidance = guidance?.trimIndent()?.trim()?.takeIf { it.isNotEmpty() },
            input = resolvedInput,
            handler = resolvedHandler,
        )
    }
}

@HexanaToolsDsl
class InputBuilder internal constructor() {
    private var wasmModuleDeclared = false
    private val parameters = mutableListOf<InputParameter>()

    fun wasmModule() {
        require(!wasmModuleDeclared) { "`wasmModule()` declared twice." }
        wasmModuleDeclared = true
    }

    fun int(
        name: String,
        default: Int? = null,
        min: Int? = null,
        max: Int? = null,
        describe: String? = null,
    ) {
        parameters += InputParameter.IntParam(name, default, min, max, describe)
    }

    fun intList(
        name: String,
        minSize: Int? = null,
        maxSize: Int? = null,
        describe: String? = null,
    ) {
        parameters += InputParameter.IntListParam(name, minSize, maxSize, describe)
    }

    fun closedRange(name: String, describe: String? = null) {
        parameters += InputParameter.ClosedRangeParam(name, describe)
    }

    fun flag(name: String, default: Boolean = false, describe: String? = null) {
        parameters += InputParameter.FlagParam(name, default, describe)
    }

    internal fun build(): InputSchema {
        require(wasmModuleDeclared) {
            "Every tool must declare `wasmModule()` in its `input { }` block."
        }
        return InputSchema(parameters.toList())
    }
}

fun tools(block: ToolsBuilder.() -> Unit): List<ToolSpec> {
    return ToolsBuilder().apply(block).build()
}
