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
    string("detail", default = "compact")
        .describe("Result detail mode: `compact` returns diff-first report facts and summaries; `full` also returns unchanged allowed globals as full objects.")
        .maxLength(16)
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

val GlobalStatePolicyDetailResultClass = class_("GlobalStatePolicyDetailResult") {
    string("mode").describe("Effective detail mode: `compact` or `full`.")
    int("compactPayloadBudgetEstimatedTokens").describe("Documented compact-mode payload budget for this production tool.")
    int("allowedPublicGlobalsReturned").describe("Number of allowed public globals returned as full objects.")
    int("allowedPublicGlobalsTotal").describe("Total allowed public globals considered by the audit.")
    int("evidenceReturned").describe("Number of evidence records returned.")
    int("evidenceTotal").describe("Total evidence records available in `full` mode.")
}

val GlobalStatePolicyAllowedGlobalsSummaryResultClass = class_("GlobalStatePolicyAllowedGlobalsSummaryResult") {
    int("count").describe("Total allowed public global export names.")
    list("names", of = string()).describe("Allowed public global export names as compact strings.")
    int("returnedAsObjects").describe("Number of allowed globals included in `allowedPublicGlobals` as full objects.")
    int("omittedObjectCount").describe("Number of allowed globals summarized instead of returned as full objects.")
}

val GlobalStatePolicyUnchangedGlobalsSummaryResultClass = class_("GlobalStatePolicyUnchangedGlobalsSummaryResult") {
    int("currentGlobalCount").describe("Total imported and local globals in the current module.")
    int("importedGlobalCount").describe("Imported global count in the current module.")
    int("localGlobalCount").describe("Local global count in the current module.")
    int("unchangedPublicGlobalCount").describe("Public globals present in both baseline and current modules.")
    list("unchangedPublicGlobalNames", of = string()).describe("Unchanged public global export names summarized as strings.")
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

val GlobalStatePolicyReportEvidenceResultClass = class_("GlobalStatePolicyReportEvidenceResult") {
    nullable("comparedTo", string()).serialName("compared_to").describe("Baseline module path for the final report.")
    list("targetedGlobalIndices", of = int()).serialName("targeted_global_indices")
        .describe("Report-ready targeted global indices sorted ascending.")
    nullable("logEventId", string()).serialName("log_event_id").describe("Relevant runtime log event id, when available.")
}

val GlobalStatePolicyReportViolationResultClass = class_("GlobalStatePolicyReportViolationResult") {
    string("kind").describe("Report violation kind.")
    int("globalidx").describe("Global index involved in the report violation.")
    nullable("importName", string()).serialName("import").describe("Qualified import name such as `env.clock_offset`.")
    string("type").describe("Global value type.")
    boolean("mutable").describe("True when the global is mutable.")
}

val GlobalStatePolicyReportGlobalResultClass = class_("GlobalStatePolicyReportGlobalResult") {
    string("export").describe("Public global export name.")
    int("globalidx").describe("Global index for the public global.")
    string("origin").describe("Global origin: `import` or `local`.")
    string("type").describe("Global value type.")
    boolean("mutable").describe("True when the global is mutable.")
    nullable("initExpr", string()).serialName("init_expr").describe("Initialization expression in WAT syntax, or null for imports.")
}

val GlobalStatePolicyReportResultClass = class_("GlobalStatePolicyReportResult") {
    nullable("policyId", string()).serialName("policy_id").describe("Policy identifier from the policy file.")
    nullable("module", string()).describe("Current module path for the final report.")
    list("violations", of = GlobalStatePolicyReportViolationResultClass).describe("Report-ready blocking violations.")
    list("newPublicGlobals", of = GlobalStatePolicyReportGlobalResultClass).serialName("new_public_globals")
        .describe("Report-ready new public globals sorted by export name.")
    list("allowedPublicGlobals", of = string()).serialName("allowed_public_globals")
        .describe("Allowed public global names for the final report.")
    embed("evidence", of = GlobalStatePolicyReportEvidenceResultClass)
        .describe("Report-ready evidence object with baseline path, targeted indices, and log event id.")
}

val GlobalStatePolicyRecommendedReportResultClass = class_("GlobalStatePolicyRecommendedReportResult") {
    nullable("policyId", string()).serialName("policy_id").describe("Policy identifier from the policy file.")
    nullable("module", string()).describe("Current module path for the final report.")
    nullable("comparedTo", string()).serialName("compared_to").describe("Baseline module path parsed from the log or echoed from input.")
    nullable("logEventId", string()).serialName("log_event_id").describe("Relevant runtime log event id, when available.")
    list("violations", of = GlobalStatePolicyReportViolationResultClass).describe("Report-ready blocking violations.")
    list("newPublicGlobals", of = GlobalStatePolicyReportGlobalResultClass).serialName("new_public_globals")
        .describe("Report-ready new public globals sorted by export name.")
    list("allowedPublicGlobals", of = string()).serialName("allowed_public_globals")
        .describe("Allowed public global names for the final report.")
    list("targetedGlobalIndices", of = int()).serialName("targeted_global_indices")
        .describe("Report-ready targeted global indices sorted ascending.")
    string("recommendation").describe("Concise release recommendation/remediation text.")
}

val AuditGlobalStatePolicyResultClass = class_("AuditGlobalStatePolicyResult", kotlinPackage = MODEL_PACKAGE) {
    embed("module", of = GlobalStatePolicyModuleIdentityClass).describe("Exact input path identity for report-ready global-state policy output.")
    nullable("policyId", string()).describe("Policy identifier from the policy file, when present.")
    embed("detail", of = GlobalStatePolicyDetailResultClass).describe("Effective detail mode and compact payload budget facts.")
    list("globals", of = GlobalStatePolicyGlobalResultClass).describe("Targeted globals relevant to the audit in stable `globalidx` order.")
    list("newPublicGlobals", of = GlobalStatePolicyGlobalResultClass).describe("Public global exports added relative to the baseline in stable `globalidx` order.")
    list("allowedPublicGlobals", of = GlobalStatePolicyGlobalResultClass).describe("Allowed public global objects returned for the selected `detail` mode.")
    embed("allowedPublicGlobalSummary", of = GlobalStatePolicyAllowedGlobalsSummaryResultClass)
        .describe("Compact allowed-public-global summary; use this by default instead of full unchanged objects.")
    embed("unchangedGlobalSummary", of = GlobalStatePolicyUnchangedGlobalsSummaryResultClass)
        .describe("Compact unchanged global inventory summary.")
    list("requestLimits", of = GlobalStatePolicyRequestLimitResultClass).describe("Request-limit public globals evaluated against policy limits in stable `globalidx` order.")
    list("violations", of = GlobalStatePolicyViolationResultClass).describe("Report-ready policy violations in stable `globalidx` and violation order.")
    list("targetedGlobalIndices", of = int()).describe("Stable sorted `globalidx` values relevant to the audit.")
    list("evidence", of = GlobalStatePolicyEvidenceResultClass).describe("Relevant global evidence returned for the selected `detail` mode.")
    embed("report", of = GlobalStatePolicyReportResultClass)
        .describe("Copy-ready final report JSON with `policy_id`, `module`, `violations`, `new_public_globals`, `allowed_public_globals`, and nested `evidence`.")
    embed("recommendedReport", of = GlobalStatePolicyRecommendedReportResultClass)
        .describe("Legacy report-ready fields plus recommendation text; prefer `report` when the target file expects nested `evidence`.")
    list("warnings", of = string()).describe("Non-fatal input or runtime-log warnings.")
}

internal val GlobalTools: List<ToolSpec> = tools {
    tool("list_globals") {
        summary = "Low-level list of imported and defined globals from a `.wasm` module."
        guidance = """
            List imported and defined globals declared by a WebAssembly (.wasm) module.
            Returns each global with its value type, mutability, normalized initializer expression, import identity, and export names pointing at the global.
            For baseline-plus-policy global-state audits or release reports, use `audit_global_state_policy` instead; this low-level view is intentionally broad and does not compare baseline public globals or policy rules.
        """
        input = WasmInputClass
        output = ListGlobalsResultClass
    }

    tool("audit_global_state_policy") {
        summary = "Audit global-state policy with current/baseline Wasm and policy JSON; returns report-ready violations."
        guidance = """
            Use this as the shortest path for global-state audits that have a current module, `baselinePath`, and `policyPath`.
            Compares current globals with baseline public globals, applies policy rules, and returns exact input path echoes.
            When the target JSON needs `policy_id`, `module`, `violations`, `new_public_globals`, `allowed_public_globals`, and nested `evidence`, call this tool once with optional `logPath` and write the returned `report` object.
            Default `detail=compact` is diff-first: it returns policy violations, added public globals, compact allowed/unchanged summaries, copy-ready `report`, targeted global indices, and remediation/recommendation text.
            Use `detail=full` only when full unchanged allowed-global objects are required.
        """
        input = AuditGlobalStatePolicyInput
        output = AuditGlobalStatePolicyResultClass
    }

    tool("get_globals_for_indices") {
        summary = "Return globals for the requested indices from the unified global index space."
        guidance = """
            Return only the requested globals from the unified global index space.
            Use this for targeted analysis when `list_globals` would return more data than needed.
        """
        input = GetGlobalsForIndicesInput
        output = ListGlobalsResultClass
    }
}
