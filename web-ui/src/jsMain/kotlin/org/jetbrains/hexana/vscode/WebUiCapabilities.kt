package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import org.jetbrains.hexana.providers.sbom.CycloneDxSbom
import org.jetbrains.hexana.providers.sbom.vuln.SbomVulnerabilityReport

/**
 * Product-specific UI features that are intentionally absent unless an entrypoint supplies them.
 */
class WebUiCapabilities(
    val sbomVulnerabilityCheck: SbomVulnerabilityContribution? = null,
)

class SbomVulnerabilityContribution(
    val render: @Composable (CycloneDxSbom, (SbomVulnerabilityReport?) -> Unit) -> Unit,
)
