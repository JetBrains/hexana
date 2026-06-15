package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.components.*
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
    val index = ComponentScanIndex.scan(roots, excludedPath = path)
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
    }
    val resolvedDependencyPaths = (directDeps.map { it.toDependencyPathResult(classification = "direct") } +
        transitiveDeps.map { it.toDependencyPathResult(classification = "transitive") })
        .distinctBy { it.classification + "\u0000" + it.importName + "\u0000" + it.dependencyPath }
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
        requiredNonRuntimeImports = requiredImports.take(MAX_RESULT_ITEMS),
        ignoredRuntimeImports = ignoredRuntimeImports.take(MAX_RESULT_ITEMS),
        resolvedDependencyPaths = resolvedDependencyPaths.take(MAX_RESULT_ITEMS),
        directDependencies = directDeps.take(MAX_RESULT_ITEMS),
        transitiveDependencies = transitiveDeps.take(MAX_RESULT_ITEMS),
        unresolvedImports = allUnresolved.map { it.importRef.toUnresolvedResult() }.take(MAX_RESULT_ITEMS),
        candidateConflicts = allConflicts.map { it.toResult() }.take(MAX_RESULT_ITEMS),
        rejectedCandidates = rejectedCandidates
            .map { it.toResult() }
            .sortedBy { it.candidatePath }
            .take(MAX_RESULT_ITEMS),
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
): CheckComponentCompatibilityResult {
    val explicitProviders = dependencyPaths.mapNotNull { depPath ->
        readComponentProvider(Path.of(depPath))?.let { provider ->
            ScannedComponent(Path.of(depPath).normalize(), provider.exports.map { it.exportRef(Path.of(depPath).toString()) })
        }
    }
    val resolvedIndex = if (explicitProviders.isEmpty()) {
        ComponentScanIndex.scan(componentScanRoots(path, projectRoot, emptyList()), excludedPath = path)
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
    val incompatibleDescriptionCount = issues.count { it.reason == "extern_description_mismatch" }
    val errorCount = issues.count { it.severity == "error" }
    return CheckComponentCompatibilityResult(
        path = path,
        compatible = errorCount == 0,
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
        summary = ComponentCompatibilitySummaryResult(
            compatible = errorCount == 0,
            matchedCount = matches.size,
            missingCount = missing.size,
            versionDriftCount = versionDriftCount,
            ambiguousCount = resolved.conflicts.size,
            incompatibleDescriptionCount = incompatibleDescriptionCount,
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
        fun scan(roots: List<Path>, excludedPath: String?): ComponentScanIndex {
            val excluded = excludedPath?.let { Path.of(it).normalize() }
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
                        if (excluded != null && normalized == excluded) continue
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
    val exports = component.exports.map { it.fullName }.distinct().take(3)
    val suffix = if (component.exports.size > exports.size) ", ..." else ""
    return if (exports.size == 1) {
        "exports ${exports.single()}$suffix, which does not match any required import"
    } else {
        "exports ${exports.joinToString()}$suffix, which do not match any required import"
    }
}

private fun componentScanRoots(path: String?, projectRoot: String?, scanRoots: List<String>): List<Path> {
    val roots = linkedSetOf<Path>()
    scanRoots.filter { it.isNotBlank() }.forEach { roots.add(Path.of(it).normalize()) }
    projectRoot?.takeIf { it.isNotBlank() }?.let { roots.add(Path.of(it).normalize()) }
    path?.takeIf { it.isNotBlank() }?.let { rawPath ->
        Path.of(rawPath).toAbsolutePath().parent?.normalize()?.let(roots::add)
    }
    return roots.filter { Files.isDirectory(it) }.take(MAX_SCAN_ROOTS)
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
