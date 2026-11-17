package org.jetbrains.hexana.mcp.model.artifacts

import kotlinx.serialization.Serializable

@Serializable
data class QueryArtifactsResult(
    val operation: String,
    val detail: String,
    val modules: List<ArtifactModuleResult>,
    val records: List<ArtifactRecordResult>,
    val omitted: ArtifactOmittedResult? = null,
    val warnings: List<String> = emptyList()
)

@Serializable
data class ArtifactModuleResult(
    val source: String,
    val path: String? = null,
    val artifactPath: String? = null,
    val artifactName: String? = null,
    val functions: Int,
    val imports: Int,
    val exports: Int,
    val memories: Int,
    val globals: Int,
    val tables: Int,
    val types: Int,
    val moduleSize: Int
)

@Serializable
data class ArtifactRecordResult(
    val id: String,
    val entityType: String,
    val status: String,
    val source: String,
    val summary: String,
    val name: String? = null,
    val kind: String? = null,
    val index: Int? = null,
    val artifactPath: String? = null,
    val artifactName: String? = null,
    val locationKind: String? = null,
    val module: String? = null,
    val typeidx: Int? = null,
    val locationDisplay: String? = null,
    val relation: String? = null,
    val targetId: String? = null,
    val signature: ArtifactSignatureResult? = null,
    val attributes: List<ArtifactAttributeResult> = emptyList()
)

@Serializable
data class ArtifactSignatureResult(
    val params: List<String>,
    val results: List<String>
)

@Serializable
data class ArtifactAttributeResult(
    val name: String,
    val value: String
)

@Serializable
data class ArtifactOmittedResult(
    val count: Int,
    val reason: String
)
