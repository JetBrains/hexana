package org.jetbrains.hexana.mcp.model.components

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
    val importName: String,
    val baseName: String,
    val version: String,
    val classification: String,
)

@Serializable
data class ComponentIgnoredRuntimeImportResult(
    val importName: String,
    val baseName: String,
    val version: String,
    val classification: String,
    val reason: String,
)

@Serializable
data class ComponentResolvedDependencyPathResult(
    val importName: String,
    val dependencyPath: String,
    val classification: String,
)

@Serializable
data class ComponentUnresolvedImportResult(
    val importName: String,
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
    val path: String?,
    val scanRoots: List<String>,
    val requiredNonRuntimeImports: List<ComponentDependencyImportResult>,
    val ignoredRuntimeImports: List<ComponentIgnoredRuntimeImportResult>,
    val resolvedDependencyPaths: List<ComponentResolvedDependencyPathResult>,
    val directDependencies: List<ComponentDependencyProviderResult>,
    val transitiveDependencies: List<ComponentDependencyProviderResult>,
    val unresolvedImports: List<ComponentUnresolvedImportResult>,
    val candidateConflicts: List<ComponentCandidateConflictResult>,
    val rejectedCandidates: List<ComponentRejectedCandidateResult>,
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
data class ComponentCompatibilitySummaryResult(
    val compatible: Boolean,
    val matchedCount: Int,
    val missingCount: Int,
    val versionDriftCount: Int,
    val ambiguousCount: Int,
    val incompatibleDescriptionCount: Int,
)

@Serializable
data class CheckComponentCompatibilityResult(
    val path: String?,
    val compatible: Boolean,
    val matches: List<ComponentCompatibilityMatchResult>,
    val missingImports: List<ComponentUnresolvedImportResult>,
    val issues: List<ComponentCompatibilityIssueResult>,
    val summary: ComponentCompatibilitySummaryResult,
)
