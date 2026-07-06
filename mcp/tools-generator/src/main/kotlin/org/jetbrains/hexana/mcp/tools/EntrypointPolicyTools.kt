package org.jetbrains.hexana.mcp.tools

private const val ENTRYPOINT_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.entrypoints"

private val EntrypointSignatureResultClass = class_("EntrypointSignatureResult", kotlinPackage = ENTRYPOINT_MODEL_PACKAGE) {
    list("params", of = string()).describe("Exported function parameter types.")
    list("results", of = string()).describe("Exported function result types.")
}

private val EntrypointImportSignatureResultClass = class_("EntrypointImportSignatureResult", kotlinPackage = ENTRYPOINT_MODEL_PACKAGE) {
    list("params", of = string()).describe("Imported function parameter types.")
    list("result", of = string()).serialName("return").describe("Imported function return types.")
}

private val EntrypointCalledImportResultClass = class_("EntrypointCalledImportResult", kotlinPackage = ENTRYPOINT_MODEL_PACKAGE) {
    string("module").describe("Imported module name.")
    string("name").describe("Imported field name.")
    embed("signature", of = EntrypointImportSignatureResultClass).describe("Imported function signature.")
}

private val EntrypointPolicyEntryResultClass = class_("EntrypointPolicyEntryResult", kotlinPackage = ENTRYPOINT_MODEL_PACKAGE) {
    string("export").describe("Export name of the local entrypoint.")
    int("funcidx").describe("Unified function index of the exported local function.")
    embed("signature", of = EntrypointSignatureResultClass).describe("Exported entrypoint function signature.")
    list("calledImports", of = EntrypointCalledImportResultClass)
        .serialName("called_imports")
        .describe("Unique imports directly called by this entrypoint body, sorted by module then name.")
}

private val EntrypointPolicyResultClass = class_("EntrypointPolicyResult", kotlinPackage = ENTRYPOINT_MODEL_PACKAGE) {
    string("policyId").serialName("policy_id").describe("Entrypoint host-call policy identifier.")
    string("module").describe("Module path label echoed from the tool input path.")
    list("directHostEntrypoints", of = EntrypointPolicyEntryResultClass)
        .serialName("direct_host_entrypoints")
        .describe("Policy-ready entries for exported local functions whose own body directly calls imported functions.")
    list("excludedTransitiveExports", of = string())
        .serialName("excluded_transitive_exports")
        .describe("Exports that reach imported functions only through local helper calls and should stay out of the direct policy.")
}

internal val EntrypointPolicyTools: List<ToolSpec> = tools {
    tool("get_entrypoint_policy") {
        summary = "Return policy-ready direct host-call facts for exported WebAssembly entrypoints."
        surfaces = productionCliSurface("get-entrypoint-policy", ToolGroup.EntrypointPolicy, ToolOutputPolicy.ToolDefined)
        guidance = """
            Use this when repairing or validating entrypoint host-call policy JSON.
            It scans exported local functions and returns only imports called directly by each entrypoint body; exports that only reach host imports through local helper calls are listed separately in `excluded_transitive_exports`.
            The complete top-level JSON object is the canonical entrypoint-host-call-policy-v2 payload and is ready to copy unchanged into both the policy file and any matching repair/audit report file.
            Do not wrap, rename, summarize, or split the returned fields when the target file expects `policy_id`, `module`, `direct_host_entrypoints`, and `excluded_transitive_exports`.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmPathLabel
        output = EntrypointPolicyResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readEntrypointPolicy"
    }
}
