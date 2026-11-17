package org.jetbrains.hexana.mcp.model.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TypedCustomMetadataResult(
    @SerialName("producers")
    val producers: TypedProducersReport,
    @SerialName("target_features")
    val targetFeatures: TypedTargetFeaturesReport,
)

@Serializable
data class TypedProducersReport(
    @SerialName("status")
    val status: MetadataStatus,
    @SerialName("sections")
    val sections: List<ProducerSectionReport>,
)

@Serializable
data class ProducerSectionReport(
    @SerialName("occurrence")
    val occurrence: Int,
    @SerialName("offset_bytes")
    val offsetBytes: Long,
    @SerialName("status")
    val status: MetadataSectionStatus,
    @SerialName("fields")
    val fields: List<ProducerFieldReport>?,
    @SerialName("diagnostics")
    val diagnostics: List<MetadataDecodeDiagnostic>,
)

@Serializable
data class ProducerFieldReport(
    @SerialName("field_index")
    val fieldIndex: Int,
    @SerialName("field")
    val field: String,
    @SerialName("values")
    val values: List<ProducerValueReport>,
)

@Serializable
data class ProducerValueReport(
    @SerialName("name")
    val name: String,
    @SerialName("version")
    val version: String,
)

@Serializable
data class TypedTargetFeaturesReport(
    @SerialName("status")
    val status: MetadataStatus,
    @SerialName("sections")
    val sections: List<TargetFeaturesSectionReport>,
)

@Serializable
data class TargetFeaturesSectionReport(
    @SerialName("occurrence")
    val occurrence: Int,
    @SerialName("offset_bytes")
    val offsetBytes: Long,
    @SerialName("status")
    val status: MetadataSectionStatus,
    @SerialName("features")
    val features: List<TargetFeatureReport>?,
    @SerialName("diagnostics")
    val diagnostics: List<MetadataDecodeDiagnostic>,
)

@Serializable
data class TargetFeatureReport(
    @SerialName("feature_index")
    val featureIndex: Int,
    @SerialName("name")
    val name: String,
    @SerialName("state")
    val state: TargetFeatureState,
)

@Serializable
data class MetadataDecodeDiagnostic(
    @SerialName("message")
    val message: String,
    @SerialName("byte_offset")
    val byteOffset: Long?,
)

@Serializable
enum class MetadataStatus {
    @SerialName("absent")
    ABSENT,

    @SerialName("decoded")
    DECODED,

    @SerialName("partial")
    PARTIAL,

    @SerialName("malformed")
    MALFORMED,
}

@Serializable
enum class MetadataSectionStatus {
    @SerialName("decoded")
    DECODED,

    @SerialName("malformed")
    MALFORMED,
}

@Serializable
enum class TargetFeatureState {
    @SerialName("enabled")
    ENABLED,

    @SerialName("disabled")
    DISABLED,
}
