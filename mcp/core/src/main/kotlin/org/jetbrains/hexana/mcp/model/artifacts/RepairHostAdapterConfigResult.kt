package org.jetbrains.hexana.mcp.model.artifacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepairHostAdapterConfigResult(
    val module: HostAdapterConfigModuleIdentity,
    val requiredImports: Map<String, HostAdapterSignature>,
    val requiredEntrypoint: HostAdapterEntrypoint? = null,
    val staleImports: Map<String, HostAdapterSignature>,
    val staleEntrypoint: HostAdapterEntrypoint? = null,
    val ignoredConfigFields: List<String>,
    val configMismatches: List<HostAdapterConfigMismatchResult>,
    @SerialName("repair_config")
    val repairConfig: HostAdapterRepairConfig,
    val warnings: List<String> = emptyList()
)

@Serializable
data class HostAdapterConfigModuleIdentity(
    val path: String? = null,
    val configPath: String? = null,
    val staleModule: String? = null,
    val repairModule: String
)

@Serializable
data class HostAdapterSignature(
    val params: List<String>,
    val results: List<String>
)

@Serializable
data class HostAdapterEntrypoint(
    val export: String,
    val params: List<String>,
    val results: List<String>
)

@Serializable
data class HostAdapterConfigMismatchResult(
    val field: String,
    val expectedJson: String,
    val actualJson: String
)

@Serializable
data class HostAdapterRepairConfig(
    val module: String,
    val imports: Map<String, HostAdapterSignature>,
    val entrypoint: HostAdapterEntrypoint? = null
)
