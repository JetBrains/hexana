package org.jetbrains.hexana.ui.panel

import org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnosticSeverity
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaScriptRunner
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSession
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSource
import org.jetbrains.hexana.providers.BinaryProvider

/**
 * Result of a session-based script run.
 *
 * If [mode] is [ScriptRenderMode.Legacy] the caller should use the line stream
 * from [legacyLines] to drive the existing block-parsing rendering path, exactly
 * as today. [session] is null in that case (it was already closed).
 *
 * If [mode] is [ScriptRenderMode.Compound] the caller holds a live [session] and
 * should render the compound slice+log view.
 */
internal class ScriptSessionRunResult(
    val mode: ScriptRenderMode,
    val legacyLines: List<String>,
    val session: HexanaScriptSession?,
)

/**
 * Opens a session, streams eval output exactly like [ScriptExecutor.execute] did,
 * and decides the render mode.
 *
 * If the script registered no slices the session is closed immediately and the
 * result carries the legacy line list so that [ScriptTabContent] can pass it to
 * the existing block-parsing path. The timing and the line content are
 * byte-for-byte identical to the old path: diagnostics are formatted the same
 * way ScriptExecutor did (prefix + location + message).
 *
 * If slices are registered the live session is returned in [ScriptSessionRunResult.session]
 * and the caller must close it when done (DisposableEffect onDispose).
 *
 * Must be called on a background thread (Dispatchers.IO). Never on the EDT.
 */
internal fun openScriptSession(
    scriptPath: String,
    binaryProvider: BinaryProvider,
    binaryFileName: String?,
    binaryFilePath: String?,
    onLine: (String) -> Unit,
): ScriptSessionRunResult {
    val runner = HexanaScriptRunner(limits = HexanaScriptLimits.interactive())
    val session = runner.openSession(
        source = HexanaScriptSource.FilePath(scriptPath),
        binaryProvider = binaryProvider,
        fileName = binaryFileName,
        filePath = binaryFilePath,
        onLine = onLine,
    )

    val result = session.initialResult

    // Emit diagnostics to the line stream exactly as ScriptExecutor did.
    for (diagnostic in result.diagnostics) {
        val prefix = when (diagnostic.severity) {
            HexanaScriptDiagnosticSeverity.ERROR -> "[Error]"
            HexanaScriptDiagnosticSeverity.WARNING -> "[Warning]"
        }
        onLine("$prefix ${diagnostic.location()}${diagnostic.message}")
    }

    return if (result.slices.isEmpty()) {
        // Legacy path: no slice API used. Close the session immediately
        // (it was a one-shot run and we consumed the line stream above).
        session.close()
        ScriptSessionRunResult(
            mode = ScriptRenderMode.Legacy,
            legacyLines = result.output.lines,
            session = null,
        )
    } else {
        ScriptSessionRunResult(
            mode = ScriptRenderMode.Compound,
            legacyLines = emptyList(),
            session = session,
        )
    }
}

private fun org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnostic.location(): String {
    val line = line ?: return ""
    val column = column
    return if (column != null) "$sourceName:$line:$column — " else "$sourceName:$line — "
}
