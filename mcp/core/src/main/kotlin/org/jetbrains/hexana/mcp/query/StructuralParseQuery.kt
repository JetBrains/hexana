package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.model.structural.ComponentStructuralSummary
import org.jetbrains.hexana.mcp.model.structural.CoreModuleStructuralSummary
import org.jetbrains.hexana.mcp.model.structural.IdentifiedStructuralParseResult
import org.jetbrains.hexana.mcp.model.structural.ParseProblemReport
import org.jetbrains.hexana.mcp.model.structural.SectionScanFailureReport
import org.jetbrains.hexana.mcp.model.structural.StructuralKind
import org.jetbrains.hexana.mcp.model.structural.StructuralParseFailure
import org.jetbrains.hexana.mcp.model.structural.StructuralParseFailureKind
import org.jetbrains.hexana.mcp.model.structural.StructuralParseResult
import org.jetbrains.hexana.mcp.model.structural.StructuralParseStatus
import org.jetbrains.hexana.mcp.model.structural.StructuralSummary
import org.jetbrains.hexana.mcp.model.structural.StructuralSummaryStage
import org.jetbrains.hexana.mcp.model.structural.UnidentifiedStructuralParseResult
import org.jetbrains.hexana.mcp.model.structural.UnresolvedSectionReport
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.buildComponentTypeIndexPartial
import org.jetbrains.hexana.wasm.ComponentModule
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParseFailure
import org.jetbrains.hexana.wasm.WasmParseFailureKind as ParserFailureKind
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.components.WasmComponentSection
import org.jetbrains.hexana.wasm.sections.ParseProblem
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmSectionExport
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctions
import org.jetbrains.hexana.wasm.sections.WasmSectionGlobal
import org.jetbrains.hexana.wasm.sections.WasmSectionMemory
import org.jetbrains.hexana.wasm.sections.WasmSectionTable
import org.jetbrains.hexana.wasm.sections.WasmSectionTypes
import org.jetbrains.hexana.wasm.sections.WasmSectionUnresolved
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord

/**
 * Identifies a supported Wasm binary and exposes parser/provider structural evidence.
 * This query deliberately performs no semantic validation.
 */
fun CommonByteBuffer.readStructuralParse(): StructuralParseResult {
    return when (val parsed = WasmParser().parse(duplicate().position(0))) {
        is WasmParseSuccess -> parsed.toStructuralResult(duplicate().position(0))
        is WasmParseFailure -> UnidentifiedStructuralParseResult(
            StructuralParseFailure(
                kind = parsed.kind.toStructuralFailureKind(),
                version = parsed.header?.version?.toInt(),
                layer = parsed.header?.layer?.toInt(),
            )
        )
    }
}

private fun WasmParseSuccess.toStructuralResult(bytes: CommonByteBuffer): IdentifiedStructuralParseResult {
    val header = requireNotNull(header) { "WasmParser success must include decoded header metadata" }
    val module = wasmModule
    val (summary, providerParseProblems) = when (module) {
        is WasmModule -> {
            val provider = WasmBinaryProvider(bytes)
            StructuralSummary(
                kind = StructuralKind.CORE_MODULE,
                version = header.version.toInt(),
                layer = header.layer.toInt(),
                sectionCount = module.sections.size,
                customSectionCount = module.customSections.size,
                unresolvedSectionCount = module.unresolvedSections.size,
                coreModule = CoreModuleStructuralSummary(
                    stage = StructuralSummaryStage.COMPLETED,
                    functions = module.sections.filterIsInstance<WasmSectionFunctions>().sumOf { section ->
                        with(section) { bytes.parseFunctionsPartial().first.size }
                    },
                    exports = module.sections.filterIsInstance<WasmSectionExport>().sumOf { section ->
                        with(section) { bytes.scanPartial().first.size }
                    },
                    memories = module.sections.filterIsInstance<WasmSectionMemory>().sumOf { section ->
                        with(section) { bytes.scanPartial().first.size }
                    },
                    globals = module.sections.filterIsInstance<WasmSectionGlobal>().sumOf { section ->
                        with(section) { bytes.scanPartial().first.size }
                    },
                    tables = module.sections.filterIsInstance<WasmSectionTable>().sumOf { section ->
                        with(section) { bytes.scanTablePartial().first.size }
                    },
                    types = module.sections.filterIsInstance<WasmSectionTypes>().sumOf { section ->
                        with(section) { bytes.parseTypesPartial().first }.sumOf { type ->
                            if (type is WasmRecursiveTypeRecord) type.entries.size else 1
                        }
                    },
                ),
                component = notApplicableComponentSummary(),
            ) to provider.parseProblems
        }

        is ComponentModule -> {
            val provider = WasmComponentBinaryProvider(bytes)
            StructuralSummary(
                kind = StructuralKind.COMPONENT,
                version = header.version.toInt(),
                layer = header.layer.toInt(),
                sectionCount = module.sections.size,
                customSectionCount = module.customSections.size,
                unresolvedSectionCount = module.unresolvedSections.size,
                coreModule = notApplicableCoreSummary(),
                component = ComponentStructuralSummary(
                    stage = StructuralSummaryStage.COMPLETED,
                    imports = module.importSection.sumOf { section ->
                        with(section) { bytes.scanPartial().first.size }
                    },
                    exports = module.exportSection.sumOf { section ->
                        with(section) { bytes.scanPartial().first.size }
                    },
                    types = buildComponentTypeIndexPartial(module.sections, bytes).size,
                    nestedComponents = module.sections.filterIsInstance<WasmComponentSection>().count { section ->
                        try {
                            with(section) { bytes.parseNestedComponent() } != null
                        } catch (_: Exception) {
                            false
                        }
                    },
                ),
            ) to provider.parseProblems
        }

        else -> error("WasmParser returned an unsupported identified module type: ${module::class.simpleName}")
    }

    val parseProblems = providerParseProblems.orderedByOffset { it.byteOffset }.map { it.toReport() }
    val scanFailures = sectionScanFailures.orderedByOffset { it.byteOffset }
        .map { SectionScanFailureReport(it.byteOffset, it.message) }
    val unresolvedSections = module.unresolvedSections.orderedByOffset { it.sectionOffset }
        .map { it.toReport() }

    return IdentifiedStructuralParseResult(
        status = if (scanFailures.isEmpty() && parseProblems.isEmpty() && unresolvedSections.isEmpty()) {
            StructuralParseStatus.CLEAN
        } else {
            StructuralParseStatus.PARTIAL
        },
        summary = summary,
        sectionScanFailures = scanFailures,
        parseProblems = parseProblems,
        unresolvedSections = unresolvedSections,
    )
}

private fun notApplicableCoreSummary() = CoreModuleStructuralSummary(
    stage = StructuralSummaryStage.NOT_APPLICABLE,
    functions = null,
    exports = null,
    memories = null,
    globals = null,
    tables = null,
    types = null,
)

private fun notApplicableComponentSummary() = ComponentStructuralSummary(
    stage = StructuralSummaryStage.NOT_APPLICABLE,
    imports = null,
    exports = null,
    types = null,
    nestedComponents = null,
)

private fun ParseProblem.toReport() = ParseProblemReport(
    kind = kind.name,
    sectionId = sectionId,
    sectionName = sectionName,
    functionIndex = funcIdx,
    typeIndex = typeIdx,
    byteOffset = byteOffset,
    byteSize = byteSize,
    message = message,
)

private fun WasmSection.toReport() = UnresolvedSectionReport(
    sectionId = (this as? WasmSectionUnresolved)?.id?.toInt()?.and(0xff),
    sectionName = name,
    byteOffset = sectionOffset,
    byteSize = sectionSize,
)

private fun ParserFailureKind.toStructuralFailureKind() = when (this) {
    ParserFailureKind.MALFORMED_MAGIC -> StructuralParseFailureKind.MALFORMED_MAGIC
    ParserFailureKind.TRUNCATED_HEADER -> StructuralParseFailureKind.TRUNCATED_HEADER
    ParserFailureKind.UNSUPPORTED_VERSION_LAYER -> StructuralParseFailureKind.UNSUPPORTED_VERSION_LAYER
}

private fun <T> List<T>.orderedByOffset(offset: (T) -> Int): List<T> =
    withIndex().sortedWith(compareBy<IndexedValue<T>> { offset(it.value) }.thenBy { it.index }).map { it.value }
