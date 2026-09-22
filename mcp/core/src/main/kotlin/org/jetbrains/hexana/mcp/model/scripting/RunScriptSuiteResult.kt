package org.jetbrains.hexana.mcp.model.scripting

import kotlinx.serialization.Serializable

/**
 * Wire model for the `run_script_suite` tool result (the `hexana-cli-script test`
 * command). Mirrors the catalog contract declared in ScriptingTools.kt; the CLI
 * renders the human report from the suite tree and uses this envelope for the
 * declared semantic contract only.
 */
@Serializable
data class RunScriptSuiteResult(
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val durationMillis: Long,
)
