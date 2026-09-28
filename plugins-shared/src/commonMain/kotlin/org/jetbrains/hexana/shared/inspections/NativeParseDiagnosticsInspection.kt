package org.jetbrains.hexana.shared.inspections

import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeParserSeverity
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider

/**
 * What the ELF, Mach-O and PE/COFF parsers report about a file: a header they cannot read
 * (an error — nothing past it opens) and a table they cannot read (a warning — the rest of
 * the file still does).
 *
 * Before inspections existed these were computed and then dropped: the tabs read the
 * per-table lazies, which turn a failure into an absent table without saying why.
 *
 * The parsers' unresolved records are deliberately not reported. Their "known" type sets
 * list the records Hexana decodes, not the ones the format defines, so `LC_BUILD_VERSION`,
 * `LC_MAIN` or `.gnu.hash` all come back unresolved: a note on every real binary, each one
 * wrongly calling a conforming record unknown.
 */
object NativeParseDiagnosticsInspection : BinaryInspection {
    override val id = "native.parse"
    override val displayName = "Native parse problems"
    override val description = "Headers and tables an ELF, Mach-O or PE/COFF parser could not read."

    override fun mayReport(provider: BinaryProvider, context: InspectionContext): Boolean =
        // Knowing needs the whole-file parse, so advertise for every format that has one.
        provider is NativeBinaryProvider && provider.hasParseDiagnostics

    override fun inspect(provider: BinaryProvider, context: InspectionContext): List<BinaryProblem> {
        val diagnostics = (provider as? NativeBinaryProvider)?.parseDiagnostics ?: return emptyList()
        return diagnostics.map { it.toBinaryProblem() }
    }
}

/**
 * Whether [NativeBinaryProvider.parseDiagnostics] would be non-null, answered without computing
 * it (the property is a lazy full parse). Kept beside the inspection so the two cannot disagree.
 */
private val NativeBinaryProvider.hasParseDiagnostics: Boolean
    get() = this !is ArchiveBinaryProvider

private fun NativeParserDiagnostic.toBinaryProblem(): BinaryProblem = BinaryProblem(
    inspectionId = NativeParseDiagnosticsInspection.id,
    code = code,
    severity = when (severity) {
        NativeParserSeverity.Error -> ProblemSeverity.Error
        NativeParserSeverity.Warning -> ProblemSeverity.Warning
        NativeParserSeverity.Info -> ProblemSeverity.Info
    },
    kind = when (severity) {
        NativeParserSeverity.Error -> "Malformed header"
        NativeParserSeverity.Warning -> "Unreadable table"
        NativeParserSeverity.Info -> "Parser note"
    },
    message = message,
    byteRange = range?.toProblemRange(),
)

private fun NativeByteRange.toProblemRange() = ProblemByteRange(offset.toLong(), size.toLong())
