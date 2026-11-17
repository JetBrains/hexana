package org.jetbrains.hexana.mcp.model.structural

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Parser-backed structural evidence. It intentionally makes no validation claim. */
@Serializable
sealed interface StructuralParseResult

/** A supported core module or component whose header and sections were identified. */
@Serializable
@SerialName("identified")
data class IdentifiedStructuralParseResult(
    val status: StructuralParseStatus,
    val summary: StructuralSummary,
    @SerialName("section_scan_failures")
    val sectionScanFailures: List<SectionScanFailureReport>,
    @SerialName("parse_problems")
    val parseProblems: List<ParseProblemReport>,
    @SerialName("unresolved_sections")
    val unresolvedSections: List<UnresolvedSectionReport>,
) : StructuralParseResult

@Serializable
enum class StructuralParseStatus {
    @SerialName("clean")
    CLEAN,

    @SerialName("partial")
    PARTIAL,
}

/** Input whose header did not identify a supported core module or component. */
@Serializable
@SerialName("unidentified")
data class UnidentifiedStructuralParseResult(
    val failure: StructuralParseFailure,
) : StructuralParseResult

@Serializable
data class StructuralParseFailure(
    val kind: StructuralParseFailureKind,
    val version: Int? = null,
    val layer: Int? = null,
)

@Serializable
enum class StructuralParseFailureKind {
    @SerialName("malformed_magic")
    MALFORMED_MAGIC,

    @SerialName("truncated_header")
    TRUNCATED_HEADER,

    @SerialName("unsupported_version_layer")
    UNSUPPORTED_VERSION_LAYER,
}

@Serializable
data class StructuralSummary(
    val kind: StructuralKind,
    val version: Int,
    val layer: Int,
    @SerialName("section_count")
    val sectionCount: Int,
    @SerialName("custom_section_count")
    val customSectionCount: Int,
    @SerialName("unresolved_section_count")
    val unresolvedSectionCount: Int,
    @SerialName("core_module")
    val coreModule: CoreModuleStructuralSummary,
    val component: ComponentStructuralSummary,
)

@Serializable
enum class StructuralKind {
    @SerialName("core_module")
    CORE_MODULE,

    @SerialName("component")
    COMPONENT,
}

@Serializable
enum class StructuralSummaryStage {
    @SerialName("completed")
    COMPLETED,

    @SerialName("not_applicable")
    NOT_APPLICABLE,
}

@Serializable
data class CoreModuleStructuralSummary(
    val stage: StructuralSummaryStage,
    val functions: Int?,
    val exports: Int?,
    val memories: Int?,
    val globals: Int?,
    val tables: Int?,
    val types: Int?,
)

@Serializable
data class ComponentStructuralSummary(
    val stage: StructuralSummaryStage,
    val imports: Int?,
    val exports: Int?,
    val types: Int?,
    @SerialName("nested_components")
    val nestedComponents: Int?,
)

@Serializable
data class SectionScanFailureReport(
    @SerialName("byte_offset")
    val byteOffset: Int,
    val message: String,
)

@Serializable
data class ParseProblemReport(
    val kind: String,
    @SerialName("section_id")
    val sectionId: Int,
    @SerialName("section_name")
    val sectionName: String,
    @SerialName("function_index")
    val functionIndex: Int?,
    @SerialName("type_index")
    val typeIndex: Int?,
    @SerialName("byte_offset")
    val byteOffset: Int,
    @SerialName("byte_size")
    val byteSize: Int,
    val message: String,
)

/** A retained section whose parser-specific decoder is unavailable or failed. */
@Serializable
data class UnresolvedSectionReport(
    @SerialName("section_id")
    val sectionId: Int?,
    @SerialName("section_name")
    val sectionName: String,
    @SerialName("byte_offset")
    val byteOffset: Int,
    @SerialName("byte_size")
    val byteSize: Int,
)
