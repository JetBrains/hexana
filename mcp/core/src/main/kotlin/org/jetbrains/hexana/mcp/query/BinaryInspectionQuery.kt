package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.inspections.BinaryInspectionResult
import org.jetbrains.hexana.mcp.model.inspections.BinaryProblemCountsResult
import org.jetbrains.hexana.mcp.model.inspections.BinaryProblemResult
import org.jetbrains.hexana.mcp.model.inspections.InspectBinaryResult
import org.jetbrains.hexana.mcp.toModuleIdentity
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.jetbrains.hexana.shared.features.WasmFeatureCatalog
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility
import org.jetbrains.hexana.shared.inspections.BinaryInspectionRegistry
import org.jetbrains.hexana.shared.inspections.BinaryProblem
import org.jetbrains.hexana.shared.inspections.InspectionContext
import org.jetbrains.hexana.shared.inspections.counts
import org.jetbrains.hexana.shared.inspections.inspectBinary

/**
 * Runs the shared binary inspections — the ones behind the IDE Problems tab — over this
 * binary.
 *
 * @param runtimeTargets the runtimes declared in a `.hexana/runtimes.json`; empty skips the
 *   runtime-compatibility inspection. Verdicts come from the feature catalog snapshot
 *   bundled with Hexana, so the result does not depend on the network.
 */
fun BinaryProvider.readBinaryInspections(
    pathLabel: String? = null,
    runtimeTargets: RuntimeTargets = RuntimeTargets.EMPTY,
): InspectBinaryResult {
    val context = InspectionContext(
        runtimeCompatibility = WasmRuntimeCompatibility(WasmFeatureCatalog.bundled, runtimeTargets)
            .takeIf { runtimeTargets.targets.isNotEmpty() },
    )
    val problems = inspectBinary(this, context)
    val counts = problems.counts()
    return InspectBinaryResult(
        module = toModuleIdentity(pathLabel),
        counts = BinaryProblemCountsResult(errors = counts.errors, warnings = counts.warnings, infos = counts.infos),
        problems = problems.map { it.toResult() },
        inspections = BinaryInspectionRegistry.inspections.map {
            BinaryInspectionResult(id = it.id, name = it.displayName, description = it.description)
        },
        runtimeTargets = runtimeTargets.targets.map { it.toString() },
    )
}

private fun BinaryProblem.toResult() = BinaryProblemResult(
    inspectionId = inspectionId,
    code = code,
    severity = severity.name.lowercase(),
    kind = kind,
    message = message,
    location = location,
    byteOffset = byteRange?.offset,
    byteLength = byteRange?.size,
    funcidx = funcIdx,
)
