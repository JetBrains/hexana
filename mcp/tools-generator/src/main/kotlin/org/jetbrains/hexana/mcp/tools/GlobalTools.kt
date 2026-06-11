package org.jetbrains.hexana.mcp.tools

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.globals"

val GetGlobalsForIndicesInput = indicesInput(
    "GetGlobalsForIndicesInput",
    "List of global indices (`globalidx`) to return.",
)

val AuditGlobalStatePolicyInput = WasmInputClass.with("AuditGlobalStatePolicyInput") {
    string("baselinePath").describe("Path to the baseline `.wasm` file to compare against the current module.")
        .maxLength(4096)
    string("policyPath").describe("Path to a global-state policy JSON file with allowed public globals and request-limit rules.")
        .maxLength(4096)
    nullable("logPath", of = string()).describe("Optional runtime log path to validate as readable input and attach log-related warnings.")
        .maxLength(4096)
}

val GlobalResultClass = class_("GlobalResult") {
    int("globalIdx").describe("Global index in the unified global index space.")
    nullable("prefix", string()).describe("Import module prefix, when the global is imported.")
    nullable("name", string()).describe("Import or exported name, when applicable.")
    string("type").describe("Global value type (`i32`, `i64`, `f32`, `f64`, ...).")
    boolean("mutability").describe("True when the global is mutable.")
    nullable("expression", string()).describe("Initialization expression in WAT syntax.")
    string("definedIn").describe("Section the global is declared in.")
    string("origin").describe("Global origin: `import` or `local`.")
    nullable("importModule", string()).describe("Import module name when the global is imported.")
    nullable("importName", string()).describe("Import field name when the global is imported.")
    list("exportedAs", of = string()).describe("Export names pointing at this global.")
}

val ListGlobalsResultClass = class_("ListGlobalsResult", kotlinPackage = MODEL_PACKAGE) {
    list("globals", of = GlobalResultClass).describe("Globals declared or imported by the module.")
}

val GlobalStatePolicyGlobalResultClass = class_("GlobalStatePolicyGlobalResult") {
    nullable("export", string()).describe("Export name when the global is public.")
    nullable("importNameQualified", string()).describe("Qualified import name such as `env.clock_offset`, when imported.")
    int("globalidx").describe("Global index in the unified global index space.")
    string("type").describe("Global value type.")
    boolean("mutable").describe("True when the global is mutable.")
    nullable("initExpr", string()).describe("Initialization expression in WAT syntax.")
    string("origin").describe("Global origin: `import` or `local`.")
    nullable("importModule", string()).describe("Import module name when the global is imported.")
    nullable("importName", string()).describe("Import field name when the global is imported.")
    string("status").describe("Audit status such as `added`, `baseline_public`, `policy_allowed`, or `targeted_mutable_import`.")
    boolean("allowed").describe("True when the policy or baseline allows this public global.")
    nullable("allowedBy", string()).describe("Policy concept that allowed the global, such as `baseline`, `allowed_public_globals`, or `request_limits`.")
}

val GlobalStatePolicyRequestLimitResultClass = class_("GlobalStatePolicyRequestLimitResult") {
    string("export").describe("Request-limit export name.")
    int("globalidx").describe("Global index for the request-limit export.")
    nullable("value", long()).describe("Constant request-limit value, when statically available.")
    nullable("maxValue", long()).describe("Maximum request-limit value from policy, when configured.")
    boolean("allowed").describe("True when the request-limit global satisfies the policy.")
    nullable("violation", string()).describe("Violation code such as `request_limit_exceeded` or `mutable_request_limit`.")
}

val GlobalStatePolicyModuleIdentityClass = class_("GlobalStatePolicyModuleIdentity") {
    nullable("path", of = string()).describe("Exact current module path echoed from input `path`, when available.")
    nullable("baselinePath", of = string()).describe("Exact baseline module path echoed from input `baselinePath`, when available.")
    nullable("policyPath", of = string()).describe("Exact global-state policy path echoed from input `policyPath`, when available.")
    nullable("logPath", of = string()).describe("Exact runtime log path echoed from input `logPath`, when provided.")
}

val GlobalStatePolicyViolationResultClass = class_("GlobalStatePolicyViolationResult") {
    string("kind").describe("Stable violation code.")
    int("globalidx").describe("Global index involved in the violation.")
    nullable("export", string()).describe("Export name involved in the violation, when public.")
    nullable("importNameQualified", string()).describe("Qualified import name involved in the violation, when imported.")
    string("field").describe("Policy field or Wasm property involved in the violation.")
    nullable("observed", string()).describe("Observed value.")
    nullable("policy", string()).describe("Policy value or policy concept.")
    string("message").describe("Report-ready explanation of the violation.")
}

val GlobalStatePolicyEvidenceResultClass = class_("GlobalStatePolicyEvidenceResult") {
    string("kind").describe("Evidence category.")
    int("globalidx").describe("Global index for this evidence item.")
    nullable("export", string()).describe("Export name, when public.")
    nullable("importNameQualified", string()).describe("Qualified import name, when imported.")
    string("type").describe("Global value type.")
    boolean("mutable").describe("True when the global is mutable.")
    nullable("initExpr", string()).describe("Initialization expression in WAT syntax.")
    string("origin").describe("Global origin: `import` or `local`.")
}

val AuditGlobalStatePolicyResultClass = class_("AuditGlobalStatePolicyResult", kotlinPackage = MODEL_PACKAGE) {
    embed("module", of = GlobalStatePolicyModuleIdentityClass).describe("Exact input path identity for report-ready global-state policy output.")
    nullable("policyId", string()).describe("Policy identifier from the policy file, when present.")
    list("globals", of = GlobalStatePolicyGlobalResultClass).describe("Targeted globals relevant to the audit in stable `globalidx` order.")
    list("newPublicGlobals", of = GlobalStatePolicyGlobalResultClass).describe("Public global exports added relative to the baseline in stable `globalidx` order.")
    list("allowedPublicGlobals", of = GlobalStatePolicyGlobalResultClass).describe("Public global exports allowed by the baseline or policy in stable `globalidx` order.")
    list("requestLimits", of = GlobalStatePolicyRequestLimitResultClass).describe("Request-limit public globals evaluated against policy limits in stable `globalidx` order.")
    list("violations", of = GlobalStatePolicyViolationResultClass).describe("Report-ready policy violations in stable `globalidx` and violation order.")
    list("targetedGlobalIndices", of = int()).describe("Stable sorted `globalidx` values relevant to the audit.")
    list("evidence", of = GlobalStatePolicyEvidenceResultClass).describe("Relevant global evidence used for the audit report.")
    list("warnings", of = string()).describe("Non-fatal input or runtime-log warnings.")
}

internal val GlobalTools: List<ToolSpec> = tools {
    tool("list_globals") {
        summary = "Low-level list of imported and defined globals from a `.wasm` module."
        guidance = """
            List imported and defined globals declared by a WebAssembly (.wasm) module.
            Returns each global with its value type, mutability, normalized initializer expression, import identity, and export names pointing at the global.
            For baseline-plus-policy global-state audits, use `audit_global_state_policy`; this low-level view does not compare baseline public globals or policy rules.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = ListGlobalsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readGlobals"
    }

    tool("audit_global_state_policy") {
        summary = "Audit global-state policy with current/baseline Wasm and policy JSON; returns report-ready violations."
        guidance = """
            Use this as the shortest path for global-state audits that have a current module, `baselinePath`, and `policyPath`.
            Compares current globals with baseline public globals, applies policy rules, and returns exact input path echoes.
            The result includes report-ready violations, added public globals, allowed public globals, request-limit facts, targeted global indices, and supporting evidence.
        """
        input = AuditGlobalStatePolicyInput
        inputPattern = ToolInputPattern.PolicyWithBaselineAndLog
        output = AuditGlobalStatePolicyResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.auditGlobalStatePolicy"
    }

    tool("get_globals_for_indices") {
        summary = "Return globals for the requested indices from the unified global index space."
        guidance = """
            Return only the requested globals from the unified global index space.
            Use this for targeted analysis when `list_globals` would return more data than needed.
        """
        input = GetGlobalsForIndicesInput
        inputPattern = ToolInputPattern.Indices
        output = ListGlobalsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readGlobalsByIndices"
    }
}
