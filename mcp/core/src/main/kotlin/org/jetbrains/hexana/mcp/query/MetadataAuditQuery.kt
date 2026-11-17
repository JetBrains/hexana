package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.model.features.ListEnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.metadata.MetadataStatus
import org.jetbrains.hexana.mcp.model.metadata.TypedCustomMetadataResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.ComponentModule
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseFailure
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.sections.WasmSectionCustom
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved

private const val MAX_TYPED_METADATA_PAYLOAD_BYTES = HexanaAnalysisLimits.MAX_OUTPUT_BYTES
private const val PROVIDER_PARSE_PROBLEM_LIMIT = 1_000

sealed interface MetadataAuditQueryResult {
    data class Identified(
        val customSections: List<AuditCustomSection>,
        val omittedCustomSections: Int,
        val typedMetadata: TypedCustomMetadataResult,
        val enabledFeatures: ListEnabledFeaturesResult?,
        val enabledFeaturesIncomplete: Boolean,
        val sectionScanFailures: List<MetadataAuditScanFailure>,
        val omittedSectionScanFailures: Int,
        val parseProblems: List<MetadataAuditParseProblem>,
        val providerParseProblemsSaturated: Boolean,
        val firstProviderParseProblemOffset: Long?,
        val typedMetadataBudget: MetadataAuditTypedBudget?,
        val unresolvedSections: List<MetadataAuditUnresolvedSection>,
        val omittedUnresolvedSections: Int,
    ) : MetadataAuditQueryResult

    data class Unidentified(val reason: String) : MetadataAuditQueryResult
}

data class MetadataAuditScanFailure(val byteOffset: Long, val message: String)
data class MetadataAuditParseProblem(val kind: String, val sectionId: Int, val byteOffset: Long, val message: String)
data class MetadataAuditTypedBudget(val omittedSections: Int, val firstOmittedOffset: Long)
data class MetadataAuditUnresolvedSection(val sectionId: Int?, val byteOffset: Long, val message: String)

/** Composes only already available structural inventory and typed metadata evidence. */
fun readMetadataAudit(
    bytes: ByteArray,
    maxItems: Int = HexanaAnalysisLimits.MAX_CANDIDATE_COUNT,
): MetadataAuditQueryResult {
    require(maxItems > 0) { "maxItems must be positive." }
    val itemLimit = minOf(maxItems, HexanaAnalysisLimits.MAX_CANDIDATE_COUNT)
    val buffer = bytes.toCommonByteBuffer()
    return when (val parsed = WasmParser().parse(buffer.duplicate().position(0))) {
        is WasmParseFailure -> MetadataAuditQueryResult.Unidentified(parsed.kind.name)
        is WasmParseSuccess -> {
            val module = parsed.wasmModule as BaseModule
            val sectionScanFailures = parsed.sectionScanFailures.take(itemLimit).map {
                MetadataAuditScanFailure(it.byteOffset.toLong(), it.message)
            }
            val coreProvider = if (module is WasmModule) WasmBinaryProvider(buffer.duplicate().position(0)) else null
            val componentProvider = if (module is ComponentModule) {
                WasmComponentBinaryProvider(buffer.duplicate().position(0))
            } else null
            val enabledFeatures = try {
                coreProvider?.readEnabledFeatures()
            } catch (_: Exception) {
                null
            }
            val (typedMetadata, typedMetadataBudget) = module.readBoundedTypedMetadata(buffer, itemLimit)
            val providerParseProblems = (coreProvider?.parseProblems ?: componentProvider?.parseProblems).orEmpty()
            val providerOnlyKeys = providerParseProblems.map { it.byteOffset.toLong() to it.message }.toMutableSet()
            parsed.sectionScanFailures.forEach { providerOnlyKeys.remove(it.byteOffset.toLong() to it.message) }
            val unresolvedSections = module.unresolvedSections.take(itemLimit).map { section ->
                val sectionId = (section as? WasmSectionUnresolved)?.id?.toUByte()?.toInt()
                MetadataAuditUnresolvedSection(sectionId, section.sectionOffset.toLong(),
                    "Section ${sectionId ?: section.name} could not be resolved.")
            }
            MetadataAuditQueryResult.Identified(
                customSections = readAuditCustomSections(module, buffer, itemLimit),
                omittedCustomSections = module.customSections.size - minOf(module.customSections.size, itemLimit),
                typedMetadata = typedMetadata,
                enabledFeatures = enabledFeatures,
                enabledFeaturesIncomplete = coreProvider != null && enabledFeatures == null,
                sectionScanFailures = sectionScanFailures,
                omittedSectionScanFailures = parsed.sectionScanFailures.size - sectionScanFailures.size,
                parseProblems = providerParseProblems.mapNotNull { problem ->
                    val byteOffset = problem.byteOffset.toLong()
                    if ((byteOffset to problem.message) !in providerOnlyKeys) null else MetadataAuditParseProblem(
                        problem.kind.name.lowercase(), problem.sectionId, byteOffset, problem.message,
                    )
                },
                providerParseProblemsSaturated = providerParseProblems.size >= PROVIDER_PARSE_PROBLEM_LIMIT,
                firstProviderParseProblemOffset = providerParseProblems.firstOrNull()?.byteOffset?.toLong(),
                typedMetadataBudget = typedMetadataBudget,
                unresolvedSections = unresolvedSections,
                omittedUnresolvedSections = module.unresolvedSections.size - unresolvedSections.size,
            )
        }
    }
}

private fun BaseModule.readBoundedTypedMetadata(
    bytes: CommonByteBuffer,
    inventoryLimit: Int,
): Pair<TypedCustomMetadataResult, MetadataAuditTypedBudget?> {
    // Each nested entry consumes payload bytes, so this cap bounds decoder and JSON allocations.
    val selected = mutableListOf<WasmSectionCustom>()
    val producers = TypedMetadataBudgetTracker()
    val targetFeatures = TypedMetadataBudgetTracker()
    var omittedCount = 0
    var firstOmittedOffset: Long? = null

    customSections.forEachIndexed { index, section ->
        val budget = when (section.rawName) {
            "producers" -> producers
            "target_features" -> targetFeatures
            else -> return@forEachIndexed
        }
        if (index < inventoryLimit && budget.retain(section)) {
            selected += section
        } else {
            budget.omit()
            omittedCount++
            if (firstOmittedOffset == null) firstOmittedOffset = section.sectionOffset.toLong()
        }
    }

    // The decoder only reads custom sections; retain per-type prefixes to preserve occurrence indexes.
    val decoded = readTypedCustomMetadata(BaseModule(emptyList(), selected, emptyList()), bytes)
    val bounded = decoded.copy(
        producers = decoded.producers.copy(
            status = if (producers.omitted) MetadataStatus.PARTIAL else decoded.producers.status,
        ),
        targetFeatures = decoded.targetFeatures.copy(
            status = if (targetFeatures.omitted) MetadataStatus.PARTIAL else decoded.targetFeatures.status,
        ),
    )
    return bounded to firstOmittedOffset?.let { MetadataAuditTypedBudget(omittedCount, it) }
}

private class TypedMetadataBudgetTracker {
    private var bytes = 0
    var omitted = false
        private set

    fun omit() {
        omitted = true
    }

    fun retain(section: WasmSectionCustom): Boolean {
        if (omitted || section.contentSize > MAX_TYPED_METADATA_PAYLOAD_BYTES - bytes) {
            omit()
            return false
        }
        bytes += section.contentSize
        return true
    }
}
