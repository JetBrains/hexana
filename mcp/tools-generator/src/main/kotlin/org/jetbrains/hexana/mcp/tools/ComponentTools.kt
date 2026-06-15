package org.jetbrains.hexana.mcp.tools

private const val COMPONENT_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.components"

val ComponentSurfaceEntryResultClass = class_("ComponentSurfaceEntryResult") {
    string("name").describe("Full WIT/component name, including version when present.")
    string("baseName").describe("Name without the version suffix.")
    string("version").describe("Version suffix without `@`, or empty when absent.")
    nullable("sort", of = string()).describe("Component sort/index label for exports when available.")
    nullable("externDescription", of = string()).describe("Compact component extern description when available.")
    boolean("runtimeProvided").describe("True when this import is provided by a known runtime namespace.")
}

val ComponentSurfaceSummaryResultClass = class_("ComponentSurfaceSummaryResult") {
    int("importCount").describe("Total component import count before response truncation.")
    int("exportCount").describe("Total component export count before response truncation.")
    int("nonRuntimeImportCount").describe("Imports that require component dependencies.")
    int("runtimeImportCount").describe("Imports supplied by known runtime namespaces.")
}

val ListComponentSurfaceResultClass = class_(
    "ListComponentSurfaceResult",
    kotlinPackage = COMPONENT_MODEL_PACKAGE,
) {
    nullable("path", of = string()).describe("Input path label when the caller provided `path`.")
    boolean("isComponent").describe("True when the input parsed as a WebAssembly component.")
    list("imports", of = ComponentSurfaceEntryResultClass).describe("Component import surface.")
    list("exports", of = ComponentSurfaceEntryResultClass).describe("Component export surface.")
    embed("summary", of = ComponentSurfaceSummaryResultClass).describe("Compact surface counts.")
}

val ComponentDependencyProviderResultClass = class_("ComponentDependencyProviderResult") {
    string("importName").describe("Required import name.")
    string("importBaseName").describe("Required import base name without version.")
    string("importVersion").describe("Required import version without `@`, or empty when absent.")
    string("dependencyPath").describe("Component file that provides a matching export.")
    string("exportName").describe("Matched export name.")
    string("exportVersion").describe("Matched export version without `@`, or empty when absent.")
    boolean("exactVersionMatch").describe("True when versions are absent or equal.")
    boolean("transitive").describe("True when the dependency was required by another resolved dependency.")
}

val ComponentUnresolvedImportResultClass = class_("ComponentUnresolvedImportResult") {
    string("importName").describe("Unresolved import name.")
    string("baseName").describe("Unresolved import base name without version.")
    string("version").describe("Unresolved import version without `@`, or empty when absent.")
    boolean("runtimeProvided").describe("True when the import is known to be runtime-provided.")
}

val ComponentCandidateConflictResultClass = class_("ComponentCandidateConflictResult") {
    string("importName").describe("Import name with more than one matching candidate.")
    string("baseName").describe("Import base name used for matching.")
    list("candidatePaths", of = string()).describe("Candidate component files, capped for compactness.")
    string("reason").describe("Short conflict reason.")
}

val ComponentDependencySummaryResultClass = class_("ComponentDependencySummaryResult") {
    int("directResolvedCount").describe("Direct dependency matches.")
    int("transitiveResolvedCount").describe("Transitive dependency matches.")
    int("unresolvedCount").describe("Unresolved non-runtime imports.")
    int("ambiguousCount").describe("Imports with multiple candidate providers.")
    int("scannedFileCount").describe("Number of `.wasm` files inspected.")
    boolean("truncated").describe("True when bounded scanning stopped before exhausting all files.")
}

val ResolveComponentDependenciesResultClass = class_(
    "ResolveComponentDependenciesResult",
    kotlinPackage = COMPONENT_MODEL_PACKAGE,
) {
    nullable("path", of = string()).describe("Input path label when the caller provided `path`.")
    list("scanRoots", of = string()).describe("Directory roots used for bounded component scanning.")
    list("directDependencies", of = ComponentDependencyProviderResultClass).describe("Resolved direct dependencies.")
    list("transitiveDependencies", of = ComponentDependencyProviderResultClass).describe("Resolved transitive dependencies.")
    list("unresolvedImports", of = ComponentUnresolvedImportResultClass).describe("Non-runtime imports without a provider.")
    list("candidateConflicts", of = ComponentCandidateConflictResultClass).describe("Ambiguous provider candidates.")
    embed("summary", of = ComponentDependencySummaryResultClass).describe("Compact dependency-resolution counts.")
}

val ComponentCompatibilityIssueResultClass = class_("ComponentCompatibilityIssueResult") {
    string("importName").describe("Import involved in the compatibility issue.")
    nullable("dependencyPath", of = string()).describe("Dependency file involved, when there is one.")
    string("severity").describe("Issue severity: `error` or `warning`.")
    string("reason").describe("Stable reason such as `missing_import`, `version_drift`, or `ambiguous_provider`.")
    nullable("expected", of = string()).describe("Expected version or extern description when available.")
    nullable("actual", of = string()).describe("Actual version, provider list, or extern description when available.")
}

val ComponentCompatibilityMatchResultClass = class_("ComponentCompatibilityMatchResult") {
    string("importName").describe("Required import name.")
    string("dependencyPath").describe("Dependency file that provided the import.")
    string("exportName").describe("Matched export name.")
    boolean("exactVersionMatch").describe("True when versions are absent or equal.")
}

val ComponentCompatibilitySummaryResultClass = class_("ComponentCompatibilitySummaryResult") {
    boolean("compatible").describe("True when no compatibility errors were found.")
    int("matchedCount").describe("Number of matched imports.")
    int("missingCount").describe("Number of missing imports.")
    int("versionDriftCount").describe("Number of matches with version drift.")
    int("ambiguousCount").describe("Number of imports with ambiguous providers.")
    int("incompatibleDescriptionCount").describe("Number of incompatible extern-description matches.")
}

val CheckComponentCompatibilityResultClass = class_(
    "CheckComponentCompatibilityResult",
    kotlinPackage = COMPONENT_MODEL_PACKAGE,
) {
    nullable("path", of = string()).describe("Input path label when the caller provided `path`.")
    boolean("compatible").describe("True when no compatibility errors were found.")
    list("matches", of = ComponentCompatibilityMatchResultClass).describe("Matched imports and provider files.")
    list("missingImports", of = ComponentUnresolvedImportResultClass).describe("Required imports without a provider.")
    list("issues", of = ComponentCompatibilityIssueResultClass).describe("Compatibility issues and warnings.")
    embed("summary", of = ComponentCompatibilitySummaryResultClass).describe("Compact compatibility counts.")
}

val ComponentDependencyInputClass = WasmInputClass.with("ComponentDependencyInput") {
    nullable("projectRoot", of = string())
        .describe("Optional project root used as a bounded dependency scan root.")
    nullable("scanRoots", of = list(of = string()))
        .describe("Optional directory roots to scan for dependency `.wasm` components.")
        .maxItems(SchemaIntValue.Literal(8))
}

val ComponentCompatibilityInputClass = WasmInputClass.with("ComponentCompatibilityInput") {
    nullable("dependencyPaths", of = list(of = string()))
        .describe("Optional explicit component dependency files. When omitted, `projectRoot` is scanned.")
        .maxItems(SchemaIntValue.Literal(64))
    nullable("projectRoot", of = string())
        .describe("Optional project root used for compatibility resolution when `dependencyPaths` is omitted.")
}

internal val ComponentTools: List<ToolSpec> = tools {
    tool("list_component_surface") {
        summary = "Inspect WebAssembly component imports and exports with WIT-style names and runtime-import flags."
        guidance = """
            Use this for compact component-model surface inspection before dependency or compatibility reasoning.
            This is an inspection lookup only; it does not select provider files or compute compatibility verdicts.
            Imports and exports include base names, versions, sort or extern descriptions when available, and known runtime-provided imports.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.ComponentSurface
        output = ListComponentSurfaceResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readComponentSurface"
    }

    tool("resolve_component_dependencies") {
        summary = "Resolve direct and transitive component dependencies from scan roots and project roots."
        guidance = """
            Search `scanRoots`, `projectRoot`, and the input component directory for `.wasm` components whose exports satisfy non-runtime imports.
            The result separates direct and transitive dependencies, unresolved imports, and ambiguous provider candidates.
            Use this aggregate dependency-resolution workflow when provider files may need to be discovered rather than supplied explicitly.
        """
        input = ComponentDependencyInputClass
        inputPattern = ToolInputPattern.ComponentDependencies
        output = ResolveComponentDependenciesResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.resolveComponentDependencies"
    }

    tool("check_component_compatibility") {
        summary = "Compute component-provider compatibility verdicts against explicit or resolved dependency exports."
        guidance = """
            Compare non-runtime imports with `dependencyPaths` or with dependencies resolved from `projectRoot`.
            Reports matched provider files, missing imports, version drift, ambiguous providers, and extern-description or sort mismatches when available.
            Use this aggregate compatibility workflow when you need provider verdicts rather than raw component surface listings.
        """
        input = ComponentCompatibilityInputClass
        inputPattern = ToolInputPattern.ComponentCompatibility
        output = CheckComponentCompatibilityResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.checkComponentCompatibility"
    }
}
