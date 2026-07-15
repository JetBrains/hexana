package org.jetbrains.hexana.cli.scripting.consumer

import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.scripting.RunScriptCliRequest
import org.jetbrains.hexana.cli.scripting.runScriptCli
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits

fun main() {
    val output = runScriptCli(
        RunScriptCliRequest(
            sourceSelections = listOf(
                ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "binary")
            ),
            params = mapOf("script" to JsonPrimitive("print(binary.bytes.size);")),
            format = ToolOutputFormat.RAW,
            limits = HexanaScriptLimits(timeoutMillis = 1_000),
            allowedFilePolicy = AllowedFilePolicy.allowAll,
        )
    )

    check(output.stdout == "3") { "Expected script stdout '3', got '${output.stdout}'." }
    check(output.stderr == "") { "Expected empty script stderr, got '${output.stderr}'." }
    check(output.exitCode == 0) { "Expected exit code 0, got ${output.exitCode}." }
}
