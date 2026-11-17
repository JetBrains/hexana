package org.jetbrains.hexana.mcp.model.imports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListImportsResult(
    val imports: List<ImportFunctionResult>
)

@Serializable
data class ImportFunctionResult(
    val funcidx: Int,
    val typeidx: Int? = null,
    val locationKind: String = "import",
    val module: String,
    val name: String,
    val locationDisplay: String,
    val signature: ImportFunctionSignature?
)

@Serializable
data class ImportFunctionSignature(
    val params: List<String>,
    @SerialName("return")
    val ret: List<String>
)
