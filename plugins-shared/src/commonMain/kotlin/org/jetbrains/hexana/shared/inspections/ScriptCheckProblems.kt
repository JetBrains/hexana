package org.jetbrains.hexana.shared.inspections

import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ScriptAnchor

/**
 * A finding a user script reported through `report.fail/warn/info`, as a problem.
 *
 * Script checks are not a [BinaryInspection]: they need a script engine only some hosts
 * have, and they run when the user runs the script rather than when the file opens. The
 * host that ran them passes the result in as [InspectionContext.hostProblems].
 *
 * The check id doubles as the inspection id, so SARIF groups each check as its own rule —
 * the same rule ids `run-script --format sarif` emits.
 */
fun CheckFinding.toBinaryProblem(): BinaryProblem {
    val anchor = anchor
    return BinaryProblem(
        inspectionId = checkId,
        code = checkId,
        severity = when (severity) {
            CheckSeverity.ERROR -> ProblemSeverity.Error
            CheckSeverity.WARNING -> ProblemSeverity.Warning
            CheckSeverity.INFO -> ProblemSeverity.Info
        },
        kind = "Script check",
        message = message,
        location = when (anchor) {
            is ScriptAnchor.Function -> "func ${anchor.funcidx}"
            is ScriptAnchor.ByteRange, null -> checkId
        },
        byteRange = (anchor as? ScriptAnchor.ByteRange)?.let { ProblemByteRange(it.offset, it.length) },
        funcIdx = (anchor as? ScriptAnchor.Function)?.funcidx?.toInt(),
    )
}
