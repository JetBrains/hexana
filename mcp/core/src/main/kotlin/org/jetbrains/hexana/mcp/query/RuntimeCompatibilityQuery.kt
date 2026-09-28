package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.model.features.EnabledFeatureSourcesResult
import org.jetbrains.hexana.mcp.model.features.EnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.features.ListEnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.runtime.CheckRuntimeCompatibilityResult
import org.jetbrains.hexana.mcp.model.runtime.DiffRequiredProposalsResult
import org.jetbrains.hexana.mcp.model.runtime.DiffRequiredProposalsSummaryResult
import org.jetbrains.hexana.mcp.model.runtime.ListRequiredProposalsResult
import org.jetbrains.hexana.mcp.model.runtime.ProposalEvidenceResult
import org.jetbrains.hexana.mcp.model.runtime.RequiredProposalResult
import org.jetbrains.hexana.mcp.model.runtime.RequiredProposalsSummaryResult
import org.jetbrains.hexana.mcp.model.runtime.RuntimeProposalStatusResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

private const val STATUS_SUPPORTED = "supported"
private const val STATUS_UNSUPPORTED = "unsupported"
private const val STATUS_UNKNOWN = "unknown"

fun WasmBinaryProvider.readRequiredProposals(): ListRequiredProposalsResult {
    return readEnabledFeatures().toRequiredProposals()
}

fun WasmBinaryProvider.readRuntimeCompatibility(
    runtime: String,
    version: String?
): CheckRuntimeCompatibilityResult {
    return readRequiredProposals().checkRuntimeCompatibility(runtime, version)
}

fun WasmBinaryProvider.diffRequiredProposals(
    baseline: WasmBinaryProvider,
    pathLabel: String? = null,
    baselinePathLabel: String? = null
): DiffRequiredProposalsResult {
    return readRequiredProposals().diffRequiredProposals(
        baseline = baseline.readRequiredProposals(),
        pathLabel = pathLabel,
        baselinePathLabel = baselinePathLabel,
    )
}

internal fun ListEnabledFeaturesResult.toRequiredProposals(): ListRequiredProposalsResult {
    val proposals = proposalRules()
        .filter { rule -> rule.isEnabled(features) }
        .map { rule ->
            RequiredProposalResult(
                proposal = rule.proposal,
                required = true,
                evidence = rule.evidence(sources)
            )
        }

    return ListRequiredProposalsResult(
        proposals = proposals,
        summary = RequiredProposalsSummaryResult(
            requiredCount = proposals.size,
            proposals = proposals.map { it.proposal }
        )
    )
}

internal fun ListRequiredProposalsResult.checkRuntimeCompatibility(
    rawRuntime: String,
    version: String?
): CheckRuntimeCompatibilityResult {
    val runtime = validateRuntimeTarget(rawRuntime)
    val runtimeMatrix = checkNotNull(runtimeSupportMatrix()[runtime])

    val statuses = proposals.map { proposal ->
        val support = runtimeMatrix[proposal.proposal] ?: RuntimeSupport(
            STATUS_UNKNOWN,
            "No conservative support entry is available for this proposal and runtime."
        )
        RuntimeProposalStatusResult(
            proposal = proposal.proposal,
            status = support.status,
            note = support.note,
            evidence = proposal.evidence
        )
    }
    val unsupported = statuses.filter { it.status == STATUS_UNSUPPORTED }.map { it.proposal }
    val unknown = statuses.filter { it.status == STATUS_UNKNOWN }.map { it.proposal }

    return CheckRuntimeCompatibilityResult(
        runtime = runtime,
        version = version?.takeIf { it.isNotBlank() },
        compatible = unsupported.isEmpty() && unknown.isEmpty(),
        requiredProposals = proposals.map { it.proposal },
        unsupportedProposals = unsupported,
        unknownProposals = unknown,
        proposalStatuses = statuses,
        evidence = proposals,
        notes = listOf(
            "Compatibility is conservative: `unknown` means the runtime/version/flag/environment combination was not confirmed by this built-in matrix.",
            "The optional `version` value is reported for context but exact version parsing is intentionally not attempted."
        )
    )
}

internal fun validateRuntimeTarget(rawRuntime: String): String {
    val runtime = rawRuntime.trim().lowercase()
    if (runtime.isBlank()) {
        throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "Runtime must not be blank")
    }
    if (runtime !in runtimeSupportMatrix()) {
        throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "Unsupported runtime `$rawRuntime`. Supported runtimes: ${runtimeSupportMatrix().keys.sorted().joinToString()}"
        )
    }
    return runtime
}

private fun ListRequiredProposalsResult.diffRequiredProposals(
    baseline: ListRequiredProposalsResult,
    pathLabel: String?,
    baselinePathLabel: String?
): DiffRequiredProposalsResult {
    val currentByProposal = proposals.associateBy { it.proposal }
    val baselineByProposal = baseline.proposals.associateBy { it.proposal }
    val currentNames = currentByProposal.keys.sorted()
    val baselineNames = baselineByProposal.keys.sorted()
    val newlyRequired = currentNames.filterNot(baselineByProposal::containsKey)
    val noLongerRequired = baselineNames.filterNot(currentByProposal::containsKey)

    return DiffRequiredProposalsResult(
        path = pathLabel,
        baselinePath = baselinePathLabel,
        currentRequiredProposals = currentNames,
        baselineRequiredProposals = baselineNames,
        newlyRequiredProposals = newlyRequired,
        noLongerRequiredProposals = noLongerRequired,
        newlyRequiredEvidence = newlyRequired.mapNotNull(currentByProposal::get),
        noLongerRequiredEvidence = noLongerRequired.mapNotNull(baselineByProposal::get),
        summary = DiffRequiredProposalsSummaryResult(
            currentRequiredCount = currentNames.size,
            baselineRequiredCount = baselineNames.size,
            newlyRequiredCount = newlyRequired.size,
            noLongerRequiredCount = noLongerRequired.size,
        )
    )
}

private data class ProposalRule(
    val proposal: String,
    val featureFlag: String,
    val isEnabled: (EnabledFeaturesResult) -> Boolean,
    val sectionNames: Set<String> = emptySet(),
    val instructionNames: Set<String> = emptySet(),
    val instructionPrefixes: Set<String> = emptySet(),
    val notes: List<String> = emptyList()
) {
    fun evidence(sources: EnabledFeatureSourcesResult): ProposalEvidenceResult {
        return ProposalEvidenceResult(
            featureFlag = featureFlag,
            sections = sources.sections.filter { it in sectionNames },
            instructions = sources.instructions.filter { instruction ->
                instruction in instructionNames || instructionPrefixes.any(instruction::startsWith)
            },
            customSections = sources.customSections,
            notes = notes
        )
    }
}

private fun proposalRules(): List<ProposalRule> = listOf(
    ProposalRule(
        proposal = "reference-types",
        featureFlag = "referenceTypes",
        isEnabled = { it.referenceTypes },
        sectionNames = setOf("Type", "Import", "Table", "Element", "Global", "Code"),
        instructionNames = setOf(
            "ref.null",
            "ref.is_null",
            "ref.eq",
            "ref.as_non_null",
            "ref.func",
            "call_ref",
            "return_call_ref"
        ),
        instructionPrefixes = setOf("br_on_null", "br_on_non_null"),
        notes = listOf("Required because reference types or reference instructions were detected.")
    ),
    ProposalRule(
        proposal = "bulk-memory",
        featureFlag = "bulkMemory",
        isEnabled = { it.bulkMemory },
        sectionNames = setOf("DataCount", "Element"),
        instructionNames = setOf(
            "memory.init",
            "memory.copy",
            "memory.fill",
            "data.drop",
            "table.init",
            "table.copy",
            "table.fill",
            "elem.drop"
        ),
        notes = listOf("Required because bulk-memory sections or bulk-memory/table instructions were detected.")
    ),
    ProposalRule(
        proposal = "multi-value",
        featureFlag = "multiValue",
        isEnabled = { it.multiValue },
        sectionNames = setOf("Type"),
        notes = listOf("Required because at least one function type has multiple results.")
    ),
    ProposalRule(
        proposal = "sign-extension",
        featureFlag = "signExtension",
        isEnabled = { it.signExtension },
        instructionNames = setOf(
            "i32.extend8_s",
            "i32.extend16_s",
            "i64.extend8_s",
            "i64.extend16_s",
            "i64.extend32_s"
        )
    ),
    ProposalRule(
        proposal = "simd",
        featureFlag = "simd",
        isEnabled = { it.simd },
        sectionNames = setOf("Type", "Code"),
        instructionPrefixes = setOf("v128.", "i8x16.", "i16x8.", "i32x4.", "i64x2.", "f32x4.", "f64x2.")
    ),
    ProposalRule(
        proposal = "threads",
        featureFlag = "threads",
        isEnabled = { it.threads },
        sectionNames = setOf("Code"),
        instructionPrefixes = setOf("memory.atomic.", "atomic.", "i32.atomic.", "i64.atomic.")
    ),
    ProposalRule(
        proposal = "memory64",
        featureFlag = "memory64",
        isEnabled = { it.memory64 },
        sectionNames = setOf("Import", "Memory"),
        notes = listOf("Required because an i64 memory limit was detected.")
    ),
    ProposalRule(
        proposal = "gc",
        featureFlag = "gcTypes",
        isEnabled = { it.gcTypes },
        sectionNames = setOf("Type", "Import", "Global", "Table", "Element", "Code"),
        notes = listOf("Required because GC types, packed storage, non-core heap types, or GC instructions were detected.")
    ),
    ProposalRule(
        proposal = "subtyping",
        featureFlag = "subtyping",
        isEnabled = { it.subtyping },
        sectionNames = setOf("Type"),
        notes = listOf("Required because type subtyping metadata was detected.")
    ),
    ProposalRule(
        proposal = "function-references",
        featureFlag = "functionReferences",
        isEnabled = { it.functionReferences },
        instructionNames = setOf("ref.func", "call_ref", "return_call_ref")
    ),
    ProposalRule(
        proposal = "extended-const",
        featureFlag = "extendedConst",
        isEnabled = { it.extendedConst },
        sectionNames = setOf("Global", "Table", "Data", "Element"),
        notes = listOf("Required because a non-MVP constant-expression instruction was detected.")
    )
)

private data class RuntimeSupport(val status: String, val note: String)

private fun runtimeSupportMatrix(): Map<String, Map<String, RuntimeSupport>> {
    val matureModernSupport = RuntimeSupport(
        STATUS_SUPPORTED,
        "Supported by the conservative profile for modern versions; verify the exact deployed version."
    )
    val versionGated = RuntimeSupport(
        STATUS_UNKNOWN,
        "Support is version, flag, embedder, or environment dependent; verify the target runtime."
    )
    val notInConservativeProfile = RuntimeSupport(
        STATUS_UNSUPPORTED,
        "Not included in this runtime's conservative support profile."
    )
    val graalUnknown = RuntimeSupport(
        STATUS_UNKNOWN,
        "GraalWasm support depends on the embedded GraalVM version and options; verify before relying on it."
    )

    return mapOf(
        "graalwasm" to mapOf(
            "reference-types" to graalUnknown,
            "bulk-memory" to graalUnknown,
            "multi-value" to graalUnknown,
            "sign-extension" to graalUnknown,
            "simd" to notInConservativeProfile,
            "threads" to notInConservativeProfile,
            "memory64" to notInConservativeProfile,
            "gc" to notInConservativeProfile,
            "subtyping" to notInConservativeProfile,
            "function-references" to notInConservativeProfile,
            "extended-const" to graalUnknown
        ),
        "wasmtime" to mapOf(
            "reference-types" to matureModernSupport,
            "bulk-memory" to matureModernSupport,
            "multi-value" to matureModernSupport,
            "sign-extension" to matureModernSupport,
            "simd" to matureModernSupport,
            "threads" to versionGated,
            "memory64" to versionGated,
            "gc" to versionGated,
            "subtyping" to versionGated,
            "function-references" to versionGated,
            "extended-const" to versionGated
        ),
        "v8" to browserLikeSupport(matureModernSupport, versionGated),
        "node" to browserLikeSupport(matureModernSupport, versionGated),
        "browser" to browserLikeSupport(matureModernSupport, versionGated)
    )
}

private fun browserLikeSupport(
    matureModernSupport: RuntimeSupport,
    versionGated: RuntimeSupport
): Map<String, RuntimeSupport> = mapOf(
    "reference-types" to matureModernSupport,
    "bulk-memory" to matureModernSupport,
    "multi-value" to matureModernSupport,
    "sign-extension" to matureModernSupport,
    "simd" to versionGated,
    "threads" to versionGated,
    "memory64" to versionGated,
    "gc" to versionGated,
    "subtyping" to versionGated,
    "function-references" to versionGated,
    "extended-const" to versionGated
)
