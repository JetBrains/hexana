package org.jetbrains.hexana.mcp.model.artifacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlanAbiBindingRegenerationResult(
    val module: AbiBindingModuleIdentity,
    val imports: Map<String, AbiBindingSignature>,
    val exports: Map<String, AbiBindingSignature>,
    val staleImports: Map<String, AbiBindingSignature>,
    val staleExports: Map<String, AbiBindingSignature>,
    val bindingMismatches: List<AbiBindingMismatchResult>,
    val replacementBindings: AbiReplacementBindingsResult,
    val warnings: List<String> = emptyList()
)

@Serializable
data class AbiBindingModuleIdentity(
    val path: String? = null,
    val bindingPath: String? = null,
    val staleModule: String? = null,
    val replacementModule: String
)

@Serializable
data class AbiBindingSignature(
    val params: List<String>,
    val results: List<String>
)

@Serializable
data class AbiBindingMismatchResult(
    val symbol: String,
    val expected: AbiBindingSignature?,
    val actual: AbiBindingSignature?
)

@Serializable
data class AbiReplacementBindingsResult(
    @SerialName("MODULE")
    val module: String,
    @SerialName("IMPORTS")
    val imports: Map<String, AbiBindingSignature>,
    @SerialName("EXPORTS")
    val exports: Map<String, AbiBindingSignature>
)
