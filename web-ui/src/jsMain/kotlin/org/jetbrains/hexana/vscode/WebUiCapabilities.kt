package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import org.jetbrains.hexana.providers.sbom.CycloneDxSbom
import org.jetbrains.hexana.providers.sbom.vuln.SbomVulnerabilityReport

/**
 * Product-specific UI features that are intentionally absent unless an entrypoint supplies them.
 */
class WebUiCapabilities(
    val sbomVulnerabilityCheck: SbomVulnerabilityContribution? = null,
    /**
     * Whether the host can supply the bundled example scripts offered by the
     * Add Script Tab dialog. False in the browser build, which ships no example
     * files and cannot read from disk — the starter template still works there,
     * since its source is compiled in.
     */
    val bundledScriptExamples: Boolean = false,
)

class SbomVulnerabilityContribution(
    val render: @Composable (CycloneDxSbom, (SbomVulnerabilityReport?) -> Unit) -> Unit,
)
