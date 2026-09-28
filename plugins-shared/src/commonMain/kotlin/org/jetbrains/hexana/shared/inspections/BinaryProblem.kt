package org.jetbrains.hexana.shared.inspections

/**
 * How much a [BinaryProblem] matters. Declared most severe first, so the natural enum
 * order is the order problems are listed in.
 */
enum class ProblemSeverity(val label: String) {
    /** The binary is malformed, or cannot do what the project declared it must. */
    Error("Error"),

    /** Hexana could not fully decode something, or a declared target needs attention. */
    Warning("Warning"),

    /** Worth knowing, not worth acting on: an unknown record type that was skipped intact. */
    Info("Info"),
}

/** A byte region of the inspected file. Offsets are absolute file offsets. */
data class ProblemByteRange(val offset: Long, val size: Long)

/**
 * One problem found in a binary, in the form every host renders: the IntelliJ and VS Code
 * Problems tab, the toolbar count badge, and the `inspect-binary` CLI command.
 *
 * @param inspectionId the [BinaryInspection.id] that reported it, or a script check id
 *   (`user:…`) for a host-supplied finding; SARIF uses it as the rule id
 * @param code a stable, machine-readable classification within the inspection, e.g.
 *   `wasm.unknown_opcode` or `elf.invalid_symbols`; never localized
 * @param kind a short human label for [code], shown in the Kind column
 * @param location where the problem is, in the format's own terms (`func 12`, `Code`,
 *   `Garbage collection`), or `null` for a file-level problem
 * @param byteRange the affected bytes, when the problem has a position in the file
 * @param funcIdx the Wasm function index, when the problem is inside one; hosts navigate
 *   to the function rather than to the bytes
 */
data class BinaryProblem(
    val inspectionId: String,
    val code: String,
    val severity: ProblemSeverity,
    val kind: String,
    val message: String,
    val location: String? = null,
    val byteRange: ProblemByteRange? = null,
    val funcIdx: Int? = null,
)

/** Per-severity totals of a problem list, for the toolbar badge and the CLI summary. */
data class ProblemCounts(val errors: Int, val warnings: Int, val infos: Int) {
    val total: Int get() = errors + warnings + infos

    /**
     * `2 errors, 1 warning` — errors and warnings only, which is what the badge shows.
     * Info-only lists read `3 notes`. Empty string when there is nothing at all.
     */
    val label: String
        get() {
            val parts = buildList {
                if (errors > 0) add(plural(errors, "error"))
                if (warnings > 0) add(plural(warnings, "warning"))
                if (isEmpty() && infos > 0) add(plural(infos, "note"))
            }
            return parts.joinToString(", ")
        }

    /** The most severe level present, or `null` for an empty list. */
    val highestSeverity: ProblemSeverity?
        get() = when {
            errors > 0 -> ProblemSeverity.Error
            warnings > 0 -> ProblemSeverity.Warning
            infos > 0 -> ProblemSeverity.Info
            else -> null
        }

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}

fun List<BinaryProblem>.counts(): ProblemCounts = ProblemCounts(
    errors = count { it.severity == ProblemSeverity.Error },
    warnings = count { it.severity == ProblemSeverity.Warning },
    infos = count { it.severity == ProblemSeverity.Info },
)
