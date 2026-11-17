package org.jetbrains.hexana.providers.sbom.vuln

import org.jetbrains.hexana.providers.sbom.CdxComponent
import org.jetbrains.hexana.providers.sbom.CycloneDxSbom

/**
 * How reachable a vulnerability is, given what the SBOM actually proves about the
 * shipped (DCE-pruned) image.
 *  - [ComponentPresent]: the component is in the image — the honest floor available
 *    for every default `--enable-sbom` binary; finer reachability is unknown.
 *  - [VulnerableClassRetained] / [VulnerableClassEliminated]: only when the binary
 *    carries class-level data AND the advisory names affected classes.
 */
enum class ReachabilityState(val label: String) {
    ComponentPresent("Component present"),
    VulnerableClassRetained("Vulnerable class retained"),
    VulnerableClassEliminated("Vulnerable class eliminated (DCE)"),
}

data class VulnerabilityFinding(
    val record: OsvRecord,
    val severity: CvssSeverity,
    val baseScore: Double?,
    val state: ReachabilityState,
)

data class ComponentVulnerabilities(
    val component: CdxComponent,
    val findings: List<VulnerabilityFinding>,
) {
    val maxSeverity: CvssSeverity
        get() = findings.maxByOrNull { it.severity.rank }?.severity ?: CvssSeverity.NONE
}

data class SbomVulnerabilityReport(
    val vulnerableComponents: List<ComponentVulnerabilities>,
    val scannedComponentCount: Int,
    val classLevelAvailable: Boolean,
) {
    val vulnerableComponentCount: Int get() = vulnerableComponents.size
    val totalFindings: Int get() = vulnerableComponents.sumOf { it.findings.size }
    val maxSeverity: CvssSeverity
        get() = vulnerableComponents.maxByOrNull { it.maxSeverity.rank }?.maxSeverity ?: CvssSeverity.NONE
}

/**
 * Cross-references SBOM components against matched OSV records and attaches a
 * reachability state + CVSS severity to each finding. [affectedClasses] is a
 * pluggable hook that returns the class names an advisory implicates (usually
 * empty — OSV rarely names classes); when it returns names AND the binary carries
 * class-level data, findings refine to retained/eliminated.
 */
object ReachabilityAnalyzer {

    fun analyze(
        sbom: CycloneDxSbom,
        matcher: VulnerabilityMatcher,
        affectedClasses: (OsvRecord) -> List<String> = { emptyList() },
    ): SbomVulnerabilityReport {
        val classLevelAvailable = sbom.components.any { it.children.isNotEmpty() }
        val vulnerable = sbom.components.mapNotNull { component ->
            val records = matcher.match(component)
            if (records.isEmpty()) return@mapNotNull null
            val survivingClasses = component.allClasses().toHashSet()
            val findings = records.map { record ->
                val score = bestScore(record)
                VulnerabilityFinding(
                    record = record,
                    severity = score.severity,
                    baseScore = score.base,
                    state = reachabilityState(survivingClasses, affectedClasses(record)),
                )
            }
            ComponentVulnerabilities(component, findings)
        }
        return SbomVulnerabilityReport(vulnerable, sbom.components.size, classLevelAvailable)
    }

    private fun reachabilityState(survivingClasses: Set<String>, affected: List<String>): ReachabilityState =
        when {
            survivingClasses.isEmpty() || affected.isEmpty() -> ReachabilityState.ComponentPresent
            affected.any { it in survivingClasses } -> ReachabilityState.VulnerableClassRetained
            else -> ReachabilityState.VulnerableClassEliminated
        }

    private fun bestScore(record: OsvRecord): CvssScore {
        val scores = record.severities
            .filter { it.type.startsWith("CVSS", ignoreCase = true) }
            .map { Cvss.parse(it.score) }
        return scores.maxByOrNull { it.base ?: -1.0 } ?: CvssScore(null, CvssSeverity.UNKNOWN, "")
    }
}