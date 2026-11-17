package org.jetbrains.hexana.mcp

fun Local.renderedMcpType(): String = renderedType().toMcpTypeLabel()

fun String.toMcpTypeLabel(): String {
    return when {
        startsWith("num:") -> removePrefix("num:")
        startsWith("vec:") -> removePrefix("vec:")
        startsWith("pack:") -> removePrefix("pack:")
        startsWith("ref:") -> removePrefix("ref:")
        else -> this
    }
}
