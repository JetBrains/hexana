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
