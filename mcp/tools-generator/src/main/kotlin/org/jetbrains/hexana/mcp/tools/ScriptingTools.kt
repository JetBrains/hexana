package org.jetbrains.hexana.mcp.tools

private const val SCRIPTING_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.scripting"
private const val SCRIPT_SOURCE_VALIDATION =
    "Provide exactly one of `scriptPath` or `script`."

private val RunBinaryScriptInput = BinaryInputClass.with("RunBinaryScriptInput") {
    nullable("scriptPath", of = string())
        .describe("Readable local JavaScript file path. $SCRIPT_SOURCE_VALIDATION")
        .maxLength(4096)
    nullable("script", of = string())
        .describe("Inline JavaScript source text. $SCRIPT_SOURCE_VALIDATION")
        .maxLength(1_000_000)
}

private val RunBinaryScriptOutputResultClass = class_("RunBinaryScriptOutputResult", kotlinPackage = SCRIPTING_MODEL_PACKAGE) {
    list("lines", of = string()).describe("Lines emitted by `print(text)` in order.")
    boolean("truncated").describe("Whether output was truncated by configured script output limits.")
    int("emittedBytes").describe("UTF-8 bytes emitted before truncation.")
    int("maxLines").describe("Configured maximum output lines.")
    int("maxBytes").describe("Configured maximum output bytes.")
}

private val RunBinaryScriptDiagnosticResultClass = class_("RunBinaryScriptDiagnosticResult", kotlinPackage = SCRIPTING_MODEL_PACKAGE) {
    string("code").describe("Stable diagnostic code.")
    string("message").describe("Human-readable diagnostic message.")
    string("severity").describe("Diagnostic severity: `ERROR` or `WARNING`.")
    nullable("exceptionClass", of = string()).describe("Runtime exception class when available.")
    nullable("sourceName", of = string()).describe("Script source name associated with the diagnostic.")
    nullable("line", of = int()).describe("One-based source line when available.")
    nullable("column", of = int()).describe("One-based source column when available.")
}

private val RunBinaryScriptResultClass = class_("RunBinaryScriptResult", kotlinPackage = SCRIPTING_MODEL_PACKAGE) {
    outputCapabilities(
        OutputConversionCapability.SemanticJson,
        OutputConversionCapability.McpWrapper,
        OutputConversionCapability.RawText,
    )
    string("status").describe("Script execution status.")
    boolean("success").describe("Whether the script completed successfully.")
    embed("output", of = RunBinaryScriptOutputResultClass).describe("Captured script output.")
    list("diagnostics", of = RunBinaryScriptDiagnosticResultClass).describe("Script diagnostics and output-limit warnings.")
    nullable("binary", of = BinaryModuleIdentityResultClass).describe("Loaded binary identity when available.")
    string("sourceName").describe("Display name of the executed script source.")
    nullable("sourcePath", of = string()).describe("Resolved script path when loaded from a file.")
    long("durationMillis").describe("Execution duration in milliseconds.")
}

internal val ScriptingTools: List<ToolSpec> = tools {
    tool("run_binary_script") {
        summary = "Run a Hexana JavaScript script over a Wasm or native binary."
        surfaces = scriptingCliSurface(
            "run-script",
            ToolGroup.Scripting,
            ToolOutputPolicy.ToolDefined,
            executionStatus = CliExecutionStatus.Executable,
            defaultOutput = OutputConversionCapability.RawText,
            executableRenderer = executableCliRenderer("renderRunBinaryScriptCommandOutput"),
        )
        guidance = """
            Use this only in the dedicated scripting profile or explicit CLI command when a small custom script is needed over the loaded `binary` object.
            The script runtime exposes the existing globals `binary` and `print(text)`; it does not expose host classes, file IO, process creation, or native access.
            Pass exactly one binary source and exactly one script source, either `scriptPath` or inline `script`.
        """
        input = RunBinaryScriptInput
        inputPattern = ToolInputPattern.BinaryOnly
        inputModel = toolInputModel {
            binarySource("binary", target = SourceTargetSpec.RootInput) {
                cli {
                    positional("binary")
                    stdin("--stdin")
                    base64("--bytes-base64")
                    autoStdin()
                }
            }
            param(name = "scriptPath", cliOption = "--script")
            param(name = "script", cliOption = "--script-text")
        }
        output = RunBinaryScriptResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.server.runBinaryScript"
    }
}
