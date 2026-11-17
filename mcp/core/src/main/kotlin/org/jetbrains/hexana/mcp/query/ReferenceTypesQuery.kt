package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.types.ListReferenceTypesResult
import org.jetbrains.hexana.mcp.model.types.ReferenceTypesResult
import org.jetbrains.hexana.mcp.model.types.ReferenceTypesSummaryResult
import org.jetbrains.hexana.mcp.model.types.ReferenceValueTypeCountResult
import org.jetbrains.hexana.mcp.model.types.ReferenceValueTypeResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

fun WasmBinaryProvider.readReferenceTypes(): ListReferenceTypesResult {
    val references = readValueTypes().counts.valueTypes.reference

    return ListReferenceTypesResult(
        referenceTypes = ReferenceTypesResult(
            functionReferences = references.referencesFor("func"),
            externalReferences = references.referencesFor("extern"),
            gcReferences = references.referencesFor("struct", "array"),
            primitiveReferences = references.referencesFor("i31"),
            anyReferences = references.referencesFor("any", "eq"),
            noneReferences = references.referencesFor("none", "nofunc", "noextern", "noexn")
        ),
        counts = references,
        summary = references.toSummary()
    )
}

private fun List<ReferenceValueTypeCountResult>.referencesFor(
    vararg refKinds: String
): List<ReferenceValueTypeResult> {
    val acceptedKinds = refKinds.toSet()
    return filter { it.refKind in acceptedKinds }
        .map {
            ReferenceValueTypeResult(
                type = it.type,
                refKind = it.refKind,
                nullable = it.nullable
            )
        }
}

private fun List<ReferenceValueTypeCountResult>.toSummary(): ReferenceTypesSummaryResult {
    return ReferenceTypesSummaryResult(
        hasGC = any { it.refKind !in NON_GC_REFERENCE_KINDS },
        hasI31 = any { it.refKind == "i31" },
        hasStruct = any { it.refKind == "struct" },
        hasArray = any { it.refKind == "array" },
        hasRefEq = any { it.refKind == "eq" },
        hasAnyRef = any { it.refKind == "any" },
        hasExternRef = any { it.refKind == "extern" }
    )
}

private val NON_GC_REFERENCE_KINDS = setOf("func", "extern")
