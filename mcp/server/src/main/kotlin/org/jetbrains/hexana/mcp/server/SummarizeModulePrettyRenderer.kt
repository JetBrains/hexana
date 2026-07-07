package org.jetbrains.hexana.mcp.server

import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult

internal fun renderSummarizeModulePretty(result: SummarizeModuleResult): String =
    buildString {
        appendLine("WebAssembly module summary")
        appendLine("Functions: ${result.functions}")
        appendLine("Exports: ${result.exports}")
        appendLine("Memories: ${result.memories}")
        appendLine("Globals: ${result.globals}")
        appendLine("Tables: ${result.tables}")
        appendLine("Types: ${result.types}")
        appendLine("Imports:")
        appendLine("  Functions: ${result.imports.functions}")
        appendLine("  Memories: ${result.imports.memories}")
        appendLine("  Tables: ${result.imports.tables}")
        appendLine("  Globals: ${result.imports.globals}")
        append("Module size: ${result.moduleSize} bytes")
    }
