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

val ComponentDependencyImportResultClass = class_("ComponentDependencyImportResult") {
    string("importName").serialName("name").describe("Required import name.")
    string("baseName").serialName("base_name").describe("Required import base name without version.")
    string("version").describe("Required import version without `@`, or empty when absent.")
    string("classification").describe("Dependency classification: `direct` or `transitive`.")
}

val ComponentIgnoredRuntimeImportResultClass = class_("ComponentIgnoredRuntimeImportResult") {
    string("importName").serialName("name").describe("Ignored runtime import name.")
    string("baseName").serialName("base_name").describe("Ignored runtime import base name without version.")
    string("version").describe("Ignored runtime import version without `@`, or empty when absent.")
    string("classification").describe("Import classification: `direct` or `transitive`.")
    string("reason").describe("Concise reason the import was ignored for dependency resolution.")
}

val ComponentResolvedDependencyPathResultClass = class_("ComponentResolvedDependencyPathResult") {
    string("importName").serialName("import").describe("Required import name resolved by the dependency file.")
    string("dependencyPath").serialName("path").describe("Dependency file that provided the import.")
    string("classification").describe("Dependency classification: `direct` or `transitive`.")
}

val ComponentUnresolvedImportResultClass = class_("ComponentUnresolvedImportResult") {
    string("importName").serialName("name").describe("Unresolved import name.")
    string("baseName").serialName("base_name").describe("Unresolved import base name without version.")
    string("version").describe("Unresolved import version without `@`, or empty when absent.")
    boolean("runtimeProvided").describe("True when the import is known to be runtime-provided.")
}

val ComponentCandidateConflictResultClass = class_("ComponentCandidateConflictResult") {
    string("importName").describe("Import name with more than one matching candidate.")
    string("baseName").describe("Import base name used for matching.")
    list("candidatePaths", of = string()).describe("Candidate component files, capped for compactness.")
    string("reason").describe("Short conflict reason.")
}

val ComponentRejectedCandidateResultClass = class_("ComponentRejectedCandidateResult") {
    string("candidatePath").serialName("path").describe("Scanned candidate file that was not selected as a dependency.")
    string("reason").describe("Concise rejection reason.")
}

val ComponentDependencySummaryResultClass = class_("ComponentDependencySummaryResult") {
    int("directRequiredImportCount").describe("Direct non-runtime imports required by the root component.")
    int("transitiveRequiredImportCount").describe("Non-runtime imports required only by resolved dependencies.")
    int("ignoredRuntimeImportCount").describe("Runtime-provided imports ignored for dependency resolution.")
    int("directResolvedCount").describe("Direct dependency matches.")
    int("transitiveResolvedCount").describe("Transitive dependency matches.")
    int("unresolvedCount").describe("Unresolved non-runtime imports.")
    int("ambiguousCount").describe("Imports with multiple candidate providers.")
    int("rejectedCandidateCount").describe("Scanned candidates rejected from the resolved dependency set.")
    int("scannedFileCount").describe("Number of `.wasm` files inspected.")
    boolean("truncated").describe("True when bounded scanning stopped before exhausting all files.")
}

val ResolveComponentDependenciesResultClass = class_(
    "ResolveComponentDependenciesResult",
    kotlinPackage = COMPONENT_MODEL_PACKAGE,
) {
    nullable("path", of = string()).serialName("component_path")
        .describe("Input path label when the caller provided `path`.")
    list("scanRoots", of = string()).serialName("scan_roots")
        .describe("Directory roots used for bounded component scanning.")
    list("requiredNonRuntimeImports", of = ComponentDependencyImportResultClass)
        .serialName("required_non_runtime_imports")
        .describe("Non-runtime imports required by the root component or resolved dependencies.")
    list("ignoredRuntimeImports", of = ComponentIgnoredRuntimeImportResultClass)
        .serialName("ignored_runtime_imports")
        .describe("Runtime-provided imports ignored during dependency resolution.")
    list("resolvedDependencyPaths", of = ComponentResolvedDependencyPathResultClass)
        .serialName("resolved_dependency_paths")
        .describe("Resolved dependency files with direct/transitive classification.")
    list("directDependencies", of = ComponentDependencyProviderResultClass).serialName("direct_dependencies")
        .describe("Resolved direct dependencies.")
    list("transitiveDependencies", of = ComponentDependencyProviderResultClass).serialName("transitive_dependencies")
        .describe("Resolved transitive dependencies.")
    list("unresolvedImports", of = ComponentUnresolvedImportResultClass).serialName("unresolved_imports")
        .describe("Non-runtime imports without a provider.")
    list("candidateConflicts", of = ComponentCandidateConflictResultClass).serialName("candidate_conflicts")
        .describe("Ambiguous provider candidates.")
    list("rejectedCandidates", of = ComponentRejectedCandidateResultClass)
        .serialName("rejected_candidates")
        .describe("Scanned candidates rejected from the resolved dependency set.")
    string("minimalRunBuildFix").serialName("minimal_run_build_fix")
        .describe("Concise action for wiring resolved dependency files before running the component.")
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

val ComponentCompatibilityFailingImportResultClass = class_("ComponentCompatibilityFailingImportResult") {
    string("importName").describe("Current import selected as the root compatibility failure.")
    string("baseName").describe("Current import base name without version.")
    string("requiredVersion").describe("Current required version without `@`, or empty when absent.")
    nullable("sort", of = string()).describe("Expected provider export sort inferred from the import shape when available.")
    nullable("externDescription", of = string()).describe("Current import extern description when available.")
    nullable("previousImportName", of = string()).describe("Previous import name for the same base interface when available.")
    nullable("previousBaseName", of = string()).describe("Previous import base name when available.")
    nullable("previousVersion", of = string()).describe("Previous import version without `@` when available.")
    boolean("changedSincePrevious").describe("True when the current import differs from the previous component import.")
}

val ComponentCompatibilityReportFailingImportResultClass = class_("ComponentCompatibilityReportFailingImportResult") {
    string("importName").serialName("name").describe("Failing import name.")
    string("baseName").serialName("base_name").describe("Failing import base name.")
    string("requiredVersion").serialName("required_version").describe("Required version without `@`.")
    nullable("previousImportName", of = string()).serialName("previous_import").describe("Previous matching import.")
    nullable("previousBaseName", of = string()).serialName("previous_base_name").describe("Previous import base name.")
    nullable("previousVersion", of = string()).serialName("previous_version").describe("Previous version without `@`.")
    boolean("changedSincePrevious").serialName("changed_since_previous").describe("True when the current import differs from the previous component import.")
}

val ComponentConfiguredProviderResultClass = class_("ComponentConfiguredProviderResult") {
    string("interfaceName").describe("Provider interface/base name from dependency metadata.")
    string("version").describe("Configured provider version without `@`, or empty when absent.")
    string("path").describe("Configured provider path from dependency metadata.")
}

val ComponentProviderVerdictResultClass = class_("ComponentProviderVerdictResult") {
    string("dependencyPath").describe("Scanned provider component file.")
    string("exportName").describe("Provider export name.")
    string("baseName").describe("Provider export base name without version.")
    string("version").describe("Provider export version without `@`, or empty when absent.")
    string("sort").describe("Provider export sort label.")
    nullable("externDescription", of = string()).describe("Provider export extern description when available.")
    string("verdict").describe("Provider verdict: `compatible`, `version_drift`, `sort_mismatch`, `extern_description_mismatch`, or `wrong_interface`.")
}

val ComponentCandidateProviderInspectedResultClass = class_("ComponentCandidateProviderInspectedResult") {
    string("dependencyPath").serialName("path").describe("Provider component path.")
    string("exportName").serialName("export").describe("Provider export name.")
    string("baseName").serialName("base_name").describe("Provider export base name.")
    string("version").describe("Provider export version.")
    string("sort").describe("Provider export sort.")
    nullable("externDescription", of = string()).serialName("extern_description").describe("Provider export shape.")
    string("verdict").describe("Compatibility verdict.")
}

val ComponentDependencyLockFixResultClass = class_("ComponentDependencyLockFixResult") {
    nullable("dependencyLockPath", of = string()).describe("Dependency lock/config path to update when provided.")
    nullable("replaceProviderPath", of = string()).describe("Configured provider path that should be replaced when known.")
    string("requiredInterface").describe("Required provider interface/base name.")
    string("requiredVersion").describe("Required provider version without `@`, or empty when absent.")
    string("requiredShape").describe("Required provider export shape.")
    nullable("rejectProviderPath", of = string()).describe("Candidate provider path that has the wrong version or shape when known.")
}

val ComponentReportDependencyLockFixResultClass = class_("ComponentReportDependencyLockFixResult") {
    nullable("replaceProviderPath", of = string()).serialName("replace_provider_path").describe("Configured provider path to replace.")
    string("requiredInterface").serialName("required_interface").describe("Required provider interface.")
    string("requiredVersion").serialName("required_version").describe("Required provider version.")
    string("requiredShape").serialName("required_shape").describe("Required provider shape.")
    nullable("rejectProviderPath", of = string()).serialName("reject_provider_path").describe("Rejected wrong-version or wrong-shape provider.")
}

val ComponentCompatibilityEvidenceResultClass = class_("ComponentCompatibilityEvidenceResult") {
    string("summary").describe("Prose summary of the failing import and provider evidence.")
}

val ComponentCompatibilitySummaryResultClass = class_("ComponentCompatibilitySummaryResult") {
    boolean("compatible").describe("True when no compatibility errors were found.")
    int("matchedCount").describe("Number of matched imports.")
    int("missingCount").describe("Number of missing imports.")
    int("versionDriftCount").describe("Number of matches with version drift.")
    int("sortMismatchCount").describe("Number of providers with sort or shape mismatches.")
    int("wrongInterfaceCount").describe("Number of scanned providers exporting unrelated interfaces.")
    int("ambiguousCount").describe("Number of imports with ambiguous providers.")
    int("incompatibleDescriptionCount").describe("Number of incompatible extern-description matches.")
    int("candidateProviderCount").describe("Number of candidate provider exports inspected.")
}

val CheckComponentCompatibilityResultClass = class_(
    "CheckComponentCompatibilityResult",
    kotlinPackage = COMPONENT_MODEL_PACKAGE,
) {
    nullable("componentPath", of = string()).serialName("component_path").describe("Current component path.")
    nullable("previousComponentPath", of = string()).serialName("previous_component_path").describe("Previous component path.")
    nullable("dependencyLockPathForReport", of = string()).serialName("dependency_lock_path").describe("Dependency lock/config path.")
    boolean("compatible").describe("True when no compatibility errors were found.")
    list("matches", of = ComponentCompatibilityMatchResultClass).describe("Matched imports and provider files.")
    list("missingImports", of = ComponentUnresolvedImportResultClass).describe("Required imports without a provider.")
    list("issues", of = ComponentCompatibilityIssueResultClass).describe("Compatibility issues and warnings.")
    nullable("failingImportForReport", of = ComponentCompatibilityReportFailingImportResultClass).serialName("failing_import").describe("Report-ready failing import.")
    list("configuredProviders", of = ComponentConfiguredProviderResultClass).describe("Provider entries read from optional dependency metadata.")
    list("candidateProvidersInspected", of = ComponentCandidateProviderInspectedResultClass).serialName("candidate_providers_inspected").describe("Report-ready provider verdicts.")
    nullable("selectedIncompatibilityReasonForReport", of = string()).serialName("selected_incompatibility_reason").describe("Report-ready incompatibility reason.")
    list("providerPathsInvolvedForReport", of = string()).serialName("provider_paths_involved").describe("Report-ready provider paths for the fix.")
    nullable("minimalDependencyLockFixForReport", of = ComponentReportDependencyLockFixResultClass).serialName("minimal_dependency_lock_fix").describe("Report-ready lock/config fix.")
    nullable("evidence", of = ComponentCompatibilityEvidenceResultClass).describe("Report-ready evidence summary.")
    nullable("recommendation", of = string()).describe("Concise production-oriented remediation recommendation when available.")
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
    nullable("previousPath", of = string())
        .describe("Optional previous component path used to explain import drift.")
    nullable("dependencyLockPath", of = string())
        .describe("Optional dependency lock/config JSON path used to report configured providers and lock fixes.")
}

internal val ComponentTools: List<ToolSpec> = tools {
    tool("list_component_surface") {
        summary = "Low-level component import/export listing; not for dependency/provider resolution."
        surfaces = advancedHiddenCliSurface()
        guidance = """
            Use this only for compact component-model surface inspection when provider selection is not needed.
            For component run/link failures, project-root scans, or dependency resolution across candidate component files, call `resolve_component_dependencies` instead.
            For previous/current component drift, compose failure logs, provider-candidate compatibility, or lock/config repair, call `check_component_compatibility` instead.
            This low-level surface view does not select providers, follow transitive imports, reject candidates, ignore runtime imports for dependency decisions, or compute compatibility verdicts.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.ComponentSurface
        output = ListComponentSurfaceResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readComponentSurface"
    }

    tool("resolve_component_dependencies") {
        summary = "Resolve component run/link dependencies from a main component and candidate roots into report-ready fields."
        surfaces = advancedHiddenCliSurface()
        guidance = """
            Search `scanRoots`, `projectRoot`, and the input component directory for component providers of non-runtime imports.
            Use this aggregate dependency-resolution workflow first when a component run/build/link step has a main component plus project root or candidate directories.
            This is the shortest path when provider files must be discovered, transitive imports must be followed, runtime imports ignored, rejected candidates explained, and a minimal run/build fix reported.
            Prefer this over `list_component_surface`, `list_imports`, or `list_exports` for component provider selection because it performs the scan and returns the final dependency report fields directly.
            Returns report-ready `required_non_runtime_imports`, `ignored_runtime_imports`, `resolved_dependency_paths`, `rejected_candidates`, and `minimal_run_build_fix` fields.
        """
        input = ComponentDependencyInputClass
        inputPattern = ToolInputPattern.ComponentDependencies
        output = ResolveComponentDependenciesResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.resolveComponentDependencies"
    }

    tool("check_component_compatibility") {
        summary = "Compare current/previous component imports against provider candidates and lock/config files."
        surfaces = publicMcpbProductionCliSurface("check-component-compatibility", ToolGroup.Component, ToolOutputPolicy.ToolDefined)
        guidance = """
            Use this first for component compatibility drift, compose failure logs, provider candidate triage, and dependency lock/config repair.
            Compare current non-runtime imports with `dependencyPaths` or providers discovered from `projectRoot`, then use `previousPath` to explain import drift and `dependencyLockPath` to identify the configured provider to replace.
            Returns report-ready `failing_import`, `candidate_providers_inspected`, `provider_paths_involved`, `minimal_dependency_lock_fix`, `evidence`, and `recommendation` fields.
        """
        input = ComponentCompatibilityInputClass
        inputPattern = ToolInputPattern.ComponentCompatibility
        output = CheckComponentCompatibilityResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.checkComponentCompatibility"
    }
}
