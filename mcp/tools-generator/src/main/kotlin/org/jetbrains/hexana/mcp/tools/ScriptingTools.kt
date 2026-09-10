package org.jetbrains.hexana.mcp.tools

private const val SCRIPTING_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.scripting"
val RunBinaryScriptToolId = ToolId("run_binary_script")
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
    list("lines", of = string())
        .describe("Lines emitted by `print(...values)`, `table(...columns)` and `row(...cells)` in order. Table lines are tab-separated.")
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
    tool(RunBinaryScriptToolId) {
        summary = "Run a Hexana JavaScript script over a Wasm or native binary."
        // Keep this in step with the scripting API surface itself, which is
        // defined in `plugins-shared/.../scripting/ScriptBinaryApi.kt` and
        // documented in `ScriptApiReference.kt`. That module is deliberately not
        // a dependency here — this module stays a pure contract catalog — so the
        // member list below is written out by hand.
        guidance = """
            Use this only in the dedicated scripting profile or explicit CLI command when a small custom script is needed over the loaded `binary` object.
            The runtime exposes `binary` plus `print(...values)`, and `table(...columns)`/`row(...cells)` for tab-separated tabular output.
            `binary` is format-agnostic: `format`, `formatName`, `name`, `size`, `bytes`, `structure`, `strings`, `sizeAnalysis`, `tabs`, `tab(name)`, `raw`.
            `binary.tabs` lists every table Hexana renders for the binary and `binary.tab(name)` reads one; `binary.bytes` has `at`, `slice`, `hex`, `ascii`, `u8`/`u16`/`u32`, and `indexOf`.
            `binary.raw` is the format-specific provider, and members not listed above resolve against it too.
            It does not expose host classes, file IO, process creation, or native access.
            Pass exactly one binary source and exactly one script source, either `scriptPath` or inline `script`.
        """
        input = RunBinaryScriptInput
        output = RunBinaryScriptResultClass
    }
}
