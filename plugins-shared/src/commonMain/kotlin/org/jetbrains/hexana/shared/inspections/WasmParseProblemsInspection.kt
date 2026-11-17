package org.jetbrains.hexana.shared.inspections

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.sections.ParseProblem
import org.jetbrains.hexana.wasm.sections.ParseProblemKind

/**
 * Regions of a core module or component that the Wasm parser could not decode: the
 * provider's `parseProblems`, which already folds in section-scan failures.
 *
 * The severity says whether the bytes are wrong or merely unknown to Hexana. A truncated
 * section or a corrupt entry is malformed by the binary format, so it is an error. An
 * unknown opcode, type byte or instance tag is usually a proposal Hexana does not decode
 * yet rather than a broken file — a runtime may well accept it — so it is a warning.
 */
object WasmParseProblemsInspection : BinaryInspection {
    override val id = "wasm.parse"
    override val displayName = "Wasm parse problems"
    override val description = "Regions of a WebAssembly module or component that could not be decoded."

    override fun mayReport(provider: BinaryProvider, context: InspectionContext): Boolean = when (provider) {
        is WasmBinaryProvider -> {
            // parseProblems is lazy, so this must not force it. Any section whose partial
            // parse can fail, or a recorded scan failure, is a possible source.
            val module = provider.wasmModule
            module != null && (
                module.codeSection != null || module.typesSection != null ||
                    module.importSection != null || module.exportSection != null ||
                    module.tableSection != null || module.memorySection != null ||
                    module.functionSection != null || module.exceptionSection != null ||
                    module.globalSection != null || module.elementSection != null ||
                    module.dataSection != null || provider.hasSectionScanFailures
                )
        }
        is WasmComponentBinaryProvider -> provider.canProvideWasmModule()
        else -> false
    }

    override fun inspect(provider: BinaryProvider, context: InspectionContext): List<BinaryProblem> {
        // Both lists are lazy and capped at 1000 by the provider; inspections run off the UI thread.
        val problems = when (provider) {
            is WasmBinaryProvider -> if (provider.canProvideWasmModule()) provider.parseProblems else emptyList()
            is WasmComponentBinaryProvider -> if (provider.canProvideWasmModule()) provider.parseProblems else emptyList()
            else -> emptyList()
        }
        return problems.map { it.toBinaryProblem() }
    }
}

internal fun ParseProblem.toBinaryProblem(): BinaryProblem = BinaryProblem(
    inspectionId = WasmParseProblemsInspection.id,
    code = "wasm.${kind.name.lowercase()}",
    severity = kind.severity(),
    kind = kind.label(),
    message = message,
    location = when {
        funcIdx != null -> "func $funcIdx"
        typeIdx != null -> "type $typeIdx"
        else -> sectionName
    },
    byteRange = ProblemByteRange(byteOffset.toLong(), byteSize.toLong()),
    funcIdx = funcIdx,
)

private fun ParseProblemKind.severity(): ProblemSeverity = when (this) {
    ParseProblemKind.UNKNOWN_OPCODE,
    ParseProblemKind.UNKNOWN_TYPE_BYTE,
    ParseProblemKind.UNKNOWN_CORE_INSTANCE_TAG,
    -> ProblemSeverity.Warning
    ParseProblemKind.LOCALS_PARSE_FAILURE,
    ParseProblemKind.CODE_ENTRY_CORRUPT,
    ParseProblemKind.INIT_EXPR_PARSE_FAILURE,
    ParseProblemKind.CORE_INSTANCE_ENTRY_CORRUPT,
    ParseProblemKind.SECTION_TRUNCATED,
    ParseProblemKind.SECTION_RESOLVE_FAILURE,
    ParseProblemKind.IMPORT_ENTRY_CORRUPT,
    ParseProblemKind.EXPORT_ENTRY_CORRUPT,
    ParseProblemKind.SECTION_ENTRY_CORRUPT,
    -> ProblemSeverity.Error
}

private fun ParseProblemKind.label(): String = when (this) {
    ParseProblemKind.LOCALS_PARSE_FAILURE -> "Locals parse failure"
    ParseProblemKind.UNKNOWN_OPCODE -> "Unknown opcode"
    ParseProblemKind.CODE_ENTRY_CORRUPT -> "Code entry corrupt"
    ParseProblemKind.UNKNOWN_TYPE_BYTE -> "Unknown type byte"
    ParseProblemKind.INIT_EXPR_PARSE_FAILURE -> "Init expr failure"
    ParseProblemKind.UNKNOWN_CORE_INSTANCE_TAG -> "Unknown instance tag"
    ParseProblemKind.CORE_INSTANCE_ENTRY_CORRUPT -> "Instance entry corrupt"
    ParseProblemKind.SECTION_TRUNCATED -> "Section truncated"
    ParseProblemKind.SECTION_RESOLVE_FAILURE -> "Malformed section name"
    ParseProblemKind.IMPORT_ENTRY_CORRUPT -> "Import entry corrupt"
    ParseProblemKind.EXPORT_ENTRY_CORRUPT -> "Export entry corrupt"
    ParseProblemKind.SECTION_ENTRY_CORRUPT -> "Section entry corrupt"
}
