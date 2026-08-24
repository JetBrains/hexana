package org.jetbrains.hexana.ui.panel

import org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnosticSeverity
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaScriptRunner
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSource
import org.jetbrains.hexana.providers.BinaryProvider

/**
 * Runs a script tab's `.js` file over the open binary, streaming each output line
 * to [execute]'s callback as it is produced.
 *
 * Execution is delegated to `mcp:scripting`, the same runtime the `run-script`
 * CLI and the MCP tool use, so a script written for a tab behaves identically
 * everywhere. That shared runtime also owns the sandbox: no host classes, no file
 * IO, no process or thread creation, and a timeout. This tab used to build its
 * own GraalJS context with `HostAccess.ALL` and host class lookup enabled, which
 * meant a `.js` file dropped into a script tab could call arbitrary Java.
 */
internal object ScriptExecutor {

    fun execute(
        scriptPath: String,
        binaryProvider: BinaryProvider,
        fileName: String? = null,
        onLine: (String) -> Unit,
    ) {
        val result = HexanaScriptRunner(limits = HexanaScriptLimits.interactive()).run(
            source = HexanaScriptSource.FilePath(scriptPath),
            binaryProvider = binaryProvider,
            fileName = fileName,
            onLine = onLine,
        )

        for (diagnostic in result.diagnostics) {
            val prefix = when (diagnostic.severity) {
                HexanaScriptDiagnosticSeverity.ERROR -> "[Error]"
                HexanaScriptDiagnosticSeverity.WARNING -> "[Warning]"
            }
            onLine("$prefix ${diagnostic.location()}${diagnostic.message}")
        }
    }

    private fun org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnostic.location(): String {
        val line = line ?: return ""
        val column = column
        return if (column != null) "$sourceName:$line:$column — " else "$sourceName:$line — "
    }
}
