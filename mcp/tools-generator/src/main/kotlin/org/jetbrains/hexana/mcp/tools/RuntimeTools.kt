package org.jetbrains.hexana.mcp.tools

private const val RUNTIME_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.runtime"

val ProposalEvidenceResultClass = class_("ProposalEvidenceResult") {
    string("featureFlag").describe("Enabled feature flag that required the proposal.")
    list("sections", of = string()).describe("Module sections that contributed evidence for the proposal.")
    list("instructions", of = string()).describe("Instructions that contributed evidence for the proposal.")
    list("customSections", of = string()).describe("Feature-related custom sections found in the module.")
    list("notes", of = string()).describe("Short conservative interpretation notes for the evidence.")
}

val RequiredProposalResultClass = class_("RequiredProposalResult") {
    string("proposal").describe("WebAssembly proposal name.")
    boolean("required").describe("True when the proposal is required by detected module features.")
    embed("evidence", of = ProposalEvidenceResultClass).describe("Evidence that made the proposal required.")
}

val RequiredProposalsSummaryResultClass = class_("RequiredProposalsSummaryResult") {
    int("requiredCount").describe("Number of required proposals.")
    list("proposals", of = string()).describe("Required proposal names.")
}

val ListRequiredProposalsResultClass = class_(
    "ListRequiredProposalsResult",
    kotlinPackage = RUNTIME_MODEL_PACKAGE
) {
    list("proposals", of = RequiredProposalResultClass).describe("Required WebAssembly proposals with evidence.")
    embed("summary", of = RequiredProposalsSummaryResultClass).describe("Compact proposal count and name summary.")
}

val RuntimeProposalStatusResultClass = class_("RuntimeProposalStatusResult") {
    string("proposal").describe("Required WebAssembly proposal name.")
    string("status").describe("Compatibility status: `supported`, `unsupported`, or `unknown`.")
    string("note").describe("Conservative runtime-support note for this proposal.")
    embed("evidence", of = ProposalEvidenceResultClass).describe("Evidence that made the proposal required.")
}

val CheckRuntimeCompatibilityResultClass = class_(
    "CheckRuntimeCompatibilityResult",
    kotlinPackage = RUNTIME_MODEL_PACKAGE
) {
    string("runtime").describe("Normalized runtime name.")
    nullable("version", of = string()).describe("Optional caller-provided runtime version.")
    boolean("compatible").describe("True only when every required proposal is marked `supported`.")
    list("requiredProposals", of = string()).describe("Required proposal names.")
    list("unsupportedProposals", of = string()).describe("Required proposals marked `unsupported` for the runtime.")
    list("unknownProposals", of = string()).describe("Required proposals with unknown, version-gated, or environment-gated support.")
    list("proposalStatuses", of = RuntimeProposalStatusResultClass)
        .describe("Per-required-proposal runtime support status and evidence.")
    list("evidence", of = RequiredProposalResultClass).describe("Required proposal evidence reused for compatibility reasoning.")
    list("notes", of = string()).describe("Top-level conservative compatibility notes.")
}

val RuntimeCompatibilityInputClass = WasmInputClass.with("RuntimeCompatibilityInput") {
    string("runtime")
        .describe("Runtime to check: `graalwasm`, `wasmtime`, `v8`, `node`, or `browser`.")
    nullable("version", of = string())
        .describe("Optional runtime version label. Reported for context; exact version parsing is not attempted.")
}

internal val RuntimeTools: List<ToolSpec> = tools {
    tool("list_required_proposals") {
        summary = "List required WebAssembly proposals inferred from detected module features."
        guidance = """
            Derive compact required WebAssembly proposals from the same detector used by `list_enabled_features`.
            Each required proposal includes the source feature flag plus section, instruction, and custom-section evidence when available.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = ListRequiredProposalsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readRequiredProposals"
    }

    tool("check_runtime_compatibility") {
        summary = "Check a WebAssembly module's required proposals against a conservative runtime support profile."
        guidance = """
            Compare required proposals against a small conservative support matrix for `graalwasm`, `wasmtime`, `v8`, `node`, and `browser`.
            Treat `unknown` as not confirmed compatible; exact runtime version parsing and external support lookups are intentionally avoided.
        """
        input = RuntimeCompatibilityInputClass
        inputPattern = ToolInputPattern.RuntimeCompatibility
        output = CheckRuntimeCompatibilityResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readRuntimeCompatibility"
    }
}
