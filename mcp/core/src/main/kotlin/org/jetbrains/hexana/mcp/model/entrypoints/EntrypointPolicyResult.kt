package org.jetbrains.hexana.mcp.model.entrypoints

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EntrypointPolicyResult(
    @SerialName("policy_id")
    val policyId: String,
    val module: String,
    @SerialName("direct_host_entrypoints")
    val directHostEntrypoints: List<EntrypointPolicyEntryResult>,
    @SerialName("excluded_transitive_exports")
    val excludedTransitiveExports: List<String>
)

@Serializable
data class EntrypointPolicyEntryResult(
    val export: String,
    val funcidx: Int,
    val signature: EntrypointSignatureResult,
    @SerialName("called_imports")
    val calledImports: List<EntrypointCalledImportResult>
)

@Serializable
data class EntrypointSignatureResult(
    val params: List<String>,
    val results: List<String>
)

@Serializable
data class EntrypointCalledImportResult(
    val module: String,
    val name: String,
    val signature: EntrypointImportSignatureResult
)

@Serializable
data class EntrypointImportSignatureResult(
    val params: List<String>,
    @SerialName("return")
    val result: List<String>
)
