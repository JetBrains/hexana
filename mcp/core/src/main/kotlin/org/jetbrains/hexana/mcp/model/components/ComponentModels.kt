package org.jetbrains.hexana.mcp.model.components

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ComponentSurfaceEntryResult(
    val name: String,
    val baseName: String,
    val version: String,
    val sort: String?,
    val externDescription: String?,
    val runtimeProvided: Boolean,
)

@Serializable
data class ComponentSurfaceSummaryResult(
    val importCount: Int,
    val exportCount: Int,
    val nonRuntimeImportCount: Int,
    val runtimeImportCount: Int,
)

@Serializable
data class ListComponentSurfaceResult(
    val path: String?,
    val isComponent: Boolean,
    val imports: List<ComponentSurfaceEntryResult>,
    val exports: List<ComponentSurfaceEntryResult>,
    val summary: ComponentSurfaceSummaryResult,
)

@Serializable
data class ComponentDependencyProviderResult(
    val importName: String,
    val importBaseName: String,
    val importVersion: String,
    val dependencyPath: String,
    val exportName: String,
    val exportVersion: String,
    val exactVersionMatch: Boolean,
    val transitive: Boolean,
)

@Serializable
data class ComponentDependencyImportResult(
    @SerialName("name")
    val importName: String,
    @SerialName("base_name")
    val baseName: String,
    val version: String,
    val classification: String,
)

@Serializable
data class ComponentIgnoredRuntimeImportResult(
    @SerialName("name")
    val importName: String,
    @SerialName("base_name")
    val baseName: String,
    val version: String,
    val classification: String,
    val reason: String,
)

@Serializable
data class ComponentResolvedDependencyPathResult(
    @SerialName("import")
    val importName: String,
    @SerialName("path")
    val dependencyPath: String,
    val classification: String,
)

@Serializable
data class ComponentUnresolvedImportResult(
    @SerialName("name")
    val importName: String,
    @SerialName("base_name")
    val baseName: String,
    val version: String,
    val runtimeProvided: Boolean,
)

@Serializable
data class ComponentCandidateConflictResult(
    val importName: String,
    val baseName: String,
    val candidatePaths: List<String>,
    val reason: String,
)

@Serializable
data class ComponentRejectedCandidateResult(
    @SerialName("path")
    val candidatePath: String,
    val reason: String,
)

@Serializable
data class ComponentDependencySummaryResult(
    val directRequiredImportCount: Int,
    val transitiveRequiredImportCount: Int,
    val ignoredRuntimeImportCount: Int,
    val directResolvedCount: Int,
    val transitiveResolvedCount: Int,
    val unresolvedCount: Int,
    val ambiguousCount: Int,
    val rejectedCandidateCount: Int,
    val scannedFileCount: Int,
    val truncated: Boolean,
)

@Serializable
data class ResolveComponentDependenciesResult(
    @SerialName("component_path")
    val path: String?,
    @SerialName("scan_roots")
    val scanRoots: List<String>,
    @SerialName("required_non_runtime_imports")
    val requiredNonRuntimeImports: List<ComponentDependencyImportResult>,
    @SerialName("ignored_runtime_imports")
    val ignoredRuntimeImports: List<ComponentIgnoredRuntimeImportResult>,
    @SerialName("resolved_dependency_paths")
    val resolvedDependencyPaths: List<ComponentResolvedDependencyPathResult>,
    @SerialName("direct_dependencies")
    val directDependencies: List<ComponentDependencyProviderResult>,
    @SerialName("transitive_dependencies")
    val transitiveDependencies: List<ComponentDependencyProviderResult>,
    @SerialName("unresolved_imports")
    val unresolvedImports: List<ComponentUnresolvedImportResult>,
    @SerialName("candidate_conflicts")
    val candidateConflicts: List<ComponentCandidateConflictResult>,
    @SerialName("rejected_candidates")
    val rejectedCandidates: List<ComponentRejectedCandidateResult>,
    @SerialName("minimal_run_build_fix")
    val minimalRunBuildFix: String,
    val summary: ComponentDependencySummaryResult,
)

@Serializable
data class ComponentCompatibilityIssueResult(
    val importName: String,
    val dependencyPath: String?,
    val severity: String,
    val reason: String,
    val expected: String?,
    val actual: String?,
)

@Serializable
data class ComponentCompatibilityMatchResult(
    val importName: String,
    val dependencyPath: String,
    val exportName: String,
    val exactVersionMatch: Boolean,
)

@Serializable
data class ComponentCompatibilityFailingImportResult(
    val importName: String,
    val baseName: String,
    val requiredVersion: String,
    val sort: String?,
    val externDescription: String?,
    val previousImportName: String?,
    val previousBaseName: String?,
    val previousVersion: String?,
    val changedSincePrevious: Boolean,
)

@Serializable
data class ComponentConfiguredProviderResult(
    val interfaceName: String,
    val version: String,
    val path: String,
)

@Serializable
data class ComponentProviderVerdictResult(
    val dependencyPath: String,
    val exportName: String,
    val baseName: String,
    val version: String,
    val sort: String,
    val externDescription: String?,
    val verdict: String,
)

@Serializable
data class ComponentDependencyLockFixResult(
    val dependencyLockPath: String?,
    val replaceProviderPath: String?,
    val requiredInterface: String,
    val requiredVersion: String,
    val requiredShape: String,
    val rejectProviderPath: String?,
)

@Serializable
data class ComponentCompatibilitySummaryResult(
    val compatible: Boolean,
    val matchedCount: Int,
    val missingCount: Int,
    val versionDriftCount: Int,
    val sortMismatchCount: Int,
    val wrongInterfaceCount: Int,
    val ambiguousCount: Int,
    val incompatibleDescriptionCount: Int,
    val candidateProviderCount: Int,
)

@Serializable
data class CheckComponentCompatibilityResult(
    val path: String?,
    val previousPath: String?,
    val dependencyLockPath: String?,
    val compatible: Boolean,
    val matches: List<ComponentCompatibilityMatchResult>,
    val missingImports: List<ComponentUnresolvedImportResult>,
    val issues: List<ComponentCompatibilityIssueResult>,
    val failingImport: ComponentCompatibilityFailingImportResult?,
    val configuredProviders: List<ComponentConfiguredProviderResult>,
    val candidateProviders: List<ComponentProviderVerdictResult>,
    val selectedIncompatibilityReason: String?,
    val providerPathsInvolved: List<String>,
    val minimalDependencyLockFix: ComponentDependencyLockFixResult?,
    val recommendation: String?,
    val summary: ComponentCompatibilitySummaryResult,
)
