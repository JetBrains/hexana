package org.jetbrains.hexana.mcp.model.inspections

import kotlinx.serialization.Serializable
import org.jetbrains.hexana.mcp.model.binaries.BinaryModuleIdentityResult

/** One problem, serialized from a `plugins-shared` `BinaryProblem`. */
@Serializable
data class BinaryProblemResult(
    val inspectionId: String,
    val code: String,
    /** `error`, `warning`, or `info`. */
    val severity: String,
    val kind: String,
    val message: String,
    val location: String? = null,
    val byteOffset: Long? = null,
    val byteLength: Long? = null,
    val funcidx: Int? = null,
)

@Serializable
data class BinaryProblemCountsResult(
    val errors: Int,
    val warnings: Int,
    val infos: Int,
)

/** An inspection that ran, so a renderer can describe rules that reported nothing too. */
@Serializable
data class BinaryInspectionResult(
    val id: String,
    val name: String,
    val description: String,
)

@Serializable
data class InspectBinaryResult(
    val module: BinaryModuleIdentityResult,
    val counts: BinaryProblemCountsResult,
    val problems: List<BinaryProblemResult> = emptyList(),
    val inspections: List<BinaryInspectionResult> = emptyList(),
    /** Runtime ids the compatibility inspection checked against; empty when none were declared. */
    val runtimeTargets: List<String> = emptyList(),
)
