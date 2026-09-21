package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.model.metadata.MetadataDecodeDiagnostic
import org.jetbrains.hexana.mcp.model.metadata.MetadataSectionStatus
import org.jetbrains.hexana.mcp.model.metadata.MetadataStatus
import org.jetbrains.hexana.mcp.model.metadata.ProducerFieldReport
import org.jetbrains.hexana.mcp.model.metadata.ProducerSectionReport
import org.jetbrains.hexana.mcp.model.metadata.ProducerValueReport
import org.jetbrains.hexana.mcp.model.metadata.TargetFeatureReport
import org.jetbrains.hexana.mcp.model.metadata.TargetFeatureState
import org.jetbrains.hexana.mcp.model.metadata.TargetFeaturesSectionReport
import org.jetbrains.hexana.mcp.model.metadata.TypedProducersReport
import org.jetbrains.hexana.mcp.model.metadata.TypedTargetFeaturesReport
import org.jetbrains.hexana.mcp.model.metadata.TypedCustomMetadataResult
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.metadata.CustomMetadataSectionStatus
import org.jetbrains.hexana.wasm.metadata.CustomMetadataStatus
import org.jetbrains.hexana.wasm.metadata.MetadataDecodeDiagnostic as WasmMetadataDecodeDiagnostic
import org.jetbrains.hexana.wasm.metadata.ProducerField
import org.jetbrains.hexana.wasm.metadata.ProducerMetadataSection
import org.jetbrains.hexana.wasm.metadata.ProducerValue
import org.jetbrains.hexana.wasm.metadata.TargetFeature
import org.jetbrains.hexana.wasm.metadata.TargetFeaturesMetadataSection
import org.jetbrains.hexana.wasm.metadata.decodeTypedCustomMetadata

/** Reads typed custom metadata from either an identified core module or component. */
fun readTypedCustomMetadata(module: BaseModule, bytes: CommonByteBuffer): TypedCustomMetadataResult {
    val metadata = module.decodeTypedCustomMetadata(bytes)
    return TypedCustomMetadataResult(
        producers = TypedProducersReport(
            status = metadata.producers.status.toResultStatus(),
            sections = metadata.producers.sections.map { it.toResult() },
        ),
        targetFeatures = TypedTargetFeaturesReport(
            status = metadata.targetFeatures.status.toResultStatus(),
            sections = metadata.targetFeatures.sections.map { it.toResult() },
        ),
    )
}

private fun ProducerMetadataSection.toResult(): ProducerSectionReport = ProducerSectionReport(
    occurrence = occurrence,
    offsetBytes = offset.toLong(),
    status = status.toResultStatus(),
    fields = fields?.map { it.toResult() },
    diagnostics = diagnostics.map { it.toResult() },
)

private fun ProducerField.toResult(): ProducerFieldReport = ProducerFieldReport(
    fieldIndex = fieldIndex,
    field = field,
    values = values.map { it.toResult() },
)

private fun ProducerValue.toResult(): ProducerValueReport = ProducerValueReport(name, version)

private fun TargetFeaturesMetadataSection.toResult(): TargetFeaturesSectionReport = TargetFeaturesSectionReport(
    occurrence = occurrence,
    offsetBytes = offset.toLong(),
    status = status.toResultStatus(),
    features = features?.map { it.toResult() },
    diagnostics = diagnostics.map { it.toResult() },
)

private fun TargetFeature.toResult(): TargetFeatureReport = TargetFeatureReport(
    featureIndex = featureIndex,
    name = name,
    state = when (state) {
        org.jetbrains.hexana.wasm.metadata.TargetFeatureState.ENABLED -> TargetFeatureState.ENABLED
        org.jetbrains.hexana.wasm.metadata.TargetFeatureState.DISABLED -> TargetFeatureState.DISABLED
    },
)

private fun WasmMetadataDecodeDiagnostic.toResult(): MetadataDecodeDiagnostic =
    MetadataDecodeDiagnostic(message, byteOffset?.toLong())

private fun CustomMetadataStatus.toResultStatus(): MetadataStatus = when (this) {
    CustomMetadataStatus.ABSENT -> MetadataStatus.ABSENT
    CustomMetadataStatus.DECODED -> MetadataStatus.DECODED
    CustomMetadataStatus.PARTIAL -> MetadataStatus.PARTIAL
    CustomMetadataStatus.MALFORMED -> MetadataStatus.MALFORMED
}

private fun CustomMetadataSectionStatus.toResultStatus(): MetadataSectionStatus = when (this) {
    CustomMetadataSectionStatus.DECODED -> MetadataSectionStatus.DECODED
    CustomMetadataSectionStatus.MALFORMED -> MetadataSectionStatus.MALFORMED
}
