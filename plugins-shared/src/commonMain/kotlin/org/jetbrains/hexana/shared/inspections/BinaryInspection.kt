package org.jetbrains.hexana.shared.inspections

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility

/**
 * A check that looks at a [BinaryProvider] and reports [BinaryProblem]s.
 *
 * Inspections are the single place a problem about a binary is decided. Hosts never
 * compute problems of their own: IntelliJ, the VS Code webview and the `inspect-binary`
 * command all call [inspectBinary], so a new inspection registered in
 * [BinaryInspectionRegistry] appears everywhere at once.
 *
 * Implementations must be pure and synchronous, and must not throw for a malformed
 * binary — that is what they exist to describe. [inspectBinary] still guards against it.
 */
interface BinaryInspection {
    /** Stable id, used as the SARIF rule id. Lower-case, dot-separated, e.g. `wasm.parse`. */
    val id: String

    /** Short title, e.g. "Wasm parse problems". */
    val displayName: String

    /** One sentence on what the inspection looks for; the SARIF rule description. */
    val description: String

    /**
     * Cheap guess at whether [inspect] could report anything, without parsing. Used to
     * advertise the Problems tab before its contents are resolved, so it may say `true`
     * for a binary that turns out to be clean — never `false` for one that is not.
     */
    fun mayReport(provider: BinaryProvider, context: InspectionContext): Boolean

    fun inspect(provider: BinaryProvider, context: InspectionContext): List<BinaryProblem>
}

/**
 * Host-supplied inputs to an inspection run.
 *
 * @param runtimeCompatibility the feature catalog and the runtimes declared in
 *   `.hexana/runtimes.json`; `null` (or no declared targets) skips the runtime inspection
 * @param hostProblems problems only the host knows about, merged into the result as-is:
 *   the IntelliJ plugin passes the findings of the script checks it last ran
 */
class InspectionContext(
    val runtimeCompatibility: WasmRuntimeCompatibility? = null,
    val hostProblems: List<BinaryProblem> = emptyList(),
)

object BinaryInspectionRegistry {
    val inspections: List<BinaryInspection> by lazy {
        listOf(
            WasmParseProblemsInspection,
            NativeParseDiagnosticsInspection,
            WasmRuntimeCompatibilityInspection,
        )
    }

    fun inspection(id: String): BinaryInspection? = inspections.firstOrNull { it.id == id }
}

/** Code of the problem reported in place of an inspection that threw. */
const val INSPECTION_FAILED_CODE = "inspection.failed"

/**
 * Runs every inspection over [provider] and returns their problems merged with
 * [InspectionContext.hostProblems], most severe first, then in file order.
 *
 * An inspection that throws does not take the others down: it is replaced by one warning
 * saying so, because a silently missing inspection reads exactly like a clean binary.
 */
fun inspectBinary(
    provider: BinaryProvider,
    context: InspectionContext = InspectionContext(),
    inspections: List<BinaryInspection> = BinaryInspectionRegistry.inspections,
): List<BinaryProblem> {
    val found = buildList {
        for (inspection in inspections) {
            try {
                addAll(inspection.inspect(provider, context))
            } catch (e: Exception) {
                add(
                    BinaryProblem(
                        inspectionId = inspection.id,
                        code = INSPECTION_FAILED_CODE,
                        severity = ProblemSeverity.Warning,
                        kind = "Inspection failed",
                        message = "${inspection.displayName} could not run: ${e.message ?: e::class.simpleName}",
                    ),
                )
            }
        }
        addAll(context.hostProblems)
    }
    // Stable sort: problems at the same severity and offset keep the order they were
    // reported in, which is the order each inspection walked the file.
    return found.sortedWith(
        compareBy<BinaryProblem> { it.severity }
            .thenBy(nullsLast()) { it.byteRange?.offset },
    )
}

/** Whether a Problems tab is worth advertising for [provider]; see [BinaryInspection.mayReport]. */
fun mayHaveProblems(
    provider: BinaryProvider,
    context: InspectionContext = InspectionContext(),
    inspections: List<BinaryInspection> = BinaryInspectionRegistry.inspections,
): Boolean = context.hostProblems.isNotEmpty() || inspections.any {
    try {
        it.mayReport(provider, context)
    } catch (_: Exception) {
        // inspectBinary would turn the same failure into a problem worth a tab.
        true
    }
}
