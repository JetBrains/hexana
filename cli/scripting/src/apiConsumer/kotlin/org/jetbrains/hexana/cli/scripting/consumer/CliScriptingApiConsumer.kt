package org.jetbrains.hexana.cli.scripting.consumer

import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.scripting.RunScriptCliRequest
import org.jetbrains.hexana.cli.scripting.runScriptCli
import org.jetbrains.hexana.mcp.AllowedFilePolicy

fun main() {
    val output = runScriptCli(
        RunScriptCliRequest(
            sourceSelections = listOf(
                ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "binary")
            ),
            params = mapOf("script" to JsonPrimitive("print(binary.bytes.size);")),
            format = ToolOutputFormat.RAW,
            allowedFilePolicy = AllowedFilePolicy.allowAll,
        )
    )

    check(output.stdout == "3" && output.stderr == "" && output.exitCode == 0) {
        "Expected stdout '3', empty stderr, and exit code 0; got " +
            "stdout='${output.stdout}', stderr='${output.stderr}', exitCode=${output.exitCode}."
    }
}
