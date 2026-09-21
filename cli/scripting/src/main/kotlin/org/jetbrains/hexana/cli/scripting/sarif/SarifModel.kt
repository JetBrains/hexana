package org.jetbrains.hexana.cli.scripting.sarif

import org.jetbrains.hexana.shared.scripting.CheckFinding

/** Baseline classification of a finding relative to the stored baseline. */
enum class BaselineState { NEW, UNCHANGED, ABSENT }

/**
 * A [CheckFinding] enriched for SARIF rendering.
 *
 * @param baselineState null when no baseline comparison ran
 * @param fingerprintOverride set for synthesized ABSENT entries (the stored fingerprint)
 */
data class SarifFinding(
    val finding: CheckFinding,
    val baselineState: BaselineState? = null,
    val fingerprintOverride: String? = null,
)

/** A resolved source-level location (DWARF or source map). */
data class SarifSourceLocation(val uri: String, val startLine: Int)
