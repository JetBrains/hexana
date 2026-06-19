package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.components.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.componentExternDescriptionLabel
import org.jetbrains.hexana.shared.componentSortIndexLabel
import org.jetbrains.hexana.shared.isRuntimeImport
import org.jetbrains.hexana.shared.splitNameAndVersion
import org.jetbrains.hexana.wasm.components.WasmComponentExport
import org.jetbrains.hexana.wasm.components.WasmComponentImport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

private const val MAX_SCAN_ROOTS = 8
private const val MAX_SCANNED_WASM_FILES = 500
private const val MAX_SCAN_DEPTH = 16
private const val MAX_RESULT_ITEMS = 200

fun WasmComponentBinaryProvider.readComponentSurface(pathLabel: String? = null): ListComponentSurfaceResult {
    if (!canProvideWasmModule()) {
        return ListComponentSurfaceResult(
            path = pathLabel,
            isComponent = false,
            imports = emptyList(),
            exports = emptyList(),
            summary = ComponentSurfaceSummaryResult(0, 0, 0, 0),
        )
    }
    val imports = imports.map { it.surfaceEntry() }
    val exports = exports.map { it.surfaceEntry() }
    return ListComponentSurfaceResult(
        path = pathLabel,
        isComponent = true,
        imports = imports.take(MAX_RESULT_ITEMS),
        exports = exports.take(MAX_RESULT_ITEMS),
        summary = ComponentSurfaceSummaryResult(
            importCount = imports.size,
            exportCount = exports.size,
            nonRuntimeImportCount = imports.count { !it.runtimeProvided },
            runtimeImportCount = imports.count { it.runtimeProvided },
        ),
    )
}

fun WasmComponentBinaryProvider.resolveComponentDependencies(
    path: String?,
    projectRoot: String?,
    scanRoots: List<String>,
): ResolveComponentDependenciesResult {
    val roots = componentScanRoots(path, projectRoot, scanRoots)
    val index = ComponentScanIndex.scan(roots, excludedPaths = excludedComponentPaths(path, projectRoot))
    val directImports = imports.map { it.importRef() }
    val directRuntimeImports = directImports
        .filter { isRuntimeImport(it.fullName) }
        .map { it.toIgnoredRuntimeImportResult(classification = "direct") }
    val direct = directImports
        .filterNot { isRuntimeImport(it.fullName) }
    val directMatches = resolveImports(direct, index)
    val directDeps = directMatches.matches.map { it.toProviderResult(transitive = false) }

    val transitiveImports = linkedSetOf<ComponentImportRef>()
    val transitiveRuntimeImports = linkedMapOf<String, ComponentIgnoredRuntimeImportResult>()
    val visitedDependencies = mutableSetOf<String>()
    val queue = ArrayDeque(directDeps.map { it.dependencyPath }.distinct())
    while (queue.isNotEmpty()) {
        val depPath = queue.removeFirst()
        if (!visitedDependencies.add(depPath)) continue
        val provider = readComponentProvider(Path.of(depPath)) ?: continue
        val depImports = provider.imports.map { it.importRef() }
        depImports
            .filter { isRuntimeImport(it.fullName) }
            .filter { candidate -> directImports.none { it.fullName == candidate.fullName } }
            .forEach { importRef ->
                transitiveRuntimeImports.putIfAbsent(
                    importRef.fullName,
                    importRef.toIgnoredRuntimeImportResult(classification = "transitive")
                )
            }
        val nonRuntimeDepImports = depImports
            .filterNot { isRuntimeImport(it.fullName) }
            .filter { candidate -> direct.none { it.fullName == candidate.fullName } }
        for (depImport in nonRuntimeDepImports) {
            if (transitiveImports.add(depImport)) {
                val matches = resolveImports(listOf(depImport), index).matches
                matches.firstOrNull()?.dependency?.path?.toString()?.let { queue.add(it) }
            }
        }
    }
    val transitiveMatches = resolveImports(transitiveImports.toList(), index)
    val transitiveDeps = transitiveMatches.matches.map { it.toProviderResult(transitive = true) }
    val allUnresolved = (directMatches.unresolved + transitiveMatches.unresolved).distinctBy { it.importRef.fullName }
    val allConflicts = (directMatches.conflicts + transitiveMatches.conflicts).distinctBy { it.importRef.fullName }
    val requiredImports = direct.map { it.toDependencyImportResult(classification = "direct") } +
        transitiveImports.map { it.toDependencyImportResult(classification = "transitive") }
    val ignoredRuntimeImports = (directRuntimeImports + transitiveRuntimeImports.values).distinctBy {
        it.classification + "\u0000" + it.importName
    }.sortedWith(compareBy({ it.classification }, { it.importName }))
    val resolvedDependencyPaths = (directDeps.map { it.toDependencyPathResult(classification = "direct") } +
        transitiveDeps.map { it.toDependencyPathResult(classification = "transitive") })
        .distinctBy { it.classification + "\u0000" + it.importName + "\u0000" + it.dependencyPath }
        .sortedWith(compareBy({ it.classification }, { it.importName }, { it.dependencyPath }))
    val excludedRejectedPaths = (directDeps + transitiveDeps)
        .map { Path.of(it.dependencyPath).normalize() }
        .toMutableSet()
    allConflicts.flatMap { conflict -> conflict.candidates }
        .mapTo(excludedRejectedPaths) { it.dependency.path.normalize() }
    val rejectedCandidates = index.rejectedCandidates + buildRejectedComponentCandidates(
        index = index,
        requiredImports = direct + transitiveImports,
        excludedPaths = excludedRejectedPaths,
    )

    return ResolveComponentDependenciesResult(
        path = path,
        scanRoots = roots.map(Path::toString),
        requiredNonRuntimeImports = requiredImports
            .sortedWith(compareBy({ it.classification }, { it.importName }))
            .take(MAX_RESULT_ITEMS),
        ignoredRuntimeImports = ignoredRuntimeImports.take(MAX_RESULT_ITEMS),
        resolvedDependencyPaths = resolvedDependencyPaths.take(MAX_RESULT_ITEMS),
        directDependencies = directDeps.sortedBy { it.importName }.take(MAX_RESULT_ITEMS),
        transitiveDependencies = transitiveDeps.sortedBy { it.importName }.take(MAX_RESULT_ITEMS),
        unresolvedImports = allUnresolved.map { it.importRef.toUnresolvedResult() }
            .sortedBy { it.importName }
            .take(MAX_RESULT_ITEMS),
        candidateConflicts = allConflicts.map { it.toResult() }
            .sortedBy { it.importName }
            .take(MAX_RESULT_ITEMS),
        rejectedCandidates = rejectedCandidates
            .map { it.toResult() }
            .sortedBy { it.candidatePath }
            .take(MAX_RESULT_ITEMS),
        minimalRunBuildFix = buildMinimalRunBuildFix(
            componentPath = path,
            projectRoot = projectRoot,
            resolvedDependencyPaths = resolvedDependencyPaths,
            unresolvedImports = allUnresolved,
        ),
        summary = ComponentDependencySummaryResult(
            directRequiredImportCount = direct.size,
            transitiveRequiredImportCount = transitiveImports.size,
            ignoredRuntimeImportCount = ignoredRuntimeImports.size,
            directResolvedCount = directDeps.size,
            transitiveResolvedCount = transitiveDeps.size,
            unresolvedCount = allUnresolved.size,
            ambiguousCount = allConflicts.size,
            rejectedCandidateCount = rejectedCandidates.size,
            scannedFileCount = index.scannedFileCount,
            truncated = index.truncated,
        ),
    )
}

fun WasmComponentBinaryProvider.checkComponentCompatibility(
    path: String?,
    dependencyPaths: List<String>,
    projectRoot: String?,
    previousPath: String?,
    dependencyLockPath: String?,
): CheckComponentCompatibilityResult {
    val lock = readDependencyLock(dependencyLockPath, projectRoot)
    val explicitProviders = dependencyPaths.mapNotNull { depPath ->
        val resolvedPath = resolveOptionalProjectPath(depPath, projectRoot) ?: Path.of(depPath).normalize()
        readComponentProvider(resolvedPath)?.let { provider ->
            ScannedComponent(resolvedPath, provider.exports.map { it.exportRef(resolvedPath.toString()) })
        }
    }
    val resolvedIndex = if (explicitProviders.isEmpty()) {
        ComponentScanIndex.scan(
            componentScanRoots(path, projectRoot, emptyList()),
            excludedPaths = excludedComponentPaths(path, projectRoot),
        )
    } else {
        ComponentScanIndex(
            explicitProviders,
            rejectedCandidates = emptyList(),
            scannedFileCount = explicitProviders.size,
            truncated = false
        )
    }

    val required = imports
        .filterNot { isRuntimeImport(it.fullName()) }
        .map { it.importRef() }
    val resolved = resolveImports(required, resolvedIndex)
    val matches = resolved.matches
    val candidateProviders = buildProviderVerdicts(required, resolvedIndex.components)
    val failingImport = selectFailingImport(required, resolved, candidateProviders)
    val previousImport = failingImport?.let { readPreviousImport(previousPath, projectRoot, it) }
    val selectedReason = failingImport?.let {
        selectIncompatibilityReason(it, resolved, candidateProviders)
    }
    val providerPathsInvolved = failingImport
        ?.let { involvedProviderPaths(it, selectedReason, lock.configuredProviders, candidateProviders) }
        .orEmpty()
    val lockFix = failingImport?.let {
        buildDependencyLockFix(
            failingImport = it,
            selectedReason = selectedReason,
            dependencyLockPath = lock.label,
            configuredProviders = lock.configuredProviders,
            candidateProviders = candidateProviders,
        )
    }
    val issues = mutableListOf<ComponentCompatibilityIssueResult>()
    issues += resolved.unresolved.map {
        ComponentCompatibilityIssueResult(
            importName = it.importRef.fullName,
            dependencyPath = null,
            severity = "error",
            reason = "missing_import",
            expected = it.importRef.externDescription,
            actual = null,
        )
    }
    issues += resolved.conflicts.map {
        ComponentCompatibilityIssueResult(
            importName = it.importRef.fullName,
            dependencyPath = null,
            severity = "error",
            reason = "ambiguous_provider",
            expected = it.importRef.fullName,
            actual = it.candidates.joinToString(", ") { candidate -> candidate.dependency.path.toString() },
        )
    }
    for (match in matches) {
        if (!match.exactVersionMatch) {
            issues += ComponentCompatibilityIssueResult(
                importName = match.importRef.fullName,
                dependencyPath = match.dependency.path.toString(),
                severity = "warning",
                reason = "version_drift",
                expected = match.importRef.version.ifBlank { null },
                actual = match.export.version.ifBlank { null },
            )
        }
        val expectedShape = expectedProviderShape(match.importRef)
        val actualShape = actualProviderShape(match.export)
        if (!isProviderShapeCompatible(match.importRef, match.export)) {
            issues += ComponentCompatibilityIssueResult(
                importName = match.importRef.fullName,
                dependencyPath = match.dependency.path.toString(),
                severity = "error",
                reason = "sort_mismatch",
                expected = expectedShape,
                actual = actualShape,
            )
            continue
        }
        val expectedDesc = match.importRef.externDescription
        val actualDesc = match.export.externDescription
        if (expectedDesc != null && actualDesc != null && expectedDesc != actualDesc) {
            issues += ComponentCompatibilityIssueResult(
                importName = match.importRef.fullName,
                dependencyPath = match.dependency.path.toString(),
                severity = "error",
                reason = "extern_description_mismatch",
                expected = expectedDesc,
                actual = actualDesc,
            )
        }
    }

    val missing = resolved.unresolved.map { it.importRef.toUnresolvedResult() }
    val versionDriftCount = issues.count { it.reason == "version_drift" }
    val sortMismatchCount = issues.count { it.reason == "sort_mismatch" }
    val wrongInterfaceCount = candidateProviders.count { it.verdict == "wrong_interface" }
    val incompatibleDescriptionCount = issues.count { it.reason == "extern_description_mismatch" }
    val errorCount = issues.count { it.severity == "error" }
    val compatible = errorCount == 0 && versionDriftCount == 0
    return CheckComponentCompatibilityResult(
        path = path,
        previousPath = previousPath,
        dependencyLockPath = lock.label,
        compatible = compatible,
        matches = matches.map {
            ComponentCompatibilityMatchResult(
                importName = it.importRef.fullName,
                dependencyPath = it.dependency.path.toString(),
                exportName = it.export.fullName,
                exactVersionMatch = it.exactVersionMatch,
            )
        }.take(MAX_RESULT_ITEMS),
        missingImports = missing.take(MAX_RESULT_ITEMS),
        issues = issues.take(MAX_RESULT_ITEMS),
        failingImport = failingImport?.toResult(previousImport),
        configuredProviders = lock.configuredProviders
            .map { it.toResult() }
            .take(MAX_RESULT_ITEMS),
        candidateProviders = candidateProviders
            .map { it.toResult() }
            .sortedBy { it.dependencyPath }
            .take(MAX_RESULT_ITEMS),
        selectedIncompatibilityReason = selectedReason,
        providerPathsInvolved = providerPathsInvolved.take(MAX_RESULT_ITEMS),
        minimalDependencyLockFix = lockFix,
        recommendation = lockFix?.recommendation(path),
        summary = ComponentCompatibilitySummaryResult(
            compatible = compatible,
            matchedCount = matches.size,
            missingCount = missing.size,
            versionDriftCount = versionDriftCount,
            sortMismatchCount = sortMismatchCount,
            wrongInterfaceCount = wrongInterfaceCount,
            ambiguousCount = resolved.conflicts.size,
            incompatibleDescriptionCount = incompatibleDescriptionCount,
            candidateProviderCount = candidateProviders.size,
        ),
    )
}

private fun WasmComponentImport.surfaceEntry(): ComponentSurfaceEntryResult {
    val ref = importRef()
    return ComponentSurfaceEntryResult(
        name = ref.fullName,
        baseName = ref.baseName,
        version = ref.version,
        sort = null,
        externDescription = ref.externDescription,
        runtimeProvided = isRuntimeImport(ref.fullName),
    )
}

private fun WasmComponentExport.surfaceEntry(): ComponentSurfaceEntryResult {
    val ref = exportRef(path = "")
    return ComponentSurfaceEntryResult(
        name = ref.fullName,
        baseName = ref.baseName,
        version = ref.version,
        sort = componentSortIndexLabel(sortIndex),
        externDescription = ref.externDescription,
        runtimeProvided = false,
    )
}

private fun WasmComponentImport.importRef(): ComponentImportRef {
    val name = fullName()
    val split = splitComponentName(importName, version)
    return ComponentImportRef(
        fullName = name,
        baseName = split.baseName,
        matchKey = componentMatchKey(split.baseName),
        version = split.version,
        externDescription = componentExternDescriptionLabel(externDescription),
    )
}

private fun WasmComponentExport.exportRef(path: String): ComponentExportRef {
    val split = splitComponentName(exportName, version)
    return ComponentExportRef(
        fullName = fullName(),
        baseName = split.baseName,
        matchKey = componentMatchKey(split.baseName),
        version = split.version,
        sort = componentSortIndexLabel(sortIndex),
        externDescription = externDescription?.let(::componentExternDescriptionLabel),
        path = path,
    )
}

private fun WasmComponentImport.fullName(): String = combineComponentName(importName, version)

private fun WasmComponentExport.fullName(): String = combineComponentName(exportName, version)

private fun combineComponentName(name: String, version: String?): String =
    if (version.isNullOrBlank()) name else name + version

private data class ComponentNameParts(val baseName: String, val version: String)

private fun splitComponentName(name: String, version: String?): ComponentNameParts {
    if (!version.isNullOrBlank()) {
        return ComponentNameParts(name.substringBeforeLast("@"), version.removePrefix("@"))
    }
    val (baseName, parsedVersion) = splitNameAndVersion(name)
    return ComponentNameParts(baseName, parsedVersion.removePrefix("@"))
}

private data class ComponentImportRef(
    val fullName: String,
    val baseName: String,
    val matchKey: String,
    val version: String,
    val externDescription: String?,
)

private data class ComponentExportRef(
    val fullName: String,
    val baseName: String,
    val matchKey: String,
    val version: String,
    val sort: String,
    val externDescription: String?,
    val path: String,
)

private data class ScannedComponent(
    val path: Path,
    val exports: List<ComponentExportRef>,
)

private data class RejectedScannedCandidate(
    val path: Path,
    val reason: String,
) {
    fun toResult(): ComponentRejectedCandidateResult =
        ComponentRejectedCandidateResult(
            candidatePath = path.toString(),
            reason = reason,
        )
}

private data class ComponentScanIndex(
    val components: List<ScannedComponent>,
    val rejectedCandidates: List<RejectedScannedCandidate>,
    val scannedFileCount: Int,
    val truncated: Boolean,
) {
    companion object {
        fun scan(roots: List<Path>, excludedPaths: Set<Path>): ComponentScanIndex {
            val components = mutableListOf<ScannedComponent>()
            val rejectedCandidates = mutableListOf<RejectedScannedCandidate>()
            var scanned = 0
            var truncated = false
            for (root in roots.take(MAX_SCAN_ROOTS)) {
                if (truncated) break
                if (!Files.isDirectory(root)) continue
                Files.walk(root, MAX_SCAN_DEPTH).use { paths ->
                    val iterator = paths.iterator()
                    while (iterator.hasNext()) {
                        val path = iterator.next()
                        if (scanned >= MAX_SCANNED_WASM_FILES) {
                            truncated = true
                            break
                        }
                        if (!Files.isRegularFile(path) || path.extension.lowercase() != "wasm") continue
                        val normalized = path.normalize()
                        if (normalized in excludedPaths) continue
                        scanned += 1
                        val provider = readComponentProvider(path)
                        if (provider == null) {
                            rejectedCandidates += RejectedScannedCandidate(
                                normalized,
                                "not a WebAssembly component and exports no component interface"
                            )
                            continue
                        }
                        components += ScannedComponent(
                            normalized,
                            provider.exports.map { it.exportRef(normalized.toString()) },
                        )
                    }
                }
            }
            return ComponentScanIndex(components, rejectedCandidates, scanned, truncated)
        }
    }
}

private data class ResolvedImport(
    val importRef: ComponentImportRef,
    val dependency: ScannedComponent,
    val export: ComponentExportRef,
) {
    val exactVersionMatch: Boolean =
        importRef.version.isBlank() || export.version.isBlank() || importRef.version == export.version

    fun toProviderResult(transitive: Boolean): ComponentDependencyProviderResult =
        ComponentDependencyProviderResult(
            importName = importRef.fullName,
            importBaseName = importRef.baseName,
            importVersion = importRef.version,
            dependencyPath = dependency.path.toString(),
            exportName = export.fullName,
            exportVersion = export.version,
            exactVersionMatch = exactVersionMatch,
            transitive = transitive,
        )
}

private data class UnresolvedImport(val importRef: ComponentImportRef)

private data class CandidateConflict(
    val importRef: ComponentImportRef,
    val candidates: List<ResolvedImport>,
) {
    fun toResult(): ComponentCandidateConflictResult =
        ComponentCandidateConflictResult(
            importName = importRef.fullName,
            baseName = importRef.baseName,
            candidatePaths = candidates.map { it.dependency.path.toString() }.distinct().take(20),
            reason = "multiple component exports satisfy `${importRef.baseName}`",
        )
}

private data class DependencyLock(
    val label: String?,
    val configuredProviders: List<ConfiguredProvider>,
)

private data class ConfiguredProvider(
    val interfaceName: String,
    val version: String,
    val path: String,
) {
    fun toResult(): ComponentConfiguredProviderResult =
        ComponentConfiguredProviderResult(
            interfaceName = interfaceName,
            version = version,
            path = path,
        )
}

private data class ProviderVerdict(
    val dependency: ScannedComponent,
    val export: ComponentExportRef,
    val importRef: ComponentImportRef,
    val verdict: String,
) {
    fun toResult(): ComponentProviderVerdictResult =
        ComponentProviderVerdictResult(
            dependencyPath = dependency.path.toString(),
            exportName = export.fullName,
            baseName = export.baseName,
            version = export.version,
            sort = export.sort,
            externDescription = export.externDescription,
            verdict = verdict,
        )
}

private data class ResolveResult(
    val matches: List<ResolvedImport>,
    val unresolved: List<UnresolvedImport>,
    val conflicts: List<CandidateConflict>,
)

private fun resolveImports(imports: List<ComponentImportRef>, index: ComponentScanIndex): ResolveResult {
    val matches = mutableListOf<ResolvedImport>()
    val unresolved = mutableListOf<UnresolvedImport>()
    val conflicts = mutableListOf<CandidateConflict>()
    for (importRef in imports.distinctBy { it.fullName }) {
        val candidates = index.components.flatMap { component ->
            component.exports
                .filter { it.matchKey == importRef.matchKey }
                .map { ResolvedImport(importRef, component, it) }
        }
        if (candidates.isEmpty()) {
            unresolved += UnresolvedImport(importRef)
            continue
        }
        val exact = candidates.filter { it.exactVersionMatch }
        val usable = exact.ifEmpty { candidates }
        val candidatePaths = usable.map { it.dependency.path }.distinct()
        if (candidatePaths.size > 1) {
            conflicts += CandidateConflict(importRef, usable)
            continue
        }
        matches += usable.first()
    }
    return ResolveResult(matches, unresolved, conflicts)
}

private fun buildProviderVerdicts(
    required: List<ComponentImportRef>,
    components: List<ScannedComponent>,
): List<ProviderVerdict> {
    if (required.isEmpty()) {
        return emptyList()
    }
    return components.flatMap { component ->
        component.exports.mapNotNull { export ->
            val bestImport = required.firstOrNull { it.matchKey == export.matchKey }
                ?: required.firstOrNull()
                ?: return@mapNotNull null
            val verdict = providerVerdict(bestImport, export)
            ProviderVerdict(
                dependency = component,
                export = export,
                importRef = bestImport,
                verdict = verdict,
            )
        }
    }
}

private fun providerVerdict(importRef: ComponentImportRef, export: ComponentExportRef): String {
    if (export.matchKey != importRef.matchKey) {
        return "wrong_interface"
    }
    if (importRef.version.isNotBlank() && export.version.isNotBlank() && importRef.version != export.version) {
        return "version_drift"
    }
    if (!isProviderShapeCompatible(importRef, export)) {
        return if (!expectedProviderSortMatches(importRef, export)) "sort_mismatch" else "extern_description_mismatch"
    }
    return "compatible"
}

private fun selectFailingImport(
    required: List<ComponentImportRef>,
    resolved: ResolveResult,
    candidateProviders: List<ProviderVerdict>,
): ComponentImportRef? {
    for (importRef in required) {
        val sameInterface = candidateProviders.filter { it.export.matchKey == importRef.matchKey }
        if (sameInterface.any { it.verdict != "compatible" }) {
            return importRef
        }
        if (resolved.unresolved.any { it.importRef.fullName == importRef.fullName }) {
            return importRef
        }
        if (resolved.conflicts.any { it.importRef.fullName == importRef.fullName }) {
            return importRef
        }
    }
    return null
}

private fun selectIncompatibilityReason(
    failingImport: ComponentImportRef,
    resolved: ResolveResult,
    candidateProviders: List<ProviderVerdict>,
): String? {
    val sameInterface = candidateProviders.filter { it.export.matchKey == failingImport.matchKey }
    val sameVersion = sameInterface.filter {
        failingImport.version.isBlank() || it.export.version.isBlank() || it.export.version == failingImport.version
    }
    sameVersion.firstOrNull { it.verdict == "sort_mismatch" }?.let { return it.verdict }
    sameVersion.firstOrNull { it.verdict == "extern_description_mismatch" }?.let { return it.verdict }
    sameVersion.firstOrNull { it.verdict == "compatible" }?.let { return null }
    sameInterface.firstOrNull { it.verdict == "version_drift" }?.let { return it.verdict }
    if (resolved.conflicts.any { it.importRef.fullName == failingImport.fullName }) {
        return "ambiguous_provider"
    }
    if (resolved.unresolved.any { it.importRef.fullName == failingImport.fullName }) {
        return "missing_provider"
    }
    return sameInterface.firstOrNull { it.verdict != "compatible" }?.verdict
}

private fun involvedProviderPaths(
    failingImport: ComponentImportRef,
    selectedReason: String?,
    configuredProviders: List<ConfiguredProvider>,
    candidateProviders: List<ProviderVerdict>,
): List<String> {
    val paths = linkedSetOf<String>()
    configuredProviders
        .filter { it.interfaceName == failingImport.baseName }
        .forEach { paths.add(it.path) }
    candidateProviders
        .filter { it.export.matchKey == failingImport.matchKey }
        .filter { it.verdict == selectedReason || it.verdict == "version_drift" }
        .forEach { paths.add(it.dependency.path.toString()) }
    return paths.toList()
}

private fun buildDependencyLockFix(
    failingImport: ComponentImportRef,
    selectedReason: String?,
    dependencyLockPath: String?,
    configuredProviders: List<ConfiguredProvider>,
    candidateProviders: List<ProviderVerdict>,
): ComponentDependencyLockFixResult? {
    if (selectedReason == null && dependencyLockPath == null) {
        return null
    }
    val configured = configuredProviders.firstOrNull { it.interfaceName == failingImport.baseName }
    val rejectProvider = candidateProviders
        .filter { it.export.matchKey == failingImport.matchKey }
        .firstOrNull {
            it.verdict == selectedReason &&
                (failingImport.version.isBlank() || it.export.version == failingImport.version)
        }
    return ComponentDependencyLockFixResult(
        dependencyLockPath = dependencyLockPath,
        replaceProviderPath = configured?.path,
        requiredInterface = failingImport.baseName,
        requiredVersion = failingImport.version,
        requiredShape = expectedProviderShape(failingImport),
        rejectProviderPath = rejectProvider?.dependency?.path?.toString(),
    )
}

private fun ComponentDependencyLockFixResult.recommendation(componentPath: String?): String {
    val target = dependencyLockPath ?: "the dependency lock/config"
    val component = componentPath ?: "the component"
    val provider = if (requiredVersion.isBlank()) requiredInterface else "$requiredInterface@$requiredVersion"
    return "Update $target to point at a rebuilt $provider provider with $requiredShape required by $component."
}

private fun ComponentImportRef.toResult(
    previousImport: ComponentImportRef?,
): ComponentCompatibilityFailingImportResult =
    ComponentCompatibilityFailingImportResult(
        importName = fullName,
        baseName = baseName,
        requiredVersion = version,
        sort = expectedProviderSortLabel(externDescription),
        externDescription = externDescription,
        previousImportName = previousImport?.fullName,
        previousBaseName = previousImport?.baseName,
        previousVersion = previousImport?.version,
        changedSincePrevious = previousImport != null && previousImport.fullName != fullName,
    )

private fun readPreviousImport(
    previousPath: String?,
    projectRoot: String?,
    failingImport: ComponentImportRef,
): ComponentImportRef? {
    val previous = resolveOptionalProjectPath(previousPath, projectRoot) ?: return null
    val provider = readComponentProvider(previous) ?: return null
    return provider.imports
        .filterNot { isRuntimeImport(it.fullName()) }
        .map { it.importRef() }
        .firstOrNull { it.matchKey == failingImport.matchKey }
}

private fun readDependencyLock(dependencyLockPath: String?, projectRoot: String?): DependencyLock {
    val rawPath = dependencyLockPath?.takeIf { it.isNotBlank() } ?: return DependencyLock(null, emptyList())
    val resolvedPath = resolveOptionalProjectPath(rawPath, projectRoot) ?: return DependencyLock(rawPath, emptyList())
    val providers = try {
        val root = Json.parseToJsonElement(Files.readString(resolvedPath)).jsonObject
        val providerArray = root["providers"] as? JsonArray ?: return DependencyLock(rawPath, emptyList())
        providerArray.mapNotNull { provider ->
            val obj = provider as? JsonObject ?: return@mapNotNull null
            val interfaceName = obj.stringField("interface") ?: obj.stringField("baseName") ?: return@mapNotNull null
            val version = obj.stringField("version").orEmpty()
            val path = obj.stringField("path") ?: return@mapNotNull null
            ConfiguredProvider(interfaceName = interfaceName, version = version, path = path)
        }
    } catch (_: Exception) {
        emptyList()
    }
    return DependencyLock(rawPath, providers)
}

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun resolveOptionalProjectPath(path: String?, projectRoot: String?): Path? {
    val raw = path?.takeIf { it.isNotBlank() } ?: return null
    val candidate = Path.of(raw)
    return if (candidate.isAbsolute) {
        candidate.normalize()
    } else {
        projectRoot
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).resolve(candidate).normalize() }
            ?: candidate.normalize()
    }
}

private fun expectedProviderShape(importRef: ComponentImportRef): String {
    val sort = expectedProviderSortLabel(importRef.externDescription)
    val sortText = sort?.let { "$it export" } ?: "provider export"
    val extern = importRef.externDescription ?: "matching extern description"
    return "$sortText with $extern"
}

private fun actualProviderShape(export: ComponentExportRef): String {
    val extern = export.externDescription ?: "no extern description"
    return "${export.sort} export with $extern"
}

private fun isProviderShapeCompatible(importRef: ComponentImportRef, export: ComponentExportRef): Boolean =
    expectedProviderSortMatches(importRef, export) &&
        (importRef.externDescription == null ||
            export.externDescription == null ||
            importRef.externDescription == export.externDescription)

private fun expectedProviderSortMatches(importRef: ComponentImportRef, export: ComponentExportRef): Boolean {
    val expected = expectedProviderSortLabel(importRef.externDescription) ?: return true
    return export.sort.startsWith("$expected(") || export.sort == expected
}

private fun expectedProviderSortLabel(externDescription: String?): String? = when {
    externDescription == null -> null
    externDescription.startsWith("Extern(Type(") -> "Func"
    externDescription.startsWith("Extern(Value(") -> "Value"
    externDescription.startsWith("Component(") -> "Component"
    externDescription.startsWith("Instance(") -> "Instance"
    externDescription.startsWith("Extern(Core:Type(") -> "Core:Func"
    else -> null
}

private fun ComponentImportRef.toDependencyImportResult(classification: String): ComponentDependencyImportResult =
    ComponentDependencyImportResult(
        importName = fullName,
        baseName = baseName,
        version = version,
        classification = classification,
    )

private fun ComponentImportRef.toIgnoredRuntimeImportResult(classification: String): ComponentIgnoredRuntimeImportResult =
    ComponentIgnoredRuntimeImportResult(
        importName = fullName,
        baseName = baseName,
        version = version,
        classification = classification,
        reason = "provided by the WebAssembly runtime",
    )

private fun ComponentDependencyProviderResult.toDependencyPathResult(
    classification: String,
): ComponentResolvedDependencyPathResult =
    ComponentResolvedDependencyPathResult(
        importName = importName,
        dependencyPath = dependencyPath,
        classification = classification,
    )

private fun ComponentImportRef.toUnresolvedResult(): ComponentUnresolvedImportResult =
    ComponentUnresolvedImportResult(
        importName = fullName,
        baseName = baseName,
        version = version,
        runtimeProvided = isRuntimeImport(fullName),
    )

private fun buildRejectedComponentCandidates(
    index: ComponentScanIndex,
    requiredImports: Collection<ComponentImportRef>,
    excludedPaths: Set<Path>,
): List<RejectedScannedCandidate> {
    if (requiredImports.isEmpty()) {
        return emptyList()
    }
    return index.components
        .filterNot { it.path.normalize() in excludedPaths }
        .map { component ->
            RejectedScannedCandidate(
                path = component.path,
                reason = rejectedComponentReason(component, requiredImports),
            )
    }
}

private fun excludedComponentPaths(path: String?, projectRoot: String?): Set<Path> {
    val raw = path?.takeIf { it.isNotBlank() } ?: return emptySet()
    val candidate = Path.of(raw)
    return buildSet {
        add(candidate.normalize())
        add(candidate.toAbsolutePath().normalize())
        if (!candidate.isAbsolute && !projectRoot.isNullOrBlank()) {
            add(Path.of(projectRoot).resolve(candidate).normalize())
        }
    }
}

private fun buildMinimalRunBuildFix(
    componentPath: String?,
    projectRoot: String?,
    resolvedDependencyPaths: List<ComponentResolvedDependencyPathResult>,
    unresolvedImports: List<UnresolvedImport>,
): String {
    val component = componentPath?.takeIf { it.isNotBlank() } ?: "the component"
    val dependencySet = componentDependencySetLabel(componentPath)
    val dependencies = resolvedDependencyPaths
        .map { displayPath(it.dependencyPath, projectRoot) }
        .distinct()
    val unresolved = unresolvedImports
        .map { it.importRef.fullName }
        .distinct()
    return when {
        dependencies.isEmpty() && unresolved.isEmpty() ->
            "No component dependency changes are required before running $component."
        unresolved.isEmpty() ->
            "Add ${dependencies.toHumanList()} to the $dependencySet before running $component."
        dependencies.isEmpty() ->
            "Add providers for ${unresolved.toHumanList()} to the $dependencySet before running $component."
        else ->
            "Add ${dependencies.toHumanList()} and providers for ${unresolved.toHumanList()} to the $dependencySet before running $component."
    }
}

private fun componentDependencySetLabel(componentPath: String?): String {
    val fileName = componentPath
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it).fileName?.toString() }
        .orEmpty()
    val rawName = when {
        fileName.endsWith(".component.wasm") -> fileName.removeSuffix(".component.wasm")
        fileName.endsWith(".wasm") -> fileName.removeSuffix(".wasm")
        else -> ""
    }
    val componentName = rawName
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
    return if (componentName.isBlank()) {
        "component dependency set"
    } else {
        "$componentName component dependency set"
    }
}

private fun displayPath(path: String, projectRoot: String?): String {
    if (projectRoot.isNullOrBlank()) return path
    val root = Path.of(projectRoot).normalize()
    val candidate = Path.of(path).normalize()
    return try {
        if (candidate.isAbsolute && candidate.startsWith(root)) {
            root.relativize(candidate).toString()
        } else {
            path
        }
    } catch (_: IllegalArgumentException) {
        path
    }
}

private fun List<String>.toHumanList(): String = when (size) {
    0 -> ""
    1 -> single()
    2 -> "${this[0]} and ${this[1]}"
    else -> dropLast(1).joinToString(", ") + ", and " + last()
}

private fun rejectedComponentReason(
    component: ScannedComponent,
    requiredImports: Collection<ComponentImportRef>,
): String {
    if (component.exports.isEmpty()) {
        return "exports no component interface"
    }
    for (required in requiredImports) {
        val versionMismatch = component.exports.firstOrNull { export ->
            export.matchKey == required.matchKey &&
                export.version.isNotBlank() &&
                required.version.isNotBlank() &&
                export.version != required.version
        }
        if (versionMismatch != null) {
            return "exports ${versionMismatch.fullName}, but required version is ${required.version}"
        }
    }
    val closestRequiredBase = component.exports
        .asSequence()
        .mapNotNull { export ->
            requiredImports.firstOrNull { required ->
                componentPackagePrefix(export.baseName) == componentPackagePrefix(required.baseName)
            }?.baseName
        }
        .firstOrNull()
    if (closestRequiredBase != null) {
        val exports = component.exports.map { it.fullName }.distinct().take(3)
        val suffix = if (component.exports.size > exports.size) ", ..." else ""
        val exportText = if (exports.size == 1) exports.single() else exports.joinToString()
        return "exports $exportText$suffix, which does not match required base name $closestRequiredBase"
    }
    val exports = component.exports.map { it.fullName }.distinct().take(3)
    val suffix = if (component.exports.size > exports.size) ", ..." else ""
    return if (exports.size == 1) {
        "exports ${exports.single()}$suffix, which does not match any required import"
    } else {
        "exports ${exports.joinToString()}$suffix, which do not match any required import"
    }
}

private fun componentPackagePrefix(baseName: String): String = baseName.substringBeforeLast('/', baseName)

private fun componentScanRoots(path: String?, projectRoot: String?, scanRoots: List<String>): List<Path> {
    val roots = linkedMapOf<Path, Path>()
    fun addRoot(candidate: Path) {
        val normalized = candidate.normalize()
        val key = try {
            normalized.toRealPath()
        } catch (_: Exception) {
            normalized.toAbsolutePath().normalize()
        }
        roots.putIfAbsent(key, normalized)
    }

    val projectRootPath = projectRoot
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it).normalize() }
    scanRoots.filter { it.isNotBlank() }.forEach { rawRoot ->
        val candidate = Path.of(rawRoot)
        if (candidate.isAbsolute || projectRootPath == null) {
            addRoot(candidate)
        } else {
            addRoot(projectRootPath.resolve(candidate))
        }
    }
    projectRootPath?.let(::addRoot)
    path?.takeIf { it.isNotBlank() }?.let { rawPath ->
        val candidate = Path.of(rawPath)
        val resolvedPath = if (candidate.isAbsolute || projectRootPath == null) {
            candidate
        } else {
            projectRootPath.resolve(candidate)
        }
        resolvedPath.toAbsolutePath().parent?.normalize()?.let(::addRoot)
    }
    return roots.values.filter { Files.isDirectory(it) }.take(MAX_SCAN_ROOTS)
}

private fun componentMatchKey(baseName: String): String =
    baseName.substringAfter(':', baseName)

private fun readComponentProvider(path: Path): WasmComponentBinaryProvider? {
    val bytes = try {
        Files.readAllBytes(path)
    } catch (_: Exception) {
        return null
    }
    val buffer = bytes.toCommonByteBuffer()
    if (WasmBinaryProviderSelector.select(buffer) != BinaryProviderType.WasmComponent) return null
    return WasmComponentBinaryProvider(buffer).takeIf(WasmComponentBinaryProvider::canProvideWasmModule)
}
