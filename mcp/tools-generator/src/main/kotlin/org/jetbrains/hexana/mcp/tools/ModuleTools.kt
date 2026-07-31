package org.jetbrains.hexana.mcp.tools

private const val SUMMARY_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.summary"
private const val SECTIONS_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.sections"
private const val FEATURES_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.features"

val SummarizeModuleImportsClass = class_("SummarizeModuleImports") {
    int("functions").describe("Number of imported functions.")
    int("memories").describe("Number of imported memories.")
    int("tables").describe("Number of imported tables.")
    int("globals").describe("Number of imported globals.")
}

val SummarizeModuleResultClass = class_("SummarizeModuleResult", kotlinPackage = SUMMARY_MODEL_PACKAGE) {
    outputCapabilities(
        OutputConversionCapability.SemanticJson,
        OutputConversionCapability.McpWrapper,
        OutputConversionCapability.Pretty,
        OutputConversionCapability.Table,
    )
    int("functions").describe("Total function count (imports + defined).")
    int("exports").describe("Number of exports declared by the module.")
    int("memories").describe("Number of memories declared by the module.")
    int("globals").describe("Number of globals declared by the module.")
    int("tables").describe("Number of tables declared by the module.")
    int("types").describe("Number of type entries declared by the module.")
    embed("imports", of = SummarizeModuleImportsClass).describe("Import breakdown by kind.")
    int("moduleSize").describe("Size of the module binary in bytes.")
}

val EnabledFeaturesResultClass = class_("EnabledFeaturesResult") {
    boolean("referenceTypes").describe("True when reference types are used.")
    boolean("bulkMemory").describe("True when bulk-memory sections or instructions are used.")
    boolean("multiValue").describe("True when multi-value function results are used.")
    boolean("signExtension").describe("True when sign-extension instructions are used.")
    boolean("simd").describe("True when SIMD value types or instructions are used.")
    boolean("threads").describe("True when atomic/thread instructions are used.")
    boolean("memory64").describe("True when memory64 limits are used.")
    boolean("gcTypes").describe("True when GC types, packed storage, or GC instructions are used.")
    boolean("subtyping").describe("True when type subtyping metadata is present.")
    boolean("functionReferences").describe("True when function-reference instructions such as `ref.func` or `call_ref` are used.")
    boolean("extendedConst").describe("True when extended constant-expression instructions are observed.")
}

val EnabledFeatureSourcesResultClass = class_("EnabledFeatureSourcesResult") {
    list("sections", of = string()).describe("Sections that contributed feature evidence.")
    list("instructions", of = string()).describe("Instructions that contributed feature evidence.")
    list("customSections", of = string()).describe("Feature-related custom sections found in the module.")
}

val ListEnabledFeaturesResultClass = class_("ListEnabledFeaturesResult", kotlinPackage = FEATURES_MODEL_PACKAGE) {
    embed("features", of = EnabledFeaturesResultClass).describe("Enabled or inferred WebAssembly feature flags.")
    embed("sources", of = EnabledFeatureSourcesResultClass).describe("Evidence sources for enabled features.")
}

val CustomSectionResultClass = class_("CustomSectionResult") {
    string("name").describe("Custom section name as encoded in the module.")
    long("offset").describe("Byte offset of the full custom section in the module.")
    long("size").describe("Size of the full custom section in bytes.")
    string("encoding").describe("Preview encoding: `utf8` when the content prefix is printable UTF-8, otherwise `binary`.")
    string("preview").describe("Small content preview, excluding the section name.")
}

val ListCustomSectionsResultClass = class_("ListCustomSectionsResult", kotlinPackage = SECTIONS_MODEL_PACKAGE) {
    list("customSections", of = CustomSectionResultClass)
        .describe("Custom sections found in the module.")
}

internal val ModuleTools: List<ToolSpec> = tools {
    tool("summarize_module") {
        summary = "Summarize a WebAssembly module — counts of imports, exports, memories, globals, functions, types."
        guidance = """
            Summarize a WebAssembly (.wasm) module with high-level counts for its major sections and declarations.
            Includes the nested import breakdown and total module size, making it a low-cost overview before calling heavier tools.
            For broad Wasm analysis requests, this is usually the best first MCP call before choosing more specific import, export, or instruction tools.
            After this overview, prefer `list_imports` for import/link analysis, `list_exported_functions` for export analysis, and `disassemble_function` for targeted body analysis.
        """
        input = WasmInputClass
        output = SummarizeModuleResultClass
    }

    tool("list_enabled_features") {
        summary = "List enabled or inferred WebAssembly feature flags for a `.wasm` module."
        guidance = """
            Infer WebAssembly feature usage from type declarations, section presence, instructions, and feature-related custom sections.
            Use this before choosing specialized tools when syntax or semantics depend on proposals such as reference types, bulk memory, SIMD, threads, memory64, GC, or function references.
        """
        input = WasmInputClass
        output = ListEnabledFeaturesResultClass
    }

    tool("list_custom_sections") {
        summary = "List WebAssembly custom sections with offsets, sizes, and small content previews."
        guidance = """
            List custom sections from a WebAssembly module, including names, full-section byte ranges, and a capped content preview.
            The preview excludes the encoded section name; `encoding` is `utf8` for printable UTF-8 content prefixes and `binary` for hex-rendered byte prefixes.
            Use this before deeper debug, producer, source-map, or CTF-style metadata investigation.
        """
        input = WasmInputClass
        output = ListCustomSectionsResultClass
    }
}
