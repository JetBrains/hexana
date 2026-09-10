package org.jetbrains.hexana.mcp.tools

val ListMemoryToolId = ToolId("list_memory")
val GetMemoryForIndicesToolId = ToolId("get_memory_for_indices")
val CheckMemoryContractToolId = ToolId("check_memory_contract")

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.memory"

val GetMemoryForIndicesInput = indicesInput(
    "GetMemoryForIndicesInput",
    "List of memory indices (`memidx`) to return.",
)

val CheckMemoryContractInput = WasmInputClass.with("CheckMemoryContractInput") {
    string("baselinePath").describe("Path to the baseline `.wasm` file to compare against the current module.")
        .maxLength(4096)
    string("policyPath").describe("Path to a memory policy JSON file with module-wide limits and optional per-memory overrides.")
        .maxLength(4096)
    nullable("logPath", of = string()).describe("Optional runtime log path to validate as readable input and attach log-related warnings.")
        .maxLength(4096)
}

val MemoryLimitsResultClass = class_("MemoryLimitsResult") {
    string("addressType").describe("Address space type (`i32` or `i64`).")
    long("min").describe("Minimum number of pages.")
    nullable("max", long()).describe("Maximum number of pages, when the memory is bounded.")
    boolean("hasMax").describe("True when an explicit upper bound was declared.")
    string("range").describe("Human-readable range such as `1..ε`.")
    string("flag").describe("Raw limits flag byte as hex (`0x00`, `0x01`, etc.).")
}

val MemoryImportResultClass = class_("MemoryImportResult") {
    string("module").describe("Import module name.")
    string("name").describe("Import field name.")
}

val MemoryResultClass = class_("MemoryResult") {
    int("memoryIdx").describe("Index in the unified memory index space.")
    embed("limit", of = MemoryLimitsResultClass).describe("Page limits for the memory.")
    nullable("importInfo", of = MemoryImportResultClass).serialName("import")
        .describe("Import descriptor when the memory is imported.")
    string("definedIn").describe("Section the memory is declared in.")
    list("exportedAs", of = string()).describe("Memory export names that point at this memory index.")
}

val ListMemoryResultClass = class_("ListMemoryResult", kotlinPackage = MODEL_PACKAGE) {
    list("memories", of = MemoryResultClass).describe("Imported and defined memories.")
}

val MemoryContractPolicyLimitsResultClass = class_("MemoryContractPolicyLimitsResult") {
    nullable("maxMin", of = long()).describe("Maximum allowed declared minimum pages.")
    nullable("maxMax", of = long()).describe("Maximum allowed declared maximum pages.")
    nullable("requireMax", of = boolean()).describe("True when an explicit maximum must be declared.")
}

val MemoryContractCompatibleLimitsResultClass = class_("MemoryContractCompatibleLimitsResult") {
    string("addressType").describe("Address space type (`i32` or `i64`).")
    long("min").describe("Minimum pages compatible with current and baseline declarations.")
    nullable("max", of = long()).describe("Maximum pages compatible with current and baseline declarations, when bounded.")
    boolean("hasMax").describe("True when the compatible limits include an explicit maximum.")
    string("range").describe("Human-readable compatible range such as `24..48`.")
}

val MemoryContractModuleIdentityClass = class_("MemoryContractModuleIdentity") {
    nullable("path", of = string()).describe("Exact current module path echoed from input `path`, when available.")
    nullable("baselinePath", of = string()).describe("Exact baseline module path echoed from input `baselinePath`, when available.")
    nullable("policyPath", of = string()).describe("Exact memory policy path echoed from input `policyPath`, when available.")
    nullable("logPath", of = string()).describe("Exact runtime log path echoed from input `logPath`, when provided.")
}

val MemoryContractViolationResultClass = class_("MemoryContractViolationResult") {
    int("memoryIdx").describe("Memory index whose contract check produced this violation.")
    list("exportedAs", of = string()).describe("Memory export names that point at this memory index.")
    string("kind").describe("Stable violation code, for example `min_increased`, `max_decreased`, or `policy_max_exceeded`.")
    string("field").describe("Limit field involved in the violation: `min`, `max`, or `hasMax`.")
    nullable("previous", of = long()).describe("Previous baseline value for diff violations.")
    nullable("declared", of = long()).describe("Current declared value.")
    nullable("policy", of = long()).describe("Policy value involved in the violation.")
    string("message").describe("Report-ready explanation of the violation.")
}

val MemoryContractEntryResultClass = class_("MemoryContractEntryResult") {
    int("memoryIdx").describe("Index in the unified memory index space.")
    list("exportedAs", of = string()).describe("Memory export names that point at this memory index.")
    embed("declaredLimits", of = MemoryLimitsResultClass).describe("Current declared memory limits.")
    nullable("previousLimits", of = MemoryLimitsResultClass).describe("Baseline memory limits for the same index, when available.")
    embed("policyLimits", of = MemoryContractPolicyLimitsResultClass).describe("Effective policy limits after per-memory overrides.")
    list("violations", of = MemoryContractViolationResultClass).describe("Limit changes and policy violations for this memory.")
    embed("minimalCompatibleLimits", of = MemoryContractCompatibleLimitsResultClass)
        .describe("Smallest limits compatible with the current and baseline declarations.")
}

val CheckMemoryContractResultClass = class_("CheckMemoryContractResult", kotlinPackage = MODEL_PACKAGE) {
    embed("module", of = MemoryContractModuleIdentityClass).describe("Exact input path identity for report-ready memory contract output.")
    list("memories", of = MemoryContractEntryResultClass)
        .describe("Per-memory contract report entries in stable `memoryIdx` order.")
    list("violations", of = MemoryContractViolationResultClass)
        .describe("Flattened contract violations in stable `memoryIdx` and violation order.")
    list("warnings", of = string()).describe("Non-fatal input or runtime-log warnings.")
}

internal val MemoryTools: List<ToolSpec> = tools {
    tool(ListMemoryToolId) {
        summary = "Low-level list of imported and defined memories from a `.wasm` module."
        guidance = """
            List imported and defined memories declared by a WebAssembly (.wasm) module.
            Returns memory indices together with decoded WebAssembly limits.
            For baseline-plus-policy memory contract audits, use `check_memory_contract`; this low-level view does not compare baseline declarations or policy limits.
        """
        input = WasmInputClass
        output = ListMemoryResultClass
    }

    tool(GetMemoryForIndicesToolId) {
        summary = "Return memories for the requested indices from the unified memory index space."
        guidance = """
            Return only the requested memories from the unified memory index space.
            Useful for multi-memory or wasm64 modules when only a subset matters.
        """
        input = GetMemoryForIndicesInput
        output = ListMemoryResultClass
    }

    tool(CheckMemoryContractToolId) {
        summary = "Check a memory contract with current/baseline Wasm and policy JSON; returns report-ready violations."
        guidance = """
            Use this as the shortest path for memory contract audits that have a current module, `baselinePath`, and `policyPath`.
            Compares current declarations with baseline declarations, applies policy limits, and returns exact input path echoes.
            The result includes per-memory declared limits, previous limits, exported memory names, policy limits, stable structured violations, and minimal compatible limits.
        """
        input = CheckMemoryContractInput
        output = CheckMemoryContractResultClass
    }
}
